## Context

The SQL front end (`service.query.sql`) parses with Calcite Babel and resolves calls against
`SqlQueryOperators.TABLE`, which chains a custom table ahead of `SqlStdOperatorTable`.
`RexToExprTranslator.callExpr` then lowers each resolved call onto a DSL catalog name. So the set of
accepted SQL spellings is the image of three things combined: the parser's keyword handling, the
operator table, and the translator's `switch`. It is **not** the DSL catalog.

`GET /v1/queries/functions` is the only function discovery today. It is a frozen bare array
(`openspec/config.yaml`, API conventions), so it cannot gain an envelope.

## Goals / Non-Goals

**Goals:**
- A SQL caller can learn, from one call, every function-like spelling they may write, how to write
  it, and what it means.
- The SQL catalog cannot drift silently from either the DSL catalog or the SQL front end.

**Non-Goals:**
- Changing what the SQL surface accepts. If an audit spelling (for example `TIMESTAMPADD`) is not
  proven to translate, it is not advertised; it is not added to the surface either.
- Listing the SQL grammar: comparisons, `IN`, `BETWEEN`, `IS NULL`, `AND`/`OR`, joins, set operations.
  Those stay in `docs/sql-reference.md`. The catalog lists spellings that compute a value.
- Fixing the description defects the audit found outside the SQL surface (`length`, `distinct` on
  `min`/`max`, the array carve-out wording, `round`).

## Decisions

### D1. A separate endpoint, not a query parameter or a per-entry field on the DSL catalog

`GET /v1/queries/sql-functions` returns `{"functions": [...]}`. A separate endpoint was chosen over
`GET /v1/queries/functions?dialect=sql` for four reasons, in order of weight.

1. **The two catalogs list different kinds of thing.** A DSL entry is one function of the `fn`
   grammar, and its `name` is that wire name. A SQL entry is a *spelling*, and the mapping between the
   two is many-to-many:
   - `modulo` is spelled both `%` and `mod`;
   - `length` has three spellings;
   - `+` means `plus` or `date_add`, depending on its operands;
   - `coalesce`, `nullif`, `case`, `like` and `ilike` have no DSL function at all.

   A dialect param would give every field a mode-dependent meaning. `name` would stop being the wire
   name, `args` would describe operators, and `dsl_function` would be meaningful in one mode and null
   in the other. That is two resources sharing one schema. It is not one resource with a filter.
2. **House precedent.** The DSL/SQL split is already modelled as sibling endpoints: `execute` /
   `execute-sql`, `translate` / `translate-sql`, `validate` / `validate-sql`. None of them uses
   `?dialect=`, so a caller who knows the API looks for a sibling.
3. **The SQL catalog has a second consumer coming.** `AdasFlightSqlProducer#getStreamSqlInfo` leaves
   the Flight function-support sets (`SQL_STRING_FUNCTIONS`, `SQL_NUMERIC_FUNCTIONS`,
   `SQL_SYSTEM_FUNCTIONS`, `SQL_DATETIME_FUNCTIONS`) unset on purpose, pending a mapping "against the
   closed function catalog". That mapping must be made over SQL spellings, not DSL names.
   `SqlFunctionCatalog` is therefore a first-class model that REST serves now and Flight can project
   from later. It is not a filtered view of the DSL list. Populating the Flight sets is **not** part of
   this change: over-claiming there makes clients fold expressions the front end then rejects, so it
   needs its own measurement.
4. **The envelope convention.** Anything new returns an object; the bare-array `/functions` is frozen.

The param's real advantage, that one known URL leads to both lists, is recovered by cross-linking
instead. The OpenAPI description of `GET /v1/queries/functions` states that it lists DSL `fn` names and
points SQL callers at `/v1/queries/sql-functions`. The five DSL arithmetic descriptions (D6), the
`validate-sql` remedies (D5) and `docs/sql-reference.md` point there too.

- *Rejected: a `sql` sub-object on each DSL entry.* It is DSL-centric. It has nowhere to put the
  SQL-only spellings (`COALESCE`, `NULLIF`, `CASE`, `ILIKE`), which are exactly the ones a SQL caller
  cannot find today. It would also make a SQL client read 63 DSL entries in order to filter out five.

### D2. Derived from the DSL catalog, with explicit overrides

`SqlFunctionCatalog` builds one entry for each DSL descriptor except those in a `DSL_ONLY` set (the
five arithmetic names). Each entry copies the descriptor's `group`, `returns`, `distinct_supported`,
`deterministic` and `args`. Explicit maps override `signature`, `description` and `aliases` where the
SQL spelling differs, and an `EXAMPLES` map gives every entry its statement. A fixed list of SQL-only
entries follows: the operators, `mod`, `coalesce`, `nullif`, `case`, `cast`, `like` and `ilike`.
Entries are sorted case-insensitively by name, which is the order the DSL catalog already uses.

- *Rejected: a hand-written parallel list.* That is exactly the kind of list the audit found drifting.
  Derivation means a new DSL function appears in SQL discovery automatically, and a test fails if it
  lacks an example (see D4).
- *Rejected: deriving from the Calcite operator table reflectively.* The table has no descriptions,
  contains internal markers (`TO_STRING_CAST`, `__LAMBDAPARAM`), and chains the whole standard table,
  whose names mostly validate and then fail at lowering (`SUBSTRING`, `POWER`). Reflection would
  advertise functions that do not work.

### D3. `form` says how an entry is written; `dsl_function` says what it means

