## 1. Accept the Basic credential form

- [x] 1.1 In `FlightHeaderAuthenticator`, add the constants for the new credential form: the sentinel
      username `api-key`, the `Basic ` scheme prefix, and the bearer marker `adas-api-key:`.
- [x] 1.2 Add a private `basicCredential(CallHeaders)` helper that returns the decoded
      `<username>:<password>` pair from an `authorization: Basic …` value, or `null` when the header is
      absent or carries another scheme. Split on the **first** colon (a password may contain colons).
      Reject non-base64 input and a decoded value with no colon with `UNAUTHENTICATED`, without
      disclosing which part was malformed.
- [x] 1.3 Add the `Basic` branch to `resolveAuthentication` **after** the existing `Bearer` and
      `Api-Key` branches, so precedence stays `Bearer` → `Api-Key` → `Basic`. Reject a username other
      than the sentinel (case-insensitive compare) with `UNAUTHENTICATED` and a message naming the
      accepted username, **before** any introspection. Otherwise delegate the password to the existing
      `authenticateApiKey`, unchanged.
- [x] 1.4 Route a bearer value beginning with the `adas-api-key:` marker (case-insensitive) to
      `authenticateApiKey` on the stripped remainder, ahead of the JWT/opaque decision in
      `authenticateBearer`, so no OIDC round trip is attempted for it.
- [x] 1.5 Extend the "credentials required" message to name all three accepted forms.

## 2. Answer the handshake without minting a token

- [x] 2.1 Restructure `resolveAuthentication` so `authenticate` can tell whether the call presented the
      `Basic` form: introduce a private carrier (e.g. a record holding the `Authentication` plus the
      optional outgoing `authorization` value) resolved by one private method, with the public
      `resolveAuthentication(CallHeaders)` returning just the `Authentication` so
      `FlightPrincipalMiddleware` is unaffected.
- [x] 2.2 Have `authorized(...)` return an `AuthResult` that overrides `appendToOutgoingHeaders` to add
      `authorization: Bearer adas-api-key:<the presented key>` when — and only when — the call
      authenticated via the `Basic` form. Every other form keeps today's peer-identity-only result whose
      `appendToOutgoingHeaders` is the interface's no-op.
- [x] 2.3 Update the class javadoc: three credential forms, their precedence, the handshake response and
      why it is not a session token (the value is the API key, re-introspected per call).

## 3. Tests (`FlightHeaderAuthenticatorTest`)

- [x] 3.1 `api-key` + a valid key → authenticated, peer identity is the key's principal, roles resolved
      from the introspection result.
- [x] 3.2 `API-KEY` and `Api-Key` usernames → accepted (case-insensitive sentinel).
- [x] 3.3 Any other username with a valid key → `UNAUTHENTICATED`, message names `api-key`, and
      `introspector.introspect` is never called.
- [x] 3.4 `api-key` + a key Core rejects → `UNAUTHENTICATED` with the same description the `Api-Key`
      header path produces.
- [x] 3.5 `api-key` + a valid key whose roles map to nothing → `UNAUTHORIZED` (gRPC PERMISSION_DENIED).
- [x] 3.6 Malformed `Basic` values → `UNAUTHENTICATED`: not base64, and base64 of a string with no colon.
- [x] 3.7 Precedence: a call carrying both `Bearer` and `Basic` uses the bearer and never introspects the
      key; a call carrying both `Api-Key` and `Basic` uses the `Api-Key` header.
- [x] 3.8 Handshake answer: a `Basic`-authenticated `AuthResult.appendToOutgoingHeaders` writes
      `authorization: Bearer adas-api-key:<key>` into the outgoing headers; a `Bearer`- and an
      `Api-Key`-authenticated result write nothing.
- [x] 3.9 The marked bearer value authenticates as the same principal as the raw key, and introspection
      runs on the **stripped** key (assert the argument passed to the introspector).
- [x] 3.10 Per-call revalidation with no session credential: authenticate once via `Basic`, then present
      the returned marked bearer value after the introspector starts rejecting the key, and assert the
      later call fails `UNAUTHENTICATED` — nothing issued by the handshake keeps it working. Also assert
      the introspection goes through `ApiKeyCache.getOrAuthenticate` for both forms, so the TTL bound is
      the shared one.
