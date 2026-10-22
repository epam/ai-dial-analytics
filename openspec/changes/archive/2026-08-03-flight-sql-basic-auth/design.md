## Context

`FlightHeaderAuthenticator` (design D7 of `add-arrow-flight-sql-endpoint`) is a gRPC header adapter over
the same Spring Security components that guard HTTP. It exists only when `security.mode=oidc` and
`flight.enabled=true`, and it accepts two credential forms:

1. `authorization: Bearer <token>` → multi-issuer JWT / opaque introspection (`ProviderManager`s built
   from `JwtAuthenticationProvider` / `OpaqueTokenAuthenticationProvider`);
2. `Api-Key: <key>` → `CoreApiKeyIntrospector` → `ApiKeyCache` → `ApiKeyAuthorityResolver`.

Arrow calls it through `ServerCallHeaderAuthMiddleware.Factory.onCallStarted`, which runs on **every**
RPC — `Handshake` included. Authorization then mirrors the HTTP `/v1/**` rule (`FULL_ADMIN` or
`READ_ONLY_ADMIN`). `FlightPrincipalMiddleware` separately calls `resolveAuthentication` to carry the
caller's roles into the producer for sensitive-column filtering.

Clients that can only express a username and a password (Power BI's Dremio connector, JDBC drivers,
DBeaver) can produce neither form, so a secured ADAS is unreachable from them.

### What the client sources actually do (verified, not assumed)

The proposal's constraint was "do not mint a session bearer token, because per-call validation is what
bounds revocation". That presumes clients re-send their credential per call. They do not. Read from the
sources:

- **Arrow C++** `GrpcClientImpl::AuthenticateBasicToken` (`transport/grpc/grpc_client.cc`) attaches the
  `Basic` header to a `Handshake` `ClientRpc` **only**, then returns `GetBearerTokenHeader(rpc.context)`.
  That helper searches the server's **trailing** metadata for `authorization`, then its **initial**
  metadata, and returns the value only if it begins with `Bearer ` (case-insensitive). Missing header, or
  any other prefix — including `Basic ` echoed back verbatim — yields `{"", ""}`.
- **The Arrow Flight SQL ODBC driver** (`odbc_impl/flight_sql_auth_method.cc`,
  `UserPasswordAuthMethod::Authenticate`) does
  `call_options.headers.push_back(bearer_result.ValueOrDie())` with **no check for the empty pair**. A
  zero-length gRPC metadata key trips an assertion in gRPC's C++ core and `abort()`s — on Windows,
  `0xC0000409`, no exception. This is the crash observed with both the Dremio and GizmoSQL drivers, and
  it explains it exactly. The original `user_`/`password_` are retained as fields but never re-sent.
- **Arrow Java** `ClientBearerHeaderHandler.getCredentialCallOptionFromIncomingHeaders` applies the same
  `Bearer `-prefix rule and returns `null` otherwise, in which case no credential accompanies later
  calls.

So "accept `Basic` on every call" is necessary (it is what makes the `Handshake` succeed and what a
per-call client would use) but **not sufficient**: without a `Bearer `-prefixed answer, the motivating
client dies on connect. A decision is unavoidable, and it is Decision 4 below.

One server-side fact makes the answer deliverable: ADAS's `Handshake` completes through
`ServerAuthWrapper.wrapHandshake(NO_OP, …)` without ever sending a message, so gRPC emits a
trailers-only response. Arrow's `ServerInterceptorAdapter.close()` handles precisely that case — *"gRPC
doesn't always send response headers if the call errors or completes immediately"* — and runs
`onBeforeSendingHeaders` against the **trailers**. Since the C++ client reads trailing metadata first,
a header contributed by an `AuthResult` on a payload-less `Handshake` does reach the client.

## Goals / Non-Goals

**Goals:**

- Let a client that can only express user/password authenticate to a secured Flight endpoint with a DIAL
  API key it already has.
- Keep revocation semantics identical to the `Api-Key` header path: every call re-introspects, bounded
  by `security.api-key.cache-ttl-seconds`.
- Reject a wrong username loudly rather than authenticating someone as a principal they did not name.
- Remove the `abort()` failure mode for ODBC drivers in `oidc` mode.
- Keep the change inside `FlightHeaderAuthenticator`.

**Non-Goals:**

- No `Basic` on the HTTP surface. `/v1/**` keeps `Bearer` and `Api-Key`.
- No server-side session state, no token store, no signing key, no new configuration property.
- No new credential *source*: `Basic` carries a DIAL API key and nothing else. No username/password
  directory, no OIDC resource-owner-password grant.
- Not a Dremio accommodation. Nothing in the implementation or the spec names a vendor.
- No change to `security.mode=none`, which registers no authenticator.
- No negative caching of failed introspections in this change (Decision 6).

## Decisions

