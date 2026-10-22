## Deviations from this plan, and why

Four from the plan as written, plus what two review rounds changed, all recorded here rather than
left implicit:

- **The base moved.** The plan was written against an older base; in the meantime the codebase had
  folded `/v1/rules` into `/v1/pipelines` (`V36__fold_rule_into_pipeline.sql`),
  renamed `RuleAuthorizer` to `PipelineAuthorizer`, and added `POST /v1/queries/validate-sql`.
  The newer base was taken first and the `enrichment-runtime` / `enrichment-registry` /
  `aggregate-pipeline` deltas were rewritten onto the folded surface. The migration is **V42**, not V33 — it was V41 until the
  evaluator fold landed its own `V41__fold_evaluator_into_pipeline.sql`, and numbering follows merged history.

- **`UserRole` was deleted, not kept** (task 2.7). The task predates the two analyst bundles; with four
  bundles, `RoleBundle` is the home for every reserved name and a separate two-value enum would be pure
  duplication. `FULL_ADMIN` and `READ_ONLY_ADMIN` are `RoleBundle` constants, which is what the task's
  "keep only as the two reserved bundle names" asked for.

- **Task 6.1 was dropped** — the enrichment analogue of the aggregate sensitive-to-sensitive rule. It
  contradicts a shipped requirement with a stated rationale: `enrichment-registry` specifies that derived
  enrichment columns SHALL NOT inherit sensitivity and that such a pipeline SHALL NOT be rejected, because
  registration must not mutate catalog metadata, and `Class1MaterializeIntegrationTest` pins it. The two
  paths differ for a reason the specs already give: the aggregate scheduler refreshes under an elevated
  system identity with no caller to gate, whereas every enrichment derive path runs under the caller's own
  identity and is already refused unless that caller is entitled. The subset rule (6.2) is what the
  `enrichment-runtime` delta actually requires, and it is implemented at both registration and `PUT`.

- **`GET /v1/tables` answers `200` with no entries, not `403`.** The `authentication` delta carried a
  scenario saying a caller with no privilege gets `403` on a catalog listing, which contradicted
  `table-management`'s normative "an empty listing SHALL be `200` with no entries rather than `403`".
  The normative text won; the scenario was rewritten onto a global-only endpoint. An `@AuthenticatedOnly`
  declaration was added for `GET /v1/queries/functions` so the coverage test still demands one explicit
  requirement per mapping.

## What the review rounds changed

Seven review rounds found defects the plan did not anticipate. The substantive ones, because each
changed a contract rather than an implementation detail:

- **`sensitive_grant_widens` was not total** (task 6.2). The `PUT .../access` check asked only "did
  this request widen the enrichment's list", which missed narrowing the *source* while an enrichment
  still named the role, and missed a column becoming `sensitive` after the grants were already laxer —
  `PATCH`/`POST .../schema` bypassed it entirely. The rule now lives in one `SensitiveGrantInvariant`
  with three entry points, and its predicate was widened to "the target enrichment **carries** a
  `sensitive` column": the narrower "derives one from a sensitive source column" is not answerable at
  grant time without enumerating every pipeline into the enrichment, and a predicate that holds at
  registration but not at grant time is what left the hole.

- **The global-holder exemption was unsound and was dropped.** The invariant exempted a role that holds
  `TABLE_READ_SENSITIVE` globally, resolved through `ProviderRolePrivileges` — a **union across every
  configured provider**. That would exempt a role on the strength of a mapping a caller arriving through
  a different provider does not have. Exemption is now only "the source's own list names it".
  `ProviderRolePrivileges` remains, advisory, feeding the ineffective-grant report.

- **The invariant runs in `none` mode**, unlike the privilege checks around it. It is not an
  authorization check but a constraint on what stored state may exist, and a grant document written
  while security was off goes live the moment a deployment switches to `oidc`. The
  `table-access-control` delta carries the carve-out explicitly.

- **`AccessReplacedDto` kept the six lists at the top level.** Nesting them under `access` alongside the
  new `permissions` and `ineffective_grants` was an undeclared break for a client reading `write`.

- **`SqlQueryFrontEnd.translate(sql, bar)` discarded its bar**, so `translate-sql` and `validate-sql`
  planned at `TABLE_READ` while declaring `TABLE_GET` — two named delta scenarios were unimplemented.

