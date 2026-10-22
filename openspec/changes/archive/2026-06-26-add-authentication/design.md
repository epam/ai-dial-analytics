## Context

ADAS is a Spring Web MVC (servlet) service on Spring Boot 4.0.6 / Java 25 with no security today.
The sibling `ai-dial-admin-deployment-manager-backend` already implements multi-issuer OIDC with
role-based authorization; we port its **multi-issuer JWT path** and adapt it to ADAS conventions.
ADAS uses Jackson 3 (`tools.jackson` `JsonMapper`, configured in `JsonMapperConfiguration`),
env-driven `@ConfigurationProperties`, `/v1` endpoint paths, and a universal `ErrorView` JSON error
contract rendered by `DefaultExceptionHandler`.

## Goals / Non-Goals

**Goals:**
- Validate `Bearer` JWTs from multiple configured identity providers (issuer + audience + expiry).
- Accept opaque (non-JWT) bearer tokens by introspecting them against a provider's userinfo endpoint,
  routing per token via an `AuthenticationManagerResolver` (JWS → JWT path; else → opaque path).
- Map per-provider role claims to `FULL_ADMIN` / `READ_ONLY_ADMIN`; require `FULL_ADMIN` for
  mutations via a `@FullAdminOnly` (`@PreAuthorize`) annotation; reads open to any authenticated caller.
- Keep a `none` mode (default) so local dev and existing tests run unauthenticated.
- Return the `ErrorView` JSON for 401/403, matching the rest of the API.
- Stay close to the reference implementation so behavior and future maintenance are consistent.

**Non-Goals:**
- HTTP basic-auth mode.
- DIAL Core API-Key authentication (can be added later behind the same filter pattern).
- Custom userinfo role-derivation functions (the reference's `fn:` converters); opaque providers read
  roles from the userinfo response via the same `role-claims` paths as JWT.
- Per-tenant or fine-grained resource-level authorization beyond the two roles.

## Decisions

**JWT + opaque resource server (no basic/API-Key).** The filter chain configures
`oauth2ResourceServer.authenticationManagerResolver(...)`: the resolver reads the bearer token and, if
it parses as a JWS, routes to a `ProviderManager(JwtAuthenticationProvider)` backed by the custom
`MultiIssuerJwtDecoder` + per-issuer `JwtAuthenticationConverter`; otherwise it routes to a
`ProviderManager(OpaqueTokenAuthenticationProvider)` backed by a `MultiIssuerOpaqueTokenIntrospector`
+ per-provider `OpaqueAuthenticationConverter`. Alternative considered: Spring's built-in
`JwtIssuerAuthenticationManagerResolver` — rejected because it does not support the per-issuer audience
validation, Azure v1/v2 alias issuer expansion, or per-issuer role converters we preserve for parity,
and it has no opaque path.

**Opaque introspection.** An opaque token carries no issuer, so each configured opaque provider's
userinfo endpoint is tried in turn (`MultiIssuerOpaqueTokenIntrospector`). `DefaultOpaqueTokenIntrospector`
calls the userinfo endpoint with the bearer token via a `RestClient` (built with `RestClient.create()`
to avoid depending on an autoconfigured `RestClient.Builder` bean), extracts provider roles from the
response with the same dotted-path `MultiPathGrantedAuthoritiesConverter` used for JWTs, and tags the
principal with the originating provider name (`idp` attribute) so the per-provider
`OpaqueAuthenticationConverter` (roles mapping + email) can be selected. Authorities-converter is
generalized to any `ClaimAccessor`, so the same role extraction serves JWT claims and userinfo attributes.

**Multi-issuer decoding.** Port `MultiIssuerJwtDecoder` (reads `iss`, dispatches to a per-issuer
`NimbusJwtDecoder`), `IssuerToDecoderMapFactory` (builds the issuer→decoder map and attaches a
`DelegatingOAuth2TokenValidator` of `TokenClaimsValidator` + `JwtTimestampValidator`),
`TokenDecoderFactory(Impl)`, and a `NimbusJwtDecoderResolver` interface so tests can swap the
production JWKS decoder for an HMAC decoder.

**Claims → roles.** Port `MultiPathGrantedAuthoritiesConverter` (dotted-path role extraction),
`JwtAuthenticationConverter`, `JwtAuthenticationConverterFactory` (per-issuer converters),
`UserRolesResolver` + `RolesMappingResolver` (provider+default mapping), `UserRole` enum,
`UserSecurityDetails` record, and the `@FullAdminOnly` annotation
(`@PreAuthorize("hasAuthority('FULL_ADMIN')")`). `@EnableMethodSecurity` is placed on the `oidc`
config only, so the annotation is a no-op in `none` mode.

**ErrorView for 401/403 (improvement over reference).** The reference returns Spring's default
empty-body 401/403. ADAS requires the `ErrorView` JSON everywhere, so we add a custom
`AuthenticationEntryPoint` (401 → `unauthorized`) and `AccessDeniedHandler` (403 → `forbidden`)
serializing `ErrorView`, wired via `.exceptionHandling(...)`. These run inside the security filter
chain (before the dispatcher), which is why `DefaultExceptionHandler` cannot handle them.

**Config style.** Scalars live in env-driven `security.*` properties; providers live in a
`providers.*` map. The providers holder is a mutable `@ConfigurationProperties` class with
`@PostConstruct` validation (fail fast when `oidc` and no valid provider) rather than an immutable
record, because a map-of-nested-config with validation does not fit ADAS's record style cleanly —
a justified, localized deviation. Roles-mapping JSON is parsed with the existing Jackson 3
`JsonMapper`.

**Apache Commons `StringUtils` for parity.** The reference uses Apache Commons (`StringUtils`,
`MapUtils`, `CollectionUtils`). We use `org.apache.commons.lang3.StringUtils` for blank/empty string
checks so the ported security code stays aligned with the deployment-manager service (easing
cross-service maintenance); it is BOM-managed (commons-lang3 3.20.0) and already on the classpath
transitively, so this adds no version risk. Collection/map checks remain plain JDK for now.

**Default mode `none`.** `application.yml` defaults `security.mode=${SECURITY_MODE:none}`; a real deployment
sets `SECURITY_MODE=oidc`. This keeps the existing MockMvc/Testcontainers integration tests passing
unchanged.

## Risks / Trade-offs

- [Existing tests break if `oidc` becomes default] → Default `none`; confirm no test sets
  `SECURITY_MODE=oidc` globally.
- [Jackson 3 enum deserialization of roles-mapping `Map<String,Set<UserRole>>`] → Reuse the
  configured `JsonMapper`; cover with a unit test asserting the mapping parses and applies.
- [Azure alias issuer expansion carried over but unused] → Harmless when `aliases` is empty; keep for
  fidelity, easy to delete later.
- [springdoc UI permitted publicly] → Acceptable for an internal gateway; can be gated behind a
  toggle later if needed.
- [Security filter chain bypasses `DefaultExceptionHandler`] → Mitigated by the custom entry
  point/handler; covered by tests asserting the `ErrorView` body on 401 and 403.

## Migration Plan

Additive change. Deploy with `SECURITY_MODE=oidc` and provider configuration set from the secret
store. Rollback is setting `SECURITY_MODE=none` (or reverting), which restores open access with no
data migration. No schema or persisted-state changes.
