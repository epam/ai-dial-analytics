# Design — standardize the row-write timestamp format on ISO-8601

## Context

See `proposal.md` — Why. The constraints that shape the approach, all verified against
the default branch:

- **`TableDataService.write` is the single choke point.** It is the only caller of
  `CatalogRowWriter.insert` in the codebase, and both row-write endpoints reach it:
  `TableController` (`POST /v1/tables/{name}/rows`) directly, and `RuleDataController`
  (`POST /v1/rules/{id}/rows`) through `RuleWriteService.write`, which ends in
  `tableData.write(target, …)`.
- **It already walks every field of every row** to validate keys against the catalog's physical
  column set, and already loads the column list to build that set. Validation is the same loop and
  the same query, not a new pass.
- **A `timestamp` column's zone is pinned.** `ClickHouseTypeMapper` renders `TIMESTAMP` as
  `DateTime64(3, 'UTC')` — deliberately, so the read path's zone-less→UTC conversion is true by
  construction. Storage is therefore always an instant in UTC.
- **`422 validation_error` already exists.** `TableValidationException` is mapped by
  `DefaultExceptionHandler` to HTTP 422 with code `validation_error`. No new exception type and no new
  handler are needed.
- **The read path emits ISO-8601 with `Z`.** A `DateTime64(3, 'UTC')` surfaces from the ClickHouse
  client as a `java.time` value, passes through `StructuredQueryExecutor.normalize` untouched, and is
  serialized by the configured Jackson mapper.
- **Backward compatibility is explicitly not a constraint.** The endpoint has no production consumers,
  which is what makes a single canonical format available instead of a permissive superset.

## Goals / Non-Goals

**Goals**

- One canonical timestamp format, chosen deliberately, that matches what the read path emits — so the
  round-trip works by construction rather than by coincidence.
- No inferred zones anywhere in the contract: every accepted value states its own instant.
- One implementation at the shared write choke point, so the two row-write endpoints cannot diverge.
- A rejection specific enough to fix the request from the response alone.
- **Cost that does not scale with batch size.** A request may carry up to 10 000 rows
  (`TableLimitsProperties.maxWriteBatchRows`), and the loop being extended already visits every key of
  every row — on an 8-column table, ~80 000 visits. The catalog SHALL therefore be read a fixed 1–2
  times per request regardless of row count, the formatter SHALL be static rather than per value, and
  parsing SHALL touch only `timestamp` cells. A table with no `timestamp` column pays nothing. The
  specific traps this rules out — a per-row catalog lookup, a per-row type map, a per-value
  `DateTimeFormatter`, an unconditional row copy — are what D4 exists to prevent.

**Non-Goals**

- Preserving today's accepted inputs. The space-separated, zone-less, date-only and numeric forms are
  dropped on purpose (see `proposal.md` — the BREAKING items).
- Coercing or validating any other declared type. `decimal`, `date`, `string`, `uuid`, `array`,
  `object`, numerics and booleans are passed through exactly as today; this change does not open a
  general value-validation surface on the write path.
- Changing any ClickHouse setting, including `date_time_input_format`.
- Changing the query/read path. DSL timestamp literals remain epoch-milliseconds
  (`ValueExprToObjectMapper`), a separate, already-shipped convention on a different surface.

## Decisions

### D1 — Validate in `TableDataService.write`, not in the writer or the controllers

The check needs the column's **declared type**, which exists only in the catalog. By the time a row
reaches `CatalogRowWriter` it is a `Map<String, Object>` with no type context, and by the time it
reaches ClickHouse a timestamp is one string among strings.

Placing it in `TableDataService.write` also settles the rule-endpoint question by construction rather
than by discipline: `RuleWriteService` delegates there after its own column-set validation, so
`POST /v1/rules/{id}/rows` inherits the identical format and rejection with no second implementation
to keep in sync.

*Alternatives rejected.* **In `CatalogRowWriter`** — no type information, and it would have to query
the catalog itself. **In each controller / request DTO** — two implementations, no catalog access at
the DTO layer, and the exact divergence this change exists to prevent. **A Jackson deserializer** —
the target type is not known statically; it comes from the catalog at runtime.
**`date_time_input_format=best_effort`** — see `proposal.md`: it trades a wrong rejection for a silent
misparse.

### D2 — One canonical format: ISO-8601 with a mandatory explicit zone

The accepted grammar is implemented as a single static `DateTimeFormatter` built with
`DateTimeFormatterBuilder`: ISO local date, literal `T`, hour and minute, optional seconds, optional
fractional seconds at any precision, then a **required** offset via `appendOffsetId()` (which covers
`Z` and `±HH:MM`). Because the offset is required rather than optional, a zone-less value fails to
parse instead of silently resolving — the requirement is enforced by the grammar, not by a follow-up
check that could be forgotten.

