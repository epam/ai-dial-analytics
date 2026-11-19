# openapi-document Specification

## Purpose

The published OpenAPI description of the `/v1` surface, for client generators and API reviewers. The document
the service serves on `/v3/api-docs` is committed as `docs/openapi.json` in a byte-stable normal form, and the
build fails when the file drifts from the code. Every operation carries an explicit, unique operation id and
exactly one tag. The extensions `x-preview` and `x-internal` tell a generator which operations may still change
and which are not for API clients.

## Requirements

### Requirement: The OpenAPI document is committed as a versioned file

The repository SHALL contain the service's OpenAPI document at `docs/openapi.json`. The file SHALL be the
document `/v3/api-docs` serves for the same commit, after the normalisation defined below, so a consumer can
read the API contract of any commit or release tag without running the service. The file SHALL be produced
only by the regenerate command (`./gradlew updateOpenApi`) and never edited by hand.

#### Scenario: A consumer reads the contract of a release

- **WHEN** a consumer checks out a release tag and reads `docs/openapi.json`
- **THEN** the file describes every `/v1` operation that release serves, with the same paths, parameters,
  request bodies, responses, schemas, operation ids, tags and extensions that `/v3/api-docs` of that release
  returns

#### Scenario: The file is regenerated from code

- **WHEN** a developer changes a controller or DTO annotation and runs `./gradlew updateOpenApi`
- **THEN** `docs/openapi.json` is rewritten from the document the changed code serves, and no other file
  changes

### Requirement: The committed document is normalised to be byte-stable

The committed document SHALL be normalised so that the same code always gives the same bytes, whatever the
host, port, JVM or bean scan order. Every JSON object's keys SHALL be sorted lexicographically, and array
order SHALL be kept as springdoc emits it. The top-level `servers` member SHALL be removed. The file SHALL be
UTF-8 JSON, pretty-printed with two-space indentation, and end with a single newline. Normalisation SHALL NOT
add, drop or rewrite any other member, so the file is semantically equal to the served document apart from
`servers`.

#### Scenario: Two regenerations of unchanged code

- **WHEN** `./gradlew updateOpenApi` runs twice on the same commit, on different machines or ports
- **THEN** both runs write byte-identical files

#### Scenario: The servers member is not committed

- **WHEN** the served document carries a `servers` entry naming the host and port of the test server
- **THEN** the committed file has no top-level `servers` member

### Requirement: The build fails when the committed document drifts from the code

`./gradlew build` SHALL include a check that compares the normalised served document with
`docs/openapi.json` and fails when they differ. The failure message SHALL name the regenerate command
`./gradlew updateOpenApi` and the first JSON path at which the two documents differ. The check SHALL also fail
when `docs/openapi.json` is missing. The check SHALL run without ClickHouse and without credentials: it
reads the document in-process from the same Spring context the existing PostgreSQL-backed integration tests
use.

#### Scenario: An annotation change without regeneration

- **WHEN** a change edits an `@Operation` summary, adds a request parameter, or renames a DTO field, and
  does not regenerate `docs/openapi.json`
- **THEN** `./gradlew build` fails, naming `./gradlew updateOpenApi` and the JSON path of the first difference

#### Scenario: A regenerated change passes

- **WHEN** the same change also commits the file `./gradlew updateOpenApi` wrote
- **THEN** the drift check passes

#### Scenario: The file is deleted

- **WHEN** `docs/openapi.json` does not exist
- **THEN** `./gradlew build` fails, naming `./gradlew updateOpenApi`

### Requirement: The document does not depend on deployment configuration

The served document SHALL have the same paths, operations, schemas, operation ids, tags, extensions and
security schemes in every deployment that serves it, whatever the authentication mode, the
`DISABLE_SWAGGER_AUTHORIZATION` setting, or feature and integration settings. A handler SHALL NOT be added to
or removed from the document by a configuration property. Configuration decides only whether the document is
served (`API_DOCS_ENABLED`) and whether reading it needs a credential (owned by the `authentication`
capability). That is what lets one committed file stand for every deployment of a release.

#### Scenario: Authentication mode does not change the document

- **WHEN** the document is read from an instance in `none` mode and from an instance of the same release in
  `oidc` mode with a valid credential
- **THEN** both documents normalise to the same bytes as the release's `docs/openapi.json`

### Requirement: Every operation has an explicit, unique, stable operation id

Every operation in the document SHALL carry an `operationId` declared explicitly on its handler, never derived
from the Java method name. Ids SHALL be lowerCamelCase, begin with a verb (`list`, `get`, `create`,
`replace`, `update`, `delete`, `execute`, `translate`, `validate`, `preview`, `export`, `import` and the like)
and name their resource (`listPipelines`, `getDashboard`, `executeSqlCsv`). They SHALL be unique across the
whole document. No id SHALL end in the `_<digits>` suffix springdoc appends on a collision. Once released, an
id SHALL change only together with a breaking change to that operation, because a generated client's
method names come from it. The build SHALL fail when any of these rules is broken, and the failure SHALL name
the offending handler.

#### Scenario: A new handler without an explicit id

- **WHEN** a change adds a handler whose `@Operation` declares no `operationId`
- **THEN** `./gradlew build` fails, naming the controller class and method

#### Scenario: Two handlers declare the same id

