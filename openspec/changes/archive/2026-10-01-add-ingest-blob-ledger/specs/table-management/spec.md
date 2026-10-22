## ADDED Requirements

### Requirement: Service-internal table names are reserved

Table names beginning with `_dial_internal_` SHALL be reserved for tables the service itself owns in the
ClickHouse database — tables provisioned by its migrations, never registered in the catalog and never
reachable through any query surface (the ingest ledger `_dial_internal_ingested_blob` is the first). `POST /v1/tables`
SHALL reject such a name with `422`, before any catalog row is written or any ClickHouse statement is
issued, with a message stating that the prefix is reserved for service-internal tables. The rejection SHALL
hold independently of the general identifier grammar, which already refuses a leading `_`, so that relaxing
that grammar later cannot open the prefix; every other path that creates catalog tables through the same
creation step inherits it.

The reason is not only naming: a catalog create whose `CREATE TABLE` fails undoes its partial table with a
best-effort `DROP TABLE IF EXISTS`, so a create aimed at the name of an existing table the catalog does not
know would drop that table. Rejecting the prefix before any DDL guarantees that no table API can reach a
service-internal table, by create or by its compensating drop.

#### Scenario: Creating a table under the internal prefix is rejected

- **WHEN** `POST /v1/tables` is called with the name `_dial_internal_ingested_blob` or any other name beginning with
  `_dial_internal_`
- **THEN** the request is rejected with `422`, the message says the prefix is reserved for service-internal
  tables, no catalog row is written and no ClickHouse statement is issued, and the existing internal table
  is untouched

#### Scenario: Internal tables are not addressable through the tables API

- **WHEN** a caller reads, patches, defines the schema of, or deletes `_dial_internal_ingested_blob` through `/v1/tables`
- **THEN** the service responds as for a table that does not exist, and issues no ClickHouse statement
  against it
