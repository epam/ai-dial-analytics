## Why

The Enrichment Runner needs a **control-plane** in ADAS where evaluators (LLM
scoring/labelling definitions) and rules (bindings of an evaluator to a target enrichment) are
registered, statically governed, and synced. ADAS is the governed gate: filters, entitlements,
type-compatibility, and write targets must be validated **once at registration** and enforced at
runtime, so the runner is never trusted to self-govern. The catalog today models only
source/enrichment/aggregation tables — there is no evaluator or rule registry. This change adds
it, and is what makes the `enrichment-runtime` rule-scoped scan/write endpoints resolvable
(they read the `rule`/`enrichment_grain` records defined here).

## What Changes

- **Evaluator registry with immutable versioning (two tables).** `evaluator(name PK,
  latest_version, …)` + `evaluator_version(name, version, type=llm, preset, model, params,
  request_template, input_vars, response_schema, output_vars, …, PK(name,version))`. Editing an
  evaluator creates a **new version row**; existing versions never mutate; `latest` resolves to
  `max(version)`. The initial preset is `chat_completion` only.
  - Endpoints: `POST /v1/evaluators` (new name/version), `GET /v1/evaluators`,
    `GET /v1/evaluators/{name}` and `GET /v1/evaluators/{name}/versions/{v}`. No update/delete of
    a version (409 `evaluator_immutable`); an unknown name → 404 `unknown_evaluator`.
- **Rule registry binding an evaluator to a target enrichment.** `rule(id PK, name UNIQUE,
  evaluator_name, evaluator_version NULL=latest, target_enrichment UNIQUE, trigger_kind
  [on_ingest|schedule], trigger_cron NULL, filter_sql, input_bindings, output_bindings, sampling,
  cadence, batch_scan_limit, batch_chunk, rate_rpm, priority [live|backfill], enabled, generation
  BIGINT, created_at, updated_at)`. **Source + grain are derived** from the target enrichment's
  `parent`/`grain` — the rule never re-declares them.
  - Endpoints: `POST/PUT/DELETE /v1/rules`, `GET /v1/rules/{id}`, and the **runner sync**
    `GET /v1/rules[?updated_since=…]` returning each enabled rule with its **resolved pinned
    evaluator inlined**, the derived source/grain, and a `generation` for change detection (ETag).
- **Static governance gate** (on register + enable), each failure → 422 `rule_validation_failed`
  with detail: (1) target enrichment exists / `ACTIVE` / kind=enrichment and output-binding
  columns exist with matching types (shared JSON→CH type-mapping util); (2) `filter_sql` parses
  under the **existing Calcite SQL front-end**, boolean over declared source columns, bounded
  grammar (no joins/subqueries/CTEs); (3) entitlement — filter/bound bindings reference only
  permitted columns; any `sensitive` column needs an explicit grant, else 403
  `sensitive_column_not_entitled`; (4) evaluator resolves and `model` is a real DIAL deployment
  (reuse the Api-Key/DIAL introspection path); (5) `trigger_kind ∈ {on_ingest, schedule}` and
  row-grain only (reject `group`/`push`); (6) input/output JSONata expressions **parse** (static
  parse only); (7) **one rule per `target_enrichment`** (DB `UNIQUE`) — a second rule on the same
  enrichment → 409 `rule_validation_failed`.
- **New dependency:** a JSONata parser (`com.dashjoin:jsonata`), pinned to the **same
  implementation/version as the runner**, so a rule that parses at registration can't fail on a
  dialect difference at runtime.
- **B5 write grants.** The runner gets a per-target `WRITE` grant on each enrichment via the
  existing `PUT /v1/tables/{name}/access` (reusing the `rework-uac-role-based-access` `access`
  model) — no generic table-write capability. This is the write flavour of the same
  target-enrichment authorization the `enrichment-runtime` scan/write endpoints use.
- **Error contract:** new stable codes `rule_validation_failed` (422),
  `sensitive_column_not_entitled` (403), `evaluator_immutable` (409),
  `unknown_evaluator`/`unknown_rule` (404) in `DefaultExceptionHandler`.

Out of scope (locked): the scan/write data-plane endpoints and keyset engine (→
`enrichment-runtime`); any dynamic dry-run / rendered preview (deferred; any rendering lives in
the runner, which owns the JSONata + template engine); independent cadence/model per label on one
table (needs a column-coalescing engine — deferred); triggers other than `on_ingest`/`schedule`.

## Capabilities

### New Capabilities
- `enrichment-registry`: the evaluator + rule control-plane — immutable evaluator versioning,
  rule CRUD with derived source/grain, the static governance gate (parse/type/reference/
  entitlement/trigger/JSONata/one-rule-per-target), and the runner sync feed with resolved pinned
  evaluators and a `generation` for change detection.

### Modified Capabilities
<!-- None. B5 write grants reuse the existing table-access-control PUT endpoint and the in-flight
     rework-uac-role-based-access access model without a requirement change here; filter
     validation reuses the structured-query Calcite front-end read-only. -->

## Impact

- **New code:** `web/controller/enrichment/{EvaluatorController,RuleController}`;
  `web/dto/enrichment/*` (records + `@Schema`); `web/mapper/enrichment/*`;
  `service/enrichment/{EvaluatorService,RuleService,RuleGovernanceValidator,FilterSqlValidator
  (wraps Calcite),TypeMapping}`; entities `EvaluatorEntity,EvaluatorVersionEntity,RuleEntity`
  (+ Spring Data repos); new `@ExceptionHandler`s in `DefaultExceptionHandler`.
- **Migration:** Postgres `V11__enrichment_registry.sql` (evaluator, evaluator_version, rule
  tables; `UNIQUE(target_enrichment)`, `UNIQUE(rule.name)`). Confirm `V11` still free at apply
  (`rework-uac-role-based-access` is in flight and may claim it — renumber if so).
- **New dependency:** `com.dashjoin:jsonata` (version-pinned to the runner) in `build.gradle`.
- **Reuses:** the Calcite SQL front-end (filter parse/column extraction), the DIAL Api-Key
  introspection path (model existence), and the `rework-uac` `access` model (entitlement + B5
  write grants).
- **Cross-change dependency:** `enrichment-runtime`'s rule-scoped endpoints consume the `rule` /
  `enrichment_grain` records defined here; both changes build on `rework-uac-role-based-access`
  and should land after it merges.
