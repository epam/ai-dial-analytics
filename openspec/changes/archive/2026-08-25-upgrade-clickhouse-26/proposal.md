## Why

ADAS states its supported ClickHouse floor as **25.3 LTS or newer** and pins **25.3** in the two
places that actually exercise a server (the Testcontainers integration base and `local_env`), while
deployments it runs against have moved to newer versions such as **25.7**. The 26.x line is out, with **26.3 LTS** as the
current long-term release, and nobody has established whether ADAS runs on it.

An investigation answered that empirically: a full `./gradlew clean build` — **1,758 tests across 132
classes** — passes green against `clickhouse/clickhouse-server:26.3` (resolved 26.3.21.7) with **no
production-code changes**. That includes Flyway provisioning of `dial_usage_log` (the
`AES_256_GCM_SIV` body codec and the `jwt_claims`/`request_tags` `JSON` columns), `final = 1` dedup
reads, the query budget, aggregate pipelines, enrichment write-back, and the Flight SQL JSON-column
round trip.

Because that suite always starts from an empty database, the upgrade was **also verified in place** on
the `local_env` stack over 77,618 real rows in 31 parts written by 25.3: after the image swap the only
value that changed was the version string, ADAS booted, Flyway validated the pre-existing history and
applied the two pending migrations on 26.3, and the API returned the exact pre-upgrade `FINAL` count.

So the work here is not a port. It is to move the tested pins onto 26.3, widen the stated support
contract to the range that is now verified, and — the part that carries real operational risk —
write down that upgrading past 25.10 is a **one-way door** on the data, which no code change can undo
after the fact.

## What Changes

- **Widen the supported-version contract** from "ClickHouse 25.3 LTS or newer" to "ClickHouse 25.3
  LTS or newer, verified through 26.3 LTS". The floor stays at 25.3 because that is where ADAS's only
  hard server requirement sits (the `JSON` type going GA); nothing in ADAS requires 26.x. Deployments
  still on 25.x remain supported — but from this change on they are no longer CI-covered.
- **Move the pinned server image to `26.3`** in `ClickHouseContainerSupport` (the Testcontainers
  singleton) and `local_env/docker-compose.yml`, so CI and local development run the version the
  deployment is moving to.
- **Document the downgrade one-way door** for operators, with the version floors taken from
  `system.settings_changes` rather than from the release notes. The part format changed **inside the
  25.x line**: `string_serialization_version` → `with_size_stream` and `serialization_info_version` →
  `with_types` at **25.11** (the latter is what surfaces as `Unknown version of serialization infos`),
  then `object_serialization_version` → `v3` and `object_shared_data_serialization_version` →
  `advanced` at **25.12**. So a part written by 25.11+ is unreadable below 25.10 and a `JSON` column
  written by 25.12+ is unreadable below 25.12; 26.3 inherits all of it, and staging through 25.8 does
  **not** soften it because the whole change lands in the hop that crosses 25.10–25.12. Setting
  `<compatibility>` in the **default profile** does work — verified on 26.3.21.7, which then reverted all
  five settings and wrote a part matching the pre-25.10 layout — but a per-query `SETTINGS
  compatibility=…` does not reach them, because they are MergeTree settings rather than query settings,
  and the value must name the version being rolled back *to*.
