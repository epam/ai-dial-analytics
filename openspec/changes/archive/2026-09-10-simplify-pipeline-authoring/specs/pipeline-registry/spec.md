## MODIFIED Requirements

### Requirement: Pipeline registry object

The system SHALL maintain a Postgres-backed registry of **pipelines** — declared row-producing
processes, each writing exactly one **target** table. A pipeline SHALL carry a `name` (the registry
key and the `{name}` in the API path), a `kind`, the `target` table it writes, its `inputs`, a
kind-specific declaration, a `trigger`, and an `enabled` flag. A pipeline is **data, not code**:
registering one deploys nothing, and the platform's generic executor runs it.

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
SHALL name its input. An `enrich` pipeline MAY omit it, which SHALL mean "read the target
enrichment's own parent source"; the **compiled** projection SHALL echo the resolved input so a caller
can see what was chosen, and the **authored** projection SHALL echo the stored value — absent when the
pipeline declared none — so that a read fed back into a patch does not promote an inherited input into
a declared one. The input SHALL NOT be the pipeline's own target — a
pipeline that reads what it writes SHALL be rejected with HTTP 422.

Pipeline registration, mutation, and deletion SHALL require the `FULL_ADMIN` authority in `oidc`
mode; per-table access roles SHALL NOT authorize them. **Reading** a pipeline SHALL require any
mapped application role — `FULL_ADMIN` or `READ_ONLY_ADMIN` — and SHALL NOT require `FULL_ADMIN`.
The declaration is the answer to "what populates this table, and is it running", which is the
question the read-only catalog role exists to answer; withholding it while the table catalog,
schema discovery and the evaluator registry are all open to that role made the registry read the
only closed catalog read, without closing anything the role could not already reach. A read
therefore exposes the declaration in full — `filter`, `expression`, measures, bindings, the inlined
resolved evaluator including its prompt, and the **names** of `sensitive` columns the declaration
references. What a read SHALL NOT expose is any `sensitive` column **value**; see **A pipeline read
carries no row values**.

**A read of an historical revision** (`GET /v1/pipelines/{name}/revision/{revision}`) SHALL be
understood to return the stored declaration **re-serialized in the current shape**, not the bytes
that were stored. A member that has since stopped being part of a declaration is therefore silently
absent from a revision read, even though the revision recorded it. This is a stated limitation rather
than a behaviour to rely on; returning the raw stored JSON for a revision read is the fix and is not
part of this change. It is also why the persistence codec's tolerance of unknown stored members is
**permanent**: stored declarations are deliberately not rewritten when the request vocabulary changes.

#### Scenario: Register a pipeline

- **WHEN** `POST /v1/pipelines` is called with a valid declaration of an implemented kind
- **THEN** the response is 201 with the stored pipeline, `enabled: false`

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

#### Scenario: An aggregate pipeline may not omit its input

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with no `inputs`
- **THEN** the request is rejected with HTTP 422

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

- **WHEN** a caller holding only `READ_ONLY_ADMIN` reads an `enrich` pipeline whose resolved evaluator
  carries a prompt template, and whose `filter` references a column marked `sensitive`
- **THEN** the response carries the evaluator's prompt template and the `filter` text naming that
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
`PATCH /v1/pipelines/{name}`. A declaration edit SHALL be re-validated in full, exactly as at
registration, and SHALL be supplied whole: a partial declaration patch SHALL be rejected rather than
merged, since a declaration is validated as one object and a half-applied one has no meaning.

**What counts as a declaration patch SHALL cover every declaration member**, including members that
are optional at registration. A patch carrying only such a member SHALL be treated as a declaration
patch — re-validated, stored and bumped — and SHALL NOT be mistaken for an enable-only patch, which
would discard it while answering success. Conversely a member that is **optional at registration**
SHALL NOT be required by a declaration patch: requiring it would make a pipeline that never declared
it unpatchable, asking for a field the registration said was optional.

**A registration that declares `enabled: true` for a kind whose disabled state is its dry-run gate
SHALL be rejected** with HTTP 422 naming the preview endpoint, rather than silently coerced to
`false`. A field the caller set and the server discarded is precisely the silent mis-answer the
registry's strictness exists to prevent. `enabled: false` and an absent `enabled` SHALL both stay
legal for such a kind — an honest statement is not a mistake. The rejection SHALL apply to
**registration only**: the patch path is the only way such a pipeline is ever armed, and it
re-validates first, so extending the rejection to `PATCH` would make the kind unarmable.

