## 1. Fixture harness — the load-bearing test, written first

- [x] 1.1 Build **synthetic** registry fixtures modelled on real shapes — deliberately NOT copies.
  Real deployment declarations are not used as test resources: their prompts carry organisation-specific
  policy, and a checked-in copy would go stale while looking authoritative. What the fixtures reproduce
  is the *structure* the assertions depend on, verified by their own tests: the jsonb property-order
  defect (a derived field generated ahead of its inputs), enum-order drift between a schema and its
  column, a closed domain on a plain `string` column, exactly one non-identity `jsonata`, the `array`
  and `timestamp` output types, and every provenance pattern seen in practice (all five, two, or none). Structural facts and
  their provenance are recorded in `fixtures/README.md`; the measurements themselves stay
  out of the repository.
  **Consequence to carry forward:** re-validating the *real* stored declarations is now a
  verification step against a running deployment, not something the test suite can claim.
- [x] 1.2 `CompiledProjectionIntegrationTest` seeds an **old-shape** `evaluator_version` row through the
  repository (the API no longer accepts that shape, which is the point — such rows exist and are never
  rewritten) and asserts the compiled projection against it. Not golden files captured from a
  pre-change build: for a stored old-shape row "compiled == stored, verbatim" is the same claim and is
  checkable from inside this build, since the pre-change code echoed the stored spec
- [ ] 1.3 Assert **every existing declaration still passes the registration gate** by re-running the gate
  over every stored spec — the same thing `PipelineService.patch` does before arming. This is the
  assertion that catches a validator rule bricking `session-insights-live`; write it before task 6
- [x] 1.4 Assert the stored spec member names are unchanged after a full parse + re-serialize through
  `PipelineSpecCodec` (`input_bindings`, `cadence`, `batch_scan_limit`, `batch_chunk`, `rate_rpm`,
  `sampling`) — the assertion that makes decision 6 safe
- [x] 1.5 Pin spec-codec tolerance with a regression test: a stored spec carrying `priority` and
  `freshness` still parses after both stop being fields

## 2. Schema composer

- [x] 2.1 Add a schema composer under `service/enrichment/` taking (target definition, evaluator
  `outputs`) → JSON Schema: exhaustive type mapping, 422 for `map`/`json`, `timestamp` with no
  `format`, `array` with mapped `items`
- [x] 2.2 Preserve declared property order end to end — `LinkedHashMap` in, ordered JSON out — and
  make `required` every declared output with `additionalProperties: false`
- [x] 2.3 Resolve the enum domain from the column's `enum_values`, else the output's `values`; 422
  naming both when a column with `enum_values` also receives `values`
- [x] 2.4 Match an `outputs` key against the column's **exposed `name` only**; 422 naming the exposed
  spelling when a key matches only a `source_name`; 422 naming the output when it matches no column
- [x] 2.5 Unit-test the composer exhaustively, including the order assertions and every 422

## 3. Evaluator `outputs` and the single accessor

- [x] 3.1 Add `outputs` to `CreateEvaluatorRequest` as an ordered map, with a **delegating**
  string-or-object deserializer (`ctxt.readValue(p, OutputSpec.class)` for the object branch) so
  `FAIL_ON_UNKNOWN_PROPERTIES` still applies inside it
- [x] 3.2 Stop accepting `input_vars`, `response_schema` and `output_vars`; make `preset` optional
  defaulting to `chat_completion`; keep `type` `@NotNull` and add a regression test that a body with
  no `type` is still a 400 rather than defaulted to `llm`
- [x] 3.3 Validate `outputs` per type in `EvaluatorService`: `llm` string-or-object with at most one of
  `values`/`jsonata` and a 422 for an identity `jsonata`; `sql` bare non-blank expressions with no
  per-output `type`
- [x] 3.4 Store `outputs` as an **ordered array** of `{name, prose, values?, jsonata?, sql?}` in the new
  column, never as a JSON object
- [x] 3.5 Add `EvaluatorVersionEntity.effectiveOutputVars()` resolving `outputs ?? output_vars`, and
  route **all five** current `getOutputVars()` call sites through it: `MaterializeService:305`,
  `EnrichSpecValidator:468` (`validateSqlExpressions`), `:785` (`validateOutputBindings`), `:921`
  (`parseJsonata`), and — the data-integrity one — `:722` (`outputExpressionTokens`, feeding
  `declaredFieldsOf` → `PipelineGenerationService.pipelinesReadingEnrichmentColumn`)
- [x] 3.6 Resolve `response_schema` as stored-else-composed at the one `??`, and synthesize
  `output_vars` as `{name, sql}` with **no `type`** for a new-shape `sql` evaluator
