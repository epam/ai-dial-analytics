## RENAMED Requirements

- FROM: `### Requirement: Static governance gate on register and enable`
- TO: `### Requirement: Static governance gate on arming and execution`

## MODIFIED Requirements

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
consistent for its `type`; (5) `trigger.kind ∈ {on_ingest, schedule, group}` and the trigger's
declaration is internally consistent; (6) the type-specific checks; (7) the group-trigger checks below.

There SHALL be no "the evaluator resolves" check, because there is nothing left to resolve: the
transform is a member of the declaration being validated. The whole class of failure this removes —
accepted at one object's registration and refused at another's binding — is gone rather than moved.

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

Note (the model check): the gate does NOT verify that an `llm` transform's `model` names a
live DIAL deployment — ADAS has no deployment-listing client to reuse (only caller-key
introspection). Live deployment verification is deferred; only a non-blank model is enforced here.
What the fold removes from this note is its other half: there is no evaluator resolution left to
enforce.

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

- **WHEN** a pipeline declares a `trigger.kind` outside `{on_ingest, schedule, group}` (e.g.
  `push`), or declares `group` for a `transform.type: sql`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

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

#### Scenario: The gate no longer resolves an evaluator

- **WHEN** any `enrich` pipeline is registered, patched or enabled
- **THEN** no `evaluator` or `evaluator_version` row is read, and no failure mode of the form
  "the evaluator does not resolve" can be reported

#### Scenario: A sql expression naming an unknown source column is refused when it arms or is previewed

- **WHEN** a write arms a pipeline, or a disabled pipeline is previewed, whose `sql` transform's
  expression references a column the pipeline's read source does not have
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the expression
  and the column, rather than at the next materialize

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
materialize instead of at the DDL. Folding the transform onto the pipeline does not relax this: it
moves both shapes into `spec`, where the same accessor answers, and removes the second object the scan
previously had to reach through. That accessor is also why the bound above costs less than it reads:
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

### Requirement: Dry-run preview of an enrich declaration

The system SHALL extend `POST /v1/pipelines/{name}/preview` to the `enrich` kind. For such a pipeline
preview SHALL return the composed `response_schema` (or the stored one, where the declaration carries
one), the resolved output mapping, the expanded `transform.inputs` bindings, and a bounded **sample of the input
values** the transform would receive. It SHALL write nothing to the target and SHALL call no model,
and SHALL be permitted on a disabled pipeline — it is the checkpoint an author has before arming.

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
**enabled** pipeline preview SHALL run only the template-placeholder check it runs today, and SHALL
NOT re-run the gate.

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
request body. This is a real narrowing and is stated rather than left to be discovered: before the
fold an author could register a new evaluator version and repoint a pinned pipeline as two steps, so
the definition existed before it was live. After the fold a `PATCH` is one step, and on an enabled
pipeline the new prompt is live at the next sync — the checkpoint is `enabled: false`, preview, then
enable. Accepting a candidate transform in the preview body would restore the two-step workflow
without restoring the second object, and is a separate change.

The preview response SHALL name the transform by **`type`** and SHALL NOT carry `evaluator_name` or
`evaluator_version`: there is no registry row to identify, the pipeline is named in the path, and the
generation is on the pipeline read. `response_schema_source` SHALL be retained — whether the schema is
the declaration's stored one or composed is exactly what an author previewing a folded legacy
declaration needs to know.

Preview SHALL report a **note** for every `llm` output that composed no `description` — one declaring no
prose whose target column also carries none. Such a declaration is storable and armable, so a refusal
would be wrong, but a property the model is given no description for is the kind of degradation an
author wants told at the checkpoint rather than inferred from an answer. This is a note beside the
existing ones, not a new refusal.

Preview SHALL NOT return a rendered prompt. The template renderer and the group-input assembler live
in the runner, and their escaping is what determines what the model actually receives; a second
implementation in ADAS would be the last place to accept drift. A caller wanting the rendered request
uses the runner's own dry-run surface.

Preview SHALL read through the **already-bounded** governed read paths, with an explicit row cap and
version window, rather than through the aggregate preview's compiled-statement path. The aggregate
preview's budget bounds memory and time but not rows read, and an `llm` enrich preview reads the
heavy body columns that are refused unbounded and whose group-assembly caps apply only after the read.

For a **`group`** pipeline preview SHALL take the group key **value** to sample from an optional
request body, and SHALL report which value it used; there is no group-discovery surface in ADAS, so a
group preview whose body names none SHALL report that rather than sampling an arbitrary group. For a
row-grain pipeline the body MAY carry a cursor.

That report is not the incomplete-declaration refusal and SHALL NOT be confused with it: the body's
group key names **which group** to sample, while `trigger.group_by` names **which column** the
declaration groups by, and a `group` declaration carrying no `trigger.group_by` is incomplete (see
[enrichment-runtime](../enrichment-runtime/spec.md)). So a disabled pipeline missing `trigger.group_by`
is refused 422 `pipeline_validation_failed` before anything is sampled and never reaches the
report, and the report answers only the remaining case — a declaration that resolves a group key
column and a caller who did not say which group.

