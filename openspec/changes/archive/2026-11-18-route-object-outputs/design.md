## Context

`ResponseSchemaComposer.typeOf` maps a target column's type to a JSON Schema property. It is shared by both
`llm` presets. `EnrichResolver.composeSchema` already passes `nullableFromColumn = true` for
`dial_app_route`, and that flag currently controls only nullability. `object` (ClickHouse `JSON`) and `map`
(`Map(String, String)`) fall to the `default` branch and are refused, with a message saying a model cannot
be asked to produce them. For a route that message is wrong: no `response_format` is sent, and the runner
validates the object assembled from each output's `source` with a JSON Schema 2020-12 validator.

The motivating shape is an application that returns families of counters (`intent_counts`,
`friction_counts`, `satisfaction_counts` and so on). Today each key becomes its own integer column with its
own `source`, every key is stored even when zero, and a new key needs a new column and a re-declaration.

## Goals / Non-Goals

**Goals:**
- A route output can bind an `object` or `map` column, so the value is stored as one document.
- `chat_completion` keeps refusing both, with a message that names the preset.
- Schemas composed for every currently accepted declaration stay exactly the same.

**Non-Goals:**
- Typing an object's members. The catalog's `object` type declares no inner shape, and adding one is a
  catalog change of its own.
- Object outputs under `chat_completion`.
- Any change to the Enrichment Runner, the rows write path, or the query surfaces.
- Migrating existing flat `*_counts__*` columns. That is an operator's re-declaration.

## Decisions

**Preset-aware type mapping, carried on the flag that already exists.** `compose(..., nullableFromColumn)`
is already true exactly when the preset is `dial_app_route`. Rename the parameter to say what it means
(`route`, or pass the `TransformPreset`) and let `typeOf` consult it for `OBJECT` and `MAP`. Rejected: a
second boolean, which would let the two route relaxations disagree. Also rejected: a separate composer per
preset, which would duplicate the enum, description and order rules this class exists to keep in one place.

**`object` composes an open `{"type": "object"}`.** There is nothing to close it with: the column carries
no member list, and inventing `additionalProperties: {"type": "integer"}` would hard-code one use case into
a general type. Rejected: deriving members from the output's `prose` or `values`. That would be a second,
drift-prone declaration of a shape. The cost is that member types are not validated. A counter stored as a
non-numeric string reads back through `json_extract_long` as the type's default (a numeric string reads
back as its number), so the column description should state the contract.

**`map` composes `additionalProperties: {"type": "string"}`.** A `Map(String, String)` cannot store a
non-string value, and `WriteValuePreparer` already refuses a null value, so the schema states what insert
enforces. Validation then fails at the output that owns the problem, not as a write error.

**Object and map outputs are always required.** The catalog refuses a nullable `object` or `map` column
(`TableColumnRules`: ClickHouse has no `Nullable(JSON)` or `Nullable(Map)`), so the existing route rule,
nullability follows the column, makes both required and never `null`. That is also what the write path
needs: `TableDataService` refuses a row that omits or nulls a non-nullable column. Rejected: composing them
as optional and writing an absent value as `{}`. That would need either ADAS's write path to stop refusing
null for these columns for every writer, or the runner to know column types and substitute an empty value.
The first weakens a write contract. The second is a runner change and moves a storage rule into the runner.
An absent required value dead-letters at `validate`, which surfaces a broken `source` to the operator
instead of hiding it.

**Sparse documents are authored, not built in.** Dropping zeros belongs to the declaration, or to an
application that omits them. It is not a composer or writer rule: a writer that strips zeros would silently
change values for every other object output. With runner JSONata 0.9.10, `$sift` evaluates to nothing when
no member passes, and the required output then dead-letters. So the documented idiom keeps an empty
document: `$exists(intent_counts) ? $merge([{}, $sift(intent_counts, function($v){ $v != 0 })])` yields
`{"fix": 2}` for `{"fix": 2, "plan": 0}` and `{}` for all zeros. The `$exists` guard keeps a response that
lacks the member dead-lettering: `$merge([{}, …])` alone would turn it into `{}` and record the unit as
done. With no else branch the conditional yields nothing. Reads stay total: `json_extract_long` returns `0`
for an absent path, so a sum over a sparse counter document needs no `coalesce`.

