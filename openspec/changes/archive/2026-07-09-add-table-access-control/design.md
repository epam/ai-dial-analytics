## Context

ADAS authorization today is coarse and global: two application roles (`FULL_ADMIN`,
`READ_ONLY_ADMIN`) enforced by URL/method rules. Every table-mutating endpoint is gated by
`@FullAdminOnly` (`@PreAuthorize("hasAuthority('FULL_ADMIN')")`), so any full admin can write to and
reshape any table and no one else can touch any table. The table catalog is Postgres-backed
(`table_catalog`), and callers authenticate either via Bearer JWT / opaque token or via DIAL Core
API-key introspection (`/v1/user/info`), which yields either a **project key**
(`{project, roles}`) or a **JWT-rooted key** (`{userClaims}`).

Two facts constrain the design:
- `ApiKeyAuthenticationToken` currently retains only the principal name and mapped authorities — the
  `fromProjectKey` flag and the raw user claims are discarded after role mapping. Grant evaluation
  needs them back on the security context.
- Method security (`@EnableMethodSecurity`) is enabled only in `OidcSecurityConfiguration`; in `none`
  mode `@PreAuthorize` is inert. Any per-table check must inherit that same inertness.

## Goals / Non-Goals

**Goals:**
- Let a `FULL_ADMIN` grant `WRITE` and/or `MODIFY` rights on a specific table to a `PROJECT` subject
  or a `CLAIM_RULE` subject.
- Authorize the per-table mutating endpoints (rows, schema PATCH, description PUT) by `FULL_ADMIN` OR
  a matching grant, while keeping create/delete/grant-management `FULL_ADMIN`-only.
- Keep the `none` mode behavior identical to today (no enforcement).
- Reuse the existing Postgres catalog, `ErrorView` contract, and method-security machinery.

**Non-Goals:**
- Table ownership / self-service table creation by non-admins (create stays `FULL_ADMIN`-only).
- Namespace/prefix grants or multi-tenant table isolation.
- Reusable/named claim rules (rules are inline per grant).
- Read-scoping (reads remain open to any authenticated caller).
- Grant delegation (a grantee cannot re-grant).

## Decisions

### Grant model: one permission per grant row, inline claim rule

A grant is `(table_name, subject, permission)`. `permission` is a single enum value (`WRITE` |
`MODIFY`), so a subject that needs both holds two grant rows. This keeps the row shape flat and the
uniqueness constraint simple, and matches the decision that `MODIFY` does not imply `WRITE`.

`subject` is discriminated by `subject_type` (`PROJECT` | `CLAIM_RULE`):
- `PROJECT`: `project` (text).
- `CLAIM_RULE`: `claim_name`, `claim_operator` (`EQUALS` | `CONTAINS` | `STARTS_WITH`), `claim_value`.

Alternatives considered: a set-valued `permissions` column (rejected — complicates uniqueness and
querying for little gain); named reusable claim rules (rejected as a non-goal — inline is simpler and
sufficient for this phase).

### Storage: `table_grants` in the Postgres catalog with FK cascade

```
table_grants
  id             bigserial primary key
  table_name     text not null references table_catalog(name) on delete cascade
  subject_type   text not null            -- PROJECT | CLAIM_RULE
  project        text                     -- non-null iff subject_type = PROJECT
  claim_name     text                     -- non-null iff subject_type = CLAIM_RULE
  claim_operator text                     -- EQUALS | CONTAINS | STARTS_WITH
  claim_value    text
  permission     text not null            -- WRITE | MODIFY
  created_by     text                     -- audit (SecurityClaimsExtractor.getAuthor())
  created_at     timestamptz not null default now()
  unique (table_name, subject_type, project, claim_name, claim_operator, claim_value, permission)
```

`ON DELETE CASCADE` gives grant cleanup on table delete for free (delivers the table-management
delta). A new Flyway migration under `db/migration/postgres` adds the table; the exact `name` FK
mirrors how `table_catalog` is keyed.

### Caller identity: a small abstraction populated for every auth path

Introduce a caller-identity view exposing `isProjectKey()`, `project()`, and `claims()`
(a `Map<String, Object>` of raw identity claims). It is derived from the current `Authentication`:
- **API-key project caller**: `ApiKeyAuthenticationToken` is enriched to carry `fromProjectKey=true`
  and the project name.
- **API-key JWT-rooted caller**: the token carries the introspected `userClaims`.
- **Bearer JWT / opaque caller**: claims are already on the `Jwt` / `OAuth2AuthenticatedPrincipal`;
  the identity view reads them directly.

