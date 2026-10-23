## Why

DIAL Core now records which route served a request. [`epam/ai-dial-core@ffc06207`](https://github.com/epam/ai-dial-core/commit/ffc06207)
("route observability") adds two flat, top-level fields to every analytics log record that a route
handled. It writes them after `execution_path` and before `operation_duration_ms`:

```java
if (logContext.getRouteName() != null) {
    append(entry, ",\"route_name\":\"", false);
    append(entry, logContext.getRouteName(), true);
    append(entry, "\"", false);
}
if (logContext.getRouteType() != null) {
    append(entry, ",\"route_type\":\"", false);
    append(entry, logContext.getRouteType(), true);
    append(entry, "\"", false);
}
```

The following properties come from reading the commit, not from assumption:

- **`route_name` is the route's configuration key.** Names can collide:
  - a global route and an application route may share a name;
  - application route names repeat across applications.

  So the name identifies a route only together with `route_type` and, for an application route,
  `deployment`.
- **`route_type` has two values.** It is `global` for a route from the `routes` section and
  `application` for a route from `applications.<name>.routes`. Core's `RouteType` enum emits it
  lower-cased.
- **Both fields are omitted, never `null`, when no route served the request.** That covers chat
  completions, embeddings, MCP, deployment features and background jobs. Core also logs only `POST` route
  calls.
- **An application route sets `deployment` to the application. A global route leaves `deployment`
  absent.**

ADAS persists only the columns its mapping declares, so today it drops both fields during ingest. The
only route signal left in `dial_usage_log` is `event_kind = 'route'`, which a URI regex derives. That
regex matches only `/route/` paths, so it classifies application routes and misses every global route.
It also cannot say *which* route ran. Route-level usage, cost and error questions currently need a
`request_uri` parse that works for one route type only.

## What Changes

- Add two physical columns to `dial_usage_log` with a new ClickHouse migration: `route_name` and
  `route_type`, both `LowCardinality(Nullable(String))`.
  - NULL means "no route served this request".
  - Rows ingested before this change read NULL until they are re-ingested.
- Register both columns in the PostgreSQL catalog with a new catalog migration:
  - type `string`, nullable;
  - tag `deployment`, next to `deployment`, `parent_deployment` and `execution_path`;
  - not sensitive, not heavy;
  - ordinals after the current maximum, matching the physical append order.
- Map both columns in `mapping/dial-usage-log.yaml` as direct, nullable string sources. The change needs
  no new transform or deriver and no configuration surface.
- `route_type` is a free string, not a ClickHouse `Enum`. If Core adds a third route kind, it ingests
  without an ADAS release instead of failing the insert. `design.md` explains why the enum was rejected.
- `event_kind` is **unchanged**. It still derives from the URI, so a global route keeps its current `''`
  classification. Consumers that want "served by a route" filter on `route_type IS NOT NULL`.

No breaking change:
- The columns are additive and nullable.
- Existing queries, pipelines and dashboards are unaffected.
- An ADAS upgraded ahead of DIAL Core stores NULL in both columns.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`:
  - Gains a behavioral requirement that a producer-reported route name and type are persisted, and that a
    record without them stores NULL.
  - The table-provisioning requirement's column inventory adds both columns to the producer-optional
    direct set.
- `table-management`: the "Seeded column nullability matches the physical table" requirement's list of
  nullable `dial_usage_log` columns adds `route_name` and `route_type`.

## Impact

- **ClickHouse:** a new migration `src/main/resources/db/migration/clickhouse/V15__dial_usage_log_route.sql`.
  It runs `ALTER TABLE … ADD COLUMN`, which changes metadata only. It doesn't touch the sort key or the
  partitioning.
- **Catalog:** a new migration `src/main/resources/db/migration/postgres/V49__seed_route_columns.sql`.
  This surfaces the columns through `/v1/queries` schema discovery, `/v1/tables/dial_usage_log`, SQL,
  Flight SQL and CSV export. No `/v1` endpoint contract changes.
- **Mapping:** two entries in `src/main/resources/mapping/dial-usage-log.yaml`.
- **Startup validation:** `SchemaValidator` refuses the mapping until the DDL is applied. Operators who
  run with `clickhouse.migrations.enabled=false` must apply `V15` before they upgrade.
- **Tests:**
  - updated counts and column lists in `MappingFileLoaderTest`, `MappingEngineTest`, `SchemaValidatorTest`,
    `DialUsageLogTestModel`, `QuerySchemaDiscoveryIntegrationTest` and `TableManagementIntegrationTest`;
  - an end-to-end ingest assertion.
- **Docs and local tooling:** `docs/column-tags.md` (the `deployment` tag row) and
  `local_env/seed/seed_dial_usage_log.py`.
- **Not in scope:**
  - reclassifying `event_kind`;
  - a backfill of historical rows;
  - surfacing route columns in the recommended catalog's rollups or in Grafana dashboards.
