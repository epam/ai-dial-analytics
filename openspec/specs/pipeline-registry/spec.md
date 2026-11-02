# pipeline-registry Specification

## Purpose

The **pipeline registry**: the kind-independent object every declared row-producing process is, and the
`/v1/pipelines` control plane over it. A pipeline names one `target` table it writes, its `inputs`, a
membership `filter`, a `trigger`, an `enabled` flag, and a `kind` — and the kind selects both the
transform's vocabulary and where it runs. A pipeline is **data, not code**: registering one
deploys nothing.

This capability owns what does not depend on the kind: registry CRUD, the `kind` discriminator and its
refusal contract, one writer per target, the identity grammar and its immutability, the register → enable
lifecycle, the rejection of a field belonging to another kind, the readable runtime `state`, and the
`ErrorView` codes every `/v1/pipelines` failure uses. The kinds themselves are elsewhere:
[aggregate-pipeline](../aggregate-pipeline/spec.md) for the in-DB rollup ADAS refreshes on a cron, and
[enrichment-registry](../enrichment-registry/spec.md) plus
[enrichment-runtime](../enrichment-runtime/spec.md) for the `enrich` declaration the Enrichment Runner
drives — except its Class-1 case, which ADAS materializes itself
([class1-enrichment](../class1-enrichment/spec.md)).

It exists as a capability of its own because it used to be documented inside one of its kinds, which is
what made two registries look like two concepts. The contract that matters most is **cross-kind field
rejection**: one flat request shape serves every kind, and a field belonging to another kind is refused
rather than ignored — a field silently dropped would tell a caller their declaration was accepted while
the part they cared about was discarded.
## Requirements
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

Pipeline registration, mutation, and deletion SHALL require `REGISTRY_ADMIN` in `oidc` mode; per-table
grants SHALL NOT authorize them. **Reading** a pipeline SHALL require `REGISTRY_READ`, which the
`READ_ONLY_ADMIN` bundle holds.

`REGISTRY_READ` is the same reach the previous rule described — "any mapped application role" — named as
a privilege instead of as the absence of a narrower one. The reasoning is unchanged and is what makes it
a *registry* privilege rather than a table one: the declaration is the answer to "what populates this
table, and is it running", which is the question the read-only catalog role exists to answer, and
withholding it while the table catalog and schema discovery were open to that role made the registry read the only closed catalog read, without closing anything the role could
not already reach.

Being its own privilege has one consequence worth stating: `REGISTRY_READ` neither confers nor is
conferred by any table privilege. A caller holding `TABLE_READ` on every table cannot list pipelines, and
a caller holding `REGISTRY_READ` can enumerate pipelines whose sources it cannot read — which is sound
precisely because a read exposes no row value (below). A read
therefore exposes the declaration in full — `filter`, `expression`, measures, bindings, the `enrich`
kind's `transform` including its prompt, and the **names** of `sensitive` columns the declaration
references. What a read SHALL NOT expose is any `sensitive` column **value**; see **A pipeline read
carries no row values**.

The prompt SHALL be visible to this role because the prompt **is** the declaration — the answer to
"what populates this table" — and the pipeline read is the only place that question is answered. The
prompt therefore appears in **both** projections, the authored one included.

**A read of an historical revision** (`GET /v1/pipelines/{name}/revision/{revision}`) SHALL be
understood to return the stored declaration **re-serialized in the current shape**, not the bytes
that were stored. A member that has since stopped being part of a declaration is therefore silently
absent from a revision read, even though the revision recorded it. This is a stated limitation rather
than a behaviour to rely on; returning the raw stored JSON for a revision read is the fix and is not
specified here. One instance: a revision recorded before the transform was inlined named its evaluator
by reference, which the current shape has no member for, so a revision read of it answers with no
transform and no evaluator reference at all. It is also why the persistence codec's tolerance of
unknown stored members is **permanent**: stored declarations are deliberately not rewritten when the
request vocabulary changes.

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

- **WHEN** a caller holding no `REGISTRY_ADMIN` invokes a mutating `/v1/pipelines` registry endpoint in
  `oidc` mode — register, patch, or delete
- **THEN** the request is rejected with HTTP 403

#### Scenario: A read-only admin may read the registry

- **WHEN** a caller holding only the `READ_ONLY_ADMIN` bundle calls `GET /v1/pipelines`,
  `GET /v1/pipelines/{name}`, or `GET /v1/pipelines/{name}/revision/{revision}` in `oidc` mode
- **THEN** each request succeeds with the same body a fully-privileged caller receives, field for field

#### Scenario: A read-only admin sees the declaration, prompt included

- **WHEN** a caller holding only the `READ_ONLY_ADMIN` bundle reads an `enrich` pipeline whose transform
  carries a prompt template, and whose `filter` references a column marked `sensitive`
- **THEN** the response carries the transform's prompt template and the `filter` text naming that
  column, because both are declaration rather than row content

#### Scenario: A caller with no application role still cannot read the registry

- **WHEN** a caller holding no `REGISTRY_READ` — including one authorized entirely by per-table grants,
  and one holding `TABLE_READ` over every table — calls any `GET /v1/pipelines` endpoint in `oidc` mode
- **THEN** the request is rejected with HTTP 403; a table privilege SHALL NOT authorize a registry read
  any more than it authorizes a registry write

#### Scenario: Operator actions on a pipeline still require full admin

- **WHEN** a caller holding `REGISTRY_READ` but not `REGISTRY_ADMIN` calls
  `POST /v1/pipelines/{name}/preview` or `POST /v1/pipelines/{name}/refresh`
- **THEN** the request is rejected with HTTP 403, because each executes a query against the input or
  writes rows to the target rather than reporting registered state. `DATA_SYNC_OPERATE` does not
  authorize them either: it covers advancing ingestion without reference to a stored declaration

#### Scenario: Widening the registry read does not widen the data plane

- **WHEN** a caller holding only the `READ_ONLY_ADMIN` bundle, and no `write` grant on the pipeline's
  target, calls `POST /v1/pipelines/{name}/scan`, `/group-rows`, `/materialize`, or `/rows`
- **THEN** the request is rejected by the pipeline's own grant check, which now requires `TABLE_READ` on
  the read source **and** `TABLE_WRITE` on the target (see `enrichment-runtime`); `REGISTRY_READ` SHALL
  NOT authorize any pipeline-scoped data-plane call

### Requirement: A pipeline name is an identity, not a SQL identifier

A pipeline `name` SHALL match `^[a-z][a-z0-9_-]{0,63}$` — a lower-case letter followed by up to 63
lower-case letters, digits, underscores or hyphens — validated at registration and reported as a
pipeline validation failure. The grammar is an **identity** grammar and SHALL NOT be the
table-and-column identifier grammar: a pipeline `name` never becomes a ClickHouse identifier, so
what the grammar exists for is not injection safety but the properties out-of-process consumers
depend on.

The exclusions are what the grammar is for. `#` SHALL be excluded because the Enrichment Runner
embeds the identity in `#`-delimited work-item identifiers and counts a pipeline's in-flight work by
its `name + "#"` prefix; the names `a#b` and `a` would then share a prefix and one pipeline's
backpressure would count another's work. Upper case and a leading underscore SHALL be excluded so an
identity is unambiguous in the paths, logs and metric tags that carry it.

