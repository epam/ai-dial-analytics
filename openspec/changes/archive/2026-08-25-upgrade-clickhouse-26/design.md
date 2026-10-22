## Context

ADAS pins `clickhouse/clickhouse-server:25.3` in the two places that exercise a real server — the
Testcontainers singleton (`ClickHouseContainerSupport`) and `local_env/docker-compose.yml` — and
`openspec/specs/data-sync/spec.md` states the supported floor as "ClickHouse **25.3 LTS or newer**",
the only ClickHouse version statement in any capability spec. Deployments have meanwhile moved to
newer versions such as **25.7**, so the tested contract is already looser than what runs.

The 26.x line is current, with **26.3 LTS** as its long-term release (26.7 is the newest minor). An
investigation established empirically that ADAS runs on it: a full `./gradlew clean build` — 1,758
tests across 132 classes — is green against `clickhouse/clickhouse-server:26.3` (26.3.21.7) with no
production-code changes, covering Flyway provisioning (`AES_256_GCM_SIV` body codec, `JSON` columns),
`final = 1` dedup reads, the query budget, aggregate pipelines, enrichment write-back and the Flight
SQL JSON round trip. That suite always starts from an empty database, so an in-place upgrade over real
pre-existing 25.3 parts was verified separately against the `local_env` stack (see Risks).

Two facts shape everything below. First, ADAS's SQL surface is narrow and it renders all of it: the
DSL emits a fixed function set through jOOQ, and even the `execute-sql` path is parsed by Calcite and
re-rendered, so no caller SQL reaches the server unmediated. That is why a 26.x-wide sweep of
backward-incompatible changes lands almost entirely outside ADAS. Second, the 26.x change that *does*
matter is not a SQL or API change at all — it is on-disk part serialization, which makes the upgrade
irreversible in a way no code change can undo afterwards.

Settings were verified directly against both images rather than read from a changelog. Of everything
ADAS sets (`max_execution_time`, `max_memory_usage`, `max_bytes_before_external_group_by`, `final`),
all still exist on 26.3 with identical defaults. That framing was too narrow on its own, though — it
covers what ADAS *sets*, not what ADAS *depends on* — so it was widened into a sweep of
`system.settings_changes` across the whole 25.4–26.3 window. Several defaults moved there; the one that
forces a code change is `async_insert` (`0` on 25.3, `1` from 26.2), and the rest are enumerated in the
proposal and in Risks below.

## Goals / Non-Goals

**Goals:**
- Widen the stated support contract to the range that is now verified — 25.3 LTS or newer, verified
  through 26.3 LTS — and give it a first-class requirement instead of a clause buried in DDL prose.
- Move the pinned server image to `26.3` in the Testcontainers singleton and `local_env`.
- Write down the rollback constraint for operators, with the staged upgrade path.
- Refresh the "verified against 25.3" provenance comments so they name what was actually re-checked.

**Non-Goals:**
- No Gradle dependency changes. `client-v2`/`clickhouse-jdbc` stay at 0.9.8 and
  `flyway-database-clickhouse` at 10.24.0.
- No migration, no DDL change, no change to the `AES_256_GCM_SIV` encryption-key requirement.
- No adoption of any 26.x feature. The point is that ADAS keeps working, not that it gains anything.
- Not performing any deployment's upgrade. This change makes 26.3 supported and documented; moving a
  deployment is an operations activity gated on the staged verification below.

## Decisions

- **Widen the range rather than raise the floor.** Nothing in ADAS requires 26.x — the floor exists
  solely because the `JSON` type went GA in 25.3 — so raising it to 26.3 would make every deployment
  still on 25.x unsupported to buy nothing. The contract becomes "25.3 LTS or newer, verified through
  26.3 LTS". *Alternatives:* raise the floor to 26.3 (simplest spec text, but a **BREAKING** contract
  change with no engineering justification); run the suite against both 25.3 and 26.3 as a matrix
  (honest coverage of the whole range, but roughly doubles integration wall-clock — see the risk
  below, where it is the named escape hatch if a 25.x-only regression ever appears).
- **Pin `26.3`, the LTS tag — not `26.7`, `latest`, or a point release.** This continues the existing
  convention (24.8 and 25.3 were both LTS tags): an LTS line gives a support window and reproducible
  builds without chasing every minor. 26.4 and 26.7 are non-LTS. A point tag like `26.3.21.7` would
  pin past the patch stream the LTS tag exists to deliver.
