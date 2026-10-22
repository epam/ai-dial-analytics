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
