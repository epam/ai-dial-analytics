## MODIFIED Requirements

### Requirement: Scan projection and version isolation

The scan query SHALL read the base source, joined to **each `ACTIVE` enrichment whose columns the scan's
own clauses reference and to no other** — an enrichment nothing references SHALL contribute no join, so a
declaration naming none renders the statement it rendered before enrichment references were admitted. Each join
SHALL be a single-key `LEFT JOIN` on the shared grain key, which cannot inflate the source's row count,
and SHALL be **keyed on the scan's own cursor predicates** (the safety lag, the `[from, to)` window, the
seek past the cursor and, when present, the freshness clamp — all on source columns), plus the pipeline's
filter when it names source columns only: the enrichment side is a derived table restricted to the
grain keys those predicates select, so a scan tick reads the enrichment rows of its page rather than
hashing the whole enrichment table. The scan SHALL push projection down to `{fields referenced by the pipeline's filter and input bindings} ∪
{grain id} ∪ {cursor columns}`. The scan SHALL read with `final=1` so only the latest version per source
identity is returned, independent of the projected columns.

An enrichment field SHALL be projected under its **qualified flat name** (`<enrichment>.<column>`), which
is the name the pipeline declared it under, so a caller reads it back by the name it wrote.

For a pipeline whose `trigger.kind` is `group`, the projection SHALL additionally include the pipeline's
`trigger.group_by` value, so one scan page tells the caller which groups changed. When `trigger.group_by` names an
enrichment column the value SHALL appear **twice**: under that qualified flat name, and again under the
**target enrichment's plain grain key**. Both are required, by different consumers:

- The **qualified** name is the spelling `trigger.group_by` carries on the wire, and a consumer addresses the key
  column by the name the pipeline declares. The Enrichment Runner reads it as `row.get(pipeline.triggerGroupBy())`, and a
  row where that lookup misses is skipped with nothing raised — so omitting this name makes the pipeline scan
  its page, advance its cursor, and mark no group at all, indistinguishable from an idle source.
- The **plain grain key** is what the target enrichment is physically keyed on, and the spelling the key
  round-trips as through the dirty mark, the assembly request and the write-back.

The plain grain key SHALL be an alias over the joined field rather than a projected column of its own: the
read source does not declare it, so naming it would render a column the `FROM` clause does not carry. The
activity timestamp needed to evaluate an idle window requires no addition: the scan already projects the
source's version and identity columns under their own names.

For a pipeline whose `trigger.kind` is `group`, the projection SHALL **exclude the pipeline's bound
input-binding columns**. A group pipeline's page is consumed by a dirty-mark, which needs to know *which
groups changed* and not what they contain; the bound columns exist to render one prompt per group, and
are read by `POST /v1/pipelines/{name}/group-rows`, where the row and byte caps that make reading a body safe
are applied. Projecting them on the scan would put an unbounded volume — a group pipeline may bind
request/response bodies — on the one pipeline-scoped read that has **no byte cap**, and would charge every
page for data the caller discards. This is the same principle the readiness verdict already follows
below: the page carries what the caller must act on, not the evidence behind it. For every other
`trigger.kind` the bound columns SHALL be projected, because a row-grain pipeline's scanned row *is* its
unit of work and its chunk payload is mapped from exactly those columns.

The **sensitive-column re-check SHALL continue to cover a group pipeline's bound input-binding columns**
even though they are no longer projected, and SHALL cover an enrichment column exactly as a source
column. The check governs what a pipeline reads or derives from, not what a response happens to return, and a
pipeline binding a `sensitive` column still reads it — at the assembly endpoint rather than at the scan.
Narrowing the projection SHALL NOT become a way for a caller who may not see sensitive columns to
dirty-mark a pipeline whose groups it would then be refused.

For a group pipeline declaring `trigger.ready_when.signal`, each returned row SHALL additionally carry `__signal`:
the **computed boolean verdict** of that predicate for that row. The predicate SHALL be evaluated
**inside the scan query**, translated from the same parsed form the pipeline's registration validated, and the
raw columns it references SHALL NOT be projected on its behalf — the page carries the verdict, not the
evidence. The verdict exists so a caller can honour `signal` without holding a second implementation of
ADAS's bounded predicate grammar, which would be free to diverge and would then mark the wrong groups
ready rather than failing; it also keeps values the caller has no other use for inside ClickHouse. A
group pipeline declaring no `signal`, and every row-grain pipeline, SHALL carry no `__signal` column at all.

`__signal` SHALL be a JSON boolean on the wire. Because a predicate is answered as an integer — and as a
nullable one when it touches a nullable column — the scan SHALL normalize it, and an unknown (null)
verdict SHALL be reported as `false`: a signal is a positive assertion that a group is complete, so
unknown fails closed and the group is left to its other readiness conditions.

