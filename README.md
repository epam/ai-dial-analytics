# AI DIAL Analytics

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.x-brightgreen)

This repository hosts the Analytics Data Access Service (ADAS), the runtime gateway between
analytics consumers and the underlying data stores — primarily ClickHouse.

This repository is a runnable Spring Boot application with configuration loading, a stable JSON
error contract, logging, health and metrics endpoints, and container packaging. Its functional
surface is the structured query DSL at `POST /v1/queries/execute` with schema discovery under
`/v1/queries` (over ClickHouse), the `data-sync` ingest path described below, the PostgreSQL-backed
table-management catalog under `/v1/tables`, and aggregate pipelines under `/v1/pipelines` that persist
rollups on a schedule. Functionality is added incrementally as spec-driven
OpenSpec changes.

## Tech stack

- Java 25, Spring Boot 4 (Spring Web MVC), Gradle (wrapper 9.6.0)
- Log4j2 logging, Micrometer + Prometheus, Spring Boot Actuator
- OpenTelemetry OTLP export of metrics/traces/logs, off by default — enable with
  `OTEL_EXPORT_ENABLED` (see [`docs/configuration.md`](docs/configuration.md))
- springdoc-openapi (Swagger UI), MapStruct, Lombok
- PostgreSQL table-management catalog via Spring Data JPA + Flyway (drives the ClickHouse query path)
- Checkstyle (Google Java Style, 180-char lines), `-Werror`

## Prerequisites