- **Pin `async_insert = 0` on the two row-insert clients.** The server flips this default `0` → `1` at
  **26.2**, and inheriting it widens a shipped guarantee's failure window from a clock tick to a flush:
  `table-management` requires that "every write lands with a strictly increasing RMT version", but both
  version columns (`dial_usage_log._ingested_at`, a catalog table's `_updated_at`) are server-evaluated
  `DEFAULT now64(3)` values, and the async buffer evaluates a column default once per *flush* rather
  than once per insert. Measured on 26.3.21.7: eight concurrent inserts of one key inside the 200 ms
  `async_insert_busy_timeout_ms` window shared one stamp and `ReplacingMergeTree` kept an arbitrary row,
  where the same eight with the pin produced stamps a millisecond apart and the newest won. Pinning `0`
  is a no-op below 26.2, so it preserves the semantics the deduplication design was verified against
  rather than inventing new ones. It does **not** make the guarantee absolute: the stamp is millisecond
  resolution, so concurrent writers of one key can still tie inside a millisecond and a tie leaves the
  survivor unspecified — the spec text is amended to say so rather than repeating the stronger claim.
  `INSERT … SELECT` (Class-1) and DDL are never routed through the buffer — verified on 26.3 — so they
  need no pin. A new integration test asserts the pin against `system.asynchronous_insert_log`; without
  it that assertion reads 5 instead of 0 and no other test changes colour.
- **Hold the Java client at 0.9.8.** `client-v2` 0.9.8 does not pin `async_insert`; `clickhouse-jdbc`
  0.9.8 hardcodes `async_insert=0`/`wait_end_of_query=0` in `ConnectionImpl`, so the Flyway path is
  insulated regardless. [clickhouse-java 0.10.0](https://github.com/ClickHouse/clickhouse-java/releases)
  removes that hardcoding, which would change the migration path's behavior — so the driver bump
  **must not ride along** in this change.
- **Refresh the "verified against 25.3" provenance comments** so they name the version they were
  actually re-checked on, rather than silently ageing.

**Other defaults that moved inside the newly claimed range**, found by sweeping
`system.settings_changes` rather than only the settings ADAS sets — the original sweep looked at
`max_execution_time`/`max_memory_usage`/`max_bytes_before_external_group_by`/`final` and so missed
defaults ADAS *depends on* without setting. None changes a returned value; each is recorded so the range
claim is not read as behavioural uniformity:

- `use_skip_indexes_if_final` and `use_skip_indexes_if_final_exact_mode` `0` → `1` at **25.6**. Every
  ADAS read runs under `final = 1` and `dial_usage_log` declares four `bloom_filter` indexes plus V3's
  `minmax` on `_ingested_at`. On the 25.3 floor those indexes are inert under `FINAL`; from 25.6 they are
  used. This is the main reason the floor scenario now says "same results", not "identical behaviour".
- `defer_partition_pruning_after_final` `0` → `1` at **26.3**, which ClickHouse's own changelog entry
  describes as retroactively gating a silent regression: under `FINAL`, partition pruning is deferred
  when the partition-key column is not in the sorting key. `dial_usage_log` and `rate_analytics` are
  unaffected (`request_time` is in the sort key, confirmed by `EXPLAIN indexes=1`). A catalog-registered
  `upsert_by_key` source can hit it — `CatalogSchemaWriter` requires only that `partition_by` name a
  declared, keyable column — and a probe table shaped that way read 6/6 parts on the default against 1/6
  with the setting off. Left as a follow-up rather than fixed here: such a table is already the shape for
  which `ReplacingMergeTree` cannot collapse across partitions, so the fix is a `table-management`
  validation with its own spec delta, not a rider on a server upgrade.
- Column statistics went GA (`allow_statistics`/`allow_experimental_statistics` `0` → `1` at 26.3,
  `allow_statistics_optimize` `0` → `1` at 25.12, `use_statistics_cache` and
  `refresh_statistics_interval` `0` → `300` at 26.2). `system.settings_changes` also advertises
  `auto_statistics_types` `''` → `'minmax, uniq'` at 26.2 **and** 26.3, which would build statistics on
  every MergeTree table by default — but the effective default in `system.merge_tree_settings` on
  26.3.21.7 is still empty, so auto statistics are **off** on the version being adopted and cost no merge
  CPU or disk. A settings-changes row records an intended default, not the default the build ships; only
  `system.merge_tree_settings` / `system.settings` answers that.
- `deduplicate_insert_select` default changed at 26.1 and `deduplicate_blocks_in_dependent_materialized_views`
  `0` → `1` at 26.2. Neither reaches ADAS today (`non_replicated_deduplication_window` is still `0`; no
  materialized view is created, `FreshnessMode.INCREMENTAL` being unimplemented) but the second is exactly
  what an incremental aggregate would meet.
- `use_variant_as_common_type` `0` → `1` at 26.1 and `output_format_json_quote_64bit_integers` `1` → `0` at
  25.8. The latter provably does not reach ADAS: the read path is client-v2 `queryAll`/`GenericRecord`, a
  binary row format.

Explicitly **not** changed, each verified as a non-exposure rather than assumed: the `Object` type
removal (ADAS uses `JSON`; `Object` is gone from `system.data_type_families` on 26.3), the text/inverted
index v3 rebuild and `hypothesis` index removal (ADAS declares only `bloom_filter` and `minmax`), the
`DEFLATE_QPL`/`ZSTD_QAT` codec removal (ADAS uses ZSTD/T64/Delta/DoubleDelta/AES), empty `ORDER BY` now
forbidden on `ReplacingMergeTree` (`TableDdlRenderer` already rejects an empty `ordering_key`), non-ASCII
index filenames now failing to load (`Identifiers` restricts every name to `^[a-z_][a-z0-9_]*$`),
`AggregatingMergeTree` schema validation (that engine is rejected at registration), and `IN` with exact
`Bool` semantics (jOOQ's ClickHouse dialect renders real `true`/`false` literals, not `1`/`0`).
`deduplicate_insert` now defaults to `enable`, but `non_replicated_deduplication_window` is still `0`
and ADAS's tables are plain non-replicated `ReplacingMergeTree`, so insert-level dedup stays off.
`ttl_only_drop_parts` is still `0` by default, so migration V9 remains a real change rather than a
restatement.

## Capabilities

### New Capabilities
<!-- None — this change introduces no new behavior. -->

### Modified Capabilities

- `structured-query`: the **Supported function catalog** requirement's observational-equivalence
  guarantee for `json_extract_*` over an `object` field already exempted what the native `JSON` type
  chooses to store a member as. The exemption is amended to say that it holds *across server versions*,
  and to name the known instance (an above-`Int64` integer stored as `Float64` on 25.3 and `UInt64` from
  26.3), because a range claim is what makes a per-version storage difference load-bearing.
- `data-sync`: the **Target table provisioning and schema** requirement pins the supported server as
  "ClickHouse **25.3 LTS or newer**" in its `JSON`-columns clause, and its *JSON columns provision
  without an experimental flag* scenario repeats that version. Both become the verified range (25.3
  LTS or newer, verified through 26.3 LTS). A new **Supported ClickHouse server version** requirement
  is added alongside it, so the version contract — the verified range, the downgrade constraint, and
  the `async_insert` posture — is stated once as a first-class requirement instead of staying buried
  in DDL prose. `data-sync` hosts it because it is today the only capability spec that states a
  ClickHouse version at all.

## Impact

- **Code/config**: `src/test/java/.../integration/ClickHouseContainerSupport.java` (image pin);
  `local_env/docker-compose.yml` (image pin + lockstep comment); the `async_insert = 0` pin on
  `UsageLogWriter` / `CatalogRowWriter` with its rationale on `ClickHouseWriterClient.ASYNC_INSERT`, plus
  `RowWriteVersionMonotonicityIntegrationTest`. Otherwise no `src/main` behavior change — only provenance
  comments in `service/query/translate/ExprTranslator.java`. **No migration file is touched**, not even a
  comment: Flyway checksums the whole file, so a header edit would abort startup on every database that
  already applied it (see design.md).
- **Docs**: `docs/configuration.md` — the supported-version note, the `ALTER SETTINGS` verification
  provenance, and two new blocks: the one-way rollback constraint and the `async_insert` posture — plus
  a `README.md` prose bullet. **No new shipped page**: a step-by-step upgrade runbook is scaffolding for
  performing the upgrade rather than a description of how the system behaves, so it is not committed.
- **Dependencies**: none. `client-v2` / `clickhouse-jdbc` stay at 0.9.8 and
  `flyway-database-clickhouse` at 10.24.0 — the Flyway ClickHouse module gates on
  `databaseProductName.startsWith("ClickHouse")` with a no-op `ensureSupported`, so it does not
  refuse a 26.x server.
- **Operations**: **the upgrade is irreversible past 25.10 unless the `compatibility` profile is set
  first** — the single highest-consequence item in this change. Ingest stays synchronous because the
  service pins `async_insert = 0`. Existing `dial_usage_log` data and DDL are unchanged, and no migration
  is added.
- **Build**: `./gradlew clean build` runs the ClickHouse-backed suite against the 26.3 image via
  Testcontainers (Docker required).
- **Residual risk**: the hop verified in place was **25.3 → 26.3**, not a 25.7 → 26.3 hop.
  A 25.7-written part is no older in format than a 25.3-written one, so this is strong but not
  identical evidence, and the staged upgrade remains the gate. `escape_variant_subcolumn_filenames`
  turned out **not** to need `0` at 25.3 → 26.3 — both `JSON` columns on the pre-existing table read
  correctly with the 26.3 default — but that is worth re-confirming on a staged hop.
- **Found on the way**: `ExprTranslator`'s JSON-subcolumn accessors reproduce
  `JSONExtractBool`/`JSONExtractInt` by string-comparing `dynamicType()` against a hardcoded type-name
  list. Re-running that matrix on both versions (a `String` column holding the raw document against a
  `JSON` column, 20 cases) found one disagreement: any integer magnitude above the `Int64` bound, which
  25.3 stores as `Float64` and 26.3 as `UInt64`. **26.3 is the version that agrees** with the text form.
  This is not a defect against `structured-query`'s "observationally indistinguishable" requirement —
  that requirement already exempts what the `JSON` column type chooses to store a member as — so rather
  than open a follow-up change, this one makes the exemption say so explicitly, since it now spans two
  versions that answer the case differently. `json_extract_long` agrees with the text form in every case
  on both versions.