**The arming gate composes the schema.** During implementation it turned out that registration never ran
the composer. `EnrichSpecValidator` only resolved each output to a column. The read paths
(`EnrichResolver.composedSchemaOf`) compose the schema but swallow a refusal and log it, so that a drifted
target cannot fail a whole listing. `PipelineService` then leaves such a pipeline out of the compiled feed
and answers 422 on its compiled read. The result was that every composer refusal (an object column under
`chat_completion`, `values` on an enum column, an array with no `element_type`) armed with 201, and the
pipeline then silently never ran. The spec already said such a declaration is rejected. Two changes fix
that:
- The gate now calls `ResponseSchemaComposer.composeFor` on every gated request, so the refusal is a 422
  when the pipeline is armed and a draft is still stored.
- `ResponseSchemaComposer.composes` is the one statement of when composition applies (`llm`, no stored
  schema, `outputs` declared). The gate and the resolver call it through `composeFor`, and
  `PipelineService` asks it for the feed omission and the compiled-read refusal, so they cannot disagree.
- Composing at arming reaches `List.copyOf(values)`, which throws on a `null` member, so the gate now
  refuses a null or blank `values` member as a 422 before composition runs.

Rejected: refusing on reads, because one drifted target would fail `GET /v1/pipelines` for every other
pipeline. Also rejected: leaving the gap to a separate change, because this change's own refusal scenario
cannot ship true without it. The consequence is intended: an armed pipeline whose schema does not
compose is refused on its next declaration patch, the same posture every other gate check has.

**No Enrichment Runner change.** The runner's route path was traced end to end:
- `AppRouteCall` evaluates each `source` with JSONata. A `$sift`/`$merge` result is a `LinkedHashMap`.
  Values that evaluate to nothing are left out, which is the runner's spec'd behaviour.
- The runner serialises the assembled object, and `ResponseValidator` validates it with networknt
  JSON Schema 2020-12, which accepts `{"type": "object"}` and `additionalProperties` schemas.
- `TransformInvoker` re-parses the validated JSON, and `OutputMapper` reads `map.get(name)` with no
  per-type coercion.
- `AdasUpsertClient` posts the rows as JSON, and ADAS writes `JSONEachRow`, where a nested object lands
  in the `JSON` column as a document.

Nothing on that path switches on value type.

## Risks / Trade-offs

- [Unvalidated member types in an `object` output] → stated in the spec. Authors who need typed members
  keep scalar columns. The column description carries the contract.
- [Readers move from `col__key` to `json_extract_long(col, 'key')`] → no ADAS change needed. Saved queries,
  dashboards and rollups over the flat columns are re-authored as part of the operator's migration.
- [Per-key catalog metadata (description, display name, sensitivity) is lost] → accepted for a document
  column. Keys that need governance stay scalar.
- [An all-zero `$sift` source dead-letters every quiet session] → the spec and the docs give the
  `$exists(…) ? $merge([{}, …])` idiom, and `POST /v1/pipelines/{name}/preview` shows the failure
  before arming.
- [Numeric member types drift: JSONata or the application may yield `2.0` where `2` was meant, and
  ClickHouse `JSON` then infers `Float64` for that path] → `json_extract_long` still reads the value. The
  write-path test pins an integer member round-tripping as an integer (`dynamicType` of the path is
  `Int64`).
- [The runner path behaves differently than traced] → the end-to-end task writes a nested object
  through ADAS's rows write path. The runner half rests on the trace above and the runner's own spec for
  route outputs.

## Migration Plan

No migration or config key. An armed `llm` pipeline whose schema does not compose never ran before this
change either; it is now refused on its next declaration patch, and the upgrade note shows how to find one.
Rolling back to a build without route pipelines follows that feature's rollback: re-declare each route
pipeline as `chat_completion` on scalar columns, or delete it.
