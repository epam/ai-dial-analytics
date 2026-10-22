## Context

An enrich pipeline has three trigger kinds today. Two are row grain and run the **same** runner scan
over the same `(_ingested_at, event_id)` cursor. They differ only in their clock:

| | `on_ingest` | `schedule` |
|---|---|---|
| Runner schedule | `Schedules.fixedDelay(advanced.scan_every)` | `CronSchedule(trigger.cron, UTC)` |
| Default | `SCAN_DEFAULT_CADENCE=30s`; `CLASS1_DEFAULT_CADENCE=5m` for `sql` | none, cron required |
| Next run | completion + delay (db-scheduler `FixedDelay`) | next cron instant after completion (`CronSchedule`) |

Neither one stacks runs. Both compute the next run from completion, so missed slots are skipped. The
runner's db-scheduler polls for due work every `DB_SCHEDULER_POLLING_INTERVAL=10s`, which is the
effective floor of either clock. Recurring scan and materialize tasks run at db-scheduler's default
priority 50, above every work item (`WRITE_BACK=20`, `LIVE=10`, `BACKFILL=1`).

For `group`, `scan_every` drives the **mark scan**, which records `last_activity_at` from the row's own
version column and never evaluates anything. The readiness sweep (`GROUP_SWEEP_INTERVAL=30s`) fires a
group when `now − last_activity_at > idle`. Nothing in the sweep checks that the mark scan has caught
up. So a scan cadence near `idle` evaluates a group early, and then again once its later rows are
marked.

`advanced.scan_every` is stored unvalidated (`PipelineMapper.toSpec` copies the string). It is ignored
on `schedule`, and a malformed value only shows up as the runner skipping that pipeline with a warning.

A representative registry: a few enabled `on_ingest` pipelines, all `sql`, all `scan_every: 60s`
(`turn-feedback-live`, `usage-client-identity-live`, `usage-request-baggage-live`), plus one `group`
(`session-insights-live`, `scan_every: 5m`, `idle: 30m`). Aggregate rollups are cron-staggered
by hand (`*/15`, `3/15`, `7/15`, `12/15`).

## Goals / Non-Goals

**Goals:**
- One row-grain trigger with one clock (`schedule` + `trigger.cron`). `on_ingest` and
  `advanced.scan_every` are gone from the wire and from storage.
- A sensible default so a UI or API author doesn't have to write a cron: every minute, spread across
  seconds.
- Migrate existing pipelines without losing Class-1 materialized positions or group runner state.

**Non-Goals:**
- Reducing end-to-end latency. Most of the ~10 min lag is blob ingest (`POLL_FINALIZATION_LAG`), before
  any pipeline runs.
- Event-driven triggering of any kind.
- Changing `aggregate` pipelines. Their cron stays required, and they never had `scan_every`.
- The admin UI and adas-mcp edits beyond what the contract forces (tracked as cross-repo tasks).

## Decisions

### D1. Retire `on_ingest` rather than alias it to `schedule`

An alias keeps the misleading name in every client and in the read path, and the project's rule is
read-old/write-new with no second vocabulary (`retired-fields-are-read-only` precedent). Writing it is a
**422** naming `schedule`, consistent with how `trigger.kind` already refuses unsupported values (a raw
string, not a binding enum). Rejected: hiding it in the UI only. That leaves the API, adas-mcp and the
compiled feed with three kinds, and the UI would have to translate.

### D2. Default cron `S * * * * *`, `S = MD5(name)[0..4] unsigned mod 60`, resolved and stored at write time

- **Every minute** is fast enough relative to ingest lag and cheap. An idle tick is one keyset probe.
- **Second derived from the name, not `0`**: cron is wall-clock aligned, and scans run ahead of all
  queued work (priority 50). With a fixed second, every defaulted pipeline would take the runner's
  threads at the same instant. The two `request_tags`-reading `sql` pipelines would also run their
  `INSERT … SELECT` concurrently on a ClickHouse server with a memory ceiling of a few GiB. Fixed delay used to
  spread them by accident; this spreads them on purpose. The impact on a small registry is minor (a few sql
  pipelines, a handful of threads), but the default is written once and inherited by every future pipeline.
- **Stored, not applied in the runner**: the declaration stays the single readable source of the
  cadence. A runner-side default (what `SCAN_DEFAULT_CADENCE` was) is invisible to the API and can
  drift between replicas' configs.
