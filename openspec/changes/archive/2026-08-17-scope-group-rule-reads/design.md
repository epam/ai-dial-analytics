## Context

The group lane has two reads against the rule's source: the incremental **scan**, which dirty-marks
which groups changed, and the **group-input assembly**, which returns one group's members so the caller
can render a prompt. They were built as "the row lane, plus a group key" and "a bounded read of one
group" respectively, and each inherited a decision from that framing which is wrong once the members
carry bodies.

The trigger for fixing both now is the first real group rule: an LLM enrichment over `dial_usage_log`
with `request_body`/`response_body` bound, thousands of conversation groups, tens of thousands of
qualifying rows, and on the order of 100 KB of bodies per row. At that size the scan's projection is a transfer problem and the
assembly's retained end is a correctness problem.

## Goals / Non-Goals

**Goals**
- A group rule's scan page costs what a dirty-mark costs, not what a prompt costs.
- When a group is trimmed, the members that survive are the ones a label about the group's current
  state depends on.
- The sensitive-column posture is unchanged in both directions — no new exposure, no new refusal.
- No Enrichment Runner change, and no change of any kind for a row-grain rule.

**Non-Goals**
- Giving the scan a byte cap. It would be a second, differently-shaped bound on a page that this change
  makes small; if a future rule binds large columns on a *row* lane it can be reconsidered on its own.
- Changing the response ordering of `group-rows`. The ascending order is load-bearing (below).
- Paginating `group-rows`. A group is deliberately a single bounded read; `truncated` is the contract
  for partiality.
- Reconsidering the 200-row / 8 MB defaults. With the correct end retained they are adequate; raising
  them is a deployment decision, not a code one.

## Decisions

### 1. The projection drops the bindings; the entitlement check keeps them

The two sets stop being the same set. `ScanService` currently derives the guarded set from the
projection, so "what we return" and "what we are allowed to read" are one list. This change separates
them, exactly as the spec already does for `ready_when.signal`: a signal's raw columns are checked but
never projected, on the reasoning that the check governs what a rule *derives from*, not what the
response returns.

Applying the same rule to a group rule's bindings is what stops this being a security change. A rule
binding `request_body` still requires `FULL_ADMIN` to scan, because the rule still reads that column —
just at `group-rows` rather than at `scan`. Had the guarded set simply followed the projection, a
non-entitled caller would have gained the ability to dirty-mark a rule whose groups it is then refused,
which is both a posture change and an incoherent one.

### 2. Only `group` is exempted, and only from the projection

A row-grain rule's scanned page *is* its unit of work: `ChunkBuilder` maps each row through the input
bindings to build chunk payloads, so the bound columns must be present. The exemption is therefore
keyed on `trigger_kind == GROUP` and nothing else — not on column size, not on a `heavy` flag, not on
an opt-in field. A group rule has a second endpoint for the bodies; a row rule does not.

### 3. Sort direction and retained end are separate decisions

The tempting fix for the assembly is to flip the sort to descending. That would be wrong. The ascending
order is required by the spec ("ordered by the source's total order"), keeps the assembly consistent
with the `group_version` a caller dirty-marked from a scan page, and is what
`GroupInputAssembler` depends on when it trims from the front — a descending response would have the
runner discard the newest members, which is worse than today.

What must change is which members the *cap* retains. `LIMIT` on an ascending order silently means "keep
the oldest", and that is the only reason the head survives; nothing decided it. The implementation
therefore orders descending to *select* the newest window, then reverses before returning, so the
caller still receives ascending members. Selection order and response order are independent, and
conflating them is the original defect.

### 4. The byte cap accumulates from the newest end

The row cap and the byte cap must agree about which end they keep, or a group trimmed by bytes and a
group trimmed by rows would carry different halves of the conversation. Since the query now returns
newest-first, the existing accumulate-and-stop loop keeps the newest members with no change of shape —
it runs over the same list in the same direction, and the reverse at the end covers both caps.

This also improves the deliberate oversized-single-member case. ADAS returns a first member that alone
exceeds the byte cap rather than an empty group, so that "no members" stays distinguishable from "one
huge member". That member is now the newest one, which for a conversation is the hop carrying the
accumulated message history — the one worth keeping.

### 5. `truncated` semantics are untouched

`truncated: true` still means "either cap trimmed this group", and still says nothing about which end
was dropped or how much. It does not need to: the caller's use of it is to record that its label was
formed from a partial input. Adding a direction or a count would be a wire change for information no
consumer has asked for, and the runner already folds this flag into its own truncation signal.

### 6. Fix both in one change

They are one story — a group rule reading the wrong rows at the wrong end — and they are validated by
the same rollout. Splitting them would leave the plan carrying a `filter_sql` byte ceiling (added only
to keep scan pages small) through a release where the assembly fix has landed but the projection fix
has not, and that ceiling is precisely the mechanism that produces an unflagged partial label.

## Risks / Trade-offs

- **A caller debugging a group rule via `POST /scan` no longer sees the bodies.** That is the point, but
  it removes a convenient (if expensive) way to eyeball what a rule would read. Mitigation: `group-rows`
  answers the same question properly, for one group, with the caps applied — and the plan's dry-run step
  already uses it.
- **The assembly query changes shape.** Ordering descending over `(version, identity)` uses the same
  projected cursor expressions as the ascending form and the same `LIMIT`, so ClickHouse's work is
  comparable; the reverse is over at most `limit` rows already in memory. No index or sort-key
  assumption changes, because the assembly never relied on the table's physical order.
- **Groups already labelled under the old behaviour keep their labels.** Nothing recomputes them. For
  the pending rollout this is moot — no group rule has run — but a future deployment that already has
  group labels would need a backfill to benefit. Recorded rather than handled.
- **`truncated` still cannot distinguish which cap fired.** Unchanged, and out of scope, but it means
  the improvement here is invisible in the data: a truncated row looks the same before and after.

## Migration Plan

No migration. No schema change, no stored data, no wire-shape change, and no coordinated deploy: the
runner is unaffected either way, so ADAS can ship independently and a runner running against an older
or newer ADAS behaves identically.

The dependent conversation-insights enrichment plan is updated in the same change
because its `filter_sql` byte ceiling and reduced `batch_scan_limit` exist only to work around the scan
projection.

## Open Questions

None.
