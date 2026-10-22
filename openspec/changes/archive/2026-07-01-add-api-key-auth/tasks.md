## 1. Dependencies & config

- [x] 1.1 Add `implementation 'com.github.ben-manes.caffeine:caffeine:3.2.3'` to `build.gradle`
- [x] 1.2 Add the `security.api-key.*` block to `application.yml` (bare `key: ${ENV:default}` lines:
      `enabled`/`API_KEY_ENABLED:false`, `core-url`/`API_KEY_CORE_URL:`,
      `cache-ttl-seconds`/`API_KEY_CACHE_TTL_SECONDS:60`, `cache-max-size`/`API_KEY_CACHE_MAX_SIZE:10000`,
      `request-timeout-ms`/`API_KEY_REQUEST_TIMEOUT_MS:3000`, `roles-mapping`/`API_KEY_ROLES_MAPPING:`,
      `startup-probe`/`API_KEY_STARTUP_PROBE:true`)

## 2. apikey package (`web/security/apikey/`)

- [x] 2.1 `ApiKeyProperties` — `@ConfigurationProperties("security.api-key")`,
      `@ConditionalOnProperty("security.api-key.enabled"=true)`; inject `JsonMapper` +
      `@Value("${security.defaults.roles-mapping:}")`; `@PostConstruct validate()` requires `core-url`,
      parses both role maps, fails if both empty
- [x] 2.2 `IntrospectionResult` record (`principal`, `email`, `rawRoles`, `fromProjectKey`)
- [x] 2.3 `CoreApiKeyIntrospector` — `RestClient` GET `${core-url}/v1/user/info` with `Api-Key` header,
      body read as `String` + parsed with `JsonMapper`; connect/read timeouts; parse `project` vs
      `userClaims`; principal/email from `security.defaults.principal-claim:oid` /
      `email-claim:unique_name`; Core 4xx/5xx → `BadCredentialsException`, connection failure →
      `AuthenticationServiceException`; `@PostConstruct probeCore()` guarded by `startup-probe`
- [x] 2.4 `ApiKeyAuthorityResolver` — two `UserRolesResolver`s (project-key map vs defaults map)
- [x] 2.5 `ApiKeyCache` — Caffeine cache keyed by JDK SHA-256 (`MessageDigest` + `HexFormat`),
      TTL/max-size from properties; only caches successful authentications
- [x] 2.6 `ApiKeyAuthenticationToken` extends `AbstractAuthenticationToken` (principal = project/user)
- [x] 2.7 `ApiKeyAuthenticationFilter` extends `OncePerRequestFilter`,
      `@ConditionalOnProperty("security.api-key.enabled"=true)`; skip if `Authorization` present or key
      blank; `cache.getOrAuthenticate` → set `SecurityContext`; write `ErrorView` via `ErrorViewWriter`
      (`401 unauthorized` / `503 service_unavailable`); deny (unauthenticated token) when no authority

## 3. Wire into security chain

- [x] 3.1 In `OidcSecurityConfiguration.securityFilterChain`, inject
      `ObjectProvider<ApiKeyAuthenticationFilter>` and
      `apiKeyFilter.ifAvailable(f -> http.addFilterBefore(f, BearerTokenAuthenticationFilter.class))`
- [x] 3.2 Add `@Bean @ConditionalOnBean(ApiKeyAuthenticationFilter.class)` returning a
      `FilterRegistrationBean` with `setEnabled(false)` to suppress servlet auto-registration

## 4. Docs

- [x] 4.1 Add `#### API-key authentication` env-var table under Authentication in `docs/configuration.md`
- [x] 4.2 Add one sentence to the Authentication prose noting the `Api-Key` header path and JWT precedence

## 5. Tests

- [x] 5.1 `CoreApiKeyIntrospectorTest` — project-key shape; userClaims shape; missing principal → bad
      creds; neither field → bad creds; Core 4xx → `BadCredentialsException`; connection error →
      `AuthenticationServiceException` (RestClient + `MockRestServiceServer`)
- [x] 5.2 `ApiKeyPropertiesTest` — blank `core-url` throws; both role maps empty throws; valid parse
- [x] 5.3 `ApiKeyAuthenticationSecurityTest` — `security.mode=oidc`, `api-key.enabled=true`, Core
      stubbed: project-key `FULL_ADMIN` allowed on a mutating endpoint; viewer role → 200 read /
      403 mutate; invalid key → 401; `Authorization` header precedence; blank key falls through;
      same key served from cache (1 Core call across N requests); JWT-rooted key via defaults map

## 6. Verify

- [x] 6.1 `./gradlew checkstyleMain checkstyleTest` passes
- [x] 6.2 `./gradlew test --tests '*ApiKey*'` passes (no Docker); full `clean build` when Docker available
- [x] 6.3 `openspec validate add-api-key-auth` passes
