## ADDED Requirements

### Requirement: Register an evaluator with immutable versioning

The system SHALL register evaluators at `POST /v1/evaluators` from a body carrying
`{name, type:"llm", preset, model, params, request_template, input_vars, response_schema,
output_vars}`. Evaluator definitions SHALL be stored across two tables: `evaluator(name PK,
latest_version, …)` and `evaluator_version(name, version, …, PK(name,version))`. Registering a
`name` that does not yet exist SHALL create version `1`; registering an existing `name` SHALL
create a **new version** (`max(version)+1`) and never mutate an existing version row. `latest`
SHALL resolve to `max(version)`. There SHALL be no update or delete of an existing version; an
attempt SHALL be rejected with HTTP 409 `evaluator_immutable`. Initially the only accepted
`preset` SHALL be `chat_completion`.

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

### Requirement: Read evaluators

The system SHALL expose `GET /v1/evaluators` (list), `GET /v1/evaluators/{name}` (latest version
resolved), and `GET /v1/evaluators/{name}/versions/{v}` (a specific version). An unknown name or
version SHALL be rejected with HTTP 404 `unknown_evaluator`.

#### Scenario: Read the latest version of an evaluator

- **WHEN** `GET /v1/evaluators/{name}` is called for an existing evaluator
- **THEN** the response returns the evaluator with its `latest_version` fields resolved

#### Scenario: Read a specific version

- **WHEN** `GET /v1/evaluators/{name}/versions/{v}` is called for an existing version
- **THEN** the response returns exactly that version's stored definition

#### Scenario: Unknown evaluator is rejected

- **WHEN** a read targets a `name` or `version` that does not exist
- **THEN** the request is rejected with HTTP 404 and error code `unknown_evaluator`

### Requirement: Rule CRUD binding an evaluator to a target enrichment

The system SHALL manage rules via `POST /v1/rules`, `PUT /v1/rules/{id}`, `DELETE /v1/rules/{id}`,
and `GET /v1/rules/{id}`. A rule SHALL bind an `evaluator_name` (+ optional `evaluator_version`,
null meaning `latest`) to a `target_enrichment`, plus `trigger_kind`, optional `trigger_cron`,
`filter_sql`, `input_bindings`, `output_bindings`, and execution knobs (`sampling`, `cadence`,
`batch_scan_limit`, `batch_chunk`, `rate_rpm`, `priority`, `enabled`). The rule's **scan source
and grain SHALL be derived** from the target enrichment's `parent`/`grain` and SHALL NOT be
re-declared on the rule. Each mutating rule operation SHALL bump a `generation` counter and
`updated_at`. An unknown rule id SHALL be rejected with HTTP 404 `unknown_rule`.

#### Scenario: Create a rule derives its source and grain

- **WHEN** `POST /v1/rules` is called with a valid evaluator and `target_enrichment`
- **THEN** the rule is created with `source`/`grain` derived from the target enrichment, `enabled`
  per the request, `generation` initialized, and HTTP 201 returned

#### Scenario: Update bumps generation

- **WHEN** `PUT /v1/rules/{id}` changes a rule
- **THEN** the stored rule is updated, `generation` is incremented, and `updated_at` advances

#### Scenario: Delete removes the rule

- **WHEN** `DELETE /v1/rules/{id}` is called for an existing rule
- **THEN** the rule is removed and HTTP 204 returned

#### Scenario: Unknown rule is rejected

- **WHEN** any rule read/update/delete targets an id that does not exist
- **THEN** the request is rejected with HTTP 404 and error code `unknown_rule`

### Requirement: Runner sync feed with resolved evaluator and change detection

The system SHALL expose `GET /v1/rules[?updated_since=…]` returning each **enabled** rule (the feed
is enabled-only; disabled rules are never returned) with its **resolved pinned evaluator inlined**
(the concrete version, resolving `latest` at sync time), the derived source/grain, and a
`generation` value the runner uses for change detection (surfaced as an ETag). `updated_since` SHALL
filter to rules changed at or after the given timestamp.