- **MD5 over `String.hashCode`/`hashtext`**: it must give the same answer in Java (write path) and in
  PostgreSQL (migration). `hashtext` is undocumented and version-dependent. In SQL, MD5 is
  `('x' || substr(md5(name), 1, 8))::bit(32)::bigint % 60`; in Java, `MessageDigest("MD5")`, first four
  bytes big-endian, `Integer.toUnsignedLong`, `% 60`. A unit test pins both against the same fixture
  names.
- Rejected: runner-side jitter on a `0 * * * * *` cron (the stored cron would no longer say when it
  runs); keeping fixed delay internally for "every minute" (two clocks again).

### D3. Retire `advanced.scan_every` entirely; the group mark scan gets a runner-config cadence

After D1, `scan_every` would only mean something for `group`. There its only useful direction is
*down* to a small value: raising it buys nothing, because readiness is measured from row time, and it
causes early-then-repeated LLM evaluations. A knob with one safe direction belongs in runner config:
`GROUP_SCAN_CADENCE` (default `1m`, paired runner change). Rejected: allowing `trigger.cron` on `group`
(brings back "one trigger, two clocks" under another name and breaks the cron-iff-`schedule`
invariant); keeping `scan_every` group-only (a per-pipeline knob for something nobody should tune).

### D4. In-place Flyway migration `V44`, no `generation` bump, no audit revision