Hyphens SHALL be admitted. Kebab-case is the enrichment sub-domain's naming convention (for example
`session-insights-live`), though nothing enforces it, while snake_case is the catalog's, *because*
catalog names become SQL. A registry holding both kinds SHALL admit both spellings rather than force one sub-domain onto
the other's convention.

`name` SHALL be **immutable**. A patch that supplies a different name SHALL NOT rename the pipeline
in place. Out-of-process consumers key their own runtime state on the identity, so a rename is a
data migration and not a field edit.

A name can be reused, though: a delete followed by a registration under the same name is a new pipeline.
`created_at` SHALL therefore be set once, at registration, and SHALL NOT change for the life of the pipeline, so a
pipeline recreated under the same name always reports a different `created_at` from its predecessor. The Enrichment
Runner depends on this: it records each name's `created_at` and resets the runtime state it keys on the name when the
value changes. Changing when `created_at` is set is therefore a runner contract change.

#### Scenario: A recreated pipeline reports a new created_at

- **GIVEN** a pipeline `p` that is patched, disabled and re-enabled, then deleted
- **WHEN** a pipeline is registered again under the name `p`
- **THEN** every read of the first pipeline reported one `created_at`, and the new pipeline reports a different one

#### Scenario: A hyphenated name is accepted

- **WHEN** `POST /v1/pipelines` registers a pipeline named `usage-client-identity-live`
- **THEN** the pipeline is accepted and addressable at `/v1/pipelines/usage-client-identity-live`

#### Scenario: A name carrying the work-item delimiter is refused

- **WHEN** `POST /v1/pipelines` registers a name containing `#`
- **THEN** the request is rejected with HTTP 422 and the pipeline validation error code, not the
  table validation code

#### Scenario: Upper case and a leading underscore are refused

- **WHEN** `POST /v1/pipelines` registers a name containing an upper-case letter, or one beginning
  with `_`
- **THEN** the request is rejected with HTTP 422

#### Scenario: A name is not renamable

- **WHEN** a caller attempts to change a registered pipeline's `name`
- **THEN** the stored name is unchanged

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

**`evaluator_name` and `evaluator_version`** are members of no kind and SHALL be refused at the
binding with **400** on either kind.

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

### Requirement: Pipeline listing carries the registry's filters, envelope and ordering

`GET /v1/pipelines` SHALL return an object envelope — `{"pipelines": [ … ]}` — never a bare array, so
counts, cursors and metadata can be added without breaking a client.

The listing SHALL accept four independent, all-optional filters, applied together: `target` (the
table a pipeline writes), `kind`, `enabled`, and `updated_since` (pipelines whose declaration
changed at or after an instant). Omitting a filter SHALL mean "do not filter on it".

`enabled` SHALL be bound **strictly**: exactly `true` or `false`. Any other value, including an empty
one, SHALL be rejected with HTTP 400 rather than treated as absent. A parameter that selects which
subset is returned cannot be lenient — a caller asking for the enabled subset and receiving the whole
registry would act on pipelines it deliberately excluded.

The listing SHALL be ordered `updated_at` ascending, then `name` ascending. The tiebreaker is
required rather than cosmetic: `updated_at` is stored at limited precision, so pipelines written in
one transaction share it, and without a deterministic secondary key two calls over an unchanged
registry would return them in different orders — which a consumer diffing or paging the feed would
read as a change. Every listing SHALL use this one ordering, so a consumer's ordering guarantee does
not depend on which filters it passed.

Each entry SHALL carry the pipeline's change token `generation`, which SHALL be a declaration fact
rather than a member of runtime `state`: an out-of-process consumer detects a re-declaration by
comparing it against the token it last saw, so it cannot live behind a projection that a consumer
might not read.

#### Scenario: The listing is an envelope

- **WHEN** `GET /v1/pipelines` is called
- **THEN** the response body is an object carrying the pipelines under a named member, not a bare
  array

