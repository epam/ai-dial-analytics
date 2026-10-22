## Why

ADAS's global authorization axis has exactly two values, so `FULL_ADMIN` carries **sixteen unrelated
capabilities** — catalog DDL, grant management, evaluator/rule/pipeline authoring, backfill
triggering, `common` saved-query publishing, and `sensitive` column read. Reading one PII column
therefore requires the authority to drop tables and rewrite access lists. That is a least-privilege
violation, not a granularity gap, and it is visible in shipped code rather than merely inferable:
`SavedQueryController` justifies its `common`-write gate as "requires `FULL_ADMIN` — the same role
entitled…" (publishing a shared query reasoned about by reference to PII entitlement), and
`SystemIdentity` grants full `FULL_ADMIN` to a scheduler thread that needs exactly one capability,
then spends a javadoc paragraph arguing that is safe. Nothing narrower exists to grant.

The field made the **privilege** the unit of authorization and a **role** a named bundle of
privileges decades ago — Snowflake ships `SECURITYADMIN` holding `MANAGE GRANTS` separately from
`SYSADMIN`; PostgreSQL separates role attributes from object privileges and ships `pg_read_all_data`
as a grantable role rather than a special case in the checker; Unity Catalog splits `MANAGE`,
`BROWSE` and `READ METADATA`. ADAS made the role the unit, so every new capability had to be
attached to one of two existing bundles.

This is also the prerequisite for per-table read grants: a `read` list underneath a role that
bypasses every grant delivers a fraction of its value, so this change is sequenced **before or
with** `add-table-read-grants`. That change was retired unarchived when this one landed: its per-table
`read` list is one of the five grant keys here, and its authorizer and chokepoint work were absorbed.

## What Changes

- **Sixteen atomic privileges** replace `UserRole` as the unit of authorization, named
  `<OBJECT>_<VERB>` over five object classes (`TABLE`, `REGISTRY`, `AUDIT`, `SAVED_QUERY`,
  `DATA_SYNC`). Six are table-scoped — holdable globally *or* checked per table, with effective
  access the union — and five of those are grantable per table (all but `TABLE_LIST`).
- **A four-rung table read ladder** — `TABLE_LIST` → `TABLE_GET` → `TABLE_READ` →
  `TABLE_READ_SENSITIVE` — with implication decided by one stated rule: an atom implies another iff
  **disclosure-monotone**. `TABLE_READ_SENSITIVE` is a rung, not a modifier, so it is never inert.
  `TABLE_WRITE` implies nothing (**the `WRITE → READ` confusion is the escalation this closes**).
- **Five per-table grant keys** in the existing `access` JSONB: `get`, `read`, `read_sensitive`,
  `write`, `modify`. Stored as sent; visibility derived at check time. There is no `list` key:
  `TABLE_LIST` held globally lists every table, and on one table it is conferred by any grant that
  already shows it (design D12).
- **Four bundles** expanded at config-parse time, never seen by any gate: `FULL_ADMIN`,
  `READ_ONLY_ADMIN` (both unchanged in meaning), plus `READ_ONLY_ANALYTIC` and
  `READ_ONLY_SENSITIVE_ANALYTIC`. The latter is what finally makes PII read reachable without
  administrative authority. `roles-mapping` accepts a bundle name or an atom name, mixed; an unknown
  token **fails startup** instead of being dropped.
- **BREAKING — `sensitive` flag changes require `TABLE_GRANT`, not `TABLE_MODIFY`.**
  `PATCH /v1/tables/{name}/schema` guards `sensitive` only when *setting* it, so clearing it is
  unrestricted today and a per-table `modify` grantee can declassify any column and then read it.
  Disclosure monotonicity governs what an atom reveals; this is the second axis — what an atom lets
  you *grant yourself* — and every future atom is checked on both.
- **BREAKING — system tables become grantable on the read side.** `dial_usage_log` and
  `rate_analytics` reject *every* `PUT .../access` today (`409 table_is_system` via
  `requireModifiable()`), which makes per-table read grants inapplicable to the primary analytics
  table. The refusal moves from the object to the keys: read-side keys are always settable,
  `write`/`modify` are refused only when **non-empty**, and an empty `write`/`modify` passes so a
  five-key PUT succeeds. Contradicts nothing in the flag's intent — its own migration says a system
  table "stays fully readable and queryable".
- **BREAKING — `PUT .../access` becomes absent-keeps / `[]`-revokes.** It is a full replace today, so
  adding three keys would make every existing two-key client silently revoke all read grants on each
  PUT.
- **BREAKING — `GET /v1/tables/{name}/access` requires `TABLE_GRANT`.** It discloses grantee role
  names, i.e. organisational structure, to every catalog reader today.