The assembled formatter pins `ResolverStyle.STRICT` and `IsoChronology.INSTANCE` explicitly. This is not
belt-and-braces: `DateTimeFormatterBuilder.append(DateTimeFormatter)` copies only the appended
formatter's printer/parser, so `ISO_LOCAL_DATE`'s own strict resolver is *discarded* and the builder's
default `SMART` applies — which clamps an out-of-range day-of-month instead of rejecting it. Without the
pin, `2026-02-29T10:00:00Z` (2026 is not a leap year) is accepted and stored as `2026-02-28 10:00:00.000`:
a wrong instant, with a `200` and no diagnostic, i.e. exactly the failure this change exists to remove.
The diagnosis-only zone-less formatter is pinned the same way for consistency, and an out-of-range day is
recognised by re-parsing the date with a clamping resolver, so the rejection names the bad date rather
than reporting an unrecognised shape.

Choosing exactly what the read path emits is the whole point: the defect being fixed is that write
rejected read's output, and a single shared format makes that impossible by construction rather than
by keeping two lists in agreement.

Rejecting the space-separated form is the deliberate half of this. It is a `JSONEachRow` parser detail,
not a designed format, and accepting it as an alias would convert the leak into permanent API surface —
withdrawable later only as a real breaking change. The cost is a mandatory DIAL Admin update in the
same release window, which is why the rejection message names the separator explicitly.

Rejecting zone-less input follows from the same reasoning the proposal applies to `best_effort`: a
naive local wall clock read as UTC stores a wrong instant with no error, which is strictly worse than a
`422` the caller fixes in one line. Once the space form is gone there is no longer any accepted form
without a zone, so the rule is uniform: *every value states its instant.*

Sub-millisecond precision is truncated toward the past to the column's `DateTime64(3)` precision.
ClickHouse would truncate anyway; doing it explicitly makes the stored value predictable from the spec
rather than from the store's rounding mode.

*Alternatives rejected.* **`Instant.parse`** — accepts only `Z`, rejecting the explicit-offset form
that `time.RFC3339` and aware `datetime.isoformat()` emit. **`DateTimeFormatter.ISO_OFFSET_DATE_TIME`**
— requires seconds, rejecting `2026-08-11T10:00Z`. **`ISO_DATE_TIME`** — accepts region zone ids
(`[Europe/Kyiv]`) and zone-less values, widening the contract past what the spec promises. **The ISO
basic offset forms (`+0300`, `+03`)** — no standard library emits them, so they add surface for no
caller.

### D3 — Render the storage form explicitly rather than passing the ISO string through

The validated instant is rendered as `yyyy-MM-dd HH:mm:ss.SSS` at UTC before it reaches
`CatalogRowWriter` — the form the store's default parser demonstrably accepts today.

This keeps the change independent of any assumption about ClickHouse's parser: ADAS decides what the
API accepts, and hands the store a form already proven to work, so the two concerns do not have to
move together. It also means the accepted API format could later widen or narrow with no change to
what is written.

*Alternative rejected.* **Forward the caller's ISO-8601 string unchanged** — it would work only while
the store's `date_time_input_format` keeps its current value, making a ClickHouse setting load-bearing
for the API contract again. That is the coupling this change exists to remove.

### D4 — Resolve the timestamp-column set once per request; copy rows only on change

`physicalColumns(definition)` already iterates the result of one
`columns.findByTableNameOrderByOrdinal` call. That single pass now yields both the physical-name set
and the set of `TIMESTAMP`-typed column names. The row loop consults the second set by name, so a
non-timestamp column costs one `Set.contains`, and a table with no timestamp column skips the work
entirely. At the 10 000-row cap the added cost is proportional to *timestamp* cells, not all cells,
and the catalog is read once per request rather than once per row.

Rows are treated as immutable input: a row map is copied only when one of its values actually changes,
mirroring the existing `RuleWriteService.dropAbsentProvenance` idiom. The caller's list and maps are
never mutated in place.

### D5 — Column-set validation runs before timestamp validation

A row naming an unknown or `_`-prefixed system column must keep failing for *that* reason. Ordering the
checks the other way would change the rejection reason for an existing case — `TableWriteFacetIntegrationTest`
posts `_updated_at` with a space-separated value and expects the unknown-column rejection, which would
silently become a timestamp rejection instead. The ordering is therefore part of the spec, not an
implementation accident.

