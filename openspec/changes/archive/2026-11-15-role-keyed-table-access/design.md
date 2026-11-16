## Context

See proposal.md for motivation. Today `table_definition` carries two access documents: `access` (JSONB, five role
lists mapped as the `TableAccess` value class) and `row_policies` (JSONB held as text, read through `RowPolicyCodec`
because the DSL's `FilterNode` needs the Jackson 3 wiring Hibernate's JSON mapper cannot see). Both are `@Audited`
through the entity. Everything that decides access reads them: `TableAccessEvaluator`/`GoverningAccess`,
`TableCallerPermissions`, `RowPolicyFilter`, `WildcardGranteeReport`, `ImportTargetReader`, the revision mapper.
Column exposure has one chokepoint for queries, `CatalogQueryService.buildModel`, which applies the read bar and the
sensitive filter per owning table through `SensitiveColumnAccess`. Several other places consult the clearance
directly: the catalog read (`TableMapper`), the list item's `column_count` (`TableCatalogService`), the schema-mutation
lookup (`TableSchemaService`), the import preview's `prev` (`ImportTargetReader`), export (`CatalogExportService`) and
the pipeline guards (`SensitiveProjectionGuard` — `enforce`, `enforceEntity`, `reattribute` — `EnrichSpecValidator`,
`AggregateGovernance`). `DashboardService` is the precedent for a client-carried `generation` with a `409` on
staleness.

## Goals / Non-Goals

**Goals:**
- One role-keyed resource with whole-entry replace and a table-wide generation; no change to how grants and row
  policies are evaluated or enforced.
- Column policies enforced through one visible-column decision in `SensitiveColumnAccess`, consumed at every place
  that consults the clearance today, so a policy-hidden column is treated exactly like an unentitled sensitive one.

**Non-Goals:**
- A bulk replace of every entry, column deny-lists, tags or classifications, per-column masking.
- Changing the row-policy rename/drop guard (`column_in_row_policy`), the evaluator's privilege rules, or what
  `sensitive` means.
- Keeping the removed endpoints as deprecated aliases: 0.24 is already a breaking release.

## Decisions

### D1. Keep the two stored documents; add `column_policies`; assemble entries at the API

The API entry `{privileges, rows, columns}` is a view over three per-table documents: `access` (unchanged),
`row_policies` (unchanged) and a new `column_policies` JSONB, `{"<role>": ["col", "enrichment.col"]}`, held as text
with a small codec like `row_policies`. A `PUT` for role `R` rewrites `R` in all three; a `DELETE` removes `R` from all
three.

*Alternative rejected:* one role-keyed JSONB document replacing `access` and `row_policies`. Cleaner on paper, but it
rewrites every reader listed in Context, needs a data migration of both columns, and makes every revision recorded
before it unreadable without a second decoder. The view costs one assembler in the service.

*Not a value class:* `column_policies` is text with a codec, not a mapped `Map<String, List<String>>`. Mapping a
`List<String>` through Hibernate's JSON type has re-typed unrelated columns before. The codec writes roles and names
sorted, so its output is deterministic, but the column is JSONB, which Postgres re-serialises (key order, whitespace):
the text read back never equals the codec's. Whether a role's entry changed is therefore decided on decoded values —
the role's privilege set, its decoded row policy, its decoded column list — as `RowPolicyService` already does, and a
document is written only when its decoded value for that role differs. Otherwise every `PUT` would dirty the entity
and record an empty revision.

### D2. `access_generation` column; every definition writer, the access API included, locks the row first

`table_definition.access_generation BIGINT NOT NULL DEFAULT 0`, mapped on the entity as an ordinary updatable field
and `@NotAudited`. One mechanism serialises every write of a definition: a `PESSIMISTIC_WRITE` finder
(`findForUpdate(name)`, `SELECT … FOR UPDATE`) used as the **first load of that row in the transaction**. Hibernate
returns an entity already in the persistence context as it is, even from a locking query, so a lock taken after an
earlier plain load of the same row would hold the lock over stale state. Where an earlier load in the same transaction
cannot be avoided, the writer locks with `EntityManager.refresh(entity, PESSIMISTIC_WRITE)`, which re-reads the row
under the lock. `spring.jpa.open-in-view` is `false`, so a load outside the transaction leaves nothing behind in it.

