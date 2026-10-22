## Why

Some catalog columns carry sensitive data (caller identity, token claims, raw
prompt/completion bodies). Today every authenticated caller — including
`READ_ONLY_ADMIN` — sees every column through schema discovery and can query it.
We need column-level access control so sensitive columns are reachable only by
`FULL_ADMIN`, while all other columns remain available to read-only roles.

## What Changes

- Add a catalog-only `sensitive` boolean attribute to every column (defaults to
  `false`), stored in the `column_mapping` table. Like `tag`, it is metadata
  only and never part of the ClickHouse DDL.
- Filter sensitive columns out of the resolved query model for callers who are
  not `FULL_ADMIN`. This single chokepoint (`CatalogQueryService`) covers schema
  discovery, structured `execute`, `execute-sql`, and Flight SQL, plus the
  empty-projection "select all" expansion — in one place.
- For a non-admin, a sensitive column is **hidden**, not forbidden: it is absent
  from schema discovery and from the query model, so referencing it anywhere
  (`select`, `filter`, `group_by`, `having`, `sort`) fails as an ordinary
  unknown-field error. No error path reveals that the column exists.
- Expose `sensitive` through the table-management API the same way `tag` is
  exposed: settable at table create (`ColumnRequest`), returned in schema/table
  responses (`ColumnDto`, `QuerySchemaFieldDto`), and mutable via a new
  `PATCH /v1/tables/{name}/schema` operation.
- Seed as sensitive the direct-identity and raw-content columns on the two
  existing source tables: `dial_usage_log.jwt_claims`,
  `dial_usage_log.request_body`, `dial_usage_log.response_body`, and
  `rate_analytics.comment`. `user_hash` deliberately stays visible — it is a
  de-identified per-user surrogate that enables safe per-user analytics (distinct
  users, per-user rollups) — as does `request_tags` (routing metadata).
- When `security.mode=none` (auth disabled, no roles), sensitive columns remain
  visible — the filter fails open only when authentication is entirely off.

## Capabilities

### New Capabilities
<!-- None. This refines column-level behavior across existing capabilities. -->

### Modified Capabilities
- `query-schema-discovery`: base-schema fields gain a `sensitive` attribute;
  discovery responses omit sensitive fields for non-`FULL_ADMIN` callers.
- `authentication`: refine the "read endpoints are accessible to any
  authenticated role" guarantee — endpoint access is unchanged, but response
  content is now role-filtered at column granularity (sensitive columns are
  `FULL_ADMIN`-only).
- `table-management`: a column's `sensitive` flag can be declared at create,
  is returned in table/schema responses, and can be changed via the schema
  PATCH endpoint (catalog-only, no ClickHouse `ALTER`).
- `structured-query`: field resolution for a non-admin caller operates over a
  model with sensitive columns removed, including empty-projection expansion.

## Impact

- **Postgres schema**: new Flyway migration adding `sensitive BOOLEAN NOT NULL
  DEFAULT FALSE` to `column_mapping`, plus seed `UPDATE`s for the sensitive
  columns of `dial_usage_log` and `rate_analytics`.
- **Code**: `ColumnMappingEntity`; `CatalogQueryService` (role-aware model
  build); `SecurityClaimsExtractor` / security-mode signal; DTOs
  `QuerySchemaFieldDto`, `TableDto.ColumnDto`, `CreateTableRequest.ColumnRequest`,
  `PatchSchemaRequest`; table create/patch service + mapper.
- **APIs**: `/v1/queries/**` (discovery + execute), `/v1/tables/**` (create,
  get, PATCH schema), and the Flight SQL surface all reflect the flag/filter.
- **Docs**: `docs/configuration.md` unaffected (no new config property);
  table-management API docs note the new field/operation.
