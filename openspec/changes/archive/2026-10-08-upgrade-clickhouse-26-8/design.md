## Context

See proposal.md — Why. The 26.3 upgrade (archived `2026-08-25-upgrade-clickhouse-26`) established the
pattern this change follows: no `src/main` change, the verified pin moves, the contract names the
range, and every operator claim is re-derived from the server (`system.settings_changes`,
`system.settings`, `system.merge_tree_settings`, part files on disk) rather than from release notes.

What constrains this change:

- **The suite already passes on 26.8.** 3,602 of 3,603 tests green on 26.8.15.10 before any edit; the
  one failure is `SHOW CREATE` text. So the design question is not "how to make ADAS run on 26.8" but
  "what moved between 26.3 and 26.8 that the suite cannot see" — rollback, operator claims, and
  behaviour behind flags the suite leaves at their default.
- **A migration file is immutable, comments included** (Flyway checksums the whole file; measured in
  the 26.3 change). Version statements live in docs and specs only.
- **Testcontainers starts empty.** The suite proves fresh provisioning on 26.8, not reading parts
  written by 26.3. The settings sweep stands in for that here; an in-place upgrade of a running deployment is the real gate.
- **Provenance comments are records.** A comment saying "measured on 26.3.24" is only rewritten when
  the measurement was re-run on 26.8 in this change.

## Goals / Non-Goals

**Goals:**

- The build exercises 26.8; `local_env` matches it.
- Every stated contract names the verified range (25.3 floor, 26.8 verified).
- Everything that changed its default between 26.4 and 26.8 is classified: reaches ADAS / reaches a
  caller's own SQL / does not reach ADAS — with the measurement, not the reasoning.
- The rollback note tells an operator what 26.8 → 26.3 costs and how to avoid it.

**Non-Goals:**

- Raising the floor. Nothing needs 26.x; the `data-sync` requirement forbids raising it for having
  verified a newer line.
- Bumping `clickhouse-java`. The Flyway path's `async_insert` insulation depends on the pinned driver
  (`build.gradle` note); a driver bump is its own change.
- Using any 26.4–26.8 feature (AI functions settings, `ie_join`, adaptive aggregator, text indexes).
- An in-place upgrade over 26.3-written data in this change. The 26.3 change did that hop on
  `local_env` with real rows; this change records the settings evidence and leaves the data hop to the deployment's own upgrade.

## Decisions

**D1 — Fix the `SHOW CREATE` assertion with a pattern, not by normalising the text.** 26.8 prints the
sort key as the migration spelled it, `ORDER BY (event_id)`; 26.3 normalised it to `ORDER BY event_id`.
The test's point is "event_id alone", so it matches `ORDER BY \(?event_id\)?` followed by neither a
word character nor a comma. *Rejected:* stripping parentheses from the DDL before asserting (hides the
very spelling difference a future reader will want to see); asserting on `system.tables.sorting_key`
(changes what the test proves — it pins the DDL text operators read).

**D2 — Do not pin `analyzer_compatibility_apply_final_to_all_joined_tables`.** Its default moved
`1` → `0` at 26.6, and the FINAL keyword's scope visibly changed (measured both ways on scratch tables).
ADAS does not need either value: `FinalTables` puts `FINAL` on the source `FROM` and on every enrichment
join itself, the pipeline's internal reads use `SETTINGS final = 1` (deduplicates every table on both
servers — measured), and a composite statement renders each entity reference as its own derived table
(`from (select …) "t0" join (select …) "t1"`), so a keyword in one operand's body cannot spill onto the
other on 26.3 either. `FinalQueryModifierIntegrationTest.finalMarksOneReferenceOfJoin` passes on both
images for exactly this reason. *Rejected:* pinning the setting to `0` on every user-facing read
(would make the service's correctness depend on a compatibility setting that 26.3 does not know —
`UNKNOWN_SETTING` on 26.3.27.3 — and buys nothing the rendering does not already guarantee). The spec
records the server's change and the rendering's independence from it.

**D3 — Recommend the rollback mitigation at server level, not per table.** `packed_skip_index_max_bytes`
(`0` → `1 MiB` at 26.8) is unknown to 26.3. `ALTER TABLE … MODIFY SETTING` would write it into the table
definition, which 26.3 then cannot load — turning a soft door (inert indexes until re-merge) into a hard
one (table missing). The `compatibility` default profile or `<merge_tree>` in `config.d` keeps the
layout classic without touching table metadata; both verified at the part-file level on 26.8.15.10
(`skp_idx_bf.idx` + `.cmrk4` written instead of `skp_idx.packed`). ADAS does not set it: it is a rollback
preference, not a correctness requirement, and the 26.3 change decided ADAS follows server defaults
unless a shipped guarantee depends on the value.

**D4 — Say what `compatibility = 26.3` reverts, measured.** On 26.8.15.10 the profile also turns auto
statistics off (`auto_statistics_types` → empty; 26.8 otherwise writes `statistics.packed` into every
part), reverts the lenient string-to-`DateTime` parsing 26.5 made default, and re-arms the older FINAL
keyword scoping. The 26.3 change's lesson holds: the profile is not scoped to the part format.

