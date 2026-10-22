## MODIFIED Requirements

### Requirement: Export dependency closure

The export SHALL include the dependency closure of the selected components, computed in both directions: a selected table SHALL pull
in its `source_table` and every pipeline that targets it; a selected pipeline SHALL pull in its inputs and its target; and both SHALL
pull in every enrichment a pipeline reads, including enrichments referenced only as `"<enrichment>.<column>"` tokens inside filter
and measure expressions, or inside an `aggregate` pipeline's `sql` statement.

For an `aggregate` pipeline in SQL mode, its inputs SHALL be the input table derived from the statement, and the enrichments it reads
SHALL be found by scanning the stored statement for qualified tokens — the same scan the registry's dependency guards use — rather than
by parsing it. A statement that does not parse SHALL therefore not make its pipeline `unresolvable`: a disabled draft whose `sql` cannot
yet be read is exported with whatever its tokens reference, and with no input when none could be derived.

#### Scenario: Enrichment pulls in its source

- **WHEN** the selection is only the enrichment table `usage_sentiment`
- **THEN** the bundle also holds its source table and the pipeline that targets `usage_sentiment`

#### Scenario: Enrichment referenced only inside a filter

- **WHEN** the selection is an aggregate pipeline whose only reference to enrichment `usage_sentiment` is the filter token
  `"usage_sentiment.sentiment"`
- **THEN** the bundle holds `usage_sentiment` and the pipeline that targets it

#### Scenario: Enrichment referenced only inside an SQL statement

- **WHEN** the selection is an aggregate pipeline in SQL mode whose statement reads `dial_usage_log` and references enrichment
  `usage_sentiment` only as `"usage_sentiment.sentiment"` in its select list
- **THEN** the bundle holds `usage_sentiment` and the pipeline that targets it, and `required_system_tables` names `dial_usage_log`

#### Scenario: An unparseable SQL draft is exported, not skipped

- **WHEN** an export of everything reaches a disabled aggregate pipeline whose stored `sql` does not parse
- **THEN** the pipeline is in the bundle with its `sql` verbatim, and is not listed under `skipped` with `reason` `unresolvable`

### Requirement: Bundle format and version compatibility

The bundle SHALL carry `format: "adas-catalog"`, an integer `format_version` (currently `2`), `exported_at`, `skipped`,
`required_system_tables`, `tables` (each `{ table, schema }` in request shape) and `pipelines` (each
`{ pipeline, exported_enabled }`).

The bundle SHALL carry no provenance of the exporting ADAS beyond `exported_at`. A bundle SHALL import into the ADAS version that
exported it or any newer one. `format_version` SHALL be raised whenever the shape of any bundle member changes, including a new enum value.

Version `2` adds the `aggregate` declaration's `sql` member. An export SHALL always write the current version, whether or not any
pipeline it carries uses `sql`, so a bundle never claims a version by its content. An ADAS that reads only version `1` therefore refuses
every bundle a version-`2` ADAS writes with the readable `catalog_bundle_version_unsupported` refusal below, rather than the strict
binding's unknown-field `400` that a bundle carrying `sql` under version `1` would meet. A version-`1` bundle SHALL still import into
a version-`2` ADAS unchanged: it carries no `sql`, and every pipeline in it is in form mode.

Import SHALL parse in two stages. Stage 1 SHALL read only `format` and `format_version`, leniently: a wrong or missing `format` SHALL
be `400`, and a `format_version` above the highest supported SHALL be `422` with `error` `catalog_bundle_version_unsupported`, whose
message names the bundle's version. Stage 2 SHALL bind the whole bundle strictly: unreadable JSON, an unknown field at any depth,
an unknown enum value, or a constraint violation SHALL be `400`.

#### Scenario: Bundle from a newer ADAS

- **WHEN** a bundle with `format_version: 3` that also carries unknown fields is uploaded to an ADAS that supports version 2
- **THEN** the response is `422` with `error` `catalog_bundle_version_unsupported`, and the message names version 3

