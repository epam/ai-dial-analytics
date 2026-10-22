## RENAMED Requirements

- FROM: `### Requirement: Rule-scoped incremental scan endpoint`
- TO: `### Requirement: Pipeline-scoped incremental scan endpoint`
- FROM: `### Requirement: Rule-scoped versioned write-back endpoint`
- TO: `### Requirement: Pipeline-scoped versioned write-back endpoint`
- FROM: `### Requirement: Rule-scoped scan and write authorization`
- TO: `### Requirement: Pipeline-scoped scan and write authorization`
- FROM: `### Requirement: Rule-scoped group-input assembly endpoint`
- TO: `### Requirement: Pipeline-scoped group-input assembly endpoint`
- FROM: `### Requirement: A rule's read window is clamped to its referenced enrichments' positions`
- TO: `### Requirement: An ENRICH pipeline's read window is clamped to its referenced enrichments' positions`
- FROM: `### Requirement: A rule's state reports its freshness exposure`
- TO: `### Requirement: An ENRICH pipeline's state reports its freshness exposure`

## MODIFIED Requirements

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


### Requirement: Pipeline-scoped incremental scan endpoint

The system SHALL expose `POST /v1/pipelines/{name}/scan` accepting a body `{cursor?, limit?, from?, to?}`
and returning `{rows[], nextCursor, hasMore, lagSeconds}`. The system SHALL resolve the pipeline's
stored, already-validated source, filter, referenced columns, and grain from the `pipeline` record and
the target enrichment's catalog row; the caller SHALL NOT supply a table name, filter, or column list.
The scan SHALL require the pipeline to **exist** but NOT to be `enabled` (a paused pipeline can still be
backfilled). An unknown pipeline SHALL return HTTP 404 `unknown_pipeline`. When `limit` is omitted the
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

#### Scenario: Scan of a disabled-but-existing pipeline succeeds

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline that exists but is `enabled=false`
- **THEN** the scan proceeds normally (enablement gates only the live recurring schedule, not backfill)

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


### Requirement: Scan projection and version isolation