A `disabled` pipeline SHALL NOT write to its target by any route — neither on a schedule, nor
through an operator-triggered refresh, nor through the pipeline-scoped data plane. Were any route to
write anyway, `enabled` would describe only the schedule while the gate it exists to enforce stayed
open, and a target could be filled from a declaration nobody had looked at.

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

#### Scenario: A declaration edit is fully re-validated

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration that violates a governance rule of its
  kind
- **THEN** the request is rejected with HTTP 422 and the stored declaration is unchanged

#### Scenario: Editing an enabled aggregate pipeline's declaration is a conflict

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration for an `aggregate` pipeline that is
  `enabled`
- **THEN** the request is rejected with HTTP 409 and the stored declaration is unchanged

#### Scenario: Editing an enabled enrich pipeline's declaration is permitted

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration for an `enrich` pipeline that is
  `enabled`
- **THEN** the declaration is re-validated, stored, and the change token is bumped, and the
  pipeline's rebuild anchor is left untouched

#### Scenario: A partial declaration patch is refused

- **WHEN** `PATCH /v1/pipelines/{name}` supplies some but not all of a declaration's required members
- **THEN** the request is rejected and the stored declaration is unchanged

#### Scenario: A patch carrying only an optional declaration member is a declaration patch

- **WHEN** `PATCH /v1/pipelines/{name}` carries only a declaration member that is optional at
  registration
- **THEN** the declaration is re-validated in full and stored and the change token is bumped, rather
  than the body being read as an enable-only patch and the member discarded

#### Scenario: A registration declaring enabled true on a dry-run-gated kind is refused

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with `enabled: true`
- **THEN** the request is rejected with HTTP 422 naming `POST /v1/pipelines/{name}/preview`, and
  nothing is persisted

#### Scenario: A registration declaring enabled false is accepted

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with `enabled: false`, or with
  `enabled` absent
- **THEN** the pipeline is created disabled

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
that tells the caller which field they misplaced, which is the whole purpose of the rejection.

A field that **no longer belongs to any kind** SHALL be refused at the binding with HTTP 400 naming
it, exactly as a field that never existed — not accepted for a transitional release and not
cross-checked against a derived value. Accepting a spelling the read never echoes is the same failure
strict request parsing exists to prevent: the request succeeds while doing something other than what
the caller wrote. It follows that a removed **flat** spelling is refused at the binding on **either**
kind, before the cross-kind check is reached, so nothing has to be double-listed and there is no
window in which the flat spelling stops being refused on the kind it never belonged to.

#### Scenario: An aggregate-only field on an enrich declaration is refused

- **WHEN** `POST /v1/pipelines` registers `kind: enrich` carrying aggregate `measures`
- **THEN** the request is rejected with HTTP 422 naming the field, and nothing is persisted

#### Scenario: An enrich-only field on an aggregate declaration is refused

- **WHEN** `POST /v1/pipelines` registers `kind: aggregate` carrying an `evaluator_name`
- **THEN** the request is rejected with HTTP 422 naming the field, and nothing is persisted

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

- **WHEN** `POST /v1/pipelines` registers `kind: aggregate` carrying `advanced.scan_every`
- **THEN** the request is rejected with HTTP 422 naming `advanced.scan_every`, not `advanced`

#### Scenario: A removed flat spelling is refused on either kind

- **WHEN** `POST /v1/pipelines` carries `cadence` on `kind: enrich` or on `kind: aggregate`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, before any cross-kind
  check is reached

## ADDED Requirements

### Requirement: A pipeline read selects an authored or a compiled projection

The system SHALL accept a `view` query parameter on `GET /v1/pipelines` and `GET
/v1/pipelines/{name}`, selecting which projection of one stored pipeline is returned:

- **`compiled`** — the resolved, executable form: for an `enrich` pipeline its resolved evaluator
  version with its response schema (stored or composed), its derived output mapping, its resolved read
  source and its `version_column`.
