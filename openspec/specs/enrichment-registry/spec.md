# enrichment-registry Specification

## Purpose

The control-plane surface for enrichment: the **`enrich` kind** of pipeline, which declares how a value
is produced and binds it to a target enrichment. The registry object such a pipeline is — CRUD over
`/v1/pipelines`, the identity rules, one writer per target, the lifecycle, the listing's envelope and
ordering, and the error codes — belongs to [pipeline-registry](../pipeline-registry/spec.md); what this
capability owns is the `enrich` declaration's **transform** and the declaration's own governance.

A transform is declared on the pipeline itself, as the `transform` block of its declaration — either an
LLM call (`type=llm`, `chat_completion` preset) or a deterministic in-database derivation (`type=sql`,
see [class1-enrichment](../class1-enrichment/spec.md)). It is versioned by the pipeline's own
`generation` and attributed by the pipeline's audit trail; there is no separately registered evaluator
object, and no `/v1/evaluators` surface. An `enrich` pipeline writes a single target enrichment,
derives its grain from that enrichment, declares (or defaults) the single **read source** it derives
values from, and passes a static governance gate (catalog, read source, filter, transform, trigger,
plus the transform-type-specific checks) with sensitive-column entitlement before it can be registered or
enabled. It fires at row grain (`schedule`, on its `trigger.cron`) or at **group** grain (`group`, one label per
group key, declared with `trigger.group_by` and a `trigger.ready_when` readiness predicate).

The feed the Enrichment Runner syncs is
`GET /v1/pipelines?kind=enrich&enabled=true&view=compiled`: the same listing every operator reads,
narrowed to the set a runner is to execute and served in the compiled projection, with each entry
carrying its authored `transform` block, the resolved `response_schema` the model is held to, its
derived grain key and version column, and a `generation` value for change detection.
## Requirements

### Requirement: No evaluator surface remains

The system SHALL expose no route under `/v1/evaluators` and SHALL store no evaluator definition: an
enrichment's definition exists only as its pipeline's `transform`, versioned by the pipeline's `generation`
and attributed by the pipeline's audit trail. A request to any `/v1/evaluators` path SHALL be answered
exactly as a request to any other unmapped path is — for `GET`, HTTP 404 `not_found` in the `ErrorView`
shape — and the error codes `unknown_evaluator` and `evaluator_immutable` SHALL NOT be produced by any
route.

