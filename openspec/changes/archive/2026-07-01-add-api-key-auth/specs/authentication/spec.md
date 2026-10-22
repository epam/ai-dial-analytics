## ADDED Requirements

### Requirement: API-key authentication

In `oidc` mode, when `security.api-key.enabled` is `true`, the service SHALL additionally accept an
API key presented in the `Api-Key` request header. The service SHALL validate the key by
introspecting it against DIAL Core's `/v1/user/info` endpoint (base URL from
`security.api-key.core-url`), map the roles Core returns onto the application roles `FULL_ADMIN` /
`READ_ONLY_ADMIN`, and authorize the request under the same URL and method rules that apply to
Bearer-token callers. When `security.api-key.enabled` is `false` (the default), the `Api-Key` header
SHALL be ignored. API-key authentication SHALL NOT be active in `none` mode.

#### Scenario: Valid project-key with a mapped role is authenticated
- **WHEN** a request to a protected `/v1/**` endpoint presents an `Api-Key` header whose Core
  introspection returns a `project` and a role that maps to an application role
- **THEN** the service authenticates the caller and processes the request under that role

#### Scenario: API-key auth ignored when disabled
- **WHEN** `security.api-key.enabled` is `false` and a request presents only an `Api-Key` header
- **THEN** the header is not used for authentication and the request is treated as unauthenticated
  (rejected `401` on protected endpoints)

### Requirement: API-key key shapes

The service SHALL support the two response shapes DIAL Core returns from `/v1/user/info`. When the
response contains a non-blank `project`, the caller is a project-key caller and the returned roles
SHALL be mapped using `security.api-key.roles-mapping`. When the response contains `userClaims`, the
caller is a JWT-rooted per-request key; the principal and email SHALL be read from the claims named
by `security.defaults.principal-claim` and `security.defaults.email-claim`, and the returned roles
SHALL be mapped using `security.defaults.roles-mapping`. A response containing neither `project` nor
`userClaims`, or a `userClaims` response missing the configured principal claim, SHALL be rejected.

#### Scenario: Project-key roles use the api-key mapping
- **WHEN** Core returns `{"project":"acme","roles":["admin"]}` and
  `security.api-key.roles-mapping` maps `admin` to `FULL_ADMIN`
- **THEN** the caller is granted `FULL_ADMIN`

#### Scenario: JWT-rooted key roles use the defaults mapping
- **WHEN** Core returns a `userClaims` object carrying the configured principal claim and a role
  that `security.defaults.roles-mapping` maps to an application role
- **THEN** the caller is authenticated with that application role and the principal/email taken from
  the configured claims

#### Scenario: Malformed Core response is rejected
- **WHEN** Core returns a body containing neither `project` nor `userClaims`
- **THEN** the service responds `401` with the `ErrorView` body and error code `unauthorized`

### Requirement: Bearer header precedence over API key

When a request carries an `Authorization` header, the service SHALL authenticate it via the Bearer
token path and SHALL ignore any `Api-Key` header on that request.

#### Scenario: JWT wins when both headers are present
- **WHEN** a request presents both an `Authorization: Bearer <jwt>` header and an `Api-Key` header
- **THEN** the service authenticates using the JWT and does not introspect the API key

### Requirement: API-key failure modes

The service SHALL deny any request whose `Api-Key` is rejected by Core, yields a malformed
introspection response, or maps to no application authority. A key that is rejected or malformed
SHALL produce `401` with error code `unauthorized`; a key that authenticates but maps to no
application authority SHALL be denied `403` by the URL/method rules. When DIAL Core is unreachable or
times out, the service SHALL respond `503` with error code `service_unavailable`. All such responses
SHALL use the standard `ErrorView` contract.

#### Scenario: Invalid API key is rejected
- **WHEN** Core rejects the presented `Api-Key`
- **THEN** the service responds `401` with the `ErrorView` body and error code `unauthorized`

#### Scenario: Unmapped role is forbidden
- **WHEN** an `Api-Key` authenticates but none of its Core roles map to an application role
- **THEN** the request is denied `403` with the `ErrorView` body

#### Scenario: Core unavailable yields 503
- **WHEN** the introspection call to DIAL Core fails to connect or times out
- **THEN** the service responds `503` with the `ErrorView` body and error code `service_unavailable`

### Requirement: API-key introspection caching

The service SHALL cache successful API-key introspection results in an in-memory cache keyed by a
SHA-256 digest of the API key, bounded by `security.api-key.cache-ttl-seconds` and
`security.api-key.cache-max-size`. Rejected keys SHALL NOT be cached, so an invalid key is
re-checked against Core on every request. Within the TTL, repeated requests with the same valid key
SHALL be served from the cache without an additional Core call.

#### Scenario: Repeated valid key is served from cache
- **WHEN** the same valid `Api-Key` is presented on several requests within the cache TTL
- **THEN** DIAL Core `/v1/user/info` is called at most once for those requests

#### Scenario: Invalid key is not cached
- **WHEN** an invalid `Api-Key` is presented on several requests
- **THEN** each request results in a fresh Core introspection call
