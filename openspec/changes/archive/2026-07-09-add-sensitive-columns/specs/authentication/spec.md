## ADDED Requirements

### Requirement: Sensitive column authorization

A catalog column MAY be marked `sensitive`. In `oidc` mode the service SHALL restrict sensitive
columns to callers holding the `FULL_ADMIN` authority: for any caller that is not `FULL_ADMIN`,
sensitive columns SHALL be filtered out of the resolved query model before it is used, so they are
absent from schema discovery and cannot be referenced in any clause of a structured or SQL query
(`select`, `filter`, `group_by`, `having`, `sort`). A non-`FULL_ADMIN` caller SHALL NOT be able to
distinguish a sensitive column from a non-existent one: referencing a sensitive column SHALL fail as
an ordinary unknown-field validation error (HTTP 400), and no error path SHALL confirm the column's
existence. Endpoint-level access is unchanged — read endpoints remain reachable by any authenticated
role; only the column-level *content* is filtered.

When security is disabled (`security.mode=none`, no roles present), the filter SHALL fail open:
all columns, including sensitive ones, SHALL be visible. The filter is applied at a single chokepoint
in the catalog-backed query model, so it SHALL cover schema discovery, `POST /v1/queries/execute`,
`POST /v1/queries/execute-sql`, the Flight SQL surface, and the empty-projection ("select all")
expansion uniformly.

#### Scenario: Non-admin cannot see or reference a sensitive column

- **WHEN** a caller authenticated with only `READ_ONLY_ADMIN` requests schema discovery for an entity
  that has a sensitive column, and separately submits a query that references that column
- **THEN** the sensitive column is absent from the discovery response, and the query is rejected with
  HTTP 400 as an unknown field — the response does not reveal that the column exists

#### Scenario: FULL_ADMIN sees and can reference a sensitive column

- **WHEN** a caller authenticated with `FULL_ADMIN` requests schema discovery and queries a sensitive
  column
- **THEN** the sensitive column appears in the discovery response and the query resolves and executes
  normally

#### Scenario: Security disabled shows sensitive columns

- **WHEN** `security.mode=none` and a caller requests schema discovery or queries a sensitive column
- **THEN** the sensitive column is visible in discovery and resolvable in queries (the filter fails
  open when authentication is not enforced)

## MODIFIED Requirements

### Requirement: Mutating endpoints require FULL_ADMIN

In `oidc` mode the service SHALL require the `FULL_ADMIN` authority for mutating endpoints: metadata
create, rename, and delete, and the data-sync backfill trigger. Read endpoints (query execution,
schema discovery, metadata list/get, backfill status) SHALL be accessible to any authenticated
caller regardless of role. Endpoint-level read access is role-agnostic; however, the *content* of
query-execution and schema-discovery responses is filtered by role at column granularity — sensitive
columns are restricted to `FULL_ADMIN` callers per **Sensitive column authorization**.

#### Scenario: READ_ONLY_ADMIN cannot mutate
- **WHEN** a caller authenticated with only `READ_ONLY_ADMIN` invokes a mutating endpoint
- **THEN** the service responds `403` with the `ErrorView` body and error code `forbidden`

#### Scenario: FULL_ADMIN can mutate
- **WHEN** a caller authenticated with `FULL_ADMIN` invokes a mutating endpoint with a valid request
- **THEN** the service processes the request normally

#### Scenario: Any authenticated caller can read
- **WHEN** a caller authenticated with `READ_ONLY_ADMIN` invokes a read endpoint
- **THEN** the service processes the request normally, with sensitive columns filtered from the
  response per **Sensitive column authorization**
