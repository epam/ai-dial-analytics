## ADDED Requirements

### Requirement: A pipeline has exactly one run control

A pipeline's `enabled` flag SHALL be its **single run control**, for every kind: `true` means the
pipeline is active and `false` means it is stopped. It is stored in the registry, changed only through
`PATCH /v1/pipelines/{name}` (or set at registration where the kind allows it), and SHALL be the one
place any caller — an API client, an admin tool, a UI — reads or changes whether a pipeline runs. No
other component SHALL hold a caller-set stop for a pipeline: the out-of-process runner follows `enabled`
through the enrich feed (see [enrichment-registry](../enrichment-registry/spec.md), "The enrich feed
carries the transform, a resolved schema and a change token") and offers no pause of its own.

Two independent switches — a registry flag and a pause held by the runner — let one pipeline read as
running in one place and stopped in the other, and neither switch could explain the other's state. One
durable flag owned by the registry removes that ambiguity: what `GET /v1/pipelines/{name}` reports is
what every consumer acts on.

**Stopping SHALL keep the pipeline's progress, whichever component owns it.** For a kind whose cursor
ADAS owns, disabling SHALL leave the cursor and recorded state untouched, and the first run after
re-enabling SHALL continue from that cursor; a re-declaration, not a stop, is what resets it. For a kind
whose cursor the runner holds, the runner keeps its cursor, group state and queued work while the
pipeline is stopped and continues from them once it is re-enabled. Neither a stop nor a restart moves
`generation` (see "Register and enable lifecycle"), so nothing qualified by it is invalidated. Re-enabling
SHALL re-run the declaration gate exactly as the first arming does, so a pipeline whose inputs drifted
while it was stopped is refused rather than restarted.

**A pause the runner imposes on its own is a runtime condition, not a control.** The runner's dead-letter
circuit breaker stops a pipeline's new work for a bounded window and lifts itself; it SHALL NOT change
`enabled`, and it is reported by the runner's runtime status rather than by the registry. A stopped
pipeline and a pipeline the breaker has paused are therefore told apart by where the state is read: the
first reads `enabled: false` here, the second reads `enabled: true` here and paused in the runner's
status.

#### Scenario: Stopping and restarting an ADAS-driven pipeline keeps its cursor

- **GIVEN** an enabled `aggregate` pipeline whose cursor has advanced
- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: false` and a later patch sets `enabled: true`
- **THEN** the cursor is unchanged, the next run time is computed from the trigger's cron at the second
  patch, and the next run reads from the kept cursor

#### Scenario: A stopped enrich pipeline stays on the unfiltered feed, marked stopped

- **GIVEN** an enabled `enrich` pipeline whose declaration is complete
- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: false`
- **THEN** `GET /v1/pipelines?kind=enrich&view=compiled` still returns it, carrying `enabled: false` and
  the same resolved shape and an unchanged `generation`, while
  `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled` does not

#### Scenario: Restarting re-runs the declaration gate

- **GIVEN** a stopped pipeline whose declaration no longer passes its kind's governance gate
- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` and the pipeline stays
  `enabled: false`

#### Scenario: A breaker pause does not change the run control

- **GIVEN** an enabled `enrich` pipeline that the runner's dead-letter circuit breaker has paused
- **WHEN** `GET /v1/pipelines/{name}` is called
- **THEN** it reports `enabled: true`

## MODIFIED Requirements

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
narrowed by state rather than overturned. An `aggregate` declaration's `sql` is the one exception besides
`target`: a patch that carries neither `sql` nor any form-mode member keeps the stored statement, for
the reasons stated in "An aggregate declaration is authored in exactly one of two modes".

**A declaration patch that leaves the pipeline disabled SHALL NOT turn a complete declaration into an
incomplete one.** Where the stored declaration names every member its kind authors — for `enrich` a
typed `transform` with outputs and a `trigger.kind` (with its `cron` for `schedule`, its `group_by` for
`group`); for `aggregate` a `trigger.cron` and either its `inputs` and at least one measure (form mode)
or a non-blank `sql` statement with the input table stored for it (SQL mode) — a patch whose
declaration would lack any of them SHALL be refused with HTTP 422 `pipeline_validation_failed` naming
the members it would drop, and SHALL change nothing. Nothing stored tells a pipeline that ran and was
paused apart from a draft, so under replace semantics a patch carrying one runtime knob would otherwise
erase a paused pipeline's transform, trigger and inputs while answering 200. Derived members (the output
mapping, `group_by`) are not asked, since a draft may lack them for reasons no patch caused. A
declaration that is still incomplete MAY be saved with any subset, and one that is complete MAY be
replaced whole; starting a complete declaration over is a delete and re-register.

The refusal guards against **omission**, so it SHALL ask only about members of blocks the patch left
out. An `enrich` `transform` the patch restates with its `type` SHALL be taken as sent, `outputs`
included, and so SHALL a `trigger` restated with its `kind`, and an `aggregate` `sql` the patch sends,
even one from which no input table can be read. Emptying `transform.outputs` this way is
how a paused pipeline's last output is removed so that its target column can be dropped — otherwise
the catalog's drop guard and this refusal would each wait on the other, leaving delete-and-re-register
as the only way out. Arming still holds the declaration to completeness.

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

**An omitted `inputs` SHALL instead keep the declared read source when that source is the parent of the
target the patch leaves the pipeline writing** (the target it names, else the stored one). The omission
then repoints nothing, so there is nothing to refuse — and the source is kept rather than cleared, as an
omitted `target` is: clearing it would still change the pipeline, since the `view=source` projection and
a catalog export would stop naming it, the invariants a declared source is held to would lapse, and the
write would no longer leave the read source unchanged, dropping the derived members carried over only
while it is. The case is not contrived: the `compiled` projection always serves the resolved `inputs`, so
a client reading it cannot tell a declared source equal to the parent from a followed one.

**A derived member SHALL be carried over from the stored row only while it describes the same
tables.** Where a declaration write that leaves the pipeline disabled cannot re-derive a derived member
— an `aggregate` pipeline's `group_by`, an `enrich` pipeline's `output_bindings` — it keeps the stored
value only when the write leaves both the `target` and the read source unchanged from the stored row. A
write that repoints either SHALL store an empty member instead: the stored one names the previous
target's keys or columns, and carried across a repoint it would protect, or write, columns of a table
the pipeline no longer targets. A carried `output_bindings` SHALL also keep only the bindings whose
output a typed `transform` in the write still declares: a binding for an output the write removed
would keep that column guarded against a drop by a pipeline that no longer writes it.

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
  runtime edit would instead stop the pipeline for every edit: the out-of-process runner holds a disabled
  pipeline's queued work unexecuted until it is re-enabled, so each edit would delay its labels for the
  length of the cycle.

A declaration change SHALL bump the pipeline's change token (`generation`), which is what an
out-of-process consumer detects a re-declaration by.

**A change of `enabled` alone is not a declaration change.** A patch that changes only `enabled` SHALL NOT
bump `generation`; it SHALL move `updated_at`, so an `updated_since` reader still sees it. `generation`
revises the declaration: it qualifies the materialized position, the runner's queued work and every row's
`pipeline_generation` stamp, and a stop and restart changes none of what those describe. Bumping it would
make the runner discard a stopped pipeline's queued work as stale on restart — on the row lane
irreversibly, since the cursor already passed those rows — and would void the pipeline's materialized
position, holding every dependent rollup back until the runner writes again, all for a declaration that
did not change. A patch that re-declares and changes `enabled` together bumps `generation` once, for the
re-declaration. The one exception on an enable-only patch is a derived member: the arming gate derives as
well as refuses, and should it store a derived member (an output mapping, group keys, an SQL-mode input)
other than the stored one, the declaration did change under the enable, so that patch SHALL bump
`generation` once, with cause `patch(re-derive,enable)`. A draft save derives the same way, so arming
normally stores what was already there and does not bump.

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

#### Scenario: Stopping and restarting does not move the generation

- **GIVEN** an enabled pipeline at generation 4
- **WHEN** `PATCH /v1/pipelines/{name}` with only `enabled: false`, and later only `enabled: true`, is applied
- **THEN** both responses report generation 4, and each moves `updated_at`

#### Scenario: An enable whose gate re-derives a stored member bumps once

- **GIVEN** a disabled `enrich` pipeline at generation 7 whose stored output mapping differs from the one
  the arming gate derives
- **WHEN** `PATCH /v1/pipelines/{name}` with only `enabled: true` is applied
- **THEN** the response reports generation 8 and the generation-change line names `patch(re-derive,enable)`

#### Scenario: A re-declaration that also enables bumps once

- **GIVEN** a disabled pipeline at generation 4
- **WHEN** one `PATCH /v1/pipelines/{name}` supplies a whole declaration and `enabled: true`
- **THEN** the response reports generation 5

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

#### Scenario: A paused pipeline may lose its last output so its column can be dropped

- **GIVEN** a disabled `enrich` pipeline whose complete declaration has one output, `comment_count`
- **WHEN** a patch that leaves it disabled restates `transform` with its `type` and no `outputs`, and
  restates `trigger`
- **THEN** the patch is accepted, the stored output mapping no longer binds `comment_count`, and a
  following `PATCH /v1/tables/{target}/schema` dropping `comment_count` is accepted
- **AND** enabling the pipeline is refused until it declares an output again

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

#### Scenario: Omitting a declared read source that is the target's parent keeps it

- **GIVEN** an `enrich` pipeline whose stored declaration declares `inputs: ["dial_usage_log"]`, and whose
  target enrichment's parent source is `dial_usage_log`
- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration without `inputs` and without a `target`
  whose parent differs
- **THEN** the patch is accepted and the stored read source stays `dial_usage_log`, still served by the
  `view=source` projection
- **AND WHEN** the stored declaration instead declares `inputs: ["response_ratings"]` over a target whose
  parent is `turns`
- **THEN** the same patch is rejected with HTTP 422 `pipeline_validation_failed`

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
their only writer. An `aggregate` pipeline declared in **SQL mode** has a different derived member: its
input table is read from the statement's `FROM`, and its `group_by` is **not derived** at all, because
the statement's own `GROUP BY` is the declaration of its keys. Filling `group_by` from the target's
`ordering_key` in SQL mode would store a form-mode member beside `sql`, which the exclusivity rule
refuses from a caller and which the read would then serve as if the author had written it.

- **Derivation SHALL run on every declaration write**, whether or not the write arms the pipeline, and
  SHALL be best-effort: where a member cannot be derived — no transform declared yet, an output naming
  a column that does not exist — the previously stored value SHALL be left untouched. A declaration
  write SHALL NOT erase a derived member it could not re-derive. Were it to, the catalog's column
  drop/rename dependency guard, which resolves a pipeline's writes from its stored `output_bindings`,
  would stop seeing the pipeline: the column would become droppable, the ClickHouse `ALTER` would
  execute, and the catalog transaction that re-derives afterwards would roll back — leaving the column
  gone from one store and present in the other, with no operation that repairs the pair. In SQL mode
  the best-effort derivation SHALL read the input table only from a statement the SQL front end can
  plan into one `INSERT` block. A statement it cannot plan, for any reason (a parse error, an unknown
  table or column, a construct the front end refuses), SHALL be stored verbatim with the input table
  absent, and the derivation SHALL NOT throw. Whether the statement is admissible is the gate's question, not the draft write's.
- **Refusal SHALL run when the write arms the pipeline**, and on the dry-run preview of a pipeline
  that is **disabled** and therefore never gated. Arming asks, besides the kind's gate, the trigger's own
  completeness — a trigger kind, and a cron on a `schedule` trigger — and the preview of a disabled
  pipeline SHALL ask the same, so that a preview that passes means arming will pass. Whether the preview of an **enabled**
  pipeline re-gates it is the kind's to state, and the two kinds state it differently: an `enrich`
  preview of an enabled pipeline SHALL NOT re-gate it, because its declaration was gated when it was
  armed and re-gating would report it as unpreviewable for drift the operator did not cause and cannot
  see from there; an `aggregate` preview SHALL run its full gate in either state, because on that kind
  the drift is the answer an operator reading a live rollup needs (see
  [aggregate-pipeline](../aggregate-pipeline/spec.md), "Dry-run preview of an aggregate declaration").
  An `aggregate` declaration in SQL mode SHALL be held to its **full** SQL gate — the statement parsed,
  its eligibility for incremental refresh checked, and every governance rule of the form path applied
  to it — at exactly the moments the form gate runs: when a write arms it, on every preview, and on
  every refresh. A refresh SHALL NOT reuse a verdict from arming, because the input's schema, the
  referenced enrichments and the sensitive grants may all have moved since.
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
  *absence* on a `schedule` trigger is deferred to arming. An `aggregate` `sql` that does not parse is
  **not** malformed in this sense: it is a string member that binds, and it SHALL be stored on a write
  that leaves the pipeline disabled, because a statement is authored over several saves and its first
  drafts rarely parse.
- **A named table that does not exist.** `target` and `inputs` each carry a foreign key to the table
  catalog, which the database enforces on every row, so a declaration naming a table that does not exist
  SHALL be refused with HTTP 422 naming it rather than reaching storage and being reported as a name
  collision. Whether the table is the right kind of table, `ACTIVE` and scannable stays the gate's
  question. The refusal covers the tables a caller **names** in `target` and `inputs`; a table an SQL
  statement's `FROM` names is read by the best-effort derivation, so one that does not exist leaves
  the derived input table absent on a disabled write rather than refusing it, and arming then refuses
  it as incomplete.

A storage-layer refusal that is reached anyway SHALL be reported by the constraint it broke: a
collision on the pipeline's name or target as HTTP 409, and a CHECK or foreign-key violation as HTTP 422
`pipeline_validation_failed` naming the rule — never as "name or target already in use".

A derived member SHALL be stored as an **empty collection rather than null** when it has no entries, so
that a partial declaration has one representation in storage and in memory.

**The deferral is safe because a disabled pipeline runs nothing.** ADAS's scheduler selects on
`(enabled, next_run_at)`; the enrich feed omits a pipeline whose declaration is incomplete whatever its
flag, and the Enrichment Runner executes only feed entries carrying `enabled: true`, so no incomplete
declaration is reached by either. What the gate protects is the moment the pipeline begins to
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
- **THEN** ADAS's scheduler does not select it, the enrich feed does not carry it under any
  `enabled` filter, and nothing is written to its target

#### Scenario: Preview of an enabled pipeline re-gates according to its kind

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an enabled pipeline whose stored
  declaration no longer passes its kind's gate
- **THEN** an `aggregate` pipeline is refused with the status and code arming would give, while an
  `enrich` pipeline runs only the checks its enabled preview runs, not the gate, and is sampled

#### Scenario: An unparseable SQL draft is stored without an input table

- **WHEN** `POST /v1/pipelines` or a `PATCH /v1/pipelines/{name}` that leaves the pipeline disabled carries
  an `aggregate` `sql` that does not parse
- **THEN** the write succeeds, `sql` is stored verbatim and read back unchanged, the pipeline's input table
  is absent, and no parse error is raised

#### Scenario: An SQL draft derives its input table from the statement

- **WHEN** a write that leaves an `aggregate` pipeline disabled carries an `sql` whose `FROM` names an
  existing table and carries no `inputs`
- **THEN** the stored input table is the table the statement reads

#### Scenario: An SQL-mode draft does not derive group keys

- **WHEN** a write that leaves an `aggregate` pipeline disabled carries `sql` and no `group_by`, and the
  target's `ordering_key` columns are all plain input columns of the same name
- **THEN** no `group_by` is stored, and the authored projection serves `sql` without `group_by`

#### Scenario: Arming an SQL declaration runs the full SQL gate

- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true` on an `aggregate` pipeline whose stored `sql`
  parses but is not eligible for incremental refresh — for instance it carries a `HAVING` clause
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the clause, the
  pipeline stays disabled, and the stored `sql` is unchanged

#### Scenario: An armed SQL declaration is re-gated on every refresh

- **GIVEN** an enabled `aggregate` pipeline in SQL mode whose statement passed the gate when it was armed
- **WHEN** the input or an enrichment the statement references changes so that the statement no longer
  passes the gate, and the next refresh runs
- **THEN** the refresh runs the full SQL gate again, fails with the refusal arming would now give, and
  writes nothing to the target

### Requirement: A generation change is logged with its old and new value and its cause

Every change to a pipeline's `generation` SHALL log one INFO line naming the pipeline, the old generation,
the new generation and the cause. The cause is the mutation that bumped it: `register`; a patch, as
`patch(…)` naming what the patch did (`re-declare` or, for an enable whose gate re-derived a stored member,
`re-derive`, with `enable` or `disable` comma-separated after it when the same patch changed `enabled`); `version_column_repoint` for a
source's version-column repoint; or `target_columns_changed` for a target's column change. A patch
bumps the generation once however many of those it combines, so it logs one line; a registration or patch that
is refused logs none, and neither does a patch that only changes `enabled` and re-derives nothing, since it bumps nothing (see "Register
and enable lifecycle"). It SHALL name the acting principal
as `actor=` when the mutation carries one, and SHALL give `actor=-` rather than invent one when it does not, as
with a bump cascaded from a schema edit, or when `security.mode=none`. The field is always present, as the
Enrichment Runner's operator lines always carry `principal=`, so a line without a principal is still parsed the
same way. A bump that touches several pipelines at once
SHALL log one line per pipeline, not only a count.

The register, re-declare, enable and disable lines SHALL also give the pipeline's generation after the change.

The generation qualifies the materialized position, the runner's scan state and every row's
`pipeline_generation` stamp. A bump nobody can see explains a rollup freeze or a runner re-scan only
after a database read.

#### Scenario: A re-declaration logs the bump

- **WHEN** an operator re-declares a pipeline at generation 4
- **THEN** one INFO line reports the pipeline, `4 -> 5`, the cause `patch(re-declare)`, and the operator when one
  is authenticated

#### Scenario: A stop logs no generation change

- **WHEN** an operator patches only `enabled: false` on a pipeline at generation 4
- **THEN** no generation-change line is logged, and the disable line reports generation 4

#### Scenario: A cascaded bump names each pipeline

- **WHEN** a source's version column is repointed and three pipelines reading it are bumped
- **THEN** three INFO lines are logged, each with that pipeline's old and new generation, the cause and
  `actor=-`
