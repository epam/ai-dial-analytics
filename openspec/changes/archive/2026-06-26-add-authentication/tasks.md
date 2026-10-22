## 1. Dependencies

- [x] 1.1 Add `spring-boot-starter-security` and `spring-boot-starter-security-oauth2-resource-server` to `build.gradle`
- [x] 1.2 Add test deps `spring-boot-starter-security-test` and `io.jsonwebtoken:jjwt-api`/`-impl`/`-jackson:0.13.0`

## 2. Multi-issuer JWT decoding

- [x] 2.1 Add `MultiIssuerJwtDecoder` (dispatch by `iss` claim) in `web/security`
- [x] 2.2 Add `NimbusJwtDecoderResolver` interface and production bean (`NimbusJwtDecoder.withJwkSetUri`)
- [x] 2.3 Add `TokenClaimsValidator` (issuer + audience) and combine with `JwtTimestampValidator`
- [x] 2.4 Add `IssuerToDecoderMapFactory` (build issuer→decoder map, attach validators, audience prefixes)
- [x] 2.5 Assemble the multi-issuer decoder as a `JwtDecoder` bean in `OidcSecurityConfiguration` (no separate factory needed for JWT-only)

## 3. Claims to roles

- [x] 3.1 Add `UserRole` enum (`FULL_ADMIN`, `READ_ONLY_ADMIN`) and `UserSecurityDetails` record
- [x] 3.2 Add `MultiPathGrantedAuthoritiesConverter` (dotted-path role-claim extraction)
- [x] 3.3 Add `UserRolesResolver` (provider+default mapping → authorities; merge handled in `IdentityProviderUtils`)
- [x] 3.4 Add `JwtAuthenticationConverter` (principal, email, roles → authentication token)
- [x] 3.5 Add `JwtAuthenticationConverterFactory` (per-issuer converter map)
- [x] 3.6 Add `@FullAdminOnly` annotation (`@PreAuthorize("hasAuthority('FULL_ADMIN')")`)

## 4. Configuration properties

- [x] 4.1 Add `IdentityProvidersProperties` record (`security.*`: mode, require-email, defaults, providers map) — replaces the reference's mutable holder
- [x] 4.2 Add `IdentityProviderUtils` (accepted issuers, default claims/mapping, fail-fast on oidc with no provider)
- [x] 4.3 Parse roles-mapping JSON via the existing Jackson 3 `JsonMapper`

## 5. Filter chain and error rendering

- [x] 5.1 Add `RestAuthenticationEntryPoint` (401 → `ErrorView` `unauthorized`) + `ErrorViewWriter`
- [x] 5.2 Add `RestAccessDeniedHandler` (403 → `ErrorView` `forbidden`) for URL-level denials
- [x] 5.3 Add `OidcSecurityConfiguration` (`oidc`): filter chain, `oauth2ResourceServer.jwt`, `@EnableMethodSecurity`, public paths, `/v1/**` requires a mapped role, exception handling
- [x] 5.4 Add `NoneSecurityConfiguration` (`none`, default): permitAll chain
- [x] 5.5 Map method-level `@FullAdminOnly` denials (`AccessDeniedException`) to 403 `forbidden` in `DefaultExceptionHandler`

## 6. Authorization wiring and principal access

- [x] 6.1 Annotate `MetadataController` create/rename/delete with `@FullAdminOnly`
- [x] 6.2 Annotate `DataSyncBackfillController` trigger with `@FullAdminOnly`
- [x] 6.3 Add `service/security/SecurityClaimsExtractor` (author/email/roles from `SecurityContextHolder`)

## 7. Configuration and documentation

- [x] 7.1 Add bare `security:` block to `application.yml` (`${ENV:default}`, no comments)
- [x] 7.2 Add Authentication + Identity Providers tables to `docs/configuration.md`
- [x] 7.3 Add `unauthorized`, `forbidden`, `invalid_token` to error codes in `openspec/project.md`

## 8. Tests

- [x] 8.1 Add `TestSecurityConfig` (`@Primary` HMAC `NimbusJwtDecoderResolver`) and `JwtTestTokens` helper
- [x] 8.2 Add `MetadataAuthIntegrationTest` (two providers → FULL_ADMIN / READ_ONLY_ADMIN)
- [x] 8.3 Cover 401 (no/malformed/expired/wrong-audience/unknown-issuer token), 403 (read-only on mutation), success (full-admin mutation, both roles read), public actuator; assert `ErrorView` body
- [x] 8.4 Keep existing tests green: default `none` mode for integration tests; `@AutoConfigureMockMvc(addFilters = false)` on the three `@WebMvcTest` controller slices

## 9b. Opaque tokens

- [x] 9b.1 Add `user-info-endpoint` to `IdentityProvidersProperties.ProviderConfig` (+ `hasJwkSetUri`/`hasUserInfoEndpoint`)
- [x] 9b.2 Generalize `MultiPathGrantedAuthoritiesConverter` to `<T extends ClaimAccessor>` (serves JWT + userinfo)
- [x] 9b.3 Add `DefaultOpaqueTokenIntrospector` (userinfo call via `RestClient.create()`, role extraction, `idp` tag)
- [x] 9b.4 Add `MultiIssuerOpaqueTokenIntrospector` (try each opaque provider)
- [x] 9b.5 Add `OpaqueAuthenticationConverter` + `OpaqueAuthenticationConverterFactory` (per-provider roles/email)
- [x] 9b.6 Switch `OidcSecurityConfiguration` to an `AuthenticationManagerResolver` routing JWS→JWT, else→opaque; split jwt/opaque providers; fail-fast on no usable provider
- [x] 9b.7 Tests: `DefaultOpaqueTokenIntrospectorTest` (MockRestServiceServer, Docker-free) + opaque cases in `MetadataAuthIntegrationTest` (in-JVM userinfo server)

## 9. Verification

- [x] 9.1 `./gradlew checkstyleMain checkstyleTest compileJava compileTestJava` — green
- [x] 9.1a `MetadataAuthIntegrationTest` — 8/8 pass (Docker up, `TESTCONTAINERS_RYUK_DISABLED=true`); `AnalyticsDataAccessApplicationTests` passes in isolation
- [x] 9.2 `./gradlew clean build` fully green — **gated on a Docker env with working Ryuk**; locally the shared PostgreSQL Testcontainer is unstable under Ryuk-disabled mode (a documented environment limitation), so run this in CI
- [x] 9.3 Manual smoke: `none` open; `oidc` 401/403/success; actuator health public
