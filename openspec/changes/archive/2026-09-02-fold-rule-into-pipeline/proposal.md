> **Shipped-as note.** This change was authored against `V33` as
> `V34__fold_rule_into_pipeline.sql`. The audit trail for `rule`, `pipeline` and
> `saved_query` landed first (`V34__audit_rule.sql`, `V35__audit_pipeline_and_saved_query.sql`), and an applied
> migration cannot be renumbered, so the fold **shipped as `V36__fold_rule_into_pipeline.sql`** with an
> added **Part D** reconciling the two: `pipeline_aud` widened with the six control-plane columns Part A
> adds (without it Envers names a missing column and every pipeline write fails at commit),
> `audit_activity.resource_type` remapped to `Pipeline` via `rule_legacy`, and `rule_aud` renamed
> `rule_legacy_aud`. Every `V34` below means the fold migration and should be read as `V36`.
> The sibling runner change also landed under the name **`fold-rule-into-pipeline`**, not
> `adopt-pipeline-registry-contract` as the Impact section calls it.

## Why

ADAS carries two registries for one concept. `pipeline` is "a declared process that produces rows
for exactly one target table"; `rule` is the same thing for enrichment. Two DTO families, two
validators, two sets of `ErrorView` codes, two addressing schemes (`name` vs a UUID `id`) and a
near-duplicate freshness projection, for one idea.

This is not new design. The accepted tables-and-data-movement design decision
states that `pipeline.kind ∈ {external, aggregate, enrich}` and that
"the existing class-2 enrichment `rule` (evaluator + trigger) **is** the `enrich` kind — generalised,
not duplicated by a parallel subsystem". `V20__aggregate_pipeline.sql` persisted the discriminator
in anticipation: "only AGGREGATE is implemented here, and the discriminator is persisted so the
`external` and `enrich` kinds can converge onto this object later without a second re-modelling."
`PipelineKind.ENRICH` already exists as a declared-but-refused member. This change performs that
convergence.

Two facts make it a fold rather than a re-model: `rule.name` and `pipeline.name` are both `UNIQUE`,
so `name` becomes the single identity with no new uniqueness to establish; and
`rule.target_enrichment` and `pipeline.target` are both `UNIQUE`, so one-writer-per-target already
holds on both sides and the merged `UNIQUE (target)` needs no data reconciliation. Registries are
small — a handful of aggregate pipelines and rules — and names do not collide across the two.

## What Changes

- **BREAKING** `/v1/rules` is **deleted**, not deprecated. Its nine endpoints move onto
  `/v1/pipelines` addressed by `name` instead of a UUID `id`: registration becomes
  `POST /v1/pipelines` with `"kind": "enrich"`, `PUT /v1/rules/{id}` becomes
  `PATCH /v1/pipelines/{name}`, the listing becomes `GET /v1/pipelines?kind=enrich&enabled=&updated_since=`
  returning the `{"pipelines":[…]}` envelope in place of today's bare array, and the four runner
  data-plane endpoints (`scan`, `group-rows`, `materialize`, `rows`) re-path with their request and
  response bodies unchanged. The Enrichment Runner is the only out-of-process consumer, so both
  services deploy in one maintenance window.
- **BREAKING** One merged flat DTO. `id` is removed, `target_enrichment` → `target`, `source` →
  `inputs`, `filter_sql` → `filter`, and the four trigger fields nest under `trigger{kind, cron,
  group_by, ready_when, member_select}` — `group_by` is forced to move because it collides with the
  aggregate grain. A field belonging to the other kind is rejected with **422**, which is the only
  thing that makes a flat union safe.
- **BREAKING** Error codes converge: `unknown_rule` → `unknown_pipeline`,
  `rule_validation_failed` → `pipeline_validation_failed`, `rule_access_denied` →
  `pipeline_access_denied`, and the rule path's 409 stops returning a *validation* code —
  it becomes `pipeline_conflict`, which the pipeline path already returned.
- **BREAKING** A pipeline `name` is an **identity**, not a SQL identifier, and takes the identity
  grammar `^[a-z][a-z0-9_-]{0,63}$`. This *widens* the grammar the pipeline path enforces today
  (`Identifiers.requireUserIdentifier`, which admits no hyphen and would reject all four existing
  enrichment names) and it *removes* the rule path's ability to rename in place. The charset is
  load-bearing: the runner embeds the identity in `#`-delimited db-scheduler instance ids.