An access `PUT`, in one transaction:

1. loads the definition with `findForUpdate` (`404` when absent);
2. runs the spec's checks in order under the lock: the enrichment refusal, a usable generation (`422`), the generation
   against the locked row (`409 access_conflict`), the role, `table_is_system`, then the remaining body checks
   (privileges, `rows` shape, `where` translation, row-policy and column-policy validation), reading the table's and
   its enrichments' columns with repository queries;
3. writes each of the three documents only when its decoded value for the role changed (D1) and sets
   `accessGeneration + 1` through the entity, so Envers records the documents in the same transaction. A change to the
   `@NotAudited` generation alone records no revision, so an unchanged `PUT` bumps without one.

A `DELETE` loads with `findForUpdate`, answers `404` when the table or the role's entry is absent, and otherwise removes
the role from the three documents and bumps the generation. It carries no generation and is checked against none, so
two concurrent `DELETE`s of one entry answer `204` and `404`; a `409` would only tell the caller to retry an operation
whose outcome it did not condition on anything.

Validating under the lock is what makes the stored names current: a rename, drop, re-define or enrichment delete takes
the same row lock before it reads the policies (D4), so it either commits before the `PUT` locks the row or waits until
the `PUT` commits and then sees its entry. It closes the same race for a row policy's referenced columns, which the
removed row-policy endpoint had.

**Every other path that writes `table_definition` through the entity** — description update, schema `PATCH`,
define/re-define and status transitions, delete, relayout — loads the definition the same way. Hibernate writes every
column on update and Envers snapshots the whole in-memory entity, so a writer that loaded the row before an access
`PUT` committed would otherwise write the stale `access`/`row_policies`/`column_policies`/`access_generation` back
(silently reverting the `PUT`) and record a revision showing them. The lock serialises it with access writes in both
directions.

*Alternatives rejected:* JPA `@Version` on `TableDefinitionEntity` — it would bump on every schema and description
edit and make them conflict with access edits, which the spec forbids. A separate `table_access` table with its own
`@Version` — a new audited entity, an `AuditResourceMapper` case and a join on every access read, for the same
guarantee. `@DynamicUpdate` on the entity — protects the database row from a stale writer but not the Envers snapshot,
which is written from memory; with every writer locking, it adds nothing. A conditional
`UPDATE … SET access_generation = access_generation + 1 WHERE access_generation = :expected` on the access path — the
dashboard shape, but a second mechanism (read-only mapping, flush ordering, an explicit refresh) on top of the row lock
every other writer needs anyway; the lock alone gives the same contract.

### D3. Column visibility in `SensitiveColumnAccess`, consumed wherever the clearance is

Add a per-caller, per-table visible-column decision (`visibleColumns(owningTable)` returning "all" or a set) next to
the clearance checks, computed from the governing roles as the spec defines them. The governing-role computation
reuses the "may read the table" predicate `RowPolicyFilter` already has for lifting confinement, so the two rules
cannot drift (and inherits its cross-provider approximation). A caller with no governing role gets "all": it holds
neither `TABLE_GET` nor `TABLE_READ`, so the verb bars already decide what it reaches, and narrowing it would refuse a
`REGISTRY_ADMIN`-only author every column of a table that carries any column policy, a regression against the
pipeline-authoring rule.

