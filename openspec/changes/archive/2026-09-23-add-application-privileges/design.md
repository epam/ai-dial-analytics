## Context

See proposal.md — Why. What follows is only the state that shapes the approach.

Authorization today has two layers that do not share a vocabulary. The **subject** layer is a
two-value enum (`UserRole`), mapped from provider role names by `roles-mapping` and enforced by one
URL rule (`/v1/**` requires `FULL_ADMIN` or `READ_ONLY_ADMIN`) plus 21 `@FullAdminOnly` sites and 7
custom `@PreAuthorize` expressions. The **object** layer is two role-name lists (`write`, `modify`)
in a JSONB column on `table_definition`, consulted at four call sites. The raw provider role names
travel alongside the mapped roles as `PROVIDER_ROLE_*` authorities so the object layer can match on
them.

Four facts constrain every decision below.

1. **Read is not in the object layer at all.** Of ~56 endpoints, about 25 are read endpoints whose
   only gate is that one URL rule. Any caller with any application role reads every table.
2. **A queryable entity is a composite.** `CatalogQueryService.buildModel` flattens a source with
   its ACTIVE enrichments into one model, and `findModel` accepts only `type == SOURCE`, so an
   enrichment is never addressable on its own. The model is built per request and already carries one
   caller-dependent decision — the `sensitive` filter — applied where each column's owning table is
   in hand.
3. **The mode is derived nine ways in four semantics.** `@ConditionalOnProperty` (case-insensitive),
   SpEL equality against `'oidc'` (case-sensitive), `equalsIgnoreCase` with fail-open, and
   `blank || "none"` with fail-closed. `SECURITY_MODE=OIDC` therefore enforces over HTTP while
   leaving Flight unauthenticated and `SensitiveColumnAccess` failing open.
4. **The four authentication entry points build different identities.** Flight's API-key path omits
   `PROVIDER_ROLE_*`; the three HTTP paths add them. Both share one `ApiKeyCache` entry keyed by
   sha256 of the key, so whichever surface introspects first decides whether the object layer can
   match at all.

## Goals / Non-Goals

**Goals:**

- One vocabulary across both layers, so a privilege means the same thing held globally and granted
  per table, and the object layer needs no bypass for a privileged caller.
- An upgrade that changes nothing observable for an unmodified `roles-mapping`.
- One chokepoint per concern: one mode resolution, one token builder, one matcher shared by
  enforcement and reporting, one place where per-table read and sensitivity are applied to the query
  model.
- Fail-closed by construction, since removing the blanket URL rule makes a missing annotation an
  open endpoint rather than an admin-only one.

**Non-Goals:**

- Column-level read grants replacing the binary `sensitive` flag; row-level policies.
- Grantee types beyond raw provider role names; schema-, tag- or wildcard-scoped grants.
- Incremental `GRANT`/`REVOKE` in place of a merge `PUT`, and the normalized grant table that would
  come with it.
- Delegated ownership (a table-scoped `TABLE_GRANT`) and splitting `REGISTRY_ADMIN` per object kind.
- Making an enrichment independently queryable.

## Decisions

### D1 — Privileges are a closed enum; bundles are configuration expanded at parse time

`Privilege` is a Java enum the code gates on. `FULL_ADMIN` and `READ_ONLY_ADMIN` become reserved
**names accepted in `roles-mapping`**, expanded once when the mapping is parsed, and are invisible
below that: `UserRolesResolver` returns privileges, authorities become `PRIVILEGE_*`, and no gate
mentions a bundle.

*Rejected:* keeping the two roles as enum constants alongside the privileges. Every gate would then
need "…or holds `FULL_ADMIN`", which is the `TableAccessEvaluator` short-circuit this change exists
to delete, and the constant would need excluding from `EnumSet.allOf`, from the grant keys and from
the reported permissions — three special cases to maintain forever.

*Rejected:* free-form privilege strings, as GCP IAM uses. Strings scale to thousands of permissions
across hundreds of services and no single binary that knows them all; ADAS has sixteen and one
binary, and an enum buys startup validation and exhaustive switching. The enum's *name* is still the
configuration form, so the operator experience is identical.

Bundles are stored **minimally** (`READ_ONLY_ANALYTIC` is `TABLE_READ` plus two saved-query
privileges, not the closure) and closed by the implication DAG at expansion time, with a test
asserting each closure. One source of truth for implication.

### D2 — Implication is derived at check time, admitted only where disclosure-monotone

