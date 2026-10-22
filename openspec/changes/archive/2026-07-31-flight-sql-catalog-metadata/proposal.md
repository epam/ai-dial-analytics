# Flight SQL catalog metadata RPCs

## Why

The Flight SQL endpoint can execute statements but cannot describe itself, and the gap is worse than
an empty navigator. Measured on 2026-07-31 against a local ADAS (`FLIGHT_ENABLED=true`,
`security.mode=none`) with Dremio's Arrow Flight SQL ODBC driver 0.9.7 and Power BI Desktop 2.155:

- Statement execution works end to end. Power BI Desktop imports through the generic ODBC connector
  and the values match `POST /v1/queries/execute-sql` exactly, timestamps included.
- Every catalog RPC is half-implemented. A raw pyarrow probe showed `CommandGetSqlInfo` returning 13
  rows, while `CommandGetCatalogs`, `CommandGetDbSchemas`, `CommandGetTables`, `CommandGetTableTypes`
  and `CommandGetXdbcTypeInfo` **succeed** at `GetFlightInfo` and then fail `DoGet` with
  `UNIMPLEMENTED`. `BasicFlightSqlProducer` supplies the `getFlightInfo*` half; ADAS overrides
  `determineEndpoints` to hand out an endpoint for *every* command type, so an unimplemented command
  advertises itself as supported and only fails once the client commits to fetching.
- The cost is a client crash, not a missing feature. Connecting without an explicit SQL statement
  makes the driver call `SQLTables`, receive `UNIMPLEMENTED` where it was promised a stream, and
  hard-crash the Power BI mashup container (`0xC0000409`).

## What Changes

- Implement the `getStream*` half of the catalog RPCs in `AdasFlightSqlProducer`: `GetCatalogs`,
  `GetDbSchemas`, `GetTables` (including `include_schema`, which serialises each table's Arrow
  schema), `GetTableTypes`, and `GetXdbcTypeInfo`.
- Source every row from `CatalogQueryService` — the same entity catalog that backs
  `GET /v1/queries/entities` and `GET /v1/queries/entities/schema/{name}` — so the RPC catalog and the
  REST catalog cannot drift. `include_schema` renders through the existing `ArrowTypeMapper`, and
  `GetXdbcTypeInfo` enumerates exactly the `ResultColumnKind` families that mapper can produce.
- Apply the query path's role-based sensitive-column filtering to the catalog: every catalog read runs
  under the Flight caller's identity, so a non-`FULL_ADMIN` caller never sees that `jwt_claims` or
  `request_tags` exist. Listing a column the query path deliberately hides is an information leak
  regardless of whether the column can then be selected.
- **BREAKING** (`flight-sql-endpoint`): a Flight SQL statement with no `LIMIT` is rejected at
  `GetFlightInfo` with `INVALID_ARGUMENT` instead of silently receiving the default limit of 100 rows.
  Working discovery means clients generate `SELECT * FROM dial_usage_log` themselves; combined with the
  1000-row ceiling, a silent default would hand Power BI a table that looks complete and holds 100
  rows. See design decision D6 for the full reasoning and the alternatives rejected.
- **BREAKING** (`flight-sql-endpoint`): `GetFlightInfo` for a still-unimplemented command
  (`GetPrimaryKeys`, `GetExportedKeys`, `GetImportedKeys`, `GetCrossReference`) now fails
  `UNIMPLEMENTED` instead of returning an endpoint whose `DoGet` fails. Unsupported means unsupported
  at the first call, which is the only shape a client can react to safely.
- No new configuration, no new dependency, no HTTP surface change.

## Capabilities

### New Capabilities

None. This completes an existing capability rather than adding one.

### Modified Capabilities

- `flight-sql-endpoint`: catalog/metadata RPCs become supported and specified (the current spec's
  blanket "metadata RPCs are deliberately unimplemented" claim becomes wrong for the metadata half);
  unimplemented commands must fail at `GetFlightInfo`; a statement with no `LIMIT` is rejected rather
  than defaulted; catalog rows are subject to the same sensitive-column filtering as query results.

## Impact

- `src/main/java/com/epam/aidial/analytics/web/flight/AdasFlightSqlProducer.java` — five new
  `getStream*` overrides, a whitelisting `determineEndpoints`, and the no-`LIMIT` rejection.
- New `web/flight/FlightCatalogStreams.java` (or equivalent) holding the row-building for the five
  metadata schemas, so the producer stays a thin transport adapter and the row builders are directly
  testable.
- `openspec/specs/flight-sql-endpoint/spec.md` — updated in this change.
- `docs/` — the Flight SQL client notes gain the explicit-`LIMIT` requirement and the
  no-catalog/no-schema namespace fact.
- Tests: extend `integration/FlightSqlIntegrationTest` for the metadata happy paths and the
  `LIMIT` change (its `limitGovernanceMatchesRest` assertion on a no-`LIMIT` query changes), plus a
  new `oidc`-mode Flight integration test proving a `READ_ONLY_ADMIN` caller's catalog omits sensitive
  columns.
- Read-only and Postgres-only: the catalog RPCs touch the table catalog, never ClickHouse.

## Follow-ups (explicitly out of scope)

- **`INFORMATION_SCHEMA` as queryable SQL.** Power BI's *Dremio* connector issues catalog queries via
  `ExecuteQuery` rather than the RPCs and needs it; the generic ODBC path does not. A future pg-wire or
  `INFORMATION_SCHEMA` surface must render from the same internal model these RPCs use.
- **Prepared statements.** GizmoSQL's ODBC driver 1.1.2 is blocked solely by `createPreparedStatement`
  returning `UNIMPLEMENTED` — the first concrete client the statement-only v1 decision costs, and
  evidence worth weighing against it.
- The table-less `SELECT 1` health probe, and Calcite's misleading message for an unknown column
  (`The definition of column 'X' depends on itself through the following columns: 'X'`).