- **WHEN** two handlers declare the same `operationId`
- **THEN** `./gradlew build` fails, naming both handlers

#### Scenario: Handlers that share a Java method name

- **WHEN** the document is read for the dashboard, saved-query and table handlers whose Java methods are all
  named `create`
- **THEN** each publishes its own resource-named id (for example `createDashboard`, `createSavedQuery`,
  `createTable`) and none carries a `_<digits>` suffix

### Requirement: Every operation carries exactly one tag

Every operation in the document SHALL carry exactly one tag, and every tag in use SHALL be declared in the
document's top-level `tags` with a description. The tag groups the operation by the resource or surface it
serves, so a generator emits one client class per resource. Every declaration of the same tag name SHALL carry
the same description, so the published description doesn't depend on scan order. Tag names SHALL name the
resource only. Stability notes such as "experimental" belong in the description, not the name. The query
surfaces under `/v1/queries` (structured query, SQL, CSV export, translation, SQL validation and schema
discovery) SHALL share the tag `Queries`. The build SHALL fail on an operation with no tag or with more than
one tag, and on a tag name declared with two different descriptions.

#### Scenario: A controller without a tag

- **WHEN** a change adds a controller with no `@Tag`, so its operations publish no tag
- **THEN** `./gradlew build` fails, naming the operations without a tag

#### Scenario: The query surfaces share the Queries tag

- **WHEN** the document is read for `POST /v1/queries/execute`, `POST /v1/queries/execute-sql`,
  `POST /v1/queries/execute-csv`, `POST /v1/queries/translate` and the `/v1/queries` schema discovery reads
- **THEN** each carries exactly the tag `Queries`, the top-level `tags` declares `Queries` once with a
  description that states the surface is experimental, and no operation carries
  `Structured Queries (experimental)`

#### Scenario: One tag name declared with two descriptions

- **WHEN** two controllers declare `@Tag` with the same name and different descriptions
- **THEN** `./gradlew build` fails, naming both controllers

### Requirement: Non-client operations are marked internal

Operations meant for machine producers rather than API clients SHALL carry the operation-level extension
`x-internal: true`. All other operations SHALL NOT carry it. These operations are the OTLP/HTTP receivers
under `/v1/otel/sink/{sinkName}/v1/*`, which OpenTelemetry exporters call, and the Enrichment Runner data plane
under `/v1/pipelines/{name}`, the contract-header-guarded `POST` handlers other than `preview` and `refresh`.
Marked operations SHALL stay in the served document and in the Swagger UI, because operators still exercise
them there. The marking only tells client generators and renderers they may skip them. The build SHALL fail
when a runner data-plane handler or an OTLP ingest handler is not marked, or when any other handler is.

#### Scenario: The OTLP receivers are marked

- **WHEN** the document is read for `POST /v1/otel/sink/{sinkName}/v1/logs`, `.../traces` and `.../metrics`
- **THEN** each operation carries `x-internal: true`

#### Scenario: The runner data plane is marked, operator surfaces are not

- **WHEN** the document is read for the `POST` handlers under `/v1/pipelines/{name}`
- **THEN** `scan`, `group-rows`, `materialize` and `rows` carry `x-internal: true`, and `preview` and
  `refresh` do not

#### Scenario: Client operations are not marked

- **WHEN** the document is read for `GET /v1/pipelines`, `POST /v1/queries/execute` or `POST /v1/otel/sinks`
- **THEN** none of them carries `x-internal`

#### Scenario: A new runner endpoint is added unmarked

- **WHEN** a change adds a contract-guarded `POST` handler under `/v1/pipelines/{name}` without the
  `x-internal` marking
- **THEN** `./gradlew build` fails, naming the handler

### Requirement: Preview operations are marked x-preview

Operations that are part of the client API but may still change SHALL carry the operation-level extension
`x-preview: true`, published as a JSON boolean. These are exactly the operations tagged `Queries`: structured
query, SQL, CSV export, translation, SQL validation and schema discovery under `/v1/queries`. All other
operations SHALL NOT carry it. This is the extension DIAL Core uses for endpoints still under development, so
a client generator built against both services can treat ADAS preview operations the same way. Declaring a
`/v1/queries` operation stable means removing its `x-preview` in a change that also updates this requirement.
No operation SHALL carry both `x-preview` and `x-internal`. The build SHALL fail when a `Queries` operation is
not marked, when any other operation is marked, or when an operation carries both extensions.

#### Scenario: The query surfaces are marked preview

- **WHEN** the document is read for `POST /v1/queries/execute`, `POST /v1/queries/execute-sql`,
  `POST /v1/queries/translate` and `GET /v1/queries/entities`
- **THEN** each operation carries `x-preview: true` as a boolean, not as the string `"true"`

#### Scenario: Stable client operations are not marked preview

- **WHEN** the document is read for `GET /v1/pipelines`, `POST /v1/saved-queries` or `GET /v1/dashboards`
- **THEN** none of them carries `x-preview`

#### Scenario: A new query endpoint is preview by construction

- **WHEN** a change adds a handler to a controller tagged `Queries` without the `x-preview` marking
- **THEN** `./gradlew build` fails, naming the handler

#### Scenario: An operation marked both preview and internal

- **WHEN** a handler carries both `x-preview` and `x-internal`
- **THEN** `./gradlew build` fails, naming the handler
