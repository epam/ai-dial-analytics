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

- **WHEN** `POST /v1/pipelines` registers `kind: aggregate` carrying `advanced.scan_every`
- **THEN** the request is rejected with HTTP 422 naming `advanced.scan_every`, not `advanced`

#### Scenario: A removed flat spelling is refused on either kind

- **WHEN** `POST /v1/pipelines` carries `cadence` on `kind: enrich` or on `kind: aggregate`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, before any cross-kind
  check is reached

#### Scenario: A retired evaluator reference is refused at the binding, not cross-kind

- **WHEN** `POST /v1/pipelines` carries `evaluator_name` or `evaluator_version` on `kind: enrich` or on
  `kind: aggregate`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, before any cross-kind
  check is reached — the member belongs to no kind rather than to the other one

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

The version this build serves SHALL be raised to **2** by the evaluator fold, because that change
reshapes the compiled projection — it removes the inlined `evaluator` object, removes the top-level
`vars`, and adds a derived top-level `response_schema` — which is exactly the trigger this header's own
rule names. The bump is the whole of that change's obligation to the ordered deploy: a consumer holding
the old shape reads the echo before it interprets the body, finds a version it does not recognise, and
refuses the response rather than rendering a template whose placeholders bind to nothing.

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
schema, so serving both would ship a multi-kilobyte object twice in every feed entry. That is precisely
the duplication the inlined `evaluator` object was deleted to remove, and re-introducing it under a
different member name would undo that decision rather than qualify it.

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

The rule SHALL hold with **no enumerated exception**. An earlier draft of this change kept the
transform inlined a second time as an `evaluator` object, so that the fold would cost the Enrichment
Runner no redeploy; that object is removed, because the same release renames `vars` to
`transform.inputs` and therefore spends a coordinated runner release anyway. Keeping it would have
shipped a standing second spelling of the response's largest payload — a `request_template` is
multi-kilobyte — in exchange for nothing.

This replaces an earlier requirement that the compiled projection be **byte-identical** to the
response served before the declaration surface was shrunk. That requirement existed to let a runner
deployed before this change keep reading the feed unchanged across a rolling deploy; it is withdrawn
because the runner is updated in the same maintenance window, with its own deployment scaled to zero
between the two updates, so no such reader exists. Nothing weaker replaces it: the guarantee is now
that the compiled projection carries exactly the authored vocabulary plus the derived members, which
is asserted as a set difference against the authored projection rather than as a diff against a
captured response.

The derived members SHALL be exactly: `response_schema`, `grain_key`, `version_column`, the resolved
`inputs`, and `outputs`. The transform arrives as an **authored** member, so the only change to this set
is that the inlined `evaluator` object is replaced by the one derived member it carried. The set stays
exactly five because the contract version travels as a **response header** rather than a body member
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

Every fact the removed `evaluator` object carried SHALL remain readable on the entry, under one
spelling each: its `name` is the pipeline's `name`, its `version` is the pipeline's `generation`, its
`created_at` is the pipeline's `updated_at`, its `type`, `model`, `preset`, `params` and
`request_template` are the `transform`'s own members, and its `response_schema` is the derived top-level
member above. Nothing it carried is lost, and no consumer needs a second lookup to recover any of it.

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
and which is served as authored, nor to `GET /v1/evaluators/{name}/versions/{version}`, the retained
read-only archive, which SHALL keep answering with the stored row exactly as written, per-output `type`
included — a stored version is immutable by contract, so a read of one may not answer with less than
was stored.

Resolution SHALL be performed only for the compiled projection. A listing or read in the authored
projection SHALL NOT pay the lookups that produce derived members, and because every derived member
is driven by the presence of a resolution, not resolving is also what makes it impossible for one to
appear in an authored response. After the fold no projection SHALL read an `evaluator` or
`evaluator_version` row at all.

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
- **THEN** every entry is built from that pipeline's own stored declaration, and no `evaluator` or
  `evaluator_version` row is read to serve the listing

#### Scenario: An output carries the exposed name and the physical column separately

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` is called for a pipeline whose target exposes a
  column under a name other than its physical one
- **THEN** the matching `outputs` entry carries the exposed name in `name` and the physical spelling
  in `column`

#### Scenario: An unknown view value is rejected

- **WHEN** `GET /v1/pipelines?view=<value other than compiled or source>` is called
- **THEN** the request is rejected with HTTP 400 `bad_request` naming `compiled` and `source`, the message
  does not contain the rejected value, and no projection is served in place of the unrecognized one

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
on it turns into a silent data loss. The enumeration names `response_schema` rather than an inlined
`evaluator` object because the evaluator fold replaced the one with the other. The **two** refusals
above remain two: the fold introduces no third combination, because the runner contract version travels
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