*Rows and columns combine per entry, failing closed.* Column `c` is visible when some governing role with no row
policy allows it, or when every governing role allows it ("allows" = no column policy, or one listing `c`). A naive
union of columns beside the union of rows would pair one role's rows with another role's columns: `R` (rows `P1`,
columns `[a]`) and `U` (rows `P2`, every column) would show `c` on `P1` rows, which neither entry grants. ClickHouse's
`additional_table_filters` cannot express a per-column row filter, and the caller's row predicate does not record
which role admitted a row, so the only sound per-table answer is a column every row's admitting role allows: one role
that admits every row, or all of them. With no governing row policy that is the union of the lists; for a confined
caller it is their intersection. It may hide a column the caller could see on the rows one role admits — accepted, the
operator widens that role's or every role's list. A role whose row policy adds rows without governing (an entry with
`rows` and no read path; row evaluation ORs every carried role's policy) takes no part: on its rows the caller sees
only what the governing roles' rule admits, so it never widens columns. The empty governing set yields "all", the same
answer as the no-governing-role rule above. `columns_restricted` and every consumer below read this one decision.

A computed field (`ComputedFields`) is visible exactly when every catalog column whose values it returns
(`request_body` and `request_cache_control`) is visible; it is judged through the same descriptors that already carry
the inherited `sensitive` flag.

The decision is memoised only within one model build or one call, as a local or a parameter — never in a bean field or
a cache keyed by table, because Flight and scheduler threads have no request scope and a table-keyed memo would serve
one caller's visible set to another.

Every place that consults the clearance also applies the decision:
- `CatalogQueryService.buildModel` (discovery, DSL, SQL, translation, Flight, `INFORMATION_SCHEMA`, CSV);
- the catalog read in `TableMapper` (live and revision — the revision path evaluates against the snapshot's
  documents);
- `TableCatalogService` list item `column_count`, counting columns that are both sensitive and hidden once;
- the schema-mutation lookup in `TableSchemaService` (a hidden column is unknown, `422`; the TTL path leaves it out of
  the physical columns a `ttl.column` resolves against, and refuses any `ttl` member while the stored TTL is on a
  policy-hidden column with `403 column_not_entitled`, where the sensitive case answers `403 forbidden` today);
- `ImportTargetReader`'s `prev`, which omits hidden columns as it omits unentitled sensitive ones;
- the pipeline and export paths that answer `sensitive_column_not_entitled` — `SensitiveProjectionGuard.enforce`
  (both spellings: logical `getName()` and physical `getSourceName()`), `enforceEntity` (qualified names),
  `reattribute`/`reattributing`, `EnrichSpecValidator`, `AggregateGovernance`, `CatalogExportService` — check the
  hidden column first and answer `403 column_not_entitled` (new `ColumnNotEntitledException`). `reattribute` turns a
  parse failure into `column_not_entitled` when any table the pipeline reads hides a column by policy from the caller,
  else into `sensitive_column_not_entitled` when one hides a sensitive column, else returns it unchanged: a parse
  failure does not say which column caused it, so the policy-hidden case, the stricter one, is checked first.
  This blanket re-attribution covers only the pipeline-scoped parses that go through `reattributing(..., true)`
  (filter, `ready_when.signal`, `prefer_sql`, enrich expressions, spec-form aggregate `filter`/`measures[].where` via
  `PipelineFilters`). An aggregate SQL statement keeps `AggregateSqlValidator.read`'s text-mention rule: on a planning
  failure only a protected column the text mentions is refused — policy-hidden first, across every candidate source,
  then sensitive — and every other failure stays `422`, as the aggregate-pipeline delta states.
  Column policies list exposed names; `enforce`'s two spellings are each mapped to their catalog column and judged by
  its exposed name, which is also how the scan's physically-named cursor columns are judged.
  The precedence is request-wide, not per table: a path judging several tables (export of a bundle, `enforceEntity`
  over a source and its enrichments) checks every table for policy-hidden columns before it checks any for the
  clearance, so the code answered never depends on iteration order.
  The scan's judged set already holds the cursor columns, the grain key and the group key, all of which its page
  returns, so they are judged for column policies too. It gains the alignment column: for a row-trigger pipeline whose
  target is aligned with its source, every page row carries the source's partition column as `__partition_v`
  (`PartitionEcho.column`), which the guarded set does not hold today, so it is added and judged by its exposed name.
  The same set feeds `enforceEntity`'s sensitive check, so the alignment column is judged for the clearance too: a
  source may declare its `partition_by` column `sensitive`, and a runner without the clearance that scans for a target
  aligned with such a source, accepted today, is newly refused `403 sensitive_column_not_entitled` (the
  enrichment-runtime delta pins it; the upgrade guide says so).
  Group assembly (`GroupFetchService.prepare`, which `group-rows` and the group preview share) already adds the read
  source's version and identity columns and the group key to the set `enforceEntity` judges, because every member row
  returns them, so once the shared guard checks column policies they are judged for them too; the
  enrichment-runtime delta states it on "Sensitive-column re-check on group assembly".
  Materialize's judged set (filter, expressions with the alignment column `withAlignment` adds, grain key) gains the
  read source's `version_column` and `identity_column`, mapped to exposed names as on the scan: `nextCursor` is
  signed, not encrypted, so it shows their window-end values. An exposed reserved column such as
  `dial_usage_log._ingested_at`, and `request_time` for an aligned target, must therefore be on a restricted runner's
  list.
  *`lag_seconds` is not a column read.* It is a duration derived from a version, returned by the scan, materialize, an
  on-demand aggregate refresh and `GET /v1/pipelines/{name}` (to any `REGISTRY_READ` holder); judging a column for it
  on one path while the pipeline read discloses it anyway would read the rule two ways, so it is exempt everywhere.
  *Previews do not judge the version column; the note drops its instants instead.* The Class-1 preview
  (`MaterializeService.preview`) and the aggregate preview judge filter, expressions and grain key only, yet hand the
  sampled rows' maximum version to `PreviewNotes.enrichmentBehind`, whose message prints it and the enrichment's
  materialized position (also a version of the source) as instants. Adding the version column to their judged sets
  would refuse a restricted author a preview whose sample needs no version value at all; instead, when the read
  source's version column is absent from the caller's model (hidden by a column policy, or sensitive and uncleared),
  the note is still raised with its `subject` and gap but its message carries neither instant. The gap is a derived
  duration, exempt as `lag_seconds` is. The LLM row preview and the group preview are unaffected: they go through the
  scan and `GroupFetchService`, which judge the version column.
  *The pipeline read stays a registry-level disclosure.* `GET /v1/pipelines` (the sync feed) and
  `GET /v1/pipelines/{name}` return `state.cursor_*`, `state.materialized_through_*` and `state.clamp.cursor_*` —
  version and identity values of the input, including the enrichment position the note's instants show — to any
  `REGISTRY_READ` holder, today and regardless of table privileges and sensitive flags. This change does not alter that
  read. Materialize's judgement and the note's dropped instants keep those values off the data-plane and preview
  responses themselves; they hide nothing from a caller that also holds `REGISTRY_READ`, which is the privilege that
  governs the registry read (see Risks).

`TableSchemaCreationService` consults the clearance only to refuse declaring a column `sensitive`; it needs no change
(a re-define that omits a column the caller cannot see drops it, as for an unentitled sensitive column — see Risks).

*Bar-independent visibility:* one visible set per caller and table — read-governing roles when the caller may read,
get-governing otherwise — rather than one set per verb bar. Per-bar sets would let translation (`TABLE_GET` bar) accept
a column that execution (`TABLE_READ` bar) rejects, and would let a `get`-only role widen what a restricted reader
sees. The cost: a caller whose reading role is restricted does not see more columns through a `get`-only or
`modify`-only role (see Risks).

The system identity is short-circuited (it carries no provider roles); `none` mode is short-circuited to "all".

### D4. Rename, drop and enrichment delete lock first, then decide

`TableSchemaService` (PATCH), `CatalogSchemaWriter` (re-define) and `TableCatalogService.delete` (of an enrichment)
take the row lock of every definition whose policies they may rewrite **before** they read any policy — the table's
own and, for an enrichment (its column or the enrichment itself), its source's — whether or not anything ends up
rewritten, each as D2 requires (the first load of that row in the transaction, or a locking refresh). One exception: a
define or re-define of an enrichment that drops none of its stored columns cannot change a source list, so it leaves
the source row unlocked. That keeps the catalog import preview, whose rolled-back pass defines every new bundle table,
from holding a lock on an existing source for the whole pass. Lock order: the enrichment's row, then its source's. Under those locks they run the row-policy guard first, unchanged, then compute the
rewritten `column_policies` (for a `PATCH`, drops prune first and renames rewrite the pruned lists, so a column added
under a dropped name is never listed, and one renamed into it is listed only where its old name was; table-management
states the order), refuse `409 column_in_column_policy` (new `ColumnInColumnPolicyException`) if any list
would empty, and otherwise write the rewritten lists through the entity of the table **whose lists change** and bump
that entity's `accessGeneration` — the source's for an enrichment's column or delete, never the enrichment's — only when
an entry was rewritten. Because both the refusal and the pruning are computed on the locked state, a concurrent `PUT`
that adds a name the operation drops either commits first (and the operation sees it) or waits and then validates
against the new columns (D2); no list is left empty and no name dangles.

**Schema `PATCH` becomes one transaction.** Today `TableSchemaService.patchSchema` is not transactional: it loads with
`findById`, validates (the row-policy guard included), issues the ClickHouse `ALTER`, and only then calls
`CatalogSchemaWriter.applyPatch`, which opens its own transaction and re-loads the definition. A locking load there
would either fail (no transaction) or come after the DDL. `patchSchema` therefore runs, in a `TransactionTemplate`, from
`findForUpdate` of the table (then of its source for an enrichment) through every validation — the row-policy guard
and the column-policy refusal included — the `ALTER`, and `applyPatch`, which joins the transaction (`REQUIRED`) and
uses the already-locked entity instead of re-loading it; `pendingAlignment`'s source load runs after the source lock,
so it returns the locked instance. The response (`catalogService.get`) is read after the commit. Holding the row lock
across the `ALTER` is accepted (see Risks). The residual risk is unchanged from today: an `ALTER` that succeeds followed
by a Postgres commit that fails leaves ClickHouse ahead of the catalog. Define is already ordered safely
(`persistSchema` commits the catalog before `CREATE`), and `TableCatalogService.delete` is already `@Transactional`.
The main table-management spec's wording that the output mapping is re-derived "in the catalog transaction that follows
the `ALTER`" still holds: the catalog writes follow the `ALTER`; only the transaction's start moves before it.

*Alternative rejected:* refusing a drop or rename of any listed column, like row policies. Simpler, but a list is an
allowlist of names: renaming keeps the meaning exactly, and pruning a list that keeps other names hides nothing new.
Only the emptying case has no safe rewrite.

### D5. Role validation is unified; the role travels as one percent-encoded path segment

The `{role}` path segment, decoded, goes through the existing row-policy role check (trim, non-blank, not a bundle or
privilege name) on every `PUT`, and is trimmed on `GET` and `DELETE` (as `RowPolicyService.delete` already does). The
removed grant API accepted a bundle name as a grantee; an IdP can emit a raw role with a bundle's name, and the old
evaluator matched raw names, so such a grant could take effect. It is now refused, as `authentication` already requires
of a grantee; an existing one stays in force as stored until it is `DELETE`d, which needs no role check.

Provider roles come from configurable claim paths, and IdP group paths such as `/analytics/viewers` are common; the
removed five-list `PUT` carried roles in its body, so grants for such roles exist. The role is carried as one
percent-encoded segment (`%2Fanalytics%2Fviewers`), which needs three settings, all feasible with the current stack:
- Tomcat rejects an encoded solidus by default; its connector's `encodedSolidusHandling` is set to `passthrough` (a
  `WebServerFactoryCustomizer`, no configuration key), so `%2F` reaches the application undecoded;
