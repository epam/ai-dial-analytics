## Context

springdoc builds the OpenAPI document from the controller annotations. Twenty REST controllers expose about
seventy operations, and the document is served only at runtime on `/v3/api-docs`, credential-gated in `oidc`
mode (see `openspec/specs/authentication/spec.md`). `OpenApiPublishedContractIntegrationTest` already reads the
document in-process over a PostgreSQL-only context. It asserts a few published enum defaults
(`openspec/specs/pipeline-registry/spec.md`) but does not pin the document as a whole.

Current state relevant to client generation:

- **Operation ids** are derived from Java method names. `create`, `delete`, `get`, `list`, `replace`,
  `snapshot` and `writeRows` each occur on two to seven controllers. That covers about thirty handlers, and
  springdoc separates them with `_1`, `_2` suffixes in scan order. `PipelineController.listPipelines` was
  already renamed for this reason. Only one handler declares an `operationId`.
- **Tags** exist on every controller: thirteen tags, one per resource. The six `/v1/queries` controllers share
  `Structured Queries (experimental)`, but declare it with two different descriptions. The top-level `tags`
  entry springdoc publishes therefore depends on which controller it scans first.
- **Non-client operations**: the OTLP receivers (`OtelIngestController`) and the Enrichment Runner data plane
  (`PipelineDataController`, every `POST` except the `@RunnerContractExempt` `preview` and `refresh`).
- **CI** runs the shared `ai-dial-ci` reusable workflows. PR CI runs `./gradlew check`.

**The DIAL Core precedent.** The SDK already generates its Core client from `docs/open_api_core.yaml` in
`epam/ai-dial-core`, and this change follows that model where it fits:

- Core generates the file with its own annotation processor (Core is not a Spring application): OpenAPI 3.0,
  YAML. `./gradlew replaceSpec` rewrites it.
- The generator validates required and unique `operationId`s, unique `(method, path)` pairs, and that every
  annotated operation matches a registered route.
- Endpoints still under development carry `x-preview: true`, and Core's README documents that extension.
- A PR job regenerates the file and pushes a bot commit to the PR branch when it changed. A byte-compare task
  (`checkOpenApiDiff`) exists but is not part of `check`, so drift is fixed automatically, never failed.
- Releases carry no asset. Consumers read the file from the repository at a branch or tag.
- `./gradlew replaceSpec -Plint` runs Redocly lint with a relaxed rule set.

## Goals / Non-Goals

**Goals:**

- A committed, byte-stable `docs/openapi.json` that equals what the code serves, enforced by the build.
- Stable, explicit, unique operation ids; one tag per operation.
- Preview and internal operations marked with extensions an SDK built against both DIAL Core and ADAS reads
  the same way.

**Non-Goals:**

- Generating or publishing a client from this repository. That belongs to the SDK.
- Attaching the file to GitHub releases. The release tag carries `docs/openapi.json`, which is how consumers
  read DIAL Core's file. The shared release workflow exposes no outputs, so a follow-on upload job would have
  to work out the tag from the version commit. That means fragile coupling for no extra information.
- Renaming tags other than the query tag, or reshaping paths, parameters or DTOs for nicer generated code.
  Any of these would be a separate, deliberately breaking change.
- Stamping the release version into `info.version`. It stays `v1`, the API's major version. Core's committed
  file carries the unstamped `0.0.0`, so neither service puts a release number in the file. The tag says
  which release a file belongs to.
- A YAML copy of the document.
- Changing who may read `/v3/api-docs` at runtime.

## Decisions

### Generate from an in-process integration test, not the springdoc Gradle plugin

The snapshot comes from a test that extends `AbstractPostgresIntegrationTest`, reads `/v3/api-docs` through
`MockMvc`, normalises it, and then either compares it with `docs/openapi.json` (default) or writes the file
(when the system property `openapi.update=true` is set). `./gradlew updateOpenApi` is a `Test` task that runs
only that class with the property set. The comparison runs in the normal `test` task, so PR CI's
`./gradlew check` enforces drift with no workflow change.

*Rejected: `springdoc-openapi-gradle-plugin`.* It forks `bootRun` and polls the endpoint. ADAS startup needs
reachable PostgreSQL and ClickHouse (Flyway, schema reconciliation) and credentials, which means extra CI
services and a second, slower startup path that tests don't cover. *Rejected: a `@WebMvcTest` slice.* It
would build the document from a partial set of beans, and the file must equal what the full application
serves.

The test shares the existing PostgreSQL context (same base class, no extra `@TestPropertySource`), so it adds
no entry to the capped context cache. The best place for it is beside `OpenApiPublishedContractIntegrationTest`
or inside that class. The PostgreSQL-only context must still map every REST controller. A guard asserts that
the set of handler methods in `RequestMappingHandlerMapping` equals the set of operations in the document, so
a controller missing from the context fails loudly and is never silently left out of the file.

