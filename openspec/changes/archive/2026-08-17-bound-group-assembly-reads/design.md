## Context

`GroupFetchService.fetch` builds one query that projects the rule's bound input columns, filters on the
group key and the rule's `filter_sql`, orders by the cursor tuple descending, and applies
`LIMIT effectiveLimit + 1`. It executes through `StructuredQueryExecutor.executeScan`, which applies the
`final = 1` server setting. `trim` then applies the byte cap in Java over the rows already returned.

Everything expensive therefore happens before either cap can act. Three observations fix the shape of
the problem:

- `dial_usage_log` can hold millions of rows over dozens of daily partitions; `llm_call` rows carry tens of
  KB of `request_body` plus tens of KB of `response_body`, both `Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV)`.
- `chat_id` is **not** in the sort key `(project_id, deployment, request_time, trace_id, core_span_id)`,
  but it does carry `INDEX idx_chat_id chat_id TYPE bloom_filter(0.01) GRANULARITY 1`. Granule pruning by
  chat therefore works; small groups of a few hops assemble successfully.
- A group of a few hundred hops carrying tens of MB of bodies can fail with `Code: 241 MEMORY_LIMIT_EXCEEDED`
  against a server ceiling of a few GiB. The server can then stay pinned near that ceiling and a subsequent
  single-row body read fails too, so the failure is a shared-instance outage, not a per-request error.

The cost driver is the number of **rows whose bodies are read**, because each row's granule is read whole
(adaptive granularity, 10 MB default) and a group's rows are scattered across granules. The consuming
Enrichment Runner then applies a 60,000-token budget (~240 KB) to what it receives, so for a conversation
averaging a few hundred KB per hop only one or two members survive to the model. Reading 201 to send 1 is the waste
this change removes.

The design must work within a fixed server memory ceiling of a few GiB rather than assume more memory is
available.

## Goals / Non-Goals

**Goals:**

- Make a group assembly's read cost proportional to the members returned, not to the group's size.
- Let a rule declare which members bear on its label, since "newest N" is wrong for DIAL conversations.
- Preserve the endpoint's observable contract: same request and response shape, members ascending in the
  source's total order, `truncated` honest.
- Land entirely in ADAS — no Enrichment Runner release required.

**Non-Goals:**

- Changing the runner's token budget, its oldest-first trimming, or `GroupInputAssembler.truncate`'s
  head-keeping behaviour for a single oversized member. That remains a known runner-side edge.
- Removing `final = 1` from the read path. Deduplication of a `ReplacingMergeTree` source stays.
- Adding a ClickHouse index or migration. `idx_chat_id` already exists and is doing its job.
- Recovering a saturated ClickHouse instance. That is operational: `SYSTEM JEMALLOC PURGE`, `SYSTEM DROP MARK
  CACHE`, or a restart, plus a `mark_cache_size` far below the 5 GiB default.
- Enriching `turns` instead of `conversations`, or deriving insights from metadata rather than bodies.
  Both were considered and rejected by the consumer.

## Decisions

### Two phases, not one query with a subselect

Phase one selects the cheap columns and decides membership; phase two fetches bodies for the survivors.

*Rejected: a single query with the selection as a subquery.* jOOQ can render it, but ClickHouse would
still resolve the outer projection over the inner result set, and with `final = 1` the read is planned
before the inner `LIMIT` is known — which is the present behaviour. Two round trips make the bound
explicit and let phase one's result be inspected and capped in Java, where the byte-cap logic already
lives.

*Rejected: `optimize_move_to_prewhere_if_final = 1` as a one-line fix.* It would let the filter run in
`PREWHERE`, but it changes filtering semantics relative to the dedup collapse (a predicate evaluated
before FINAL can select a row version that FINAL would have replaced). It is safe for `dial_usage_log`
only because a re-ingested duplicate carries identical filter columns — an argument that holds for this
source and would silently not hold for another. It also does nothing about reading 201 members to use 2.

### Phase two addresses members by the source's full ordering key

The ordering key is the source's primary index, so a point lookup on all of its columns prunes to the
granule containing that row. All of its columns are cheap and available from phase one.

*Rejected: fetching by `event_id`.* This was the original instinct and it is wrong: `event_id` is neither
in the sort key nor indexed, so the `chat_id` bloom filter would select the same granules as today and the
read would not shrink. Recording it here because it looks obviously correct and is not.

