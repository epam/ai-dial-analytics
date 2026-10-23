## MODIFIED Requirements

### Requirement: The merged assembled response is persisted

The service SHALL persist the producer's merged streaming response, `assembled_response`, to a column
of `dial_usage_log_payload` when the configured mapping declares one, storing the producer's value
verbatim with no reassembly, parsing, or normalization of its own. There is one exception: when
`sync.inline-files-max-size` is set, the payloads of inline files longer than it are removed from the value,
and when `sync.inline-files-drop-truncated` is on, a value DIAL Core cut at 4 MiB inside a file is cut back to
before that file (see "Inline files are removed from payload bodies in drop mode"). That parse locates files only and never validates,
rejects or repairs the value. A value that does not parse is handled by that requirement's "Body formats" and
"Truncated bodies" rules: removals found before the parse error are kept, and a truncated body is cut back to
before the file the cut landed in; nothing else in such a value is changed. The column SHALL be nullable, so a
record that carries no `assembled_response` stores `NULL` rather than an empty string and "the producer
sent nothing" stays distinguishable from "the producer sent an empty response".

The expected value is a **merged JSON response message**, for a streamed and a non-streamed call
alike — that is what makes the column readable as an answer where `response.body` on a streamed call is
a raw chunk transcript. The service SHALL nonetheless store the value as reported **whatever its
shape**, and SHALL NOT validate, parse, or reject it on shape: deciding what arrived would mean parsing
a payload the service otherwise never reads, and a row rejected or emptied on that basis would report
"no response" for a request that had one.

A stored value that does not parse as JSON therefore SHALL NOT be treated as an ingest failure, and a
consumer that needs a message SHALL parse the column defensively. A minority of records — including
current ones, not only historical ones — carry a value that does not parse, some of them an SSE
transcript in place of the merged message. The persisted shape is the producer's contract, not this
service's: ADAS neither guarantees JSON nor repairs a payload that is not, so a consumer that assumes
JSON will fail on that minority rather than on nothing.

Persisting the merged response SHALL NOT change how it feeds derived columns: it remains available as
a deriver source for the `dial_usage_log` mapping, and a column derived from it is unaffected by
whether it is also stored, or by which table stores it. `response_id` and `reasoning_tokens` therefore
remain columns of `dial_usage_log` while their source is stored on `dial_usage_log_payload`, because a
deriver reads the parsed record rather than a stored column.

#### Scenario: The assembled response is stored when the mapping declares it

- **WHEN** a record carries `assembled_response`, the payload mapping declares the corresponding column,
  `sync.inline-files-drop-truncated` is off and `sync.inline-files-max-size` is unset
- **THEN** the inserted `dial_usage_log_payload` row stores the producer's value verbatim

#### Scenario: Drop mode removes files from the assembled response

- **WHEN** `sync.inline-files-max-size` is `0` and a chat record's `assembled_response` carries
  `choices[0].message.audio.data`
- **THEN** the stored `assembled_response` carries that value as `""` and is otherwise the producer's
  value verbatim

#### Scenario: A record without an assembled response stores null

- **WHEN** a record carries no `assembled_response` and the mapping declares the column as nullable
- **THEN** the inserted row stores `NULL` for it, and the record is ingested normally

#### Scenario: A value that is not JSON is stored unchanged

- **WHEN** a record's `assembled_response` is not a JSON document (for example an SSE transcript), and
  neither inline-files setting is in effect, or DIAL Core did not cut the value and no size limit is set,
  or the value holds no inline file longer than the limit at a location of the record's API
- **THEN** the inserted row stores that value verbatim, and ingest neither fails, skips, nor empties the
  column (otherwise an SSE transcript is scanned event by event, as the inline-files requirement says)

#### Scenario: Deriving from the assembled response is unchanged

- **WHEN** a `dial_usage_log` column derives its value from `assembled_response` and the payload mapping
  also declares `assembled_response` as a stored column
- **THEN** the derived column's value is exactly what it would be were the column not stored, and it is
  unaffected by the payload column living in a different table