- **Pin `async_insert = 0` on the row-insert clients.** An earlier draft of this design accepted the
  server default, on the reasoning that `wait_for_async_insert` stays `1` so inserts are still
  acknowledged and re-ingest idempotence is unaffected. The second half of that is wrong, and the suite
  could not show it: every deduplication test supplies the version column explicitly, so none exercises
  the server-side `DEFAULT now64(3)` that real ingest actually relies on. The async buffer evaluates a
  column default once per *flush*. Measured on 26.3.21.7, eight **concurrent** inserts of one key inside
  the 200 ms `async_insert_busy_timeout_ms` window shared a single stamp and `ReplacingMergeTree` kept an
  arbitrary row (`v5` of `v1`…`v8`); the same eight with the pin were stamped a millisecond apart and
  `FINAL` returned the newest. `table-management` stated the guarantee as "every write lands with a
  strictly increasing RMT version", so this is a correctness pin, not a preference. Pinning `0` is a
  no-op below 26.2, which makes it the option that changes nothing anywhere relative to the verified
  baseline. What it does **not** do is make that sentence true as written: `now64(3)` is millisecond
  resolution, and eight *concurrent* inserts with the pin still produced one stamp shared by two of them.
  The pin narrows the tie window from a flush (~200 ms, many requests) to a clock tick; ties inside a tick
  remain, and on a tie the survivor is unspecified. The single-threaded ingest path is sequential and
  stamps 1–2 ms apart; the catalog row-write path is HTTP and can be genuinely concurrent. So both the
  `table-management` sentence and this change's `data-sync` requirement were amended to state the
  guarantee at the stamp's resolution rather than absolutely — the honest fix for the residue is the
  rejected alternative below (client-supplied version), which stays out of a server-upgrade change.
  *Alternatives:* follow the server default and document the weakening (rejected — it silently
  contradicts a shipped SHALL); have the writers supply the version column themselves instead of relying
  on the server default (a larger change to the mapping and DDL contract, and it would have to be made
  for `dial_usage_log` and every catalog table alike); pin `1` (would change 25.x behaviour too, for
  nothing).
- **Hold the Java driver at 0.9.8 and keep the 0.10.0 bump out of this change.** `client-v2` 0.9.8
  does not pin `async_insert`, so ADAS inherits the server default as intended. `clickhouse-jdbc`
  0.9.8 hardcodes `async_insert=0`/`wait_end_of_query=0` in `ConnectionImpl`, so the Flyway migration
  path is insulated regardless of server version. 0.10.0 removes exactly that hardcoding — bumping it
  here would change the migration path's behavior in the same change as a server upgrade, leaving two
  variables to disentangle if anything breaks. The driver bump is its own change.
