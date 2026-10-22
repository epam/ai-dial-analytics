## ADDED Requirements

### Requirement: The declaration gate runs when a write arms the pipeline, not when it is stored

The system SHALL apply the kind's declaration gate at the moment a pipeline is **armed or executed**,
and SHALL NOT require a stored declaration to be complete or valid while the pipeline is disabled. A
write that leaves the pipeline `enabled: false` SHALL store what it was given.

This is the registry's single statement of validation timing; where another capability's requirement
says a rule is enforced "at registration", it SHALL be read as enforced at this gate.

**The gate has two halves, and only one of them is deferred.** The gate does not merely inspect a
declaration — it **derives** parts of it: an `aggregate` pipeline's `group_by` is expanded from the
target's `ordering_key` when omitted, and an `enrich` pipeline's `output_bindings` are derived from its
transform's outputs against the target's exposed columns. Neither member is authored, and the gate is
their only writer.

- **Derivation SHALL run on every declaration write**, whether or not the write arms the pipeline, and
  SHALL be best-effort: where a member cannot be derived — no transform declared yet, an output naming
  a column that does not exist — the previously stored value SHALL be left untouched. A declaration
  write SHALL NOT erase a derived member it could not re-derive. Were it to, the catalog's column
  drop/rename dependency guard, which resolves a pipeline's writes from its stored `output_bindings`,
  would stop seeing the pipeline: the column would become droppable, the ClickHouse `ALTER` would
  execute, and the catalog transaction that re-derives afterwards would roll back — leaving the column
  gone from one store and present in the other, with no operation that repairs the pair.
- **Refusal SHALL run when the write arms the pipeline**, and on the dry-run preview of a pipeline
  that is **disabled** and therefore never gated. Arming asks, besides the kind's gate, the trigger's own
  completeness — a trigger kind, and a cron on a `schedule` trigger — and the preview of a disabled
  pipeline SHALL ask the same, so that a preview that passes means arming will pass. An **enabled** pipeline's declaration was gated when
  it was armed, so preview SHALL NOT re-gate it: doing so would report it as unpreviewable for drift
  the operator did not cause and cannot see from there.
- **The pipeline-scoped data plane SHALL NOT run the whole gate.** It refuses a declaration whose
  required members are **absent**, and the materialize path additionally re-checks the two rules whose
  absence produces a silently wrong row rather than a loud failure — the result-type fit against the
  bound target column, and the row-grain rule against the read source's `identity_column`. Importing
  the rest would stop a running pipeline for drift the operator did not cause and cannot see from
  there. The two **writing** endpoints refuse a disabled pipeline outright, so what they execute has
  always passed the gate once, when it was armed. A refusal an endpoint already owns
  unconditionally stays unconditional in either state: turning a server fault into a refusal is not a
  new gate.

**Three classes of refusal SHALL remain unconditional**, on every write, armed or not, and they are
exceptions to the rule above rather than gaps in it:

- **An intra-declaration contradiction the storage layer refuses regardless** — a `member_select`
  declared against a non-`group` trigger, or a non-`schedule` trigger on an `aggregate` pipeline.
  These are refused by database constraints that hold for every row, so without a service-layer
  refusal the author would meet them as an integrity violation reported as a name collision. They are
  also not incompleteness: they are two stated members that cannot both be true, which no amount of
  further authoring resolves.
- **A malformed request body**, which is a binding failure and answered HTTP 400 naming the field, as
  it is today. Deferring governance does not make an unparseable body storable. A `trigger.cron` that
  does not parse is malformed in this sense and SHALL be refused on every write with HTTP 422; only its
  *absence* on a `schedule` trigger is deferred to arming.
- **A named table that does not exist.** `target` and `inputs` each carry a foreign key to the table
  catalog, which the database enforces on every row, so a declaration naming a table that does not exist
  SHALL be refused with HTTP 422 naming it rather than reaching storage and being reported as a name
  collision. Whether the table is the right kind of table, `ACTIVE` and scannable stays the gate's
  question.

A storage-layer refusal that is reached anyway SHALL be reported by the constraint it broke: a
collision on the pipeline's name or target as HTTP 409, and a CHECK or foreign-key violation as HTTP 422
`pipeline_validation_failed` naming the rule — never as "name or target already in use".

A derived member SHALL be stored as an **empty collection rather than null** when it has no entries, so
that a partial declaration has one representation in storage and in memory.

**The deferral is safe because a disabled pipeline runs nothing.** ADAS's scheduler selects on
`(enabled, next_run_at)`, and the Enrichment Runner's sync feed asks for `enabled=true`, so no
incomplete declaration is reached by either. What the gate protects is the moment the pipeline begins to
produce rows, and that moment has its own enforcement, unchanged.

**The gate's content SHALL NOT change.** Every check applied at registration today SHALL be applied at
arming, over the stored declaration, with the same status and the same error code.

#### Scenario: An incomplete declaration is stored while disabled

- **WHEN** `POST /v1/pipelines` registers a pipeline with `name`, `kind` and `target` and no other
  declaration member
- **THEN** the response is 201, the pipeline is stored `enabled: false`, and no kind gate has run

#### Scenario: A complete but invalid declaration is stored when the write does not arm

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with a complete declaration that
  violates a governance rule of its kind
- **THEN** the response is 201 and the declaration is stored, because an `aggregate` registration lands
  disabled and the write therefore arms nothing; the refusal is raised by `POST …/preview` or by the
  patch that arms it

#### Scenario: Arming runs the gate the registration did not

- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true` on a pipeline whose stored declaration
  does not satisfy its kind's gate
- **THEN** the request is rejected, the pipeline stays disabled, and the stored declaration is unchanged

#### Scenario: A declaration write never erases a derived member

- **GIVEN** an `enrich` pipeline whose stored `output_bindings` were derived by an earlier gated write
- **WHEN** a declaration patch is applied while the pipeline is disabled and the new declaration's
  outputs cannot be re-derived
- **THEN** the previously stored `output_bindings` are retained rather than replaced with an empty
  mapping, and the catalog's drop/rename guard continues to see the pipeline as a writer of those
  columns

#### Scenario: A derivable member is re-derived on an ungated write

- **WHEN** a declaration patch of a disabled `aggregate` pipeline omits `group_by` and the target's
  `ordering_key` columns are all plain input columns of the same name
- **THEN** the group keys are derived and stored, exactly as they would be on a gated write

#### Scenario: A disabled pipeline with an incomplete declaration produces no rows

- **WHEN** a pipeline's stored declaration is incomplete and its trigger's moment arrives
- **THEN** ADAS's scheduler does not select it, the runner's `enabled=true` sync feed does not carry
  it, and nothing is written to its target

### Requirement: A declaration patch keeps the pipeline's stored target unless it names another

A declaration patch that omits `target` SHALL keep the stored value, and one that supplies a different
value SHALL repoint the pipeline. Every other declaration member keeps replace semantics: what the
patch supplies becomes the declaration, and what it omits is cleared.

`target` is exempt because it is the one declaration member that can never legitimately be absent. It
is required at registration and the pipeline is meaningless without it, so "omitted" cannot mean
"cleared" the way it does for `filter` or a stored response schema — where omission is the documented
way to remove a stored value, and where treating absence as "unchanged" would turn a clearing request
into a success that changed nothing. Exempting `target` therefore takes nothing away from replace
semantics; it puts `target` on the footing `name` and `kind` already have, which a patch may either
restate or omit with the same result.

Without the exemption the authoring loop would break on the author's **second** request: the target
availability check runs before any other, so a patch adding only measures would be refused for a
member the author never intended to change.

A `target` that is blank or whitespace-only SHALL be treated as **omitted** — the stored value is kept,
and the member does not by itself make the body a declaration patch — and a `target` carrying
surrounding whitespace SHALL be trimmed before it is compared with the stored value or resolved.

#### Scenario: A declaration patch omitting the target keeps it

- **WHEN** `PATCH /v1/pipelines/{name}` supplies declaration members but no `target`
- **THEN** the stored `target` is retained and the patch is applied to the rest of the declaration

#### Scenario: A blank target on a patch is treated as omitted

- **WHEN** `PATCH /v1/pipelines/{name}` carries `target: ""` or a whitespace-only `target`
- **THEN** the stored `target` is retained, and a body carrying nothing else of the declaration is not
  treated as a declaration patch

#### Scenario: A padded target is trimmed

- **WHEN** `PATCH /v1/pipelines/{name}` carries the stored `target` wrapped in whitespace
- **THEN** it is compared and resolved as the trimmed name, so it restates the stored target rather
  than repointing to a table that does not exist

#### Scenario: A declaration patch naming a different target repoints the pipeline

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a `target` other than the stored one
- **THEN** the pipeline is repointed, subject to the one-pipeline-per-target rule

## MODIFIED Requirements

### Requirement: Pipeline registry object

The system SHALL maintain a Postgres-backed registry of **pipelines** — declared row-producing
processes, each writing exactly one **target** table. A pipeline SHALL carry a `name` (the registry
key and the `{name}` in the API path), a `kind`, the `target` table it writes, and an `enabled` flag;
and it MAY carry, as its declaration is authored, its `inputs`, a kind-specific declaration and a
`trigger`. A pipeline is **data, not code**: registering one deploys nothing, and the platform's
generic executor runs it.

`name`, `kind` and `target` SHALL be required at registration, and they are the whole of what
registration requires. The three are not a compromise between strictness and convenience: `name` and
`kind` are the pipeline's identity, and `target` is the object every later authoring step is expressed
against — an `aggregate` pipeline's group keys are derived from the target's `ordering_key` and its
measures are checked against the target's columns, and an `enrich` pipeline's authored outputs are
**keyed by** target column name. A pipeline with no target could be stored but not meaningfully
continued. Keeping `target` required also keeps its `UNIQUE` constraint, its foreign key and its
`ON DELETE CASCADE` meaningful, so a pipeline cannot outlive the table it writes.

`kind` SHALL be a closed set. `aggregate` and `enrich` SHALL be implemented; the `kind` SHALL select
both the declaration's vocabulary and **where the pipeline runs** — an `aggregate` pipeline is
refreshed by ADAS on its own cron, an `enrich` pipeline is driven by the Enrichment Runner through
the pipeline-scoped data plane, except for its Class-1 case which ADAS materializes in-database. A
request naming an unimplemented kind SHALL be rejected with HTTP 422 naming the implemented kinds.
The unimplemented members SHALL therefore be part of the accepted vocabulary rather than omitted
from it — an omitted member is a deserialization failure, answered with a generic HTTP 400 that
names neither the offending value nor the alternatives, which is not the contract above. Only an
implemented kind is ever persisted.

`kind` SHALL be immutable: a different transform over the same target is a different pipeline, and a
patch that changes it SHALL be rejected.

**One pipeline per target**: a target table SHALL have at most one pipeline **of any kind**,
enforced by a database `UNIQUE (target)` constraint, and registering a second pipeline for the same
target SHALL be rejected with HTTP 409. This SHALL hold across kinds rather than by disjointness of
target shapes. The reasons are per-kind and both stand: a rollup's target replaces rows by key, and
an enrichment's target is a `ReplacingMergeTree` with full-row replace by grain, so two pipelines
writing the same target would clobber each other's columns. Multi-label output is expressed as one
declaration emitting multiple output columns, not as a second pipeline.

`inputs` SHALL name at most one input table (multi-source input is post-v1). An `aggregate` pipeline
SHALL name its input **by the time it is armed**; an unarmed one MAY omit it while its declaration is
being authored. An `enrich` pipeline MAY omit it at any time, which SHALL mean "read the target
enrichment's own parent source"; the **compiled** projection SHALL echo the resolved input so a caller
can see what was chosen, and the **authored** projection SHALL echo the stored value — absent when the
pipeline declared none — so that a read fed back into a patch does not promote an inherited input into
a declared one. The input SHALL NOT be the pipeline's own target — a
pipeline that reads what it writes SHALL be rejected with HTTP 422.

Pipeline registration, mutation, and deletion SHALL require the `FULL_ADMIN` authority in `oidc`
mode; per-table access roles SHALL NOT authorize them. **Reading** a pipeline SHALL require any
mapped application role — `FULL_ADMIN` or `READ_ONLY_ADMIN` — and SHALL NOT require `FULL_ADMIN`.
The declaration is the answer to "what populates this table, and is it running", which is the
question the read-only catalog role exists to answer; withholding it while the table catalog and
schema discovery are open to that role made the registry read the
only closed catalog read, without closing anything the role could not already reach. A read
therefore exposes the declaration in full — `filter`, `expression`, measures, bindings, the `enrich`
kind's `transform` including its prompt, and the **names** of `sensitive` columns the declaration
references. What a read SHALL NOT expose is any `sensitive` column **value**; see **A pipeline read
carries no row values**.

Folding the transform onto the pipeline SHALL NOT change what this role can see, and the reason SHALL
be restated rather than inherited. Before the fold the justification was partly that the evaluator
registry was open to the same role, so the prompt was reachable anyway; that argument expires with the
registry. It is replaced by the stronger one it was always standing next to: the prompt **is** the
declaration — the answer to "what populates this table" — and the pipeline read is the only place that
question is answered. The prompt therefore appears in **both** projections, the authored one included,
where before the fold the default projection carried only the evaluator's name.

**A read of an historical revision** (`GET /v1/pipelines/{name}/revision/{revision}`) SHALL be
understood to return the stored declaration **re-serialized in the current shape**, not the bytes
that were stored. A member that has since stopped being part of a declaration is therefore silently
absent from a revision read, even though the revision recorded it. This is a stated limitation rather
than a behaviour to rely on; returning the raw stored JSON for a revision read is the fix and is not
part of this change. The fold makes one instance of it concrete and worth naming: a revision recorded
**before** the fold carries `evaluator_name` and `evaluator_version` in `pipeline_aud`, and the current
shape has no such members, so a revision read of it answers with no transform and no evaluator
reference at all. Which archived version that revision resolved to is answerable from the retained
mirror columns and from the fold migration's log, not from this endpoint. It is also why the
persistence codec's tolerance of unknown stored members is **permanent**: stored declarations are
deliberately not rewritten when the request vocabulary changes.

#### Scenario: Register a pipeline

- **WHEN** `POST /v1/pipelines` is called with a valid declaration of an implemented kind
- **THEN** the response is 201 with the stored pipeline, `enabled: false`

#### Scenario: Register a pipeline with identity and target alone

- **WHEN** `POST /v1/pipelines` is called with only `name`, `kind` and `target`
- **THEN** the response is 201 with the stored pipeline, `enabled: false`, and the declaration members
  the caller did not supply are absent from the read rather than served as empty — `trigger` included,
  which is absent rather than `{}` — with one exception: an `aggregate` pipeline's `trigger.kind`
  defaults to `schedule` and is served as such

#### Scenario: A registration with no target is rejected

- **WHEN** `POST /v1/pipelines` is called with `name` and `kind` and no `target`
- **THEN** the request is rejected and nothing is persisted

#### Scenario: A second pipeline for the same target is rejected

- **WHEN** `POST /v1/pipelines` names a `target` that already has a pipeline
- **THEN** the request is rejected with HTTP 409 and nothing is persisted

#### Scenario: The one-writer rule holds across kinds

- **WHEN** `POST /v1/pipelines` registers an `enrich` pipeline whose `target` is already written by
  an `aggregate` pipeline, or the reverse
- **THEN** the request is rejected with HTTP 409 by the same constraint, not admitted because the two
  kinds were once separate registries

#### Scenario: An unimplemented kind is rejected

- **WHEN** `POST /v1/pipelines` is called with a `kind` that is part of the vocabulary but not
  implemented
- **THEN** the request is rejected with HTTP 422 naming the implemented kinds

#### Scenario: A pipeline reading its own target is rejected

- **WHEN** `POST /v1/pipelines` names the same table in `inputs` and `target`
- **THEN** the request is rejected with HTTP 422 (a cycle)

#### Scenario: An enrich pipeline may omit its input

- **WHEN** `POST /v1/pipelines` registers an `enrich` pipeline with no `inputs`
- **THEN** the pipeline is accepted and reads its target enrichment's parent source, and the compiled
  projection echoes that resolved input while the authored projection omits `inputs`

#### Scenario: A revision read is re-serialized in the current shape

- **WHEN** `GET /v1/pipelines/{name}/revision/{revision}` reads a revision stored before a member
  stopped being part of the declaration vocabulary
- **THEN** the response carries the declaration in the current shape, with that member absent, and the
  read succeeds rather than failing on the unknown stored member

#### Scenario: A pre-fold revision read carries no transform and no evaluator reference

- **WHEN** `GET /v1/pipelines/{name}/revision/{revision}` reads an `enrich` pipeline's revision
  recorded before the fold
- **THEN** the response carries the declaration in the current shape, with no `transform` and no
  `evaluator_name`, and the read succeeds

#### Scenario: An aggregate pipeline may omit its input while unarmed

- **GIVEN** `POST /v1/pipelines` registers an `aggregate` pipeline with no `inputs`
- **THEN** the pipeline is created disabled
- **AND WHEN** it is later armed with a declaration otherwise complete — a `trigger.cron` included —
  but still no `inputs`
- **THEN** arming is refused with HTTP 422 naming the absent `inputs`; without a cron it is refused
  first for `trigger.cron`, since the defaulted `schedule` trigger is checked before the inputs

#### Scenario: The kind cannot be changed

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a different `kind`
- **THEN** the request is rejected with HTTP 422 and the stored kind is unchanged

#### Scenario: A patch may restate the identity it read, but not change it

- **WHEN** `PATCH /v1/pipelines/{name}` carries a `name` and `kind` equal to the pipeline's own — the shape
  a full-body or read-modify-write caller sends
- **THEN** the request is accepted and neither member has any effect

#### Scenario: A patch naming another pipeline is refused, not applied to the path's

- **WHEN** `PATCH /v1/pipelines/{name}` carries a `name` other than the one in the path, whether or not it
  also carries a declaration
- **THEN** the request is rejected with HTTP 422 and the pipeline the path names is left untouched

`name` and `kind` are therefore part of the patch vocabulary while remaining unpatchable, and the check
SHALL precede the enable-only short-circuit: neither member is a declaration, so a body carrying only a
foreign `name` and an `enabled` flag would otherwise flip the pipeline in the URL rather than being
refused — the one failure of this kind that acts on a pipeline the caller did not mean to touch.

#### Scenario: Pipeline management requires admin

- **WHEN** a non-`FULL_ADMIN` caller invokes a mutating `/v1/pipelines` registry endpoint in `oidc`
  mode — register, patch, or delete
- **THEN** the request is rejected with HTTP 403

#### Scenario: A read-only admin may read the registry

- **WHEN** a caller holding only `READ_ONLY_ADMIN` calls `GET /v1/pipelines`,
  `GET /v1/pipelines/{name}`, or `GET /v1/pipelines/{name}/revision/{revision}` in `oidc` mode
- **THEN** each request succeeds with the same body a `FULL_ADMIN` caller receives, field for field

#### Scenario: A read-only admin sees the declaration, prompt included

- **WHEN** a caller holding only `READ_ONLY_ADMIN` reads an `enrich` pipeline whose transform
  carries a prompt template, and whose `filter` references a column marked `sensitive`
- **THEN** the response carries the transform's prompt template and the `filter` text naming that
  column, because both are declaration rather than row content

#### Scenario: A caller with no application role still cannot read the registry

- **WHEN** a caller that maps to neither `FULL_ADMIN` nor `READ_ONLY_ADMIN` — including one holding
  only a per-table access role — calls any `GET /v1/pipelines` endpoint in `oidc` mode
- **THEN** the request is rejected with HTTP 403; per-table access roles SHALL NOT authorize a
  registry read any more than they authorize a registry write

#### Scenario: Operator actions on a pipeline still require full admin

- **WHEN** a caller holding only `READ_ONLY_ADMIN` calls `POST /v1/pipelines/{name}/preview` or
  `POST /v1/pipelines/{name}/refresh`
- **THEN** the request is rejected with HTTP 403, because each executes a query against the input or
  writes rows to the target rather than reporting registered state

#### Scenario: Widening the registry read does not widen the data plane

- **WHEN** a caller holding only `READ_ONLY_ADMIN`, and no `WRITE` grant on the pipeline's target,
  calls `POST /v1/pipelines/{name}/scan`, `/group-rows`, `/materialize`, or `/rows`
- **THEN** the request is rejected exactly as before this change, by the pipeline's own per-target
  grant check; a global read-only role SHALL NOT authorize any pipeline-scoped data-plane call

### Requirement: Register and enable lifecycle

A pipeline SHALL be registered `enabled: false` and SHALL NOT run until explicitly enabled through
`PATCH /v1/pipelines/{name}`. A declaration edit SHALL be re-validated in full **when it leaves the
pipeline armed**, and SHALL then be supplied whole: a partial declaration patch of a pipeline that
the patch leaves armed SHALL be rejected rather than merged, since a declaration that is about to
produce rows is validated as one object and a half-applied one has no meaning.

**While the patch leaves the pipeline unarmed, completeness SHALL NOT be required.** `PATCH` SHALL
keep **replace** semantics at all times: the declaration members a patch supplies become the stored
declaration, and the members it omits are cleared. Replace rather than merge is deliberate and the
distinction is not academic — omission is the documented way to clear a live member, and a stored
response schema is switched over to a composed one precisely by omitting it on a later patch. Under
merge semantics that request would answer 200 having changed nothing, which is the failure this
registry's strictness exists to prevent. Replace also preserves the reason partial patches were
refused in the first place: every storable state remains one a caller described whole, so the rule is
narrowed by state rather than overturned.

**A declaration patch that leaves the pipeline disabled SHALL NOT turn a complete declaration into an
incomplete one.** Where the stored declaration names every member its kind authors — for `enrich` a
typed `transform` with outputs and a `trigger.kind` (with its `cron` for `schedule`, its `group_by` for
`group`); for `aggregate` its `inputs`, at least one measure and a `trigger.cron` — a patch whose
declaration would lack any of them SHALL be refused with HTTP 422 `pipeline_validation_failed` naming
the members it would drop, and SHALL change nothing. Nothing stored tells a pipeline that ran and was
paused apart from a draft, so under replace semantics a patch carrying one runtime knob would otherwise
erase a paused pipeline's transform, trigger and inputs while answering 200. Derived members (the output
mapping, `group_by`) are not asked, since a draft may lack them for reasons no patch caused. A
declaration that is still incomplete MAY be saved with any subset, and one that is complete MAY be
replaced whole; starting a complete declaration over is a delete and re-register.

**What counts as a declaration patch SHALL cover every declaration member**, including members that
are optional at registration. A patch carrying only such a member SHALL be treated as a declaration
patch — re-validated where the patch arms, stored and bumped — and SHALL NOT be mistaken for an
enable-only patch, which would discard it while answering success. Conversely a member that is
**optional at registration** SHALL NOT be required by a declaration patch that leaves the pipeline
unarmed: requiring it would make a pipeline that never declared it unpatchable, asking for a field the
registration said was optional.

**A body that restates only the stored `target` SHALL NOT be a declaration patch.** A patch carrying
`target` equal to the stored value and nothing else of the declaration — optionally beside the
identity echo of `name` and `kind`, or an `enabled` flag — SHALL change no declaration member, rather
than being read as a declaration consisting of a target alone and clearing everything else. The
restated target is what a read-modify-write caller echoes, and under replace semantics reading it as a
declaration would erase the draft the caller meant to leave alone. Such a body carrying `enabled: true`
is therefore an **enable-only** patch: the gate runs over the stored declaration and nothing is
cleared.

**An `enrich` pipeline that declares its own read source SHALL have it restated by every declaration
patch**, armed or not: a declaration patch that omits `inputs` on such a pipeline SHALL be refused with
HTTP 422 `pipeline_validation_failed` rather than silently repointing it to the target enrichment's
parent source, which is what an omitted input means for this kind. The omission is a repoint the caller
never wrote, not an unfinished member, so it is refused on a draft save as much as on an arming write.
A patch carrying `inputs: []` SHALL explicitly drop the declared read source.

**A derived member SHALL be carried over from the stored row only while it describes the same
tables.** Where a declaration write that leaves the pipeline disabled cannot re-derive a derived member
— an `aggregate` pipeline's `group_by`, an `enrich` pipeline's `output_bindings` — it keeps the stored
value only when the write leaves both the `target` and the read source unchanged from the stored row. A
write that repoints either SHALL store an empty member instead: the stored one names the previous
target's keys or columns, and carried across a repoint it would protect, or write, columns of a table
the pipeline no longer targets.

**A registration that declares `enabled: true` for a kind whose disabled state is its dry-run gate
SHALL be rejected** with HTTP 422 naming the preview endpoint, rather than silently coerced to
`false`. A field the caller set and the server discarded is precisely the silent mis-answer the
registry's strictness exists to prevent. `enabled: false` and an absent `enabled` SHALL both stay
legal for such a kind — an honest statement is not a mistake. The rejection SHALL apply to
**registration only**: the patch path is the only way such a pipeline is ever armed, and it
re-validates first, so extending the rejection to `PATCH` would make the kind unarmable.

**`enabled` SHALL NOT be required at registration for any kind**, and its absence SHALL mean `false`.
The earlier rule requiring an `enrich` registration to state it existed so that a declaration would not
start dormant by accident, leaving "a pipeline that looks registered and never runs". Registration now
*means* that: it is the act of beginning to author a declaration, and dormancy is the expected initial
state rather than an accident. An `enrich` registration that states `enabled: true` SHALL remain legal
and SHALL be gated, because the write arms the pipeline.

A `disabled` pipeline SHALL NOT write to its target by any route — neither on a schedule, nor
through an operator-triggered refresh, nor through the pipeline-scoped data plane. Were any route to
write anyway, `enabled` would describe only the schedule while the gate it exists to enforce stayed
open, and a target could be filled from a declaration nobody had looked at.

**The pipeline-scoped write paths SHALL enforce this.** `POST …/rows` and `POST …/materialize` SHALL
refuse a disabled pipeline with HTTP 409 `pipeline_conflict` before reading or writing anything, while
the two **read** paths, `…/scan` and `…/group-rows`, stay open to it; the rationale and the accepted cost
are stated in [enrichment-runtime](../enrichment-runtime/spec.md), "A disabled pipeline's write paths
refuse".

**Whether a declaration may be edited while the pipeline is enabled SHALL depend on the kind**, and
the reason is the target's write semantics rather than operator convenience:

- For a kind whose target holds **windowed** results replaced by key — `aggregate` — editing an
  enabled pipeline's declaration SHALL be rejected with HTTP 409. It is a state conflict, like
  refreshing a disabled pipeline, and not a 422: the declaration sent may be perfectly valid and only
  the timing wrong, and reporting it as a validation failure would tell the client to fix a spec that
  has nothing wrong with it. What the 409 protects against is a target holding rows computed two
  different ways with nothing recording which row came from which.
- For a kind whose target is **grain-keyed with full-row replace** — `enrich` — a declaration edit on
  an enabled pipeline SHALL be permitted. After such an edit new rows compute the new way and rows
  already written keep their previous values, which is already how that kind behaves and is why it
  carries a re-derivation marker and a backfill lane of its own. The invariant above is not weakened
  for this kind; it was never this kind's invariant. Requiring a disable/enable cycle for a routine
  runtime edit would instead cost real work: the out-of-process runner drops a disabled pipeline from
  its cache, and its dead-letter items lose their stored labels while it is disabled.

A declaration change SHALL bump the pipeline's change token (`generation`), which is what an
out-of-process consumer detects a re-declaration by.

A declaration change SHALL reset **only the execution state ADAS itself owns for that kind**. For a
kind whose cursor and rebuild anchor are ADAS-owned, the cursor SHALL be reset so the next run reads
its input from the beginning and recomputes every key under the new declaration; keeping it would
confine the new declaration to windows not yet read, leaving every key materialized before the edit
holding values the *previous* declaration produced — indefinitely, and with nothing in the target
recording it. For a kind whose cursor is held by the out-of-process runner, the reset SHALL be a
no-op and SHALL NOT stamp a rebuild anchor that does not apply to it. Rows whose key the new
declaration no longer produces are NOT removed: recomputation can only replace keys it derives, so
rebuilding or replacing a re-grained target remains an operator action.

`DELETE /v1/pipelines/{name}` SHALL remove the pipeline and leave the target table and its rows
intact: a target that stops being fed is stale, not invalid.

#### Scenario: A registered pipeline does not run until enabled

- **WHEN** a pipeline is registered and its trigger fires
- **THEN** no statement is executed against the target while `enabled` is false

#### Scenario: The write paths refuse a disabled pipeline

- **WHEN** `POST /v1/pipelines/{name}/rows` or `POST /v1/pipelines/{name}/materialize` is called for a
  pipeline that is disabled, whether its declaration is complete or a draft
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict` and nothing is written