### D1 — `Basic` is parsed inside `resolveAuthentication`, not composed via `BasicCallHeaderAuthenticator`

Arrow ships `BasicCallHeaderAuthenticator`, whose `CredentialValidator.validate(user, password)` returns
an `AuthResult`. Composing it would mean two authenticators on the server and a second place where
precedence is decided — and the stock composition pulls `GeneratedBearerTokenAuthenticator` along with
it (rejected, D4). A `Basic` branch alongside the existing `Bearer` and `Api-Key` branches keeps one
function responsible for "which credential is this call presenting", which is also what
`FlightPrincipalMiddleware` re-runs. Decoding is `Base64.getDecoder()` + split on the **first** colon,
matching `BasicCallHeaderAuthenticator`'s own parse (a password may contain colons; a username may not).

Malformed input — not base64, no colon — is `UNAUTHENTICATED`, the same status a bad key produces, with
no detail about which part failed.

### D2 — the username is a fixed sentinel `api-key`, compared case-insensitively

The authenticated principal comes from introspecting the key, never from the username. So the username
carries no information and could in principle be ignored — but ignoring it is the dangerous option: a
user who types their own email as the username and an API key as the password would be authenticated as
whatever principal the *key* names, while believing they had authenticated as themselves. Audit records,
sensitive-column filtering, and table-grant matching would all key off a principal the user never
mentioned.

Therefore any username other than `api-key` is rejected, and the rejection message names the accepted
value (`username must be "api-key"; the password is the DIAL API key`) so the fix is self-evident in a
client dialog that shows only a status message. Case-insensitive because DSN editors and connection
dialogs freely re-case what the user typed; `api-key` mirrors ADAS's own `Api-Key` header rather than
any vendor's convention (Dremio uses `$token`, others `token` or `user`).

Naming the accepted username leaks nothing: it is a constant, documented in the spec, identical for
every deployment and every caller.

### D3 — validation reuses `authenticateApiKey` unchanged

The `Basic` branch extracts the password and calls the **existing** private `authenticateApiKey`. So the
introspection call, the SHA-256-keyed cache, the authority mapping, the
`token.setAuthenticated(false)`-on-empty-authorities behaviour, the `UNAVAILABLE` mapping for a
`AuthenticationServiceException`, and the `invalid API key` description are all shared with the header
path by construction rather than by resemblance. A `Basic`-authenticated call is indistinguishable
downstream from an `Api-Key`-authenticated one: same `ApiKeyAuthenticationToken`, same
`UserSecurityDetails`, same authorities, same `getName()`.

### D4 — the handshake is answered with the API key in a marked envelope, not a minted token

**The decision this change turns on.** Given the client behaviour above, three options:

| Option | Works with ODBC/JDBC | Revocation | Verdict |
|---|---|---|---|
| A. Answer `Bearer adas-api-key:<the key>`; every call re-introspects it | yes | identical to `Api-Key` header | **chosen** |
| B. Accept `Basic` per call, answer nothing | no — driver `abort()`s on connect | identical | rejected: does not achieve the goal |
| C. `GeneratedBearerTokenAuthenticator` — mint a random session token | yes | **token outlives revocation** | rejected |

Option C is the regression the proposal forbids: a self-issued token is a second credential with its own
lifetime and no revocation channel, so a key revoked in DIAL Core keeps working until the token expires.

Option B is faithful to the letter of "don't answer with a token" and useless in practice — the client
this change exists for crashes before issuing a query.

Option A answers the handshake without creating a credential. The value returned **is** the API key the
client just sent, prefixed with the constant marker `adas-api-key:` so the server can recognise it
without guessing. Consequences:

- **No new secret.** The value travels back to the client that just sent it, on the same TLS channel. It
  is not derived, signed, or stored; ADAS keeps no record of it.
- **No new lifetime.** Nothing expires it independently. Every call carrying it goes through
  `authenticateApiKey` → `ApiKeyCache` → `CoreApiKeyIntrospector`, so a revoked key stops working within
  `security.api-key.cache-ttl-seconds` — the same bound as the header path, on the same cache entry (the
  cache is keyed by a digest of the key, and the marker is stripped before lookup).
- **Stateless.** Works across replicas and survives restarts, because there is nothing to share.
- **No privilege gain.** `Bearer adas-api-key:<key>` grants exactly what `Api-Key: <key>` grants. Anyone
  who can send the former already holds a valid key and could send the latter.

The marker is what avoids the alternative shape — echo the bare key and have the `Bearer` branch try
OIDC first and fall back to API-key introspection on failure. That fallback would make every genuinely
invalid bearer token cost an extra DIAL Core round trip, and would blur `invalid bearer token` against
`invalid API key` in a way that makes misconfiguration hard to diagnose. An explicit marker keeps
routing deterministic: marker present → API-key path, marker absent → today's behaviour, unchanged.