- Spring Security's `StrictHttpFirewall` rejects `%2F` and `%25` by default; one `HttpFirewall` bean shared by both
  security modes sets `setAllowUrlEncodedSlash(true)` and `setAllowUrlEncodedPercent(true)` (the latter is also needed
  because, with `passthrough`, the servlet path keeps the literal `%2F`). Every other firewall rule stays;
- Spring MVC's `PathPatternParser` matches on the raw path's segments and decodes a path variable after matching, so
  `{role}` receives `/analytics/viewers` with no further change.

Why this opens no path-matching bypass. In `oidc` mode the URL layer `permitAll`s the operational paths
(`/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`) and, when `disableSwaggerAuthorization` is set, the
API-docs paths (`/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`); it requires authentication on `/v1/**` and
on the API-docs paths otherwise, and denies everything else; `none` mode permits everything. With `passthrough`, no
layer turns `%2F` into a separator before routing: Spring Security's matchers and MVC's `PathPatternParser` both see
`%2F` inside one segment, and only a matched path variable's value is decoded. So `/actuator/health/%2F..%2Fenv` stays
under `/actuator/health/**` and reaches the health endpoint as an unknown component name (`404`); only `health`,
`info` and `prometheus` are exposed in any case. The Swagger UI's static resources are served by
`ResourceHttpRequestHandler`, which refuses a decoded `..` or an encoded traversal in a resource path itself.
Authorization of `/v1/**` is per handler, so a path variable that now carries a `/` changes which name a handler is
asked about, never which handler or privilege applies. The change is endpoint-wide: a `%2F` or `%25` in any `/v1`
path segment, refused `400` before, now reaches the handler as part of the decoded name (an unknown table named `a/b`
answers `404`); the upgrade guide says so.