#### Scenario: The read paths still serve a disabled pipeline

- **WHEN** `POST /v1/pipelines/{name}/scan` or `POST /v1/pipelines/{name}/group-rows` is called for a
  disabled pipeline whose declaration is complete
- **THEN** the read proceeds, because it writes nothing and a disabled pipeline's preview samples through it

#### Scenario: A declaration edit is fully re-validated when it arms

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration that violates a governance rule of its
  kind, and the patch leaves the pipeline enabled
- **THEN** the request is rejected with HTTP 422 and the stored declaration is unchanged

#### Scenario: A declaration edit of a disabled pipeline is stored ungated

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration that would violate a governance rule of
  its kind, and the patch leaves the pipeline disabled
- **THEN** the declaration is stored, the change token is bumped, and no governance refusal is raised
  until the pipeline is armed or previewed

#### Scenario: Editing an enabled aggregate pipeline's declaration is a conflict

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration for an `aggregate` pipeline that is
  `enabled`
- **THEN** the request is rejected with HTTP 409 and the stored declaration is unchanged

#### Scenario: Editing an enabled enrich pipeline's declaration is permitted

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration for an `enrich` pipeline that is
  `enabled`
- **THEN** the declaration is re-validated, stored, and the change token is bumped, and the
  pipeline's rebuild anchor is left untouched

