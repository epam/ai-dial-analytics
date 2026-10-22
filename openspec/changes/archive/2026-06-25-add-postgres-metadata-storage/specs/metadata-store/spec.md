## ADDED Requirements

### Requirement: Metadata is persisted in PostgreSQL

The service SHALL persist metadata records in a PostgreSQL database, separate from ClickHouse. A
metadata record SHALL consist of a single `name` field, which SHALL be the record's primary key and
therefore unique. The backing `metadata` table SHALL be provisioned by a Flyway SQL migration that
runs against the PostgreSQL `DataSource` on startup, isolated from the ClickHouse migrations.

#### Scenario: Schema provisioned on startup

- **WHEN** the application starts against a PostgreSQL database that has not yet been migrated
- **THEN** the Flyway migration creates the `metadata` table with `name` as its primary key
- **AND** CRUD operations succeed against the provisioned table

#### Scenario: ClickHouse migrations are not applied to PostgreSQL

- **WHEN** the PostgreSQL Flyway migration runs on startup
- **THEN** only PostgreSQL-targeted migrations are applied
- **AND** the ClickHouse migrations (e.g. `dial_usage_log`) are not run against PostgreSQL

### Requirement: Create a metadata record

The service SHALL expose `POST /v1/metadata` that creates a metadata record from a request body
carrying a non-blank `name`. On success it SHALL return HTTP 201 with the created record as a JSON
object. A blank or missing `name` SHALL be rejected with HTTP 400 and the `ErrorView` contract.
Creating a record whose `name` already exists SHALL be rejected with HTTP 409 and a stable `error`
code, leaving the existing record unchanged.

#### Scenario: Successful creation

- **WHEN** a client sends `POST /v1/metadata` with body `{"name": "alpha"}` and no record named `alpha` exists
- **THEN** the service stores the record and responds 201 with body `{"name": "alpha"}`

#### Scenario: Duplicate name rejected

- **WHEN** a client sends `POST /v1/metadata` with body `{"name": "alpha"}` and a record named `alpha` already exists
- **THEN** the service responds 409 with an `ErrorView` and does not modify the existing record

#### Scenario: Blank name rejected

- **WHEN** a client sends `POST /v1/metadata` with a missing or blank `name`
- **THEN** the service responds 400 with an `ErrorView` and stores nothing

### Requirement: List metadata records

The service SHALL expose `GET /v1/metadata` that returns all metadata records as a JSON object
containing an array field (not a bare array), so additional fields can be added later without
breaking clients.

#### Scenario: List returns stored records

- **WHEN** records `alpha` and `beta` exist and a client sends `GET /v1/metadata`
- **THEN** the service responds 200 with an object whose array field contains entries for `alpha` and `beta`

#### Scenario: Empty list

- **WHEN** no records exist and a client sends `GET /v1/metadata`
- **THEN** the service responds 200 with an object whose array field is empty

### Requirement: Get a metadata record by name

The service SHALL expose `GET /v1/metadata/{name}` that returns the record with the given name. A
name with no matching record SHALL return HTTP 404 with the `ErrorView` contract.

#### Scenario: Existing record returned

- **WHEN** a record named `alpha` exists and a client sends `GET /v1/metadata/alpha`
- **THEN** the service responds 200 with body `{"name": "alpha"}`

#### Scenario: Missing record

- **WHEN** no record named `ghost` exists and a client sends `GET /v1/metadata/ghost`
- **THEN** the service responds 404 with an `ErrorView`

### Requirement: Rename a metadata record

The service SHALL expose `PUT /v1/metadata/{name}` that renames an existing record to a new non-blank
name carried in the request body. On success it SHALL return HTTP 200 with the updated record.
Renaming a record that does not exist SHALL return HTTP 404. Renaming to a name that already belongs
to another record SHALL return HTTP 409. A blank or missing new name SHALL return HTTP 400.

#### Scenario: Successful rename

- **WHEN** a record named `alpha` exists, no record named `gamma` exists, and a client sends `PUT /v1/metadata/alpha` with body `{"name": "gamma"}`
- **THEN** the service responds 200 with body `{"name": "gamma"}`
- **AND** a record named `alpha` no longer exists and a record named `gamma` does

#### Scenario: Rename of missing record

- **WHEN** no record named `ghost` exists and a client sends `PUT /v1/metadata/ghost` with body `{"name": "gamma"}`
- **THEN** the service responds 404 with an `ErrorView`

#### Scenario: Rename collides with existing name

- **WHEN** records `alpha` and `beta` exist and a client sends `PUT /v1/metadata/alpha` with body `{"name": "beta"}`
- **THEN** the service responds 409 with an `ErrorView` and neither record is changed

### Requirement: Delete a metadata record

The service SHALL expose `DELETE /v1/metadata/{name}` that removes the record with the given name. On
success it SHALL return HTTP 204 with no body. Deleting a record that does not exist SHALL return HTTP
404 with the `ErrorView` contract.

#### Scenario: Successful deletion

- **WHEN** a record named `alpha` exists and a client sends `DELETE /v1/metadata/alpha`
- **THEN** the service responds 204 and a record named `alpha` no longer exists

#### Scenario: Delete of missing record

- **WHEN** no record named `ghost` exists and a client sends `DELETE /v1/metadata/ghost`
- **THEN** the service responds 404 with an `ErrorView`

### Requirement: PostgreSQL availability is mapped to the error contract

When the PostgreSQL database is unreachable or a data-access error occurs, metadata endpoints SHALL
respond with HTTP 503 and the `ErrorView` contract using a stable `error` code, without leaking
stack traces or raw driver internals.

#### Scenario: Database unavailable

- **WHEN** a metadata endpoint is invoked while PostgreSQL is unreachable
- **THEN** the service responds 503 with an `ErrorView` whose `error` code identifies a PostgreSQL availability failure

### Requirement: Metadata is not queryable through the structured query DSL

The `metadata` store SHALL be isolated from the structured query DSL. The `metadata` table SHALL NOT
be registered as a queryable entity, SHALL NOT appear in schema discovery under `/v1/queries`, and a
structured query targeting it via `POST /v1/queries/execute` SHALL be rejected with HTTP 400 as an
unknown entity.

#### Scenario: Metadata absent from schema discovery

- **WHEN** a client lists queryable entities under `/v1/queries`
- **THEN** `metadata` is not among them

#### Scenario: Querying metadata via the DSL is rejected

- **WHEN** a client sends `POST /v1/queries/execute` with `entity` set to `metadata`
- **THEN** the service responds 400 with an `ErrorView` indicating an unknown entity
