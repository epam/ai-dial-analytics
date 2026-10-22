## Context

ADAS's `oidc` security is an OAuth2 resource server (`OidcSecurityConfiguration`) that authenticates
Bearer JWT/opaque tokens via an `AuthenticationManagerResolver` and authorizes `/v1/**` by
application role. The sibling `ai-dial-admin-deployment-manager` (DM) already implements DIAL
`Api-Key` auth as a `OncePerRequestFilter` layered before the Bearer filter, introspecting keys
against DIAL Core `/v1/user/info`. ADAS shares DM's security lineage (`UserRole`,
`UserRolesResolver`, `UserSecurityDetails`, `ErrorView`, `RestAuthenticationEntryPoint`), so DM's
`apikey` package ports over with minimal adaptation.

## Goals / Non-Goals

**Goals:**
- Accept a DIAL `Api-Key` header in `oidc` mode, gated by `security.api-key.enabled`.
- Full behavioral parity with DM: Core introspection, project-key + JWT-rooted key shapes, two role
  mappings, JWT-header precedence, Caffeine caching, startup probe, `401`/`503` failure modes.
- Fit ADAS conventions: `security.api-key.*` config namespace, `tools.jackson` `JsonMapper`, the
  4-arg `ErrorView` + `ErrorViewWriter`, and the `RestClient` HTTP style already used by
  `DefaultOpaqueTokenIntrospector`.

**Non-Goals:**
- No API-key support in `none` mode (beans are conditional on `enabled` and only wired in `oidc`).
- No change to existing Bearer-token behavior or role mappings.
- No local/static key comparison — validation is always delegated to DIAL Core.
- No new REST endpoints.

## Decisions

- **Package**: new `com.epam.aidial.analytics.web.security.apikey` with `ApiKeyProperties`,
  `CoreApiKeyIntrospector`, `IntrospectionResult`, `ApiKeyAuthorityResolver`, `ApiKeyCache`,
  `ApiKeyAuthenticationToken`, `ApiKeyAuthenticationFilter` — mirroring DM one-to-one.
- **Gating**: every bean is `@ConditionalOnProperty("security.api-key.enabled"=true)`; the filter is
  wired only inside `OidcSecurityConfiguration` (itself `@ConditionalOnProperty security.mode=oidc`)
  via `http.addFilterBefore(filter, BearerTokenAuthenticationFilter.class)`, plus a
  `FilterRegistrationBean(enabled=false)` to suppress duplicate servlet-level registration.
- **HTTP client**: use ADAS `RestClient` (not DM's `RestTemplateBuilder`); read the response body as
  `String` and parse with `JsonMapper` to sidestep Core labelling JSON as `application/octet-stream`;
  set connect/read timeouts from `request-timeout-ms`. Core 4xx/5xx → `BadCredentialsException`;
  connection failure/timeout → `AuthenticationServiceException`.
- **Role mapping**: reuse the existing `UserRolesResolver`. Project-key roles use a parsed
  `security.api-key.roles-mapping`; JWT-rooted roles use the parsed `security.defaults.roles-mapping`
  (same map OIDC callers use). Principal/email claim names default to ADAS's `oid` / `unique_name`.
- **Cache key hashing**: JDK `MessageDigest` (SHA-256) + `HexFormat` rather than `commons-codec`
  `DigestUtils`, avoiding a new dependency. Caffeine is added for the cache itself.
- **Errors**: build `ErrorView(request, status, code, message)` with codes `unauthorized` (401) and
  `service_unavailable` (503) and render via the shared `ErrorViewWriter`, matching
  `RestAuthenticationEntryPoint`.

## Risks / Trade-offs

- **Outbound dependency on DIAL Core**: per-uncached-key HTTP call adds latency and a failure
  surface; mitigated by the Caffeine cache and a `503` (not `500`) on Core unavailability, plus an
  optional startup probe.
- **Full `./gradlew clean build` needs Docker** (Testcontainers ClickHouse/Postgres); the new logic
  is covered by fast unit/slice tests (`RestClient` + `MockRestServiceServer`, `@WebMvcTest`) that
  run without Docker.
- **Config surface**: two role mappings (api-key vs defaults) can confuse operators; validation at
  startup fails fast if both are empty while enabled, with a message naming both properties.
- **Deviation from DM internals** (RestClient vs RestTemplate, JDK hashing vs commons-codec) means
  the port is not byte-identical; behavior is equivalent and adaptations are documented here.