### Requirement: Persisted columns are governed by the mapping (allowlist)

The service SHALL persist only the columns declared in the configured mapping (allowlist semantics):
a field that is not declared MUST NOT be written, regardless of its presence in the source record.
The shipped **payload** mapping declares the heavy body columns `request_body`, `response_body`, and
`assembled_response`, so raw prompt content, raw response content, and the producer's merged response
all ARE persisted to ClickHouse. Whether heavy content is captured is therefore controlled entirely by
that mapping (removing those columns from it, or removing the payload target itself, stops capturing
them). When captured, access to those columns and their retention MUST be governed
operationally — column-level access controls plus a retention/TTL policy — because the service applies
no masking or redaction of its own. Removing inline files under `sync.inline-files-max-size` or
`sync.inline-files-drop-truncated` (see "Inline files are removed from payload bodies in drop mode") is not redaction:
it removes file payloads at fixed locations only, and every text value is still stored as captured.
Personal data in prompt text or file names, and in any file kept because it is within the limit or no limit
is set, remains covered only by those operational controls.

`assembled_response` is captured **in addition to** `response_body` rather than instead of it, and the
two overlap: for a streamed call the raw body is a chunk transcript while the merged response is the
same content assembled. Capturing both is a deliberate cost — one more unbounded-size column of
verbatim user-visible content per row — accepted because the merged response is the only form in which
a consumer can read an answer without reassembling a stream, and because dropping the raw body would
lose the transport-level detail an operator needs to diagnose a malformed stream. An installation that
does not want the merged response captured removes its column from the payload mapping, exactly as for
the other two.

#### Scenario: Declared heavy payload columns are captured

- **WHEN** a record contains `request.body`, `response.body`, and/or `assembled_response` and the
  payload mapping declares the corresponding columns
- **THEN** the inserted `dial_usage_log_payload` row contains those fields' values, less the payloads of
  inline files longer than `sync.inline-files-max-size` when it is set, and cut back before the file the
  cut landed in for a body DIAL Core cut at 4 MiB when `sync.inline-files-drop-truncated` is on

#### Scenario: An undeclared payload column is not captured

- **WHEN** a record contains `assembled_response` and no mapping declares a column for it
- **THEN** no inserted row contains an `assembled_response` column, though its value MAY still feed
  derived columns such as `response_id`

#### Scenario: Removing the payload target stops capturing content

- **WHEN** the payload target is removed from the configured target list
- **THEN** ingestion continues to write `dial_usage_log` rows and writes no verbatim body content
  anywhere, and the derived columns computed from the record's bodies are unaffected

#### Scenario: Only declared columns are written

- **WHEN** a record contains fields that are not declared as columns in the mapping
- **THEN** the inserted row contains only the declared columns and none of the undeclared fields

### Requirement: Ingest observability

The service SHALL expose ingest observability through logs and Micrometer metrics, including counts
of files processed, rows inserted, rows skipped (unparseable or oversized lines), rows dropped (records lacking the
deduplication key), run outcome, run duration, and the timestamp of the last successful run. The
service SHALL additionally expose poller metrics: poll cycles executed, blobs discovered and ingested
per cycle, poll failures, and the current watermark lag (how far the watermark trails the current
time). It SHALL expose, for each ingest path (reconciliation run, poller and backfill), the number of blobs
skipped because the ingest ledger records them. The files-processed count and the poller's blobs-discovered
count SHALL count only blobs selected for ingestion, never blobs skipped by the ledger. No HTTP status
endpoint is provided in this phase.

The ingest write path SHALL additionally expose, per target table, the number of batch inserts it
issued and the number that failed, the latter tagged by reason so that a write refused for lack of disk
space is countable separately from other insert failures. One batch insert creates one part per
partition it touches, so the insert count is the service's own measure of the part pressure it imposes
on the store — a number it cannot obtain by querying, because the query surface reaches only
catalog-registered tables and ClickHouse's parts metadata is not among them. These two counts are
attributed to the target table rather than to the ingest path that produced them, unlike the per-path
row counts above: the store's part pressure is a property of the table, and every ingest path writes
through the same write path. The ledger's own inserts SHALL be counted the same way, under the ledger
table's name, so the parts the ledger adds are observable beside those of the target tables.

