# enrichment-runtime Specification

## Purpose

The governed data-plane the Enrichment Runner drives once an `enrich` pipeline is declared (see
[enrichment-registry](../enrichment-registry/spec.md)): a pipeline-scoped **incremental scan** that pages
matching source rows out of ClickHouse exactly once, a pipeline-scoped **group-input assembly** that
returns one group's members for a `group` pipeline, and a pipeline-scoped **versioned write-back** that
lands the transform's output into the pipeline's target enrichment. (The Class-1 set-based derivation path is a
capability of its own — see [class1-enrichment](../class1-enrichment/spec.md).)

Every endpoint resolves everything it needs (source, filter, projection, grain, target) from the stored
declaration — the caller never names a table, filter, or column set — and is authorized by the target
enrichment's WRITE grant, with the sensitive-column rule re-applied against the calling identity at each
read. These four paths are the reason the registry's data plane is reachable by an authenticated identity
holding that grant rather than by a full admin: the runner needs no global read or write authority.

The scan advances by an opaque, signed, rotatable cursor over the source's catalog-declared unique total
order (`(_ingested_at, event_id)` for both seeded sources) using keyset (seek) pagination, reads with
`final=1`, hides a still-mutating recent window behind a safety-lag, and optionally bounds itself to a
caller-supplied version window. The underlying keyset scan is a mode of the shared structured-query
pipeline (see [structured-query](../structured-query/spec.md)).
## Requirements
### Requirement: Catalog-configured scan cursor columns

The scan's total-order columns SHALL be resolved from the catalog rather than hardcoded per source.
Each `table_definition` SHALL carry an optional `version_column` and `identity_column`; together they
define the source's unique total order `(version_column, identity_column)` used for keyset paging,
the safety-lag predicate, and the cursor tuple. `dial_usage_log` SHALL be configured with
`(_ingested_at, event_id)`, preserving the originally shipped behaviour exactly, and `rate_analytics`
SHALL be configured with its own `(_ingested_at, event_id)` so ratings are scannable on the same
terms. A source's `identity_column` SHALL also be registered as a column of that source: an
enrichment keyed on it resolves the key's type from the source's catalog columns when its schema is
materialized, so an unregistered identity column would leave every enrichment on that source
unbuildable. A scan of a source
with either column unconfigured SHALL be rejected with HTTP 400 `bad_request` — the same status and
code the hardcoded branch produced — and a message naming the missing scan-metadata configuration
rather than a phase limitation.

The two columns SHALL be **caller-declared** on the table-management API: a source MAY declare both at
`POST /v1/tables/{name}/schema` (see [table-management](../table-management/spec.md)), alongside the
migration-seeded values the built-in sources carry — which is what makes a source created through the API
scannable with no migration. Declaring them SHALL remain optional: a source whose rows have no single
identifying column (a rollup keyed on several columns, whose identity is its whole `ordering_key`) has no
truthful `identity_column` to name, and the scan's rejection above is the correct answer for it. A declared
pair that does not hold is worse than none, because a repeated cursor tuple makes the keyset seek skip rows
silently instead of failing.

The table API SHALL verify that a declared pair is **shaped** for this scan — each name a non-nullable,
non-`sensitive` column of that table, and the `version_column` a `timestamp` (or, on an `upsert_by_key`
table, the engine version `_updated_at`) — because those are exactly the properties the SQL above requires:
a non-temporal version cannot be read as a millisecond timestamp, a `NULL` in either half fails every
cursor comparison, and a sensitive cursor column bars any caller without the sensitive entitlement from
operating the pipeline.

Those properties SHALL hold for the **life** of the table, not only at declaration: `PATCH .../schema`
SHALL NOT be able to leave a declared pair unusable by the scan. Accordingly it rejects a `drop` of either
column, and rejects an `update` marking either column `sensitive` — the one column attribute the scan
depends on that a metadata patch could otherwise flip after materialization. A **rename** of either column
SHALL be permitted and SHALL repoint the stored name in the same transaction: renaming preserves the
column's type, nullability and sensitivity, so no verified property can change under it, and a scan cursor
carries the tuple's *values* plus the source entity rather than the column names, so no cursor in flight is
invalidated. A rename of the `version_column` SHALL bump the `generation` of every pipeline reading that source
(see [enrichment-registry](../enrichment-registry/spec.md)), because a scan page carries that column under
its own name.

The exactly-once guarantee additionally depends on `version_column` being assigned at ingest, monotonic,
and never backdated, and on `identity_column` being unique per row — physical properties of ingest that no
layer can verify, an operator migration no better than a caller's schema request. Those therefore remain
the **declaring caller's promise**, and the table API SHALL NOT be read as validating them.

#### Scenario: A caller-declared source scans on its own order

- **WHEN** a caller declares `identity_column` and `version_column` for a source at
  `POST /v1/tables/{name}/schema`, and a pipeline over that source is scanned
- **THEN** the scan pages the source on that `(version, identity)` tuple, with no migration in between,
  exactly as it does for a migration-seeded source

#### Scenario: A pair the scan could not page by never reaches the scan

- **WHEN** a caller declares a `version_column` that is not a `timestamp`, or a nullable or `sensitive`
  member, at `POST /v1/tables/{name}/schema`
- **THEN** the schema request is rejected with HTTP 422 (see
  [table-management](../table-management/spec.md)), so the scan never renders SQL that ClickHouse would
  refuse, and never silently omits rows whose cursor value is `NULL`

#### Scenario: A materialized source's pair cannot be made unusable

- **WHEN** `PATCH /v1/tables/{name}/schema` attempts to `drop` a scan-metadata column, or to `update` one
  with `sensitive: true`
- **THEN** the request is rejected with HTTP 422 and the scan of a pipeline over that source keeps working for
  every caller it worked for before

#### Scenario: A renamed cursor column keeps paging, cursors included

- **WHEN** a source renames the column named by its `version_column` or `identity_column` through
  `PATCH /v1/tables/{name}/schema`, and a pipeline over that source is then scanned with a cursor issued
  before the rename
- **THEN** the scan pages on the renamed column and resumes at exactly the position the cursor encodes,
  neither skipping nor repeating a row

#### Scenario: A configured source scans using its catalog-declared order

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline whose source declares `version_column` and
  `identity_column`
- **THEN** the scan orders, seeks, safety-lags, and builds its cursor on that `(version, identity)`
  tuple, and `dial_usage_log` behaves exactly as it did before the columns were catalog-driven

#### Scenario: An unconfigured source is rejected with a catalog-driven message

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline whose source has no `version_column` or no
  `identity_column` configured
- **THEN** the request is rejected with HTTP 400 `bad_request` and a message identifying the missing
  scan-metadata configuration for that source

### Requirement: Exactly-once keyset scan semantics

The scan SHALL return each source row **at most once** across pages by advancing an opaque
cursor over a **unique total order** — the tuple `(version_column, identity_column)` declared for the
source in the catalog, which for both seeded sources is `(_ingested_at, event_id)`. The scan SHALL order
by that tuple and apply a keyset (seek) predicate `(version, identity) > (:c1, :c2)` rather than
offset pagination, so concurrent inserts never shift a page boundary. The scan SHALL advance by
**ingest/version order**, not the source sort key, so late-arriving rows are never skipped.
`nextCursor` SHALL encode the last returned row's tuple; an **empty page** SHALL return the input
cursor unchanged (or a start token when none was supplied) with `hasMore=false`; a full page SHALL
report `hasMore=true`. When `cursor` is omitted the scan SHALL start from the source's beginning, or
from `from` when a bounded window is supplied (enabling both full-replay and windowed backfill); the
server SHALL be stateless about cursor position (it signs and decodes only — the caller persists the
cursor).

#### Scenario: Each row is returned exactly once across pages

- **WHEN** a source is scanned across multiple pages, echoing each `nextCursor` as the next request's `cursor`
- **THEN** every matching row appears in exactly one page, with no gaps and no duplicates

#### Scenario: Colliding version values are disambiguated by the tuple tiebreaker

- **WHEN** multiple rows share the same version value at a page boundary
- **THEN** the identity component of the cursor tuple orders them deterministically so none is skipped or repeated

#### Scenario: Omitted cursor scans from the source start

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with no `cursor`
- **THEN** the scan begins at the source's earliest rows (full-replay), and the server holds no per-caller cursor state

#### Scenario: Empty page returns the input cursor unchanged

- **WHEN** a scan matches no rows above the current cursor below the safety-lag horizon
- **THEN** the response has an empty `rows` array, `hasMore=false`, and `nextCursor` equal to the input cursor

#### Scenario: Adjacent windows do not overlap at their shared boundary

- **WHEN** a caller scans `(t1, t2]` and then `(t2, t3]`
- **THEN** a row whose version is exactly `t2` is returned by the first window only

### Requirement: A cursor-bounded read on a partitioned source reads only the partitions its window can reach

A cursor-bounded pipeline read SHALL restrict its source to the partitions its window can reach, when the source's catalog definition declares a `partition_column`.
A cursor-bounded read is one that bounds its source from below by the cursor tuple or by a caller window. "Bounds from below by the cursor tuple" means the keyset seek
`(version, identity) > (v, id)`. "Bounds from below by a caller window" means `version > from`. The reads
covered are the pipeline scan, the Class-1 materialize probe and its write, the aggregate refresh probe
and its dirty-key sub-select, and every grain-join key sub-select built from the same source predicates.

The restriction SHALL be a predicate on the source's `partition_column`, qualified by the source table.
It requires that column to be at least the minimum `partition_column` value among the source's rows whose
version is at or after the bound. That minimum SHALL be derived **inside the same statement**, from the
data, by a sub-select that does not deduplicate (`final=0`). It SHALL NOT be a fixed lag, a value computed
in the service, or an assumption about how late rows arrive. Rows reach the source arbitrarily late
relative to their partition value, through re-ingest and backfill, so any assumed bound would skip rows.

The restriction SHALL be exact: it SHALL NOT change any observable outcome. That covers:

- the rows a scan page returns, its `nextCursor` and `hasMore`;
- the window a Class-1 or aggregate probe fixes, and the rows that window writes;
- `rows_written`, `has_more` and the stored cursor.

It holds because every row the seek admits has a version at or after the bound, so its partition value is
at or above the derived minimum. The bound prunes whole parts *before* the deduplicated read (`final=1`)
collapses a key, so a pruned row must never have been the key's winner over a row the seek admits. That is
guaranteed in two cases, and the bound SHALL be applied only in them:

- the source is partition-local: it is an `append` source, or its `partition_column` is one of its
  `ordering_key` columns, so every version of a key shares one partition value and is pruned or kept as a
  whole;
- the source's `version_column` is the engine's own collapse version (`_updated_at` on an
  `upsert_by_key` source), so a pruned row, whose version is below the bound, never outranks a kept one.

An `upsert_by_key` source partitioned outside its key whose cursor orders by any other timestamp gets no
bound: pruning could hand a key back to the row its newer version superseded.

When a read has both a caller window and a cursor, it SHALL carry a single bound, at the later of the two:
every row it admits is past both, so one bound there is as exact as two.

A source that declares no `partition_column` SHALL render exactly the statement it rendered before this
requirement. The upper bounds (`atMost`, `version <= to`) and the safety lag gain no derived predicate.

The obvious alternative is a plain lower bound on the version column. It is not sufficient and SHALL NOT
be relied on instead. Under the pipeline's `final=1` read, ClickHouse expands a skip-index selection back
to every part whose primary-key range intersects it. On `dial_usage_log`, whose sort key leads with
`(project_id, deployment)`, that restores the full table.

#### Scenario: A window statement no longer reads the whole partitioned source

- **WHEN** a pipeline probes or writes a window on `dial_usage_log` whose cursor sits near the newest
  ingested rows
- **THEN** the statement reads only the partitions holding rows at or after the cursor's version, plus
  the minimum sub-select's own read, rather than every row of the table

#### Scenario: The bound reaches enrichments keyed through the source predicates

- **WHEN** a window statement joins `dial_usage_log_payload`, whose grain-join key sub-selects reuse the
  source's cursor predicates
- **THEN** those key sub-selects are restricted by the same bound, so the payload side is read only for
  the reachable partitions

#### Scenario: The bound changes no result

- **WHEN** the same scan page, probe, or write is executed once with the bound and once without it, at
  the same cursor, horizon and window
- **THEN** both return identical rows, the same end tuple and `has_more`, and write identical rows

#### Scenario: A late row with an old partition value is still read

- **WHEN** a row whose `partition_column` value is days older than any other row in the window is
  ingested after the cursor's version
- **THEN** the derived minimum moves back to include that row's partition, and the row appears in the
  next window exactly once

#### Scenario: An empty window still reports nothing to do

- **WHEN** no source row has a version at or after the bound
- **THEN** the read returns no rows and the cursor is unchanged, exactly as without the bound

#### Scenario: A source on which the bound would not be exact gets none

- **WHEN** a cursor-bounded read runs on an `upsert_by_key` source whose `partition_column` is not in its
  `ordering_key` and whose `version_column` is not `_updated_at`
- **THEN** the rendered statement carries no partition predicate, exactly as for an unpartitioned source

#### Scenario: An unpartitioned source is unaffected

- **WHEN** a cursor-bounded read runs on a source whose catalog definition declares no `partition_column`
- **THEN** the rendered statement carries no partition predicate and is unchanged from before this
  requirement

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
  binds one or more source columns as transform inputs
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

### Requirement: Scan safety-lag

The scan SHALL exclude rows whose version column exceeds `now64(3) − lag` (configurable, default
5 seconds) so a scan never enters the still-mutating recent window. This relies on the source's
catalog-declared `version_column` being assigned at ingest and never backdated. `lagSeconds` in the
response SHALL be `server_now − max(version)` among rows available below the safety-lag horizon, so a
caller can emit a lag metric without decoding the cursor. The safety-lag SHALL always apply, including
when a bounded window is supplied: a `to` above the horizon SHALL be clamped by the horizon rather
than overriding it, and the scan SHALL continue to report `hasMore`/`nextCursor` relative to the
window so a caller can resume once the horizon advances.

#### Scenario: Too-recent rows are hidden by the safety-lag

- **WHEN** a row's version value is newer than `now64(3) − lag`
- **THEN** the scan does not return that row until it falls below the safety-lag horizon

#### Scenario: A window reaching past the horizon is clamped, not honoured

- **WHEN** a scan supplies a `to` newer than the safety-lag horizon
- **THEN** rows above the horizon are still excluded, and the caller can resume the same window later
  to pick them up

### Requirement: Opaque, signed, rotatable cursor

The cursor SHALL be opaque to callers: `base64url(format-version ∥ key-id ∥ entity ∥ tuple)`
authenticated with an HMAC over a configured secret. Decoding SHALL verify the HMAC and that the
encoded entity matches the pipeline's source; a tampered, mismatched, or malformed cursor SHALL be
rejected with HTTP 400 `invalid_cursor`. The HMAC secret SHALL be a **keyring** keyed by
`key-id`, so the secret can rotate without invalidating outstanding cursors (dual-key window); an
unknown or retired `key-id` SHALL be rejected with HTTP 400 `invalid_cursor`.

#### Scenario: Tampered or cross-entity cursor is rejected

- **WHEN** a scan is called with a cursor whose HMAC fails or whose entity does not match the pipeline's source
- **THEN** the request is rejected with HTTP 400 and error code `invalid_cursor`

#### Scenario: Key rotation keeps previously-issued cursors valid

- **WHEN** the HMAC keyring adds a new key while retaining a prior key, and a caller echoes a cursor signed by the prior key
- **THEN** the cursor still verifies and the scan proceeds

#### Scenario: Unknown key-id is rejected

- **WHEN** a cursor carries a `key-id` not present in the keyring
- **THEN** the request is rejected with HTTP 400 and error code `invalid_cursor`

### Requirement: Sensitive-column re-check on scan

Independently of the target-enrichment access grant, the scan SHALL re-apply the sensitive-column
declaration (see [enrichment-registry](../enrichment-registry/spec.md)) against the **scanning caller's**
identity: if the scan's projection includes a `sensitive`-flagged column of the pipeline's **read source**
and the caller may not see them (no `TABLE_SENSITIVE` on the table that owns the column, and
`security.mode` is not `none`), the
scan SHALL be rejected with HTTP 403 `sensitive_column_not_entitled`. This holds even though the stored
declaration was registered by an entitled author, so a runner without that entitlement cannot read
sensitive data,
and it holds whether the read source is the target enrichment's `source_table` or a declared one.

The refusal SHALL carry that code **however the sensitive column is reached** — projected directly via a
bound column, or referenced by the pipeline's `filter` or `trigger.ready_when.signal`. A non-entitled caller
cannot even parse the stored predicate, because the SQL front end validates against a schema from which
sensitive columns are hidden for that caller; such a parse failure SHALL be re-attributed to
`sensitive_column_not_entitled` rather than reported as a malformed declaration (422), which would both mislead
and misstate whose problem it is. An entitled caller's parse failure SHALL be reported unchanged, so a
genuinely broken declaration still reports accurately to the caller who can fix it.

