# Design

## Storage: JSONB column, not a child table

Access lists live in a JSONB `access` column on `table_definition`
(`{"write":[...],"modify":[...]}`), not a separate table. Rationale: the model is now a small,
role-keyed set per table; the write/modify paths already load the definition row, so access is
co-located in one read; deleting a table drops its access for free. Mapped with Hibernate
`@JdbcTypeCode(SqlTypes.JSON)` over a plain `TableAccess` POJO.

## Raw provider roles as prefixed authorities

Per-table matching keys on the caller's **raw** provider role names (e.g. `analytics-writer`), which
were previously discarded after mapping to application roles. They are retained as
`GrantedAuthority`s under a `PROVIDER_ROLE_` prefix (`ProviderRoleAuthority`), alongside the mapped
`FULL_ADMIN`/`READ_ONLY_ADMIN`. This keeps a single uniform surface across all three auth shapes
(Bearer JWT, opaque, API key) and leaves `hasAnyAuthority(FULL_ADMIN, READ_ONLY_ADMIN)` URL rules and
`getRoles()` untouched — `SecurityClaimsExtractor.getRoles()` filters the prefix out, and
`getProviderRoles()` reads it back. A valid token that maps to no application role is now
**authenticated** (previously rejected), so it can reach method security and be authorized per-table.

## Grant satisfies the gate: relax three matchers, keep method security

`POST /v1/tables/*/rows`, `PATCH /v1/tables/*/schema`, and `PUT /v1/tables/*` are relaxed to
`authenticated()` at the URL layer; their existing `@PreAuthorize("@tableAccess.can*")` becomes the
sole authorization. Create/delete, reads, and `/v1/tables/*/access` fall through to the app-role rule
and stay `FULL_ADMIN`/app-role gated. Chosen over a custom `AuthorizationManager` for minimal change.
Tradeoff: single authz layer on those three endpoints — mitigated by keeping the annotations and a
negative test. Intended capability asymmetry: a caller with only a granted write role can write one
table but cannot read/list/query (those stay app-role gated).

## Out of scope

Audit of access changes (who changed a table's access, and when) is deferred to a separate future
change; it is intentionally not shipped here. Optimistic locking (`@Version`) is likewise deferred;
concurrent `access` replaces are last-writer-wins. Acceptable given access edits are admin-only and
infrequent.
