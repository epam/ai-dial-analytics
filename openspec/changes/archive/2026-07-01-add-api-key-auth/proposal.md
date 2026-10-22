## Why

ADAS authenticates `/v1/**` callers only via Bearer JWT / opaque tokens (`oidc` mode) or leaves
everything open (`none` mode). DIAL clients that call ADAS with a DIAL `Api-Key` header — project
keys or per-request JWT-rooted keys minted by DIAL Core — have no way to authenticate. The sibling
service `ai-dial-admin-deployment-manager` already supports this; ADAS should behave the same so
DIAL consumers can reach it with the same credentials.

## What Changes

- Add API-key authentication in `oidc` mode, gated by `security.api-key.enabled` (default `false`).
- A caller may present an `Api-Key` header; ADAS introspects it against DIAL Core `/v1/user/info`
  and authorizes by mapped application role (`FULL_ADMIN` / `READ_ONLY_ADMIN`).
- Support both key shapes Core returns: project-keys (`project`, roles mapped via
  `security.api-key.roles-mapping`) and JWT-rooted per-request keys (`userClaims`, roles mapped via
  `security.defaults.roles-mapping`).
- The `Authorization` (JWT/opaque) header takes precedence: when present, the `Api-Key` header is
  ignored.
- Introspection results are cached (Caffeine, keyed by a SHA-256 of the key) with configurable TTL
  and size; an optional startup probe verifies Core reachability.
- Failure modes surface through the standard `ErrorView`: invalid/malformed/unmapped key →
  `401 unauthorized`; Core unreachable → `503 service_unavailable`; no mapped authority → `403`.
- New env-driven config under `security.api-key.*`; no behavior change in `none` mode or for
  existing Bearer-token auth.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `authentication`: adds API-key authentication as an additional credential accepted in `oidc`
  mode, including Core introspection, the two key shapes, JWT-header precedence, and the
  `401`/`503` failure modes.

## Impact

- Code: new `web/security/apikey/` package (filter, Core introspector, cache, properties, authority
  resolver, token); wiring in `web/security/OidcSecurityConfiguration`. Reuses existing
  `UserRolesResolver`, `UserRole`, `UserSecurityDetails`, `ErrorView`, `ErrorViewWriter` unchanged.
- Config: new `security.api-key.*` block in `application.yml`; env vars `API_KEY_*`; descriptions in
  `docs/configuration.md`.
- Dependencies: add Caffeine (`com.github.ben-manes.caffeine:caffeine`).
- Runtime: when enabled, ADAS makes an outbound HTTP call to DIAL Core `/v1/user/info` per uncached
  API key; requires `API_KEY_CORE_URL` to be reachable.