Rationale: grant evaluation must work uniformly regardless of how the caller authenticated. The
identity view is the single place that normalizes the three token shapes. `SecurityClaimsExtractor`
is the natural home to extend, since it already centralizes reading the security context.

### Enforcement: method-security expression bean, mirroring `@FullAdminOnly`

Replace `@FullAdminOnly` on `writeRows` / `patchSchema` / `update` with
`@PreAuthorize("@tableAccess.canWrite(#name)")` and `@PreAuthorize("@tableAccess.canModify(#name)")`.
The `tableAccess` bean:
1. If the caller has `FULL_ADMIN` → allow.
2. Otherwise load the table's grants, build the caller identity, and allow iff some grant matches the
   subject and covers the required permission.

Because method security is only enabled in `oidc` mode, these annotations are inert in `none` mode —
identical to `@FullAdminOnly` today, satisfying the "inert in none mode" requirement without special
casing. `create`, `delete`, and the grant endpoints keep `@FullAdminOnly`.

Alternative considered: a servlet filter or `AccessDecisionVoter`. Rejected — the `@PreAuthorize`
SpEL-bean pattern is already the project idiom (`@FullAdminOnly`), reads the `{name}` path variable
directly, and keeps enforcement next to the endpoint.

### Claim-rule matching semantics

For a `CLAIM_RULE` grant, the named claim is looked up on the caller's claims and matched by operator:
- `EQUALS`: exact string equality, or membership if the claim is a list/collection.
- `CONTAINS`: substring for scalar claims, or membership for list claims.
- `STARTS_WITH`: prefix for scalar claims (and per-element for list claims).

A missing claim never matches. This mirrors how role claims are already multi-valued in this codebase.

### API surface

New sub-resource on the existing `TableController` (or a dedicated `TableGrantController`):
- `GET /v1/tables/{name}/grants` → list (any authenticated? — no: `FULL_ADMIN`, since it exposes the
  access model; listing is admin-only per the spec).
- `POST /v1/tables/{name}/grants` → 201 with created grant.
- `DELETE /v1/tables/{name}/grants/{grantId}` → 204.
All `@FullAdminOnly`. Validation failures → 422, duplicate → 409, unknown table/grant → 404, all via
the `ErrorView` contract and `DefaultExceptionHandler`.

## Risks / Trade-offs

- **[Grant lookup on every mutating request adds a Postgres read]** → Grants are small and
  per-table; a straightforward indexed query on `table_name` is cheap. If it ever matters, the same
  short-TTL in-memory cache pattern used for API-key introspection can be applied. Not needed now.
- **[Claims must be retained on the security context — a wider identity surface]** → Scope the
  retained claims to what grant evaluation needs and never log them; they already flow through the
  auth layer, so no new data leaves the process.
- **[Granting write on a seeded table (e.g. `dial_usage_log`) is possible]** → This is the chosen
  "any catalog table" behavior; the safeguard is that only `FULL_ADMIN` can create grants, so the
  blast radius is an explicit admin action, auditable via `created_by`.
- **[Project keys silently never match CLAIM_RULE grants (and vice versa)]** → This is intended and
  documented in the spec; the admin API and docs must make the subject/caller pairing clear to avoid
  an admin granting a claim rule expecting a project key to match.
- **[Behavior change for existing deployments]** → Marked BREAKING in the proposal, but the default
  posture is unchanged: with no grants, only `FULL_ADMIN` can mutate, exactly as today. Grants are
  purely additive.

## Migration Plan

1. Ship the Flyway migration adding `table_grants` (empty on deploy → no behavior change).
2. Enrich the auth tokens/converters to retain project flag + claims; extend the identity view.
3. Add the grant service/repository, evaluation bean, and grant API.
4. Swap the three per-table `@FullAdminOnly` annotations for the `@tableAccess` expressions.
5. Rollback: revert the annotation swap (endpoints return to `FULL_ADMIN`-only) and, if needed, drop
   the `table_grants` table; no data migration is required since grants are additive.

## Open Questions

- Should `GET /v1/tables/{name}/grants` be readable by any authenticated caller, or `FULL_ADMIN`-only?
  Current spec says `FULL_ADMIN`-only (it exposes the access model); revisit if grantees need to see
  their own grants.
- Do we want an audit trail on grant revocation (who revoked, when), or is create-time `created_by`
  sufficient for this phase? Current design records create-time audit only.
