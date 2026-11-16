## ADDED Requirements

### Requirement: Every materialized table uses the replicated engine variant

The engine this specification names for a table the catalog materializes SHALL be read as an engine family. The
`CREATE TABLE` the service issues SHALL use that family's `Replicated` counterpart:

- `ReplicatedMergeTree` for an `append` source;
- `ReplicatedReplacingMergeTree(_updated_at)` for an `upsert_by_key` source and for an enrichment.

The engine SHALL carry no Keeper path or replica arguments, so ClickHouse places the table under the
`Replicated` database's defaults. The table's settings SHALL include `replicated_deduplication_window = 0` and
`replicated_deduplication_window_for_async_inserts = 0`, alongside any setting the definition already renders,
such as `ttl_only_drop_parts = 1`. Everything else this specification states about the rendered DDL is
unchanged: columns, system columns, `ORDER BY`, `PARTITION BY`, `TTL` and lookup indexes.

The same rendering SHALL apply on every path that creates a catalog table:

- `POST /v1/tables/{name}/schema` and `POST /v1/tables` with a schema;
- catalog import;
- OTel sink creation;
- the enrichment relayout's aligned copy.

The write discipline is unchanged. A table's `write` facet still selects the engine family, and the
agreement this specification requires between a hand-written table's engine and its declared facet holds within
the replicated family:

- a `ReplicatedReplacingMergeTree` source is declared `upsert_by_key`;
- a `ReplicatedMergeTree` source is declared `append`.

A table registered over hand-written DDL SHALL use a replicated engine, or the service refuses to start (see the
`clickhouse-replication` spec).

#### Scenario: An append source is created replicated

- **WHEN** an `append` source with `ordering_key: ["service_name", "event_time"]` is defined and materialized
- **THEN** a single `CREATE TABLE … ENGINE = ReplicatedMergeTree ORDER BY (service_name, event_time)` is issued,
  with `replicated_deduplication_window = 0` and `replicated_deduplication_window_for_async_inserts = 0` among
  its settings

#### Scenario: An upsert-by-key source is created replicated

- **WHEN** an `upsert_by_key` source keyed on `ticket_id` is defined and materialized
- **THEN** the issued `CREATE TABLE` uses `ENGINE = ReplicatedReplacingMergeTree(_updated_at) ORDER BY
  (ticket_id)` with the `_updated_at DateTime64(3) DEFAULT now64(3)` column and the two deduplication settings

#### Scenario: An enrichment with a retention keeps its existing settings

- **WHEN** an aligned enrichment with a `ttl` is defined and materialized
- **THEN** its `CREATE TABLE` uses `ReplicatedReplacingMergeTree(_updated_at)` and its settings carry
  `ttl_only_drop_parts = 1` together with the two deduplication settings

#### Scenario: A created table exists on every replica

- **GIVEN** a `Replicated` database with three replicas
- **WHEN** a table is defined and materialized through any of the paths above
- **THEN** the table exists on all three replicas, and rows written to it on one replica are readable on the
  others once fetched

#### Scenario: The write facet still reflects the engine family

- **WHEN** a materialized `upsert_by_key` source is read back
- **THEN** it reports `write: "upsert_by_key"`, matching its `ReplicatedReplacingMergeTree(_updated_at)` engine
