## MODIFIED Requirements

### Requirement: Mutating endpoints require FULL_ADMIN

In `oidc` mode the service SHALL require the `FULL_ADMIN` authority for catalog-level and destructive
mutating endpoints: table create (`POST /v1/tables`), table delete (`DELETE /v1/tables/{name}`), all
access-management endpoints (`/v1/tables/{name}/access`), the data-sync backfill trigger, and the
pipeline registry's mutating and operator endpoints (`POST /v1/pipelines`,
`PATCH /v1/pipelines/{name}`, `DELETE /v1/pipelines/{name}`, `POST /v1/pipelines/{name}/preview`,
`POST /v1/pipelines/{name}/refresh`). The per-table mutating endpoints — row writes
(`POST /v1/tables/{name}/rows`), schema PATCH (`PATCH /v1/tables/{name}/schema`), and description
update (`PUT /v1/tables/{name}`) — SHALL be authorized by `FULL_ADMIN` **or** a matching per-table
access role (see the `table-access-control` capability): row writes require a `write` role and
schema/description changes require a `modify` role. These three endpoints require only authentication
at the URL layer, so a caller that maps to no application role but holds a matching access role SHALL
be authorized for them. Read endpoints (query execution, schema discovery, table list/get, access
read, backfill status, evaluator list/get/version, pipeline list/get/revision-snapshot, and the audit
activity feed) SHALL require an application role; their *content* is further filtered by role at
column granularity per **Sensitive column authorization**. A pipeline read is deliberately outside that
filtering rather than an instance of it: no field of it is redacted by role, because the response
carries declarations, counts and timestamps rather than row values — see the `pipeline-registry`
capability's **A pipeline read carries no row values** for the invariant that makes this safe and for
the cross-capability rule it rests on.

The saved-query mutating endpoints (`POST`, `PUT`, `DELETE` under `/v1/saved-queries`) SHALL be
authorized by the **requested and stored scope of the saved query** rather than by the URL alone (see
the `saved-queries` capability): a write at `common` scope SHALL require `FULL_ADMIN`, while a write at
`personal` scope SHALL require only an application role and SHALL be confined to the caller's own rows.
Because the deciding input is the request body rather than the path, this authorization SHALL be
evaluated during request handling and SHALL surface as `403 forbidden` through the same `ErrorView`
contract as method-level denial.

#### Scenario: READ_ONLY_ADMIN cannot mutate
- **WHEN** a caller authenticated with only `READ_ONLY_ADMIN` and holding no matching access role
  invokes a per-table mutating endpoint
- **THEN** the service responds `403` with the `ErrorView` body and error code `forbidden`

#### Scenario: FULL_ADMIN can mutate
- **WHEN** a caller authenticated with `FULL_ADMIN` invokes a mutating endpoint with a valid request
- **THEN** the service processes the request normally

#### Scenario: A matching access role authorizes a per-table mutation
- **WHEN** a caller carrying a provider role listed in a table's access set for the required permission
  invokes the corresponding per-table mutating endpoint
- **THEN** the service processes the request normally, even if the caller has no application role

#### Scenario: Catalog-level mutations still require FULL_ADMIN
- **WHEN** a non-`FULL_ADMIN` caller invokes table create, table delete, or an access-management
  endpoint, regardless of any per-table access roles it holds
- **THEN** the service responds `403` with the `ErrorView` body and error code `forbidden`

#### Scenario: Pipeline registry mutations require FULL_ADMIN
- **WHEN** a caller authenticated with only `READ_ONLY_ADMIN` registers, patches, or deletes a
  pipeline, or invokes its preview or refresh endpoint
- **THEN** the service responds `403` with the `ErrorView` body and error code `forbidden`

#### Scenario: A pipeline read needs only an application role
- **WHEN** a caller authenticated with only `READ_ONLY_ADMIN` invokes `GET /v1/pipelines`,
  `GET /v1/pipelines/{name}`, or `GET /v1/pipelines/{name}/revision/{revision}`
- **THEN** the service processes the request normally

#### Scenario: A common-scope saved-query write requires FULL_ADMIN
- **WHEN** a `READ_ONLY_ADMIN` caller creates, replaces, or deletes a saved query whose scope is
  `common`
- **THEN** the service responds `403` with the `ErrorView` body and error code `forbidden`

#### Scenario: A personal-scope saved-query write needs only an application role
- **WHEN** a `READ_ONLY_ADMIN` caller creates a saved query whose scope is `personal`
- **THEN** the service processes the request normally and records that caller as the owner
