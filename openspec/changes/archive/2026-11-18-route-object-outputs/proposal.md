## Why

A `dial_app_route` transform cannot bind an output to an `object` or `map` target column. Schema
composition refuses both types with a message about strict mode and a model, and that reasoning applies
only to `chat_completion`. A route is not sent a `response_format`. The runner validates the object it
assembled from the outputs' `source` values against the composed schema, using a general JSON Schema
validator that handles an open object. So an application whose response carries a family of counters,
such as `intent_counts` or `friction_counts`, has to be split into one scalar column per key. Every key
must then be stored, zeros included, and a new key needs a new column.

## What Changes

- For a `dial_app_route` transform, schema composition maps a target column of type `object` to
  `{"type": "object"}`, and a column of type `map` to
  `{"type": "object", "additionalProperties": {"type": "string"}}`. Neither column type can be nullable, so
  under the existing route rule (nullability follows the column) such an output is always required. A
  `source` that filters members, for example to drop zeros, must yield `{}` rather than nothing, and
  should still yield nothing when the response lacks the member.
- For a `chat_completion` transform, both types are still refused with HTTP 422
  `pipeline_validation_failed`. The message now says the refusal applies to the `chat_completion` preset,
  and suggests either `dial_app_route` or a scalar column.
- The arming gate now composes the response schema, so every composer refusal is the HTTP 422 the spec
  already promised on a gated request. Before, registration only resolved outputs to columns; a schema that
  could not compose was accepted when armed, was then left out of the compiled feed and refused on a compiled read,
  so it never ran. An armed pipeline in that state is refused on its next declaration patch. A draft is
  still stored.
- An `llm` output's `values` with a `null` member is refused with 422 by composition, at arming and on
  preview. It used to throw, which arming would have turned into a 500. A blank member composes as before.
- The spec's wording is corrected to name the catalog types as they are declared (`object`, `map`), not
  `json`.
- **BREAKING**: arming is stricter. An `llm` declaration whose schema does not compose, which was accepted
  when armed before, is now refused with 422 when enabled or patched while enabled. Such a pipeline never ran, so
  nothing that runs stops running. Every other declaration accepted before composes the same schema it
  did before, and a route may now bind `object` and `map` columns.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `enrichment-registry`: in "The response schema is composed from the target and the transform", the type
  mapping gains `object` and `map` for the `dial_app_route` preset, and the refusal is narrowed to
  `chat_completion`; a `null` member of `values` is refused by composition. In "ENRICH pipeline declaration
  binding a transform to a target enrichment", the `values` member rule names that refusal and gains the
  scenario "An llm output declaring a null member in values is refused when armed".

## Impact

- Code: `ResponseSchemaComposer` (type mapping, refusal message, and `composes`/`composeFor`, the one rule
  for when composition applies). `ResponseSchemaComposer` refuses a `null` `values` member.
  `EnrichSpecValidator` composes the schema at arming. `EnrichResolver` and `PipelineService` (feed omission, compiled read) ask the same
  rule. Tests: `ResponseSchemaComposerTest`, a pipeline registration/gate test, and a write-path test for
  object and map values on an enrichment's columns.
- API: `POST`/`PATCH /v1/pipelines` accept object and map outputs on a route, and refuse at arming an
  `llm` declaration whose schema does not compose. A catalog import preview reports such a pipeline as
  `armable: false`. `GET /v1/pipelines?view=compiled` (the
  runner's feed) can serve an object-typed property inside a route's `response_schema`.
- Enrichment Runner: no code or contract change. Its route path (assemble by `source`, validate with JSON
  Schema 2020-12, map by name, post rows as JSON) carries an object value through unchanged. See design.md.
- No migration, no config key, and no change to the catalog or the query surfaces. `object` and `map`
  columns are already declarable on enrichments and readable through path access and `json_extract_*`.