A grant list returns exactly what was written; the evaluator computes
`visible = schemaVisible = get ∨ read ∨ read_sensitive ∨ modify` for a per-table grant (there is no
`list` key, D12), with a global `TABLE_LIST` holder additionally seeing every name.

*Rejected:* expanding at grant time (writing a `get` entry when `read` is granted). It
mutates the operator's stated intent, makes revoke asymmetric, and leaves the stored document not
matching the request that produced it.

Derivation is unavoidable anyway: a caller holding `TABLE_READ` globally has no entry in any list, so
its visibility must be computed. Not deriving for per-table grants would mean two different rules
depending on where the read came from.

The admission test is **strict containment of disclosure**, and it is applied per edge rather than
per intuition. That is why `TABLE_WRITE` implies nothing: `GET \ LIST` is `{columns, grain}`, and a
row insert only confirms or denies a column name the caller already guessed (`TableDataService`), so
`WRITE` leaks the inventory without containing it. Keeping `WRITE` isolated also gives the better
operational result — an ingest-only identity stays invisible in the catalog — and it is the same
confusion (`WRITE → READ`) that produced the rule-path escalation.

### D3 — A second axis: privileges that permit self-elevation are gated at the grant level

Disclosure monotonicity asks what a privilege *reveals*. It does not ask what a privilege lets its
holder *change in order to reveal more*. `PATCH /v1/tables/{name}/schema` guards `sensitive` only
when setting it to `true`, so a `modify` grantee can clear it and then read the column.

Decision: changing `sensitive` in either direction requires `TABLE_GRANT`. Every privilege added
later is checked on both axes.

*Rejected:* "`MODIFY` may set but never clear `sensitive`". It leaves declassification with no owner
at all — nobody could ever unmark a column — and an operator would have to delete and recreate the
table.

*Rejected:* treating this as a pre-existing bug to fix separately. The fix *is* a privilege
assignment, so it cannot be expressed before privileges exist.

### D4 — System tables are restricted by key, not by object

`TableAccessService.replaceAccess` calls `requireModifiable()`, so today no access list can be set on
`dial_usage_log` or `rate_analytics` at all. With read-side keys that is wrong on the guard's own
terms — the flag's migration says a system table "stays fully readable and queryable; only the
modifying endpoints" are refused — and it would make per-table read grants inapplicable to the
primary analytics table, which is the motivating case.

Read-side keys are always settable; `write`/`modify` are refused with `409 table_is_system` only when
**non-empty**. An empty `write`/`modify` passes, which is what lets a five-key body succeed.

*Rejected:* a separate endpoint for read grants on system tables. Two endpoints for one document, and
every client would need to know which tables are system to choose one.

### D5 — `PUT .../access` becomes a merge

Absent key keeps the stored list; `[]` revokes. The endpoint is a full replace today, so adding three
keys would make every existing two-key client silently revoke all read grants on each call — the MCP
`adas_*` tools among them.

*Rejected:* plain full-replace, reading an absent key as empty. It converts a client that was not
updated from "cannot set read grants" into "destroys them", and the failure is silent.

*Rejected:* **strict** replace — full-replace, but refusing with `422` a body that does not carry all five
keys. This is sound: nothing is ever wiped silently, and it keeps replace semantics exactly. It loses on
compatibility alone, because every existing two-key client breaks until it is updated, and the merge
gives a client that sends all five keys the identical result anyway.

*Rejected:* versioning the endpoint. A second shape to maintain for a change that can be made
compatible.

The response carries the stored lists **and** the effective permission flags, because two grants can
legitimately change nothing — a role that already holds the privilege globally, and a `read` grant on
an enrichment whose source the role cannot read. Both are reported rather than refused.

### D6 — Visibility is decided per owning table, at the existing chokepoint

`buildModel` already passes `owningTable` into the per-column step where the `sensitive` filter runs.
Both per-table read and per-table sensitivity become a predicate on that argument — one signature
change at seven call sites — so HTTP, SQL, translation, Flight and discovery cannot diverge, exactly
as the `sensitive` filter cannot today.

An entity is visible iff its **source** is readable. An unreadable enrichment's columns are absent
from the model; row counts are unchanged because the grain join contributes at most one row per
source row, so hiding an enrichment is undetectable.

*Rejected:* serving an enrichment-only `read` grant through the source's entity, projecting the grain
key plus the enrichment's columns. The grain key is the *source's* column and the `FROM` is the
source table, so this discloses the source's row population and its grain-key values — a source read
in disguise. An enrichment-only grant is therefore **inert**, and the `PUT` response says so rather
than leaving the operator to discover it.

