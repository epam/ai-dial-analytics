## Context

The evaluator fold (archived as `2026-09-18-fold-evaluator-into-pipeline`, Postgres V41) made the pipeline's
`spec.transform` the only authored home of an enrichment definition. It deliberately left four things
behind and named the conditions for removing them:

- the `evaluator` / `evaluator_version` tables, served read-only by `EvaluatorController` under
  `/v1/evaluators` — kept so a pre-fold prompt and an already-stamped `evaluator_version` stayed readable;
- `pipeline.evaluator_name` (FK to `evaluator`, index `pipeline_evaluator_name_idx`) and
  `pipeline.evaluator_version` — kept because the pre-fold build's declaration projection named them
  during a rolling deploy, to be dropped "once no deployed build maps them";
- the `pipeline_aud.evaluator_name` / `evaluator_version` mirror columns, added by V36 and never written
  since the fold, because `PipelineEntity` no longer maps the base columns;
- `rule_legacy` / `rule_legacy_aud` and `pipeline.legacy_rule_id` from the rule fold (V36), kept as the
  cut-over's id mapping. `rule_legacy.evaluator_name` still carries V11's foreign key to `evaluator`.

The current build reads none of it from any pipeline path. The previous release (0.3.0) maps neither
evaluator column on `pipeline`, but its `PipelineEntity` **does** still map `legacy_rule_id`.
`spring.jpa.hibernate.ddl-auto` is `none`, so a mapped column that no longer exists is not caught at
startup; it fails the first query that selects it.

## Goals / Non-Goals

**Goals:**
- Remove the `/v1/evaluators` surface and every class that exists only to serve it.
- Drop the evaluator tables, the pointer columns and their audit mirrors, and the rule-fold leftovers, in one
  migration that is safe to run while the previous build is still serving.
- Rename the live transform types still named `Evaluator*`, so the code has one vocabulary.
- Keep every spec statement about the archive in step with the code.

**Non-Goals:**
- Retiring the ClickHouse `evaluator_version` provenance column on existing enrichment targets, or freeing
  its name. It stays reserved (see D5).
- Dropping `pipeline.legacy_rule_id` in this release (see D3).
- Removing the read shims for stored pipeline `spec` shapes: `transform.output_vars` /
  `response_schema`, the legacy top-level `input_bindings`, and the old knob spellings on `EnrichSpec`. They
  read stored declarations, not the archive, and removing them would need a data migration that rewrites
  `pipeline.spec`.
- Changing the refusal of `evaluator_name` / `evaluator_version` on a pipeline request (strict request
  binding answers 400 naming the field, and keeps doing so).
- The matching `adas-mcp` change that removes its three evaluator read tools. It ships separately.

## Decisions

### D1. Drop the archive rather than keep it read-only

Both reasons the fold gave for retaining the archive are about reading history: a stamped
`evaluator_version`, and a pre-fold revision that carries no transform. Neither has a reader in the
service. Nothing joins a stamp to the archive, and a revision read already answers without a transform.
Keeping the tables means keeping fifteen classes, two error codes, an OpenAPI tag and spec text to explain
them, all for an answer only an operator in `psql` ever needed.

The loss is real and is stated rather than hidden. After the upgrade the service cannot say what a
pre-fold prompt was, or what a stamped `evaluator_version` referred to. The upgrade guide gives the
`pg_dump -t evaluator -t evaluator_version` an operator can run first.

*Alternative rejected*: export the archive into `pipeline_aud` or a JSON column first. That rebuilds a
lookup path the project has just decided it does not need, and it would be the second in-place
rewrite of audit history.

### D2. One migration, explicit drop order, no `CASCADE`

`V48__drop_evaluator_archive.sql` (renumber if `development` has moved past V47) drops, in dependency order:

1. `pipeline.evaluator_name` and `pipeline.evaluator_version`. This takes the FK to `evaluator` and
   `pipeline_evaluator_name_idx` with it.
2. `pipeline_aud.evaluator_name` and `pipeline_aud.evaluator_version`.
3. `rule_legacy_aud` (its index goes with it; its FK points *at* `revinfo`, so nothing depends on it), then
   `rule_legacy`, which takes the last FK to `evaluator`.
4. `evaluator_version`, then `evaluator`.

No statement uses `CASCADE` or `IF EXISTS`. An object nobody listed that depends on one of these should
stop the deploy with a named error, not vanish silently. Every object is created by a tracked migration,
so its presence is deterministic. The comment header follows the public-repo rules from the start: no
environment names and no real figures, because Flyway checksums the comments.

*Alternative rejected*: split it into two migrations, the pointer columns first and the archive later. The
fold already proposed that split, and its trigger for both halves has been met. Two migrations would buy
nothing but a second release note.

### D3. `legacy_rule_id` is unmapped now and dropped next release

Dropping `pipeline.legacy_rule_id` in V48 would fail every pipeline read and write on a 0.3 pod. The first
0.4 pod runs V48 at startup while the 0.3 pods are still serving (`maxUnavailable: 0`), and 0.3's
`PipelineEntity` selects the column. That reaches UI, MCP and API calls and 0.3's `AggregateScheduler`
ticks, until the roll completes.

A runner contract-version bump does not help. The contract gates the runner's data-plane calls, and the
runner is already scaled to zero for the 0.4 window. The overlap here is between two ADAS builds sharing
one database.