*Rejected: adding a `request_time` window only.* Partition pruning to a single day can still leave tens of
thousands of rows of that day's bodies in range, which exceeds the budget on its own.

The identity column is carried **alongside** the tuple, and phase two is capped at one row per selected
member. The ordering key is what makes the lookup cheap, but it is not what makes it exact: only a
deduplicating engine keyed on that tuple guarantees it addresses at most one row, and a source may be a
plain `MergeTree` whose ordering key repeats (`dial_usage_log` is a `ReplacingMergeTree` read with
`final = 1`, so it is exact there — an API-created append-facet source need not be). Two members sharing a
tuple would otherwise be indistinguishable when the phases are merged, and each would receive whichever of
their bodies came back last: a quietly wrong group input rather than a failure. `identity_column` is
declared unique per row, so appending it makes the match exact while leaving every pruning term in place,
and the row cap keeps a source that breaks that promise from restoring an unbounded body read.

### Phase one does not project the columns `member_select` merely ranks by

The preference predicate and the ordering are evaluated by ClickHouse; neither requires its columns in the
result set, and the response contract does not carry them. Projecting them would read and discard a value
per member, and — worse — would return a body column to phase one for a policy that ranks by one, which is
the exact cost this change exists to remove. The entitlement check is unaffected: it binds to what the rule
declares, not to what a phase projects (see below).

The fallback for a source with no declared ordering key is the existing single-phase read. Such a source
cannot be point-looked-up at all, and silently returning fewer members would be worse than keeping the
old cost.

### `member_select` as `{prefer_sql, order_by, limit}`, stored like `ready_when`

A two-part policy — a preference with a fallback, plus an ordering — is what the data needs: the
history-carrying body is the chain entry (`parent_deployment IS NULL`) for roughly half of chats, and for the
rest the best proxy is the highest `number_request_messages`. Expressing it as one predicate would force
the caller to pick one or the other; expressing it as generic SQL keeps ADAS free of DIAL semantics.

`prefer_sql` reuses `FilterSqlValidator`, so it inherits the closed function catalog, the column
resolution, and the sensitive-column reattribution already proven on `filter_sql`. Persistence follows
`ready_when`: a nullable JSON-converted column on the rule table (migration `V28`).

*Rejected: a `member_order` string of raw SQL.* Ordering needs only column and direction; accepting an
expression would widen the grammar surface for no gain.

*Rejected: hardcoding the policy for `dial_usage_log`.* The catalog is generic by design and a
source-specific branch in the assembly path would be the first of its kind.

### The declared limit lowers the effective row cap rather than sitting beside it

`min(caller limit, configured cap, member_select.limit)` keeps one number governing the read, so a caller
sending the runner's default `limit: 200` cannot defeat a rule that declares `limit: 2`. This is why no
runner change is needed.

### `truncated` covers policy narrowing

A label computed from 2 of several hundred members is partial, and the provenance column exists so a consumer can
tell partial from complete. Collapsing both causes into one boolean keeps the wire contract stable.

*Rejected: a separate `narrowed` flag, or an enum.* It would be more precise and would break every
existing consumer of `{rows[], truncated}` for a distinction no consumer has asked for. Revisit if a
consumer needs to separate "the caps bit" from "the rule chose".

### Entitlement binds to the rule's declared columns, not to a phase's projection

`scope-group-rule-reads` already established this for the scan: removing a sensitive column from a
projection must not remove the entitlement requirement, or a non-entitled caller gains a read it should
not have. Phase one must therefore run the check over bindings, filter and `member_select` together,
before either read is issued.

## Risks / Trade-offs

- **The runner may reject an unknown rule property.** `member_select` will appear in the sync feed for
  every rule. If the runner's rule deserialization fails on unknown fields, shipping this breaks rule sync
  for all rules, not just group ones → verify the runner's Jackson configuration (and add a
  `@JsonIgnoreProperties` there first if needed) before release. This is the one cross-repo gate.
- **Two round trips add latency per group.** Phase one is a narrow, index-pruned read of a handful of
  columns; the second trip costs one more network round trip per group assembly, against a call that
  already makes a model request. Acceptable, and the endpoint is per-group rather than per-row.