#### Scenario: A partial declaration patch that arms the pipeline is refused

- **WHEN** `PATCH /v1/pipelines/{name}` supplies some but not all of a declaration's required members
  and leaves the pipeline enabled
- **THEN** the request is rejected and the stored declaration is unchanged

#### Scenario: A partial declaration patch of a disabled pipeline is stored

- **GIVEN** a disabled pipeline whose stored declaration is itself incomplete
- **WHEN** `PATCH /v1/pipelines/{name}` supplies some but not all of a declaration's required members
  and leaves the pipeline disabled
- **THEN** the supplied members become the stored declaration, the members the patch omitted are
  cleared, and the change token is bumped

#### Scenario: A partial patch of a paused pipeline is refused rather than erasing it

- **GIVEN** an `enrich` pipeline that ran and was then disabled, its declaration complete
- **WHEN** `PATCH /v1/pipelines/{name}` carries `{"advanced": {"rate_rpm": 5}}` and nothing else of the
  declaration
- **THEN** the response is HTTP 422 `pipeline_validation_failed` naming the members the patch would
  drop (`transform.type, transform.outputs, trigger.kind`)
- **AND** the stored declaration and `generation` are unchanged

#### Scenario: A complete disabled declaration may be replaced whole

- **GIVEN** a disabled pipeline whose declaration is complete
- **WHEN** a patch that leaves it disabled supplies a different declaration that is itself complete
- **THEN** the patch is accepted and stored ungated

