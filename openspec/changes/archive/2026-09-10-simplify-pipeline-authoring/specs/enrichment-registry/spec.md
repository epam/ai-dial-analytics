## MODIFIED Requirements

### Requirement: Register an evaluator with immutable versioning

The system SHALL register evaluators at `POST /v1/evaluators` from a body carrying
`{name, type, preset?, model?, params, request_template?, outputs}`. Evaluator definitions SHALL be
stored across two tables: `evaluator(name PK,
latest_version, …)` and `evaluator_version(name, version, …, PK(name,version))`. Registering a
`name` that does not yet exist SHALL create version `1`; registering an existing `name` SHALL
create a **new version** (`max(version)+1`) and never mutate an existing version row. `latest`
SHALL resolve to `max(version)`. There SHALL be no update or delete of an existing version; an
attempt SHALL be rejected with HTTP 409 `evaluator_immutable`.

**`outputs`** SHALL be the evaluator's single declaration of what it produces: a non-empty map of
output name → the output's declaration, keyed by the **target column that receives it**. It replaces
`input_vars`, `response_schema` and `output_vars`, each of which SHALL NO LONGER be accepted — an
unrecognized member is refused at the binding with HTTP 400 `bad_request` naming the field. The
`outputs` **order is load-bearing** (it is the composed response schema's property order, hence the
model's generation order), so it SHALL be preserved from the request body and SHALL be stored as an
**ordered array**, never as a JSON object whose key order the storage engine may normalize.

`type` SHALL be one of `llm` (a model call) or `sql` (a deterministic derivation computed in the
database, see [class1-enrichment](../class1-enrichment/spec.md)). `type` SHALL remain **required**:
it selects the *grammar* of an `outputs` value, both grammars are a string on the wire, and there is
no safe inference in either direction — a `sql` evaluator whose `type` went missing would have its
expressions silently read as prose and composed into a response schema. A body carrying no `type`
SHALL be rejected with HTTP 400, and SHALL NOT be defaulted to `llm` from the presence or absence of
any other member. The registration shape SHALL be dispatched on `type`:

- **`llm`** — `model` SHALL be required and non-blank. `preset` SHALL be **optional**, defaulting to
  `chat_completion`, which is its only accepted value. Each `outputs` value SHALL be either a
  **string** (the prose the model is told about that output) or an **object** `{prose, values?,
  jsonata?}` carrying non-blank `prose` and **at most one** of `values` or `jsonata`. An unknown
  member inside that object SHALL be rejected with HTTP 400 naming it, exactly as at the top level.
- **`sql`** — `preset`, `model`, `request_template` and `params` SHALL be absent, and each `outputs`
  value SHALL be a non-blank SQL expression string. A per-output logical `type` SHALL NOT be
  declared; see [class1-enrichment](../class1-enrichment/spec.md).

`values` SHALL declare a closed domain for an output whose target column cannot carry one; whether the
column can carry it is a composition-time question, answered by "The response schema is composed from
the target and the evaluator". `jsonata` SHALL transform the model's field before it reaches the
column, and SHALL be rejected at registration with HTTP 422 when it is the identity expression (the
output's own name), because an absent `jsonata` already means a direct field lookup by name.

A body violating its type's shape SHALL be rejected with HTTP 422 `pipeline_validation_failed`.

An `outputs` declaration SHALL NOT be validated against any target at registration: composition
needs a target, and an evaluator names none. Where the evaluator already has dependent pipelines,
registration re-validates against them — see "Registering an evaluator version re-validates its
unpinned dependent pipelines".

The code is `pipeline_validation_failed` rather than the `rule_validation_failed` it was, and the rename
reaches this requirement for a reason worth stating rather than leaving to be rediscovered: evaluator
validation has always raised the **same** exception the declaration gate does, so it has always reported
under the declaration's code. That coupling predates the fold and is not introduced by it; what the fold
changes is the code's spelling. Giving evaluators a code of their own would be new API surface and is not
part of this change.

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

#### Scenario: An omitted preset defaults to chat_completion

- **WHEN** `POST /v1/evaluators` is called with `type: "llm"`, a non-blank `model` and no `preset`
- **THEN** the evaluator version is created with `preset: "chat_completion"` and HTTP 201 is returned

#### Scenario: An llm evaluator without a model is rejected

- **WHEN** `POST /v1/evaluators` is called with `type: "llm"` and a blank or absent `model`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A body with no type is rejected rather than defaulted

- **WHEN** `POST /v1/evaluators` is called with no `type`, whether or not `preset` is present
- **THEN** the request is rejected with HTTP 400, and the body is not read as `llm` on the strength of
  `preset`'s default

#### Scenario: A sql evaluator registers with bare expression outputs

- **WHEN** `POST /v1/evaluators` is called with `type: "sql"`, no `model`/`preset`/`request_template`/
  `params`, and `outputs` mapping each target column name to a non-blank SQL expression
- **THEN** the evaluator version is created and HTTP 201 is returned

#### Scenario: An llm evaluator registers with prose outputs

- **WHEN** `POST /v1/evaluators` is called with `type: "llm"`, a `model`, a `request_template` and
  `outputs` whose values are prose strings
- **THEN** the evaluator version is created, HTTP 201 is returned, and the stored `outputs` preserve
  the request body's declaration order

#### Scenario: A removed evaluator field is refused by name

- **WHEN** `POST /v1/evaluators` carries `input_vars`, `response_schema` or `output_vars`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the unrecognized field, and no
  evaluator version is created

#### Scenario: A misspelled member inside an output object is refused

- **WHEN** an `outputs` value is an object carrying `prosee` instead of `prose`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the unrecognized field, rather
  than stored as an output with no prose

#### Scenario: An output object carrying both values and jsonata is rejected

- **WHEN** an `outputs` value is an object declaring both `values` and `jsonata`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the output

#### Scenario: An identity jsonata is rejected

- **WHEN** an `outputs` entry declares a `jsonata` expression equal to the output's own name
- **THEN** the request is rejected with HTTP 422, because an absent `jsonata` already means a direct
  field lookup by name

### Requirement: Static governance gate on register and enable

The system SHALL statically validate a pipeline on register and on enable; each failure SHALL be
rejected with HTTP 422 `pipeline_validation_failed` (except entitlement and immutability, which use
their own codes) with detail identifying the failing check. The gate SHALL enforce: (1) the
`target` exists, is `ACTIVE`, and is kind=enrichment, and every one of the resolved evaluator's
`outputs` names a column that exists on it, at a type the shared JSON→ClickHouse type-mapping util
accepts; (2) the pipeline's **read source** resolves — a declared `inputs` entry names an existing `source`-kind
table, and an omitted one defaults to the target enrichment's `source_table` — and satisfies the
grain-key-reachable and scan-metadata invariants (see "A pipeline's read source is declarable");
(3) `filter` parses under the existing Calcite SQL front-end, is boolean over the read source's
**entity fields**, uses the bounded grammar (no joins/subqueries/CTEs), and is **entity-scoped** (see
below), with referenced columns extracted; (4) the evaluator resolves; (5) `trigger.kind ∈ {on_ingest,
schedule, group}` and the trigger's declaration is internally consistent; (6) the evaluator-type
specific checks; (7) the group-trigger checks below.

**Entity-scoping.** Every governed SQL fragment a pipeline carries — `filter`, `trigger.ready_when.signal`,
`trigger.member_select.prefer_sql`, and a `sql` evaluator's output expressions — is validated by embedding it in
a statement over the pipeline's **read source**, so the gate SHALL additionally confirm that what the front
end accepted actually reads that source's entity: the translated statement's entity SHALL equal the
pipeline's read source (compared case-insensitively), and every referenced column SHALL be either a declared
column of that source (matched on either its logical `name` or its `source_name`) or the **qualified**
flat name `<enrichment>.<column>` of a column of one of that source's `ACTIVE` enrichments. Both are
rejected with HTTP 422 `pipeline_validation_failed`. Making the read source declarable SHALL NOT weaken
either check: a pipeline reads exactly one source table, and entity-scoping is what confines it to that
source and the enrichments reached through its own grain joins.

Neither check is redundant with the front end. A fragment that supplies its own `FROM` clause — an
expression ending in a SQL line comment, say — parses as a single valid `SELECT` over a *different*
table, and the sensitive-column entitlement check resolves referenced columns against the
**pipeline's read source's** entity, where such columns are absent, so it would find nothing to
gate. Separately, the query model publishes an enrichment's columns only under their qualified flat
name, so a fragment naming an enrichment column of some *other* source, or naming an enrichment
column under a bare unqualified spelling, parses cleanly against no field the pipeline's own
statement joins — and the rendered statement would reference a table absent from its own `FROM`
clause, which ClickHouse rejects at execution. Rejecting both at registration keeps the entitlement
gate total and turns an execution failure into the validation failure it always was.

The gate SHALL be **dispatched on the resolved evaluator's `type`**:

- **`llm`** — the resolved evaluator SHALL carry a non-blank `model`; every `vars` JSONata expression
  and every `outputs[].jsonata` SHALL parse (static parse only — runtime evaluation is the runner's);
  and the `request_template`'s placeholders SHALL satisfy the trigger's grain (see "An evaluator's
  template placeholders are validated against the pipeline's trigger grain").
- **`sql`** — `model` SHALL NOT be required and JSONata SHALL NOT be parsed (there is none); every
  `outputs` expression SHALL pass Class-1 expression governance (see
  [class1-enrichment](../class1-enrichment/spec.md)); the pipeline SHALL declare no `vars`, its
  evaluator SHALL declare at least one output resolving to a column on the target, and it SHALL target
  an enrichment keyed on the **read source's**
  identity column; and the `group` trigger SHALL be rejected, because a group-grain deterministic pipeline
  is an aggregation.

The **output mapping SHALL be derived, not declared**. `output_bindings` SHALL NO LONGER be accepted
on `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` — it is an unrecognized member, refused with
HTTP 400 `bad_request` naming the field — and the mapping SHALL instead be resolved by matching each
of the resolved evaluator's `outputs` keys against the target enrichment's columns. The gate SHALL
derive it at register and at patch and SHALL persist it with the declaration, so every write path
keeps an exact allow-list of the columns the pipeline may write.

A derived binding's `column` SHALL be **the target enrichment's physical column name** (its
`source_name`). Both write paths address the target physically — the Class-1 materialize statement
renders the column list into an `INSERT`, and the pipeline-scoped write-back reconciles caller rows
against the target's physical columns — so resolving once at registration is what keeps a renamed
column writable instead of failing at execution. A pipeline read back through the API therefore
reports the physical column.

The mapping SHALL be **one-to-one** by construction: `outputs` is a map, so no two entries share a
name, and an `outputs` key SHALL be matched against the target column's **exposed `name` only** —
deliberately not against `source_name` as well, which would make a key ambiguous in principle and
could resolve two keys onto one physical column. A key matching only a column's `source_name` SHALL be
rejected with HTTP 422 naming the exposed spelling to use.

A **`vars` entry's** `column` SHALL name a field of the read source's entity, under the same
resolution as a governed fragment's column reference: a source column under either spelling, or an
enrichment column under its qualified flat name. An entry naming no field of the entity SHALL be
rejected with HTTP 422 `pipeline_validation_failed`.

The **group-trigger checks** SHALL be:

1. `trigger_kind = group` ⇒ `trigger.group_by` is present and **resolves to the target enrichment's
grain key**: either equal to it, or the qualified flat name of an enrichment column whose last
segment is it. Any other value SHALL be rejected, because the enrichment is physically keyed on its
grain and collapses by it, so a pipeline grouping by another column would write many groups onto one
key. Since the grain key is also required to be reachable on the pipeline's read source's entity, a
`group` pipeline always groups the rows it reads by a value those rows carry. 2. `trigger.group_by`
and `trigger.ready_when` SHALL be rejected on a non-`group` trigger. This is the enforceable form of
"`on_ingest` stays row-grain": a grain key carries no row-vs-group marker, so the gate constrains
the *declaration* rather than inferring the grain's nature. 3. `trigger.ready_when.signal`, when
present, SHALL parse under the same bounded Calcite grammar as `filter`, SHALL be boolean over the
**read source's entity fields**, and its referenced columns SHALL run the sensitive-column
entitlement check. 4. `trigger.ready_when.idle` and `trigger.ready_when.max_staleness`, when
present, SHALL parse as positive durations (both the short form `10m` and ISO-8601 `PT10M` are
accepted); `trigger.ready_when.cost_ceiling`, when present, SHALL be a positive integer. 5.
`trigger_kind = group` ⇒ `trigger.ready_when` SHALL declare at least one of `signal`, `idle`, or
`max_staleness`. A readiness declaration with none would mark a group dirty forever and never ready,
so the pipeline would silently do nothing.

Note (the model check): the gate does NOT verify that an `llm` evaluator's `model` names a
live DIAL deployment — ADAS has no deployment-listing client to reuse (only caller-key
introspection). Live deployment verification is deferred; only evaluator resolution and a non-blank
model are enforced here.

#### Scenario: An evaluator output naming no target column is rejected

- **WHEN** a pipeline resolves an evaluator declaring an output whose name matches no column of the
  target enrichment
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` naming
  the output, and stating that an output resolves to a target column of the same name

#### Scenario: An evaluator output whose target column has an incompatible type is rejected

- **WHEN** a pipeline resolves an evaluator whose output binds to a target column of a type the
  shared type mapping does not accept
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A derived binding names the column's physical spelling

- **WHEN** a pipeline resolves an evaluator whose output matches a target column whose logical `name`
  differs from its `source_name`
- **THEN** the pipeline is accepted, the derived and returned binding names the column's
  `source_name`, and both write paths address the column ClickHouse actually carries

#### Scenario: An output matching only a column's source_name is rejected

- **WHEN** a pipeline resolves an evaluator whose output name matches a target column's `source_name`
  but not its exposed `name`
- **THEN** the request is rejected with HTTP 422 naming the exposed spelling to declare instead

#### Scenario: A declared output_bindings member is refused by name

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries `output_bindings`, whether or
  not it agrees with what would have been derived
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is
  persisted

#### Scenario: A target column no output writes keeps its existing values

- **WHEN** a target enrichment carries a column that the resolved evaluator declares no output for
- **THEN** the pipeline is accepted, that column is absent from the derived mapping, and it is never
  written — so a deprecated column retains its historical values

#### Scenario: Unparseable or unbounded filter is rejected

- **WHEN** `filter` fails to parse, is not boolean, references columns reachable on neither the
  pipeline's read source nor its `ACTIVE` enrichments, or uses a join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Governed SQL reaching outside the pipeline's read source's entity is rejected

- **WHEN** a pipeline's `filter`, `trigger.ready_when.signal`, `trigger.member_select.prefer_sql`,
  or `sql` output expression resolves against an entity other than the pipeline's read source, or
  references a column that is neither that source's own nor the qualified `<enrichment>.<column>` of
  one of that source's `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, rather
  than passing the gate with an unentitled column reference or failing later at execution

#### Scenario: A filter over an enrichment column of the read source is accepted

- **WHEN** a pipeline declares `inputs: ["dial_usage_log"]` and a `filter` of
  `length("usage_client_identity.client_session_id") > 0`, where `usage_client_identity` is an `ACTIVE`
  enrichment of `dial_usage_log`
- **THEN** the pipeline is accepted, and the scan and group-assembly statements emit the grain join
  for that enrichment

#### Scenario: A vars entry over an enrichment column is accepted

- **WHEN** a pipeline declares a `vars` entry bound to `"usage_client_identity.client_type"`
- **THEN** the pipeline is accepted, and the binding is stored under that qualified name

#### Scenario: A vars entry naming no entity field is rejected

- **WHEN** a pipeline declares a `vars` entry bound to a column reachable on neither the read
  source nor its `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Unresolvable evaluator or missing model on an llm pipeline is rejected

- **WHEN** the pipeline's evaluator (name/version) does not resolve, or the resolved `llm` evaluator
  has a blank `model`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Disallowed trigger kind is rejected

- **WHEN** a pipeline declares a `trigger.kind` outside `{on_ingest, schedule, group}` (e.g.
  `push`), or declares `group` for a `type=sql` evaluator
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Unparseable JSONata expression is rejected

- **WHEN** any input or output JSONata expression of an `llm` pipeline fails to parse
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sql pipeline is validated without a model or JSONata

- **WHEN** a pipeline binds a `type=sql` evaluator that carries no `model` and no JSONata
- **THEN** the model and JSONata checks are skipped, the `sql` expressions are validated instead,
  and a well-formed declaration is accepted

#### Scenario: A group key not resolving to the target grain is rejected

- **WHEN** a `group` pipeline declares a `trigger.group_by` that neither equals the target
  enrichment's grain key nor is the qualified flat name of an enrichment column whose last segment
  is that grain key
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A group key or readiness declaration on a non-group trigger is rejected

- **WHEN** an `on_ingest` or `schedule` pipeline declares `trigger.group_by` or `trigger.ready_when`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An unparseable or non-boolean readiness signal is rejected

- **WHEN** a `group` pipeline's `trigger.ready_when.signal` fails to parse, is not boolean,
  references columns reachable on neither the read source nor its `ACTIVE` enrichments, or uses a
  join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An unparseable readiness duration or non-positive ceiling is rejected

- **WHEN** a `group` pipeline's `trigger.ready_when.idle`/`max_staleness` is not a parseable
  duration, or its `cost_ceiling` is zero or negative
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A readiness declaration with no condition is rejected

- **WHEN** a `group` pipeline's `trigger.ready_when` declares none of `signal`, `idle`, or
  `max_staleness`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A group pipeline referencing a sensitive column is accepted for an entitled author

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a `group` pipeline whose
  `trigger.ready_when.signal` or `filter` references a `sensitive` column of the pipeline's read source
- **THEN** the pipeline is accepted

#### Scenario: A group pipeline referencing a sensitive column is rejected for a non-entitled author

- **WHEN** a caller who may not see sensitive columns registers the same declaration
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

### Requirement: A string output binds to an enum target column

An output whose value is a `string` SHALL be bindable to a target column of type `enum`: an
enum column holds strings, and ClickHouse validates the written value against the declared domain at
insert time. Without this the governance gate's exact-type match would reject the binding and make
an enum column unwritable by any enrichment.

Where an evaluator declares its outputs as `outputs` (the current shape) an output carries **no
declared logical type at all**, so there is no spelling of `enum` for the gate to refuse: for an
`llm` output the type comes from the target column itself, and for a `sql` output it is the type the
SQL front end infers for the expression, checked directly against the column. The refusal below
therefore governs a **stored old-shape** `output_vars` declaration only, where a per-variable `type`
still exists.

`enum` SHALL NOT be accepted as a stored output variable's declared logical type. The pipeline declares
the value it produces; the column declares the domain that value must fall into. Accepting `enum` as
a variable type would advertise a domain check the gate does not perform — the same reasoning that
already refuses a parameterized `array<...>` spelling. The declaration SHALL be refused with a
message saying to declare the output as `string` and let the target column carry the domain.

Binding SHALL remain exact in the other direction: a value whose type is neither `string` nor
numeric SHALL still be rejected against an `enum` column.

#### Scenario: A string output binds to an enum column

- **WHEN** a pipeline's evaluator declares an output bound to a target column of type `enum`, whose
  value is a `string`
- **THEN** the pipeline is accepted, and a materialization writing a value from the column's
  declared domain succeeds

#### Scenario: A value outside the domain fails at write time, not registration

- **WHEN** a pipeline bound to an `enum` column produces a value outside that column's declared
  domain
- **THEN** the pipeline remains valid at registration and the failure surfaces when the
  materialization writes, through the existing materialization failure contract

#### Scenario: An enum domain composed from the column makes the failure unrepresentable for an llm output

- **WHEN** an `llm` evaluator's output binds to a target column declaring `enum_values`, and the
  composed response schema carries that column's domain
- **THEN** the model is constrained to the column's own domain under strict schema enforcement, so a
  value the column cannot store is refused at the model boundary rather than at insert

#### Scenario: A stored output variable declared enum is refused

- **WHEN** a pipeline resolves a stored old-shape evaluator version whose output variable declares
  the logical type `enum`
- **THEN** the declaration is rejected with a message directing the author to declare `string` and
  let the target column declare the domain

#### Scenario: A non-string output against an enum column is still rejected

- **WHEN** a pipeline's output value is a `timestamp` and it binds to a target column of type `enum`
- **THEN** the binding is rejected by the governance gate

### Requirement: ENRICH pipeline declaration binding an evaluator to a target enrichment

The system SHALL manage pipelines via `POST /v1/pipelines`, `PATCH /v1/pipelines/{name}`, `DELETE
/v1/pipelines/{name}`, and `GET /v1/pipelines/{name}`. An ENRICH pipeline SHALL bind an
`evaluator_name` (+ optional `evaluator_version`, null meaning `latest`) to a `target`, plus an
optional single-entry `inputs`, `trigger.kind`, optional `trigger.cron`, `trigger.group_by`,
`trigger.ready_when`, `filter`, `vars`, `enabled`, and an optional nested `advanced` block carrying
the execution knobs (`scan_every`, `rows_per_scan`, `rows_per_call`, `rate_rpm`, `sample_fraction`).
The
pipeline's **grain SHALL be derived** from the target enrichment's `grain` and SHALL NOT be
re-declared on the pipeline. The pipeline's **read source MAY be declared** as its `inputs` entry
and SHALL default to the target enrichment's `source_table` when omitted (see "A pipeline's read
source is declarable"). Each mutating pipeline operation SHALL bump a `generation` counter and
`updated_at`. An unknown pipeline name SHALL be rejected with HTTP 404 `unknown_pipeline`.

**`vars`** SHALL be a map of variable name → the value the evaluator receives, each value a source
column or a JSONata expression over the read source's entity. It is the single declaration of the
evaluator's inputs, replacing the pipeline's `input_bindings` and the evaluator's `input_vars`: the
variable names are the map's keys, so a name cannot be declared in one place and bound in another.
What a var *is* depends on the trigger's grain — a top-level template placeholder at row grain, a
field of each member object under `{{members}}` at group grain — see "An evaluator's template
placeholders are validated against the pipeline's trigger grain".

**`advanced`** SHALL carry the execution knobs, nested rather than flat so they stay out of the
required-looking surface of the declaration. Its members SHALL be `scan_every`, `rows_per_scan`,
`rows_per_call`, `rate_rpm` and `sample_fraction`, each optional, and the block itself SHALL be
optional. `sample_fraction` SHALL be a fraction in `(0, 1]`: a value above `1` SHALL be rejected with
HTTP 422 rather than read as a percentage, and `0` SHALL be rejected with HTTP 422 naming
`enabled: false` as the way to declare a pipeline that evaluates nothing.

The following members SHALL NO LONGER be accepted on this kind, each refused at the binding with
HTTP 400 `bad_request` naming the field: `input_bindings` and `output_bindings` (replaced by `vars`
and by the derived output mapping), the flat `sampling`, `cadence`, `batch_scan_limit` and
`batch_chunk` (now `advanced` members under their new names), and `priority` (nothing reads it).
`evaluator_name`, `evaluator_version` and `rate_rpm` SHALL keep their spellings: a rename buys a
permanent second vocabulary and is only worth it for a name that is ambiguous or misleading.

`advanced` SHALL count as a **declaration** for the purpose of distinguishing a declaration patch
from an enable-only patch, so a patch carrying only `advanced` (or only `vars`) SHALL be re-validated
and stored rather than silently discarded. `advanced` SHALL NOT be required by a declaration patch:
it is optional at registration, so requiring it on patch would make a pipeline that never set it
unpatchable. A cross-kind rejection of one of its members SHALL name `advanced.<member>`, not
`advanced`, so the caller is told which field they misplaced.

`enabled` SHALL be **required** on a registration of this kind and SHALL carry no implicit default:
whether a pipeline starts live is an operator decision, and this kind is registered with the flag it
is given, so an omitted one would leave a pipeline that looks registered and never runs. An omitted
or null `enabled` SHALL be rejected with HTTP 422 `pipeline_validation_failed` naming the field.

The status is 422 rather than the 400 this answered while enrichment had a registry of its own, and
the reason is the merge: the requirement is now **per-kind** — an `aggregate` pipeline ignores the
flag entirely, because its dry-run preview is what the disabled state exists for — and a per-kind
requirement cannot be expressed by request-shape validation. See
[pipeline-registry](../pipeline-registry/spec.md), "Register and enable lifecycle".

A returned pipeline SHALL carry its resolved `inputs` entry — its read source, declared or derived —
and SHALL additionally carry `version_column`: the **physical name of the read source's version
column**, derived from that source's catalog scan metadata (`table_definition.version_column`) — the
same fact the scan path resolves, so the two cannot disagree. `version_column` SHALL NOT be
declarable on the pipeline. A scan page projects that column under its own name, and for a `group`
pipeline its value is the group's activity timestamp; surfacing the name is what lets a caller read
it per source rather than holding one configured name for every source it scans. When the read
source is unknown or declares no scan metadata, `version_column` SHALL be **absent** and the read
SHALL still succeed — the pipeline is valid, and an unscannable source is rejected on the scan path
(HTTP 422) rather than on a registry read.

Because `version_column` is derived rather than declared, it can change without any pipeline being
mutated: `PATCH /v1/tables/{source}/schema` MAY rename the column and repoint the stored name (see
[table-management](../table-management/spec.md)). A pipeline mutation is therefore **not** the only
event that bumps `generation`: when a source's `version_column` is repointed, every pipeline **whose
read source is that source** SHALL have its `generation` incremented and its `updated_at` advanced,
in the same transaction as the catalog repoint. The bump SHALL follow the read source, so a pipeline
that declares one is bumped by the table it actually scans and not by its target enrichment's
`source_table`. Both `generation` and `updated_at`, not just `generation` — the sync feed selects by
`updated_at`, so a bumped generation the feed does not return would be no signal at all. Without
this a runner detecting change by `generation` would keep the pre-rename name and read a key its
scan pages no longer carry.

`trigger.kind` SHALL be one of `on_ingest`, `schedule`, or `group`. A `group` pipeline fires once per
**group key** rather than once per row: it declares `trigger.group_by` (the group key) and `trigger.ready_when`
(`{signal?, idle?, max_staleness?, cost_ceiling?}`), the declaration of when a group is considered
complete enough to evaluate. A `group` pipeline SHALL NOT carry `trigger.cron` — its cadence is the
caller's readiness sweep, not a cron expression — which the existing cron-iff-`schedule` invariant
already enforces. A `group` pipeline writes exactly one enrichment row per group key through the existing
pipeline-scoped write-back, keyed by `trigger.group_by`, and the target enrichment's `ReplacingMergeTree` collapses
by that key.

#### Scenario: Create a pipeline derives its grain and defaults its source

- **WHEN** `POST /v1/pipelines` is called with a valid evaluator and `target` and no `inputs`
- **THEN** the pipeline is created with `grain` derived from the target enrichment and `inputs`
  defaulted to that enrichment's `source_table`, `enabled` per the request, `generation`
  initialized, and HTTP 201 returned

#### Scenario: Omitted enabled is rejected

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and `enabled` absent or null
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field, and
  no pipeline is created

#### Scenario: A removed declaration member is refused by name

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries any of `input_bindings`,
  `output_bindings`, `sampling`, `cadence`, `batch_scan_limit`, `batch_chunk` or `priority`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is
  persisted — the caller is told what they sent rather than having it silently ignored

#### Scenario: A patch carrying only advanced is a declaration patch

- **WHEN** `PATCH /v1/pipelines/{name}` carries only an `advanced` block, or only `vars`
- **THEN** the declaration is re-validated in full and stored, `generation` is bumped, and the patch
  is not read as an enable-only patch that discards it

#### Scenario: A declaration patch need not restate advanced

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a complete declaration with no `advanced` block for a
  pipeline that never declared one
- **THEN** the patch is accepted

#### Scenario: A sample fraction above one is rejected

- **WHEN** `POST /v1/pipelines` declares `advanced.sample_fraction: 50`
- **THEN** the request is rejected with HTTP 422, rather than being read as 50 per cent

#### Scenario: A zero sample fraction is rejected naming the enabled flag

- **WHEN** `POST /v1/pipelines` declares `advanced.sample_fraction: 0`
- **THEN** the request is rejected with HTTP 422 naming `enabled: false` as the way to declare a
  pipeline that evaluates nothing, rather than arming a pipeline that keeps no rows

#### Scenario: A returned pipeline carries the read source's version column

- **WHEN** a pipeline is created, updated, or read and its read source declares scan metadata
- **THEN** the response carries `version_column` equal to that source's declared version column

#### Scenario: A source without scan metadata omits version_column

- **WHEN** a pipeline is read and its read source declares no version column (or the source is
  unknown)
- **THEN** `version_column` is absent from the response and the read succeeds

#### Scenario: Update bumps generation

- **WHEN** `PATCH /v1/pipelines/{name}` changes a pipeline
- **THEN** the stored pipeline is updated, `generation` is incremented, and `updated_at` advances

#### Scenario: Repointing the read source's version column bumps generation

- **WHEN** `PATCH /v1/tables/{source}/schema` renames the column named by that source's
  `version_column`, and a pipeline's read source is that source
- **THEN** that pipeline's `generation` is incremented and its `updated_at` advances in the same
  transaction as the catalog repoint, and a subsequent `GET /v1/pipelines/{name}` carries the renamed
  `version_column`
- **AND** a pipeline whose read source is a different table is untouched, including a pipeline whose
  target enrichment hangs off the repointed source but which declares another read source

#### Scenario: A repointed version column reaches an incremental sync

- **WHEN** a runner has synced with `updated_since` and the source then repoints its
  `version_column`
- **THEN** the next sync at that watermark returns the affected pipelines, each carrying the new
  `version_column` and a higher `generation`

#### Scenario: Renaming a non-version column of the source bumps nothing

- **WHEN** `PATCH /v1/tables/{source}/schema` renames a source column that is neither the source's
  `version_column` nor its `identity_column`
- **THEN** no pipeline's `generation` or `updated_at` changes

#### Scenario: Delete removes the pipeline

- **WHEN** `DELETE /v1/pipelines/{name}` is called for an existing pipeline
- **THEN** the pipeline is removed and HTTP 204 returned

#### Scenario: An unknown pipeline is rejected

- **WHEN** any pipeline read/patch/delete targets a name that does not exist
- **THEN** the request is rejected with HTTP 404 and error code `unknown_pipeline`

#### Scenario: Create a group pipeline

- **WHEN** `POST /v1/pipelines` is called with `trigger.kind: "group"`, a `trigger.group_by` equal
  to the target enrichment's grain key, and a `trigger.ready_when` carrying at least one readiness
  condition
- **THEN** the pipeline is created and HTTP 201 returned

### Requirement: An ENRICH pipeline's read source is declarable

An ENRICH pipeline SHALL have a **read source**: the single table it reads to derive its values. It
MAY be declared as the single `inputs` entry on `POST /v1/pipelines` / `PATCH /v1/pipelines/{name}`,
and when omitted SHALL default to the target enrichment's `source_table`. Every declaration
registered before this capability therefore keeps its exact behaviour, and `inputs` stays an
additive, optional field.

A declared `inputs` entry SHALL name an existing, `ACTIVE` table of kind `source`; an unknown name,
a name of kind `enrichment`, and a name whose definition is not `ACTIVE` SHALL each be rejected with
HTTP 422 `pipeline_validation_failed`. Only an `ACTIVE` definition has a confirmed ClickHouse table
behind it, and a `FAILED` one keeps the columns its failed schema definition persisted — so without
the status check it could satisfy both invariants below and still be a table no scan can read. This
mirrors the target enrichment, which must also be `ACTIVE`.

The read source is what every governed read of the pipeline resolves: `filter`, `trigger.ready_when.signal`,
`trigger.member_select`, `vars` and a `sql` evaluator's output expressions are validated against its
**entity** — the read source plus its `ACTIVE` enrichments, as "A pipeline reads its read source's entity"
defines — and the incremental scan, the group-input assembly, and the Class-1 materialize page it. It is
**not** the same fact as the target enrichment's `source_table`, which remains the enrichment's immutable
read-side attachment — the table the enrichment auto-joins into on the shared grain key, whose schema
view flattens it, and which cannot be deleted while it has enrichments. Decoupling the two is what allows
an enrichment to hang off one table while being derived from another: `conversations_feedback` is an
enrichment of `conversations` keyed on `chat_id`, computed by a `group` pipeline reading `rate_analytics`,
which carries `chat_id` too.

The two cases SHALL be **distinguishable in an authored read**. The compiled projection
(`view=compiled`) reports the resolved read source, declared or derived alike, because that is the
table the runner is to scan. The authored projection (`view=source`) SHALL report the **stored**
value: the declared table when the pipeline declared one, and **absent** when it did not. Without
that distinction a caller that echoes a read response back into `PATCH /v1/pipelines/{name}`
silently *declares* what the pipeline previously derived — after which the stricter invariants below
apply — so the authored projection is what keeps a GET → PATCH round trip faithful. Omitting `inputs`
is how a caller keeps a pipeline following its target.

A declaration patch that omits `inputs` SHALL be **rejected with HTTP 422
`pipeline_validation_failed`** for a pipeline that has a declared read source, naming `inputs` as a
member the declaration must restate. A declaration patch is validated as one whole object, and
silently repointing a pipeline from its declared source back to its target's `source_table` is a
change to what the pipeline reads that the caller never wrote. `inputs` is therefore part of what a
complete `enrich` declaration states, for a pipeline that has one; a pipeline that never declared one
SHALL remain patchable without it.

Because a declared read source is no longer guaranteed to be join-compatible with the target by
construction, the gate SHALL enforce two invariants in its place on a **declared** read source, on
register and on enable, each rejected with HTTP 422 `pipeline_validation_failed`:

1. **The grain key is reachable on the read source's entity, at a compatible type.** The target
enrichment's grain key SHALL resolve to a field of the read source's entity — either a declared
column of the read source itself (matched on either its logical `name` or its `source_name`), or the
qualified flat name of a column of one of the read source's `ACTIVE` enrichments whose last segment
is the grain key. Its declared type SHALL be compatible with the type the target enrichment is keyed
at. The pipeline's derived rows are keyed by that column, and the target is read through a grain
join on it, so a read source whose entity does not carry it could produce no addressable row, and
one carrying a same-named column of another type would key the enrichment with values its grain join
cannot match. A grain key reached **only through an enrichment** SHALL be admitted for a
`group`-trigger pipeline alone, which names the qualified field as its `trigger.group_by` and has
every read project it under the plain grain key. A row-grain pipeline declares no `trigger.group_by`
and reads the grain key under its own bare name, which the read source does not carry — and many
source rows share one enrichment value, so it would write many derived rows onto one target key for
the target's `ReplacingMergeTree` to collapse arbitrarily. Where more than one `ACTIVE` enrichment
of the read source declares the grain key, a `trigger.group_by` naming one of them SHALL settle
which is meant; a declaration naming none SHALL be rejected as ambiguous. 2. **The read source is
scannable.** The read source SHALL declare scan metadata — both `version_column` and
`identity_column` (see [enrichment-runtime](../enrichment-runtime/spec.md)). Every pipeline-scoped
read already requires the pair; checking it at registration reports the misdeclaration to the author
rather than at the runner's first scan.

Neither invariant SHALL be applied to a **derived** read source. The first holds there by
construction — an enrichment's grain key is validated to be a column of its `source_table` when its
schema is written — so applying it would only re-check a catalog fact. The second is a genuinely new
requirement that a derived source need not meet: declaring scan metadata is optional, a pipeline
over an enrichment of an unscannable source registers today and is refused on the scan path instead
(HTTP 422), and that pipeline SHALL remain registrable. Declaring a read source must not invalidate
pipelines that are valid without one.

#### Scenario: A pipeline omitting source derives it from the target enrichment

- **WHEN** `POST /v1/pipelines` is called without `inputs`
- **THEN** the pipeline's read source is the target enrichment's `source_table`, and the created
  pipeline reports that table as its `inputs` entry

#### Scenario: The authored projection echoes a declared source

- **WHEN** `GET /v1/pipelines/{name}?view=source` is called for a pipeline that declared
  `inputs: ["response_ratings"]` while its target's `source_table` is `turns`
- **THEN** the response carries `inputs: ["response_ratings"]` — the stored declaration, not the
  resolved one

#### Scenario: The authored projection omits a derived source

- **WHEN** `GET /v1/pipelines/{name}?view=source` is called for a pipeline that declared no `inputs`
- **THEN** `inputs` is **absent** from the response, so echoing the body back into a patch does not
  promote an inherited source into a declared one

#### Scenario: A declaration patch omitting a declared source is refused

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a complete declaration with no `inputs` for a
  pipeline whose stored declaration names one
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `inputs`, and the
  pipeline keeps reading the source it declared

#### Scenario: A pipeline declares a read source other than the target's source table

- **WHEN** `POST /v1/pipelines` is called for a target enrichment of `conversations` keyed on
  `chat_id`, with `inputs: ["rate_analytics"]`, a `filter` over `rate_analytics` columns, and
  `trigger.group_by: "chat_id"`
- **THEN** the pipeline is created with HTTP 201, its `inputs` entry is `rate_analytics`, and the
  target enrichment's own `source_table` is unchanged

#### Scenario: A declared read source that is not a source table is rejected

- **WHEN** `POST /v1/pipelines` declares an `inputs` entry that does not exist, or that names an
  enrichment
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A declared read source that is not ACTIVE is rejected

- **WHEN** `POST /v1/pipelines` declares an `inputs` entry naming a `source`-kind table whose status
  is `pending` or `failed`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`,
  because no materialized table stands behind it

#### Scenario: A read source whose entity does not reach the target's grain key is rejected

- **WHEN** a pipeline declares an `inputs` entry that carries no column matching the target
  enrichment's grain key, and none of that source's `ACTIVE` enrichments carries one either
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the grain key the read source's entity must reach

#### Scenario: A grain key reached through an enrichment of the read source is accepted

- **WHEN** a pipeline declares `inputs: ["dial_usage_log"]` for a target enrichment keyed on
  `client_session_id`, which `dial_usage_log` does not declare but its `ACTIVE` enrichment
  `usage_client_identity` does, and declares
  `trigger.group_by: "usage_client_identity.client_session_id"`
- **THEN** the pipeline is created with HTTP 201, and its derived rows are keyed by the plain grain
  key `client_session_id`

#### Scenario: A grain key named without its enrichment qualifier is rejected

- **WHEN** the same pipeline declares `trigger.group_by: "client_session_id"` while no column of
  that name is declared on `dial_usage_log` itself
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the qualified spelling `<enrichment>.<column>` to use instead — an enrichment column has no
  unqualified spelling in the entity namespace

#### Scenario: A row-grain pipeline may not key its rows on an enrichment column

- **WHEN** a pipeline for the same target declares `inputs: ["dial_usage_log"]` with `trigger.kind:
  "on_ingest"`, so its grain key is reachable only through an enrichment and it declares no
  `trigger.group_by`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the group trigger as the only shape that may key derived rows on an enrichment column

#### Scenario: A grain key reached through an enrichment of another source is rejected

- **WHEN** a pipeline declares a `trigger.group_by` qualified with an enrichment that is not an
  `ACTIVE` enrichment of the pipeline's own read source
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A read source whose grain-key column has an incompatible type is rejected

- **WHEN** a pipeline declares an `inputs` entry whose entity reaches the target enrichment's grain
  key as a field of a type incompatible with the type the target is keyed at, whether that field is
  a source column or an enrichment column
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A declared read source without scan metadata is rejected at registration

- **WHEN** a pipeline declares an `inputs` entry whose `table_definition` declares no
  `version_column` or no `identity_column`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` at
  registration, rather than being accepted and failing at the first scan

#### Scenario: A pipeline deriving an unscannable read source still registers

- **WHEN** a pipeline declares no `inputs` and its target enrichment's `source_table` declares no
  scan metadata
- **THEN** the pipeline is created, unchanged from the behaviour before the read source was
  declarable, and the scan path is what refuses it

#### Scenario: Governed SQL is scoped to the declared read source's entity

- **WHEN** a pipeline declares `inputs: ["rate_analytics"]` and a `filter` referencing a column of
  `conversations` that neither `rate_analytics` nor any of its `ACTIVE` enrichments carries
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` —
  entity scoping follows the read source, not the target enrichment's `source_table`

### Requirement: The enrich feed carries the resolved evaluator and a change token

The system SHALL serve the enrich feed as `GET
/v1/pipelines?kind=enrich[&enabled=…][&updated_since=…]`, returning each selected pipeline with its
**resolved pinned evaluator inlined** (the concrete version, resolving `latest` at sync time), the
pipeline's read source and derived grain, the read source's `version_column` (absent when that
source declares none), and a `generation` value the runner uses for change detection. The feed
carries **no HTTP `ETag`/304** — `generation` is the change signal and comparison is the consumer's,
done client-side per pipeline. `updated_since` SHALL filter to pipelines changed at or after the
given timestamp. For a `group` pipeline the feed SHALL additionally inline `trigger.group_by` and
`trigger.ready_when`, so a caller can route the pipeline to its group path and evaluate readiness
without a second round-trip.

The feed SHALL be the **compiled** projection, requested as `view=compiled` — see
[pipeline-registry](../pipeline-registry/spec.md), "A pipeline read selects an authored or a compiled
projection". The runner SHALL name the projection explicitly rather than relying on a default: the
authored projection is the default for every pipeline read, and it carries none of the resolved
members the runner needs. `?kind=enrich` without `view=compiled` is therefore an operator's listing
and SHALL cost no resolution.

An `enrich` entry SHALL carry the **response schema the model is held to** and the **output mapping
the write is validated against**, whether either was stored or composed:

- The inlined evaluator's `response_schema` SHALL be the version's **stored** schema when it has one,
  and otherwise the schema composed from the pipeline's target and the evaluator's `outputs` (see
  "The response schema is composed from the target and the evaluator"). A stored schema SHALL keep
  winning for the version that stores it, permanently — the composed form is not a faithful
  reproduction of one, so switching a stored version to composition would change a live prompt.
- The entry's `outputs` SHALL be the pipeline's derived output mapping, one ordered entry per mapped
  target column, each carrying the exposed `name`, the physical `column`, and the transform resolved
  through the version's stored `output_vars` or its `outputs` alike. Resolving both stored shapes
  behind one accessor is what keeps the split invisible to the consumer.
- The inlined evaluator SHALL carry neither `input_vars` nor `output_vars`. Both described what the
  entry's own `vars` and `outputs` now carry, and serving them alongside would restore the
  two-lists-joined-by-name shape the fold removes.

The transform is the half of an output that cannot be recovered by guessing: a consumer that loses it
falls back to reading `response[name]`, which an identity mapping survives and a real one does not —
storing a sentinel string where the transform would have written NULL. Folding it onto the output is
what removes that failure mode rather than documenting it.

An `enrich` pipeline whose output mapping resolves to **no columns** SHALL NOT be served on the feed:
it SHALL be omitted from the response and the omission logged. This is a wire-contract guarantee, not
an implementation detail, because the failure is silent and destructive rather than an error. A
consumer handed an empty mapping still writes the grain key and the provenance columns and still
stamps the group as computed — which against a `ReplacingMergeTree` keyed on the grain is an upsert
of a row whose label columns are all NULL over an existing, already-paid-for label, never retried,
with the model call made and charged first. An empty mapping is a bug in the derivation, never a
configuration, so the feed SHALL refuse to hand it over rather than serving a pipeline that looks
healthy.

The response envelope, the four filters and the listing order are the registry's, not this
capability's — see [pipeline-registry](../pipeline-registry/spec.md), "Pipeline listing carries the
registry's filters, envelope and ordering". What this capability adds is what an `enrich` entry
carries. One consequence is worth stating because it is a wire change for the runner: the feed is an
**object envelope** (`{"pipelines": [ … ]}`), where the enrichment registry served a bare array.

What an `enrich` entry carries SHALL NOT depend on **which** of those filters selected it. Resolution
costs a bounded number of queries for the whole page, so there is no reason to serve a narrower entry
under a narrower filter — and a caller adding `target` in order to read a single pipeline's resolved
evaluator is precisely the caller a downgraded entry would silently fail. Stated as a requirement
because the failure is invisible: the members would be **absent**, which reads as "this pipeline has
no evaluator" rather than as "this listing did not resolve one".

The `enabled` parameter SHALL narrow which pipelines are returned, and SHALL accept only the exact
values `true` (enabled only) or `false` (disabled only); every other value SHALL be rejected with
HTTP 400 `bad_request` rather than falling back to a default, since a silently-ignored filter would
report the wrong set as the whole registry. This SHALL hold against the framework's lenient boolean
conversion, which is otherwise the default behaviour: `1`/`yes`/`on` and their negatives, a
differently-cased `TRUE`, a repeated `enabled` parameter, and — the case a caller actually hits — an
**empty** value (`?enabled=`, what interpolating an unset variable sends) SHALL all be rejected
rather than accepted or mapped to the omitted-parameter default. An **omitted** `enabled` SHALL
return **all** registered pipelines regardless of their flag: a listing reports what is registered,
not only what is running, so a pipeline an operator switched off is visible rather than silently
absent. Every entry SHALL carry the same fully-resolved shape regardless of the filter — a disabled
pipeline is registered but not running, not a degraded record — and `enabled` SHALL compose with
`updated_since` rather than replace it.

The listing SHALL be returned in a **total** order — oldest `updated_at` first, `id` breaking ties —
so repeated calls agree on the sequence. `updated_at` alone does not order the set: it is truncated
to microseconds (below), so pipelines written in one transaction can share a value and shuffle
between calls.

This one endpoint is therefore both the operator's listing of the registry (no filter) and the
runner's sync feed (`enabled=true`). A consumer that **executes** pipelines SHALL pass
`enabled=true` explicitly rather than relying on the default, since the unfiltered listing carries
disabled pipelines.

The `inputs` entry the feed reports SHALL be the table the runner is to scan — the pipeline's
declared read source when it has one, otherwise the target enrichment's `source_table` — so a runner
needs no second lookup and cannot scan the wrong table.

The feed SHALL resolve every pipeline in a **constant number of queries** regardless of how many
pipelines it returns — including the version-column resolution, which SHALL be one batched fetch
over the distinct read sources of the returned pipelines rather than a per-pipeline lookup.

A pipeline's `updated_at` SHALL be stored at the precision it is reported with, so the value any
pipeline read or write returns is usable verbatim as the next `updated_since`. The watermark is
inclusive (`>=`), so a reported timestamp finer than the stored one would place the watermark past
the pipeline and skip it — the registry therefore mints `created_at`/`updated_at` truncated to
microseconds, which is what the column holds.

#### Scenario: A reported updated_at is a usable watermark

- **WHEN** a pipeline is created or updated, and the `updated_at` from that response is passed
  straight back as `GET /v1/pipelines?kind=enrich&updated_since=<updated_at>`
- **THEN** that pipeline is returned, and a later read of the pipeline reports the same `updated_at`
  value

#### Scenario: The unfiltered listing returns every registered pipeline

- **WHEN** `GET /v1/pipelines?kind=enrich` is called
- **THEN** every registered pipeline is returned regardless of its `enabled` flag, each with its
  resolved evaluator version inlined, its read source and derived grain, its `enabled` value, and a
  `generation`

#### Scenario: The listing narrows to enabled pipelines

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true` is called
- **THEN** only pipelines with `enabled: true` are returned — the set a runner executes

#### Scenario: The listing narrows to disabled pipelines

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=false` is called
- **THEN** only pipelines with `enabled: false` are returned

#### Scenario: A disabled pipeline is fully resolved

- **WHEN** a pipeline registered with `enabled: false` is returned by any of these listings
- **THEN** it carries the same resolved evaluator, read source, derived grain and `version_column`
  an enabled pipeline carries — the filter decides membership, never the shape of an entry

#### Scenario: The enabled filter composes with the watermark

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true&updated_since=<t>` is called
- **THEN** only enabled pipelines with `updated_at >= t` are returned — the filter narrows the
  watermarked set rather than replacing the watermark

#### Scenario: A non-boolean enabled value is rejected

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=<value that is not exactly true or false>` is
  called — including `1`, `yes`, `on`, `TRUE`, or the parameter repeated with two values
- **THEN** the request is rejected with HTTP 400 `bad_request` rather than served under a default
  filter

#### Scenario: An empty enabled value is rejected rather than read as omitted

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=` is called, the parameter present but valueless
- **THEN** the request is rejected with HTTP 400 `bad_request` — a present-but-empty filter is a
  caller error, and answering it with the whole registry is the silent mis-answer the filter exists
  to prevent

#### Scenario: The listing order is total

- **WHEN** `GET /v1/pipelines?kind=enrich` is called twice over an unchanged registry containing
  pipelines that share an `updated_at`
- **THEN** both calls return the pipelines in the same order, ties broken by `name`

#### Scenario: A target-filtered enrich listing is resolved like the unfiltered feed

- **WHEN** `GET /v1/pipelines?kind=enrich&target=<enrichment>` is called
- **THEN** the returned pipeline carries its resolved evaluator, derived grain key, read source and
  `version_column` exactly as `GET /v1/pipelines?kind=enrich` would return them

#### Scenario: Sync carries the read source of a pipeline that declares one

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline declares an `inputs`
  entry other than its target enrichment's `source_table`
- **THEN** that pipeline's `inputs` entry is the declared read source, and its `version_column` is
  that source's declared version column

#### Scenario: Sync carries the source's version column

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline's read source declares
  scan metadata
- **THEN** that pipeline carries `version_column` equal to that source's declared version column

#### Scenario: Incremental sync filters by updated_since

- **WHEN** `GET /v1/pipelines?kind=enrich&updated_since=<t>` is called
- **THEN** only pipelines with `updated_at >= t` are returned

#### Scenario: Sync inlines the group declaration

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline has `trigger.kind:
  "group"`
- **THEN** that pipeline's `trigger.group_by` and `trigger.ready_when` are inlined alongside its
  resolved evaluator

#### Scenario: A stored response schema is served verbatim

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline's pinned evaluator
  version carries a stored `response_schema`
- **THEN** that stored schema is served unchanged, property order included, and no composed schema
  replaces it

#### Scenario: A new-shape evaluator's schema is composed

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline's pinned evaluator
  version declares `outputs` and carries no stored `response_schema`
- **THEN** the entry carries the schema composed from the pipeline's target and those `outputs`, and
  its `outputs` mapping carries each output's transform

#### Scenario: The feed carries the derived output mapping

- **WHEN** `GET /v1/pipelines?kind=enrich` is called for a pipeline whose evaluator declares fifteen
  outputs, all resolving to target columns
- **THEN** the entry carries fifteen output bindings, each naming a target column's physical spelling

#### Scenario: A pipeline with no resolvable output mapping is omitted from the feed

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true` is called and one enabled pipeline's output
  mapping resolves to no columns
- **THEN** that pipeline is absent from the response and the omission is logged, rather than served
  with an empty mapping a consumer would act on by overwriting existing labels with NULLs

### Requirement: An ENRICH pipeline's enrichment references are protected against drop and rename

An enrichment a pipeline reads SHALL be a dependency of that pipeline, protected exactly as the
pipeline's declared read source is. What a pipeline reads SHALL be resolved from its whole
declaration, including a `type=sql` pipeline's pinned evaluator output expressions — the only place
such a pipeline's column references appear. Dropping such an enrichment, or dropping or renaming a
column of it that a rule references, SHALL be refused with HTTP 409 `conflict` naming the pipelines
that read it, rather than leaving those pipelines to fail on their next scan or materialize.

Those expressions SHALL be resolved through the **single accessor** that reads an evaluator version's
outputs in either stored shape (see "An evaluator version's outputs are one fact in two stored
shapes"). This is a data-integrity requirement rather than a refactoring preference: a new-shape
`sql` evaluator keeps its expressions in `outputs` and carries no `output_vars`, so a dependency scan
reading only `output_vars` would contribute **zero** column references and let an operator drop or
rename an enrichment column the pipeline's `INSERT … SELECT` joins — with the failure arriving at the
next materialize instead of at the DDL.

#### Scenario: Dropping an enrichment a pipeline reads is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose columns a registered pipeline
  references
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming the pipelines that
  read it

#### Scenario: Dropping an enrichment read only by a Class-1 output expression is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment referenced only by a `type=sql` pipeline's
  evaluator output expression, with no reference in the pipeline row itself
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming that pipeline

#### Scenario: Dropping a referenced column of an enrichment is refused

- **WHEN** a schema patch would drop or rename a column of an enrichment that a registered pipeline
  references
- **THEN** the request is refused with HTTP 409 and error code `conflict`

#### Scenario: An unreferenced column of a read enrichment may still change

- **WHEN** a schema patch drops a column of an enrichment that no pipeline references, while other
  columns of that enrichment are referenced
- **THEN** the patch is accepted

#### Scenario: A new-shape sql evaluator's expressions reach the dependency guard

- **WHEN** a Class-1 pipeline pins an evaluator version that declares its expressions in `outputs`,
  one of which reads `usage_client_identity.client_type`, and a schema patch would drop or rename
  that column
- **THEN** the request is refused with HTTP 409 `conflict` naming that pipeline, exactly as it is for
  an old-shape evaluator whose expressions live in `output_vars`

### Requirement: An ENRICH pipeline reads its read source's entity, not only its read source table

Every column reference a pipeline declares SHALL resolve against the **entity** of its read source:
the read source's own declared columns under their exposed names, plus the columns of each of its
`ACTIVE` enrichments under the qualified flat name `<enrichment>.<column>`. This is the same flat
namespace `GET /v1/queries/entities/{name}` publishes and the aggregate pipeline surface resolves,
so a pipeline, a rollup and a query over the same entity SHALL name a column identically.

Membership SHALL be decided once, from the catalog's `ACTIVE` enrichments of the read source, and
SHALL be the same set the compiled statement joins — a field admitted by validation that the
statement would not join is a failure at execution rather than at declaration, and SHALL NOT be
reachable.

The surfaces this covers SHALL be: the target enrichment's grain key and `trigger.group_by`;
`filter`; `trigger.ready_when.signal`; `trigger.member_select.prefer_sql` and
`trigger.member_select.order_by`; the columns a `vars` entry binds; and a `sql` evaluator's
output expressions.

An enrichment column SHALL be addressable **only** under its qualified flat name. A bare column name
SHALL always resolve to the read source, so an enrichment SHALL NOT be able to shadow a source
column, and a pipeline declaring only bare source column names SHALL behave and render exactly as
before this capability. an `inputs` entry SHALL continue to name a source table: naming an
enrichment there stays rejected with HTTP 422 `pipeline_validation_failed`, because an enrichment is
reached through its source's grain join rather than read directly. This capability SHALL NOT
introduce a second input, general joins, or a pipeline reading an enrichment at a different grain
from its read source.

No new declaration field SHALL be introduced and no opt-in SHALL be required: the resolution is a
widening, and an enrichment column's qualified spelling was previously rejected outright.

#### Scenario: A pipeline naming no enrichment column is unchanged

- **WHEN** a pipeline registered before this capability, referencing only its read source's own
  columns, is read, enabled, scanned, assembled or materialized
- **THEN** its validation outcome and the SQL its reads render are unchanged, and no enrichment is
  joined

#### Scenario: A referenced enrichment is joined; an untouched one is not

- **WHEN** a pipeline references a column of one `ACTIVE` enrichment of its read source, and its
  read source has other `ACTIVE` enrichments it does not reference
- **THEN** every statement whose own clauses reference that enrichment emits a LEFT JOIN for it on
  the shared grain key, and no join is emitted for the enrichments nothing references

#### Scenario: A bare enrichment column name resolves to the source, not the enrichment

- **WHEN** a pipeline references a bare column name that an `ACTIVE` enrichment of its read source
  declares and the read source does not
- **THEN** the reference is rejected with HTTP 422 and error code `pipeline_validation_failed`,
  naming the qualified spelling — the bare namespace belongs to the read source alone

#### Scenario: Naming an enrichment as the read source is still rejected

- **WHEN** `POST /v1/pipelines` declares an `inputs` entry naming a table of kind `enrichment`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: The pipeline path and the query path agree on a column's value

- **WHEN** the same enrichment column is read through a pipeline's scan and through
  `POST /v1/queries/execute` over the same entity for the same source row
- **THEN** both return the same value, because both resolve the same flat field through the same
  grain join

## ADDED Requirements

### Requirement: The response schema is composed from the target and the evaluator

The system SHALL compose an `llm` evaluator version's JSON response schema from the **pipeline's**
target enrichment and the evaluator's `outputs`, at pipeline registration, patch and read. The
pipeline is where composition happens because it is the only object naming both a target and an
evaluator, and binding the two is definitionally its job; the table SHALL stay ignorant of evaluators
and the evaluator SHALL stay ignorant of targets.

Composition SHALL be driven by the `outputs` **keys**, with each key's structure looked up on the
target column of the same name:

| Schema element | Source |
|---|---|
| property name | the `outputs` key, matched against the column's **exposed `name` only** |
| `type` | the target column's declared type, through the mapping below |
| `enum` values **and order** | the column's `enum_values` verbatim, else the output's `values` |
| `description` | the output's prose |
| property order and `required` order | the `outputs` declaration order |
| `required` | **every** declared output |
| `additionalProperties` | always `false` |

The type mapping SHALL be: `string` → `{"type": "string"}`; `enum` → `{"type": "string", "enum":
[...]}`; `integer` and `long` → `{"type": "integer"}`; `double` and `decimal` → `{"type": "number"}`;
`boolean` → `{"type": "boolean"}`; `timestamp` → `{"type": "string"}` with **no `format`** (strict
schema mode rejects one); `array` → `{"type": "array", "items": <element_type mapped>}`. A column of
any other type — `map`, `json` — SHALL be rejected with HTTP 422 naming the column and its type.

**Property order SHALL be the author's declared order, and SHALL be preserved through storage.** It
is not cosmetic: under strict schema enforcement the model generates properties in schema order, so a
field whose prose instructs it to derive itself from its siblings must be declarable *after* them.
Order SHALL NOT be taken from the target's column order, which is tag-group-then-alphabetical and
would make generation order a function of column tags. A storage form that normalizes key order SHALL
NOT be used for either the composed schema or the stored `outputs`.

`required` SHALL cover every declared output and SHALL NOT be derived from column nullability. Strict
mode requires every property to be required, so there is no optional-output shape to express — and the
two facts differ anyway: an enrichment column is nullable because a row may not be labelled yet, while
a schema field is required because the model must answer. An output that may legitimately be absent
SHALL be expressed as a sentinel value in `values` plus a `jsonata` mapping it to NULL.

Composing the enum domain **from the column** SHALL be preferred to declaring it on the evaluator,
and the consequence SHALL be accepted rather than worked around: enum **order** in the prompt is then
whatever order the column's `enum_values` declare, which is chosen for dashboard sorting. Membership
is the half that carries correctness — a ClickHouse enum column refuses an out-of-domain value at
insert, so composing the domain from the column makes "the model returned a value the column cannot
store" unrepresentable — and an order override on the evaluator would rebuild exactly the
two-copies-of-one-domain drift this composition removes.

An output declaring `values` for a column that already declares `enum_values` SHALL be rejected with
HTTP 422 naming both: a field has exactly one authority for its domain, chosen by which of the two
can hold it. `values` SHALL be understood to buy **schema** enforcement only — a `string` column
accepts anything at insert — and the rejection message for an out-of-domain value SHALL NOT imply
otherwise.

Because output names are matched to column names, a second target for the same evaluator SHALL reuse
the same column names; the 422 for an unmatched output SHALL say so rather than only reporting an
unknown column.

**Residual, stated rather than solved.** After composition the target's schema is a live input to both
the prompt and the write mapping, and a column dropped and re-added changes the composed schema while
`evaluator_version` — the existing per-row provenance column — does not move. Rows labelled either
side of such a change therefore carry no per-row record of which schema produced them.

#### Scenario: A schema is composed from the target's columns

- **WHEN** a pipeline is registered naming a target whose columns include an `enum` column and a
  `decimal` column, and an evaluator declaring an output for each
- **THEN** the composed schema types the first as `{"type": "string", "enum": [...]}` carrying that
  column's `enum_values` in the column's own order, and the second as `{"type": "number"}`

#### Scenario: Property order follows the outputs declaration

- **WHEN** an evaluator declares outputs in the order `data_exposure`, `prohibited_request`,
  `risk_level`
- **THEN** the composed schema's `properties` and `required` carry that order, and it survives a
  round trip through storage and a pipeline read

#### Scenario: Every declared output is required

- **WHEN** an evaluator declares an output bound to a nullable target column
- **THEN** the composed schema lists that property in `required`, and `additionalProperties` is
  `false`

#### Scenario: A domain the column cannot carry is declared on the output

- **WHEN** an output declares `values` for a target column declared plain `string`
- **THEN** the composed schema carries those values as the property's `enum`, and the domain is
  enforced at the model boundary rather than at insert

#### Scenario: A column type with no strict-mode representation is rejected

- **WHEN** an output resolves to a target column of type `map` or `json`
- **THEN** the pipeline registration is rejected with HTTP 422 naming the column and its type

#### Scenario: A timestamp property carries no format

- **WHEN** an output resolves to a `timestamp` target column
- **THEN** the composed property is `{"type": "string"}` with no `format` member

### Requirement: An evaluator version's outputs are one fact in two stored shapes

An `evaluator_version` row SHALL be immutable in storage, and this change SHALL rewrite **no** stored
row of either type. Consequently two shapes of the same fact SHALL coexist in storage indefinitely:
a version registered before this change carries `output_vars` (and, for `llm`, a stored
`response_schema`), and a version registered after it carries `outputs`. Every consumer of an
evaluator's outputs SHALL resolve them through **one accessor** that returns `outputs` when present
and falls back to `output_vars` otherwise.

A single accessor is required rather than recommended, because the consumers are not all validation
paths: one of them feeds the catalog's drop-and-rename dependency guard, where a missed resolution
does not fail a request but silently makes an enrichment column a Class-1 pipeline reads droppable
(see "An ENRICH pipeline's enrichment references are protected against drop and rename").

A stored `response_schema` SHALL keep winning over composition for the version that stores it,
permanently. The composed form is not a faithful reproduction of a stored one — stored enum order,
stored type spelling and the stored property order can each differ from what composition produces —
and under strict schema enforcement property order is generation order, so serving a composed schema
for a stored version would silently change a live prompt.

A stored `sql` version's `output_vars` SHALL likewise be served exactly as stored **on the evaluator
read**, and SHALL NOT be folded into `outputs` there. The reason is a constraint rather than a
preference: this change drops the per-output logical `type`, and an evaluator read names neither a
target nor a source, so a rewritten row could not answer `GET /v1/evaluators/{name}/versions/{v}`
with the type it replaced — and re-deriving that type from the target column would be wrong, because
the type check is compatibility rather than equality (numeric widening and `string` → `enum` both
pass).

The **pipeline** feed is the surface where the two stored shapes are reconciled, and there neither
list is served at all: the inlined evaluator omits `input_vars` and `output_vars`, and the entry's
own `outputs` carries the resolved mapping with each transform folded in, resolved from whichever
shape the version stores. A consumer of the feed therefore never learns which shape it was, which is
the property that lets the two coexist indefinitely.

#### Scenario: An old-shape version reads back exactly as stored

- **WHEN** `GET /v1/evaluators/{name}/versions/{v}` is called for a version registered before this
  change
- **THEN** the response carries its stored `output_vars`, and its stored `response_schema` where it
  has one, unchanged

#### Scenario: A new-shape version reads back as outputs

- **WHEN** `GET /v1/evaluators/{name}/versions/{v}` is called for a version registered after this
  change
- **THEN** the response carries its `outputs` in declaration order

#### Scenario: Both shapes resolve for expression analysis

- **WHEN** a Class-1 pipeline pins a new-shape `sql` evaluator version and a materialize is requested
- **THEN** the expressions are read from `outputs` and analyzed exactly as they would be from
  `output_vars`, and the materialize proceeds

### Requirement: An evaluator's template placeholders are validated against the pipeline's trigger grain

The system SHALL validate an `llm` evaluator's `request_template` placeholders at pipeline register
and enable, and the rule SHALL be **dispatched on the pipeline's `trigger.kind`**, because what a
`vars` entry *is* differs by grain: at row grain it is a top-level placeholder, and at group grain it
is a field of each member object rendered inside `{{members}}`.

- **Row grain** (`on_ingest`, `schedule`): every `{{placeholder}}` in the template SHALL be a `vars`
  key, and every `vars` key SHALL appear as a placeholder. There SHALL be **no** built-in placeholders
  at row grain — the render inputs are exactly the declared bindings. Both directions SHALL be
  enforced with HTTP 422 `pipeline_validation_failed` naming the offending name: an unmatched
  placeholder is left in the prompt **literally** rather than rendered empty, and an unused `vars` key
  projects a column, possibly a heavy one, for nothing.
- **Group grain** (`group`): every `{{placeholder}}` SHALL be one of the built-in member variables
  below, and `{{members}}` SHALL appear **at least once** — without it the model receives a prompt
  carrying no data at all. `vars` keys SHALL NOT be checked against the template, in either
  direction: they are consumed as member fields inside `{{members}}` and a valid group template names
  none of them.

The **built-in member variables** SHALL be exactly five: `members`, `members_truncated`,
`members_included`, `members_omitted` and `members_notice`. ADAS SHALL own this list as its own wire
contract and SHALL assert the five names as literals, so that adding a sixth is a deliberate
two-repo change rather than a valid template that fails registration. A list short of all five would
refuse a template that renders correctly.

#### Scenario: A row-grain template placeholder with no vars key is rejected

- **WHEN** an `on_ingest` pipeline's evaluator template carries `{{requeest}}` while `vars` declares
  `request`
- **THEN** the registration is rejected with HTTP 422 `pipeline_validation_failed` naming the
  placeholder

#### Scenario: A row-grain vars key that no placeholder uses is rejected

- **WHEN** an `on_ingest` pipeline declares a `vars` key that appears in no placeholder
- **THEN** the registration is rejected with HTTP 422 naming the key

#### Scenario: A built-in placeholder at row grain is rejected

- **WHEN** an `on_ingest` pipeline's template carries `{{members}}`
- **THEN** the registration is rejected with HTTP 422, because the row-grain render supplies only the
  declared bindings

#### Scenario: A group template using all five built-ins is accepted

- **WHEN** a `group` pipeline's template carries `{{members}}`, `{{members_truncated}}`,
  `{{members_included}}`, `{{members_omitted}}` and `{{members_notice}}`
- **THEN** the registration is accepted

#### Scenario: A group template with no members placeholder is rejected

- **WHEN** a `group` pipeline's template carries no `{{members}}`
- **THEN** the registration is rejected with HTTP 422

#### Scenario: A group pipeline's vars need not appear in its template

- **WHEN** a `group` pipeline declares four `vars` keys and its template mentions none of them
- **THEN** the registration is accepted, and a disable/enable cycle of that pipeline re-validates and
  re-arms it unchanged

### Requirement: Registering an evaluator version re-validates its unpinned dependent pipelines

On registering a new evaluator version, the system SHALL re-run composition and output resolution
against the target of **every pipeline that would resolve to it** — every pipeline naming this
evaluator whose `evaluator_version` is null, so `latest` moves under it — and SHALL reject the
registration with HTTP 422 naming the pipeline when composition fails. Without this, an `outputs` key
matching no target column is accepted at registration and refused only at the next pipeline write.

For each such pipeline of the `llm` type the system SHALL bump `generation` **and** `updated_at` in
the same transaction, and SHALL re-derive and persist its output mapping. The new prose and the new
output set are otherwise invisible to a consumer that detects change by `generation` alone. The bump
is safe: a generation change makes a consumer reschedule its recurring work, and its cursor state is
not generation-scoped, so nothing is re-scanned and nothing is re-labelled.

The bump SHALL be **scoped to the `llm` type**. A Class-1 pipeline SHALL NOT be bumped: ADAS
re-resolves its bindings and re-analyzes every expression on each materialize call rather than
trusting the registration gate, so a bump would buy nothing — and a generation change on that kind
discards the pipeline's materialized position, which holds every consumer of its target enrichment
back entirely until the next materialize re-records one.

A pipeline that **pins** an `evaluator_version` SHALL be unaffected by another version's
registration. A version staged for a pinned pipeline is therefore accepted **unvalidated**, and the
422 arrives when the pin is moved — which costs nothing, because a patch validates before it bumps,
but means the preview is what an author has to lean on before pinning.

#### Scenario: A new version whose outputs do not resolve is rejected naming the pipeline

- **WHEN** `POST /v1/evaluators` registers a new version declaring an output matching no column of the
  target of a pipeline that names this evaluator with no `evaluator_version`
- **THEN** the request is rejected with HTTP 422 naming that pipeline, and no version is created

#### Scenario: A new version bumps its unpinned llm dependents

- **WHEN** `POST /v1/evaluators` registers a new version that composes cleanly, and an `llm` pipeline
  names this evaluator with no `evaluator_version`
- **THEN** that pipeline's `generation` and `updated_at` both advance in the same transaction, and its
  stored output mapping is re-derived

#### Scenario: A new version does not bump an unpinned Class-1 dependent

- **WHEN** the dependent pipeline resolving to the new version is a `sql` pipeline
- **THEN** its `generation` is unchanged and its materialized position is preserved, and the next
  materialize picks up the new expressions anyway

#### Scenario: A version staged for a pinned pipeline is accepted unvalidated

- **WHEN** `POST /v1/evaluators` registers a new version while every pipeline naming that evaluator
  pins an explicit `evaluator_version`
- **THEN** the version is created, nothing is bumped, no pipeline is re-validated, and a later patch
  moving a pin is where a composition failure is reported

### Requirement: An enrichment target's column change is notified to the registry

The system SHALL increment a pipeline's `generation` **and** advance its `updated_at` when
`PATCH /v1/tables/{name}/schema` adds, drops or renames a column of the **enrichment** that pipeline
targets, in the same transaction as the catalog write. After composition the target's column list
is a live input to both the composed schema and the derived output mapping, while `generation` is a
consumer's only change-detection token on a feed with no `ETag` — so without the notification an added
column would never reach the runner and would simply never be written, silently and indefinitely.
Both members, not only `generation`: the sync feed selects on `updated_at`, so a bumped generation the
feed would not return is no signal at all.

The system SHALL also **re-derive and persist** that pipeline's output mapping in the same
transaction, so the stored mapping cannot go stale against the target it describes.

The notification SHALL be **scoped to pipelines whose evaluator type is `llm`**. For a Class-1
pipeline it SHALL NOT fire: ADAS re-resolves the bindings and re-analyzes the expressions on every
materialize call, so a new target column is picked up on the next tick with no signal needed — while a
generation change on that kind discards `materialized_through_version`,
`materialized_through_identity` and `drained_at`, which holds every consumer of that enrichment back
entirely until the next materialize re-records a position. Nothing is re-scanned — as above, the
cursor is not generation-scoped — but without the scoping, adding one column would cost a clamp
blackout it did not cost before this change.

A **metadata-only** patch SHALL bump nothing: under composition an output's prose comes from the
evaluator rather than the column, and a column's type and `enum_values` are immutable already.

#### Scenario: Adding a column to an llm pipeline's target bumps it

- **WHEN** `PATCH /v1/tables/{name}/schema` adds a column to an enrichment that is an `llm` pipeline's
  target
- **THEN** that pipeline's `generation` is incremented and its `updated_at` advances in the same
  transaction, its stored output mapping is re-derived, and the next feed read carries both

#### Scenario: Adding a column to a Class-1 pipeline's target bumps nothing

- **WHEN** `PATCH /v1/tables/{name}/schema` adds a column to an enrichment that is a `sql` pipeline's
  target
- **THEN** that pipeline's `generation` is unchanged and its `materialized_through_version` is
  preserved
- **AND** if the pipeline's evaluator declares an output of that name, the next materialize writes the
  new column

#### Scenario: A metadata-only patch on an enrichment target bumps nothing

- **WHEN** `PATCH /v1/tables/{name}/schema` changes only a column's `description` or `display_name`
  on an enrichment that is a pipeline's target
- **THEN** no pipeline's `generation` or `updated_at` changes

### Requirement: Dry-run preview of an enrich declaration

The system SHALL extend `POST /v1/pipelines/{name}/preview` to the `enrich` kind. For such a pipeline
preview SHALL return the composed `response_schema` (or the stored one, where the pinned version has
one), the resolved output mapping, the expanded `vars` bindings, and a bounded **sample of the input
values** the evaluator would receive. It SHALL write nothing to the target and SHALL call no model,
and SHALL be permitted on a disabled pipeline — it is the checkpoint an author has before arming, and
the only checkpoint at all for a version staged against a pinned pipeline.

Preview SHALL NOT return a rendered prompt. The template renderer and the group-input assembler live
in the runner, and their escaping is what determines what the model actually receives; a second
implementation in ADAS would be the last place to accept drift. A caller wanting the rendered request
uses the runner's own dry-run surface.

Preview SHALL read through the **already-bounded** governed read paths, with an explicit row cap and
version window, rather than through the aggregate preview's compiled-statement path. The aggregate
preview's budget bounds memory and time but not rows read, and an `llm` enrich preview reads the
heavy body columns that are refused unbounded and whose group-assembly caps apply only after the read.

For a **`group`** pipeline preview SHALL take the group key to sample from an optional request body,
and SHALL report which key it used; there is no group-discovery surface in ADAS, so a group preview
with no key named SHALL report that rather than sampling an arbitrary group. For a row-grain pipeline
the body MAY carry a cursor.

The enrich response SHALL be a **sibling** member of the preview response rather than a reuse of the
aggregate members: an aggregate preview answers with a compiled statement and rows, and an `enrich`
preview answers with a schema, a mapping and sampled inputs.

#### Scenario: Preview of a Class-1 pipeline returns the mapping and the sampled values

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `on_ingest` pipeline whose
  evaluator is `sql`
- **THEN** the response carries each target column with the expression bound to it, that expression's
  translated SQL and inferred type, and a bounded sample of computed values, and the target is
  unchanged

#### Scenario: Preview of an llm pipeline returns the composed schema and inputs

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `llm` pipeline
- **THEN** the response carries the composed `response_schema` in declaration order, the resolved
  output mapping, the expanded `vars`, and a bounded sample of the input values — and no model is
  called

#### Scenario: A group preview names the group it sampled

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a `group` pipeline with a request body
  carrying a group key
- **THEN** the response samples that group's member rows and reports the key it used

#### Scenario: A group preview with no key named reports that rather than guessing

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a `group` pipeline with no group key
- **THEN** the response reports that a group key is required rather than sampling an arbitrary group