- **Per-owning-table visibility for composite entities.** A queryable entity is a source flattened
  with its active enrichments, so visibility is decided per owning table at the single point where
  the `sensitive` filter already sits. An entity is visible iff its source is; an unreadable
  enrichment's columns are absent (row counts unchanged, since the grain join is 0..1). An
  enrichment-only `read` grant is declared **inert** and the `PUT` says so.
- **Fail-closed enforcement.** The `/v1/**` `hasAnyAuthority` rule becomes `.authenticated()`, so
  roughly 25 read endpoints lose their only gate; a test asserts every request mapping carries
  exactly one authorization annotation, with `denyAll` as the default. Flight gets per-RPC atom
  checks rather than a role test.
- **One `SecurityMode` bean, validated at startup.** The mode is derived nine ways in four
  semantics today, so `SECURITY_MODE=OIDC` leaves the Flight port unauthenticated while HTTP
  enforces, and `SensitiveColumnAccess` fails open and serves sensitive columns to anonymous Flight
  callers. The same bean replaces five independent `none`-mode fail-open checks.
- **One token-building function for all four entry points.** Flight's API-key path omits
  `PROVIDER_ROLE_*` authorities while the three HTTP paths add them, and both share one
  `ApiKeyCache` entry — so whichever surface introspects first decides whether per-table grants can
  match at all. The BI-tool persona is exactly who read grants are for.
- **No endpoint widens.** Every bundle expansion reproduces today's reach exactly; pipeline reads are
  already open to any application role, which `REGISTRY_READ` matches. `SystemIdentity` narrows from every
  privilege to `{TABLE_READ, TABLE_READ_SENSITIVE}`.
- **Not in scope**, recorded in design.md: column-level grants replacing the binary `sensitive` flag,
  row-level policies, grantee types beyond provider roles, schema/tag-scoped grants, incremental
  `GRANT`/`REVOKE`, delegated ownership (a table-scoped `TABLE_GRANT`), and splitting
  `REGISTRY_ADMIN` per object kind.

## Capabilities

### New Capabilities

None — this replaces the authorization vocabulary of existing capabilities rather than adding a
surface.

### Modified Capabilities

- `authentication`: privileges replace `UserRole` as the unit; bundles are config-expanded;
  role-based URL rules give way to per-endpoint atom checks with a fail-closed default;
  `security.mode` is validated at startup and derived once; all four authentication entry points
  build one token shape.
- `table-access-control`: the access model gains three keys; the read ladder and its
  disclosure-monotone implication rule are specified; visibility is derived at check time, never
  expanded at grant time; the `FULL_ADMIN` short-circuit is replaced by holding the atom globally;
  system tables accept read-side grants; `PUT` becomes absent-keeps; effective `permissions` reports
  five flags.
- `table-management`: catalog reads are caller-filtered (`GET /v1/tables` at `TABLE_LIST`, single
  get and revision at `TABLE_GET`, access read at `TABLE_GRANT`); a `sensitive` flag change requires
  `TABLE_GRANT`. The audit feed is gated at `AUDIT_READ`; filtering it by *table* visibility is
  deliberately **not** in scope — the predicate has to be part of the SQL query rather than a
  post-filter because paging happens in SQL, and `/v1/activities` has no spec of its own yet
  (design.md Open Question 3).
- `structured-query`: query resolution requires `TABLE_READ` on every resolved table, decided per
  owning table across the flat entity; an unreadable table is an unknown entity, not a `403`.
- `query-schema-discovery`: `GET /v1/queries/entities` is filtered at `TABLE_LIST` (it returns names
  only), and the per-entity schema at `TABLE_GET`. `GET /v1/queries/functions` declares
  authentication-only — the one endpoint that carries no tenant data — as an explicit declaration the
  coverage test counts, not an exemption.
- `flight-sql-endpoint`: the Flight surface authorizes per RPC by atom rather than by application
  role, and its metadata RPCs apply the same filter as HTTP.
- `saved-queries`: `SAVED_QUERY_READ` / `SAVED_QUERY_WRITE` / `SAVED_QUERY_PUBLISH` replace "any
  application role" and the `FULL_ADMIN` `common` gate.
- `enrichment-runtime`: the pipeline-scoped data plane requires `TABLE_READ` on the pipeline's read
  source **and** `TABLE_WRITE` on the target enrichment; the Enrichment Runner needs `REGISTRY_READ` plus
  per-table grants instead of `READ_ONLY_ADMIN`. The write-back endpoint, which carries the caller's own
  rows and reads nothing, needs the target write alone.
- `enrichment-registry`: evaluator reads require `REGISTRY_READ`, evaluator writes `REGISTRY_ADMIN`.
- `aggregate-pipeline`: pipeline reads (list, get, revision) require `REGISTRY_READ`, authoring and
  execution (`refresh`, `preview`) require `REGISTRY_ADMIN`, and the scheduler's system principal is
  elevated to two atoms rather than every privilege.
