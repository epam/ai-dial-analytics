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

### Requirement: A declaration field belonging to another kind is rejected

The system SHALL reject a registration or patch carrying a declaration field that belongs to a kind
other than the request's own `kind`, with HTTP 422 naming the offending field. Silently ignoring it
SHALL NOT be an option.

The registry serves one flat request and response shape across kinds, so a caller can send any
field to any kind and the binding will accept it. Cross-kind rejection is what makes that flat shape
safe, and it is the only thing that does: a field ignored rather than refused reads back absent from
the stored pipeline, so a caller who mistook one kind's vocabulary for another's would be told their
declaration was accepted while the part they cared about was discarded.

Consequently a field that is required for one kind and meaningless for another SHALL NOT be enforced
by the request shape alone. Such a field SHALL be optional at the binding and required by the
kind-dispatched validation, so the failure is the 422 above rather than a generic deserialization
400.

Where a kind's fields are **nested** inside a block, the cross-kind rejection SHALL name
`<block>.<member>` rather than the block. Naming only the block throws away the half of the message
that tells the caller which field they misplaced, which is the whole purpose of the rejection. The
`enrich` kind's **`transform`** block SHALL be listed this way, member by member — `transform.type`,
`transform.model`, `transform.preset`, `transform.params`, `transform.request_template`,
`transform.inputs`, `transform.outputs`, `transform.output_vars`, `transform.response_schema` — exactly
as `advanced` is, so an `aggregate` declaration carrying a prompt is told which member it misplaced.

One member of that list is **verb-dependent**, and SHALL be listed with the qualifier rather than
flatly: `transform.output_vars` is not a member of the create-side record at all (a new declaration
authors `outputs`), so on `POST` it is refused at the binding with HTTP 400 on **either** kind, under
the rule below, and the 422 cross-kind refusal applies to it only on `PATCH`.

A field that **no longer belongs to any kind** SHALL be refused at the binding with HTTP 400 naming
it, exactly as a field that never existed — not accepted for a transitional release and not
cross-checked against a derived value. Accepting a spelling the read never echoes is the same failure
strict request parsing exists to prevent: the request succeeds while doing something other than what
the caller wrote. It follows that a removed **flat** spelling is refused at the binding on **either**
kind, before the cross-kind check is reached, so nothing has to be double-listed and there is no
window in which the flat spelling stops being refused on the kind it never belonged to.

**`evaluator_name` and `evaluator_version` move from the first rule to the second** when the evaluator
is folded into the declaration. They were enrich-only members and were cross-kind-rejected with 422 on
an `aggregate` declaration; after the fold they are members of no kind, so they are removed from the
request records and refused at the binding with **400** on either kind. The status change is not
cosmetic — it is the same distinction the two rules already draw, applied as a member leaves the
vocabulary rather than moves between kinds — and it is called out because a test asserting 422 for
`evaluator_name` on an `aggregate` declaration will now see 400 and is asserting the wrong rule, not
finding a regression.

**`sql` SHALL be an `aggregate`-only member**, listed with `group_by` and `measures`, so an `enrich`
declaration carrying it is refused with HTTP 422 naming `sql`. Within the `aggregate` kind, `sql` SHALL be
**mutually exclusive** with the form-mode members `group_by`, `measures` and `filter`: a registration or
patch carrying `sql` beside any of them SHALL be refused with HTTP 422 `pipeline_validation_failed`
naming the form-mode member it carries, and nothing SHALL be persisted. The statement carries its own
group keys, measures and `WHERE`, so a request carrying both would be two declarations of the same
thing, and any rule choosing one of them silently discards the other — the failure this requirement
exists to prevent. `filter` needs the exclusivity stated separately because it is not aggregate-only:
it is shared with `enrich`, so the cross-kind check above never sees it. Like the cross-kind refusal,
the exclusivity SHALL be refused on every write, armed or not: it is a contradiction between two stated
members, which no further authoring resolves, not an incompleteness the gate may defer.

#### Scenario: An aggregate-only field on an enrich declaration is refused

- **WHEN** `POST /v1/pipelines` registers `kind: enrich` carrying aggregate `measures`
- **THEN** the request is rejected with HTTP 422 naming the field, and nothing is persisted