When `sync.inline-files-drop-truncated` is on or `sync.inline-files-max-size` is set, the service SHALL
additionally expose four counters, each tagged by target table and column:
- the inline files it removed;
- the bytes of file payload it removed;
- the truncated bodies it shortened at the cut, by cutting back before a file or by removing the payload
  of the half file the cut left;
- the bodies, or SSE events, it could not scan because they do not parse.

A truncated body that fails to parse at its cut counts as a scanned body, not as an unscannable one.
These counters are attributed to the table and column rather than to the ingest path, like the
batch-insert counts, because every ingest path writes through the same mapping. With the truncated repair
off and no size limit they SHALL NOT be incremented. The removed-files count covers complete files only, so
with no size limit it SHALL stay at zero; the removed bytes include those of the half files at the cut.

#### Scenario: Removed inline files are counted

- **WHEN** a run with `sync.inline-files-max-size=0` ingests a record whose `request_body` holds two inline
  images totalling 1 MB of payload
- **THEN** the removed-files count for `dial_usage_log_payload` / `request_body` is incremented by 2, and
  its removed-bytes count by the size of the two removed payloads

#### Scenario: An unscannable body is counted and stored unchanged

- **WHEN** a run with `sync.inline-files-max-size=0` ingests a record whose `response_body` is not JSON and
  has no `data:` events
- **THEN** the unscannable-bodies count for that table and column is incremented by 1, and the body is
  stored unchanged

#### Scenario: Metrics reflect a completed run

- **WHEN** a sync run or poll cycle completes
- **THEN** the corresponding metrics (files processed, rows inserted/skipped/dropped, run/poll
  outcome, duration, last-success timestamp, and poller discovery/lag counters) are updated to
  reflect that run

#### Scenario: Ledger skips are counted per path

- **WHEN** a reconciliation run, poll cycle or backfill skips blobs because the ledger records them
- **THEN** that path's skipped-by-ledger count is incremented by the number of such blobs, and neither its
  files-processed nor its blobs-discovered count is

#### Scenario: Ledger inserts are counted under the ledger table

- **WHEN** a path records blobs in the ledger
- **THEN** the per-table batch-insert count for the ledger table is incremented, separately from the target
  tables' counts

#### Scenario: Dropped records are counted

- **WHEN** a run encounters records lacking a deduplication key
- **THEN** the dropped-rows metric is incremented by the number of such records

#### Scenario: Batch inserts are counted per table

- **WHEN** an ingestion run flushes batches to more than one target table
- **THEN** each table's insert count reflects the batch inserts issued for that table, so the
  part-creation rate per table is observable independently of the row counts

#### Scenario: A refused insert is counted by reason

- **WHEN** a batch insert fails because ClickHouse refused it for lack of disk space
- **THEN** the insert-failure metric for that table is incremented with a reason distinguishing disk
  exhaustion from other insert failures

### Requirement: Configurable column mapping

The service SHALL load each ingest target's column structure from an external mapping file whose path
is configured per target (via `sync.targets[*].mapping-file`; the first target's location is
overridable through the `SYNC_MAPPING_FILE` environment-driven property). The mapping SHALL declare an
ordered list of columns. A **mapped** column entry SHALL specify a ClickHouse column name, a `source`
(a dotted JSON path or the `$sourceFile` synthetic token), and an extraction kind, and MAY specify a
named transform and a default value. A **derived** column entry SHALL instead specify a `derive` name
and an ordered non-empty `sources` list (and MAY specify an `args` map and a default), and MUST NOT
specify a `source`. The service MUST NOT hard-code column names, source paths, or types in code —
derived columns keep their column name and source paths in the mapping, with only the reusable
derivation logic in code (see "Closed named deriver registry"). The `$sourceFile` token SHALL resolve
to the originating blob name; any other `$`-prefixed source token MUST be rejected as a configuration
error at load time. The shipped default mapping SHALL produce the `events` column set.