- [x] 3.7 Add `V39__evaluator_outputs.sql`: `ALTER TABLE evaluator_version ADD COLUMN outputs JSONB`
- [x] 3.8 Test: `getOutputVars()` has no remaining direct callers (grep-level), plus the behavioural
  case — a new-shape `sql` evaluator reading `usage_client_identity.client_type` must make
  `pipelinesReadingEnrichmentColumn("usage_client_identity", "client_type")` name its pipeline
- [x] 3.9 Test: an old-shape version reads back verbatim through
  `GET /v1/evaluators/{name}/versions/{v}`, and a new-shape one reads back as `outputs` in order

## 4. Derived output bindings (option B — derive at write, keep storing)

- [x] 4.1 Derive the output mapping from `outputs` × target columns in `EnrichSpecValidator` at
  register and patch, normalizing each `column` to the target's `source_name`, and **persist it** in
  the spec so `EnrichWriteValidator`, `MaterializeService.analyzeExpressions` and
  `EnrichSpecValidator:463` need no change
- [x] 4.2 Stop accepting `output_bindings` on the wire (400 naming the field)
- [x] 4.3 Reword `EnrichSpecValidator:463` to "the evaluator must declare at least one output that
  resolves to a column on the target"
- [x] 4.4 Collapse the two-hop type check to one — the expression's inferred type against the target
  column's — for a new-shape `sql` evaluator, keeping the stored-declaration path (including the
  `array<string>` token refusal) for an old-shape one
- [x] 4.5 Guard the serve path: an `llm` pipeline whose derived mapping is empty is omitted from the
  feed and logged, never served
- [x] 4.6 Done in a **stronger form than specified**, and the change is worth recording. Instead of
  hand-written cases over existing output vars, the collapse is proved by *property*: an exhaustive check
  of transitivity over all 12³ type triples in `EnrichmentTypeMappingTest`, which settles by enumeration
  what the plan argued by hand — everything the two-hop check accepted, the single check still accepts,
  so no stored declaration can begin to fail. Plus `theCollapseLoosensRatherThanTightens` (a triple the
  two-hop check refused and the single one accepts), the one-way-into-enum and array-on-shape-alone
  properties, and `everyInferredTypePairingSurvivesRegistrationAndMaterialize` — one Class-1 pipeline
  carrying all six distinct pairings through **both** the registration gate and the materialize call,
  which is where the comparison actually runs per window. Existing output vars sample whichever pairings a
  registry holds today; the properties cover the ones it might hold tomorrow
- [x] 4.7 Test: a row write for `session-insights-live` naming all 15 of its output columns is
  accepted, and its compiled projection carries **15** bindings asserted as a count

## 5. Pipeline request shrink

- [x] 5.1 Add `vars` and a nested `advanced` block (`scan_every`, `rows_per_scan`, `rows_per_call`,
  `rate_rpm`, `sample_fraction`) to the request records in `web/dto/pipeline/PipelineRequests.java`
  **only** — the `EnrichSpec` `@JsonProperty` spellings do not move
- [x] 5.2 Remove `priority` from `EnrichSpec` and `PipelinePriority` from the declaration surface;
  drop the flat `sampling`/`cadence`/`batch_scan_limit`/`batch_chunk` and `input_bindings` from the
  request records
- [x] 5.3 Map the new wire names onto the stored members in `PipelineMapper`, with `advanced`'s four
  rules: it counts for `hasDeclaration()`; it is **not** part of `requireComplete`; `ENRICH_ONLY`
  carries the nested names and the cross-kind 422 names `advanced.<member>`; the old flat spellings are
  unknown properties on either kind
- [x] 5.4 Enforce `sample_fraction` in `(0, 1]`: 422 above `1`, and 422 at `0` naming `enabled: false`
- [x] 5.5 Teach `PatchPipelineRequest.hasDeclaration()` about `vars` and `advanced`, and add `inputs`
  to the enrich arm of `requireComplete` so a declaration patch cannot silently demote a declared read
  source
- [x] 5.6 Test each removed spelling is a 400 naming the field, on both kinds; a patch carrying only
  `advanced` is a declaration patch; a declaration patch of `turn-feedback-live` omitting `inputs` is a
  422

## 6. Trigger-aware template validation — land alone, after 1–5

- [x] 6.1 Row grain (`on_ingest`, `schedule`): every `{{placeholder}}` is a `vars` key, every `vars`
  key appears as a placeholder, no built-ins
- [x] 6.2 Group grain: every `{{placeholder}}` is one of the five built-ins, `{{members}}` mandatory,
  `vars` **not** checked against the template in either direction
