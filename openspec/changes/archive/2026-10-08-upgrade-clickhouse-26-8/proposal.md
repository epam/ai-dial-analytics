## Why

The deployment is moving to **ClickHouse 26.8 LTS**, and ADAS's supported contract reads "25.3 LTS or
newer, verified through 26.3 LTS": the two pins that actually exercise a server (the Testcontainers
integration base and `local_env`) sit on 26.3, so nothing in the build would notice a 26.8 regression.
A dry run of the full suite against `clickhouse/clickhouse-server:26.8` (resolved 26.8.15.10)
came back **green bar one test**; the one failure is a `SHOW CREATE` text assertion, not
a behaviour. The work is to move the verified pin, widen the contract to the range that is now
verified, and record — measured on both servers, not read from release notes — what changed between
26.3 and 26.8 that reaches ADAS.

26.8 is also the first server that ships the native AI functions (`aiGenerate`, `aiClassify`, …) the
optional AI query functions capability renders onto — 26.3 has none — so this bump is the precondition
for turning that capability on. It stays optional: off by default, and the only ADAS surface whose
floor is 26.8.

## What Changes

- **Move the pinned server image** from `26.3` to `26.8` in `ClickHouseContainerSupport` and
  `local_env/docker-compose.yml`. No `src/main` change is needed: the suite is green bar one
  assertion.
- **Fix the one assertion** that pins `SHOW CREATE TABLE dial_usage_log_payload` to the text
  `ORDER BY event_id`. 26.8 prints the migration's own spelling, `ORDER BY (event_id)`, where 26.3
  normalised it; the test accepts both and still refuses a second key column.
- **Widen the supported-version contract** to "ClickHouse 25.3 LTS or newer, verified through
  **26.8 LTS**" everywhere it is stated: the `data-sync` requirement, `docs/clickhouse-server.md`,
  `README.md`, `docs/release-0.2-upgrade.md`. The floor stays 25.3.
- **Record the one server-side semantic change in the window that ADAS's surface can see.** From
  26.6 (`analyzer_compatibility_apply_final_to_all_joined_tables` → `0`) the `FINAL` keyword applies
  only to the table reference it follows. Measured: on 26.3 `FROM a FINAL JOIN b` also deduplicated
  `b`; on 26.8 it does not. ADAS renders `FINAL` on every collapsing table of an entity itself and the
  pipeline's internal reads use the `final = 1` setting (which still deduplicates every table on 26.8),
  so the service's own guarantees hold on both — but the `sql-query-translation` requirement said
  "ClickHouse 26.3 accepts" for placement and the independence of references is now also what the
  server itself does. The spec names the measured range.
- **Extend the rollback note** in `docs/clickhouse-server.md`: 26.8 writes a part's skip indexes as one
  packed file (`packed_skip_index_max_bytes`, `0` → `1 MiB` at 26.8). 26.3 reads such parts but treats
  the indexes as absent until the part is re-merged; a `compatibility` profile naming 26.3, or
  `packed_skip_index_max_bytes = 0` in the server's `merge_tree` config, keeps the classic layout.
  Measured at the part-file level on 26.8.15.10. No serialization-format setting moved between 26.3
  and 26.8, so the 25.10/25.12 doors are unchanged and no new one opens.
- **Re-verify and re-date the operator claims** the previous upgrade established on 26.3.21.7: the
  `Query memory limit exceeded` wording (unchanged on 26.8.15.10), the `ALTER SETTINGS` grant scope
  (2 permitted, 11 refused with `ACCESS_DENIED`, unchanged), `async_insert = 1` and
  `non_replicated_deduplication_window = 0` defaults (unchanged), JSON member typing above the
  `Int64` bound (`UInt64`, unchanged).
- **State the AI functions' place in the range.** The supported-version requirement gains the rule that
  an optional, off-by-default capability may need a newer server than the floor, and names the AI query
  functions as the one that needs 26.8. The 26.8 defaults of the `ai_function_*` settings (plain-`http`
  endpoints refused, one retry, no server-side token caps, `allow_experimental_ai_functions` obsolete)
  are recorded for the capability's own change, which already pins each one per statement.
- **Leave every provenance comment that names 26.3 as a measurement alone** unless the measurement
  was actually re-run here. A comment saying "measured on 26.3.24" is a record, not a contract.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: **Supported ClickHouse server version** — verified upper bound moves from 26.3 LTS to
  26.8 LTS; the rollback-documentation obligation gains the packed-skip-index note; the scenario for
  the newest verified version names 26.8; an optional capability may sit above the floor, and the AI
  query functions are named as the one that does (26.8). **Target table provisioning and schema** — the supported
  range text it quotes moves with it.
- `sql-query-translation`: **FINAL table modifier** — the placement and scoping statements name the
  measured range (26.3 and 26.8) and record that the server's own keyword scoping changed at 26.6
  while the surface's contract did not.

## Impact

- **Code**: `src/test/.../ClickHouseContainerSupport.java` (pin),
  `src/test/.../IngestTableRetentionSettingsIntegrationTest.java` (assertion),
  `src/main/.../ClickHouseBudget.java` (provenance comment naming the pinned image). No `src/main`
  behaviour changes. No Gradle dependency moves — `clickhouse-java` stays where it is for the reason
  recorded in `build.gradle`.
- **Local stack**: `local_env/docker-compose.yml` image and comment.
- **Docs**: `docs/clickhouse-server.md` (supported range, message-wording provenance, rollback section,
  force-merge and `ALTER SETTINGS` provenance), `README.md`, `docs/release-0.2-upgrade.md`. No
  configuration property is added or renamed, so `docs/configuration.md` is untouched.
- **Specs**: `data-sync`, `sql-query-translation` deltas as above.
- **Operators**: a 26.3 → 26.8 hop needs no ADAS configuration change and no migration. Rollback to
  26.3 is possible for the data (no serialization floor moved) but loses skip-index use on parts
  written by 26.8 until they are re-merged unless the compatibility profile or the `merge_tree`
  setting is in place first.