A mapped column of kind `string` MAY declare an `inline-files` attribute, which marks the column as a
body whose inline files `sync.inline-files-drop-truncated` and `sync.inline-files-max-size` govern (see
"Inline files are removed from payload bodies in drop mode"). The attribute SHALL carry:
- `body`, either `request` or `response`, which selects the location set the column is scanned against;
- `uri`, the dotted source path of the record's request URI, from which the API is inferred.

The service SHALL reject a mapping at load time when any of these holds:
- the attribute sits on a derived column, a `$sourceFile` column or a non-`string` column;
- the attribute sits on a column that also declares a `transform`;
- `body` is missing or is not `request` or `response`;
- `uri` is missing, blank or a `$`-prefixed token.

The attribute changes nothing about how the column's value is resolved, coerced or defaulted: the removal
applies to the resolved value, and the absent/blank fallback applies as for any column. The shipped payload
mapping SHALL declare `inline-files` on `request_body` (`body: request`) and on `response_body` and
`assembled_response` (`body: response`), each with `uri: request.uri`.

#### Scenario: Columns come from the mapping file

- **WHEN** the service starts with a mapping file declaring a set of columns
- **THEN** the inserted rows contain exactly those columns, sourced (or derived) and typed as the
  mapping declares

#### Scenario: Derived column entry is loaded

- **WHEN** a column entry declares a `derive` name with a `sources` list
- **THEN** the service loads it as a derived column populated at ingest by the named deriver

#### Scenario: Source file token is injected

- **WHEN** a column declares `$sourceFile` as its source
- **THEN** that column is populated with the originating blob name for every row

#### Scenario: Unknown synthetic token is rejected

- **WHEN** the mapping declares a `$`-prefixed source other than `$sourceFile`
- **THEN** the service rejects the mapping at load time as a configuration error

#### Scenario: An inline-files body column is loaded

- **WHEN** a mapped `string` column declares `inline-files: { body: request, uri: request.uri }`
- **THEN** the service loads it as a request body column whose inline files the configured inline-files
  settings govern

#### Scenario: A misplaced inline-files attribute is rejected

- **WHEN** a derived column, a non-`string` column, or a column that also declares a `transform` declares
  `inline-files`, or the attribute's `body` is neither `request` nor `response`
- **THEN** the service rejects the mapping at load time as a configuration error

### Requirement: Body columns are captured only by the payload table's mapping

The three unbounded body columns — `request_body`, `response_body`, `assembled_response` — SHALL be
persisted to their own ClickHouse table, `dial_usage_log_payload`, and SHALL NOT be persisted to
`dial_usage_log`. This is enforced at both levels: no mapping targeting `dial_usage_log` captures a
value for any of them, and `V11` has dropped the columns from that table, so there is nothing there to
write to. The bodies are stored as the producer sent them, except that `sync.inline-files-max-size`
removes the payloads of their inline files longer than it and `sync.inline-files-drop-truncated` cuts a body
truncated inside a file back to before it (see "Inline files are removed from payload bodies in drop mode").

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
- the `ReplacingMergeTree` engine versioned by a table-side `_updated_at` default, so a re-ingested
  record replaces its predecessor for the same `event_id` in the same daily partition.

`_updated_at` SHALL be the only column of this table the shipped mapping leaves unpopulated.

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
  receives a row keyed on the same `event_id` carrying all five verbatim values. With
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

### Requirement: Inline files are removed from payload bodies in drop mode

The service SHALL accept two independent ingest settings:
- `sync.inline-files-drop-truncated` (`SYNC_INLINE_FILES_DROP_TRUNCATED`), a boolean with default `true`.
  When on, a truncated body (see below) SHALL be cut back to before the inline file the cut landed in. That
  half file cannot be decoded, so removing it loses nothing a reader could use.