- [x] 6.3 Assert the five built-in names (`members`, `members_truncated`, `members_included`,
  `members_omitted`, `members_notice`) as literals, with a comment naming the runner's
  `GroupInputAssembler` constants it mirrors
- [x] 6.4 Test against a **`session-insights-live`-shaped declaration** (several `vars`, none in
  the template) is accepted and re-arms, and every negative case from the spec is refused

## 7. Aggregate shrink

- [x] 7.1 Derive `group_by` from the target's `ordering_key` when every key column is an input column
  of the same name, expanding it into the stored spec at registration; 422 asking for the explicit form
  otherwise (the `sessions_rollup` shape)
- [x] 7.2 Stop accepting and stop echoing `freshness`; keep `AggregateSpec.freshness`'s field
  initialiser and `validateFreshness` as the hand-edited-row guard; make `PipelineMapper.toSpec` stop
  touching `freshness` at all rather than passing a null through the setter
- [x] 7.3 Make measure `column` default fn-aware — `count` stays `count(*)`, every other function
  defaults to `name` — and echo the resolved `column` expanded
- [x] 7.4 Stop echoing `distinct: false` (`Boolean` with `null ≡ false` inbound, `false ⇒ null`
  outbound, reconciled with `@JsonSetter(nulls = Nulls.AS_EMPTY)` and MapStruct's `toMeasureDtos`)
- [x] 7.5 Make `enabled: true` a 422 naming `/preview` on **registration only**, replacing the silent
  coercion at `PipelineService:107`; leave the patch path arming as before
- [x] 7.6 Stop `requireComplete` demanding `groupBy` on an aggregate declaration patch
- [x] 7.7 Test: absent and explicit-null stored `freshness` both validate; a `count` with no column
  compiles to `count(*)`; `{"name": "total_price", "fn": "sum"}` resolves to `total_price` and echoes
  it; a patch omitting `group_by` is accepted

## 8. Catalog → registry notification

- [x] 8.1 Add `onTargetColumnsChanged(target)` to `service/table/PipelineGenerationBump.java` alongside
  `onVersionColumnRepointed`, bumping `generation` **and** `updated_at`, `MANDATORY` propagation
- [x] 8.2 Fire it from `CatalogSchemaWriter.applyPatch` for a column add, drop or rename on an
  enrichment that is a pipeline's target; a `MetadataWrite` fires nothing
- [x] 8.3 Scope it to `llm` pipelines, and re-derive and rewrite the stored bindings in the same
  transaction
- [x] 8.4 Test: a column added to `session_insights` moves `generation` **and** `updated_at`; a column
  added to `usage_client_identity` moves neither and preserves `materialized_through_version`, and the
  next materialize writes the new column anyway; a metadata-only patch moves nothing

## 9. Evaluator registration re-validates its dependents

- [x] 9.1 On registering a version, re-run composition against the target of every pipeline naming
  this evaluator with `evaluator_version IS NULL`; 422 naming the pipeline on failure
- [x] 9.2 Bump `generation` and `updated_at` and rewrite the stored bindings for each such pipeline,
  **`llm` only**, in the registration's transaction
- [x] 9.3 Test: a version staged against a fully-pinned evaluator is accepted unvalidated and bumps
  nothing; an unpinned `llm` dependent is bumped; an unpinned `sql` dependent is not

## 10. Provenance injection

- [x] 10.1 Add an **ordered** canonical provenance list to
  `service/enrichment/support/ProvenanceColumns.java` (keeping `NAMES` as the set for the allow-list),
  with canonical types, descriptions, `display_name` and `tag: system`, `evaluator_version` typed
  `integer`
- [x] 10.2 Inject the missing provenance columns in the enrichment arm of the schema-define path,
  appending only what the caller did not declare and leaving a declared one exactly as sent
- [x] 10.3 Write the canonical descriptions, including that `model`/`group_version`/`truncated` are
  never populated on a Class-1 target and that `truncated` is **not** a quality signal
- [x] 10.4 Fix the two operation descriptions in `web/controller/table/TableController.java`, which
  name three of the five columns and type `enriched_at` two different ways
- [x] 10.5 Test all three provenance shapes: all five declared, two declared (`turn_feedback`), none declared
  (`usage_client_identity`); a `source` table receives none; `PATCH` injects nothing

## 11. `view` projections

- [x] 11.1 Add `view` (`compiled` default, `source`) to `GET /v1/pipelines` and
  `GET /v1/pipelines/{name}`, bound strictly so an unknown value is a 400 naming the accepted values
- [x] 11.2 Append the new `PipelineDto` components at the **end** (never interleaved), populating the
  old-shape components for `compiled` and the new ones for `source`, each left null rather than empty
- [x] 11.3 Synthesize `output_bindings` (with `source_name` columns) and `output_vars` on the compiled
  projection; echo the **stored** `inputs` — absent when not declared — on the authored one
- [x] 11.4 Split or adjust the leaf request/response records where a response must omit a default
- [x] 11.5 Assert `view=compiled` is **byte-identical** to the golden files from task 1.2 for every fixture
  pipeline, with the enumerated `output_vars[].type` delta asserted as *not occurring*, and
  per-pipeline non-emptiness of the binding list asserted explicitly

## 12. Enrich preview

- [x] 12.1 Extend `POST /v1/pipelines/{name}/preview` to the `enrich` kind in
  `web/controller/pipeline/PipelineDataController.java`, returning the composed (or stored) schema, the
  resolved output mapping, the expanded `vars` and sampled input values — writing nothing, calling no
  model, rendering no prompt. The controller dispatches on the **stored kind** and enumerates every
  member of `PipelineKind` rather than defaulting, so a fourth kind cannot be added without deciding
  what its dry run is; the `external` arm is the "no dry run for this kind" 422 the contract promises.
  The schema is composed through a new **throwing** entry point (`EnrichResolver.composeSchema`) rather
  than the resolver's swallowing one — a target that no longer fits the evaluator's outputs is the most
  valuable thing this endpoint can report, and the composer's refusal names the output, the column and
  the spelling it expected.
- [x] 12.2 Build the **Class-1 arm first** (the typical `sql` pipeline is row-grain `on_ingest`): the
  resolved `column ← expression` mapping, each expression's translated SQL and inferred type, and
  sampled values. Implemented as `MaterializeService.preview`, **beside the write it describes**, so it
  shares `analyzeExpressions` and `windowPredicates` with it as code — that sharing *is* the claim a
  preview makes, and a second implementation of either would be free to drift while still looking
  right. Three differences from a materialize, each deliberate and documented at the method: no window
  is probed and no position recorded; the freshness clamp is **not** applied (nothing is stored, so the
  permanently-wrong-value failure the clamp exists for is unreachable, while clamping would empty the
  sample for an author whose upstream is merely behind); and the sample is not seeked past the
  materialized position, for the same reason the aggregate sample is unwindowed.
- [x] 12.3 Ride the bounded `scanService` / `groupFetchService` reads with an explicit row cap and
  version window, **not** the aggregate preview path (`PreviewBudget` does not bound rows read, and the
  heavy body columns are refused unbounded).
  **How this reads for the Class-1 arm, stated rather than glossed:** `ScanService` refuses a `sql`
  pipeline outright and projects raw columns, not derived values, so it cannot serve that arm. Its
  governed read is the materialize path, and the preview rides *that* one — the same filter, safety lag
  and keyset window, capped at `EnrichPreviewService.SAMPLE_ROWS`. The requirement's intent (bounded
  rows read through the path the pipeline itself uses, rather than an unbudgeted aggregate) is met on
  both arms; the named component differs on one because that arm has a different governed read.
  The row cap is a **constant, not a configured knob** — an `llm` pipeline's bindings routinely name
  the heavy body columns, so raising it is the one way to turn a dry run back into the unbounded read
  this arm exists to avoid. That also keeps task 13.1's "no config property is added" claim true.