#### Scenario: Filters combine

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true` is called
- **THEN** only enabled pipelines of that kind are returned

#### Scenario: A non-boolean enabled value is a bad request

- **WHEN** `GET /v1/pipelines?enabled=yes` or `GET /v1/pipelines?enabled=` is called
- **THEN** the response is HTTP 400, not the unfiltered registry

#### Scenario: Ordering is stable across calls

- **WHEN** `GET /v1/pipelines` is called twice over an unchanged registry containing two pipelines
  whose `updated_at` values are identical
- **THEN** both responses order them identically, by `name` within the shared `updated_at`

#### Scenario: A change-detection consumer sees the change token

- **WHEN** a pipeline's declaration is patched and `GET /v1/pipelines` is called
- **THEN** the entry's `generation` differs from the value returned before the patch

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

### Requirement: A pipeline read carries no row values

A pipeline read SHALL disclose only declaration, counts, and timestamps — never a value copied from a
row of the input. This is what makes the read safe for a caller that may not see `sensitive` columns,
and it SHALL be maintained as an invariant of the response shape rather than restored by filtering the
response per caller. No field of a pipeline read is redacted by role.

Two response fields are the ones this invariant is about, because each holds a literal value of an
input column rather than a declaration: `state.cursor_identity` (and its counterparts
`state.materialized_through_identity` and `state.clamp.cursor_identity`), which carry a value of the
input's scan `identity_column` as the keyset tiebreaker of the scan position; and `state.last_error`,
which carries a runtime failure string.

The identity-valued fields are safe **by a rule of another capability**, not by luck: the
`table-management` capability requires that neither scan cursor column may be marked `sensitive` —
refused at metadata declaration and refused again on a metadata patch, precisely so the scan may
project and page by them. A `sensitive` value can therefore never reach `cursor_identity`. This
cross-capability dependency SHALL be treated as load-bearing for pipeline-read authorization: were the
cursor-column rule ever relaxed, this read would need per-caller redaction of those fields, and the two
requirements SHALL be changed together.

`state.last_error` SHALL continue to be reported verbatim to any caller holding `REGISTRY_READ`.
A failure string is diagnostic output, not a projection of the input, and truncating or scrubbing it
would remove the only reason the field exists. ADAS SHALL NOT itself compose input row content into
the message.

The stored message is, however, the **upstream** failure verbatim — for a refresh, the exception the
database driver raised — and such a message may quote a value from the row that failed. A scheduled
refresh runs under an elevated system identity, so it may touch a `sensitive` column that the reading
caller could not query. This is a stated, accepted residual of reporting the field at all rather than a
property this requirement establishes: the alternative is a pipeline whose stalls are undiagnosable by
the role whose purpose is to observe them. A change that wants it closed SHALL close it where the
message is stored (`PipelineStateWriter.recordFailure`), not by filtering the read, so the value never
reaches the database rather than reaching it and being hidden per caller.

#### Scenario: The identity value is reported to a read-only admin

- **WHEN** a caller holding only `READ_ONLY_ADMIN` reads a pipeline that has run at least once
- **THEN** `state.cursor_identity` is present and carries the stored value, because the input's scan
  `identity_column` cannot be a `sensitive` column

#### Scenario: A cursor column cannot be made sensitive after the fact

- **WHEN** a `FULL_ADMIN` caller patches the input table's schema to mark its `identity_column` or
  `version_column` `sensitive`, so that a subsequent pipeline read would disclose a sensitive value
- **THEN** the patch is rejected with HTTP 422 by the `table-management` cursor-column rule, and the
  pipeline read's invariant is preserved without the read itself filtering anything

#### Scenario: A failure string is visible without full admin

- **WHEN** a caller holding only `READ_ONLY_ADMIN` reads a pipeline whose last run failed
- **THEN** `state.last_error` is present and carries the failure verbatim, so a stall is diagnosable
  from the role whose purpose is to observe one

### Requirement: Freshness diagnostics are best-effort and caller-independent

A pipeline read's freshness diagnostics SHALL be best-effort and SHALL NOT depend on the caller:
`state.unclamped_reads` and `state.rebuild_required` SHALL degrade to empty rather than fail the read,
and SHALL report the same content to every caller entitled to make the read.

Establishing them means resolving which enrichments a stored declaration reaches. That resolution SHALL
be made from the declaration's own qualified column tokens against raw catalog rows, for **both** kinds,
and SHALL NOT re-run the SQL front end over the declaration or route it through the sensitive-column
entitlement gate. Two properties depend on this and neither is incidental:

- The read stays **role-invariant**, which is what allows a pipeline read to apply no per-caller
  filtering to any field. Were the resolution gated, a caller not entitled to a column the `filter`
  names would silently receive an empty report where an entitled caller receives a populated one.
- The degradation actually **works**. A gated resolution refuses by throwing, and the resolver is
  transactional, so its throw marks the state read's own transaction rollback-only: the failure is
  swallowed and the commit then fails, answering the read with HTTP 500 rather than the degraded report
  this requirement promises. A resolution that cannot throw is what makes "degrade, don't fail" true
  rather than merely intended.

A declaration whose input no longer resolves SHALL contribute no diagnostics rather than failing the
read, for either kind: the drift is already reported through `state.last_error` or by the next run, and
refusing the read would withhold the very state being asked for.

#### Scenario: A read-only admin sees the same diagnostics as a full admin

- **WHEN** a caller holding only `REGISTRY_READ` and one holding every privilege each read an
  `aggregate`
  pipeline whose `filter` references a `sensitive` column
- **THEN** both responses carry identical freshness diagnostics, and neither is refused

#### Scenario: A sensitive filter does not fail the read

- **WHEN** a caller holding only `REGISTRY_READ` reads an `aggregate` pipeline whose `filter`
  references a `sensitive` column
- **THEN** the read succeeds with HTTP 200 and the whole declaration, including the `filter` text naming
  that column — not HTTP 500 and not HTTP 403

#### Scenario: An enrich pipeline resolves the same way

- **WHEN** any entitled caller reads an `enrich` pipeline that reads an unclampable enrichment
- **THEN** `state.unclamped_reads` reports that enrichment, resolved by the same token-based path the
  `aggregate` kind uses

#### Scenario: A drifted declaration degrades rather than failing the read

- **WHEN** any caller reads a pipeline whose stored declaration no longer resolves against its
  input's current schema
- **THEN** the read succeeds with the declaration and stored state, and reports no freshness
  diagnostics

### Requirement: Pipeline errors use the shared error contract

Every `/v1/pipelines` failure SHALL use the `ErrorView` contract with a stable machine-readable
`error` code, and one set of codes SHALL serve every kind. A code is what a client switches on, so
the registry SHALL NOT report the same condition under two codes according to which kind produced it.

The mapping SHALL be: an unknown pipeline is 404 `unknown_pipeline`; a declaration, governance or
identity violation is 422 `pipeline_validation_failed`; a state conflict is 409 `pipeline_conflict` —
covering a target that already has a pipeline, running a disabled pipeline, and editing an enabled
pipeline's declaration where its kind forbids it; and an authorization refusal on a pipeline-scoped
data-plane call is 403 `pipeline_access_denied`. A state conflict SHALL NOT be reported under a
validation code: the declaration sent may be perfectly valid and only the timing wrong.

A sensitive-column refusal SHALL keep its own 403 code and SHALL NOT be flattened into a validation
failure. A preview refused for exceeding its resource budget SHALL carry its **own** code,
`pipeline_preview_budget_exceeded`, distinct from both the validation failure and an outage, and SHALL
carry it for **either kind** — whichever configured ceilings bounded the read, and for every statement a
preview issues, including the reads it makes only to explain an empty sample or choose a group. The `aggregate` sample
runs under `aggregate.preview.*` and an `enrich` sample under `pipeline.budget.*`, but the condition a
client switches on is the same, "this preview was too expensive", and its remedy is the author's, not
the runner's.

ClickHouse and driver internals SHALL NOT be echoed; a ClickHouse outage during any pipeline read or
write SHALL surface as 503.

A rejection the registry's own rules define SHALL be reported by those rules rather than by request
deserialization. A value that is part of a declaration's vocabulary but not implemented — an
unsupported `kind`, an unimplemented freshness or trigger mode, a bucket unit outside the supported
set — SHALL be accepted by the binding and refused by validation, so the response is the 422 above
with a message naming the alternatives, never the generic 400 an unbindable value produces.

Validation shared with other capabilities — notably the `filter` grammar, which the enrichment
declaration also uses — SHALL be re-attributed to the pipeline code rather than reported under the
sharing capability's, since a borrowed code points at an object that does not exist. The label the
shared analysis reports SHALL name the kind whose declaration was being validated, so a message
identifies which declaration was refused.

#### Scenario: Unknown pipeline

- **WHEN** any `/v1/pipelines/{name}` endpoint is called for a name not in the registry
- **THEN** the response is 404 `unknown_pipeline`, whatever the kind of pipeline the caller expected

#### Scenario: A conflict is not reported as a validation failure

- **WHEN** a registration names a target that already has a pipeline, of either kind
- **THEN** the response is 409 `pipeline_conflict`

#### Scenario: A declared-but-unimplemented value reports under the pipeline code

- **WHEN** `POST /v1/pipelines` names an unimplemented `kind`, or an unimplemented freshness mode
- **THEN** the response is 422 `pipeline_validation_failed` with a message naming the implemented
  alternatives, not a generic deserialization 400

#### Scenario: An invalid filter reports under the pipeline error code

- **WHEN** a pipeline of either kind is registered with a `filter` that does not parse under the
  bounded grammar
- **THEN** the response is 422 `pipeline_validation_failed`, and the message names the kind whose
  declaration was refused

#### Scenario: A data-plane authorization refusal has its own code

- **WHEN** a caller without the target's write grant invokes a pipeline-scoped data-plane endpoint
- **THEN** the response is 403 `pipeline_access_denied`

#### Scenario: ClickHouse unavailable

- **WHEN** a pipeline read or write is attempted while ClickHouse is unreachable
- **THEN** the response is 503 with the shared contract and no raw driver text

#### Scenario: A budget refusal is distinguishable from an outage

- **WHEN** a preview is refused for exceeding its resource budget
- **THEN** the response is 422 with the budget-exceeded code and a message naming the exceeded
  budget, never the 503 `clickhouse_unavailable` an unreachable server produces

#### Scenario: An enrich preview over its budget reports the preview budget code

- **WHEN** an `enrich` preview's sample read exceeds a `pipeline.budget.*` ceiling
- **THEN** the response is HTTP 422 `pipeline_preview_budget_exceeded`, not
  `pipeline_read_budget_exceeded`, with a message naming the ceiling exceeded and its configured value
- **AND** the same read issued by the runner through the scan or group fetch endpoint still refuses
  with `pipeline_read_budget_exceeded`

### Requirement: A pipeline read selects an authored or a compiled projection

The system SHALL accept a `view` query parameter on `GET /v1/pipelines` and `GET
/v1/pipelines/{name}`, selecting which projection of one stored pipeline is returned:

- **`compiled`** — the resolved, executable form: for an `enrich` pipeline its resolved
  `response_schema` (stored or composed), its derived output mapping, its resolved read source and its
  `version_column`.
- **`source`** — the authored declaration: what the caller wrote, with derived and resolved values
  omitted rather than filled in.

`source` SHALL be the **default**, because every reader that is not the out-of-process runner wants
the authored form — an operator, a management client, a UI — and because a default carrying derived
values invites a read-modify-write client to re-declare them, laundering a resolved read source into
a declared one. The runner SHALL pass `view=compiled` explicitly; it is one known client shipping in
the same release, not a population to be defaulted for.

The **transform is authored, not derived**, so it SHALL appear in **both** projections as the
`transform` block: `type`, `model`, `preset`, `params`, `request_template`, `inputs` and `outputs` — or,
for a declaration folded from an evaluator version registered before that shape, `output_vars` and a
stored `response_schema` — are members of the authored declaration, and a client SHALL be able to read
them back with `view=source` and send them straight back as its next patch **unchanged**. That round trip
is a requirement rather than a convenience: the legacy members are the only copy of a folded
declaration's outputs and of a live prompt's stored schema, and a projection that omitted them would
make a faithful read-modify-write impossible.

One member is carved out of "both": **`transform.response_schema` SHALL be served on `view=source` only**
and SHALL be absent from `view=compiled`. The carve-out is not an exception to the round-trip rule, which
is stated over the authored projection — a read-modify-write client reads `view=source`, where the member
is present. It exists because the compiled projection already carries the same fact as the derived
top-level `response_schema`: for a declaration that stores one, the resolved schema *is* the stored
schema, so serving both would ship a multi-kilobyte object twice in every feed entry.

The round trip SHALL be understood over the **declaration** members, not over the whole response body.
The authored projection also carries server-owned members a write does not accept — `generation`,
`updated_at` and the `state` block — so "send it straight back" means the declaration a patch is allowed
to supply, with those stripped, and `name` and `kind` restated inertly under the rule that already
permits that. Stating the scope is what keeps the requirement testable: asserted over the whole body it
is false by construction, and a test written to the absolute form would fail on `generation`.

The transform SHALL be served under **one member name only**. It SHALL NOT be flattened onto the
declaration's top level, because `outputs` is already the name of the *derived* output mapping there —
a JSON array, one entry per mapped target column, `{name, column, jsonata?}` — while the authored
outputs are a JSON object keyed by target column name whose values are prose or expression strings.
An array and an object under one member name, differing by projection, is the exact failure the
one-spelling rule below exists to prevent, and it would let a client reading `view=compiled` post the
derived array back as the authored map.

What the compiled projection adds on top of the authored one is resolution only: the
`response_schema` the model is held to — the declaration's stored one, or the schema composed from the
target and its `outputs` — and the output mapping resolved to physical columns.

The resolved schema SHALL be served as a **derived top-level `response_schema`**, never inside the
`transform` block. The block is the authored declaration, and `transform.response_schema` means "the
schema this declaration stores"; serving a *composed* schema under that name on one projection would let
a read-modify-write client on `view=compiled` post it back as a stored one, converting a schema that
tracks the target into a frozen copy of it without the operator ever deciding to. The relationship is
the one the authored map and the derived array already have: `transform.outputs` is what was written and
`outputs` is what was resolved, and `transform.response_schema` is what was stored while
`response_schema` is what will be used.

The two projections SHALL differ in **resolution only, never in naming**. A fact SHALL have exactly
one spelling across the request surface, both projections and the stored declaration; no member of
either projection SHALL exist under a second name for the benefit of a particular consumer. In
particular the compiled projection SHALL NOT carry `input_bindings`, `output_bindings`, `cadence`,
`batch_scan_limit`, `batch_chunk` or `sampling`.

The rule SHALL hold with **no enumerated exception**; in particular the transform SHALL NOT be served a
second time as an `evaluator` object. The guarantee is that the compiled projection carries exactly
the authored vocabulary plus the derived members, asserted as a set difference against the authored
projection rather than as a diff against a captured response.

The derived members SHALL be exactly: `response_schema`, `grain_key`, `version_column`, the resolved
`inputs`, and `outputs`. The transform arrives as an **authored** member. The set is exactly five
because the contract version travels as a **response header** rather than a body member
(see "Every pipeline response carries the runner contract version").

**None of the five SHALL be accepted on a write.** They are unknown members of both request records, so
each is a binding HTTP 400 naming the field, on either verb — and this SHALL be stated rather than left
to follow, because `response_schema` is the one member whose name now exists in two positions. Posting a
`view=compiled` body back therefore fails on the derived member rather than laundering a composed schema
into `transform.response_schema`, which is the whole reason the composed schema is served at the top
level instead of inside the block.

#### Scenario: A derived member sent on a write is refused at the binding

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries a top-level `response_schema`,
  `grain_key`, `version_column` or `outputs` — for instance a `view=compiled` body sent back unchanged
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is
  persisted

Every fact about the transform SHALL be readable on the entry under one spelling each — the pipeline's
`name`, `generation` and `updated_at`, the `transform`'s own members, and the derived top-level `response_schema` — so
no consumer needs a second lookup.

`outputs` SHALL be one ordered entry per mapped target column, carrying `name`, `column` and an
optional `jsonata`. It replaces the pair `output_bindings` + the declaration's `output_vars`, which a
consumer had to join by name in order to use either. `name` SHALL be the model response property —
the target column's **exposed** name, which is what the composed response schema declares — and
`column` SHALL be the column's **physical** spelling, which is what the write path stores. The two
SHALL remain separate members even where they are equal, because a target may expose a column under
a name it does not store it under, and a consumer that inferred one from the other would write to a
column that does not exist or read a response property that was never declared. `jsonata` SHALL be
absent rather than an identity expression when the value is taken straight from `response[name]`.

No projection SHALL serve `input_vars` or `output_vars` as a derived member: what the transform
receives is `transform.inputs` and where its answers land is the entry's `outputs`, so serving either
would restore the two-lists-joined-by-name shape `outputs` exists to collapse. This SHALL NOT extend to
the authored `transform.output_vars`, which is a declaration member a folded legacy declaration carries
and which is served as authored.

Resolution SHALL be performed only for the compiled projection. A listing or read in the authored
projection SHALL NOT pay the lookups that produce derived members, and because every derived member
is driven by the presence of a resolution, not resolving is also what makes it impossible for one to
appear in an authored response.

The **`aggregate`** projection SHALL likewise carry no `freshness`, and SHALL echo `distinct` only
when `true`. An `aggregate` entry SHALL otherwise keep every member it carries today, including the
resolved `column` on a measure whose declaration omitted it.

The two projections SHALL share one response object, with each projection's members left absent
rather than served empty.

An unrecognized `view` value SHALL be rejected with HTTP 400 `bad_request` naming the accepted values,
rather than falling back to a default — the same rule the registry's other filters follow, and for the
same reason: a silently-ignored projection selector answers with a different shape than was asked for.

The message SHALL name the accepted set and SHALL NOT repeat the rejected value. That distinction is what
makes it safe to serve: the system never echoes a binding failure's own text, because the converters'
messages leak type names, field paths and fragments of the input. A message may reach the caller only
when the parameter's own binder authored it, which the binder signals with a dedicated exception type;
every other binding failure SHALL keep answering with the generic bad-request message. The `kind` and
`enabled` filters SHALL be rejected the same way, so the three hand-written filters do not differ in what
they tell a caller.

#### Scenario: The default projection is the authored declaration

- **WHEN** `GET /v1/pipelines/{name}` is called with no `view` parameter
- **THEN** the response is the authored projection, carrying no derived `response_schema`, no
  `grain_key`, no `version_column`, no `outputs` mapping and no resolved `inputs`

#### Scenario: The authored projection carries the transform

- **WHEN** `GET /v1/pipelines/{name}?view=source` is called for an `enrich` pipeline
- **THEN** the response carries a `transform` block with `type`, `model`, `preset`, `params`,
  `request_template`, `inputs` and `outputs` as authored, and no top-level `vars`, so the same body may
  be sent back as the next patch unchanged

#### Scenario: The authored projection carries a folded declaration's legacy members

- **WHEN** `GET /v1/pipelines/{name}?view=source` is called for a pipeline whose transform was folded
  from an evaluator version registered before the `outputs` shape
- **THEN** the response's `transform` carries `output_vars` and its stored `response_schema`, and
  carries no `outputs`

#### Scenario: A write answers with what was written

- **WHEN** a pipeline is registered or patched
- **THEN** the response is the authored projection, so a client may send it back as its next patch
  without having laundered a derived value into a declared one

#### Scenario: An aggregate entry drops its echoed defaults

- **WHEN** `GET /v1/pipelines/{name}` is called for an `aggregate` pipeline in either projection
- **THEN** the response carries no `freshness`, and carries `distinct` only on a measure that declares
  it `true`, while every other member it carries today is unchanged

#### Scenario: The authored projection returns what was written

- **WHEN** `GET /v1/pipelines/{name}?view=source` is called for an `enrich` pipeline
- **THEN** the response carries the declaration's own members — the `transform` (its `inputs` and
  `outputs` included), `advanced`, the stored `inputs` where one was declared — and omits the derived
  output mapping and the derived `response_schema`

#### Scenario: The compiled projection carries one vocabulary

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` is called for an `enrich` pipeline
- **THEN** the execution knobs are served under `advanced` with their authored names, the transform
  inputs under `transform.inputs`, and the response carries no `vars`, `input_bindings`,
  `output_bindings`, `cadence`, `batch_scan_limit`, `batch_chunk` or `sampling`