- A `generation` bump runs `discardMarkOfOtherGeneration` and drops each Class-1 pipeline's
  materialized position. That stalls every dependent rollup until the next window re-records it
  (measured precedent: a cadence-only PATCH moved `request-shape-live`'s mark back ~4 weeks). V41
  already established "the deploy reshapes the projection, the `UPDATE` does not bump" for the same reason.
- **How the runner sees it without a bump**: `PipelineSyncService` reschedules when `cached == null`,
  and the cache is empty after a restart. The paired runner deploy always restarts, so every pipeline
  is rescheduled with its new `CronSchedule` on the first poll. No operator step is needed, **provided the
  paired runner change closes one gap** found in review. Today a failed reschedule is logged and skipped,
  but the pipeline is still cached at the same `generation`, so it is never retried. db-scheduler refuses
  `reschedule()` on a `picked` row, and a scan killed by the scale-to-0 step stays picked for about 4.5
  minutes, well past the first poll about 10s after start. Revived, it keeps its persisted `FixedDelay`
  (`task_data`) until some later bump. The paired runner change (its design D5) therefore reschedules when
  the stored schedule differs from the desired one, not on `generation` alone, and does not cache a pipeline
  whose reconcile failed. It also tests an instance that is picked at the first poll.
- **No `pipeline_aud` row**: Envers isn't running during Flyway, and a synthetic revision would claim an
  actor. The next real write records the new trigger. The migration header says so.
- **Mapping** (spec table): sub-minute and absent values become every minute; others round **up** to an
  even divisor so `*/m` fires at a constant interval (`*/7` would not). Unparseable values also
  become every minute. The migration `RAISE NOTICE`s each rewritten row (`name: old → new`) so the
  Flyway log records what happened.
- Duration parsing in SQL covers the forms Spring's `DurationStyle` accepts that appear in practice
  (`<n>ms|s|m|h|d`, ISO `PT…`). Anything else is "unparseable". Observed values are `60s` and `5m`.
- `pipeline_trigger_kind_check` is dropped and re-added as `IN ('SCHEDULE', 'GROUP')` *after* the
  `UPDATE`. `pipeline_cron_iff_schedule` already holds, because every rewritten row gains a cron.

### D5. Contract version 2 → 3, one exact-matched bump in two paired changes

The compiled projection loses an enum value and an `advanced` member, which is breaking for the runner
by `RunnerContract`'s own definition. `RunnerContractShapeTest` will fail until the golden list and
`VERSION_VALUE` move. The runner side is a separate OpenSpec change in `analytics-enrichment-runner`
(`retire-on-ingest-trigger`), which:
- drops the fixed-delay branch of `ScheduleFactory`;
- uses `GROUP_SCAN_CADENCE` as the group lane's mark-scan schedule;
- removes `SCAN_DEFAULT_CADENCE` / `CLASS1_DEFAULT_CADENCE`;
- keeps `TriggerKind.ON_INGEST` modelled but refused, so a stray value skips one pipeline instead of
  failing the whole feed parse.

### D6. `EnrichSpec.cadence` is deleted, not aliased

After V44 no stored spec carries the key. The primary (persistence) mapper is lenient, so a stray key
in a hand-edited row is ignored rather than breaking reads. The request record loses `scan_every`, and
the strict web mapper then answers 400 naming `advanced.scan_every`, with no new handler.

### D7. Historical `ON_INGEST` audit revisions are read as `SCHEDULE`, not rewritten

Found during apply. `pipeline_aud` keeps every pre-V44 revision, and Envers hydrates those into
`PipelineEntity`. Under `@Enumerated(STRING)`, removing `ON_INGEST` from the enum would make any
historical `on_ingest` revision fail to load, and `GET /v1/pipelines/{name}/revision/{revision}` would
answer 500. So `trigger_kind` is mapped with `TriggerKindConverter`: it writes the constant name
(byte-identical to before) and reads `ON_INGEST` back as `SCHEDULE`. Rejected alternatives:
- rewriting `pipeline_aud` in V44 (falsifies history);
- keeping `ON_INGEST` in the enum as deprecated (the second vocabulary leaks back into every `switch`).

A snapshot from before the migration reports `schedule` with no cron. That is the truth of what the row
said about its clock: nothing.

V44 also gives stored enrich `SCHEDULE` drafts without a cron the default. Otherwise a draft saved before
this change would still be refused at arming for a cron that new drafts never need.

## Risks / Trade-offs

- **[A `30s` pipeline becomes `1m`]** → intentional, and it rounds up. Sub-minute values are not
  expected in practice, and ingest lag dwarfs it. An author who wants faster can write `*/30 * * * * *` (6-field cron
  allows it), knowing the 10s polling floor.
- **[ADAS deployed, runner not yet]** → the contract guard makes the old runner refuse the feed (scaled
  to 0 in effect) instead of misreading `SCHEDULE` rows. The existing deploy-order guard covers it.
- **[Runner deployed, ADAS not yet]** → the new runner refuses an old ADAS's contract version 2. Same
  guard.
- **[Name-hash collisions]** → 60 buckets: two pipelines sharing a second is likely once there are ~10
  of them. That's acceptable; the goal is spreading, not exclusivity. An author can always declare a cron.
- **[MD5 implementations disagree]** → pinned by a test that computes the second in Java and via the
  migration's SQL expression over the same names against Testcontainers PostgreSQL.
- **[Stale group mark scan vs short `idle`]** → `GROUP_SCAN_CADENCE=1m` against a `10m` default idle is
  safe. Refusing `idle` below a multiple of the scan cadence is left open (below), because ADAS doesn't
  know the runner's configured cadence.
- **[Hand-edited rows]** → unparseable `scan_every` values degrade to every minute, logged by the
  migration.

## Migration Plan

1. Release ADAS (V44, contract 3) and the runner change (contract 3) together.
2. Deploy in either order. Until both are up, the runner refuses the feed (guard).
3. On first runner poll after restart, every pipeline is rescheduled from its compiled cron. Verify:
   - existing `sql` pipelines keep `materialized_through_*` (no reset);
   - their `trigger.cron` reads `S * * * * *` with distinct-ish seconds;
   - `session-insights-live` keeps producing labels and its `group_state` is untouched.
4. Rollback is not a requirement (project decision). Re-adding `ON_INGEST` would be a forward migration.

Verify locally first (Testcontainers + the local runner harness), not gated on a shared environment.

## Open Questions

- **30s preset in the UI?** No backend impact (`*/30 * * * * *` is already valid). This is a UI decision.
  The recommendation is no, because of the 10s polling floor and minute-scale ingest lag.
- **Refuse `ready_when.idle` below `k × GROUP_SCAN_CADENCE`?** ADAS doesn't know the runner's cadence. The
  options are a fixed floor in ADAS (e.g. `idle ≥ 5m`) or a runner-side warning. This is deferred and not
  required for this change.
- **Rounding policy**: the spec chooses round-*up* to an even divisor. Confirm before apply whether any
  deployment has non-60s values. Check with
  `GET /v1/pipelines?kind=enrich` there before deploying.