- [x] 12.4 Add the optional request body: a `group_by` value for a group pipeline (reported back in the
  response) and a row `cursor` for the row-grain case; a group preview with no key reports that rather
  than sampling an arbitrary group. Reported as a **200 carrying the pipeline's shape** rather than a
  refusal: everything except the sample is answerable without a key, and the author still needs the
  schema and the mapping. A member belonging to the other grain is **refused**, not ignored — a
  `group_key` on a row-grain pipeline, a cursor or window on a group one, and any member at all on an
  `aggregate` preview, each naming what it applies to.
- [x] 12.5 Add the enrich members to `web/dto/pipeline/PreviewDto.java` as a **sibling** of
  `{statement, sample_statement, rows}`, not a nullable reuse
- [x] 12.6 Echo the expanded `group_by` and resolved measure `column` in the aggregate preview

## 13. Documentation and clients

- [x] 13.1 Update `docs/enum-columns.md` and any pipeline/evaluator authoring doc that shows the old
  bodies; no config property is added or renamed by this change, so `docs/configuration.md` needs no
  edit — confirm that explicitly rather than assuming it
- [x] 13.2 Update the adas-mcp tool docstrings that name `input_bindings`, `output_bindings`,
  `sampling`, `cadence`, `batch_scan_limit`, `batch_chunk`, `rate_rpm` and `priority`, add the `view`
  parameter, and change the `adas_preview_pipeline` contract. `view` and the preview body are
  **parameters, not only prose**: `adas_list_pipelines` / `adas_get_pipeline` take `view` (validated
  client-side against `source`/`compiled`, since ADAS answers a *generic* 400 for an unbindable
  request parameter and names no accepted value), and `adas_preview_pipeline` takes an optional
  `body` dict forwarded verbatim — `from` is a Python keyword, so the four members travel in a dict
  rather than as named parameters and keep ADAS's own spelling. An empty dict is sent as **no body**,
  because the aggregate arm refuses any body at all. `docs`-adjacent files updated with it: the
  README tool table (preview is no longer `aggregate`-only) and the evaluator `outputs` vocabulary