- [x] 3.11 `security.mode=none` unchanged, in `FlightSqlIntegrationTest` (which runs in that mode): a
      query carrying a `Basic` header whose username the sentinel rule forbids still succeeds, proving no
      authenticator is registered and credentials are not inspected rather than merely not required.
- [x] 3.12 End-to-end over real gRPC, in a new `FlightBasicAuthIntegrationTest` (oidc mode +
      `security.api-key.enabled=true` against an in-JVM DIAL Core stub): drive
      `FlightClient.authenticateBasicToken`, which is the ODBC drivers' own flow, and assert the returned
      credential is present (i.e. the handshake was answered in the form they require), that the captured
      response header is exactly `Bearer adas-api-key:<key>`, that `GetTables` then succeeds with it, that
      a wrong username fails without reaching Core, that an unmapped role is `UNAUTHORIZED`, and that Core
      is introspected once across the handshake and two subsequent calls.
- [x] 3.13 A blank bearer value (`authorization: Bearer ` with nothing after the scheme) yields
      `UNAUTHENTICATED`, not the gRPC `INTERNAL` that `BearerTokenAuthenticationToken`'s non-blank assertion
      would otherwise produce — the endpoint's own stated contract for a rejected credential.

## 4. Specs and docs

- [x] 4.1 Keep the delta specs in `openspec/changes/flight-sql-basic-auth/specs/` aligned with what
      shipped — `flight-sql-endpoint` (the modified authentication requirement) and `authentication`
      (the added envelope requirement plus the clarified bearer-precedence requirement).
- [x] 4.2 README, `## Arrow Flight SQL endpoint`: extend the existing "Authentication follows
      `security.mode` just like HTTP" sentence to name the three credential forms and the `api-key`
      sentinel username in prose, linking to `docs/configuration.md`. No env-var table (no new
      properties, and property descriptions live only in `docs/configuration.md`).
- [x] 4.3 `docs/configuration.md`, Arrow Flight SQL section: document the accepted credential forms
      alongside the existing Flight properties — including that the `Basic` username is the fixed
      sentinel `api-key`, that the password is a DIAL API key, and that no session token is issued. No
      new property rows, since the change adds no configuration.

## 5. Verify

- [x] 5.1 `./gradlew clean build` passes (compile + checkstyle + tests).
- [x] 5.2 Run locally with `FLIGHT_ENABLED=true` and `SECURITY_MODE=oidc` via `./gradlew bootRun` (not an
      IDE run configuration — `build.gradle` attaches the Arrow `--add-opens` JVM flags only to the
      `test` and `bootRun` tasks, and without them every `doGet` fails with a bare `internal error`).
      Confirm: a `Basic` handshake with the sentinel username and a valid key returns the marked bearer
      value and a query succeeds; a wrong username is rejected with the message naming `api-key`.

      **Done** — against the local ClickHouse + PostgreSQL stack with a stub DIAL Core, driven from
      `pyarrow.flight`, whose `authenticate_basic_token` binds the same C++
      `FlightClient::AuthenticateBasicToken` the ODBC drivers call (so it enforces the same
      `Bearer `-prefix rule on the handshake answer). 12/12 cases passed: the handshake returned exactly
      `Bearer adas-api-key:<key>` and that credential ran `SELECT count(*) FROM dial_usage_log`;
      `API-KEY`/`Api-Key` accepted; a wrong username rejected `UNAUTHENTICATED` with
      `Basic credentials must use the username "api-key"…`; invalid key and malformed `Basic` rejected;
      no credentials rejected with all three forms named; the `Api-Key` header path unregressed; the
      marked bearer accepted on a fresh connection and rejected for a bad key; and `Basic` accepted
      per call with no handshake at all. Smoke: health `UP`, `/v1/does-not-exist` → 404 `not_found`
      with credentials and 401 `unauthorized` without. HTTP boundary confirmed at runtime: `Basic`
      alone → 401, `Basic` + valid `Api-Key` → 401, `Api-Key` alone → 200.
- [x] 5.3 The ODBC DSN check against a real Arrow Flight SQL ODBC driver and a running deployment behind
      TLS (`useEncryption=true`) is **not** covered — no Windows ODBC
      driver was available for verification and no deployment ran this build yet. The C++
      client code path the driver uses *is* covered by 5.2 via pyarrow; what remains unverified is the
      driver's own DSN plumbing (`UID`/`PWD` → `AuthenticateBasicToken`) and TLS at the ingress.
