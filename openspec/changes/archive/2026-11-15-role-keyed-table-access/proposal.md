## Why

A table's access is configured through two unrelated resources — five privilege-keyed grant lists and a separate
per-role row-policy map — so one role's access is never readable or writable as one thing, two operators editing the
same table silently overwrite each other, and there is no way to keep a column from some readers short of marking it
`sensitive` for all of them.

## What Changes

- **BREAKING** One role-keyed access resource per table replaces both old APIs:
  `GET /v1/tables/{name}/access` (every role's entry plus the table's access `generation`),
  `GET|PUT|DELETE /v1/tables/{name}/access/{role}`. An entry is `{privileges, rows, columns}`. `PUT` replaces the
  role's whole entry; an entry left with nothing in it is removed. All four require `TABLE_GRANT`. `{role}` is one
  percent-encoded path segment; encoded `/` and `%` are accepted, so IdP group paths are addressable.
- **BREAKING** Removed: the privilege-keyed merge `PUT /v1/tables/{name}/access` and its five-list `GET` shape, the
  `permissions` member of its response, and `GET /v1/tables/{name}/row-policies`,
  `PUT|DELETE /v1/tables/{name}/row-policies/{role}`. A row policy is now an entry's `rows` (`filter` or `where`, stored
  and returned as `filter`), validated and enforced exactly as before.
- New **column policies**: an entry's `columns` allowlist (`null` = every column). Each entry is one unit: a caller
  sees a column when a role that governs its access to the table and carries no row policy allows it, or when every
  governing role allows it, so one role's columns are never shown on rows only another role admits; `sensitive` still
  stacks on top. A caller with no governing role on a table is not narrowed. A hidden column is absent from every read surface (the
  list item's `column_count` and the import preview's `prev` included), refused `403 column_not_entitled` on pipeline
  and export paths (a preview's `enrichment_behind` note drops its version instants instead), and follows a column
  rename; a drop that would empty a list is refused
  `409 column_in_column_policy`.
- New **optimistic locking** on a table's whole access configuration: `PUT .../access/{role}` must carry the
  `generation` it read (`422` missing, `409 access_conflict` stale); every successful write bumps it, and so does a
  schema change that rewrites an entry. Other schema edits do not conflict with it, and every writer of a table
  definition locks its row so none overwrites an access change; schema `PATCH` becomes one transaction from that lock
  through the `ALTER`.
- Endpoint-wide: an encoded `/` (`%2F`) or `%` (`%25`) in any path segment is no longer refused by the request
  firewall, and the firewall's refusals become `ErrorView` `400 bad_request`.
- `permissions` on `GET /v1/tables` and `GET /v1/tables/{name}` gains `columns_restricted`.
- `ineffective_grants` gains reason `lifts_column_policies`.
- Revision snapshots carry the role-keyed `roles` member in place of `access` and `row_policies`, same withholding.
- An enrichment's entry takes only `write` and `modify`; anything else is `409 enrichment_follows_source`. Source
  entries name an enrichment's columns as `<enrichment>.<column>`.
- Catalog: Postgres migration `V57` adds `column_policies` and `access_generation` to `table_definition` (and
  `column_policies` to its audit mirror). No config key.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-access-control`: role-keyed access API replaces the grant-management and row-policy APIs; column policies
  and their evaluation; access generation; `columns_restricted`; the requirements that cite the removed endpoints.
- `table-management`: `permissions` gains `columns_restricted`; `columns` and `column_count` follow column
  visibility; a column rename rewrites column policies and an emptying drop is refused.
- `query-schema-discovery`: the entity schema omits a field hidden by a column policy, so "all fields" holds only
  for a caller from whom neither the clearance nor a column policy hides one.
- `audit-trail`: role-keyed `roles` snapshot member; access-entry change scenarios; the enrichment clean-up
  limitation restated against the new shape.
- `catalog-export-import`: export refuses a column hidden from the caller; the import preview's `prev` omits it; an
  imported table has no access entries.
- `authentication`: encoded `/` and `%` accepted on every path, firewall refusals as `ErrorView` `400 bad_request`;
  the bundle and implication wording moves from grant lists to role-keyed entries.
- `enrichment-runtime`, `enrichment-registry`, `class1-enrichment`: a policy-hidden column is refused
  `403 column_not_entitled`, and a parse failure is re-attributed to it before the clearance; group assembly judges
  the version and identity columns it returns.
- `aggregate-pipeline`: an aggregate SQL statement's text-mention rule also refuses a mentioned policy-hidden column,
  checked first; other planning failures stay `422`.

## Impact

- Code: `web/controller/table/TableAccessController`, `TableRowPolicyController` (removed), `web/dto/table/access/*`,
  `web/dto/table/rowpolicy/*`, `service/table/TableAccessService`, `service/table/rowpolicy/*`,
  `service/table/grant/*`, `TableDefinitionEntity`/`TableDefinitionRepository` (locking finder, generation bump),
  `TableSchemaService` (rename, drop, schema-mutation lookup), `CatalogSchemaWriter` (re-define, status),
  `TableCatalogService` (description, delete, `column_count`), `EnrichmentRelayoutService` (row lock),
  `service/security/SensitiveColumnAccess`, `service/query/CatalogQueryService`,
  `service/enrichment/support/SensitiveProjectionGuard` (`enforce`, `enforceEntity`, `reattribute`),
  `EnrichSpecValidator`, `AggregateGovernance`, `AggregateSqlValidator` (text-mention rule), `CatalogExportService`, `ImportTargetReader`, `TableMapper` (live and
  revision), `DefaultExceptionHandler` (three new codes); `web/security` (an `HttpFirewall` allowing encoded `/` and
  `%`, a `RequestRejectedHandler` writing `ErrorView`) and a Tomcat customizer passing `%2F` through.
- Project conventions: the `context:` of `openspec/config.yaml` (column visibility at the chokepoint, the new `403`
  code, `none` mode).
- API: breaking for every client of the old grant and row-policy endpoints; `docs/release-0.24-upgrade.md` maps old
  calls to new ones.
- Catalog schema: `V57` (two columns, one audit-mirror column). Rolling back to a build without column policies
  makes every hidden column visible again.
- Docs: `row-level-security.md`, `catalog-promotion.md`, `claude-code-telemetry.md`, `configuration.md` (prose links
  only, no new property), `recommended-catalog/README.md`, `release-0.24-upgrade.md`.
