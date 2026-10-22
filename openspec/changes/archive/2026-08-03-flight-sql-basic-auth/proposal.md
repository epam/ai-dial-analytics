## Why

A large class of SQL clients can express exactly one credential: a username and a password. They offer
no way to set a custom request header and no way to hand over a bearer token. Power BI's Dremio
connector is the motivating case — its only two authentication choices are **Username/Password** and a
**Microsoft Entra account** whose OAuth resource is hardcoded to Dremio's own application registration,
so it can never name ADAS as an audience. JDBC drivers and DBeaver have the same shape.

`FlightHeaderAuthenticator` accepts only `Bearer` and `Api-Key` today, both of which those clients
cannot produce. A secured ADAS (`security.mode=oidc`) is therefore unreachable from them — not
degraded, unreachable.

There is a second, uglier symptom. Arrow's Flight handshake is a *request/response*: a client that
authenticates with user/password sends `Basic` on the `Handshake` RPC and then carries forward whatever
`authorization` value the server answered with. ADAS answers with nothing. Both the Dremio and the
GizmoSQL Arrow Flight SQL ODBC drivers take that empty answer and build a gRPC metadata entry with a
**zero-length key**, which trips a gRPC assertion and `abort()`s. The client process dies with
`0xC0000409` and no exception, no log line, no dialog. Answering the handshake removes that failure
mode in `oidc` mode.

This is deliberately **client-agnostic**. The sentinel username below is ADAS's own — it matches ADAS's
`Api-Key` header, not any vendor's convention — and nothing in the change references Dremio.

## What Changes

- **`Basic` becomes a third accepted credential form on the Flight endpoint.**
  `authorization: Basic base64(<username>:<password>)` is accepted where the username is the fixed
  sentinel `api-key` (compared case-insensitively) and the password is a DIAL API key. The key is
  validated through the existing path — `CoreApiKeyIntrospector` → `ApiKeyCache` →
  `ApiKeyAuthorityResolver` — byte for byte as the `Api-Key` header is handled today.
- **A wrong username is rejected, never ignored.** Any username other than `api-key` fails with
  `UNAUTHENTICATED` and a message naming the accepted value. Silently ignoring it would leave a user
  who typed their own email believing they had authenticated as themselves, when the principal actually
  comes from introspecting the key.
- **Credential precedence is unchanged, with `Basic` last:** `Bearer` → `Api-Key` header → `Basic`.
- **Authorization is unchanged:** the principal must carry `FULL_ADMIN` or `READ_ONLY_ADMIN`.
- **The handshake is answered** so credential-per-call clients can work at all: a `Basic`-authenticated
  call replies `authorization: Bearer adas-api-key:<the same API key>`. This is **not a minted session
  token** — see below — it is the same credential in a labelled envelope, re-introspected on every
  single call.
- **`Bearer adas-api-key:<key>` is accepted as a credential form** on the Flight endpoint, routed
  straight to the API-key path. Without an explicit marker the server would have to guess whether an
  opaque bearer value is an OIDC token or an API key, and fall back from one to the other on failure.
- Scope is `security.mode=oidc` **and** `flight.enabled=true` — the only configuration in which
  `FlightHeaderAuthenticator` exists. `security.mode=none` registers no authenticator and is
  untouched. The HTTP surface is untouched: no `Basic` on `/v1/**`.

### No session token is minted — and why the handshake must still be answered

Arrow's stock composition for user/password is `BasicCallHeaderAuthenticator` plus
`GeneratedBearerTokenAuthenticator`: handshake once, then present a server-issued token for the rest of
the session. That is rejected here. `ApiKeyCache` bounds how long a **revoked** key stays usable
(`security.api-key.cache-ttl-seconds`); a self-issued token survives to its own expiry no matter what
happened in DIAL Core. Adopting it would introduce a second credential system with its own lifetime and
no revocation channel.

But the premise that a client re-sends its credential per call was **checked against the client sources,
and it is false** for every Arrow-derived client:

| Client | Behaviour |
|---|---|
| Arrow C++ `AuthenticateBasicToken` | sends `Basic` on `Handshake` only; carries forward only a `Bearer `-prefixed value from the response (trailing metadata first, then initial). Anything else → `{"",""}`. |
| Arrow Flight SQL ODBC driver (Dremio, GizmoSQL) | pushes that pair into call headers **with no empty check** → zero-length metadata key → `abort()`. |
| Arrow Java `ClientBearerHeaderHandler` | same rule: the response must be `Bearer `-prefixed or no credential is retained for later calls. |

So accepting `Basic` per call is necessary but not sufficient: without a `Bearer `-prefixed answer to
the handshake the motivating client still crashes on connect. The resolution keeps the revocation
property that mattered while answering the handshake: the value ADAS returns **is** the API key, with a
marker so the server can recognise it, and it carries **no independent lifetime**. Every call bearing it
re-enters `authenticateApiKey` → `ApiKeyCache` → `CoreApiKeyIntrospector`. A revoked key stops working
inside the same TTL bound as the `Api-Key` header path, and there is no state on the server that could
outlive it.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `authentication`: adds `Basic` (sentinel username `api-key`) and marker-prefixed `Bearer` as accepted
  credential forms **on the Flight surface only**, both resolving to the existing API-key introspection
  path with its existing cache and revocation bound. States that no session token is issued.
- `flight-sql-endpoint`: the requirement "Flight authentication mirrors service security mode" no longer
  says credentials are header-only; it gains the `Basic` form, the sentinel-username rule, the
  three-way precedence, and the handshake response contract.

## Impact

- **Code**: `FlightHeaderAuthenticator` — a `Basic` branch and a marker branch in
  `resolveAuthentication`, plus an `AuthResult` that appends the handshake response header. No new bean,
  no new dependency, no change to `FlightSqlServerLifecycle`, `FlightPrincipalMiddleware`, or the
  producer.
- **Configuration**: no new env var and no new property. `docs/configuration.md` gains prose describing
  the accepted Flight credential forms, but no new property rows.
- **Migrations**: none.
- **Security surface**: widened deliberately and recorded in `design.md`. The credential is the same API
  key that already crosses the wire as a header on both HTTP and Flight; `Basic` over TLS is no weaker.
  What does change is that BI tools persist username/password readily — into ODBC DSNs, `.pbix` files,
  gateway credential stores — a wider spill surface than a header set deliberately per connection,
  though not a new class of risk.
- **Compatibility**: additive. Every credential accepted today is accepted unchanged and takes
  precedence. The one behavioural change for existing callers is that a successful `Basic`-authenticated
  call now carries an `authorization` response header; no current client sends `Basic`, so nothing in
  flight today is affected.
