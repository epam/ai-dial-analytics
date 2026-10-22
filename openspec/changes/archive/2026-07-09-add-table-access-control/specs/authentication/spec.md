## MODIFIED Requirements

### Requirement: Mutating endpoints require FULL_ADMIN

In `oidc` mode the service SHALL require the `FULL_ADMIN` authority for catalog-level and destructive
mutating endpoints: table create (`POST /v1/tables`), table delete (`DELETE /v1/tables/{name}`), all
grant-management endpoints (`/v1/tables/{name}/grants`), and the data-sync backfill trigger. The
per-table mutating endpoints — row writes (`POST /v1/tables/{name}/rows`), schema PATCH
(`PATCH /v1/tables/{name}/schema`), and description update (`PUT /v1/tables/{name}`) — SHALL be
authorized by `FULL_ADMIN` **or** a matching per-table grant (see the `table-access-control`
capability): row writes require a `WRITE` grant and schema/description changes require a `MODIFY`
grant. Read endpoints (query execution, schema discovery, table list/get, grant list, backfill status)
SHALL be accessible to any authenticated caller regardless of role.

#### Scenario: READ_ONLY_ADMIN cannot mutate

- **WHEN** a caller authenticated with only `READ_ONLY_ADMIN` and holding no matching grant invokes a
  mutating endpoint
- **THEN** the service responds `403` with the `ErrorView` body and error code `forbidden`

#### Scenario: FULL_ADMIN can mutate

- **WHEN** a caller authenticated with `FULL_ADMIN` invokes a mutating endpoint with a valid request
- **THEN** the service processes the request normally

#### Scenario: Any authenticated caller can read

- **WHEN** a caller authenticated with `READ_ONLY_ADMIN` invokes a read endpoint
- **THEN** the service processes the request normally

#### Scenario: A matching grant authorizes a per-table mutation

- **WHEN** a non-`FULL_ADMIN` caller holding a grant that matches its subject and covers the required
  permission invokes the corresponding per-table mutating endpoint
- **THEN** the service processes the request normally

#### Scenario: Catalog-level mutations still require FULL_ADMIN

- **WHEN** a non-`FULL_ADMIN` caller invokes table create, table delete, or a grant-management endpoint,
  regardless of any per-table grants it holds
- **THEN** the service responds `403` with the `ErrorView` body and error code `forbidden`