The firewall's remaining rules still refuse a role containing `;`, `\`, `//`, a NUL, CR, LF, U+2028 or U+2029, or one
that is exactly `.` or `..`; allowing `;` or `\` would weaken protections that are not specific to this route, so those
roles stay unaddressable. A `RequestRejectedHandler` renders the firewall's refusal as `ErrorView` `400 bad_request`
(today it is a bare `400`). Tomcat rejects some encoded characters (at least an encoded NUL) before the request reaches
Spring, with its own `400` page; the spec promises only "HTTP 400" for those, and `ErrorView` where the firewall
refuses. A legacy grant for such a role can only have come from the removed five-list `PUT` (the removed row-policy
path had the same firewall), so the upgrade guide tells operators to find and remove those grants with the previous
release's API before upgrading.

### D6. Removal, not aliasing

`TableRowPolicyController`, `ReplaceAccessRequest`, `AccessReplacedDto`, `RowPoliciesDto` and their tests are deleted;
`TableAccessController` is rewritten. `RowPolicyService` keeps its validation and the column guard and loses its
endpoint-shaped methods; `TableAccessService` becomes the one write path for an entry (row-policy and column-policy
validation delegated). Stable codes added to `DefaultExceptionHandler`: `access_conflict` (409),
`column_not_entitled` (403), `column_in_column_policy` (409).

### D7. Audit

No new resource type: a `PUT`/`DELETE` that changes an entry is one `table_definition` revision, so one `Update`
activity, attributed as today. `table_definition_aud` gains `column_policies`. The revision mapper assembles `roles`
from the snapshot's three documents; a pre-V57 revision has `column_policies` `NULL`, read as no policies.

## Risks / Trade-offs

- [Rolling back to a build without column policies ignores `column_policies`, so every hidden column becomes visible
  to its readers] → Upgrade guide: delete column policies (or the read paths they narrow) before a rollback that must
  not widen; the column itself is harmless to an old build.
- [During a rolling upgrade, old replicas serve the removed endpoints, ignore column policies, do not bump the
  generation, and record `table_definition_aud.column_policies` as `NULL` on any table write, which a snapshot at that
  revision reads as no policies] → Upgrade guide: make no access or table edits until every replica runs the new
  build.
- [A caller whose reading role is column-restricted cannot see or patch hidden columns through another, unrestricted
  `get` or `modify` role — including an enrichment maintainer who also reads the source through a restricted role] →
  Stated in the spec; the remedy is an unrestricted entry for the maintaining role or no restriction on the reading
  one. Chosen over per-bar visibility, which leaks.
- [A caller with no governing role is not narrowed, so a `REGISTRY_ADMIN`-only author may reference a column that a
  restricted reader of the same table cannot] → Accepted: column names are not secret, the author still cannot read
  the values, and narrowing it would break authoring on every table that carries a column policy.
- [A caller confined by several roles sees only the columns all of them allow, so a column one role shows on the rows
  only it admits is hidden from a caller that also carries a narrower role] → Accepted: fail closed rather than pair
  one role's rows with another's columns (D3). Remedy: widen the narrower role's list, or give the role meant to see
  everything no row policy.
- [An entry with `rows` and no read path adds its rows to a confined caller carrying that role (unchanged row
  evaluation), although the new API makes rows-only entries easy to write] → Stated in the spec; its `columns` take no
  part, so it never widens columns; `docs/row-level-security.md` warns operators.
- [A restricted runner's allowlist must name the scan's cursor columns, grain key, group key and an aligned target's
  partition column, group assembly's cursor columns and group key, and materialize's cursor columns, including an
  exposed reserved column such as `_ingested_at`, or every scan, group assembly or materialize is refused] → Stated in
  the spec with scenarios; the docs say so.
- [The `enrichment_behind` gap and every `lag_seconds` let a caller from whom the version column is hidden estimate how
  recent the newest rows are] → Accepted: a duration relative to now is not a stored value, and the pipeline read
  already returns `lag_seconds` to any `REGISTRY_READ` holder.
- [`GET /v1/pipelines`, its list form as the runner's sync feed, and `GET /v1/pipelines/{name}` return cursor and
  position values — `state.cursor_version`/`cursor_identity`, `state.materialized_through_version`/`_identity`,
  `state.clamp.cursor_version`/`cursor_identity` — to any `REGISTRY_READ` holder, so such a caller reads the version
  and identity values (and the enrichment position) that materialize's judgement and the preview note's dropped
  instants keep off those responses] → Accepted and unchanged: the pipeline read returns them today regardless of
  table privileges and sensitive flags, and this change leaves that registry-level disclosure as it is.
  `REGISTRY_READ` is the privilege that governs it; an operator who must keep those values from a column-restricted
  identity does not grant it `REGISTRY_READ`.
- [An aggregate derived from a hidden column exposes derived values] → Same accepted risk as row policies; stated in
  the spec, closed by an entry on the target.
- [Every successful `PUT` bumps the generation, even an unchanged one] → Intended (as dashboards); a client always
  takes the generation from the response.
- [The definition row lock is held across the access `PUT`'s `where` translation and validation, and across a schema
  `PATCH`'s ClickHouse `ALTER`] → Access and schema edits of one table (and, for an enrichment, of its source) serialise
  for that long; both are rare operator actions and other tables are unaffected. An `ALTER` that succeeds followed by a
  failed Postgres commit leaves ClickHouse ahead of the catalog, as today.
- [Roles containing `;`, `\`, `//`, NUL, CR, LF, U+2028, U+2029, or exactly `.`/`..` cannot be addressed] → Upgrade guide:
  remove such grants with the previous release before upgrading; an entry left behind keeps applying and is listed by
  `GET .../access`.
