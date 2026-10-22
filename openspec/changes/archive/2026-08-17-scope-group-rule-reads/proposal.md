## Why

A `group` rule's two reads are both mis-scoped, in ways that only surface at the scale the first
real group enrichment brings (a conversation-insights enrichment over thousands of
conversations in `dial_usage_log`).

**1. The dirty-mark scan ships the bodies it never reads.** `ScanService` pushes
`{filter columns} ∪ {bound input-binding columns} ∪ {grain} ∪ {cursor columns}` down as the
projection, with no group exemption — and the spec mandates exactly that. But a group rule's scan page
is consumed only by a dirty-mark, which reads the `group_by` value, the version column, and `__signal`.
The bound columns are read later and elsewhere, by `POST /v1/rules/{id}/group-rows`. So for a rule
binding `request_body`/`response_body`, every scan page carries megabytes per row that the caller
discards. Bodies can run to the order of 100 KB per qualifying row, so a 1000-row page is a ~100 MB
response — and unlike `group-rows`, the scan has **no byte cap at all**, only a row count. The rule
author's only lever is to shrink `batch_scan_limit`, or to add a `filter_sql` byte ceiling that
excludes whole rows from the group to keep pages small. The second is actively harmful: it is a
`WHERE` clause, not a cap, so the excluded rows never enter the group and the resulting label reports
`truncated: false` while having seen less than it appears to.

The spec already settled this exact question for `ready_when.signal` — "the page carries the verdict,
not the evidence". A group rule's page should likewise carry *which groups changed*, not their contents.

**2. The group-input assembly returns the oldest members when it trims.** `GroupFetchService` orders by
the source's total order ascending — correct, required, and relied on — but then applies `LIMIT` to
that ascending order, and its byte-cap loop accumulates from the head. Both caps therefore retain the
**start** of the group. The Enrichment Runner does the opposite with the same list: its token budget
drops oldest-first, on the documented reasoning that the newest members are the ones bearing on a
label about the group's current state. Both cannot be right, and today the layer that runs first wins:
a conversation of several hundred hops is delivered as its opening 200 hops, and the label describes the beginning of
the conversation as though it were the whole thing.

The spec does not adjudicate this. It requires the total order and says the response is "trimmed to fit
the cap" with `truncated: true`, but never states **which end survives** — so ADAS and the runner
filled an unstated contract in opposite directions. That gap is the root cause; pinning the retained
end is the fix.

Neither is a regression: both behaviours have been present since the group lane shipped, and no
group rule exercises them yet. Fixing them before the first group enrichment is created
is what keeps a backfilled label from having to be recomputed later.

## What Changes

- **A `group` rule's scan projection SHALL omit the rule's bound input-binding columns.** The page
  carries the group key, the cursor columns, the filter's columns and `__signal` — what a dirty-mark
  needs — and nothing that exists only to render a prompt. Row-grain (`on_ingest`, `schedule`) rules are
  untouched: their chunk work is built from the scanned row, so the bindings must be projected there.
- **The sensitive-column re-check keeps covering the bound columns even though a group rule no longer
  projects them.** This follows the precedent `ready_when.signal` already sets: the check governs what a
  rule *reads or derives from*, not what the response happens to return. Dropping the columns from the
  projection SHALL NOT become a way for a non-entitled caller to dirty-mark a rule whose groups it would
  be refused.
- **Both group-assembly caps SHALL retain the most recent members.** The row cap keeps the newest
  `limit` members; the byte cap accumulates from the newest and stops before exceeding. The response
  ordering is unchanged — members are still returned oldest-first in the source's total order — because
  which members qualify and which order they are returned in are separate decisions. `truncated`
  semantics are unchanged.
- **The oversized-single-member rule keeps its guarantee and gains a better outcome**: the member
  returned when one alone exceeds the byte cap is now the newest rather than the oldest.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enrichment-runtime`: the scan projection excludes a group rule's bound input-binding columns while
  the sensitive-column re-check continues to cover them; the group-input payload caps are defined to
  retain the most recent members, with the response ordering unchanged.

## Impact

**API** (behavioural, no shape change)
- `POST /v1/rules/{id}/scan` for a `group` rule: returned rows no longer carry the rule's bound columns.
  The response envelope, cursor, paging and `__signal` are unchanged. Row-grain rules are byte-for-byte
  unchanged.
- `POST /v1/rules/{id}/group-rows`: when either cap trims, the returned window is the tail of the group
  rather than its head. Shape, ordering and `truncated` are unchanged.

**Consumers**
- The Enrichment Runner needs **no change**. `GroupMarkBuilder` reads only `group_by`, the version
  column and `__signal` from a scan page, and `GroupInputAssembler` already assumes members arrive
  oldest-first and trims from the front — this change makes what it receives match what it assumes.

**Code**
- `service/enrichment/ScanService`: split the bound-binding column set out of the projection, add it to
  the projection only for a non-`GROUP` trigger, and add it unconditionally to the guarded set.
- `service/enrichment/GroupFetchService`: order the assembly query descending, cap on that order, and
  reverse before returning; accumulate the byte cap from the newest member.

**Docs**
- The dependent conversation-insights enrichment plan: drop the `request_body_bytes` ceiling that
  existed only to bound scan pages, restore `batch_scan_limit`, and retire the two recorded edges.

**Not affected**
- Cursor semantics, keyset paging, safety-lag, `final=1`, `__signal`, the write-back, Class-1
  materialize, the row lane in any respect, and every rule whose `trigger_kind` is not `group`.
