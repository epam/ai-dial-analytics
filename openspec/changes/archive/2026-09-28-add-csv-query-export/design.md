## Context

`StructuredQueryController.execute` and `SqlQueryController.executeSql` both end in
`StructuredQueryService.execute(...)`, which returns a `QueryResultPage(rows, totalCount)`. Rows are
ordered `LinkedHashMap`s built in `StructuredQueryExecutor.materialize`. That method already knows
the projected column list (`select.getSelect()` field names), because it seeds every column to null
before it copies the record's values. The list is not returned, though, so a page with zero rows says
nothing about its columns. The execute-sql plans carry column descriptors already:
`SqlTranslation.columns()`, `SqlCompositeTranslation.columns()` and `SqlMetadataResult.columns()` are
all `List<ResultColumn>`. The DSL path has no equivalent. See proposal.md for motivation, and
`specs/query-csv-export/spec.md` for the contract.

`DefaultExceptionHandler` methods return a bare `ErrorView` under `@ResponseStatus`, so Spring
content-negotiates the error body against the request's `Accept` header.

## Goals / Non-Goals

**Goals:**
- The CSV endpoints cannot drift from the JSON endpoints. They call the same service methods with
  the same arguments, and the only thing that differs is the writer.
- The header always equals the key set of the JSON rows, in the same order, including when there
  are no rows.

**Non-Goals:**
- Bulk or streaming export beyond the maximum limit (that is a separate change, if ever).
- CSV for saved-query execution, Flight or translation endpoints.
- Content negotiation on the existing `/execute` and `/execute-sql` paths.
- Dialect options (delimiter, BOM, quoting style) as request parameters.

## Decisions

**D1. Separate paths, not `Accept`-based negotiation on the existing endpoints.**
The new endpoints are `/v1/queries/execute-csv` and `/v1/queries/execute-sql-csv`, named like the
existing hyphenated siblings (`execute-sql`, `translate-sql`, `validate-sql`).
*Alternative:* `Accept: text/csv` on `/execute`. Rejected for three reasons: the maintainers asked for
endpoints; a browser link or a naive client can't easily set `Accept`; and it would change how the
existing endpoints negotiate, with an error path that is harder to keep JSON (see D5).

**D2. The header comes from the executed statement, carried on `QueryResultPage`.**
`QueryResultPage` gains `List<String> columns`, filled at both construction sites in
`StructuredQueryExecutor` (the DSL page and the composite scan page) from the same
`select.getSelect()` names `materialize` keys rows by. For a catalog statement the controller uses
`SqlMetadataResult.columns()` names. The header is therefore by construction the JSON row keys.
*Alternative:* derive the header from `SqlTranslation.columns()` or `OutputColumnNaming` in the
controller. Rejected because it is a second derivation of the names, which could disagree with the
row keys (the executor renders the names that are actually used). It also has no answer for the
DSL path without re-resolving the query model.
Every other `QueryResultPage` constructor call, in tests and elsewhere, is updated. The JSON DTO does
not change.

**D3. Cells render through the primary `JsonMapper`, not through `toString()`.**
Each non-null, non-`String` cell is serialized with `jsonMapper.writeValueAsString(value)`:
- JSON `null` → empty field;
- a JSON string → that string's decoded characters;
- anything else (number, boolean, array, object) → the JSON text as written, which is compact.

The value is serialized directly rather than via `valueToTree`. A `JsonNode` tree normalizes
decimals (`12.50` → `12.5`), but the JSON body does not.
This reuses the serializers the JSON body goes through: the normalized ClickHouse array wrapper,
temporal formatting, enum values and BigDecimal rendering. That's how the "same value as JSON"
requirement holds without a parallel type switch.
*Alternative:* a hand-written switch on Java types. Rejected because it would silently diverge the
next time a value type gains a custom serializer (the enum `EnumValue` case already shows this
happens).

**D4. A small in-house RFC 4180 writer, and no library.**
The writer is `CsvResultWriter` in `web/`. It takes the header plus a list of rows, writes UTF-8
with CRLF, quotes a field only when needed (and always quotes the empty string), and doubles `"`
inside a quoted field. It is about 50 lines and unit-testable on its own. The body is at most 1000
DSL rows, or a catalog-bounded metadata result, so it is built in memory and returned as a
`ResponseEntity<byte[]>` with the content type and disposition set explicitly.
*Alternatives:* `jackson-dataformat-csv` or Commons CSV. Rejected: the first can't render nested
values the way D3 needs without the same per-cell conversion, and neither is worth a new dependency
for a writer this small. `StreamingResponseBody` is also unnecessary at this size, and it would turn
a mid-body failure into a truncated 200.

