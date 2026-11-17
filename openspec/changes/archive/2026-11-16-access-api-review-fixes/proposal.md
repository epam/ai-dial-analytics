## Why

The role-keyed access API addresses one role as a percent-encoded path segment, which made the service relax its
request firewall on every endpoint and still left some roles (`;`, `\`, `//`, `.`) unaddressable. Its one table-wide
optimistic-locking generation makes every write conflict with every other, so two operators editing different roles
collide, and a retried `PUT` whose response was lost fails against itself. Three gaps sit next to it: a revision
snapshot shows a column that a column policy added later hides, a write that waits on another change to the same
table waits without bound, and the way two roles' column policies combine is not explained where operators read.

## What Changes

- **BREAKING (unreleased API):** one role's entry is addressed as `GET`/`PUT`/`DELETE /v1/tables/{name}/access?role=<role>`,
  an ordinary URL-encoded query value; `/v1/tables/{name}/access/{role}` is no longer served. Any role is addressable.
  A missing or blank `role` on `PUT`, `DELETE` or a one-role `GET` is `400 bad_request`, before the privilege check.
- The endpoint-wide acceptance of `%2F` and `%25` in path segments is withdrawn: such a path is refused `400` again,
  and the health endpoint's special case for it goes. Firewall refusals keep their `ErrorView` `400 bad_request`
  shape.
- The table keeps one `generation`, and the service also records the generation at which each role's entry last
  changed, a removal included. A `PUT` conflicts (`409 access_conflict`) only when its own role changed after the
  generation it carries, or the generation is above the table's. A `PUT` that would store the role's current entry
  answers `200`, writes nothing and does not move the generation, whatever generation it carries.
- `DELETE` accepts an optional `generation` query parameter that makes it conditional in the same way.
- A schema change that rewrites column policies marks only the roles whose lists it rewrote.
- A revision snapshot hides a column when either that revision's or the table's current column policies hide it from
  the caller.
- A write to a table definition waits at most `tables.lock-timeout` (`TABLES_LOCK_TIMEOUT`, default `10s`) for another
  change to the same table, and is otherwise refused `409 table_busy`.
- The row-level-security guide and the upgrade guide explain how a column-restricted role narrows a caller that
  also holds a row-confined role.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-access-control`: the role is a query parameter; per-role conflict detection with an idempotent unchanged
  `PUT` and an optionally conditional `DELETE`; revision snapshots also apply the current column policies; a
  scenario for gaining a column-restricted role.
- `authentication`: encoded `/` and `%` in a path segment are refused again; the access path in the bundle
  requirement.
- `audit-trail`: snapshots hide columns by the revision's and the current column policies; access paths; an
  unchanged `PUT` moves no generation.
- `catalog-export-import`: the access path in the import requirement.
- `table-management`: column-policy rewrites mark the roles they change; bounded wait on concurrent definition writes
  with `409 table_busy`.

## Impact

- Code: `TableAccessController`, `TableAccessService`, `ColumnPolicyRewriter`, a new `RoleGenerationCodec`,
  `TableDefinitionEntity`, `TableDefinitionRepository` (locking moved to a fragment with a bounded wait),
  `SensitiveColumnAccess`, `TableMapper`, `RequestFirewallConfiguration`, `DefaultExceptionHandler`.
- Data: the unreleased `V57__role_keyed_table_access.sql` gains `table_definition.access_role_generations`.
- Configuration: one new property, `tables.lock-timeout`.
- Docs: `release-0.24-upgrade.md`, `row-level-security.md`, `configuration.md`, and the other docs that show the
  access path.