#### Scenario: An enrich-only field on an aggregate declaration is refused

- **WHEN** `POST /v1/pipelines` registers `kind: aggregate` carrying `transform.request_template`
- **THEN** the request is rejected with HTTP 422 naming `transform.request_template`, not `transform`,
  and nothing is persisted

#### Scenario: A field required only for another kind is not required by the binding

- **WHEN** `POST /v1/pipelines` registers a kind for which `inputs` is optional, omitting it
- **THEN** the request is accepted, rather than failing the binding that another kind's requirement
  would impose

#### Scenario: A field belonging to no kind is refused by the binding

- **WHEN** a registration or patch carries a member no kind declares — a misspelling such as
  `member_select.order` for `order_by`, at any nesting depth
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the unrecognized field, and nothing
  is persisted

The two refusals are deliberately different statuses, and the boundary is which vocabulary the field
belongs to. A field this registry knows but the request's `kind` does not is a semantic error the
kind-dispatched validation reports (422, above). A field the shape does not define at all cannot be
attributed to a kind, so it is refused at the binding (400). What matters is that neither is accepted
silently: the flat shape makes both spellings bind cleanly, and an all-optional record cannot tell them
apart by validation.

#### Scenario: A nested enrich-only member on an aggregate declaration names the member

- **WHEN** `POST /v1/pipelines` registers `kind: aggregate` carrying `advanced.rows_per_scan`
- **THEN** the request is rejected with HTTP 422 naming `advanced.rows_per_scan`, not `advanced`

#### Scenario: A removed flat spelling is refused on either kind

- **WHEN** `POST /v1/pipelines` carries `cadence` on `kind: enrich` or on `kind: aggregate`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, before any cross-kind
  check is reached

#### Scenario: The retired scan_every member is refused at the binding on either kind

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries `advanced.scan_every` on
  `kind: enrich` or on `kind: aggregate`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming `advanced.scan_every`, not
  cross-kind-rejected with 422, because after its retirement the member belongs to no kind

#### Scenario: A retired evaluator reference is refused at the binding, not cross-kind

- **WHEN** `POST /v1/pipelines` carries `evaluator_name` or `evaluator_version` on `kind: enrich` or on
  `kind: aggregate`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, before any cross-kind
  check is reached — the member belongs to no kind rather than to the other one

#### Scenario: An SQL declaration on an enrich pipeline is refused

- **WHEN** `POST /v1/pipelines` registers `kind: enrich` carrying `sql`
- **THEN** the request is rejected with HTTP 422 naming `sql`, and nothing is persisted

#### Scenario: An SQL declaration beside form-mode group keys or measures is refused

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` on `kind: aggregate` carries `sql` together
  with `group_by` or `measures`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `group_by` or
  `measures`, and nothing is persisted, whether or not the write would arm the pipeline

#### Scenario: An SQL declaration beside a filter is refused

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` on `kind: aggregate` carries `sql` together
  with `filter`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `filter`, and nothing
  is persisted, rather than the filter being dropped or ANDed into the statement's `WHERE`

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

### Requirement: A declaration patch keeps the pipeline's stored target unless it names another

A declaration patch that omits `target` SHALL keep the stored value, and one that supplies a different
value SHALL repoint the pipeline. Every other declaration member keeps replace semantics: what the
patch supplies becomes the declaration, and what it omits is cleared — except an `aggregate`
declaration's `sql`, which a patch carrying no declaration-mode member keeps (see "An aggregate
declaration is authored in exactly one of two modes").

In SQL mode the stored `target` and the statement's `INSERT INTO` target are two statements of the
same fact, and the pipeline's `target` SHALL remain the authoritative one: it is what the one
pipeline-per-target constraint, the foreign key and the read projection hold. A repoint SHALL NOT
rewrite the stored statement, and a statement naming a different table SHALL NOT repoint the pipeline.
Whether the two agree is the gate's question — a mismatch is refused with HTTP 422
`pipeline_validation_failed` naming both tables when the pipeline is armed or previewed — and a write
that leaves the pipeline disabled SHALL store them as given, because an author repointing a draft
restates the statement in a later save.

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

#### Scenario: An SQL declaration patch omitting the target keeps it

