# Design — Flight SQL catalog metadata RPCs

## Context

`AdasFlightSqlProducer` extends `BasicFlightSqlProducer`, which implements `getFlightInfo*` for every
catalog command by pairing the command's fixed Arrow schema (`FlightSqlProducer.Schemas.*`) with
whatever `determineEndpoints` returns. ADAS's override returns a single endpoint unconditionally:

```java
protected <T extends Message> List<FlightEndpoint> determineEndpoints(
        T request, FlightDescriptor descriptor, Schema schema) {
    return List.of(new FlightEndpoint(new Ticket(Any.pack(request).toByteArray())));
}
```

`DoGet` then lands on `NoOpFlightSqlProducer`'s `getStream*`, which returns `UNIMPLEMENTED`. That is
the measured two-phase failure: promise at `GetFlightInfo`, break at `DoGet`.

Everything the catalog RPCs need already exists and is already the source of truth for the query path:

- `CatalogQueryService.queryableEntityNames()` — ACTIVE `SOURCE` tables, alphabetical.
- `CatalogQueryService.findModel(entity)` → `QueryEntityModel` with `bindings` (flat name → type, in
  catalog order) and `baseSchema`. `buildModel` is the **single** column-level access-control
  chokepoint: it consults `SensitiveColumnAccess.maySeeSensitive()` and omits sensitive columns from
  the model entirely, so a caller who may not see them cannot discover them anywhere.
- `WildcardProjection.sourceFields(model)` — what `SELECT *` expands to: non-heavy source columns.
- `ArrowTypeMapper.field(ResultColumn)` — the one normative kind → Arrow type table.
- `AdasFlightSqlProducer.asCaller(context, action)` — installs the Flight caller's `Authentication`
  into `SecurityContextHolder` for the duration of a catalog read.

The exact metadata schemas are fixed by the Flight SQL protocol and were dumped from
`flight-sql-18.3.0` to avoid guessing:

| Command | Fields |
| --- | --- |
| `GetCatalogs` | `catalog_name: Utf8 not null` |
| `GetDbSchemas` | `catalog_name: Utf8?`, `db_schema_name: Utf8 not null` |
| `GetTables` | `catalog_name: Utf8?`, `db_schema_name: Utf8?`, `table_name: Utf8 not null`, `table_type: Utf8 not null`, `table_schema: Binary not null` |
| `GetTables` (no schema) | the first four |
| `GetTableTypes` | `table_type: Utf8 not null` |
| `GetXdbcTypeInfo` | 19 fields: `type_name`, `data_type`, `column_size`, `literal_prefix`, `literal_suffix`, `create_params: List<item: Utf8>`, `nullable`, `case_sensitive`, `searchable`, `unsigned_attribute`, `fixed_prec_scale`, `auto_increment`, `local_type_name`, `minimum_scale`, `maximum_scale`, `sql_data_type`, `datetime_subcode`, `num_prec_radix`, `interval_precision` |

## Goals / Non-Goals

**Goals:**

- Every catalog RPC ADAS advertises streams real rows; every RPC it does not implement fails at
  `GetFlightInfo`.
- The RPC catalog is a projection of `CatalogQueryService`, structurally incapable of drifting from
  `/v1/queries/entities` — no second catalog, no duplicated type table.