`adas-api-key:` is ADAS-owned and cannot collide with a JWT (`eyJ…`) or an opaque provider token.

### D5 — the response header is emitted by the `AuthResult`, so it lands on exactly the right calls

`authenticate()` returns an `AuthResult`; Arrow's `ServerCallHeaderAuthMiddleware.onBeforeSendingHeaders`
calls `appendToOutgoingHeaders` on it. ADAS returns `() -> identity` today, inheriting the interface's
no-op default. The `Basic` path instead returns an `AuthResult` that also appends
`authorization: Bearer adas-api-key:<key>`.

This needs no `CallInfo`, no new middleware, and no change to `FlightSqlServerLifecycle` — and it is
self-scoping: the header is appended precisely when the incoming call presented `Basic`. For an ODBC
client that is the `Handshake` and nothing else, because every later call presents the marked `Bearer`
instead (whose `AuthResult` appends nothing). A client that chooses to send `Basic` on every call gets
the echo on every response, which is harmless — it is the credential that client just sent.

Rejected alternative: registering a replacement for Arrow's auth middleware so the response could be
restricted to `FlightMethod.HANDSHAKE` explicitly. It requires dropping
`FlightServer.Builder.headerAuthenticator` (`build()` registers its middleware under the reserved key
`Authorization`, and `middleware()` rejects a duplicate key) and re-implementing the peer-identity
plumbing. Strictly more code for a distinction that the credential form already draws.

### D6 — failed introspections stay uncached

Today `ApiKeyCache` caches successes only; the spec states outright that a rejected key is re-checked
against Core on every request. A client retrying bad credentials therefore hits DIAL Core once per
attempt, and `Basic` slightly widens the exposure because a wrong password in a connection dialog is
easy to retry.

Not added here, deliberately:

- `ApiKeyCache` is shared with the HTTP filter, so a negative cache is a change to HTTP behaviour and to
  a shipped requirement of the `authentication` capability — outside this change's scope.
- The retry shape does not warrant it. The ODBC driver fails the connection attempt and stops; the
  operator retypes the key. There is no reconnect loop, and no observed Core pressure.
- A negative cache has a real cost: a key that has just been *granted* a role, or a transient Core error
  cached as a rejection, would keep failing for the TTL. Getting that right needs a shorter, separate
  TTL and careful separation of "Core said no" from "Core did not answer" — a change worth making on
  evidence of retry pressure, not pre-emptively.

Note also that a wrong **username** never reaches Core at all: the sentinel check short-circuits before
introspection, so the cheapest misconfiguration to make is also free to reject.

One measured detail sharpens the cost side of this, observed during the local verification run: a
**rejected** credential costs **two** Core introspections per RPC, not one. `FlightPrincipalMiddleware`
resolves the authentication before the gate does, and since failures are not cached both resolutions
reach Core. A valid key pays this only once (the second resolution is a cache hit). This is pre-existing
and identical for a bad `Api-Key` header, but it doubles whatever retry pressure a negative cache would
be meant to absorb — worth knowing if the evidence for D6 ever arrives.

### D7 — precedence stays `Bearer` → `Api-Key` → `Basic`

`Basic` goes last, so nothing that authenticates today changes how it authenticates. `bearerToken()`
already ignores an `authorization` value that is not `Bearer `-prefixed, so `Basic` in that header falls
through the first branch untouched. A call carrying both `Bearer` and `Basic` uses the `Bearer` —
matching the HTTP filter order and the shipped "Bearer header precedence over API key" requirement.

The "credentials required" message gains the third form so an empty-credentials rejection tells a client
everything it may present.

## Risks / Trade-offs

- **BI tools persist username/password far more readily than headers** — ODBC DSNs (including
  machine-wide `HKLM` DSNs), `.pbix` files, on-premises gateway credential stores, DBeaver's credential
  file. A header is typically set per connection by whoever configures it; a password is *offered* a save
  box. The credential is not new and the exposure is not a new class of risk, but the spill surface is
  genuinely wider. → Mitigation: none available in ADAS beyond keeping the credential a DIAL API key
  whose revocation is a single Core operation that takes effect within the cache TTL. Recorded here so
  the trade-off is a decision rather than an oversight; operators should prefer short-lived keys where
  their client stores credentials at rest.
- **`Basic` over a plaintext channel exposes the key to anyone on the path** — as would the `Api-Key`
  header, identically. A typical deployment terminates TLS at the ingress
  (`useEncryption=true` on the client side). → Mitigation: no in-code enforcement is added (the Flight
  listener itself is `Location.forGrpcInsecure`, with TLS terminated in front of it, so ADAS cannot tell
  a TLS-fronted deployment from a plaintext one); the spec records that credentials on the Flight
  endpoint require a TLS-terminated deployment, exactly as they do today.