The **sensitive-column re-check SHALL cover the columns a governed predicate derives from**, not only the
columns the response returns: a caller who may not see sensitive columns SHALL be refused a scan whose
`trigger.ready_when.signal` reads one, exactly as when a binding projects it. Evaluating the predicate
server-side SHALL NOT become a way around that refusal.

Every other aspect of the scan — the keyset predicate, the opaque cursor, the safety-lag, `final=1`,
ordering, and the sensitive-column re-check — SHALL be unchanged for a group pipeline; the dirty-mark read is
the same endpoint with a **differently scoped** projection, not a second endpoint. Its cost is therefore
proportional to **new rows**, not to the number of groups, and not to the size of their bodies.

#### Scenario: Scan omits the enrichments nothing references

- **WHEN** the scanned source has one or more enrichments and the pipeline references no column of any of
  them
- **THEN** the statement emits no join, and the returned rows contain only source/projected columns —
  no auto-joined enrichment columns

#### Scenario: Scan carries an enrichment column the pipeline references

- **WHEN** a pipeline's `filter` or an input binding references a column of an `ACTIVE` enrichment of the
  scanned source
- **THEN** the statement emits a `LEFT JOIN` for that enrichment on the shared grain key, and the value
  is returned under its qualified flat name

#### Scenario: Projection is limited to referenced columns

- **WHEN** a pipeline references only a subset of the source's columns
- **THEN** the scan projects only those columns plus the grain and cursor columns

#### Scenario: A group pipeline's scan projects the group key and the readiness verdict

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline whose `trigger.kind` is `group` and whose
  `trigger.ready_when` declares a `signal`
- **THEN** each returned row additionally carries the pipeline's `trigger.group_by` value, the source's version column
  as an activity timestamp, and `__signal` as a boolean — `true` for a row satisfying the predicate and
  `false` for one that does not

#### Scenario: An enrichment-sourced group key is projected under both names

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a group pipeline whose `trigger.group_by` is
  `"usage_client_identity.client_session_id"` and whose target enrichment is keyed on
  `client_session_id`
- **THEN** each returned row carries the value under `"usage_client_identity.client_session_id"` — the
  name the pipeline declared, which is how a consumer addresses the key column — and under
  `client_session_id`, the spelling that addresses the group in the dirty mark, the assembly request and
  the write-back

#### Scenario: A group pipeline's scan omits its bound input columns

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline whose `trigger.kind` is `group` and which
  binds one or more source columns as evaluator inputs
- **THEN** those columns are absent from the returned rows, while the `trigger.group_by` value, the cursor
  columns, and any column the pipeline's `filter` references are present

#### Scenario: A row-grain pipeline's scan still projects its bound input columns

- **WHEN** the same bindings are declared on a pipeline whose `trigger.kind` is `schedule`
- **THEN** the bound columns are projected, because the scanned row is the unit of work

#### Scenario: A non-entitled caller cannot scan a group pipeline that binds a sensitive column

- **WHEN** a caller who may not see sensitive columns scans a group pipeline that binds a `sensitive` source
  column which nothing else references
- **THEN** the request is rejected with HTTP 403 `sensitive_column_not_entitled`, even though that column
  is no longer part of the group pipeline's projection

#### Scenario: A non-entitled caller cannot scan a pipeline that reads a sensitive enrichment column

- **WHEN** a caller who may not see sensitive columns scans a pipeline referencing a `sensitive` column of an
  `ACTIVE` enrichment of its read source
- **THEN** the request is rejected with HTTP 403 `sensitive_column_not_entitled`

#### Scenario: The signal's raw columns are not projected

- **WHEN** the same pipeline is scanned and its `trigger.ready_when.signal` references a column that no input binding
  and no filter reads
- **THEN** that column is absent from the returned rows and only the `__signal` verdict is returned

#### Scenario: A group pipeline without a signal carries no verdict

- **WHEN** a group pipeline whose `trigger.ready_when` declares only `idle` and/or `max_staleness` is scanned
- **THEN** the returned rows carry no `__signal` column

#### Scenario: A non-entitled caller cannot scan a pipeline whose signal reads a sensitive column

- **WHEN** a caller who may not see sensitive columns scans a group pipeline whose `trigger.ready_when.signal`
  references a `sensitive` source column that nothing else projects
- **THEN** the request is rejected with HTTP 403 `sensitive_column_not_entitled` — computing the verdict
  server-side does not make the read permissible

#### Scenario: A group pipeline's scan keeps row-scan semantics

- **WHEN** a group pipeline is scanned across pages
- **THEN** paging, cursor advance, safety-lag, `final=1`, and the sensitive-column re-check behave
  exactly as for a row-grain pipeline

