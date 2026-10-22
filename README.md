# DIAL Analytics

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.x-brightgreen)

DIAL Analytics hosts the **Analytics Data Access Service (ADAS)**, the runtime gateway between analytics
consumers and the underlying data stores, primarily ClickHouse.

ADAS ingests DIAL usage logs from blob storage into ClickHouse, keeps a catalog of queryable tables in
PostgreSQL, and serves governed queries over them through a structured query DSL, plain SQL over REST, and
an optional Arrow Flight SQL endpoint. BI tools such as Grafana and Power BI can query it directly.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Features](#features)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [API Overview](#api-overview)
- [Monitoring](#monitoring)
- [Security](#security)
- [Documentation](#documentation)
- [Spec-Driven Development](#spec-driven-development)
- [Contributing](#contributing)
- [License](#license)

## Prerequisites

- JDK 25 (the Gradle toolchain can resolve one)
- Docker, which the test suite needs because it starts ClickHouse and PostgreSQL via
  [Testcontainers](https://testcontainers.com/)
- GitHub Packages credentials. The build pulls EPAM's `jclouds` fork from GitHub Packages, which needs a
  token even for public packages. Export `GPR_USERNAME` (your GitHub username) and `GPR_PASSWORD` (a
  personal access token with `read:packages`), or set `gpr.user` / `gpr.key` in
  `~/.gradle/gradle.properties`.
- ClickHouse 25.3 LTS or newer and PostgreSQL. `local_env/docker-compose.yml` provides both for local
  development.

## Features

- **Usage log ingestion:** a near-real-time poller plus a daily reconciliation sweep load DIAL usage logs
  from Azure Blob Storage or AWS S3. Ingestion is idempotent and deduplicated by the ClickHouse engine.
- **Configurable column mapping:** target columns, source paths and transforms are declared in YAML and
  checked against the live table at startup.
- **Table catalog:** a PostgreSQL-backed catalog of ClickHouse tables, with schema creation and evolution
  through the API.
- **Structured query DSL and SQL:** query catalog entities as JSON or as PostgreSQL-flavored `SELECT`
  (joins, CTEs, subqueries, window functions), with translation between the two.
- **Arrow Flight SQL:** an optional gRPC endpoint for pandas/ADBC, JDBC and BI clients.
- **CSV export:** download any query result as RFC 4180 CSV.
- **Aggregate pipelines:** scheduled rollups written to ordinary, queryable tables.
- **Enrichment pipelines:** derived per-row data joined onto a source table.
- **OpenTelemetry sinks:** ADAS can act as an OTLP/HTTP backend, landing logs, traces and metrics in
  queryable tables.
- **Access control:** OIDC and DIAL API key authentication, per-table grants, sensitive and heavy column
  handling, and row-level security.
- **Saved queries and audit trail:** shareable saved queries, plus a full revision history of catalog
  changes.
- **Catalog promotion:** export and import user tables and pipelines between environments.
- **Grafana dashboards:** ready-made dashboards over DIAL usage data in [`grafana/`](grafana/).

## Getting Started

### Quick start

Start a local ClickHouse and PostgreSQL, run ADAS against them, and seed synthetic data:

```bash
docker compose -f local_env/docker-compose.yml up -d --wait   # ClickHouse + PostgreSQL
POLL_ENABLED=false ./gradlew bootRun                          # no blob store locally
python local_env/seed/seed_dial_usage_log.py                  # synthetic usage rows (second terminal)
```

Check that it works:

```bash
curl -s localhost:9464/actuator/health
# {"status":"UP"}

curl -s localhost:5000/v1/queries/execute-sql -H 'Content-Type: application/json' \
  -d '{"sql": "SELECT deployment, count(*) AS calls FROM dial_usage_log GROUP BY deployment"}'
```

The Swagger UI is at <http://localhost:5000/swagger-ui.html>. More client examples are in
[`local_env/python-examples/`](local_env/python-examples/README.md).

### Build

```bash
./gradlew clean build                     # compile, checkstyle, tests
./gradlew test                            # tests only
./gradlew checkstyleMain checkstyleTest   # code style
```

### Run with Docker

Pass the GitHub Packages credentials as BuildKit secrets. They are mounted only into the build step and
never reach an image layer.

```bash
docker build --secret id=GPR_USERNAME,env=GPR_USERNAME --secret id=GPR_PASSWORD,env=GPR_PASSWORD \
  -t ai-dial-analytics .
docker run --rm -p 5000:5000 \
  -e CLICKHOUSE_HOST=<clickhouse-host> -e POSTGRES_HOST=<postgres-host> \
  ai-dial-analytics
```

Inside the container, the default `localhost` points at the container itself, so set the connection
variables to hosts it can reach.

### Recommended catalog

A new installation should import the
[recommended catalog bundle](docs/recommended-catalog/README.md). It creates the tables and pipelines that
the analytics UI Sessions tab and the evaluation framework read. Without it, those features have no data.

## Configuration

ADAS is configured through environment variables. The full reference, with every variable, default, and
whether a deployment must set it, is [`docs/configuration.md`](docs/configuration.md).

Main configuration areas:

- **ClickHouse:** reader and writer credentials (`CLICKHOUSE_*`, `CLICKHOUSE_WRITER_*`). The writer also
  runs schema migrations, so it needs DDL grants. See [ClickHouse server setup](docs/clickhouse-server.md).
- **PostgreSQL:** the catalog database (`POSTGRES_*`).
- **Blob source:** provider, container and credentials (`BLOB_*`). Without them, nothing is ingested. Set
  `POLL_ENABLED=false` for local runs.
- **Ingestion schedules:** the poller (`POLL_*`) and the daily sweep (`SYNC_*`).
- **Security:** `SECURITY_MODE` and identity providers.
- **Optional surfaces:** Flight SQL (`FLIGHT_ENABLED`), OTLP sinks (`OTEL_SINK_ENABLED`), aggregate
  scheduler (`AGGREGATE_ENABLED`), OpenTelemetry export (`OTEL_EXPORT_ENABLED`).

The ClickHouse and PostgreSQL defaults match `local_env/docker-compose.yml`, so a local run needs no
connection settings.

## API Overview

All functional endpoints live under `/v1`. Errors share one JSON shape:
`{status, error, message, path, method}`, where `error` is a stable machine code.

| Area | Endpoints | Details |
|---|---|---|
| Queries | `POST /v1/queries/execute`, `execute-sql`, `execute-csv`, `execute-sql-csv` | [structured-query](openspec/specs/structured-query/spec.md), [rest-sql-endpoint](openspec/specs/rest-sql-endpoint/spec.md), [SQL reference](docs/sql-reference.md) |
| Validation and translation | `POST /v1/queries/validate-sql`, `translate`, `translate-sql` | [query-translation-endpoint](openspec/specs/query-translation-endpoint/spec.md) |
| Schema discovery | `GET /v1/queries/entities`, `/v1/queries/sql-functions` | [query-schema-discovery](openspec/specs/query-schema-discovery/spec.md) |
| Table catalog | `/v1/tables` | [table-management](openspec/specs/table-management/spec.md) |
| Pipelines | `/v1/pipelines` | [aggregate-pipeline](openspec/specs/aggregate-pipeline/spec.md), [pipeline-registry](openspec/specs/pipeline-registry/spec.md) |
| Saved queries | `/v1/saved-queries` | [saved-queries](openspec/specs/saved-queries/spec.md) |
| Audit trail | `/v1/activities` | [audit-trail](openspec/specs/audit-trail/spec.md) |
| Data sync | `POST /v1/data-sync/backfill` | [data-sync-backfill](openspec/specs/data-sync-backfill/spec.md) |
| OTLP sinks | `/v1/otel/sinks`, `/v1/otel/sink/{name}` | [otel-sink](openspec/specs/otel-sink/spec.md), [Claude Code example](docs/claude-code-telemetry.md) |
| Catalog promotion | `POST /v1/catalog/export`, `import` | [catalog promotion runbook](docs/catalog-promotion.md) |
| Arrow Flight SQL | gRPC, port 32010 | [flight-sql-endpoint](openspec/specs/flight-sql-endpoint/spec.md) |

The OpenAPI document is served at `/v3/api-docs`.

## Monitoring

The actuator serves on its own management port, **9464** (`MANAGEMENT_SERVER_PORT`), separate from the API on
5000. Its endpoints need no credential in any security mode.

| Endpoint | Use | Checks | Answers |
|---|---|---|---|
| `GET /actuator/health/liveness` | Liveness probe, Docker `HEALTHCHECK` | Application state only, no dependency | `200 {"status":"UP"}`, else `503` |
| `GET /actuator/health/readiness` | Readiness probe | Startup (migrations included) complete, PostgreSQL catalog reachable | `200 {"status":"UP",...}`, else `503` |
| `GET /actuator/health` | Diagnostics | All contributors, with details | `200` / `503` |
| `GET /actuator/prometheus` | Prometheus scrape (pull) | — | Prometheus text format |

ClickHouse is not a health contributor: an unreachable ClickHouse surfaces per request as `503
clickhouse_unavailable` rather than taking the pod out of rotation. Probe, metric and OTLP export behaviour is
specified in [observability](openspec/specs/observability/spec.md).

- **Metrics:** the main families are `sync_*` (daily
  sweep), `poll_*` (poller, including `poll_watermark_lag_seconds`), `clickhouse_inserts_total` /
  `clickhouse_insert_failures_total`, and `aggregate_*` (pipelines).
- **OpenTelemetry:** OTLP export of metrics, traces and logs is off by default. Turn it on with
  `OTEL_EXPORT_ENABLED`.

## Security

With no `SECURITY_MODE` set, ADAS runs in `none` mode and every endpoint is open. That mode is meant for
local development only. **Any shared deployment must set `SECURITY_MODE=oidc`** and configure identity
providers, as described in [`docs/configuration.md`](docs/configuration.md).

In `oidc` mode, callers authenticate with an OIDC bearer token or a DIAL API key. Access to data is
governed per table, with more controls on top:

- [Sensitive and heavy columns](docs/heavy-columns.md) for raw prompt and response payloads
- [Row-level security](docs/row-level-security.md) per provider role

The shipped mapping stores raw request and response bodies in an encrypted payload table with a 90-day
retention. Review [column mapping](docs/column-mapping.md) before enabling ingestion.

To report a vulnerability, follow [SECURITY.md](SECURITY.md). Do not open a public issue.

## Documentation

| Topic | Document |
|---|---|
| Configuration reference | [docs/configuration.md](docs/configuration.md) |
| ClickHouse server setup and grants | [docs/clickhouse-server.md](docs/clickhouse-server.md) |
| AWS S3 access | [docs/aws-s3-access.md](docs/aws-s3-access.md) |
| Column mapping for ingestion | [docs/column-mapping.md](docs/column-mapping.md) |
| SQL reference | [docs/sql-reference.md](docs/sql-reference.md) |
| Heavy and sensitive columns | [docs/heavy-columns.md](docs/heavy-columns.md) |
| Enum columns | [docs/enum-columns.md](docs/enum-columns.md) |
| Column tags | [docs/column-tags.md](docs/column-tags.md) |
| Row-level security | [docs/row-level-security.md](docs/row-level-security.md) |
| Catalog promotion | [docs/catalog-promotion.md](docs/catalog-promotion.md) |
| Recommended catalog | [docs/recommended-catalog/](docs/recommended-catalog/README.md) |
| Grafana dashboards | [docs/grafana-dashboards.md](docs/grafana-dashboards.md) |
| Kubernetes exposure | [docs/kubernetes-exposure.md](docs/kubernetes-exposure.md) |
| Claude Code telemetry example | [docs/claude-code-telemetry.md](docs/claude-code-telemetry.md) |
| Upgrade guides | `docs/release-<version>-upgrade.md` |

Shipped behavior for each capability is specified in [`openspec/specs/`](openspec/specs/).

## Spec-Driven Development

This project uses [OpenSpec](https://github.com/Fission-AI/OpenSpec) for spec-driven development. Specs in
`openspec/specs/` describe shipped behavior, and in-progress work lives in `openspec/changes/`.

| Command | Purpose |
|---|---|
| `/opsx:propose "<idea>"` | Create a change with proposal, design and tasks |
| `/opsx:apply` | Implement the current change's tasks |
| `/opsx:archive` | Merge delta specs into `openspec/specs/` and archive the change |
| `/opsx:explore` | Explore an idea before proposing it |

See [CLAUDE.md](CLAUDE.md) and `openspec/config.yaml` for project conventions.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright 2026 EPAM Systems, Inc.

Licensed under the [Apache License, Version 2.0](LICENSE).
