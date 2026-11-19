## 1. Operation ids and tags

- [x] 1.1 Add an explicit `operationId` to every `@Operation` across the REST controllers. Keep the current
      derived spelling where it is already unique and resource-named, and give the colliding `create`, `delete`,
      `get`, `list`, `replace`, `snapshot` and `writeRows` handlers resource-named ids (`createDashboard`,
      `listSavedQueries`, `writeTableRows`, `writePipelineRows`, …)
- [x] 1.2 Add an annotation guard test over `RequestMappingHandlerMapping`. It fails, naming the handler, on a
      missing id, a duplicate id, a non-lowerCamelCase id or a `_<digits>` suffix
- [x] 1.3 Rename the tag on the six `/v1/queries` controllers from `Structured Queries (experimental)` to
      `Queries`. Take the name and the single description (which keeps "Experimental — may change.") from one
      shared constant
- [x] 1.4 Extend the guard: every operation publishes exactly one tag, every tag in use is declared with a
      description, and a tag name declared with two different descriptions fails, naming both controllers
- [x] 1.5 Assert in `OpenApiPublishedContractIntegrationTest` that the `/v1/queries` operations carry exactly
      `Queries`, and that no operation carries `Structured Queries (experimental)`
- [x] 1.6 Record the old → new operation ids of the renamed handlers, and the tag rename, in the change for the
      release notes

## 2. Preview and internal markings

- [x] 2.1 Add the `x-preview: true` operation extension (`parseValue = true`, so it publishes as a boolean) to
      every handler on the six `Queries`-tagged controllers
- [x] 2.2 Add the `x-internal: true` operation extension to the `OtelIngestController` handlers and to every
      `PipelineDataController` `POST` not marked `@RunnerContractExempt`
- [x] 2.3 Add a guard that derives both expected sets independently: preview from the controllers declaring
      the `Queries` tag constant, internal from the OTLP ingest handlers plus the non-exempt runner `POST`s. It
      fails, naming the handler, on any handler marked or unmarked against its set, and on a handler carrying
      both
- [x] 2.4 Assert in `OpenApiPublishedContractIntegrationTest`:
      - `x-preview` is the boolean `true` on `POST /v1/queries/execute`, `execute-sql`, `translate` and
        `GET /v1/queries/entities`, and absent on `GET /v1/pipelines`, `POST /v1/saved-queries` and
        `GET /v1/dashboards`
      - `x-internal` is present on `scan`, `group-rows`, `materialize`, `rows` and the three OTLP receivers, and
        absent on `preview`, `refresh`, `GET /v1/pipelines`, `POST /v1/queries/execute` and `POST /v1/otel/sinks`

## 3. Snapshot and drift check

- [x] 3.1 Implement the normaliser: recursive key sort, array order kept, top-level `servers` dropped,
      two-space pretty JSON, trailing newline
- [x] 3.2 Add the snapshot test on the existing `AbstractPostgresIntegrationTest` context with no extra
      properties. It compares the normalised document with `docs/openapi.json` and reports the first differing
      JSON pointer and `./gradlew updateOpenApi`. With `-Dopenapi.update=true` it writes the file instead. A
      missing file fails the test
- [x] 3.3 Add a guard that the handler methods in `RequestMappingHandlerMapping` and the operations in the
      document are the same set, so a controller missing from the PostgreSQL-only context fails the build
- [x] 3.4 Register the `updateOpenApi` Gradle `Test` task. It runs only the snapshot test with
      `openapi.update=true`, carries the same JVM args as `test`, and is never up-to-date
- [x] 3.5 Run `./gradlew updateOpenApi`, commit `docs/openapi.json`, and check that a second run leaves the
      file byte-identical

## 4. OpenAPI version and lint

- [x] 4.1 Record which OpenAPI version (`openapi` member) springdoc emits here by default. Ask the SDK
      maintainers on the issue whether their generator takes it. If it needs 3.0, pin
      `springdoc.api-docs.version` to `openapi_3_0` in `application.yml` following the configuration
      conventions, add its one-line row to the springdoc table in `docs/configuration.md`, and regenerate the
      file. Otherwise add no property
- [x] 4.2 Lint `docs/openapi.json` once with `npx @redocly/cli lint`, using a ruleset that starts from DIAL
      Core's (`recommended`, with `tag-description`, `no-empty-servers`, `no-path-trailing-slash`,
      `operation-2xx-response` and `operation-4xx-response` off). Fix findings in the annotations and
      regenerate, or record each extra rule turned off with its reason in the design. This is not a build step
- [x] 4.3 Run a TypeScript generator against the file once. Check that it emits one client class per tag, no
      `_N` method names, and that `x-internal` operations can be skipped. This is a one-off check

## 5. Docs and spec

- [x] 5.1 Add a short "Generating a client" section to `README.md`. It says to read `docs/openapi.json` at a
      release tag, explains `x-preview` and `x-internal`, and names `./gradlew updateOpenApi` for contributors
- [x] 5.2 Confirm `openspec/specs/openapi-document/spec.md` (from this change's delta) matches the shipped
      behaviour, and that every contract the new tests pin has a scenario. Confirm `docs/configuration.md`
      changed only if 4.1 added the version property
- [x] 5.3 Run `.claude/skills/pr-ready/hygiene-sweep.pl` on the branch, including the generated
      `docs/openapi.json` (examples and descriptions in the annotations end up in it)

## 6. Verification

- [x] 6.1 `./gradlew clean build` passes
