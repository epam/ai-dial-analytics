## Why

`source` is the `view` default in code, but nothing machine-readable says so: `PipelineView` declares
`COMPILED` first and both `view` parameters carry `example = "compiled"` with no schema default, so the
published OpenAPI reads `{"enum": ["compiled", "source"], "example": "compiled"}`. Any spec-driven
consumer — a generated client, an OpenAPI-rendered filter form, a `<select>` with no empty option — fills
an optional enum from its first member or its example, so a caller whose code never mentions `view` sends
`view=compiled`. The DIAL Admin UI pipelines page has been doing exactly that since the parameter shipped;
it answered 200 while the compiled listing silently mis-answered with authored declarations, and now
answers `400 bad_request` since the contract guard turned that mis-answer into a refusal. The request never
changed — the published contract had been misdirecting clients for two days.

## What Changes

- `PipelineView` declares `SOURCE` before `COMPILED`, so the published enum leads with the value that is
  actually the default.
- Both `view` parameters on `GET /v1/pipelines` and `GET /v1/pipelines/{name}` publish
  `schema.default = "source"` and carry `example = "source"`.
- `PipelineKind` declares the unimplemented `EXTERNAL` **last**, so the published enum no longer offers it
  as the middle choice a generated form picks. `kind` gains **no** default: an absent `kind` means "all
  kinds", so publishing one would be wrong.
- A test asserts the published document itself. Nothing in ADAS asserts `/v3/api-docs` today, which is why
  a contract defect invisible at the Java level shipped.

Not breaking. The accepted value set, the refusal codes and the in-code default are all unchanged; only
what the document *advertises* changes. A client that already sends `view` explicitly is unaffected, and a
client that sends nothing keeps getting the authored projection.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `pipeline-registry`: the requirement *A pipeline read selects an authored or a compiled projection*
  already states that `source` SHALL be the default. It gains the obligation that the default and the
  accepted-value ordering be **published machine-readably**, so a spec-driven client arrives at the
  default without reading prose — and the obligation that an unimplemented `kind` not be advertised ahead
  of implemented ones.

## Impact

- **Surfaces**: `GET /v1/pipelines` and `GET /v1/pipelines/{name}` — the published OpenAPI description of
  the `view` and `kind` query parameters. No request or response body changes; no endpoint behaviour
  changes.
- **Code**: `web/dto/pipeline/PipelineView.java` (declaration order),
  `service/pipeline/PipelineKind.java` (declaration order),
  `web/controller/pipeline/PipelineController.java` (both `@Parameter` annotations).
- **Downstream**: `adas-mcp` bundles a snapshot of this document
  (`src/adas_mcp/swagger/adas-openapi.json`) and must regenerate after this ships; that is a separate
  docs-only follow-up in that repo, not part of this change.
- **No config keys, no migration, no new credential.** `RunnerContract.VERSION` is **not** bumped: the
  compiled projection's shape is untouched, and the runner passes `view=compiled` explicitly rather than
  relying on any default.
