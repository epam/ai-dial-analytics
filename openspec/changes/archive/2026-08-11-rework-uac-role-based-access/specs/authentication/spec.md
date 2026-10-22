## MODIFIED Requirements

### Requirement: Role-based authorization

In `oidc` mode the service SHALL extract role claims from the token (or introspection response) using
each provider's configured role-claim paths, map those provider roles to the application roles
`FULL_ADMIN` and `READ_ONLY_ADMIN` using the provider's (or the default) roles mapping, and grant the
resulting authorities to the caller. The service SHALL additionally retain the caller's raw provider
role names as authorities under a reserved prefix, so per-table access can match on them. This applies
uniformly to all three authentication shapes (Bearer JWT, opaque token, DIAL API key). A valid token
that yields no mapped application role SHALL be treated as **authenticated** (carrying only its raw
provider roles); it is unauthorized for endpoints that require an application role (`403`), but MAY be
authorized for a specific per-table mutating endpoint by a matching access role (see
`table-access-control`).

#### Scenario: Provider role maps to application role
- **WHEN** a token carries a provider role that the roles mapping associates with `FULL_ADMIN`
- **THEN** the caller is granted the `FULL_ADMIN` authority

#### Scenario: No mapped role is authenticated but unauthorized for role-gated endpoints
- **WHEN** a valid token carries no role that maps to any application role
- **THEN** the caller is authenticated, and requests to application-role-gated endpoints are rejected
  with `403` and the `ErrorView` body (not `401`)

### Requirement: Mutating endpoints require FULL_ADMIN

In `oidc` mode the service SHALL require the `FULL_ADMIN` authority for catalog-level and destructive
mutating endpoints: table create (`POST /v1/tables`), table delete (`DELETE /v1/tables/{name}`), all
access-management endpoints (`/v1/tables/{name}/access`), and the data-sync backfill trigger. The
per-table mutating endpoints — row writes (`POST /v1/tables/{name}/rows`), schema PATCH
(`PATCH /v1/tables/{name}/schema`), and description update (`PUT /v1/tables/{name}`) — SHALL be
authorized by `FULL_ADMIN` **or** a matching per-table access role (see the `table-access-control`
capability): row writes require a `write` role and schema/description changes require a `modify` role.
These three endpoints require only authentication at the URL layer, so a caller that maps to no
application role but holds a matching access role SHALL be authorized for them. Read endpoints (query
execution, schema discovery, table list/get, access read, backfill status) SHALL require an
application role; their *content* is further filtered by role at column granularity per **Sensitive
column authorization**.

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