The enrich response SHALL be a **sibling** member of the preview response rather than a reuse of the
aggregate members: an aggregate preview answers with a compiled statement and rows, and an `enrich`
preview answers with a schema, a mapping and sampled inputs.

#### Scenario: Preview of a Class-1 pipeline returns the mapping and the sampled values

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `on_ingest` pipeline whose
  `transform.type` is `sql`
- **THEN** the response carries each target column with the expression bound to it, that expression's
  translated SQL and inferred type, and a bounded sample of computed values, and the target is
  unchanged

#### Scenario: Preview of an llm pipeline returns the composed schema and inputs

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `llm` pipeline
- **THEN** the response carries the composed `response_schema` in declaration order, the resolved
  output mapping, the expanded `transform.inputs`, and a bounded sample of the input values — and no model is
  called

#### Scenario: The preview response names the transform by type

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for any `enrich` pipeline
- **THEN** the response carries `type` and carries neither `evaluator_name` nor `evaluator_version`

#### Scenario: Preview of a folded legacy declaration reports its stored schema

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a pipeline whose transform was folded
  from an old-shape evaluator version carrying a stored `response_schema`
- **THEN** the response carries that stored schema and reports `response_schema_source` as stored

#### Scenario: Preview notes an output that composed no description

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an `llm` pipeline with an output declaring
  no prose whose target column also carries no `description`
- **THEN** the preview succeeds, the composed property carries no `description`, and the response
  carries a note naming that output

#### Scenario: A group preview names the group it sampled

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a `group` pipeline with a request body
  carrying a group key
- **THEN** the response samples that group's member rows and reports the key it used

#### Scenario: A group preview with no key named reports that rather than guessing

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a `group` pipeline whose declaration is
  **complete** — its `trigger.group_by` resolves — with a request body naming no group key value
- **THEN** the response reports that a group key value is required rather than sampling an arbitrary
  group, in either pipeline state, because what is absent is the caller's choice of group and not a
  member of the declaration

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
- **THEN** the preview succeeds, running only the template-placeholder check, because an armed
  pipeline's declaration was gated when it was written and a preview is not a re-validation

### Requirement: Every governed SQL fragment is deterministic

A governed SQL fragment a pipeline carries SHALL be rejected when it calls a function the query
function catalog declares **non-deterministic** — the governed fragments being `filter`,
`trigger.ready_when.signal`, `trigger.member_select.prefer_sql`, a `sql` transform's output
expressions, and an aggregate declaration's `filter` and per-measure `where` (see
[structured-query](../structured-query/spec.md), "The catalog declares which functions are
non-deterministic"). The rejection SHALL be HTTP 422 `pipeline_validation_failed`, the same code
every other defective-declaration failure carries, with a message naming the offending function and
stating that a pipeline declaration must be reproducible. It SHALL apply to a nested call as well as
a top-level one, since `date_sub('hour', 1, now())` is no more reproducible than `now()` is.

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

#### Scenario: A deterministic fragment using the offset functions is accepted

- **WHEN** a governed fragment calls `date_trunc('day', request_time)` or
  `date_sub('day', 1, request_time)` — the offset functions over a column, with no clock
- **THEN** the fragment is accepted: it is the clock that is refused, not date arithmetic

### Requirement: A stored ENRICH declaration carrying no transform is refused rather than run empty

The system SHALL refuse to preview, scan, assemble a group for, materialize or accept a write-back
for an `ENRICH` pipeline whose **stored** `spec` carries no `transform`, with HTTP 422
`pipeline_validation_failed` — the code this refusal ships with today, unchanged. An empty transform
SHALL NOT be manufactured on read: reading a declaration SHALL NOT change it, and a transform SHALL be
created only where a write authors one.

**The wire contract of this refusal is unchanged, and that is deliberate.** The status stays 422 and
the code stays `pipeline_validation_failed`, so a client that matches it today keeps matching. Two
things do change, and neither is visible as a code: the **surface widens** from three paths — preview,
scan and materialize — to five, adding group assembly and the write-back, which previously met this
declaration with whatever diagnosis their own checks produced; and the **ERROR log becomes
conditional** on the pipeline being enabled. Nothing that was accepted becomes refused.

**The message changes with the timing of the gate, and the refusal itself does not.** A declaration
with no transform used to be reachable only by damage; it is now also the first thing an author saves,
and the two are the same bytes — the requirement said as much before there was a second cause. So the
refusal SHALL NOT assert a cause it cannot know: it names the pipeline and states that the declaration
carries no transform and that nothing will run until one is declared, without claiming a pre-fold
write produced it. It SHALL NOT be read as an exception to the timing rule either: this refusal is one
an endpoint **already owns unconditionally**, so it applies in either pipeline state, exactly as it
does today — except that the two writing endpoints now refuse a disabled pipeline before asking about
its declaration at all.

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
