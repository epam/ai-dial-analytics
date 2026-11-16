## MODIFIED Requirements

### Requirement: The request body storage mode is configurable

The service SHALL accept an ingest setting `sync.request-body-mode` (`SYNC_REQUEST_BODY_MODE`), set to
`whole`, `whole-with-path` or `delta`, with default `whole`. Any other value SHALL fail startup.

- **Under `whole`,** `request_body` SHALL be stored as it is today, and no request body SHALL be parsed for this
  setting: every request-delta column except the identity columns SHALL keep its default. A deployment that never
  opts in pays nothing for request-delta storage on its ingest path.
- **Under `whole-with-path`,** `request_body` SHALL be stored as under `whole`, and a chat request's row SHALL also
  carry its `request_hash`, so that a row stored whole can be a parent (see "Rebuild equivalence"), and the row of a
  body eligible for a body hash SHALL carry that hash for the same reason (see "A large request body equal to an
  earlier one is stored once"); every other request-delta column except the identity columns SHALL keep its default.
- **Under `delta`,** a chat request body (see "A chat request body is stored as its new history and a parent
  link") SHALL be stored in `request_body` cut to what its parent does not supply, and every other body SHALL be
  stored whole (see "A request body that is not a chat request is stored whole"), except one that "A large
  request body equal to an earlier one is stored once" makes a copy.

The setting SHALL apply to rows written after it takes effect only; it SHALL NOT rewrite, convert or
re-read rows already stored. Switching from `delta` to either whole mode SHALL leave rows already written in
delta form as they are. Rolling back from `delta` SHALL go to `whole-with-path`: a parent re-ingested under it keeps
its path, so its delta children stay rebuildable. A parent re-ingested under `whole` loses its path, and a delta
child not re-ingested with it then names a path no row carries; avoiding that is the operator's, as for the other
re-ingest cases under "Rebuild equivalence". Switching from `whole-with-path` to `delta` SHALL let new requests link
to rows stored before the switch; rows stored under `whole` carry no path and are never parents.

The mode SHALL apply after the inline-file rewrite (see "Inline files are removed from payload bodies in
drop mode"), so a file removed by that rewrite is absent from the stored body.

The mode SHALL apply to an ingest target whose mapping maps `request_body`, `project_id` and `user_hash` and
whose table has the request-delta columns. The table SHALL be checked once, on the target's first flush, whether or
not startup schema validation is on: a body cut on a table that lacks the chain columns could never be rebuilt, and
validation off is an ordinary setting, not a sign that no table exists. A target whose table lacks the columns SHALL
have its bodies stored as mapped, with a warning. A check that fails SHALL fail that flush before any body is cut,
like any other ClickHouse error. Under `delta` with no target qualifying, startup SHALL log a warning that the
setting has no effect.

A target's mapping is taken here after sensitive-column exclusion (see "Sensitive columns can be excluded from
ingest"). A target whose `request_body` is excluded SHALL therefore not qualify, and SHALL get no request-delta
processing: no body is parsed, and its rows keep every request-delta column except the identity columns at its
default, `request_cache_control` included. Under `whole-with-path` or `delta` with no target qualifying while the
switch is `false`, startup SHALL log one warning that the mode has no effect, naming the switch, in place of the
`delta` warning above, whose advice to check the mappings would mislead.

#### Scenario: The default stores request bodies whole

- **WHEN** the service starts without `SYNC_REQUEST_BODY_MODE` and ingests a chat request
- **THEN** the payload row carries the body as received in `request_body`, an all-zero `request_hash` and
  `request_parent_hash`, an empty `request_ancestor_hashes`, and a `NULL` `request_cache_control`

#### Scenario: whole-with-path stamps a chat request with its path

- **WHEN** the service runs with `SYNC_REQUEST_BODY_MODE=whole-with-path` and ingests a chat request
- **THEN** the payload row carries the body as received in `request_body` and its `request_hash`,
  an all-zero `request_parent_hash`, an empty `request_ancestor_hashes`, and a `NULL` `request_cache_control`

#### Scenario: The table is checked with schema validation off

- **WHEN** the service runs under `delta` with `sync.validate-schema=false` and the payload table lacks the
  request-delta columns
- **THEN** its rows carry `request_body` as mapped and a warning names the table

#### Scenario: A table without the request-delta columns keeps its bodies

- **WHEN** a custom target maps `request_body`, `project_id` and `user_hash` onto a table without the
  request-delta columns, under `delta`
- **THEN** its rows carry `request_body` as mapped and a warning names the table

#### Scenario: An unknown mode fails startup

- **WHEN** the service starts with `SYNC_REQUEST_BODY_MODE=compact`
- **THEN** startup fails, naming the setting and the rejected value

#### Scenario: Switching back to a whole mode leaves delta rows unchanged

- **WHEN** rows were ingested under `delta` and the service is restarted under `whole` or `whole-with-path`
- **THEN** new rows carry their whole body, and the rows written under `delta` still carry their cut
  `request_body` and their chain columns

#### Scenario: A parent re-ingested under whole-with-path keeps its child rebuildable

- **WHEN** A and its child B were stored under `delta`, the service switches to `whole-with-path`, and A's event is
  re-ingested
- **THEN** the surviving A row holds its body whole and carries A's path, and B still rebuilds to its history
  through it

#### Scenario: A switch to delta links onto rows stored with their path

- **WHEN** request A was stored under `whole-with-path`, the service switches to `delta`, and A's follow-up B is
  ingested
- **THEN** B's parent is A's path, and B rebuilds through A's whole body

#### Scenario: An excluded request body gets no request-delta processing

- **WHEN** the service starts with `SYNC_SENSITIVE_COLUMNS_ENABLED=false` and `SYNC_REQUEST_BODY_MODE=delta`, and
  ingests a chat request
- **THEN** startup logs one warning that the mode has no effect, naming `sync.sensitive-columns-enabled`, and the
  payload row carries a `NULL` `request_body` and `request_cache_control`, an all-zero `request_hash` and
  `request_parent_hash`, and an empty `request_ancestor_hashes`

### Requirement: A request body that is not a chat request is stored whole

The service SHALL store a request body that is not a chat request in `request_body` exactly as it is stored
under `whole`, under every mode, with every request-delta column other than the identity columns at its default,
unless, under `whole-with-path` or `delta`, it is eligible for a body hash (see "A large request body equal to an
earlier one is stored once"). Such a
body is an MCP JSON-RPC body, an embeddings request (an `input` of strings or token arrays included), a value
that does not parse as JSON (including a body truncated at 4 MiB), a JSON object naming one member twice, a
request whose `request_time` is written with a numeric UTC offset, `+00:00` included (its text day need not be its
partition day),
or a JSON value of any other shape. Such a request SHALL never be the parent of a chat request. A record with no request body SHALL store `NULL` in `request_body` and in every request-delta
content column.

#### Scenario: An MCP body is stored whole

- **WHEN** an MCP `tools/call` request is ingested under `delta`
- **THEN** its row carries the body as received in `request_body`, and its `request_hash` is all-zero

#### Scenario: A batched embeddings request is stored whole

- **WHEN** a request body `{"model":"e","input":["chunk a","chunk b"]}` is ingested under `delta`
- **THEN** its row carries the body in `request_body`, and no request-delta column is set

#### Scenario: A truncated chat body is stored whole

- **WHEN** a chat request body cut at 4 MiB, and therefore not valid JSON, is ingested under `delta`, it holds at
  least 65,536 UTF-16 code units as stored, and no earlier request of its project, user and UTC day carried the
  same body
- **THEN** its row carries the body in `request_body` as the inline-file mode leaves it, its body hash in
  `request_hash`, and an all-zero `request_parent_hash`

### Requirement: A rebuilt request body is readable beside the stored one

The payload enrichment SHALL expose two read-only fields computed on read, beside `request_body`, under the qualified
names `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild`. They SHALL NOT be
stored: no column, ingest stage or migration is added for them, and ingest SHALL be unchanged by them.

- **`request_body_full`** (`string`, nullable) SHALL hold the row's request body as the request carried it, rebuilt
  by the rebuild equivalence of "A chat request body is stored as its new history and a parent link": the history its
  `request_parent_hash` chain supplies followed by its own message array elements, put back under the key the
  classification chose; for a row with a parent, each system key and the `tools` member of the root the chain ends
  at; and the row's own cache markers. A rebuilt body SHALL be the same JSON document the request carried — same
  members and values; key order and whitespace are not preserved — written as compact JSON. For a copy (see "A
  large request body equal to an earlier one is stored once") it SHALL instead hold the `request_body` of a row
  carrying the copy's `request_parent_hash`, byte for byte: that body is not JSON and is never re-written.
- **`request_body_rebuild`** (`enum` of `stored`, `rebuilt`, `resolvable`, `partial`; nullable) SHALL say what
  `request_body_full` holds:
  - `NULL`, with `request_body_full` `NULL`, for a row with no payload row or a `NULL` `request_body`;
  - `stored`, with `request_body_full` equal to the stored `request_body` byte for byte, for a row whose
    `request_parent_hash` is all-zero and whose `request_cache_control` is `NULL` — a body stored whole under either
    mode, a root without markers, a body that is not a chat request, a row ingested before delta storage;
  - `rebuilt`, with the rebuilt body, for a row with a parent whose chain reaches a root, a root carrying cache
    markers, or a copy whose carrier was found;
  - `resolvable`, only when `request_body_rebuild` is read without `request_body_full`, for a row that would otherwise
    be checked for `rebuilt`: its chain was found, but no body was read (see below);
  - `partial`, with `request_body_full` equal to the stored `request_body` byte for byte, for a rebuild that cannot
    be completed (see below).

A rebuild SHALL NOT turn a stored body into `NULL` and SHALL NOT fail the read: every failure below SHALL return the
stored body marked `partial`. `request_body` SHALL keep returning exactly what is stored, whatever either field
returns.

**The chain is read partition-locally, in two phases.** Every row a rebuild reads SHALL lie in the requested row's
own UTC-day partition, which is where its chain lives (see "A parent never crosses a UTC day or a user scope"); a
rebuild SHALL NOT read any other partition. The first phase SHALL resolve each requested row's chain from the
chain columns alone — no body — looking paths up in batches, seeded from the requested row's
`request_ancestor_hashes` and, where a found row's parent is still missing, from that missing path and the found
row's own `request_ancestor_hashes`, round after round until every chain reaches a root, a round finds nothing new,
or a bound below is reached. Any row carrying a path SHALL serve, since every such row yields the same history. The
second phase SHALL read the bodies of the rows the chains name, by their `event_id`, and only those. Both phases
SHALL read deduplicated — the latest version per `event_id`, deduplicated inside each partition. The walk SHALL use
the parent link of the very row whose body it reads; when a row read in the second phase no longer carries the path
or parent the first phase found (it was re-ingested between the two reads), its chain SHALL be resolved again within
the remaining rounds or returned `partial`.

A rebuild SHALL return `partial` when: a path on the chain is carried by no row of the partition (the parent was
never stored, was dropped early by part-granular expiry, or was orphaned by a re-ingest after a canonical-form, element-hashing or
inline-file setting change); a bound below is reached before the chain is complete; a rebuild statement fails or the
read's budget runs out while the rebuild is in progress; or a body on the chain does not have the shape the rebuild
expects (for example, a stored root that is not a chat request, unless it is the carrier of a copy; or a row with
an empty stored body whose parent is not such a carrier).

**Bounds.** Two settings SHALL bound the work one read spends on rebuilds:
`query.request-body-rebuild.max-rounds` (`QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS`, default `8`), the number of
first-phase lookup rounds, and `query.request-body-rebuild.max-ancestors`
(`QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS`, default `20000`), the number of distinct ancestor rows one read may
resolve. Each SHALL be at least `1`, or startup SHALL fail naming the setting. A row whose chain is incomplete when
either is reached SHALL be returned `partial`; rows whose chains completed SHALL be returned `rebuilt`. The rebuild
statements SHALL run under the same resource settings as the read that asked for them, sharing what is left of its
wall-clock ceiling rather than being granted a new one, and SHALL run after the read's own statements (its row query
and, when requested, its total count), so a rebuild never takes the time those need. A rebuild statement SHALL NOT be
issued once less than one second of that ceiling is left; the rows it would have served SHALL be returned `partial`
with reason `failed`. A failed or skipped statement SHALL make `partial` only the rows of the UTC day it served; the
rows of other days SHALL keep resolving.

**Access.** The fields SHALL be `sensitive` when `request_body` or `request_cache_control` is, since the rebuilt body
returns the values of both, and SHALL therefore be visible to a caller only when it may see every sensitive column
among the two. The ancestors a rebuild reads SHALL be read under the same governance as the requested row and
SHALL never be returned as rows of their own. Because a chain stays inside one project, user and UTC day and a
rebuilt body equals what the requested row's own request carried, a rebuild SHALL disclose nothing the same row
would not disclose had it been stored whole. Restricting the ancestors a rebuild reads by deployment or model
inside a project is not part of this requirement.

**Observability.** Every completed value SHALL be counted on `query.request.rebuild.rows`, tagged `outcome`
(`stored`, `rebuilt`, `resolvable`, `partial`) and `surface` (`query` for the query surfaces, `pipeline` for the pipeline data
plane), and every `partial` value additionally on `query.request.rebuild.partial`, tagged `reason`: `missing`,
`limit`, `failed` or `invalid`. A read that returns any `partial` value SHALL log one WARN line naming the counts per
reason and the number of rounds, and never a body or a hash. A read whose rows are all `stored` SHALL issue no
rebuild statement. A read projecting `request_body_rebuild` without `request_body_full` SHALL run the first phase
only and read no body, reporting `resolvable` for every chain that phase completes, a root carrying cache markers
included; projected together, the two fields SHALL always agree, and `resolvable` SHALL NOT be reported. `resolvable`
states that the chain was found, not that its bodies rebuild: a body or marker of the wrong shape (reason `invalid`)
is detected only by a read that also projects `request_body_full`, which may return `partial` for a row the
status-only read reported `resolvable`. `rebuilt` SHALL mean only that the body was rebuilt. Every rebuild statement SHALL carry a query id of its own, never the triggering statement's: on the
query surfaces `adas-query-rebuild-{uuid}`, on a pipeline read the id the enrichment-runtime capability prescribes, and
on an untagged pipeline read `adas-pipeline-rebuild-{uuid}`; each SHALL be logged on the statement logger like the
service's other statements, its bound paths and event ids counted rather than printed.

#### Scenario: A follow-up turn reads back whole

- **WHEN** turns A, B and C of one conversation were stored under `delta` and a query projects
  `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild` for C
- **THEN** the row carries C's body as it was sent — its whole message array, A's system prompt and A's `tools`
  included — and `request_body_rebuild` is `rebuilt`
- **AND** `dial_usage_log_payload.request_body` of the same row still holds only C's new messages

#### Scenario: A body stored whole is returned unchanged

- **WHEN** a row whose `request_parent_hash` is all-zero and whose `request_cache_control` is `NULL` is read with
  `request_body_full`
- **THEN** `request_body_full` equals the stored `request_body` byte for byte, `request_body_rebuild` is `stored`, and
  no rebuild statement is issued for it

#### Scenario: A root with cache markers gets its markers back

- **WHEN** a chat request that is a root carried `cache_control` on its last message and is read with
  `request_body_full`
- **THEN** the rebuilt body carries that marker where it was sent, `request_body_rebuild` is `rebuilt`, and no
  ancestor is read

#### Scenario: A missing ancestor returns the stored delta marked partial

- **WHEN** a row's chain names a path that no row of its partition carries
- **THEN** `request_body_full` equals the stored `request_body`, `request_body_rebuild` is `partial`,
  `query.request.rebuild.partial{reason=missing}` is incremented, and the read succeeds

#### Scenario: A chain deeper than the ancestor hint is followed

- **WHEN** a row's chain has 300 ancestors, so its `request_ancestor_hashes` holds only the nearest 128
- **THEN** later rounds resolve the rest from the found rows' own hints, and the row is `rebuilt`

#### Scenario: A bound makes the row partial, not the read fail

- **WHEN** `QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS=1` and a row's chain is longer than its hint covers
- **THEN** that row is returned `partial` with its stored body, `query.request.rebuild.partial{reason=limit}` is
  incremented, and the other rows of the read are unaffected

#### Scenario: A failing rebuild statement degrades to partial

- **WHEN** a rebuild statement fails after the read's own statement succeeded
- **THEN** every row whose rebuild needed it is returned `partial` with its stored body, the reason is `failed`, and
  the response is still `200`

#### Scenario: A spent budget stops the rebuild, not the read

- **WHEN** a read's own statements leave less than one second of its wall-clock ceiling and its rows need a rebuild
- **THEN** no rebuild statement is issued, those rows are returned `partial` with their stored bodies and reason
  `failed`, and the read, its total count included, succeeds

#### Scenario: A parent re-ingested between the two phases is not mixed

- **WHEN** an ancestor's event is re-ingested between the two phases and its surviving row links to a different
  parent
- **THEN** the rebuild either follows the surviving row's link and returns `rebuilt` with the requested row's exact
  body, or returns `partial` with the stored body — never a body assembled from two versions of one event

#### Scenario: The rebuild never leaves the row's day

- **WHEN** a page of rows from 2026-03-02 and 2026-03-03 is read with `request_body_full`
- **THEN** every rebuild statement is restricted to those two partitions, and each row's ancestors are looked up in
  its own day only

#### Scenario: An invalid bound fails startup

- **WHEN** the service starts with `QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS=0`
- **THEN** startup fails, naming the setting

#### Scenario: A row without a payload row reads null

- **WHEN** a `dial_usage_log` row whose payload row has expired is read with both fields
- **THEN** both fields are `NULL`

### Requirement: Pipelines binding the stored body are re-pointed before delta storage is enabled

`request_body` SHALL keep its meaning on every surface, so enabling `SYNC_REQUEST_BODY_MODE=delta` changes what
every existing reader of `dial_usage_log_payload.request_body` receives for a later chat turn, and for a copy (see
"A large request body equal to an earlier one is stored once"), whose `request_body` is the empty string. The
upgrade notes of the release that introduces copies SHALL say so, for deployments already running `delta`, and SHALL
say that an instance of an earlier release re-ingesting a carrier removes its hash, so a whole UTC day is
re-ingested on one release, and that an earlier release reads every copy as `partial` with an empty body, so a
deployment holding copies rolls back to `whole-with-path` on the release that introduces them, not to an earlier
release. The release notes for
the request-delta storage SHALL tell operators to re-point every pipeline whose `transform.inputs` binds
`dial_usage_log_payload.request_body`, and every saved query or other consumer that needs a turn's whole request, to
`dial_usage_log_payload.request_body_full` before enabling `delta`. ADAS SHALL NOT rewrite a stored pipeline or
saved query to do so.

#### Scenario: The upgrade notes name the replacement field

- **WHEN** an operator reads the release notes' row for request-delta storage
- **THEN** it names `dial_usage_log_payload.request_body_full` as the field to bind before enabling `delta`

#### Scenario: The upgrade notes name the empty body of a copy

- **WHEN** an operator running `delta` reads the upgrade notes of the release that introduces copies
- **THEN** they say that a repeated large non-JSON request body is stored with an empty `request_body`, name
  `dial_usage_log_payload.request_body_full` as the field that returns it, say that a UTC day holding copies
  is re-ingested whole, on one release, and name `whole-with-path` on that release as the rollback

### Requirement: A parent never crosses a UTC day or a user scope

A parent SHALL be in the same daily partition (`toYYYYMMDD(request_time)`, UTC) as its child and in the same
`(project_id, user_hash)` scope. Because the scope and the day are part of `p0`, a request in
another scope or on another day can never match. The first request of a conversation on a new UTC day SHALL be a
root, so a child never outlives a parent under the daily partition TTL, and rebuilding a request never
reads another user's rows. Deployment is not part of the scope, so a user who switches deployment mid-conversation
keeps one chain; the history hash alone decides what links. Nor is the client session: it is a client-supplied
value that can be absent, change or vanish mid-conversation, and in the scope it would only cut links. Two
conversations of one user with an identical history MAY share rows, which rebuilds correctly.

A row whose `project_id` and `user_hash` are both empty has no scope: it SHALL be stored whole with no path, as a
body that is not a chat request is (see "A request body that is not a chat request is stored whole"), so such rows
of different tenants never share a chain. One of the two is enough for a scope; API-key traffic, which carries a
project and no user, chains within its project.

A rebuild reads only rows whose path equals a prefix of the rebuilt row's own history, and takes from them only
their history elements, system keys and `tools`, which the prefix hashes prove equal to the rebuilt request's own. A
rebuilt body therefore never carries another request's content, though the rows read to assemble it hold their own
top-level members (`model`, sampling parameters) and cache markers, which a rebuilding surface SHALL NOT return. A
rebuild does need every row of the chain, and a chain MAY span rows that differ in anything outside the scope and the
history, deployment included. A surface that rebuilds bodies SHALL read the chain under the reader's own access, and
SHALL report a row whose chain it cannot read in full as incomplete rather than fail the read or widen the reader's
access. A copy (see "A large request body equal to an earlier one is stored once") is read differently and to the
same effect: its rebuild reads one row carrying its body hash and returns that row's `request_body` whole, which
the hash proves equal to the copy's own body, so it too never carries another request's content.

Parent resolution SHALL consider, in order: earlier chat requests of the same flush (ordered by
`request_time`), paths this service instance has already written successfully on the current or the previous UTC
day (older paths can never be the parent of live traffic, so a backfill of past days does not displace the live
ones), then the payload table — asked once per flush, about the requests not yet resolved, and about a bounded
number of their prefixes in all, each query bounded in execution time; a request beyond that bound is a root. A path SHALL become a parent candidate only
once its row is written or is in the same insert, so a row never names a parent that was never stored.

A missing parent SHALL NOT be an error. A request whose parent was not found — it arrived before its
parent, another instance is ingesting the parent at the same time, the parent is in an unflushed batch, or
the payload-table lookup failed — SHALL be stored as a root. A failed lookup SHALL be logged and counted and
SHALL NOT fail the flush. This costs storage, never correctness.

#### Scenario: A failed lookup stores roots and keeps ingesting

- **WHEN** the payload-table lookup fails or times out during a flush
- **THEN** the requests it would have resolved are stored as roots, the batch is written, and the failure is
  counted

#### Scenario: A backfill does not displace live paths

- **WHEN** a backfill ingests chat requests of a day older than the previous UTC day
- **THEN** their paths are not remembered by the instance, and their follow-ups still link through the payload
  table

#### Scenario: A request with neither project nor user is stored whole

- **WHEN** two consecutive turns of a conversation arrive with an empty `project_id` and an empty `user_hash`
- **THEN** both rows carry their bodies as received and an all-zero `request_hash`, and neither is a parent

A re-ingested event MAY resolve a different parent than its earlier copy did (for example, its parent has
been stored in the meantime). Both copies carry the same path and rebuild to the same history, so the
`ReplacingMergeTree` collapse keeping either one is correct — for the event itself and for every row that
names its path as parent (see "Rebuild equivalence").

#### Scenario: A conversation crossing midnight starts a new chain

- **WHEN** a user's requests at 23:58 and 00:03 UTC on consecutive days are ingested, and the second
  extends the first's history
- **THEN** the second request is a root, stored complete in `request_body`

#### Scenario: A deployment switch keeps the chain

- **WHEN** a user's follow-up request goes to a different deployment than the turn before it, with the same history
  format
- **THEN** the follow-up's parent is the earlier turn

#### Scenario: Another project's identical history is not a parent

- **WHEN** two requests with identical histories and the same user hash belong to different projects
- **THEN** neither is the other's parent

#### Scenario: A changed session id keeps the chain

- **WHEN** a user's follow-up request carries a different `session.id` than the turn before it, or none
- **THEN** the follow-up's parent is still the earlier turn, since the session is not part of the scope

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
  `request_parent_hash` from it yields its history

### Requirement: Cache markers are kept per event

The service SHALL record, under `sync.request-body-mode=delta`, every `cache_control` member removed from a
chat request's history in that row's `request_cache_control`, as a JSON array of entries, each giving the index
of the history element, the location of the member inside that element, and its value verbatim.
A chat request's row with a parent SHALL carry one further entry, `{"text_blocks":[…]}`, listing the indices of the message
elements it inherits that it sent as a bare text block, empty when it sent none: the row storing such a message
MAY have sent it as the plain string, and the entry is what lets a rebuild return the shape this request sent.
`request_cache_control` SHALL be `NULL` for a request with no parent that carried no marker, and for a copy (see
"A large request body equal to an earlier one is stored once"), whose body is not a chat request. Markers SHALL belong only to
the event that carried them and SHALL NOT be inherited along a chain: a rebuild restores the row's own
markers into the assembled history and no others. `cache_control` members outside the history (for
example on `tools`) SHALL stay where they are, in the stored body or in the stored `tools` a request inherits.

Removing the markers from the hash is what lets consecutive turns link: clients move the marker to the
newest message on each turn, so the same earlier message would otherwise hash differently on every turn.

#### Scenario: A moved marker does not break the chain

- **WHEN** turn 1 carries `cache_control` on its last user message, and turn 2 repeats that message
  without it and adds two messages carrying `cache_control` on the newest
- **THEN** turn 2's parent is turn 1, its `request_body` holds only the two added messages without markers, and
  its `request_cache_control` records the one marker on its newest message, beside its text-block entry

#### Scenario: Markers are not inherited

- **WHEN** turn 2's markers are restored during a rebuild
- **THEN** the assembled request carries turn 2's own markers only, not turn 1's

#### Scenario: A root without markers stores null

- **WHEN** a chat request carrying no `cache_control` member is stored in delta form with no parent
- **THEN** its `request_cache_control` is `NULL`

#### Scenario: A row with a parent records the text blocks it inherits

- **WHEN** a chat request carrying no `cache_control` member is stored in delta form with a parent, and the
  second of its three inherited history elements is a message it sent as a bare text block
- **THEN** its `request_cache_control` is `[{"text_blocks":[1]}]`

#### Scenario: A row stored before shapes were recorded inherits its messages as stored

- **WHEN** a row with a parent carries no `text_blocks` entry and its root stored a message as a bare text block
- **THEN** its rebuild returns that message as the stored block

## ADDED Requirements

### Requirement: A large request body equal to an earlier one is stored once

A request body SHALL be **eligible for a body hash** when it is not JSON (a body truncated at 4 MiB and a
multipart upload are such bodies), holds at least 65,536 UTF-16 code units as ingest is about to store it, that
is, after the inline-file rewrite, its row has a project or a user, and its `request_time` is a date and time
written without a UTC offset or with `Z`; a time written with a numeric offset, `+00:00` included, is not eligible. A body is JSON when it is one syntactically valid JSON value with nothing
but whitespace around it, however
deeply nested, however long its numbers and strings, and whether or not an object names a member twice. A body
nested too deeply for ingest to read to its end SHALL be treated as JSON. A client whose conversation has outgrown the 4 MiB body
limit sends the same cut text on every later turn, so such bodies repeat exactly. A body that is JSON SHALL NOT be
eligible, whatever its size and shape, a JSON object naming one member twice included: its top-level members stay
readable from `request_body` on every row. Its **body hash** SHALL be a 128-bit hash of the project, the user, the UTC day of
`request_time` and the body text exactly as ingest is about to store it, that is, after the inline-file rewrite.
The body hash SHALL be computed under a seed of its own, apart from the chat prefix hashes. A chat request's parent
is always a prefix hash of its own history, which no body hash equals, so a chat request never takes a carrier as
its parent.

Under `whole-with-path`, an eligible row SHALL carry its body hash in `request_hash` and its body whole.

Under `delta`, an eligible row SHALL be stored as one of:

- a **carrier**: `request_body` whole, `request_hash` = its body hash, every other request-delta column except
  the identity columns at its default;
- a **copy**: `request_body` the empty string, `request_parent_hash` = its body hash, `request_ancestor_hashes`
  holding that hash alone, an all-zero `request_hash` and a `NULL` `request_cache_control`.

A row SHALL be a copy only when a carrier of its body hash with a strictly earlier `request_time`, compared as
instants at millisecond precision, is in the same insert or stored in the payload table; otherwise it SHALL be a
carrier. Only a row holding the body therefore carries the hash, and the earliest request with a given body is a
carrier however often its event is re-ingested, so a re-ingested carrier never becomes a copy of itself. Two
requests with equal bodies and equal `request_time` SHALL both be carriers. A stored carrier SHALL count only when
the current version of its row carries the hash: a row version that a re-ingest has since replaced SHALL NOT make
a copy, and neither SHALL the stored row of an event that is in the same insert, which that insert replaces. A
row with no carrier in its insert SHALL be a carrier whenever the lookup returns no stored one, for example
because only another instance's concurrent insert holds it, the lookup's bound on hashes per flush is reached, or
the lookup fails, or is not issued because the flush's lookup for chat parents failed. A failed lookup SHALL be logged and counted and SHALL NOT fail the insert. Each of these costs storage only. When a
batch is inserted in parts, a copy whose carrier lies in another part SHALL be stored as a carrier instead, so no
stored copy names a carrier that a refused part kept out of the table.

`dial_usage_log_payload.request_body_full` SHALL return, for a copy, the `request_body` of a row carrying its
parent hash byte for byte, with `request_body_rebuild` = `rebuilt` (`resolvable` when read without the body),
read through the same two phases, bounds and partition as any other rebuild. When no row of the partition carries
the hash, it SHALL return the stored empty body marked `partial` with reason `missing`. `request_body` SHALL keep
returning the stored empty string.

A copy holds none of its body, so it depends on its carrier more than a chat child does on its parent. What
"Rebuild equivalence" promises of a parent's path, that a row carrying it exists while the child does, holds for a
body hash only outside the cases below, which are an operator's to avoid. Re-ingesting a carrier gives its row another hash after a change
of `sync.inline-files-drop-truncated` or `sync.inline-files-max-size`, and no hash at all under `whole`, with
`sync.sensitive-columns-enabled=false`, or on a release that predates body hashes or that changes which bodies
are eligible or how the hash is computed; a copy not re-ingested with it then names a hash no row carries. A carrier whose hash is removed between the lookup that found it and the insert
of its copy, and a carrier seen on a replica that has not yet received its replacement, orphan a copy the same
way. Re-ingesting whole UTC days under one configuration and release restores every copy of those days. As for a chat parent,
part-granular expiry MAY drop a carrier before its copy in the last day of a day's retention.

#### Scenario: A repeated truncated body is stored once

- **WHEN** a body of 70,000 characters that is not valid JSON is ingested under `delta` at `10:00:00.000`, and the
  same body arrives from the same project and user at `10:00:05.000` on the same UTC day
- **THEN** the first row carries the body and its body hash in `request_hash`, and the second carries an empty
  `request_body`, that hash in `request_parent_hash` and as the only element of `request_ancestor_hashes`, and an
  all-zero `request_hash`

#### Scenario: Equal bodies in one insert are stored once

- **WHEN** two requests of one project and user with equal eligible bodies, `request_time` `10:00:00.000` and
  `10:00:05.000`, are ingested in one batch that is inserted whole
- **THEN** the earlier row is a carrier and the later one a copy

#### Scenario: A body exactly at the size bound is hashed

- **WHEN** a body of exactly 65,536 UTF-16 code units that is not JSON is ingested under `delta`, and a body one
  unit shorter that is not JSON is ingested too
- **THEN** the first row carries its body hash in `request_hash`, and the second an all-zero `request_hash`

#### Scenario: The size is measured on the body as stored

- **WHEN** a truncated body holds 70,000 characters as received and 40,000 once the inline-file rewrite has
  removed a file from it
- **THEN** its row carries the rewritten body and an all-zero `request_hash`

#### Scenario: A body with neither project nor user is not hashed

- **WHEN** two equal eligible-sized bodies that are not JSON arrive with an empty `project_id` and an empty
  `user_hash`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: A request time with a UTC offset is not hashed

- **WHEN** an eligible-sized body that is not JSON arrives with a `request_time` carrying the offset `+02:00`
- **THEN** its row carries the body and an all-zero `request_hash`

#### Scenario: A carrier replaced by the same insert does not make a copy

- **WHEN** a carrier is stored, an inline-file setting changes, and one batch then carries that carrier's event,
  whose body now differs, together with a new request whose body equals the carrier's stored one
- **THEN** the new request's row holds its body and is a carrier

#### Scenario: A copy reads back whole

- **WHEN** a query projects `dial_usage_log_payload.request_body_full` and `request_body_rebuild` for a copy whose
  carrier is stored
- **THEN** the row carries the carrier's body byte for byte and `rebuilt`

#### Scenario: A copy whose carrier is gone is partial

- **WHEN** a copy is read and no row of its partition carries its parent hash
- **THEN** `request_body_full` is the empty string, `request_body_rebuild` is `partial`, and the value is counted
  with reason `missing`

#### Scenario: A copy read without its body is resolvable

- **WHEN** a query projects `request_body_rebuild` without `request_body_full` for a copy whose carrier is stored
- **THEN** the row carries `resolvable`, and no body is read

#### Scenario: A failed lookup stores a carrier

- **WHEN** an eligible body is ingested, no row of its insert carries its hash, and the payload-table lookup fails
- **THEN** its row holds the body and its hash, the batch is written, and the failure is counted

#### Scenario: A replaced row version is not a carrier

- **WHEN** a carrier was stored, its event was re-ingested under `whole` so that its current row carries no hash,
  the replaced version is still on disk, and an equal body is then ingested under `delta`
- **THEN** the new row holds the body and is a carrier

#### Scenario: Valid JSON beyond a parser's usual limits is not hashed

- **WHEN** two equal bodies of 70,000 characters, each one valid JSON array nested 2,000 levels deep, are
  ingested under `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: A body nested too deeply to read is treated as JSON

- **WHEN** two equal bodies, each 100,001 `[` characters, are ingested under `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: An earlier request arriving later becomes the carrier's peer

- **WHEN** a carrier with `request_time` `10:00:05.000` is stored, and a request with an equal body and
  `request_time` `10:00:00.000` is ingested afterwards
- **THEN** the new row is a carrier, and both rows hold the body

#### Scenario: A JSON object naming a member twice is not hashed

- **WHEN** two equal bodies of 70,000 characters, each a JSON object naming one member twice, are ingested under
  `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: A body below the size bound is not hashed

- **WHEN** two equal bodies of 1,000 characters that are not JSON are ingested under `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: A large JSON body is not hashed

- **WHEN** two equal MCP `tools/call` bodies of 70,000 characters, each valid JSON, are ingested under `delta`
- **THEN** both rows carry the body and an all-zero `request_hash`

#### Scenario: An equal body of another user, project or day is not a copy

- **WHEN** a body equal to a stored carrier's arrives from another user, another project, or on another UTC day
- **THEN** its row is a carrier with a different body hash

#### Scenario: A re-ingested carrier stays a carrier

- **WHEN** a carrier and its copy are stored and the carrier's event is ingested again
- **THEN** the surviving carrier row still holds the body and the hash, and the copy still reads back whole

#### Scenario: Equal bodies at the same instant are both carriers

- **WHEN** two requests with equal eligible bodies and equal `request_time` are ingested
- **THEN** both rows hold the body

#### Scenario: whole-with-path stamps an eligible body with its hash

- **WHEN** an eligible body is ingested under `whole-with-path`, and under `delta` the same body arrives later
- **THEN** the first row holds the body and its body hash, and the second is a copy of it

#### Scenario: A copy split from its carrier is stored whole

- **WHEN** a carrier and its copy are prepared in one batch that is then inserted in two parts, one row in each
- **THEN** both rows hold the body, each with the body hash in `request_hash` and an all-zero
  `request_parent_hash`

#### Scenario: A failed chat lookup stores the flush's bodies whole

- **WHEN** a flush carries a chat request and an eligible body, and the payload-table lookup for the chat request's
  parent fails
- **THEN** the eligible body's row holds the body and its hash, and the table is not asked about its hash