- `sync.inline-files-max-size` (`SYNC_INLINE_FILES_MAX_SIZE`), a data size with a unit (`B`, `KB`, `MB`,
  `GB`, binary multiples, so `1KB` is 1,024 bytes); a bare number is bytes. It is unset by default. When
  set, the payload of every **inline file** whose size (see "Size" below) is greater than the limit SHALL be
  removed; a file whose size is at or under the limit SHALL be kept. `0` therefore removes every non-empty
  file. A negative value, or one that is not a data size, SHALL fail startup.

The previous setting `sync.inline-files-mode` (`SYNC_INLINE_FILES_MODE`) no longer exists, and a value
left in the environment for it SHALL have no effect. Its values map onto the two settings: `keep` is the
repair off with no limit, `drop-truncated` is the defaults, and `drop` is the repair on with a limit of `0`.

The settings apply only to the columns whose mapping entry declares the `inline-files` attribute (see
"Configurable column mapping"). The shipped payload mapping declares it on `request_body` as a request
body, and on `response_body` and `assembled_response` as response bodies.
- **With the repair off and no limit,** such a column SHALL be stored exactly as it is without the
  attribute: the body is not parsed, and the value is byte for byte what the producer sent.
- **With the repair on and no limit** (the defaults), only a column value that is a truncated body SHALL be
  scanned, and every complete inline file SHALL be kept. Any other value SHALL be stored byte for byte as
  sent, without being parsed.
- **With a limit,** the service SHALL remove the payload of every inline file over the limit in the
  column's value before the row is written, and leave every other character of the value unchanged. A
  value whose length in characters is not greater than the limit SHALL be stored unchanged without being
  parsed, unless it is a truncated body and the repair is on, since no file inside it can exceed the limit.

**Size.** The size of an inline file SHALL be the number of characters of its payload, which is its raw
text between the quotes as it stands in the body, without a data URL's `data:...;base64,` prefix. Base64
text is ASCII, so for a base64 file this is its stored size in bytes; the decoded file is about three
quarters of it. A JSON escape counts with its backslash, so `\/` is two characters. The size SHALL be
read off the positions the scan already found, without decoding or re-reading the payload.

**The API of a record** SHALL be inferred from the path of its request URI, read from the source path that
the attribute names, with the query string ignored. The whole path SHALL match one of the patterns below,
which are the fixed paths on which DIAL Core serves each API. `{id}` is one or more characters and may
contain `/`. One or more leading `/` are accepted, except on the two MCP paths, which take exactly one, as
in Core. Matching is case-sensitive and on the raw path, so percent-encoding is kept. The patterns SHALL be
tried in this order, and the first match wins:

| Path | API |
|---|---|
| `/openai/deployments/{id}/chat/completions`, `/openai/v1/chat/completions` | Chat Completions |
| `/openai/deployments/{id}/embeddings` | Embeddings |
| `/openai/v1/responses`, `/openai/responses` | Responses |
| `/anthropic/v1/messages/count_tokens`, `/anthropic/v1/messages` | Anthropic Messages |
| `/v1/toolset/{id}/mcp`, `/v1/deployments/{id}/mcp` | MCP |

The service SHALL NOT infer an API from any other path, and it never guesses one from a path's last
segments. That covers in particular:
- a route with an operator-chosen path (DIAL global routes such as `/claude_code_router/v1/messages`);
- an application route `/v1/deployments/{id}/route/...`, even one that ends with `/messages` or
  `/chat/completions`.

Either may proxy any upstream, so a location carries no meaning there. A record whose path matches none of
the patterns, or whose request URI is absent, SHALL have no inline files: its bodies are stored unchanged.

**What counts as an inline file.** A value is an inline file only when all of the following hold. The
shape of the string SHALL NOT matter: text anywhere else is stored as it is, however it is encoded.
- It is a JSON string.
- It sits at one of the locations below for the record's API and for the column's side (request or
  response).
- Its condition holds.

Locations are matched from the root of a JSON body, or the root of each event of an SSE body. `*` matches
any array index or object key.