### Drift fails the build; no bot commit

A PR whose code changes the document but whose `docs/openapi.json` doesn't match fails `./gradlew check`,
naming `./gradlew updateOpenApi`. The author regenerates the file and commits it.

*Rejected: Core's regenerate-and-push PR job.* It works for Core because Core's generator needs nothing but
the classpath. Here, generation boots a Spring context over Testcontainers PostgreSQL, so a second job would
need Docker and a duplicated test run. A bot push also needs `contents: write` on pull requests, fails for
PRs from forks of this public repository, and adds a commit the author didn't make or review locally. A
failing check with a one-command fix keeps the generated diff in the author's own commit, where reviewers see
it next to the code that caused it.

### Normalise in the test; leave the served document alone

The normaliser sorts object keys recursively, keeps array order, drops the top-level `servers`, and writes
two-space pretty JSON with a trailing newline. Normalising in the test rather than through
`springdoc.writer-with-order-by-keys` keeps runtime output and configuration unchanged. A consumer that
compares the runtime document with the file compares parsed JSON, not bytes. Array order is kept because
springdoc emits it deterministically from annotation order, and because sorting `enum`, `parameters` or
`oneOf` would change meaning: the `pipeline-registry` spec relies on enum order.

On a mismatch the test reports the first differing JSON pointer and the regenerate command. It does not dump
the whole diff, which is what `git diff` after `updateOpenApi` is for.

*Rejected: YAML, to match Core.* JSON is springdoc's native output, needs no extra serialiser configuration to
be stable, and every generator reads both formats. *Rejected: repository root.* `docs/` already holds the
API-facing reference material, and it is where Core keeps its file.

### Explicit operation ids, kept where already good

Every `@Operation` gets an `operationId`. Where the derived id is already unique and resource-named
(`listPipelines`, `executeSql`, …) the explicit id keeps the same spelling, so those operations don't change.
Only the colliding handlers are renamed. A unit-level guard walks `RequestMappingHandlerMapping`, reads each
handler's `@Operation`, and fails on a missing id, a duplicate, a non-lowerCamelCase id, or a `_\d+$` suffix.
It reads annotations rather than the document because springdoc has already resolved collisions by the time
the document exists. These are the checks Core's generator makes.

The rule is "explicit on every handler", not just "unique in the document". A derived id changes silently when
someone renames a Java method in a refactor, and that would break generated clients. Because the id is
explicit, an id change has to be a visible edit to the annotation.

### Tags: one per operation, one description per tag, `Queries` renamed

The guard asserts that every operation has exactly one tag and every tag is declared with a description. It
also asserts that every `@Tag` declaring the same name declares the same description, because springdoc keeps
whichever declaration it meets first.

`Structured Queries (experimental)` becomes `Queries` on all six `/v1/queries` controllers, with one shared
description, held in a constant so the six can't drift apart again: "Run structured (DSL) and SQL queries,
export results as CSV, translate between DSL and SQL, and discover the queryable schema. Experimental — may
change." The old name gives generators an unusable class name (`StructuredQueriesExperimentalApi`), and it
undersells a group that also covers SQL. Experimental status describes stability, not the resource, so it
leaves the tag name. It moves into the description, for readers, and into `x-preview`, for tools. The rename is
done now because the first committed file and the first generated clients fix tag names. After that, a rename
renames a client class.

The other twelve tags keep their names. They are already one per resource and generate usable class names,
and renaming them all to lower-kebab would change the Swagger UI grouping for no gain to generators.

### `x-preview` and `x-internal`, applied by marker, verified by guard

Two operation-level extensions, each meaning one thing:

- **`x-preview: true`**: the operation is part of the client API but may still change. Every operation
  tagged `Queries` carries it. This is Core's extension for endpoints under development, so the SDK treats
  ADAS and Core preview endpoints alike, for example by generating them into a preview namespace or marking
  them with a doc comment. Removing it from an operation is the deliberate act that declares that operation
  stable.
- **`x-internal: true`**: the operation is not for API clients at all. It goes on every `OtelIngestController`
  handler and every `PipelineDataController` `POST` without `@RunnerContractExempt`. Redocly and
  openapi-generator recognise `x-internal` for skipping operations, so most consumers need no configuration.

Both are applied by marker annotations, `@ApiPreview` and `@ApiInternal` (package `web.openapi`), on a
controller or a handler. `OpenApiMarkings`, a springdoc `GlobalOperationCustomizer`, turns them into
the extensions as JSON booleans. Inline `@Operation(extensions = @Extension(properties = @ExtensionProperty(…,
parseValue = true)))` was rejected: it would repeat a three-level annotation on eighteen handlers, with a string
value whose boolean-ness depends on remembering `parseValue`.

