## MODIFIED Requirements

### Requirement: A chat request body is stored as its new history and a parent link

A request body SHALL be a **chat request** when it parses as a JSON object carrying a **message array** — an
array whose elements are all JSON objects — under the first of `messages`, `input` or `contents` whose value is
an array. An `input` of strings or token arrays (an embeddings request) is therefore not a message array. Its
**history** SHALL be, in order: the value of each present **top-level system key** — `system`,
`instructions`, `system_instruction`, `systemInstruction`, in that order — then the elements of the message
array. No other key SHALL be treated as history. Each history element SHALL be normalized by removing every `cache_control` member at any depth
(see "Cache markers are kept per event") and canonicalized as JSON with object keys sorted and no
insignificant whitespace.

For a history `h1..hn`, ingest SHALL compute the **prefix hashes**
`p0 = H(project_id, user_hash, UTC day of request_time, tools)` and `pk = H(p(k-1), e(hk))`. `H` is a 128-bit hash;
`e(hk)` is the canonical JSON of `hk`, preceded for a system element by its key name (`"system":…`), so a system
element never matches a message element or the same value under another system key. The two scope values are those
the record's `dial_usage_log` row carries, and `tools` is the canonical JSON of the request's top-level `tools`,
hashed apart from every value when the request carries none, so `null`, `[]` and an absent `tools` never match. `pn`
is the request's **path**. Because each prefix hash folds in every element before it, the scope and the tools, two
requests SHALL have an equal `pk` only when their first `k` history elements are identical, system key names
included, and they share project, user, UTC day and `tools`. Equal message text under a different history or scope
SHALL never yield an equal hash.

