## Why

A group pipeline such as `session-insights-live` can stop writing entirely while every surface reports it
healthy. Pinning a new evaluator version triggers a re-enrichment burst; each group's second-phase payload
read can exhaust ClickHouse's memory, so most executions are killed with `MEMORY_LIMIT_EXCEEDED`, the
ClickHouse pod can be OOM-killed, groups dead-letter, and the runner's DLQ circuit breaker auto-pauses
enqueue. ADAS recovers; the pipeline does not.

The read at the centre of it returns **one row**. `GroupFetchService` reads a group in two phases: phase
one selects the members, phase two fetches the bound payload columns for the ones the selection policy
kept — here `member_select.limit: 1`, so exactly one. That single-row read can cost several GiB.

`dial_usage_log_payload` is `ORDER BY (event_id)`, `PARTITION BY toYYYYMMDD(request_time)`, on the order of
a hundred GiB over hundreds of parts. `event_id` is a deterministic hash of `(trace_id, core_span_id)`, so it is
uniformly distributed across every daily partition: a lookup for one value gives the primary-key search no
basis to exclude a part and lands one candidate granule in each, and adaptive granularity makes a granule
~10 MB. Through the shipped read path, one heavy column costs on the order of 1 GiB for a single-row
result and any two exceed a 2 GiB ceiling.

**This is fallout from the payload split.** Before `V10`/`V11` the bodies lived on `dial_usage_log`, whose
ordering key is `(project_id, deployment, request_time, trace_id, core_span_id)` — so the tuple
identifying a row pruned the bodies to one partition for free. The split moved the bodies to a table that
same predicate no longer names. The group-fetch statement still carries
`dial_usage_log.request_time = '…'`; it just applies it to the wrong table, and ADAS has no way to apply
it to the right one, because `partition_by` is source-only in the catalog and `V38` deliberately left the
payload table's unset as a value that "would be stored and never read".

Two further defects turn an expensive query into a prolonged outage and lost rows:

- **The pipeline-internal reads carry no resource ceiling.** `executeScan` sets `final = 1` and nothing
  else. The query surface has `query.budget.*` and refuses cleanly at 2 GiB; a group fetch consumes
  whatever the node will give it, which is how one of them can take ClickHouse down and every other
  capability with it. The reasoning for leaving them unbudgeted — that each is bounded by its own window,
  page size or limit — does not hold: a limit bounds the rows returned, not the rows read.
- **A ClickHouse resource failure is reported as a client error.** Rows can be lost at `upsert`:
  `POST /v1/pipelines/{name}/rows` answers `400 bad_request` — "check field/function/type
  compatibility" — for rows whose every value is valid. Four byte-identical copies of the
  connectivity/rejection classifier treat any non-socket failure as a statement rejection, so
  `MEMORY_LIMIT_EXCEEDED` on an insert tells a first-party caller its work is malformed, and the caller
  discards it.

## What Changes

- **Partition-aligned grain joins.** An enrichment whose catalog declares the *same* partition column as
  its source claims that the two hold the same value for the same grain key. Given that claim, a keyed
  `EnrichmentJoins` restriction bounds the enrichment's partition column by the values the source
  predicates select, in the same `IN`-set shape as the existing grain-key restriction — so a point read
  prunes to the partitions that can hold the row. Declared for `dial_usage_log_payload` by migration;
  readable on any table through `GET /v1/tables/{name}`, settable only on a source.
- **The group projection keys its joins.** `GroupFetchService.projectMembers` hands its member lookup and
  safety lag — source-only by construction — to `EnrichmentJoins` instead of only adding them to the
  `WHERE`. This is what supplies the `request_time` values the alignment prunes on. The group-key predicate
  stays out: it may name an enrichment column, which disqualifies it.
- **A resource budget on every pipeline-internal read.** New `pipeline.budget.*`, applied to the
  enrichment scan page, both group-fetch phases, the materialize and refresh window probes and the Class-1
  preview sample. Exceeding it refuses that read with `422 pipeline_read_budget_exceeded` — deterministic,
  so the runner dead-letters rather than retries.
- **ClickHouse resource exhaustion reported as unavailable.** One shared classifier replaces four
  identical copies and adds the server-side resource codes, so out-of-memory, out-of-disk, out-of-slots and
  cancelled surface as `503` on every path instead of as a statement rejection.

## Non-goals

- **Re-keying or re-partitioning `dial_usage_log_payload`.** `ORDER BY` cannot be `ALTER`ed; changing it
  means a new table and copying every body, and `event_id` as the sole ordering column is what makes the grain
  join's `ReplacingMergeTree` collapse correct. Pruning partitions gets the same result for the cost of one
  catalog row.
- **Accepting `partition_by` on an enrichment through the API.** The declaration asserts something about
  how a table is *populated*, which holds for a table written from one record on both sides and not for one
  written by arbitrary row writes — and nothing at query time can catch a false claim, because an
  over-restricted LEFT JOIN yields the column's type default, indistinguishable from an unmatched row. No
  API-created enrichment is partitioned at all, so there is nothing yet to align.
- **A skip index on `event_id`.** It cannot help: the primary key is already precise to one granule per
  part, and nearly every candidate part genuinely might hold a uniformly-distributed identifier.
- **The runner-side defects.** The DLQ circuit breaker's missing auto-reset and operator-visible state, and
  queued group work being dropped across a restart, live in the Enrichment Runner repository. They are what
  can keep such an outage going long after ADAS recovers, and they are addressed separately.
- **`rebuild_required` on `usage_client_identity`.** A real concern, but orthogonal: it affects which
  session ids existing rows are keyed on, not whether new rows can be written.