**D5. Error bodies stay JSON when `Accept` excludes JSON.**
With `Accept: text/csv`, returning an `ErrorView` from `DefaultExceptionHandler` fails negotiation
(`HttpMediaTypeNotAcceptableException`). The handler's result is then discarded and the original
exception escapes as a bodiless 500. This was reproduced first: all five `Accept: text/csv` error
tests failed before the fix.
The fix is `QueryCsvWebConfiguration`, a `WebMvcConfigurer` that replaces the content-negotiation
strategy list with the header strategy plus one addition. For exactly the two CSV paths, it appends
`application/json` to the requested types, at lowest precedence, when `Accept` does not already
admit it. Every other path negotiates as before, because the header strategy alone is Spring Boot's
default. The success path is unaffected: its `ResponseEntity` sets `Content-Type` explicitly, which
bypasses negotiation.
*Alternative:* a `OncePerRequestFilter` that rewrites the `Accept` header. Rejected because it
changes the request every later consumer sees, and MockMvc slice tests run with filters disabled, so
the fix would go untested there. The negotiation hook runs in the slice.
*Alternative:* make every `DefaultExceptionHandler` method return `ResponseEntity` with an explicit
JSON content type. Rejected as too broad for this change.
*Alternative:* `produces = "text/csv"` on the mappings. Rejected: a client sending
`Accept: application/json` would get 406 instead of the CSV the spec promises.

**D6. One controller, `QueryCsvController`, holds both endpoints.**
It maps the two full paths through shared constants (`EXECUTE_CSV_PATH`, `EXECUTE_SQL_CSV_PATH`),
and `QueryCsvWebConfiguration` builds its path set from those same constants. It carries the same
OpenAPI tag as the JSON controllers. The SQL branch mirrors
`SqlQueryController`'s `switch` over the plan. To keep the two from drifting, the page-producing
switch is extracted into `SqlStatementRunner`, a package-private component that both SQL controllers
call. For a catalog statement it returns a `QueryResultPage` whose columns come from
`SqlMetadataResult.columns()`. Each controller then only formats the result.

**D7. `totalCount` goes in `X-Total-Count`.** This is the conventional header name for it, and it is
only set when the value is non-null.

## Risks / Trade-offs

- **[CSV injection in spreadsheet apps]** A cell starting with `=`, `+`, `-` or `@` is written as-is,
  so opening the file in Excel may evaluate it. → Accepted deliberately, because data fidelity comes
  first, and it is documented in the spec. A caller who needs escaping can post-process.
- **[Excel and UTF-8 without a BOM]** Double-clicking the file in Excel may mis-decode non-ASCII text.
  → Accepted: pandas, notebooks and most tools read BOM-less UTF-8 correctly, and a BOM breaks naive
  parsers. Excel's "Data → From Text/CSV" import handles UTF-8. Revisit only on request.
- **[Null vs empty string in consumers]** Many readers, pandas included, treat both `` and `""` as
  missing by default. → The file still preserves the distinction for a reader that honours it.
- **[Header duplicates]** Output names are made unique upstream: `OutputColumnNaming` adds `_<n>`
  suffixes, and the SQL front end injects aliases. So the header list cannot repeat a name. If that
  guarantee ever broke, the CSV would not collapse the columns the way the JSON map does. It would
  repeat the header name and the one surviving value, which would show up as a visible defect
  rather than a silent one.
- **[The negotiation hook covers the wrong path set]** → Its path list is built from the controller's
  own path constants, and a test sends `Accept: text/csv` to each CSV endpoint. The JSON endpoints
  negotiate exactly as before.
- **[Replacing the strategy list]** `configurer.strategies(...)` replaces whatever strategies Spring
  Boot would configure. Today that is only the header strategy, because
  `spring.mvc.contentnegotiation.favor-parameter` is unset. → If anyone enables parameter-based
  negotiation later, it must be added to this list too. That is noted in the class Javadoc.

## Migration Plan

This change is additive only: no config, no Flyway migration, no new credential. Rolling back
removes two endpoints and an internal record component.