- **BREAKING** `GET /v1/rules` and `GET /v1/rules/{id}` are reachable by `READ_ONLY_ADMIN` today;
  under `@FullAdminOnly` pipelines they become FULL_ADMIN-only. The runner's identity is already
  FULL_ADMIN, so nothing breaks — but read-only admins lose the ability to inspect an enrichment
  declaration.
- A declaration change on an *enabled* pipeline stays a 409 for AGGREGATE and is **permitted for
  ENRICH**. The 409's stated reason is a windowed rollup holding rows computed two ways; an ENRICH
  target is a grain-keyed `ReplacingMergeTree`, where that is already how it works.
- `V36__fold_rule_into_pipeline.sql` (authored as `V34`; see the note above) widens `pipeline` with the trigger, evaluator and Class-1
  position columns, moves the four rules onto it keyed on `name` (carrying `generation`, the
  Class-1 position and each row's `enabled` value untouched), and renames `rule` to `rule_legacy`.
  It changes no existing row's `spec` value.
- The registry object is documented where it belongs: a new `pipeline-registry` capability owns the
  kind-independent contract, and `aggregate-pipeline` reduces to the AGGREGATE transform.

Explicitly **out of scope**, each a real gap and each its own follow-up: dry-run preview for ENRICH;
pipeline backfill as a bounded run of one definition; table lifecycle `status`; the `EXTERNAL` kind;
a runner-reported materialized position. Behavior is preserved everywhere the fold does not force a
change — with the four exceptions listed above, all of them narrowings or conflicts the merge
exposes.

## Capabilities

### New Capabilities
- `pipeline-registry`: the kind-independent registry object — registry CRUD over `/v1/pipelines`,
  the `kind` discriminator and its refusal contract, `UNIQUE (target)`, the identity grammar and
  immutability, the register → enable lifecycle, cross-kind field rejection, readable runtime
  `state`, and the shared `ErrorView` codes.

### Modified Capabilities
- `aggregate-pipeline`: reduces to the AGGREGATE transform and its in-ADAS execution. Four
  requirements move to `pipeline-registry` (registry object, the non-preview half of the register /
  enable lifecycle, readable runtime state, the error contract); the clamp requirements drop
  "rule" from their reason codes and prose.
- `enrichment-registry`: reduces to evaluators plus the ENRICH declaration and its static
  governance gate. "One rule per target enrichment" moves to `pipeline-registry`; thirteen of its
  sixteen requirements are rule-worded and re-path onto `/v1/pipelines`.
- `enrichment-runtime`: the four data-plane endpoints re-path and "rule-scoped" becomes
  "pipeline-scoped"; substance unchanged.
- `class1-enrichment`: `materialize` re-paths and the position requirement is renamed; substance
  unchanged.
- `table-management`: the 409 messages guarding a table that a declaration reads are reworded from
  "rule(s)" to one pipeline-shaped message.

## Impact

- **API**: `/v1/rules` and `/v1/rules/{id}/**` deleted; `/v1/pipelines` gains `kind`, `enabled` and
  `updated_since` filters plus the four data-plane endpoints. `OidcSecurityConfiguration` must pin
  the four data-plane paths as `.authenticated()` **before** the admin rule, while `preview` and
  `refresh` stay `@FullAdminOnly`.
- **Catalog schema**: `V36`; `rule` becomes `rule_legacy` (dropped in a follow-up change, once the
  cut-over window has closed — it is the only source of the runner's id→name mapping and the only
  in-database rollback reference).
- **Code**: `PipelineEntity`/`PipelineRepository` absorb `RuleEntity`/`RuleRepository`;
  `RuleService`, `RuleController`, `RuleDataController`, `CreateRuleRequest`, `RuleDto` and
  `RuleMapper` are deleted; ~20 further classes are renamed to the pipeline vocabulary;
  `EnrichmentFreshness` moves into `service/pipeline` to avoid a package cycle. `service/enrichment/`
  keeps its name and its 52 files.
- **No config key renames**: `enrichment.*` and `aggregate.*` are named for the domain and the kind,
  not for "rule".
- **Coordinated deploy**: the Enrichment Runner's `fold-rule-into-pipeline` change ships in
  the same window, and its five runtime-state tables are re-keyed from the UUID to the name by an
  operational step between the two deploys. `adas-mcp` regenerates its OpenAPI snapshot and collapses
  `tools/rules.py` into `tools/pipelines.py` as a third repo.
