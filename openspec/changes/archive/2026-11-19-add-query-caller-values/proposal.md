## Why

A caller who holds a role that reads a table with no row policy reads every row, even when another of its roles
carries an own-rows policy such as `user_hash = caller.user_hash`. Such a caller has no way to ask for only its own
rows: the user hash is derived on the server from a salt the client does not hold, and a query cannot name the caller
values that a row policy can. A "my requests" view for an administrator, or one saved query or dashboard that shows
each viewer its own rows, needs a query to filter on who is running it.

## What Changes

- A query may compare a `string` field with a **caller value**, `caller.user_hash` or `caller.project`, in its
  filters. In the structured-query DSL it is the `param` expression of that name, the same node a row policy stores;
  in SQL it is written `caller.user_hash()` or `caller.project()`.
- A caller value in a query is resolved on each execution from the identity of the request being served, on every
  read surface: `/v1/queries/execute`, `execute-sql`, both CSV twins and Flight SQL. A value the caller does not have
  is unknown: a comparison with it is `NULL`, as in a row policy, so it matches no row (a `NOT EXISTS` or `NOT IN`
  subquery around it still follows SQL's rules for `NULL`).
- Placement follows the row-policy rule on the caller value's side: a direct operand of `=` or `!=`, in a filter. The
  other side is a `string` expression over a column (a field, `lower(user_hash)`, a grouped column or output alias in
  `HAVING`). A caller value anywhere else is refused `400 bad_request`; a SQL `IN` list holding one is read as the
  disjunction it abbreviates. Every other `param` stays refused.
- The translation endpoints carry a caller value unresolved in both directions.
- A saved query or a dashboard's inline body may hold a caller value. It is stored as written and resolved for
  whoever runs it; every other `param` stays refused `422 validation_error`.
- A row policy written as a SQL condition accepts `caller.user_hash()` and `caller.project()` as well as the bare
  spelling it accepts today.
- Row policies, the confinement rule, the caller values themselves and how they are derived are unchanged. A bare
  `caller.user_hash` in a query stays an ordinary column reference.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: a query filter accepts a caller value; where it may appear and how it resolves on execution.
- `sql-query-translation`: the SQL spelling `caller.user_hash()` / `caller.project()` and its translation to the
  `param`, including in composite statements.
- `query-translation-endpoint`: DSL → SQL renders a caller value as `caller.user_hash()` instead of refusing it.
- `saved-queries`: a body may hold a caller value; a saved query is still not a template.
- `dashboards`: an inline body may hold a caller value, as a saved query body may.
- `table-access-control`: a query `param` named after a caller value is no longer refused; a policy condition
  accepts the call spelling and stores an `IN` list holding a caller value as a disjunction; `none` mode still applies
  a query's own filter.
- `authentication`: the caller values serve queries as well as row policies; `none` mode has none.
- `query-schema-discovery`: the SQL function catalog lists `caller.user_hash` and `caller.project`.

## Impact

- **API**: no new endpoint, field or error code. `POST /v1/queries/execute`, `execute-csv`, `translate` accept
  `{"type": "param", "name": "caller.user_hash"}` in `filter` and `having`; `execute-sql`, `execute-sql-csv`,
  `validate-sql`, `translate-sql` and Flight SQL accept `caller.user_hash()`; saved-query and dashboard writes accept
  both; `PUT /v1/tables/{name}/access` accepts the call spelling in `rows.where`; `GET /v1/queries/sql-functions`
  gains two entries.
- **Code**: the SQL front end (spelling, operator, placement check), the filter translation and read shaping, the
  validation-only builds, the DSL → SQL renderer, the saved-query body validator and the policy condition reader.
- **Docs**: `docs/sql-reference.md` and `docs/row-level-security.md`.
- **No config, no migration, no catalog change.** Pipeline declarations keep refusing caller values: a pipeline runs
  with no caller.