- [x] 13.3 Regenerate `src/adas_mcp/swagger/adas-openapi.json` and compare it **structurally** —
  regeneration churns ~1400 lines of hash-order noise.
  Regenerated from a local ADAS, with compose bound to non-default host ports
  (55432 / 58123) because 5432 and 8123 were already taken locally. The
  non-default ports make a partially-specified environment fail
  loudly instead of reaching whatever happens to be bound. The structural diff is exactly this change's surface — 3 schemas removed
  (`FreshnessRequest`, `InputBindingDto`, `OutputBindingDto`), 10 added, 5 operations and 7 schemas
  changed — plus **three older drifts the snapshot had simply gone stale on**, all real and all
  correctly picked up: `QueryFunctionDto` gaining `element_of_argument` (array_join),
  `SavedQueryDto.source` becoming an array, and the `DefineSchemaRequest` nullable-key notes. No
  hash-order churn appeared this run (432 insertions / 229 deletions, all substantive).
  **The comparison earned its keep**: it caught three places where the *published document* misstated
  the new API, each fixed in ADAS and the snapshot regenerated afterwards — `PipelineView` had no
  `@JsonValue`, so `?view=` was documented as `COMPILED`/`SOURCE` (a spelling that works, because the
  binder compares case-insensitively, but that appears in no other document); and springdoc read the
  derived accessors `PreviewRequest.isEmpty()` and `OutputSpec.isBareString()` as bean properties,
  publishing `empty` and `bareString` as request members the strict mapper answers with a 400 naming
  them. Both are `@Schema(hidden = true)` and deliberately **not** `@JsonIgnore`: an ignored property
  is silently accepted on the way in, which is the failure strict request parsing exists to prevent
- [x] 13.4 Record that existing declarations are deliberately **not** moved to the new shape, and that
  Class-1 pipelines must not be patched or flipped during the upgrade window.
  Written to a deployment-specific runbook kept outside the repository, **not** to a `notes.md` in this
  change directory: a deploy runbook is necessarily written against a
  specific registry — it names the actual pipelines, the enrichments they clamp, and that environment's
  `DB_SCHEDULER_THREADS` and `rate_rpm` values — which is the one thing the change directory does not
  carry. The fixtures make the same split for the same reason (`src/test/resources/fixtures/README.md`
  encodes structure, and points at the gitignored measurements rather than restating them). The
  requirement here is that the fact be **recorded before the window**, not that it be checked in;
  nothing else in the change references the file

## 14. Local end-to-end verification

- [x] 14.1 Stand up the local stack and seed ClickHouse; run ADAS with `POSTGRES_HOST` / `_PORT` /
  `_DATABASE` / `POSTGRES_DATASOURCE_USERNAME` / `_PASSWORD` (**not** `POSTGRES_URL`/`_USER`/`_PASSWORD`,
  which are silently ignored and fall back to whatever database the defaults reach).
  Run on **default ports** (5432 / 8123), with 80,303 rows already seeded in `dial_usage_log`
- [x] 14.2 Author an **aggregate** through the new body — `group_by` omitted, no `freshness`, one
  `count` with no `column` and one `sum` with no `column` — then `/preview` (confirm the echoed
  expanded `group_by` and that the `count` compiled to `count(*)`), `/refresh`, and check the rows.
  `usage-e2e-rollup` → `usage_e2e_daily`. Registration echoed `group_by` derived from the target's
  ordering_key and the `sum`'s defaulted `column`; preview showed `count(*)` and
  `sum("total_tokens")`. After refresh the target matched the source **exactly** — 75,132 calls,
  564,054,292 tokens, 269 keys. Note the baseline must be `dial_usage_log FINAL`: raw it reads 80,303,
  and the 6.4% gap is the ReplacingMergeTree collapsing, not a shortfall