**D5 — Leave the serialization floors as they are.** `string_serialization_version`,
`serialization_info_version`, `object_serialization_version`, `object_shared_data_serialization_version`
are identical on 26.3.27.3 and 26.8.15.10 and `system.settings_changes` lists no change for them in
26.4–26.8. The 25.10 / 25.12 doors are the only data-level ones in the range.

**D6 — Classify, do not patch, the caller-visible default moves.** `cast_string_to_date_time_mode` /
`date_time_input_format` → `best_effort` (26.5) makes `CAST('02/01/2026 03:04:05' AS DateTime)` parse
where 26.3 refused it; `allow_rank_dense_rank_arguments` → `0` (26.5) refuses `rank(1)`. ADAS inserts
canonical timestamp strings, renders `rank()` bare, and renders no string-to-timestamp cast of its own;
a caller's own SQL that does either sees ClickHouse's behaviour on the server it runs against, which is
what the fidelity goal asks for. Nothing to pin, nothing to spec.

**D8 — Treat the AI functions as the one capability above the floor, and leave their settings to their
own change.** `system.functions` lists the eight `ai*` functions on 26.8.15.10 and none on 26.3.27.3, so
the AI query functions capability (a separate change, which already pins its
suite to 26.8) cannot run below 26.8 and nothing else in ADAS needs it; the `data-sync` requirement says
so generically (optional, off by default, names its own floor) and names the instance. The 26.8 defaults
that moved in this family — `ai_function_allow_insecure_endpoint` `1` → `0` (a plain-`http` endpoint on a
non-loopback host is refused), `ai_function_max_retries` `0` → `1`, `ai_function_max_input_tokens_per_query`
`1000000` → `0` and `ai_function_max_output_tokens_per_query` `500000` → `0` (`0` disables the cap), and
`allow_experimental_ai_functions` now "obsolete, does nothing" — are each set per statement by that
capability's `AiQuerySettings` except the two token caps, which it never relied on (its `X-AI-Max-Tokens`
tally is ADAS's own). So no default move reaches it; the capability's integration test ran on 26.8. This
change adds no AI configuration, named collection or test: the shared paragraphs it touches in
`docs/clickhouse-server.md` and `local_env/docker-compose.yml` use that change's wording verbatim so the
two stay consistent.

**D7 — Re-run the operator probes rather than re-date them.** Memory-limit wording, `ALTER SETTINGS`
grant scope (2 permitted / 11 refused, Code 497), `async_insert` and `non_replicated_deduplication_window`
defaults, JSON `dynamicType` over the `Int64` bound, and `FINAL` placement were each executed on
26.8.15.10 in this change; those are the only claims whose version was moved.

## Risks / Trade-offs

- [26.8 computes statistics on every insert and merge (`materialize_statistics_on_insert = 1`,
  `auto_statistics_types = basic, uniq_v2`), a CPU and small disk cost the suite does not measure] →
  A check saw no measurable merge cost on a payload-shaped table; watch
  `system.part_log` merge durations and ingest latency for the first day after the upgrade. `compatibility = 26.3`
  switches it off if it matters.
- [Rollback leaves skip indexes inert on 26.8-written parts] → Documented with the two mitigations
  (D3). Cost is read work, not data; `V12`'s force-merge rewrites settled days within two days anyway,
  which also restores the indexes on the older server.
- [The suite runs on the floating `26.8` tag, so a 26.8 patch release can change a result under the
  build] → Same posture as the 26.3 pin, deliberately: patch drift is found in CI, not after deployment.
- [The settings sweep is from one build, 26.8.15.10] → A later 26.8 patch can move a default; the
  sweep is dated and the query to repeat it is one line against `system.settings_changes`.
- [The in-place data hop 26.3 → 26.8 is not exercised here] → No serialization setting moved (D5), so
  the structural risk the 26.3 change carried does not recur; a deployment's own upgrade remains the acceptance
  gate, with a backup first.

## Migration Plan

For the deployment (26.3 → 26.8):

1. Back up the ClickHouse volume.
2. Decide the rollback posture: set `<compatibility>26.3</compatibility>` in the default profile, or
   `packed_skip_index_max_bytes = 0` in `merge_tree`, **before** starting 26.8 if rollback with intact
   skip indexes matters; otherwise accept that a rollback runs slower until settled days re-merge.
3. Start 26.8; ADAS needs no configuration change and runs no migration for this hop.
4. Run the one-line memory-limit check from `docs/clickhouse-server.md` once against the new server.
5. Watch `system.part_log` and ingest latency for the statistics cost; watch query latency for the
   planner changes (adaptive aggregator, `ie_join`, lazy FINAL are all on by default).

Rollback: stop ADAS, start 26.3 against the same volume (parts are readable — no serialization floor
moved), accept inert skip indexes on 26.8-written parts unless step 2 was taken.

## Open Questions

- Whether the statistics cost on the payload table's heavy rows is visible at real data volume — answered by
  the first day's `system.part_log` after the hop; does not change this design.
- Whether to keep `compatibility = 26.3` on a deployment after acceptance (it also disables the 26.8
  planner defaults listed in D4) — an operator decision once rollback is no longer wanted.
