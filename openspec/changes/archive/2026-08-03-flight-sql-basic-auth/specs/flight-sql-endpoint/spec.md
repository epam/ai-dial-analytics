## MODIFIED Requirements

### Requirement: Flight authentication mirrors service security mode
When `security.mode=oidc`, the Flight endpoint SHALL require authentication on every RPC — the
`Handshake` RPC included — and SHALL accept three client-presented credential forms, plus the marked
bearer envelope specified further below that the endpoint itself hands out in answer to a `Basic`
handshake:

1. `authorization: Bearer <token>`, validated by the same multi-issuer JWT/introspection machinery as
   HTTP — except for a value carrying the `adas-api-key:` marker, which is routed to the API-key path
   instead and never presented to an identity provider;
2. `Api-Key: <key>`, validated by the same DIAL Core introspection with the same cache;
3. `authorization: Basic base64(<username>:<password>)`, where `<username>` SHALL be the fixed sentinel
   `api-key` (compared case-insensitively) and `<password>` SHALL be a DIAL API key, validated by that
   same Core introspection and cache.

Precedence SHALL be `Bearer` → `Api-Key` → `Basic`: a call carrying more than one form SHALL be
authenticated by the first present in that order and the others SHALL NOT be introspected. A `Basic`
credential SHALL be validated on **every** call that presents it, exactly as an `Api-Key` header is, and
the endpoint SHALL NOT issue a session token: no server-side credential exists that could outlive the
API key's revocation, and a revoked key SHALL stop working within the same
`security.api-key.cache-ttl-seconds` bound that governs the `Api-Key` header path.

A `Basic` credential whose username is anything other than the sentinel SHALL be rejected with
UNAUTHENTICATED and a message naming the accepted username, and SHALL NOT be introspected — it SHALL NOT
be silently ignored in favour of another credential form or of the password alone, because the
authenticated principal comes from introspecting the key and a caller who supplied a different username
must not be left believing they authenticated as that identity. A `Basic` value that is not valid base64,
or that decodes to a string containing no colon, SHALL be rejected with UNAUTHENTICATED without
disclosing which part was malformed.

So that clients which authenticate once per connection can work at all, a call authenticated by the
`Basic` form SHALL carry the response header `authorization: Bearer adas-api-key:<the same API key>`,
and the endpoint SHALL accept `authorization: Bearer adas-api-key:<key>` as a credential form equivalent
to `Api-Key: <key>`, routed to the same introspection path and stripped of the marker before validation.
This value SHALL NOT be a minted token: it carries the presented API key unchanged, has no independent
lifetime, is never stored server-side, and is re-introspected on every call that presents it. Absent
this response, Arrow-derived clients that authenticate with username/password retain no credential for
subsequent calls, and the Arrow Flight SQL ODBC drivers construct a gRPC metadata entry with a
zero-length key and abort the client process.

Unauthenticated or rejected credentials SHALL yield gRPC UNAUTHENTICATED; an unreachable introspection
backend SHALL yield UNAVAILABLE. When `security.mode=none`, the endpoint SHALL be open and SHALL accept
no credential form, matching HTTP behavior — no authenticator is registered, so `Basic` is neither
required nor inspected and no `authorization` response header is emitted. Authorization SHALL mirror the
HTTP `/v1/**` rule for every credential form alike: the principal must carry `FULL_ADMIN` or
`READ_ONLY_ADMIN`; a principal without either role SHALL yield gRPC PERMISSION_DENIED.

Because every credential form crosses the wire in a request header, an `oidc`-mode deployment SHALL
terminate TLS in front of the Flight listener.

#### Scenario: Missing credentials rejected in oidc mode
- **WHEN** `security.mode=oidc` and a client calls getFlightInfo with no auth headers
- **THEN** the call fails with UNAUTHENTICATED and the message names all three accepted credential forms

#### Scenario: Valid api-key accepted
- **WHEN** `security.mode=oidc` and a client presents an `Api-Key` header that DIAL Core resolves to a mapped role
- **THEN** the query proceeds

#### Scenario: Basic credential with the sentinel username is accepted
- **WHEN** `security.mode=oidc` and a client presents `authorization: Basic base64("api-key:<valid key>")` whose Core introspection resolves to a mapped role
- **THEN** the caller is authenticated as the key's principal with that principal's roles and the query proceeds, identically to presenting the same key in an `Api-Key` header

#### Scenario: Sentinel username is case-insensitive
- **WHEN** a client presents the same valid key with the username `API-KEY`, or `Api-Key`, instead of `api-key`
- **THEN** the credential is accepted exactly as the lowercase form is

#### Scenario: Wrong username is rejected, not ignored
- **WHEN** a client presents `authorization: Basic base64("someone@example.com:<valid key>")`
- **THEN** the call fails with UNAUTHENTICATED, the message names `api-key` as the accepted username, the key is not introspected, and the caller is not authenticated as the key's principal

#### Scenario: Invalid key in a Basic credential fails like the header path
- **WHEN** a client presents `authorization: Basic base64("api-key:<key Core rejects>")`
- **THEN** the call fails with UNAUTHENTICATED with the same message the `Api-Key` header path produces for a rejected key

#### Scenario: Basic-authenticated principal without an application role is denied
- **WHEN** a client presents a valid key via `Basic` whose Core roles map to no application role
- **THEN** the call fails with PERMISSION_DENIED, as it does for the same key in an `Api-Key` header

#### Scenario: Bearer wins over Basic
- **WHEN** a call carries both `authorization: Bearer <valid token>` and a `Basic` credential
- **THEN** the bearer token authenticates the call and the API key in the `Basic` credential is not introspected

#### Scenario: Handshake answers a Basic credential with the marked bearer value
- **WHEN** a client authenticates the `Handshake` RPC with the `Basic` form
- **THEN** the response carries `authorization: Bearer adas-api-key:<the presented key>`, and a subsequent call presenting that value authenticates as the same principal

#### Scenario: Marked bearer value is revalidated on every call
- **WHEN** a client authenticates with `authorization: Bearer adas-api-key:<key>` and the key subsequently stops introspecting successfully
- **THEN** calls made after the cached introspection expires fail with UNAUTHENTICATED — no session credential keeps the connection working

#### Scenario: Basic-authenticated call does not weaken the introspection cache
- **WHEN** the same valid key is presented repeatedly via `Basic` within the cache TTL
- **THEN** DIAL Core is introspected at most once for those calls, sharing the cache entry the `Api-Key` header path would use for that key

#### Scenario: Open in none mode
- **WHEN** `security.mode=none`
- **THEN** Flight RPCs succeed without credentials, a `Basic` header is neither required nor inspected, and no `authorization` response header is emitted