- `data-sync-backfill`: triggering a backfill requires `DATA_SYNC_OPERATE`; reading a run requires
  `REGISTRY_READ`.
- `otel-sink`: sink create/delete require `TABLE_CREATE`/`TABLE_DELETE`, sink reads `REGISTRY_READ`,
  and OTLP ingest `TABLE_WRITE` on the landing table.
- `rest-sql-endpoint`: ad-hoc SQL requires `TABLE_READ` on every table the statement resolves;
  `POST /v1/queries/validate-sql` requires `TABLE_GET`, like the translation endpoints, because it
  resolves against the catalog model and never contacts ClickHouse.
- `query-translation-endpoint`: translation requires `TABLE_GET` — it discloses schema and never
  contacts ClickHouse.
- `pipeline-registry`: `REGISTRY_ADMIN` for register/patch/delete and `REGISTRY_READ` for reads,
  replacing "`FULL_ADMIN` to write, any mapped application role to read". Same reach, named as a
  privilege; and `REGISTRY_READ` now neither confers nor is conferred by any table privilege.
- `audit-trail`: the activity feed is gated at `AUDIT_READ`. Its requirement that the audit surface be
  *derived* from each resource's own live read is **withdrawn** — that derivation cannot survive
  per-table authorization, since a `Table` activity's live read is one of four rungs on a named table and
  a feed paging in SQL cannot apply a per-row predicate unless the predicate is in the query. The
  trade-off is now stated instead of derived, and the saved-query bypass becomes
  `SAVED_QUERY_PUBLISH`.
- `otel-sink`: sink create/delete at `TABLE_CREATE`/`TABLE_DELETE`, sink reads at `REGISTRY_READ`, and
  the metrics `point_attributes` column governed by `TABLE_READ_SENSITIVE` on its landing table — which
  is what lets a team read that one column's values without administrative authority.
- `class1-enrichment`: the materialize endpoint is authorized by both directions it exercises, and its
  sensitive re-check names the privilege rather than the role.
- `enrichment-registry` (second requirement): the per-table `read_sensitive` grant **is** the narrower
  sensitive grant that capability named as a deferred follow-up, so the original least-privilege stance
  stands for every pipeline again.

## Impact

- **APIs**: `access` request/response bodies gain three keys and `PUT` becomes a merge; `permissions`
  on table reads grows from two flags to five; `GET .../access` re-gated; catalog, discovery, query,
  translation, Flight and audit surfaces become caller-filtered; unreadable tables answer `404` or
  unknown-entity `400`, never `403`.
- **Configuration**: `security.defaults.roles-mapping`, `providers.<name>.roles-mapping` and
  `security.api-key.roles-mapping` accept atom names alongside the four bundle names, and reject
  unknown tokens at startup; `ApiKeyProperties.validate` must stop requiring that some mapping yield
  an application role, since a grant-only deployment is legitimate; `UserRolesResolver`'s per-request
  warning for role-less callers must go. No new property is introduced.
- **Catalog schema**: one Flyway migration for the `access` column default; existing rows are
  null-safe via field initializers.
- **Code**: `UserRole` (deleted; `RoleBundle` carries the reserved names), `@FullAdminOnly` (deleted),
  the four programmatic `FULL_ADMIN` checks, `Permission` (folded into `Privilege`),
  `TableAccess` (2 lists → 5, and given value equality — without it a JSON-mapped column is dirty on
  every load and each load writes an unexplained Envers revision),
  `TableAccessEvaluator`, `TableCallerPermissions`, `EffectivePermissions`, `AccessDto`,
  `ReplaceAccessRequest`, `TableMapper`, `CatalogQueryService`, `SensitiveColumnAccess`,
  `SensitiveProjectionGuard`, `RuleGovernanceValidator`, `RuleAuthorizer`, `OtelSinkAccess`,
  `SavedQueryAccess`, `SystemIdentity`, `AuditActivityService`, `OidcSecurityConfiguration`,
  `FlightHeaderAuthenticator` and `AdasFlightSqlProducer`.
- **Docs**: `docs/configuration.md` (the two-role model is described in nine passages), `README.md`
  (five), `docs/claude-code-telemetry.md` (six), `docs/heavy-columns.md`; plus the OpenAPI
  description strings that name `FULL_ADMIN`.
- **Operational**: no behaviour changes for an unmodified `roles-mapping` — the bundles reproduce
  today's two roles exactly, with the single deliberate exception of pipeline reads. Narrowing a
  team's access is an ADAS config edit (map the provider role to a narrower atom set) plus per-table
  grants; an identity-provider change is needed only when one provider role must be split into
  global and restricted readers.