- **A group whose members change between phases** could yield a phase-two point lookup that matches
  nothing. The safety lag makes this narrow but not impossible under re-ingest, which can raise
  `_ingested_at` for an already-ingested row → treat a member missing in phase two as excluded rather than
  as an error, and let `truncated` reflect the shortfall. A dead-lettered group would otherwise result from
  a benign race.
- **The runner's front-trim can undo the policy's choice.** The response stays ascending in the source's
  total order, and `GroupInputAssembler` applies its 60,000-token budget by dropping from the front. With the
  default policy those two agree — the front is the oldest member. With a declared `order_by` they need not:
  the highest-ranked member can be the older of the two returned, and the runner would drop exactly it. At
  a few hundred KB per hop, `limit: 2` can already exceed a ~240 KB budget, so this is the expected case for
  the motivating consumer, not a corner → set `limit` so the whole selection fits the caller's budget (`limit:
  1` for that consumer, or a runner-side change to respect the server's ranking, which is out of scope here).
- **`prefer_sql` widens the validated-SQL surface.** It is a second predicate a caller can attack with the
  same grammar as `filter_sql` → route it through the same validator and the same sensitive reattribution,
  and add no new function or operator.
- **Policy selection can pick a poor member.** `number_request_messages` is a good proxy for "carries the
  conversation" but an agentic sub-agent can accumulate a longer internal message list than the
  user-facing exchange → out of scope to solve here; the consumer's evaluator prompt already tells the
  model its input may be a subset, and `truncated` marks the row.
- **Existing group rules keep their cost.** A rule declaring no `member_select` still retains up to the
  configured 200 members, so the two-phase read bounds *which* rows are projected but not how many → the
  cost improvement for such rules is real but partial. Only a declared limit makes it proportional.
  `usage_request_summary` is a row-lane rule and is unaffected either way.

## Migration Plan

1. `V28` adds a nullable `member_select` column to the rule table. Nullable means every existing rule
   keeps today's behaviour with no backfill.
2. Deploy ADAS. Rule sync carries the new field; the runner gate above must be cleared first.
3. Verify against a running deployment with the motivating consumer: `conversation-insights-live` gains
   `prefer_sql: "parent_deployment IS NULL"`, `order_by: [{column: number_request_messages, direction:
   desc}]`, `limit: 2`, then `POST /v1/rules/{id}/group-rows` for a conversation of a few hundred hops must return 2
   members with `truncated: true` and must not exhaust ClickHouse memory.
4. Rollback is the `PUT /v1/rules/{id}` that removes `member_select`, then the previous ADAS image. The
   column can stay; it is inert when null.

A ClickHouse instance already saturated by the old read must be purged or restarted before step 3,
independently of this change.

## Open Questions

- ~~Does the Enrichment Runner tolerate an unknown rule field in the sync feed?~~ **Resolved:
  yes.** `JsonMapperConfiguration:37` disables `FAIL_ON_UNKNOWN_PROPERTIES` on the `@Primary` `JsonMapper`,
  and `AdasRegistryClient` parses `GET /v1/rules` with that mapper, so `member_select` is ignored by an
  older runner. No release gate, no runner change.
- ~~Should phase two batch its point lookups into one `WHERE (tuple) IN ((…),(…))` statement or issue one
  read per member?~~ **Resolved during implementation: neither — a disjunction of conjunctions**
  (`(a=… AND b=…) OR (a=… AND b=…)`), in one statement. It is the union of point ranges the key analysis
  prunes on, it is one round trip like the tuple-`IN`, and unlike row-value `IN` it does not depend on the
  dialect supporting row-value expressions. Per-member reads were rejected as N round trips for a default
  rule that keeps 200 members. The pruning claim was not measured against a running deployment, so the
  integration tests run it against a real ClickHouse instead.
- Selection resolves `prefer_sql` and its fallback in **one** read, not two: the predicate is projected as
  an internal `__prefer` verdict column and ranked first, so preferred members sort ahead of the rest, and
  the fallback is then "if the top member did not satisfy it, nobody did, so keep everyone". The `__signal`
  column on the scan is the precedent for a read-side helper column that is stripped before returning.
- Is `truncated: true` on a policy-narrowed group going to read as an anomaly to the consumers of
  `conversation_insights`, given it will be the normal case? Documentation problem rather than a design
  one, but worth confirming with that consumer.
