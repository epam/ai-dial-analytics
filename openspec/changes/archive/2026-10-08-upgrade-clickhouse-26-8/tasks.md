## 1. Move the pinned server image

- [x] 1.1 In `src/test/java/com/epam/aidial/analytics/integration/ClickHouseContainerSupport.java`, change `DockerImageName.parse("clickhouse/clickhouse-server:26.3")` to `:26.8`. Leave the `TZ=UTC` env and the `encryption.xml` mount untouched — both are still required on 26.8.
- [x] 1.2 In `local_env/docker-compose.yml`, change `image: clickhouse/clickhouse-server:26.3` to `:26.8` and update the lockstep comment that names the version the integration tests run and the newest verified version.

## 2. Fix the one assertion 26.8 breaks

- [x] 2.1 In `IngestTableRetentionSettingsIntegrationTest.payloadTableKeepsTheShapeTheGrainJoinWillNeed`, replace `contains("ORDER BY event_id")` with a match that accepts both the bare spelling 26.3 prints and the parenthesised `ORDER BY (event_id)` 26.8 prints for the migration's own text, while still refusing a second key column (`event_id,` or `event_id_x`). Measured: a fresh `ORDER BY (event_id)` prints with parentheses on 26.8.15.10 and without on 26.3.27.3.

## 3. Widen the supported-version statements