#### Scenario: Sync returns enabled rules with inlined evaluators

- **WHEN** `GET /v1/rules` is called
- **THEN** each enabled rule is returned with its resolved evaluator version inlined, derived
  source/grain, and a `generation`

#### Scenario: Incremental sync filters by updated_since

- **WHEN** `GET /v1/rules?updated_since=<t>` is called
- **THEN** only enabled rules with `updated_at >= t` are returned

### Requirement: Static governance gate on register and enable

The system SHALL statically validate a rule on register and on enable; each failure SHALL be
rejected with HTTP 422 `rule_validation_failed` (except entitlement and immutability, which use
their own codes) with detail identifying the failing check. The gate SHALL enforce: (1) the
`target_enrichment` exists, is `ACTIVE`, and is kind=enrichment, and every output-binding column
exists on it with a type matching the output-var type via the shared JSON→ClickHouse type-mapping
util; (2) `filter_sql` parses under the existing Calcite SQL front-end, is boolean over declared
source columns, uses the bounded grammar (no joins/subqueries/CTEs), with referenced columns
extracted; (4) the evaluator resolves and carries a non-blank `model`; (5) `trigger_kind ∈
{on_ingest, schedule}` and row-grain only; (6) all input/output JSONata expressions parse (static
parse only — runtime evaluation is the runner's).

Note (check 4): the gate does NOT verify the `model` names a live DIAL deployment — ADAS
has no deployment-listing client to reuse (only caller-key introspection). Live deployment
verification is deferred; only evaluator resolution and a non-blank model are enforced here.

#### Scenario: Bad output binding column or type is rejected

- **WHEN** a rule declares an output binding to a column absent on the target enrichment or with a
  mismatched type
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unparseable or unbounded filter is rejected

- **WHEN** `filter_sql` fails to parse, is not boolean, references undeclared columns, or uses a
  join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unresolvable evaluator or missing model is rejected

- **WHEN** the rule's evaluator (name/version) does not resolve, or the resolved evaluator has a
  blank `model`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Disallowed trigger kind is rejected

- **WHEN** a rule declares `trigger_kind` of `group` or `push` (or is not row-grain)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unparseable JSONata expression is rejected

- **WHEN** any input or output JSONata expression fails to parse
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

### Requirement: Sensitive-column entitlement

The rule's `filter` and `bound` input bindings SHALL reference only columns the caller is
permitted to use. Any column flagged `sensitive` SHALL require an explicit grant to be usable in a
rule; a rule referencing a `sensitive` column without such a grant SHALL be rejected with HTTP 403
`sensitive_column_not_entitled`.

Note: no separate sensitive-column grant store exists yet, so the check is **deny-by-
default** — any reference to a `sensitive` source column is rejected. When a grant store lands, the
check SHALL consult it and admit granted references.

#### Scenario: Sensitive reference is rejected (deny-by-default)

- **WHEN** a rule's filter or a bound input binding references a `sensitive`-flagged source column
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: Non-sensitive reference is accepted

- **WHEN** a rule's filter and bound bindings reference only non-`sensitive` source columns
- **THEN** the entitlement check passes

### Requirement: One rule per target enrichment

The system SHALL enforce at most one rule per `target_enrichment` via a database
`UNIQUE(target_enrichment)` constraint. A second rule targeting an enrichment that already has one
SHALL be rejected with HTTP 409 `rule_validation_failed`. This is because enrichment tables are
`ReplacingMergeTree` with full-row replace by grain, so two rules writing the same grain would
clobber each other's columns; multi-label is expressed as one evaluator emitting multiple output
columns.

#### Scenario: Second rule on the same target is rejected

- **WHEN** `POST /v1/rules` targets a `target_enrichment` that already has a rule
- **THEN** the request is rejected with HTTP 409 and error code `rule_validation_failed`
