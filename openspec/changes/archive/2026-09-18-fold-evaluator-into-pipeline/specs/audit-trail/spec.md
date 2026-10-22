## MODIFIED Requirements

### Requirement: Audited resources

The system SHALL track every insert, update, and delete of the catalog state backing tables, and of
the pipeline registry and the saved query registry:

- the **table definition** row in its entirety — its type, status, write discipline, scan metadata,
  partitioning, TTL, ordering key, an enrichment's grain key, and its role-based `access` lists;
- every **column** of a table — its type, element type, nullability, description, display name, tag,
  ordinal, and its `sensitive` and `heavy` flags;
- every **pipeline**, of either kind — its name, kind, target, input table, filter, transform
  declaration, trigger configuration (including a group trigger's key, readiness and membership
  conditions), enabled flag, generation counter, and timestamps. For an `enrich` pipeline the
  **transform declaration** SHALL include the folded evaluator definition — the `transform` block's
  `type`, `model`, `preset`, `params`, `request_template`, **`inputs`** and `outputs`, or the
  `output_vars` and stored `response_schema` a folded legacy declaration carries instead — so a change
  to a prompt, a model, an input binding, an output's prose or a stored response schema is a tracked,
  attributed revision like any other declaration change. `inputs` is named explicitly because it was the
  top-level `vars` before the fold and was audited then; omitting it from this enumeration would read as
  a binding change having become untracked. Its **runtime
  position** is deliberately excluded: the scan cursor, scheduling and last-run state, the clamp
  record, and the Class-1 materialized position are not tracked. Those move on every scheduler tick
  or are written by bulk updates the persistence context never sees, so tracking them would either
  flood the feed with entries no one administered or mirror a value that disagrees with the live row;
- every **saved query** — its identity, name, description, tag, scope, owner, source, its structured
  or SQL body, time intent, result view, chart configuration, generation counter, and timestamps.

For each tracked change the system SHALL preserve the complete state of the changed entity at that
point, so the resource can be read back as it stood at that revision. A delete SHALL preserve the
entity's final state rather than a row of nulls.

Analytics row writes (`POST /v1/tables/{name}/rows`) are data-plane writes to ClickHouse and SHALL
NOT be audited. The **retained evaluator archive** — the `evaluator` and `evaluator_version` tables,
readable through `GET /v1/evaluators*` and closed to writes — SHALL remain outside this capability's
coverage. Auditing it would record nothing: no route can change it, so no revision can ever be
produced. Before the fold this exclusion covered a live registry and was a real gap in attribution;
it now covers an immutable archive, and the gap it left is closed by the transform being part of the
audited pipeline declaration.

Two **known and accepted limitations** SHALL be stated rather than discovered.

First, a `Pipeline` revision recorded **before** the fold carries no transform, because those members
were not part of the declaration then. A snapshot at such a revision SHALL return the declaration as
it was stored — without `transform` — rather than synthesising it from the evaluator the pipeline
named at the time. Reconstructing which evaluator version a past revision resolved to, and inlining
it, is out of scope: the pin was resolved at read time, not stored per revision. Nor does the snapshot
answer with the pin: a revision read re-serialises the stored declaration **in the current shape**
(see [pipeline-registry](../pipeline-registry/spec.md), "Pipeline registry object"), and the current
shape has no `evaluator_name`. The linkage survives in two places outside this endpoint, and both are
deliberate: `pipeline_aud` keeps its `evaluator_name` and `evaluator_version` mirror columns, which
carry no foreign key precisely so they outlive the registry, and the fold migration logs every
`(pipeline, evaluator, resolved version)` triple it folds. The retained archive is where the
definition those name is read.

Second, **the fold migration itself records no revision**. It rewrites `spec` in raw SQL, outside the
persistence context, and deliberately bumps neither `generation` nor `updated_at` — bumping would
re-sync every runner lane and discard every Class-1 materialized position for a change no consumer can
observe. The trail therefore shows a pipeline whose transform appears between two revisions with no
revision in between. This is the correct trade: the alternative is a revision attributed to no
identity, describing an administrative act nobody performed.

#### Scenario: A scheduled refresh creates no revision

- **WHEN** an enabled aggregate pipeline's scheduled refresh advances its cursor, records its last run and
  sets its next run
- **THEN** no revision and no activity are recorded for it, because every field written is runtime position

This scenario is the exclusion above stated as an observable, because the exclusion is defeasible by a
single line elsewhere: it holds only while the runtime writers leave the pipeline's **audited** columns
alone. `updated_at` is audited, so a state write that stamped it produced one revision per write — two per
fire — whose audited columns were byte-identical to the previous revision's. `updated_at` therefore means
*the declaration last changed*, which is also what the enrichment runner's incremental sync needs it to
mean; run state is carried by `last_run_at` and `next_run_at`, which are not audited.

#### Scenario: Table creation is tracked

- **WHEN** a table is created through `POST /v1/tables`
- **THEN** a `Create` activity for resource type `Table` with that table's name is recorded

#### Scenario: Schema definition is tracked

- **WHEN** a table's schema is defined and materialized through `POST /v1/tables/{name}/schema`
- **THEN** an `Update` activity for the `Table` and a `Create` activity for each declared
  `TableColumn` are recorded, and the resulting snapshot carries the persisted columns, ordering key,
  and status

#### Scenario: Access-list change is tracked

- **WHEN** an administrator replaces a table's role-based access lists through
  `PUT /v1/tables/{name}/access`
- **THEN** an `Update` activity for that table is recorded and the snapshot carries the new lists

#### Scenario: Sensitivity change is tracked

- **WHEN** a schema patch marks a column `sensitive`
- **THEN** an `Update` activity for that `TableColumn` is recorded and the snapshot carries the flag