#### Scenario: Non-entitled runner scanning a sensitive-referencing pipeline is rejected

- **WHEN** a caller who may not see sensitive columns calls `POST /v1/pipelines/{name}/scan` for a pipeline
  whose projection includes a `sensitive`-flagged column of the pipeline's read source
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: The refusal holds for a sensitive column referenced only by the pipeline's filter

- **WHEN** the same caller scans a pipeline whose `filter` (or `trigger.ready_when.signal`) references a
  `sensitive` column of the read source that no binding projects
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not with
  a validation error

#### Scenario: FULL_ADMIN runner scanning a sensitive-referencing pipeline is allowed

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the read source (or any caller in
  `none` mode) calls the same scan
- **THEN** the sensitive column is projected and the scan proceeds normally

### Requirement: Group-input payload caps

The group assembly response SHALL be bounded by **both** a row count and a response byte size, each
with a configured default and maximum, and additionally by the pipeline's `trigger.member_select.limit` where one is
declared. A group whose members carry `sensitive` body columns can be megabytes, so a row cap alone would
leave the real failure mode unbounded. The **effective row cap** SHALL be the smallest of the caller's
`limit`, the configured default or maximum that applies to it, and the pipeline's `trigger.member_select.limit`; it
SHALL be applied in the selection phase, so a pipeline declaring a small `limit` obtains a correspondingly
small read.

When either cap trims the group, **or** the pipeline's member selection narrowed it, **or** the projection phase
could not find a member the selection phase qualified, the response SHALL set
`truncated: true`, so a caller can record that its input was partial and a downstream consumer can
identify a partial result. Policy narrowing counts as truncation because the resulting label was computed
from part of the group, and a label that saw two of a conversation's three hundred members SHALL NOT
report itself complete. When the response carries every member of the group, `truncated` SHALL be `false`.

When either cap trims the group, the members that SHALL be retained are the **most recent** ones — the
tail of the source's total order, not its head — unless the pipeline declares a `trigger.member_select`, in which case
its policy decides which members qualify. A group-grain label describes the group's current state, so
absent a declared policy the members that bear on it are the newest; retaining the oldest would answer a
question about a conversation's outcome from its opening. This is a statement about **which members
qualify**, and is deliberately independent of the **order they are returned in**: the response ordering is
unchanged and remains the source's total order, ascending. A caller that trims further SHALL therefore
receive a window it can trim from the front, and the two layers SHALL agree about which end is
expendable.

That agreement is automatic only for the **default** policy, where the retained members are the newest and
the oldest is by definition the expendable end. A pipeline declaring a `trigger.member_select` chooses its members by
its own ranking, which the ascending response does not express, so a caller trimming from the front may drop
the member the pipeline ranked highest. Such a pipeline's `limit` SHALL therefore be understood as a selection the
caller is expected to consume whole, and SHOULD be set low enough to fit the caller's own budget rather than
relying on the caller to trim it.

The single-member exception is unchanged in effect: a group whose first retained member alone exceeds the
byte cap SHALL still return that member rather than an empty page, so "this group has no members" stays
distinguishable from "this group has one oversized member".

#### Scenario: A row cap trims the group

- **WHEN** a group has more members than the effective row limit
- **THEN** the response returns at most that many rows and `truncated: true`

#### Scenario: A byte cap trims the group

- **WHEN** a group's members are individually small enough in number but exceed the response byte cap
- **THEN** the response is trimmed to fit the cap and `truncated: true`

#### Scenario: A declared member limit lowers the effective row cap

- **WHEN** a group pipeline declares `trigger.member_select.limit` below the configured and caller-supplied row limits
- **THEN** the effective row cap is the declared limit, it is applied in the selection phase, and at most
  that many members have their bound columns read

#### Scenario: A member lost between the two phases reports truncation

- **WHEN** the projection phase cannot find a member the selection phase qualified, because the source
  re-ingested that row between the two reads
- **THEN** that member is omitted rather than failing the request, and the response sets `truncated: true`

#### Scenario: A group narrowed by member selection reports truncation

- **WHEN** a pipeline's member selection retains fewer members than the group contains
- **THEN** the response sets `truncated: true` even though no configured cap was reached

#### Scenario: A trimmed group retains its most recent members

- **WHEN** either cap trims a group for a pipeline declaring no member selection
- **THEN** the members returned are the most recent ones by the source's total order, and the earlier
  members are the ones dropped

#### Scenario: A trimmed group is still returned in ascending order

- **WHEN** either cap trims a group, or a member selection narrows it
- **THEN** the retained members are returned in the source's total order, ascending, exactly as an
  untrimmed group is

#### Scenario: A group within both caps is not marked truncated

- **WHEN** a group fits within both the row and the byte cap and its every member is retained
- **THEN** every member is returned and `truncated: false`

#### Scenario: A single oversized member is returned rather than an empty page

- **WHEN** a group's first retained member alone exceeds the response byte cap
- **THEN** that member is returned with `truncated: true`, rather than an empty `rows` array

### Requirement: Declarable group member selection

A `group` pipeline SHALL be able to declare **which** of a group's members bear on its label, through an
optional `trigger.member_select` object carrying an optional `prefer_sql`, an optional `order_by`, and a `limit`.
Without it the endpoint's existing policy applies unchanged: the newest members by the source's total
order, bounded by the configured caps.

The three fields SHALL compose as one selection policy, evaluated over the group's members after the
membership `filter` and the safety lag:

- `prefer_sql` — a boolean predicate over the read source's **entity fields**, validated by the same
  closed grammar and field resolution as `filter`. Members satisfying it are the **candidate set**.
  When **no** member satisfies it, the candidate set SHALL fall back to **all** members of the group, so a
  declaration declaring a preference still produces a label for a group that cannot satisfy it.
- `order_by` — an ordered list of `{column, direction}` over the read source's **entity fields**, applied
  to the candidate set. It SHALL be followed by the source's total order descending as a deterministic
  tiebreak, so selection is stable across re-assembly of an unchanged group.
- `limit` — the number of candidates retained, at least 1 and at most the configured group-fetch maximum
  row count.

`trigger.member_select` SHALL be valid only for a pipeline whose `trigger.kind` is `group`; declaring it on any other
trigger kind SHALL be rejected with HTTP 422 `pipeline_validation_failed`. **This one SHALL be refused
on every write, armed or not**, unlike the checks below: a `member_select` beside a `schedule` trigger
is a contradiction inside the declaration the author just sent, legible with nothing else resolved and
meaning nothing at any later stage, and the storage guard that refuses the pair is kept unconditional —
so a service-layer refusal is what turns it into a `pipeline_validation_failed` naming the field rather
than a database-integrity failure reported as something else. A `member_select` on a declaration that
does **not name a trigger kind yet** is not that contradiction and SHALL be storable.

