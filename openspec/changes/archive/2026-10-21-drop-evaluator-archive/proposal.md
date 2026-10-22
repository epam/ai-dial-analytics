## Why

The evaluator fold moved every enrichment definition onto its pipeline as `spec.transform`, but it left behind
code and schema whose only remaining job is lookup: a read-only `/v1/evaluators` archive over the
`evaluator` / `evaluator_version` tables, the unmapped `pipeline.evaluator_name` / `evaluator_version`
columns and their unwritten `pipeline_aud` mirrors, and `rule_legacy` / `rule_legacy_aud` from the earlier
rule fold. No pipeline read, write or validation path consults any of it, and the condition the fold set for
dropping the pipeline columns — no deployed build maps them — now holds. Keeping it costs a second
vocabulary in the code (the live transform types are still named `Evaluator*`), a misleading API tag, and
spec text that has to keep explaining an object that no longer does anything.

## What Changes

- **BREAKING** Remove the evaluator archive API: `GET /v1/evaluators`, `GET /v1/evaluators/{name}`,
  `GET /v1/evaluators/{name}/versions/{version}`, and the `PUT` / `DELETE` refusals on a version. Every
  such path now answers the service's ordinary `404 not_found`. The error codes `unknown_evaluator` and
  `evaluator_immutable` are retired.
- **BREAKING (data)** A new Postgres migration drops the `evaluator` and `evaluator_version` tables,
  `pipeline.evaluator_name` (with its foreign key and `pipeline_evaluator_name_idx`),
  `pipeline.evaluator_version`, the `evaluator_name` / `evaluator_version` mirror columns of `pipeline_aud`,
  and `rule_legacy` / `rule_legacy_aud`. Pre-fold prompts, and what an already-stamped `evaluator_version`
  value referred to, stop being readable from the service; an operator who wants them dumps the two tables
  before upgrading.
- `pipeline.legacy_rule_id` is **unmapped** from `PipelineEntity` but **not dropped**: the previous release
  still maps it, and dropping it would fail every pipeline read on a not-yet-replaced pod during a rolling
  deploy. The next release drops it.
- The frozen ClickHouse `evaluator_version` provenance column on existing enrichment targets is **kept**,
  still a reserved provenance name — refused on write, never injected, not renamable. Only the spec wording
  that called it "answerable through the archive" changes.
- Rename the live transform types, behaviour-neutral: `EvaluatorType` → `TransformType`, `EvaluatorPreset`
  → `TransformPreset`, `EvaluatorOutput` → `TransformOutput`, `EvaluatorVar` → `TransformVar`,
  `EvaluatorVarDto` → `TransformVarDto`. Wire JSON is unchanged; the OpenAPI component `EvaluatorVarDto`
  is renamed `TransformVarDto` and the `Evaluators` tag disappears.
- Not changed: a pipeline request carrying `evaluator_name` / `evaluator_version` keeps being refused
  `400 bad_request` naming the field (strict request binding), and stored pipeline `spec` shapes keep their
  read shims (`output_vars`, legacy top-level `input_bindings`, the old knob spellings).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `enrichment-registry`: the "Read evaluators" requirement is removed and replaced by one stating that no
  evaluator surface remains; the archive is struck from the enum-binding, two-output-shapes and
  registry-privilege requirements.
- `pipeline-registry`: a pre-fold revision's evaluator linkage is no longer answerable from retained mirror
  columns; the compiled-projection requirement no longer carves out the archive.
- `audit-trail`: the "retained evaluator archive" exclusion and its scenario go; pre-fold linkage is
  described as surviving only in the fold migration's log.

## Impact

- **API**: `/v1/evaluators*` removed (404). OpenAPI loses the `Evaluators` tag and the archive's schemas;
  `EvaluatorVarDto` is renamed `TransformVarDto`.
- **Postgres**: one new migration (V48, if `development` is still at V47). No ClickHouse migration.
- **Code**: about fifteen archive classes deleted (controller, service, two repositories, three entities,
  mapper, three DTOs, two exceptions, two exception handlers); `PipelineEntity.legacyRuleId` removed; five
  live types renamed across roughly sixty files; stale Javadoc reworded.
- **Tests**: the archive test, service test and V41 replay test deleted; audit, constraint, compiled-projection
  and strict-binding tests adjusted; `fixtures/evaluator-versions/` renamed.
- **Docs**: `README.md`, `docs/catalog-promotion.md`, `docs/release-0.4-upgrade.md` (migration row, the
  optional pre-upgrade dump, the breaking-change row, the OpenAPI rename); `openspec/config.yaml` context
  (bare-array endpoints, typed-path-variable example, audit note).
- **Other repositories**: `adas-mcp`'s evaluator read tools (`adas_list_evaluators`, `adas_get_evaluator`,
  `adas_get_evaluator_version`) start answering 404 and are removed there in a matching change. The
  Enrichment Runner calls none of these endpoints; no runner contract change.
