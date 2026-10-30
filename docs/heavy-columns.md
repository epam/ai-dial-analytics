# Heavy columns

A column may be marked **`heavy`** — expensive to transfer, typically a raw payload/blob column. A
heavy column is **omitted from wildcard projections** and returned normally when you name it.

Why it exists: the first query an external client runs is almost always
`pandas.read_sql("SELECT * FROM dial_usage_log", conn)` over Arrow Flight SQL, or the same statement
through `POST /v1/queries/execute-sql`. On a usage-log-shaped table that drags the verbatim LLM
request/response bodies and the merged response — tens of KB per row each — along with the small
columns the analyst wanted, turning a few hundred KB into tens of MB. The client cannot opt out: the ADBC/pandas driver sends
nothing but SQL text, and `SELECT * EXCEPT (…)` is outside the accepted SQL subset. So the opt-out
lives in the catalog. The rule follows the entity wherever it is named: a `*` over a join expands to
each operand's non-heavy source columns, and a derived table or CTE projects what its own select list
asked for.

Like `tag`, `display_name` and `sensitive`, `heavy` is **catalog-only metadata**: it never appears in
any ClickHouse DDL, and flipping it moves no data and issues no `ALTER`.

## What a wildcard projects

A **wildcard projection** is either of:

- SQL `SELECT *` (or `SELECT t.*`) — `POST /v1/queries/execute-sql`, Flight SQL, `POST /v1/queries/translate-sql`;
- a row-mode DSL query with no `select` — `POST /v1/queries/execute`.

Both expand to the same set: **the entity's source columns that are not heavy**, in catalog order.
Two things are left out, for two different reasons:

| Left out | Why | How to get it |
|---|---|---|
| heavy columns | expensive to transfer | name the column |
| enrichment columns | naming one is what pulls in its `LEFT JOIN`, so a wildcard never fans out into joins | name it by its quoted qualified name, `"deployment_ref.display_name"` |

Nothing else changes. A heavy column stays usable in `WHERE`/`filter`, `GROUP BY`/`group_by`,
`HAVING`/`having`, `ORDER BY`/`sort`, and as a function argument, and its values are returned verbatim
when projected — heaviness never truncates or reshapes a value.

```sql
-- fast: the payload columns are left out
SELECT * FROM dial_usage_log LIMIT 1000;

-- you asked for it: the payload comes back in full. The seeded heavy columns live on the payload
-- enrichment, so they are named by their quoted qualified names (see below).
SELECT event_id, "dial_usage_log_payload.request_body", "dial_usage_log_payload.response_body"
FROM dial_usage_log LIMIT 10;

-- filtering on a heavy column is fine — it just is not projected
SELECT * FROM dial_usage_log
WHERE "dial_usage_log_payload.response_body" LIKE '%rate limit%' LIMIT 100;
```

If **every** column visible to the caller is heavy, a wildcard is rejected with
`400 bad_request` (gRPC `INVALID_ARGUMENT` on Flight) naming the entity, rather than returning a
zero-column result.

## Not access control

`heavy` is a **cost hint**. It hides nothing: the column is present in schema discovery for every
caller, with the flag set, and selecting it by name is always allowed. Confidentiality is governed
exclusively by `sensitive` (see the `authentication` capability), which removes a column from the
caller's model entirely.

The two are independent, and the seeded payload columns happen to carry both.

## Discovering which columns are heavy

- `GET /v1/queries/entities/schema/{entity}` — each field carries `heavy` (never role-filtered);
- `GET /v1/tables/{name}` — each column carries `heavy`, unfiltered, for operators;
- `POST /v1/queries/translate-sql` — translating `SELECT * FROM <entity>` returns the DSL document with
  the expansion spelled out, which is the precise answer to "what will my star actually return?".

## Setting and clearing it

Declare it when the schema is defined (before materialization):

```http
POST /v1/tables/widget_metrics/schema
{
  "columns": [
    {"source_name": "widget_id", "name": "widget_id", "type": "string"},
    {"source_name": "seen_at",   "name": "seen_at",   "type": "timestamp"},
    {"source_name": "payload",   "name": "payload",   "type": "string", "heavy": true}
  ],
  "ordering_key": ["widget_id"],
  "identity_column": "widget_id",
  "version_column": "seen_at"
}
```

(`identity_column`/`version_column` are optional for a source and unrelated to heaviness — see the
`table-management` spec. A heavy column may be named as either: heaviness is a transfer-cost hint, not
access control, and stays freely settable on a cursor column. `sensitive` is the one attribute that does
not: the scan projects both cursor columns, so marking either one sensitive is rejected.)

