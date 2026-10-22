## Why

Pipelines and evaluators are effectively unauthorable by hand — they need MCP plus a strong model to
assemble a body. Surveying real declarations, the cause is not "many features" but **the
same fact declared repeatedly, with nothing cross-checking the copies**: `output_bindings` is
`column == var` on **every** binding, `output_vars[].jsonata` equals `name` on **all but one**
output var, the declared `input_vars` are read by nothing, `distinct: false` is echoed on **nearly
every** measure, `freshness: {mode: periodic}` is the only legal value on **every** aggregate, and
`priority` is read by nobody. One output field (`risk_level`) can be declared **seven** times.

The copies drift, three ways: enum domains disagree in order between an
evaluator's schema and its target column (the column's order assigns the numeric ids and the sort
order); `sentiment_score` is spelled `double`, `decimal` and `number` in three places; and because
`response_schema` is stored `jsonb`, Postgres re-sorted its keys by length-then-bytes, so
a pipeline such as `session-insights` generates `risk_level` **ninth of fifteen — ahead of all three
fields it is instructed to derive itself from**. Nothing in the system could have noticed any of it.

## What Changes

The principle: **structure is declared once and derived everywhere else; prose is not structure.**
One authority per field — the target table's column wherever it can carry the fact, the evaluator's
output where the column structurally cannot.

- **Evaluator gains `outputs`** — a map of output name → what the model is told about it (a bare prose
  string, or an object with `prose` plus at most one of `values` / `jsonata`). For a `sql` evaluator
  the value is the bare expression. **BREAKING**: `input_vars`, `response_schema` and `output_vars`
  are no longer accepted (400 naming the field), and per-output `type` is dropped for `sql`.
- **The pipeline composes the response schema** from its own `target` + `evaluator` — property name,
  type, enum domain **and order** come from the target column; `description` from the evaluator's
  prose; property order and `required` from the `outputs` declaration order. The table stays ignorant
  of evaluators.
- **No stored `evaluator_version` row is ever rewritten**, of either type. A stored `response_schema`
  (and a stored `sql` row's `output_vars`) keeps winning forever for the version that stores it; the
  two shapes coexist in storage and one accessor resolves them. Prompt delta on deploy: **none,
  bit-identical.**
- **Pipeline request shrinks**: `input_bindings` + `input_vars` → `vars`; five knobs move behind one
  nested `advanced` object and four are renamed (`cadence`→`scan_every`,
  `batch_scan_limit`→`rows_per_scan`, `batch_chunk`→`rows_per_call`, `sampling`→`sample_fraction`,
  strict 0–1). **BREAKING**: `output_bindings`, `priority` and the old flat spellings are 400s;
  `output_bindings` is derived from `outputs` × target columns and stored by ADAS (option B).
- **Aggregates shrink**: `group_by` optional and derived from the target's `ordering_key` (normalised
  into the stored spec at registration, echoed expanded), wire `freshness` removed, `distinct: false`
  no longer echoed, measure `column` defaults to `name` **except for `count`** (echoed expanded),
  and `enabled: true` at registration becomes a 422 naming `/preview` instead of a silent coercion.
- **Trigger-aware template validation**: at row grain every `{{placeholder}}` is a `vars` key and
  there are no built-ins; at group grain the placeholders are exactly the **five** `members_*`
  built-ins, `{{members}}` is mandatory, and `vars` are consumed inside it and are *not* checkable
  against the template. (The naive rule would refuse every existing group pipeline.)
- **The catalog notifies the registry** when an enrichment target's columns change
  (`onTargetColumnsChanged`: bump `generation` **and** `updated_at`, re-derive the stored bindings),
  scoped to `llm` — a Class-1 bump would buy nothing and would clamp every consumer of the
  enrichment until its next materialize.
- **Provenance auto-injection** on `POST /v1/tables/{name}/schema` for an enrichment (new tables
  only), appending only the columns the caller did not declare, in a fixed declared order.
- **Two read projections, one vocabulary**: `?view=source` (**default** — the authored declaration,
  echoing the *stored* `inputs`) and `?view=compiled` (the resolved executable form the Enrichment
  Runner asks for by name). They differ in resolution, never in naming: `input_bindings`,
  `output_bindings`, `cadence`, `batch_scan_limit`, `batch_chunk` and `sampling` are gone from every
  response, and the derived mapping is served as **`outputs`** — one list of `{name, column,
  jsonata?}`, folding what used to be `output_bindings` joined by name to the evaluator's
  `output_vars`. The inlined evaluator drops `input_vars`/`output_vars`; `GET /v1/evaluators/...`
  still answers with the stored row exactly as written.