The guard derives both expected sets independently of the annotations and fails on any difference in either
direction:

- Preview: the operations of the controllers that declare the `Queries` tag constant.
- Internal: the OTLP ingest handlers, plus the non-exempt runner `POST`s.

This reuses the deny-by-default rule the runner contract already uses, so a new runner endpoint that forgets
the marking fails the build. A new `/v1/queries` endpoint is preview by construction. No operation may carry
both, because an internal operation's stability is not a client concern.

*Rejected: `@Hidden` / a separate `GroupedOpenApi`.* Hiding removes the operations from the Swagger UI, which
operators use to test sinks and runner calls. A second group would mean two documents to commit and keep in
sync. *Rejected: a dedicated tag such as `internal` or `preview`.* It would replace the resource tag and
conflict with the one-tag rule. *Rejected: `x-preview` without `x-internal`, as Core has only the one.* Core
has no machine-producer endpoints. Here, preview means "use it, carefully" and internal means "not for you",
and a generator needs to tell those apart.

### Lint once with Redocly; don't add it to the build

During implementation, the committed file is linted with `npx @redocly/cli lint` against a ruleset starting
from Core's `openapi-generator/redocly.yaml`: `recommended` with `tag-description`, `no-empty-servers`,
`no-path-trailing-slash`, `operation-2xx-response` and `operation-4xx-response` off. The findings are fixed in
annotations, or recorded as rules turned off with their reason. The build gets no lint step: it has no Node
toolchain, and the guards above already enforce the rules the SDK depends on (ids, tags, markings).

That lint found three defects, now fixed in the annotations:

- The `Adas-Contract-Version` header on the four runner endpoints, and the optional `role` query parameter on the
  table-access `GET`, had no `schema`. The header is published as `type: string`, matching the interceptor's
  string comparison. `role` is published as `type: string`.
- `BearerAuth` is `type: http`, which takes no `in` or `name`. Those members are dropped.
- `info` had no `license`. It now names Apache 2.0, the repository's license.

## Risks / Trade-offs

- **Merge conflicts on `docs/openapi.json`** between parallel PRs that both touch the API → The file is
  generated, so resolving means rebasing and running `./gradlew updateOpenApi`. The failure message says so.
- **springdoc upgrades reshape the document** (new defaults, different schema naming) → A Dependabot bump
  fails the drift check. That is the desired signal: the bump PR regenerates the file and the diff shows
  consumers what changed. Dependabot PRs need a maintainer to push the regenerated file, which a bot commit
  would have avoided. That cost is accepted for the reasons under the drift decision.
- **Renaming colliding operation ids breaks clients generated from a runtime document** → No committed
  contract existed, so the break happens once, before the first published file. The proposal flags it as
  **BREAKING** and the release notes list the old → new ids.
- **The PostgreSQL-only context might not register a controller** whose dependencies are ClickHouse-only beans
  → The handler-set guard described under the first decision fails in that case. The fix is to make the
  document's context complete, never to accept a partial file.
- **Renaming the query tag changes the Swagger UI grouping** → The operations stay together under `Queries`.
  Only the heading changes, and the release notes mention it.
- **No release asset** → A consumer that wants a download link uses the raw file URL at the tag
  (`…/blob/<tag>/docs/openapi.json`), as with Core. If an asset is wanted later, the shared `ai-dial-ci`
  workflow is the place to add it for every service.

## Migration Plan

No runtime migration. The change ships the file and the guards together. Existing deployments see only the new
operation ids, the `Queries` tag and the `x-preview` / `x-internal` extensions in `/v3/api-docs`. Rollback is
a revert. Release notes for the version that ships this change list the renamed operation ids and the tag
rename.

The renamed operation ids, for the release notes. A previous id with a `_N` suffix was assigned in scan order, so a
given runtime document may have numbered it differently. The twelve operations not listed keep their id. The tag
`Structured Queries (experimental)` becomes `Queries`.