Change it later on a materialized table through the `update` merge-patch — omit `heavy` to leave it
unchanged, send `true`/`false` to set it:

```http
PATCH /v1/tables/widget_metrics/schema
{"update": [{"name": "payload", "heavy": true}]}
```

The patch issues **no** ClickHouse `ALTER`, takes effect on the very next query (the query model is
read per request), and survives a `rename`. It may be set on any exposed column, including a system,
grain, or ordering-key column — the mutability restrictions apply to `drop`/`rename`, not to metadata.

## Seeded heavy columns

The catalog migrations seed four:

| Column | Why |
|---|---|
| `dial_usage_log_payload.request_body` | verbatim LLM request body, unbounded per row |
| `dial_usage_log_payload.response_body` | verbatim LLM response body, unbounded per row |
| `dial_usage_log_payload.assembled_response` | the producer's merged streaming response, unbounded per row |
| `dial_usage_log_payload.request_cache_control` | the `cache_control` markers taken out of a chat request's history, under `SYNC_REQUEST_BODY_MODE=delta` |

Every other seeded column is non-heavy — including the `object`-typed
`"dial_usage_log_payload.jwt_claims"` and `"dial_usage_log_payload.request_tags"` (small claim/header
documents), the `*_body_bytes` size columns, and `rate_analytics.comment`. All four heavy columns are
also `sensitive`, so the wildcard change is observable only to callers holding the `TABLE_SENSITIVE` clearance on
the owning table and to deployments
running `security.mode=none`.

**`heavy` and the payload split are independent.** The claim/header pair moved to the payload
enrichment without becoming heavy, and is excluded from a wildcard anyway — by ownership, not by size.