#### Scenario: Omitting a member on a patch still clears it

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration that omits a member the stored
  declaration carries
- **THEN** the stored member is cleared, whether or not the patch arms the pipeline, because the patch
  replaces the declaration rather than merging into it

#### Scenario: A patch carrying only an optional declaration member is a declaration patch

- **WHEN** `PATCH /v1/pipelines/{name}` carries only a declaration member that is optional at
  registration
- **THEN** the declaration is stored and the change token is bumped, rather than the body being read
  as an enable-only patch and the member discarded

#### Scenario: A body restating only the stored target changes nothing

- **WHEN** `PATCH /v1/pipelines/{name}` on a disabled draft carries only `target` equal to the stored
  one, optionally with the pipeline's own `name` and `kind`
- **THEN** the request is accepted, no declaration member is cleared, and the change token is not bumped

#### Scenario: A restated target beside enabled true is an enable-only patch

- **GIVEN** a disabled pipeline whose stored declaration is complete
- **WHEN** `PATCH /v1/pipelines/{name}` carries `{target: <the stored target>, enabled: true}`
- **THEN** the patch is treated as enable-only: the gate runs over the stored declaration, no
  declaration member is cleared, and the pipeline is armed if the gate passes

#### Scenario: Omitting a declared read source is refused on a draft save too