- **Add no server-version check.** ADAS already fails fast on the thing that actually matters: a
  `CREATE TABLE` that the server cannot satisfy aborts startup through the existing
  migration-failure path, and `SchemaValidator` catches a live table that disagrees with the mapping.
  A version comparison would add a second, more brittle gate — one that can refuse a working server
  over a version string (a vendor build such as Altinity's, a fork, a patch numbering change) while
  still not proving the feature is present. *Alternative considered:* a startup guard that refuses a
  server outside the supported range. Rejected: it converts a working deployment into a startup
  failure, which is the failure mode this project has elsewhere chosen to avoid.
- **Host the version contract in `data-sync`.** It is today the only capability spec that names a
  ClickHouse version, and it already owns the provisioning requirement the version clause sits in.
  *Alternative:* a new capability for the platform contract — rejected as a capability invented to
  hold a version statement, which would also duplicate `data-sync`'s provisioning requirement.
- **Touch no migration file — an applied migration is immutable, comments included.** This was
  discovered during implementation, not planned: the task list had assumed a header-comment edit was
  safe because it changes no DDL. Flyway checksums the whole file, so editing V1's or V9's header makes
  `migrate()` fail with `Migration checksum mismatch` against any database that already applied it, and
  a migration failure aborts startup — so it would have broken every existing deployment while the test
  suite stayed green, because Testcontainers always starts from an empty database. The version
  statements therefore live only in `docs/configuration.md` and the spec delta. *Alternatives:* have
  operators run `flyway repair` before deploying (a manual step whose failure mode is a startup abort); set `validateOnMigrate=false` or repair on
  startup in `ClickHouseMigrationRunner` (removes a real drift guard to buy a comment); carry the text
  in a new no-op migration as V4 does with `SELECT 1;` (a migration existing only to hold a comment).
  All three were rejected — the statements do not need to live in a checksummed file. Note that the
  precedent change `2026-06-25-bump-clickhouse-25-3` edited V1's header the same way, so this trap may
  already have been absorbed once.
- **Ship no new operator page; keep only the durable half.** What outlives this change — the rollback
  constraint, the `async_insert` posture, and the no-version-check stance — is a short block in
  `docs/configuration.md`'s ClickHouse schema-migrations section, which the spec delta's "documented for
  operators" scenario reads against. The step-by-step verification runbook is a one-off: it is
  scaffolding for performing *this* upgrade, not a description of how the system behaves, and the repo
  would carry it long after it stopped being true. *Alternatives:* ship it as a standalone page
  (rejected — stale the day the upgrade completes); fold the runbook into `configuration.md` as well
  (rejected — that file's one job is the property reference).

## Risks / Trade-offs

- **JSON/Dynamic inference drift silently changes results — confirmed, and it lands in ADAS's favor.**
  `ExprTranslator`'s JSON-subcolumn accessors reproduce `JSONExtractBool`/`JSONExtractInt` by
  string-comparing `dynamicType()` against a hardcoded type-name list (`'Int8'`…`'UInt256'`, `'Bool'`).
  Re-running the matrix on both versions — a `String` column holding the raw document against a `JSON`
  column, which is the pair the requirement is about, over 20 cases — found exactly one difference, and
  **26.3 is the version that agrees**: any integer magnitude above the `Int64` bound is stored as
  `Float64` on 25.3 and `UInt64` from 26.3, so `json_extract_bool` returns `false` on 25.3 where the text
  form returns `true`. `json_extract_long` agrees on both. → **Consequence for this change:** it is *not*
  a breach of `structured-query`'s "observationally indistinguishable" requirement, which already exempts
  what the `JSON` column type chooses to store a member as; but the exemption did not say that the
  exemption spans server versions, and a range claim makes that load-bearing. So the requirement is
  amended to say it explicitly, and the accessor's Javadoc records the instance. Closing the 25.3 gap
  would mean treating some `Float64`-stored members as integers, which breaks the fractional cases the
  rendering exists to get right, so it stays recorded rather than papered over. Not documented in
  `docs/configuration.md`: it is a DSL semantic, not a configuration one.
- **~~The migration over existing parts is unverified.~~ Now verified locally, 25.3 → 26.3.** The
  `local_env` volume held 77,618 rows in 31 parts across 14 partitions written by 25.3. After an
  in-place image swap onto 26.3 the baseline diff showed **only the version string** changing: identical
  row counts (plain and `FINAL`), identical per-partition counts and numeric sums, both `JSON` columns
  identical, and 5,052,894,060 + 2,909,688,263 bytes of `AES_256_GCM_SIV` body content read back
  identically, with 0 detached parts. ADAS then booted against it, Flyway **validated the pre-existing
  V1–V7 history** and applied the pending V8/V9 on 26.3 against 25.3-created tables, and the API
  returned 73,002 — the exact `FINAL` baseline. A 26.3 write into the 25.3-created table coexisted with
  the old parts and read back intact. → Residual: the hop tested was 25.3 → 26.3, not the deployment's
  25.7 → 26.3. A 25.7 part is no older in format than a 25.3 part, so this is strong but not identical
  evidence; the staged upgrade stays the gate.
- **~~`escape_variant_subcolumn_filenames` may need `0` for pre-existing tables.~~ Not needed at
  25.3 → 26.3.** It defaulted to `1` and both `JSON` columns on the pre-existing table read correctly
  through ADAS without it, including nested objects and arrays. Re-check on a staged hop rather than
  assuming it generalises — and note it is a **MergeTree table setting**, absent from `system.settings`,
  so applying it would be `ALTER TABLE … MODIFY SETTING` per table (needing the `ALTER SETTINGS` grant)
  or a `<merge_tree>` server default, not a session flip.
- **The upgrade is irreversible past 25.10**, and the door is inside the 25.x line rather than at 26.3.
  Read from `system.settings_changes`: `string_serialization_version` → `with_size_stream` and
  `serialization_info_version` → `with_types` at **25.11** (an older server rejects the latter with
  `Unknown version of serialization infos`), `object_serialization_version` → `v3` and
  `object_shared_data_serialization_version` → `advanced` at **25.12**. So a 25.11+ part is unreadable
  below 25.10 and a 25.12+ `JSON` column below 25.12; 26.3 only inherits this, and staging through 25.8
  buys nothing against it. → Set `<compatibility>` in the **default profile**, naming the rollback
  target, before starting the newer server, and take a backup regardless. Verified on 26.3.21.7: with
  `<compatibility>25.8</compatibility>` in `users.d`, all five settings revert and the written part
  matches 25.3's layout (`serialization.json` version 0, one `String` substream, `map` shared data); a
  per-query `SETTINGS compatibility='25.8'` changes nothing, because these are MergeTree settings. This is
  the single highest-consequence item in the change.
  → And `compatibility` is not scoped to the part format: it reverts **every** default changed after the
  version it names. Verified on 26.3.21.7 — at `25.8` it also gives back `defer_partition_pruning_after_final`
  `0` (re-arming the `FINAL` partition-pruning regression that 26.3's default exists to gate — see the last
  open question), `use_variant_as_common_type` `0`, `allow_statistics_optimize` `0` and `deduplicate_insert`
  `backward_compatible_choice`; at a target below 25.6 it also gives back `use_skip_indexes_if_final` `0`,
  which makes `dial_usage_log`'s four bloom filters and V3's `minmax` inert under `final = 1`. Retaining
  rollback therefore costs query planning, and at some targets costs a correctness fix — now stated in
  `docs/configuration.md` rather than left as "what it costs is unmeasured".
- **~~Ingest semantics diverge between a 25.x and a 26.x deployment.~~ Closed by pinning
  `async_insert = 0`** (see Decisions). With the pin there is no async buffer in the ingest path, so the
  new failure surface, the interaction between a ~64 MiB `SYNC_MAX_BATCH_BYTES` batch and the 10 MiB
  `async_insert_max_data_size` buffer, and the untested question of whether a disk-full `ServerException`
  (code 243, matched by the storage-pressure guard) still surfaces identically from a buffer flush all
  become moot rather than accepted. `INSERT … SELECT` and DDL were verified not to use the buffer.
- **25.x stops being CI-covered.** The range claim rests on ADAS containing no 26.x-specific construct,
  which remains true — the only main-code line this change adds is a pin that is inert below 26.2. But
  the sweep found in-range default drift that CI can no longer see (skip indexes under `FINAL` from 25.6
  being the material one), so the `data-sync` floor scenario now claims *same results*, not identical
  behaviour. → If a 25.x-only regression surfaces, take the matrix option: make the container image a
  property with `26.3` as the default and run the suite against both.

## Migration Plan

The code change is inert with respect to running data — no migration, no DDL. What needs sequencing is
the server upgrade itself, which is an operations activity this change documents rather than performs.

0. Before touching the server, diff the deployment's `flyway_schema_history` checksums against `HEAD` — a
   pre-existing mismatch aborts startup and would be misattributed to the upgrade. (V1 was edited after
   creation in `cb9c02b`, so this is not hypothetical.)
1. Land this change: pins, the `async_insert` pin, spec deltas, docs. `./gradlew clean build` green
   against 26.3.
2. Re-run the `ExprTranslator` JSON case matrix against real `jwt_claims` data on a staging deployment (25.7) to capture
   the baseline answers.
3. On the staging deployment, back up, put `<compatibility>25.8</compatibility>` in the **default profile** (`users.d`),
   then upgrade 25.7 → 25.8 LTS. Verify ingest and reads. The compatibility value must name the version
   to roll back to; a per-query setting does not reach the part format.
4. Upgrade the staging deployment 25.8 → 26.3 LTS. Re-run the JSON case matrix and compare against the step-2 baseline;
   read the `JSON` and body columns back over a bounded `request_time` window; check whether
   `escape_variant_subcolumn_filenames = 0` is needed for the pre-existing tables.
5. Only then promote, staging through 25.8 the same way.

**Rollback:** available only before step 4 completes, and only if `compatibility` was set first.
After 26.3 has written parts without it, rollback means restoring the backup — which is why steps 3
and 4 are separated and why the backup is not optional.

## Open Questions

- ~~Does `escape_variant_subcolumn_filenames` need to be `0` for pre-existing tables?~~ **Answered no**
  for the 25.3 → 26.3 hop, verified locally over real parts. Still worth confirming on a staged hop.
- Does `compatibility = 25.8` on 26.3 have a measurable cost on the query paths ADAS uses? It is known to
  *work*, and it is now known **what** it reverts (every default above the named version, enumerated in
  Risks) — including one correctness fix. What remains unmeasured is the runtime cost on ADAS's own
  queries; the trade against retaining rollback is an operations decision, not a spec one.
- Does a background **merge** honour the default profile's `compatibility`? Insert-written parts do. If
  merges do not, a merged part could carry the new format even with compatibility set, which would defeat
  rollback for exactly the largest parts. Worth confirming on a staged hop before relying on rollback.
- `defer_partition_pruning_after_final` costs a catalog `upsert_by_key` table whose `partition_by` is
  outside its `ordering_key` all partition pruning under `final = 1` (6/6 parts against 1/6, measured).
  Such a table also cannot collapse duplicates across partitions, so the real fix is a
  `table-management` validation — its own change, not a rider here.
