## Context

An enrichment is already keyed on an arbitrary declared source column with `1:0..1` cardinality, and
`CatalogQueryService.buildModel` joins it back with a bare `sourceKey.eq(enrichmentKey)` — no date
condition, no partition condition, no assumption that the grain is the source's ordering key. A
`chat_id`-grained enrichment over `dial_usage_log` therefore already stores and reads correctly today.
The group capability is not a new table kind, a new cardinality, or a new join; it is a **trigger
kind plus two governed reads**.

The hard part is not grain, it is **completeness**: "this group changed" and "this group is finished"
are different questions, and answering them naively (recompute every group on every tick, or wait a
fixed delay) is either unaffordable or wrong. The design splits them — dirty-marking rides the row
scan and costs proportional to new rows, while readiness is a declarative predicate ADAS validates and
the runner evaluates. Only the declaration and the reads are ADAS's; the per-group state machine is
the runner's.

## Goals / Non-Goals

**Goals:**

- Let a rule declare "one enrichment row per group key, computed when the group is ready".
- Keep readiness governance static and server-side, over declared columns, in the same bounded
  grammar as a filter.
- Let the runner discover changed groups without scanning groups, and assemble a group's input without
  naming a table, a column set, or a filter.
- Apply the sensitive-column rule at the point group members are read, and bound the response so a
  body-carrying group cannot blow up ADAS or the runner.

**Non-Goals:**

- The runner's dirty-group state, readiness sweep, group work-item keying, or cost-ceiling
  enforcement — all runner-side, driven by what this change exposes.
- Aggregation of any kind. A group enrichment is a *label per group*, not a materialized aggregate.
- `1:N` group values, map-reduce over long groups, or multi-writer column coalescing.
- Any system content. The conversations/turns datasets are a separate change.

## Decisions

**D1 — Reuse the `1:0..1` grain model and the existing keyed-lookup join; add no table kind.** The
join condition is already grain-agnostic, so a group enrichment is structurally identical to a row
enrichment whose grain happens to be a coarser column. This is what makes the capability small.
*Alternative rejected:* a distinct "group enrichment" table type with its own join semantics — it
would fork the query path for no behavioural difference.

**D2 — Group-ness lives on the *rule*, not on the enrichment.** `enrichment_grain` carries only
`(table_name, grain_key, cardinality)` and gains nothing here. A grain key is just a column; whether
it is "a row" or "a group" is a statement about how the rule fires. Consequence, taken deliberately:
the gate **cannot** check "reject `on_ingest` on a group-grain target", because there is no
group-grain marker to check. What it can and does enforce is the symmetric statement —
`group_by`/`ready_when` are valid **only** on a `GROUP` trigger, and a `GROUP` trigger requires
`group_by` to equal the grain. *Alternative rejected:* adding a `grain_kind` column to
`enrichment_grain` — it would duplicate information the rule already carries and would have to be kept
consistent with it.

**D3 — `group_by` must equal the target enrichment's grain key.** The enrichment is physically keyed
on its grain and collapses by it, so a rule grouping by anything else would write many groups' labels
onto one key and lose all but the last. The check is exact equality against
`enrichment_grain.grain_key`. *Alternative rejected:* deriving `group_by` from the grain and not
accepting it at all — the explicit field makes the rule self-describing for the sync feed and makes
the mismatch a loud registration error rather than an implicit coupling.

**D4 — `ready_when` is a declaration ADAS validates and the runner evaluates.** ADAS parses
`signal` under the same Calcite front end as a filter (bounded grammar, boolean, declared columns,
entitlement-checked) and validates the duration and ceiling fields, then stores and serves it. The
runner evaluates the signal over the columns the scan projected for it. Splitting it this way is what
lets dirty-marking cost `O(new rows)`: ADAS never has to know which groups exist. *Alternative
rejected:* ADAS computing readiness — it would require ADAS to hold per-group state, which is exactly
the state the runner already owns for its queue and cursors.

**D5 — At least one of `signal`/`idle`/`max_staleness` is required.** A `ready_when` with only a
`cost_ceiling`, or an empty one, declares a group that is dirty forever and never ready — a rule that
silently does nothing. Rejecting it at registration turns a silent no-op into a 422. *Alternative
rejected:* defaulting to some idle period — a guessed default that silently decides when someone's
data is summarised is worse than an explicit error.

**D6 — Durations accept both `10m` and `PT10M`.** The codebase's configuration convention is the
short form (`5s`), and JSON APIs conventionally use ISO-8601. Spring's `DurationStyle.detectAndParse`
accepts both, so neither audience is surprised. Stored as the author wrote it, validated at
registration.