- A caller sees exactly the columns the query path would let them select, and no others.
- A client driven purely by metadata (Power BI's generic ODBC navigator) either gets a correct import
  or a loud, actionable error — never a silently truncated table.

**Non-Goals:**

- `INFORMATION_SCHEMA` / pg-wire as queryable SQL (follow-up; must render from the same model).
- Prepared statements, DML/DDL, transactions, key-metadata RPCs.
- Making the Flight SQL surface able to export a whole table. The structured-query ceiling is 1000
  rows and this change does not touch it.

## Decisions

### D1 — Row building lives in `FlightCatalogStreams`, not in the producer

Five metadata schemas with 1–19 vectors each is more row-assembly code than the producer's transport
role warrants, and it is the part worth unit-testing in isolation. A new package-private
`web/flight/FlightCatalogStreams` takes `CatalogQueryService` and exposes one method per command,
each returning the populated `VectorSchemaRoot` for an allocator the caller owns. The producer stays a
thin adapter: acquire allocator → build root → `listener.start/putNext/completed` → close.

*Alternative rejected:* inline in `AdasFlightSqlProducer`. It would push the class past 500 lines and
make the row content reachable only through gRPC.

### D2 — ADAS exposes no catalog and no schema namespace

`GetCatalogs` and `GetDbSchemas` stream **zero rows**; every `GetTables` row carries `null`
`catalog_name` and `null` `db_schema_name` (both fields are nullable in the protocol schema, and null
is the protocol's "not applicable").

This is not laziness — it is required for the generated SQL to be executable. ADAS's Calcite catalog
(`CalciteQueryCatalog.snapshot()`) registers each entity directly on the root schema, so only a
one-part name resolves. A client told the table lives in catalog `adas`, schema `public` will generate
`SELECT * FROM "adas"."public"."dial_usage_log"`, which the front end rejects as an unknown entity.
Reporting no namespace makes the client generate `SELECT * FROM "dial_usage_log"`, which works today.

Filter semantics follow the protocol comments exactly: a `catalog` field that is **omitted** matches
everything; an **empty string** matches objects without a catalog, i.e. all of ours; a **non-empty**
value matches nothing, so the stream is empty. `db_schema_filter_pattern` behaves the same way — a
pattern that does not match the empty schema name yields no rows.

*Alternative rejected:* invent `adas` / `public` and teach the Calcite catalog to resolve three-part
names. That is a change to the `sql-query-translation` capability for a cosmetic gain, and it is the
right move only as part of the `INFORMATION_SCHEMA` follow-up, where the namespace has to be modelled
properly anyway.

*Known risk:* a navigator that groups strictly by catalog → schema → table may render an awkward blank
group. Recorded under Risks; the crash is fixed either way.

### D3 — `include_schema` advertises the star projection, not the full flat schema

`table_schema` carries the IPC-serialised Arrow schema of `WildcardProjection.sourceFields(model)` —
the entity's non-heavy source columns — rendered through `ArrowTypeMapper`, byte-identical to what
`GetFlightInfo`/`DoGet` return for `SELECT * FROM <entity>`.

The shipped spec already fixed `SELECT *` to mean "non-heavy source columns". A metadata-driven client
reads `table_schema`, then issues `SELECT *`. If the two disagree, the navigator preview shows columns
the import does not deliver — exactly the drift this change exists to prevent. Heavy and enrichment
columns stay fully queryable by name and stay visible with their `heavy` flag on
`GET /v1/queries/entities/schema/{name}`, which is the surface built to describe them.

Serialisation uses the Arrow-standard IPC metadata encoding
(`MessageSerializer.serialize(new WriteChannel(...), schema)`), the same form
`FlightSqlExample` uses, so any conforming client can deserialise it.

An entity whose star expansion is empty (every visible column `heavy`) is still listed — it is
queryable by naming columns — with an empty Arrow schema in `table_schema`.

*Alternative rejected:* advertise every visible column. Maximal information, but it guarantees the
preview/import mismatch above.

### D4 — `GetTableTypes` streams exactly one row: `TABLE`

ADAS exposes ACTIVE `SOURCE` entities and nothing else. Enrichments are not queryable entities, there
are no views, and there is no temporary-table concept. `GetTables`' `table_types` filter therefore
accepts a list containing `TABLE` (or an empty list, meaning no filter) and yields nothing for any
other value.

### D5 — `GetXdbcTypeInfo` ships in this change, derived from `ResultColumnKind`

Power BI's `SQLGetTypeInfo` calls it, so deferring it leaves the navigator broken — which is the whole
point of the change. It is the most tedious piece, but it is also the one most at risk of drifting, so
it is generated rather than hand-maintained: one row per scalar `ResultColumnKind` the Arrow mapper
can produce, in that enum's order.

| `ResultColumnKind` | `type_name` | `data_type` | Notes |
| --- | --- | --- | --- |
| `STRING` | `VARCHAR` | `XDBC_VARCHAR` | case-sensitive, `SEARCHABLE_FULL`, literal quote `'` |
| `INT32` | `INTEGER` | `XDBC_INTEGER` | `num_prec_radix` 10 |
| `INT64` | `BIGINT` | `XDBC_BIGINT` | `num_prec_radix` 10 |
| `FLOAT64` | `DOUBLE` | `XDBC_DOUBLE` | `num_prec_radix` 2 |
| `DECIMAL` | `DECIMAL` | `XDBC_DECIMAL` | precision 38, scale fixed at 9 (min = max = 9) |
| `BOOL` | `BOOLEAN` | `XDBC_BIT` | |
| `DATE` | `DATE` | `XDBC_DATE` | |
| `TIMESTAMP` | `TIMESTAMP` | `XDBC_TIMESTAMP` | millisecond precision, UTC |

Every row is `NULLABILITY_NULLABLE` (all ADAS columns are nullable in Arrow), `auto_increment` null
(no writes), `create_params` null (no DDL through this surface), `fixed_prec_scale` true only for
`DECIMAL`. `uuid` and `object` columns surface as `VARCHAR` and so are covered by the `STRING` row;
`array` has no XDBC code and is deliberately absent — documented rather than faked, since inventing a
code would make a driver generate casts ADAS cannot honour. A `data_type` filter on the command
narrows to the matching row.

Deriving the table from `ResultColumnKind` means a future kind cannot be added to `ArrowTypeMapper`
without the switch here failing to compile.

### D6 — A Flight statement with no `LIMIT` is rejected (the "unbounded `SELECT *`" decision)

**Decision:** `getFlightInfoStatement` rejects a statement that specifies no `LIMIT` with
`INVALID_ARGUMENT`, before ClickHouse is contacted, with a message that names the fix:

> this endpoint requires an explicit LIMIT: add `LIMIT <n>` with n ≤ 1000 (a query with no LIMIT would
> be silently truncated to the default of 100 rows)

Detection needs no new plumbing. `RelToStructuredQueryTranslator.page(ctx)` returns `null` when the
statement had neither `OFFSET` nor `LIMIT`, and an `OffsetPage` with `limit == 0` for
`OFFSET`-without-`LIMIT`; the default of 100 is applied later, inside
`StructuredQueryBuilder.applyPage`, which runs during `queryService.execute`. So at the producer,
`translation.query().page()` still distinguishes "no limit given" from `LIMIT 100`.

**Reasoning.** ADAS's hard ceiling is 1000 rows, so *no* Flight client can ever export a complete
table — every result is a sample. That makes the size of the sample the only thing the user can
reason about, and today it is invisible: a Power BI import of `SELECT * FROM dial_usage_log` yields
100 rows with no indication that 100 was chosen by the server. Someone builds a dashboard on it and
publishes the numbers. Discovery is precisely what turns that from unreachable into the default path,
so the change that adds discovery is the change that has to close it.

Alternatives, and why they lose:

- *Accept it, document the default.* Cheapest, and the failure mode is a wrong published number. The
  only channel to the user is documentation nobody reads before clicking Load.
- *Surface the truncation.* Arrow schema metadata (`adas.row_limit_applied=100`) or per-batch
  `app_metadata` reaches ADBC/pyarrow but is inert over ODBC — the exact client that crashes today
  would still show 100 unmarked rows. Worth adding later for ADBC callers; useless as the primary
  guard.
- *Clamp loudly / raise the ceiling.* Out of scope; changes shipped structured-query governance for
  every surface.

**Blast radius is confined to Flight.** REST `POST /v1/queries/execute-sql`, the DSL, and
`/v1/queries/translate*` keep the default limit unchanged: those callers author the request and read
the limit in their own payload, and changing them would break shipped capabilities nobody asked about.
The rejection is stricter than structured-query governance, never looser, so the "governance applies
unchanged" guarantee is preserved in substance — the spec wording gains the Flight-specific stricter
rule explicitly.

`OFFSET`-without-`LIMIT` (`page.limit == 0`) is rejected by the same rule; it is the same silent
default wearing a different hat.

The existing `FlightSqlIntegrationTest.limitGovernanceMatchesRest` asserts the old behaviour
(no `LIMIT` → 100 rows) and is updated to assert the rejection, keeping the `LIMIT 50000` /
`LIMIT 1000` / `LIMIT 7` cases as they are.

### D7 — `determineEndpoints` whitelists, and every catalog `getStream*` runs `asCaller`

`determineEndpoints` switches on the request message type and returns an endpoint only for
`CommandGetSqlInfo`, `CommandGetCatalogs`, `CommandGetDbSchemas`, `CommandGetTables`,
`CommandGetTableTypes` and `CommandGetXdbcTypeInfo`. Anything else — `CommandGetPrimaryKeys`,
`CommandGetExportedKeys`, `CommandGetImportedKeys`, `CommandGetCrossReference` — throws
`UNIMPLEMENTED` from `GetFlightInfo`, naming the command. `getFlightInfoStatement` and the
prepared-statement overrides do not route through this method and are unaffected.

Every catalog `getStream*` wraps its `CatalogQueryService` reads in `asCaller(context, …)`, the same
call the statement path uses, so `SensitiveColumnAccess` sees the Flight caller's roles. Without it the
reads would run with whatever `SecurityContextHolder` holds on a pooled gRPC thread — in practice no
authentication, i.e. fail-open, which for a catalog means leaking the existence of `jwt_claims` and
`request_tags` to a `READ_ONLY_ADMIN`. In `security.mode=none` no middleware is registered and the
filter fails open by design, exactly as on the query path.

### D8 — Metadata streams take a bounded child allocator but not the ClickHouse stream gate

Each catalog `DoGet` allocates from `ArrowAllocators.newChildAllocator(...)` bounded by
`flight.request-memory-limit-bytes` and closes it in a `finally`, satisfying the no-leak requirement.
It does **not** acquire `FlightStreamGate`: that semaphore caps *ClickHouse* concurrency, and metadata
never reaches ClickHouse. Gating it would mean a saturated query load makes discovery fail — for the
Dremio ODBC driver, the same `UNIMPLEMENTED`-shaped surprise on a call it treats as infallible, which
is the crash this change removes. Row counts are bounded by the catalog's size (tens of rows), so
there is nothing to protect against.

### D9 — `GetSqlInfo` declares the absent namespaces explicitly (added on the draft MR)

D2 decided that ADAS exposes no catalog and no schema namespace, and relied on `GetCatalogs`/
`GetDbSchemas` streaming zero rows to convey it. That turned out not to convey it at all: zero rows says
the namespace is *empty*, not that it is *absent*. Measured against Power BI Desktop with Dremio's Flight
SQL ODBC driver 0.9.7, the driver reported `SQL_CATALOG_NAME=False`/`SQL_CATALOG_USAGE=0` (so no catalog
part) but `SQL_SCHEMA_TERM='schema'`/`SQL_SCHEMA_USAGE=1` from its own defaults, and generated
`select "response_id", ... from ""."rate_analytics"` — the schema name being the empty string. ADAS
answered `SQL parse error: Encountered "from \""`.

`getStreamSqlInfo` therefore declares all four values: `SQL_CATALOG_TERM`/`SQL_SCHEMA_TERM` empty and
`SQL_CATALOGS_SUPPORTED_ACTIONS`/`SQL_SCHEMAS_SUPPORTED_ACTIONS` with no actions (an int32 bitmask of 0,
which is what surfaces as ODBC's `SQL_SCHEMA_USAGE=0`). `withSqlDdlCatalog`/`withSqlDdlSchema`, already
present, do not cover this — they declare whether DDL can create such objects.

*Alternative rejected:* expose a named schema such as `public` from `GetDbSchemas`. Verified that
`select "rate" from "public"."rate_analytics"` fails with `Object 'public' not found`, so that route also
requires qualified-name resolution in the SQL front end — a change to `sql-query-translation` for no gain
over declaring the truth. Unqualified names remain the decision; this only makes it discoverable.

*Lesson for D2:* a design decision about what does not exist has to be asserted on the wire, not
inferred from an empty result. The spec now states the declaration as its own requirement and has the
D2 rationale reference it.

## Risks / Trade-offs

- **A navigator groups strictly by catalog → schema → table (D2)** → tables appear under a blank
  group instead of a named one. The crash is fixed regardless, and naming a namespace would break the
  generated SQL (D2). The `INFORMATION_SCHEMA` follow-up is where a real namespace belongs.
- **Rejecting no-`LIMIT` statements is a breaking change (D6)** → a client that today gets 100 rows
  gets an error. Deliberate and confined to Flight; the error names the fix, and today's discovery gap
  means very few callers can be relying on it. Marked **BREAKING** in the delta spec.
- **`table_schema` omits heavy and enrichment columns (D3)** → a metadata-only client cannot discover
  them. They stay queryable by name and fully described by
  `GET /v1/queries/entities/schema/{name}`; the alternative guarantees a preview/import mismatch.
- **`array` columns have no `GetXdbcTypeInfo` row (D5)** → a driver that consults type info before
  binding may not know how to handle a `List` column. It still arrives correctly typed in the Arrow
  stream; faking an XDBC code would invite casts ADAS cannot honour.
- **Catalog rows are rebuilt per call, one `findModel` per entity** → with `include_schema` that is
  N+1 cheap Postgres reads, each in its **own** read-only transaction (neither `streamCatalog` nor
  `FlightCatalogStreams` opens one, so `CatalogQueryService`'s own `@Transactional(readOnly = true)`
  scopes each call separately). There is therefore no snapshot across the listing and the per-entity
  lookups — which is exactly why `starSchema` guards against an entity disappearing mid-request; do not
  drop that guard on the assumption of a shared transaction. Read volume matches what
  `CalciteQueryCatalog` already does on every statement. If the catalog ever grows to hundreds of
  entities this is the first thing to cache — the same caching that would serve the SQL front end.
- **Local verification cannot cover Power BI** → the autopilot verifies over pyarrow/`FlightSqlClient`
  in tests and against a running instance; the ODBC-driver and Power BI-navigator behaviour that
  motivated the change has to be re-checked by hand on the reporter's machine before the MR leaves
  draft.

## Migration Plan

No data migration, no configuration change, no Flyway version. Deploy is a normal rollout; rollback is
reverting the commit. The two breaking changes are gRPC-status changes on a capability that is disabled
by default (`flight.enabled=false`) and, where enabled, is reached only by Flight SQL clients.

Verification note: launching ADAS from an IntelliJ run configuration breaks Flight, because
`build.gradle` attaches the Arrow JVM flags (`--add-opens=java.base/java.nio=ALL-UNNAMED`,
`--sun-misc-unsafe-memory-access=allow`, `--enable-native-access=ALL-UNNAMED`) only to the `test` and
`bootRun` tasks. The symptom is `GetFlightInfo` succeeding while every `DoGet` returns a bare
`internal error`. Use `./gradlew bootRun` or add the flags to the run configuration.

## Open Questions

- Should ADBC/pyarrow callers also get `adas.row_limit_applied` schema metadata once an explicit
  `LIMIT` is mandatory? Not needed for correctness after D6, so left out of this change.
- Does the Dremio ODBC driver 0.9.7 tolerate a zero-row `GetCatalogs`? The protocol allows it and the
  pyarrow probe path will confirm the stream shape, but the driver's navigator rendering can only be
  confirmed on the reporter's Power BI install.