#### Scenario: The projections differ only by the derived members

- **WHEN** the same `enrich` pipeline is read in both projections
- **THEN** the compiled response's members are exactly the authored response's members plus
  `response_schema`, `grain_key`, `version_column`, `outputs` and the resolved `inputs`, and no entry in
  either projection carries an `evaluator` object

#### Scenario: A composed response schema is not served inside the transform

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` is called for an `enrich` pipeline that stores no
  `response_schema` of its own
- **THEN** the composed schema is served as the top-level `response_schema` and
  `transform.response_schema` is absent, so the body cannot be patched back as a stored schema

#### Scenario: A stored response schema is served once on compiled and verbatim on source

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` is called for a folded legacy declaration that
  stores a `response_schema`
- **THEN** the top-level `response_schema` carries that stored schema as the resolved one and
  `transform.response_schema` is absent, so the schema crosses the feed once rather than twice

#### Scenario: A stored response schema is readable for a read-modify-write

- **WHEN** the same pipeline is read with `view=source`
- **THEN** `transform.response_schema` carries the stored schema as authored, and the body round-trips
  through `PATCH` unchanged

#### Scenario: The authored transform and the derived mapping do not collide

- **WHEN** an `enrich` pipeline is read with `view=compiled`
- **THEN** the authored outputs are carried inside `transform.outputs` and the derived mapping under
  the top-level `outputs`, each in its own shape, and neither is served under the other's name

