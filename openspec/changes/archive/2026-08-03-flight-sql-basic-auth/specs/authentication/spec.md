## ADDED Requirements

### Requirement: API-key credential envelopes on the Flight surface

In `oidc` mode, when `security.api-key.enabled` is `true`, the Flight SQL surface SHALL accept a DIAL API
key in two additional envelopes beyond the `Api-Key` header:

- `authorization: Basic base64(<username>:<password>)`, where `<username>` SHALL be the fixed sentinel
  `api-key` (compared case-insensitively) and `<password>` SHALL be the API key. This exists for clients
  that can express only a username and a password and can set neither a custom header nor a bearer token.
- `authorization: Bearer adas-api-key:<key>`, a marked bearer value whose marker `adas-api-key:` SHALL be
  stripped before validation.

Both envelopes SHALL resolve to the **same** validation path as the `Api-Key` header — introspection
against DIAL Core's `/v1/user/info`, the same SHA-256-keyed introspection cache, the same role mapping
onto `FULL_ADMIN` / `READ_ONLY_ADMIN` — and SHALL produce, **on the Flight surface**, an authenticated
caller indistinguishable from one that presented the same key in an `Api-Key` header on that surface:
same principal, same email, same authorities. A caller in either envelope SHALL be authorized under the
same rules as any other Flight caller. Neither envelope SHALL be accepted when
`security.api-key.enabled` is `false`, and neither SHALL be active in `none` mode.

The equivalence is deliberately scoped to the Flight surface: the token the Flight path builds for a
validated key is not identical to the one the HTTP filter builds for that same key (the HTTP token
additionally carries the raw provider-role authorities and the project/claims identity used for
per-table grant matching). That divergence is a property of the pre-existing Flight API-key path, not of
these envelopes, and is out of scope here.

These envelopes SHALL be confined to the Flight SQL surface. HTTP `/v1/**` SHALL continue to accept only
a `Bearer` token or an `Api-Key` header, and SHALL NOT accept a `Basic` credential.

The service SHALL NOT issue a credential of its own in exchange for either envelope. In particular the
value returned when a Flight `Basic` credential is accepted SHALL carry the presented API key unchanged,
SHALL have no independent lifetime, and SHALL NOT be recorded server-side — so that every call is
validated afresh and a revoked key ceases to work within the same
`security.api-key.cache-ttl-seconds` bound that governs the `Api-Key` header, with no session credential
able to outlive it.

A `Basic` credential whose username is not the sentinel SHALL be rejected with a message naming the
accepted username, and its password SHALL NOT be introspected. It SHALL NOT be ignored in favour of the
password alone: the authenticated principal comes from introspecting the key, so a caller who supplied a
different username must not be authenticated as the key's principal while believing they authenticated as
the identity they typed.

#### Scenario: Sentinel Basic credential authenticates as the key's principal
- **WHEN** a Flight call presents `authorization: Basic base64("api-key:<key>")` and Core introspection
  resolves that key to a principal with a mapped role
- **THEN** the caller is authenticated as that principal with that role, exactly as if the key had been
  presented in an `Api-Key` header

#### Scenario: Sentinel username is matched case-insensitively
- **WHEN** the username in the `Basic` credential is `API-KEY` or `Api-Key`
- **THEN** it is accepted as the sentinel

#### Scenario: Non-sentinel username is rejected without introspection
- **WHEN** a Flight call presents `authorization: Basic base64("someone@example.com:<valid key>")`
- **THEN** the call is rejected with a message naming `api-key` as the accepted username, DIAL Core is not
  called, and no caller is authenticated

#### Scenario: Marked bearer envelope is equivalent to the header
- **WHEN** a Flight call presents `authorization: Bearer adas-api-key:<key>`
- **THEN** the marker is stripped and the key is validated by the same introspection path and cache as the
  `Api-Key` header, yielding the same principal and authorities

#### Scenario: Envelopes share the introspection cache
- **WHEN** the same key is presented within the cache TTL in an `Api-Key` header, in a `Basic` credential,
  and in the marked bearer envelope
- **THEN** DIAL Core `/v1/user/info` is called at most once across those calls

#### Scenario: Revocation is not outlived
- **WHEN** a caller has authenticated with the `Basic` envelope and the key subsequently stops
  introspecting successfully
- **THEN** calls made after the cached introspection expires are rejected, because no server-issued
  credential exists that could keep the caller authenticated

#### Scenario: Basic is not accepted over HTTP
- **WHEN** a request to a protected `/v1/**` endpoint presents only `Authorization: Basic base64("api-key:<valid key>")`
- **THEN** the request is treated as unauthenticated and rejected `401` with the `ErrorView` body

#### Scenario: Envelopes are inert when API-key auth is disabled
- **WHEN** `security.api-key.enabled` is `false` and a Flight call presents the `Basic` envelope with an
  otherwise valid key
- **THEN** the call is rejected as unauthenticated

## MODIFIED Requirements

### Requirement: Bearer header precedence over API key

When a request carries an `Authorization` header **whose value is `Bearer `-prefixed**, the service SHALL
authenticate it via the Bearer token path and SHALL ignore any `Api-Key` header on that request. An
`Authorization` value carrying any other scheme SHALL NOT be treated as a bearer token.

On HTTP, the presence of an `Authorization` header of **any** scheme SHALL cause the `Api-Key` header to be
ignored: the API-key filter defers to the bearer path on any non-blank `Authorization` value, so a request
combining a non-Bearer `Authorization` value with an otherwise valid `Api-Key` header SHALL be rejected
`401` rather than authenticated by the key.

On the Flight surface, where a `Basic` value is an accepted API-key envelope, credential precedence SHALL
be `Bearer` → `Api-Key` header → `Basic`, so a call is authenticated by the first form present in that
order and no later form is introspected. Precedence there SHALL be decided by the credential's scheme
rather than by the order values arrived in, since a gRPC call may carry the `authorization` header more
than once.

#### Scenario: JWT wins when both headers are present
- **WHEN** a request presents both an `Authorization: Bearer <jwt>` header and an `Api-Key` header
- **THEN** the service authenticates using the JWT and does not introspect the API key

#### Scenario: A non-Bearer Authorization header still suppresses the Api-Key header over HTTP
- **WHEN** a request to a protected `/v1/**` endpoint presents `Authorization: Basic base64("api-key:<valid key>")`
  together with a valid `Api-Key` header
- **THEN** the service responds `401` with the `ErrorView` body — the `Authorization` header suppresses the
  `Api-Key` header, and `Basic` is not an accepted HTTP credential form

#### Scenario: Bearer wins over Basic whatever the wire order
- **WHEN** a Flight call carries the `authorization` header twice, once with a `Bearer` token and once with
  a `Basic` API-key envelope, in either order
- **THEN** the bearer token authenticates the call and the key is not introspected

#### Scenario: Bearer wins over a Basic envelope
- **WHEN** a Flight call presents both `Authorization: Bearer <valid token>` and a `Basic` API-key envelope
- **THEN** the service authenticates using the bearer token and does not introspect the key in the `Basic`
  envelope

#### Scenario: Api-Key header wins over a Basic envelope
- **WHEN** a Flight call presents both an `Api-Key` header and a `Basic` API-key envelope
- **THEN** the service authenticates using the `Api-Key` header
