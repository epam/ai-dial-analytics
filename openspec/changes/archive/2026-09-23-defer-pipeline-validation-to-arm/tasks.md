## 1. Storage

- [x] 1.1 Write `V42__defer_pipeline_validation.sql`: drop `trigger_kind`'s `NOT NULL`; re-scope
  `pipeline_input_required_unless_enrich`, `pipeline_cron_iff_schedule` and
  `pipeline_group_by_iff_group` to `NOT enabled OR (…)`, each written so it evaluates to FALSE and not
  NULL on a missing `trigger_kind` (`trigger_kind IS NOT NULL AND …`); add
  `pipeline_armed_is_complete`. Leave `pipeline_member_select_only_group`,
  `pipeline_aggregate_is_scheduled` and `target NOT NULL` untouched.
- [x] 1.2 Confirm V42 is unique among the Flyway migrations — a duplicate Flyway version never
  conflicts in git and instead fails every test at startup.
- [x] 1.3 Relax `@Column(nullable = false)` on `PipelineEntity.triggerKind`; Hibernate enforces it at
  flush, so the migration alone leaves every draft write failing.
- [x] 1.4 Confirm no `pipeline_aud` change is needed (no column is added) and add a test asserting the
  mirror still carries every audited column.
- [x] 1.5 Make `PipelineService.save` map **by constraint name** for every pipeline CHECK, not only the
  new one; today any `DataIntegrityViolationException` is reported as "pipeline name or target already
  in use". `pipeline_member_select_only_group` stays verbatim and evaluates to FALSE for an
  ordinary half-authored draft (a `member_select` with `trigger_kind = 'SCHEDULE'`), so without this the
  author is told their name is taken.

## 2. Request layer

- [x] 2.1 Make `inputs`, `filter`, `group_by`, `measures`, `trigger`, `transform` and `advanced`
  optional on `CreatePipelineRequest`; keep `@NotBlank name`, `@NotNull kind`, `@NotBlank target`.
  Bean validation already allows this — the refusals come from `singleInput`, `requireEnabledStated`
  and the gate — so the real work is the `@Schema` prose, which still says `inputs` is "Required for
  `aggregate`" and `enabled` "Required for `enrich`". Both are published in the OpenAPI document the
  sibling MCP repo generates clients from.
- [x] 2.1a Make `target` sticky on a patch (D14): omitting it keeps the stored value, supplying a
  different one repoints. Without this `requireTargetAvailable` refuses every draft save that does not
  restate `target`, which breaks the authoring loop on the author's second request.
- [x] 2.2 Scope `PipelineMapper.requireComplete` to a patch that leaves the pipeline armed. Its call
  site does not currently receive the stored `enabled`, so thread it through `toRegistration` (or move
  the call beside the existing `armed` computation in `PipelineService.patch`).
- [x] 2.3 Drop `requireEnabledStated`; an absent `enabled` means `false` for both kinds.
- [x] 2.4 Relax `PipelineMapper.singleInput` so a non-`enrich` kind with no `inputs` is accepted while
  unarmed, and refused at the gate.

## 3. The derivation / refusal split

- [x] 3.1 Split `PipelineService.validateAndStore` so derivation runs on every declaration write and
  refusal runs only when the write arms. The aggregate arm currently ignores `armed` entirely.
- [x] 3.2 Make derivation best-effort and non-destructive: where `deriveOutputBindings` or
  `deriveGroupKeys` cannot produce a value, retain the stored one rather than writing an empty
  collection. This is what keeps the catalog's drop/rename guard armed.
- [x] 3.3 Store a derived member as an empty collection, never null, so a partial declaration has one
  representation in memory and in storage.
- [x] 3.4 Arm-scope `triggerKindOf` (it defaults an aggregate to `SCHEDULE` and throws for an enrich
  with no trigger) and the two conditional checks in `requireValidCron`, keeping its cron-parse check
  on every write.