#### Scenario: The compiled listing needs no registry lookup

- **WHEN** `GET /v1/pipelines?kind=enrich&view=compiled` is called
- **THEN** every entry is built from that pipeline's own stored declaration

#### Scenario: An output carries the exposed name and the physical column separately

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` is called for a pipeline whose target exposes a
  column under a name other than its physical one
- **THEN** the matching `outputs` entry carries the exposed name in `name` and the physical spelling
  in `column`

#### Scenario: An unknown view value is rejected

- **WHEN** `GET /v1/pipelines?view=<value other than compiled or source>` is called
- **THEN** the request is rejected with HTTP 400 `bad_request` naming `compiled` and `source`, the message
  does not contain the rejected value, and no projection is served in place of the unrecognized one

### Requirement: Every pipeline response carries the runner contract version

Every response under `/v1/pipelines`, of any method and any status, SHALL carry the
`Adas-Contract-Version` header naming the contract this build serves. It SHALL be set unconditionally —
on a read, a write, a refusal and an error alike — and SHALL NOT depend on the caller having asked for it.

Because "absent" is the signal, the header SHALL be set at a point every response under the prefix passes
through: **before authentication and before handler resolution**, so that a `401` or a URL-level `403`
written by the security filter chain, and a `404` for a path under the prefix that resolves to no handler,
carry it as well as a response a controller produced. Setting it only where a handler runs would make the
service's most ordinary failure — a wrong or expired API key — indistinguishable from a build that
predates the contract, which is the one distinction this header exists to make.

The header is what lets a client distinguish a current ADAS from one that predates the contract entirely:
an unknown request header is silently ignored by any Spring service, and an unknown *response* header is
the only signal a client can test for that an older build cannot forge. Its absence therefore means "this
ADAS is older than the contract", which is precisely the case a consumer must refuse rather than
interpret. Setting it on the control plane as well as the data plane matters because the feed is the first
call a runner makes after a deploy: it is where the skew can be detected before any work is scheduled,
scanned or paid for.

Requiring the header on a request is a separate matter, specified per endpoint: the control-plane reads
SHALL NOT require it, so an operator, the OpenAPI console and a management client keep reading the registry
unchanged.

#### Scenario: The contract version is present on an ordinary read

- **WHEN** `GET /v1/pipelines` or `GET /v1/pipelines/{name}` is called with no contract header
- **THEN** the response is served normally and carries the `Adas-Contract-Version` header

#### Scenario: The contract version is present on a refusal

- **WHEN** a request under `/v1/pipelines` is answered with an error, including a contract refusal
- **THEN** that response also carries the `Adas-Contract-Version` header, so a client can tell "refused by
  a current build" from "answered by a build that has no contract"

#### Scenario: An unauthenticated call still carries the contract version

- **WHEN** a request under `/v1/pipelines` is rejected by the security filter chain with `401` or a
  URL-level `403`, before any handler runs
- **THEN** that response carries the `Adas-Contract-Version` header, so a caller reads it as an
  authentication failure from a current build rather than as a version skew

A change that reshapes the compiled projection SHALL raise the version this build serves, and the bump is
the whole of that change's obligation to the ordered deploy: a consumer holding the old shape reads the
echo before it interprets the body, finds a version it does not recognise, and refuses the response
rather than rendering a template whose placeholders bind to nothing.

No **second** version SHALL be introduced for the feed read. In particular the compiled read SHALL NOT
grow a `contract` query parameter, SHALL NOT answer 426, and SHALL NOT echo the version as a response
**body** member. The header already carries the echo on the feed, and on the responses a body member
cannot reach — a `401`, a URL-level `403`, an unmapped-path `404` — which is the distinction the header
exists to make. A second integer with the same bump condition would be two spellings of one fact, which
this registry's one-spelling rule forbids; requiring a parameter on the compiled read would contradict
the paragraph above, since `view=compiled` is an operator's and the OpenAPI console's read as well as
the consumer's; and a body echo would falsify the derived-member enumeration below and would have no
clean home on the single read, whose response is the pipeline object itself.

#### Scenario: The contract version reflects a reshaped compiled projection

- **WHEN** a release changes the compiled projection's member set and `GET /v1/pipelines?view=compiled`
  is called
- **THEN** the response's `Adas-Contract-Version` names the raised version, and a consumer built against
  the previous one treats the response as incompatible rather than interpreting its body

#### Scenario: The compiled read requires no contract parameter

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled` is called with no `contract` query
  parameter
