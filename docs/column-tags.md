# Column tags

> Column metadata comes in two flavors: **descriptive** (this page — `tag`, `display_name`,
> `description`) and **behavioral** (`sensitive`, which restricts visibility, and `heavy`, which drops
> the column from wildcard projections — see [heavy columns](heavy-columns.md)). A column's *type* may
> also carry a declared value domain — see [enum columns](enum-columns.md).

Every catalog column may carry an optional `tag` — a short, free-form label that helps a consumer
group a table's many columns when composing a query. It is catalog-only metadata: it never appears in
the ClickHouse DDL, is not validated against a fixed list, and is purely descriptive (you cannot
filter, group, or sort by it). It is returned on `GET /v1/tables/{name}` and
`GET /v1/queries/entities/schema/{name}`, and can be declared when the schema is defined
(`POST /v1/tables/{name}/schema`, on a not-yet-materialized table) or, once the table is materialized,
changed via `PATCH /v1/tables/{name}/schema` (the `update` merge-patch — send `{"name": "...", "tag":
"..."}`; a blank `tag` clears it, an omitted `tag` leaves it unchanged).

The seeded system tables (`dial_usage_log`, `rate_analytics`) use two kinds of tag:

- Two **subject** tags for the attribution columns — `principal` and `deployment` — answering "who made
  the request?" and "what did they call?".
- **Role** tags for the rest, grouping columns by how a consumer uses them in a query: `identifier`
  (correlation keys), `dimension` (categorical/time slicers), `token-usage`, `cost`, `performance`,
  `request`, `response`, `system`.

New tables should follow the same spirit: subject tags for the who/what columns, role tags for the
rest.

## Tag ordering (`tag_order`)

A table may carry an optional **tag ordering** — an ordered list of tag names that says in what order
a consumer should render the tag groups its columns fall into. Without it, columns come back in their
declared (physical) order, which groups tags arbitrarily. It is table-level, catalog-only metadata
(never in the ClickHouse DDL), returned on `GET /v1/tables/{name}` and
`GET /v1/queries/entities/schema/{name}` as `tag_order` (absent/`null` when not set).

Set it at create (`POST /v1/tables`, top-level `tag_order`) or later via `PUT /v1/tables/{name}`
(alongside `description`) — it is table catalog metadata, not part of the schema endpoints. It follows
the same merge-patch idiom as the column `update`:

- omit it (or send `null`) → leave the ordering unchanged;
- send `[]` → clear it (back to "not set");
- send a non-empty array → replace it.

Entries are validated (trimmed, non-blank, ≤ 64 chars each, no duplicates, ≤ 100 entries) but are
**not** checked against the tags currently on columns — the ordering is a decoupled display hint. This
is deliberate, so the common churn cases just work:

- you can set the order *before* retagging columns;
- retagging a column to an unlisted tag, or adding a column with a brand-new tag, never errors and
  never rewrites the ordering — the new tag simply becomes "unlisted";
- an entry for a tag that no column currently carries is kept and returned verbatim.

**How columns/fields are sorted when an ordering is set** (applied on both read surfaces via one
shared rule):

1. tags listed in `tag_order`, in list order;
2. then tags present on columns but not listed, ordered alphabetically by tag name;
3. then untagged columns, last;
4. within each group, alphabetically by the column's exposed name.

The sort is presentation-only — the physical column order (and thus ClickHouse column order) is never
changed. The seeded system tables ship with a taxonomy ordering (see the `V10` migration).

## `display_name` vs. `tag`

A column also has an optional `display_name` — a short, user-friendly name (e.g. `Total money spend`
for the column `total_money`) that a UI can render in place of the raw column name. It is a different
concept from `tag`: `tag` groups columns for a consumer; `display_name` is how a single column is
shown. Like `tag`, `display_name` is catalog-only metadata (never in the ClickHouse DDL, not validated
against a fixed list), is returned on `GET /v1/tables/{name}` and
`GET /v1/queries/entities/schema/{name}`, and can be declared when the schema is defined
(`POST /v1/tables/{name}/schema`) or, once materialized, changed via `PATCH /v1/tables/{name}/schema`
(the `update` merge-patch — send `{"name": "...", "display_name": "..."}`). It is capped at 128
characters; a blank display name is treated as absent (which clears it).

## Column `description`

A column also has an optional `description` — free-form help text explaining what the column means,
longer than a `display_name`. Like `display_name` and `tag` it is catalog-only metadata (never in the
ClickHouse DDL), is returned on `GET /v1/tables/{name}` and `GET /v1/queries/entities/schema/{name}`,
and can be declared when the schema is defined (`POST /v1/tables/{name}/schema`) or, once materialized,
added with a new column (`PATCH ... add`) or changed via `PATCH /v1/tables/{name}/schema`
(the `update` merge-patch — send `{"name": "...", "description": "..."}`, keyed by the column's
exposed name). It is capped at 1024 characters; a blank description is treated as absent (which
clears it).

## The subject tags: `principal` / `deployment`

| tag | answers | covers |
|---|---|---|
| `principal` | Who made the request? | `project_id`, `user_hash` |
| `deployment` | What did they call? | `deployment`, `parent_deployment`, `execution_path` (the deployment that handled the hop, its caller, and the routing path) |

On `rate_analytics`, which exposes only a subset, `principal` covers `project_id`/`user_hash` and
`deployment` covers `deployment`.

## The role tags

The remaining columns are grouped by how they are used in a query:

| tag | covers |
|---|---|
| `identifier` | the keys/correlation ids that identify one interaction (and its response) — used to filter-to-one, join, dedup, or trace: `chat_id`, `trace_id`, `core_span_id`, `core_parent_span_id`, `event_id`, `response_id` (on `rate_analytics`, `chat_id`/`event_id`/`response_id`) |
| `token-usage` | prompt / completion / total / cached / cache-creation / reasoning token counts |
| `cost` | `total_price`, `deployment_price` |
| `performance` | `operation_duration_ms` |
| `request` | the request side: `request_method`, `request_uri`, `mcp_method`, `mcp_tool_call_name`, `number_request_messages`, `request_body_bytes`, and `"dial_usage_log_payload.jwt_claims"`, `"dial_usage_log_payload.request_tags"`, `"dial_usage_log_payload.request_body"` on the payload enrichment (and the user-submitted `rate`/`rate_raw`/`comment` on `rate_analytics`) |
| `response` | the response side: `response_status`, `success`, `response_upstream_uri`, `response_body_bytes`, and `"dial_usage_log_payload.response_body"`, `"dial_usage_log_payload.assembled_response"` on the payload enrichment |
| `system` | ingest/storage bookkeeping (`_ingested_at`, `_source_file`) |
| `dimension` | the plain categorical/time slicers not covered above: `event_kind` and `request_time` (`request_time` on both tables) |

The old `identity` / `lineage` tags are retired from the seeded tables. `request_time` stays a
`dimension` (you range-filter and time-bucket it); a dedicated `timestamp` tag is a possible future
refinement.