#### Scenario: Deletion preserves final state

- **WHEN** an audited resource is deleted
- **THEN** the preserved state for that change is the resource's state immediately before deletion

#### Scenario: Row writes are not tracked

- **WHEN** rows are written through `POST /v1/tables/{name}/rows`
- **THEN** no activity is recorded

#### Scenario: The evaluator archive is not tracked

- **WHEN** the retained `evaluator` and `evaluator_version` tables are read through `GET /v1/evaluators*`
- **THEN** no activity is recorded for them, and no route exists that could change them, so no
  revision is ever produced

#### Scenario: A prompt change is a tracked, attributed revision

- **WHEN** `PATCH /v1/pipelines/{name}` changes an `enrich` pipeline's `transform.request_template`,
  `transform.model` or an output's prose
- **THEN** an `Update` activity for resource type `Pipeline` is recorded, attributed to the calling
  identity, and its preserved state carries the new transform in full

#### Scenario: A pre-fold pipeline revision carries no transform

- **WHEN** a caller requests a `Pipeline` snapshot at a revision recorded before the fold
- **THEN** the snapshot returns the declaration as it was stored at that revision, without the
  `transform` and without an `evaluator_name`, rather than resolving the evaluator the pipeline named
  at the time

#### Scenario: The fold migration records no revision

- **WHEN** the fold migration rewrites every `enrich` pipeline's `spec` with its resolved transform
- **THEN** no `Pipeline` revision and no activity are recorded, and no pipeline's `generation` or
  `updated_at` changes

#### Scenario: Pipeline mutations are tracked

- **WHEN** a pipeline of either kind is created through `POST /v1/pipelines`, changed through
  `PATCH /v1/pipelines/{name}`, or removed through `DELETE /v1/pipelines/{name}`
- **THEN** a `Create`, `Update`, or `Delete` activity respectively is recorded for resource type
  `Pipeline`, and its preserved state carries the pipeline's full configuration at that point

#### Scenario: A scheduler tick records no activity

- **WHEN** an aggregate pipeline's scheduled refresh advances its cursor and last-run state, or a
  Class-1 materialize advances its materialized position
- **THEN** no activity is recorded — nothing an administrator did changed

#### Scenario: Saved query mutations are tracked

- **WHEN** a saved query is created through `POST /v1/saved-queries`, changed through
  `PUT /v1/saved-queries/{id}`, or removed through `DELETE /v1/saved-queries/{id}`
- **THEN** a `Create`, `Update`, or `Delete` activity respectively is recorded for resource type
  `SavedQuery`, and its preserved state carries the saved query's full configuration at that point

### Requirement: Point-in-time reads for pipeline and saved query

The system SHALL expose `GET /v1/pipelines/{name}/revision/{revision}` and
`GET /v1/saved-queries/{id}/revision/{revision}`, each returning that resource's own stored fields —
not a resolved view — as they stood at the end of the given revision.

Unlike the live reads of the same resources, these SHALL NOT resolve state belonging to another
entity as of that revision: an enrich pipeline's snapshot SHALL NOT resolve its derived grain key or
its read source's version column, since reconstructing those would require historical catalog state
this capability does not track. A resource whose own history reconstructs correctly but names another
entity SHALL still return successfully, carrying that other entity's identifier verbatim rather than
its resolved current or historical state.

**The transform is no longer in that category.** Before the evaluator fold a pipeline named an
evaluator by reference and resolved it at read time, so a snapshot could not answer with the prompt at
all. After the fold the transform is a member of the pipeline's own declaration, stored in `spec`,
which `pipeline_aud` mirrors — so a snapshot SHALL carry it, and there is no pin left to decline to
resolve. The one thing a snapshot still cannot answer is a **pre-fold** revision's transform: those
members were not part of the declaration then, and the pin was resolved at read time rather than stored
per revision. Such a revision SHALL be returned without a `transform` rather than refused or
synthesised, and the evaluator archive, the retained `pipeline_aud` mirror columns and the fold
migration's log are where that linkage is read instead (see "Audited resources").

A request for a revision at which the resource did not exist — before its creation, at or after its
deletion, or naming a revision that was never issued — SHALL be answered with HTTP 404 and the error
code `revision_not_found`, matching `GET /v1/tables/{name}/revision/{revision}`'s existing behavior.
A resource whose creation is not itself recorded in the trail SHALL be answered the same way.

#### Scenario: Pipeline snapshot returns the pipeline as it stood at that revision

- **WHEN** a pipeline's declaration is changed and a caller requests its snapshot at the revision
  preceding the change
- **THEN** the previous declaration is returned

#### Scenario: Enrich pipeline snapshot returns its own stored fields only

- **WHEN** an enrich pipeline is created, then updated, and a caller requests its snapshot at the
  revision preceding the update
- **THEN** its fields as they stood before the update are returned, including the `transform` it
  declared at that revision, and the derived grain key and resolved read source present on the live
  read are absent from the snapshot

#### Scenario: A pre-fold revision answers with no transform

- **WHEN** a caller requests the snapshot of a revision recorded before the evaluator fold, when the
  pipeline named an evaluator by reference
- **THEN** the response is returned successfully and carries no `transform`, rather than being refused
  or answered with a transform reconstructed from the archive

#### Scenario: Saved query snapshot returns the saved query as it stood at that revision

- **WHEN** a saved query's body is changed and a caller requests its snapshot at the revision
  preceding the change
- **THEN** the previous body is returned

#### Scenario: Snapshot of a resource whose creation predates the trail is not found

- **WHEN** a caller requests a pipeline or saved query snapshot at a revision, and that resource's own
  creation is not recorded in the trail
- **THEN** the system returns HTTP 404 with error code `revision_not_found`