- **GIVEN** an `enrich` pipeline whose stored declaration declares its own `inputs`
- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration without `inputs`, whether or not the
  patch leaves the pipeline enabled
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` and the stored declaration
  is unchanged
- **AND WHEN** the patch instead carries `inputs: []`
- **THEN** the declared read source is dropped and the pipeline reads its target enrichment's parent
  source

#### Scenario: A draft save carries a derived member over only for the same tables

- **GIVEN** a disabled pipeline whose stored `group_by` or `output_bindings` were derived against its
  current target and read source
- **WHEN** a declaration patch that cannot re-derive that member leaves both target and read source
  unchanged
- **THEN** the stored derived member is kept
- **AND WHEN** such a patch instead repoints the target or the read source
- **THEN** the derived member is stored empty rather than carried over from the previous tables

#### Scenario: A registration declaring enabled true on a dry-run-gated kind is refused

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with `enabled: true`
- **THEN** the request is rejected with HTTP 422 naming `POST /v1/pipelines/{name}/preview`, and
  nothing is persisted

#### Scenario: A registration declaring enabled false is accepted

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with `enabled: false`, or with
  `enabled` absent
- **THEN** the pipeline is created disabled

#### Scenario: An enrich registration omitting enabled is accepted as disabled

- **WHEN** `POST /v1/pipelines` registers an `enrich` pipeline with no `enabled` member
- **THEN** the pipeline is created disabled, rather than the request being refused for not stating it

#### Scenario: An enrich registration declaring enabled true is gated

- **WHEN** `POST /v1/pipelines` registers an `enrich` pipeline with `enabled: true` and a declaration
  that does not satisfy its kind's gate
- **THEN** the request is rejected and nothing is persisted, because the write would have armed the
  pipeline

#### Scenario: A patch enabling a dry-run-gated kind is accepted

- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true` on a registered `aggregate` pipeline
- **THEN** the declaration is re-validated and the pipeline is armed