- **`TableAccess` needed value equality.** Without `@EqualsAndHashCode` Hibernate's JSON dirty check
  rewrote every definition loaded in a write transaction, and Envers recorded a revision attributed to
  whoever triggered the load. Two audit-feed tests caught it. The V42 migration therefore needs no
  backfill: an absent key reads as an empty list and the default covers new rows.

- **Six shipped requirements still name `FULL_ADMIN` or `READ_ONLY_ADMIN` and carry no delta.** Left
  deliberately. The criterion applied: a mention is a finding only where it states an authorization rule
  the code contradicts. All six are incidental scenario actors or test-class references where the name
  remains a valid configured bundle, so the text stays true.

### Round 3

- **The laundering invariant followed the wrong edge** (task 6.2, again). It compared a target's grants
  against its **catalog parent**, but `EnrichSpecValidator.requireReadSource` lets a declaration name
  any ACTIVE source, so a pipeline could copy a sensitive column of `s2` into an enrichment of `s1` and
  have it served to `s1`'s grantees. And an **aggregate rollup target** has no catalog parent at all —
  it is a `SOURCE` with its own `read_sensitive` list, addressable without any grant on the table it
  rolls up — so the rule never examined it. The relation is now "reads from": the union of the catalog
  edge and the pipeline edge, in both directions, with two scalar projections added to
  `PipelineRepository` (entity loads there would dirty-flush a pipeline row and open an Envers revision,
  the same hazard `TableAccess` cost two audit-feed failures). `AggregateSpecValidator` now runs the rule
  at registration, and `aggregate-pipeline` carries the requirement.

- **`POST /v1/tables/{name}/schema` was a fourth unguarded way in.** Grant `read_sensitive` on a
  `PENDING` enrichment while it has no columns (accepted — nothing to protect), then define a schema
  declaring one. `TableSchemaCreationService` now runs the rule, making the invariant's four entry points
  total.

- **`SAVED_QUERY_PUBLISH` authorized nothing.** Every saved-query write endpoint gated on
  `SAVED_QUERY_WRITE` and resolved scope downstream, and `requireWritable` checked only `common` — so a
  `PUBLISH`-only caller was refused at the gate, and an authenticated caller holding neither privilege
  could create a `personal` row. The three gates are now `deferred` and `requireWritable` decides both
  scopes. `deferred` accordingly widened from "the tables are not known" to "the discriminator is not
  known here", and the coverage test's coherence rule with it. One implication edge was added,
  `SAVED_QUERY_PUBLISH → SAVED_QUERY_READ`, on the same disclosure-monotonicity test as the four ladder
  edges: `PUBLISH` already sees every saved query, so it reveals a superset of what `READ` does.

- **`ProviderRolePrivileges` made a `none`-mode deployment fail startup** over a `roles-mapping` that
  mode ignores. The other three parse sites are `oidc`-only; this bean is registered in every mode, so a
  stale or misspelled mapping that used to be ignored became a refusal to boot. It now parses nothing
  when security is not enforced, where its advice has nothing to be right about anyway.

- **Two delta blocks were filed under the wrong section.** `Opaque token introspection` sat under
  `REMOVED` while reproducing the requirement with one edit (archive would have deleted shipped
  behaviour the code still implements), and `Scheduled refreshes execute under a system identity` under
  `ADDED` although it exists verbatim in the shipped spec. Both were MODIFIED blocks. The cause was a
  generator appending blocks with no section header, which the normalizer then assigned to whatever
  section came last in the file; a structural check now covers it.

- **Three delta statements contradicted the code**: `table-management` still specified a two-field
  `permissions` object (it has six), `authentication` claimed an unmodified `roles-mapping` "changes
  nothing observable" (the `GET .../access` break is declared in two other places), and the bundle
  scenario said `READ_ONLY_ANALYTIC` confers "no other privilege" than its three declared ones, when a
  bundle is stored minimally and closed under implication.

- **Two minors left unfixed, deliberately, and recorded rather than silently dropped.** The audit feed
  (`AUDIT_READ`) returns `resourceId` for every `Table` activity, so it enumerates table names a caller
  cannot list — existence and metadata only, no column data or grant lists, and the consistent fix is a
  `Table`-typed narrowing at `TABLE_LIST` mirroring the saved-query one. And `DATA_SYNC_OPERATE` cannot
  poll the run it starts (`GET /v1/data-sync/backfill/{runId}` requires `REGISTRY_READ`); the docs now
  say a backfill key needs both, but the endpoint pair is still split across two privileges.