- [x] 14.3 Author a **Class-1** pipeline on a new-shape `sql` evaluator with no per-output `type` →
  `/preview`, `/materialize`, check the target.
  `e2e-req-shape` (3 bare-expression outputs, no per-output `type`) → `usage_e2e_shape`. Preview
  returned translated SQL and inferred types with no `response_schema` and no `vars` (a `sql`
  evaluator asks no model anything), plus 5 rows of real derived values. Materialize drained in **5
  ticks / 47,017 rows**, exactly the `llm_call` population.
  **Two traps worth recording.** An unbounded `/materialize` (empty body) re-scans from the start, so
  `has_more` never clears and it looks like a stuck pipeline — the cursor must be threaded from
  `next_cursor`, which is what the runner does. And the 30,408
  empty `req_model` values are **correct**: those bodies carry `"model": null`, where `JSONHas` is
  true but `JSONExtractString` is `''` — the split is exactly 16,514 String / 30,408 Null
- [x] 14.4 Author an **`llm`** pipeline on a newly registered evaluator using `outputs` — one entry with
  `values` on a `string` column, one with `jsonata` → `/preview` for the composed schema and sampled
  inputs (check property order matches the declaration order), then the runner's dry run against a real
  DIAL key for one row, with the runner running locally against local ADAS.
  **Done end to end, and it caught a shipping blocker — see 14.6.** Final run wrote
  `evidence="QA hist 1782901531100"`, `topic="TESTING"`, `risk="low"`, `evaluator_version=4`,
  `model=anthropic.claude-haiku-4-5-…`, `enriched_at` set, `group_version`/`truncated` null.
  `topic` is the load-bearing assertion: the model was instructed to answer **lowercase**, so an
  uppercase value can only come from the `$uppercase(topic)` transform actually running. `risk`
  landing inside the declared domain proves the `values` declaration became a real enum constraint on
  a plain `string` column via the **composed** schema. Property order matched the declaration order
  (deliberately non-alphabetical) on every read.
  **Getting there needed three wrong answers cleared first**, each of which looked like the cause:
  an invalid DIAL key (real, fixed server-side); the runner's **circuit breaker**, which pauses a
  pipeline after consecutive `dial_call` failures and is **not** cleared by disabling/re-enabling in
  ADAS — the scan ticks keep succeeding while silently skipping it, so it presents as an idle pipeline
  with no error anywhere (`POST /v1/pipelines/{name}/resume` on the runner is the reset); and the
  first evaluator authored, whose `jsonata` referenced a field the composed schema does not declare and so
  would have written a NULL label while looking like a pass.
  **Earlier state, kept for the record:** `e2e-topic` declared `evidence`, `topic`
  (`jsonata`), `risk` (`values`) in that deliberately non-alphabetical order; the composed schema came
  back in **exactly** that order with `required` = all three, `additionalProperties: false`, and
  `risk.enum` from the declared `values` on a plain `string` column. `preset` defaulted to
  `chat_completion`. The runner reached `stage: dial_call` and got **401 Unauthorized** — the known
  local-DIAL-key symptom — so the model call itself is unverified. **Retried with a
  freshly-supplied key and a second, untouched row (evaluator v2): still 401.** Isolated to the
  credential rather than the runner or this change — a direct call to `$DIAL_BASE_URL/openai/deployments`
  bypassing the runner entirely returns 401 under **both** `Api-Key` and `Authorization: Bearer`, while
  `/health` on the same host returns 200. The key is simply not accepted by that DIAL instance.
  **Also learned, and it cost two cycles:** a generation bump does **not** rewind the runner's scan
  cursor. ADAS's contract that "a declaration patch resets the pipeline's cursor" holds for the
  ADAS-driven `aggregate` kind; an `enrich` pipeline's scan position lives in the runner's
  `pipeline_cursor` row, which no bump touches. So a row that has already been scanned and failed into
  the DLQ with `requeueable: false` is **not** reachable again by re-enabling or re-declaring — the
  retry needed a different row positioned after the stored cursor. That is the right
  behaviour (a patch must not silently re-spend on history), but it makes DLQ requeue the only
  recovery path for an already-scanned row. Everything before it is verified by
  that same DLQ row: the runner read the compiled feed, scanned the row, resolved `vars`, rendered the
  template, and recorded the failing stage. Blast radius was one row (filter narrowed to a single
  `event_id`, 100-byte body), and the pipeline was disabled immediately after