- **THEN** the request is served normally and carries the `Adas-Contract-Version` header, because
  requiring a parameter on a control-plane read is what this requirement declines to do

### Requirement: A requested projection is served as requested or refused

A read that asks for the compiled projection SHALL either return a resolved response or be refused; it
SHALL NOT answer with the unresolved object under the compiled name. Because resolution is performed only
where the registry resolves — for the `enrich` kind — the two combinations that cannot be served SHALL be
refused:

- `GET /v1/pipelines?view=compiled` **without** `kind=enrich` SHALL be rejected with HTTP 400
  `bad_request`, naming the combination and that `kind=enrich` is required to compile a listing. Resolving
  a cross-kind listing would cost a per-pipeline lookup for entries that have nothing to resolve, which is
  why the filter is required rather than inferred.
- `GET /v1/pipelines/{name}?view=compiled` for a pipeline whose kind is not `enrich` SHALL be rejected with
  HTTP 422 `pipeline_validation_failed`, naming the pipeline's kind — the same shape as every other
  endpoint that serves one kind.

This extends the existing rule that an unrecognized `view` value is refused rather than defaulted, to a
*recognized* value that this read cannot honour. The reason is the same and the failure is worse: a
silently unresolved response is not an obviously wrong shape but a plausible one, carrying no
`response_schema`, no `grain_key`, no `version_column` and no `outputs` — indistinguishable by a
consumer from an `enrich` pipeline that genuinely has none, and the precise shape that a consumer acting
on it turns into a silent data loss. The **two** refusals above are the only two: there is no third
combination, because the runner contract version travels
as a response header and refuses nothing on a read (see "Every pipeline response carries the runner
contract version"). A read path that quietly disagrees with the read path beside it is the defect this rule
exists to remove, not an optimization to preserve.

#### Scenario: A compiled listing without the enrich filter is refused

- **WHEN** `GET /v1/pipelines?view=compiled` is called without `kind=enrich`
- **THEN** the response is HTTP 400 `bad_request` naming the combination, rather than a 200 carrying
  unresolved entries

#### Scenario: A compiled read of a non-enrich pipeline is refused

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` names an `aggregate` pipeline
- **THEN** the response is HTTP 422 `pipeline_validation_failed` naming the pipeline's kind

#### Scenario: The authored projection is unaffected

- **WHEN** the same reads are made with `view=source` or with no `view` parameter
- **THEN** they are served exactly as before, for every kind

### Requirement: The published contract states a filter's default and offers no unimplemented value first

The published OpenAPI document SHALL state every optional enum query parameter's default
machine-readably, and SHALL order each parameter's accepted values so that no unimplemented value
precedes an implemented one. A default stated only in prose or only in code SHALL NOT be treated as
published.

This exists because a spec-driven consumer never reads the prose. A generated client, an
OpenAPI-rendered filter form, and a `<select>` built from an enum with no empty option all fill an
optional parameter from its **first** accepted value or from its `example`, so the published ordering and
example are the effective default for every such caller. `view` is the case that proved it: `source` is
the default, is documented as the default in the parameter's own description, and is what an absent `view`
resolves to — but the document advertised `{"enum": ["compiled", "source"], "example": "compiled"}`, so
callers that never mentioned `view` sent `view=compiled`. Those callers were answered 200 with authored
declarations rendered under the compiled name for two days, then refused outright once that mis-answer
became a refusal. Neither the callers nor the endpoint were wrong; the published contract was.

The obligation is therefore on the **document**, not on the handler: the in-code default, the accepted
value set and the refusal codes are all already correct and SHALL NOT change. What changes is that a
consumer can derive the default without reading prose written for a human.

A parameter that genuinely has **no** default SHALL NOT publish one. An absent `kind` selects every kind
rather than a particular one, so publishing a `kind` default would narrow a listing that was asked to be
unnarrowed — a worse failure than the one this requirement removes, because it silently drops rows instead
of refusing. Such a parameter SHALL still order its values so an unimplemented kind is not offered ahead
of implemented ones.

#### Scenario: The view parameter publishes its default

- **WHEN** the OpenAPI document is read for `GET /v1/pipelines` or `GET /v1/pipelines/{name}`
- **THEN** each `view` parameter carries a schema default of `source`, and its example is `source`
- **AND** the accepted values are offered with `source` first

#### Scenario: A client that sends no view still gets the authored projection

- **WHEN** either endpoint is called with no `view` parameter
- **THEN** the response is the authored projection, unchanged by this requirement

#### Scenario: The kind filter publishes no default

- **WHEN** the OpenAPI document is read for `GET /v1/pipelines`
- **THEN** the `kind` parameter carries no default, because an absent `kind` returns every kind
- **AND** no unimplemented kind is offered ahead of an implemented one

#### Scenario: The accepted value set is unchanged

- **WHEN** a caller sends `view=compiled` explicitly
- **THEN** it is honoured or refused exactly as before, and an unrecognized `view` is still rejected with
  HTTP 400 `bad_request` naming the accepted values

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

### Requirement: A generation change is logged with its old and new value and its cause

Every change to a pipeline's `generation` SHALL log one INFO line naming the pipeline, the old generation,
the new generation and the cause. The cause is the mutation that bumped it: `register`; a patch, as
`patch(…)` naming what the patch did (`re-declare`, `enable`, `disable`, or several of them, comma-separated); `version_column_repoint` for a
source's version-column repoint; or `target_columns_changed` for a target's column change. A patch
bumps the generation once however many of those it combines, so it logs one line, and a registration or patch that is refused logs none. It SHALL name the acting principal
as `actor=` when the mutation carries one, and SHALL give `actor=-` rather than invent one when it does not, as
with a bump cascaded from a schema edit, or when `security.mode=none`. The field is always present, as the
Enrichment Runner's operator lines always carry `principal=`, so a line without a principal is still parsed the
same way. A bump that touches several pipelines at once
SHALL log one line per pipeline, not only a count.

The register, re-declare and enable lines SHALL also give the pipeline's resulting generation.

The generation qualifies the materialized position, the runner's scan state and every row's
`pipeline_generation` stamp. A bump nobody can see explains a rollup freeze or a runner re-scan only
after a database read.

#### Scenario: A re-declaration logs the bump

- **WHEN** an operator re-declares a pipeline at generation 4
- **THEN** one INFO line reports the pipeline, `4 -> 5`, the cause `patch(re-declare)`, and the operator when one
  is authenticated

#### Scenario: A cascaded bump names each pipeline

- **WHEN** a source's version column is repointed and three pipelines reading it are bumped
- **THEN** three INFO lines are logged, each with that pipeline's old and new generation, the cause and
  `actor=-`

### Requirement: Imported pipelines are registered disabled through the registration path

A catalog import SHALL register each pipeline through the same registration step as `POST /v1/pipelines`, from its `view=source`
projection, and SHALL always register it disabled, as defined by [catalog-export-import](../catalog-export-import/spec.md) ("Pipelines
are imported disabled"). Arming an imported pipeline is the ordinary `PATCH enabled=true` and runs the declaration gate described
above. An import never patches or replaces an existing pipeline. A pipeline name that has a delete revision in the audit trail and
was once enabled is refused unless the import acknowledges it, because the Enrichment Runner's state is keyed by name ("Re-used
pipeline name guard").

#### Scenario: An imported pipeline is armed separately

- **WHEN** a pipeline is created by a catalog import
- **THEN** it is stored with `enabled: false` at generation 1, and only a later `PATCH enabled=true` arms it

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

### Requirement: The dry-run preview answers in one shape for both kinds

`POST /v1/pipelines/{name}/preview` SHALL answer with one response shape for every kind it serves. That
shape SHALL carry only what a preview alone can know: the sample, the typed columns of that sample,
the declaration as a query a caller can run through the query API where there is one, the number of
rows an `aggregate` target will hold, and coded notes about it.

A preview SHALL NOT echo any member of the declaration that `GET /v1/pipelines/{name}` serves, whether
authored or derived:

- group keys and measures;
- the transform and its type;
- the response schema and its source;
- the output mapping and the input bindings.

The reasoning:

- An author reaches preview from the pipeline they just wrote, so a client already holds the declaration
  and renders the preview beside it.
- The gate derives the derived members on every declaration write, so they are already on the read:
  `GET /v1/pipelines/{name}` serves an `aggregate` pipeline's expanded `group_by` and resolved measure
  columns, and `GET /v1/pipelines/{name}?view=compiled` serves an `enrich` pipeline's resolved
  `response_schema` and output mapping.
- Every echoed member is one more place where two answers to the same question can disagree.
- It is payload the reader has to wade through to reach the sample.

The response SHALL carry these members:

- **`rows`**: the sample rows. Always present, and `[]` when no row was sampled. Each row is keyed by
  column name.
- **`columns`**: one entry per key of a row, in row order. Always present, including when `rows` is
  empty, so a client can render the sample's header either way. A helper expression the sampling
  statement projects only for the preview's own checks (an ordering value, a window count) SHALL NOT
  appear in `columns` or `rows`. Each entry carries:
  - `name`.
  - `role`: `key` or `measure` on an `aggregate`; `grain_key` and `output` on a Class-1 `sql` pipeline;
    `grain_key` and `input` on a row-grain `llm` one; `input` alone on a `group` one, whose group is
    named once by `group_key` rather than repeated on every member.
  - `type`: the `QueryFieldType` code of the value as sampled.
  - `target_type`: the type of the target column the value is written to. Present for `key`,
    `measure` and `output`; absent for `grain_key` and `input`.
  - `max_bytes`: the largest UTF-8 size, in bytes, of this column's non-null values across the sampled
    rows, measured **before** any value is capped. A `string` value counts as its text and an `object`,
    `array` or `map` value as its compact JSON serialization, before the response's own JSON string escaping. Present
    for `string`, `object`, `array` and `map` columns that sampled at least one non-null value, and
    absent otherwise (including for `uuid` and `enum`, whose width is fixed or small). On an `input`
    column it is the author's hint at what one row or member costs the model.
- **`total_rows`** (`aggregate` only): the number of groups the declaration produces over the whole
  input, which is the number of rows the target holds once the pipeline has caught up. It SHALL be
  counted by the sampling statement that produced `rows`, before its `LIMIT`, and not by a second read.
  The portable `statement` does not reproduce it. It is
  `0`, present rather than absent, when the input yields no group. The preview SHALL carry no row-count
  or "more rows" member on the `enrich` kind, where a count would only measure the source table.
  Preview has no pagination, so a "more rows exist" flag would give a client nothing to act on.
- **`statement`**: the declaration as one `SELECT` in the **ADAS SQL dialect**, the subset
  `POST /v1/queries/execute-sql` accepts, with its values inlined. A caller SHALL be able to send it
  unchanged to that endpoint, or open it in a query builder, and have it execute. It SHALL be written
  against the catalog: it reads the pipeline's input entity by its catalog name, names that entity's
  exposed fields (an enrichment column by its exposed name, with no join), and names every output
  column by the target column it is written to. It SHALL NOT carry:
  - a physical table or column name that the entity does not expose, or a join;
  - an engine-only function, an internal alias (such as a positional `__c<i>`), or a `SETTINGS` or
    `FORMAT` clause;
  - a helper expression the sampling statement projects only for the preview's own checks.

  The statement ADAS executes to produce `rows` is a different, physical statement. It is not returned,
  and it is logged at `DEBUG` on the SQL log as a refresh statement is. Each kind states which parts of
  the sample the portable statement reproduces and where its rows can differ from `rows`.
  - Present for an `aggregate` pipeline and a Class-1 `sql` one, unless the query path refuses the
    rendered query (see `statement_not_portable`).
  - Absent for an `llm` pipeline, whose sample is read through the governed scan or group assembly and
    has no single statement.
- **`query`**: the same query as a structured-query DSL document, accepted as is by
  `POST /v1/queries/execute`. Present exactly when `statement` is. It SHALL be the document the SQL front
  end reads `statement` as, so `POST /v1/queries/translate-sql` on `statement` answers exactly `query`,
  with the defaults the front end spells out (`final`, the null ordering of a sort) spelled out alike.
  `query` and `statement` are renderings of the declaration for running elsewhere, not echoes of its
  members, so carrying them does not conflict with the rule above.
- Running `statement` or `query` SHALL go through the governed query path and grant nothing beyond what
  the caller already holds: table grants, sensitive-column clearance, row policies, the query budget and
  the row limits all apply as they do to any other query. A preview SHALL render both against the
  caller's own column visibility, so neither names a column the caller cannot see.
- **`group_key`**: the group the sample describes, as a string, for a `group` pipeline only, whether
  the caller named it or the preview chose it.
- **`notes`**: always present, and `[]` on an unremarkable preview. Each note carries a stable `code`, a
  `severity` (`info` or `warning`), a `subject` naming the column, output or enrichment it is about
  (present exactly for the codes whose meaning below names one), and a prose `message`. A client SHALL
  be able to switch on `code`; `message` is for people. A code that names a subject is emitted once per
  subject.

**Every value in `rows` SHALL be capped at 2048 bytes.** A body column runs to tens of kilobytes per
row (a usage-log body is commonly tens of KB), so five member rows of two body columns make
a response of megabytes that a client cannot usefully render. The cap SHALL apply on every kind, because
an aggregate's `group_uniq_array` measure or a Class-1 extraction can be just as wide.

- A `string` value over the cap SHALL be cut at a Unicode code point boundary (never inside a UTF-8
  sequence or a surrogate pair; a grapheme cluster may be split) and end in `…`, so that the cell,
  `…` included, is at most 2048 bytes.
- An `object`, `array` or `map` value whose JSON serialization exceeds the cap SHALL be replaced by that
  serialization, cut the same way. The cell then holds a string, and the column's `type` still names
  the value's real type.
- Every value at or under the cap SHALL be returned unchanged.

The cap shortens what is **returned**, never what is **read**. Every check, note and count (including
`max_bytes`, `value_out_of_target_range` and `total_rows`) SHALL be computed from the full values. The
cap is a constant, like the `enrich` sample's row cap, and not a configured knob: raising it would
rebuild the response this cap exists to avoid.

A note SHALL never stand in for a refusal. A declaration that cannot be armed is refused with an error,
and a note describes a declaration that passed every check.

The note codes SHALL be the closed set below. A new code SHALL be added only by amending this list.

| `code` | `severity` | Kinds | Meaning |
|---|---|---|---|
| `no_matching_rows` | warning | all | The sample is empty because no input row matches the declaration's `filter` (and `from`/`to`, where given), or, for a named `group_key`, because that group has no member that does |
| `held_by_enrichment` | warning | all | An enrichment the declaration reads withholds every matching input row (it has no freshness bound yet, or, on a clamped `llm` read, every matching row lies above its bound), so an enabled run would write nothing yet; `subject` names it |
| `enrichment_behind` | info | `aggregate`, Class-1, row-grain `llm` | Matching input rows lie above an enrichment's freshness bound. On `aggregate` and Class-1, which sample unclamped, those rows read the enrichment's columns as unmatched (NULL, or the type default for a non-nullable column); on row-grain `llm`, whose read is clamped, they are left out of the sample. The message gives the gap; `subject` names the enrichment |
| `target_type_narrowing` | warning | `aggregate`, Class-1 | A column's sampled `type` is wider than its `target_type`, so a value outside the target's range would be coerced silently on insert; `subject` names the column |
| `value_out_of_target_range` | warning | `aggregate`, Class-1 | A sampled value cannot be held by the target column's physical type, so the insert would wrap or clamp it silently (`integer`, `long`, `date`, `timestamp`) or the refresh would fail with a decimal overflow (`decimal`); `subject` names the column |
| `no_group_found` | warning | `group` | No `group_key` was named and no matching group with a non-empty key exists to choose |
| `group_cut_by_fetch_limit` | warning | `group` | The sampled group has more members than the runner's group fetch would deliver under its row cap, beyond any `member_select` narrowing the declaration asks for; the message names the cap and both counts |
| `output_without_description` | info | `llm` | An output composed no `description`; `subject` names the output |
| `unconstrained_response` | warning | `llm` | The declaration composes no response schema, so the model's answer is held to none |
| `values_truncated` | info | all | One or more of a column's sampled values exceeded the value cap and were shortened in `rows`; the message gives how many and the cap; `subject` names the column |
| `statement_not_portable` | info | `aggregate`, Class-1 | The declaration has no statement the query API would run for this caller (inexpressible in the ADAS SQL dialect or structured-query DSL, or refused by the query path's validation), so `statement` and `query` are absent; the sample is unaffected, and the message gives the query path's reason; `subject` names an output whose target name shadows an input field, where there is one |

**Samples SHALL be newest first.** Ordering by key or by oldest input put test traffic and months-old
rows in front of the author. Most of a
sample could be such groups, with identical rows among them. The newest input is what an author who has just changed a declaration needs to judge,
and it is what the pipeline will process once enabled. Each kind states what "newest" orders by. On a
`group` pipeline "newest" chooses the group; its members keep the order the group assembly delivers
them in, because that is the order the model receives.

#### Scenario: Both kinds answer in the same shape

- **WHEN** `POST /v1/pipelines/{name}/preview` succeeds for an `aggregate` pipeline, and for an
  `enrich` one
- **THEN** both responses carry `columns`, `rows` and `notes` at the top level, and only the
  `aggregate` one carries `total_rows`
- **AND** neither carries `group_by`, `measures`, `transform`, `response_schema`,
  `response_schema_source`, `inputs`, `outputs`, or an `enrich` member

#### Scenario: Columns describe an empty sample

- **WHEN** a preview samples no rows
- **THEN** `rows` is `[]` and `columns` still lists each column the sample would have carried, with
  its role and types
- **AND** `notes` carries a code saying why the sample is empty

#### Scenario: A group sample its members could not be read back for is not blamed on the filter

- **GIVEN** a `group` sample whose member selection chose members, none of whose bound input columns the
  member read then found by their lookup key (the source changed between the two reads)
- **WHEN** it is previewed
- **THEN** `rows` is `[]` and `notes` carries no `no_matching_rows`, since no code in the closed set
  describes the condition and that one would send the author to a filter that is not the cause

#### Scenario: An aggregate preview reports how many rows the target will hold

- **GIVEN** an `aggregate` pipeline whose declaration produces more groups over its input than the
  preview's row cap
- **WHEN** it is previewed
- **THEN** `rows` holds exactly the cap and `total_rows` is the number of groups over the whole input
- **AND** no statement other than the sample's own was issued to count them

#### Scenario: An empty aggregate sample reports zero rows

- **WHEN** an `aggregate` preview's input yields no group
- **THEN** `total_rows` is present and `0`

#### Scenario: The preview statement runs through the query API

- **WHEN** a preview of an `aggregate` or Class-1 pipeline returns a sample, and the caller holds
  `TABLE_READ` on the pipeline's input
- **THEN** `POST /v1/queries/execute-sql` with `statement` as its `sql` answers HTTP 200, with one column
  per entry of `columns`, under the same names
- **AND** `POST /v1/queries/execute` with `query` as its body answers the same columns

#### Scenario: The preview statement carries no physical detail

- **WHEN** a preview of an `aggregate` pipeline that reads an enrichment column returns a sample
- **THEN** `statement` reads the input entity by its catalog name and the enrichment column by its exposed
  name, with no `JOIN`
- **AND** it carries no `SETTINGS` clause, no alias beginning with `__`, and no helper column
- **AND** `columns` and `rows` carry none of the helper columns the executed sampling statement projects

#### Scenario: Statement and query describe the same query

- **WHEN** a preview returns `statement` and `query`
- **THEN** `POST /v1/queries/translate-sql` on `statement` answers a `query` equal to the preview's

#### Scenario: Copying the statement grants no access

- **GIVEN** a preview's `statement`, and a caller who holds `TABLE_GET` but no `TABLE_READ` on the pipeline's
  input
- **WHEN** that caller sends the statement to `POST /v1/queries/execute-sql`
- **THEN** the request is refused as any query over a table the caller may not read is: HTTP 400
  `bad_request` reporting an unknown relation

#### Scenario: A declaration the dialect cannot express previews without a statement

- **GIVEN** an `aggregate` pipeline whose declaration the ADAS SQL dialect cannot express
- **WHEN** it is previewed
- **THEN** the preview succeeds with its sample, `columns` and `total_rows`
- **AND** `statement` and `query` are absent, and `notes` carries `statement_not_portable` whose message
  gives the reason

#### Scenario: A large value is capped and reported

- **GIVEN** a pipeline whose sampled rows carry a body column with a 90 KB value
- **WHEN** it is previewed
- **THEN** that cell holds at most 2048 bytes: the value's leading bytes, cut at a code point boundary,
  followed by `…`
- **AND** the column's `max_bytes` is the full value's size
- **AND** `notes` carries `values_truncated` whose `subject` names the column
- **AND** a column whose values all fit carries no such note and holds its values unchanged

#### Scenario: Notes are coded

- **WHEN** a preview carries a note
- **THEN** the note carries a `code` from the closed set, a `severity` of `info` or `warning`, and a
  `message`
- **AND** a `subject` wherever the code names a column or an enrichment

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
