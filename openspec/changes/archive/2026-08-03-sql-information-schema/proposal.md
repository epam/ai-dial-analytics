## Why

Power BI Desktop's built-in **Dremio Software** connector is the only shipped path to **DirectQuery**
we have found, and DirectQuery suits ADAS far better than Import: each visual sends an aggregate,
results are small, nothing is copied, and the Flight row cap stops mattering. Power BI's generic ODBC
connector cannot do DirectQuery at all.

The connector already reaches ADAS over Flight SQL (`adbc://host:32010`, Encryption=Disabled). It dies
at the first discovery call. Captured verbatim from Power BI's own Mashup trace
(`%LOCALAPPDATA%\Microsoft\Power BI Desktop\Traces\Performance\Microsoft.Mashup.Container.*.log`):

```
Engine/IO/Adbc/ExecuteQuery  ResourceKind:"Dremio"  ResourcePath:"adbc://127.0.0.1:32010;Disabled"
IsMetadata:"True"
CommandText:
/* PBI ADBC metadata: GetSchemas */
                SELECT DISTINCT TABLE_SCHEMA
                FROM INFORMATION_SCHEMA."TABLES"
                WHERE TABLE_TYPE <> 'SYSTEM_TABLE'
                ORDER BY TABLE_SCHEMA
AdbcStatus:"InvalidArgument"
[FlightSQL] SQL validation error: Object 'INFORMATION_SCHEMA' not found
```

The connector discovers through **catalog SQL**, not the Flight SQL metadata RPCs — it never calls
`GetTables`/`GetDbSchemas`, so the RPC work that shipped with `flight-sql-catalog-metadata` does not
help it. This is recorded in full in `local_env/powerbi/dremio-connector-findings.md`, including the
connector's extracted M source (`Dremio.pqx`, connector version 3.14.0), which is where the statement
texts below come from.

Four separate things block it, each measured against a running ADAS:

1. **No `INFORMATION_SCHEMA`.** The relations do not exist.
2. **No schema namespace.** `SELECT … FROM adas.dial_usage_log` fails `Object 'adas' not found`. This
   matters twice over: the metadata statements are themselves schema-qualified, and whatever value is
   returned in `TABLE_SCHEMA` comes straight back as the qualifier on every *data* query, because the
   connector builds its table reference as `[Kind = "FromTable", Table = [Schema=…, Name=…]]`.
3. **A trailing `;` is a parse error.** The connector's third statement ends with one.
4. **`DATA_TYPE` must use Dremio's spelling.** The connector looks each value up in a 12-entry table
   and hard-errors `TYPE NOT FOUND: <type>` on a miss — `CHARACTER VARYING`, never `VARCHAR`.

## What Changes

- **A queryable `INFORMATION_SCHEMA`** with three relations — `TABLES`, `COLUMNS`, `SCHEMATA` — served
  from `CatalogQueryService`, the same catalog behind `GET /v1/queries/entities`. Column sets and value
  semantics follow Dremio's, verified against a live Dremio OSS 26.0.5.
- **A schema namespace, `adas`**, holding every queryable entity. Bare names stay canonical and keep
  working; the qualified form is accepted so catalog-SQL-driven clients can use what they read from
  `TABLE_SCHEMA`.
- **Catalog metadata queries are answered in-process**, by Calcite's interpreter over in-memory tables,
  never translated to the DSL and never sent to ClickHouse.
- **A single trailing statement separator is accepted** and stripped before parsing.
- Metadata statements are available wherever ADAS accepts SQL: `POST /v1/queries/execute-sql` and the
  Flight SQL statement path. `POST /v1/queries/translate-sql` rejects them — a catalog query has no
  `StructuredQuery` equivalent — with a message that says so.

**Deliberately not in this change** (see design.md):

- The Flight SQL **catalog RPCs and `GetSqlInfo` keep declaring no schema namespace.** The Dremio
  connector reads neither; changing them would make every RPC-driven ODBC client start emitting
  `"adas"."x"`, which is a behaviour change to the one integration that works end to end today, for no
  gain here. Bare names remain the form ADAS steers clients toward.
- **Authentication.** The connector offers no Anonymous credential and its Username/Password becomes a
  Flight `Handshake`, which ADAS answers with an empty header name and empty token — so only blank
  credentials work, and a secured deployment has no usable shape at all. That needs a Flight
  `CallHeaderAuthenticator` and is its own change.
- **DirectQuery.** Navigating is not the same as folding. The connector declares all five SQL-92 joins
  and `CAST`, so Power BI may still generate SQL ADAS refuses. This change makes discovery work; how
  much of DirectQuery survives is a separate measurement.

## Impact

- Affected specs: `sql-query-translation` (new surface), `rest-sql-endpoint` and
  `query-translation-endpoint` (how each endpoint treats a metadata statement), `flight-sql-endpoint`
  (one requirement's namespace narrative becomes inaccurate and is corrected).
- Affected code: `service/query/sql/` — `CalciteQueryCatalog`, `SqlQueryFrontEnd`, plus new
  information-schema types; `web/controller/query/SqlQueryController`,
  `web/controller/query/QueryTranslationController`, `web/flight/AdasFlightSqlProducer`.
- No configuration, no migration, no new dependency: `calcite-core` already ships the interpreter.
- Backward compatible. Every statement that works today keeps working — bare names are untouched, and
  the only parser change accepts input that is currently rejected.
