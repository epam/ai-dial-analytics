## REMOVED Requirements

### Requirement: Metadata is persisted in PostgreSQL

**Reason**: The `metadata` store was throwaway scaffolding that proved the PostgreSQL + Flyway path.
It is superseded by the `table-management` catalog (`table_definition`/`column_mapping`/
`enrichment_join`), which persists real domain metadata in the same PostgreSQL database.
**Migration**: The `metadata` table and its `V1__create_metadata.sql` are removed; the
table-management catalog migration becomes the Postgres `V1`. No data migration is needed — the
store held no real data.

### Requirement: Create a metadata record

**Reason**: Superseded by `POST /v1/tables` in the `table-management` capability.
**Migration**: Use `POST /v1/tables` with `{name, description, type}`.

### Requirement: List metadata records

**Reason**: Superseded by `GET /v1/tables`.
**Migration**: Use `GET /v1/tables`.

### Requirement: Get a metadata record by name

**Reason**: Superseded by `GET /v1/tables/{name}`.
**Migration**: Use `GET /v1/tables/{name}`.

### Requirement: Rename a metadata record

**Reason**: Superseded by `table-management`, where `name` is the immutable identifier (rename is
not supported; delete + recreate instead).
**Migration**: Delete the table and recreate it under the new name.

### Requirement: Delete a metadata record

**Reason**: Superseded by `DELETE /v1/tables/{name}`.
**Migration**: Use `DELETE /v1/tables/{name}`.

### Requirement: PostgreSQL availability is mapped to the error contract

**Reason**: The capability is removed; the underlying behavior (PostgreSQL data-access failures →
HTTP 503 `postgres_unavailable` via the shared `ErrorView`) is retained by the global exception
handler and now serves the `table-management` endpoints.
**Migration**: None — the same 503 `ErrorView` behavior applies to `/v1/tables`.

### Requirement: Metadata is not queryable through the structured query DSL

**Reason**: The `metadata` entity no longer exists. The isolation guarantee is replaced by the
`table-management`/`query-schema-discovery` rule that only `MATERIALIZED` `source` tables are
queryable (enrichment and aggregation tables are not).
**Migration**: None — non-source catalog tables are not exposed as queryable entities.