| API | Side | Location | Condition |
|---|---|---|---|
| Chat Completions | request | `messages/*/content/*/image_url/url` | data URL |
| Chat Completions | request | `messages/*/content/*/input_audio/data` | none |
| Chat Completions | request | `messages/*/content/*/file/file_data` | data URL |
| Chat Completions | request | `messages/*/custom_content/attachments/*/data`, `messages/*/custom_content/stages/*/attachments/*/data` | DIAL binary attachment |
| Chat Completions | response | `choices/*/message/audio/data`, `choices/*/delta/audio/data` | none |
| Chat Completions | response | `choices/*/message/custom_content/attachments/*/data`, `choices/*/message/custom_content/stages/*/attachments/*/data`, and the same two under `choices/*/delta/` | DIAL binary attachment |
| Responses | request | `input/*/content/*/image_url`, `input/*/content/*/file_data`, `input/*/output/image_url`, `input/*/output/*/image_url`, `input/*/output/*/file_data` | data URL |
| Responses | request | `input/*/result` | none |
| Responses | response | `output/*/result`, `item/result`, `response/output/*/result`, `partial_image_b64` | none |
| Anthropic Messages | request | `messages/*/content/*/source/data`, `messages/*/content/*/content/*/source/data`, `messages/*/content/*/content/content/source/data` | base64 source |
| MCP | response | `result/content/*/data` | sibling `type` is `image` or `audio` |
| MCP | response | `result/content/*/resource/blob` | sibling `mimeType` is a binary type |
| Embeddings | response | `data/*/embedding` | none |

Conditions:
- **data URL:** the value starts with `data:`, and the part before its first `,` ends with `;base64`
  (case-insensitive). Parameters are allowed, for example `data:image/png;name=a.png;base64,`. A JSON
  escape inside that prefix, such as `data:image\/png;base64,`, SHALL be unescaped before this check.
- **binary type:** after removing any `;` parameters and ignoring case, the type is one of:
  - `image/*` except `image/svg+xml`;
  - `audio/*`, `video/*`;
  - `application/pdf`, `application/octet-stream`, `application/zip`, `application/msword`;
  - `application/vnd.ms-*`, `application/vnd.openxmlformats-officedocument.*`.
- **DIAL binary attachment:** the sibling `type` is a binary type; or the value is a data URL and the
  sibling `type` is missing or a binary type.
- **base64 source:** the sibling `type` is `base64`.

A sibling MAY come after the value in its object. The decision SHALL be made once the whole object has
been read. For an object the body or event ends inside, whether cut or malformed, a sibling not read by
then is unknown, not missing: a DIAL attachment there SHALL count as an inline file only when a `type`
read before the end is a binary type.

**Removal.** The removed span SHALL be the value's raw text between its quotes, as it stands in the body:
- a data URL becomes its prefix, for example `"data:image/png;base64,"`, so the media type stays
  visible;
- any other inline file becomes `""`.

The rest of the body, its JSON escapes included, SHALL stay as it was.

**Body formats.**
- A body whose first non-whitespace character is `{` or `[` SHALL be read as one JSON document.
- Any other body SHALL be read as SSE:
  - each line that starts with `data:` and holds a JSON object is one event, and an optional space after
    the colon is not part of it;
  - an event spread over several `data:` lines SHALL NOT be scanned;
  - every non-`data:` line SHALL be kept as it is.
- A body or event that does not parse SHALL keep the removals found before the parse error. Nothing
  after the error is changed. This SHALL NOT fail, skip or empty the column.

**Truncated bodies.** A value is a truncated body when it ends with `>>` and its UTF-8 length is at least
4,194,306 bytes: DIAL Core cut it at 4 MiB and appended that marker.

When either setting is in effect, a truncated body SHALL be handled as follows:
- every inline file that ends before the cut SHALL be removed when it is over the limit, and kept
  otherwise;
- when the value that is open at the cut is an inline file and the repair is on, the body SHALL be cut
  back to just before that value's opening quote and the `>>` marker kept;
- when the open value is an inline file and the repair is off, it SHALL be treated like any other inline
  file: its size is that of its payload up to the cut, and when that is over the limit its payload SHALL be
  removed, leaving the body to end with the value's opening quote (and a data URL's prefix) followed by
  `>>`; otherwise the body SHALL be left as cut;