- **`Bearer adas-api-key:<key>` becomes a permanent, documented credential form.** It cannot be treated
  as an internal detail once a client has stored it. → Mitigation: specify it deliberately rather than
  leaving it implicit, and keep the marker constant. It is also independently useful for clients that
  can set a bearer token but not a custom header.
- **The API key appears in a response header** (`authorization`) on `Basic`-authenticated calls. Reverse
  proxies and ingress controllers log request headers far more often than response headers, but a
  deployment that logs response headers would capture the key. → Mitigation: it is only ever sent back to
  the client that just presented it; the alternative (a minted token) trades this for a revocation hole.
  Called out so an operator auditing log configuration knows to look.
- **Authentication work per call doubles for the `Basic` path only in the same way it already does for
  every other form** — `FlightPrincipalMiddleware` re-runs `resolveAuthentication` after the gate, so a
  `Basic` call decodes base64 twice and hits the cache twice per RPC. → Mitigation: none needed; both are
  in-memory, the Core call is cached, and this is pre-existing behaviour for `Bearer` and `Api-Key`
  alike. Consolidating the two would be a separate refactor.
- **A client that sends `Basic` on every call re-decodes and re-echoes on every response.** → Mitigation:
  negligible cost; correctness is unaffected.
- **Pre-existing, REPRODUCED, and this change makes it likelier to bite: the two API-key surfaces build
  different tokens but share one cache entry.** `ApiKeyAuthenticationFilter.authenticate` builds
  `ApiKeyAuthenticationToken(principal, appRoles + ProviderRoleAuthority.of(rawRole)…, fromProjectKey,
  project, claims)`, while `FlightHeaderAuthenticator.authenticateApiKey` uses the 2-arg convenience
  constructor — mapped application roles only, `projectKey=false`, `project=null`, no claims. Both call
  `ApiKeyCache.getOrAuthenticate` on the same bean, keyed by a digest of the raw key, so whichever surface
  introspects a key first owns the cached `Authentication` for the TTL. If Flight wins that race, a later
  HTTP request with the same key gets a token with no provider-role authorities and no project/claims
  identity, and per-table grant evaluation can deny a caller who actually holds the grant. (`FULL_ADMIN` /
  `READ_ONLY_ADMIN` gating is unaffected — those authorities are present either way.)

  **Reproduced against a running instance.** A source table whose write access list holds the
  raw provider role `viewer`, and one API key that Core resolves to `roles:["viewer"]` (so its write
  permission rests entirely on `SecurityClaimsExtractor.getProviderRoles()` containing `viewer`):

  | Which surface populated the cache entry first | `POST /v1/tables/basic_auth_probe/rows` |
  |---|---|
  | HTTP (`GET /v1/queries/entities`) | **200** `{"inserted":1}` |
  | Flight, `Basic` credential | **403** `forbidden` |
  | Flight, pre-existing `Api-Key` **header** | **403** `forbidden` |

  Same key, same access list, fresh key strings per phase so each entry had exactly one origin. The third
  row is the one that settles ownership: the `Api-Key` header path reproduces it with no `Basic` anywhere,
  so the defect is `authenticateApiKey`'s and predates this change entirely.

  → Mitigation: **none in this change.** It is not introduced here (the Flight API-key path and the shared cache both predate
  it) and the brief is explicit that the key must flow through `authenticateApiKey` unchanged; changing
  which authorities a cached token carries would alter the HTTP grant path and belongs in its own change.
  What this change does is route a whole new client class — BI tools that can only send user/password —
  through the Flight API-key path, which makes "Flight populated the cache first" a common ordering rather
  than a rare one. The fix, when taken: have the Flight path build the same token as the HTTP filter, or
  key the cache per surface. Recorded here so it is a known, scoped follow-up rather than a surprise.

## Migration Plan

Additive and backward compatible; no data or configuration migration.

1. Deploy. Existing `Bearer` and `Api-Key` callers are unaffected — their branches run first and their
   `AuthResult` appends nothing.
2. Verify with an ODBC DSN
   (`Driver={Arrow Flight SQL ODBC Driver};HOST=…;PORT=…;UID=api-key;PWD=<dial api key>;`) against a
   `SECURITY_MODE=oidc` deployment.
3. Rollback is a redeploy of the previous image. Nothing persists: no schema change, no stored token, no
   cache format change (`ApiKeyCache` entries are keyed by the digest of the key exactly as before), so
   a rolled-back instance simply stops accepting `Basic` and clients fall back to failing as they do
   today.

Mixed-version fleets are safe: instances differ only in which credential forms they accept, and any
instance accepting the marked `Bearer` re-introspects it independently.

## Open Questions

None. The one question that could have blocked the change — whether real clients re-send credentials per
call, and therefore whether a handshake answer is required — was resolved against the client sources and
is recorded as D4.