- [x] 3.5 Require `trigger_kind` **and a valid cron** on the arming path in the service layer. The
  enable-only patch branch (`registration == null`) never calls `apply()`, so it runs neither
  `triggerKindOf` nor `requireValidCron` today. Arming an aggregate draft that declared
  `trigger: {kind: schedule}` with no cron currently reaches `AggregateScheduler.nextRun` →
  `CronExpression.parse(null)` → **500**, before the re-scoped CHECK can fire.
- [x] 3.5a Keep the template-placeholder check when preview runs the gate on a disabled enrich
  pipeline: `validate(declaration)` always checks placeholders (there is no `armed` parameter any
  more), so the full gate never drops what `validateForPreview` performs today.
- [x] 3.6 Null-guard `AggregateSpecValidator.declaredFieldsOf` and `AggregateSpec.groupKeyOutputNames`.
- [x] 3.7 Give `EnrichSpecValidator.referencedEnrichments` the non-throwing contract its aggregate twin
  already has, so a drifted declaration cannot fail an unrelated table delete or schema patch.
- [x] 3.8 Keep two refusals **unconditional** on every write: a `member_select` declared against a
  non-`group` trigger, and a non-`schedule` trigger on an `aggregate`. Both are refused by CHECKs that
  hold for every row, so without a service-layer refusal the author meets them as a bogus 409.
- [x] 3.9 Fix the false refusal text at `AggregateSpecValidator:217` ("its evaluator is not 'sql'") so
  it names the new incomplete-writer clampability instead of misreporting the cause.

## 5. Execution entry points

- [x] 5.1 Preview, per kind: `aggregate` keeps the full gate in **both** states (it already runs it —
  do not introduce a "lighter" enabled path, which would drop sensitivity propagation and the
  inactive-input refusal); `enrich` runs the full gate when disabled and keeps `validateForPreview`
  when enabled.
- [x] 5.2 Refuse a declaration with **absent** required members on `/scan`, `/group-rows`,
  `/materialize` and `/rows`, 422 `pipeline_validation_failed`, before any ClickHouse statement and
  after the contract-header and authorization layers.
- [x] 5.2a Add exactly two re-checks to `MaterializeService` (D13): the result-type fit against the
  bound target column, and the row-grain rule against the read source's `identity_column`. Do **not**
  run the whole gate there — a disabled pipeline is refused 409 first, so what reaches it is an enabled
  pipeline that may have drifted since arming, and the full gate would stop it mid-run for drift the
  operator did not cause.
- [x] 5.2b Treat stored `output_bindings` that no longer re-derive from the current transform as
  absent for 5.2, so `/materialize` cannot write a new expression into a column the previous transform
  bound.
- [x] 5.3 Fix both `trigger_group_by` paths, which fail differently: `GroupFetchService:192`
  dereferences it unguarded and is an **NPE/500**; `ScanService:178` guards the null but then adds it
  to the projection at `:190`, producing a **null column name** in the query.
- [x] 5.3a Update `EnrichWriteValidator`'s javadoc invariant — it asserts a registered pipeline's
  permitted-output set is never empty, which this change falsifies — and make the `/rows` refusal name
  the real codes: an out-of-set label column is `write_column_not_allowed`, a row carrying no output
  column at all is `write_output_column_required`. The allow-list is never literally empty (it always
  holds the provenance columns and the grain key).
- [x] 5.4 Refuse `GET /v1/pipelines/{name}?view=compiled` on an incomplete declaration with 422 naming
  what is missing, rather than serving empty members.
- [x] 5.4a Compiled **listing** behaviour: omit an incomplete entry rather than refusing the listing,
  and do not log it as an error while the pipeline is disabled. Only a named single-pipeline read
  refuses.
- [x] 5.5 Stop `withResolvableOutputMapping` logging at ERROR for a **disabled** pipeline;
  incomplete-and-disabled is the expected state, not a fault, and it would otherwise log once per draft
  per listing call.