#### Scenario: Deleting a pipeline keeps the target

- **WHEN** `DELETE /v1/pipelines/{name}` is called
- **THEN** the pipeline is removed, and the target table and its rows still exist and remain
  queryable

### Requirement: Pipeline runtime state is readable

A pipeline SHALL surface its runtime execution state read-only on `GET /v1/pipelines/{name}`,
including a staleness measure, so an operator can tell a fresh target from a stalled one without
reading logs. State SHALL be reported under one projection for every kind, with the members that do
not apply to a kind omitted rather than reported empty — an omitted member says "this kind has no
such position", where a zero would read as a real one.

State SHALL be server-owned: no endpoint SHALL let a caller set a cursor, since a caller-chosen
position could skip input rows and silently produce wrong results.

Where a kind's schedule is ADAS-driven, the state SHALL carry the scan cursor, the last and next run
times, and the last failure, persisted in the registry (not ClickHouse), because no caller holds the
position. Where a kind's cursor is held by the out-of-process runner, those members SHALL be absent
and the runner SHALL remain their owner; the registry SHALL NOT report a position it does not know.

Where a read window was clamped short of its input's available cursor to keep a referenced
enrichment's join sound, the state SHALL say so, identifying the enrichment that bounded it and the
position it bounded the window to. A clamped pipeline is behind its input for a reason an operator
must be able to see: without it, a pipeline permanently held back by a lagging enrichment is
indistinguishable from a healthy one that simply had less input to read, and the failure this
introduces is a quiet one.

Where a pipeline reads an enrichment that imposes no clamp because ADAS cannot establish a position
on the axis the read windows on, the state SHALL name that enrichment and the reason. This indicator
is what makes admitting such a column different from ignoring the problem: the pipeline is exposed to
the freshness gap for that one enrichment, and an operator has to be able to see which one and why
without reading the declaration against the registry.

Where an enrichment a pipeline reads has had its history re-derived since the pipeline last read it,
the state SHALL report that the pipeline's output needs rebuilding, naming the enrichment. This is
not a clamp and no window is held back: the output is already wrong and no ordinary run will correct
it, so the signal is an instruction to the operator rather than a description of progress.

A failure SHALL be recorded against the pipeline **whichever route triggered it** — a trigger firing
or an operator-triggered run. A failure that only returned an HTTP error would leave the state view
reporting the *previous* run's health, so the endpoint an operator consults would disagree with what
actually happened. Recording the failure SHALL NOT mask the failure being reported: if the state
write itself fails, the original cause is still what surfaces. A recorded message SHALL be non-empty,
since a blank one is stored as "no error" and reads as a success.

#### Scenario: State is reported after a run

- **WHEN** `GET /v1/pipelines/{name}` is called after a successful ADAS-driven refresh
- **THEN** the response carries the advanced cursor, the last run time, the next run time, and a
  staleness measure

#### Scenario: A kind whose cursor the runner owns reports no cursor

- **WHEN** `GET /v1/pipelines/{name}` is called for an `enrich` pipeline
- **THEN** the ADAS-owned cursor and schedule members are absent from `state` rather than reported as
  zero or epoch values

#### Scenario: A failure is visible without reading logs

