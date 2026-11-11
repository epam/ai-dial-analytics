## Why

A row policy is written today as a filter tree in the structured query's filter grammar. That is exact but
verbose: `user_hash = caller.user_hash` takes a nested JSON document. The service already translates a SQL
`WHERE` clause into that same grammar for ad-hoc queries, so an operator should be able to write a policy
the way they would write the condition in SQL.

## What Changes

- `PUT /v1/tables/{name}/row-policies/{role}` accepts `{"where": "<SQL condition>"}` as an alternative to
  `{"filter": <filter node>}`. Exactly one of the two is given.
- The condition is translated by the SQL front end that serves `execute-sql`, as the `WHERE` of a
  statement over the table, into a filter tree. The tree is then validated, stored and enforced exactly as
  a policy written as `filter` is. Nothing new reaches ClickHouse: the stored policy is a filter tree.
- The two caller values are written in the condition as `caller.user_hash` and `caller.project`.
- The response of the `PUT` and the listing return the stored `filter`, as today. The SQL text is not
  stored.
- Not changed: what a policy may contain, how it is evaluated, the size limit, the `*` grantee.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-access-control`: the row-policy management API accepts a policy written as a SQL condition.

## Impact

- Code: the row-policy request DTO and controller, `RowPolicyService`, and a small translator from a
  condition text to a filter tree built on `SqlQueryFrontEnd`.
- API: one optional request member, `where`. No response change, no storage change, no migration.
- Docs: `docs/row-level-security.md` gains the SQL form of its examples.
