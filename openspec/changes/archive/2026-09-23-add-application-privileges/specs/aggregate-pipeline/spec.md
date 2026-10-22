## ADDED Requirements

### Requirement: Pipeline endpoints are authorized by registry privileges

The pipeline surface SHALL be authorized by privilege rather than by application role, for both kinds.
Reading pipelines — `GET /v1/pipelines`, `GET /v1/pipelines/{name}` and
`GET /v1/pipelines/{name}/revision/{revision}` — SHALL require `REGISTRY_READ`. Declaring, updating and
deleting them — `POST /v1/pipelines`, `PATCH` and `DELETE /v1/pipelines/{name}` — SHALL require
`REGISTRY_ADMIN`.

Executing a stored declaration SHALL also require `REGISTRY_ADMIN`:
`POST /v1/pipelines/{name}/refresh` writes rows chosen entirely by whoever authored the declaration,
and `POST /v1/pipelines/{name}/preview` returns that declaration's output over the **entire** input,
unwindowed, with a projection the caller did not choose. Neither is usefully separable from
authoring, so no distinct execute privilege SHALL be introduced; `DATA_SYNC_OPERATE` SHALL NOT
authorize them.

Pipeline reads are reachable today by any application role, so `REGISTRY_READ` reproduces that reach
exactly: the `READ_ONLY_ADMIN` bundle holds it, and a caller that maps to no privilege loses a read it
never had a reason to hold. No endpoint in this change widens.

#### Scenario: Pipeline reads require REGISTRY_READ

- **WHEN** a caller holding the `READ_ONLY_ADMIN` bundle lists pipelines
- **THEN** the response is `200` with the declared pipelines
- **AND WHEN** a caller holding only `TABLE_READ` lists pipelines
- **THEN** the response is `403 forbidden`, because a table privilege confers no registry read

#### Scenario: Refresh requires REGISTRY_ADMIN

- **WHEN** a caller holding `REGISTRY_READ` and `DATA_SYNC_OPERATE` but not `REGISTRY_ADMIN` invokes
  a pipeline refresh
- **THEN** the response is `403 forbidden`

#### Scenario: Preview requires REGISTRY_ADMIN

- **WHEN** the same caller invokes a pipeline preview
- **THEN** the response is `403 forbidden`

### Requirement: The scheduler's system identity holds the narrowest sufficient privileges

The system principal the scheduler elevates to SHALL hold exactly the read ladder — `TABLE_READ` and
`TABLE_READ_SENSITIVE`, closed under implication — globally, and nothing else. It SHALL NOT hold
`TABLE_GRANT`, `TABLE_CREATE`, `TABLE_DELETE` or `REGISTRY_ADMIN`, so scheduled work cannot administer
anything.

Naming the set is what bounds the elevation. Before privileges existed the narrowest grantable thing was
the full administrative role, so the elevation had to be justified in prose rather than by construction;
the justification is now the set itself.

The narrowed principal SHALL be sufficient for every operation a scheduled refresh performs. If
service-layer writes are ever brought under `TABLE_WRITE`, the principal SHALL be extended to hold it,
because a refresh writes its target table.

#### Scenario: A scheduled refresh over a sensitive column succeeds

- **GIVEN** an enabled pipeline whose declaration reads a `sensitive` source column
- **WHEN** the scheduler runs its refresh with no caller present
- **THEN** the refresh executes and writes its target, exactly as an on-demand refresh does

#### Scenario: The system principal cannot administer

- **WHEN** the scheduled work attempts an operation requiring `TABLE_GRANT`, `TABLE_CREATE`,
  `TABLE_DELETE` or `REGISTRY_ADMIN`
- **THEN** the operation is denied, because the system principal holds no such privilege

#### Scenario: The elevated set is closed under implication

- **WHEN** the system principal's authorities are inspected
- **THEN** they include `TABLE_GET` and `TABLE_LIST` as well as the two read privileges, because holding
  a rung confers every rung below it and a check tests exact containment

### Requirement: A rollup target may not grant a protected column more widely than its input

Where a rollup target **carries** a `sensitive` column, its `read_sensitive` grantee set SHALL be a
subset of its input table's — the same condition as the enrichment rule in `enrichment-runtime`, which
that capability states and defends: the target carrying such a column, **not** the narrower "a pipeline
derives one from a sensitive input column". The narrow reading is the more precise statement of the risk
and is not answerable when a grant is edited, and it is the input table the rollup reads that the subset
is taken against.