- [x] 14.5 Run every negative case from the delta specs, including the `session-insights-live`
  group-template case against the real stored declaration.
  All 10 retired members refused **by name** with a 400 (`output_bindings`, `input_bindings`,
  `freshness`, `priority`, `cadence`, `batch_scan_limit`, `sampling`, `output_vars`,
  `response_schema`, `input_vars`); the evaluator grammar, cross-kind, lifecycle, composition and
  preview negatives all refused with the right status and a message naming the offending member.
  `session-insights-live` does not exist in the local registry, so the group-template case was covered
  by a synthetic equivalent (`{{prompt}}` declared by no var) rather than against the real stored
  declaration — that one case remains to be verified against a running deployment.
  **Found and fixed:** four user-facing messages still used the retired vocabulary — "bound input
  binding column", "input binding references sensitive column", "input binding for '<var>'" and
  "output binding references unknown evaluator output variable". They now say `vars` / `outputs`.
  A refusal that names a field the API no longer accepts is worse than a generic one

- [x] 14.6 **Defect found by 14.4 and fixed: the sync feed served no composed response schema.**
  `EnrichResolver.resolveAll` — the batched path behind
  `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled`, which is the only pipeline read the
  Enrichment Runner performs — built `ResolvedEnrichPipeline` through the 6-arg constructor and never
  called `composedSchemaOf`. The single-pipeline `resolve` used the 7-arg form that does. So
  `GET /v1/pipelines/{name}?view=compiled` was correct while the feed was not, which is why every
  check made against the single read looked right.
  **The failure was silent and paid-for.** A new-shape evaluator stores no `response_schema`, so an
  absent composed one reaches the runner as null, `DialRequestBuilder` skips `response_format`, the
  model answers free-form, and `ResponseValidator` rejects it into the DLQ at `validate` — after the
  call was made and charged. Every `outputs`-authored `llm` pipeline would have behaved this way once
  deployed while stored-schema pipelines kept working, so it would have read as one bad evaluator
  rather than as a broken feed.
  **Fix:** compose on the batched path too, with the target columns fetched in **one** grouped query
  (`findByTableNameInOrderByTableNameAscOrdinalAsc`) rather than per pipeline — the feed documents a
  constant number of queries, and the single-table finder in a loop would have made it one query per
  enabled pipeline.
  **Regression test:** `CompiledProjectionIntegrationTest.theSyncFeedComposesTheSchemaJustAsTheSinglePipelineReadDoes`,
  asserting the two read paths **agree**, because the defect was exactly a disagreement and either path
  alone passed while it existed. Verified to **fail without the fix**, twice — the first version passed
  for the wrong reason (an `@Autowired ObjectMapper`, which is not a bean on Jackson 3, failed context
  startup and took the whole class down), so it was rewritten on `JsonPath`.
  **The suite was fully green while this shipped**: no test compared the two read paths. That gap is
  the lesson, not the one-line omission.

- [x] 14.7 Two follow-ups the e2e run surfaced, both fixed after 14.6.
  **(a) Empty collections served where the contract says absent.** `EvaluatorMapper` sets
  `nullValueIterableMappingStrategy = RETURN_DEFAULT`, which turns a null column into `[]`, and
  `NON_NULL` does not omit an empty list — only `outputs` was guarded. So a **new-shape** evaluator read
  back with `input_vars: []` and `output_vars: []`, contradicting the DTO's own "present only on a
  version stored before `outputs`" and putting a second, retired spelling of what an evaluator produces
  back on the response the fold removed it from. Guarded through `varsOrNull`, mirroring
  `outputsOrNull`; an old-shape version stores populated lists and is unaffected, so "reads back
  verbatim" still holds. `Class1MaterializeIntegrationTest` pinned the old wire behaviour and was
  updated: the **storage** claim it cared about (those columns are NOT NULL and are stored empty rather
  than synthesised) is unchanged, and which shape a row is stays answerable more directly than before —
  `outputs` present means new, absent means old.
  Same class, second instance: `vars` is now **absent** rather than `{}` on a `sql` pipeline, which has
  no variables because it asks no model anything.
  **(b) The `view` rejection now names the accepted values**, closing the gap between the spec and the
  wire rather than weakening the spec. The three hand-written `@InitBinder`s (`view`, `kind`,
  `enabled`) already authored good messages that `DefaultExceptionHandler` swallowed, correctly: it
  never echoes a binding failure's text because Spring's and Jackson's leak type names, field paths and
  fragments of the input. The fix is not "echo the message" but a marker type —
  `InvalidRequestParameterException` — that a binder throws to mark its message as **authored here**,
  plus one carve-out that unwraps it from the `MethodArgumentTypeMismatchException` Spring wraps it in.
  Every other binding failure still gets the generic sentence, pinned by
  `anUnauthoredBindingFailureStillGetsTheGenericMessage`: a non-numeric version segment must keep
  reporting nothing, or the carve-out has widened from "messages we wrote" to "binding messages".
  The assertions also check the message does **not** contain the rejected value — naming the accepted
  set is the point; repeating the input is the half the policy forbids.
  All three binders were converted together, so `kind` and `enabled` do not stay inconsistent with
  `view`. The `pipeline-registry` delta spec's withdrawal of the naming claim (made earlier when the
  code did not do this) is therefore **reverted** — see 16.11.