- **`POST /v1/pipelines/{name}/preview` extended to `enrich`** — composed schema, resolved output
  mapping, expanded `vars`, sampled inputs. Writes nothing, calls no model, renders no prompt.
- **Migration V39**: `ALTER TABLE evaluator_version ADD COLUMN outputs JSONB`, and nothing else. Not
  one row rewritten, no re-registration, no re-drain, no spend.

## Capabilities

### New Capabilities

None. Every change lands on an existing capability's requirements.

### Modified Capabilities

- `enrichment-registry`: the evaluator `outputs` shape and the two coexisting stored shapes resolved
  through one accessor; pipeline-side schema composition and name-matched output resolution;
  trigger-aware template validation and ADAS's ownership of the five built-in placeholder names;
  `vars`/`advanced` on the request surface; the guarantee that an enrich pipeline whose output
  mapping does not resolve is **not served on the feed**; evaluator registration re-validating
  against unpinned dependent pipelines; the `view` projections and the enrich preview.
- `pipeline-registry`: `vars`/`advanced` request shape, the removed fields, `hasDeclaration()` and
  `requireComplete` coverage, the `view` parameter, an enrich pipeline's read source being separable
  from its target's `source_table`, and the revision-read regression.
- `aggregate-pipeline`: derived `group_by`, removed wire `freshness`, fn-aware measure `column`
  defaults, un-echoed `distinct`, registration-only `enabled: true` 422.
- `class1-enrichment`: `sql` evaluators declaring `outputs` with no per-output `type`, the collapsed
  single type check against the target column, and why a `sql` evaluator needs no `generation` bump.
- `table-management`: provenance auto-injection on enrichment schema definition. (The catalog's new
  notification to the registry on an enrichment target's column change is specified in
  `enrichment-registry`, alongside the existing `version_column`-repoint bump it mirrors.)

## Impact

- **APIs**: `POST|PATCH /v1/pipelines` (shrunken bodies, removed fields are 400s),
  `GET /v1/pipelines[/{name}]` (`?view=`), `POST /v1/pipelines/{name}/preview` (enrich arm),
  `POST /v1/evaluators` (`outputs`; three fields removed), `POST /v1/tables/{name}/schema`
  (provenance injection).
- **Catalog schema**: one additive column, `evaluator_version.outputs JSONB` (V39). No stored
  `pipeline.spec` migration: the four renamed knobs gain a `@JsonAlias` on their former spelling, so
  every existing row keeps parsing and only a rewritten row moves to the new name.
- **Code**: `service/enrichment/` (new schema composer, `EvaluatorService`,
  `EvaluatorVersionEntity.effectiveOutputVars()`, `EnrichResolver`, `EnrichSpecValidator`),
  `service/pipeline/` (`PipelineService`, `AggregateSpecValidator`, `EnrichSpec`, `AggregateSpec`,
  `Measure`), `service/table/` (`PipelineGenerationBump`, `CatalogSchemaWriter`,
  `ProvenanceColumns`), `web/dto/pipeline/`, `web/dto/enrichment/`, `web/mapper/`,
  `web/controller/pipeline/`.
- **Other repos**: the Enrichment Runner **ships in the same maintenance window** and is not
  optional. The deploy is: scale the runner to 0 → update ADAS → update the runner → scale back to 1,
  so no old runner ever reads a new feed. (An earlier draft kept the feed byte-identical so the runner
  could follow on its own schedule; that bought one rolling-deploy window at the price of a permanent
  second vocabulary, and was withdrawn — see design decision 9.) adas-mcp ships in the same release:
  the tool docstrings and the OpenAPI snapshot name the old fields, and that is the real
  compatibility surface, not a typed client.
- **Untouched by construction**: `generation`, `materialized_through_*`, `drained_at`,
  `history_rederived_at`, `enabled` on every existing row; the rendered prompt is bit-identical. The
  stored spec is likewise never migrated — the four renamed knobs are written under their new names
  and read under either, via `@JsonAlias`.