### D7 — Column names are not secret; only values are

`table-management` already specifies that its read surface is deliberately not column-role-filtered,
so an operator can audit and manage sensitivity. Column names also appear in the audit trail's
`resourceId` (`table:column`), in `RuleDto.filter_sql` / `group_by` / `member_select` and in an
evaluator version's request template.

Decision: accept it, state it once, and scope `TABLE_READ_SENSITIVE` to **values**. `TABLE_GET`
exposes a sensitive column's name and flag.

*Rejected:* filtering all three surfaces. Redacting SQL text is unbounded work with no clean stopping
point, and it would contradict a shipped decision that has a stated rationale.

### D8 — One `SecurityMode` bean, validated at startup

A single component resolves the mode, rejects anything but `oidc`/`none` at startup, and matches
case-insensitively. Every gate consults it: the filter chains, method security, the Flight
authenticator, API-key beans, the grant evaluator, the sensitive filter, saved-query ownership and
rule authorization.

This is in scope rather than a follow-up because the evaluator would otherwise inherit whichever of
the four semantics it happened to copy, and because the failure it prevents — Flight serving
unauthenticated while HTTP enforces — is the one that would make per-table read grants worthless.

### D9 — One token builder for all four entry points

Bearer JWT, opaque introspection, API key over HTTP and API key over Flight construct the caller's
authorities through one function: expanded privileges plus `PROVIDER_ROLE_*`. Today Flight's API-key
path omits the provider roles while sharing an `ApiKeyCache` entry with HTTP, so cache population
order decides whether grants match.

*Rejected:* keying the cache per surface. It doubles introspection traffic against DIAL Core and
leaves two identities for one credential — the divergence would persist, just less visibly.

### D10 — Fail-closed authorization coverage is enforced by a test

Removing the blanket URL rule means a handler with no annotation is reachable by any authenticated
caller. A reflection/ArchUnit test asserts every request mapping under `/v1` declares exactly one
privilege requirement, and the chain's default becomes `denyAll`.

*Rejected:* relying on review. 56 endpoints across 16 controllers, and the failure is silent and
security-relevant.

### D11 — The enrich lane checks, at every read egress, that its sensitive reads were approved

Added after implementation, from seven review rounds that each found one more edit changing what a
stored enrich declaration read **without the governance gate running again**: a new evaluator version,
a declared read source, a rollup's input, a grain-join enrichment, an initial schema, a source-column
rename. Each was closed at its own surface, and rounds 5–7 each found the defect inside the previous
round's fix. The pattern said the control was in the wrong place.

The shipped `enrichment-registry` design accepts that a value derived from a sensitive column may be
readable by callers who cannot read its source, and rests that on one premise: **the pipeline governs
who may reference the column.** Every defect broke the premise by changing the reference after the gate
ran. The aggregate lane never had the problem, because its refresh re-runs its whole gate each time;
the enrich lane re-checks only the *calling* identity at its egresses, and the runner's elevated
identity always passes. So the premise held only at the edits someone had thought to guard.

D11 makes it a checked fact where ADAS itself reads source values for derivation:

- The governance gate (register, re-declare, enable) records on the pipeline the **owning tables whose
  sensitive columns the declaration reads**. It has just checked the author's entitlement on each, so
  the set is what an entitled author approved. It is audited: the trail says which author approved
  which tables.
- Materialize, scan and group assembly recompute the same set over their own read set, with the same
  classifier, and **refuse a table not in it** — `409 sensitive_read_unapproved`, not a 403, because
  the caller is usually an entitled runner and what is missing is a decision, not a privilege.
- Re-approval is any governance pass by an author entitled to the named tables; disabling and
  re-enabling is the one that leaves the declaration and the Class-1 cursor alone.

**Why owning tables, not columns or an author identity.** Entitlement is per owning table, so an author
entitled to one sensitive column of a table is entitled to all of them — a column renamed within a
table stays within the approval, and no identity has to be stored and re-evaluated with the author
absent. What the check catches is a table *joining* the set. After the evaluator fold and the
source-rename guard, the plainest remaining way is a column the pipeline already reads being marked
`sensitive` afterwards, after which nobody has decided its derived copies may stay readable.

**Why not make derived columns inherit sensitivity.** That is the aggregate lane's rule, and it would
also close the class. But `enrichment-registry` considered and rejected it for the enrich lane, in
normative text: the consequence "is explicit and accepted … controlling that is the enrichment author's
decision". Reversing that is a product decision, and it would refuse legitimate de-identifying
derivations (`length(request_body)`). D11 keeps the accepted design and enforces its premise.