- **WHEN** `PATCH /v1/pipelines/{name}` on a disabled `aggregate` pipeline carries a new `sql` and no
  `target`
- **THEN** the stored `target` is retained and the new `sql` is stored

#### Scenario: Repointing an SQL pipeline does not rewrite its statement

- **GIVEN** a disabled `aggregate` pipeline in SQL mode whose statement inserts into its stored target
- **WHEN** `PATCH /v1/pipelines/{name}` supplies a different `target` and no `sql`
- **THEN** the pipeline is repointed, the stored `sql` is unchanged and still names the previous table
- **AND WHEN** the pipeline is then previewed or armed
- **THEN** the request is refused with HTTP 422 `pipeline_validation_failed` naming the statement's
  target and the pipeline's target

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

It SHALL apply to an `aggregate` declaration's `sql` and to the members of the mode it does not use. A
pipeline in SQL mode SHALL be served with `sql`, verbatim as it was sent — not re-rendered, normalised or
re-indented — and without `group_by`, `measures` or `filter`, with `inputs` carrying the input table
derived from the statement whether or not the caller declared it; a pipeline in form mode SHALL be served
without `sql`. Mode exclusivity makes this load-bearing rather than cosmetic: a read that served the
unused mode's members as `[]` would, sent back whole, be refused as a request carrying both modes.

`view=compiled` SHALL stay refused for an `aggregate` pipeline in either mode, as "A requested projection
is served as requested or refused" states. SQL mode adds no compiled projection: the statement ADAS
renders and runs is an execution detail of the refresh, and serving it would make it a contract.

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

#### Scenario: An SQL-mode read carries the statement verbatim and no form-mode member

- **WHEN** an `aggregate` pipeline declared with `sql` is read with the authored projection
- **THEN** the response carries `sql` exactly as it was last sent, comments and whitespace included, and
  omits `group_by`, `measures` and `filter`

#### Scenario: A form-mode read carries no sql

- **WHEN** an `aggregate` pipeline declared with `group_by` and `measures` is read with the authored
  projection
- **THEN** the response omits `sql`

