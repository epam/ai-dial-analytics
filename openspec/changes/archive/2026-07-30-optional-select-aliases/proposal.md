## Why

The structured query DSL forces clients to invent an `as` alias for every computed or aggregate
select entry in aggregate mode — `{"expr": {"type":"fn","name":"count","args":[]}}` is rejected with
`aggregate output column requires an 'as' alias`, even though the client only wants a count. Nothing
about the query needs a name from the caller: every SQL engine derives one. On the SQL surface the
opposite problem shows up — unnamed expressions surface under Calcite's synthetic `EXPR$0` name, which
leaks planner internals into the response keys and the Arrow schema.

Aliases should be a way to *override* the output name, never a requirement, and the name the service
picks when the caller stays silent should be the one a PostgreSQL user already expects (`count`,
`sum`, `date_bin`, the column's own name, `?column?` for a bare literal).

## What Changes

- `as` becomes **optional everywhere** in `select`: the aggregate-mode "requires an 'as' alias"
  validation is removed, so aggregate and computed entries project without a caller-supplied name.
- Output column names are derived **PostgreSQL-style** when `as` is absent (PostgreSQL's
  `FigureColname` rules, restricted to what the DSL can express):
  - plain field reference → the field's name (an enrichment column keeps its qualified flat name,
    dots included — the dot is part of the name here, not a table qualifier);
  - function call (scalar or aggregate, with or without `distinct`) → the lowercase function name;
  - value literal → `?column?`;
  and duplicate output names are made unique by appending `_1`, `_2`, … Explicit aliases always keep
  the name the caller asked for; only derived names yield.
- Derived names are **first-class references**: `group_by`, `sort`, and `having` may name a derived
  output column exactly as they may name an explicit alias today.
- **BREAKING (DSL surface)**: a `group_by` name that matches both an entity column and a select output
  now resolves to the **entity column** (PostgreSQL's rule; `sort` still resolves to the output). The
  only pre-existing documents that changes are those whose `group_by` key is the **explicit** `as` alias
  of a select entry projecting something other than that column — e.g. `date_bin(1, 'hour',
  request_time)` aliased `request_time` and grouped by `request_time`, which used to group by the hour
  bucket. Those are **rejected with `400 bad_request`** naming the ambiguous key instead of being
  silently regrouped by the column; alias the entry to a name that is not a column of the entity to keep
  grouping by its output. An alias that repeats the name of the field it projects is unaffected. On the
  SQL surface the statements that map onto such a document are rejected by the translator for the same
  reason.
- **BREAKING (SQL surface)**: unnamed SQL select expressions are no longer reported as `EXPR$n`.
  `SELECT count(*) FROM t` now returns the column as `count` — over REST SQL
  (`POST /v1/queries/execute-sql`), in the translated DSL (`POST /v1/queries/translate-sql`), and in
  the Flight SQL Arrow schema. Clients that hard-coded `EXPR$0` must read the new name.
- `POST /v1/queries/translate` renders the derived name as an explicit `AS` alias for every output
  column whose name the expression would not already produce, so the emitted SQL declares exactly the
  result column names the execute path will return.
- `POST /v1/queries/translate-sql` emits `as` only where it differs from the derived default, so a
  translated query shows aliases only where they carry information.

## Capabilities

### New Capabilities

(none — this changes the behavior of existing query capabilities)

### Modified Capabilities

- `structured-query`: `as` is optional for every select entry (aggregate mode included); output/response
  keys are derived PostgreSQL-style with deterministic de-duplication; derived names are resolvable from
  `group_by`, `sort`, and `having` (a physical column of the same name keeps precedence in `having` and
  `group_by`, and a `group_by` key that is both a column and the explicit alias of a different
  expression is rejected as ambiguous).
- `sql-query-translation`: unnamed select expressions take PostgreSQL-derived names instead of Calcite's
  `EXPR$n`, in both the produced DSL `select` and the `ResultColumn` descriptors that drive the Arrow
  schema; the produced DSL omits `as` where the default already yields the same name.
- `rest-sql-endpoint`: response row keys for unnamed SQL expressions follow the new naming (no `EXPR$n`).
- `query-translation-endpoint`: DSL→SQL rendering emits the derived alias explicitly; SQL→DSL emits `as`
  only when it is not the default.

## Impact

- `service/query/model/OutputColumn` (doc contract), new shared naming resolver in
  `service/query/model` used by every surface.
- `service/query/translate/StructuredQueryBuilder`: `requireAlias` removed; every projected field is
  aliased with its resolved name; group/sort/having binding sets built from resolved names.
- `service/query/sql/RelToStructuredQueryTranslator`: `EXPR$n` replaced by derived names, shared
  uniquifying, `as` omitted when redundant.
- `service/query/sql/StructuredQueryToSqlRenderer`: renders derived aliases.
- Flight SQL (`web/flight/AdasFlightSqlProducer`) and REST SQL responses inherit the new names via
  `ResultColumn` — no code change expected, but schema/response names change.
- Specs: `structured-query`, `sql-query-translation`, `rest-sql-endpoint`, `query-translation-endpoint`.
- No configuration, migration, or dependency change.
