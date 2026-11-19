## Why

Generated clients (for example a typed ADAS client in a TypeScript SDK) need the OpenAPI document as a
file they can pin to a release and diff between releases. DIAL Core already provides this with a committed
`docs/open_api_core.yaml`. Today the ADAS document exists only at runtime on `/v3/api-docs`: reading it means
booting ADAS with PostgreSQL, authenticating in `oidc` mode (or setting `DISABLE_SWAGGER_AUTHORIZATION=true`),
and then guessing which commit the answer belongs to. The document is also not yet stable enough to generate
from: springdoc derives `operationId` from the Java method name, and seven handler names (`create`, `delete`,
`get`, `list`, `replace`, `snapshot`, `writeRows`) repeat across controllers, so those operations are told
apart by a `_N` suffix that depends on bean scan order.

## What Changes

- Commit the OpenAPI document as `docs/openapi.json`. It is normalised so that the same code always gives the
  same bytes: keys sorted, `servers` dropped, pretty-printed with a trailing newline. Consumers read it from
  the repository at a release tag, the same way they read DIAL Core's committed file.
- A test in the existing `./gradlew build` compares the committed file with the document the code serves,
  and fails with the regenerate command when they differ. PR CI already runs the build, so a PR that changes
  the API without regenerating the file fails. A Gradle task, `./gradlew updateOpenApi`, rewrites the file.
- Every operation declares an explicit `operationId` that is unique across the document. The build fails on
  an operation with no explicit id, or on a springdoc collision suffix. **BREAKING** for anyone who generated
  a client from a runtime document: the operations that now carry a `_N` suffix get new names. No committed
  contract existed before this change, so no published file changes.
- Every operation has exactly one tag, and each tag name has one description. **BREAKING** for Swagger UI
  grouping and generated class names: the six `/v1/queries` controllers move from
  `Structured Queries (experimental)` to `Queries`. Today they declare that tag with two different
  descriptions, so the published description depends on scan order. The other per-resource tags are kept as
  they are.
- Two operation-level vendor extensions tell generators what an operation is:
  - `x-preview: true` marks the experimental `/v1/queries` operations, which may still change. This is DIAL
    Core's convention for endpoints still under development, so an SDK built against both services treats
    them alike. The tag description also says they are experimental.
  - `x-internal: true` marks operations that are not part of the client API, so generators and renderers can
    skip them. These are the OTLP/HTTP receivers under `/v1/otel/sink/{sinkName}/v1/*` and the Enrichment Runner
    data plane under `/v1/pipelines/{name}` (`scan`, `group-rows`, `materialize`, `rows`).
  Marked operations stay in the document and in the Swagger UI.
- No `/v1` endpoint changes behaviour. The runtime `/v3/api-docs` response carries the same operation ids,
  tags and extensions as the committed file.

## Capabilities

### New Capabilities

- `openapi-document`: the published OpenAPI description of the `/v1` surface. Covers what the committed
  file contains and how it is normalised, the drift check that keeps it equal to the code, operation id and
  tag rules, and the `x-preview` and `x-internal` markings.

### Modified Capabilities

None. The `authentication` requirement that gates `/v3/api-docs` and the Swagger UI, and the
`pipeline-registry` requirement on published enum defaults, still hold unchanged. The committed file is a
snapshot of the same document they describe.

## Impact

- **Code**: an explicit `operationId` on every `@Operation`. `@ApiPreview` on the `/v1/queries` controllers and
  `@ApiInternal` on the OTLP ingest and runner data-plane handlers, which a springdoc operation customizer publishes as
  `x-preview` and `x-internal`. One shared `Queries` tag on the six query controllers. Schemas on two parameters
  that published none, a valid `BearerAuth` scheme, and a `license` in `info`, all found by Redocly lint.
- **Tests**: a snapshot and drift test, plus guards for operation ids, tags and markings. All run in the
  context of the existing `AbstractPostgresIntegrationTest`, so no new Spring context is added.
- **Build**: `updateOpenApi` Gradle task. No new plugin or dependency.
- **CI**: no workflow change. PR CI's `./gradlew check` runs the drift check.
- **Configuration**: none. springdoc emits OpenAPI 3.1.0, and the SDK's generator accepts it, so
  `springdoc.api-docs.version` stays unset.
- **Docs**: `docs/openapi.json` (new, generated) and a short "Generating a client" section in `README.md`
  that links the file, the markings and the regenerate command.
- **Consumers**: client generators read `docs/openapi.json` at a release tag instead of fetching from a
  running instance.