It SHALL be enforced at all four of the points `enrichment-runtime` names, reached here through the
pipeline's `input_table` rather than a catalog parent: registration and enable of an `aggregate` pipeline
(rejected `422 pipeline_validation_failed`), `PUT /v1/tables/{name}/access` on either table, and either
schema endpoint when the target first comes to carry a sensitive column (rejected
`422 sensitive_grant_widens`).

**This is a distinct rule from the sensitivity-propagation one above, and the gap between them was a
disclosure.** Requiring the derived column to carry the `sensitive` flag stops it being served to an
unentitled caller; it says nothing about *who* is entitled. A rollup target is a `SOURCE` — a
first-class queryable entity with its own grant lists and, unlike an enrichment, **no catalog
parent** — so a rule that walked source/enrichment parentage never examined it at all. The result was
more direct than the enrichment path: a rollup is addressable on its own, so a caller granted
`read_sensitive` on the target needed no grant whatsoever on the table being aggregated, and the
scheduler's elevated identity did the copying.

#### Scenario: A laxer rollup target is refused at registration

- **GIVEN** `dial_usage_log.jwt_claims` is `sensitive` and `dial_usage_log`'s `read_sensitive` list
  names `pii-team` only
- **WHEN** an `aggregate` pipeline is registered whose target declares a `sensitive` column derived
  from `jwt_claims`, and whose target's `read_sensitive` list names `analysts`
- **THEN** the registration is rejected `422 pipeline_validation_failed`, naming the widening role
  and both tables

#### Scenario: Granting the rollup target more widely is refused

- **GIVEN** the same pipeline registered with matching lists
- **WHEN** an operator grants `read_sensitive` on the rollup target to a role the input's list does
  not name
- **THEN** the request is rejected `422 sensitive_grant_widens` and neither list changes

#### Scenario: Narrowing the input walks its rollup targets

- **WHEN** an operator revokes `read_sensitive` on the input table while a rollup target of it still
  names a role and carries a sensitive column
- **THEN** the request is rejected `422 sensitive_grant_widens`

## MODIFIED Requirements

### Requirement: Scheduled refreshes execute under a system identity

A scheduled refresh SHALL execute under an internal system identity entitled to read `sensitive` columns.
Column-level governance is decided from the acting identity, and a scheduler tick has no caller: without
this, a pipeline an entitled author registered over a `sensitive` column would validate, preview, and
refresh
on demand, yet be refused on every tick — permanently, and only where security is enabled. Scheduled
execution has to behave like the on-demand execution it automates.

The elevation SHALL be scoped to one pipeline's refresh and SHALL be unwound afterwards, including when the
refresh fails. A tick refreshes many pipelines on one thread, so an elevation left in place would silently
grant it to whichever pipeline ran next. The exposure the elevation would otherwise create is closed at
declaration time by the sensitive-target-column rule above, not at execution.

The aggregate tick SHALL run on a task scheduler of its own, separate from the one the service's ingestion
jobs use, and its size SHALL be configurable. Refreshes run serially inside a tick, so a slow recompute
does not merely lengthen the tick — on a shared pool it occupies a thread that ingestion also needs. The
two have different consequences when late: a delayed rollup serves a stale number, while delayed ingestion
loses ground against its source, so they SHALL NOT compete for the same budget. Isolation SHALL be the only
effect: refreshes remain serial within a tick, and ticks SHALL NOT overlap by default.

Introducing the dedicated scheduler SHALL NOT disturb the scheduler the ingestion jobs already use, nor the
configuration that sizes it.

#### Scenario: A pipeline over a sensitive column refreshes on schedule

- **WHEN** an enabled pipeline reads a `sensitive` input column into a `sensitive` target column and the
  scheduler tick fires
- **THEN** the refresh executes, rather than being refused for want of a caller

#### Scenario: The elevation does not outlive the refresh

- **WHEN** a scheduled refresh completes or fails
- **THEN** the acting identity in effect afterwards is the one that was in effect before it

#### Scenario: A slow refresh does not delay ingestion

- **WHEN** an aggregate refresh occupies its scheduler for longer than the ingestion jobs' interval
- **THEN** the scheduled poll and the daily sync still run on their own schedule, unblocked by it

#### Scenario: The ingestion scheduler keeps its configured size

- **WHEN** the service starts with the scheduled-task pool size configured
- **THEN** the ingestion jobs' scheduler is sized by that configuration, unaffected by the aggregate
  scheduler's presence