The remaining checks are part of the declaration gate and SHALL run **where the gate runs** — on a
write that arms the pipeline and on `POST /v1/pipelines/{name}/preview` of a disabled one — rather
than at registration, and SHALL NOT be re-asked at the group-assembly endpoint, which runs no part of
this gate (see "The governed data plane refuses an absent declaration, and materialize re-checks two
rules"): a `prefer_sql`
that fails the grammar, either field naming a field reachable on neither the read source nor its
`ACTIVE` enrichments, or a `limit` outside its range SHALL each be rejected with HTTP 422 `pipeline_validation_failed`. An
`order_by` direction SHALL be `asc` or `desc`; any other value is rejected as a malformed request body
(HTTP 400) **on every write including a draft save**, as every other enum-valued field of the API is —
that refusal is request binding, not the gate, and deferring it would mean storing bytes no member of
the model can hold. A `trigger.member_select` referencing a `sensitive`
column — of the read source or of one of its `ACTIVE` enrichments — SHALL be treated exactly as
`filter` is: group assembly by a caller who may not see sensitive columns SHALL be rejected with HTTP
403 `sensitive_column_not_entitled`, and the check runs against the calling identity on every call.
On the authoring surfaces, which are `FULL_ADMIN`-only, the in-gate half of that check is defence in
depth (see [enrichment-registry](../enrichment-registry/spec.md), "Static governance gate on arming and
execution").

Selection is a statement about **which members qualify** and SHALL NOT change the order members are
returned in: the response ordering remains the source's total order, ascending.

#### Scenario: A preference narrows the candidate set

- **WHEN** a group pipeline declaring `trigger.member_select.prefer_sql` assembles a group in which some members
  satisfy that predicate
- **THEN** only members satisfying it are candidates, and the members that do not are excluded from the
  response

#### Scenario: A preference no member satisfies falls back to the whole group

- **WHEN** the same pipeline assembles a group in which **no** member satisfies `prefer_sql`
- **THEN** all members of the group become candidates and a label is still produced, rather than the group
  returning empty

#### Scenario: Candidates are ordered by the declared ordering

- **WHEN** a group pipeline declares `trigger.member_select.order_by` over a source column and its candidate set is
  larger than `limit`
- **THEN** the retained members are those ranking first by that ordering, with the source's total order
  descending breaking ties, and re-assembling the same unchanged group retains the same members

#### Scenario: A selection policy over an enrichment column is accepted

- **WHEN** a group pipeline declares a `trigger.member_select.prefer_sql` or `order_by` naming a column of an
  `ACTIVE` enrichment of its read source
- **THEN** the pipeline is accepted, and the selection phase emits the grain join for that enrichment so the
  predicate and the ordering resolve

#### Scenario: A ranked selection is still returned in the source's total order

- **WHEN** a pipeline's `order_by` ranks an older member above a newer one and both are retained
- **THEN** the response returns them ascending in the source's total order, not in the order the ranking put
  them in

#### Scenario: A limit smaller than the group retains exactly that many members

- **WHEN** a group has more candidates than `trigger.member_select.limit`
- **THEN** exactly `limit` members are returned

#### Scenario: Member selection is rejected on a non-group pipeline

- **WHEN** a pipeline whose `trigger.kind` is not `group` is created or updated with `trigger.member_select`,
  whether or not the write leaves it enabled
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, and
  nothing is stored

#### Scenario: A member selection on a declaration with no trigger kind yet is stored

- **WHEN** a disabled pipeline is patched with a `trigger.member_select` and no `trigger.kind`
- **THEN** the write is accepted, because the contradiction above is not present and the author has
  not yet said what the trigger is

#### Scenario: An invalid member selection is rejected when the pipeline arms

- **WHEN** a write arms a group pipeline, or a disabled one is previewed, whose `trigger.member_select` has a
  `prefer_sql` that fails the closed grammar, a
  `prefer_sql` or `order_by` naming a field reachable on neither the read source nor its `ACTIVE`
  enrichments, or a `limit` below 1 or above the configured maximum row count
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: The same invalid selection is storable while the pipeline stays disabled

- **WHEN** that declaration is saved on a pipeline that stays disabled
- **THEN** the write is accepted, no schedule or sync feed picks the pipeline up, and the refusal
  arrives the moment the author arms or previews it rather than at the save

#### Scenario: A malformed order_by direction is rejected on any write

- **WHEN** a `trigger.member_select.order_by` entry declares a `direction` outside `{asc, desc}`, on a
  draft save as much as on an arming write
- **THEN** the request is rejected with HTTP 400 as a malformed request body, because that is request
  binding rather than the declaration gate

#### Scenario: A non-entitled caller cannot use a selection over a sensitive column

- **WHEN** a caller who may not see sensitive columns, holding the target enrichment's WRITE grant,
  assembles a group for a pipeline whose `trigger.member_select` references a `sensitive` column of the
  read source or of one of its `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A pipeline declaring no member selection is unchanged

- **WHEN** a group pipeline carrying no `trigger.member_select` assembles a group
- **THEN** the members retained are the most recent ones by the source's total order, bounded by the
  configured caps, exactly as before

### Requirement: Sensitive-column re-check on group assembly

The group assembly endpoint SHALL re-apply the sensitive-column rule against the **calling** identity,
exactly as the scan does, and SHALL bind the check to the columns the **declaration declares** — its bound
input bindings, its membership filter, and its `trigger.member_select` — rather than to the columns a given read
phase happens to project. If any of those is a `sensitive`-flagged column of the read source's
entity — a source column or an enrichment column reached through the grain join, the two governed
alike — and the caller may not see them (no `TABLE_SENSITIVE` on the owning table, and
`security.mode` is not
`none`), the request SHALL be rejected
with HTTP 403 `sensitive_column_not_entitled`, **before** either phase reads anything. Sensitive columns
SHALL NOT be silently stripped from the projection — a group input missing the very column the pipeline was
written to read would yield a quietly wrong label, so the misconfiguration fails loudly instead. The scan
and the group assembly SHALL share one implementation of this check so their behaviour cannot diverge.

Naming the owning table SHALL NOT be a way around the check: a declaration that reads
`"dial_usage_log_payload.request_body"` is refused for an unentitled caller on the same terms as one
reading a `sensitive` column of the source itself, and the refusal SHALL carry
`sensitive_column_not_entitled` rather than an unknown-field error, so the operator sees why the
declaration was refused rather than being told the column does not exist.

#### Scenario: An entitled caller receives body columns

- **WHEN** an entitled caller (or any caller in `none` mode) assembles a group for a pipeline whose
  projection includes `"dial_usage_log_payload.request_body"` or
  `"dial_usage_log_payload.response_body"`
- **THEN** those columns are present in the returned member rows, joined from the payload table by the
  grain key

#### Scenario: A non-entitled caller is rejected rather than served a stripped group

- **WHEN** a caller who may not see sensitive columns assembles the same group
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and no
  partially-projected group is returned

#### Scenario: Entitlement is enforced before the selection phase reads

- **WHEN** a caller who may not see sensitive columns assembles a group for a pipeline that binds a sensitive
  column, even though the selection phase would not project it
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and
  neither phase issues a read

#### Scenario: A member row whose payload has expired carries nulls

- **WHEN** an entitled caller assembles a group whose members include an event older than the payload
  table's retention
- **THEN** the member row is returned with its body columns null, and the assembly does not fail

### Requirement: An unaddressable group key is excluded by the read

Every pipeline-scoped read of a group pipeline whose `trigger.group_by` names an enrichment column SHALL carry an
ADAS-emitted predicate excluding a group key that is NULL or the empty string, in addition to the pipeline's
own `filter`. This covers the incremental scan and the group-input assembly alike.

Both exclusions are required and for different reasons. NULL is what an unmatched outer join yields for
a `nullable` enrichment column. The empty string is a **derived** value — an enrichment may compute it
for a row that genuinely has no key — and, because ClickHouse's `join_use_nulls` is 0, is also how an
unmatched join renders a non-nullable column. Neither value addresses a row of the target enrichment: a
NULL key cannot be attributed to any group, and an empty key would collect every keyless row of the
source into a single group that corresponds to nothing.

The predicate SHALL be emitted by ADAS rather than required of the author's `filter`. A declared
requirement cannot be verified — whether a SQL predicate excludes NULL and the empty string is not
decidable from its parse tree, and any syntactic check is satisfied by a reference that excludes nothing
— so a requirement would read as a guarantee it does not provide. A pipeline whose `filter` already
excludes those rows SHALL be unaffected, the emitted predicate being redundant for it.

A pipeline whose `trigger.group_by` names a column of the read source itself SHALL NOT gain the predicate, so no
declaration registered before this capability changes the statement it renders.

#### Scenario: A NULL group key is excluded from the scan

- **WHEN** a group pipeline whose `trigger.group_by` names an enrichment column is scanned, and a source row's
  enrichment cell is absent so the joined value is NULL
- **THEN** that row is absent from the scan page, and no group is marked for it

#### Scenario: An empty group key is excluded from the scan

- **WHEN** the same pipeline is scanned and a source row's enrichment cell holds the empty string
- **THEN** that row is absent from the scan page, so keyless rows do not accumulate into a single group

#### Scenario: The same exclusion applies to group assembly

- **WHEN** `POST /v1/pipelines/{name}/group-rows` is called for that pipeline
- **THEN** members whose group key is NULL or empty are excluded from the assembled result

#### Scenario: A source-column group key renders unchanged

- **WHEN** a group pipeline whose `trigger.group_by` names a column of its read source is scanned
- **THEN** no additional predicate is emitted and the statement is unchanged

### Requirement: Pipeline-scoped incremental scan endpoint

The system SHALL expose `POST /v1/pipelines/{name}/scan` accepting a body `{cursor?, limit?, from?, to?}`
and returning `{rows[], nextCursor, hasMore, lagSeconds}`. The system SHALL resolve the pipeline's
stored, already-validated source, filter, referenced columns, and grain from the `pipeline` record and
the target enrichment's catalog row; the caller SHALL NOT supply a table name, filter, or column list.
The scan SHALL require the pipeline to **exist** and its declaration to be **complete** for its kind,
but NOT to be `enabled` (it writes nothing, and a disabled pipeline's dry-run preview samples through
it). The two conditions are separate
and the tolerance is bounded to the first: "already-validated" above is what the endpoint has always
assumed of the stored declaration, and once a declaration can be stored before it passes the gate,
"not enabled" stops implying "was validated once". So a **disabled and complete** pipeline still
scans — that is what a disabled pipeline's preview reads through — while a pipeline whose
declaration is incomplete SHALL be refused with HTTP 422 `pipeline_validation_failed` naming what
is missing, because there is no source, filter or grain to resolve and the alternative is a query
compiled from absent members. A pipeline whose `transform.type` is `sql` SHALL be refused with HTTP 422
`pipeline_validation_failed` pointing at `POST /v1/pipelines/{name}/materialize` **before** the
completeness check, in either state, since finishing the declaration would not make it scannable. The refusal is **absence only**: a complete declaration SHALL NOT be
re-judged here, whatever its state, because drift the operator did not cause must not stop a
running pipeline's scan (see "The governed data plane refuses an
absent declaration, and materialize re-checks two rules", which owns that rule for all four
data-plane endpoints). An unknown pipeline SHALL return HTTP 404 `unknown_pipeline`. When `limit` is omitted the
system SHALL apply a configured default; an over-limit request SHALL be clamped or rejected
consistent with the existing query-limit policy.

`from` and `to` are optional ISO-8601 instants bounding the source's version column as the half-open
range `(from, to]`. They are an additional predicate only: they compose with `cursor`, the keyset
seek, and the safety-lag rather than replacing any of them, and they do not change ordering,
projection, `final=1`, or cursor encoding. A request where `from` is greater than or equal to `to`
SHALL be rejected with HTTP 400, so an inverted window is never mistaken for an exhausted one.

#### Scenario: Scan resolves the pipeline and returns a bounded page

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for an existing pipeline with a valid or omitted `cursor`
- **THEN** the system runs the pipeline's governed scan and returns up to `limit` rows plus
  `nextCursor`, `hasMore`, and `lagSeconds`

#### Scenario: Scan of a disabled but complete pipeline succeeds

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline that exists, is `enabled=false`,
  and whose declaration is complete for its kind
- **THEN** the scan proceeds normally (a read path; it writes nothing)

#### Scenario: Scan of an incomplete declaration is refused

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline that exists but whose declaration
  is not yet complete — no transform, no `trigger.kind`, or a `group` trigger with no
  `trigger.group_by`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` — by the shared
  no-transform refusal when the declaration carries no `transform`, and otherwise naming every absent
  member in one message — and no query is compiled and no cursor is issued

#### Scenario: Scan of a sql-transform pipeline points at materialize before completeness

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline, disabled or not, whose
  `transform.type` is `sql`, whether or not the rest of its declaration is complete
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` telling the caller to use
  `POST /v1/pipelines/{name}/materialize`, and that refusal is reported ahead of any completeness
  problem, because no completion of the declaration would make the row scan the right endpoint

#### Scenario: Scan of an unknown pipeline is rejected

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with a name that does not resolve to a pipeline
- **THEN** the request is rejected with HTTP 404 and error code `unknown_pipeline`

#### Scenario: A bounded window returns only rows inside it

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with `from` and `to`
- **THEN** only rows whose version column is greater than `from` and less than or equal to `to` are
  returned, and rows on either side of the window are excluded regardless of the cursor position

#### Scenario: A window composes with the cursor across pages

- **WHEN** a caller pages through a window by echoing each `nextCursor` while re-supplying the same
  `from` and `to`
- **THEN** each row inside the window is returned exactly once and paging stops with `hasMore=false`
  at the window's end rather than continuing to the source head

#### Scenario: An inverted window is rejected

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with `from` greater than or equal to `to`
- **THEN** the request is rejected with HTTP 400 rather than returning an empty page

### Requirement: Pipeline-scoped versioned write-back endpoint

The system SHALL expose `POST /v1/pipelines/{name}/rows` accepting a non-empty `rows` array, symmetric
with the scan endpoint. The system SHALL resolve the pipeline's `target` and validate that
each row's column-set is a subset of {the pipeline's declared output columns} ∪ {the grain key} ∪
{permitted provenance columns}; a row naming any other column SHALL be rejected with HTTP 422
`write_column_not_allowed`. The caller SHALL NOT name a table and SHALL NOT be able to write
outside its pipeline's declared columns.

For a pipeline whose `trigger.kind` is `group`, a row naming the pipeline's **`trigger.group_by`** SHALL be accepted as
naming the target's **plain grain key**, and the column SHALL be renamed to the grain key before validation
and before the write. The two spellings coincide unless the group key is reachable only through an
enrichment of the read source, where `trigger.group_by` carries the qualified flat name `<enrichment>.<column>`
that both pipeline-scoped reads project it under — the scan under *both* names by design. Since the reads hand
the caller two spellings of one key, which one it echoes back SHALL NOT decide whether the write is
accepted; the qualified name is no column of the target, so it is **renamed rather than admitted**. This
widens nothing: registration has already validated that `trigger.group_by` resolves to the grain, no column outside
the pipeline's declared set becomes writable, and every other column is still rejected as below. A row naming
**both** spellings SHALL be accepted only when their values are equal, and SHALL otherwise be rejected with
HTTP 422 `validation_error` — disagreeing values name two grains for one row, and keeping either silently
would write the label against the wrong key.

The **permitted provenance columns** SHALL be `pipeline_generation`, `evaluator_version`, `model`,
`enriched_at`, `group_version`, and `truncated`, supplied by the caller and written as ordinary user
columns — except `evaluator_version`, which stays in this set only so that naming it is answered by the
refusal below rather than by `write_column_not_allowed`, whose remedy is the opposite one. Membership
here settles which code a row gets, not whether it is accepted. `group_version` records the version of the input the row was computed from — for a `group`
pipeline, the maximum version-column value across the members the caller saw — and `truncated` records
whether that input had been trimmed, whether by the group-assembly caps or by the caller's own budget.
The two exist so a **truncated group's label is distinguishable from a complete group's**, and so a
label's input version is identifiable after the fact: without them a partial judgement reads as a full
one, and only the caller's private state knows which version of the group was judged.

**`pipeline_generation`** records the pipeline `generation` the row was derived under, and SHALL be the
provenance key for the pipeline's declaration. `generation` moves when the prompt or the expressions
change and also when a `filter` or `member_select` change alters which rows are derived and from what.
Resolving a value back to the declaration it names is the pipeline snapshot at that revision (see
[audit-trail](../audit-trail/spec.md)), which carries the whole declaration and the identity that wrote
it.

**A caller-supplied `evaluator_version` SHALL be rejected** with HTTP 422 `validation_error` naming the
column. Nothing writes that column, and the feed offers no member a caller could derive it from —
provenance is stamped from the pipeline's `generation` — so a row naming it is not an ambiguity to
resolve but **evidence that the caller was built against an older feed**. Admitting it would let a
stale consumer write generation numbers into a column holding legacy version integers, mixing two
number spaces in one column.

**This refusal is not the deploy detector, and SHALL NOT be specified as one.** This endpoint already
requires an exact-matched `Adas-Contract-Version` (see "The governed data plane requires an exact runner
contract version"), decided before any pipeline lookup, so a runner built against an older contract is
refused **HTTP 409** here and never reaches this check — a test asserting 422 for that caller would
observe 409. What this refusal governs is the caller the header gate does not reach the same way: a
hand-written request, an out-of-date `adas-mcp`, any holder of a `WRITE` grant that declares the current
contract and still names the retired column. It is the repository's ordinary rule that a retired
spelling is refused rather than absorbed, applied to a provenance column name.

**`evaluator_version` SHALL remain a permitted name and its column SHALL NOT be removed**, so that a
target carrying already-stamped rows keeps a column those values are valid in and no ClickHouse schema
change is forced. Nothing writes it: rows already stamped keep their integers, new rows carry
`pipeline_generation`, and the two number spaces never mix.

**Provenance is best-effort, so this stamp can silently write nothing** — and by design it almost never
does. Creation-time injection reaches new tables only, so an enrichment materialized before the column
was introduced does not carry `pipeline_generation` of its own accord. The system therefore
**reconciles the column onto every existing enrich target at startup**, idempotently and with no operator action (see
[table-management](../table-management/spec.md), "The provenance stamp column is reconciled onto
existing enrich targets at startup"); that reconciliation, not a hand-written schema patch, is what
turns the stamp on for a target that predates the column. A target that still does not physically carry
the column — the reconciler is disabled, or its last attempt failed and is retried on the next
startup — drops the value exactly as it drops any provenance column it lacks, and its label columns are
written regardless.

Every permitted provenance name SHALL be **un-prefixed**: the `_` prefix is reserved for system columns,
which are not caller-settable at all, and a user-declared enrichment column may not begin with `_`, so a
provenance column lives in the user namespace and is declared on the target like any other column.

Provenance is informational lineage and is therefore **optional on the target**: a permitted provenance column
that the target enrichment does not physically carry SHALL be silently dropped from the write
(best-effort lineage) rather than failing it, so an enrichment created without provenance columns
still enriches successfully. This tolerance applies only to the permitted provenance names; a
non-provenance column outside the pipeline's declared set is still rejected as above, and the target's
physical-column check still applies to every remaining column. The version column `_updated_at`
SHALL be stamped by ClickHouse and SHALL NOT be accepted as caller input. After validation the
write SHALL delegate to the existing materialized-table write path. An unknown pipeline SHALL return
HTTP 404 `unknown_pipeline`.

Because the write delegates to the materialized-table write path, a value for a `timestamp`-typed
column on this endpoint SHALL be accepted in exactly the **same single format** as on
`POST /v1/tables/{name}/rows` — ISO-8601 with an explicit zone, either `Z` or an explicit offset — and a
value not of that form SHALL be rejected the same way, with HTTP 422 and error code `validation_error`
naming the row, the column, the value and the specific defect (see the `table-management` capability for
the normative format and rejection detail). The two row-write surfaces SHALL NOT diverge on which
timestamp values they accept. A provenance column such as `enriched_at`, when declared on the target as
a `timestamp`, is therefore written in ISO-8601 form; when declared as a `string` its value is stored
verbatim and is not inspected.

The same holds for every other value check of the materialized-table write path (integer and decimal
ranges, numeric shape, `date` form and range, `enum` membership, null array elements and map values):
a value this endpoint forwards SHALL be accepted or rejected exactly as `POST /v1/tables/{name}/rows`
would, with HTTP 422 `validation_error`. In particular an integer output column refuses `4.0` as not a
whole number, as ClickHouse already did with an opaque error.

#### Scenario: A well-formed write is accepted and collapses by grain

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with rows whose columns are all within the pipeline's outputs plus grain and provenance
- **THEN** the rows are written to the target enrichment and a subsequent `final=1` read returns the latest version per grain key

#### Scenario: A row records the pipeline generation that derived it

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with rows carrying `pipeline_generation` and the
  target enrichment declares that column
- **THEN** the value is written as an ordinary column, and a query over the enrichment can partition
  rows by the declaration that produced them

#### Scenario: An already-stamped evaluator_version column keeps working

- **WHEN** a target enrichment carries `evaluator_version` with already-stamped rows, and a write
  carries `pipeline_generation` but no `evaluator_version`
- **THEN** the write succeeds, the already-stamped rows keep their values, and no schema change to the target
  is required

#### Scenario: A caller-supplied evaluator_version is refused as a stale-consumer signal

- **WHEN** a caller built against an older feed stamps `evaluator_version`, whether or not the target
  carries the column
- **THEN** the request is rejected with HTTP 422 `validation_error` naming the column, no row of the
  batch is written, and rows already stamped in that column are unchanged

#### Scenario: A row naming both provenance spellings is rejected whatever the values

- **WHEN** a row carries `evaluator_version` and `pipeline_generation`, with equal or with different
  values
- **THEN** the request is rejected with HTTP 422 `validation_error`, because the refusal is of the
  spelling rather than of a disagreement between two claims

#### Scenario: A target without pipeline_generation drops the stamp rather than failing

- **WHEN** a write carrying `pipeline_generation` and no `evaluator_version` targets an enrichment
  that carries `evaluator_version` but not `pipeline_generation`, because
  the startup reconciler is disabled or has not yet completed against it
- **THEN** that stamp is dropped, the label columns are written successfully, and the target records no
  generation until a startup reconciles the column onto it

#### Scenario: An ISO-8601 timestamp is accepted on the pipeline-scoped write

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with a row whose `timestamp`-typed column (for example the
  provenance column `enriched_at`) carries an ISO-8601 value such as `2026-08-11T10:00:00Z`
- **THEN** the write is accepted and stores the instant it denotes, identically to the same value written
  through `POST /v1/tables/{name}/rows`

#### Scenario: A timestamp not in the accepted format is rejected the same way on both write surfaces

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with a row whose `timestamp`-typed column carries a value
  that is not ISO-8601 with an explicit zone — for example the space-separated form or a zone-less value
- **THEN** the request is rejected with HTTP 422 and error code `validation_error` naming the row, the
  column, the value and the specific defect, and no row of the batch is written

#### Scenario: A provenance column absent from the target is skipped, not rejected

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with rows carrying a permitted provenance column (e.g. `enriched_at`) that the target enrichment does not physically have
- **THEN** that provenance column is silently dropped and the remaining columns are written successfully (the write is not failed and no dead-letter is produced)

#### Scenario: A group pipeline records its input version and truncation

- **WHEN** a `group` pipeline writes its one row per group key with `group_version` and `truncated`, and the target enrichment carries both columns
- **THEN** both are written as ordinary columns and a query over the enrichment can tell a label computed from a trimmed group from one computed from a complete group

#### Scenario: Group provenance absent from the target is skipped

- **WHEN** the same write targets an enrichment that carries neither `group_version` nor `truncated`
- **THEN** both are silently dropped and the label is written successfully

#### Scenario: A group pipeline's write may name the key by its declared `trigger.group_by`

- **GIVEN** a group pipeline whose `trigger.group_by` is the qualified flat name of an enrichment column (for example
  `usage_client_identity.client_session_id`) and whose target enrichment's grain key is `client_session_id`
- **WHEN** `POST /v1/pipelines/{name}/rows` is called with a row naming the key under that qualified spelling
- **THEN** the write is accepted and the row is keyed by `client_session_id`, indistinguishable from the
  same row written under the plain grain key

#### Scenario: A row naming the group key under both spellings with different values is rejected

- **WHEN** a row of such a pipeline names both `<enrichment>.<column>` and the plain grain key with different
  values
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, and no row is written

#### Scenario: A write naming an undeclared column is rejected

- **WHEN** a row references a column that is neither a declared output of the pipeline, the grain key, nor a permitted provenance column
- **THEN** the request is rejected with HTTP 422 and error code `write_column_not_allowed`

#### Scenario: Caller-supplied `_updated_at` is rejected

- **WHEN** a write row attempts to set the system version column `_updated_at`
- **THEN** the request is rejected (system column is not caller-settable) and ClickHouse stamps the version on insert

### Requirement: Pipeline-scoped group-input assembly endpoint

The system SHALL expose `POST /v1/pipelines/{name}/group-rows` accepting a body `{groupKey, limit?}` and
returning `{rows[], truncated}`. It SHALL resolve the pipeline's source, membership `filter`, projection,
member selection, and ordering from the stored pipeline; the caller SHALL supply **only** the group key, and
SHALL NOT name a table, a filter, a column list, an ordering, or a member-selection policy. The assembled
result SHALL comprise the source rows whose `trigger.group_by` column equals `groupKey` and which satisfy the
pipeline's `filter`, ordered by the source's total order, read with `final=1`, and bounded by the caps and the
member selection below. The pipeline SHALL be a `group` pipeline; a request for a pipeline with any other trigger kind
SHALL be rejected with HTTP 422 `pipeline_validation_failed`. An unknown pipeline SHALL return HTTP 404
`unknown_pipeline`.

The assembly SHALL be read in **two phases**, so that its cost in the data store is proportional to the
members it returns rather than to the size of the group:

1. **Selection** reads only inexpensive columns — the `trigger.group_by` column, the cursor columns, the columns
   the membership filter references, and the source's full ordering key — and determines which members
   qualify under the caps and the member selection. It SHALL NOT project the pipeline's bound input-binding
   columns. The columns `trigger.member_select` references are evaluated by the data store, in the preference
   predicate and in the ordering, and SHALL NOT be projected either: they are not part of the response, so
   projecting them would read a value per member only to discard it, and would return a body column to this
   phase for a policy that ranks by one.
2. **Projection** reads the pipeline's bound input-binding columns for **only the qualifying members**, each
   addressed by its value for **every column of the source's ordering key**, so that the data store can
   prune its read to the storage granules holding those members. Addressing the members by a column that
   the source does not index or order by SHALL NOT be used *in place of* that tuple: it would not prune the
   read and would leave the endpoint's cost proportional to the whole group. The source's identity column
   SHALL be carried **alongside** the tuple, because an ordering key is not required to be unique and two
   members sharing one would otherwise be indistinguishable when the two phases' rows are merged; and the
   phase SHALL read at most one row per qualifying member, so a source whose identity column is not in fact
   unique cannot restore an unbounded read.

A group whose pipeline binds **no** input columns, and a group whose read source declares **no ordering key**,
SHALL each be assembled by a single-phase read with the projection applied directly — in the first case
there is nothing expensive to defer, and in the second the members cannot be addressed for a second read
at all. The two-phase read is an optimisation of *how* the contract is met and SHALL NOT change the
response: the members, their columns, their ascending order, and `truncated` SHALL be the same as a
single-phase read of the same group would produce.

The endpoint SHALL apply the **same safety-lag** as the scan, in the selection phase, so the members
qualifying are drawn from the same window the scan has surfaced. Assembling from rows the scan has not yet
surfaced would produce a group whose contents disagree with the group version the caller dirty-marked, so
consistency between the two reads takes precedence over assembling the newest possible group.

A group key that matches no rows — because it has aged out of the source's retention, because the
membership filter excluded every member, or because it never existed — SHALL return HTTP 200 with an
empty `rows` array and `truncated: false`. It SHALL NOT be an error: groups are discovered from a row
scan and expire with the source, so an empty group is a normal race rather than a caller mistake.

#### Scenario: A group's members are assembled, filtered and ordered

- **WHEN** `POST /v1/pipelines/{name}/group-rows` is called with a `groupKey` for a `group` pipeline
- **THEN** the response contains the source rows sharing that group key which satisfy the pipeline's
  membership filter, in the source's total order, projected to the pipeline's referenced columns

#### Scenario: The caller supplies only the group key

- **WHEN** any `group-rows` request is made
- **THEN** the source table, membership filter, projection, member selection, and ordering are resolved
  from the stored pipeline, and no caller-supplied table, filter, column list, or selection policy is accepted

#### Scenario: Bound input columns are read only for the qualifying members

- **WHEN** a group pipeline binding a body column assembles a group whose members outnumber those the caps and
  member selection retain
- **THEN** the bound columns are read only for the retained members, addressed by the source's ordering
  key, and are not read for the members that were excluded

#### Scenario: The selection phase does not read the bound input columns

- **WHEN** the selection phase of any assembly runs
- **THEN** it projects the group key, the cursor columns, the membership filter's columns and the source's
  ordering key, and does not project the pipeline's bound input columns nor the columns its member selection
  merely ranks by

#### Scenario: A pipeline with nothing to defer is assembled in one phase

- **WHEN** a group pipeline declares no input bindings, or its read source declares no ordering key
- **THEN** the group is assembled by a single-phase read whose response is identical in members, columns,
  order and `truncated` to what the two-phase read would return

#### Scenario: A non-group pipeline is rejected

- **WHEN** `POST /v1/pipelines/{name}/group-rows` is called for a pipeline whose `trigger.kind` is not `group`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An unknown or empty group returns an empty page

- **WHEN** `group-rows` is called with a `groupKey` matching no rows
- **THEN** the response is HTTP 200 with `rows: []` and `truncated: false`

### Requirement: An ENRICH pipeline's read window is clamped to its referenced enrichments' positions

Every pipeline-scoped read that advances a cursor SHALL bound its window to the position through which each
enrichment its statements join has been materialized, taking the **lowest** such position when several
enrichments are joined. This applies to the incremental scan; the Class-1 materialize window carries the
same clamp (see [class1-enrichment](../class1-enrichment/spec.md)).

The clamp exists because an enrichment row lands **after** the source row it derives from, while a
pipeline's window advances on the source's cursor. Without it a row would be read while its enrichment cell
is still absent, and because the source row does not move again, the consequence is permanent: a group
pipeline would attribute the row to the wrong group or to none and never revisit it, a `trigger.ready_when.signal`
computed inside the scan query would return a verdict derived from an absent value, and a
bound `transform.inputs` value read at assembly would be missing from the prompt. Clamping the window removes
every one of those at once, because a row that does not enter the window contributes to none of them.

Clamping the scan window SHALL be sufficient for a group pipeline's members: the assembly endpoint selects
the **whole** group by key rather than only newly seen members, so a group that is marked is assembled
from every member it has. The unrecoverable case is a source row that is never marked at all, which is
what the clamp prevents.

The clamp's set and the **join** set are therefore not the same set, and each SHALL be derived for what it
governs. A statement SHALL join only the enrichments its own clauses name, so nothing pays a full hash
read of a table it never selects from. The clamp SHALL additionally cover the columns a group pipeline's
`transform.inputs` bind, which the scan page does not project but the assembly reads for the very rows
that page marks: a
bound value read while its enrichment cell is absent is a wrong prompt value, so the enrichment behind it
holds the scan window back even though no clause of the scan names it.

An enrichment that contributes no bound — one no pipeline targets — SHALL NOT hold the window back. A
`trigger.ready_when.idle` window SHALL be measured after the clamp, so a group's idle timer starts from the
activity the scan actually reported; the clamp therefore delays labelling by at most the lag of the
enrichments a pipeline reads.

A pipeline joining no enrichment SHALL be unclamped and its window unchanged.

#### Scenario: A window is held back to an enrichment's materialized position

- **WHEN** a pipeline referencing an enrichment column is scanned while source rows exist above that
  enrichment's recorded materialize position
- **THEN** those rows are excluded from the page, the returned cursor does not advance past that
  position, and a later scan returns them once the enrichment has reached them

#### Scenario: The lowest position bounds a multi-enrichment read

- **WHEN** a pipeline references columns of two enrichments materialized to different positions
- **THEN** the window is bounded by the lower of the two

#### Scenario: An enrichment with no pipeline imposes no bound

- **WHEN** a pipeline references a column of an `ACTIVE` enrichment that no pipeline targets
- **THEN** the window is not held back, because that enrichment will never gain another row

#### Scenario: A pipeline joining no enrichment is unclamped

- **WHEN** a pipeline referencing only its read source's own columns is scanned
- **THEN** the window is bounded only by the cursor, the safety-lag, and any requested bounds, exactly
  as before

#### Scenario: A clamped scan reports its lag rather than failing

- **WHEN** a pipeline's referenced enrichment has not been materialized at all, so no source row is inside
  the window
- **THEN** the scan returns an empty page and echoes the input cursor, rather than failing

### Requirement: An ENRICH pipeline's state reports its freshness exposure

A pipeline read SHALL report two freshness facts that are true **now** rather than as of its last run:
which enrichments it reads that contribute no bound to its window and why, and whether an enrichment it
reads has had its history re-derived since this pipeline last wrote. Both SHALL appear on the pipeline
representation returned by `GET /v1/pipelines?kind=enrich`, `GET /v1/pipelines/{name}`, and the create and update responses,
mirroring the equivalent fields on a pipeline.

The system SHALL NOT act on either fact. An enrichment whose history moved invalidates the values a
pipeline derived from it, and re-deriving them SHALL remain an operator action: the downstream of an `llm`
pipeline is metered, so cascading over history would spend budget no operator approved, and doing it safely
would require ordering, a cost ceiling and a kill switch. Reporting the exposure is what lets an
operator decide.

Both facts SHALL be **derived on read**, not stored: they describe the current state of the pipelines a
declaration points at, so a stored answer would report nothing for a pipeline that has never run and a stale
answer for one that ran before an upstream pipeline changed — in both cases exactly when an operator is
asking. A failure to resolve them SHALL degrade to reporting no diagnostics and SHALL NOT fail the pipeline
read, which is already reporting the pipeline's own state.

#### Scenario: An unbounded enrichment read is reported

- **WHEN** a pipeline reads an enrichment that contributes no bound to its window
- **THEN** the pipeline's representation names that enrichment and the reason it contributes no bound

#### Scenario: A re-derived upstream is reported and not acted on

- **WHEN** an enrichment a pipeline reads has its history re-derived after the pipeline last wrote
- **THEN** the pipeline's representation reports that enrichment and when its history moved, and no
  re-derivation of the reading pipeline is triggered

#### Scenario: A pipeline reading no enrichment reports nothing

- **WHEN** a pipeline referencing only its read source's own columns is read
- **THEN** it reports no unbounded reads and no re-derived upstream

#### Scenario: Undiagnosable state does not fail the read

- **WHEN** a pipeline's declaration cannot be resolved against its read source's current schema
- **THEN** the pipeline read succeeds and reports no freshness diagnostics

### Requirement: A pipeline may read every payload column, and `object` joins the nullability exemption

All five payload columns SHALL remain readable by an `enrich`/Class-1 pipeline after the split, so an
expression MAY reference `"dial_usage_log_payload.request_body"` and its siblings through the grain
join exactly as it referenced the bare names before.

The general rule SHALL stand: a value-producing reference to a **non-nullable** enrichment column is
refused, because an unmatched grain join renders it as its type default rather than `NULL`, so a
derived value could not distinguish "no payload row for this event" from a real value. Because the
payload table's retention is shorter than its parent's, unmatched rows are an ordinary occurrence
rather than an edge case, and the distinction is load-bearing. The three body columns satisfy the rule
directly: they are declared `nullable`.

An `object` column SHALL be **exempt** from that refusal, joining `array` and `map`, and for the same
two reasons: ClickHouse has no `Nullable(JSON)`, so the rule would refuse the type permanently; and
the type default is the *empty document*, which every accessor the capability exposes reads exactly as
it reads a present document lacking the key — `toJSONString` yields `'{}'`, subcolumn access yields
`NULL`, `json_extract_array` yields `[]`, and `json_extract_string_ci` yields `''`. Absence and
emptiness are therefore indistinguishable by construction, so no derived value can be silently
corrupted by the default. This is what permits `jwt_claims` and `request_tags` to live on the
enrichment at all.

A consumer SHALL NOT rely on `IS NULL` over a claim or header column to detect a missing payload row:
the rendered value is `{}`. A body column, being `Nullable(String)`, does read `NULL` and remains the
way to make that distinction.

#### Scenario: A pipeline reads a body column through the grain join

- **WHEN** an entitled author registers a `type=sql` pipeline whose expression reads
  `"dial_usage_log_payload.request_body"`
- **THEN** the pipeline is accepted, and its materialize statement emits the grain join for the payload
  enrichment

#### Scenario: A pipeline reads the claim document through the grain join

- **WHEN** an entitled author registers a `type=sql` pipeline whose expression reads
  `json_extract_array("dial_usage_log_payload.jwt_claims", 'roles')`
- **THEN** the pipeline is accepted, because an `object` enrichment column is exempt from the
  non-nullable refusal

#### Scenario: An unmatched grain join yields the empty document, not null

- **WHEN** a pipeline reads `"dial_usage_log_payload.jwt_claims"` for an event whose payload row has
  expired
- **THEN** the expression sees the empty document `{}`, and every extraction over it returns what the
  same extraction returns over a present document that lacks the key

#### Scenario: A non-nullable enrichment column of a scalar type is still refused

- **WHEN** an entitled author registers a `type=sql` pipeline whose expression derives a value from a
  non-nullable `string` column of an enrichment
- **THEN** the pipeline is refused, naming the column and the nullability requirement

### Requirement: Every pipeline-internal read runs under a resource budget

Every statement ADAS issues on a pipeline's behalf SHALL execute under the configured `pipeline.budget.*`
ceilings, carried as ClickHouse per-query settings. They SHALL be the enrichment scan page, both phases of
a group fetch, the Class-1 materialize window probe, the Class-1 preview sample, every read an `enrich`
preview issues (its row-grain sample and clamp probe, its group choice, member count and member read),
the aggregate refresh window probe, **the Class-1 materialize write (`INSERT … SELECT`)**, **and the aggregate refresh write
(`INSERT … SELECT`)**.

**No budgeted statement SHALL gate an unbudgeted one on the Class-1 or the aggregate path.** Where one statement exists only to fix the window
for another, as a probe does for its write, a ceiling on the first is in effect a ceiling on the second;
leaving the second exempt places the bound on whichever statement reads less and lets the larger one
consume whatever the node will give it. Which statements are bounded SHALL therefore be a property of the
pipeline's kind, and SHALL NOT depend on whether its declaration happens to carry a `filter`.

**The write SHALL narrow rather than fail.** A write refused by a ceiling SHALL halve its window and
retry, down to a floor of one source row, and SHALL report the narrowed window through the cursor and
`hasMore` it already returns. Only a write refused at that floor SHALL surface the refusal. This is the one
statement whose legitimate size genuinely exceeds any read's, and it is also the one whose contract already
expresses partial progress — so bounding it needs no new vocabulary and strands no pipeline. Narrowing here
is not the sampling that is forbidden below: the rows omitted are not dropped, they are left above the
returned cursor for the next call.

**Narrowing SHALL apply only to a refusal by the write's own ceiling.** A write refused for an exhaustion
the server attributes to the node SHALL surface that refusal without halving its window and without
issuing a further probe, because a smaller window does not address a condition the window's size did not
cause — it spends another statement on a node that is already out of memory. A write whose refusal leaves
no usable budget SHALL likewise report that no narrowed attempt was made, rather than reporting a failure
across retries that did not happen.

Except for a read made for the dry-run preview (below), exceeding a ceiling SHALL refuse that read with
HTTP 422 `pipeline_read_budget_exceeded`, whose message
SHALL name the ceiling exceeded and its configured value. It SHALL NOT be 503: the cost of one of these
reads follows the physical layout of the tables it reads, so the identical request is refused identically
until the declaration, the layout or the budget changes, and a caller doing the correct thing for a 503
would retry forever. The refusal SHALL be reported as a distinct code from `query_budget_exceeded`, because
the caller and the remedy differ — a runner cannot narrow a time range, and the remedies that apply are
reading fewer heavy columns or aligning the partitioning of the enrichment holding them.

A read made **for the dry-run preview** — the Class-1 preview sample, and every read an `enrich`
preview issues — SHALL run under these same ceilings but SHALL refuse with HTTP 422
`pipeline_preview_budget_exceeded` rather than `pipeline_read_budget_exceeded`, with a message that
names the ceiling exceeded, its configured value, and the remedies open to an **author**: a narrower
`from`/`to` window (where the arm accepts one), fewer heavy columns bound, or a raised budget. The
runner's own scan and group fetch SHALL keep `pipeline_read_budget_exceeded`; the preview code is chosen
by the surface that issued the read, not by the read path it shares. The preview is not the runner's
read, and a runner-oriented refusal ("this enrichment scan exceeded…") misdirects the person who will
read it (see [pipeline-registry](../pipeline-registry/spec.md), "Pipeline errors use the shared error
contract").

This is the refusal the server attributes to **this read's own ceiling**. A read that fails because the
node itself ran out of memory is not this refusal however similar its error code, and is reported as 503
`clickhouse_unavailable` — see the classification requirement above. The identical read succeeds once the
load clears, so a caller that dead-lettered it would discard work that was never too expensive.

The ceilings SHALL bound memory and wall-clock time, and SHALL NOT bound rows read, for the reason the
query surface gives: what these reads exhaust is memory, and a scan cap would refuse a bounded-but-wide
read while letting a narrow ruinous one through. A read SHALL be refused rather than sampled, narrowed or
truncated: a group labelled from a silently narrowed input is indistinguishable from one labelled from the
whole of it.

This requirement exists because these statements were left unbudgeted on the reasoning that each is
bounded by its own window, page size or limit. That reasoning does not hold. A group fetch's second phase reads the
declaration's bound columns for the members the selection policy kept — one, under
`member_select.limit: 1` — and where those columns live on an enrichment whose grain key prunes no
partitions, that single-row read costs gigabytes. Cost here is a property of the physical layout, not of
how many rows were asked for, so no limit a pipeline declares can bound it. Without a ceiling one such
read consumes whatever the node will give it: the observed failure was a ClickHouse pod OOM-killed by one
group fetch, taking every other capability down with it. The ceiling is a blast-radius bound, not an abuse
bound — the caller is trusted, and the point is that its cost is contained rather than shared.

The write's exemption was justified on the grounds that its cost is bounded by the window its probe
already fixed. That is only true when the probe and the write read the same way, and they do not: each
emits the grain join for the enrichments its *own* clauses reference, so a pipeline declaring no `filter`
has an unjoined, genuinely cheap probe and does its entire enrichment read inside the exempt write. Under node-wide
memory pressure that statement can be the single largest consumer on the ClickHouse node — a Class-1 materialize
write holding hundreds of MiB for most of a minute, beside concurrent ones, while the node sits at its server
limit shedding queries. The pipeline that
declared a `filter` was refused at a two-column probe; the one that declared none was never bounded at all.

The wall-clock ceiling SHALL bound the **materialize call**, not each statement or attempt. The call's
clock SHALL start before its first window probe. Every later statement the call issues SHALL run on what
remains of that one ceiling after the statements before it. That covers the re-probe that establishes
whether the freshness clamp withheld rows, each narrowing re-probe, and each write attempt. A call with
nothing usable left SHALL surface the refusal rather than begin a statement it cannot finish, so one call
occupies at most one configured timeout plus the time to refuse. A statement refused while running on a
remainder SHALL still name the configured timeout, with what was left of it beside it, because the remainder
is no value an operator can set. A narrowing sequence
bounded per attempt would occupy a multiple of the configured time and outlast the caller's own read
timeout; the server would then finish into a closed socket, which the caller reports as a transport failure
and retries — the livelock a deterministic refusal exists to replace. A ceiling that bounded the write
attempts while the probe and each re-probe ran on a fresh one still let a call run for about three times
the configured timeout, which exceeds the runner's read timeout at the defaults. The runner's read timeout
SHALL therefore be documented as paired with this ceiling on a per-call basis.

The aggregate refresh write carries the same ceilings and SHALL NOT narrow. A refresh recomputes every
affected group key in full, so a prefix of its window is not a smaller correct write. A write refused by
its ceiling SHALL fail that run as any other refresh failure does: the rollup's position does not move, the
failure is recorded against the pipeline, and an on-demand refresh answers HTTP 422
`pipeline_read_budget_exceeded`. The ceiling is what keeps one hung recompute from holding the aggregate
scheduler, which runs refreshes serially on a pool of one by default. The refusal SHALL name lowering
`aggregate.window-limit` as its remedy, not the heavy-column and partition-alignment remedy of a read
already at its smallest, since a smaller window is what makes a refresh touch fewer group keys.

#### Scenario: A narrowing sequence does not outlast the budget it runs under

- **GIVEN** a Class-1 materialize whose write is refused by the resource budget
- **WHEN** it narrows and retries
- **THEN** each attempt runs under the time remaining from the call's own ceiling
- **AND** once that ceiling is spent the refusal is reported rather than another attempt started

#### Scenario: The probe's time counts against the call's single ceiling

- **GIVEN** a Class-1 materialize whose first window probe takes most of the configured timeout
- **WHEN** the call goes on to re-probe and write
- **THEN** those statements run only under the time the probe left
- **AND** the whole call ends, successfully or with HTTP 422 `pipeline_read_budget_exceeded`, within one
  configured timeout plus the time to refuse

#### Scenario: A clamp re-probe runs on the remaining time

- **GIVEN** a clamped Class-1 pipeline whose window probe returned nothing
- **WHEN** the call re-probes with the clamp lifted to decide whether to record the drained horizon
- **THEN** that re-probe runs under the time remaining from the call's ceiling, not under a fresh one

#### Scenario: An aggregate write refused by its ceiling fails the run without narrowing

- **GIVEN** an aggregate pipeline whose refresh write exceeds the `pipeline.budget.*` ceiling
- **WHEN** the refresh runs
- **THEN** the run fails with no narrowed retry, the rollup's position does not move, and the failure is
  recorded against the pipeline
- **AND** an on-demand refresh answers HTTP 422 `pipeline_read_budget_exceeded`

#### Scenario: A materialize write refused by the ceiling narrows its window instead of failing

- **GIVEN** a Class-1 pipeline whose write over the probed window exceeds the memory ceiling
- **WHEN** the materialize call runs
- **THEN** the write is retried over a window narrowed from the rows the probe matched
- **AND** the response reports the rows written, a cursor at the narrowed window's end, and `hasMore: true`
- **AND** no refusal is surfaced to the caller

#### Scenario: A write refused at a single row surfaces the refusal

- **GIVEN** a Class-1 pipeline whose write exceeds the ceiling for one source row
- **WHEN** the materialize call runs
- **THEN** the response is HTTP 422 `pipeline_read_budget_exceeded`
- **AND** the cursor does not advance

#### Scenario: Whether a pipeline is bounded does not depend on its filter

- **GIVEN** two Class-1 pipelines reading the same enrichment over the same window, one declaring a
  `filter` that names an enrichment column and one declaring none
- **WHEN** each is materialized
- **THEN** both pipelines' enrichment reads execute under the ceiling

#### Scenario: A group fetch that exceeds the memory ceiling is refused, not served

- **GIVEN** a group pipeline binding two heavy columns from an enrichment that cannot be partition-pruned
- **WHEN** the runner requests the group's rows
- **THEN** the response is HTTP 422 `pipeline_read_budget_exceeded`
- **AND** the message names the memory ceiling and its configured value
- **AND** no partial group is returned

#### Scenario: A refusal on one pipeline leaves the rest of the service serving

- **GIVEN** a pipeline read that would exhaust the ClickHouse node's memory
- **WHEN** it is refused by its own ceiling
- **THEN** concurrent queries on the query surface continue to be served

#### Scenario: A preview read over its ceiling refuses under the preview code

- **GIVEN** an `enrich` pipeline whose preview sample read exceeds a `pipeline.budget.*` ceiling
- **WHEN** `POST /v1/pipelines/{name}/preview` is called
- **THEN** the response is HTTP 422 `pipeline_preview_budget_exceeded` naming the ceiling and its value
- **AND** the same read issued by the runner through the scan or group endpoint is still refused with
  `pipeline_read_budget_exceeded`

### Requirement: A ClickHouse resource failure is reported as unavailable, not as a bad request

A failure in which ClickHouse ran out of a resource SHALL be reported as HTTP 503
`clickhouse_unavailable`, on every path that talks to ClickHouse — read, row insert, `INSERT … SELECT`,
DDL and the usage-log batch alike. The classification SHALL be made in one shared place, from the numeric
server error code, and SHALL cover at least out of memory, unable to allocate, execution timeout, too many
simultaneous queries, no free connection, server socket timeout, network error, aborted, not enough space,
too many parts, and cancelled server-side.

A failure in which ClickHouse rejected the statement — unknown column, unknown table, type mismatch, syntax
— SHALL remain a statement rejection (HTTP 4xx). An unrecognised code SHALL be treated as a rejection
rather than as an outage: a real defect misreported as transient would have every caller retry a statement
that can never succeed.

Where a statement carries a resource budget, the budget's own refusal SHALL take precedence over this
classification **only where the server attributes the exhaustion to that statement's own ceiling**. The
per-query ceiling the service set and the node running out of memory under unrelated load arrive as the
**same error code**, and the code alone cannot say whose ceiling was reached: the first is deterministic
and the identical statement is refused identically (422, see above), the second is a bystander's report
and the identical statement succeeds once the load clears (503). The service SHALL therefore attribute the
refusal from the server's own account of which limit was reached, and an out-of-memory refusal it cannot
attribute to the statement's own ceiling SHALL be reported as 503 `clickhouse_unavailable`.

This is the one question for which the server's **message** is read rather than its numeric code, against
the rule stated above, and the exception SHALL be bounded to that one question. It is justified twice.
The code is already known to mean resource exhaustion, so the fallback is the 503 this requirement opens
with rather than a guess at a statement defect — the conservative direction for an exception carved out of
a rule is to require proof, not to assume it. And the ceiling the service itself set is the form a test can
provoke against a real server, so the recognised half stays pinned by the suite while the unrecognised half
defaults to the answer that loses no work.

The alternative default destroys work silently: a node-wide exhaustion reported as the budget's own refusal
is a 4xx, a first-party caller's contract makes a 4xx permanent, and ordinary node contention becomes a
dead-letter. Under ordinary contention such refusals arrive by the dozen within minutes, none of them
the per-query ceiling.

Attribution SHALL be a **positive identification of the statement's own ceiling**, not a search for
markers of the alternatives. The service sets exactly one memory ceiling — the per-query one — so a
refusal it cannot identify as that ceiling names a limit the service did not set, whoever set it: the
node's, the user's, or one a later server version introduces. All of those are environmental, they depend
on what else is executing rather than on this statement's shape, and the identical statement succeeds once
that load clears. Identifying the one ceiling the service owns therefore covers every alternative
including the ones that do not exist yet, where enumerating the alternatives would silently stop covering
them the moment one is added.

Because a write's narrowing is triggered by the budget's own refusal, a write refused for an exhaustion
attributed to the node SHALL surface that refusal rather than narrow its window: halving a window does not
address a condition the window did not cause.

A server-side resource refusal SHALL NOT be re-executed by the client transport before the service
classifies it. A refusal the service reports as deterministic is not deterministic in cost if the driver
has already run it several times, and those attempts are invisible to the budget's own wall-clock
accounting — so a refusal issued to protect a node under pressure would instead multiply the pressure it
exists to refuse.

This requirement exists because a resource failure reported as a client error both mislabels the failure
and destroys work. `POST /v1/pipelines/{name}/rows` answered `400 bad_request` — "check field/function/type
compatibility" — for rows whose every value was valid, because ClickHouse was out of memory; the runner
correctly treated a 400 as non-retryable and discarded them. Enrichment rows are lost that way
whenever the node runs out of memory. The statement was never wrong, and the caller was told it was.

#### Scenario: An insert refused for lack of memory is retryable

- **GIVEN** a valid enrichment row set
- **AND** ClickHouse failing the insert with `MEMORY_LIMIT_EXCEEDED`
- **WHEN** the runner writes the rows
- **THEN** the response is HTTP 503 `clickhouse_unavailable`
- **AND** no message suggests the rows are malformed

#### Scenario: A genuinely malformed statement is still a rejection

- **GIVEN** a statement naming a column the table does not have
- **WHEN** it is executed
- **THEN** the response is a 4xx statement rejection, not 503

#### Scenario: A node-wide memory exhaustion under a budget is not the budget's refusal

- **GIVEN** a pipeline read carrying the `pipeline.budget.*` ceilings
- **AND** ClickHouse refusing it because the node's own memory limit was reached, not the read's ceiling
- **WHEN** the read is issued
- **THEN** the response is HTTP 503 `clickhouse_unavailable`
- **AND** no message names the read's memory ceiling or advises narrowing the declaration

#### Scenario: The statement's own ceiling is still a deterministic refusal

- **GIVEN** a pipeline read whose own configured memory ceiling is exceeded
- **WHEN** the read is issued
- **THEN** the response is HTTP 422 `pipeline_read_budget_exceeded` naming the ceiling and its value

#### Scenario: An unattributable out-of-memory refusal is reported as an outage

- **GIVEN** a budgeted statement refused for memory
- **AND** a server account of the limit the service cannot attribute to that statement's own ceiling
- **WHEN** the refusal is classified
- **THEN** the response is HTTP 503 `clickhouse_unavailable`

#### Scenario: A ceiling the service did not set is not the service's refusal

- **GIVEN** a budgeted statement refused for exceeding a memory ceiling applied to the whole node or to
  the account the service connects as, rather than to the statement
- **WHEN** the refusal is classified
- **THEN** the response is HTTP 503 `clickhouse_unavailable`
- **AND** no message names the statement's configured ceiling

#### Scenario: A write refused by node-wide exhaustion is not narrowed

- **GIVEN** a Class-1 materialize write under the `pipeline.budget.*` ceilings
- **WHEN** ClickHouse refuses it because the node itself ran out of memory
- **THEN** the write is reported as HTTP 503 `clickhouse_unavailable`
- **AND** its window is not halved and no further probe is issued

#### Scenario: A resource refusal reaches the service once

- **GIVEN** a statement ClickHouse refuses with a server-side resource error
- **WHEN** the refusal is returned to the service
- **THEN** the transport has not re-executed the statement on the service's behalf

### Requirement: The group projection keys its grain joins with the member lookup

The second phase of a group fetch SHALL hand the predicates that confine the source to the grain-join
emitter as the statement's source-only predicate set, not merely add them to the `WHERE`. The predicates
SHALL be the per-member lookup-key disjunction and the safety lag.

Those predicates are source-only by construction: the lookup key is the source's ordering key plus its
identity column, and the safety lag names its version column. The service SHALL verify that before handing
them over, and SHALL fall back to an unkeyed join if it does not hold, rather than emitting a key subquery
that would need the very join it is deciding whether to emit.

The group-key predicate SHALL NOT be among them. It resolves against the joined field when an enrichment
supplies the group key, which is the ordinary case for a group trigger, and a predicate naming an
enrichment column cannot travel into the key subquery.

This matters more in this phase than anywhere else in the service, because this is the phase that projects
the declaration's heavy columns and it projects them for a handful of rows. Unkeyed, an enrichment holding
them whose grain key is uncorrelated with its partitioning is read across every one of its parts to return
those few — measured at ~1.2 GiB per heavy column for a single member.

#### Scenario: The member lookup keys the joins it also filters by

- **GIVEN** a group pipeline binding columns from an enrichment aligned with its source's partitioning
- **WHEN** the group's rows are assembled
- **THEN** the enrichment is emitted as a derived table restricted to the selected members' grain keys and
  partition values
- **AND** the statement's own `WHERE` still carries the same predicates

#### Scenario: A group key supplied by an enrichment does not travel into the key subquery

- **GIVEN** a group pipeline whose `group_by` names an enrichment column
- **WHEN** the group's rows are assembled
- **THEN** the key subquery carries the lookup key and the safety lag only

### Requirement: The group projection reads one heavy column per statement

The projection phase of a group fetch SHALL issue one statement per `heavy` bound column rather than one
statement projecting all of them. Every bound column that is not `heavy` SHALL be projected by the first
of those statements, and a declaration binding no `heavy` column SHALL be read by exactly one statement.
Each statement SHALL carry the same member lookup, the same safety lag, the same group-key predicate and
the same grain joins as a single combined statement would, so the split changes what is read per
statement and nothing else.

Heaviness SHALL be read from the catalog's column flag. A bound column with no binding in the
role-filtered model SHALL be treated as `heavy`: it cannot arise on this path, because entitlement over
the whole declaration is enforced before either phase runs, and of the two ways to misjudge a column's
cost an extra statement is the one that cannot exhaust a budget.

The statements' results SHALL be merged onto the members they were selected for, matched by the member
lookup key every statement projects. A member SHALL be kept only when **every** statement found it, and
that SHALL be determined by counting the statements that matched the member, not by testing whether a
projected value is present: a `heavy` column is legitimately NULL, and inferring absence from a NULL
would drop the member. A member that one statement found and another did not SHALL be treated exactly as
a member lost between the two phases already is — omitted, with `truncated: true`.

This requirement exists because the cost of projecting a `heavy` column is very nearly independent of the
bytes that column returns, and additive across the columns one statement projects. A lookup on a grain key
uncorrelated with the enrichment's partitioning lands a candidate granule in every part of the partitions
it reads, and the read buffers for all of a statement's columns are held at once; on the order of a
GiB per `heavy` column to return a single member, for a value of a few KB and one of hundreds of KB alike. So a declaration binding two `heavy` columns costs twice one binding a single column, and the
pair exceeds a per-statement ceiling that either half clears on its own. Splitting by column makes the
peak the largest single column instead of the sum of all of them, which is what stops a declaration's
`heavy`-column count from deciding whether its group fetch runs at all. Partition pruning does not
substitute for this: the partition a group fetch reads is the current one, which is where an ingesting
source's unmerged parts are, so pruning to it removes the other days and not the cost.

A budget refusal from one of these statements SHALL name the columns that statement projected, so the
refusal identifies which of a declaration's bound columns does not fit.

#### Scenario: Two heavy columns are projected by one statement each

- **GIVEN** a group pipeline binding two `heavy` columns and one inexpensive column
- **WHEN** a group's rows are assembled
- **THEN** the projection phase issues one statement per `heavy` column
- **AND** neither statement names the other's `heavy` column
- **AND** the inexpensive column is projected by the first of them
- **AND** the assembled member carries the values of all three

#### Scenario: One heavy column is read as before

- **GIVEN** a group pipeline binding a single `heavy` column
- **WHEN** a group's rows are assembled
- **THEN** the projection phase issues exactly one statement

#### Scenario: A NULL heavy column keeps its member

- **GIVEN** a group pipeline binding two `heavy` columns
- **AND** a member whose second `heavy` column is NULL
- **WHEN** a group's rows are assembled
- **THEN** that member is returned with that column NULL, rather than omitted as unfound

#### Scenario: A refusal names the columns of the read it refused

- **GIVEN** a group pipeline binding two `heavy` columns
- **WHEN** one projection statement exceeds the pipeline read budget
- **THEN** the refusal names the columns that statement projected

### Requirement: The governed data plane requires an exact runner contract version

Every request to a runner-only data-plane endpoint SHALL carry the `Adas-Contract-Version` header naming
the contract the caller was built against, and SHALL be refused unless it equals the version this service
serves. The endpoints SHALL be `POST /v1/pipelines/{name}/scan`, `POST /v1/pipelines/{name}/group-rows`
and `POST /v1/pipelines/{name}/rows`, plus `POST /v1/pipelines/{name}/materialize` (see the
`class1-enrichment` capability).

The version SHALL be a single opaque integer compared for **equality**, not a range and not an ordering:
the service SHALL NOT attempt to serve a caller that declares any other value. It SHALL be a property of
the build, not configuration — a deployment that could set it to the wrong value could disable every
pipeline at once, silently, which is the failure this requirement exists to remove. It SHALL be raised
whenever a breaking change lands in the compiled projection or in any of these four endpoints' request or
response shapes.

Refusals SHALL use the shared error contract: a missing header SHALL be HTTP 409
`runner_contract_required` and a header that does not match SHALL be HTTP 409
`runner_contract_mismatch`, whose message SHALL name both the version served and the version declared so
the reader learns which side is behind. The refusal SHALL be decided **before** any pipeline lookup, the
per-call grant check and any query, so a skewed caller cannot consume a read budget or reach ClickHouse.
It SHALL be decided **after** authentication: an unauthenticated caller SHALL still be answered `401`,
because "who is calling" is settled before "which build is calling".

This requirement exists because the transport survives a version skew that the definition does not. A
declaration-shape change can leave these four endpoints unchanged, so a runner built against the older
shape keeps calling them successfully while holding a declaration it cannot execute — and the scan
cursor, which advances at enqueue and is never re-examined, runs through the backlog producing nothing.
The requirement is therefore stated over the *transport* rather than over any one missing field: each
shape change removes a different field, and the endpoints stay just as willing.

Refusing the call is what this service can guarantee on its own: the cursor is untouched and nothing is
written, whatever the caller does next, so the skew costs a stall rather than a loss. It is deliberately
*not* the whole of "equivalent to a runner scaled to zero" — a chunk already queued on the caller's side
reaches its model call without asking this service anything, so suppressing the spend is the runner half's
responsibility and is specified there. This requirement covers what is decidable from the request alone.

The operator surfaces `POST /v1/pipelines/{name}/preview` and `/refresh`, pipeline registration, and both
read projections SHALL NOT require the header, so a management client, the OpenAPI console and an
operator's own reads are unaffected by it.

#### Scenario: A scan without the contract header is refused before any lookup

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with no `Adas-Contract-Version` header
- **THEN** the response is HTTP 409 `runner_contract_required`, no scan is executed, and the pipeline's
  cursor position and the caller's read budget are untouched

#### Scenario: A caller declaring a different contract version is refused on every data-plane endpoint

- **WHEN** `scan`, `group-rows`, `materialize` or `rows` is called with an `Adas-Contract-Version` that
  differs from the version served
- **THEN** each response is HTTP 409 `runner_contract_mismatch` with a message naming both versions, and
  no row is read, computed or written

#### Scenario: A matching caller is served exactly as before

- **WHEN** the same requests carry the matching `Adas-Contract-Version`
- **THEN** each endpoint behaves exactly as specified elsewhere in this capability, the header having no
  effect beyond admission

#### Scenario: An operator surface does not require the header

- **WHEN** `POST /v1/pipelines/{name}/preview` or `/refresh` is called with no `Adas-Contract-Version`
  header
- **THEN** the request is served normally, because these are operator surfaces rather than the runner's

### Requirement: A pipeline-scoped write-back that could write no label is refused

Every row of `POST /v1/pipelines/{name}/rows` SHALL name at least one of the pipeline's declared output
columns, and a row naming only the grain key and provenance columns SHALL be rejected with HTTP 422
`write_output_column_required` naming the pipeline and the requirement. The check SHALL be on the
**presence of the column**, not on its value: an output column explicitly carrying `null` is a derived
label of `null` and SHALL be accepted, because a mapping that deliberately maps a sentinel to `null` is a
declared outcome.

The code SHALL be distinct from the existing `write_column_not_allowed`, which this endpoint already uses
for a row naming a column the pipeline may **not** write. Both are 422s about a write's column set, but
the remedies are opposite — remove the named column there, add a declared one here — and a machine code a
client acts on without reading the message may not carry the wrong remedy half the time.

The target is a `ReplacingMergeTree` read at its grain, so a row of grain plus provenance does not fail
and does not no-op — it **replaces** whatever label that grain already holds with nothing, after the model
call that produced it was made and charged. The existing subset rule cannot catch this: such a row names
only permitted columns. A caller that has lost its output mapping, for any reason including a version
skew, therefore reaches a silent and irreversible overwrite through a request that is otherwise valid, and
this requirement is what makes that unreachable regardless of which caller version sent it.

#### Scenario: A row of grain and provenance only is refused

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with a row naming the grain key and provenance
  columns but none of the pipeline's declared output columns
- **THEN** the response is HTTP 422 `write_output_column_required`, and no row of the batch is written

#### Scenario: A row naming a forbidden column keeps its own code

- **WHEN** a row names a column the pipeline may not write
- **THEN** the response is still HTTP 422 `write_column_not_allowed`, unchanged by this requirement

#### Scenario: An output column present with a null value is accepted

- **WHEN** a row names a declared output column whose value is `null`
- **THEN** the write is accepted and stores `null` for that column, because the label was derived and its
  value is `null`

### Requirement: A runner that has stopped calling is visible as a metric

The service SHALL publish, per `enrich` pipeline, the time since the runner last successfully called one
of that pipeline's data-plane endpoints, and SHALL count contract refusals tagged by reason
(`required` or `mismatch`). The refusal counters SHALL be registered at startup, so the series exists
before the first skew rather than appearing once it has already happened, and SHALL **not** carry a
`pipeline` tag: a refusal is decided before the pipeline is looked up, so the only name available is the
unvalidated path segment the caller sent — an unbounded tag whose values a skewed or hostile client
chooses. Staleness SHALL be measured against the present at **observation** time from a
stored last-contact instant, not published as a value frozen between calls, so a runner that has stopped
shows as a series that keeps climbing with no call needed to move it — the same rule the aggregate
staleness metric follows and for the same reason.

A pipeline's series SHALL be registered on its **first** data-plane contact, and SHALL be dropped when the
pipeline is **deleted or disabled** — by every path that deletes one, including the deletion that follows
its target table being dropped. A gauge derived at scrape time from an instant nothing will move again
raises the stall signal forever, so a series must not outlive the thing that moves it: a pipeline the
runner has never called has no series rather than one climbing since startup, and a pipeline an operator
switched off leaves the runner's `enabled=true` feed by design, which would otherwise make the intended
behaviour the loudest alarm on the dashboard. Re-enabling or re-creating a pipeline re-registers the
series on the next call, measured from a contact that actually happened.

This exists because ADAS's own output depends on the runner being alive: a Class-1 enrichment's
materialized position is what every dependent rollup's freshness clamp reads, so a runner that is down,
skewed or refused makes rollups write zero rows per tick with the clamp naming an enrichment, and nothing
in ADAS today distinguishes that from a quiet period. The signal SHALL be a metric and SHALL NOT be a
health contributor: the deployment's liveness probe targets the aggregate health endpoint, so a
contributor reporting DOWN for an operational condition would restart the service instead of reporting it.

#### Scenario: Staleness climbs while the runner is absent

- **WHEN** no data-plane call is made for an `enrich` pipeline over several observations
- **THEN** its runner-contact staleness series grows between observations, without any call occurring

#### Scenario: A refused caller is counted by reason

- **WHEN** a data-plane call is refused for a missing or mismatched contract version
- **THEN** a refusal counter tagged with that reason, and with no `pipeline` tag, is incremented

#### Scenario: A refusal naming an unknown pipeline adds no tag value

- **WHEN** a data-plane call is refused for a contract version while naming a pipeline that does not exist
- **THEN** the refusal is counted and the metric registry gains no new series, because the counters are
  registered at startup and are not keyed by the name the caller sent

#### Scenario: A deleted pipeline leaves no climbing series

- **WHEN** an `enrich` pipeline is deleted — directly, or because its target table was dropped
- **THEN** its runner-contact series is removed rather than left to climb indefinitely

#### Scenario: A disabled pipeline leaves no climbing series

- **WHEN** an `enrich` pipeline is disabled, so it leaves the runner's feed and stops being called
- **THEN** its runner-contact series is removed, because the runner's silence is now the declared state
  rather than a condition to report

#### Scenario: A pipeline the runner has never called has no series

- **WHEN** an `enrich` pipeline has been registered but no data-plane call has been made for it
- **THEN** no runner-contact staleness series exists for it

### Requirement: Pipeline-scoped read and write authorization

The scan, the group assembly, the Class-1 materialize, and the write endpoints SHALL require **both**
`TABLE_READ` on the pipeline's **read source** and `TABLE_WRITE` on its **target enrichment**, each held
globally or granted on that table. A caller lacking either SHALL be rejected with HTTP 403
`pipeline_access_denied`. A caller holding every privilege satisfies the requirement, and does so because
it holds those two privileges globally rather than by any check on a role name.

The write endpoint (`POST /v1/pipelines/{name}/rows`) carries the caller's own rows and reads nothing, so
it SHALL require the target's `TABLE_WRITE` alone.

This replaces authorizing both directions from the target's write grant alone. That earlier rule was
a confused deputy: the endpoints return rows of the read source, so a caller holding only `write` on
a small derived enrichment obtained a read of the table that enrichment derives from — and, because a
scan's projection may include the columns of **other active enrichments of the same source**, of
those siblings too. The `access` model now has a read grant, so the read direction SHALL be
authorized against the table actually read.

This holds when the pipeline declares a **read source** other than its target enrichment's
`source_table` (see [enrichment-registry](../enrichment-registry/spec.md)): the read source is
authorized in its own right, so a pipeline reading a table other than its target's requires a read
privilege on that table. The sensitive-column re-check SHALL continue to be applied against the
pipeline's read source, in addition to this authorization.

An Enrichment Runner therefore needs `REGISTRY_READ` for the pipeline sync feed plus per-table grants on
the sources it scans and the targets it writes; it SHALL NOT need a bundle conferring global read
over every table.

#### Scenario: Least-privilege scan requires source read and target write

- **WHEN** a caller holding `read` on the pipeline's read source and `write` on its target enrichment
  calls `POST /v1/pipelines/{name}/scan`
- **THEN** the scan is authorized without requiring any global read privilege

#### Scenario: Target write alone no longer authorizes a scan

- **WHEN** a caller holding only `write` on the target enrichment calls the scan, group assembly, or
  materialize endpoint
- **THEN** the request is rejected with HTTP 403 and error code `pipeline_access_denied`

#### Scenario: Missing target access is rejected

- **WHEN** a caller holding `read` on the read source but no write access on the target enrichment
  calls any of those endpoints
- **THEN** the request is rejected with HTTP 403 and error code `pipeline_access_denied`

#### Scenario: A declared read source is authorized in its own right

- **WHEN** a caller scans a pipeline whose declared read source is a table other than its target's
  `source_table`, holding `write` on the target but no read privilege on that declared source
- **THEN** the request is rejected with HTTP 403 and error code `pipeline_access_denied`
- **AND WHEN** the caller is additionally granted `read` on the declared source
- **THEN** the scan is authorized, and the sensitive-column re-check is applied against that source's
  columns

#### Scenario: The write-back endpoint needs no source read

- **WHEN** a caller holding only `write` on the target enrichment posts rows to
  `POST /v1/pipelines/{name}/rows`
- **THEN** the write is authorized, because the endpoint carries the caller's own rows and reads nothing

### Requirement: A grant may not launder a restricted column into a laxer target

The service SHALL require that a pipeline's target enrichment expose a derived sensitive column no more
widely than its read source exposes the column it derives from. Sensitive-column entitlement is grantable
per table, so declaring a derived column `sensitive` no longer implies a uniform barrier: the roles
cleared for the target table's sensitive values may differ from the read source's. **Where the target enrichment carries a `sensitive` column**, its `sensitive` grantee
set SHALL be a subset of the roles its source lists under **both** `read` and `sensitive`. A role on
the source's `sensitive` list alone does not count, because the clearance without `read` reads
nothing. Every role on the target's `sensitive` list SHALL count, whatever its `read` grant there,
because that role might hold `TABLE_READ` globally. The target's `modify` list SHALL NOT be checked:
`modify` without the clearance reaches no sensitive column, and `modify` with it is covered because the
`sensitive` list is checked.

The condition is the target carrying such a column, not the narrower "a pipeline derives one from a
sensitive source column", and the difference is deliberate. The narrower reading states the risk more
precisely but is not answerable when a grant is edited without enumerating every pipeline targeting the
enrichment and resolving what each reads — and a predicate that holds at registration and not at grant
time is what let the narrowing direction go unchecked in the first place. One condition evaluated
identically at every entry point is worth being conservative for: the cost is refusing a grant on an
enrichment whose sensitive column happens not to be derived from a protected one, and the remedy is the
same `read` and `sensitive` grants on its source.

A role SHALL be exempt only where the **source's own `read` and `sensitive` lists both name it**.
Holding `TABLE_READ` or `TABLE_SENSITIVE` globally SHALL NOT exempt it: the only available answer to
"does this role name hold the privilege globally" is a union across every configured provider's `roles-mapping`, which would exempt a role on
the strength of a mapping that a caller arriving through a different provider does not have. Granting a
globally-entitled role is reported as an ineffective grant in any case.

The relation is **"reads from"**, not "is a child of", and the distinction is the rule's whole
correctness argument. **Four** different edges carry it:

- an enrichment's catalog `source_table`;
- an `ENRICH` pipeline's declared `input_table`, which MAY name **any** ACTIVE source and so need not
  be the target's parent. A rule that followed parentage alone compared the target's grants against the
  wrong table: a pipeline could copy a sensitive column of `s2` into an enrichment of `s1` and have it
  served to `s1`'s grantees;
- an `AGGREGATE` pipeline's `input_table`, whose target has no catalog parent at all (see
  `aggregate-pipeline`);
- and an **enrichment reached through the read source's grain join**. A declaration addresses such a
  column as `"<enrichment>.<column>"`, so the table whose values are copied appears nowhere on the
  pipeline row — its `input_table` is the source, and the enrichment exists only in the parsed
  declaration. Missing this edge left the same disclosure the `input_table` edge closes, one join
  further out: a rollup of a protected enrichment column into a target granted more widely than that
  enrichment, readable by a caller holding nothing on it.

The relation SHALL NOT be narrowed to "a source that itself carries a `sensitive` column". An author MAY
declare a derived column `sensitive` whose origin column is not flagged — the ordinary shape on the
aggregate path, where rolling a protected value up is what the flag is for — so a source with no flagged
column can still be where protected values come from. The cost is that a target carrying a sensitive
column must hold its `sensitive` grants within the `read` and `sensitive` lists of **every** table it
reads, enrichments included, and the remedy is the same grants on that table.

The relation follows the **edge, not the data**: deleting the pipeline, or re-pointing it at another
target, removes the edge while the rows it already wrote remain, after which a grant on the abandoned
target is unconstrained. That is accepted rather than closed — it takes `REGISTRY_ADMIN` and
`TABLE_GRANT` together, and `TABLE_GRANT` is global-only, so such a caller could grant itself the source
directly.

It SHALL be enforced at **four** points, because each closes what the others cannot:

- **pipeline registration and enable** — rejected with `422 pipeline_validation_failed`, this
  capability's code for a governance violation, so the registry does not report one condition under two
  codes;
- **`PUT /v1/tables/{name}/access`** — rejected with `422 sensitive_grant_widens`, in **both**
  directions: widening the enrichment's list, and narrowing the source's while an enrichment of it still
  names a role. Checking only "did this request widen anything" misses the second entirely;
- **`PATCH /v1/tables/{name}/schema`** — when a column is first marked `sensitive`, whether by
  patching an existing column or by adding one already flagged. Until then the pair had nothing to
  protect, so neither of the points above could have seen it;
- **`POST /v1/tables/{name}/schema`** — the same condition on the initial schema of a not-yet-
  materialized table. Leaving this out left the whole rule bypassable through the front door: grant
  `sensitive` on a `PENDING` enrichment while it has no columns at all (accepted, because the
  pair has nothing to protect), then define a schema declaring one;

A pipeline's `transform` — expressions included — lives on the pipeline row, so the only way to change
what a declaration reads is `PATCH /v1/pipelines/{name}`. That patch, or the enable that later arms a
draft, runs the full registration gate: the grant subset rule above **and** the author's own
sensitive-column entitlement over every reference. The second matters more on that path, because the
subset rule is vacuous whenever the target carries no `sensitive` column.

Class-1 enrichment has **no runtime backstop**, which is why the gate on that edit is required rather than
merely tidy. A scheduled aggregate refresh re-runs its whole registration gate, this rule included, so a
stored state that drifted is caught on the next tick; `POST /v1/pipelines/{name}/materialize` checks only
the *calling* identity's entitlement, which the runner's elevated identity always satisfies. On that path
the authoring edit is the only moment the rule can be applied.

Where the relation itself **cannot be evaluated** — a stored declaration that no longer parses or whose
read source no longer resolves — the edit SHALL be **refused**, `409 pipeline_declaration_unresolvable`,
naming the declaration. Not skipped: the registry's other consumers (the table-delete guard, the
freshness diagnostics) degrade to "no edges" on a drifted declaration, deliberately, because a broken
pipeline must not make an unrelated table undeletable. Under this rule the same degradation reads as
"this table has no readers", which **accepts** a grant the rule exists to refuse — so one drifted row
would silently reopen the path. Letting the failure escape instead is no better: it turns every grant
edit, on any table, into a `500` naming a pipeline the operator was not touching.

The rule is enforced in application code and has **no database-level backing**: a Flyway migration that
wrote a role into a grant list, or set a column's `sensitive` flag, would bypass it by construction. No
shipped migration does either — none writes a grant list, and the two migrations that mark columns
sensitive predate per-table grants entirely — and a migration that needs to do so SHALL apply the
rule itself or state why the pair it touches is vacuous.

The rule SHALL be enforced in `none` mode as well as `oidc`, unlike the privilege checks around it. Those
decide who may act and fail open where nothing is enforced; this decides what stored state may exist, and
a grant document written while security was off goes live the moment a deployment switches to `oidc`,
when refusing it would be too late.

This rule constrains **grants**, not the `sensitive` flag. Derived enrichment columns SHALL continue not
to inherit sensitivity, as `enrichment-registry` specifies: registration never mutates catalog metadata,
and the enrichment author declares the flag through the schema API. The consequence that capability
already states and accepts — a value derived into an unflagged column is readable by callers who may not
read its source — is unchanged and is **not** what this rule closes. This rule bites exactly where the
author did declare the target column sensitive.

Changing a column's `sensitive` flag requires `TABLE_MODIFY` and `TABLE_SENSITIVE` on that table (see
`table-management`), not `TABLE_GRANT`. The accepted consequence is that a holder of both on a derived
table MAY declassify a derived column. Through per-table grants such a holder is, by this rule, a role
every read source lists under both `read` and `sensitive`, so it could already read the source values.

The clearance can now be held **globally without any read**, which the former rung could not express.
Such a role needs only a `read` grant on the derived table to read its sensitive values, and it appears on
no `sensitive` list. So where the target carries a `sensitive` column, every role in its `read` list that
the configured `roles-mapping` maps to global `TABLE_SENSITIVE` SHALL also be listed under `read` by every
table the target reads. Otherwise the grant edit, the registration, or the schema change that marks a
column sensitive SHALL be refused on the same terms as the `sensitive`-list rule. The mapping is
consulted here only to **refuse**, never to exempt. The rule is evaluated when grants, pipelines or the
schema change, not when the mapping does, so remapping a role to global `TABLE_SENSITIVE` after its
derived `read` grant was stored is not re-examined.

#### Scenario: A globally cleared role may not read laundered values through a read grant

- **GIVEN** a role `pii-bots` that `roles-mapping` maps to `TABLE_SENSITIVE` only, a read source that
  grants `pii-bots` nothing, and a target enrichment that carries a `sensitive` column
- **WHEN** an operator grants `pii-bots` `read` on the target
- **THEN** the request is refused `422 sensitive_grant_widens`
- **AND WHEN** the read source also lists `pii-bots` under `read`
- **THEN** the same grant on the target is accepted

#### Scenario: Registration is rejected when the target is laxer

- **GIVEN** a read source whose `read` and `sensitive` lists are both `["pii-readers"]`, and a target
  enrichment that
  carries a `sensitive` column and whose `sensitive` list is `["pii-readers","analysts"]`
- **WHEN** a pipeline into that target is registered
- **THEN** the registration is rejected with `422 pipeline_validation_failed`, naming the widening role
  and both tables

#### Scenario: Narrowing the source is rejected while an enrichment still names the role

- **GIVEN** the same pair, with the source's `read` and `sensitive` lists and the target's `sensitive`
  list all naming `analysts`
- **WHEN** an operator revokes `sensitive`, or `read`, on the source from `analysts`
- **THEN** the request is rejected with `422 sensitive_grant_widens` and neither list changes

#### Scenario: Editing a transform re-runs the full gate

- **GIVEN** an enabled `sql` pipeline whose target carries no `sensitive` column
- **WHEN** a caller holding `REGISTRY_ADMIN` but not both `TABLE_READ` and `TABLE_SENSITIVE` on the
  read source
  patches its `transform` so an output expression reads a `sensitive` column of the read source
- **THEN** the patch is rejected `403 sensitive_column_not_entitled`, and the stored declaration is
  unchanged — the expression lives on the pipeline row, so there is no way to change what a stored
  pipeline reads that bypasses this gate

#### Scenario: Marking a column sensitive is rejected while the grants already widen

- **GIVEN** an enrichment with no sensitive column whose `sensitive` list names a role its source does
  not list under both `read` and `sensitive` — accepted, because the pair had nothing to protect
- **WHEN** a schema patch marks one of its columns `sensitive`, or adds a column already flagged
- **THEN** the request is rejected with `422 sensitive_grant_widens` and no column changes

#### Scenario: A later grant that would widen is rejected

- **GIVEN** a registered pipeline reading a sensitive source column into a target enrichment that
  carries a sensitive column
- **WHEN** an operator adds a role to the target's `sensitive` list that the read source does not
  list under both `read` and `sensitive`
- **THEN** the request is rejected with `422 sensitive_grant_widens` and the lists are unchanged

### Requirement: A schema edit may not repoint what a stored declaration reads

A column any stored declaration reads SHALL NOT be dropped, renamed, or **renamed onto** — on an
enrichment, where a declaration addresses it as `"<enrichment>.<column>"`, and equally on a **source**,
where it is addressed by its bare name. Refused `409 conflict`, naming the declarations.

**The source half is a security control, not only an availability one.** A rename preserves the
`sensitive` flag and changes only the name, so on a source a holder of `TABLE_MODIFY` and
`TABLE_SENSITIVE` — which together confer no `TABLE_READ` — could free the name an expression reads and
move a protected column into it. Without the clearance a sensitive column does not exist for a
`TABLE_MODIFY` holder, so the rename is `422` as an unknown column; with it, the column is reachable. The
declaration is untouched and the flag is untouched, so neither the clearance rule on the flag nor the
subset rule above fires; the pipeline's elevated runner then copies the protected values into an
unflagged enrichment column that plain `TABLE_READ` reads. Before privileges existed this was
unreachable, because `PATCH .../schema` required the full administrative role, which conferred global
sensitive read; letting schema authority over sensitive columns be held without `TABLE_READ` is what
opened it.

**Both halves of a rename SHALL be asked**, because freeing a read name and occupying one are the two
ways to repoint a reference, and only the second carries the values.

A source column counts as read when **any** SQL fragment of the declaration names it as a bare
identifier — the filter, the `ready_when` signal, the member-selection `prefer_sql`, and each `sql`
output expression — as well as through the declaration's structured members. On an `aggregate`
pipeline declared by `sql`, the statement text counts as one such fragment, read by the same token scan
with the leading `INSERT INTO <target> (<columns>)` prefix excluded: those names are target columns, not
reads. When the prefix is not recognised, the whole text is scanned. The statement's bare reads pin the
columns of its input table only once that table has been derived, so a draft whose `sql` cannot be planned
pins no source column; arming re-validates it against the schema as it stands then. A scan for qualified
`table.column` tokens is not enough: a source column has no qualified spelling, so an expression reading
`length(nick)` would be invisible to it, and that is exactly the shape a rename-repoint uses. Every
identifier SHALL count, keywords and string contents included; a false positive refuses a rename of a
column nobody reads, and a false negative is impossible.

#### Scenario: Renaming a source column a pipeline reads is refused

- **GIVEN** an enabled `sql` pipeline whose expression reads the source column `nick`
- **WHEN** a caller holding `TABLE_MODIFY` on that source renames `nick`
- **THEN** the request is refused `409 conflict`, naming the pipeline, and no column changes

#### Scenario: Renaming a sensitive column onto a read name is refused

- **GIVEN** the same pipeline, and a `sensitive` column `user_email` on the same source
- **WHEN** a caller holding `TABLE_MODIFY` and `TABLE_SENSITIVE` on that source renames `user_email`
  to `nick`
- **THEN** the request is refused `409 conflict` — the rename would repoint the expression at a
  protected column without touching the declaration, the flag, or any grant list

#### Scenario: Renaming a source column an SQL-declared aggregate reads bare is refused

- **WHEN** an `aggregate` pipeline is declared by `sql` whose block reads `deployment` as a bare
  identifier only, and a caller renames the input's `deployment` column
- **THEN** the request is refused `409 conflict`, naming the pipeline

#### Scenario: A target column-list name does not block a source rename

- **WHEN** an `aggregate` pipeline's `sql` names `user_id` only in its `INSERT INTO <target> (user_id, …)`
  column list and nowhere in the `SELECT`, and a caller renames the input's `user_id` column
- **THEN** the rename is not refused on that pipeline's account

### Requirement: The governed data plane refuses an absent declaration, and materialize re-checks two rules

The system SHALL refuse, at each of the four pipeline-scoped data-plane endpoints —
`POST /v1/pipelines/{name}/scan`, `POST /v1/pipelines/{name}/group-rows`,
`POST /v1/pipelines/{name}/materialize` and `POST /v1/pipelines/{name}/rows` — a stored declaration
missing a member its kind requires, with HTTP 422 `pipeline_validation_failed` naming what is absent,
**before** any ClickHouse statement is compiled, read or written. On the two read endpoints this holds
in either pipeline state; the two writing endpoints refuse a disabled pipeline first, with HTTP 409 (see
"A disabled pipeline's write paths refuse"), so there it guards an enabled one. A
declaration is incomplete here when a member the kind requires is absent: `transform`,
`transform.type`, `transform.outputs`, `trigger.kind`, `trigger.cron` on a `schedule` trigger,
`trigger.group_by` on a `group` trigger, or the derived output mapping. The four endpoints serve
`enrich` pipelines only, so no aggregate member is consulted here. A read source that does not
resolve is **not** a completeness member: `/scan` and `/materialize` refuse it as they always have,
with HTTP 400 `bad_request` from the governed query layer.

`POST /v1/pipelines/{name}/materialize` SHALL additionally re-check exactly **two** rules of the
kind's gate: the **result-type fit** of each derived value against the target column it is bound to,
and the **row-grain rule** against the read source's `identity_column`. **Nothing else of the gate
SHALL be imported onto these lanes.**

These are the entry points that **compile or execute** the stored declaration, they never went
through a caller who could be told to fix a body, and they have always been written against the
assumption — true until the gate was deferred — that a stored declaration had already passed it. The
absence refusal is what restores the part of that assumption they cannot do without: a query compiled
from members that are not there is not a query.

**Why the two re-checks, and only those two.** Completeness and validity are different questions: a
declaration naming every member its kind requires passes the absence refusal while still failing the
gate. Such a declaration is storable for the first time, though only while disabled — and a disabled
pipeline is not executed by the writing endpoints — so what the re-checks guard is a declaration that
was gated when it was armed and has **drifted** since. The execution entry points re-derive only
part of what the gate asks — `POST …/materialize` re-checks the expression parse, the closed function
catalog, determinism, source scoping and the caller's sensitive entitlement, but **not** the result
type's fit against the bound column, **not** the row-grain rule against the read source's
`identity_column`, and **not** the enrichment-read acyclicity rules. Those first two are the ones
whose absence produces a **silently wrong row** rather than a loud failure: a Class-1 declaration
whose target is keyed more coarsely than its read source would materialize, and the target's
`ReplacingMergeTree` would collapse many rows onto one arbitrary survivor — a label that is neither
wrong nor reproducible, which is precisely what the row-grain rule exists to prevent. They are cheap,
and they are added. The rest is not.

**The whole gate SHALL NOT be run here.** A running pipeline's declaration can drift under it — a
column renamed on the read source, an enrichment that moved — and a full gate would stop it mid-run for
drift the operator did not cause and cannot see from there, which is the same regression the preview
rule declines to accept on an armed pipeline. So a **complete** declaration SHALL be run as it is found,
subject only to the two re-checks above.

**A stored derivation that no longer re-derives SHALL count as absent at `/materialize`.** A
declaration write keeps a derived `output_bindings` it could not re-derive rather than erasing it, so
the stored mapping can outlive the transform it came from. Where the stored bindings do not re-derive
from the current transform, `/materialize` SHALL refuse — the alternative is writing a newly authored
expression into a column bound by the previous one, which is a write the stored declaration does not
describe.

**A refusal an endpoint already owns unconditionally SHALL stay unconditional**, and is not part of
what is timed above: a stored declaration carrying no `transform` is refused on all four — on the two
writing endpoints only once the pipeline is enabled, since a disabled one is refused there first with
HTTP 409 — and logged at ERROR when the pipeline is enabled (see
[enrichment-registry](../enrichment-registry/spec.md), "A stored ENRICH declaration carrying no
transform is refused rather than run empty"), and a null `trigger.group_by` is refused rather than
dereferenced in either state. Turning a server fault into a refusal is not a new gate.

Two of the four do not merely mis-report an incomplete declaration today; they fail on it:

- **`/group-rows` is a 500, not a refusal.** The group-assembly path compares the stored
  `trigger.group_by` without a presence check, so a `group` pipeline whose group key has not been set
  is a null dereference reported as a server fault, inviting a retry that cannot succeed. It SHALL be
  the 422 above, in either state — a null group key is a crash on the enabled path too.
- **`/rows` mis-diagnoses it, under either of two codes.** The write allow-list is **not** empty when
  no `output_bindings` have been derived: it is seeded with the provenance columns and the pipeline's
  grain key, and only the declared output columns are missing from it. So a row carrying the label
  column the operator did declare fails the subset check and is refused `write_column_not_allowed`
  naming that column, while a row of grain and provenance alone **passes** the subset check and is
  refused `write_output_column_required` for naming none of the — empty — declared outputs. Nothing
  unsafe happens, since every row meets one refusal or the other and the "empty bindings write NULL
  labels" hazard does not resurface; what is wrong is that neither code names the declaration, so the
  operator is told about their row. The absence refusal SHALL be raised first, and
  both `write_column_not_allowed` and `write_output_column_required` SHALL remain exactly what they
  are today on a **complete** declaration.

One code SHALL serve all four — `pipeline_validation_failed`, which is what every other declaration
refusal on this surface already carries — so a runner switches on the code rather than on which
endpoint it happened to call, and 422 is the status on every surface: the declaration is not valid and
the remedy is to finish it, which is what 422 means here — 409 stays reserved for a request whose body
may be perfectly good and whose timing is wrong. A declaration carrying no `transform` is refused by
the shared no-transform refusal above, which the data plane asks first; otherwise, unlike the
declaration gate, which reports one problem per refusal, the completeness refusal names **every**
absent member in one message — the check is a single presence sweep over the declaration, so there is
no ordering to impose and nothing gained by withholding the rest.

Both refusals SHALL be ordered **after** the exact `Adas-Contract-Version` check and after
`unknown_pipeline`: a caller at the wrong contract version is answered HTTP 409 before any pipeline is
loaded, and a name that resolves to nothing is HTTP 404, so neither is re-reported as a declaration
problem.

The completeness refusal itself is not an enablement check: it refuses a declaration that cannot be
compiled, whatever the pipeline's state. Whether a disabled pipeline may be written through is decided
separately, by "A disabled pipeline's write paths refuse".

#### Scenario: A group pipeline with no group key is refused rather than crashing

- **WHEN** `POST /v1/pipelines/{name}/group-rows` is called for a `group` pipeline whose
  `trigger.group_by` has not been declared
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming
  `trigger.group_by`, rather than answering HTTP 500 from a null dereference

#### Scenario: A write-back against an incomplete declaration names the declaration, not the column

- **WHEN** `POST /v1/pipelines/{name}/rows` is called for an enabled pipeline whose declaration is
  incomplete,
  so no output mapping has been derived
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming what is
  missing, no row is written, and the message reports neither `write_column_not_allowed` against the
  caller's own column nor `write_output_column_required` against a row of grain and provenance

#### Scenario: An out-of-set column on a complete declaration keeps its own refusal

- **WHEN** `POST /v1/pipelines/{name}/rows` is called for a pipeline whose declaration is complete,
  with a row naming a column outside its declared outputs, grain and provenance set
- **THEN** the request is rejected with HTTP 422 `write_column_not_allowed` exactly as before, and a
  row naming none of the declared outputs keeps `write_output_column_required`

#### Scenario: Materialize of an incomplete declaration is refused before any statement runs

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for an enabled `sql` pipeline whose
  declaration carries no transform or no resolvable output
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, no
  `INSERT … SELECT` is issued and no cursor or watermark advances

#### Scenario: Materialize of a complete declaration keyed too coarsely is refused by the row-grain re-check

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for a Class-1 pipeline whose
  declaration names every member its kind requires but whose target is keyed more coarsely than its
  read source's `identity_column`, and which is enabled — a state reached by drift, since arming it is
  refused by the gate
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the row-grain
  rule, and no `INSERT … SELECT` is issued — rather than materializing rows the target's
  `ReplacingMergeTree` would collapse onto an arbitrary survivor

#### Scenario: A stale derivation is treated as absent at materialize

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for an enabled pipeline whose stored
  `output_bindings` were derived from a transform the declaration has since replaced, and no longer
  re-derive from it
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, rather than
  writing a newly authored expression into a column bound by the previous one

#### Scenario: A drifted but complete declaration is not re-gated

- **WHEN** any of the four endpoints is called for an enabled pipeline whose stored declaration is
  complete but no longer passes its kind's gate — a read-source column renamed since it was written,
  say — or either read endpoint is called for such a pipeline while it is disabled
- **THEN** the call proceeds exactly as it does today, because refusing it would stop a running
  pipeline for drift the operator did not cause — subject, at `/materialize`, to its two re-checks
  (result-type fit and row grain), which still refuse a drift that would write a silently wrong row

#### Scenario: A wrong contract version is still answered before the completeness check

- **WHEN** a caller at a mismatched `Adas-Contract-Version` calls any of the four endpoints for a
  pipeline whose declaration is incomplete
- **THEN** the response is HTTP 409 for the contract mismatch, decided before the pipeline is loaded,
  and the declaration problem is not reported instead

#### Scenario: A complete and valid declaration is unaffected on every one of the four endpoints

- **WHEN** any of the four endpoints is called for an enabled pipeline whose declaration is complete
  and passes its kind's gate, or either read endpoint is called for such a pipeline while it is disabled
- **THEN** the endpoint behaves exactly as it does today

### Requirement: A disabled pipeline's write paths refuse

`POST /v1/pipelines/{name}/rows` and `POST /v1/pipelines/{name}/materialize` SHALL refuse a pipeline
that is disabled with HTTP 409 `pipeline_conflict`, before reading or writing anything and whatever its
declaration holds, after the `Adas-Contract-Version` check, `unknown_pipeline` and the caller's
per-target authorization.

A disabled pipeline writes to its target by no route (see
[pipeline-registry](../pipeline-registry/spec.md), "Register and enable lifecycle"), and these two were
the routes that did not enforce it. Their tolerance existed so a paused pipeline could still be
backfilled, which no consumer relied on: the Enrichment Runner reads only the `enabled=true` feed, drops
a disabled pipeline from its cache, and stalls its own backfill of one until it is re-enabled. Once a
declaration may be stored before it passes the gate, the tolerance would also have written a target
from a declaration that was never armed and so never validated.

409 rather than 422, because the call may be perfectly good and only the pipeline's state is wrong — the
answer a disabled `aggregate` pipeline's on-demand refresh already gives. The two read endpoints,
`…/scan` and `…/group-rows`, SHALL stay open to a disabled pipeline: they write nothing, and the dry-run
preview of a disabled pipeline samples through them.

The cost is accepted and stated: work the runner already has in flight when an operator disables a
pipeline, before its next sync, has its write refused, and those labels go to its dead-letter handling.

#### Scenario: A write-back to a disabled pipeline is refused

- **WHEN** `POST /v1/pipelines/{name}/rows` is called for a disabled pipeline
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict` and no row is written

#### Scenario: A materialize of a disabled pipeline is refused

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for a disabled pipeline, whether its
  declaration is complete or a draft
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict`, no statement is executed, and no
  position or drained horizon is recorded

#### Scenario: The contract and existence checks still come first

- **WHEN** either endpoint is called for a disabled pipeline with a wrong `Adas-Contract-Version`, or
  for a name that resolves to no pipeline
- **THEN** the response is the contract 409 or the 404 `unknown_pipeline` respectively, not the
  disabled-pipeline refusal

### Requirement: A scan call reports its outcome in one log line

Each call to `POST /v1/pipelines/{name}/scan` that returns a page SHALL log one INFO line naming the
pipeline, its generation, the rows returned, `has_more`, `lag_s`, the statement duration, the call's total
duration and the ClickHouse `query_id` of the scan statement. The line SHALL carry no row values, no cursor
tuple and no SQL. A call that returns an empty page SHALL log that line only when the pipeline's previous
scan call on the same replica returned rows, or on its first call, so a caught-up pipeline polling on a
short cadence does not log one line per poll.

The state used for that throttle is held in memory per replica. Several replicas therefore each log their
own first empty page, which is acceptable because the pod name is on the exported log resource.

#### Scenario: A page of rows is logged once

- **WHEN** a scan call returns a page of rows
- **THEN** exactly one INFO line reports the pipeline, the generation, the row count, `has_more`, `lag_s`,
  the durations and the `query_id`, and no row value or cursor tuple

#### Scenario: Repeated empty pages are logged once

- **WHEN** a pipeline's scan calls return an empty page several times in a row on one replica
- **THEN** only the first of them logs an outcome line

### Requirement: A runner's attempts are visible separately from its successful contact

The service SHALL publish, per `enrich` pipeline, the time since the runner last **attempted** one of that
pipeline's data-plane endpoints, as a series separate from the existing last-contact staleness gauge. An
attempt SHALL be recorded for every call to an existing, enabled `enrich` pipeline, whatever its outcome, so a
call refused after the pipeline resolved — for authorization, a budget, or any other reason — still counts as
an attempt. It is recorded when the call completes. A call that succeeded resolved the pipeline on its way, so
recording it SHALL cost no registry read of its own; only a refused call is checked against the registry,
and a call refused as an unknown pipeline is not checked at all. A call refused because the pipeline is
disabled SHALL NOT be recorded: disabling drops the pipeline's series, and a straggling call that re-created
one would leave a gauge climbing for a pipeline nothing is meant to call. A call refused by the runner
contract check SHALL NOT be recorded as an attempt, because that check runs before the pipeline is looked up
and the only name available is the unvalidated path segment.

The existing last-contact gauge SHALL keep its meaning, "last successfully called", unchanged. The two
series together separate a runner that is down (both climb) from a runner that calls and is refused (the
attempt series stays low while contact climbs). The attempt series SHALL follow the same lifecycle rules as
the contact series: it is registered on the first attempt, measured against the present at observation
time, and dropped by every path that deletes or disables the pipeline.

The service SHALL NOT log a "runner contact stale" warning. Each replica records only the calls the load
balancer routed to it, so a replica that is rarely routed to would warn falsely. The signal is the gauge's
minimum across replicas.

#### Scenario: A refused call moves the attempt series but not contact

- **WHEN** the runner calls an existing `enrich` pipeline's data-plane endpoint and the call is refused after
  the pipeline was resolved
- **THEN** that pipeline's attempt series is reset to zero and its last-contact staleness keeps climbing

#### Scenario: A disabled pipeline's refusal records no attempt

- **WHEN** the runner calls a disabled `enrich` pipeline's data-plane endpoint and is refused with `409`
- **THEN** no attempt is recorded, and the series its disabling dropped is not re-created

#### Scenario: A served call costs no extra registry read

- **WHEN** a data-plane call succeeds
- **THEN** its attempt and its contact are recorded without a registry read beyond the ones the call made

#### Scenario: A contract refusal records no attempt

- **WHEN** a data-plane call is refused for a missing or mismatched contract version
- **THEN** no attempt is recorded and no attempt series is created for the name in the path

#### Scenario: A deleted or disabled pipeline leaves no attempt series

- **WHEN** an `enrich` pipeline that has been attempted is deleted or disabled
- **THEN** its attempt series is removed together with its contact series

#### Scenario: No stale-contact warning is logged

- **WHEN** no data-plane call reaches a replica for a pipeline for a long time
- **THEN** that replica logs no warning about stale runner contact, and only the gauges report it

### Requirement: The pipeline data plane refuses a row-confined caller

The pipeline data plane SHALL refuse a caller confined by a row policy on a table it reads.

The scan, the group assembly and the Class-1 materialize — and the enrich dry-run preview, which reads
through them — read the pipeline's sources with pipeline settings, which carry no row-policy filter (see
[table-access-control](../table-access-control/spec.md)). A caller confined by a row policy on any table
those endpoints may read for the pipeline — its read source, its target's `source_table`, and every
enrichment of either — SHALL therefore be rejected with HTTP 403 and error code `row_policy_confined`,
naming the table, after the read and write authorization those endpoints already require has passed. The
data plane SHALL NOT serve a confined caller a filtered read instead: the exactly-once scan cursor and the
group assembly are defined over the whole source, and a filtered view would silently drop rows from a
derivation.

The write endpoint (`POST /v1/pipelines/{name}/rows`) reads nothing and SHALL NOT be subject to this
check; row policies narrow reads only.

In `none` mode the check SHALL NOT apply.

#### Scenario: A confined runner cannot scan

- **GIVEN** a caller holding `read` on a pipeline's read source and `write` on its target, whose role is
  confined by a row policy on that read source
- **WHEN** it calls `POST /v1/pipelines/{name}/scan`, the group assembly or the materialize endpoint
- **THEN** the request is rejected `403 row_policy_confined`

#### Scenario: A confined caller may still write back

- **WHEN** the same caller posts rows to `POST /v1/pipelines/{name}/rows`
- **THEN** the write is authorized by the target's `TABLE_WRITE` as before

### Requirement: Group member selection pre-selects partitions from an aligned group key

The selection phase of `POST /v1/pipelines/{name}/group-rows` SHALL pre-select partitions when the pipeline's
`trigger.group_by` names a `string` column of an aligned enrichment. It SHALL render the group-key predicate on the bare
column (`col = '<key>'`, never `toString(col) = '<key>'`) and SHALL apply the partition pre-selection of the
structured-query capability ("A lookup on an aligned enrichment pre-selects the source's partitions") with that predicate
as the qualifying conjunct, bounded by the pipeline `filter`'s own bounds on the source's partition column. The
enrichment supplying the key SHALL be restricted to the key's rows; every other aligned join SHALL be pruned to the
selected partitions through its tuple join; and every join without an aligned partition pair SHALL be keyed on the
pre-selection together with the `filter`'s source-only conjuncts and the safety lag, so it is keyed rather than unkeyed.
The selected members SHALL be the same as without the pre-selection.

A group key of any other type SHALL be compared through `toString(col) = '<key>'` and SHALL NOT be pre-selected,
because the caller's key arrives as text and parsing it would change which values match. A blank group key is refused
before any selection, so no default-valued key reaches the pre-selection.

#### Scenario: Selecting a session's members reads only its active days

- **GIVEN** `session-insights-live` grouping by `usage_client_identity.client_session_id`, aligned
- **WHEN** the group rows of one session are assembled
- **THEN** the selection restricts `dial_usage_log` and `usage_client_identity` to the partitions holding
  that session's enrichment rows, and returns the same members as before

#### Scenario: A non-string group key is not pre-selected

- **GIVEN** a group pipeline whose `trigger.group_by` is a `long` column of an aligned enrichment
- **WHEN** the group rows of a key are assembled
- **THEN** the key is compared through `toString` and no partition pre-selection is emitted

#### Scenario: A blank group key is refused

- **WHEN** the group rows of the key `''` are requested
- **THEN** the response is HTTP 400 `bad_request` and no selection is run

### Requirement: The scan carries an aligned target's partition value and the write-back returns it

The scan (`POST /v1/pipelines/{name}/scan`) of a row-trigger pipeline whose target is aligned SHALL return, in
every row, a member `__partition_v` holding the source row's alignment-column value (`request_time` for
`dial_usage_log`), whatever the declaration's inputs. The pipeline write-back (`POST /v1/pipelines/{name}/rows`)
for such a pipeline SHALL require `__partition_v`, non-null, on each row and SHALL write it into the target's
alignment column. A row without it, or with it `null`, SHALL be refused with HTTP 422 `validation_error` naming
`__partition_v`, before any row of the request is written. A scan or write-back of a pipeline whose target is not
aligned SHALL neither return nor accept the member: the write-back refuses it as a system column with HTTP 422
`write_column_not_allowed`. The scan and the write-back SHALL decide whether the member applies by one shared rule,
so the write-back requires it exactly when the scan returns it.

On the write-back the member SHALL be the only source of the alignment column's value. A row naming the alignment
column itself SHALL be refused with HTTP 422 `write_column_not_allowed` unless the declaration binds an output to
it; a row naming both a bound alignment column and `__partition_v` SHALL be refused with HTTP 422
`validation_error` when the two values differ, rather than one silently overwriting the other.

The write-back's inserts SHALL NOT be limited by ClickHouse's `max_partitions_per_insert_block`: a batch spanning
more than 100 day partitions of an aligned target is written, not refused.

The value travels with the row because the write-back is the only writer of an LLM pipeline's rows and ADAS
holds no per-caller state between the two calls: looking it up at write-back would scan the source's identity
column across every partition per batch, which is the cost the aligned layout removes. The Enrichment Runner
carries it from the scanned row to the write row unchanged; a wrong value only leaves that row unmatched under
the tuple join, and attaches it to no other source row.

The runner contract version this build serves SHALL be `4`, so a runner that does not echo the value
is refused by the exact-match contract check rather than having its rows refused one by one.

#### Scenario: The scan returns the partition value of an aligned target

- **GIVEN** a row-trigger `llm` pipeline targeting an aligned enrichment of `dial_usage_log`
- **WHEN** it is scanned
- **THEN** every returned row carries `__partition_v` equal to its source row's `request_time`

#### Scenario: The echoed value lands in the alignment column

- **WHEN** the runner writes back a row carrying the `__partition_v` it was scanned with
- **THEN** the row is written with that `request_time` and a query projecting the enrichment over its source
  row reads the written labels

#### Scenario: A write-back row without the value is refused

- **WHEN** a write-back row for an aligned target omits `__partition_v`, or carries it as `null`
- **THEN** the response is HTTP 422 `validation_error` naming `__partition_v`, and nothing is written

#### Scenario: A write-back row carrying the alignment column instead of the value is refused

- **GIVEN** a row-trigger `llm` pipeline targeting an aligned enrichment, with no output bound to `request_time`
- **WHEN** a write-back row carries `request_time` but no `__partition_v`
- **THEN** the response is HTTP 422 `write_column_not_allowed` and nothing is written

#### Scenario: An unaligned target is unaffected

- **GIVEN** a row-trigger `llm` pipeline whose target is not aligned
- **WHEN** it is scanned and written back
- **THEN** the scan rows carry no `__partition_v`, the write-back does not require it, and a row carrying it is
  refused with HTTP 422 `write_column_not_allowed`

### Requirement: Only a row-trigger pipeline of the target's own source can write an aligned enrichment

Registering or patching an `enrich` pipeline over an aligned target so that it is armed SHALL be refused with
HTTP 422 `pipeline_validation_failed` when the pipeline, of either transform type (`llm` or `sql`), declares a read
source other than the target's own source, or when its trigger is `group`. A row of another table holds no value
of the target's source rows to carry, and a row written from an assembled group has no single source row to take
it from; neither the scan page nor the group-rows assembly carries `__partition_v` for such a pipeline, so every
row it wrote would match nothing.

A target whose automatic relayout is in progress (`layout: migrating`) SHALL be judged as the aligned table it is
about to become: the two refusals above apply to it, and arming an `llm` pipeline onto it SHALL additionally be
refused with HTTP 503 `table_relayout_in_progress` until the relayout finishes, because its scans would carry no
`__partition_v` until the catalog records the alignment and every write-back after that would be refused. A `sql`
pipeline of the target's own source SHALL be accepted, since materialize writes the alignment column itself.

#### Scenario: A pipeline reading another table cannot target an aligned enrichment

- **GIVEN** an aligned enrichment of `dial_usage_log`
- **WHEN** a row-trigger pipeline targeting it, `llm` or `sql`, declares `rate_analytics` as its read source and is
  armed
- **THEN** the response is HTTP 422 `pipeline_validation_failed`

#### Scenario: A group-trigger pipeline cannot target an aligned enrichment

- **GIVEN** an aligned enrichment of `dial_usage_log` keyed on `event_id`
- **WHEN** a group-trigger pipeline with `group_by: event_id` targeting it is armed
- **THEN** the response is HTTP 422 `pipeline_validation_failed`

#### Scenario: An llm pipeline waits for its target's relayout

- **GIVEN** an enrichment whose relayout is in progress
- **WHEN** an `llm` pipeline targeting it is armed
- **THEN** the response is HTTP 503 `table_relayout_in_progress`, while a `sql` pipeline of its own source is accepted

### Requirement: The pipeline write-back refuses during a target's relayout hold

`POST /v1/pipelines/{name}/rows` for a pipeline whose target is held by a relayout SHALL be refused with
HTTP 503 `table_relayout_in_progress` before any write, as specified by the table-management capability
("The writers of a relaying-out enrichment are held briefly"). The refusal SHALL NOT change the runner
contract state: the runner treats a 503 as a transient gateway error and retries the write.

#### Scenario: A write-back during the hold is refused

- **WHEN** a write-back targets an enrichment whose relayout is holding writers
- **THEN** the response is HTTP 503 `table_relayout_in_progress` and nothing is written
