## MODIFIED Requirements

### Requirement: Outbound dependency calls emit CLIENT spans
When tracing is enabled, outbound I/O to backing data stores SHALL be traced as OpenTelemetry
`CLIENT`-kind spans so distributed traces reflect the service's dependencies. This SHALL cover
ClickHouse read queries, inserts, DDL, and `DESCRIBE` (over the `client-v2` HTTP client),
blob-store list and get operations (jclouds), and Postgres JDBC statements (the metadata store).
ClickHouse spans SHALL carry `db.system.name`, the operation, the target database, and the server
address/port, but SHALL NOT carry the raw SQL statement (the query path inlines parameters, which
could carry user-identifying values); a ClickHouse insert SHALL keep a low-cardinality span name
and identify the target table via a `db.collection.name` attribute. Postgres JDBC spans SHALL carry
a sanitized statement (literals replaced with `?`). Blob-store spans SHALL carry a peer identity so
the blob backend renders as a node in a service graph (e.g. Grafana Tempo) alongside ClickHouse and
Postgres: `peer.service` SHALL identify the provider, and `server.address` SHALL carry the endpoint
host when a `blob.endpoint` is configured (absent for the provider default / `filesystem` backend).
The instrumentation SHALL require no additional configuration and SHALL be inert (no spans produced)
when tracing is disabled.

Background cycles that run off the request thread SHALL open a single `INTERNAL`-kind parent span so
the `CLIENT` spans they trigger nest under one trace rather than each becoming a disconnected root.
This SHALL cover the scheduled poll, the scheduled daily sync, each operator-triggered backfill day,
and each aggregate pipeline refresh. A refresh's parent span SHALL identify the pipeline, since its
`CLIENT` spans deliberately carry no statement text and would otherwise be unattributable to the
declaration that caused them.

#### Scenario: ClickHouse query traced as a CLIENT span
- **WHEN** a query executes against ClickHouse while a valid span context is active
- **THEN** a `CLIENT`-kind span is recorded with `db.system.name=clickhouse` and no raw SQL statement
  attribute

#### Scenario: Blob-store fetch traced as a CLIENT span
- **WHEN** the data-sync path lists or downloads a blob while a valid span context is active
- **THEN** a `CLIENT`-kind span is recorded for the list/get operation carrying a `peer.service` peer
  identity (and `server.address` when a blob endpoint is configured) so the blob backend renders as a
  service-graph node

#### Scenario: Ingestion cycle spans nest under one trace
- **WHEN** a scheduled poll/sync cycle or a backfill day runs with tracing enabled
- **THEN** its outbound `CLIENT` spans are children of a single `INTERNAL`-kind parent span for the
  cycle

#### Scenario: A refresh's spans nest under one trace naming the pipeline
- **WHEN** an aggregate pipeline refresh runs with tracing enabled
- **THEN** the `CLIENT` spans its probe and `INSERT … SELECT` raise are children of a single
  `INTERNAL`-kind parent span that identifies the pipeline

#### Scenario: No client spans when tracing disabled
- **WHEN** tracing is disabled (the default) and an outbound ClickHouse, blob-store, or JDBC call
  is made
- **THEN** no span is produced (the SDK's no-op tracer records nothing)
