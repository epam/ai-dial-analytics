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
  `source` that filters members, for example to drop zeros, must yield `{}` rather than nothing.
- For a `chat_completion` transform, both types are still refused with HTTP 422
  `pipeline_validation_failed`. The message now says the refusal applies to the `chat_completion` preset,
  and suggests either `dial_app_route` or a scalar column.
- The arming gate now composes the response schema, so every composer refusal is the HTTP 422 the spec
  already promised on a gated request. Before, registration only resolved outputs to columns; a schema that
  could not compose armed with 201 and was served with no `response_schema`. An armed pipeline in that state
  is refused on its next declaration patch. A draft is still stored.
- The spec's wording is corrected to name the catalog types as they are declared (`object`, `map`), not
  `json`.
- Not breaking: a declaration that was refused before can now be accepted on a route. Every declaration
  that was accepted before composes the same schema it did before.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `enrichment-registry`: in "The response schema is composed from the target and the transform", the type
  mapping gains `object` and `map` for the `dial_app_route` preset, and the refusal is narrowed to
  `chat_completion`.

## Impact

- Code: `ResponseSchemaComposer` (type mapping and refusal message). `EnrichResolver` already passes the
  preset flag. Tests: `ResponseSchemaComposerTest`, a pipeline registration/gate test, and a
  write-path test for a nested object value on an enrichment's `object` column.
- API: `POST`/`PATCH /v1/pipelines` accept more declarations. `GET /v1/pipelines?view=compiled` (the
  runner's feed) can serve an object-typed property inside a route's `response_schema`.
- Enrichment Runner: no code or contract change. Its route path (assemble by `source`, validate with JSON
  Schema 2020-12, map by name, post rows as JSON) carries an object value through unchanged. See design.md.
- No migration, no config key, and no change to the catalog or the query surfaces. `object` and `map`
  columns are already declarable on enrichments and readable through path access and `json_extract_*`.