The request's **parent** SHALL be a stored or same-flush chat request whose path equals the deepest `pk`
of this request with `max(1, s) ≤ k < n`, where `s` is the number of its top-level system keys, among the sources
resolution consults (see "A parent never crosses a UTC day
or a user scope"); a deeper match that only another instance's rows hold MAY be missed, which costs storage only. A request is therefore never its own parent: a parent's history is always
strictly shorter than its child's, and it always covers the child's whole system part, so a stored body either
carries all of its system keys or inherits all of them, and every request on a chain has the same system keys, names
and values. The stored row SHALL carry:

- `request_hash` = `pn`;
- `request_parent_hash` = the parent's path, or all-zero when no parent was found (the request is a **root**);
- `request_ancestor_hashes` = up to the 128 nearest paths of the requests on the chain when the row was written,
  root side first, ending with the parent's path; empty for a root. It is a read hint, not the chain itself
  (see rebuild below);
- `request_body` = the canonical JSON of the body with its `cache_control` markers removed from the history,
  its message array holding only the elements of `h(k+1)..hn` that are messages, and, when it has a parent, without
  its top-level system keys (their values are `h1..hs`, within the parent's history) and without
  `tools` (see "A request inherits its parent's tools"). A root's `request_body` is therefore the whole request.

**Rebuild equivalence.** A row's history SHALL be its parent path's history followed by the message array
elements of its own `request_body`, where a path's history is that of **any** stored row carrying that path, and
a row with an all-zero parent — a root, or a row stored whole — supplies its history from `request_body`,
normalized and canonicalized as above.
Rows carrying the same path MAY differ in parent and stored body (a re-ingested event can link differently, and an
event stored as a root can share its path with one stored as a child), yet every one of them yields the same
history, so following `request_parent_hash` through whichever row is found always terminates — each parent is
strictly shallower — and always yields the row's exact canonical history. While a row exists, a row carrying
its parent's path SHALL exist: a path depends only on the stored history and tools, so replacing a row under the
`ReplacingMergeTree` keeps it whichever mode wrote the copy, and parent and child share a daily partition, so
they expire together — up to the part granularity of the TTL: with `ttl_only_drop_parts`, a day still split
across parts can lose an earlier part before a later one, so in the last day of a day's retention a child MAY
name a parent already dropped, and its rebuild then fails as an expired row would. One more case breaks that, and it is an operator's to avoid: a path is computed from the
history as ingest stores it, so re-ingesting an event after changing `sync.inline-files-drop-truncated` or `sync.inline-files-max-size`, or after a
release that changes the canonical form, gives its new copy a different path. A child that is not re-ingested
with it then names a path no row carries any more. Re-ingesting whole UTC days after such a change avoids it,
since a parent and its children always share one.
`request_ancestor_hashes` lets a rebuild read the whole chain in one query in the common case, and read further
only where a row found links through a path that list lacks. Putting back into the row's `request_body` its full
message array (under the key the classification above chose: the first of `messages`, `input` or `contents` whose
value is an array), then, for a row with a parent, each system key that its chain's root carries, in the order listed
above, valued with its history element (`h1..hs`), and the root's `tools` member when the root has one, and finally
the row's own cache markers, SHALL yield a JSON document equal to the received body
(same members and values; key order and whitespace are not preserved). Ingest SHALL store everything a rebuild needs,
and the read surfaces SHALL expose the rebuilt body as `dial_usage_log_payload.request_body_full` (see "A rebuilt
request body is readable beside the stored one"); `request_body` itself SHALL keep returning the stored body.

#### Scenario: A follow-up turn stores only its new messages

- **WHEN** request A with history `[system s, user u1]` is stored, and later request B with history
  `[system s, user u1, assistant a1, user u2]` by the same user on the same UTC day is ingested
- **THEN** B's row carries `request_parent_hash` = A's path and a `request_body` whose message
  array holds only `[a1, u2]`

#### Scenario: Equal messages under a different history are not linked

- **WHEN** a stored request has history `[u0, r0, u1, r1]`, and a request in the same scope arrives with
  history `[u1, r1, u2]`
- **THEN** the new request is a root, and its `request_body` holds all three elements

#### Scenario: A first request is a root

- **WHEN** a chat request has no stored or same-flush request whose path matches any of its prefixes
- **THEN** its row carries an all-zero `request_parent_hash`, an empty `request_ancestor_hashes`, and the whole request
  in `request_body`

#### Scenario: An identical retry is linked like the original

- **WHEN** a request with the same history and scope as a stored request R is ingested as another event
- **THEN** its row carries R's path, and its parent is the deepest stored request R's history extends —
  never R itself

#### Scenario: Top-level fields stay readable

- **WHEN** a chat request carrying `model` and `temperature` is stored in delta form
- **THEN** `json_extract_string("dial_usage_log_payload.request_body", 'model')` returns its model, and
  `request_body` carries `temperature` unchanged

#### Scenario: An Anthropic system prompt is part of the history

- **WHEN** two consecutive Anthropic Messages requests of a conversation carry the same top-level `system`
  and the second extends the first's `messages`
- **THEN** the second's `request_body` holds only the messages it added and no `system` member, and its rebuild
  carries the first's `system`

#### Scenario: A system prompt under another key is another history

- **WHEN** a stored request carries `"system": "s"`, and a request in the same scope carries `"instructions": "s"`
  and extends the stored request's messages
- **THEN** the second request is a root, and its `request_body` holds `instructions` and all its messages

#### Scenario: A Gemini-style body is a chat request

- **WHEN** a request body carries `system_instruction` and a `contents` array
- **THEN** it is stored in delta form, with `system_instruction` as the first history element and the
  `contents` elements after it

#### Scenario: The history rebuilds exactly

- **WHEN** a chain root A and its descendants B and C are stored, and C is rebuilt by following
  `request_parent_hash` from C through stored rows to the root and concatenating their history elements root first
- **THEN** the result equals C's normalized, canonical history element for element

#### Scenario: A rebuild survives a parent re-linked by re-ingest

- **WHEN** B was stored as a root, C was stored as B's child, and B is then re-ingested after A was stored,
  so the surviving B row is A's child with a shorter stored body
- **THEN** rebuilding C by following `request_parent_hash` through the surviving rows still yields C's exact
  history

## ADDED Requirements

### Requirement: A rebuilt request body is readable beside the stored one

The payload enrichment SHALL expose two read-only fields computed on read, beside `request_body`, under the qualified
names `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild`. They SHALL NOT be
stored: no column, ingest stage or migration is added for them, and ingest SHALL be unchanged by them.

- **`request_body_full`** (`string`, nullable) SHALL hold the row's request body as the request carried it, rebuilt
  by the rebuild equivalence of "A chat request body is stored as its new history and a parent link": the history its
  `request_parent_hash` chain supplies followed by its own message array elements, put back under the key the
  classification chose; for a row with a parent, each system key and the `tools` member of the root the chain ends
  at; and the row's own cache markers. A rebuilt body SHALL be the same JSON document the request carried — same
  members and values; key order and whitespace are not preserved — written as compact JSON.
- **`request_body_rebuild`** (`enum` of `stored`, `rebuilt`, `partial`; nullable) SHALL say what `request_body_full`
  holds:
  - `NULL`, with `request_body_full` `NULL`, for a row with no payload row or a `NULL` `request_body`;
  - `stored`, with `request_body_full` equal to the stored `request_body` byte for byte, for a row whose
    `request_parent_hash` is all-zero and whose `request_cache_control` is `NULL` — a body stored whole under either
    mode, a root without markers, a body that is not a chat request, a row ingested before delta storage;
  - `rebuilt`, with the rebuilt body, for a row with a parent whose chain reaches a root, or a root carrying cache
    markers;
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
never stored, was dropped early by part-granular expiry, or was orphaned by a re-ingest after a canonical-form or
inline-file setting change); a bound below is reached before the chain is complete; a rebuild statement fails or the
read's budget runs out while the rebuild is in progress; or a body on the chain does not have the shape the rebuild
expects (for example, a stored root that is not a chat request).

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
(`stored`, `rebuilt`, `partial`) and `surface` (`query` for the query surfaces, `pipeline` for the pipeline data
plane), and every `partial` value additionally on `query.request.rebuild.partial`, tagged `reason`: `missing`,
`limit`, `failed` or `invalid`. A read that returns any `partial` value SHALL log one WARN line naming the counts per
reason and the number of rounds, and never a body or a hash. A read whose rows are all `stored` SHALL issue no
rebuild statement. A read projecting `request_body_rebuild` without `request_body_full` SHALL run the first phase
only and read no body, reporting `rebuilt` for every chain that phase completes; projected together, the two fields
SHALL always agree.

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
every existing reader of `dial_usage_log_payload.request_body` receives for a later chat turn. The release notes for
the request-delta storage SHALL tell operators to re-point every pipeline whose `transform.inputs` binds
`dial_usage_log_payload.request_body`, and every saved query or other consumer that needs a turn's whole request, to
`dial_usage_log_payload.request_body_full` before enabling `delta`. ADAS SHALL NOT rewrite a stored pipeline or
saved query to do so.

#### Scenario: The upgrade notes name the replacement field

- **WHEN** an operator reads the release notes' row for request-delta storage
- **THEN** it names `dial_usage_log_payload.request_body_full` as the field to bind before enabling `delta`
