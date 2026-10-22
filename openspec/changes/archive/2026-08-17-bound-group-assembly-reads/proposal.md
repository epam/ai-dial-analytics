## Why

`POST /v1/rules/{id}/group-rows` bounds its **response** but not its **read**. Both caps run after
ClickHouse has already materialized the rows: the row cap is a `LIMIT` applied to a query that already
projects the rule's bound body columns, and the byte cap is applied in application code over rows that
are already in hand. A group rule binding `dial_usage_log.request_body` / `response_body` therefore asks
ClickHouse for up to 201 bodies to return at most 8 MB, and the caller's token budget then uses a
fraction of that.

Assembling one conversation of a few hundred hops carrying tens of MB of bodies can fail with
ClickHouse `Code: 241 MEMORY_LIMIT_EXCEEDED` against a server ceiling of a few GiB, and the server can then
stay pinned near that ceiling, so a subsequent single-row body read fails too. No rule setting avoids
this — a `filter_sql` byte ceiling is a `WHERE` evaluated after the bodies are read, and a smaller
`limit` is applied after the sort. The endpoint is unusable for its designed purpose on modestly sized hardware,
and the failure is a shared-instance outage rather than a per-request error.

The second half of the problem is that the endpoint hardcodes *which* members matter: the newest N by
the source's total order. For DIAL usage-log conversations that is the wrong policy. A conversation's
newest hop is frequently a 2-message internal sub-agent call, while the body carrying the accumulated
history is the chain entry (`parent_deployment IS NULL`, present for roughly half of chats) or otherwise the hop
with the highest `number_request_messages`. Reading 200 members to find 2 useful ones is what makes the
read expensive in the first place.

## What Changes

- **Group assembly becomes a two-phase read.** Phase one selects only cheap columns — the group key, the
  cursor columns, the membership filter's columns, the member-selection columns, and the source's full
  ordering key — and decides which members qualify. Phase two fetches the rule's bound input columns for
  **only those members**, addressed by the source's full ordering key so ClickHouse prunes to one granule
  per member. Fetching by a non-indexed column (for example `event_id`) SHALL NOT be used: it would not
  prune granules and would reproduce the present cost.
- **A group rule may declare which members matter**, via a new optional `member_select` on the rule:
  an optional `prefer_sql` predicate, an optional `order_by`, and a `limit`. Members matching `prefer_sql`
  are the candidate set; when none match, all members are. Candidates are ordered by `order_by` with the
  source's total order as a deterministic tiebreak, and the first `limit` are retained.
- **The effective row cap becomes the smaller of the configured cap and `member_select.limit`**, and it is
  applied in phase one, so a rule that declares a small `limit` gets a read proportional to that limit.
- **`truncated` covers policy narrowing as well as cap trimming.** A group whose members were narrowed by
  `member_select` reports `truncated: true`, because the label saw part of the group.
- **The sensitive-column entitlement check continues to cover the rule's bound columns** even though phase
  one no longer projects them — the same treatment the scan received in `scope-group-rule-reads`.
- **A source declaring no ordering key falls back to the existing single-phase read**, with the existing
  cost. Nothing silently changes behaviour for such a source.
- Not breaking: a rule that declares no `member_select` keeps today's semantics — newest N by the source's
  total order, returned ascending — and every existing scenario in the capability continues to hold.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enrichment-runtime`: the group-input assembly requirement gains the two-phase read and its granule
  -pruning obligation; the payload-caps requirement gains `member_select` as an input to the effective row
  cap and extends `truncated` to policy narrowing; a new requirement covers `member_select` declaration and
  validation; the sensitive re-check requirement is restated to bind on the rule's declared columns rather
  than on what a phase happens to project.

## Impact

- **API**: `POST /v1/rules` and `PUT /v1/rules/{id}` accept the optional `member_select` object; `GET
  /v1/rules` and `GET /v1/rules/{id}` return it. `POST /v1/rules/{id}/group-rows` keeps its request and
  response shape — `{rows[], truncated}` — with members still ascending in the source's total order.
- **Catalog schema**: one PostgreSQL migration (`V28`) adding a nullable `member_select` column to the rule
  table, stored like `ready_when`.
- **Code**: `GroupFetchService` (two-phase assembly), `RuleEntity` / rule DTOs and mapper,
  `RuleGovernanceValidator` (validation of `prefer_sql`, `order_by` and `limit`), `ScanSourceMetadata`
  (exposing the source's ordering key for point lookups). No change to `ScanService` or the write-back.
- **Enrichment Runner**: no change required — it already sends `limit`, which ADAS now caps. The new field
  appears in the rule sync feed, so the runner's tolerance of an unknown rule property must be confirmed
  before release.
- **Config**: no new keys. The existing group-fetch default and maximum row counts keep their meaning as
  the outer bound.
- **Consumers**: an enrichment such as `conversation_insights` needs this change; it intends
  `prefer_sql: "parent_deployment IS NULL"`, `order_by: number_request_messages desc`, `limit: 2`.