## 6. Reads and diagnostics

- [x] 6.1 Omit `group_by` and `measures` from the authored projection when empty; leave
  `output_bindings` and the compiled projection untouched.
- [x] 6.2 Add a `Clampability` value for "the writer has no declaration yet", so a draft targeting an
  enrichment stops making every reader of it refuse with the false reason "its evaluator is not 'sql'".
- [x] 6.3 Surface the new clampability reason through `PipelineFreshnessDiagnostics`.

## 7. Tests

- [x] 7.1 `@WebMvcTest` coverage for the minimal registration, the optional members, and the
  arm-scoped `requireComplete`.
- [x] 7.2 Integration test for the whole authoring loop: register minimally, patch in stages (omitting
  `target` on each, per 2.1a), preview to get the next problem, arm, and assert each refusal names the
  member it is about.
- [x] 7.3 Integration test for the non-destructive derivation — patch a disabled enrich pipeline so its
  outputs cannot be re-derived, then assert the stored `output_bindings` survive and the catalog still
  refuses to drop the columns they name.
- [x] 7.4 Migration test asserting each re-scoped CHECK is FALSE, not NULL, on an armed row with a null
  `trigger_kind`.
- [x] 7.5 Data-plane tests for all four endpoints refusing an incomplete declaration, including the
  `GROUP`-with-no-group-key case that NPEs today.
- [x] 7.5a Data-plane test for D13: store a **complete but invalid** enrich declaration while disabled
  — a Class-1 target keyed more coarsely than its read source — and assert `/materialize` refuses it
  rather than collapsing rows to an arbitrary survivor.
- [x] 7.5b Test that aggregate preview of an **enabled** pipeline still applies sensitivity propagation
  and the inactive-input refusal, so 5.1 cannot silently introduce a lighter path.
- [x] 7.6 Round-trip test: GET the authored projection of a partially declared pipeline, PATCH it back
  unchanged, and assert nothing became an empty declaration.
- [x] 7.6a Test that the `Adas-Contract-Version` 409 and the `unknown_pipeline` 404 still precede the
  completeness refusal on the four runner endpoints.
- [x] 7.8 `GET` a draft of each kind, single and listing, both projections — assert 200. A throw
  inside `PipelineFreshnessDiagnostics.of`'s participating read-only transaction marks it
  rollback-only, so a draft whose `referencedEnrichments` throws would make the read answer 500.
- [x] 7.9 Enable-only patch of a draft: missing trigger kind, missing cron, and the aggregate cron 500.
- [x] 7.10 A draft `PATCH` omitting `target` keeps the stored target rather than being refused.
- [x] 7.11 `DELETE /v1/tables/{name}` cascading a draft away, and `DELETE /v1/pipelines/{name}` of one.
- [x] 7.12 Migration test that every **existing** row (enabled and complete) still satisfies
  `pipeline_armed_is_complete` — 7.4 only covers the new null case.
- [x] 7.13 Pin the new `Clampability` value's `refused()`/`clamps()` semantics.
- [x] 7.7 Confirm the existing suites that register complete declarations still pass unchanged — the
  change is additive for them.

## 8. Specs and docs

- [x] 8.1 Re-run `openspec validate defer-pipeline-validation-to-arm --strict` and confirm each MODIFIED
  requirement carries every base scenario that should survive; base scenarios whose behaviour this change
  deliberately alters (registration-time refusals, the non-entitled-author sensitive scenarios, preview's
  template-only check on a disabled pipeline) are replaced by their retimed counterparts rather than kept.
- [x] 8.2 No configuration property is added or renamed, so `docs/configuration.md` gets no row; the
  `ENRICHMENT_GROUP_FETCH_MAX_ROWS` description is reworded from "422 at registration" to "422 when armed or
  previewed".