#### Scenario: A bundle carrying sql is refused readably by an older ADAS

- **WHEN** a `format_version: 2` bundle holding an aggregate pipeline with `sql` is uploaded to an ADAS that supports only version 1
- **THEN** the response is `422` with `error` `catalog_bundle_version_unsupported` naming version 2, not a `400` naming `sql`

#### Scenario: A version 1 bundle still imports

- **WHEN** a `format_version: 1` bundle exported before the bump is uploaded to an ADAS that supports version 2
- **THEN** it is parsed and planned as before, and each of its aggregate pipelines is imported in form mode

#### Scenario: Unknown nested field

- **WHEN** a `format_version: 2` bundle carries an unknown field inside a pipeline's transform
- **THEN** the response is `400`

#### Scenario: Missing required nested field

- **WHEN** a `format_version: 2` bundle has a table entry whose `table.name` is blank
- **THEN** the response is `400`

### Requirement: Pipelines travel in source projection

The bundle SHALL carry each pipeline as its `view=source` projection, without `generation`, `state`, `enabled`, timestamps, `notes`
and runtime state, and with `exported_enabled` recording whether it was enabled on the source.

A transform declared with legacy `output_vars` SHALL be carried and imported with `output_vars` unchanged, never converted to
`outputs`. An absent `transform.inputs` SHALL stay absent.

An `aggregate` pipeline in SQL mode SHALL be carried with its `sql` exactly as stored — the text the author sent, never a re-rendered
statement — and without `group_by`, `measures` or `filter`, and SHALL be imported with the same text. The registration path the import
uses stores `sql` as a disabled write does, so a statement that does not parse on the target still lands, disabled, as the draft it was.
Its `inputs` SHALL carry the stored input table — the one the source derived from the statement when `inputs` was not declared —
since the registry keeps no record of whether it was declared; the target accepts it because it equals the statement's source, and
derives the input table from the statement when `inputs` is absent, as registration does.

#### Scenario: Legacy output_vars preserved

- **WHEN** a pipeline whose transform declares `output_vars` is exported and imported
- **THEN** the imported pipeline's `view=source` projection declares the same `output_vars` and no `outputs`

#### Scenario: An SQL declaration round-trips verbatim

- **WHEN** an aggregate pipeline declared with an `sql` statement carrying comments, a `WITH` clause and its author's line breaks is
  exported and imported
- **THEN** the imported pipeline's `view=source` projection carries the same `sql`, character for character, and no `group_by`,
  `measures` or `filter`, and it is disabled

### Requirement: Environment-specific values reported

The import preview and result SHALL list under `env_specific` every environment-specific value in the bundle: pipeline
`transform.model`, `advanced.rate_rpm`, cron triggers, absolute date literals in filters and in an `aggregate` pipeline's `sql`, and
table `ttl`. Import SHALL NOT validate `transform.model` against the target. A date literal in `sql` SHALL be reported under the field
`sql`, found by the same literal scan as a filter's, so a statement's `WHERE request_time >= '2026-08-05'` is as visible to the operator
as the same predicate written as a filter.

#### Scenario: Model listed

- **WHEN** a bundle pipeline declares `transform.model: gemini-2.5-flash-lite`
- **THEN** `env_specific` holds an entry naming that pipeline, the field `transform.model` and the value

#### Scenario: Date literal in an SQL statement listed

- **WHEN** a bundle aggregate pipeline's `sql` carries `WHERE request_time >= '2026-08-05'`
- **THEN** `env_specific` holds an entry naming that pipeline, the field `sql` and the value `2026-08-05`


## ADDED Requirements

### Requirement: An SQL declaration's import dependencies are read from its statement