#### Scenario: A compiled read of an SQL-mode aggregate stays refused

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` names an `aggregate` pipeline declared with `sql`
- **THEN** the request is refused with HTTP 422 `pipeline_validation_failed` naming the pipeline's kind,
  exactly as for a form-mode `aggregate` pipeline, and no rendered statement is served


### Requirement: A row-confined caller may not arm or run a pipeline over a table it is confined on

A caller SHALL NOT arm or run a pipeline over a table a row policy confines it on.

A pipeline reads its sources under the system identity, which no row policy confines (see
[table-access-control](../table-access-control/spec.md)). A caller confined by a row policy on a table
SHALL therefore not be able to have a pipeline read that table on its behalf.

The tables a pipeline **reads**, for this rule, SHALL be its input table — the declared one, or for an
`aggregate` in SQL mode that declares no `inputs` the one derived from its statement's `FROM` — its target's
catalog `source_table` when the target is an enrichment, and every enrichment of either. That is deliberately a
superset of what any one declaration joins: the answer does not depend on a declaration that may not yet
resolve, and refusing a confined caller costs nothing it was entitled to. On any of these tables, a caller
on which row-policy evaluation confines its reads SHALL be refused with HTTP 403 and error code
`row_policy_confined`, naming the table, when it:

- writes a declaration that **arms** the pipeline — a registration or patch leaving it `enabled: true`,
  or an enable;
- requests a dry-run **preview** of the pipeline, of either kind; or
- requests an **on-demand refresh** of an aggregate pipeline.

The check SHALL run before any part of the request is persisted, and SHALL be asked of the tables the
pipeline reads after the write: an SQL-mode input is derived as the declaration is applied, so asking the
request's own `inputs`, which SQL mode does not require, would skip the very table the refresh reads.
Naming the table discloses nothing: the
caller addressed a pipeline whose declaration already names it. A write that leaves the pipeline disabled
SHALL be stored as the deferred gate stores it, and the check SHALL run when that pipeline is armed. A
scheduled refresh SHALL NOT be subject to the check — it runs under the system identity, which carries no
provider role — and what it writes is governed by the target's own policies.

In `none` mode the check SHALL NOT apply.

#### Scenario: A confined administrator cannot arm a pipeline over its confined table

- **GIVEN** a caller holding `REGISTRY_ADMIN` whose role is confined by a row policy on a table, and a
  disabled aggregate pipeline whose input is that table
- **WHEN** it enables the pipeline, previews it, or refreshes it on demand
- **THEN** each request is refused `403 row_policy_confined` naming the table, and nothing runs

#### Scenario: A disabled declaration is stored and checked on arming

- **WHEN** the same caller registers a pipeline over the table with `enabled: false`
- **THEN** the declaration is stored
- **AND WHEN** it later enables the pipeline
- **THEN** the enable is refused `403 row_policy_confined`

#### Scenario: A confinement on the target's source is refused

- **GIVEN** a caller unconfined on an enrich pipeline's declared input but confined on its target
  enrichment's `source_table`
- **WHEN** it arms the pipeline
- **THEN** the request is refused `403 row_policy_confined` naming that source

#### Scenario: An unconfined caller reaches the gate

- **WHEN** a caller holding `REGISTRY_ADMIN`, unconfined on every table the pipeline reads, arms it
- **THEN** the request is processed under the existing rules, including the declaration gate

#### Scenario: Arming an SQL declaration is checked against the statement's input

- **GIVEN** a caller confined by a row policy on `dial_usage_log`, and a disabled `aggregate` pipeline in SQL
  mode whose statement reads `dial_usage_log` and which declares no `inputs`
- **WHEN** it sends `PATCH /v1/pipelines/{name}` carrying a new `sql`, or only a `trigger`, together with
  `enabled: true`
- **THEN** the request is refused `403 row_policy_confined` naming `dial_usage_log`, and nothing is stored

## ADDED Requirements

### Requirement: An aggregate declaration is authored in exactly one of two modes

An `aggregate` declaration SHALL be authored in exactly one of two modes, and the stored declaration
SHALL carry the members of one mode only:

- **form mode** — `group_by`, `measures` and `filter`, as before;
- **SQL mode** — `sql`, one `INSERT INTO <target> (<columns>) SELECT …` statement that carries the group
  keys, the measures and the `WHERE` itself. What the statement may contain, and how it is refreshed, is
  [aggregate-pipeline](../aggregate-pipeline/spec.md)'s to state.

`inputs` and `trigger` belong to both modes. In SQL mode `inputs` SHALL be optional: the statement's
`FROM` names the input, and the pipeline's input table is derived from it. An `inputs` the caller does
send SHALL be kept and SHALL equal the statement's input at the gate, refused with HTTP 422
`pipeline_validation_failed` naming both tables otherwise. The rule that an `aggregate` pipeline names
its input by the time it is armed is satisfied in SQL mode by the statement.

The registry SHALL keep no record of whether an SQL-mode `inputs` was declared or derived: the stored
input table is one value, and the authored read SHALL serve it as `inputs` either way. A client that
reads the pipeline, changes the statement's `FROM` and sends the body back with the stale `inputs`
SHALL therefore meet the HTTP 422 mismatch naming both tables at the gate, and SHALL drop or update
`inputs` alongside the statement.

**A patch SHALL switch modes explicitly, by what it carries**:

- A patch carrying `sql` SHALL store an SQL-mode declaration: any stored `group_by`, `measures` and
  `filter` are cleared.
- A patch carrying `group_by`, `measures` or `filter` SHALL store a form-mode declaration: any stored
  `sql` is cleared.
- A patch carrying **neither** — only `trigger`, `inputs`, `target`, or any other member both modes share —
  SHALL keep the stored mode, and in SQL mode SHALL keep the stored `sql` unchanged.

The third rule is an exception to replace semantics, and it is deliberate. In form mode a declaration is
several members, and omitting one of them is the documented way to clear it. In SQL mode the whole
keys-measures-filter body is one member, so clearing it by omission would make every runtime patch — a new
cron, a restated input — erase the statement it never mentioned. On a draft that would answer 200 and lose
the author's text; on a paused complete pipeline it would be refused by the completeness guard, which
makes every such patch carry a statement it did not mean to change. Mode switching is therefore stated by
what a patch carries, never by what it omits. The way to clear a stored statement is to send a form-mode
declaration, or to delete and re-register the pipeline.

A blank `sql` SHALL be refused with HTTP 422 `pipeline_validation_failed` ("sql must not be blank") on every
write, armed or not and whatever else the write carries: stored, it would be an SQL-mode declaration with no
statement in it.

A mode switch SHALL be subject to every other rule a declaration patch is: it is a re-declaration, it is
refused with HTTP 409 on an enabled pipeline, and on a disabled pipeline whose stored declaration is
complete it SHALL NOT leave the declaration incomplete.

#### Scenario: Sending sql switches a form-mode pipeline to SQL mode

- **GIVEN** a disabled `aggregate` pipeline declared with `group_by`, `measures` and `filter`
- **WHEN** `PATCH /v1/pipelines/{name}` carries `sql`, `inputs` and `trigger`
- **THEN** the stored declaration carries `sql`, and `group_by`, `measures` and `filter` are cleared and
  absent from the authored read

#### Scenario: Sending measures switches an SQL-mode pipeline back to form mode

- **GIVEN** a disabled `aggregate` pipeline declared with `sql`
- **WHEN** `PATCH /v1/pipelines/{name}` carries `group_by`, `measures`, `inputs` and `trigger`
- **THEN** the stored declaration carries the form-mode members, and `sql` is cleared and absent from the
  authored read

#### Scenario: A patch carrying only the trigger keeps the stored statement

- **GIVEN** a paused `aggregate` pipeline in SQL mode whose declaration is complete
- **WHEN** `PATCH /v1/pipelines/{name}` carries only `trigger` with a new `cron`
- **THEN** the response is 200, the new cron is stored, and `sql` is unchanged, character for character

#### Scenario: A mode switch may not leave a paused declaration incomplete

- **GIVEN** a paused `aggregate` pipeline in SQL mode whose declaration is complete
- **WHEN** `PATCH /v1/pipelines/{name}` carries `group_by` and nothing else of the declaration
- **THEN** the response is HTTP 422 `pipeline_validation_failed` naming the members the form-mode
  declaration would lack, and the stored `sql` and `generation` are unchanged

#### Scenario: A blank statement is refused

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` on `kind: aggregate` carries an `sql` that is
  empty or only whitespace, with or without a form-mode member beside it
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` with the message `sql must not
  be blank`, and nothing is persisted

#### Scenario: A declared input that disagrees with the statement is refused at the gate

- **WHEN** an `aggregate` pipeline in SQL mode carries `inputs` naming a table other than the one the
  statement reads, and the pipeline is previewed or armed
- **THEN** the request is refused with HTTP 422 `pipeline_validation_failed` naming both tables

#### Scenario: A stale echoed inputs is refused after the FROM changes

- **GIVEN** an `aggregate` pipeline in SQL mode registered with no `inputs`, whose statement reads
  `dial_usage_log`
- **WHEN** a caller reads it, receiving `inputs` of `dial_usage_log`, changes the statement to read
  `turns`, sends the body back with that `inputs` unchanged, and the pipeline is then previewed or armed
- **THEN** the request is refused with HTTP 422 `pipeline_validation_failed` naming both tables

### Requirement: An SQL declaration counts as authored for completeness

An `aggregate` `sql` SHALL count as an authored declaration for every completeness rule the registry
applies — the refusal of a disabled patch that would turn a complete declaration incomplete, and the
completeness asked when a write arms the pipeline. An SQL-mode declaration SHALL be **complete** when it carries `trigger.cron` and an `sql` from
which its input table could be read, and it SHALL NOT be asked for `measures`, `group_by` or `inputs`.

For the refusal of a disabled patch, the **stored** declaration's completeness SHALL be judged from what is
stored, without reading the statement again: a non-blank `sql`, a stored input table (declared, or derived
when the statement was written) and a `trigger.cron`. Reading the statement plans it as the current caller,
so a caller who may not see a column it reads would otherwise see a complete declaration as incomplete, and
its partial patch would erase the statement.

Without this a paused SQL-mode pipeline would never count as complete — it carries no measures — so
nothing would protect it, and a declaration patch that dropped its statement would answer 200.

An `sql` from which no input table could be read — one the SQL front end cannot plan into one `INSERT`
block, for any reason: a parse error, an unknown table or column, or a construct the front end refuses —
SHALL count as **incomplete**, reported as `sql`. Arming it SHALL be refused with
HTTP 422 `pipeline_validation_failed` naming `sql`, before the SQL gate is asked anything else. A
disabled write SHALL still store it, and the stored declaration is then a draft like any other
incomplete one, which later patches may replace with any subset.

The completeness guard asks about **omission**, as it does for a restated `enrich` transform: an `sql`
the patch itself carries SHALL be taken as sent, even one from which no input table can be read.
Rewriting the statement is how its author edits it, and refusing an unfinished edit of a paused
pipeline would make the statement editable only in one save.

#### Scenario: A complete SQL-mode declaration is not asked for measures

- **GIVEN** a disabled `aggregate` pipeline whose stored `sql` parses and reads an existing input, with a
  `trigger.cron`, and which declares no `measures`, `group_by` or `inputs`
- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true`
- **THEN** no completeness refusal names `measures`, `group_by` or `inputs`, and arming proceeds to the
  full SQL gate