### D6 — An enrichment's hidden grain key is validated using the source's declared type

For an enrichment, the grain key is a physical column deliberately absent from the enrichment's
`column_mapping`, so its declared type is not available from the target. It is, per
`EnrichmentGrainEntity`, the same column name on the source, where it *is* declared — and it may
legally be a `timestamp`.

The type is therefore resolved from the source table's column mapping (one extra repository call, for
enrichment targets only, once per request). Skipping it would leave an ISO-8601 grain key failing with
the opaque `400` this change exists to remove — the same defect, surviving inside one endpoint.

### D7 — Discriminated rejection messages, thrown on the first bad cell

`TableValidationException` is thrown on the first offending cell, before any insert is attempted, so
the batch is all-or-nothing exactly as an unknown-column rejection already is.

The message names the **1-based row index**, the column and the value, then identifies which of six
mistakes was made: missing zone, space separator, date-only, a day that is not on the calendar,
non-string, or unparseable. The index matters because a batch may carry 10 000 rows. The discrimination
is where most of the user-visible value sits — "invalid timestamp" would still leave a caller guessing,
whereas "uses a space separator; ISO-8601 requires `T` and an explicit zone, e.g.
`2026-08-11T10:00:00Z`" is a one-line fix. Five of the cases are exactly the migration paths off the old
accepted set plus the generic fallback, so the breaking change diagnoses itself; the sixth (a
non-existent day) is the one input that would otherwise look well-formed, so reporting it as an
unrecognised shape would be actively misleading.

Echoing the value is safe and consistent with the surrounding handlers: it is the caller's own input
from the request they just sent, so it discloses nothing they do not already hold. It is length-capped
so a pathological value cannot inflate the error body.

*Alternative rejected.* **Per-row skip with a partial-success report** — a different write contract
(`inserted` would stop meaning "all of them"), a spec change well beyond this fix, and inconsistent
with every other row-level rejection on this endpoint.

### D8 — Reuse `TableValidationException`; no new error code

The spec requires `422` with code `validation_error`, which is exactly what `TableValidationException`
renders. A dedicated exception and stable machine code would add plumbing for what is categorically
the same request-validation failure as an unknown column.

## Risks / Trade-offs

- **DIAL Admin writes start failing if it ships later than ADAS** → the accepted trade for a single
  canonical format. Mitigated by the space-separator-specific `422` message, which makes the required
  fix self-evident instead of mysterious, and by naming the coupling explicitly in the proposal so the
  two repositories are released together rather than discovering it in staging.
- **An unknown consumer is relying on a now-rejected form** → the premise of this design is that the
  endpoint has no production consumers. If that turns out to be wrong, the recovery is additive and
  cheap: re-admitting a form is a non-breaking widening, and the discriminated message means any such
  caller reports a precise, searchable error rather than a silent failure.
- **Rejecting numeric epochs removes a working input** → deliberate: a bare number is ambiguous between
  seconds and milliseconds, and the DSL's own epoch-millis convention lives on a different surface, so
  silently adopting either reading on the write path would risk storing data off by a factor of 1000.
  Callers send a string; the `422` says so.
- **Added per-cell cost co the hot write path** → bounded by D4 (one catalog read per request,
  timestamp cells only, static formatter, copy-on-write). A batch with no timestamp column is
  unaffected.
- **The two write surfaces drift apart later** → the validation lives in the shared method rather than
  in either controller, and both delta specs state the invariant, so a divergence would require
  deliberately bypassing `TableDataService.write` — which would also bypass the physical-column check
  and the batch cap.
- **A `string`-typed column remains the escape hatch** → a caller can still dodge validation by
  declaring a timestamp column as `string`, as `EnrichmentWriteBackIntegrationTest` currently does.
  Not addressed here: the fix is to stop needing the workaround, and re-typing that column is a
  catalog change outside this change's scope.

## Migration Plan

No data migration: no schema change, no ClickHouse setting, no new configuration key or credential, no
dependency change. Values already stored are unaffected, and the form written to ClickHouse is
unchanged (D3), so a rollback is a plain revert with no data implication.

The API change is breaking, so the deployment order matters:

1. Ship ADAS with the new validation.
2. Ship DIAL Admin's switch to `…T…Z` in the same release window, dropping the "timestamps are
   space-separated" panel copy.

Rolling ADAS back re-admits the old forms, so a DIAL Admin version already emitting ISO-8601 keeps
working either way — the pair is safe to roll back in either order.

## Open Questions

None. The one genuinely open item — what a bare numeric epoch should mean — is resolved by rejecting
it, so no unstated assumption is baked into the implementation.