`assembled_response` is the column a conversation UI actually wants: on a streamed call `response_body`
holds the raw SSE chunk transcript, so the assistant's answer is not readable from it without
reassembly, while the merged form already is. It has no `*_bytes` companion, so there is no way to size
the payload before fetching it — name it on a query that is already narrowed by `request_time`, because
projecting it across an unbounded window is what the [query execution
budget](configuration.md#query-execution-budget) refuses.

## Request bodies in delta form

Under `SYNC_REQUEST_BODY_MODE=delta` (default `whole`; see [configuration](configuration.md)) a chat request is
not stored whole. Every turn of a conversation re-sends the history before it, and usually the same tools, so
`request_body` keeps the request with only the history elements this turn added, without the system prompt and the
tools when the earlier turn it links to already has them, and a hidden link to that turn. The first turn of a
conversation is stored complete, and every other body (MCP, embeddings, anything that does not parse) is stored
whole. What that means for a query:

```sql
-- top-level fields read the same way as before
SELECT json_extract_string("dial_usage_log_payload.request_body", 'model') AS model FROM dial_usage_log
WHERE request_time >= now() - INTERVAL 1 HOUR LIMIT 10;
```

A text search over `request_body` usually finds each message once, in the turn that first sent it; a conversation
that starts a new chain, on a new UTC day for example, stores its history again.

A query that needs a later turn's whole history, or its tools, does not find them in that turn's `request_body`. It
reads `dial_usage_log_payload.request_body_full` instead: the body rebuilt, after the statement runs, to the request
as it was sent (system prompt, tools and cache markers included; key order and whitespace are not kept), with
`dial_usage_log_payload.request_body_rebuild` saying what it holds — `stored` (the body was stored whole, returned
byte for byte), `rebuilt`, or `partial` (the chain could not be completed, so this is the stored delta, not the whole
request). Both are `NULL` when the event has no payload row or no stored request body.

```sql
-- one hop, read back whole
SELECT trace_id, "dial_usage_log_payload.request_body_full", "dial_usage_log_payload.request_body_rebuild"
FROM dial_usage_log
WHERE trace_id = 'trace-0001' AND core_span_id = 'span-0003'
  AND request_time >= '2026-03-02 10:00:00' AND request_time < '2026-03-02 10:01:00'
LIMIT 1;
```

Both fields are **projection-only**: they can be selected, aliased or not, by a single-entity query, and bound as a
pipeline's `transform.inputs`, but not filtered, sorted, grouped, passed to a function or read inside a join, derived
table, CTE, subquery or set operation (`400 bad_request`; `422 pipeline_validation_failed` in a pipeline declaration,
a disabled draft included).
Discovery marks them `projection_only: true`. A rebuild reads the row's chain inside its own UTC day — first the small
chain columns, then the bodies of exactly the rows on the chain — so it costs extra statements for delta rows only,
none for rows stored whole. It runs within what is left of the read's time budget, after the read's own statements,
and rows it has no time left for read `partial`; the per-read bounds are in
[configuration](configuration.md#request-body-rebuild).
The behaviour is specified in `openspec/specs/data-sync/spec.md` ("A chat request body is stored as its new history
and a parent link", "A rebuilt request body is readable beside the stored one").

## The payload columns live on their own table

The heavy payload columns — `request_body`, `response_body`, `assembled_response` and `request_cache_control` — are **not** columns of
`dial_usage_log`, neither in the catalog nor physically (`V11` dropped them from it). They live on
`dial_usage_log_payload`, a system **enrichment** of it keyed on `event_id`, so that they can carry a
shorter retention (90 days by default, `SYNC_PAYLOAD_TTL_DAYS`) against the usage log's (a year by default,
`SYNC_USAGE_LOG_TTL_DAYS`, which bounds the payload's). A ClickHouse
row-expiry TTL is a property of the table, not of a column, so a second table is the only way to say
"keep the metrics for a year and the payloads for a quarter".

They are reached from the same entity, under their qualified flat names:

```sql
-- the payload columns, named explicitly
SELECT event_id, "dial_usage_log_payload.request_body" FROM dial_usage_log LIMIT 10;

-- filtering works too; the grain LEFT JOIN fires because the query names the column
SELECT * FROM dial_usage_log
WHERE "dial_usage_log_payload.response_body" LIKE '%rate limit%' LIMIT 100;
```

The bare names `request_body`, `response_body` and `assembled_response` **do not resolve** on
`dial_usage_log`. A query, saved query or pipeline declaration naming one fails as an unknown field.
There is deliberately no alias: a bare name that silently meant a 90-day column on a one-year table
would be worse than one that fails.

Two consequences of the move:

- **A wildcard omits them twice over.** They were already skipped for being `heavy`; now they are also
  enrichment columns, and [a wildcard never fans out into a grain join](#what-a-wildcard-projects).
  Either rule alone would exclude them.
- **A payload can be absent while its event is present.** The payload table holds 90 days by default and
  `dial_usage_log` a year by default, so naming a payload column for an older event returns `NULL` — the
  outcome of a `LEFT JOIN` that finds no row — rather than failing.

`jwt_claims` and `request_tags` **moved too**, and are addressed as
`"dial_usage_log_payload.jwt_claims"` and `"dial_usage_log_payload.request_tags"`. The storage argument
does not apply to them — they are small documents — but the disclosure one does: they are the two
columns that can carry bearer tokens, cookies and forwarded client IPs, and a year is a long time to
hold one. They are not flagged `heavy`.

They stay **non-nullable**, because ClickHouse has no `Nullable(JSON)`. An unmatched grain join
therefore renders them as the empty document `{}` rather than as `NULL` — which is safe, and is why
they can be enrichment columns at all: every accessor returns over `{}` exactly what it returns over a
document that is present but lacks the key (`json_extract_string` → `NULL`, `json_extract_array` → `[]`,
`json_extract_string_ci` → `''`). Nothing can tell absence from emptiness, so no derived value can be
corrupted by the default. That is the same exemption `array` and `map` columns have always had.

One consequence worth knowing: an `IS NULL` check against a claim/header column does **not** detect a
missing payload row, because the value is `{}`, not `NULL`. Use a body column, which is
`Nullable(String)` and does read `NULL`, when you need to distinguish the two.

**The same asymmetry applies after the payload expires.** Once an event is older than the payload retention (90 days by default) its
payload row is gone while the event stays (a year by default), and its `jwt_claims` / `request_tags` then read
as `{}` — indistinguishable from a request that genuinely carried no claims or no headers. A query
that counts requests "whose `request_tags` lacks header X" over six months will silently classify
everything older than the retention as lacking it. Bound such a query to the payload window
(`request_time > now() - INTERVAL <SYNC_PAYLOAD_TTL_DAYS> DAY`), or test a body column's `IS NULL` alongside to tell an
expired payload from an empty document.

Composite SQL statements (joins, CTEs, subqueries) run with `join_use_nulls = 1`, standard outer-join
semantics; single-entity statements keep ClickHouse's default of `0`. The claim/header columns read
`{}` under both, because a `JSON` column cannot be wrapped in `Nullable` — so the setting changes
nothing here, and the rule above holds on every surface.

**How the grain join is executed.** ClickHouse builds a `LEFT JOIN`'s hash table from the right-hand
table, and nothing in the source's `WHERE` narrows that build on its own. ADAS therefore emits the
payload join *keyed* on the source-only top-level `AND` conjuncts of the statement's filter: the payload
side becomes a derived table restricted to `event_id IN (SELECT event_id FROM dial_usage_log WHERE
<source conjuncts>)`, so a lookup by `trace_id` reads the handful of payload rows it needs instead of
every body in the window. When the source conjuncts are only bounds on `request_time` (a plain time window), the
payload side is restricted by that window on its own `request_time` instead. This skips building a key set of every
event the window holds, and returns the same rows, since a payload row carries its event's `request_time`. A conjunct on a payload or other enrichment column stays in the outer `WHERE`
and does not stop the source conjuncts beside it from keying the join. Only a statement with no filter,
or one with no source-only conjunct (a payload-only filter, or an `OR` mixing source and payload
columns), is joined whole. The hash join over the payload table is then the honest cost of what it
asked for, and the query budget applies. When reading bodies at scale, put a source conjunct
(`request_time`, `trace_id`, `project_id`) next to any body predicate.

**What a body read costs.** Keying bounds *which* payload rows the join reads; on the single-entity
surfaces (`execute`, `execute-sql`, the CSV twins, Flight) three further rewrites bound how much of that
ClickHouse builds into the join. None changes a result; the
[structured-query spec](../openspec/specs/structured-query/spec.md) states each rule exactly.

| statement shape | what is read into the join |
|---|---|
| bodies only in the `SELECT`, under a `LIMIT` (`SELECT trace_id, "…request_body" … ORDER BY request_time DESC LIMIT 10`) | the bodies of the returned page only, whatever the window: the page's keys are chosen first, then its bodies fetched |
| a body search in the `WHERE` (`"…request_body" ILIKE '%error%'`, `= 'x'`, `IN (…)`, `starts_with(…)`) | only the payload rows that match; every body in the window is still *scanned* to decide that |
| a body used only through `length`, `json_extract_*`, `starts_with` or `contains` (`avg(length("…request_body"))`) | the number, string or flag the call returns, not the body |

Everything else — a body in `ORDER BY` or `GROUP BY`, `DISTINCT`, a window function or `array_join` beside
a projected body, a body passed to any other function in a filter or an aggregate (in a `LIMIT`ed row
projection any function over it is still fetched for the page only), and every composite statement (joins, CTEs,
subqueries) — reads the whole keyed window as described above. A projected body under no partition
bound is cheap now; the source scan that picks the page is not, and a full-text search over days of
bodies still scans every one of them, so keep a `request_time` bound on both.

The `*_bytes` columns also stayed, deliberately: they are 4 bytes each, they are the only way to size a
payload before fetching it, and they must outlive the payload they describe for "how big were the
bodies last quarter" to stay answerable past the payload window.

## Upgrading an existing deployment

The split is two ClickHouse migrations. `V10` creates `dial_usage_log_payload`, and the shipped
mapping change starts capturing new bodies, claims and headers there instead of on `dial_usage_log`.
`V11` then drops the five columns from `dial_usage_log`, which by that point no mapping writes to.

**`V11` deletes pre-split content, permanently.** There is no backfill between the two: the body,
claim and header values of rows ingested before the mapping cutover are dropped with the columns and
are not recoverable from the database. They were already unreachable through ADAS — the catalog
publishes those five names from `dial_usage_log_payload`, so the bare names stopped resolving on
every query surface when the split shipped, and only a direct ClickHouse session could still read
them. An operator who wants that history copies it out **before** deploying.

The drop itself needs no planning: ClickHouse removes each dropped column's files from every part
rather than rewriting the surviving columns, so there is no free-space headroom to arrange and no
transient double-storage. The `ALTER` returns before the space is actually back — watch
`system.mutations` for `dial_usage_log` if you need to know when the reclaim has finished.

**Out-of-band application.** When migrations are disabled, apply
`V10__create_dial_usage_log_payload.sql` and then `V11__drop_dial_usage_log_payload_columns.sql` by
hand with an administrative credential before starting the new build, in that order. `V10` needs the
AES codec key, as `V1` does; `V11` needs `ALTER DROP COLUMN` on `dial_usage_log` (see
[ClickHouse server](clickhouse-server.md)).

**Rolling deploys and rollback.** Rolling forward across `V11` is safe: every build since the mapping
cutover writes the payload table and not these columns, so a pod that has not yet been replaced is
unaffected by their removal. Rolling back is safe too, as far back as the cutover. Rolling back to a
build from **before** it means restoring the shape first with `ALTER TABLE dial_usage_log ADD COLUMN`
— and that restores the columns empty. The split is one-way for data.

See also [column tags](column-tags.md) for the other per-column catalog metadata, and
[enum columns](enum-columns.md) for declaring a column's closed value domain.