`form` takes one of three values:
- `function`: `name(args)`.
- `operator`: infix or prefix, such as `+` or `||`, and also `LIKE`/`ILIKE`.
- `syntax`: a keyword construct that is neither, namely `CASE … END` and `CAST(x AS VARCHAR)`.

`dsl_function` names the DSL catalog function the spelling lowers onto. It is null where the lowering
target is not a catalog function:
- `COALESCE`, `NULLIF` and `CASE` lower onto the DSL `case` expression;
- `LIKE`/`ILIKE` lower onto the filter grammar's `co`/`ico` comparison.

Because of this field, a client can join the two catalogs, and the build can check that every DSL
function has a SQL spelling (D4). The `-` operator lowers to `minus`, or to `date_sub` when it carries
an `INTERVAL`; `+` likewise lowers to `plus` or `date_add`. `dsl_function` names the arithmetic target,
and the description covers the interval form.

An entry with a `dsl_function` copies that function's `group`, `returns`, `distinct_supported`,
`deterministic` and `args`, with one exception: an operator whose operand count is fixed by its syntax
declares its own `args`. `||` is binary, so it lists operands `a` and `b` rather than `concat`'s
variadic `value`; copying `concat`'s args would tell a client that `a || b` takes a repeatable operand
list, which it cannot. The arithmetic operators need no exception, because their DSL functions are
already binary.

Keyword names are served in lowercase (`like`, `case`), like every other name. Matching is
case-insensitive on this surface, and one convention is simpler for a client than two.

### D4. The guards live in one parity test, driven by the catalog itself

`SqlFunctionCatalogParityTest` (package `service.query.sql`, so it can read `SqlQueryOperators.TABLE`)
checks five things:
1. every entry's `example` translates through `SqlQueryFrontEnd` over a test catalog holding
   `dial_usage_log` and an OTel metrics entity with a map column;
2. every alias, substituted into its entry's example, translates too;
3. every DSL catalog name is either the `dsl_function` of some entry or listed in `DSL_ONLY` with an
   operator entry that names it;
4. every caller-facing name the custom operator table registers is an entry name or an alias; internal
   markers are excluded by an explicit set;
5. none of the `DSL_ONLY` names is callable: each is refused by the front end, so advertising their
   absence is true.

Examples are **complete statements**, not expression fragments. A fragment cannot show `WITHIN GROUP`,
`OVER` or a predicate position. Complete statements are also what makes (1) a real test rather than a
parse check. The examples read `dial_usage_log`, which every deployment has. Map functions need a map
column, which `dial_usage_log` lacks, so they read `otel_claude_code_metrics`, the sink table the docs
already use.

### D5. Remedies name the SQL catalog

`SqlIssueClassifier`'s three function rules now say `GET /v1/queries/sql-functions`. The rules run
only on the validate endpoint, so the SQL catalog is the right target for all three. No other text
changes.

### D6. Reword the five DSL arithmetic descriptions; leave their shape unchanged

Each description drops "Also spelled with the `+` operator in SQL" and gains: "Not callable by this
name in SQL — write `a + b`; `GET /v1/queries/sql-functions` lists the SQL spellings." The DSL catalog's
scenario for these entries is amended to pin the statement. No new field is added to the DSL entries,
so its tests and its frozen shape are untouched apart from the text.

### D7. Fix the two array-typed JSON extractions over a nullable text operand (found at runtime)

Executing every example against a real ClickHouse (26.8), not only translating it, showed that
`json_extract_array` and `json_extract_string_ci` over `"dial_usage_log_payload.request_body"` fail in
the engine with `Code 43: Nested type Array(String) cannot be inside Nullable type`. The payload grain
join is a `LEFT` join, so the text column arrives `Nullable(String)`. `JSONExtract(…, 'Array(String)')`
and `JSONExtractKeysAndValues` cannot return their array type wrapped in `Nullable`; the scalar
extractors can, which is why `json_extract_string` over the same column worked. This defect predates
this change, and the change's own examples are what surfaced it. The object-column path already carried
the guard, in `ExprTranslator.subcolumnArray`.

The fix applies that same guard to the text form of the two array-typed extractions:
`assumeNotNull(ifNull(<json>, ''))`, in `ExprTranslator.nonNullJsonText`. The empty document extracts to
the empty array and the empty string, which is exactly what the structured-query spec already promises
for an absent path. So the contract is unchanged, and no spec delta is needed: behaviour now matches the
spec. The guard is a no-op over a non-nullable column.

- *Rejected: an example that avoids the payload column.* It would have hidden a reachable defect behind
  a curated example. The natural example is the one a caller writes, so it is the one to make work.

## Risks / Trade-offs

- **[Risk] An example passes in the offline front end but fails against ClickHouse.** Translation
  proves the spelling is accepted, not that the engine agrees. → Mitigation: this is the same guarantee
  the DSL catalog gives. The runtime verification of this change executes a sample of the examples
  against a local ClickHouse.
- **[Risk] Override maps drift when a DSL description changes.** → Mitigation: an entry with no
  override copies the DSL text, so only the handful of overridden entries (`if`, `contains`, `count`,
  the percentiles, `array_has_any`/`array_has_all`, the window functions) can drift, and each override
  sits next to the example the test runs.
- **[Trade-off] Operators appear alongside functions.** Five arithmetic operators and `||` are
  listed, and comparisons are not. The line is drawn at "spellings that compute a value", which is also
  the set that answers "how do I write DSL function X in SQL".

## Migration Plan

This change is additive: a new read-only route with no data, config or migration. Rolling back removes
the route; no client of the existing endpoint is affected.
