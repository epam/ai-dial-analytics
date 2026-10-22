## Context

`ClickHouseFailures.isUnavailable` classifies code 241 as resource exhaustion, so every caller wraps it in
`ClickHouseUnavailableException` → `503 clickhouse_unavailable` — at `ClickHouseClientWrapper.java:69-70`
for reads and `ClickHouseInsertSelectExecutor.java:74-78` for `INSERT … SELECT`. A call site that attached
a budget then catches that and asks `ClickHouseBudget.translate` to re-read the cause chain; finding 241,
it **downgrades** the 503 to a 422 naming our ceiling (`:147-151`). Three budgets share that one method —
`QueryBudget`, `PreviewBudget`, `PipelineReadBudget` — so the decision is already made in one place.

That downgrade is right for one of the two conditions 241 covers and wrong for the other. Under node-wide
memory pressure it can be wrong for every refusal in a burst and right for none.

The shipped spec currently *mandates* the wrong behaviour (`enrichment-runtime:1239-1242`: the budget's
refusal "SHALL take precedence"), which is why this change carries a spec delta rather than being a pure
defect fix. On the query surface the opposite is true: `structured-query:1801-1804` already requires that a
statement failing "for a reason other than the budget's ceilings" keep its own mapping, so there today's
code is already outside its spec.

## Goals / Non-Goals

**Goals:**

- A node-wide exhaustion is retryable by the status it returns, so a first-party caller stops converting
  node contention into permanent dead-letters.
- The per-query ceiling stays a deterministic 422 — nothing that is genuinely our ceiling becomes a retry
  loop.
- A refusal ADAS calls deterministic costs one execution, not four.
- The narrowing refusal message stops describing retries that did not happen.

**Non-Goals:**

- Distinguishing a **per-user** memory breach. On 25.3 and 26.3 it is textually identical to a per-query
  breach; it stays a 422 and the spec records the limitation rather than implying it is solved.
- Any change to `ClickHouseFailures`' code-only classification. It stays code-keyed; only the budget's
  *narrowing* of one code reads a message.
- Restoring narrowing for a timeout-refused write. That needs reserved budget headroom and contradicts the
  call-bounded rule at `enrichment-runtime:1175-1180` which exists to prevent a retry livelock. It
  stays deferred, now with the reason recorded in the spec.
- Any runner change. See the cross-service note below.

## Decisions

