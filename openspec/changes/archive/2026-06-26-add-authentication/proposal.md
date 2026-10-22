## Why

ADAS currently ships no authentication — every `/v1` endpoint (query execution, schema
discovery, metadata CRUD, data-sync backfill) is open to any caller. As ADAS becomes a runtime
gateway over analytics data, it must authenticate callers and restrict mutating operations. The
sibling service `ai-dial-admin-deployment-manager-backend` already solves this with a mature
multi-issuer OIDC setup; adopting the same model gives the DIAL platform one consistent auth story.

## What Changes

- Add Spring Security as an OAuth2 **resource server** that validates `Bearer` tokens.
- **Multi-issuer JWT** validation: accept JWTs from several configured identity providers, each with
  its own JWKS URI, accepted issuers, and audiences. Validate issuer, audience, and expiry locally.
- **Opaque-token** support: accept non-JWT bearer tokens (e.g. Google access tokens) by introspecting
  them against a configured provider's OIDC userinfo endpoint; the request is routed to the JWT or
  opaque path per token by an `AuthenticationManagerResolver`.
- **Role-based authorization**: map per-provider token role claims to two application roles,
  `FULL_ADMIN` and `READ_ONLY_ADMIN`. Mutating endpoints (metadata create/rename/delete, data-sync
  backfill trigger) require `FULL_ADMIN` via a `@FullAdminOnly` (`@PreAuthorize`) annotation; read
  endpoints are open to any authenticated user.
- A **`none` mode** (the default) that disables security for local development and tests; production
  sets the mode to `oidc`.
- **Public paths** permitted without auth: `actuator` health/info/prometheus and springdoc UI.
- Authentication (401) and authorization (403) failures return the standard `ErrorView` JSON
  contract with stable error codes `unauthorized` and `forbidden`; token validation failures inside
  the resource server surface as `invalid_token`.
- Deliberately **out of scope** for this change (deferred): HTTP basic-auth mode and DIAL Core
  API-Key authentication.

## Capabilities

### New Capabilities
- `authentication`: How ADAS authenticates callers (multi-issuer Bearer JWT validation, the `none`
  mode, public vs protected paths) and authorizes them (role mapping, `FULL_ADMIN` requirement on
  mutations), including the `ErrorView` shape for 401/403 responses.

### Modified Capabilities
<!-- No existing capability's requirements change; endpoint behavior on success is unchanged.
     Authorization rules are additive and captured in the new `authentication` spec. -->

## Impact

- **Dependencies**: add `spring-boot-starter-security` and
  `spring-boot-starter-security-oauth2-resource-server` (BOM-managed by Spring Boot 4.0.6, nimbus
  pulled transitively); test-scope `spring-boot-starter-security-test` and `io.jsonwebtoken:jjwt-*`.
- **New package** `com.epam.aidial.analytics.web.security` (filter chain, multi-issuer decoder,
  claims→roles conversion, config properties) and `service/security/SecurityClaimsExtractor`.
- **Controllers**: `MetadataController` (create/rename/delete) and `DataSyncBackfillController`
  (trigger) gain `@FullAdminOnly`.
- **Configuration**: new env-driven `security.*` and `providers.*` properties in `application.yml`;
  descriptions added to `docs/configuration.md`. New error codes documented in `openspec/project.md`.
- **Tests**: existing MockMvc/Testcontainers integration tests are unaffected because `none` is the
  default mode; new slice tests cover the `oidc` path.
