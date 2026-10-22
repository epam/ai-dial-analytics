## ADDED Requirements

### Requirement: Security mode selection

The service SHALL support a configurable security mode via the `security.mode` property with two
values: `oidc` and `none`. The default SHALL be `none`. When the mode is `none`, all endpoints are
accessible without authentication and method-level authorization is not enforced. When the mode is
`oidc`, the service SHALL enforce Bearer-JWT authentication and role-based authorization as
specified below.

#### Scenario: Default mode is none
- **WHEN** the service starts with no `security.mode` configured
- **THEN** the mode is `none` and all `/v1/**` endpoints respond without requiring a token

#### Scenario: OIDC mode requires configuration
- **WHEN** the service starts with `security.mode=oidc` and no valid identity provider configured
- **THEN** startup fails fast with an error indicating no identity providers are configured

### Requirement: Bearer JWT authentication

In `oidc` mode the service SHALL authenticate callers using a `Bearer` JWT presented in the
`Authorization` header. The service SHALL reject requests to protected endpoints that present no
token, a malformed token, or a token failing validation.

#### Scenario: Missing token is rejected
- **WHEN** a request to a protected `/v1/**` endpoint is made without an `Authorization` header
- **THEN** the service responds `401` with the `ErrorView` body and error code `unauthorized`

#### Scenario: Malformed token is rejected
- **WHEN** a request presents an `Authorization: Bearer <value>` header whose value is not a valid JWT
- **THEN** the service responds `401` with the `ErrorView` body

### Requirement: Multi-issuer token validation

In `oidc` mode the service SHALL accept JWTs issued by any of several configured identity providers.
Each provider is configured with at least an issuer, a JWKS URI, accepted audiences, and role
claims. The service SHALL select the verification key set by the token's `iss` claim and SHALL
validate the token's signature, issuer, audience, and expiry. A token whose issuer is not configured,
whose audience is not accepted, or which has expired SHALL be rejected.

#### Scenario: Token from a configured issuer is accepted
- **WHEN** a request presents a valid, unexpired JWT whose `iss` matches a configured provider and
  whose audience is accepted
- **THEN** the service authenticates the caller and processes the request

#### Scenario: Unknown issuer is rejected
- **WHEN** a request presents a JWT whose `iss` claim matches no configured provider
- **THEN** the service responds `401` with the `ErrorView` body

#### Scenario: Wrong audience is rejected
- **WHEN** a request presents a JWT whose audience is not in the provider's accepted audiences
- **THEN** the service responds `401` with the `ErrorView` body

#### Scenario: Expired token is rejected
- **WHEN** a request presents a JWT whose expiry is in the past
- **THEN** the service responds `401` with the `ErrorView` body

### Requirement: Opaque token introspection

In `oidc` mode the service SHALL also accept opaque (non-JWT) bearer tokens for providers configured
with a userinfo endpoint. The service SHALL determine per request whether a token is a JWT or opaque,
and for an opaque token SHALL introspect it by calling a configured provider's userinfo endpoint with
the token; the returned claims supply the principal, email, and provider roles, which are mapped onto
application roles as for JWTs. A token that no configured provider can introspect SHALL be rejected.

#### Scenario: Valid opaque token is accepted
- **WHEN** a request presents an opaque bearer token that a configured provider's userinfo endpoint
  accepts and returns claims for
- **THEN** the service authenticates the caller using the userinfo claims and processes the request

#### Scenario: Unintrospectable opaque token is rejected
- **WHEN** a request presents an opaque bearer token that no configured provider's userinfo endpoint accepts
- **THEN** the service responds `401` with the `ErrorView` body

### Requirement: Role-based authorization

In `oidc` mode the service SHALL extract role claims from the token using each provider's configured
role-claim paths, map those provider roles to the application roles `FULL_ADMIN` and
`READ_ONLY_ADMIN` using the provider's (or the default) roles mapping, and grant the resulting
authorities to the caller. A caller whose token yields no mapped application role SHALL be treated
as unauthorized for protected endpoints.

#### Scenario: Provider role maps to application role
- **WHEN** a token carries a provider role that the roles mapping associates with `FULL_ADMIN`
- **THEN** the caller is granted the `FULL_ADMIN` authority

#### Scenario: No mapped role is unauthorized
- **WHEN** a valid token carries no role that maps to any application role
- **THEN** requests to protected endpoints are rejected with `403` and the `ErrorView` body

### Requirement: Mutating endpoints require FULL_ADMIN

In `oidc` mode the service SHALL require the `FULL_ADMIN` authority for mutating endpoints: metadata
create, rename, and delete, and the data-sync backfill trigger. Read endpoints (query execution,
schema discovery, metadata list/get, backfill status) SHALL be accessible to any authenticated
caller regardless of role.

#### Scenario: READ_ONLY_ADMIN cannot mutate
- **WHEN** a caller authenticated with only `READ_ONLY_ADMIN` invokes a mutating endpoint
- **THEN** the service responds `403` with the `ErrorView` body and error code `forbidden`

#### Scenario: FULL_ADMIN can mutate
- **WHEN** a caller authenticated with `FULL_ADMIN` invokes a mutating endpoint with a valid request
- **THEN** the service processes the request normally

#### Scenario: Any authenticated caller can read
- **WHEN** a caller authenticated with `READ_ONLY_ADMIN` invokes a read endpoint
- **THEN** the service processes the request normally

### Requirement: Public paths

The service SHALL permit access without authentication to operational and documentation paths:
`actuator` health, info, and prometheus endpoints, and the springdoc OpenAPI UI and API-docs paths.

#### Scenario: Health endpoint is public
- **WHEN** a request is made to the actuator health endpoint without a token in `oidc` mode
- **THEN** the service responds `200`

### Requirement: Auth failures use the ErrorView contract

Authentication and authorization failures SHALL return the standard `ErrorView` JSON shape
(`{status, error, message, path, method}`). A `401` response SHALL use error code `unauthorized` and
a `403` response SHALL use error code `forbidden`.

#### Scenario: 401 body shape
- **WHEN** an unauthenticated request to a protected endpoint is rejected
- **THEN** the response body is an `ErrorView` with `status` 401 and `error` `unauthorized`

#### Scenario: 403 body shape
- **WHEN** an authenticated but insufficiently-privileged request is rejected
- **THEN** the response body is an `ErrorView` with `status` 403 and `error` `forbidden`