So this change removes the `legacyRuleId` field and leaves the column, unmapped and unread. V48's header
says so. The next release's first migration drops it with one statement, once no deployed build maps it.
This is the same expand/contract step the fold used for the evaluator columns.

*Alternatives rejected*:
- Drop it now and tell operators to hold pipeline traffic while ADAS rolls. That turns a documentation step
  into an outage risk.
- Drop it now and deploy ADAS without overlap (scale to zero, or `Recreate`). That trades the risk for a
  guaranteed full outage, to save one column for one release.

Nothing else V48 drops is mapped by 0.3, which was checked against the `0.3.0` tag. A 0.3 pod's own
`/v1/evaluators` reads fail during the roll, which is harmless because the endpoint is going away.

### D4. `/v1/evaluators` becomes an unmapped path, not a tombstone

The routes are removed outright, so a request is answered the way any unmapped path is: `GET` gets
`404 not_found`. No `410 Gone` controller is kept. A tombstone is code with no exit condition, and the
only known caller (`adas-mcp`) is removed in step. `DefaultExceptionHandler` loses its `unknown_evaluator`
and `evaluator_immutable` handlers along with the two exceptions.

### D5. The ClickHouse `evaluator_version` column stays reserved

The column physically exists, with values, on enrichment targets created before the fold. Retiring it
means DDL on user tables and destroys provenance data in place. Freeing the name would let it be renamed or
reused with a different meaning in the same column. So `ProvenanceColumns.EVALUATOR_VERSION` stays in
`NAMES`, `EnrichWriteValidator` keeps refusing a caller-supplied value with `422 validation_error`, and
`TableSchemaService` keeps refusing renames onto or off it. Only the comments that call it "answerable
through the archive" change. The enrichment-runtime, class1-enrichment and table-management specs need no
delta, because none of them cites the archive.

### D6. Rename the live types to `Transform*`

`EvaluatorType`, `EvaluatorPreset`, `EvaluatorOutput` and `EvaluatorVar` (`service/enrichment/model/`)
and `EvaluatorVarDto` (`web/dto/enrichment/`) are the live transform model. They are renamed to
`TransformType`, `TransformPreset`, `TransformOutput`, `TransformVar` and `TransformVarDto`. No name
collides with existing types: `sync/mapping/Transform`, `TransformRegistry`, `EnrichSpec.TransformSpec`
and the private `PipelineMapper.TransformFields` all differ.

Enum constants, `@JsonProperty` names and stored JSON are untouched, so nothing on the wire or in
`pipeline.spec` changes. The one visible effect is the springdoc component name `EvaluatorVarDto` →
`TransformVarDto`. It is noted in the upgrade guide, and `adas-mcp`'s OpenAPI snapshot will show it. Local
variables and comments that say "evaluator" for "transform" are reworded in the same pass.
`TableAccessEvaluator` and Calcite's "evaluator" are a different concept and keep their names.

*Alternative rejected*: keep the names and delete only the archive. The archive is the last thing that gave
"evaluator" a referent. Without it the name points at nothing, which is the second-vocabulary cost this
change exists to remove.

### D7. The typed-path-variable regression test moves to a unit test

`CompiledProjectionIntegrationTest.anUnauthoredBindingFailureStillGetsTheGenericMessage` uses
`/v1/evaluators/{name}/versions/not-a-number`, which is the only typed (non-`String`) path variable in the
service. No remaining route has one: revision ids are bound as `String` and parsed by `AuditSnapshotGuards`.
The guard it protects is real: a `MethodArgumentTypeMismatchException` must answer the generic 400 sentence,
never Spring's message. So the test becomes a direct `DefaultExceptionHandler.handleBadRequest` test with a
constructed exception, following `DefaultExceptionHandlerLoggingTest`. The `openspec/config.yaml`
convention keeps its rule and drops the evaluator example.

## Risks / Trade-offs

- **An operator who needed a pre-fold prompt finds it gone.** → The 0.4 guide's "Before you deploy" section
  carries the dump command; the loss is stated in the enrichment-registry spec.
- **An unlisted dependent object stops V48.** → No `CASCADE`, so the failure names the object. Before the
  migration is written it is checked against a local database at V47 with `pg_depend`, and the migration
  test runs it against a database built from the real migration chain.
- **A 0.3 pod serves `/v1/evaluators` with a 500 during the roll.** → Harmless: the endpoint disappears with
  the roll, and `adas-mcp` drops its tools in step.
- **The rename touches many files and reviews poorly.** → It is mechanical and behaviour-neutral, done as its
  own task after the deletion, so the deletion diff stays readable. A passing `clean build` and an unchanged
  `view=compiled` JSON before and after are the check.
- **`legacy_rule_id` lingers one more release.** → V48's header and the 0.4 guide name the follow-up, so it is
  a scheduled contract step rather than a forgotten column.

## Migration Plan

1. Deploy in the usual 0.4 pair order (runner scaled to zero, ADAS, runner). This change adds no ordering
   constraint of its own.
2. V48 runs on the first 0.4 pod. Old pods keep serving pipelines throughout, because nothing they map is
   dropped.
3. Optional, before deploying: `pg_dump -t evaluator -t evaluator_version` if the pre-fold definitions are
   wanted.
4. Next release: `ALTER TABLE pipeline DROP COLUMN legacy_rule_id`.

Rollback is not a design constraint for this project. Deploy skew is, and D3 is what bounds it.

## Open Questions

None blocking. The next release's `legacy_rule_id` drop is recorded here and in V48's header rather than as a
separate change.