### Round 4

Round 4 verified round 3's fixes against the code. Four of the five held; the laundering relation did not.

- **The relation was still missing an edge: an enrichment reached through the grain join.** A declaration
  addresses such a column as `"<enrichment>.<column>"`, so the table whose values are copied appears
  nowhere on the pipeline row — its `input_table` is the source. Neither new query could see it, and the
  reverse direction was empty too (`derivedFrom` skips the catalog query for an enrichment, and an
  enrichment is never a pipeline's `input_table`), so a rollup of `usage_client_identity.user_email` into
  a target granted more widely than that enrichment passed registration *and* survived a later narrowing
  of the enrichment. That is the same disclosure the `input_table` edge closed, one join further out.
  `PipelineInputDependents` — which exists precisely because this edge lives only in the parsed
  declaration — gained both directions, and the invariant now consults them. It is injected `@Lazy`: the
  implementing component depends on both validators, which depend on the invariant.

- **The relation is deliberately not narrowed to "a source that itself carries a sensitive column."** An
  author may declare a derived column `sensitive` whose origin is not flagged — the ordinary aggregate
  shape — so a source with no flagged column can still be where protected values come from. The cost is
  that a target carrying a sensitive column must hold its `read_sensitive` grants within every table it
  reads, and the remedy is the same grant on that table.

- **The deferred saved-query gates opened a 404-vs-403 existence oracle.** `replace` and `delete` load the
  row to learn its scope, so a caller holding no saved-query privilege could tell an existing `common`
  row from an absent one — which the non-deferred gate refused outright. `requireAnyWriteAuthority` now
  runs before the lookup; `requireWritable` still decides the scope-dependent half.

- **`aggregate-pipeline` stated the laundering condition narrowly**, contradicting `enrichment-runtime`,
  the code and its own third scenario, and named two enforcement points where there are four.

- **The "structural check now covers it" claim from round 3 was false when written.** There was no such
  check: `openspec validate --strict` passes on a misfiled block, and nothing else read `openspec/`.
  `DeltaSpecStructureTest` now enforces the three section rules, and `build.gradle` declares `openspec/`
  as an input of the `test` task — without that Gradle reports the task up to date after a spec-only edit
  and the check never runs against the change it guards. Verified by mutation.

- **One review finding was not fixed, and the tool is why.** `audit-trail`'s scenario title *Pipeline
  snapshot needs only an application role* names vocabulary this change removes (the body names
  `REGISTRY_READ` correctly). Renaming it inside a MODIFIED block is a **drop**: `openspec validate
  --strict` requires a MODIFIED requirement to carry every shipped scenario verbatim, and refuses the
  change. The same stale phrasing appears in a few other scenario titles. They are cosmetic, and the place
  to fix them is the shipped spec after archive, not a delta that cannot express a rename.

### Round 5

- **A fifth way around the laundering rule, at a moment none of the earlier rounds named.** Every
  previous fix guarded a write to a grant list or a `sensitive` flag. Registering a new **evaluator
  version** writes neither — it rewrites what a stored pipeline *reads*, which is the other half of every
  pair. For a `sql` evaluator the read references live in `outputs[].sql`, and
  `EvaluatorDependentValidator.revalidate` re-ran only output-name resolution, so a v2 naming
  `"<enrichment>.<column>"` added a grain-join edge to every unpinned dependent with no check. The edge
  goes live immediately (an unpinned declaration resolves `latest`), and Class-1 has **no runtime
  backstop** — the aggregate lane re-runs its whole gate on every refresh, while materialize checks only
  the calling identity's entitlement, which the runner's elevated identity always satisfies. The check
  now runs at the edit that creates the edge.

- **The invariant has no database-level backing**, and nothing in the repo prevents a future migration
  from writing a grant document or a `sensitive` flag directly. No shipped migration does — `V42` changes
  only the column DEFAULT, and the two that mark columns sensitive predate per-table grants — so this is
  stated in the `enrichment-runtime` spec rather than enforced.

- **The two new registry lookups failed open on a drifted declaration, and inconsistently.** They resolve
  every stored declaration to find the grain-join edge, and the two resolvers behind them disagree: the
  aggregate one swallows any `RuntimeException` and returns "no enrichments", the enrich one lets it
  escape. Both were written for a delete guard and a freshness diagnostic, where degrading is right. Under
  the subset rule "cannot be read" and "reads nothing" became the same answer, and the second **accepts a
  grant the rule exists to refuse** — one drifted row silently reopened the laundering path. The other
  half turned every grant edit and every sensitive-column schema patch, on any table, into a 500 naming an
  unrelated pipeline. Both now refuse, named, with a new `409 pipeline_declaration_unresolvable`.

- **The fifth implication edge was withdrawn.** Round 3 added
  `SAVED_QUERY_PUBLISH → SAVED_QUERY_READ`, arguing that publishing already sees every saved query. It
  does not: only the revision endpoint and the audit feed consult `maySeeEverySavedQuery`, while `get` and
  `list` resolve visibility through a repository predicate with no such bypass. The edge therefore let a
  `PUBLISH`-only caller read another user's **personal** saved query at `GET /{id}/revision/{rev}` while
  `GET /{id}` on the same row still answered `404`. The dead-privilege defect it was meant to help with is
  fixed by the deferred gates alone, so the edge is gone and the DAG is four edges again.

- **The coverage test's `deferred` relaxation was too broad.** Round 3 let any global-only privilege defer,
  which would have accepted `@RequiresPrivilege(value = AUDIT_READ, deferred = true)` on a wide-open
  handler — the exact class the test exists to catch. It is now an allowlist naming the three saved-query
  writes and the chokepoint that carries them.

- **Two cost defects from the new walk.** `requireNotWidened` parsed every declaration even for a table
  with no sensitive column, where the rule provably cannot fire; and `PATCH .../schema` ran the whole walk
  once per patched column for a verdict that is a property of the pair. Both are guarded now.

- **`build.gradle` declared all of `openspec/` as a test input**, so ticking a checkbox in a `tasks.md`
  the test never opens re-ran the entire suite, integration tests included. Narrowed to the spec files the
  test actually walks.

- **Left open: `revalidate`'s narrow re-check is wider than the laundering rule.** For an unpinned
  Class-1 dependent it also skips the caller's sensitive-column entitlement, the enrichment-freshness
  soundness rule, the acyclicity check and SQL expression validation, so a v2 naming a column the source
  does not have is stored and fails at the next materialize instead of at registration. That predates
  this change and is not about privileges; the laundering half is fixed here because this change is what
  claims the invariant is total.

### Round 6

- **The round-5 fix was half a fix, and the missing half was the critical one.** Registering a `sql`
  evaluator version re-ran the grant subset rule but not the **author's sensitive-column entitlement** —
  and the subset rule is *vacuous* whenever the target carries no `sensitive` column, which is the common
  case, because a derived enrichment column deliberately does not inherit sensitivity. That carve-out is
  sound only because the pipeline surface governs *who may reference* the protected column; a version
  registration moves the reference off that surface. So an author holding `REGISTRY_ADMIN` and nothing
  else could point an existing unpinned pipeline's expression at a column it may not read, let the
  runner's elevated identity copy the values into an **unflagged** column, and read them back with plain
  `TABLE_READ` — no `TABLE_GRANT` anywhere, two API calls from a stored pipeline. Before privileges
  existed the two could not come apart: registering an evaluator required the full administrative role,
  which conferred sensitive-column entitlement too. Separating `REGISTRY_ADMIN` from
  `TABLE_READ_SENSITIVE` is what made it reachable, so it is this change's defect to fix.
  `EnrichSpecValidator.recheckExpressionEntitlement` now re-runs that arm, and the evaluator path reports
  the same `403 sensitive_column_not_entitled` the pipeline surface gives for the same reference.

- **The newest call site used the lenient resolver** — the exact fail-open shape round 5 removed
  everywhere else. It now asks `referencedEnrichmentsOrThrow`.

- **Three statements had drifted from the code**: the invariant's javadoc still presented four drift
  paths as exhaustive when the evaluator edit is a fifth (and is enforced from another class);
  `enrichment-runtime` said "four points" above five bullets; and `409
  pipeline_declaration_unresolvable` existed only in this file. All three are stated where they belong.

### Round 7

- **A schema rename could repoint what a stored pipeline reads.** `requireNoPipelineReads` returned
  immediately for a `SOURCE`, on the reasoning that a source column drop "is already governed by the
  grain-key and scan-metadata rules". Those cover grain keys, ordering keys and cursor columns — not a
  column an expression reads. A rename preserves the `sensitive` flag and changes only the name, so a
  holder of `TABLE_MODIFY` on a source (which implies nothing but `TABLE_GET`) could free the name an
  expression reads and move a protected column into it: declaration untouched, flag untouched, so neither
  the `TABLE_GRANT` bar nor the subset rule fires, and the pipeline's elevated runner copies the values
  into an unflagged enrichment column that plain `TABLE_READ` reads. Same shape as round 6, reached
  through the schema surface and needing *less* privilege. Both halves of a rename are now asked, on
  sources as well as enrichments.

- **The round-6 gate promised a `403` it could not produce.** The front end resolves at the caller's own
  bar, so a protected column is simply absent from its model and Calcite answers "unknown column" — a
  `422` about a column that exists and is protected, with `checkEntitlement` never reached. Every sibling
  path already wraps the analysis in `SensitiveProjectionGuard.reattributing` to convert that back; the
  enrich registration gate did not, so neither the round-6 scenario nor the shipped `enrichment-registry`
  requirement was actually met. Both expression arms reattribute now.

- **Accepted and stated rather than fixed:** an evaluator author holding no `TABLE_READ` on a dependent's
  source can no longer register a version of that evaluator, because the entitlement re-check resolves
  through the front end. Fail-closed and arguably right — an author who cannot read the source cannot be
  shown to be entitled — but it is a behaviour change for a `REGISTRY_ADMIN`-only identity such as a CI
  bot.

- **The enrich filter arm still reports `422` where the aggregate lane reports `403`.** Pre-existing, and
  not reached by any scenario this change adds; the expression arms were fixed because round 6 put a
  claim on them.

### After round 7: the evaluator fold, and a design pass (design D11)

Seven rounds each found one more edit that changed what a stored enrich declaration read without the
governance gate running again, and rounds 5–7 each found the defect inside the previous round's fix. The
review loop was stopped there and the enrich lane redesigned instead of patched further.

- **The evaluator fold landed first**, and it closed one path outright: the transform now
  lives on the pipeline row and `POST /v1/evaluators` is gone, so changing what a declaration reads is a
  pipeline `PATCH`, which runs the full gate. `EvaluatorDependentValidator` and the checks rounds 5–6
  added to it were deleted with it. This change's migration was renumbered `V41` → `V42` behind the
  fold's own `V41`, and three MODIFIED delta blocks were three-way merged onto the fold's rewrite of the
  requirements they replace.

- **D11 adds a runtime backstop** where the enrich lane had none: the gate records the owning tables
  whose sensitive columns a declaration reads, and materialize, scan and group assembly refuse a read
  that has grown past it (`409 sensitive_read_unapproved`). It keeps the shipped design — derived
  columns do not inherit sensitivity — and enforces the premise that design rests on, instead of
  reversing a product decision `enrichment-registry` made on purpose. The residual it closes that no
  surface check covered: a column a pipeline already reads being marked `sensitive` afterwards. On a real
  deployment that can only happen to a user-created table — `system` tables refuse every schema patch —
  but D11 is deliberately indifferent to which edit it was.

- **One implementation trap, recorded because it cost a full suite run.** The approval column was first
  mapped as a JSON `List<String>`. Hibernate resolves basic types by Java type, and that one mapping
  re-typed every other `List<String>` in the model — `table_definition.ordering_key`, explicitly
  annotated as an array, began binding as `jsonb` and every table create failed with a 503. It is
  `text[]`, like every other such column.

- **Verified by mutation:** the oidc integration test that flips a read column's flag directly in the
  catalog — under every API guard, since a backstop must not depend on which surface did the edit —
  fails when the materialize check is removed.

### Review of D11

One adversarial review of the design pass. Two majors, both inside D11, both fixed:

- **The approval named tables no author was checked against.** `trigger.group_by` and the grain key joined
  the gate's reference set without an entitlement check, so a `REGISTRY_ADMIN`-only author could record a
  table as approved, after which a column later marked sensitive on it read as already approved — the
  very residual D11 exists to close. Both are entitlement-checked now; a sensitive grain key is copied into
  the target on every row anyway, so authoring over one was already a disclosure.

- **A legacy seed came from one egress's read set.** A group pipeline's scan and group assembly read
  different subsets, so a seed from whichever ran first made the other refuse an unchanged pipeline — a
  false refusal on a running workload. The seed now covers the whole declaration.

- **Building that fix exposed a latent gap in round 7's guard.** Both relied on `declaredFieldsOf`, which
  scans SQL fragments for qualified `table.column` tokens only — written for enrichment dependencies — so
  a bare source reference like `length(deployment)` was invisible to it. Round 7's source-rename guard
  therefore missed the exact shape of the attack it was written for; its unit test mocked the lookup.
  D11 still caught that attack at materialize, which is the backstop doing its job, but the guard's claim
  was false. `readFieldsOf` adds every identifier in every SQL fragment (an over-approximation, so a false
  positive refuses a rename nobody needed and a false negative is impossible) and both callers use it.

- **Minors fixed:** approvals are recorded only under enforced security (one taken in `none` mode would
  be trusted after a switch to `oidc`); the refusal names both remedies and what each costs. **Minor left
  open:** a legacy row's seed can be overwritten by a concurrent full-row `PATCH` that loaded it first —
  `PipelineEntity` has no `@Version` — after which the next egress re-seeds from what it then reads.
  Legacy-only and a narrow race.

- **The verifying round found no majors.** It confirmed every field entering the approval is
  entitlement-checked, every egress's reads are covered by a legacy seed, and no caller can put a pipeline
  back into the unapproved state under `oidc`. Fixed from it: the identifier scan now includes
  `_`-prefixed system columns, and a comment wrongly claiming aggregates name all input columns
  explicitly. **Left open, all minor:** the qualified-token scan is case- and whitespace-sensitive while
  the SQL parser is not, so a legacy pipeline spelling an enrichment column `Enr.Flag` can still be seeded
  narrowly; the identifier scan over-approximates (keywords, literals and the bare halves of `enr.col`
  feed the seed and the rename guard), which can over-approve a legacy seed or block a harmless rename;
  and `checkEntitlement` and `sensitiveOwnersOf` classify a name that is one column's exposed name and
  another's physical name differently, which can over-approve.

- **Three new integration tests call real beans where the old ones mocked them** — the seed through the
  real bulk update against Postgres, the source-column guard over a real stored declaration, and a
  `REGISTRY_ADMIN`-only author refused on a sensitive group key. Each fails with its fix removed.

## 1. Single mode resolution and one caller identity (design D8, D9)

- [x] 1.1 Add a `SecurityMode` component that resolves `security.mode` once, matches
      case-insensitively, and fails startup on any value other than `oidc` or `none`, with an error
      naming the offending value
- [x] 1.2 Replace all nine derivations with that component: both filter-chain conditionals, the
      Flight authenticator and principal middleware conditions, the API-key bean condition,
      `SensitiveColumnAccess`, `TableCallerPermissions`, `SavedQueryAccess`, `RuleAuthorizer`
      (now `PipelineAuthorizer`). The bean conditions go through `@ConditionalOnSecurityMode`, which
      funnels into the same `SecurityMode.parse`
- [x] 1.3 Extract one token-builder used by `JwtAuthenticationConverter`,
      `OpaqueAuthenticationConverter`, `ApiKeyAuthenticationFilter` and
      `FlightHeaderAuthenticator.authenticateApiKey`, emitting mapped authorities **and**
      `PROVIDER_ROLE_*` on every path
- [x] 1.4 Test that the same API key yields identical authorities whichever surface populates
      `ApiKeyCache` first, and that `security.mode=OIDC` enforces on HTTP *and* Flight

## 2. Privilege enum, bundles and mapping (design D1, D2)

- [x] 2.1 Add the `Privilege` enum (16 values) with scope classification kept in the grant package,
      plus a test asserting every value is classified as table-scoped or global-only
- [x] 2.2 Add the implication DAG as data (4 direct edges) with a transitive closure helper, and a
      test asserting the closure of each edge set
- [x] 2.3 Add `RoleBundle` with the four bundles stored minimally, and a test asserting each
      closure equals its expected privilege set
- [x] 2.4 Change the three `roles-mapping` parse sites (`IdentityProviderUtils`, `ApiKeyProperties`,
      the resolver map) to `Map<String, Set<Privilege>>`, accepting bundle and privilege names mixed
      and failing startup on an unknown token
- [x] 2.5 Emit `PRIVILEGE_*` authorities from `UserRolesResolver`; drop its per-request warning for a
      caller that maps to nothing
- [x] 2.6 Remove the `ApiKeyProperties.validate` check that requires some mapping to yield an
      application role — a grant-only deployment is legitimate
- [x] 2.7 Delete `UserRole`; `RoleBundle` carries the reserved names (see Deviations)

## 3. Gate substitution and fail-closed coverage (design D10)

- [x] 3.1 Add a `@RequiresPrivilege` annotation and replace every `@FullAdminOnly` and
      `@PreAuthorize` site with the privilege each endpoint requires per the operation table in the
      specs (54 request mappings across 16 controllers)
- [x] 3.2 Replace the four programmatic `FULL_ADMIN` checks (`TableAccessEvaluator`,
      `TableCallerPermissions`, `SensitiveColumnAccess`, `SavedQueryAccess`) with privilege checks
- [x] 3.3 Replace `FlightHeaderAuthenticator.authorized`'s role test with the "could match something"
      check, and filter the metadata RPCs through the same per-table chokepoint HTTP uses
- [x] 3.4 Change the `/v1/**` URL rule to `.authenticated()` and make the chain's default `denyAll`
- [x] 3.5 Add the coverage test asserting every `@RequestMapping` method under `/v1` declares exactly
      one requirement, and that the declaration is coherent
- [x] 3.6 Delete `@FullAdminOnly`

## 4. Object layer: grant keys (design D4, D5; five since D12)

- [x] 4.1 Fold `Permission` into `Privilege`; grow `TableAccess` to six lists keyed by the
      table-scoped privilege name minus `TABLE_`
- [x] 4.2 Add the Flyway migration for the `access` column default (**V42**, the next slot after
      the evaluator fold's V41)
- [x] 4.3 Rewrite `TableCallerPermissions.isGranted` to take a `Privilege` and apply the implication
      closure, replacing the hard-coded write/modify ternary; keep one matcher shared by enforcement
      and reporting
- [x] 4.4 Remove the `FULL_ADMIN` short-circuit from `TableAccessEvaluator`: a global privilege holder
      is authorized because it holds the privilege, with no role-name check
- [x] 4.5 Grow `EffectivePermissions` and `TableDto.PermissionsDto` to six flags, reported after
      implication; keep a `system` table's `write`/`modify` hard `false`
- [x] 4.6 Make `PUT /v1/tables/{name}/access` a merge — absent keeps, `[]` revokes — on `AccessDto`
      and `ReplaceAccessRequest`
- [x] 4.7 Move the system-table refusal from the object to the keys: read-side keys always settable,
      `409 table_is_system` only for a **non-empty** `write` or `modify`
- [x] 4.8 Return the effective permission matrix from `PUT .../access`, and report each listed role
      that already holds the privilege globally and each `read` grant on an enrichment whose source
      the role cannot read
- [x] 4.9 Re-gate `GET /v1/tables/{name}/access` to `TABLE_GRANT`

## 5. Read enforcement (design D3, D6, D7)

- [x] 5.1 Change `CatalogQueryService.buildModel` to decide read and sensitivity **per owning
      table**, replacing the hoisted boolean. The visibility bar became an explicit parameter on
      `findModel`/`queryableEntityNames` rather than a default, so no call site can inherit the wrong one
- [x] 5.2 Make an entity visible iff its source is readable; drop unreadable enrichments' columns
      from the model and verify row counts are unchanged
- [x] 5.3 Filter `GET /v1/tables` by `TABLE_LIST` and answer `404` on a single get or revision the
      caller lacks `TABLE_GET` for
- [x] 5.4 Filter `GET /v1/queries/entities` by `TABLE_LIST`, gate the per-entity schema at
      `TABLE_GET`, and leave `GET /v1/queries/functions` at authentication only
- [x] 5.5 Gate `POST /v1/queries/translate`, `/translate-sql` and `/validate-sql` at `TABLE_GET`; keep
      execute and execute-sql at `TABLE_READ` on every resolved table
- [x] 5.6 Filter the Flight metadata RPCs by `TABLE_READ` (not `TABLE_GET`), so nothing advertised is
      unrunnable
- [x] 5.7 Require `TABLE_GRANT` for a `sensitive` flag change in `TableSchemaService`, in either
      direction, keeping the existing cursor-column restriction
- [x] 5.8 Change `PipelineAuthorizer` to require `TABLE_READ` on the pipeline's read source **and**
      `TABLE_WRITE` on the target enrichment, and update its five callers
- [x] 5.9 Narrow `SystemIdentity` to `{TABLE_READ, TABLE_READ_SENSITIVE}` and rewrite its javadoc
      justification
- [x] 5.10 Map the remaining surfaces: saved queries to the three `SAVED_QUERY_*` privileges, the
      registry to `REGISTRY_READ`/`REGISTRY_ADMIN` (pipeline reads included), backfill to
      `DATA_SYNC_OPERATE`, sink lifecycle to `TABLE_CREATE`/`TABLE_DELETE`, OTLP ingest to
      `TABLE_WRITE`

## 6. Laundering subset rule

- [x] 6.1 Dropped — contradicts a shipped requirement; see Deviations
- [x] 6.2 Enforce the subset rule at pipeline registration **and** on `PUT .../access` — a target's
      `read_sensitive` list may not exceed its read source's for an already-derived sensitive column —
      with its own stable `ErrorView` code (`sensitive_grant_widens`) and an `@ExceptionHandler`

## 7. Tests

- [x] 7.1 Update the test files naming the two roles and those configuring `roles-mapping`
- [x] 7.2 Add integration coverage for per-table read: filtered listing, `404` on an unreadable get,
      per-owning-table enrichment hiding
- [x] 7.3 Add coverage for the read ladder and implication, including `WRITE` conferring no read and
      `MODIFY` conferring schema visibility
- [x] 7.4 Add coverage for system-table grants: read-side accepted, non-empty `write` refused `409`,
      empty `write` accepted
- [x] 7.5 Add coverage for the merge `PUT` (absent keeps, `[]` revokes) and for the ineffective-grant
      report
- [x] 7.6 Add coverage for `sensitive` flag changes refused at `TABLE_MODIFY` and allowed at
      `TABLE_GRANT`
- [x] 7.7 Add coverage for the pipeline data plane requiring source read and target write, and for the
      runner working with grants alone
- [x] 7.8 Add coverage for Flight: metadata filtering, a grant-only API-key caller admitted, and a
      caller that can match nothing refused

## 8. Docs

- [x] 8.1 Rewrite the two-role passages in `docs/configuration.md` as the privilege and bundle model,
      keeping one line per property and no behaviour prose
- [x] 8.2 Update `README.md`, `docs/claude-code-telemetry.md`, `docs/column-mapping.md`,
      `docs/grafana-dashboards.md`, `docs/heavy-columns.md`, `docs/release-0.2-upgrade.md` and
      `docs/sql-reference.md` to the new vocabulary
- [x] 8.3 Update the OpenAPI description strings naming `FULL_ADMIN` in `QuerySchemaFieldDto`,
      `SavedQueryRequestDto`, `CreateTableRequest`, `SavedQueryController`, `SqlValidationController`
      and `TableAccessController`
- [x] 8.4 Document the two narrowing paths — remap a provider role to a narrower privilege set, or
      split the provider role — and the rule that a tenant role should not carry global `TABLE_READ`
      if its tables are to be granted individually

## 9. Verification

- [x] 9.1 Confirm the `access` JSONB default migration applies cleanly against a catalog holding
      pre-change rows (the Testcontainers suite migrates from V1 every run, and `TableAccess` carries
      value equality so a two-key row is never rewritten behind an unrelated caller's revision)
- [x] 9.2 Run `openspec validate add-application-privileges --strict`
- [x] 9.3 Run `./gradlew clean build`

## 10. Post-archive: no per-table `list` key (design D12)

Raised in review: listing is a global question, and a `get` grant already lists its
table, so a per-table `list` key granted a name with nothing to protect behind it.

- [x] 10.1 Split `PrivilegeScope` into `TABLE_SCOPED` (six, checkable per table) and `GRANTABLE` (five);
      `grantKey` and the implication walk in `TableCallerPermissions.isGranted` consult `GRANTABLE`
- [x] 10.2 Drop `list` from `TableAccess`, `ReplaceAccessRequest`, `AccessDto`, `AccessReplacedDto` and
      `TableDto.PermissionsDto`; keep `EffectivePermissions.list` internal for the catalog filter
- [x] 10.3 Edit `V42__table_access_read_keys.sql` in place to the five-key default — it has not shipped
- [x] 10.4 Tests: a `get` grant lists the table; a body carrying `list` is `400`; the reported
      `permissions` has no `list`; `GRANTABLE` excludes `TABLE_LIST`
- [x] 10.5 Specs: `table-access-control`, `authentication`, `table-management`,
      `query-schema-discovery` (main and this archive's deltas)
