## Context

Columns live in the Postgres catalog (`column_mapping`), managed by the table-management API and
consumed by both schema discovery and query execution. Today every authenticated caller sees every
column. We need column-level access control: columns flagged `sensitive` are reachable only by
`FULL_ADMIN`; everything else stays available to `READ_ONLY_ADMIN`.

The catalog already carries a near-identical piece of per-column metadata — `tag`: catalog-only
(never in ClickHouse DDL), nullable, declared at create, returned in responses, mutated via
`PATCH .../schema` `retag`. `sensitive` follows the same rails, adding one thing `tag` does not:
role-based filtering of the resolved query model.

Key existing seams (main tree paths):
- `service/table/ColumnMappingEntity.java` — the per-column catalog record (`tag` at ~line 48).
- `service/query/CatalogQueryService.java` — single source of truth for discovery *and* execution;
  `buildModel(...)`/`addColumn(...)` turn each `ColumnMappingEntity` into a `QueryFieldBinding` and a
  `QuerySchemaFieldDto`. Both `/queries` discovery and `/queries/execute*` and Flight consume the
  `QueryEntityModel` it produces.
- `service/security/SecurityClaimsExtractor.java` — `getRoles()` reads `SecurityContextHolder`;
  returns the caller's application-role authority names (empty in `none` mode).
- `web/security/UserRole.java` — `FULL_ADMIN`, `READ_ONLY_ADMIN` (authority name == enum name).
- DTOs: `web/dto/query/QuerySchemaFieldDto.java`, `web/dto/table/TableDto.java` (`ColumnDto`),
  `web/dto/table/CreateTableRequest.java` (`ColumnRequest`), `web/dto/table/PatchSchemaRequest.java`.
- Migrations: `src/main/resources/db/migration/postgres/` (Flyway; `V1` seeds `column_mapping`).

## Goals / Non-Goals

**Goals:**
- A catalog-only `sensitive` boolean per column, defaulting to `false`.
- Sensitive columns hidden from non-`FULL_ADMIN` callers across all query surfaces (discovery,
  structured execute, SQL execute, Flight) and in the empty-projection expansion, enforced at one
  chokepoint.
- Hidden, not forbidden: referencing a sensitive column as a non-admin is an ordinary unknown-field
  400 — no existence disclosure.
- Manage the flag through the table-management API exactly as `tag` is managed (create + PATCH + read).
- Fail open when security is disabled (`security.mode=none`).
- Seed the direct-identity/raw-content columns as sensitive: `dial_usage_log.jwt_claims`,
  `dial_usage_log.request_body`, `dial_usage_log.response_body`, `rate_analytics.comment`.
  `user_hash` and `request_tags` stay visible (see Decision 5).

**Non-Goals:**
- Row-level security or value masking/redaction (columns are all-or-nothing).
- A distinct 403 forbidden path for sensitive columns (explicitly rejected — leaks existence).
- Letting non-admins filter/group on a column they cannot select (any reference is blocked).
- Column-role filtering on the `/v1/tables` management surface (operators must see the flag to
  manage it; management endpoints are already gated appropriately at the endpoint level).
- Any new configuration property.

## Decisions

### Decision 1: Enforce by filtering the query model in `CatalogQueryService`, not in the builder

`CatalogQueryService.buildModel` is the single point where `column_mapping` rows become the
`QueryEntityModel` (`bindings` + `baseSchema`) that discovery, `execute`, `execute-sql`, and Flight
all consume. Filtering sensitive columns out *there*, for non-admin callers, means:
- Schema discovery omits them (they were never added to `baseSchema`).
- Field resolution rejects them as unknown (they were never added to `bindings`) — in every clause,
  because `bindings` is the universe the builder resolves against.
- The empty-projection "select all source columns" expansion (`StructuredQueryBuilder`) only ever
  sees non-sensitive bindings, so it excludes them automatically.

One filter, all surfaces, all clauses, plus the "hidden not forbidden" UX for free.

**Alternative considered — check referenced fields in `StructuredQueryBuilder`/`resolveField` and
throw 403:** rejected. It requires the column to stay in the model (a second enforcement point), it
leaks existence, and it does not naturally cover discovery or the empty-projection path.

### Decision 2: Role signal = `FULL_ADMIN` present, OR security disabled

The predicate is `showSensitive = securityDisabled || roles.contains("FULL_ADMIN")`. The subtlety:
`SecurityClaimsExtractor.getRoles()` returns an empty set both when auth is off (`none`) and when a
caller is authenticated but not an admin — and those must resolve oppositely. So the model build
needs a distinct "is security enabled?" signal, not just the role set. Options: inject the resolved
`security.mode` (or a small `@ConditionalOnProperty`-driven flag bean), or have the security layer
expose "security enforced" explicitly. We fail **open** only when authentication is entirely off, so
`none`-mode local development keeps seeing everything; an authenticated non-admin is filtered.

