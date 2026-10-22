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
enrichment's own parent source"; the response SHALL echo the resolved input rather than the omitted
value, so a caller can see what was chosen. The input SHALL NOT be the pipeline's own target — a
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
- **THEN** the pipeline is accepted and reads its target enrichment's parent source, and the response
  echoes that resolved input

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

## ADDED Requirements

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

`state.last_error` SHALL continue to be reported verbatim to any caller holding an application role.
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

- **WHEN** a caller holding only `READ_ONLY_ADMIN` and a `FULL_ADMIN` caller each read an `aggregate`
  pipeline whose `filter` references a `sensitive` column
- **THEN** both responses carry identical freshness diagnostics, and neither is refused

#### Scenario: A sensitive filter does not fail the read

- **WHEN** a caller holding only `READ_ONLY_ADMIN` reads an `aggregate` pipeline whose `filter`
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
