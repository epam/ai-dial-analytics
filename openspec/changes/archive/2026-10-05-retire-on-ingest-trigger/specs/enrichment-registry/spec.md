## ADDED Requirements

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

**Stored `on_ingest` pipelines SHALL be migrated in place, without bumping `generation` or
`updated_at`.** A bump would discard every Class-1 pipeline's materialized position and re-sync every
runner lane for a change the operator did not make. Each `ON_INGEST` row SHALL become `SCHEDULE`, with a
cron derived from its stored `scan_every` (or its pre-rename spelling `cadence`), always rounded **up** to the next cadence a cron can express
evenly. In the forms below, `S` is the name-derived second defined above:

| Stored `scan_every` (`d`) | Stored `trigger.cron` |
|---|---|
| absent, unparseable, or `d ≤ 60s` | `S * * * * *` |
| `60s < d ≤ 60m` | `S */m * * * *`, `m` the smallest divisor of 60 with `m ≥ ⌈d / 1m⌉` (`m = 60` is `S 0 * * * *`) |
| `d > 60m` | `S 0 */h * * *`, `h` the smallest divisor of 24 with `h ≥ ⌈d / 1h⌉` (`h = 24` is `S 0 0 * * *`; a `d` above a day is capped at daily) |

A `scan_every` below a minute therefore becomes every minute. That is intentional: the caller polls
for due work at a coarser granularity than a few seconds, and ingest lags by minutes. The stored
`scan_every` and `cadence` keys SHALL be removed from **every** enrich spec, `group` pipelines included, so no stored
row carries the retired knob. A stored enrich `SCHEDULE` draft that has no cron SHALL be given the
name-derived default the write path now applies, so a draft saved before this change and one saved after it
arm alike. Revisions recorded in the audit history before the migration SHALL be left as written, and an
`ON_INGEST` revision SHALL read back as `schedule`, so an audit snapshot taken from before the migration
still loads. The trigger-kind storage constraint SHALL admit only `SCHEDULE` and
`GROUP` once the rows are rewritten.

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
consistent for its `type`; (5) `trigger.kind ∈ {schedule, group}` and the trigger's
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

#### Scenario: The gate no longer resolves an evaluator

- **WHEN** any `enrich` pipeline is registered, patched or enabled
- **THEN** no `evaluator` or `evaluator_version` row is read, and no failure mode of the form
  "the evaluator does not resolve" can be reported

#### Scenario: A sql expression naming an unknown source column is refused when it arms or is previewed

- **WHEN** a write arms a pipeline, or a disabled pipeline is previewed, whose `sql` transform's
  expression references a column the pipeline's read source does not have
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the expression
  and the column, rather than at the next materialize

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
  and that pipeline switches to composition from its `outputs`. That is a change of meaning from the
  pre-fold rule, where a stored schema won permanently because an evaluator version was immutable, and
  it is stated rather than discovered: it is the only way to migrate a legacy declaration onto
  `outputs`, it is the operator's explicit act because a declaration patch supplies the whole
  declaration, and its consequence is that the model is then held to a newly composed schema whose
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
where it was a list would make a pre-fold body bind cleanly and mean something else.

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
evaluator members belong to **no kind at all** after the fold, so they are refused at the binding on
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

This corrects a statement the pre-fold spec made and the code never implemented: that a patch carrying
only `advanced` is "re-validated and stored" and bumps `generation`. `PipelineMapper.requireComplete`
refuses it, and rightly — the completeness rule and the enable-only distinction are two different
questions about one request, and only the second is what "counts as a declaration" answers.

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

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `schedule` pipeline whose
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