**D7 — Extend the existing scan rather than adding a dirty-mark endpoint.** For a `GROUP` rule the
projection gains `group_by` and the `signal` columns; nothing else changes — same URL, same cursor,
same safety-lag, same `final=1`, same sensitive re-check. The activity timestamp needs no work at all:
the scan already projects its version and identity columns under their own names alongside the
internal cursor aliases. *Alternative rejected:* a separate `dirty-mark` endpoint — it would duplicate
the entire keyset/cursor/lag apparatus to change a projection.

**D8 — The server owns group identity; `groups:fetch` takes a key, not a query.** The runner posts
`{groupKey}` and ADAS resolves the source, the membership filter, the ordering, and the projection
from the stored rule. The runner never names a table or a column set and never learns where the group
lives. This is the same trust boundary as the scan and the write-back. *Alternative rejected:* letting
the runner request specific columns — it would move projection governance to the client.

**D9 — `groups:fetch` applies the same safety-lag as the scan.** The group version the runner
dirty-marked was computed from lagged rows, so assembling from unlagged rows could include a member
the runner has not accounted for and produce a label whose `group_version` understates its input.
Consistency between the two reads matters more than assembling the newest possible group.
*Alternative rejected:* no lag on assembly — it makes the group's contents and its version disagree.

**D10 — Sensitive columns are gated by entitlement, not stripped from the projection.** Stripping
would silently produce a group input missing the very column the rule was written to read, and the
label would be quietly wrong. Rejecting with 403 makes the misconfiguration loud. This mirrors the
scan's existing egress behaviour, and the shared `SensitiveProjectionGuard` is extracted so the two
cannot diverge. *Alternative rejected:* returning the group with sensitive columns omitted — quiet
wrongness.

**D11 — Cap by rows **and** response bytes.** A group of 200 turns each carrying a request and
response body is megabytes; a row cap alone bounds nothing that matters. The service accumulates the
serialized size and stops at the byte cap, setting `truncated=true` if either cap trimmed the group.
The runner applies its own token budget on top. *Alternative rejected:* a row cap alone — it leaves
the actual failure mode (memory and payload size) unbounded.

**D12 — An unknown or empty group is HTTP 200 with `rows: []`, never an error.** Groups are discovered
from a row scan and evicted by the runner on a TTL; a group that has aged out of the source's
retention, or was filtered away by the membership filter, is a normal race, not a caller error. A 404
would push retry logic into the runner for a condition it cannot fix. *Alternative rejected:* 404
`unknown_group` — it conflates "you asked about nothing" with "you asked wrongly".

**D13 — `groups:fetch` is authorized exactly like the scan.** Same `RuleAuthorizer`, same
target-enrichment grant, same `none`-mode bypass, then the sensitive re-check. It is a rule-scoped
read of the same source the scan reads, so a caller that may scan a rule may assemble its groups.

## Risks / Trade-offs

- **Group members carrying bodies blow up the `groups:fetch` response** → row and byte caps plus
  `truncated`; the runner trims further to its token budget and records `truncated` on the row's
  provenance so a partial result is identifiable downstream.
- **A `chat_id`-grained enrichment on `dial_usage_log` is a non-ordering-key grain, so the source side
  of the join full-scans** → accepted for the capability fixture; `chat_id` carries a bloom index, and
  the conversations change re-parents onto a spine whose ordering key *is* `chat_id`, which is one of
  its motivations.
- **The gate cannot reject `on_ingest` on a "group-grain" target** (D2) → mitigated by the symmetric
  check; a rule that wants group semantics must declare them, and declaring them on the wrong trigger
  is rejected.
- **A `ready_when.signal` that is never true makes a group depend entirely on `idle`/`max_staleness`**
  → not detectable statically; D5 at least guarantees some condition exists.
- **Dirty-group state growth at high group-key cardinality** → runner-side concern; bounded there by a
  TTL tied to `idle` and source retention. ADAS holds no per-group state by construction (D4).

## Migration Plan

1. `V15__group_trigger.sql`: add `group_by` and `ready_when`, relax the `trigger_kind` CHECK to
   `('ON_INGEST','SCHEDULE','GROUP')`, add `rule_group_by_iff_group`. Existing rows are all
   `ON_INGEST`/`SCHEDULE` with `group_by IS NULL`, so both CHECKs hold without a backfill.
   `rule_cron_iff_schedule` is left alone — it already forces `trigger_cron IS NULL` for `GROUP`.
2. Registry (`TriggerKind`, `ReadyWhen`, DTOs, governance), then the two reads.
3. **Rollback:** the migration only widens the trigger CHECK and adds two nullable columns, so the
   prior code runs against the new schema unless a `GROUP` rule exists; delete those rows first.

## Open Questions

- The row and byte caps' defaults are a starting point; the right values depend on real body sizes and
  the runner's token budget, and should be measured during the runner's group milestones.
- `group_version` semantics (`max(version)` vs `count + max`) are a runner-side decision; ADAS exposes
  the raw activity timestamp either way and takes no position.