**Seeding.** A pipeline stored before D11 has no approval, and is seeded on its first egress under
enforced security with the owning tables of everything its declaration may read — not only what that
egress reads, because a group pipeline's scan and group assembly read different subsets and a seed from
whichever ran first made the other refuse. Approvals are likewise recorded only under enforced security;
one taken in `none` mode would name reads no authenticated author decided. Everything stored before this deploy was authored when only the
full administrative role could author, and that role conferred every sensitive read, so this is the
right trust anchor rather than a shortcut. The edit-surface checks cover the window before that read
and remain the primary controls; D11 is the net under them.

**The approval is only as good as the entitlement checks feeding it.** Its first review found that
`trigger.group_by` and the grain key joined the gate's reference set without an entitlement check, so a
table could be recorded as approved by an author never checked against it — and a column later marked
sensitive on that table then read as already approved. Both are checked now. The rule to keep: *nothing
enters the approval that the gate did not check against the author.*

*Scope:* not the Class-1 **preview**, which writes nothing, already enforces its caller's own
entitlement, and is where an author diagnoses a refused pipeline. Not the aggregate lane, which has its
own backstop. Inert in `none` mode, like every check beside it.

*Rejected:* re-running the full governance gate at each egress. It would need the author's authorities
with the author absent, and it would turn every catalog drift anywhere into a stopped pipeline — the
reason `rederiveOutputBindings` deliberately does not.

### D12 — `TABLE_LIST` is checked per table but has no grant key

Decided after archive, on review. The document first carried a sixth key, `list`, granting a single
table's *name* without its definition. That grant meant nothing useful:

- **Listing is a global question.** "Which tables exist?" is answered over the whole catalog, so
  `TABLE_LIST` held globally is the natural reading: the holder enumerates every table.
- **On one table, `get` already lists it.** `TABLE_GET → TABLE_LIST` is an edge of the ladder, so every
  grant that shows a table — `get`, `read`, `read_sensitive`, `modify` — confers listing it.
- **Name-without-definition protects nothing.** D7 makes column names non-secret; `GET \ LIST` is the
  column inventory and grain, so a table whose name is disclosable has a disclosable definition too.

Decision: `TABLE_LIST` stays a privilege, stays **table-scoped** (the list endpoint checks it per table,
and a per-table `get` holder passes that check by implication), but is **not grantable**. The
document carries five keys; a `PUT` naming `list` is `400` through the strict request mapper, never
silently dropped; the reported `permissions` object drops its `list` flag, which could only ever read
`true` for a caller that sees the table at all.

`PrivilegeScope` therefore distinguishes `TABLE_SCOPED` (six, checkable on one table) from `GRANTABLE`
(five, carrying a list), and the implication walk in `TableCallerPermissions.isGranted` consults only
the latter.

*Rejected:* keeping `list` for symmetry with the ladder. Symmetry is not a use: no persona needs to
know a table exists while being barred from its column names.

## Risks / Trade-offs

- **A missed annotation is an open endpoint** → D10's coverage test plus a `denyAll` default; the
  test fails the build rather than reporting at runtime.
- **The change is large enough to land badly** (~35 main files, ~20 test files, the Flight authorizer,
  one migration) → see Migration Plan: the privilege enum and bundles land first and are inert while
  the two bundles reproduce today's authority, so each subsequent step is independently revertable.
- **Narrowing requires a config edit, not just a grant** → effective access is a union, so a role
  mapped to `READ_ONLY_ANALYTIC` keeps reading every table however grants are set. Documented as the
  two narrowing paths, and `PUT .../access` reports the ineffective grant instead of accepting it
  silently. Rejected the alternative — a non-empty list narrowing even a global holder — because an
  allow list that changes meaning when it becomes non-empty is non-monotone: one `PUT` would revoke
  access for every other caller in the deployment with nothing in the request showing it.
- **Two rules in one JSON document** — three monotone rungs plus two independent keys → stated in the
  spec and in the `PUT` response's effective flags; rejected nesting the ladder because it breaks the
  uniform array shape and pushes the same ambiguity one level down.
- **`REGISTRY_ADMIN` cannot later become table-scoped** → accepted for now; a team that owns one
  domain's tables still cannot author a rule targeting its own table. If that persona arrives, the
  names to introduce are `RULE_WRITE` / `PIPELINE_WRITE`, which can carry per-table keys.