- [A re-define of a `PENDING`/`FAILED` table that omits a column the caller cannot see drops it] → Parity with an
  unentitled sensitive column; the emptying refusal still protects every list.
- [A revision snapshot older than a restriction shows the now-hidden column's metadata to a caller its revision's
  policies did not restrict] → Parity with sensitive flags, which snapshots also resolve as of their revision; stated
  in the audit-trail spec.

## Migration Plan

1. `V57__role_keyed_table_access.sql` (one-line header naming what it adds, linking
   `openspec/specs/table-access-control/spec.md`):
   `ALTER TABLE table_definition ADD COLUMN column_policies JSONB NOT NULL DEFAULT '{}'::jsonb, ADD COLUMN
   access_generation BIGINT NOT NULL DEFAULT 0;` and `ALTER TABLE table_definition_aud ADD COLUMN column_policies
   JSONB;`. No data rewrite: existing grants and policies read as entries unchanged.
2. Before deploying: remove, with the previous release's five-list `PUT`, any grant whose role the firewall cannot
   carry in a path (see D5).
3. Deploy; clients move from the removed endpoints using the mapping in `docs/release-0.24-upgrade.md`.
4. Rollback: the added columns are ignored by an older build; column policies stop applying (see Risks). Grants and
   row policies written through the new API are in the old documents and keep working.

No new configuration key and no new credential.