- JDK 25 (the Gradle toolchain will resolve one if configured)
- Docker — **required to run the test suite**: the integration tests start a real ClickHouse and a
  real PostgreSQL via [Testcontainers](https://testcontainers.com/), so `./gradlew clean build` and
  `./gradlew test` need a running Docker daemon. Also used for containerized runs.
- GitHub Packages credentials — the build resolves EPAM's `jclouds` fork from
  `https://maven.pkg.github.com/epam/jclouds`, which needs a token even for public packages. Export
  `GPR_USERNAME` (your GitHub username) and `GPR_PASSWORD` (a personal access token with
  `read:packages`), or set `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`.

## Build & run

```bash
./gradlew clean build            # compile, checkstyle, tests
./gradlew bootRun                # run locally
./gradlew checkstyleMain checkstyleTest
./gradlew test                   # run the test suite
```

## Quick start

A local ClickHouse + PostgreSQL, ADAS against them, and synthetic data to query:

```bash
docker compose -f local_env/docker-compose.yml up -d --wait   # ClickHouse + PostgreSQL
POLL_ENABLED=false ./gradlew bootRun                          # no blob store locally; migrations create the tables
python local_env/seed/seed_dial_usage_log.py                  # ~600 synthetic usage rows (second terminal)

curl -s localhost:5000/actuator/health
curl -s localhost:5000/v1/queries/execute-sql -H 'Content-Type: application/json' \
  -d '{"sql": "SELECT deployment, count(*) AS calls FROM dial_usage_log GROUP BY deployment"}'
```

The OpenAPI document is served at `/v3/api-docs` and the Swagger UI at `/swagger-ui.html`. In `oidc` mode both
need a credential unless `DISABLE_SWAGGER_AUTHORIZATION=true`; see [configuration](docs/configuration.md#api-documentation-springdoc--swagger-ui).

**Security:** with no `SECURITY_MODE` set, ADAS runs with `none`, so every endpoint is open. That is
meant for local development. Any shared deployment must set `SECURITY_MODE=oidc` and configure identity
providers; see [`docs/configuration.md`](docs/configuration.md).

## Recommended catalog

A new installation should import the recommended catalog bundle,
[`docs/recommended-catalog/adas-catalog.json`](docs/recommended-catalog/adas-catalog.json). It holds the user tables
and pipelines that other components depend on:

- **Sessions tab.** The analytics UI always ships a Sessions tab. It reads the `sessions` and
  `usage_client_identity` tables, so without the bundle the tab stays empty.
- **Evaluation framework.** Parts of the evaluation framework read the `usage_request_baggage` table, which the
  bundle creates and fills.

The bundle carries definitions only: no rows and no grants. See
[`docs/recommended-catalog/`](docs/recommended-catalog/README.md) for its contents and the import steps.

## Configuration

Configuration is supplied via environment variables (see `src/main/resources/application.yml`).
The ClickHouse and PostgreSQL defaults work against `local_env/docker-compose.yml`, so a local run
needs neither set. The blob source has no local equivalent: without `BLOB_*` pointing at a real
store nothing is ingested, and the poller logs a failed cycle every minute until it is configured or
switched off with `POLL_ENABLED=false`.

The full reference — every variable, its property path, default, and whether a real deployment must
set it — is [`docs/configuration.md`](docs/configuration.md). Server-side prerequisites and the
ClickHouse grants are in [`docs/clickhouse-server.md`](docs/clickhouse-server.md).

## Data sync (DIAL usage logs → ClickHouse)

ADAS ingests DIAL usage logs from blob storage into ClickHouse so they can be queried later. Blob
access is provider-agnostic via jclouds: `azureblob` and `aws-s3` are supported (see
[`docs/aws-s3-access.md`](docs/aws-s3-access.md)). There are two ingestion paths, both unattended;
duplicate records are collapsed by the target table's `ReplacingMergeTree` engine on the primary key
`trace_id` + `core_span_id` (not by skipping files):

- **Near-real-time (poller)** — the primary path, and fully provider-agnostic. On a short cron
  (`POLL_CRON`, default every minute) the service lists the active date-derived prefix(es) and ingests
  every blob whose **last-modified time** is at or after a moving watermark (less `POLL_OVERLAP`). It
  uses only the jclouds blob-listing abstraction — **no cloud-specific notification service and no
  external infrastructure to provision** — so it works on any supported store. The watermark is held
  in memory (reset to `now − POLL_RESTART_LOOKBACK` on startup); correctness rests on engine dedup, so
  the overlap and restart re-scans cannot create duplicates. **Enabled by default** (`POLL_ENABLED=true`).
- **Daily reconciliation (scheduled sweep)** — a safety net. Runs on a daily cron (`SYNC_CRON`,
  default 01:00) and lists `<SYNC_PATH_PREFIX>/<date-path>/` for the date
  `today − SYNC_DAY_OFFSET` (UTC, default yesterday) — the date directory follows
  `SYNC_DATE_PATH_PATTERN` (`yyyy/MM/dd` by default; see
  [docs/configuration.md](docs/configuration.md)), or is absent entirely when that pattern is blank, in
  which case the prefix runs into the file name (`logs/data=2026-06-24`) and the file-name head carries
  the date — re-ingesting anything the poller missed during downtime longer than its lookback window. A
  run never overlaps another run of itself. Past date ranges can be re-ingested on demand through
  `POST /v1/data-sync/backfill` (see the [data-sync-backfill spec](openspec/specs/data-sync-backfill/spec.md)).
- **Idempotent** — every run re-parses and re-inserts; the table's `ReplacingMergeTree` engine
  collapses records that share the `(trace_id, core_span_id)` key, so the poll overlap, daily re-runs,
  restarts, and overlap between the two paths never leave duplicate rows.
  Dedup on disk is eventual (on merge); ADAS reads with `FINAL` by default (`FORCE_FINAL_QUERIES=true`),
  so query results see each record once.
  `_source_file` is retained as ingest provenance, not as the dedup key. Records missing the dedup
  key are dropped + counted.
- **Fault-tolerant parsing** — an NDJSON line that cannot be parsed is logged, skipped and counted
  (`sync_rows_skipped`); it never aborts the remaining lines in the file, or the remaining files in
  the run.
- **Allowlist capture** — only columns declared in the mapping are written; undeclared fields are
  never persisted. ⚠️ The shipped mappings **do** declare three heavy payload columns —
  `request_body`, `response_body`, and the producer's merged `assembled_response` — so raw prompt
  content, raw response content, and the assembled answer **are** all stored, in the
  `dial_usage_log_payload` table under a 90-day retention. The merged response is captured in addition
  to the raw one, because on a streamed call the raw body is a chunk transcript and only the merged
  form is readable as an answer. Inline files inside the bodies are trimmed per
  `SYNC_INLINE_FILES_MODE` (see [column mapping](docs/column-mapping.md)). All three are seeded `sensitive` (readable only with `TABLE_READ` plus the `TABLE_SENSITIVE` clearance) and `heavy`
  (never in a wildcard); gate them with column-level access controls + a retention/TTL policy (the
  service applies no masking), and remove a column from the mapping to stop capturing it. See
  [heavy columns](docs/heavy-columns.md) and [column mapping](docs/column-mapping.md).
- **Configurable mapping** — the target table's columns, source paths, kinds, transforms, and
  defaults are declared in an external YAML mapping file (`SYNC_MAPPING_FILE`, default
  `classpath:mapping/dial-usage-log.yaml`), not hard-coded. At startup the service reconciles the
  mapping against the live table (`DESCRIBE TABLE`) and refuses to start on drift
  (`SYNC_VALIDATE_SCHEMA=true`, default). See [`docs/column-mapping.md`](docs/column-mapping.md).
- **Write isolation** — inserts use a dedicated `clickhouse.writer.*` credential; the read/query
  path stays strictly read-only.
- **Table** — the target table `dial_usage_log` is a `ReplacingMergeTree` with per-column codecs,
  bloom-filter skip indexes on the point-lookup keys, and a 1-year TTL. It and the companion
  `dial_usage_log_payload` table are provisioned by the Flyway migrations under
  [`src/main/resources/db/migration/clickhouse`](src/main/resources/db/migration/clickhouse); with
  migrations disabled, apply the same set in order. The payload body columns are AES-256 encrypted at
  rest, which **requires** ClickHouse server-side encryption keys (`encryption_codecs`) to be configured.
  See *Schema migrations* below.

## Schema migrations (ClickHouse)

On startup, `ClickHouseMigrationRunner` runs the Flyway migrations under
`src/main/resources/db/migration/clickhouse` against ClickHouse (over the ClickHouse JDBC driver) to provision
the schema (e.g. `dial_usage_log`).

- **Enable/disable** — `CLICKHOUSE_MIGRATIONS_ENABLED` (default `true`). Set `false` when the schema
  is provisioned out of band; migration failures abort startup.
- **Supported server** — ClickHouse 25.3 LTS or newer, verified through 26.8 LTS. Moving between
  versions in that range needs no ADAS configuration change, but upgrading past 25.10 cannot be rolled
  back once the server has written parts unless the `compatibility` profile is set first — see
  [`docs/clickhouse-server.md`](docs/clickhouse-server.md).
- **Credential** — uses the `clickhouse.writer.*` credential, which must additionally hold
  `CREATE TABLE` (DDL) grants (Flyway also creates its `flyway_schema_history` table).
- **How** — migrations run programmatically (not via Spring Boot's Flyway auto-configuration, which
  requires `spring-jdbc` that ADAS does not use). `flyway-core` is BOM-managed (11.x);
  `flyway-database-clickhouse` (community SPI module) and `clickhouse-jdbc` are added explicitly.
- **At-rest encryption** — the migration creates the body columns with the `AES_256_GCM_SIV` codec,
  which requires a ClickHouse server-side encryption key (`encryption_codecs` in `config.d`); without it
  the migration's `CREATE TABLE` fails. The integration tests and `local_env` Docker Compose mount a
  throwaway local key; real deployments supply one from their secret store.
- **Payload split (V10, V11)** — `dial_usage_log_payload` holds bodies, claims and headers under a
  90-day `TTL`, shorter than `dial_usage_log`'s one year. `V11` drops those five columns from
  `dial_usage_log`, which deletes the content of rows ingested before the split — content the catalog
  had already stopped publishing from that table. There is no backfill, so copy it out first if you
  want it. See
  [docs/heavy-columns.md](docs/heavy-columns.md#upgrading-an-existing-deployment) for the full
  picture.
- **Observability** — logs + Micrometer metrics (`sync_runs_total`, `sync_files_processed`,
  `sync_rows_inserted`, `sync_rows_skipped`, `sync_rows_dropped`, `sync_duration`,
  `sync_last_success_timestamp`, `sync_blobs_band_shape_mismatch_total` (listed keys whose file name the
  configured layout cannot match, so `band` polling could not discover them — see
  [docs/configuration.md](docs/configuration.md)); plus `poll_cycles_total{outcome}`, `poll_blobs_discovered_total`,
  `poll_blobs_ingested_total`, `poll_blobs_failed_total`, `poll_duration`, and the
  `poll_watermark_lag_seconds` gauge for the near-real-time path) on `/actuator/prometheus`. The shared
  write path adds `clickhouse_inserts_total{table}` (batch INSERTs issued — each creates one part in every
  partition it touches, so this is the service's own measure of the part pressure it imposes) and
  `clickhouse_insert_failures_total{table,reason}` (`reason=storage_full` when ClickHouse refused the
  write for lack of disk space); both are tagged by target table rather than by ingest path.

Poller settings (`poll.*` / `POLL_*`) are documented in
[`docs/configuration.md`](docs/configuration.md#near-real-time-poller). The poller needs **no external
infrastructure** — it works wherever blob access already works, with no provisioning prerequisite.

## Table catalog (PostgreSQL)

ADAS stores the catalog of ClickHouse tables and their column mappings in PostgreSQL, exposed as CRUD
under `/v1/tables`. Creating a table records its identity as `PENDING`; `POST /v1/tables/{name}/schema`
defines its physical schema and materializes it in ClickHouse, and `PATCH /v1/tables/{name}/schema`
evolves its columns. The catalog is the source of truth for which
tables are queryable and how their columns are exposed — it **drives** the analytical query path under
`/v1/queries` (a source table plus its enrichments is flattened into one queryable entity).

- **Persistence** — Spring Data JPA over a JDBC `DataSource`. The JDBC URL is composed from
  `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DATABASE` (plus optional `POSTGRES_OPS` query string),
  with `POSTGRES_DATASOURCE_USERNAME` / `POSTGRES_DATASOURCE_PASSWORD`.
- **Schema** — the catalog tables are provisioned on startup by Boot's Flyway auto-configuration from
  `src/main/resources/db/migration/postgres`, scoped to that PostgreSQL-only location so it never runs
  the ClickHouse migrations. Toggle with `POSTGRES_MIGRATIONS_ENABLED` (default `true`).
- **Materialization** — `POST /v1/tables/{name}/schema` issues `CREATE TABLE` in ClickHouse using the
  **writer** credential (`CLICKHOUSE_WRITER_*`) (status `active`, or `failed` if ClickHouse rejects it); `PATCH .../schema` issues `ALTER TABLE` (add/drop/rename column); deleting a table issues
  `DROP TABLE`. The read/query path stays strictly read-only.
- **Write discipline** — a source declares `write` at create: `append` (the default) keeps every row
  (`MergeTree`), while `upsert_by_key` collapses to the latest row per `ordering_key`
  (`ReplacingMergeTree`), making that key the table's unique key — the shape an aggregate pipeline's
  target needs. It is immutable, because it selects the engine, which is fixed at materialization. An
  enrichment always collapses on its grain key, so the member is rejected for one.
- **Limits** — lightweight catalog guardrails (`tables.limits.*`): max columns per table, max tables,
  and max write-batch rows. See [`docs/configuration.md`](docs/configuration.md#table-catalog-limits).
- **System tables** — a definition may be flagged `system` (owned/managed by ADAS itself). The three
  ingest-fed tables — the sources `dial_usage_log` and `rate_analytics`, and the payload enrichment
  `dial_usage_log_payload` — are seeded `system`. A system table is
  fully readable and queryable, but every modifying operation (update, `PATCH .../schema`, delete, and
  row write) is rejected with `409`; the flag is server-controlled and never settable via the API.

- **Audit trail** — every change to a table definition, a column, a pipeline (either kind), or a
  saved query is preserved as a full historical snapshot attributed to the identity that made it.
  `POST /v1/activities` returns the paged, filtered feed of what changed (one entry per changed entity,
  children linked to the act that caused them), `GET /v1/activities/{id}` returns one entry, and each
  covered resource has its own point-in-time read: `GET /v1/tables/{name}/revision/{revision}`,
  `GET /v1/pipelines/{name}/revision/{revision}`, and `GET /v1/saved-queries/{id}/revision/{revision}`,
  each returning that resource as it stood at a past revision in the same shape as its live read. The
  trail starts at deployment — analytics row writes are deliberately outside it. A pipeline's snapshot
  now carries its whole declaration, the prompt included, because an enrichment's definition is
  authored on the pipeline rather than on a separate object. Each read names
  the privilege it requires: the feed and one entry need `AUDIT_READ`, a table snapshot needs
  `TABLE_GET` on that table, a pipeline snapshot `REGISTRY_READ`, and a saved query's follows its
  owner/`common`-scope visibility rule resolved against its current state — a deleted saved query's
  history is visible to `SAVED_QUERY_PUBLISH` holders only. Nothing can write to the trail itself.

See [`docs/configuration.md`](docs/configuration.md#postgresql-table-catalog) for the full reference.
Minimal Python examples live in [`local_env/python-examples/tables/`](local_env/python-examples/tables/):
`create_table.py` creates and materializes a custom source, `write_custom_table.py` writes rows into
it (`POST /v1/tables/{name}/rows`), and the queries that read them back are documented alongside.

## Aggregate pipelines (rollups)

An **aggregate pipeline** persists a rollup: a declared `group_by` + `measures` over one source table,
materialized into a target `source` table written `upsert_by_key`. Registered under `/v1/pipelines`
(admin-only), each pipeline runs on its own cron, and the rollup is then queryable through
`/v1/queries` like any other source — a rollup is not a special table kind.

- **Lifecycle** — `POST /v1/pipelines` registers a pipeline **disabled**, and needs only `name`, `kind`
  and `target`: a disabled pipeline is a draft, authored by repeated `PATCH` (which replaces the
  declaration, keeping `target` when omitted), and the declaration gate runs when it is armed or
  previewed rather than when it is stored. So `GET /v1/pipelines` also lists half-built drafts that will
  never run until armed; completeness is computed, not stored, so there is no filter for it and
  `enabled=false` is the closest proxy. `POST /v1/pipelines/{name}/preview` is the dry run: it refuses what
  arming would refuse, and otherwise returns a bounded, newest-first sample of the declaration's output over
  the whole input without writing — typed columns, the sample's own `SELECT`, the number of rows the target
  will hold, and coded notes (an enrichment holding it back or behind, a value the target cannot hold). It
  echoes none of the declaration, whose derived values `GET /v1/pipelines/{name}` serves expanded; `PATCH`
  then enables it. (The same endpoint serves an `enrich` pipeline in the same shape, sampling the input the
  transform would receive, or a Class-1 pipeline's derived values.) `POST .../refresh` runs
  one window on demand — useful to drain a backlog faster than the schedule would, repeating while
  `has_more` is true. A disabled pipeline never writes (on-demand refresh is refused with 409), so
  `preview` is always what sits between declaring a rollup and letting it write. Re-declaring a disabled
  pipeline resets its scan position, so the next run rebuilds the rollup under the new declaration
  instead of leaving earlier keys computed the old way.
- **How a refresh stays correct** — the target replaces rows by key, so each run recomputes **in full**
  every group key its window touched, rather than aggregating the window alone. That makes a run
  idempotent: a retry, or two replicas ticking at once, writes the same values and cannot double-count,
  and a late-arriving row corrects its bucket instead of adding a second one.
- **Reads the input entity, not just the input table** — a declaration may name a column of one of its
  input's enrichments, by the same qualified flat name the query surface uses
  (`"usage_client_identity.client_session_id"`), in a group key, a measure, the `filter`, or a measure's
  own `where`; each statement of a refresh joins only the enrichments its own clauses reference. The join
  is outer, so a group key or measure may only read an enrichment column declared `nullable`, a group key
  over one must declare `as`, and a measure over one needs a nullable target column. **A refresh waits for
  its enrichments:** its window stops at the position ADAS has materialized each joined enrichment through,
  so a source row is never aggregated before its enrichment row exists — the honest cost being that a
  rollup is only as fresh as the slowest enrichment it reads. An enrichment written by a group-trigger
  pipeline, or by a pipeline ADAS does not execute itself, has no settled position and is refused; one whose pipeline
  reads a different table, or whose only writer is a draft that declares no transform yet, is read without
  a bound and named in pipeline state. Re-running or backfilling an
  enrichment still invalidates every rollup reading it until rebuilt, and for a Class-1 enrichment that is
  now reported rather than left to folklore. Before declaring one, read
  `openspec/specs/aggregate-pipeline/spec.md` for the declaration rules, what the window waits for and
  what it does not, the join's read cost, and the two rebuild shapes.
- **State** — `GET /v1/pipelines/{name}` reports the scan cursor, last/next run, staleness, last error,
  which enrichment held the last window back, which enrichments are read without a bound, and whether the
  rollup needs rebuilding — so a stalled rollup is visible without reading logs, and one held back on
  purpose is distinguishable from one that is simply quiet. The same signals are Micrometer metrics
  (`aggregate.refresh.*`, `aggregate.lag.seconds`).
- **Scope** — `periodic` freshness only; `incremental` (an insert-time materialized view) is rejected at
  registration. Set `AGGREGATE_ENABLED=false` to stop the scheduler while leaving the API available.

See [`docs/configuration.md`](docs/configuration.md#aggregate-pipelines) for the full reference.

## OpenTelemetry sinks

ADAS can act as an OTLP backend. `POST /v1/otel/sinks` provisions a named *sink*, creating one ClickHouse
landing table per opted-in signal (`otel_<sink>_logs`, `_traces`, `_metrics`) — pick one, two, or all
three. OTLP attribute bags land in `Map(String, String)` columns; the tables are ordinary catalog sources,
so everything a sink ingests is immediately queryable through `/v1/queries` and the SQL and Flight SQL
surfaces.

The sink's `endpoint` is the base URL an OTLP/HTTP exporter is configured with — it appends the standard
signal paths itself:

```yaml
exporters:
  otlphttp:
    endpoint: http://localhost:5000/v1/otel/sink/app
    encoding: json      # OTLP/JSON only; protobuf is answered 415
```

Exporting requires the `write` grant on the signal's landing table, the same per-table grant that governs
`POST /v1/tables/{name}/rows`. Set `OTEL_SINK_ENABLED=false` to switch the whole surface off.

See [`docs/configuration.md`](docs/configuration.md#otlp-sinks) for the full reference, and
[`docs/claude-code-telemetry.md`](docs/claude-code-telemetry.md) for a worked end-to-end example —
provisioning a sink for Claude Code CLI telemetry and pointing the CLI at it.

## Catalog promotion

`POST /v1/catalog/export` writes a versioned bundle of user tables (columns as stored, no grants) and
pipelines, with their dependency closure; `POST /v1/catalog/import` recreates them on another environment from a
`multipart/form-data` upload, with a required `resolution_policy` (`FAIL_IF_EXISTS` or `SKIP_IF_EXISTS`). Each
has a `/preview` twin that writes nothing. Import never changes an existing object, writes no grants,
and every pipeline lands disabled.

See [`docs/catalog-promotion.md`](docs/catalog-promotion.md) for the promotion runbook,
[`docs/recommended-catalog/`](docs/recommended-catalog/README.md) for the recommended starter bundle, and
[`docs/configuration.md`](docs/configuration.md#catalog-transfer) for the upload limits.

## Arrow Flight SQL endpoint

ADAS can optionally expose an [Arrow Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html)
endpoint (gRPC, default port 32010, off by default — enable with `FLIGHT_ENABLED=true`). Clients such
as pandas/ADBC or the Flight SQL JDBC driver submit plain SQL `SELECT` statements; ADAS parses and
validates them with Apache Calcite against the same entity catalog as `/v1/queries`, translates them
onto the structured query DSL, executes through the same governed pipeline as
`POST /v1/queries/execute`, and streams results back as typed Arrow record batches. The accepted SQL is
standard (PostgreSQL-shaped) `SELECT`: filters, the function catalog of `/v1/queries/sql-functions`,
arithmetic, `CASE`, aggregates with `GROUP BY`/`HAVING`, `ORDER BY`, `LIMIT`/`OFFSET`, window functions
(`row_number`, `rank`, `dense_rank`, and `count`/`sum`/`avg`/`min`/`max` over `PARTITION BY`/`ORDER BY`
with `ROWS`/`RANGE` frames), joins between entities (`INNER`/`LEFT`/`RIGHT`/`FULL`/`CROSS`, with at least
one equality in `ON`; outer joins yield NULL for an unmatched side), non-recursive `WITH` clauses,
derived tables of any accepted shape, uncorrelated `IN`/`EXISTS`/scalar subqueries, and set operations
(`UNION ALL`/`UNION DISTINCT`/`INTERSECT`/`EXCEPT`, at any level). Set operations follow **ClickHouse**
rather than the SQL standard wherever the two disagree, so the same text returns the same rows here as
against the database: a bare `INTERSECT`/`EXCEPT` means `ALL`, a trailing `ORDER BY`/`LIMIT` applies to
the last branch, and a bare `UNION` is refused just as ClickHouse refuses it — see
[docs/sql-reference.md](docs/sql-reference.md#set-operations). Every entity a
statement reads is validated against the catalog and masked per caller exactly as a single-entity query
is; the query budget bounds joins like everything else. Still rejected before execution, with an error
naming the construct: `WITH RECURSIVE`, correlated subqueries,
`lag`/`lead`, general `CAST`, `ROLLUP`/`CUBE`, and functions outside the catalog. Statements against
`INFORMATION_SCHEMA` are answered from ADAS's own catalog (see **Catalog discovery** below).
Authentication follows `security.mode` just like HTTP. In `oidc` mode the listener accepts three
credential forms on every RPC, in precedence order: an `Authorization: Bearer` OIDC token, an
`Api-Key` header, or `Authorization: Basic` with the fixed username `api-key` and a DIAL API key as
the password — the last for clients that can express only a username and a password and can set
neither a custom header nor a bearer token. See
[`docs/configuration.md`](docs/configuration.md#arrow-flight-sql-endpoint) for properties, the
credential forms in full, and the required JVM flags.

`SELECT *` deliberately returns less than every column: columns marked `heavy` in the catalog (raw
payload/blob columns such as `"dial_usage_log_payload.request_body"`) and enrichment columns are left
out of a wildcard, so the canonical first query of a notebook stays fast. Naming a column always
returns it — see [`docs/heavy-columns.md`](docs/heavy-columns.md). A column may also declare a closed
set of string values (`type: "enum"`), stored as a ClickHouse `Enum8`/`Enum16` and published on the
discovery surface — see [`docs/enum-columns.md`](docs/enum-columns.md). A table's rows can be narrowed
per provider role with a row policy, enforced on this endpoint and on every REST read alike — see
[`docs/row-level-security.md`](docs/row-level-security.md).

**The Flight endpoint has its own row cap**, `FLIGHT_MAX_ROWS` (default 10 000), which replaces the
shared 1000-row maximum for this transport and bounds both an explicit `LIMIT` and a statement that
states none. A statement with no `LIMIT` is not rejected: it runs asking for one row beyond the cap and
fails only if that row appears, which proves the result was truncated — so a truncated page is never
returned, while `SELECT count(*)` and the aggregate queries a BI tool's DirectQuery generator emits work
without a clause they cannot produce. An explicit `LIMIT` within the cap is honoured exactly, and makes
the query immune to the data-dependent truncation error. REST keeps the shared default of 100 and
maximum of 1000. See [`docs/configuration.md`](docs/configuration.md#arrow-flight-sql-endpoint).

Catalog discovery works over the standard Flight SQL metadata RPCs — `GetTables` (including
`include_schema`, which reports the same Arrow schema `SELECT *` delivers), `GetTableTypes`,
`GetXdbcTypeInfo`, and `GetSqlInfo` — all sourced from the same catalog as `/v1/queries/entities` and
filtered by the caller's role exactly like query results. These RPCs declare **no catalog and no schema
namespace**, through `GetSqlInfo` (empty catalog/schema terms, no supported element actions) as well as
returning no rows from `GetCatalogs`/`GetDbSchemas` — the declaration is what makes an RPC-driven client
generate unqualified `SELECT ... FROM <entity>` statements. Without it a driver assumes schemas exist and
qualifies with the empty schema name.

Clients that discover through **catalog SQL** rather than the RPCs — Power BI's Dremio connector among
them, which never calls the metadata RPCs — are served instead by `INFORMATION_SCHEMA.TABLES`,
`.COLUMNS` and `.SCHEMATA`, queryable as ordinary statements over both Flight and
`POST /v1/queries/execute-sql`. Their rows describe the same catalog as `/v1/queries/entities`, under the
same per-caller column filtering, and they carry a schema name (`adas`) that entities also resolve
under — so the statements such a client generates from what it read run unchanged. A catalog statement is
answered in process, never reaching ClickHouse, so the row cap and the page limits do not apply to it.
Prepared statements and the key-metadata RPCs (`GetPrimaryKeys`, `GetExportedKeys`,
`GetImportedKeys`, `GetCrossReference`) are unimplemented and fail at `getFlightInfo`.

The same SQL is also accepted over plain REST: `POST /v1/queries/execute-sql` takes
`{"sql": "SELECT ..."}` and returns the same `{rows}` JSON envelope as
`POST /v1/queries/execute` (`totalCount` is never populated on the SQL path). It runs through the
identical translate → validate → execute pipeline and needs no Flight setup — it is always on, with
no extra configuration. The default limit of 100 and the maximum of 1000 govern the outermost
statement; a `LIMIT` inside a derived table, CTE or subquery runs as written. `POST /v1/queries/translate-sql`
returns the structured-query document of a single-entity statement (`CASE` and window expressions
included); a statement that joins, nests a derived table or carries a subquery executes but has no DSL
document and is answered with 400 there.

To check a statement before running it, `POST /v1/queries/validate-sql` takes the same `{"sql": "..."}`
body and answers `{valid, effective_limit, issues[], warnings[]}` without contacting ClickHouse. It
applies every check `execute-sql` applies except the round trip — over the whole accepted surface,
composite statements included — so a statement it calls valid is one `execute-sql` will not reject.
A well-formed request is always 200; only a malformed one (missing or blank `sql`) is a 400. Issues carry
a stable `code` and, where there is a concrete fix, a `remedy`, and several unresolvable identifiers are
reported together. `valid: true` is not a promise that execution succeeds — the query budget is enforced
as the statement runs — which is what the advisory `warnings` are for. See
[docs/sql-reference.md](docs/sql-reference.md).

Both SQL surfaces can be driven straight from Grafana and Power BI, with no query gateway in between.
Seven ready-made Grafana dashboards over DIAL usage data ship in [`grafana/`](grafana/) — install
them into your own Grafana per [`docs/grafana-dashboards.md`](docs/grafana-dashboards.md), or bring
up the local preview stack there. Synthetic `dial_usage_log` rows come from
[`local_env/seed/`](local_env/seed/).

## Docker

The build pulls the `epam/jclouds` fork from GitHub Packages, so it needs GitHub
Packages credentials. Export `GPR_USERNAME`/`GPR_PASSWORD` (a PAT with
`read:packages`) and pass them as BuildKit secrets — they are mounted only into the builder
stage's Gradle step and never reach an image layer:

```bash
docker build --secret id=GPR_USERNAME,env=GPR_USERNAME --secret id=GPR_PASSWORD,env=GPR_PASSWORD \
  -t ai-dial-analytics .
docker run --rm -p 5000:5000 \
  -e CLICKHOUSE_HOST=<clickhouse-host> -e POSTGRES_HOST=<postgres-host> \
  ai-dial-analytics
```

The defaults point at `localhost`, which inside the container is the container itself; set the
ClickHouse and PostgreSQL connection variables (see [`docs/configuration.md`](docs/configuration.md))
to reachable hosts.

## Development workflow

This project uses [OpenSpec](https://github.com/Fission-AI/OpenSpec) for spec-driven
development. See `CLAUDE.md` and `openspec/` for the proposal → apply → archive workflow.

## Release notes

Operator upgrade guides for releases are in `docs/release-<version>-upgrade.md`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Report security issues as described in
[SECURITY.md](SECURITY.md), not as public issues.

## License

Copyright 2026 EPAM Systems, Inc.

Licensed under the [Apache License, Version 2.0](LICENSE).