- [x] 8.3 Document for operators that `GET /v1/pipelines` now returns half-built pipelines that will
  never run, and that there is no `complete` filter — the condition is computed, not stored (D1), so
  `enabled=false` is the closest proxy. This is the one genuinely new operational surface the change
  creates.
- [x] 8.4 Report the pre-existing divergence separately: `/rows` and `/materialize` write to a disabled
  pipeline's target although the registry spec says a disabled pipeline writes by no route. Out of
  scope here; it must not be silently inherited. (Reported; brought into scope as 8.5.)
- [x] 8.5 Close it: `/rows` and `/materialize` refuse a disabled pipeline with 409 `pipeline_conflict`,
  after the contract, existence and authorization checks and before anything is read or written;
  `/scan` and `/group-rows` stay open. Specs updated in `pipeline-registry`, `enrichment-runtime`,
  `class1-enrichment` and `enrichment-registry`.

## 9. Verification

- [x] 9.1 `./gradlew clean build` passes — compile, checkstyle and tests.

## 10. Review fixes

- [x] 10.1 Preview of a disabled `enrich` pipeline runs the trigger's arm checks and the full gate before
  any transform-presence check, so it reports the same first problem an enable-only patch arming it would.
- [x] 10.2 A table schema patch no longer refuses when a DISABLED target pipeline's output mapping cannot
  be re-derived: the stored mapping is kept and nothing is bumped; the backstop refusal stays for
  ENABLED pipelines.
- [x] 10.3 Freshness classifies a disabled no-transform writer `UNDECLARED_WRITER` before `GROUP_TRIGGER`.
- [x] 10.4 A draft save carries over a stored derived member (`group_by`, `output_bindings`) only when
  target and read source are unchanged; a repoint clears it.
- [x] 10.5 The "declared read source must be restated" refusal applies to every declaration patch, armed
  or not; `inputs: []` drops the declared source.
- [x] 10.6 A patch restating only the stored `target` (optionally with `name`/`kind`) is not a declaration
  and changes nothing.
- [x] 10.7 The authored projection omits `trigger` when a draft declares no trigger member.
- [x] 10.8 A scan of a `sql`-transform pipeline reports "use /materialize" before completeness.
- [x] 10.9 Specs corrected: authoring surfaces are `FULL_ADMIN`-only (403 `forbidden`), so the in-gate
  `sensitive_column_not_entitled` is described as defence in depth; completeness and compiled reads name
  every absent member; compiled reads do not statically validate expressions; bare-draft first refusals
  (`trigger.kind`, `trigger.cron`) stated; D13 rationale updated for the 409 on disabled materialize.
- [x] 10.10 Preview of a disabled `enrich` pipeline samples through the declaration it just gated, with
  the output mapping derived in memory, not the stored mapping — so a draft with a stale stored mapping
  previews whenever arming would succeed.
- [x] 10.11 Freshness classifies a DISABLED `enrich` writer whose declaration is incomplete (any missing
  member) `UNDECLARED_WRITER`; a disabled complete writer keeps its existing classification.
- [x] 10.12 Log an `llm` draft's composed-schema mismatch at debug rather than warn.
- [x] 10.13 A blank or whitespace `target` on a PATCH is treated as omitted (sticky, not a declaration);
  a padded one is trimmed.
- [x] 10.14 Specs corrected after a second review: the 403 precedes the declaration gate (not
  decoding — a malformed body is still a 400); the data-plane completeness members are listed exactly,
  with an unresolvable read source left to its 400 `bad_request`; the no-transform refusal precedes the
  member sweep; a draft schema patch that re-derives is rewritten and bumped as for an enabled pipeline;
  duplicated rationale consolidated into one owning capability each.
- [x] 10.15 A patch leaving the pipeline disabled is refused when it would turn a complete declaration
  (authored members only) into an incomplete one; unit tests in `PipelineServiceTest` and the paused-
  pipeline case in `DraftPipelineIntegrationTest`.