**Alternative — fail closed in `none` mode:** rejected per product decision (surprising in local development,
where `none` is the default).

### Decision 3: `CatalogQueryService` becomes role-aware; model is built per-caller

`buildModel`/`findModel` gain the caller's "may see sensitive" bit (derived via
`SecurityClaimsExtractor` + the security-enabled signal). This means the resolved model can differ
by caller, so any caching of `QueryEntityModel` must be keyed by (entity, maySeeSensitive) or scoped
per-request. Investigate current caching in `CatalogQueryService` during apply; if models are cached
per-entity, split the cache key or compute the admin/non-admin variants. If not cached, no change.

### Decision 4: Manage the flag exactly like `tag`

- `column_mapping` migration (next `V<n>`): `ADD COLUMN sensitive BOOLEAN NOT NULL DEFAULT FALSE`,
  then two seed `UPDATE`s — `... WHERE table_name='dial_usage_log' AND source_name IN
  ('jwt_claims','request_body','response_body')` and `... WHERE table_name='rate_analytics' AND
  source_name = 'comment'`.
- `ColumnMappingEntity`: add `sensitive` (primitive `boolean`, default false).
- `CreateTableRequest.ColumnRequest`: add `sensitive` (Boolean, default false) — declared at create.
- `TableDto.ColumnDto` and `QuerySchemaFieldDto`: add `sensitive` — returned in responses.
- `PatchSchemaRequest`: add a `set_sensitive` op `{name, sensitive}` (catalog-only, no ClickHouse
  `ALTER`), applied after `retag`; and thread `sensitive?` through the `add` op. Preserve `sensitive`
  across `rename`, mirroring `tag`.
- MapStruct mappers / builders that construct these records get the new field wired.

No validation beyond "boolean"; unlike `tag` there is no length/format concern. `set_sensitive`/`add`
reuse the existing unknown-column (422) and unknown-table (404) rejections.

### Decision 5: Seed only direct-identity and raw-content columns; keep `user_hash` visible

Sensitivity is seeded conservatively — only columns that directly expose identity or raw user
content: `jwt_claims` (real identity/email/claims), `request_body` and `response_body` (prompt and
completion text), and `rate_analytics.comment` (free-text user feedback).

`user_hash` is deliberately **left visible**. It is a de-identified per-user surrogate whose whole
purpose is to enable safe per-user analytics — distinct-user counts, per-user request/cost rollups,
cardinality. Hiding it would break those central read-only use cases while the actual identity leak
(`jwt_claims`) is already restricted. `request_tags` is left visible too (routing/metadata, not
content). Both remain one `PATCH set_sensitive` away if the threat model later requires blocking
per-user singling-out.

**Alternative — also mark `user_hash` sensitive:** rejected; it defeats the reason the value is
hashed in the first place and removes per-user analytics from read-only roles for little marginal
protection given `jwt_claims` is already sealed.

## Risks / Trade-offs

- [Non-admin gets a confusing "unknown field" when they name a real-but-sensitive column] → Intended
  by design (no existence disclosure). Documented in the spec so it is not read as a bug.
- [Per-caller models undermine an existing model cache] → Audit `CatalogQueryService` caching during
  apply; key any cache by the sensitive-visibility bit or compute per-request. Cheap either way.
- [Fail-open in `none` mode surprises someone who expected sensitivity to hold locally] → Explicit,
  spec'd decision; `none` means "no authz enforced" consistently with the rest of the auth spec.
- [A future non-query read surface forgets to filter] → All current query/discovery/Flight paths
  funnel through `CatalogQueryService.buildModel`; keeping the filter there (not scattered) is the
  mitigation. `/v1/tables` management is deliberately unfiltered.
- [Migration ordering] → New migration must be the next `V<n>` after the current highest; confirm the
  latest version in `db/migration/postgres` at apply time (do not hardcode `V3` if `V2`+ exist).

## Migration Plan

1. Ship Flyway migration adding the column (default false) + seed `UPDATE`s. Backward compatible:
   existing rows become `sensitive=false`; no behavior change until callers/roles differ.
2. Deploy code: model filter + DTO/API surface. In `none` mode nothing changes; in `oidc` mode
   `READ_ONLY_ADMIN` immediately stops seeing the four seeded `dial_usage_log` columns.
3. Rollback: revert code; the `sensitive` column can remain (inert with default false) — no data
   migration reversal required.

## Open Questions

- Resolved: `user_hash` and `request_tags` stay visible; sensitive seed is `jwt_claims`,
  `request_body`, `response_body` (dial_usage_log) and `comment` (rate_analytics). See Decision 5.
- Confirm the exact "security enabled" signal to inject (resolved `security.mode` value vs. a
  dedicated bean) against how `OidcSecurityConfiguration`/`NoneSecurityConfiguration` are wired —
  resolve during apply.
