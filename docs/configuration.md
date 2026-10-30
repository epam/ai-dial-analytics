# Configuration

Property reference for AI DIAL Analytics, the Analytics Data Access Service (ADAS). Every
operationally relevant property is bound to an environment variable in
`src/main/resources/application.yml` using the `${ENV_VAR:default}` convention.

This file says **what each variable is and what to set it to**. Why a limit exists, what a subsystem
does with it, and what a refusal means are specified in `openspec/specs/<capability>/spec.md`.

## Overview

Values may be supplied as environment variables, YAML/properties files, command-line arguments, or
system properties, resolved in Spring Boot's normal precedence order.

**Required** is `Yes` when a real deployment cannot work correctly on the default, `No` when the
variable is tuning and safe to leave alone, and otherwise the short condition under which it is
required. The `Property` column is the `application.yml` path, which is what an external YAML file
overrides. Passwords and secrets say so in their description — supply those from the deployment's
secret store and never commit them.

The ClickHouse and PostgreSQL defaults line up with `local_env/docker-compose.yml`, so `./gradlew
bootRun` connects to a local stack with nothing set. The [blob source](#blob-source) has no local
default, and it is the one group a working deployment cannot skip: until it points at a reachable
store, nothing is ever ingested.

Set `SPRING_CONFIG_ADDITIONAL_LOCATION` to a file or directory (`file:/config/override.yml`,
`file:/config/`) to override any property here. This is the only practical way to set the list- and
map-valued properties — `sync.targets`, `enrichment.cursor.keys`, `providers.*`, `blob.overrides.*`.

## Container environment

Read by `docker-entrypoint.sh` and the base image's certificate hook, not by Spring:

| Variable                            | Default   | Required | Description                                                                                             |
|-------------------------------------|-----------|----------|---------------------------------------------------------------------------------------------------------|
| `USE_SYSTEM_CA_CERTS`               | _(unset)_ | No       | Any non-empty value (`1`, `true`, `yes`) imports certificates mounted into `/certificates/` at startup. |
| `JAVA_OPTS`                         | _(empty)_ | No       | Extra JVM arguments appended to the launch command.                                                     |
| `DEBUG_OPTS`                        | _(empty)_ | No       | JVM debug arguments, applied before `JAVA_OPTS`.                                                        |
| `SPRING_CONFIG_ADDITIONAL_LOCATION` | _(unset)_ | No       | External YAML/properties location overriding any property here.                                         |

The three JVM flags Arrow's off-heap allocator needs on JDK 24+ are applied by the entrypoint, by
`bootRun`, and by the test suite. Only a custom non-Docker launch must add them itself — see
`openspec/specs/flight-sql-endpoint/spec.md`.

## Spring framework

### Server

| Variable                 | Property                 | Default | Required | Description                                                         |
|--------------------------|--------------------------|---------|----------|---------------------------------------------------------------------|
| `HTTP_PORT`              | `server.port`            | `5000`  | No       | Port ADAS serves the API on.                                        |
| `MANAGEMENT_SERVER_PORT` | `management.server.port` | `9464`  | No       | Port the `/actuator` endpoints (probes, info, Prometheus) serve on. |

### Scheduled task execution

The pool the blob poller, the daily data-sync and the runtime level-file poll share. The aggregate tick
and the operator backfill each run on their own threads, so neither is sized by this.

| Variable               | Property                           | Default | Required | Description                                                                                                                |
|------------------------|------------------------------------|---------|----------|----------------------------------------------------------------------------------------------------------------------------|
| `SCHEDULING_POOL_SIZE` | `spring.task.scheduling.pool.size` | `4`     | No       | Threads for both ingestion jobs and the level-file poll; below three a slow job delays another. Takes effect at startup.   |

### Logging

| Variable                  | Property                                  | Default                               | Required | Description                                                                                 |
|---------------------------|-------------------------------------------|---------------------------------------|----------|---------------------------------------------------------------------------------------------|
| `APP_LOG_LEVEL`           | `logging.level.com.epam.aidial.analytics` | `INFO`                                | No       | Log level for the application package (`TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR`).              |
| `LOG_CONFIG_PATH`         | `observability.log-levels.path`           | `/app/log-config/logging.levels.json` | No       | Runtime level file (below). A missing file changes nothing.                                 |
| `LOG_CONFIG_INTERVAL`     | `observability.log-levels.interval`       | `10s`                                 | No       | How often the level file is re-read; at least `1s`.                                         |
| `EXECUTION_TRACE_ENABLED` | `observability.execution-trace.enabled`   | `true`                                | No       | Call tracing for `@LogExecution` classes, active only while a class's logger is at `TRACE`. |

**Runtime levels.** To change a level without a restart, put it in the level file, and give anything below
`INFO` a `validTill` (epoch milliseconds) so it reverts on its own. Format:

```json
{"loggerLevels": {
   "com.epam.aidial.analytics.service.enrichment": {"defaultLevel": "INFO", "configuredLevel": "DEBUG", "validTill": 1790000000000}}}
```

In Kubernetes, mount the file from a ConfigMap as a **directory** at `/app/log-config` — a `subPath` mount never
sees updates — and allow about a minute for a change to arrive. `LOGGING_LEVEL_<PACKAGE>` variables still set
static levels at startup.

Not configurable: `com.epam.aidial.analytics.sql`, `org.springframework.web.client`,
`org.springframework.http.converter`, `org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind`,
`org.springframework.jdbc.core`, `jclouds.wire`, `jclouds.headers` and `jclouds.signature` are pinned at `INFO`,
and neither `APP_LOG_LEVEL` nor the level file can lower them — below `INFO` they print SQL with its literals,
HTTP and blob bodies, bound catalog values, and the blob-storage Authorization header.

### API documentation (springdoc / Swagger UI)

Both are enabled by default (`/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`). In `oidc` mode they need
an authenticated caller unless `DISABLE_SWAGGER_AUTHORIZATION=true`; in `none` mode they are open, as is everything.

| Variable                        | Property                                 | Default | Required | Description                                                                                                                                                                    |
|---------------------------------|------------------------------------------|---------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `API_DOCS_ENABLED`              | `springdoc.api-docs.enabled`             | `true`  | No       | Serve the OpenAPI document at `/v3/api-docs`; `false` also disables the UI, which is rendered from it.                                                                         |
| `SWAGGER_UI_ENABLED`            | `springdoc.swagger-ui.enabled`           | `true`  | No       | Serve the Swagger UI at `/swagger-ui.html`, keeping the OpenAPI JSON available.                                                                                                |
| `DISABLE_SWAGGER_AUTHORIZATION` | `security.disable-swagger-authorization` | `false` | No       | Serve the OpenAPI document and Swagger UI without a credential in `oidc` mode. They map every functional endpoint, so leave it `false` wherever ADAS is broadly reachable. |

### OpenTelemetry

All export is disabled by default. `OTEL_EXPORT_ENABLED` is the master switch; each per-signal flag
overrides it for its signal. The base endpoint is fanned out to the per-signal URLs by appending
`/v1/traces`, `/v1/logs` and `/v1/metrics`. The Prometheus scrape endpoint stays active regardless.

| Variable                              | Property                                                          | Default                         | Required              | Description                                                                                                                                     |
|---------------------------------------|-------------------------------------------------------------------|---------------------------------|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `OTEL_EXPORT_ENABLED`                 | _(fallback for the flags below)_                                  | `false`                         | No                    | Master switch for OTLP export of traces, logs and metrics.                                                                                      |
| `OTEL_TRACES_EXPORT_ENABLED`          | `management.tracing.enabled`, `management.tracing.export.enabled` | `OTEL_EXPORT_ENABLED`           | No                    | Create and export spans; when off the SDK hands out a no-op tracer, so tracing is fully inert.                                                  |
| `OTEL_LOGS_EXPORT_ENABLED`            | `management.logging.export.otlp.enabled`                          | `OTEL_EXPORT_ENABLED`           | No                    | Export application logs over OTLP.                                                                                                              |
| `OTEL_METRICS_EXPORT_ENABLED`         | `management.otlp.metrics.export.enabled`                          | `OTEL_EXPORT_ENABLED`           | No                    | Export application metrics over OTLP, additively to the Prometheus endpoint.                                                                    |
| `OTEL_EXPORTER_OTLP_ENDPOINT`         | _(base for the endpoints below)_                                  | `http://localhost:4318`         | If any export enabled | Collector base URL, no trailing slash.                                                                                                          |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`  | `management.opentelemetry.tracing.export.otlp.endpoint`           | base + `/v1/traces`             | No                    | Full traces URL; overrides the base for this signal.                                                                                            |
| `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT`    | `management.opentelemetry.logging.export.otlp.endpoint`           | base + `/v1/logs`               | No                    | Full logs URL; overrides the base for this signal.                                                                                              |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `management.otlp.metrics.export.url`                              | base + `/v1/metrics`            | No                    | Full metrics URL; overrides the base for this signal.                                                                                           |
| `OTEL_EXPORTER_OTLP_PROTOCOL`         | `management.opentelemetry.*.export.otlp.transport`                | `http`                          | No                    | Transport for traces and logs: `http` or `grpc`. Metrics are always sent over HTTP.                                                             |
| `OTEL_TRACES_SAMPLING_PROBABILITY`    | `management.tracing.sampling.probability`                         | `1.0`                           | No                    | Probability (0.0–1.0) that a new trace is sampled; parent-based, so upstream decisions are honoured.                                            |
| `OTEL_SERVICE_NAME`                   | `management.opentelemetry.resource-attributes.service.name`       | `ai-dial-analytics`             | No                    | `service.name` resource attribute.                                                                                                              |
| `OTEL_RESOURCE_ATTRIBUTES`            | _(read by the OTel SDK, not bound in `application.yml`)_          | _(empty)_                       | No                    | Extra resource attributes as `key=value,key=value`; a `service.name` entry here always loses to the property above, so use `OTEL_SERVICE_NAME`. |

Not configurable: the actuator endpoints exposed under `/actuator` on the management port
(`MANAGEMENT_SERVER_PORT`) are fixed to `health,info,prometheus`, the health endpoint always returns full details, and the liveness and readiness
probe groups are always served.

## Data stores

ClickHouse, where everything lands and every query runs; the PostgreSQL catalog that says what is
queryable; and the blob source `data-sync` ingests from.

### ClickHouse connection

Backs every read — `/v1/queries`, Flight SQL, and the scan / group-rows / preview / window-probe
reads under `/v1/pipelines`. The connection is **read-only**: `GRANT SELECT` is sufficient.

The query client is created lazily, so an unreachable ClickHouse surfaces per request as
`503 clickhouse_unavailable` rather than at boot — but only once the two startup checks that do reach
ClickHouse are off (`CLICKHOUSE_MIGRATIONS_ENABLED`, `SYNC_VALIDATE_SCHEMA`); with either at its
default, an unreachable server aborts startup.

Server-side prerequisites — supported versions, the encryption key the body columns need, and the
grants — are in [`clickhouse-server.md`](clickhouse-server.md).

| Variable              | Property              | Default     | Required | Description                                                                   |
|-----------------------|-----------------------|-------------|----------|-------------------------------------------------------------------------------|
| `CLICKHOUSE_HOST`     | `clickhouse.host`     | `localhost` | Yes      | ClickHouse host.                                                              |
| `CLICKHOUSE_PORT`     | `clickhouse.port`     | `8123`      | No       | ClickHouse HTTP interface port.                                               |
| `CLICKHOUSE_DATABASE` | `clickhouse.database` | _(empty)_   | No       | Default database for both connections. Blank means `default`.                 |
| `CLICKHOUSE_USER`     | `clickhouse.user`     | `default`   | Yes      | Read-only user. The default is the local image's passwordless `default` user. |
| `CLICKHOUSE_PASSWORD` | `clickhouse.password` | _(empty)_   | Yes      | Password for the read-only user. Supply from the deployment's secret store.   |

### ClickHouse writer credential

Used by `data-sync` ingest, the startup migrations, the table catalog (`/v1/tables`), and the Class-1
and aggregate `INSERT … SELECT` statements. Host, port and database are reused from the read
connection; only the credential differs, so the read path stays strictly read-only. The grant matrix
is in [`clickhouse-server.md`](clickhouse-server.md#writer-credential).

| Variable                     | Property                     | Default   | Required | Description                                                              |
|------------------------------|------------------------------|-----------|----------|--------------------------------------------------------------------------|
| `CLICKHOUSE_WRITER_USER`     | `clickhouse.writer.user`     | `default` | Yes      | User holding the write and DDL grants.                                   |
| `CLICKHOUSE_WRITER_PASSWORD` | `clickhouse.writer.password` | _(empty)_ | Yes      | Password for the writer user. Supply from the deployment's secret store. |

### ClickHouse schema migrations

Run at startup by `ClickHouseMigrationRunner` (programmatic Flyway over the ClickHouse JDBC driver)
from `src/main/resources/db/migration/clickhouse`, authenticating with the writer credential.
ClickHouse has no Spring-managed `DataSource`, so it falls outside Boot's Flyway — which is scoped to
the PostgreSQL catalog alone. A file placed in the wrong migration root silently never runs.

| Variable                        | Property                        | Default | Required | Description                                                                                                                                                                    |
|---------------------------------|---------------------------------|---------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CLICKHOUSE_MIGRATIONS_ENABLED` | `clickhouse.migrations.enabled` | `true`  | No       | Run the ClickHouse migrations at startup; `true` also when the property is absent. Set `false` when the schema is provisioned out of band. A migration failure aborts startup. |

### PostgreSQL table catalog

Holds the table definitions, column mappings and per-table access lists that drive the structured
query path. The JDBC URL is composed from the host/port/database parts below. Schema is provisioned
by Boot's Flyway, scoped to `classpath:db/migration/postgres`.

| Variable                       | Property                               | Default     | Required | Description                                                                                                                           |
|--------------------------------|----------------------------------------|-------------|----------|---------------------------------------------------------------------------------------------------------------------------------------|
| `POSTGRES_HOST`                | `spring.datasource.url` (host)         | `localhost` | Yes      | Catalog host.                                                                                                                         |
| `POSTGRES_PORT`                | `spring.datasource.url` (port)         | `5432`      | No       | Catalog port.                                                                                                                         |
| `POSTGRES_DATABASE`            | `spring.datasource.url` (database)     | `testdb`    | Yes      | Catalog database name.                                                                                                                |
| `POSTGRES_OPS`                 | `spring.datasource.url` (query string) | _(empty)_   | No       | JDBC URL query string appended after `?`, e.g. `sslmode=require&ApplicationName=adas`.                                                |
| `POSTGRES_DATASOURCE_USERNAME` | `spring.datasource.username`           | `postgres`  | Yes      | Catalog user; needs read/write on the catalog tables, and DDL when migrations run.                                                    |
| `POSTGRES_DATASOURCE_PASSWORD` | `spring.datasource.password`           | `postgres`  | Yes      | Catalog password. Supply from the deployment's secret store.                                                                          |
| `POSTGRES_MIGRATIONS_ENABLED`  | `spring.flyway.enabled`                | `true`      | No       | Run the PostgreSQL migrations at startup. Set `false` when the schema is provisioned out of band. A migration failure aborts startup. |

### Blob source

Where `data-sync` reads DIAL usage logs from, and its only input: until these point at a reachable
store nothing is ever ingested. The client is built on first use, so a missing or wrong setting
surfaces as a failed poll cycle every minute, not at startup. Read-only access suffices.

Access goes through jclouds, on EPAM's fork because upstream's `azureblob` cannot take an Azure AD
bearer token. Two providers are supported: `azureblob` for Azure Blob Storage, and `aws-s3` for AWS
S3 — its endpoint and region rules, credential modes and IAM policy are in
[`aws-s3-access.md`](aws-s3-access.md). Other providers from jclouds' all-blobstore bundle are on the
classpath and accept an identity/secret pair, but are not documented here.

For `azureblob`, auth is **shared key** when both `BLOB_IDENTITY` and `BLOB_CREDENTIAL` are set. If
either is omitted it falls back to **Azure AD / managed identity** (`DefaultAzureCredential`), which
additionally requires `BLOB_ENDPOINT`, the **Storage Blob Data Reader** role on the pod's
user-assigned managed identity, a pod configured per Azure Workload Identity, and **hierarchical
namespace (HNS) disabled** on the storage account. SAS tokens are not used.

| Variable          | Property          | Default     | Required             | Description                                                                                                                                                                                                  |
|-------------------|-------------------|-------------|----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `BLOB_PROVIDER`   | `blob.provider`   | `azureblob` | No                   | jclouds provider id, `azureblob` or `aws-s3`. `filesystem` is test-scoped and unavailable at runtime.                                                                                                        |
| `BLOB_BUCKET`     | `blob.bucket`     | `logs`      | Match producer       | Container or bucket name holding the logs.                                                                                                                                                                   |
| `BLOB_IDENTITY`   | `blob.identity`   | _(empty)_   | For shared key       | Azure storage account name, or AWS access key id; blank with `BLOB_CREDENTIAL` selects Azure managed identity / AWS workload identity.                                                                       |
| `BLOB_CREDENTIAL` | `blob.credential` | _(empty)_   | For shared key       | Azure account key, or AWS secret access key, from the deployment's secret store; blank with `BLOB_IDENTITY` selects managed identity / workload identity.                                                    |
| `BLOB_ENDPOINT`   | `blob.endpoint`   | _(empty)_   | Managed identity, S3 | Endpoint override, `https://<account>.blob.core.windows.net` or `https://s3.<region>.amazonaws.com`. Blank uses the provider default, which Azure AD auth cannot resolve and which signs S3 for `us-east-1`. |

Managed-identity mode auto-applies the jclouds overrides `jclouds.azureblob.auth=azureAd` and
`jclouds.oauth.credential-type=bearerTokenCredentials`; setting any jclouds property by hand — rarely
needed — means `blob.overrides.<key>` in an external YAML file, as dotted map keys are impractical as
environment variables.

Which keys under the container are read, and on what schedule, is [data sync](#data-sync).

## Query surface

### Query execution budget

Per-query ceilings attached as ClickHouse server settings, applied to `POST /v1/queries/execute`,
`POST /v1/queries/execute-sql` and Arrow Flight SQL alike. Exceeding one refuses the query with
`422 query_budget_exceeded`; nothing is sampled or truncated.

The default timeout is chosen against a 30s client, so raise it only together with the timeouts of
the clients that wait on it. Size `max-memory` against the ClickHouse **container's** limit, not the
host's RAM. Spilling consumes scratch disk, so raise `spill-threshold` and `max-memory` together or
neither.

| Variable                       | Property                       | Default | Required | Description                                                                                                                            |
|--------------------------------|--------------------------------|---------|----------|----------------------------------------------------------------------------------------------------------------------------------------|
| `QUERY_BUDGET_TIMEOUT`         | `query.budget.timeout`         | `20s`   | No       | Wall-clock ceiling per request, shared by a row-mode query's `include_total` count. At least `1s`.                                     |
| `QUERY_BUDGET_MAX_MEMORY`      | `query.budget.max-memory`      | `2GB`   | No       | Hard memory ceiling per query per node. Keep below the ClickHouse container's memory limit.                                            |
| `QUERY_BUDGET_SPILL_THRESHOLD` | `query.budget.spill-threshold` | `1GB`   | No       | Aggregation-state size at which a query spills to disk instead of growing. Must be strictly below `max-memory`; `0` disables spilling. |

### Query deduplication

| Variable              | Property            | Default | Required | Description                                                                                                          |
|-----------------------|---------------------|---------|----------|----------------------------------------------------------------------------------------------------------------------|
| `FORCE_FINAL_QUERIES` | `query.force-final` | `true`  | No       | Migration switch: `true` reads every user-facing query `FINAL` whatever it says; `false` honours the query's `FINAL`. |

### Request body rebuild

| Variable                                   | Property                                   | Default | Required | Description                                                                                                                             |
|--------------------------------------------|--------------------------------------------|---------|----------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS`    | `query.request-body-rebuild.max-rounds`    | `8`     | No       | Chain-lookup rounds one read may run for `request_body_full` / `request_body_rebuild`; past it rows read `partial`. At least `1`.       |
| `QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS` | `query.request-body-rebuild.max-ancestors` | `20000` | No       | Distinct ancestor rows one read of `request_body_full` / `request_body_rebuild` may resolve; past it rows read `partial`. At least `1`. |

### Saved queries

| Variable                       | Property                       | Default | Required | Description                                                                                                                        |
|--------------------------------|--------------------------------|---------|----------|------------------------------------------------------------------------------------------------------------------------------------|
| `SAVED_QUERIES_MAX_BODY_BYTES` | `saved-queries.max-body-bytes` | `65536` | No       | Maximum combined serialized UTF-8 size of a saved query's `query`, `sql`, `time` and `chart` payload; larger is rejected with 422. |

### Arrow Flight SQL endpoint

An optional Arrow Flight SQL server (gRPC) on its own port, running statements through the same
governed pipeline as `POST /v1/queries/execute`. Disabled by default; every variable below except
`FLIGHT_ENABLED` is read only when it is `true`. Authentication follows `security.mode`. Needs its own
gRPC/h2c route on a dedicated hostname — see [`kubernetes-exposure.md`](kubernetes-exposure.md).

| Variable                            | Property                            | Default    | Required | Description                                                                                                                                    |
|-------------------------------------|-------------------------------------|------------|----------|------------------------------------------------------------------------------------------------------------------------------------------------|
| `FLIGHT_ENABLED`                    | `flight.enabled`                    | `false`    | No       | Master switch. When `false` no listener is opened and no Flight components are created.                                                        |
| `FLIGHT_PORT`                       | `flight.port`                       | `32010`    | No       | gRPC port for the listener; `0` binds an ephemeral port (used by tests).                                                                       |
| `FLIGHT_BATCH_ROWS`                 | `flight.batch-rows`                 | `1024`     | No       | Flush an Arrow record batch to the client after this many rows.                                                                                |
| `FLIGHT_BATCH_MAX_BYTES`            | `flight.batch-max-bytes`            | `4194304`  | No       | …or after this much buffered Arrow memory in bytes, whichever comes first.                                                                     |
| `FLIGHT_MAX_CONCURRENT_STREAMS`     | `flight.max-concurrent-streams`     | `4`        | No       | Cap on concurrently executing result streams; excess requests fail fast with retryable `RESOURCE_EXHAUSTED`.                                   |
| `FLIGHT_REQUEST_MEMORY_LIMIT_BYTES` | `flight.request-memory-limit-bytes` | `67108864` | No       | Hard bound on Arrow memory a single request may allocate, via a per-request child allocator.                                                   |
| `FLIGHT_MAX_ROWS`                   | `flight.max-rows`                   | `10000`    | No       | Most rows this transport returns from ClickHouse, replacing the shared 1000-row maximum for Flight only. Raising it trades JVM heap for reach. |

## Table catalog and sinks

### Table catalog limits

Guardrails on `/v1/tables`; a violation is rejected with 422.

| Variable                       | Property                              | Default | Required | Description                                                                                                        |
|--------------------------------|---------------------------------------|---------|----------|--------------------------------------------------------------------------------------------------------------------|
| `TABLES_MAX_COLUMNS_PER_TABLE` | `tables.limits.max-columns-per-table` | `256`   | No       | Maximum exposed columns per table, enforced at create and when a `PATCH` adds columns.                             |
| `TABLES_MAX_TABLES`            | `tables.limits.max-tables`            | `1000`  | No       | Maximum table definitions in the catalog, enforced at create.                                                      |
| `TABLES_MAX_WRITE_BATCH_ROWS`  | `tables.limits.max-write-batch-rows`  | `10000` | No       | Maximum rows accepted in one `POST /v1/tables/{name}/rows` batch, and the chunk size an OTLP export is written in. |

### OTLP sinks

The OpenTelemetry sink surface: `/v1/otel/sinks` to provision, `/v1/otel/sink/{name}/v1/{logs,traces,metrics}`
to export. See [`claude-code-telemetry.md`](claude-code-telemetry.md) for a worked setup.

| Variable                      | Property                           | Default | Required | Description                                                                                                                                        |
|-------------------------------|------------------------------------|---------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `OTEL_SINK_ENABLED`           | `otel-sink.enabled`                | `true`  | No       | Serve the OTLP surface; `true` also when the property is absent. When `false` every `/v1/otel/**` endpoint answers `503 otel_disabled`.            |
| `OTEL_MAX_ROWS_PER_REQUEST`   | `otel-sink.max-rows-per-request`   | `50000` | No       | Maximum rows one export may flatten into (one per log record, span, or metric data point); larger is rejected with 422 before anything is written. |
| `OTEL_DEFAULT_RETENTION_DAYS` | `otel-sink.default-retention-days` | `30`    | No       | Row-expiry TTL in days given to a sink's landing tables when the create request omits `retention_days`. `0` creates them with no TTL.              |

### Catalog transfer

Upload limits on `POST /v1/catalog/import` and `/import/preview`, ADAS's only multipart endpoints; a larger upload is
rejected with `413 payload_too_large`. See [`catalog-promotion.md`](catalog-promotion.md).

| Variable                          | Property                                    | Default | Required | Description                                                      |
|-----------------------------------|---------------------------------------------|---------|----------|------------------------------------------------------------------|
| `CATALOG_IMPORT_MAX_FILE_SIZE`    | `spring.servlet.multipart.max-file-size`    | `20MB`  | No       | Maximum size of the uploaded bundle file.                        |
| `CATALOG_IMPORT_MAX_REQUEST_SIZE` | `spring.servlet.multipart.max-request-size` | `25MB`  | No       | Maximum size of the whole multipart request, all parts included. |

## Pipelines

### Pipeline read budget

Per-statement ceilings for every read ADAS issues on a pipeline's behalf, and for the aggregate refresh write.
Which statements they bound, and the refusal each reports, are specified in
[enrichment-runtime](../openspec/specs/enrichment-runtime/spec.md), "Every pipeline-internal read runs under a
resource budget".

This is a blast-radius ceiling, not an abuse ceiling — the caller is the Enrichment Runner, or an author previewing an `enrich` pipeline. A row limit
bounds the rows a pipeline read returns, not the rows it reads, so a group fetch that projects heavy
columns for one member can cost gigabytes; without a ceiling it consumes whatever the node will give it.

Size `max-memory` from the heaviest legitimate read, not from the product of the ceiling and the runner's
scheduler thread pool. The product is the figure that bounds the node, but it is not the figure that keeps
a pipeline working: a partition-aligned payload scan tick can still peak
above a GiB, so a ceiling chosen to make five of them fit at once refuses the pipeline instead. Where the
product exceeds ClickHouse's own `max_server_memory_usage`, bring the concurrency down.

Set `timeout` **below the runner's own HTTP read timeout**: above it the runner hangs up first and
dead-letters on an opaque socket timeout instead of getting `422 pipeline_read_budget_exceeded`, and ADAS
keeps the query running for a caller that has gone. A materialize fits within one `timeout`. A group fetch issues one statement to select its
members and one per `heavy` column its pipeline binds, each under its own ceiling, so size that read
timeout against `timeout` times one more than the pipeline's heaviest declaration.

There is no spill-threshold here, unlike the query and preview budgets: that setting bounds `GROUP BY`
state, and none of the reads groups; the aggregate refresh write, which does, is bounded by
`AGGREGATE_WINDOW_LIMIT`.

| Variable                     | Property                     | Default | Required | Description                                                                                                                                                      |
|------------------------------|------------------------------|---------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PIPELINE_BUDGET_TIMEOUT`    | `pipeline.budget.timeout`    | `120s`  | No       | Wall-clock ceiling per pipeline statement, and per materialize call; the relayout's writer hold waits this plus 15 s. Keep below the runner's HTTP read timeout. |
| `PIPELINE_BUDGET_MAX_MEMORY` | `pipeline.budget.max-memory` | `2GB`   | No       | Hard memory ceiling per statement per node, relayout slices (~1 GiB) included. Size it for the heaviest legitimate read, as above.                               |

### Enrichment scan

The governed incremental scan, `POST /v1/pipelines/{name}/scan`.

| Variable                        | Property                        | Default | Required | Description                                                                                                                                                                                                             |
|---------------------------------|---------------------------------|---------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ENRICHMENT_SCAN_SAFETY_LAG`    | `enrichment.scan.safety-lag`    | `5s`    | No       | Rows newer than `now64(3) − safety-lag` are excluded from **every** enrichment read — scan, group-rows and materialize — and from the drained horizon a dependent rollup waits on, which raising it holds back equally. |
| `ENRICHMENT_SCAN_DEFAULT_LIMIT` | `enrichment.scan.default-limit` | `1000`  | No       | Page size applied when a scan request omits `limit`. At least `1`.                                                                                                                                                      |
| `ENRICHMENT_SCAN_MAX_LIMIT`     | `enrichment.scan.max-limit`     | `10000` | No       | Upper bound on a requested `limit`; larger is rejected with 400. At least `1`.                                                                                                                                          |

### Enrichment group assembly

Group-input assembly, `POST /v1/pipelines/{name}/group-rows`. Bounded by bytes as well as rows,
because members can carry request/response bodies. Whichever cap trims the group first sets
`truncated` in the response.

| Variable                              | Property                              | Default | Required | Description                                                                                                                                               |
|---------------------------------------|---------------------------------------|---------|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ENRICHMENT_GROUP_FETCH_DEFAULT_ROWS` | `enrichment.group-fetch.default-rows` | `200`   | No       | Members returned when a request omits `limit`. At least `1`.                                                                                              |
| `ENRICHMENT_GROUP_FETCH_MAX_ROWS`     | `enrichment.group-fetch.max-rows`     | `2000`  | No       | Upper bound on a requested `limit` (400 if larger) and on a group pipeline's `trigger.member_select.limit` (422 when armed or previewed). At least `1`.   |
| `ENRICHMENT_GROUP_FETCH_MAX_BYTES`    | `enrichment.group-fetch.max-bytes`    | `8MB`   | No       | Serialized-size ceiling for the returned members; exceeding it trims the group and sets `truncated`.                                                      |

### Enrichment Class-1 materialize

The Class-1 path, `POST /v1/pipelines/{name}/materialize`. The window's row limit is the only throttle
on how much work one call does. A statement joining an enrichment pays a full read of that enrichment
per call — size such a pipeline against the enrichment's whole table, not against the window.

| Variable                               | Property                               | Default  | Required | Description                                                                      |
|----------------------------------------|----------------------------------------|----------|----------|----------------------------------------------------------------------------------|
| `ENRICHMENT_MATERIALIZE_DEFAULT_LIMIT` | `enrichment.materialize.default-limit` | `10000`  | No       | Window row count applied when a materialize request omits `limit`. At least `1`. |
| `ENRICHMENT_MATERIALIZE_MAX_LIMIT`     | `enrichment.materialize.max-limit`     | `100000` | No       | Upper bound on a requested `limit`; larger is rejected with 400. At least `1`.   |

### Enrichment provenance reconciliation

| Variable                                  | Property                                  | Default | Required | Description                                                                                  |
|-------------------------------------------|-------------------------------------------|---------|----------|----------------------------------------------------------------------------------------------|
| `ENRICHMENT_PROVENANCE_RECONCILE_ENABLED` | `enrichment.provenance-reconcile.enabled` | `true`  | No       | Adds the `pipeline_generation` provenance column to enrich targets that lack it, at startup. |

### Enrichment scan cursor keyring

Scan cursors are opaque and HMAC-signed. Every key in the keyring can verify a presented cursor while
`active-key-id` selects the signing key, so a secret rotates without invalidating cursors in flight:
add a key, switch `active-key-id`, retire the old one once outstanding cursors expire. A cursor
carrying an unknown or retired key id is rejected with `400 invalid_cursor`.

The two variables below reach only `enrichment.cursor.keys[0]`. Add further keys by declaring the
whole `enrichment.cursor.keys` list in an external YAML file.

| Variable                          | Property                           | Default   | Required | Description                                                                                                                       |
|-----------------------------------|------------------------------------|-----------|----------|-----------------------------------------------------------------------------------------------------------------------------------|
| `ENRICHMENT_CURSOR_ACTIVE_KEY_ID` | `enrichment.cursor.active-key-id`  | `k1`      | No       | Id of the keyring key that signs newly issued cursors. Must match an entry in the keyring, or startup fails.                      |
| `ENRICHMENT_CURSOR_KEY_ID`        | `enrichment.cursor.keys[0].id`     | `k1`      | No       | Stable id of the first keyring key, embedded in issued cursors.                                                                   |
| `ENRICHMENT_CURSOR_SECRET`        | `enrichment.cursor.keys[0].secret` | _(empty)_ | Yes      | HMAC secret for that key, from the deployment's secret store. Blank is accepted and logs `scan-cursor integrity is NOT enforced`. |

### Aggregate pipelines

An `aggregate` pipeline keeps a rollup current on its own cron: ADAS probes a bounded window of the
input, then recomputes in full every group key that window touched.

`AGGREGATE_TICK_CRON` is the granularity of every pipeline's own cron rather than a refresh interval,
so a pipeline asking for a finer cadence runs at the tick's rate. A refresh runs under the
[pipeline read budget](#pipeline-read-budget); `AGGREGATE_PREVIEW_*` bounds the dry-run sample only, and is
tuned exactly as the [query budget](#query-execution-budget). Every clock here is UTC.

| Variable                            | Property                            | Default       | Required | Description                                                                                                                                                              |
|-------------------------------------|-------------------------------------|---------------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AGGREGATE_ENABLED`                 | `aggregate.enabled`                 | `true`        | No       | Master switch for the refresh scheduler. When `false` nothing runs on a schedule; on-demand refresh and the whole API stay available.                                    |
| `AGGREGATE_TICK_CRON`               | `aggregate.tick-cron`               | `0 * * * * *` | No       | How often the scheduler looks for pipelines whose next run is due. Bounds the finest cadence any single pipeline can achieve.                                            |
| `AGGREGATE_SCHEDULER_POOL_SIZE`     | `aggregate.scheduler-pool-size`     | `1`           | No       | Threads on the aggregate tick's own scheduler, separate from `SCHEDULING_POOL_SIZE`. Raising it lets ticks overlap; refreshes within one tick stay serial. At least `1`. |
| `AGGREGATE_SAFETY_LAG`              | `aggregate.safety-lag`              | `5s`          | No       | Rows newer than `now64(3) − safety-lag` are excluded from a refresh window.                                                                                              |
| `AGGREGATE_WINDOW_LIMIT`            | `aggregate.window-limit`            | `100000`      | No       | Maximum input rows one refresh probes. A run that fills it reports `has_more` and the next tick continues from the advanced cursor. At least `1`.                        |
| `AGGREGATE_PREVIEW_ROWS`            | `aggregate.preview-rows`            | `5`           | No       | Rows returned by the dry-run sample (`POST /v1/pipelines/{name}/preview`), which writes nothing. At least `1`.                                                           |
| `AGGREGATE_PREVIEW_TIMEOUT`         | `aggregate.preview.timeout`         | `30s`         | No       | Wall-clock ceiling for the preview; exceeding it refuses it with `422 pipeline_preview_budget_exceeded`. At least `1s`.                                                  |
| `AGGREGATE_PREVIEW_MAX_MEMORY`      | `aggregate.preview.max-memory`      | `2GB`         | No       | Hard memory ceiling for the preview, per query per node. Keep below the ClickHouse container's memory limit.                                                             |
| `AGGREGATE_PREVIEW_SPILL_THRESHOLD` | `aggregate.preview.spill-threshold` | `1GB`         | No       | Aggregation-state size at which the preview spills to disk. Must be strictly below `max-memory`; `0` disables spilling.                                                  |

A pipeline reading an enrichment column carries two couplings before it is worth enabling — a refresh
window held back to what each joined enrichment has been materialized to (**alert on `state.clamp`
persisting, not on `lag_seconds`**), and re-derivation of an enrichment invalidating every rollup that
reads it (`state.rebuild_required`, which no future refresh clears). Both are specified in
`openspec/specs/aggregate-pipeline/spec.md`.

## Data sync

Reading the [blob source](#blob-source) into ClickHouse: on a daily schedule, continuously via the
poller, or on demand via the backfill. All three share the layout variables below.

### Data sync schedule

The scheduled daily reconciliation run. Each run targets `<path-prefix>/<date-path>/` for the date
`today − day-offset` (UTC), lists every blob under it, skips the blobs the ingest ledger records as
already ingested, and batch-inserts the rest. A duplicate written anyway is collapsed by the target
table's `ReplacingMergeTree` engine.

Ingestion fans out to a list of **targets**, each pairing a ClickHouse table with a mapping file. A
parsed record is offered to every target and produces a row for each whose mapping accepts it. The
mapping-file format is documented in [`column-mapping.md`](column-mapping.md).

The four `Match producer` variables describe the producer's blob layout and govern every discovery
path — this run, both poller listing modes, and the operator backfill — so those can never disagree
about which blobs belong to a date. The defaults are DIAL Core's own layout; a producer that writes
anything else must be described exactly, because a mismatch does not fail, it silently discovers
nothing.

| Variable                        | Property                        | Default                                         | Required       | Description                                                                                                                                                                      |
|---------------------------------|---------------------------------|-------------------------------------------------|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SYNC_CRON`                     | `sync.cron`                     | `0 0 1 * * *`                                   | No             | Spring cron for the daily run.                                                                                                                                                   |
| `SYNC_DAY_OFFSET`               | `sync.day-offset`               | `1`                                             | No             | Days back from today to target; `1` is yesterday.                                                                                                                                |
| `SYNC_BATCH_SIZE`               | `sync.batch-size`               | `10000`                                         | No             | Rows per INSERT batch. At least `1`.                                                                                                                                             |
| `SYNC_MAX_BATCH_BYTES`          | `sync.max-batch-bytes`          | `134217728`                                     | No             | Byte ceiling per INSERT batch, flushing on whichever bound is hit first. Budget this per target per in-flight run. Between 1 MiB and `357913941`.                                |
| `SYNC_MAX_LINE_BYTES`           | `sync.max-line-bytes`           | `67108864`                                      | No             | Max decompressed bytes of one log line; a longer line is skipped and counted as malformed. Between `1024` and `1073741824`.                                                      |
| `SYNC_PATH_PREFIX`              | `sync.path-prefix`              | `logs`                                          | Match producer | Base path within the container preceding the date directory. Surrounding `/` are ignored.                                                                                        |
| `SYNC_DATE_PATH_PATTERN`        | `sync.date-path-pattern`        | `yyyy/MM/dd`                                    | Match producer | `java.time` pattern for the date **directory**; `yyyy-MM-dd` for a single-directory producer, blank for one that writes no date directory at all. Must distinguish dates.        |
| `SYNC_FILE_NAME_PREFIX_PATTERN` | `sync.file-name-prefix-pattern` | `'date='yyyy-MM-dd`                             | Match producer | `java.time` pattern for the blob **file name's leading text**, before the embedded epoch. Blank is legal under a date directory; without one it must carry the date.             |
| `SYNC_VALIDATE_SCHEMA`          | `sync.validate-schema`          | `true`                                          | No             | Reconcile each target's mapping, and the ingest ledger table, against the live schema at startup and fail fast on drift. Set `false` for local runs without ClickHouse. |
| `SYNC_INLINE_FILES_DROP_TRUNCATED` | `sync.inline-files-drop-truncated` | `true`                                          | No             | Cut a payload body DIAL Core truncated at 4 MiB back to before the inline file the cut landed in. |
| `SYNC_INLINE_FILES_MAX_SIZE`    | `sync.inline-files-max-size`    | unset (no limit)                                | No             | Remove inline files whose stored base64 is longer than this (`256KB`, `1MB`; a bare number is bytes); `0` removes all. Negative fails startup. |
| `SYNC_REQUEST_BODY_MODE`        | `sync.request-body-mode`        | `whole`                                         | No             | `whole` or `delta`. `delta` stores a chat request's `request_body` cut to its new messages, without the system keys and tools shared with its parent. Others fail startup. |
| `SYNC_TABLE`                    | `sync.targets[0].table`         | `dial_usage_log`                                | No             | First ingest target's table — the usage log; its retention is `SYNC_USAGE_LOG_TTL_DAYS`.                                                                                         |
| `SYNC_MAPPING_FILE`             | `sync.targets[0].mapping-file`  | `classpath:mapping/dial-usage-log.yaml`         | No             | First target's mapping file — the match-all mapping.                                                                                                                             |
| `SYNC_PAYLOAD_TABLE`            | `sync.targets[1].table`         | `dial_usage_log_payload`                        | No             | Second ingest target's table — the payload enrichment holding the five verbatim-content columns; its retention is `SYNC_PAYLOAD_TTL_DAYS`.                                       |
| `SYNC_PAYLOAD_MAPPING_FILE`     | `sync.targets[1].mapping-file`  | `classpath:mapping/dial-usage-log-payload.yaml` | No             | Second target's mapping file — match-all, shadowing the usage log one row per `event_id`. Drop this target to stop capturing raw content.                                        |
| `SYNC_PAYLOAD_TTL_DAYS`         | `sync.payload.ttl-days`         | unset (keeps the live TTL)                      | No             | Payload table retention in days, applied to the live table at startup; unset leaves the live TTL (90 days as provisioned), `-1` removes it; `0`, below `-1`, or longer than the usage-log retention fails startup. Not applied (still validated, against the other only when both are set) when `CLICKHOUSE_MIGRATIONS_ENABLED=false`. |
| `SYNC_USAGE_LOG_TTL_DAYS`       | `sync.usage-log.ttl-days`       | unset (keeps the live TTL)                      | No             | `dial_usage_log` retention in days, applied to the live table at startup; unset leaves the live TTL (one year as provisioned), `-1` removes it; `0`, below `-1`, or shorter than the payload retention fails startup. Not applied (still validated, against the other only when both are set) when `CLICKHOUSE_MIGRATIONS_ENABLED=false`. |
| `SYNC_RATE_TABLE`               | `sync.targets[2].table`         | `rate_analytics`                                | No             | Third ingest target's table.                                                                                                                                                     |
| `SYNC_RATE_MAPPING_FILE`        | `sync.targets[2].mapping-file`  | `classpath:mapping/rate-analytics.yaml`         | No             | Third target's mapping file, emitting only `/rate` POST records. Drop this target to stop populating `rate_analytics`.                                                           |

⚠️ `SYNC_FILE_NAME_PREFIX_PATTERN` is the empty string for a producer that writes no file-name head —
so deployment tooling that prunes empty environment variables silently restores the default. The
startup log's band-prefix example is where that shows up.

Additional targets beyond the three shipped ones must be declared through an external YAML file, since
`sync.targets` is a list. At least one target is required.

### Near-real-time poller

Lists the active date-derived prefixes each cycle and ingests every blob finalized at or after a
moving watermark, less `overlap`. Needs **no cloud notification service and no external
infrastructure** — it works on any jclouds-supported store with the credentials already configured.
The watermark is in memory and reset to `now − restart-lookback` on startup; no cursor is persisted.

`band` mode (the default) narrows each listing to a ~16-minute slice server-side, via the fixed-width
epoch embedded in the blob key. `day` mode lists the whole active day and filters in memory — the
fallback for a producer whose file names carry no embedded timestamp at all, which no
`SYNC_FILE_NAME_PREFIX_PATTERN` can express.

| Variable                  | Property                  | Default       | Required | Description                                                                                                                                                                              |
|---------------------------|---------------------------|---------------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POLL_ENABLED`            | `poll.enabled`            | `true`        | No       | Master switch; `true` also when the property is absent. When off, ingestion proceeds only via the scheduled run.                                                                         |
| `POLL_CRON`               | `poll.poll-cron`          | `0 * * * * *` | No       | Spring cron controlling how often a poll cycle runs.                                                                                                                                     |
| `POLL_OVERLAP`            | `poll.overlap`            | `5m`          | No       | How far below the watermark to keep re-considering blobs, absorbing out-of-order writes and clock skew.                                                                                  |
| `POLL_RESTART_LOOKBACK`   | `poll.restart-lookback`   | `1h`          | No       | How far back the watermark is set at startup, so a restart re-scans a bounded recent window.                                                                                             |
| `POLL_PREVIOUS_DAY_GRACE` | `poll.previous-day-grace` | `1h`          | No       | How long after UTC midnight to also scan the previous day's prefix. `day` mode only; ignored in `band` mode.                                                                             |
| `POLL_FINALIZATION_LAG`   | `poll.finalization-lag`   | `15m`         | No       | `band` mode only: how far below the cutoff the listed slice reaches back. **Set at or above the longest request/stream duration**; blobs lagging further are recovered by the daily run. |
| `POLL_LISTING_MODE`       | `poll.listing-mode`       | `band`        | No       | `band` narrows each listing to a recent time slice; `day` lists the whole active day and makes no assumption about the key format.                                                       |

### Data-sync backfill

The operator-triggered one-time backfill of a bounded UTC date range, `POST /v1/data-sync/backfill`.
It runs on a dedicated single thread, so a backfill in flight neither blocks nor is blocked by the
scheduled jobs.

| Variable            | Property            | Default | Required | Description                                                                                                |
|---------------------|---------------------|---------|----------|------------------------------------------------------------------------------------------------------------|
| `BACKFILL_ENABLED`  | `backfill.enabled`  | `true`  | No       | Master switch for the backfill endpoints. When `false` a trigger is rejected with `503 backfill_disabled`. |
| `BACKFILL_MAX_DAYS` | `backfill.max-days` | `366`   | No       | Maximum inclusive span in days one request may cover; a longer range is rejected with 400.                 |

## Security

### Authentication

In `oidc` mode every `/v1` endpoint requires a credential — an OIDC `Bearer` token from a configured
[identity provider](#identity-providers), or a DIAL [`Api-Key` header](#api-key-authentication) when
that is enabled; `Bearer` takes precedence when both are sent. The operational paths on the management port
(`/actuator/health` with its liveness and readiness groups, `/actuator/info`, `/actuator/prometheus`) stay public; the springdoc UI and OpenAPI
document are public only with [`DISABLE_SWAGGER_AUTHORIZATION=true`](#api-documentation-springdoc--swagger-ui).

In `none` mode — the default — all endpoints are open and **every authorization check fails open**,
including sensitive-column masking and the unauthenticated Flight port. Deploy `none` only on a
trusted, non-internet-exposed network segment.

Provider role names are mapped onto **application privileges** — sixteen atomic values named
`<OBJECT>_<VERB>` — and the raw names are retained for per-table grant matching. A `roles-mapping` value
may name a privilege directly or one of four **bundles** that expand to a privilege set; the two forms may
be mixed in one list. A value that names neither **fails startup**.

| Bundle | Declares |
|---|---|
| `FULL_ADMIN` | every privilege |
| `READ_ONLY_ADMIN` | `TABLE_READ`, `REGISTRY_READ`, `AUDIT_READ`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` |
| `READ_ONLY_ANALYTIC` | `TABLE_READ`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` |
| `READ_ONLY_SENSITIVE_ANALYTIC` | `TABLE_READ`, `TABLE_SENSITIVE`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` |

`READ_ONLY_SENSITIVE_ANALYTIC` is what makes reading a `sensitive` column reachable without
administrative authority. `TABLE_SENSITIVE` is a clearance, not a verb: it confers nothing alone and
extends `TABLE_GET`, `TABLE_READ` and `TABLE_MODIFY` to a table's sensitive columns, so a mapping names it
beside the verb it qualifies.

`FULL_ADMIN` carries what the same-named application role carried before. A mapping carried over from an
earlier release needs these edits:

- a value naming `TABLE_READ_SENSITIVE` becomes `TABLE_READ` plus `TABLE_SENSITIVE` — the old name no longer
  exists and fails startup;
- a `READ_ONLY_ADMIN` role that reads `GET /v1/tables/{name}/access` must also name `TABLE_GRANT`, which
  the endpoint requires;
- a role mapped to `READ_ONLY_ADMIN`, `READ_ONLY_ANALYTIC` or bare verbs that audits or manages sensitivity
  must also name `TABLE_SENSITIVE`: without the clearance, `GET /v1/tables` and `GET /v1/tables/{name}`
  omit sensitive columns, and a schema change can neither see them nor set a `sensitive` flag.

See the `authentication` and `table-access-control` capabilities for why the grant lists are not a catalog
read and why sensitivity is a clearance.

Six privileges are **table-scoped** — `TABLE_LIST`, `TABLE_GET`, `TABLE_READ`, `TABLE_SENSITIVE`,
`TABLE_WRITE`, `TABLE_MODIFY` — and may be held globally or granted on a single table at
`PUT /v1/tables/{name}/access`, with effective access the union of the two. `TABLE_LIST` is the one
with no per-table grant: held globally it lists every table, and a `get` grant already lists its own. A
valid token mapping to no privilege at all is still authenticated and may be authorized for one table by a
matching grant, which is the intended shape for a BI tool or an exporter.

The full endpoint-by-endpoint authorization model is in `openspec/specs/authentication/spec.md`, and
what an operator has to do to *narrow* a team's access — a mapping edit before any grant, because
effective access is a union — is in `openspec/specs/table-access-control/spec.md`.

| Variable                 | Property                            | Default                                                 | Required   | Description                                                                                                                                                                                                                                        |
|--------------------------|-------------------------------------|---------------------------------------------------------|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SECURITY_MODE`          | `security.mode`                     | `none`                                                  | If exposed | `oidc` enforces authentication and privilege-based authorization; `none` disables all of it. Matched case-insensitively; any other value fails startup. |
| `SECURITY_REQUIRE_EMAIL` | `security.require-email`            | `false`                                                 | No         | Reject a token with no resolvable email claim. `oidc` only.                                                                                                                                                                                        |
| `SECURITY_ROLES_MAPPING` | `security.defaults.roles-mapping`   | `{"ConfigAdmin":["FULL_ADMIN"],"admin":["FULL_ADMIN"]}` | No         | Default JSON mapping of provider role names to privilege or bundle names, merged under each provider's own mapping. An unknown value fails startup. `oidc` only.                                                                                    |
| `SECURITY_EMAIL_CLAIM`   | `security.defaults.email-claim`     | `unique_name`                                           | No         | Default claim to read the caller's email from when a provider sets none. `oidc` only.                                                                                                                                                              |
| `SECURITY_USER_CLAIM`    | `security.defaults.principal-claim` | `oid`                                                   | No         | Default claim used as the authenticated principal name when a provider sets none. Note the name mismatch between variable and property. `oidc` only.                                                                                               |

### Identity providers

Configured under the top-level `providers.<name>` map, where `<name>` is an arbitrary local label —
the same layout as ai-dial-admin-backend. At least one usable provider is required when
`SECURITY_MODE=oidc`, or startup fails. Both token shapes are accepted, and may be configured side by
side:

- **JWT providers** — set `jwk-set-uri`. ADAS reads the token's `iss`, selects the matching provider,
  and validates signature, issuer, audience and expiry locally. Needs `issuer` and `jwk-set-uri`.
- **Opaque providers** — set `user-info-endpoint`. For tokens that are not JWTs (e.g. Google access
  tokens), ADAS introspects by calling the userinfo endpoint. An opaque token carries no issuer, so
  each opaque provider is tried until one accepts it.

A provider with neither is ignored.

Because the providers form a map keyed by an arbitrary name, they have **no fixed environment-variable
names** — hence property paths below rather than a variable column. Prefer putting the `providers:`
block in a file referenced by `SPRING_CONFIG_ADDITIONAL_LOCATION`, which keeps lists and the
`roles-mapping` JSON readable.

They do bind from environment variables via relaxed binding, with two traps: the uppercase form
**drops** the dashes inside a property name rather than replacing them with underscores
(`PROVIDERS_AZURE_JWKSETURI`, not `PROVIDERS_AZURE_JWK_SET_URI`, which binds to the wrong path and is
silently ignored), and the provider name must then be a single lowercase word. List properties take a
comma-separated value; `roles-mapping` takes a JSON string.

| Property                              | Example                               | Required         | Description                                                                                                                                                                                                    |
|---------------------------------------|---------------------------------------|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `providers.<name>.issuer`             | `providers.azure.issuer`              | JWT providers    | Expected `iss` claim (a URL), or a tenant id when `aliases` is set.                                                                                                                                            |
| `providers.<name>.jwk-set-uri`        | `providers.azure.jwk-set-uri`         | JWT providers    | JWKS endpoint for the provider's signing keys. Marks the provider as a JWT provider.                                                                                                                           |
| `providers.<name>.user-info-endpoint` | `providers.google.user-info-endpoint` | Opaque providers | OIDC userinfo endpoint introspected for opaque tokens. Marks the provider as an opaque provider.                                                                                                               |
| `providers.<name>.audiences`          | `providers.azure.audiences`           | No               | Accepted `aud` values; an `api://`-prefixed variant of each is also accepted. JWT providers.                                                                                                                   |
| `providers.<name>.role-claims`        | `providers.azure.role-claims`         | Yes              | Dotted claim paths to read provider role names from (`roles`, `resource_access.roles`). For opaque providers these read the userinfo response. A single `fn:`-prefixed entry selects a role converter instead. |
| `providers.<name>.email-claims`       | `providers.azure.email-claims`        | No               | Claim names to read the caller's email from, tried in order; falls back to `SECURITY_EMAIL_CLAIM`.                                                                                                             |
| `providers.<name>.principal-claim`    | `providers.azure.principal-claim`     | No               | Claim used as the principal name; falls back to `SECURITY_USER_CLAIM`.                                                                                                                                         |
| `providers.<name>.roles-mapping`      | `providers.azure.roles-mapping`       | No               | JSON mapping of this provider's role names to privilege or bundle names, merged over `SECURITY_ROLES_MAPPING`.                                                                                                 |
| `providers.<name>.aliases`            | `providers.azure.aliases`             | No               | Host aliases used to expand Azure v1/v2 issuer URLs from a tenant id in `issuer`. JWT providers.                                                                                                               |

An opaque provider whose userinfo returns no role information — plain Google userinfo, for instance —
yields no privilege, so such callers reach only what a per-table grant naming one of their raw provider
roles authorizes, unless a default mapping applies or a role converter is selected.

#### Role converters for opaque providers

A provider's `role-claims` may instead name a **role converter**: a single `fn:`-prefixed entry
standing alone (mixing it with claim paths is not supported). The converter derives provider role
names from another source using the caller's own token, and `roles-mapping` then maps them exactly as
claim-derived names. Naming a converter that does not exist fails startup.

| Converter                     | Applies to                | Derives roles from                                                                                                                                                                 |
|-------------------------------|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `fn:getGoogleWorkspaceGroups` | Google / Google Workspace | The caller's **direct** Google Workspace group memberships, read from the Cloud Identity API with the caller's own token. Each group's email address becomes a provider role name. |

```yaml
providers:
  google:
    user-info-endpoint: https://openidconnect.googleapis.com/v1/userinfo
    principal-claim: sub
    email-claims: [email]
    role-claims: ["fn:getGoogleWorkspaceGroups"]
    roles-mapping: '{"analytics-admins@example.com":["FULL_ADMIN"],"analytics-viewers@example.com":["READ_ONLY_ANALYTIC"],"pii-readers@example.com":["READ_ONLY_SENSITIVE_ANALYTIC"]}'
```

`fn:getGoogleWorkspaceGroups` needs the Cloud Identity API enabled for the workspace, the caller's
email resolvable from `email-claims` or `SECURITY_EMAIL_CLAIM`, and a token carrying a scope that
permits group-membership reads (e.g.
`https://www.googleapis.com/auth/cloud-identity.groups.readonly`) — otherwise introspection fails with
401. Only **direct** memberships resolve (nesting grants nothing) and only the first page is read (a
paginated response is logged as a warning), so map roles onto a few dedicated groups rather than a
caller's full group list. There is no membership cache: each request with an opaque Google token
makes one userinfo call plus one Cloud Identity call.

### API-key authentication

Layered onto `oidc` mode: callers may present a DIAL `Api-Key` header instead of a `Bearer` token,
validated against DIAL Core's `/v1/user/info`. Project-key responses map through
`API_KEY_ROLES_MAPPING`; JWT-rooted per-request keys map through `SECURITY_ROLES_MAPPING` and the
`security.defaults.*` claim names. Successful introspections are cached; rejected keys are not.

Inactive unless `SECURITY_MODE=oidc`, and that mode still requires at least one identity provider.

| Variable                     | Property                              | Default   | Required             | Description                                                                                                                          |
|------------------------------|---------------------------------------|-----------|----------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `API_KEY_ENABLED`            | `security.api-key.enabled`            | `false`   | No                   | Master switch. Only takes effect in `oidc` mode.                                                                                     |
| `API_KEY_CORE_URL`           | `security.api-key.core-url`           | _(empty)_ | If `API_KEY_ENABLED` | DIAL Core base URL, e.g. `http://dial-core:8080`. Blank fails startup when enabled.                                                  |
| `API_KEY_ROLES_MAPPING`      | `security.api-key.roles-mapping`      | _(empty)_ | No                   | JSON mapping of Core project-key role names to privileges or bundles. An unknown token fails startup; an empty mapping does not — a deployment may authenticate project keys and authorize them for nothing beyond their per-table grants. |
| `API_KEY_CACHE_TTL_SECONDS`  | `security.api-key.cache-ttl-seconds`  | `60`      | No                   | TTL for cached successful introspections. Also bounds how long a key revoked in Core keeps working.                                  |
| `API_KEY_CACHE_MAX_SIZE`     | `security.api-key.cache-max-size`     | `10000`   | No                   | Maximum cached introspection entries.                                                                                                |
| `API_KEY_REQUEST_TIMEOUT_MS` | `security.api-key.request-timeout-ms` | `3000`    | No                   | Connect and read timeout for the Core introspection call.                                                                            |
| `API_KEY_STARTUP_PROBE`      | `security.api-key.startup-probe`      | `true`    | No                   | Probe Core reachability at startup and fail fast if unreachable. Set `false` to skip.                                                |

## Exposing endpoints from a cluster

Route **only the read-only query surface** to external consumers and keep the mutating, operational
and internal endpoints cluster-internal; treat the ingress path list as the exposure allowlist. Expose
ADAS only after setting `SECURITY_MODE=oidc`.

For the endpoint-by-endpoint allowlist and a concrete Kubernetes/Traefik walkthrough — ingress, the
Flight SQL gRPC/h2c route, egress NetworkPolicy, verification — see
[`kubernetes-exposure.md`](kubernetes-exposure.md).

## Release and deployment order

ADAS and the Enrichment Runner ship as a pair. Deploy both halves in one window, with the runner
scaled to zero for its whole length:

1. **Scale the runner to 0** and wait for the pod to be gone. A rolling update is not enough: it
   overlaps an old and a new runner pod against a half-updated ADAS.
2. **Deploy ADAS.**
3. **Deploy the runner** to the matching build.
4. **Scale the runner back to 1.** Its readiness stays DOWN until the first pipeline sync succeeds, so
   a ready pod is itself the confirmation that the pair matches.

Nothing is lost while the runner is at 0, but Class-1 materialize positions stop advancing, so
aggregate refreshes clamp on enrichment freshness for a cadence after it is back — keep the window
short.

As a backstop, each side carries a build constant (`RunnerContract.VERSION` here,
`AdasContract.VERSION` there) that must match, and a caller on the wrong side of it is refused on the
data plane, so a skew can never write a wrong label. A skew is refused rather than absorbed in both
directions: the runner holds a work item that comes due during one — rescheduled, no attempt consumed,
no model call made — and ADAS answers 409 before any lookup, so the pair stalls and self-heals once
both sides match.

That is a safety net, not a substitute for the order above. Skipping step 1 is not a clean skew: both
ADAS builds serve concurrently during a rolling update, so a running old runner can read a matching
echo from an outgoing pod, start work, pay for a model call, and have its write refused by a new one.
That work is held and written after the upgrade rather than lost, but the write is deferred, the
compatibility signal flaps for the length of the roll, and Class-1 positions stay frozen throughout.

## Related documents

- [`clickhouse-server.md`](clickhouse-server.md) — supported ClickHouse versions, the encryption key
  the body columns need, upgrade constraints, and the grants each credential needs.
- [`aws-s3-access.md`](aws-s3-access.md) — running `data-sync` against an S3 bucket: endpoint and
  region, workload identity, and the IAM the bucket needs.
- [`column-mapping.md`](column-mapping.md) — authoring a `data-sync` mapping file.
- [`kubernetes-exposure.md`](kubernetes-exposure.md) — exposing ADAS from a cluster.
- [`sql-reference.md`](sql-reference.md) — the SQL surface `execute-sql` and Flight SQL accept.
- `openspec/specs/<capability>/spec.md` — what each subsystem does with these values.