The scan query SHALL read the base source, joined to **each `ACTIVE` enrichment whose columns the scan's
own clauses reference and to no other** — an enrichment nothing references SHALL contribute no join, so a
declaration naming none renders the statement it rendered before enrichment references were admitted. Each join
SHALL be a single-key `LEFT JOIN` on the shared grain key, which cannot inflate the source's row count.
The scan SHALL push projection down to `{fields referenced by the pipeline's filter and input bindings} ∪
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

- **WHEN** the same bindings are declared on a pipeline whose `trigger.kind` is `on_ingest` or `schedule`
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

The **permitted provenance columns** SHALL be `evaluator_version`, `model`, `enriched_at`,
`group_version`, and `truncated`, supplied by the caller and written as ordinary user columns.
`group_version` records the version of the input the row was computed from — for a `group` pipeline, the
maximum version-column value across the members the caller saw — and `truncated` records whether that
input had been trimmed, whether by the group-assembly caps or by the caller's own budget. The two exist
so a **truncated group's label is distinguishable from a complete group's**, and so a label's input
version is identifiable after the fact: without them a partial judgement reads as a full one, and only
the caller's private state knows which version of the group was judged.

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

#### Scenario: A well-formed write is accepted and collapses by grain

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with rows whose columns are all within the pipeline's outputs plus grain and provenance
- **THEN** the rows are written to the target enrichment and a subsequent `final=1` read returns the latest version per grain key

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


### Requirement: Pipeline-scoped scan and write authorization

The scan, the group assembly, and the write endpoints SHALL be authorized by the pipeline's
**target-enrichment access** (reusing the role-based `access` model), not by a global read/write
authority. A caller lacking the required access on the pipeline's target enrichment SHALL be rejected with
HTTP 403 `pipeline_access_denied`. `FULL_ADMIN` SHALL satisfy the requirement. The group assembly endpoint
SHALL be authorized identically to the scan — it is a pipeline-scoped read of the same source, so a caller
that may scan a pipeline may assemble its groups.

This holds unchanged when the pipeline declares a **read source** other than its target enrichment's
`source_table` (see [enrichment-registry](../enrichment-registry/spec.md)): the authorizing table stays
the target enrichment, and the read source is not separately authorized. That is not a widening — the
`access` model delegates only `write` and `modify` and has no read grant to bypass, so what governs
exposure of the read source's rows is the sensitive-column re-check below, which SHALL be applied
against the **pipeline's read source**. A pipeline reading a table other than its target's is therefore
constrained exactly as one reading its target's is: by who may operate the target enrichment, and by
which of the read source's columns the calling identity may see.

#### Scenario: Least-privilege scan is authorized by target access only

- **WHEN** a non-admin caller holding the target enrichment's access calls `POST /v1/pipelines/{name}/scan`
- **THEN** the scan is authorized without requiring any global read authority

#### Scenario: Missing target access is rejected

- **WHEN** a caller without the required target-enrichment access calls the scan, group assembly, or
  write endpoint
- **THEN** the request is rejected with HTTP 403 and error code `pipeline_access_denied`

#### Scenario: A declared read source is authorized by the target enrichment

- **WHEN** a caller holding the target enrichment's access scans a pipeline whose declared read source is a
  different table
- **THEN** the scan is authorized by the target-enrichment access alone, and the sensitive-column
  re-check is applied against the columns of the declared read source


### Requirement: Sensitive-column re-check on scan

Independently of the target-enrichment access grant, the scan SHALL re-apply the sensitive-column
declaration (see [enrichment-registry](../enrichment-registry/spec.md)) against the **scanning caller's**
identity: if the scan's projection includes a `sensitive`-flagged column of the pipeline's **read source**
and the caller may not see sensitive columns (not `FULL_ADMIN`, and `security.mode` is not `none`), the
scan SHALL be rejected with HTTP 403 `sensitive_column_not_entitled`. This holds even though the stored
declaration was registered by a `FULL_ADMIN` author, so a non-`FULL_ADMIN` runner cannot read sensitive data,
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

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) calls the same scan
- **THEN** the sensitive column is projected and the scan proceeds normally


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
trigger kind SHALL be rejected with HTTP 422 `pipeline_validation_failed`. A `prefer_sql` that fails the
grammar, either field naming a field reachable on neither the read source nor its `ACTIVE` enrichments,
or a `limit` outside its range SHALL each be rejected with HTTP 422 `pipeline_validation_failed`. An
`order_by` direction SHALL be `asc` or `desc`; any other value is rejected as a malformed request body
(HTTP 400), as every other enum-valued field of the API is. A `trigger.member_select` referencing a `sensitive`
column — of the read source or of one of its `ACTIVE` enrichments — SHALL be treated exactly as
`filter` is: pipeline registration and group assembly by a caller who may not see sensitive columns SHALL
be rejected with HTTP 403 `sensitive_column_not_entitled`.

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

- **WHEN** a pipeline whose `trigger.kind` is not `group` is created or updated with `trigger.member_select`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An invalid member selection is rejected at registration

- **WHEN** a group pipeline declares a `trigger.member_select` whose `prefer_sql` fails the closed grammar, whose
  `prefer_sql` or `order_by` names a field reachable on neither the read source nor its `ACTIVE`
  enrichments, or whose `limit` is below 1 or above the configured maximum row count
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A non-entitled caller cannot use a selection over a sensitive column

- **WHEN** a caller who may not see sensitive columns registers or assembles a group for a pipeline whose
  `trigger.member_select` references a `sensitive` column of the read source or of one of its `ACTIVE`
  enrichments
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A pipeline declaring no member selection is unchanged

- **WHEN** a group pipeline carrying no `trigger.member_select` assembles a group
- **THEN** the members retained are the most recent ones by the source's total order, bounded by the
  configured caps, exactly as before


### Requirement: Sensitive-column re-check on group assembly

The group assembly endpoint SHALL re-apply the sensitive-column rule against the **calling** identity,
exactly as the scan does, and SHALL bind the check to the columns the **declaration declares** — its bound
input bindings, its membership filter, and its `trigger.member_select` — rather than to the columns a given read
phase happens to project. If any of those is a `sensitive`-flagged source column and the caller may not
see sensitive columns (not `FULL_ADMIN`, and `security.mode` is not `none`), the request SHALL be rejected
with HTTP 403 `sensitive_column_not_entitled`, **before** either phase reads anything. Sensitive columns
SHALL NOT be silently stripped from the projection — a group input missing the very column the pipeline was
written to read would yield a quietly wrong label, so the misconfiguration fails loudly instead. The scan
and the group assembly SHALL share one implementation of this check so their behaviour cannot diverge.

#### Scenario: An entitled caller receives body columns

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) assembles a group for a pipeline whose
  projection includes `request_body` or `response_body`
- **THEN** those columns are present in the returned member rows

#### Scenario: A non-entitled caller is rejected rather than served a stripped group

- **WHEN** a caller who may not see sensitive columns assembles the same group
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and no
  partially-projected group is returned

#### Scenario: Entitlement is enforced before the selection phase reads

- **WHEN** a caller who may not see sensitive columns assembles a group for a pipeline that binds a sensitive
  column, even though the selection phase would not project it
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and
  neither phase issues a read


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


### Requirement: An ENRICH pipeline's read window is clamped to its referenced enrichments' positions

Every pipeline-scoped read that advances a cursor SHALL bound its window to the position through which each
enrichment its statements join has been materialized, taking the **lowest** such position when several
enrichments are joined. This applies to the incremental scan; the Class-1 materialize window carries the
same clamp (see [class1-enrichment](../class1-enrichment/spec.md)).

The clamp exists because an enrichment row lands **after** the source row it derives from, while a
pipeline's window advances on the source's cursor. Without it a row would be read while its enrichment cell
is still absent, and because the source row does not move again, the consequence is permanent: a group
pipeline would attribute the row to the wrong group or to none and never revisit it, a `trigger.ready_when.signal`
computed inside the scan query would return a verdict derived from an absent value, and an
`input_bindings` value read at assembly would be missing from the prompt. Clamping the window removes
every one of those at once, because a row that does not enter the window contributes to none of them.

Clamping the scan window SHALL be sufficient for a group pipeline's members: the assembly endpoint selects
the **whole** group by key rather than only newly seen members, so a group that is marked is assembled
from every member it has. The unrecoverable case is a source row that is never marked at all, which is
what the clamp prevents.

The clamp's set and the **join** set are therefore not the same set, and each SHALL be derived for what it
governs. A statement SHALL join only the enrichments its own clauses name, so nothing pays a full hash
read of a table it never selects from. The clamp SHALL additionally cover a group pipeline's `input_bindings`
columns, which the scan page does not project but the assembly reads for the very rows that page marks: a
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