Import SHALL resolve an `aggregate` pipeline's dependencies in SQL mode from its `sql`: the input table its `FROM` names, plus every
enrichment it references as a `"<enrichment>.<column>"` token, alongside its `target` and any `inputs` it declares. These are the
tables the pipeline cannot be created or armed without, and they SHALL drive every rule that asks for a pipeline's dependencies exactly
as a form-mode declaration's do: a pipeline depending on a refused object is `FAIL` naming that dependency, a dependency on a system
table is checked as a required system table, and the enrichments it reads are created before it under the import order.

Without this an SQL-mode pipeline, which usually declares no `inputs` and carries its enrichment references inside one string, would
look to the planner as if it depended on its target alone, and would be planned `CREATE` over a refused source, failing at apply rather
than in the preview.

The enrichment references SHALL be found by the qualified-token scan the export closure uses, which needs no parse. The input table
SHALL be read from the statement where it can be; a statement that does not parse SHALL contribute its tokens only and SHALL NOT by
itself make the pipeline `FAIL`, because the pipeline imports disabled and its own arming is what asks for a readable statement.

A name the statement's `FROM` reads SHALL count as a dependency only when it names a table the bundle carries or lists (under `tables`
or `required_system_tables`) or a table that exists on the target environment. Any other `FROM` name, a CTE's own name for instance,
SHALL be left to the arm gate: it SHALL NOT make the pipeline `FAIL` at import, and the validation pass reports it through `armable`
and `arm_problems`.

Every name the statement contributes — a `FROM` table, a CTE name, an enrichment token — SHALL be compared lowercased, quoted or not,
because the SQL front end resolves names case-insensitively: `FROM "Dial_Usage_Log"` reads `dial_usage_log`, and
`"Usage_Sentiment.sentiment"` references `usage_sentiment`.

#### Scenario: A pipeline whose statement reads a refused source fails in preview

- **WHEN** `/import/preview` runs with a bundle whose table `usage_base` is refused and whose SQL-mode aggregate pipeline declares no
  `inputs` and reads `usage_base` in its statement
- **THEN** the pipeline entry is `FAIL` naming `usage_base` as its dependency

#### Scenario: An enrichment referenced only in the statement is a dependency

- **WHEN** a bundle's SQL-mode aggregate pipeline references `"usage_sentiment.sentiment"` only inside its `sql`, and `usage_sentiment`
  is refused
- **THEN** the pipeline entry is `FAIL` naming `usage_sentiment`

#### Scenario: A system input read by the statement is a required system table

- **WHEN** a bundle's SQL-mode aggregate pipeline reads `dial_usage_log` in its statement and declares no `inputs`, the bundle lists
  `dial_usage_log` under `required_system_tables`, and the target environment lacks `dial_usage_log`
- **THEN** the pipeline entry is `FAIL` naming `dial_usage_log`, as it is for a form-mode pipeline declaring it as `inputs`

#### Scenario: A quoted mixed-case name is matched case-insensitively

- **WHEN** a bundle's SQL-mode aggregate pipeline reads `FROM "Dial_Usage_Log"`, the bundle lists `dial_usage_log` under
  `required_system_tables`, and the target environment lacks `dial_usage_log`
- **THEN** the pipeline entry is `FAIL` naming `dial_usage_log`

#### Scenario: A FROM name the bundle and the target do not know is left to the arm gate

- **WHEN** a bundle's SQL-mode aggregate pipeline reads `usage_missing` in its `FROM`, the bundle neither carries nor lists
  `usage_missing`, and the target environment has no such table
- **THEN** the pipeline entry is not `FAIL` on account of `usage_missing`, and it reports `armable: false` with an `arm_problems`
  entry

#### Scenario: An unparseable statement does not fail the import by itself

- **WHEN** a bundle's SQL-mode aggregate pipeline carries an `sql` that does not parse, and every table its tokens reference is
  importable
- **THEN** the pipeline entry is `CREATE`, it is imported disabled with its `sql` verbatim, and its `armable` is `false`