## 15. Verification

- [x] 15.1 `./gradlew clean build` passes (Docker is
  required for the integration tests; a ~30-minute "slow" build is a class-init deadlock, not memory
  pressure).
  Re-run green after §16 and after the task-14 error-message retermings: 3m 34s, and
  again after the 14.6 feed fix and its regression test: 3m 45s; and again after the 14.7 cosmetic and
  view-rejection fixes: 3m 43s — the state this change was archived in

## 16. Fold: one vocabulary on the wire (supersedes the byte-identity half of §11)

Added after the deploy plan changed to a coordinated window with the Enrichment Runner scaled to
zero. The runner-compatibility constraint that shaped §11 no longer applies, so the compiled
projection stops carrying a second spelling of anything. See design decision 9.

- [x] 16.1 Serve the derived mapping as **`outputs`** (`{name, column, jsonata?}`), folding what were
  `output_bindings` and the evaluator's `output_vars` into one list. `name` (exposed) and `column`
  (physical `source_name`) stay separate members — `Class1MaterializeIntegrationTest` covers a target
  where they differ
- [x] 16.2 Drop `input_bindings`, `output_bindings`, `sampling`, `cadence`, `batch_scan_limit` and
  `batch_chunk` from `PipelineDto`; serve `vars` and `advanced` on **both** projections. Delete
  `InputBindingDto` and `OutputBindingDto` (no other callers)
- [x] 16.3 Omit `input_vars` and `output_vars` from the **inlined** evaluator; leave
  `GET /v1/evaluators/{name}/versions/{v}` answering with the stored row exactly as written
- [x] 16.4 Flip the default `view` to `source`, and **skip resolution** for it — in the listing and in
  `withFreshness`, so no derived member can reach an authored response and `?kind=enrich` costs no
  lookups. A write answers `source`
- [x] 16.5 Rename the four `EnrichSpec` knobs to their authoring names with `@JsonAlias` on the former
  spellings; **no data migration**. Assert both directions in `StoredSpecShapeTest`
- [x] 16.6 Remove `priority` for real: the `EnrichSpec` field, `EnrichDeclaration.priority()` and the
  `PipelinePriority` enum, all of which had zero callers
- [x] 16.7 Replace the byte-identity assertions with the stronger pair: every removed spelling
  asserted **absent by name**, and the projections asserted to differ by *exactly* the derived
  members, as a set difference over the responses' own property names
- [x] 16.8 Update `pipeline-registry` and `enrichment-registry` delta specs; withdraw the
  byte-identity requirement explicitly rather than deleting it silently
- [x] 16.9 `./gradlew clean build` green (2638 tests)
- [x] 16.11 Re-assert the `view` naming requirement in `pipeline-registry`, in the delta **and** in the
  merged main spec. It had been withdrawn earlier in this session on the correct observation that the
  code answered a generic 400 — the wrong half to change, as it turned out: 14.7(b) made the code name
  the accepted values, so the withdrawal is reverted rather than left as a spec weakened to match a
  shortcoming. The restored requirement is stronger than the original: it also states that the message
  must **not** repeat the rejected value, and that a message reaches a caller only when the parameter's
  own binder authored it — which is the invariant that keeps the carve-out from widening. Delta and main
  re-verified in sync (0 mismatches across all 6 capabilities)

- [ ] 16.10 Runner side, in the same release: read the folded feed, and land the
  `unsupportedReason` empty-`outputs` guard **first**, on its own. Tracked in the runner repository.
  **Reported done, but re-open the verification half.** The runner's own code is correct and was
  confirmed against a running deployment: it requests `view=compiled` (`AdasRegistryClient`), reads `response_schema` off the
  inlined evaluator, and sends `response_format: {type: json_schema, strict: true}`
  (`DialRequestBuilder`). What was never verified is that the feed *it* reads carries what it needs —
  task 14.6 was exactly that hole, and it was on the ADAS side. Before calling this closed, confirm
  the runner is exercised against `GET /v1/pipelines?...&view=compiled`, not against the
  single-pipeline read