The floor stays 25.3 (the `JSON`-type GA requirement); 26.8 is the newest verified. **Touch no migration file** — Flyway checksums the whole file, comments included (see the 26.3 upgrade's tasks for the measured failure).

- [x] 3.1 `docs/clickhouse-server.md` "Supported versions": verified through 26.8 LTS.
- [x] 3.2 `README.md` supported-server bullet: verified through 26.8 LTS.
- [x] 3.3 `docs/release-0.2-upgrade.md`: the precondition bullet and the "`V12` settings are verified on …" sentence name 26.8 as well.
- [x] 3.4 Confirm no ClickHouse-version *property* was added or renamed, so `docs/configuration.md` needs no row.

## 4. Re-verify, then re-date the operator claims

Every version named below is a record that something *was checked* on that server. Re-run first, then write what the run showed; where a claim was not re-run, leave the version it names alone.

- [x] 4.1 Memory-limit wording: `SELECT number, count() FROM numbers(100000) GROUP BY number SETTINGS max_memory_usage = 1024` on 26.8.15.10 reads `DB::Exception: Query memory limit exceeded: …`. Re-date `docs/clickhouse-server.md` and the `ClickHouseBudget.ownCeiling` Javadoc (which also names the pinned image tag). `ClickHouseMemoryLimitMessageIntegrationTest` pins it in the build.
- [x] 4.2 `ALTER SETTINGS` grant scope: probe user holding `ALTER SETTINGS` on one table only — `MODIFY SETTING` / `RESET SETTING` permitted (plus another MergeTree setting, as documented); row rewrites, partition drop, schema change, `MODIFY TTL`, `TRUNCATE`, `DROP TABLE`, `GRANT`, `SYSTEM`, the same setting on an ungranted table, and `SELECT` each refused with `ACCESS_DENIED` (Code 497) on 26.8.15.10 — 2 allowed, 11 refused, as on 26.3.21.7. Re-date the doc.
- [x] 4.3 `async_insert` still `1` and `non_replicated_deduplication_window` still `0` on 26.8.15.10, so the row-insert pin still does work and insert deduplication still does not reach ADAS. Note it in the doc; the regression test `RowWriteVersionMonotonicityIntegrationTest` runs on 26.8 in the build.
- [x] 4.4 JSON member typing over the `Int64` bound: `dynamicType` answers `UInt64` for `18446744073709551615` and `9223372036854775808`, `Int64` for `1`, `String` for `"true"`, `Float64` for `1.5` on both 26.3.27.3 and 26.8.15.10 — the `structured-query` cross-version exemption text ("`UInt64` from 26.3") stays true and needs no edit.
- [x] 4.5 `FINAL` placement: `FROM t FINAL AS x` is a syntax error on both servers; after-alias placement accepted on both. Re-date the `SqlFinalModifier` and `FinalTables` Javadocs that say "measured on 26.3".
- [x] 4.6 Force-merge settings (`V12`): the suite's `PayloadForceMergeIntegrationTest` / `IngestTableRetentionSettingsIntegrationTest` (including `RESET SETTING`) run on the 26.8 image. Re-date the doc's "Verified on 26.3.x only" sentence once the build in group 7 is green.
- [x] 4.7 Leave the "measured on 26.3.24" / "verified on 26.3" provenance comments in `ExprTranslator`, `SqlSetOperations`, `EntityFields`, `FinalSettings`, `ClickHouseWriterClient` and the set-operation / composite tests alone: they record measurements that were not re-run here and remain true as records.

## 5. Sweep the 26.4–26.8 default changes against ADAS

Taken from `system.settings_changes` on 26.8.15.10 and checked against the effective value in `system.settings` / `system.merge_tree_settings` (a settings-changes row records an intended default, not necessarily the shipped one).

- [x] 5.1 `analyzer_compatibility_apply_final_to_all_joined_tables` `1` → `0` at 26.6. Measured on scratch `ReplacingMergeTree` tables: `FROM a FINAL JOIN b` deduplicates `b` on 26.3 (count 1) and not on 26.8 (count 2); `a JOIN b FINAL`, a plain `MergeTree` partner, `FINAL` inside a derived table and `IN (… FINAL)` behave the same on both; `SETTINGS final = 1` deduplicates every table on both. Confirm ADAS renders `FINAL` on each collapsing table itself (`FinalTables` at the source `FROM`, each enrichment join, and inside a keyed join's derived table) and that `FinalQueryModifierIntegrationTest.finalMarksOneReferenceOfJoin` passes on both images, then record the measured range in the `sql-query-translation` delta.
- [x] 5.2 `packed_skip_index_max_bytes` `0` → `1 MiB` at 26.8 (MergeTree). Measured on 26.8.15.10: default writes `skp_idx.packed`; `packed_skip_index_max_bytes = 0` writes `skp_idx_bf.idx` + `skp_idx_bf.cmrk4`; the setting is unknown to 26.3 (`UNKNOWN_SETTING`). A `compatibility = 26.3` default profile on 26.8 reverts it to `0` (part files verified). Document in `docs/clickhouse-server.md` and in the `data-sync` requirement.
- [x] 5.3 Serialization-format settings: `string_serialization_version`, `serialization_info_version`, `object_serialization_version`, `object_shared_data_serialization_version` identical on 26.3.27.3 and 26.8.15.10; no change listed for 26.4–26.8. The 25.10 / 25.12 doors are unchanged and no new one opens.
- [x] 5.4 `auto_statistics_types` effective `basic, uniq_v2` on 26.8 (was empty on 26.3) and `materialize_statistics_on_insert` `0` → `1` at 26.8: every part gains a `statistics.packed` file. 26.3 reads such parts (verified by a rollback check). Note it in the compatibility paragraph; no ADAS action.
- [x] 5.5 `cast_string_to_date_time_mode` / `date_time_input_format` `basic` → `best_effort` at 26.5: `CAST('02/01/2026 03:04:05' AS DateTime)` fails on 26.3 and parses on 26.8. ADAS inserts canonical timestamp strings and renders no string-to-timestamp cast of its own, so this is server behaviour a caller's own SQL may observe; note it in design.md, no spec change.
- [x] 5.6 `allow_rank_dense_rank_arguments` `1` → `0` at 26.5: `rank(1) OVER ()` is refused on 26.8. ADAS renders `rank()` / `dense_rank()` without arguments (`SqlFunctionCatalog`, `RexToExprTranslator`); unaffected.
- [x] 5.7 `input_format_read_datetime_number_as_raw_value` `1` → `0` at 26.8: bare integers in `JSONEachRow` are now seconds. ADAS writes string timestamps; unaffected.
- [x] 5.8 `max_insert_threads` effective `auto` on both servers: one `INSERT … SELECT` of 2M rows made 2 parts on both; no change to the Class-1 / aggregate `INSERT … SELECT` part shape.
- [x] 5.9 `allow_minmax_index_for_json` `1` → `0` at 26.7: ADAS's indexes are `bloom_filter` on `String` columns and `minmax` on `DateTime64`; none on a `JSON` column. `use_legacy_to_time`, `explain_query_plan_default`, `output_format_csv_*`, `*_arrow_use_native_*`: ADAS renders none of `toTime`, `EXPLAIN`, server-side CSV or Arrow output. Unaffected.

- [x] 5.10 AI functions: `system.functions` has the eight `ai*` functions on 26.8.15.10 and none on 26.3.27.3. Defaults that moved at 26.8 — `ai_function_allow_insecure_endpoint` `1` → `0`, `ai_function_max_retries` `0` → `1`, both `ai_function_max_*_tokens_per_query` → `0` (cap disabled), `allow_experimental_ai_functions` obsolete — checked against `AiQuerySettings` in the AI query functions change: every one it relies on is set per statement. Record the optional-capability rule and the 26.8 AI floor in the `data-sync` delta and `docs/clickhouse-server.md`, using that change's wording for the shared paragraphs.

## 6. Spec sync

- [x] 6.1 `data-sync` delta: MODIFIED **Supported ClickHouse server version** — upper bound 26.8 LTS, the packed-skip-index documentation obligation, the newest-verified scenario names 26.8, a new documentation scenario; every existing scenario carried.
- [x] 6.2 `sql-query-translation` delta: MODIFIED **FINAL table modifier** — placement text names the measured range (26.3 and 26.8); the scope paragraph records that the server's own keyword scoping changed at 26.6 and that the surface's independence of references does not rest on it; every existing scenario carried.
- [x] 6.3 `openspec validate upgrade-clickhouse-26-8 --strict` passes.

## 7. Verify

- [x] 7.1 Grep for `clickhouse-server:26.3` and `verified through 26.3` outside `openspec/changes/archive/` and confirm none survive; every remaining `26.3` is a provenance record (4.7) or a dated measurement.
- [x] 7.2 Confirm no Gradle dependency moved (`clickhouse-java`, `flyway-database-clickhouse` unchanged).
- [x] 7.3 `./gradlew checkstyleMain checkstyleTest` passes (as part of the group-7 build).
- [x] 7.4 `./gradlew clean build` passes against `clickhouse/clickhouse-server:26.8` (Docker required). **Done:** green twice — 3,719 tests in 229 classes, 0 failures, 0 skipped — against the stale local `26.8` tag (26.8.2.7, 8m 12s) and, after `docker pull`, against the current one (26.8.17.4, 7m 25s with `--no-build-cache --rerun-tasks`; a plain rebuild after the pull restored cached test results in 45 s and proved nothing). The hand probes in groups 4–5 ran on 26.8.15.10.