- **Pipeline reads widen** to `READ_ONLY_ADMIN` → deliberate and specified; it corrects an anomaly
  rather than following a rule.
- **The laundering subset rule can reject a `PUT .../access` naming a pipeline the operator was not
  thinking about** → the refusal names the rule and the derived column, and it needs its own stable
  `ErrorView` code.
- **D11 can stop a running enrich pipeline** when a column it reads is marked `sensitive` → intended:
  nobody has decided its derived copies may stay readable. The refusal names the tables and the remedy,
  and a governance pass by an entitled author resumes it. A false refusal — the gate's recorded set
  narrower than what an egress reads — would stop a legitimate pipeline instead; both are computed by
  one classifier over the same shape of set, and an oidc integration test registers a sensitive-reading
  pipeline through the API and materializes it within its approval.

## Migration Plan

Seven steps, each releasable:

1. **`SecurityMode`** (D8) and the **shared token builder** (D9). No behaviour change beyond
   rejecting an invalid mode at startup and populating provider roles on Flight; both are
   independently useful and unblock everything else.
2. **`Privilege`, `RoleBundle`, and mapping parse.** `roles-mapping` accepts bundle and privilege
   names; authorities become `PRIVILEGE_*`. `ApiKeyProperties.validate` stops requiring that some
   mapping yield an application role, and `UserRolesResolver` stops warning per request for a
   grant-only caller.
3. **Gate substitution.** `@FullAdminOnly` and the custom `@PreAuthorize` expressions become
   privilege requirements; the `/v1/**` rule becomes `.authenticated()`; the coverage test (D10)
   lands in the same step so the window in which an endpoint could be uncovered never opens.
4. **Object layer.** `Permission` folds into `Privilege`; `TableAccess` grows to five lists with the
   `access` column default migrated; the evaluator loses its role check; `permissions` reports five
   flags; `PUT` becomes a merge (D5) and system tables become grantable per key (D4).
5. **Read enforcement.** Per-owning-table visibility in the query model (D6), caller-filtered
   catalog and discovery, Flight per-RPC checks, `sensitive` flag moved to `TABLE_GRANT` (D3).
6. **Laundering subset rule** at rule registration and on `PUT .../access`.
7. **Sensitive-read approval** (D11): migration `V43` adds the audited column; the gate records it and
   the three enrich egresses check it. Existing pipelines seed on their first read, so no operator step
   is needed.

**Rollback**: steps 1–2 are additive. From step 3 on, rollback is a redeploy of the previous
version; no data migration is destructive — the `access` column's new keys are simply unread by the
older build, and its default change is backward-compatible because readers are null-safe through
field initializers.

**Sequencing against `add-table-read-grants`**: that change introduces a `read` list with
empty-means-open semantics and a `permissions.read` flag. Landing this change first makes both
unnecessary — the empty list is symmetric because global `TABLE_READ` is explicit, and the flag is
one of five. Its `RuleAuthorizer` source argument and its chokepoint work stand unchanged and are
absorbed by step 5. **Outcome:** this change landed first, and `add-table-read-grants` was retired
without being archived — its deltas MODIFY six requirements this change REMOVES, so archiving it
afterwards was impossible, and every piece of it is covered here.

## Open Questions

These are deferrable: none changes the specs, the approach or the task breakdown.

1. Should a fifth bundle name the grant-only analyst — `{SAVED_QUERY_READ, SAVED_QUERY_WRITE}` and no
   global read — or is an explicit privilege list in configuration enough?
2. Should the list item stop emitting `ordering_key`, `partition_by.column`, `ttl.column`,
   `identity_column` and `version_column`, which name 3–6 physical columns per table at
   `TABLE_LIST`? Today's behaviour is retained. D12 narrows this to the global `TABLE_LIST` holder: a
   per-table grantee can only list a table it may already `get`.
3. Should the audit feed's table-visibility predicate be added here or with the capability that owns
   `/v1/activities`, which has no spec under `openspec/specs/` yet? The predicate must be part of the
   SQL query rather than a post-filter, because paging happens in SQL.
4. Grant values are raw provider role names, which are provider-specific — Azure emits group GUIDs,
   Keycloak emits names, Core project keys emit Core role names. Worth deciding before the lists
   grow, since the values are persisted.
5. Is `READ_ONLY_SENSITIVE_ANALYTIC` the right spelling, or `READ_ONLY_ANALYTIC_SENSITIVE`, which
   puts the qualifier last as `TABLE_READ_SENSITIVE` does?