#### Scenario: A stored statement's completeness does not depend on the caller

- **GIVEN** a disabled `aggregate` pipeline in SQL mode whose declaration is complete, and a caller who may
  not read a sensitive column its statement reads
- **WHEN** that caller sends `PATCH /v1/pipelines/{name}` carrying only `group_by`
- **THEN** the response is HTTP 422 `pipeline_validation_failed` naming the members the form-mode
  declaration would lack, and the stored `sql` is unchanged

#### Scenario: Arming an unreadable statement is refused as incomplete

- **GIVEN** a disabled `aggregate` pipeline whose stored `sql` does not parse
- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `sql`, and the
  pipeline stays disabled

#### Scenario: A statement that parses but cannot be planned is incomplete

- **WHEN** a write that leaves an `aggregate` pipeline disabled carries an `sql` that parses but reads a
  column its `FROM` table does not have
- **THEN** the write succeeds with the input table absent, and a later `PATCH` setting `enabled: true` is
  rejected with HTTP 422 `pipeline_validation_failed` naming `sql`

#### Scenario: Rewriting a paused statement is taken as sent

- **GIVEN** a disabled `aggregate` pipeline in SQL mode whose declaration is complete
- **WHEN** `PATCH /v1/pipelines/{name}` carries a new `sql` that does not yet parse
- **THEN** the patch is accepted, the new `sql` is stored verbatim with the input table absent, and
  enabling the pipeline is refused until the statement can be read again

