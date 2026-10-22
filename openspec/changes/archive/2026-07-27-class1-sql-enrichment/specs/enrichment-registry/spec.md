## ADDED Requirements

### Requirement: Runner identity and derived-column sensitivity posture

The Enrichment Runner SHALL operate as `FULL_ADMIN` for any rule that references a `sensitive` source
column. This follows from the access model: `SensitiveColumnAccess` admits `FULL_ADMIN` (or any caller
when `security.mode` is `none`), with no narrower grant available, and the rule is applied both at
rule registration and again at every read/derive egress against the **calling** identity.
This **supersedes the original least-privilege stance** ("per-target grants only, no global
admin") for those rules; rules touching no sensitive column are unaffected and continue to work with a
per-target grant alone. What still bounds a `FULL_ADMIN` runner is that the surface stays rule-scoped
in both directions: a scan projects only rule-referenced columns, a group fetch returns only the
rule's membership, and a write-back rejects any column outside the rule's declared outputs — the wider
role changes *which columns a rule may reference*, not *which rules the runner may operate*. A
narrower per-rule or service-identity sensitive grant is a named deferred follow-up.

Derived enrichment columns SHALL NOT inherit sensitivity. A rule whose filter, input bindings, or
`sql` expressions reference a `sensitive` source column and whose output bindings target a
**non-`sensitive`** enrichment column SHALL be accepted as registered: the system SHALL NOT mark the
target column `sensitive` as a side effect, and SHALL NOT reject the rule. Rule registration never
mutates catalog metadata, because doing so would silently change which callers can read an existing
column. The consequence is explicit and accepted: a value derived from sensitive data can be readable
by callers who may not read its source. Controlling that is the enrichment author's decision, taken by
declaring the target column `sensitive` when defining the enrichment's schema
(`POST /v1/tables/{name}/schema`) or by patching it later (`PATCH /v1/tables/{name}/schema`).

#### Scenario: A rule deriving a non-sensitive column from a sensitive source is accepted unchanged

- **WHEN** a `FULL_ADMIN` caller registers a rule whose expression or filter reads a `sensitive`
  source column and whose output binding targets a non-`sensitive` enrichment column
- **THEN** the rule is accepted, and the target column's `sensitive` flag is left exactly as the table
  author declared it

#### Scenario: A non-entitled runner cannot operate a sensitive-referencing rule

- **WHEN** a caller who may not see sensitive columns invokes any rule-scoped read or derive path for
  a rule referencing a `sensitive` source column
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

## MODIFIED Requirements

### Requirement: Register an evaluator with immutable versioning

The system SHALL register evaluators at `POST /v1/evaluators` from a body carrying
`{name, type, preset?, model?, params, request_template?, input_vars, response_schema?,
output_vars}`. Evaluator definitions SHALL be stored across two tables: `evaluator(name PK,
latest_version, …)` and `evaluator_version(name, version, …, PK(name,version))`. Registering a
`name` that does not yet exist SHALL create version `1`; registering an existing `name` SHALL
create a **new version** (`max(version)+1`) and never mutate an existing version row. `latest`
SHALL resolve to `max(version)`. There SHALL be no update or delete of an existing version; an
attempt SHALL be rejected with HTTP 409 `evaluator_immutable`.

`type` SHALL be one of `llm` (a model call, the original path) or `sql` (a deterministic derivation
computed in the database, see [class1-enrichment](../class1-enrichment/spec.md)). The registration
shape SHALL be dispatched on `type`:

- **`llm`** — `preset` and `model` SHALL be required and non-blank, and the only accepted `preset`
  SHALL be `chat_completion`. Output variables carry `jsonata`. This is the existing behaviour, unchanged.
- **`sql`** — `preset`, `model`, `request_template`, `input_vars`, and `response_schema` SHALL be
  absent, and every entry of `output_vars` SHALL carry a non-blank `sql` expression together with a
  logical `type`.

A body violating its type's shape SHALL be rejected with HTTP 422 `rule_validation_failed`.

#### Scenario: First registration creates version 1

- **WHEN** `POST /v1/evaluators` is called with a `name` that does not exist
- **THEN** the evaluator is created at version `1`, `latest_version=1`, and HTTP 201 is returned

#### Scenario: Re-registering a name creates a new immutable version

- **WHEN** `POST /v1/evaluators` is called with an existing `name` and edited fields
- **THEN** a new `evaluator_version` row is created at `max(version)+1`, prior versions are
  unchanged, and `latest_version` advances

#### Scenario: Mutating or deleting an existing version is rejected

- **WHEN** a request attempts to update or delete an already-stored evaluator version
- **THEN** the request is rejected with HTTP 409 and error code `evaluator_immutable`

#### Scenario: A non-`chat_completion` preset is rejected

- **WHEN** `POST /v1/evaluators` is called with a `preset` other than `chat_completion`
- **THEN** the request is rejected with a 4xx client error (the bounded `preset` enum rejects an
  unsupported value at request binding, HTTP 400)

#### Scenario: An llm evaluator without model or preset is rejected

- **WHEN** `POST /v1/evaluators` is called with `type: "llm"` and a blank or absent `model` or `preset`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sql evaluator registers without LLM-only fields

- **WHEN** `POST /v1/evaluators` is called with `type: "sql"`, no `model`/`preset`/`request_template`/
  `response_schema`, and output variables each carrying `sql` and `type`
- **THEN** the evaluator version is created and HTTP 201 is returned

### Requirement: Static governance gate on register and enable

The system SHALL statically validate a rule on register and on enable; each failure SHALL be
rejected with HTTP 422 `rule_validation_failed` (except entitlement and immutability, which use
their own codes) with detail identifying the failing check. The gate SHALL enforce: (1) the
`target_enrichment` exists, is `ACTIVE`, and is kind=enrichment, and every output-binding column
exists on it with a type matching the output-var type via the shared JSON→ClickHouse type-mapping
util; (2) `filter_sql` parses under the existing Calcite SQL front-end, is boolean over declared
source columns, uses the bounded grammar (no joins/subqueries/CTEs), with referenced columns
extracted; (3) the evaluator resolves; (4) trigger and cron are consistent; (5) the evaluator-type
specific checks below.

The gate SHALL be **dispatched on the resolved evaluator's `type`**:

- **`llm`** — the resolved evaluator SHALL carry a non-blank `model`; all input/output JSONata
  expressions SHALL parse (static parse only — runtime evaluation is the runner's); the trigger SHALL
  be row-grain (`on_ingest` or `schedule`) unless the rule uses the group capability.
- **`sql`** — `model` SHALL NOT be required and JSONata SHALL NOT be parsed (there is none); every
  output variable's `sql` expression SHALL pass Class-1 expression governance (see
  [class1-enrichment](../class1-enrichment/spec.md)); the rule SHALL declare no input bindings; and
  the `group` trigger SHALL be rejected.

Note (check 3): the gate does NOT verify that an `llm` evaluator's `model` names a live DIAL
deployment — ADAS has no deployment-listing client to reuse (only caller-key introspection). Live
deployment verification is deferred; only evaluator resolution and a non-blank model are enforced here.

#### Scenario: Bad output binding column or type is rejected

- **WHEN** a rule declares an output binding to a column absent on the target enrichment or with a
  mismatched type
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unparseable or unbounded filter is rejected

- **WHEN** `filter_sql` fails to parse, is not boolean, references undeclared columns, or uses a
  join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unresolvable evaluator or missing model on an llm rule is rejected

- **WHEN** the rule's evaluator (name/version) does not resolve, or the resolved `llm` evaluator has a
  blank `model`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Disallowed trigger kind is rejected

- **WHEN** a rule declares a `trigger_kind` the registry does not accept (e.g. `push`), or declares
  `group` for a `type=sql` evaluator
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unparseable JSONata expression is rejected

- **WHEN** any input or output JSONata expression of an `llm` rule fails to parse
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sql rule is validated without a model or JSONata

- **WHEN** a rule binds a `type=sql` evaluator that carries no `model` and no JSONata
- **THEN** the model and JSONata checks are skipped, the `sql` expressions are validated instead, and
  a well-formed rule is accepted
