## ADDED Requirements

### Requirement: The request body storage mode is configurable

The service SHALL accept an ingest setting `sync.request-body-mode` (`SYNC_REQUEST_BODY_MODE`), set to
`whole` or `delta`, with default `whole`. Any other value SHALL fail startup.

- **Under `whole`,** `request_body` SHALL be stored as it is today. A chat request's row SHALL also carry its
  `request_path` and `request_depth`, so that a row stored whole can be a parent (see "Rebuild equivalence");
  every other request-delta column except `request_scope` SHALL keep its default.
- **Under `delta`,** a chat request body (see "A chat request body is stored as an envelope, a delta and a
  parent link") SHALL be stored in the request-delta columns with `request_body` `NULL`, and every other
  body SHALL be stored whole (see "A request body that is not a chat request is stored whole").

The setting SHALL apply to rows written after it takes effect only; it SHALL NOT rewrite, convert or
re-read rows already stored. Switching from `delta` back to `whole` SHALL leave rows already written in
delta form as they are, and SHALL keep them rebuildable: a parent re-ingested under `whole` keeps its path.
Switching from `whole` to `delta` SHALL let new requests link to rows stored whole before the switch.

The mode SHALL apply after the inline-file rewrite (see "Inline files are removed from payload bodies in
drop mode"), so a file removed by that rewrite is absent from the stored envelope and delta alike.

#### Scenario: The default stores request bodies whole

- **WHEN** the service starts without `SYNC_REQUEST_BODY_MODE` and ingests a chat request
- **THEN** the payload row carries the body in `request_body` and its `request_path` and `request_depth`, and
  `request_envelope`, `request_delta` and `request_cache_markers` are `NULL`

#### Scenario: An unknown mode fails startup

- **WHEN** the service starts with `SYNC_REQUEST_BODY_MODE=compact`
- **THEN** startup fails, naming the setting and the rejected value

#### Scenario: Switching back to whole leaves delta rows unchanged

- **WHEN** rows were ingested under `delta` and the service is restarted under `whole`
- **THEN** new rows carry `request_body`, and the rows written under `delta` still carry their
  envelope, delta and chain columns with `request_body` `NULL`

#### Scenario: A parent re-ingested under whole keeps its child rebuildable

- **WHEN** A and its child B were stored under `delta`, the service switches to `whole`, and A's event is
  re-ingested
- **THEN** the surviving A row holds its body whole and carries A's path, and B still rebuilds to its history
  through it

#### Scenario: A switch to delta links onto rows stored whole

- **WHEN** request A was stored under `whole`, the service switches to `delta`, and A's follow-up B is ingested
- **THEN** B's parent is A's path, and B rebuilds through A's whole body

### Requirement: A chat request body is stored as an envelope, a delta and a parent link

A request body SHALL be a **chat request** when it parses as a JSON object carrying a **message array** — an
array whose elements are all JSON objects — under the first of `messages`, `input` or `contents` whose value is
an array,
and carries no top-level member of the name the envelope reserves for its history record. An `input` of
strings or token arrays (an embeddings request) is therefore not a message array. Its
**history** SHALL be, in order: the value of each present **top-level system key** — `system`,
`instructions`, `system_instruction`, `systemInstruction`, in that order — then the elements of the message
array. No other key SHALL be treated as history. Each history element SHALL be normalized by removing every `cache_control` member at any depth
(see "Cache markers are kept per event") and canonicalized as JSON with object keys sorted and no
insignificant whitespace.

For a history `h1..hn`, ingest SHALL compute the **prefix hashes**
`p0 = H(project_id, deployment, user_hash, UTC day of request_time)` and `pk = H(p(k-1), canonical(hk))`,
where `H` is a 128-bit hash and the three scope values are those the record's `dial_usage_log` row
carries. `pn` is the request's **path**. Because each prefix hash folds in every element
before it and the scope, two requests SHALL have an equal `pk` only when their first `k` history elements
are identical and they share project, deployment, user and UTC day. Equal message text under a
different history or scope SHALL never yield an equal hash.

The request's **parent** SHALL be a stored or same-flush chat request whose path equals the deepest `pk`
of this request with `1 ≤ k < n` among the sources resolution consults (see "A parent never crosses a UTC day
or a user scope"); a deeper match that only another instance's rows hold MAY be missed, which costs storage only. A request is therefore never its own parent: a parent's history is always
strictly shorter than its child's. The stored row SHALL carry:

- `request_path` = `pn`; `request_depth` = `n`;
- `request_parent` = the parent's path, or all-zero when no parent was found (the request is a **root**);
- `request_ancestors` = up to the 128 nearest paths of the requests on the chain when the row was written,
  root side first, ending with the parent's path; empty for a root. It is a read hint, not the chain itself
  (see rebuild below);
- `request_delta` = the canonical JSON array of `h(k+1)..hn` (the whole history for a root);
- `request_envelope` = the canonical JSON of the body without the history keys (the message array's key and
  every present top-level system key), with a record of which history keys were present so the body can be
  reassembled;
- `request_body` = `NULL`.

**Rebuild equivalence.** A row's history SHALL be its parent path's history followed by its own delta, where
a path's history is that of **any** stored row carrying that path, a root's history is its own delta, and a
row stored whole supplies its history from `request_body`, normalized and canonicalized as above.
Rows carrying the same path MAY differ in parent and delta (a re-ingested event can link differently, and an
event stored as a root can share its path with one stored as a child), yet every one of them yields the same
history, so following `request_parent` through whichever row is found always terminates — each parent is
strictly shallower — and always yields the row's exact canonical history. While a row exists, a row carrying
its parent's path SHALL exist: a path depends only on the stored history, so replacing a row under the
`ReplacingMergeTree` keeps it whichever mode wrote the copy, and parent and child share a daily partition, so
they expire together — up to the part granularity of the TTL: with `ttl_only_drop_parts`, a day still split
across parts can lose an earlier part before a later one, so in the last day of a day's retention a child MAY
name a parent already dropped, and its rebuild then fails as an expired row would. One more case breaks that, and it is an operator's to avoid: a path is computed from the
history as ingest stores it, so re-ingesting an event after changing `sync.inline-files-drop-truncated` or `sync.inline-files-max-size`, or after a
release that changes the canonical form, gives its new copy a different path. A child that is not re-ingested
with it then names a path no row carries any more. Re-ingesting whole UTC days after such a change avoids it,
since a parent and its children always share one.
`request_ancestors` lets a rebuild read the whole chain in one query in the common case, and read further
only where a row found links through a path that list lacks. Restoring the history keys into the envelope
and then the row's own cache markers SHALL yield a JSON document equal to the received body (same members and values; key
order and whitespace are not preserved). This change SHALL store everything a rebuild needs; it SHALL NOT
itself expose a rebuilt body.

#### Scenario: A follow-up turn stores only its new messages

- **WHEN** request A with history `[system s, user u1]` is stored, and later request B with history
  `[system s, user u1, assistant a1, user u2]` by the same user on the same UTC day is ingested
- **THEN** B's row carries `request_parent` = A's path, `request_depth` = 4, and `request_delta` holding
  only `[a1, u2]`

#### Scenario: Equal messages under a different history are not linked

- **WHEN** a stored request has history `[u0, r0, u1, r1]`, and a request in the same scope arrives with
  history `[u1, r1, u2]`
- **THEN** the new request is a root, and its `request_delta` holds all three elements

#### Scenario: A first request is a root

- **WHEN** a chat request has no stored or same-flush request whose path matches any of its prefixes
- **THEN** its row carries an all-zero `request_parent`, an empty `request_ancestors`, and its whole
  history in `request_delta`

#### Scenario: An identical retry is linked like the original

- **WHEN** a request with the same history and scope as a stored request R is ingested as another event
- **THEN** its row carries R's path, and its parent is the deepest stored request R's history extends —
  never R itself

#### Scenario: Envelope fields stay readable

- **WHEN** a chat request carrying `model`, `temperature` and `tools` is stored in delta form
- **THEN** `json_extract_string("dial_usage_log_payload.request_envelope", 'model')` returns its model,
  and `request_envelope` carries `temperature` and `tools` unchanged

#### Scenario: An Anthropic system prompt is part of the history

- **WHEN** two consecutive Anthropic Messages requests of a conversation carry the same top-level `system`
  and the second extends the first's `messages`
- **THEN** the second's delta holds only the messages it added, and neither its envelope nor its delta
  repeats the system value

#### Scenario: A Gemini-style body is a chat request

- **WHEN** a request body carries `system_instruction` and a `contents` array
- **THEN** it is stored in delta form, with `system_instruction` as the first history element and the
  `contents` elements after it, and neither key left in `request_envelope`

#### Scenario: The history rebuilds exactly

- **WHEN** a chain root A and its descendants B and C are stored, and C is rebuilt by following
  `request_parent` from C through stored rows to the root and concatenating the deltas root first
- **THEN** the result equals C's normalized, canonical history element for element

#### Scenario: A rebuild survives a parent re-linked by re-ingest

- **WHEN** B was stored as a root, C was stored as B's child, and B is then re-ingested after A was stored,
  so the surviving B row is A's child with a shorter delta
- **THEN** rebuilding C by following `request_parent` through the surviving rows still yields C's exact
  history

### Requirement: A parent never crosses a UTC day or a user scope

A parent SHALL be in the same daily partition (`toYYYYMMDD(request_time)`, UTC) as its child and in the same
`(project_id, deployment, user_hash)` scope. Because the scope and the day are part of `p0`, a request in
another scope or on another day can never match. The first request of a conversation on a new UTC day SHALL be a
root, so a child never outlives a parent under the daily partition TTL, and rebuilding a request never
reads another user's rows. The scope is the user, not the client session, because the session is not
known at ingest; two conversations of one user with an identical history MAY share rows, which rebuilds
correctly.

Parent resolution SHALL consider, in order: earlier chat requests of the same flush (ordered by
`request_time`), paths this service instance has already written successfully, then the payload table —
asked once per flush, about the requests not yet resolved, and about a bounded number of their prefixes in
all; a request beyond that bound is a root. A path SHALL become a parent candidate only
once its row is written or is in the same insert, so a row never names a parent that was never stored.

A missing parent SHALL NOT be an error. A request whose parent was not found — it arrived before its
parent, another instance is ingesting the parent at the same time, the parent is in an unflushed batch, or
the payload-table lookup failed — SHALL be stored as a root. A failed lookup SHALL be logged and counted and
SHALL NOT fail the flush. This costs storage, never correctness.

#### Scenario: A failed lookup stores roots and keeps ingesting

- **WHEN** the payload-table lookup fails during a flush
- **THEN** the requests it would have resolved are stored as roots, the batch is written, and the failure is
  counted

A re-ingested event MAY resolve a different parent than its earlier copy did (for example, its parent has
been stored in the meantime). Both copies carry the same path and rebuild to the same history, so the
`ReplacingMergeTree` collapse keeping either one is correct — for the event itself and for every row that
names its path as parent (see "Rebuild equivalence").

#### Scenario: A conversation crossing midnight starts a new chain

- **WHEN** a user's requests at 23:58 and 00:03 UTC on consecutive days are ingested, and the second
  extends the first's history
- **THEN** the second request is a root, with its whole history in `request_delta`

#### Scenario: Another user's identical history is not a parent

- **WHEN** two users of the same deployment send requests with identical histories
- **THEN** neither request is the other's parent, and each user's chain contains only that user's rows

#### Scenario: A parent in the same flush is found

- **WHEN** one flush holds two consecutive turns of a conversation, neither previously stored
- **THEN** the later turn's parent is the earlier one, and both rows are written in that flush

#### Scenario: A child ingested before its parent is a root

- **WHEN** a conversation's later turn is ingested in a flush before its earlier turn has been stored
- **THEN** the later turn is stored as a root, and storing the earlier turn afterwards does not change
  the later row

#### Scenario: A re-ingested event keeps a correct row

- **WHEN** an event first stored as a root is re-ingested after its parent has been stored
- **THEN** a deduplicated read returns one row for that `event_id`, and whichever copy it is, following
  `request_parent` from it yields its history

### Requirement: Cache markers are kept per event

Under `sync.request-body-mode=delta`, every `cache_control` member removed from a chat request's history
SHALL be recorded in that row's `request_cache_markers` as a JSON array of entries, each giving the index
of the history element, the location of the member inside that element, and its value verbatim.
`request_cache_markers` SHALL be `NULL` for a request that carried none. Markers SHALL belong only to
the event that carried them and SHALL NOT be inherited along a chain: a rebuild restores the row's own
markers into the assembled history and no others. `cache_control` members outside the history (for
example on `tools`) SHALL stay in `request_envelope` unchanged.

Removing the markers from the hash is what lets consecutive turns link: clients move the marker to the
newest message on each turn, so the same earlier message would otherwise hash differently on every turn.

#### Scenario: A moved marker does not break the chain

- **WHEN** turn 1 carries `cache_control` on its last user message, and turn 2 repeats that message
  without it and adds two messages carrying `cache_control` on the newest
- **THEN** turn 2's parent is turn 1, its delta holds only the two added messages without markers, and
  its `request_cache_markers` records the one marker on its newest message

#### Scenario: Markers are not inherited

- **WHEN** turn 2's markers are restored during a rebuild
- **THEN** the assembled request carries turn 2's own markers only, not turn 1's

#### Scenario: A request without markers stores null

- **WHEN** a chat request carrying no `cache_control` member is stored in delta form
- **THEN** its `request_cache_markers` is `NULL`

### Requirement: A request body that is not a chat request is stored whole

Under either mode, a request body that is not a chat request — an MCP JSON-RPC body, an embeddings request
(an `input` of strings or token arrays included), a value that does not parse as JSON (including a body
truncated at 4 MiB), a JSON object naming one member twice, a request whose `request_time` carries a UTC
offset other than zero (its text day need not be its partition day), or a JSON value of any other shape — SHALL be stored in `request_body` exactly as it is
stored today, with every request-delta column other than `request_scope` at its default. Such a request SHALL
never be a parent. A record with no request body SHALL store `NULL` in `request_body` and in every request-delta
content column.

#### Scenario: An MCP body is stored whole

- **WHEN** an MCP `tools/call` request is ingested under `delta`
- **THEN** its row carries the body in `request_body`, and `request_delta` and `request_envelope` are
  `NULL`

#### Scenario: A batched embeddings request is stored whole

- **WHEN** a request body `{"model":"e","input":["chunk a","chunk b"]}` is ingested under `delta`
- **THEN** its row carries the body in `request_body`, and no request-delta column is set

#### Scenario: A truncated chat body is stored whole

- **WHEN** a chat request body cut at 4 MiB, and therefore not valid JSON, is ingested under `delta`
- **THEN** its row carries the body in `request_body` as the inline-file mode leaves it, and it is not
  linked to any parent

## MODIFIED Requirements

### Requirement: Body columns are captured only by the payload table's mapping

The three unbounded body columns — `request_body`, `response_body`, `assembled_response` — SHALL be
persisted to their own ClickHouse table, `dial_usage_log_payload`, and SHALL NOT be persisted to
`dial_usage_log`. This is enforced at both levels: no mapping targeting `dial_usage_log` captures a
value for any of them, and `V11` has dropped the columns from that table, so there is nothing there to
write to. The bodies are stored as the producer sent them, except that `sync.inline-files-max-size`
removes the payloads of their inline files longer than it and `sync.inline-files-drop-truncated` cuts a body
truncated inside a file back to before it (see "Inline files are removed from payload bodies in drop mode"), and that under
`sync.request-body-mode=delta` a chat request body is stored as an envelope, a delta and a parent link
instead (see "A chat request body is stored as an envelope, a delta and a parent link").

The table SHALL be provisioned by a versioned ClickHouse migration (never by the `/v1/tables` API) and
SHALL declare:

- the grain column `event_id UUID`, which is also its sole `ORDER BY` column and therefore its unique
  key;
- `request_time`, which drives both `PARTITION BY toYYYYMMDD(request_time)` and the retention `TTL`;
- `trace_id` and `core_span_id`, so the deduplication-key drop rule is decidable on a payload row;
- the three body columns as `Nullable(String)` under the `AES_256_GCM_SIV` codec (which requires a
  server-side encryption key, or `CREATE TABLE` fails);
- the claim and header documents `jwt_claims` and `request_tags` as `JSON`, carried across verbatim
  from `dial_usage_log` — same type, no codec. They are necessarily NON-nullable, because ClickHouse
  has no `Nullable(JSON)`;
- the request-delta columns, added by a later migration with defaults so rows written before it read
  them as empty: `request_envelope`, `request_delta` and `request_cache_markers` as `Nullable(String)`
  under the same `AES_256_GCM_SIV` codec as the bodies; the chain columns `request_scope` (a hash of the
  record's project, deployment and user, written by the mapping on every row), `request_path` and
  `request_parent` (128-bit hashes, all-zero when absent), `request_depth` (the number of history elements of
  the full request, `0` when absent) and `request_ancestors` (an array of path hashes, empty when absent); and a
  skip index on `request_path` (a low false-positive bloom filter), so a parent lookup asking about a few paths
  within one daily partition can skip granules instead of reading the partition's `request_path` whole;
- the `ReplacingMergeTree` engine versioned by a table-side `_updated_at` default, so a re-ingested
  record replaces its predecessor for the same `event_id` in the same daily partition.

`_updated_at` SHALL be the only column of this table left to its ClickHouse default on every row. The
request-delta columns are filled by ingest's request-body stage, not by the mapping; under
`sync.request-body-mode=whole` only a chat request's `request_path` and `request_depth` are set, and for a
body that is not a chat request every one keeps its default.

One parsed record SHALL produce a `dial_usage_log` row **and** a `dial_usage_log_payload` row, from two
mappings evaluated over the same record under the same match-all predicate, carrying the same
`event_id`. Because both targets apply the same deduplication-key drop rule to the same record, the two
rows SHALL be either both emitted or both dropped — so an ingested `dial_usage_log` row SHALL never
lack its payload row for a reason internal to ingest.

**The payload row SHALL become visible no later than its `dial_usage_log` row.** The two targets batch
independently, so the run sink SHALL flush in dependency order: when the `dial_usage_log` sink reaches
its threshold, or at end of run, the sinks holding its enrichments (the payload sink) SHALL be written
first and the source sink after; the payload sink reaching its own threshold MAY flush alone. A failed
payload flush SHALL propagate before the source rows of the same batch are written, so a run faults
with both tables equally behind rather than with usage-log rows whose payloads never arrive. Which
targets enrich which SHALL be read from the catalog (`table_definition.type = ENRICHMENT` and
`source_table`), once per run. The reason is the read side: every consumer of the grain join — a
query, an enrichment scan, an aggregate refresh — treats an unmatched payload as absent and final, and
a scan that read a source row while its payload row was still buffered would derive a value from a
`NULL` body and never revisit the row. The freshness classification for such an enrichment is
`ingest_written` (see the pipeline-registry capability), not `no_pipeline`.

`jwt_claims` and `request_tags` SHALL move with the bodies. They are small, so storage is not the
argument; they can carry bearer tokens, cookies and forwarded client IPs, so disclosure is. Because
they are non-nullable, an unmatched grain join renders them as the empty document rather than as
`NULL` — which the `enrichment-runtime` capability's nullability rule permits for `object` columns
specifically, since no accessor can distinguish `{}` from a present document lacking the key.

#### Scenario: One record produces a row in both tables

- **WHEN** a run ingests a record carrying `request.body`, `response.body`, `assembled_response`,
  `claims` and `headers`
- **THEN** `dial_usage_log` receives a row with the analytic columns, and `dial_usage_log_payload`
  receives a row keyed on the same `event_id` carrying all five verbatim values (under
  `sync.request-body-mode=whole`). With
  `sync.inline-files-max-size` set the three bodies lack the payloads of their inline files longer than it,
  and with `sync.inline-files-drop-truncated` on a body DIAL Core cut inside a file is cut back before it;
  they are otherwise verbatim.

#### Scenario: The payload row keys on the parent's event id

- **WHEN** a payload row is written for a record
- **THEN** its `event_id` is the same deterministic hash of (`trace_id`, `core_span_id`) that the
  record's `dial_usage_log` row carries, so the two rows join one-to-one

#### Scenario: A re-ingested record replaces its payload row

- **WHEN** the same record is ingested more than once
- **THEN** a deduplicated read of `dial_usage_log_payload` returns a single row for that `event_id`,
  the latest-written copy

#### Scenario: The payload batch is written before the usage-log batch

- **WHEN** the `dial_usage_log` sink reaches its batch threshold while payload rows for the same records
  are buffered, or the run ends with rows buffered in both sinks
- **THEN** the payload sink is flushed first and the `dial_usage_log` sink after, so no usage-log row is
  visible before its payload row

#### Scenario: A failed payload flush withholds the usage-log rows of the same batch

- **WHEN** writing the payload batch fails
- **THEN** the run faults before the `dial_usage_log` batch is written, and neither table receives the
  batch's rows until a later run re-ingests them

#### Scenario: The payload sink filling on its own flushes alone

- **WHEN** the payload sink reaches its threshold before the `dial_usage_log` sink does
- **THEN** only the payload batch is written; the usage-log sink keeps batching to its own threshold

#### Scenario: A record without a dedup key is dropped from both tables

- **WHEN** a record's `trace_id` or `core_span_id` is absent or blank
- **THEN** neither a `dial_usage_log` row nor a `dial_usage_log_payload` row is inserted for it, and the
  drop is logged and counted for each target independently

#### Scenario: The body columns are unpopulated on dial_usage_log

- **WHEN** the shipped mapping ingests a record
- **THEN** the inserted `dial_usage_log` row carries no value for `request_body`, `response_body`,
  `assembled_response`, `jwt_claims` or `request_tags`, because the mapping for that table does not
  capture them and the physical table no longer declares them

#### Scenario: The claim and header documents move with the bodies

- **WHEN** a record carrying `claims` and `headers` is ingested
- **THEN** the `dial_usage_log_payload` row carries `jwt_claims` and `request_tags`, and the
  `dial_usage_log` row carries neither