**Attribute from the message, in `ClickHouseBudget.translate`, for code 241 only.** The alternative —
doing it in `PipelineReadBudget` so only the runner-facing path changes — is rejected on two grounds.
Three copies of a string matcher is precisely the defect `ClickHouseFailures` was created to remove ("five
folded into one, which is the only way a code added here reaches every path"), and a ClickHouse rename
would then need three edits and would half-land. And the misreport is not pipeline-specific: an
interactive caller whose query dies in a node-wide OOM is currently told "this query exceeded the 2GB
memory ceiling", a confident instruction to do work that would not have helped.

**The matcher: identify our own ceiling, and treat everything else as not ours.** Match
`Query memory limit exceeded` anchored immediately after the driver's `DB::Exception: ` prefix; anything
else carrying code 241 is reported 503.

**This replaces a `(total)` deny-list, which measurement showed would never have matched.** Run against
the ClickHouse this suite pins (26.3.21.7), the two forms are:

```
Query memory limit exceeded: would use 9.64 MiB (attempt to allocate chunk of 5.00 MiB), maximum: 1.00 KiB
User memory limit exceeded: would use 5.64 MiB (attempt to allocate chunk of 5.00 MiB), maximum: 1.00 KiB.
  OvercommitTracker decision: Query was selected to stop by OvercommitTracker
```

Three corrections to the pre-implementation analysis, all first-hand rather than from a `strings` dump:

- **Tracker descriptions are bare words, not parenthesised tokens.** There is no `(total)` and no
  `(for query)` on 26.3 — the `{}{}` placeholders render as `Query `/`User ` plus `memory limit`. A
  `(total)` matcher would have compiled, passed review, and been a no-op.
- **A per-user breach is distinguishable after all**, so the "textually identical, stays a 422" limitation
  written into the first draft of this design was wrong and is withdrawn. It now resolves to 503, which is
  the correct answer: ADAS never sets `max_memory_usage_for_user`, so that ceiling binding at all is an
  environmental fact about concurrent load.
- **The overcommit tail co-occurs with the user breach** rather than being an independent transient
  marker, so it needs no handling of its own.

**Why an allow-list is the right shape, beyond being the one that works.** The service sets exactly one
memory ceiling, so "is this our ceiling" has a single positive answer while "is this somebody else's" has
an open-ended set of them. An allow-list therefore covers node-wide, per-user and any limit a future
server version introduces, without enumerating them — and its unmatched branch *is* the 503 default
decided below, so the fallback falls out of the structure instead of needing a deny-list kept exhaustive
to stay correct. It is also less code: no marker set, no overcommit special case, and no second test
container.

**The residual risk, stated plainly.** Only 26.3 is machine-verified, and `docs/clickhouse-server.md`
claims 25.3–26.3. If 25.3 words the per-query form differently, the allow-list reports our *own*
deterministic ceiling as 503 — a retry loop, the direction this change exists to avoid. It is accepted
rather than hedged with a second, unverified spelling: an untested alternative branch is indistinguishable
from a guess, and the honest mitigation is that task 1.1 pins the spelling against whatever server the
suite runs, so moving the tested image to 25.3 would fail the build rather than degrade silently. Verify
before deploying to a 25.3 node.

For the record, the format strings across the three images (read with `strings`, no container started),
which is what made the churn visible even though it identified the descriptions wrongly:

| image | `MEMORY_LIMIT_EXCEEDED` format | tracker descriptions |
|---|---|---|
| 24.8 | `Memory limit{}{} exceeded: would use {} (attempt to allocate chunk of {} bytes), maximum: {}.{}{}` | `(total)`, `(for query)`, `(for user)` |
| 25.3 | `{}{} exceeded: would use {} (attempt to allocate chunk of {} bytes){}{}, maximum: {}.{}{}` | `(total)` only |
| 26.3 | `{}{} exceeded: would use {} (attempt to allocate chunk of {}){}{}, maximum: {}.{}{}` | `(total)` only |

Two consequences survive that table, and one caution:

- **Only the `{}{}` head is worth matching.** Everything after `exceeded:` — the sizes, the chunk clause,
  the trailing context — demonstrably churns between 25.3 and 26.3, so no matcher may depend on it. The
  description and the word `exceeded` are the whole of the stable part.
- **Anchor the match.** `(in query: {})` is a literal in 26.3, so a 241 may be able to embed the caller's
  own statement text. Anchoring the description immediately after the driver's `DB::Exception: ` prefix
  makes an embedded `Query memory limit exceeded` in a caller-supplied alias unable to influence the
  decision. `String#startsWith(prefix, offset)` does this exactly, with no regex and no scanning.
- **Treat the `strings` evidence as version drift only.** It identified the descriptions wrongly, so what
  the table is good for is knowing that the *surrounding sentence* moves — not for deciding what to match.

**An unattributable 241 defaults to 503, not to today's 422.** This reverses the direction first proposed
and is the load-bearing decision:

- The outer shipped rule (`enrichment-runtime:1227`) already says a resource failure *is* 503. The 422 is a
  narrow exception carved out of it, and the conservative direction for an exception is to require proof.
  `ClickHouseFailures`' "unrecognised stays a rejection" principle is about an unrecognised **code** —
  mostly genuine statement defects. Here the code is already known to mean exhaustion; only the attribution
  is unknown.
- **Testability is asymmetric.** The per-query form is cheap to provoke against a real server
  (`QueryBudgetIntegrationTest` already does it at a 1KB ceiling), so a pin on it stays green cheaply and a
  rename fails the build. The node-wide form is awkward to provoke. Put the default on the side that
  *cannot* be pinned, because that is the side that will drift silently.
- **Blast radius is asymmetric.** A 503 loop is loud — 503 rate and runner retry metrics, actionable in
  minutes. A 4xx dead-letter is silent and destroys work needing manual replay.
- Two concrete cases land in the unattributable bucket and settle it: the driver renders an unfindable
  marker as literally `<Unreadable error message>`, and a marker straddling its 1024-byte read buffer
  yields a truncated message. Both are 241s with no usable text.

**`ServerException.isRetryable()` is not the discriminator.** Its
`discoverIsRetryable(code, message, transportProtocolCode)` accepts the message and never reads it — it
switches on the code alone and returns true for 241 unconditionally. It is `isResourceExhaustion` with a
different code list.

**Stop the driver retrying a server-side refusal (separable).** Reading `isRetryable` turned up that it is
already wired to behaviour nobody chose: `HttpAPIClientHelper.shouldRetry` is
`se.isRetryable() && retryCauses.contains(ServerRetryable)`, `ClientConfigProperties` defaults to
`retry=3` with `ServerRetryable` among the fault causes, and `ClickHouseClientWrapper.buildClient`
(`:91-99`) overrides neither. So **every 241 today is executed up to four times before ADAS sees it** —
including the deterministic per-query ceiling — which during an OOM is a 4× amplifier on the pressure being
refused, and is invisible to `ClickHouseBudget.remainingTime`'s wall-clock accounting. Fix by setting max
retries to 0 on the read client, or by dropping `ServerRetryable` from the fault causes.

This is **separable from the rest of the change** and is called out as such: it touches every statement,
not only budgeted ones. It is included because it is the same mechanism and because shipping a fix that
makes refusals honest while leaving a 4× amplifier in place would undercut the change's own purpose. Split
it into its own change if the review prefers a narrower blast radius.

**Keep the literals out of the spec.** The markers are the part that churns across 25.3/26.3; a spec naming
them goes stale on a rename. The spec states *attribution*, the code owns the strings, and
`docs/clickhouse-server.md` records which versions they were verified against.

## Risks / Trade-offs

- **A per-user breach is still a 422** → unavoidable on 25.3+ where the text is identical. ADAS runs as one
  ClickHouse user, so contention among ADAS's own concurrent queries falls in the gap. Stated in the spec
  and in the proposal rather than papered over.
- **Message matching contradicts a documented principle** (`ClickHouseFailures.java:37-43`) → bounded to
  one code and one question, defaulting to the outer rule, and pinned by a real-server test so a rename
  fails the build rather than silently reverting behaviour. Without that pin this would be theatre, exactly
  as the deferral list says of the `assumeNotNull` wrapper.
- **The narrowing control flow changes.** `MaterializeService.narrowing` (`:387`) catches only
  `PipelineReadBudgetExceededException`, so once a node-wide 241 propagates as
  `ClickHouseUnavailableException` the write no longer halves its window — it fails on the first refusal.
  That is the desired outcome, but it silently changes how many statements a materialize issues under
  pressure → needs a test asserting the probe is called **zero** times, and it is why the spec gained a
  sentence rather than leaving it implied.
- **A caller keying on 422 for this condition breaks** → in the direction `docs/sql-reference.md` already
  documents for an outage, and the `503 clickhouse_unavailable` code already exists. Recorded as a breaking
  change in the proposal and belongs in the release notes beside the existing "ClickHouse resource
  exhaustion → 503 (was 400)" row.
- **The node-wide integration pin may not be buildable.** 26.3 self-adjusts a low
  `max_server_memory_usage` ("Lowered setting … because the system has little few memory"), so the
  container may need a low-but-viable value plus a deliberately heavy query → if it proves unworkable, keep
  the marker set in one named constant with a "verified against 25.3 and 26.3" note and accept that only
  the per-query half is machine-pinned. That outcome is itself an argument for the 503 default.

## Migration Plan

No migration, no config key, no new credential. `RunnerContract.VERSION` is **not** bumped: no request or
response shape moves, only one failure's status.

**Cross-service: this closes runner item R1 with no runner code change**, and that is the whole point of
fixing the status rather than publishing a capacity-code vocabulary. The runner's own spec
(`resilience-and-dlq:268-272`) *forbids* branching on an upstream `error` machine code — "doing so
accurately requires the upstream to commit to a stable set of capacity codes, which is a contract between
the two services" — and accepts dead-lettering a capacity refusal "until that contract exists". Fixing the
status satisfies that clause without creating the contract, so the runner's existing 5xx-transient /
4xx-permanent rule becomes correct unchanged. The follow-up owed on the runner side is documentation only:
narrow the prose at `resilience-and-dlq:250-253` and the javadoc at `worker/RetryableStatus.java:14-20`,
which currently concede a flaw that will no longer exist.

Deploy order is unconstrained: a runner running against an un-upgraded ADAS behaves exactly as today, and
a runner running against an upgraded ADAS retries what it used to dead-letter.

## Open Questions

- **Should the 159 (timeout) arm get the same attribution treatment?** A server-side `max_execution_time`
  breach is ours, but a node under load can also blow a timeout that a quiet node would have met, which is
  arguably transient. Left alone in this change because no evidence was gathered for it and because
  guessing at a second ambiguous code while fixing the first is how the fix becomes unreviewable.
- **Does a 241 message ever embed the caller's statement?** `(in query: {})` exists as a literal in 26.3
  but it was not established whether the memory tracker's throw path includes it. The anchored match makes
  the answer not matter, which is why it is anchored rather than investigated first.