| Operation | Previous id | New id |
|---|---|---|
| `POST /v1/activities` | `list_4` | `listActivities` |
| `GET /v1/activities/{activityId}` | `get_6` | `getActivity` |
| `POST /v1/catalog/export` | `export` | `exportCatalog` |
| `POST /v1/catalog/export/preview` | `exportPreview` | `previewCatalogExport` |
| `POST /v1/catalog/import` | `importBundle` | `importCatalog` |
| `POST /v1/catalog/import/preview` | `importPreview` | `previewCatalogImport` |
| `GET /v1/dashboards` | `list_3` | `listDashboards` |
| `POST /v1/dashboards` | `create_4` | `createDashboard` |
| `DELETE /v1/dashboards/{id}` | `delete_3` | `deleteDashboard` |
| `GET /v1/dashboards/{id}` | `get_3` | `getDashboard` |
| `PUT /v1/dashboards/{id}` | `replace_1` | `replaceDashboard` |
| `POST /v1/data-sync/backfill` | `trigger` | `triggerBackfill` |
| `GET /v1/data-sync/backfill/{runId}` | `status` | `getBackfillRun` |
| `GET /v1/data-sync/failures` | `list_5` | `listDataSyncFailures` |
| `POST /v1/data-sync/failures/retry` | `retry` | `requeueDataSyncFailures` |
| `POST /v1/otel/sink/{sinkName}/v1/logs` | `logs` | `exportOtelLogs` |
| `POST /v1/otel/sink/{sinkName}/v1/metrics` | `metrics` | `exportOtelMetrics` |
| `POST /v1/otel/sink/{sinkName}/v1/traces` | `traces` | `exportOtelTraces` |
| `GET /v1/otel/sinks` | `list_2` | `listOtelSinks` |
| `POST /v1/otel/sinks` | `create_3` | `createOtelSink` |
| `DELETE /v1/otel/sinks/{name}` | `delete_5` | `deleteOtelSink` |
| `GET /v1/otel/sinks/{name}` | `get_5` | `getOtelSink` |
| `POST /v1/pipelines` | `create_2` | `createPipeline` |
| `DELETE /v1/pipelines/{name}` | `delete_4` | `deletePipeline` |
| `GET /v1/pipelines/{name}` | `get_4` | `getPipeline` |
| `PATCH /v1/pipelines/{name}` | `patch` | `patchPipeline` |
| `POST /v1/pipelines/{name}/group-rows` | `fetchGroup` | `fetchPipelineGroupRows` |
| `POST /v1/pipelines/{name}/materialize` | `materialize` | `materializePipelineWindow` |
| `POST /v1/pipelines/{name}/preview` | `preview` | `previewPipeline` |
| `POST /v1/pipelines/{name}/refresh` | `refresh` | `refreshPipeline` |
| `GET /v1/pipelines/{name}/revision/{revision}` | `snapshot_2` | `getPipelineRevision` |
| `POST /v1/pipelines/{name}/rows` | `writeRows_1` | `writePipelineRows` |
| `POST /v1/pipelines/{name}/scan` | `scan` | `scanPipelineSource` |
| `GET /v1/queries/entities/schema/{name}` | `getBaseSchema` | `getEntitySchema` |
| `POST /v1/queries/execute` | `execute` | `executeQuery` |
| `POST /v1/queries/execute-csv` | `executeCsv` | `executeQueryCsv` |
| `GET /v1/queries/functions` | `listFunctions` | `listQueryFunctions` |
| `POST /v1/queries/translate` | `translate` | `translateQuery` |
| `GET /v1/saved-queries` | `list_1` | `listSavedQueries` |
| `POST /v1/saved-queries` | `create_1` | `createSavedQuery` |
| `DELETE /v1/saved-queries/{id}` | `delete_2` | `deleteSavedQuery` |
| `GET /v1/saved-queries/{id}` | `get_2` | `getSavedQuery` |
| `PUT /v1/saved-queries/{id}` | `replace` | `replaceSavedQuery` |
| `GET /v1/saved-queries/{id}/revision/{revision}` | `snapshot_1` | `getSavedQueryRevision` |
| `GET /v1/tables` | `list` | `listTables` |
| `POST /v1/tables` | `create` | `createTable` |
| `DELETE /v1/tables/{name}` | `delete` | `deleteTable` |
| `GET /v1/tables/{name}` | `get` | `getTable` |
| `PUT /v1/tables/{name}` | `update` | `updateTable` |
| `DELETE /v1/tables/{name}/access` | `delete_1` | `deleteTableAccess` |
| `GET /v1/tables/{name}/access` | `get_1` | `getTableAccess` |
| `PUT /v1/tables/{name}/access` | `put` | `replaceTableAccess` |
| `GET /v1/tables/{name}/revision/{revision}` | `snapshot` | `getTableRevision` |
| `POST /v1/tables/{name}/rows` | `writeRows` | `writeTableRows` |
| `PATCH /v1/tables/{name}/schema` | `patchSchema` | `patchTableSchema` |
| `POST /v1/tables/{name}/schema` | `defineSchema` | `defineTableSchema` |

## Open Questions

None. The OpenAPI version question is settled: springdoc emits OpenAPI 3.1.0 here, and the SDK's generator
toolchain (`openapi-typescript` 7, `openapi-fetch`, a Redocly bundle step) accepts it. A generation run over the
committed file produced types for every operation, keyed by the explicit ids. No `springdoc.api-docs.version`
pin is needed, so no configuration property is added.