### Requirement: A changed SQL statement is a re-declaration

A patch that carries `sql` SHALL be a declaration patch, and SHALL bump `generation` and reset the
ADAS-owned cursor and rebuild anchor exactly as every other `aggregate` re-declaration does, so the next
refresh reads the input from the beginning and recomputes every key under the new statement. A patch
carrying `sql` on an **enabled** `aggregate` pipeline SHALL be refused with HTTP 409 `pipeline_conflict`
and SHALL change nothing, whether or not the statement differs from the stored one.

The statement is the declaration, so a change to it is a change to how every target row is computed,
and the reasons the registry gives for resetting the cursor and for refusing edits of an enabled
`aggregate` pipeline apply to it unchanged. Comparing the new text to the stored one to skip the reset
SHALL NOT be attempted: two texts that differ only in whitespace may still be the same declaration, and
two that look alike may not, and the registry does not parse to decide.

#### Scenario: Patching the statement resets the cursor

- **GIVEN** a disabled `aggregate` pipeline in SQL mode that has refreshed before
- **WHEN** `PATCH /v1/pipelines/{name}` carries a changed `sql`
- **THEN** `generation` is bumped, the cursor is reset, and the next refresh after arming recomputes every
  key from the start of the input

#### Scenario: Patching the statement of an enabled pipeline is a conflict

- **WHEN** `PATCH /v1/pipelines/{name}` carries `sql` for an `aggregate` pipeline that is `enabled`
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict`, and the stored `sql`, `generation`
  and cursor are unchanged