- **`source`** — the authored declaration: what the caller wrote, with derived and resolved values
  omitted rather than filled in.

`source` SHALL be the **default**, because every reader that is not the out-of-process runner wants
the authored form — an operator, a management client, a UI — and because a default carrying derived
values invites a read-modify-write client to re-declare them, laundering a resolved read source into
a declared one. The runner SHALL pass `view=compiled` explicitly; it is one known client shipping in
the same release, not a population to be defaulted for.

The two projections SHALL differ in **resolution only, never in naming**. A fact SHALL have exactly
one spelling across the request surface, both projections and the stored declaration; no member of
either projection SHALL exist under a second name for the benefit of a particular consumer. In
particular the compiled projection SHALL NOT carry `input_bindings`, `output_bindings`, `cadence`,
`batch_scan_limit`, `batch_chunk` or `sampling`.

This replaces an earlier requirement that the compiled projection be **byte-identical** to the
response served before the declaration surface was shrunk. That requirement existed to let a runner
deployed before this change keep reading the feed unchanged across a rolling deploy; it is withdrawn
because the runner is updated in the same maintenance window, with its own deployment scaled to zero
between the two updates, so no such reader exists. Nothing weaker replaces it: the guarantee is now
that the compiled projection carries exactly the authored vocabulary plus the derived members, which
is asserted as a set difference against the authored projection rather than as a diff against a
captured response.

The derived members SHALL be exactly: the inlined resolved evaluator, `grain_key`, `version_column`,
the resolved `inputs`, and `outputs`.

`outputs` SHALL be one ordered entry per mapped target column, carrying `name`, `column` and an
optional `jsonata`. It replaces the pair `output_bindings` + the evaluator's `output_vars`, which a
consumer had to join by name in order to use either. `name` SHALL be the model response property —
the target column's **exposed** name, which is what the composed response schema declares — and
`column` SHALL be the column's **physical** spelling, which is what the write path stores. The two
SHALL remain separate members even where they are equal, because a target may expose a column under
a name it does not store it under, and a consumer that inferred one from the other would write to a
column that does not exist or read a response property that was never declared. `jsonata` SHALL be
absent rather than an identity expression when the value is taken straight from `response[name]`.

The inlined evaluator on the compiled projection SHALL omit `input_vars` and `output_vars`: what the
evaluator receives is the pipeline's `vars` and where its answers land is the pipeline's `outputs`,
so serving either would restore the two-lists-joined-by-name shape `outputs` exists to collapse. This
SHALL NOT extend to `GET /v1/evaluators/{name}/versions/{version}`, which SHALL keep answering with
the stored row exactly as written, per-output `type` included — a stored version is immutable by
contract, so a read of one may not answer with less than was stored.

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
- **THEN** the response is the authored projection, carrying no inlined evaluator, no `grain_key`, no
  `version_column`, no `outputs` and no resolved `inputs`

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
- **THEN** the response carries the declaration's own members — `vars`, `advanced`, the stored
  `inputs` where one was declared — and omits the derived output mapping and the resolved evaluator
  payload

#### Scenario: The compiled projection carries one vocabulary

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` is called for an `enrich` pipeline
- **THEN** the execution knobs are served under `advanced` with their authored names, the evaluator
  inputs under `vars`, and the response carries no `input_bindings`, `output_bindings`, `cadence`,
  `batch_scan_limit`, `batch_chunk` or `sampling`

#### Scenario: The projections differ only by the derived members

- **WHEN** the same `enrich` pipeline is read in both projections
- **THEN** the compiled response's members are exactly the authored response's members plus the
  inlined evaluator, `grain_key`, `version_column`, `outputs` and the resolved `inputs`

#### Scenario: An output carries the exposed name and the physical column separately

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` is called for a pipeline whose target exposes a
  column under a name other than its physical one
- **THEN** the matching `outputs` entry carries the exposed name in `name` and the physical spelling
  in `column`

#### Scenario: An unknown view value is rejected

- **WHEN** `GET /v1/pipelines?view=<value other than compiled or source>` is called
- **THEN** the request is rejected with HTTP 400 `bad_request` naming `compiled` and `source`, the message
  does not contain the rejected value, and no projection is served in place of the unrecognized one