- **WHEN** a run fails — on a trigger or on an operator-triggered route — and
  `GET /v1/pipelines/{name}` is called
- **THEN** the response reports the failure alongside the unadvanced cursor, identically for both
  routes

#### Scenario: Cursors are not caller-settable

- **WHEN** a caller attempts to set a pipeline's cursor through the API
- **THEN** the attempt is rejected

#### Scenario: A clamped window is visible in state

- **WHEN** a read window is clamped to a referenced enrichment's materialized position rather than to
  its input's available cursor
- **THEN** `GET /v1/pipelines/{name}` reports that the window was clamped, names the enrichment that
  bounded it, and reports the cursor the read did reach

#### Scenario: An unclamped read reports no clamp

- **WHEN** a read window ends at or before every referenced enrichment's materialized position, or
  the pipeline references no enrichment at all
- **THEN** the state reports no clamp, so the indicator means something when it is present

#### Scenario: An enrichment that cannot be clamped against is named

- **WHEN** a pipeline reads an enrichment for which no position on the read's own axis can be
  established
- **THEN** `GET /v1/pipelines/{name}` names that enrichment as read-but-unclamped, with the reason,
  whether or not any other enrichment clamped the window

#### Scenario: Output needing a rebuild says so

- **WHEN** an enrichment a pipeline reads has had its history re-derived since the pipeline last read
  it
- **THEN** `GET /v1/pipelines/{name}` reports that the output requires a rebuild and names that
  enrichment

#### Scenario: Every unclamped-read reason is part of the published vocabulary

- **WHEN** `GET /v1/pipelines/{name}` reports an enrichment as read-but-unclamped
- **THEN** the reason is one of the published set — nothing writes the enrichment (`no_pipeline`),
  data-sync writes it beside its source and flushes it first (`ingest_written`), the writing
  pipeline is group-triggered, the writing pipeline is not in-database derived, the writing pipeline
  is a disabled draft whose declaration is incomplete (`undeclared_writer`), or the read windows on an
  axis foreign to the enrichment — and the published set names all six regardless of the reading
  pipeline's kind

#### Scenario: A draft writer is reported as undeclared, not as a writer ADAS does not execute

- **WHEN** a pipeline reads an enrichment whose only writer is a disabled pipeline whose declaration is
  incomplete — any required member absent, not only the transform (see
  [aggregate-pipeline](../aggregate-pipeline/spec.md) for the trigger-kind cases)
- **THEN** the enrichment is reported read-but-unclamped with the reason `undeclared_writer`, and the
  reading pipeline is neither refused nor told that its upstream is not in-database derived — a draft
  writes nothing, so its absent values are final until it is armed, and arming it runs its own gate

## ADDED Requirements

### Requirement: An undeclared declaration member is absent from the authored projection

The authored projection SHALL distinguish a member the pipeline **does not declare** from one it
declares as empty: an undeclared member SHALL be **absent** from the response rather than served as an
empty array.

This SHALL apply to `group_by` and `measures` as it already applies to `inputs`. The reason is
round-tripping: the authoring loop is read, edit, send whole, and `PATCH` replaces rather than merges,
so a member the read invents as `[]` is a member the next patch stores as "declares none". While every
stored declaration was complete this could not arise; once a declaration may be authored over several
patches it is the common case.

An empty collection and an undeclared member SHALL be treated as the same thing for this purpose,
because they are: a valid declaration never carries zero measures or zero group keys, so an empty one
can only mean the member has not been authored yet.

`output_bindings` SHALL be excluded from this rule. It is derived rather than authored and is served on
the **compiled** projection only, so it round-trips nowhere; changing it would alter the runner-facing
body for no authoring benefit.

A **compiled** read of a declaration that cannot be compiled SHALL be refused rather than served as a
compiled projection with empty members, and the refusal SHALL follow the same discriminator as every
other: a declaration missing required members SHALL be HTTP 422 `pipeline_validation_failed`
naming **every** absent member in one message, and a complete `enrich` declaration whose outputs
compose to no response schema SHALL be HTTP 422 `pipeline_validation_failed` pointing at the preview
that reports why. The compiled read SHALL NOT run the declaration gate: it does not statically validate
`sql` expressions or any other present member, so a complete but otherwise defective declaration is
served — its defects are the gate's to report, on arming or on preview.

A **listing** SHALL NOT be refused because one of its entries cannot be compiled: an incomplete entry
SHALL be omitted from a compiled listing, and SHALL NOT be logged as an error while the pipeline is
disabled, because incomplete-and-disabled is the expected state of a declaration being authored rather
than a fault. Only a named single-pipeline read refuses.

#### Scenario: An undeclared member is absent from the authored projection

- **WHEN** a pipeline whose declaration carries no measures is read with the authored projection
- **THEN** the response omits `measures` entirely rather than carrying `measures: []`

#### Scenario: An undeclared trigger is absent rather than an empty object

- **WHEN** a draft whose declaration carries no trigger member at all is read with the authored
  projection (`view=source`)
- **THEN** the response omits `trigger` entirely rather than carrying `trigger: {}`, so a read fed back
  into a patch does not declare an empty trigger

#### Scenario: A read fed back into a patch does not declare emptiness

- **WHEN** a caller reads a pipeline with the authored projection and sends the body back as a patch
- **THEN** no member the pipeline had not declared is stored as an empty declaration

#### Scenario: A compiled read of an incomplete declaration is refused

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` names a pipeline whose declaration is incomplete
- **THEN** the request is refused with HTTP 422 `pipeline_validation_failed` naming every absent member
  in one message