The `evaluator` and `evaluator_version` tables SHALL NOT exist in the control-plane store, and neither
SHALL the `pipeline.evaluator_name` / `pipeline.evaluator_version` pointer columns or their `pipeline_aud`
mirrors. The definitions those tables held are not carried anywhere else: a pre-fold prompt, and what an
already-stamped `evaluator_version` provenance value referred to, SHALL be understood to be unanswerable by
the service. The `evaluator_version` provenance column on an existing enrichment target is a separate
matter and is unaffected (see [enrichment-runtime](../enrichment-runtime/spec.md), "Pipeline-scoped
versioned write-back endpoint").

A pipeline request naming `evaluator_name` or `evaluator_version` SHALL keep being refused at the binding,
as specified in "ENRICH pipeline declaration binding a transform to a target enrichment".

#### Scenario: The evaluator listing is an unmapped path

- **WHEN** `GET /v1/evaluators` is called by a caller holding `REGISTRY_READ`
- **THEN** the response is HTTP 404 with error code `not_found`

#### Scenario: A version path is answered as an unmapped path

- **WHEN** `GET`, `PUT` or `DELETE` is sent to `/v1/evaluators/{name}/versions/{version}`
- **THEN** each is answered as the same method on an unmapped path is answered, the `GET` with HTTP 404
  `not_found`, and none answers `evaluator_immutable` or `unknown_evaluator`

#### Scenario: No evaluator table or pointer column remains

- **WHEN** the control-plane migrations have run
- **THEN** neither an `evaluator` nor an `evaluator_version` table exists, and neither `pipeline` nor
  `pipeline_aud` carries an `evaluator_name` or `evaluator_version` column

### Requirement: Static governance gate on arming and execution

The system SHALL statically validate an `enrich` pipeline's declaration at every moment the
declaration is about to mean something and has not already been judged — a write that leaves the
pipeline **armed** (`enabled: true`, whether by `POST /v1/pipelines`, `PATCH /v1/pipelines/{name}` or
the enable path) and `POST /v1/pipelines/{name}/preview` of a **disabled** pipeline. It SHALL NOT run
on a write that leaves the pipeline disabled, nor on a preview of a pipeline that is **enabled**: an
armed pipeline's declaration was gated at the instant it was armed, so re-asking would refuse a
preview for drift the operator did not cause. The two halves of that rule are one rule read twice —
the gate runs where a declaration is about to act and nothing has yet vouched for it. Each failure
SHALL be rejected with HTTP 422 (except entitlement and immutability, which keep their own codes) with
detail identifying the failing check. Throughout this requirement a **gated request** means one of
those two moments, and every scenario below that says "the request is rejected" means a gated request;
the same declaration written to a disabled pipeline is stored without complaint, because an author
composing a declaration is at a stage rather than in error.

**The four data-plane entry points SHALL NOT run this gate.** `…/scan`, `…/group-rows`,
`…/materialize` and `…/rows` refuse a declaration whose required members are **absent**, and
`…/materialize` additionally re-checks the result-type fit against the bound target column and the
row-grain rule against the read source's `identity_column`. Nothing else of this gate reaches them,
because importing it would stop a running pipeline — or a paused one's reads — for drift the operator
did not cause and cannot see from there. The two writing entry points, `…/materialize` and `…/rows`,
additionally refuse a disabled pipeline with HTTP 409, so nothing is written from a declaration that
was never armed (see
[enrichment-runtime](../enrichment-runtime/spec.md), "The governed data plane refuses an absent
declaration, and materialize re-checks two rules"). A refusal one of those endpoints **already owns**
unconditionally is untouched by this and stays unconditional in either state — the no-transform
refusal below, and the null `trigger.group_by` crash fix — because turning a server fault into a
refusal is not a new gate.

**The gate has two halves and only one of them is deferred.** *Derivation* — resolving the output
mapping from the declaration's outputs against the target's columns — SHALL run on **every**
declaration write, armed or not, best-effort: where it resolves it replaces the stored mapping, and
where it cannot (no transform yet, an output naming a column that does not exist) it SHALL leave the
stored mapping untouched rather than writing an empty one. This is not politeness towards the author;
`output_bindings` is the only record of which columns the pipeline writes, and the schema patch guard
that protects those columns resolves them from it (see "A column an enrich pipeline writes is
protected against drop and rename"), so a write that erased the derived mapping would disarm that
guard and let a column the pipeline writes be dropped. *Refusal* — every check that throws — is the
half that runs only on a gated request. A derived member SHALL be stored as an **empty collection,
never null**, so a partially authored declaration has one representation everywhere.

Every refusal this gate produces SHALL carry the one code **`pipeline_validation_failed`**, whether
the problem is a declaration member that is **absent** or one that is present and defective, and SHALL
report **one problem per refusal**, which is the shape every other refusal on this surface already
has. An author arming a half-finished declaration is therefore told what to fix one round trip at a
time, in the messages the gate already writes — "a transform requires at least one output",
"trigger.kind is required". A single response carrying every problem at once, keyed by field, is a
payload of its own and belongs to a change of its own.

**The entitlement check moves with the refusal half, and this requirement SHALL say what that is worth
on each surface rather than describe a caller who cannot exist.** Entitlement to `sensitive` columns
*is* `FULL_ADMIN`: one predicate decides it, admitting every caller when security is off and exactly the
`FULL_ADMIN` role otherwise. The authoring surfaces — `POST /v1/pipelines`, `PATCH /v1/pipelines/{name}`,
`…/preview` and `…/refresh` — require that same role at the method-security layer, which refuses a
caller without it with HTTP 403 `forbidden` before the declaration gate runs. So on those surfaces the
in-gate `sensitive_column_not_entitled` is **unreachable**: no caller reaches it who can fail it, in
either security mode, and a non-entitled author who stores a disabled declaration and is refused when
they arm it is not a state this capability can reach. The check SHALL be kept and SHALL move with the
rest of the refusal half as **defence in depth** against a future narrowing of who may author a
pipeline — the day a role short of `FULL_ADMIN` may write to the registry, this is the rule that decides
what it may reference. Where it is **load-bearing today** is the data plane: the scan and
group-assembly endpoints, `/materialize` and `/rows` are reachable by an identity holding the target
enrichment's WRITE grant without `FULL_ADMIN`, and re-apply the check against the calling identity on
every call (see [enrichment-runtime](../enrichment-runtime/spec.md)) — that is where a
`sensitive_column_not_entitled` refusal is actually produced. Storing a disabled declaration that names
a sensitive column reads nothing: the derivation half reads the target's column list only, and the
scheduler never selects a disabled pipeline.

The gate SHALL enforce: (1) the
`target` exists, is `ACTIVE`, and is kind=enrichment, and every one of the declaration's **resolved**
outputs names a column that exists on it, at a type the shared JSON→ClickHouse type-mapping util
accepts; (2) the pipeline's **read source** resolves — a declared `inputs` entry names an existing `source`-kind
table, and an omitted one defaults to the target enrichment's `source_table` — and satisfies the
grain-key-reachable and scan-metadata invariants (see "A pipeline's read source is declarable");
(3) `filter` parses under the existing Calcite SQL front-end, is boolean over the read source's
**entity fields**, uses the bounded grammar (no joins/subqueries/CTEs), and is **entity-scoped** (see
below), with referenced columns extracted; (4) the `transform` block is present and internally
consistent for its `type`; (5) `trigger.kind ∈ {schedule, group}` and the trigger's
declaration is internally consistent; (6) the type-specific checks; (7) the group-trigger checks below.

"**Resolved** outputs" is load-bearing wherever it appears in this requirement: outputs are read
through the single accessor that returns `transform.outputs` when present and `transform.output_vars`
otherwise (see "A transform's outputs are one fact in two stored shapes"). The gate SHALL require at
least **one resolved output**, never at least one `outputs` entry — a declaration folded from an
old-shape evaluator version has no `outputs` at all, and a gate that demanded that member would refuse
every pipeline the fold migration produced.

**Entity-scoping.** Every governed SQL fragment a pipeline carries — `filter`, `trigger.ready_when.signal`,
`trigger.member_select.prefer_sql`, and a `sql` transform's output expressions — is validated by embedding it in
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
clause, which ClickHouse rejects at execution. Rejecting both on every gated request keeps the
entitlement gate total — no declaration runs without having passed it — and turns an execution
failure into the validation failure it always was.

The gate SHALL be **dispatched on the declaration's `transform.type`**:

- **`llm`** — the declaration SHALL carry a non-blank `model`; and every `transform.inputs` JSONata
  expression and every resolved output's `jsonata` SHALL parse (static parse only — runtime evaluation
  is the runner's). The `request_template`'s placeholder check needs **no carve-out any more** — see
  "A transform's template placeholders are validated against the pipeline's trigger grain", which owns
  the rule and times it to "armed or previewed". That was the one check already living at the arming
  boundary, and the carve-out existed only because the rest of this gate ran on register; with the
  whole refusal half timed the same way the two agree by construction.
- **`sql`** — `model` SHALL NOT be required and JSONata SHALL NOT be parsed (there is none); every
  resolved output expression SHALL pass Class-1 expression governance (see
  [class1-enrichment](../class1-enrichment/spec.md)); the pipeline SHALL declare no `transform.inputs`, SHALL
  declare at least one output resolving to a column on the target, and it SHALL target
  an enrichment keyed on the **read source's**
  identity column; and the `group` trigger SHALL be rejected, because a group-grain deterministic pipeline
  is an aggregation.

Because the source is now always in hand wherever an expression is read, a `sql` transform's
expressions SHALL be validated at **every gated request** — the arming write and a disabled pipeline's
preview — and an expression naming a column the
read source does not have SHALL be refused there rather than at the next materialize. What this loses against the earlier
"validated at registration, patch and read alike" is only the moment: the expression is still never
executed unvalidated, and the author who wants the answer before arming has preview, which on a
disabled pipeline runs this check in full.

**"At read" SHALL NOT mean that a read of the declaration can be refused.** The fold migration copies a
stored declaration verbatim by design, so it can produce a declaration this gate rejects — an
`array<string>` type token in a folded `output_vars`, an expression naming a column the source has since
lost. A read that refused such a pipeline would make it unrepairable, because `GET … ?view=source` is
the only way to obtain the body a patch would fix it with. `GET /v1/pipelines/{name}` and
`GET /v1/pipelines` SHALL therefore always serve the authored declaration, and a validation failure
encountered while resolving the **compiled** projection SHALL omit that entry from a listing and log it
(as the feed already does for an unresolvable mapping) rather than failing the whole read. Refusal
belongs to an arming write, a preview and an execution.

An **incomplete** declaration reaches that same omission by a different road, and SHALL NOT be logged
as an error when the pipeline is disabled: a disabled pipeline whose compiled projection does not
resolve is the expected state of a declaration being authored, not a fault, and a listing that logged
one line per draft would bury the drifted declarations the log exists to surface. The compiled
projection of a single named pipeline is a different question — the caller asked for that pipeline's
compiled form specifically — and is refused rather than emptied; the `pipeline-registry` capability
owns that refusal and its code.

The **output mapping SHALL be derived, not declared**. `output_bindings` SHALL NO LONGER be accepted
on `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` — it is an unrecognized member, refused with
HTTP 400 `bad_request` naming the field — and the mapping SHALL instead be resolved by matching each
of the declaration's resolved output names against the target enrichment's columns. The gate SHALL
derive it on **every** declaration write — register, patch, arming or not — and SHALL persist it with
the declaration, so every write path keeps an exact allow-list of the columns the pipeline may write.
Derivation is the half that is never deferred precisely because that allow-list is load-bearing
elsewhere: where the outputs do not yet resolve, the write SHALL keep the mapping it had rather than
replace it with an empty one, so the columns a pipeline writes stay declared through every
intermediate save of an edit.

A derived binding's `column` SHALL be **the target enrichment's physical column name** (its
`source_name`). Both write paths address the target physically — the Class-1 materialize statement
renders the column list into an `INSERT`, and the pipeline-scoped write-back reconciles caller rows
against the target's physical columns — so resolving the spelling at declaration time is what keeps a
renamed column writable instead of failing at execution. A pipeline read back through the API therefore
reports the physical column.

The mapping SHALL be **one-to-one** by construction: an output name SHALL be declared at most once, and
it SHALL be matched against the target column's **exposed `name` only** —
deliberately not against `source_name` as well, which would make a name ambiguous in principle and
could resolve two outputs onto one physical column. A name matching only a column's `source_name` SHALL be
rejected with HTTP 422 naming the exposed spelling to use.

A **`transform.inputs` entry's** `column` SHALL name a field of the read source's entity, under the same
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
"`schedule` stays row-grain": a grain key carries no row-vs-group marker, so the gate constrains
the *declaration* rather than inferring the grain's nature. 3. `trigger.ready_when.signal`, when
present, SHALL parse under the same bounded Calcite grammar as `filter`, SHALL be boolean over the
**read source's entity fields**, and its referenced columns SHALL run the sensitive-column
entitlement check. 4. `trigger.ready_when.idle` and `trigger.ready_when.max_staleness`, when
present, SHALL parse as positive durations (both the short form `10m` and ISO-8601 `PT10M` are
accepted); `trigger.ready_when.cost_ceiling`, when present, SHALL be a positive integer. 5.
`trigger_kind = group` ⇒ `trigger.ready_when` SHALL declare at least one of `signal`, `idle`, or
`max_staleness`. A readiness declaration with none would mark a group dirty forever and never ready,
so the pipeline would silently do nothing.

Note (the model check): the gate does NOT verify that an `llm` transform's `model` names a
live DIAL deployment — ADAS has no deployment-listing client to reuse (only caller-key
introspection). Live deployment verification is deferred; only a non-blank model is enforced here.

#### Scenario: An output naming no target column is rejected

- **WHEN** a declaration carries an output whose name matches no column of the
  target enrichment
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` naming
  the output, and stating that an output resolves to a target column of the same name

#### Scenario: An output whose target column has an incompatible type is rejected

- **WHEN** a declaration carries an output binding to a target column of a type the
  shared type mapping does not accept
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A derived binding names the column's physical spelling

- **WHEN** a declaration carries an output matching a target column whose logical `name`
  differs from its `source_name`
- **THEN** the pipeline is accepted, the derived and returned binding names the column's
  `source_name`, and both write paths address the column ClickHouse actually carries

#### Scenario: An output matching only a column's source_name is rejected

- **WHEN** a declaration carries an output name matching a target column's `source_name`
  but not its exposed `name`
- **THEN** the request is rejected with HTTP 422 naming the exposed spelling to declare instead

#### Scenario: A declared output_bindings member is refused by name

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries `output_bindings`, whether or
  not it agrees with what would have been derived
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is
  persisted

#### Scenario: A target column no output writes keeps its existing values

- **WHEN** a target enrichment carries a column that the declaration declares no output for
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

- **WHEN** a pipeline declares a `transform.inputs` entry bound to `"usage_client_identity.client_type"`
- **THEN** the pipeline is accepted, and the binding is stored under that qualified name

#### Scenario: A vars entry naming no entity field is rejected

- **WHEN** a pipeline declares a `transform.inputs` entry bound to a column reachable on neither the read
  source nor its `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A missing model on an llm declaration is rejected

- **WHEN** a declaration's `transform.type` is `llm` and its `model` is blank or absent
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Disallowed trigger kind is rejected

- **WHEN** a pipeline declares a `trigger.kind` outside `{schedule, group}` (e.g.
  `push`), or declares `group` for a `transform.type: sql`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: The retired on_ingest trigger kind is refused naming its replacement

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` declares `trigger.kind: "on_ingest"`,
  armed or not
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, the
  message naming `on_ingest` as retired and `schedule` as the row-grain trigger to declare instead,
  and nothing is persisted

#### Scenario: Unparseable JSONata expression is rejected

- **WHEN** any `transform.inputs` or resolved-output JSONata expression of an `llm` pipeline fails to parse
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sql pipeline is validated without a model or JSONata

- **WHEN** a pipeline declares `transform.type: sql`, carrying no `model` and no JSONata
- **THEN** the model and JSONata checks are skipped, the `sql` expressions are validated instead,
  and a well-formed declaration is accepted

#### Scenario: A group key not resolving to the target grain is rejected

- **WHEN** a `group` pipeline declares a `trigger.group_by` that neither equals the target
  enrichment's grain key nor is the qualified flat name of an enrichment column whose last segment
  is that grain key
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A group key or readiness declaration on a non-group trigger is rejected

- **WHEN** a `schedule` pipeline declares `trigger.group_by` or `trigger.ready_when`
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

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) arms or previews a `group` pipeline whose
  `trigger.ready_when.signal` or `filter` references a `sensitive` column of the pipeline's read source
- **THEN** the pipeline is accepted

#### Scenario: A caller who may not see sensitive columns cannot author the declaration at all

- **WHEN** a caller who may not see sensitive columns registers, patches, previews or refreshes the same
  declaration, or any other
- **THEN** the request is refused with HTTP 403 `forbidden` by method security before the declaration
  gate runs, so the in-gate `sensitive_column_not_entitled` is unreachable from the authoring surfaces

#### Scenario: A FULL_ADMIN author may store the same declaration while it stays disabled

- **WHEN** a `FULL_ADMIN` caller saves the same declaration with `enabled: false`
- **THEN** the write is accepted and nothing is read from the sensitive column, because a disabled
  pipeline is never scheduled and its derivation reads the target's column list only

#### Scenario: A folded legacy declaration passes the gate on enable

- **WHEN** an `enrich` pipeline whose `transform` carries `output_vars` and no `outputs` is enabled
- **THEN** the gate resolves its outputs through the single accessor, finds them, validates them
  against the target, and accepts

#### Scenario: A declaration with no resolvable output at all is refused when it arms

- **WHEN** a write leaves a pipeline armed whose `transform` carries neither `outputs` nor
  `output_vars`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the absent
  outputs, and the pipeline is left as it was

#### Scenario: The same declaration is stored while the pipeline stays disabled

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` stores that declaration with the
  pipeline disabled
- **THEN** the write is accepted and the declaration is stored as given, because an author who has
  not written the outputs yet is at a stage rather than in error

#### Scenario: Arming a declaration missing several members names one of them

- **WHEN** a write arms a pipeline whose declaration is missing several independent members — no
  trigger kind and no resolvable output, say
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming one of them, and
  the pipeline is left as it was; the remaining member is named by the next attempt to arm it

#### Scenario: A non-arming write never erases the derived mapping

- **WHEN** a disabled pipeline whose `output_bindings` are derived and stored is patched with a
  declaration whose outputs do not resolve against the target
- **THEN** the write is accepted, the previously derived mapping is kept rather than replaced with an
  empty one, and the columns that pipeline writes stay protected against drop and rename

#### Scenario: A sql expression naming an unknown source column is refused when it arms or is previewed

- **WHEN** a write arms a pipeline, or a disabled pipeline is previewed, whose `sql` transform's
  expression references a column the pipeline's read source does not have
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the expression
  and the column, rather than at the next materialize

### Requirement: Sensitive-column entitlement

A pipeline SHALL reference only columns the caller is permitted to use, in its `filter`, `bound` input
bindings, `trigger.group_by`, `ready_when.signal`, `member_select` policy and `sql` output expressions,
and in the target's grain key. `trigger.group_by` and the grain key are named because they were
once the two references that joined the gate's reference set unexamined, and both are copied into the
target: the grain key is read by every derivation and stored as the target's key, so a sensitive one is
copied into the target on every row. A column flagged `sensitive` SHALL be usable in a pipeline only by a caller
entitled to see it — that is, one holding `TABLE_READ` and `TABLE_SENSITIVE` **on the table that owns
the column**, each globally or granted on that table, or any caller when `security.mode` is `none` — mirroring the filter
applied on the query surface, which is likewise decided per owning table. A pipeline
referencing a `sensitive` column by a caller not so entitled SHALL be rejected with HTTP 403
`sensitive_column_not_entitled`.

**This is now a second, independent requirement on the author, and that is a change.** While register
and enable were full-admin-only, a legitimately authorized author always satisfied the entitlement check
as a side effect; register and enable now require `REGISTRY_ADMIN`, which confers no table privilege at
all. So an author mapped to `REGISTRY_ADMIN` alone is refused `403 sensitive_column_not_entitled` on a
sensitive-referencing declaration where the same authority previously always succeeded, and must also hold
`TABLE_READ` and `TABLE_SENSITIVE` on the read source — each globally or by a grant. That is the point of separating the
two: authoring what a pipeline computes and being entitled to the values it reads are different
authorities, and the second is now grantable per table rather than only as part of a bundle. The same rule SHALL be re-applied on the
runner's read path (see the enrichment scan): a scan whose projection includes a `sensitive` column
SHALL be rejected with the same status and code unless the scanning caller is entitled, so a
runner without that entitlement cannot read sensitive data even though the stored declaration
references it.

The author's entitlement is checked when the gate runs and is **not** re-checked afterwards: an enabled
pipeline keeps running as declared even if a column it reads is later marked `sensitive`, or its author
loses the entitlement. No approval is stored for a later read to be compared against. This is a deliberate
scope decision. An operator who marks a column `sensitive` and wants its derived copies stopped disables
the pipelines that read it; enabling one again runs the gate against whoever enables it.

#### Scenario: Sensitive reference by an entitled caller is accepted

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the read source (or any caller in
  `none` mode) registers a pipeline whose filter or bound input binding references a `sensitive`-flagged source
  column
- **THEN** the entitlement check passes

#### Scenario: Sensitive reference by a non-entitled caller is rejected

- **WHEN** a caller who may not see sensitive columns references a `sensitive`-flagged source column
  in a pipeline's filter or a bound input binding
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: Non-sensitive reference is accepted

- **WHEN** a pipeline's filter and bound bindings reference only non-`sensitive` source columns
- **THEN** the entitlement check passes

### Requirement: Sensitive-column entitlement crosses the grain join

The sensitive-column rule SHALL apply to an enrichment column exactly as it applies to a read-source
column. A `sensitive` column of an `ACTIVE` enrichment of the pipeline's read source SHALL be
referenceable only by a caller holding `TABLE_READ` and `TABLE_SENSITIVE` **on that enrichment** —
each globally or granted on it — and a reference by any other caller SHALL be rejected with HTTP 403
`sensitive_column_not_entitled`.

Per owning table is what makes this a real rule rather than a restatement. Entitlement to the read
source's sensitive columns no longer implies entitlement to its enrichments': within one flattened entity
a caller may be entitled to the source's and not an enrichment's, or the reverse, and each column is
judged against the table it belongs to.

Because such a column is **hidden** from a non-entitled caller's schema view rather than forbidden
to it, a governed fragment naming one fails to parse for that caller. That failure SHALL be
re-attributed to HTTP 403 `sensitive_column_not_entitled` rather than reported as HTTP 422
`pipeline_validation_failed`: the pipeline is not malformed, the caller is not entitled, and the
contract promises the 403.

The pipeline governs **who may reference** the column, and nothing more. A derived enrichment column
SHALL NOT inherit sensitivity from an enrichment column any more than it does from a source column —
the existing "Runner identity and derived-column sensitivity posture" requirement applies unchanged,
and this capability SHALL NOT introduce an exception to it for a value that happens to arrive
through a grain join. Controlling a derived column's exposure remains the enrichment author's
decision, taken by declaring that target column `sensitive`.

#### Scenario: A sensitive enrichment column is accepted for an entitled author

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on that enrichment (or any caller in
  `none` mode)
  registers a pipeline referencing a `sensitive` column of an `ACTIVE` enrichment of its read source
- **THEN** the pipeline is accepted

#### Scenario: A sensitive enrichment column is refused for a non-entitled author

- **WHEN** a caller who may not see sensitive columns registers the same declaration
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not
  with HTTP 422 reporting an unknown column

#### Scenario: A derived column does not inherit an enrichment column's sensitivity

- **WHEN** a `type=sql` pipeline's output expression reads a `sensitive` enrichment column and binds
  the result to a target column that is not itself `sensitive`
- **THEN** the pipeline is accepted and the target column's `sensitive` flag is left as the table
  author declared it, exactly as for a `sensitive` column of the read source — an enrichment column
  reached through the grain join is governed no differently from one the source declares

### Requirement: Runner identity and derived-column sensitivity posture

The Enrichment Runner SHALL hold `TABLE_READ` and `TABLE_SENSITIVE` on a pipeline's read source for
any pipeline that references a `sensitive` column of it — each globally or **granted on that table**. The entitlement
check itself is unchanged: it is applied at pipeline registration and again at every read/derive egress,
against the **calling** identity, per owning table.

What changed is that a narrower grant now exists. The original least-privilege stance ("per-target grants
only, no global admin") was superseded for these pipelines only because `SensitiveColumnAccess` admitted
nothing but the full administrative role; with the privilege grantable per table, a runner operating a
sensitive-touching pipeline needs `REGISTRY_READ` for the sync feed plus three per-table grants —
`read` and `sensitive` on the read source (the clearance confers no read on its own), and `write` on
the target — and no global authority at all. The
least-privilege stance therefore stands for every pipeline again.

What bounds such a runner is unchanged, and no longer has to carry the argument alone: the surface stays
pipeline-scoped in both directions: a scan projects only declaration-referenced columns, a
group fetch returns only the pipeline's membership, and a write-back rejects any column outside the
pipeline's declared outputs — the entitlement changes *which columns a pipeline may reference*, not
*which pipelines the runner may operate*. The per-table `read` + `sensitive` grant pair **is** the narrower
sensitive grant this requirement named as a deferred follow-up; a per-pipeline one remains out of
scope.

Derived enrichment columns SHALL NOT inherit sensitivity. A pipeline whose filter, input bindings, or
`sql` expressions reference a `sensitive` source column and whose output bindings target a
**non-`sensitive`** enrichment column SHALL be accepted as registered: the system SHALL NOT mark the
target column `sensitive` as a side effect, and SHALL NOT reject the pipeline. Pipeline registration never
mutates catalog metadata, because doing so would silently change which callers can read an existing
column. The consequence is explicit and accepted: a value derived from sensitive data can be readable
by callers who may not read its source. Controlling that is the enrichment author's decision, taken by
declaring the target column `sensitive` when defining the enrichment's schema
(`POST /v1/tables/{name}/schema`) or by patching it later (`PATCH /v1/tables/{name}/schema`).

#### Scenario: A pipeline deriving a non-sensitive column from a sensitive source is accepted unchanged

- **WHEN** a `FULL_ADMIN` caller registers a pipeline whose expression or filter reads a `sensitive`
  source column and whose output binding targets a non-`sensitive` enrichment column
- **THEN** the pipeline is accepted, and the target column's `sensitive` flag is left exactly as the
  table author declared it

#### Scenario: A non-entitled runner cannot operate a sensitive-referencing pipeline

- **WHEN** a caller who may not see sensitive columns invokes any pipeline-scoped read or derive
  path for a pipeline referencing a `sensitive` source column
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

### Requirement: An enrichment-sourced value is readable only where absence is representable

A pipeline's grain join is **outer**, so an enrichment column SHALL be treated as possibly absent
whatever the enrichment declared. Where such a column produces a **value** — a `trigger.group_by`
key, or a `sql` transform's output expression — the enrichment column SHALL be declared `nullable`,
and a pipeline reading a non-`nullable` one in such a position SHALL be rejected with HTTP 422
`pipeline_validation_failed`.

The reason is a ClickHouse default rather than a policy: `join_use_nulls` is 0, so an unmatched
outer join renders a non-nullable column as its **type default** — `''`, `0`, `false`, the epoch —
which is indistinguishable from a derived value. `array` and `map` columns SHALL be exempt:
ClickHouse has no `Nullable(Array)`, and their default is *empty*, which contributes nothing.

A `filter`, `trigger.ready_when.signal` or `trigger.member_select.prefer_sql` MAY still read a
non-`nullable` enrichment column: there it **selects rows** rather than producing a value, and
`"<enrichment>.<column>" != ''` is how such a predicate excludes the rows the enrichment has not
reached.

#### Scenario: A non-nullable enrichment column is refused as a group key

- **WHEN** a `group` pipeline declares a `trigger.group_by` naming an enrichment column declared
  `nullable: false`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A non-nullable enrichment column is refused in a Class-1 output expression

- **WHEN** a `type=sql` pipeline's output expression reads an enrichment column declared `nullable:
  false`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A non-nullable enrichment column may still be read by a predicate

- **WHEN** the same column is referenced only by the pipeline's `filter`,
  `trigger.ready_when.signal` or `trigger.member_select.prefer_sql`
- **THEN** the pipeline is accepted

#### Scenario: An array enrichment column is exempt

- **WHEN** a pipeline reads an `array`-typed enrichment column declared `nullable: false` in a
  value-producing position
- **THEN** the pipeline is accepted, because an unmatched join renders it empty rather than as a
  value

### Requirement: An enrichment read requires an establishable materialized position

A pipeline reading an enrichment column SHALL be admitted only when the position that enrichment has
been materialized through can be established, because that position is what bounds the pipeline's
read window (see [enrichment-runtime](../enrichment-runtime/spec.md) and
[class1-enrichment](../class1-enrichment/spec.md)). Registration and enable SHALL classify each
referenced enrichment and SHALL reject, with HTTP 422 `pipeline_validation_failed` naming the
enrichment and the reason, one whose position cannot be established:

- an enrichment written by a **`group`-trigger** pipeline — a group's members span arbitrary source
  positions, so the only sound position is the one below which every group has settled, which one
  long-lived group pins indefinitely, and the value changes on every recompute by design;
- an enrichment written by a pipeline **ADAS does not execute itself** — the write-through position
  is held by the runner and never reported.

An enrichment that **no pipeline targets** SHALL be admitted and SHALL impose no bound: an unwritten
enrichment will never gain another row, so its absent values are final rather than late and there is
nothing to wait for.

This is stricter than the aggregate pipeline surface, which admits an unbounded read and reports it.
The asymmetry is deliberate: a rollup recomputes every dirty key in full on its next refresh, so an
unbounded read costs it a number that self-corrects, while a pipeline's read source cursor advances
once — neither a group left unmarked nor a Class-1 value written from an unlanded input comes back.

#### Scenario: An enrichment written by a Class-1 pipeline is admitted

- **WHEN** a pipeline references a column of an enrichment written by a `type=sql` pipeline scanning
  the same read source
- **THEN** the pipeline is accepted, and its read window is bounded by that enrichment's recorded
  position

#### Scenario: An enrichment written by a group pipeline is refused

- **WHEN** a pipeline references a column of an enrichment whose own pipeline declares
  `trigger.kind: "group"`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the enrichment and that no position can be established for a group-written enrichment

#### Scenario: An enrichment written by a runner-executed pipeline is refused

- **WHEN** a pipeline references a column of an enrichment written by a pipeline ADAS does not
  execute itself
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An enrichment no pipeline targets is admitted unbounded

- **WHEN** a pipeline references a column of an `ACTIVE` enrichment that no pipeline targets
- **THEN** the pipeline is accepted, the enrichment contributes no bound, and the pipeline's state
  reports the unbounded read

### Requirement: A string output binds to an enum target column

An output whose value is a `string` SHALL be bindable to a target column of type `enum`: an
enum column holds strings, and ClickHouse validates the written value against the declared domain at
insert time. Without this the governance gate's exact-type match would reject the binding and make
an enum column unwritable by any enrichment.

Where a declaration carries its outputs as `transform.outputs` (the current shape) an output carries
**no declared logical type at all**, so there is no spelling of `enum` for the gate to refuse: for an
`llm` output the type comes from the target column itself, and for a `sql` output it is the type the
SQL front end infers for the expression, checked directly against the column. The refusal below
therefore governs a **folded old-shape** `transform.output_vars` declaration only, where a
per-variable `type` still exists.

`enum` SHALL NOT be accepted as a stored output variable's declared logical type. The pipeline declares
the value it produces; the column declares the domain that value must fall into. Accepting `enum` as
a variable type would advertise a domain check the gate does not perform — the same reasoning that
already refuses a parameterized `array<...>` spelling. The declaration SHALL be refused with a
message saying to declare the output as `string` and let the target column carry the domain.

Binding SHALL remain exact in the other direction: a value whose type is neither `string` nor
numeric SHALL still be rejected against an `enum` column.

#### Scenario: A string output binds to an enum column

- **WHEN** a declaration carries an output bound to a target column of type `enum`, whose
  value is a `string`
- **THEN** the pipeline is accepted, and a materialization writing a value from the column's
  declared domain succeeds

#### Scenario: A value outside the domain fails at write time, not registration

- **WHEN** a pipeline bound to an `enum` column produces a value outside that column's declared
  domain
- **THEN** the pipeline remains valid at registration and the failure surfaces when the
  materialization writes, through the existing materialization failure contract

#### Scenario: An enum domain composed from the column makes the failure unrepresentable for an llm output

- **WHEN** an `llm` declaration's output binds to a target column declaring `enum_values`, and the
  composed response schema carries that column's domain
- **THEN** the model is constrained to the column's own domain under strict schema enforcement, so a
  value the column cannot store is refused at the model boundary rather than at insert

#### Scenario: A folded output variable declared enum is refused

- **WHEN** a pipeline's `transform.output_vars`, folded from an old-shape evaluator version, declares
  the logical type `enum`
- **THEN** the declaration is rejected with a message directing the author to declare `string` and
  let the target column declare the domain

#### Scenario: A non-string output against an enum column is still rejected

- **WHEN** a pipeline's output value is a `timestamp` and it binds to a target column of type `enum`
- **THEN** the binding is rejected by the governance gate

### Requirement: ENRICH pipeline declaration binding a transform to a target enrichment

The system SHALL manage pipelines via `POST /v1/pipelines`, `PATCH /v1/pipelines/{name}`, `DELETE
/v1/pipelines/{name}`, and `GET /v1/pipelines/{name}`. An ENRICH pipeline SHALL declare its own
**transform** against a `target`, plus an optional single-entry `inputs`, `trigger.kind`, optional
`trigger.cron`, `trigger.group_by`, `trigger.ready_when`, `filter`, `enabled`, and an optional nested
`advanced` block carrying the execution knobs (`rows_per_scan`, `rows_per_call`, `rate_rpm`,
`sample_fraction`). The pipeline's **grain SHALL be derived** from the target enrichment's
`grain` and SHALL NOT be re-declared on the pipeline. The pipeline's **read source MAY be declared** as
its `inputs` entry and SHALL default to the target enrichment's `source_table` when omitted (see "A
pipeline's read source is declarable"). Each mutating pipeline operation SHALL bump a `generation`
counter and `updated_at`. An unknown pipeline name SHALL be rejected with HTTP 404 `unknown_pipeline`.

**The transform SHALL be one nested block, `transform`**, stored in the pipeline's `spec`, carrying
`type` (`llm` or `sql`), `model`, `preset`, `params`, `request_template`, `inputs` and `outputs`. It SHALL be
nested rather than flat for a reason that is a contract, not a matter of taste: **`outputs` already
names the derived output mapping** at the top level of a pipeline read — one entry per mapped target
column, `{name, column, jsonata?}` — and the authored outputs are a different shape,
`{name, prose|sql, values?, jsonata?}`. Two shapes under one member name, across two projections of
one object, is exactly what "a fact SHALL have exactly one spelling" forbids, and no typed client can
model it. Nesting separates them by construction: the authored list is `transform.outputs`, the derived
one stays `outputs`. Nesting also keeps `type` from sitting beside `kind` as a second top-level
discriminator, and lets the whole block be refused as `transform.<member>` on the wrong kind through
the rule `advanced` already follows.

`type` SHALL be required within the block and SHALL NOT be inferred from the presence or absence of any
other member: it selects the *grammar* of an `outputs` value, both grammars are a string on the wire,
and a `sql` transform whose `type` went missing would have its expressions read as prose and composed
into a response schema. The shape SHALL be dispatched on `type`:

- **`llm`** — `model` SHALL be required and non-blank, and `request_template` SHALL be required and
  non-blank. The template is a **shape** requirement, answerable from the block alone, and is therefore
  distinct from the placeholder check that arm time carves out: an author may save a template whose
  placeholders bind to nothing, but not a transform carrying no template at all. Without it, a patch
  replacing `transform` wholesale and omitting `request_template` would be stored on an **enabled**
  pipeline and leave it armed with no prompt, because the placeholder rule's own blank guard returns
  early and cannot see it. `preset` SHALL be optional, defaulting to
  `chat_completion`, its only accepted value. Each `outputs` value SHALL be either a **string** (the
  prose the model is told about that output) or an object `{prose, values?, jsonata?}` carrying **at
  most one** of `values` or `jsonata`. `prose` SHALL be **optional**, defaulting to the target column's
  `description` — see "The response schema is composed from the target and the transform", which
  already takes an output's type, enum domain and enum order from the same column. An absent value, a
  JSON `null`, an empty object and a blank string SHALL all mean *default it*, so that one fact has one
  spelling. There SHALL be no separate refusal of "an object declaring none of the three": once `prose`
  is optional, `{}` and `{"prose": null}` are the same bound value as `null`, so a refusal could only
  reject one spelling of *default it* while accepting the others.
- **`sql`** — `preset`, `model`, `request_template`, `params` and `response_schema` SHALL be absent, and
  each `outputs` value SHALL be a non-blank SQL expression string. `response_schema` is refused for the
  same reason as the other four, and is named because it is the one the compiled projection would
  otherwise surface: a `sql` transform constrains no model response, so a stored schema on one would be
  served as a derived top-level `response_schema` for a pipeline that never calls a model. See
  [class1-enrichment](../class1-enrichment/spec.md).

A body violating its type's shape SHALL be rejected with HTTP 422 `pipeline_validation_failed`. The
`outputs` **order is load-bearing** — it is the composed response schema's property order, hence the
model's generation order — so it SHALL be preserved from the request body and stored as an **ordered
array**, never as a JSON object whose key order the storage engine may normalize.

**The two legacy output members are part of the authored surface.** A transform folded from an
evaluator version registered before the `outputs` shape carries `output_vars` and, for `llm`, a stored
`response_schema` instead (see "A transform's outputs are one fact in two stored shapes"). Both SHALL
be served under `view=source` and SHALL be accepted on `PATCH`, so a read-modify-write client round-trips
a folded declaration unchanged. This is a correctness requirement rather than a convenience: a patch
that silently dropped them would leave a declaration with no outputs at all, and dropping a stored
`response_schema` rewrites a live prompt. Three rules bound it:

- **`output_vars` SHALL be refused on `POST`** with HTTP 400 `bad_request` naming the field. A new
  declaration authors `outputs`; the legacy pair exists to be carried and patched back, never to start
  from. It is not a member of the create-side request shape at all, which is what makes the refusal a
  binding one rather than a validation one.
- **`response_schema`, unlike `output_vars`, SHALL be accepted on `POST`.** The two legacy members part
  company here, and deliberately: a stored schema is a legitimate thing for a new declaration to pin —
  an author reproducing an existing prompt byte for byte has no other way to do it — while
  `output_vars` is a shape that only ever arrives by being folded. A `POST` carrying
  `transform.response_schema` SHALL therefore be stored and SHALL win over composition, exactly as a
  folded one does.
- A declaration carrying **both** `transform.outputs` and `transform.output_vars` SHALL be rejected with
  HTTP 422 `pipeline_validation_failed`. This is reachable on **`PATCH` only**: on `POST` the
  `output_vars` member is refused at the binding with 400 before any validation runs, so the 422 never
  applies there. Stating the verb is what keeps the two rules from reading as a contradiction.
- **`response_schema` is an ordinary authored member**, so a declaration patch that omits it clears it
  and that pipeline switches to composition from its `outputs`. It is the only way to migrate a legacy
  declaration onto `outputs`, it is the operator's explicit act because a declaration patch supplies the
  whole declaration, and its consequence is that the model is then held to a newly composed schema whose
  property order is its generation order.

**The declaration gate SHALL require at least one output resolved through the single accessor** —
`transform.outputs` when present, `transform.output_vars` otherwise — never through `outputs` alone. A
folded old-shape declaration has no `outputs` at all, so a gate that required that member would refuse
every pipeline the fold migration produced.

Because the declaration now names both a target and a transform, `outputs` SHALL be validated against
the target **at declaration time**, and an output resolving to no column SHALL be rejected with HTTP
422 naming the output and the target. There SHALL be no deferral of this check to a later write.

**`transform.inputs`** SHALL be a map of variable name → the value the transform receives, each value
a source column or a JSONata expression over the read source's entity. It is the single declaration of
what the transform reads: the variable names are the map's keys, so a name cannot be declared in one
place and bound in another. What a variable *is* depends on the trigger's grain — a top-level template
placeholder at row grain, a field of each member object under `{{members}}` at group grain — see "A
transform's template placeholders are validated against the pipeline's trigger grain".

It SHALL live **inside** the block, replacing the top-level `vars` map, and the flat spelling SHALL be
refused at the binding with HTTP 400 `bad_request` naming it. Two reasons, and the second is the
structural one. It names half of one fact: `transform.inputs` is what the transform reads and
`transform.outputs` is what it writes, where `vars` named a mechanism beside a role and nothing in the
pair said they were two sides of one thing. And it is meaningful only when `transform.type` is `llm` —
a `sql` pipeline is refused for declaring any, because there is no request to render — so the one member
dispatched on `type` was the one member sitting outside the type-dispatched block.

The name collides with the top-level `inputs`, the table list, and that collision SHALL be resolved by
**nesting** rather than by a third spelling: it is the same rule that puts a group key at
`trigger.group_by` while an `aggregate` pipeline's rollup grain is the top-level `group_by`, and that
puts the authored output map at `transform.outputs` while the derived mapping is the top-level
`outputs`. The two are additionally distinguishable by shape — the table list is an array, the variable
map an object — so a member sent at the wrong level is a binding failure rather than a silent accept.
`input_vars` SHALL NOT be revived as the spelling: it is a retired member, and reusing it for a map
where it was a list would make a body written for the retired list bind cleanly and mean something else.

**`advanced`** SHALL carry the execution knobs, nested rather than flat so they stay out of the
required-looking surface of the declaration. Its members SHALL be `rows_per_scan`,
`rows_per_call`, `rate_rpm` and `sample_fraction`, each optional, and the block itself SHALL be
optional. `sample_fraction` SHALL be a fraction in `(0, 1]`: a value above `1` SHALL be rejected with
HTTP 422 rather than read as a percentage, and `0` SHALL be rejected with HTTP 422 naming
`enabled: false` as the way to declare a pipeline that evaluates nothing.

The following members SHALL NO LONGER be accepted on this kind, each refused at the binding with
HTTP 400 `bad_request` naming the field: **`evaluator_name` and `evaluator_version`** (the transform is
declared inline; there is no registry to name), `input_bindings` and `output_bindings` (replaced by
`transform.inputs` and by the derived output mapping), the flat `vars` (now `transform.inputs`), the
flat `sampling`, `cadence`, `batch_scan_limit` and
`batch_chunk` (now `advanced` members under their new names), `advanced.scan_every` (a row-grain
pipeline's only clock is its `trigger.cron`, and a group pipeline's mark scan runs on the caller's own
configured cadence — see "Trigger kinds and the default schedule"), and `priority` (nothing reads it).
`cadence` and `advanced.scan_every` are two spellings of one retired knob and are refused alike. Both
evaluator members belong to **no kind at all**, so they are refused at the binding on
either kind with 400 — not cross-kind-rejected with 422 — under the rule that a spelling the read never
echoes is refused where an unrecognized member is (see
[pipeline-registry](../pipeline-registry/spec.md), "A declaration field belonging to another kind is
rejected"). `rate_rpm` SHALL keep its spelling: a rename buys a permanent second vocabulary and is only
worth it for a name that is ambiguous or misleading.

Both `advanced` and `transform` SHALL count as a **declaration** for the purpose of distinguishing a
declaration patch from an enable-only patch, so a patch carrying only `advanced` or only `transform`
SHALL take the declaration path rather than being read as an enable-only patch that
silently discards it. Taking that path is not the same as being accepted: **a declaration patch SHALL
supply the whole declaration**, which for this kind SHALL be `target`, `transform` and `trigger`
together (plus `inputs` where the pipeline declares a read source), and a patch short of that SHALL be
rejected with HTTP 422 naming the members it must restate. A partial merge would leave a stored
declaration no caller described. `transform` therefore **replaces** the stored one wholesale: a member
it omits — a `response_schema`, an `output_vars` — is cleared rather than retained, which is what makes
the response-schema rule above an operator's act rather than a merge artefact.

`advanced` SHALL NOT be required by a declaration patch: it is optional at registration, so requiring
it on patch would make a pipeline that never set it unpatchable. A cross-kind rejection of a nested
member SHALL name `<block>.<member>`, not the block, so the caller is told which field they misplaced.

A patch carrying only `advanced` that leaves the pipeline enabled is therefore refused with 422 rather
than re-validated and stored, and bumps no `generation`; on a pipeline that stays disabled it is an
ordinary partial declaration patch. The completeness rule and the enable-only distinction are two
different questions about one request, and only the second is what "counts as a declaration" answers.

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

The **fold migration is the one write that bumps neither**. It rewrites `spec` in place with the
transform each pipeline already resolved to, so it changes no **fact** a consumer reads: the transform
served after the migration is the one that was served before it, reached without a second lookup. The
release *does* reshape the compiled projection — that is what the runner contract version is raised for
— but the reshaping belongs to the deploy, not to this `UPDATE`, and bumping `generation` here would
re-sync every runner lane and discard every Class-1 materialized position for a change the migration
itself did not make.

`trigger.kind` SHALL be one of `schedule` or `group` (see "Trigger kinds and the default schedule" for the
retired `on_ingest` and for the cron an enrich `schedule` trigger is stored with when it declares none). A
`group` pipeline fires once per
**group key** rather than once per row: it declares `trigger.group_by` (the group key) and `trigger.ready_when`
(`{signal?, idle?, max_staleness?, cost_ceiling?}`), the declaration of when a group is considered
complete enough to evaluate. A `group` pipeline SHALL NOT carry `trigger.cron` — its cadence is the
caller's readiness sweep, not a cron expression — which the existing cron-iff-`schedule` invariant
already enforces. A `group` pipeline writes exactly one enrichment row per group key through the existing
pipeline-scoped write-back, keyed by `trigger.group_by`, and the target enrichment's `ReplacingMergeTree` collapses
by that key.

#### Scenario: Create a pipeline derives its grain and defaults its source

- **WHEN** `POST /v1/pipelines` is called with a valid `transform` and `target` and no `inputs`
- **THEN** the pipeline is created with `grain` derived from the target enrichment and `inputs`
  defaulted to that enrichment's `source_table`, `enabled` per the request, `generation`
  initialized, and HTTP 201 returned

#### Scenario: An llm transform is declared inline

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and a `transform` carrying
  `type: "llm"`, a non-blank `model`, a `request_template` and `outputs` whose values are prose strings
  resolving to target columns
- **THEN** the pipeline is created, HTTP 201 is returned, and the stored `outputs` preserve the
  declared order

#### Scenario: An llm output declaring no prose defaults to the column description

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and an `llm` transform whose `outputs`
  carry a value that is `null`, an empty object or a blank string for a target column that has a
  `description`
- **THEN** the pipeline is created and HTTP 201 is returned, the stored declaration records **no**
  prose for that output, and the composed `response_schema` carries the column's `description` as that
  property's `description`

#### Scenario: A sql output still requires its expression

- **WHEN** `POST /v1/pipelines` is called with a `sql` transform whose `outputs` carry a blank or absent
  value for a target column that has a `description`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, because an expression is
  not prose and cannot be defaulted from a column

#### Scenario: A sql transform is declared inline

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and a `transform` carrying
  `type: "sql"`, no `model`/`preset`/`request_template`/`params`, and `outputs` mapping each target
  column name to a non-blank SQL expression
- **THEN** the pipeline is created and HTTP 201 is returned

#### Scenario: A transform with no type is rejected rather than defaulted

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and a `transform` carrying no `type`,
  whether or not `preset` is present
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, and the body is not read
  as `llm` on the strength of `preset`'s default

#### Scenario: An llm transform without a model is rejected

- **WHEN** `POST /v1/pipelines` is called with `transform.type: "llm"` and a blank or absent `model`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: An llm transform carrying no request template is rejected

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` declares `transform.type: "llm"` with a
  `model` but no `request_template`, or a blank one
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, whether or not the
  pipeline is enabled, so a wholesale `transform` replacement cannot leave an armed pipeline promptless

#### Scenario: A sql transform carrying an llm-only member is rejected

- **WHEN** `POST /v1/pipelines` is called with `transform.type: "sql"` and any of `preset`, `model`,
  `params`, `request_template` or `response_schema` inside the block
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the llm-only field

#### Scenario: An output matching no target column is rejected at declaration

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` declares a `transform.outputs` key
  matching no column of the target enrichment
- **THEN** the request is rejected with HTTP 422 naming the output and the target, at the moment it is
  declared rather than at a later write

#### Scenario: A folded legacy declaration round-trips through a patch

- **WHEN** `GET /v1/pipelines/{name}?view=source` is called for a pipeline whose transform was folded
  from an old-shape evaluator version, and the response body is sent back unchanged as
  `PATCH /v1/pipelines/{name}`
- **THEN** the patch is accepted, `transform.output_vars` and `transform.response_schema` are stored
  exactly as they were, and the compiled projection is unchanged

#### Scenario: A patch that omits a stored response schema clears it

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration whose `transform` carries `outputs` and
  no `response_schema`, for a pipeline that stored one
- **THEN** the stored schema is cleared, the compiled projection thereafter serves the schema composed
  from the target and those `outputs`, and `generation` is bumped

#### Scenario: A declaration carrying both output shapes is rejected

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries both `transform.outputs` and
  `transform.output_vars`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both members

#### Scenario: Authoring output_vars on a new pipeline is refused

- **WHEN** `POST /v1/pipelines` carries `transform.output_vars`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field — a new declaration
  authors `outputs`

#### Scenario: A folded legacy declaration passes the gate

- **WHEN** an `enrich` pipeline whose transform carries `output_vars` and no `outputs` is read, patched
  or enabled
- **THEN** the gate resolves its outputs through the single accessor, finds at least one, and accepts —
  it does not refuse the pipeline for declaring no `outputs`

#### Scenario: Naming an evaluator is refused by name

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries `evaluator_name` or
  `evaluator_version`, on either kind
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is
  persisted

#### Scenario: Omitted enabled is rejected

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and `enabled` absent or null
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field, and
  no pipeline is created

#### Scenario: A removed declaration member is refused by name

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries any of `input_bindings`,
  `output_bindings`, `sampling`, `cadence`, `advanced.scan_every`, `batch_scan_limit`, `batch_chunk`
  or `priority`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is
  persisted — the caller is told what they sent rather than having it silently ignored

#### Scenario: A patch carrying only advanced or only the transform is a declaration patch

- **WHEN** `PATCH /v1/pipelines/{name}` carries only an `advanced` block, or only
  `transform`
- **THEN** the patch takes the declaration path rather than being read as an enable-only patch that
  discards it, and is rejected with HTTP 422 naming `target`, `transform` and `trigger` as the members
  a declaration patch must supply together

#### Scenario: A declaration patch omitting the transform is rejected

- **WHEN** `PATCH /v1/pipelines/{name}` supplies `target` and `trigger` but no `transform`
- **THEN** the request is rejected with HTTP 422 naming the members a declaration patch must supply
  together, rather than merging the stored transform into a declaration the caller did not write

#### Scenario: A patch changing only the prompt bumps generation

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration differing from the stored one only in
  `transform.request_template` or in an output's prose
- **THEN** the declaration is re-validated and stored, `generation` is incremented and `updated_at`
  advances, so the change reaches the sync feed

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

#### Scenario: The fold migration bumps nothing

- **WHEN** the fold migration rewrites an `enrich` pipeline's `spec` with the transform it already
  resolved to
- **THEN** that pipeline's `generation` and `updated_at` are unchanged, no revision is recorded, the
  transform its compiled projection carries is the one it already resolved to, and no runner lane
  re-syncs on account of the migration

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
- **THEN** the pipeline is removed and HTTP 204 returned, taking its transform with it, and the
  target enrichment keeps its rows

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
`trigger.member_select`, `transform.inputs` and a `sql` transform's output expressions are validated
against its
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
  "schedule"`, so its grain key is reachable only through an enrichment and it declares no
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

### Requirement: The enrich feed carries the transform, a resolved schema and a change token

The system SHALL serve the enrich feed as `GET
/v1/pipelines?kind=enrich[&enabled=…][&updated_since=…]&view=compiled`, returning each
selected pipeline with its **authored `transform` block**, the **resolved `response_schema`** the model
is held to, the pipeline's read source and derived grain, the read source's `version_column` (absent
when that source declares none), and a `generation` value the runner uses for change detection. The feed
carries **no HTTP `ETag`/304** — `generation` is the change signal and comparison is the consumer's,
done client-side per pipeline. `updated_since` SHALL filter to pipelines changed at or after the
given timestamp. For a `group` pipeline the feed SHALL additionally inline `trigger.group_by` and
`trigger.ready_when`, so a caller can route the pipeline to its group path and evaluate readiness
without a second round-trip.

The feed SHALL carry **no `evaluator` object**: each fact about the transform appears on the entry under
exactly one spelling — the pipeline's `name` and `generation`, and the `transform`'s own `type`, `model`,
`preset`, `params` and `request_template`, with `response_schema` the derived top-level member below. A
consumer SHALL need no second lookup to recover any of them, and SHALL stamp provenance from `generation`.

Reshaping this entry is a **breaking** change to the compiled projection, so the runner contract
version SHALL be raised — see [pipeline-registry](../pipeline-registry/spec.md), "Every pipeline
response carries the runner contract version". That, and nothing added here, is what makes the ordered
deploy a checked fact: the version is echoed on every response under `/v1/pipelines`, the feed's own
response included, and a consumer verifies the echo **before** interpreting the body. The failure it
prevents is expensive and otherwise silent — a consumer that reads an entry carrying no
`transform.inputs` substitutes no placeholders, calls the model with literal `{{placeholder}}` text, is
billed for the call and stores the answer as a label. The feed SHALL NOT grow a request parameter or a
body member of its own for this; the header already covers it, on this read and on every other.

The feed SHALL be the **compiled** projection, requested as `view=compiled` — see
[pipeline-registry](../pipeline-registry/spec.md), "A pipeline read selects an authored or a compiled
projection". The runner SHALL name the projection explicitly rather than relying on a default: the
authored projection is the default for every pipeline read, and it carries none of the resolved
members the runner needs. `?kind=enrich` without `view=compiled` is therefore an operator's listing
and SHALL cost no resolution.

An `enrich` entry SHALL carry the **response schema the model is held to** and the **output mapping
the write is validated against**, whether either was stored or composed:

- The entry's top-level `response_schema` SHALL be the declaration's **stored** schema when its
  `transform` carries one, and otherwise the schema composed from the pipeline's target and its
  `outputs` (see "The response schema is composed from the target and the transform"). A stored schema
  SHALL keep winning over composition for as long as the declaration stores it — the composed form is
  not a faithful reproduction of one, so serving a composed schema for a declaration that stores one
  would change a live prompt. Clearing it is possible, and is the operator's explicit act through a
  declaration patch that omits it.
- The entry's `outputs` SHALL be the pipeline's derived output mapping, one ordered entry per mapped
  target column, each carrying the exposed `name`, the physical `column`, and the transform resolved
  through the declaration's stored `output_vars` or its `outputs` alike. Resolving both stored shapes
  behind one accessor is what keeps the split invisible to the consumer.
- The feed SHALL carry neither `input_vars` nor `output_vars` as a derived member. Both described what
  the entry's own `transform.inputs` and `outputs` now carry, and serving them alongside would restore
  the two-lists-joined-by-name shape the fold removes. The authored `transform.output_vars`, where a
  folded legacy declaration has one, is a declaration member and is served under both projections as
  the authored declaration it is.

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

An `enrich` pipeline whose declaration declares `outputs` but whose **composed response schema cannot be
built** SHALL likewise not be served, and the omission logged. It is the same failure in a different
member: such a declaration stores no `response_schema` of its own, so composition is the only source, and
a read deliberately swallows a composition failure rather than failing a whole listing over one drifted
target. Served with a full output mapping and no schema, the entry looks healthy while
`response_format` is never sent, the model answers free-form, and every row is rejected at validation —
with the call made and charged first. A declaration that declares **no** `outputs` is a different case
and SHALL still be served: a declaration folded from a version stored before that shape and carrying no
schema called the model unconstrained before this change too, and refusing it now would retire a live
pipeline rather than protect it. This is also why the fold migration SHALL NOT convert `output_vars`
into `outputs`: converting exactly this declaration would give it an `outputs` array and no stored
schema, which is the input composition fires on, and the next feed read would send a `response_format`
where none was sent before.

#### Scenario: A pipeline whose outputs compose to no schema is omitted from the feed

- **WHEN** an enabled `enrich` pipeline declares an `llm` transform that declares `outputs`, stores
  no `response_schema`, and whose outputs do not compose against its target
- **THEN** the pipeline is omitted from `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled` and
  the omission is logged naming the pipeline and its target

#### Scenario: A folded declaration with neither outputs nor a schema is still served

- **WHEN** an enabled `enrich` pipeline's transform was folded from an `llm` evaluator version carrying
  `output_vars` and no `response_schema`
- **THEN** the pipeline is served, the entry carries no `response_schema`, and the model is
  called unconstrained exactly as it was before the fold

The response envelope, the four filters and the listing order are the registry's, not this
capability's — see [pipeline-registry](../pipeline-registry/spec.md), "Pipeline listing carries the
registry's filters, envelope and ordering". What this capability adds is what an `enrich` entry
carries. One consequence is worth stating because it is a wire change for the runner: the feed is an
**object envelope** (`{"pipelines": [ … ]}`), where the enrichment registry served a bare array.

What an `enrich` entry carries SHALL NOT depend on **which** of those filters selected it. Resolution
costs a bounded number of queries for the whole page, so there is no reason to serve a narrower entry
under a narrower filter — and a caller adding `target` in order to read a single pipeline's resolved
transform is precisely the caller a downgraded entry would silently fail. Stated as a requirement
because the failure is invisible: the members would be **absent**, which reads as "this pipeline has
no transform" rather than as "this listing did not resolve one".

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

The listing SHALL be returned in a **total** order — oldest `updated_at` first, `name` breaking ties,
as [pipeline-registry](../pipeline-registry/spec.md) specifies — so repeated calls agree on the
sequence. `updated_at` alone does not order the set: it is truncated
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

#### Scenario: The feed carries no evaluator object

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled` is called
- **THEN** no entry carries an `evaluator` object, and each carries its authored `transform` and a resolved
  top-level `response_schema`

#### Scenario: A stale consumer refuses the shape rather than half-understanding it

- **WHEN** a consumer built against the previous contract version polls the feed
- **THEN** the response carries an `Adas-Contract-Version` the consumer does not recognise, and the
  consumer classifies the feed incompatible and evaluates nothing rather than rendering unsubstituted
  placeholders — while its data-plane calls are separately refused HTTP 409 by the header requirement

#### Scenario: The unfiltered listing returns every registered pipeline

- **WHEN** `GET /v1/pipelines?kind=enrich&view=compiled` is called
- **THEN** every registered pipeline is returned regardless of its `enabled` flag, each with its
  `transform`, its read source and derived grain, its `enabled` value, and a `generation`

#### Scenario: The listing narrows to enabled pipelines

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true` is called
- **THEN** only pipelines with `enabled: true` are returned — the set a runner executes

#### Scenario: The listing narrows to disabled pipelines

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=false` is called
- **THEN** only pipelines with `enabled: false` are returned

#### Scenario: A disabled pipeline is fully resolved

- **WHEN** a pipeline registered with `enabled: false` is returned by any of these listings
- **THEN** it carries the same authored transform, read source, derived grain and `version_column`
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
- **THEN** the returned pipeline carries its authored transform, derived grain key, read source and
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
  authored transform

#### Scenario: A stored response schema is served verbatim

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline's `transform` carries a
  stored `response_schema`
- **THEN** that stored schema is served unchanged, property order included, and no composed schema
  replaces it

#### Scenario: A new-shape declaration's schema is composed

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline's `transform` declares
  `outputs` and carries no stored `response_schema`
- **THEN** the entry carries the schema composed from the pipeline's target and those `outputs`, and
  its `outputs` mapping carries each output's transform

#### Scenario: The feed carries the derived output mapping

- **WHEN** `GET /v1/pipelines?kind=enrich` is called for a pipeline whose declaration declares fifteen
  outputs, all resolving to target columns
- **THEN** the entry carries fifteen output bindings, each naming a target column's physical spelling

#### Scenario: A pipeline with no resolvable output mapping is omitted from the feed

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true` is called and one enabled pipeline's output
  mapping resolves to no columns
- **THEN** that pipeline is absent from the response and the omission is logged, rather than served
  with an empty mapping a consumer would act on by overwriting existing labels with NULLs

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
`trigger.member_select.order_by`; the columns a `transform.inputs` entry binds; and a `sql`
transform's
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

### Requirement: An ENRICH pipeline's enrichment references must not form a dependency cycle

Registration and enable SHALL reject, with HTTP 422 `pipeline_validation_failed`, a pipeline whose
enrichment references would close a cycle in the dependency graph whose edges run from a pipeline's
`target` to each enrichment that pipeline reads. A `sql` pipeline's reads live in its
**`transform.outputs` expressions** — resolved through the single accessor, so a folded declaration's
`transform.output_vars` counts identically — and SHALL count as edges exactly
as the pipeline's own fragments do — otherwise the pipeline deriving from a sibling enrichment,
which is the shape this capability exists to allow, would contribute no edge at all. Two pipelines
each reading the other's target would bound each pipeline's window on the other's position, freezing
both permanently with no error raised and no operator action that releases them. The refusal SHALL
name the enrichments forming the cycle.

#### Scenario: A pipeline closing a cycle is rejected

- **WHEN** a pipeline targeting enrichment `A` references a column of enrichment `B`, and `B`'s own
  pipeline references a column of `A`
- **THEN** the later request is rejected with HTTP 422 and error code `pipeline_validation_failed`,
  naming the enrichments forming the cycle

#### Scenario: A pipeline reading its own target is rejected

- **WHEN** a pipeline references a column of the enrichment it targets
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An acyclic chain is accepted

- **WHEN** a pipeline targeting `C` references `B`, and `B`'s pipeline references `A`, and nothing
  references `C`
- **THEN** both pipelines are accepted

### Requirement: An ENRICH pipeline's enrichment references are protected against drop and rename

An enrichment a **completely declared** pipeline reads SHALL be a dependency of that pipeline,
protected exactly as the pipeline's declared read source is. What a pipeline reads SHALL be resolved
from its whole declaration, including a `transform.type: sql` pipeline's output expressions — the only
place such a pipeline's column references appear. Dropping such an enrichment, or dropping or renaming
a column of it that a pipeline references, SHALL be refused with HTTP 409 `conflict` naming the
pipelines that read it, rather than leaving those pipelines to fail on their next scan or materialize.

**The bound to complete declarations is new and is stated rather than implied.** The guard answers by
reading what is *stored*, and a declaration may now be stored before it is complete (see
[pipeline-registry](../pipeline-registry/spec.md), "The declaration gate runs when a write arms the
pipeline, not when it is stored"), so an incomplete declaration under-declares its reads: a fragment
the author has not written yet names no enrichment column, and a column that declaration will
eventually read stays droppable. Closing that would mean protecting a column on the strength of a
declaration that does not resolve — a fragment that may not parse, against a read source that may not
be declared yet — which is a guard with no way to compute its own answer. The consequence is bounded
on the other side instead: completing the declaration afterwards **fails at the declaration gate**,
naming the column the enrichment no longer has, so the cost is lost authoring work on a pipeline that
never ran and never a row derived from a column that is gone. A pipeline that was ever armed had a
complete declaration, and from that moment its reads are stored and this guard holds for it exactly as
before. The `table-management` capability states the same bound from the DDL side for both
`PATCH /v1/tables/{name}/schema` and `DELETE /v1/tables/{name}`.

Those expressions SHALL be resolved through the **single accessor** that reads a transform's
outputs in either stored shape (see "A transform's outputs are one fact in two stored shapes"). This
is a data-integrity requirement rather than a refactoring preference: a new-shape `sql` transform
keeps its expressions in `outputs` and carries no `output_vars`, so a dependency scan reading only
`output_vars` would contribute **zero** column references and let an operator drop or rename an
enrichment column the pipeline's `INSERT … SELECT` joins — with the failure arriving at the next
materialize instead of at the DDL. Both output shapes live in `spec`, where the same accessor answers.
That accessor is also why the bound above costs less than it reads:
an author who has written any governed fragment at all has already contributed its column references,
complete declaration or not — what the bound concedes is only the references not yet written down.

#### Scenario: Dropping an enrichment a pipeline reads is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose columns a registered pipeline
  references
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming the pipelines that
  read it

#### Scenario: Dropping an enrichment read only by a Class-1 output expression is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment referenced only by a `transform.type: sql`
  pipeline's output expression, with no reference elsewhere in the pipeline row
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming that pipeline

#### Scenario: Dropping a referenced column of an enrichment is refused

- **WHEN** a schema patch would drop or rename a column of an enrichment that a registered pipeline
  references
- **THEN** the request is refused with HTTP 409 and error code `conflict`

#### Scenario: An unreferenced column of a read enrichment may still change

- **WHEN** a schema patch drops a column of an enrichment that no pipeline references, while other
  columns of that enrichment are referenced
- **THEN** the patch is accepted

#### Scenario: A new-shape sql transform's expressions reach the dependency guard

- **WHEN** a Class-1 pipeline declares its expressions in `transform.outputs`, one of which reads
  `usage_client_identity.client_type`, and a schema patch would drop or rename that column
- **THEN** the request is refused with HTTP 409 `conflict` naming that pipeline, exactly as it is for
  a folded old-shape transform whose expressions live in `transform.output_vars`

#### Scenario: An enrichment column an incomplete declaration has not yet named is droppable

- **WHEN** a disabled pipeline's declaration does not yet carry the fragment that will read an
  enrichment column, and a schema patch or table delete would remove that column
- **THEN** the operation is accepted, because the guard resolves reads from the stored declaration and
  that declaration declares none

#### Scenario: Completing that declaration afterwards is refused at the gate

- **WHEN** the author then writes the fragment naming the removed column and arms or previews the
  pipeline
- **THEN** the request is rejected with HTTP 422 naming the column the enrichment no longer has, so the
  cost of the bound is the authoring work rather than a row derived from a column that is gone

### Requirement: A column an enrich pipeline writes is protected against drop and rename

The system SHALL refuse `PATCH /v1/tables/{name}/schema` with HTTP 409 `conflict`, naming the pipeline,
when the patch drops or renames a column of an enrichment that receives an output of the
pipeline **targeting** that enrichment — exactly as it refuses a change to a column another pipeline
*reads*. The output mapping is derived by matching the declaration's resolved output names to the
target's columns, so a bound column that stops existing under that name leaves the mapping
unresolvable.

The refusal SHALL be raised **before any DDL is rendered**, in the request path, rather than only by
the catalog's own re-derivation. That endpoint issues one ClickHouse `ALTER` first and commits the
catalog second, so a refusal raised inside the catalog transaction rolls the catalog back over a table
that has already lost the column — leaving the catalog and ClickHouse disagreeing about which columns
exist, permanently and with no operation that repairs the pair. The catalog-side check
(see "An enrichment target's column change is notified to the registry") SHALL remain as a backstop
for a mapping made unresolvable by some other route, which no request-path check can see.

A provenance column SHALL be outside this protection, because no output binds to one: adding
`pipeline_generation` to an existing target through this endpoint is therefore an ordinary additive
patch and is accepted.

#### Scenario: Dropping a column a pipeline writes is refused

- **WHEN** a schema patch would drop a column of an enrichment that receives an output of the
  pipeline targeting it
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming that pipeline, and
  the ClickHouse table is left unchanged

#### Scenario: Renaming a column a pipeline writes is refused

- **WHEN** a schema patch would rename such a column
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming that pipeline

#### Scenario: Adding a column to a pipeline's target is accepted

- **WHEN** a schema patch only adds a column to an enrichment a pipeline targets
- **THEN** the patch is accepted — an addition cannot make an existing output stop resolving

#### Scenario: Adding the pipeline_generation provenance column is accepted

- **WHEN** a schema patch adds `pipeline_generation` to a materialized enrichment that a live pipeline
  targets
- **THEN** the patch is accepted, and the next write stamps it

### Requirement: The response schema is composed from the target and the transform

The system SHALL compose an `llm` declaration's JSON response schema from the pipeline's target
enrichment and its `transform.outputs`, at pipeline registration, patch and read. Composition happens
on the pipeline because the pipeline is the only object there is: it names both the target and the
transform, and binding the two is definitionally its job. The table SHALL stay ignorant of transforms.

Composition SHALL be driven by the `outputs` **keys**, with each key's structure looked up on the
target column of the same name:

| Schema element | Source |
|---|---|
| property name | the `outputs` key, matched against the column's **exposed `name` only** |
| `type` | the target column's declared type, through the mapping below |
| `enum` values **and order** | the column's `enum_values` verbatim, else the output's `values` |
| `description` | the output's prose, **else the target column's `description`**, else omitted |
| property order and `required` order | the `outputs` declaration order |
| `required` | **every** declared output |
| `additionalProperties` | always `false` |

The type mapping SHALL be: `string` → `{"type": "string"}`; `enum` → `{"type": "string", "enum":
[...]}`; `integer` and `long` → `{"type": "integer"}`; `double` and `decimal` → `{"type": "number"}`;
`boolean` → `{"type": "boolean"}`; `timestamp` → `{"type": "string"}` with **no `format`** (strict
schema mode rejects one); `array` → `{"type": "array", "items": <element_type mapped>}`. A column of
any other type — `map`, `json` — SHALL be rejected with HTTP 422 naming the column and its type.

The `description` default SHALL be applied **at composition time and SHALL NOT be written into the
stored declaration**, so that the column remains the single authority for a fact the author did not
supply. It SHALL therefore appear in the composed `response_schema` only, and SHALL NOT appear in the
authored declaration under either projection: a derived default echoed onto the authored surface would
be sent back by the next read-modify-write `PATCH`, become authored, and silently stop tracking the
column. Where an output declares no prose **and** its target column has no `description`, the property
SHALL be composed with no `description` at all rather than refused — a description-less property is
valid under strict mode and is already what a legacy declaration with a stored schema produces — and
`POST /v1/pipelines/{name}/preview` SHALL report it as a note. The default SHALL apply to `llm`
outputs only: a `sql` output's string is an expression rather than prose, cannot be defaulted from any
column, and SHALL keep being required and non-blank.

Composition SHALL apply **only** where the declaration stores no `response_schema` of its own. A
declaration carrying a stored schema serves it verbatim, and a declaration carrying neither a stored
schema nor `outputs` — the shape a folded old-shape `llm` transform can have — composes nothing and
calls the model unconstrained, exactly as it did before the fold.

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

Composing the enum domain **from the column** SHALL be preferred to declaring it on the output,
and the consequence SHALL be accepted rather than worked around: enum **order** in the prompt is then
whatever order the column's `enum_values` declare, which is chosen for dashboard sorting. Membership
is the half that carries correctness — a ClickHouse enum column refuses an out-of-domain value at
insert, so composing the domain from the column makes "the model returned a value the column cannot
store" unrepresentable — and an order override on the output would rebuild exactly the
two-copies-of-one-domain drift this composition removes.

An output declaring `values` for a column that already declares `enum_values` SHALL be rejected with
HTTP 422 naming both: a field has exactly one authority for its domain, chosen by which of the two
can hold it. `values` SHALL be understood to buy **schema** enforcement only — a `string` column
accepts anything at insert — and the rejection message for an out-of-domain value SHALL NOT imply
otherwise.

Because output names are matched to column names, an output matching no column SHALL be rejected with
HTTP 422 naming the output and the target. A transform belongs to exactly one pipeline, which writes
exactly one target.

**Residual, stated rather than solved.** After composition the target's schema is a live input to both
the prompt and the write mapping, and a column dropped and re-added changes the composed schema
without any pipeline being patched, so `pipeline_generation` — the per-row provenance stamp — does not
move either. Rows labelled either side of such a change therefore carry no per-row record of which
schema produced them.

#### Scenario: A schema is composed from the target's columns

- **WHEN** a pipeline is registered naming a target whose columns include an `enum` column and a
  `decimal` column, and a `transform` declaring an output for each
- **THEN** the composed schema types the first as `{"type": "string", "enum": [...]}` carrying that
  column's `enum_values` in the column's own order, and the second as `{"type": "number"}`

#### Scenario: A declared prose wins over the column description

- **WHEN** an output declares a non-blank `prose` and its target column also carries a `description`
- **THEN** the composed property's `description` is the declared prose, and the column's `description`
  is not consulted

#### Scenario: Neither prose nor column description composes no description

- **WHEN** an output declares no prose and its target column carries no `description`
- **THEN** the composed property carries no `description` member at all, the schema is still valid, and
  the pipeline is neither refused at write nor at enable

#### Scenario: The defaulted description is absent from the authored surface

- **WHEN** a pipeline whose output declares no prose is read with `view=source` and again with
  `view=compiled`
- **THEN** neither projection's authored `transform.outputs` carries a prose for that output, only the
  derived `response_schema` carries the column's `description`, and sending the `view=source` body back
  as a `PATCH` changes nothing

#### Scenario: A stored response schema is unaffected by the default

- **WHEN** a declaration stores its own `response_schema` and one of its outputs declares no prose
- **THEN** the stored schema is served verbatim, no composition runs, and no column `description` is
  read

#### Scenario: Property order follows the outputs declaration

- **WHEN** a `transform` declares outputs in the order `data_exposure`, `prohibited_request`,
  `risk_level`
- **THEN** the composed schema's `properties` and `required` carry that order, and it survives a
  round trip through storage and a pipeline read

#### Scenario: Every declared output is required

- **WHEN** a `transform` declares an output bound to a nullable target column
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

#### Scenario: A declaration with no outputs composes nothing

- **WHEN** an `llm` declaration folded from an old-shape evaluator version carries `output_vars` and
  no `response_schema`
- **THEN** no schema is composed, the entry is served without one, and the model is called
  unconstrained as it was before the fold

### Requirement: A transform's outputs are one fact in two stored shapes

A folded transform SHALL carry its outputs in whichever of the two shapes its source evaluator version
stored, and the fold SHALL convert **neither** into the other. Two shapes of the same fact therefore
continue to coexist indefinitely, now inside `pipeline.spec`: a transform folded from a version
registered before the `outputs` shape carries `output_vars` (and, for `llm`, a stored
`response_schema`), and one folded from a later version — or authored after this change — carries
`outputs`. Every consumer SHALL resolve them through **one accessor** that returns `outputs` when
present **and non-empty** and falls back to `output_vars` otherwise — the emptiness test is part of the
rule, not an implementation detail, because an `outputs` array that is present and empty is a row the
accessor must still read as old-shape.

**Response-schema composition is the one deliberate exception, and SHALL be stated as one.** Composition
reads `transform.outputs` alone and SHALL NOT go through the accessor: a declaration carrying
`output_vars` and no stored `response_schema` calls the model unconstrained today and SHALL keep doing
so, and routing composition through the accessor would compose a schema for exactly that declaration —
sending a `response_format` where none was sent before, which is the silent live-prompt change this
requirement's copy-not-convert rule exists to prevent. Every other consumer — the gate, the cycle scan,
the drop and rename guards, the derived output mapping, expression analysis — uses the accessor.

Carrying both verbatim rather than converting is what makes the fold migration a pure column copy, and
the reason is sharper than economy. A `sql` row converts mechanically and losslessly. An `llm` row does
not, and the blocking case is the row carrying `output_vars` and **no** `response_schema`: such a
declaration calls the model unconstrained today and is served that way deliberately, but converting it
would hand it an `outputs` array with no stored schema — exactly the input composition fires on — so
the next feed read would send a `response_format` where none was ever sent. That is a live prompt
change made by a migration on an environment nobody inspected. A copying migration is correct for every
environment by construction; a converting one has to be lucky.

A single accessor is required rather than recommended, because the consumers are not all validation
paths: one of them feeds the catalog's drop-and-rename dependency guard, where a missed resolution
does not fail a request but silently makes an enrichment column a Class-1 pipeline reads droppable
(see "An ENRICH pipeline's enrichment references are protected against drop and rename"). Another is
the declaration gate itself, which requires at least one output: reading `outputs` alone there would
refuse every pipeline the fold produced.

Because the transform is now authored rather than stored immutably, both legacy members are part of
the **authored surface** — served under `view=source`, accepted on `PATCH`, refused on `POST` in the
case of `output_vars` — and a declaration carrying both shapes is refused with HTTP 422. See "ENRICH
pipeline declaration binding a transform to a target enrichment" for the three rules that bound this.

A stored `response_schema` SHALL keep winning over composition **for as long as the declaration stores
it**. The composed form is not a faithful reproduction of a stored one — stored enum order, stored type
spelling and the stored property order can each differ from what composition produces — and under
strict schema enforcement property order is generation order, so serving a composed schema for a
declaration that stores one would silently change a live prompt. What changes with the fold is only
that "for as long as" is now finite: a declaration patch that omits `response_schema` clears it, which
is how a legacy declaration is migrated onto `outputs` at all, and is an act rather than a drift.

The **pipeline** feed is the surface where the two stored shapes are reconciled, and there neither
list is served at all: the feed carries neither `input_vars` nor `output_vars`, and the entry's
own `outputs` carries the resolved mapping with each transform folded in, resolved from whichever
shape the declaration stores. A consumer of the feed therefore never learns which shape it was, which
is the property that lets the two coexist indefinitely.

#### Scenario: Both shapes resolve for expression analysis

- **WHEN** a Class-1 pipeline carries a folded new-shape `sql` transform and a materialize is requested
- **THEN** the expressions are read from `outputs` and analyzed exactly as they would be from
  `output_vars`, and the materialize proceeds

#### Scenario: The fold converts neither shape

- **WHEN** the fold migration folds an old-shape evaluator version into a pipeline's `spec`
- **THEN** `output_vars` and `response_schema` are carried across byte for byte, no `outputs` array is
  synthesised, and the pipeline resolves its outputs through the same accessor

#### Scenario: An old-shape declaration reads back as stored on the pipeline

- **WHEN** `GET /v1/pipelines/{name}?view=source` is called for a pipeline whose transform was folded from
  a version registered before the `outputs` shape
- **THEN** the response carries its stored `transform.output_vars`, and its stored
  `transform.response_schema` where it has one, unchanged

### Requirement: A transform's template placeholders are validated against the pipeline's trigger grain

The system SHALL validate an `llm` transform's `request_template` placeholders **whenever the pipeline
is armed, is already armed, or is previewed** — on `PATCH … {enabled: true}`, on a registration that
arms the pipeline in the same call, on **any declaration write to a pipeline that is already enabled**,
and on `POST /v1/pipelines/{name}/preview` — and the rule SHALL be **dispatched on the pipeline's
`trigger.kind`**, because what a `transform.inputs` entry *is* differs by grain: at row grain
it is a top-level placeholder, and at group grain it is a field of each member object rendered inside
`{{members}}`.

- **Row grain** (`schedule`): every `{{placeholder}}` in the template SHALL be a `transform.inputs`
  key, and every `transform.inputs` key SHALL appear as a placeholder. There SHALL be **no** built-in placeholders
  at row grain — the render inputs are exactly the declared bindings. Both directions SHALL be
  enforced with HTTP 422 `pipeline_validation_failed` naming the offending name: an unmatched
  placeholder is left in the prompt **literally** rather than rendered empty, and an unused `transform.inputs` key
  projects a column, possibly a heavy one, for nothing. A placeholder the declaration **does** bind
  SHALL be accepted whatever it is called, the member-variable names included: nothing supplies those
  at row grain, so their names reserve nothing there, and the refusal below tells an author to bind a
  var of that name — advice the rule must not then reject.
- **Group grain** (`group`): every `{{placeholder}}` SHALL be one of the built-in member variables
  below, and `{{members}}` SHALL appear **at least once** — without it the model receives a prompt
  carrying no data at all. `transform.inputs` keys SHALL NOT be checked against the template, in either
  direction: they are consumed as member fields inside `{{members}}` and a valid group template names
  none of them.

The **built-in member variables** SHALL be exactly five: `members`, `members_truncated`,
`members_included`, `members_omitted` and `members_notice`. ADAS SHALL own this list as its own wire
contract and SHALL assert the five names as literals, so that adding a sixth is a deliberate
two-repo change rather than a valid template that fails registration. A list short of all five would
refuse a template that renders correctly.

The already-enabled case is not a courtesy: a declaration patch to an enabled pipeline reaches the feed
at the next sync, so a template patched against inputs that do not bind would be rendered with literal
`{{placeholder}}` text and charged for. "Armed" in this requirement therefore means the pipeline's state
after the write, not the act of flipping the flag.

The check SHALL **not** run on a declaration write that leaves the pipeline disabled. A declaration is
validated for **storability** when it is written and for **executability** when it is armed, and this
check belongs to the second group: an author legitimately saves a pipeline whose `request_template` is
written and whose `transform.inputs` are not yet bound, or the reverse, and refusing that write makes
the registry unusable as the place a declaration is composed — which is the "create == publish" trap
this fold exists to remove, reappearing one level down. `enabled: false` remains what a
work-in-progress pipeline looks like; no `draft` state is added, and nothing becomes storable that was
not storable before.

Where the line falls SHALL be explicit, because the relaxation is narrow. **Shape** validation stays at
write time — `transform.type` required, the `llm`/`sql` member rules, at least one resolved output,
`name` and `kind` immutable — since a declaration that cannot be read back coherently is not a draft.
What moves is this **referential** check between two members an author fills in at different times.

Because the template and the `transform.inputs` map are members of one declaration written by one
request, the check when it does run SHALL see both: enabling a pipeline whose template was patched
against inputs that were not SHALL be refused with HTTP 422 `pipeline_validation_failed` naming the
offending name, and the pipeline SHALL remain disabled.

#### Scenario: A row-grain template placeholder with no vars key is rejected

- **WHEN** a `schedule` pipeline is registered with `enabled: true`, its template carrying
  `{{requeest}}` while `transform.inputs` declares `request`
- **THEN** the registration is rejected with HTTP 422 `pipeline_validation_failed` naming the
  placeholder

#### Scenario: A row-grain vars key that no placeholder uses is rejected

- **WHEN** a `schedule` pipeline is registered with `enabled: true` declaring a `transform.inputs`
  key that appears in no placeholder
- **THEN** the registration is rejected with HTTP 422 naming the key

#### Scenario: A built-in placeholder at row grain is rejected

- **WHEN** a `schedule` pipeline is registered with `enabled: true`, its template carrying
  `{{members}}` while `transform.inputs` declares no `members`
- **THEN** the registration is rejected with HTTP 422, because the row-grain render supplies only the
  declared bindings

#### Scenario: A member-variable name bound as a row-grain var is accepted

- **WHEN** a `schedule` pipeline is registered with `enabled: true`, its template carrying
  `{{members}}` and `transform.inputs` declaring a `members` key
- **THEN** the registration is accepted: the placeholder resolves to the declared binding, which is
  what the row-grain render supplies

#### Scenario: A group template using all five built-ins is accepted

- **WHEN** a `group` pipeline is registered with `enabled: true`, its template carrying `{{members}}`,
  `{{members_truncated}}`, `{{members_included}}`, `{{members_omitted}}` and `{{members_notice}}`
- **THEN** the registration is accepted

#### Scenario: A group template with no members placeholder is rejected

- **WHEN** a `group` pipeline is registered with `enabled: true` and its template carries no
  `{{members}}`
- **THEN** the registration is rejected with HTTP 422

#### Scenario: A group pipeline's vars need not appear in its template

- **WHEN** an enabled `group` pipeline declares four `transform.inputs` keys and its template mentions
  none of them
- **THEN** the registration is accepted, and a disable/enable cycle of that pipeline re-validates and
  re-arms it unchanged

#### Scenario: A patch of an enabled pipeline's template alone is checked against its stored inputs

- **WHEN** `PATCH /v1/pipelines/{name}` on an **enabled** pipeline changes only
  `transform.request_template`, introducing a placeholder the stored `transform.inputs` does not declare
- **THEN** the patch is rejected with HTTP 422 naming the placeholder, because the patched template
  would reach the feed at the next sync

#### Scenario: The same patch of a disabled pipeline is stored

- **WHEN** the same declaration patch is applied to a **disabled** pipeline
- **THEN** it is accepted and stored, and the pipeline is refused at the next attempt to enable it

#### Scenario: A pipeline may be saved with a template whose inputs are not yet bound

- **WHEN** `POST /v1/pipelines` registers an `enrich` pipeline with `enabled: false`, an `llm`
  `transform` carrying a `request_template` with placeholders, and no `transform.inputs`
- **THEN** the pipeline is created and HTTP 201 is returned, because a disabled declaration is
  validated for storability rather than executability

#### Scenario: A registration that arms in the same call is checked

- **WHEN** `POST /v1/pipelines` registers an `enrich` pipeline with `enabled: true` whose
  `request_template` carries a placeholder its `transform.inputs` does not declare
- **THEN** the registration is rejected with HTTP 422 `pipeline_validation_failed` naming the
  placeholder, and no pipeline is created — the relaxation is about a *disabled* declaration, never
  about an armed one

#### Scenario: Arming that pipeline is refused until its inputs are bound

- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true` on a pipeline whose template carries a
  placeholder its stored `transform.inputs` does not declare
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the placeholder,
  and the pipeline remains disabled

#### Scenario: Previewing an unbound declaration is refused rather than rendered

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a pipeline whose template carries a
  placeholder its `transform.inputs` does not declare
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the placeholder,
  rather than answering with a template rendered around an unsubstituted placeholder

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

The notification SHALL be **scoped to pipelines whose `transform.type` is `llm`**. For a Class-1
pipeline it SHALL NOT fire: ADAS re-resolves the bindings and re-analyzes the expressions on every
materialize call, so a new target column is picked up on the next tick with no signal needed — while a
generation change on that kind discards `materialized_through_version`,
`materialized_through_identity` and `drained_at`, which holds every consumer of that enrichment back
entirely until the next materialize re-records a position. Nothing is re-scanned — the cursor is not
generation-scoped — but without the scoping, adding one column would cost a clamp blackout it did not
cost before this change.

A metadata patch that changes a column's **`description`** SHALL bump an `llm` pipeline targeting that
enrichment, exactly as an added column does. This reverses the rule that a metadata-only patch bumps
nothing, and the reason is the `prose` default: under composition an output that declares no prose takes
its `description` from the column, so editing that description **changes what the model is told**. Left
unbumped it would rewrite a live prompt that no feed read would ever pick up. A patch changing only
`display_name`, the semantic tag, `sensitive` or `heavy` SHALL still bump nothing — none of those reaches
the composed schema, and a column's type and `enum_values` are immutable already. A `description` patch
on a **Class-1** target SHALL bump nothing, for the reason every other Class-1 case does not: a `sql`
output carries its own expression, no prose is composed for it, and a bump would discard
`materialized_through_version` and clamp every consumer for a change it cannot observe.

**Changed** means the stored value differs, not that the caller supplied the member. A patch that
re-sends a column's existing `description` verbatim SHALL bump nothing. The distinction is not
pedantic: an idempotent authoring or seeding client re-sends the whole metadata block on every run, and
bumping on *supplied* would bump that target's pipeline every time — re-syncing the runner, stalling
the dependent rollup for a cadence, and re-keying whatever work was in flight, for a declaration that
did not move. The system SHALL therefore compare each patched column's stored `description` against the
submitted one and bump only on a difference.

#### Scenario: A description patch on an llm pipeline's target bumps it

- **WHEN** `PATCH /v1/tables/{name}/schema` changes a column's `description` on an enrichment that is an
  `llm` pipeline's target
- **THEN** that pipeline's `generation` is incremented and its `updated_at` advances in the same
  transaction, and the next feed read composes the new description into the `response_schema` for any
  output of that name declaring no prose of its own

#### Scenario: Re-sending an unchanged description bumps nothing

- **WHEN** `PATCH /v1/tables/{name}/schema` supplies a column's `description` with the value already
  stored, on an enrichment that is an `llm` pipeline's target
- **THEN** no pipeline's `generation` or `updated_at` changes, so an idempotent client re-applying its
  metadata does not re-sync the runner on every run

#### Scenario: A display-name-only patch still bumps nothing

- **WHEN** `PATCH /v1/tables/{name}/schema` changes only a column's `display_name`, semantic tag,
  `sensitive` or `heavy` flag on an enrichment that is an `llm` pipeline's target
- **THEN** no pipeline's `generation` or `updated_at` changes, because none of those members reaches the
  composed schema

#### Scenario: A description patch on a Class-1 target bumps nothing

- **WHEN** `PATCH /v1/tables/{name}/schema` changes a column's `description` on an enrichment that is a
  `sql` pipeline's target
- **THEN** that pipeline's `generation` is unchanged and its `materialized_through_version` is preserved

Adding a **provenance** column SHALL be treated like any other added column of the target, so adding
`pipeline_generation` to an existing `llm` target bumps that pipeline once and no output binds to it.

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
- **AND** if the declaration declares an output of that name, the next materialize writes the
  new column

#### Scenario: A metadata patch on a non-target enrichment bumps nothing

- **WHEN** `PATCH /v1/tables/{name}/schema` changes a column's `description` on an enrichment that no
  pipeline targets
- **THEN** no pipeline's `generation` or `updated_at` changes

### Requirement: Dry-run preview of an enrich declaration

The system SHALL extend `POST /v1/pipelines/{name}/preview` to the `enrich` kind. It SHALL answer in the
shared preview shape (see [pipeline-registry](../pipeline-registry/spec.md), "The dry-run preview answers
in one shape for both kinds"): a bounded **sample** of what the transform would produce or receive, its
typed `columns`, and coded `notes`, with no row count. It SHALL write nothing to the target and SHALL call no
model, and SHALL be permitted on a disabled pipeline — it is the checkpoint an author has before arming.

**What preview validates SHALL depend on the pipeline's state.** On a **disabled** pipeline preview
SHALL run the kind's full declaration gate before sampling anything, and SHALL refuse an incomplete
declaration with the same HTTP 422 `pipeline_validation_failed` a defective one draws, one problem per
refusal. It SHALL run the checks in the order arming runs them — the trigger's arm checks, then the full
gate — **before** any transform-presence check, so the first problem a disabled preview reports is the
first problem an enable-only patch arming it would report: a preview that passes means arming passes
against the same catalog, and a preview that refuses gives the refusal arming would give. Having gated
the declaration, a disabled preview SHALL sample through **that** declaration, with its output mapping
derived in memory by the gate, rather than through the stored mapping — so a draft whose stored mapping
is stale (its target's schema defined or changed after the draft was saved) previews successfully
whenever arming would succeed. On an
**enabled** pipeline preview SHALL run only the checks it runs today (the template-placeholder check,
the transform's presence and type, and the response-schema composition, whose refusal it keeps although
the schema is no longer returned), and SHALL NOT re-run the gate.

The split is not a convenience; it is the only reading under which both halves stay true. The
narrow check earns its narrowness on an armed pipeline with a stated argument — the preview is
previewing what is *stored*, not re-validating it, and asking the gate again would report a pipeline
as unpreviewable for a drift the operator did not cause (a column renamed on the read source since
the declaration was written, a folded legacy declaration the gate would now reject). That argument
rests entirely on the declaration having passed the gate when it was written, which is true of every
armed pipeline and, once the gate is deferred, true of no draft. A disabled pipeline's declaration
may never have been gated at all, so there is nothing to be loyal to and everything to check:
disabled means "check my work", enabled means "show me a sample". Preview is therefore the author's
diagnostic and the one place a declaration can be judged without arming it — which is what makes
deferring the gate safe to offer rather than merely permissive.

Preview SHALL evaluate the **stored** declaration and SHALL NOT accept a candidate transform in its
request body. A `PATCH` on an enabled pipeline makes the new prompt live at the next sync, so the
checkpoint for a prompt change is `enabled: false`, preview, then enable.

The preview response SHALL NOT carry the transform's `type`, `evaluator_name`, `evaluator_version`, the
response schema, `response_schema_source`, the input bindings or the output mapping. The pipeline read
serves every one of them: `transform.type` and the authored `transform`, the resolved `response_schema`
and `outputs` on `view=compiled`, and the schema's source. A stored `transform.response_schema` is
present on the authored read exactly when the schema is stored rather than composed.

The sample SHALL depend on the transform:

- **A Class-1 `sql` pipeline** SHALL sample the grain key (`grain_key`) and every derived value
  (`output`). Each output carries `type`, the type its translated expression yields (the value the
  registration gate compared against the target column), and `target_type`. `statement` SHALL be the
  declaration's portable `SELECT` in the ADAS SQL dialect, with `query` its structured-query DSL document
  (see the shared shape). It is a row query over the read source's entity that projects the grain key and
  each output's expression under its target column name, applies the pipeline's `filter` as its `WHERE`,
  orders by the grain key ascending and is limited to the sample's row cap. An enrichment column an
  expression or the filter reads is named by its exposed name, with no join. It reproduces what each
  output computes for a source row, not which rows the preview sampled. It carries none of the sample's
  window (the newest-first order, the safety lag, a `from`/`to` bound), all of which read the source's
  version column: ordered by the grain key, two runs of it agree while the source is still being written,
  and it needs no version column exposed to queries. Its rows therefore need not be the sampled ones. An output the dialect cannot express SHALL leave
  `statement` and `query` absent and carry `statement_not_portable`; its `subject` names the output where
  the refusal can be attributed to one (an output named like a source field it does not project), and is
  absent otherwise.
- **A row-grain `llm` pipeline** SHALL sample the grain key and each column a `transform.inputs` binding
  reads (`input`), keyed by the source column name the binding names. It SHALL NOT return the
  pipeline's filter or cursor columns. Leaving them out of the projection SHALL NOT take them out of the
  read: a filter column still draws the enrichment join it needs, still counts for sensitive-column
  enforcement, and still counts towards the enrichments the clamp is computed over, exactly as in the
  runner's scan. It SHALL NOT apply an input's JSONata, which is the runner's to evaluate.
- **A `group` pipeline** SHALL sample the group's member rows, carrying only the `input` columns, in the
  order the group assembly delivers them. The group key is reported once, as `group_key`. The filter,
  version and identity columns the assembly reads to select and order members are not returned.

Preview SHALL report `output_without_description`, one note per output, for every `llm` output that
composed no `description`: one declaring no prose whose target column also carries none. Such a declaration is
storable and armable, so a refusal would be wrong. But a property the model is given no description for
is the kind of degradation an author wants told at the checkpoint, rather than inferred from an answer.
Preview SHALL report `unconstrained_response` for an `llm` declaration that composes no response schema.

Preview SHALL NOT return a rendered prompt. The template renderer and the group-input assembler live
in the runner, and their escaping is what determines what the model actually receives; a second
implementation in ADAS would be the last place to accept drift. No rendered-request dry run exists on
either service: the Enrichment Runner exposes runtime introspection and operator actions (runtime, DLQ,
pause and resume, backfill, group listing and requeue) and no rendering or test-call surface. (An earlier
revision of this requirement pointed at a runner dry-run surface; none was ever built.) A dry run
that renders the request, or calls the model on one sampled row or group, belongs to the runner, which
owns the renderer, and is a separate change.

Preview SHALL read through the **already-bounded** governed read paths, with an explicit row cap of five
rows and the optional `from`/`to` version window, rather than through the aggregate preview's
compiled-statement path. The aggregate preview's
budget bounds memory and time but not rows read, and an `llm` enrich preview reads the heavy body columns
that are refused unbounded and whose group-assembly caps apply only after the read.

**Window and order:**

- A row-grain and a Class-1 sample SHALL be read **newest first**, by the source's version and then its
  identity, both descending, within the safety lag (and, for row-grain, under the freshness clamp).
- The runner's scan SHALL keep its ascending keyset order. The preview's descending read is a separate
  read over the same predicates, not a mode of the runner's.
- The request body MAY carry `from` and `to` to restrict the sample to a version window. Without them
  the sample is the newest rows of the whole source.
- The body SHALL NOT carry a `cursor`. A seek-past cursor has no meaning under a newest-first order, and
  preview returns none to continue from, so it is refused as an unknown member with HTTP 400.

**The freshness clamp SHALL be applied by kind and reported, not left unexplained:**

- A Class-1 sample is **not** clamped. A preview stores nothing, so the permanently wrong stored value
  the clamp prevents cannot occur, and clamping would empty the sample of an author whose upstream
  enrichment happens to be behind. Preview SHALL still compute the bound the materialize write would
  apply, for its notes only. Where the newest sampled row is newer than that bound, preview SHALL
  report `enrichment_behind`, because those rows read the enrichment's columns as unmatched.
- A row-grain `llm` sample is clamped as the runner's scan is. Where the clamp leaves matching rows
  above it, preview SHALL say so: with `held_by_enrichment` when the sample is empty, and with
  `enrichment_behind` when it is not. Either way it SHALL NOT report `no_matching_rows`. Whether such
  rows exist SHALL be asked by one probe over the same predicates, past the bound, projecting only the
  version and reading at most one row; it SHALL NOT re-run the sample with the clamp lifted, which
  would read its heavy columns again.
- A `group` sample's choice of group is clamped as the runner's group marking is (below); its member
  fetch is not clamped, because the runner's group fetch is not.
- On every arm, a read enrichment with no freshness bound at all SHALL be reported as
  `held_by_enrichment` without a probe, since an enabled run would write nothing until it has one.

For a **`group`** pipeline, preview SHALL take the group key **value** to sample from an optional
`group_key` in the request body. A body naming none SHALL sample the group of the **newest** input row
that matches the declaration's filter within the safety lag, under the freshness clamp the runner's
group marking applies, and whose group key is neither NULL nor empty (the group fetch refuses a blank
key, and a keyless row belongs to no group). The choice SHALL enforce sensitive-column entitlement over
the group key and the filter columns before it reads, and SHALL resolve an enrichment-sourced group key
as the scan does. It SHALL first look within the last 24 hours of versions, which prunes partitions on
a source that declares a partition column, and only if that finds nothing look once over the whole
source. Either way, preview SHALL report the value it used as `group_key`. If no such row exists,
preview SHALL return an empty sample with `no_group_found` rather than an arbitrary group, or with
`held_by_enrichment` when an enrichment the clamp is computed over has no freshness bound.

The member sample SHALL hold at most five members, and only those five SHALL be read with their bound
input columns. The runner's cap on one group is the smaller of the configured group-fetch default row
count and the declaration's `member_select.limit`. Preview SHALL count the group's members after any
`member_select` preference, up to one past that cap and without projecting bound input columns (the
filter still applies in the count's `WHERE`, whatever columns it names). It SHALL
report `group_cut_by_fetch_limit`, naming the cap and both counts, when the count exceeds the cap and the
cap is the configured default rather than a `member_select.limit` the declaration chose. Narrowing the
declaration asked for is not a cut and SHALL NOT be reported as one. The group fetch's byte cap cannot
be predicted from five members and is not reported. A `group` body SHALL NOT carry `from` or `to`, and a
row-grain or Class-1 body SHALL NOT carry a non-empty `group_key` (a whitespace-only one included, since
the `group` arm reads only an absent or empty key as absent); either is HTTP 422 `pipeline_validation_failed`.

The `no_group_found` report is not the incomplete-declaration refusal and SHALL NOT be confused with it.
The body's group key names **which group** to sample, while `trigger.group_by` names **which column** the
declaration groups by, and a `group` declaration carrying no `trigger.group_by` is incomplete (see
[enrichment-runtime](../enrichment-runtime/spec.md)). So a disabled pipeline missing `trigger.group_by`
is refused 422 `pipeline_validation_failed` before anything is sampled and never reaches the choice of
group.

Every read a preview issues (the sample, the clamp probe, the group choice, the member count and the member read,
which on a source declaring no ordering key is a second single-phase selection limited to the sample)
SHALL run under the pipeline read budget, and a read it refuses SHALL be HTTP 422
`pipeline_preview_budget_exceeded` (see [enrichment-runtime](../enrichment-runtime/spec.md), "Every
pipeline-internal read runs under a resource budget"). Its message SHALL name the read refused, and the
remedies it offers SHALL be ones the arm accepts: a narrower `from`/`to` window is not offered on a `group` preview, which refuses one.

#### Scenario: Preview of a Class-1 pipeline returns typed values and their statement

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `schedule` pipeline whose
  `transform.type` is `sql`
- **THEN** the response carries a bounded sample of computed values, `columns` naming the grain key and
  each output with its inferred `type` and its `target_type`, and the portable `statement` and `query`
- **AND** the target is unchanged

#### Scenario: A Class-1 statement computes the outputs through the query API

- **GIVEN** a Class-1 `sql` pipeline over `dial_usage_log` with grain key `event_id`, an output
  `deployment_lower` declared as `lower(deployment)`, and a `filter`
- **WHEN** it is previewed and its `statement` is sent to `POST /v1/queries/execute-sql`
- **THEN** the statement reads `dial_usage_log`, projects `event_id` and `lower(deployment)` as `deployment_lower`,
  carries the filter as its `WHERE`, and has no `JOIN` and no `SETTINGS` clause
- **AND** the query answers HTTP 200, and for each `event_id` it shares with `rows` it answers the same
  `deployment_lower`

#### Scenario: Preview of an llm pipeline returns the input values only

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled row-grain `llm` pipeline
- **THEN** the sample's columns are the grain key, with role `grain_key`, and the source columns its
  `transform.inputs` bind, each with role `input`, and no filter or cursor column
- **AND** the response carries no `response_schema`, no `inputs`, no `outputs` and no `statement`
- **AND** no model is called

#### Scenario: The preview response carries no declaration echo

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for any `enrich` pipeline
- **THEN** the response carries none of `type`, `evaluator_name`, `evaluator_version`,
  `response_schema`, `response_schema_source`, `inputs` or `outputs`

#### Scenario: A folded legacy declaration's stored schema is visible on the read

- **GIVEN** a pipeline whose transform was folded from an old-shape evaluator version carrying a stored
  `response_schema`
- **WHEN** it is read with `GET /v1/pipelines/{name}`
- **THEN** the authored `transform` carries that stored schema, which is how a client tells a stored
  schema from a composed one

#### Scenario: Preview notes an output that composed no description

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an `llm` pipeline with an output declaring
  no prose whose target column also carries no `description`
- **THEN** the preview succeeds and carries an `output_without_description` note whose `subject` names
  that output

#### Scenario: A row-grain sample shows the newest rows

- **WHEN** a row-grain or Class-1 pipeline is previewed with no `from` or `to`
- **THEN** the sample holds the source's newest matching rows within the safety lag (and, for
  row-grain, under the freshness clamp), newest first

#### Scenario: A filter on an enrichment column still reads through its join

- **GIVEN** a row-grain `llm` pipeline whose `filter` references an enrichment column that no input binds
- **WHEN** it is previewed
- **THEN** the sample is read with that enrichment joined, the filter column is not among the `columns`,
  and the enrichment counts towards the clamp

#### Scenario: A cursor is no longer accepted

- **WHEN** `POST /v1/pipelines/{name}/preview` is called with a body carrying `cursor`
- **THEN** the request is rejected with HTTP 400

#### Scenario: A group preview names the group it sampled

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a `group` pipeline with a request body
  carrying a group key
- **THEN** the response samples that group's member rows and reports the key it used as `group_key`

#### Scenario: A group preview with no key samples the newest group

- **GIVEN** a `group` pipeline whose declaration is **complete**, so its `trigger.group_by` resolves
- **WHEN** it is previewed with a request body naming no group key value
- **THEN** the response samples the group of the newest matching input row and reports that group's key
  as `group_key`, in either pipeline state
- **AND WHEN** no input row matches
- **THEN** the sample is empty and `notes` carries `no_group_found`

#### Scenario: The newest group is chosen among rows that have a key

- **GIVEN** a `group` pipeline whose newest matching input rows carry an empty group key, and whose
  older matching rows carry non-empty keys
- **WHEN** it is previewed with no group key value
- **THEN** the sample is the group of the newest matching row with a non-empty key

#### Scenario: A group choice held by an enrichment says so

- **GIVEN** a `group` pipeline whose clamp is computed over an enrichment with no freshness bound
- **WHEN** it is previewed with no group key value
- **THEN** the sample is empty and `notes` carries `held_by_enrichment` naming that enrichment, and no
  `no_group_found`

#### Scenario: A group larger than the runner would fetch is reported

- **GIVEN** a `group` pipeline with no `member_select` limit, and a group with more members than the
  runner's group fetch row cap
- **WHEN** that group is previewed
- **THEN** the sample holds five members and `notes` carries `group_cut_by_fetch_limit` naming the cap
- **AND WHEN** the declaration's `member_select.limit` is below the configured default
- **THEN** no `group_cut_by_fetch_limit` note is carried
- **AND WHEN** the declaration's `member_select.limit` is above the configured default and the group
  exceeds the default
- **THEN** `group_cut_by_fetch_limit` is carried, naming the configured default as the cap

#### Scenario: An empty sample emptied by the clamp blames the clamp

- **GIVEN** a row-grain `llm` pipeline whose filter matches rows, reading an enrichment that has no
  freshness bound, or whose bound lies below every matching row
- **WHEN** it is previewed
- **THEN** the sample is empty and `notes` carries `held_by_enrichment` naming that enrichment, and no
  `no_matching_rows`

#### Scenario: A row-grain sample cut short by the clamp says so

- **GIVEN** a row-grain `llm` pipeline reading an enrichment whose freshness bound lies between its
  oldest and newest matching rows
- **WHEN** it is previewed
- **THEN** the sample holds the newest rows at or below the bound and `notes` carries
  `enrichment_behind` naming that enrichment and giving the gap

#### Scenario: A Class-1 sample past an enrichment's position says so

- **GIVEN** a Class-1 pipeline reading an enrichment whose freshness bound is older than the newest
  sampled rows
- **WHEN** it is previewed
- **THEN** the sample is returned unclamped and `notes` carries `enrichment_behind` naming the
  enrichment

#### Scenario: Preview of a disabled incomplete declaration reports what is missing

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled pipeline whose declaration is
  not yet complete for its kind
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming a member the
  declaration does not carry, and nothing is read or sampled

#### Scenario: Preview of a disabled draft reports the same first problem an enable-only patch would

- **GIVEN** a disabled `enrich` pipeline registered with `name`, `kind` and `target` alone
- **WHEN** it is previewed, and separately an enable-only patch arms it
- **THEN** both are rejected with HTTP 422 `pipeline_validation_failed` reporting that `trigger.kind`
  is required, rather than preview naming the absent `transform` while arming names the trigger

#### Scenario: Preview of a disabled draft samples through the mapping the gate just derived

- **GIVEN** a disabled `enrich` pipeline whose stored `output_bindings` are stale because its target's
  schema was defined after the draft was saved, and whose declaration now passes the gate
- **WHEN** `POST /v1/pipelines/{name}/preview` is called
- **THEN** the preview succeeds, sampling through the output mapping derived in memory from the gated
  declaration rather than the stored one, and nothing is written

#### Scenario: Preview of a disabled pipeline runs the full gate, not only the template check

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `enrich` pipeline whose
  declaration is complete but whose `filter` does not parse
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the filter, so
  the author learns at the checkpoint what arming would have told them

#### Scenario: Preview of an enabled pipeline is not refused for drift the operator did not cause

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an **enabled** pipeline whose stored
  declaration no longer passes the gate — a read-source column renamed since it was written, or a
  folded legacy declaration the gate would now reject
- **THEN** the preview succeeds, running only the checks an enabled preview runs and not the gate,
  because an armed pipeline's declaration was gated when it was written and a preview is not a
  re-validation

### Requirement: Every governed SQL fragment is deterministic

A governed SQL fragment a pipeline carries SHALL be rejected when it calls a function the query
function catalog declares **non-deterministic** — the governed fragments being `filter`,
`trigger.ready_when.signal`, `trigger.member_select.prefer_sql`, a `sql` transform's output
expressions, and an aggregate declaration's `filter` and per-measure `where` (see
[structured-query](../structured-query/spec.md), "The catalog declares which functions are
non-deterministic"). The rejection SHALL be HTTP 422 `pipeline_validation_failed`, the same code
every other defective-declaration failure carries, with a message naming the offending function and
stating that a pipeline declaration must be reproducible. It SHALL apply to a nested call as well as
a top-level one, since `date_sub('hour', 1, now())` is no more reproducible than `now()` is —
**including a call nested inside a lambda body**, which is an ordinary expression and hides the clock
exactly as any other argument position does.

This check is part of the declaration gate and SHALL therefore run **where the gate runs** — a write
that arms the pipeline and a preview of a disabled one — and not on a write that leaves the pipeline
disabled, nor on a preview of an enabled one, nor on the data plane, which runs no part of this gate
beyond the two rules `…/materialize` re-checks. A clock call can consequently be *stored* on a draft, which
changes nothing about what runs: no disabled pipeline is scheduled, driven or fed to the runner, and
the first attempt to arm or preview it is refused. The property being protected is that a
declaration something else re-runs on its own schedule means the same thing every time, and that
property attaches to a declaration that runs.

The check SHALL be driven by the catalog's declared property and SHALL NOT be a list of function
names maintained beside the catalog: a second list would imply the catalog is open — it is not — and
would rot the first time a function was added without remembering the list exists. Adding a
non-deterministic function to the catalog therefore closes this gate around it with no change here.
Adding a new **expression shape** is not free in the same way: the gate is a walk over the translated
document, so every shape that can carry a sub-expression — the lambda body this change adds among
them — SHALL be walked, or a declaration reading the clock through it is accepted silently.

**The rule is deliberately total, across every fragment and both pipeline kinds**, rather than
applied only where non-reproducibility is provably unsound. A narrower rule is arguable for some
positions — a `filter` selecting only recent rows, a readiness `signal` reading the clock — and each
would need its own account of what a moving predicate does to a cursor that never revisits a window,
to a refresh that recomputes a historical bucket, and to the audit trail that records what a
declaration said rather than what it selected. Until such a position is worked through and admitted
deliberately, the gate refuses the whole family: a declaration is a stored statement of intent that
something else re-runs on its own schedule, so "the same declaration means the same thing every time
it runs" is the property worth keeping, and a caller needing a moving window has the query surfaces,
where it is free.

This narrows nothing that previously worked: no non-deterministic function existed in the closed
catalog before the clock was added, so no stored declaration can contain one.

#### Scenario: A pipeline filter reading the clock is rejected

- **WHEN** a write arms a pipeline, or a disabled pipeline is previewed, whose `filter` is
  `request_time >= now() - INTERVAL 1 DAY`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  `now` and stating that a pipeline declaration must be reproducible

#### Scenario: A clock call saved on a draft is refused the moment it arms

- **WHEN** that same `filter` is saved on a pipeline that stays disabled, and the pipeline is armed
  later
- **THEN** the draft write is accepted, no schedule or feed ever picks the pipeline up, and the
  arming request is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: A readiness signal reading the clock is rejected

- **WHEN** a `group` pipeline whose `trigger.ready_when.signal` calls `now()` is armed or previewed
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A nested non-deterministic call is rejected

- **WHEN** a governed fragment calls `date_sub('hour', 1, now())` rather than `now()` directly
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A non-deterministic call inside a lambda body is rejected

- **WHEN** a governed fragment calls `arrayExists(x -> equals(x, to_string(now())), execution_path)`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  `now` — the same refusal the same call gets outside a lambda

#### Scenario: A deterministic lambda body is accepted

- **WHEN** a governed fragment calls `arrayExists(x -> starts_with(x, deployment), execution_path)`
- **THEN** the fragment is accepted: it is the clock that is refused, not the lambda

#### Scenario: A deterministic fragment using the offset functions is accepted

- **WHEN** a governed fragment calls `date_trunc('day', request_time)` or
  `date_sub('day', 1, request_time)` — the offset functions over a column, with no clock
- **THEN** the fragment is accepted: it is the clock that is refused, not date arithmetic

### Requirement: A stored ENRICH declaration carrying no transform is refused rather than run empty

The system SHALL refuse to preview, scan, assemble a group for, materialize or accept a write-back
for an `ENRICH` pipeline whose **stored** `spec` carries no `transform`, with HTTP 422
`pipeline_validation_failed`. An empty transform
SHALL NOT be manufactured on read: reading a declaration SHALL NOT change it, and a transform SHALL be
created only where a write authors one.

The refusal applies on all five paths — preview, scan, group assembly, materialize and the write-back —
with the same status and code on each, and its **ERROR log is conditional** on the pipeline being
enabled (below).

**The message SHALL NOT assert a cause it cannot know.** A declaration with no transform is reachable
by damage and is also the first thing an author saves, and the two are the same bytes. The refusal
names the pipeline and states that the declaration carries no transform and that nothing will run until
one is declared. It may mention earlier damage as a possibility, but SHALL NOT claim it as the cause.
It is not an exception to the timing rule: this refusal is one an endpoint **owns unconditionally**, so
it applies in either pipeline state — except that the two writing endpoints refuse
a disabled pipeline before asking about its declaration at all.

The **log level SHALL follow the pipeline's state**, which is the one signal that does separate the two
causes. A **disabled** pipeline with no transform is an ordinary draft and SHALL be refused without an
ERROR log — one line per draft scan would bury the damage this requirement was written for. An
**enabled** pipeline with no transform SHALL still be logged at ERROR naming the pipeline: the gate
refuses to arm a declaration carrying no transform, so an armed one that has lost it did not get there
through the API, and that is exactly the corruption below.

That corruption remains reachable. A build that predates the fold parses the stored `spec` while
ignoring unknown members, so a pipeline write it serves rewrites the row **without** the transform and
with the transform's input bindings emptied. During a rolling upgrade both builds serve concurrently,
so any pipeline write handled by the outgoing pod silently un-folds that pipeline. The emptied input
bindings travel with it, and are the more dangerous half: the drop/rename dependency guard reads those
bindings to decide which columns a pipeline still needs, so an emptied list makes columns look
droppable. The derivation half of the declaration gate is what keeps a *current* build from ever doing
the same — it runs on every write and never replaces a derived mapping it could not re-derive — so the
emptied-bindings state is now specific to a write served by an outgoing pod.

The refusal SHALL NOT be applied at the registry read. `GET /v1/pipelines` SHALL still serve such a
pipeline, because listing is how an operator finds the damaged row, and because refusing there would
fail the whole registry read — and with it the sync feed — over one row. The refusal belongs at the
paths that would otherwise act on the empty definition.

The operator repair for the damaged case SHALL be to re-declare the pipeline in full; the previous
declaration is recoverable from the pipeline's audit trail, and the bound input columns are to be
re-checked because the same write emptied them. The author's repair for the draft case is the same
action arrived at from the other side — declare the transform — which is why one code and one message
serve both. Preventing the damaged state at all is a deploy-order matter — no pipeline write for the length of
the ADAS roll — which `docs/configuration.md` carries; this requirement is the backstop for when that is
not observed.

#### Scenario: A declaration carrying no transform is refused at preview

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an `ENRICH` pipeline whose stored `spec`
  carries no `transform` — and, if the pipeline is disabled, whose trigger is complete, since a disabled
  preview reports a trigger problem first, as arming does
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, and the message
  states that the declaration carries no transform and that declaring one is the repair, without
  asserting which of the two causes produced it

#### Scenario: An armed pipeline that has lost its transform is logged at ERROR

- **WHEN** such a pipeline is **enabled** — a state the gate cannot produce, so one reached by a write
  served by a pre-fold build
- **THEN** the refusal is additionally logged at ERROR naming the pipeline, so the corruption is
  visible rather than indistinguishable from an author's draft

#### Scenario: A draft carrying no transform is refused without an ERROR log

- **WHEN** such a pipeline is **disabled** — an author who registered a name and a target and has not
  declared the transform yet
- **THEN** the refusal is the same 422 `pipeline_validation_failed` and nothing is logged at
  ERROR, so a listing or a scan attempt over many drafts does not bury the case above

#### Scenario: A declaration carrying no transform is refused across the data plane

- **WHEN** the runner calls the scan, group-rows, materialize or write-back data plane for such a
  pipeline
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` and no row is
  written, so the pipeline stops rather than deriving against an empty definition — in either pipeline
  state on scan and group-rows, and on materialize and write-back for an enabled pipeline, a disabled
  one being refused there first with HTTP 409 `pipeline_conflict`

#### Scenario: Such a declaration is still listable

- **WHEN** `GET /v1/pipelines` is called while one `ENRICH` pipeline's stored `spec` carries no
  `transform`
- **THEN** the listing succeeds and includes that pipeline, so the damaged row is discoverable and the
  feed is not failed by it

#### Scenario: Reading a declaration does not create a transform

- **WHEN** a stored `spec` carrying no `transform` is parsed and re-serialized without an authoring write
- **THEN** the stored bytes are unchanged and no empty `transform` block appears

### Requirement: Registry reads and writes are authorized by registry privileges

The **pipeline registry** SHALL be authorized by privilege rather than by application role.
Reading it, the runner's pipeline sync feed included, SHALL require `REGISTRY_READ`. An enrichment's
definition is authored on its pipeline as `transform`, so authoring it is `REGISTRY_ADMIN` on the
pipeline surface. The pipeline endpoints the sync feed and the ENRICH declaration share with the
aggregate kind are specified once, in [aggregate-pipeline](../aggregate-pipeline/spec.md).

`REGISTRY_READ` SHALL NOT confer any table privilege: an identity holding it may enumerate pipelines
without being able to read the tables they reference. Conversely a table privilege SHALL
NOT confer `REGISTRY_READ`.

Registry payloads name columns — a pipeline's `filter_sql`, `group_by`, `member_select` and
`ready_when`, and the `transform`'s request template — and the service SHALL NOT redact them.
`REGISTRY_READ` therefore discloses column names, consistent with column names not being secret;
column **values** remain governed by the table privileges.

#### Scenario: Registry read does not confer table read

- **WHEN** a caller holding `REGISTRY_READ` and no table privilege lists pipelines and then queries the
  source one of those pipelines reads
- **THEN** the pipeline listing succeeds and the query reports an unknown entity

#### Scenario: Authoring requires REGISTRY_ADMIN

- **WHEN** a caller holding `REGISTRY_READ` but not `REGISTRY_ADMIN` registers a pipeline
- **THEN** the response is `403 forbidden`

#### Scenario: The sync feed needs only REGISTRY_READ

- **WHEN** an Enrichment Runner holding `REGISTRY_READ` and per-table grants requests the pipeline sync
  feed
- **THEN** the feed is returned, and the runner needs no bundle conferring global table read

### Requirement: Trigger kinds and the default schedule

An ENRICH pipeline's `trigger.kind` SHALL be `schedule` (row grain, fired on its `trigger.cron`) or
`group` (group grain, fired by the caller's readiness sweep). There SHALL be one row-grain trigger with
one clock. `on_ingest` was a second row-grain kind that differed from `schedule` only in being driven by
a fixed delay (`advanced.scan_every`) instead of a cron, over the same cursor-incremental scan. Its name
promised event-driven delivery that never existed, so it is retired rather than kept as an alias.

**`on_ingest` SHALL be refused on every write**, armed or not, with HTTP 422
`pipeline_validation_failed`. The message SHALL name `on_ingest` as retired and `schedule` as its
replacement. It is a 422 rather than a binding 400 for the reason every other `trigger.kind` value is:
`kind` binds as a raw string so that an unsupported value is a governance failure naming the
alternatives. No read SHALL return `on_ingest`, because the stored rows are migrated (below).

**An enrich `schedule` trigger that declares no `trigger.cron` SHALL be stored with a default cron:
every minute, at a second derived from the pipeline name.** The stored value SHALL be `S * * * * *`,
where `S` is the first four bytes of the MD5 digest of the UTF-8 pipeline name, read as an unsigned
big-endian integer, modulo 60. A blank `cron` SHALL count as absent. The default SHALL be resolved
**at write time, on every write**, a disabled draft included. The resolved cron SHALL be stored and
echoed by every read, so after its first write a pipeline's cadence is an explicit, readable
declaration rather than a runner-side fallback nobody can see. A declared cron SHALL be stored as sent.
The second is derived rather than fixed at `0` because cron fires on the wall clock: a fixed second puts
every defaulted pipeline's scan into the same instant, and the caller runs scans ahead of all queued
evaluation work. The derivation SHALL be a pure function of the name, so the same name resolves to the
same second on every replica, after every restart, and in the migration below. The default SHALL apply
to `enrich` only: an `aggregate` pipeline's `trigger.cron` stays required by the time it is armed,
because a rollup's refresh cost scales with its cadence and is not something to default.

**Stored `on_ingest` pipelines SHALL have been migrated in place, without bumping `generation` or
`updated_at`**, because a bump would discard every Class-1 pipeline's materialized position and re-sync
every runner lane for a change the operator did not make. Each `ON_INGEST` row became `SCHEDULE`, with a
cron derived from its stored `scan_every` (or `cadence`), always rounded **up** to the next cadence a
cron can express evenly. In the forms below, `S` is the name-derived second defined above:

| Stored `scan_every` (`d`) | Stored `trigger.cron` |
|---|---|
| absent, unparseable, or `d ≤ 60s` | `S * * * * *` |
| `60s < d ≤ 60m` | `S */m * * * *`, `m` the smallest divisor of 60 with `m ≥ ⌈d / 1m⌉` (`m = 60` is `S 0 * * * *`) |
| `d > 60m` | `S 0 */h * * *`, `h` the smallest divisor of 24 with `h ≥ ⌈d / 1h⌉` (`h = 24` is `S 0 0 * * *`; a `d` above a day is capped at daily) |

The `scan_every` and `cadence` keys were removed from **every** enrich spec, `group` pipelines included,
and every enrich `SCHEDULE` draft without a cron was given the name-derived default, so a draft saved
before the migration and one saved after it arm alike. Audit revisions recorded before the migration
SHALL be left as written, and an `ON_INGEST` revision SHALL read back as `schedule`, so an audit snapshot
taken from before the migration still loads. The trigger-kind storage constraint SHALL admit only
`SCHEDULE` and `GROUP`.

#### Scenario: A schedule trigger without a cron is stored with the name-derived default

- **WHEN** `POST /v1/pipelines` registers `kind: "enrich"` with `trigger: {"kind": "schedule"}` and no
  `cron`
- **THEN** the pipeline is stored with `trigger.cron` equal to `S * * * * *`, `S` derived from its
  name, and every read returns that cron

#### Scenario: The default is resolved on a draft too

- **WHEN** a disabled enrich pipeline is saved with `trigger.kind: "schedule"` and a blank `cron`
- **THEN** the stored `trigger.cron` is the name-derived default, and arming it later asks for no cron

#### Scenario: The default second is a function of the name alone

- **WHEN** an enrich pipeline is deleted and re-registered under the same name with no `cron`
- **THEN** it is stored with the same `trigger.cron` it had before

#### Scenario: A declared cron is stored as sent

- **WHEN** an enrich `schedule` trigger declares `cron: "0 */5 * * * *"`
- **THEN** that cron is stored and returned unchanged

#### Scenario: An aggregate pipeline still requires its cron

- **WHEN** an `aggregate` pipeline is armed without a `trigger.cron`
- **THEN** arming is refused with HTTP 422 `pipeline_validation_failed` naming `trigger.cron`, exactly as
  before

#### Scenario: A stored on_ingest pipeline becomes a schedule pipeline without a generation bump

- **WHEN** the migration runs over an enabled `ON_INGEST` Class-1 pipeline storing `scan_every: "60s"`
  and a materialized position
- **THEN** the row is `SCHEDULE` with `trigger.cron` equal to `S * * * * *`, its spec carries no
  `scan_every`, and its `generation`, `updated_at` and materialized position are unchanged

#### Scenario: A cadence a cron cannot express is rounded up

- **WHEN** the migration runs over an `ON_INGEST` pipeline storing `scan_every: "90s"`, and another
  storing `"7m"`
- **THEN** the first is stored with `S */2 * * * *` and the second with `S */10 * * * *`

#### Scenario: An audit snapshot from before the migration still loads

- **WHEN** `GET /v1/pipelines/{name}/revision/{revision}` is called for a revision recorded while the pipeline was `on_ingest`
- **THEN** the snapshot loads and reports `trigger.kind: "schedule"`, and the stored revision is unchanged

#### Scenario: A group pipeline loses only its stored cadence

- **WHEN** the migration runs over a `GROUP` pipeline storing `scan_every: "5m"`
- **THEN** its spec no longer carries `scan_every`, and its trigger, `generation` and `updated_at`
  are unchanged