- when the open value is not an inline file, the body SHALL be left as it is at the cut. That includes a
  value whose condition sibling lies after the cut, and a data URL whose prefix was itself cut.

**Isolation from other columns.** The removal SHALL apply to the value written to the declaring column
only. Every other column of every target, derived columns reading the same source path included, SHALL
see the record as the producer sent it. `request_body_bytes` and `response_body_bytes` on
`dial_usage_log` therefore keep recording the original sizes.

**Determinism.** The output SHALL depend only on the value, the request URI and the two settings. So
ingesting a record again under the same settings writes an identical row, while ingesting it again under
different settings replaces the stored row with their result.

#### Scenario: Keep mode stores bodies unchanged

- **WHEN** `sync.inline-files-drop-truncated` is `false`, `sync.inline-files-max-size` is unset, and a
  record's request body holds an image data URL, or is a truncated body cut inside one
- **THEN** `request_body` is stored byte for byte as the producer sent it, and the body is not parsed

#### Scenario: Files over the limit are removed and files within it kept

- **WHEN** `sync.inline-files-max-size` is `1KB` and a chat request carries one `image_url` data URL whose
  payload after `data:image/png;base64,` is 1,025 characters and a second whose payload is 1,024
- **THEN** the stored `request_body` carries `"url":"data:image/png;base64,"` for the first and the
  second byte for byte

#### Scenario: A body no longer than the limit is not parsed

- **WHEN** `sync.inline-files-max-size` is `1MB` and a request body of 900,000 characters that is not a
  truncated body holds inline files
- **THEN** `request_body` is stored byte for byte as the producer sent it, without being parsed

#### Scenario: A bare number is bytes

- **WHEN** `SYNC_INLINE_FILES_MAX_SIZE` is `2048`
- **THEN** the limit is 2,048 bytes, the same as `2KB`

#### Scenario: A negative limit fails startup

- **WHEN** `SYNC_INLINE_FILES_MAX_SIZE` is `-1`, or is not a data size
- **THEN** the service fails to start

#### Scenario: The removed setting has no effect

- **WHEN** `SYNC_INLINE_FILES_MODE` is `drop` and neither new setting is set
- **THEN** ingest behaves as under the defaults: complete inline files are kept and only truncated bodies
  are cut back

#### Scenario: A chat image is removed and its media type kept

- **WHEN** the size limit is `0` and a record to `/openai/deployments/m/chat/completions` carries
  `messages[0].content[1].image_url.url = "data:image/png;base64,iVBOR..."`
- **THEN** the stored `request_body` carries `"url":"data:image/png;base64,"` at that position and every
  other character of the body unchanged

#### Scenario: Base64 text outside a file location is kept

- **WHEN** the size limit is `0` and a chat request carries a 10 KB base64 string as a text `content` part
- **THEN** that string is stored unchanged

#### Scenario: A DIAL attachment of a text type is kept

- **WHEN** the size limit is `0` and a chat message carries `custom_content.attachments[0]` with
  `type: "text/markdown"` and inline `data`
- **THEN** the attachment's `data` is stored unchanged

#### Scenario: A DIAL attachment of a binary type is removed even when its type follows the data

- **WHEN** the size limit is `0` and a chat message carries an attachment object whose `data` comes before
  `"type": "application/pdf"`
- **THEN** the attachment's `data` is stored as `""`

#### Scenario: An Anthropic text document is kept

- **WHEN** the size limit is `0` and an Anthropic Messages request carries a document block whose
  `source.type` is `text`
- **THEN** its `source.data` is stored unchanged, while a block whose `source.type` is `base64` has its
  `source.data` stored as `""`

#### Scenario: An MCP image and a binary resource are removed

- **WHEN** the size limit is `0` and an MCP response carries `result.content[0]` with `type: "image"` and
  `data`, and `result.content[1].resource` with `mimeType: "application/pdf"` and `blob`
- **THEN** both values are stored as `""`

#### Scenario: An MCP text resource is kept

- **WHEN** the size limit is `0` and an MCP response carries `result.content[0].resource` with
  `mimeType: "text/plain"` and `blob`
- **THEN** the `blob` is stored unchanged

#### Scenario: A base64 embedding vector is removed

- **WHEN** the size limit is `0` and an embeddings response carries `data[0].embedding` as a string
- **THEN** that value is stored as `""`, while an embedding given as a number array is stored unchanged

#### Scenario: A route with an API-like path is never scanned

- **WHEN** the size limit is `0` and a record's request URI is `/claude_code_router/v1/messages?beta=true`,
  or `/v1/deployments/app/route/v1/messages`, and its request body is shaped like an Anthropic Messages
  request with a `base64` image source
- **THEN** its bodies are stored unchanged, because neither path is one on which DIAL Core serves an API

#### Scenario: An application's chat path is scanned whatever its name

- **WHEN** the size limit is `0` and a record's request URI is
  `/openai/deployments/public/pg/test/agent__1.0.0/chat/completions?api-version=2024-10-21`
- **THEN** its bodies are scanned against the Chat Completions locations

#### Scenario: A streamed response has its files removed event by event

- **WHEN** the size limit is `0` and a streamed chat response body holds `data:` events whose
  `choices[0].delta.custom_content.attachments[0]` carries a binary `type` and `data`
- **THEN** each such `data` value is stored as `""`, and every other line of the transcript is stored
  unchanged

#### Scenario: A body cut inside a file is cut back before it

- **WHEN** the repair is on, the size limit is `0`, and a request body ends with `>>`, is 4,194,306 bytes
  long, and was cut inside a `messages/*/content/*/image_url/url` data URL
- **THEN** the stored `request_body` ends just before that value's opening quote followed by `>>`, and
  every complete inline file before it is removed

#### Scenario: Drop-truncated cuts back the half file and keeps the complete ones

- **WHEN** the settings are the defaults and a request body ends with `>>`, is 4,194,306 bytes long, holds
  one complete `image_url` data URL, and was cut inside a second one
- **THEN** the stored `request_body` keeps the first image byte for byte and ends just before the second
  value's opening quote followed by `>>`

#### Scenario: Drop-truncated leaves a body that was not cut untouched

- **WHEN** the settings are the defaults and a request body under 4 MiB holds an image data URL
- **THEN** `request_body` is stored byte for byte as the producer sent it, and the body is not parsed

#### Scenario: With the repair off, the half file is measured against the limit

- **WHEN** `sync.inline-files-drop-truncated` is `false`, `sync.inline-files-max-size` is `1MB`, and a
  truncated request body was cut inside an `image_url` data URL 3 MB into its payload
- **THEN** the stored `request_body` ends with that value's opening quote and `data:image/png;base64,`
  prefix followed by `>>`, and the half file counts toward the removed bytes but not the removed files

#### Scenario: With the repair off, a half file within the limit is left as cut

- **WHEN** `sync.inline-files-drop-truncated` is `false`, `sync.inline-files-max-size` is `8MB`, and a
  truncated request body was cut inside an `image_url` data URL
- **THEN** the stored `request_body` keeps the half file as cut, followed by `>>`

#### Scenario: A body cut inside text is left as cut

- **WHEN** the size limit is `0` and a truncated request body was cut inside a text `content` string
- **THEN** the body keeps its cut text and the `>>` marker, with only the complete inline files before
  the cut removed

#### Scenario: A body that does not parse is stored unchanged

- **WHEN** the size limit is `0` and a response body is neither JSON nor SSE with `data:` events
- **THEN** the body is stored unchanged and the record is ingested normally

#### Scenario: Body sizes on the usage log are unaffected

- **WHEN** the size limit is `0` and a record's request body holds a 2 MB image
- **THEN** the record's `dial_usage_log.request_body_bytes` is the size of the body as the producer sent
  it, not the size stored in `request_body`

#### Scenario: Re-ingesting under the same mode gives the same row

- **WHEN** the same record is ingested twice with `sync.inline-files-max-size=0`
- **THEN** both writes carry identical body values

