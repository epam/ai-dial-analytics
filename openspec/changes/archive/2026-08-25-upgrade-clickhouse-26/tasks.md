## 1. Move the pinned server image

- [x] 1.1 In `src/test/java/com/epam/aidial/analytics/integration/ClickHouseContainerSupport.java:31`, change `DockerImageName.parse("clickhouse/clickhouse-server:25.3")` to `:26.3`. Leave the `TZ=UTC` env and the `encryption.xml` mount untouched — both are still required on 26.3.
- [x] 1.2 In `local_env/docker-compose.yml`, change `image: clickhouse/clickhouse-server:25.3` (line 29) to `:26.3` and update the lockstep comment (line 19) that names the version the integration tests run.

## 2. Widen the supported-version statements

These are contract statements, not history: each becomes the verified **range**, not a bare swap of
`25.3` for `26.3`. The floor stays 25.3 (the `JSON`-type GA requirement); 26.3 is the newest verified.

> **An applied migration file is immutable — comments included.** This list originally said
> "comment-only edits are safe" for V1 and V9. That is **wrong**: Flyway checksums the whole file, so
> editing a header comment changes the checksum and `migrate()` fails validation against any database
> where that migration is already applied. Verified against a real 26.3 server by applying V1–V9 from
> `HEAD`, then re-running `migrate()` with only the header comments changed:
>
> ```
> FlywayValidateException: Validate failed: Migrations have failed validation
> Migration checksum mismatch for migration version 1
>   -> Applied to database : -698112075
>   -> Resolved locally    : 486665507
> Migration checksum mismatch for migration version 9
> ```
>
> A migration failure aborts startup, so this would have broken startup on every existing deployment.
> The test suite cannot catch it: Testcontainers always starts from an empty database, so the migration
> is applied fresh with the new text and the recorded checksum matches. **Resolution: touch no migration
> file.** The version statements land in `docs/configuration.md` and the spec delta instead,
> neither of which is checksummed.

- [x] 2.1 In `docs/configuration.md:172`, change "generally available on the supported server version (**ClickHouse 25.3 LTS or newer**)" to name the range — 25.3 LTS or newer, verified through 26.3 LTS. Keep the `encryption_codecs` requirement and the throwaway-test-key note exactly as they are.
- [x] 2.2 ~~In `src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql:8`, update the header comment the same way.~~ **Superseded — leave V1 untouched** (see the note above: the file is checksummed in full). Its header saying "ClickHouse 25.3 LTS or newer" remains true — 25.3 is still the floor — so nothing factual is lost by leaving it. Confirm the file is byte-identical to `HEAD`.

## 3. Re-verify, then refresh the version-provenance comments

Every remaining `25.3` reference is a record that something *was checked* on a specific server. Do not
rewrite one to say 26.3 unless the check has actually been re-run on 26.3 — a fabricated provenance
comment is worse than a stale one, because the next reader trusts it. Re-run first, then write what
the run showed. Where a re-run is pointless, say so and leave the original version named.

- [x] 3.1 Re-run the `json_extract_long` case matrix from `ExprTranslator.java:454` against the 26.3 container — integer, fractional and negative-fractional numbers, numeric and non-numeric strings, booleans, arrays, nested documents, explicit nulls, absent paths, and values at and beyond the `Int64`/`UInt64` bounds — and confirm each still agrees with `JSONExtractInt`. Update the comment to name the versions verified.
- [x] 3.2 Re-run the `json_extract_bool` case matrix from `ExprTranslator.java:475` against 26.3, including the two divergences the comment calls out (a member stored as the number `1`, and one stored as the string `"true"`), and confirm `dynamicType()` still returns the type names in `INTEGER_DYNAMIC_TYPES` / `'Bool'` for those members. Update the comment to name the versions verified. **If any answer differs, stop and raise it** — this is the one place where a 26.x change returns a wrong answer rather than an error.
- [x] 3.3 Re-check the `argMax` tie-breaking behavior recorded at `ExprTranslator.java:680` on 26.3 and update the comment to name the versions verified.
- [x] 3.4 Re-check the behavior recorded at `FlightSqlIntegrationTest.java:455` on 26.3 (the test now runs there, so confirm the comment's stated reason still holds) and update the version it names.
- [x] 3.5 Confirm the server default for `ttl_only_drop_parts` is still 0 on 26.3, so V9 remains a real change rather than a restatement. **Done:** still `0` on 26.3.21.7. ~~Add 26.3 to the versions named in `V9__ttl_only_drop_parts.sql:15`.~~ **Superseded — leave V9 untouched** (see the note above). The confirmation is recorded in this task and in design.md instead of in the checksummed file. Confirm V9 is byte-identical to `HEAD`.
- [x] 3.6 Leave `V6__dial_usage_log_nullable_cache_creation_tokens.sql:22` naming 25.3: it records a one-off performance measurement of a migration that has already been applied, so re-measuring on 26.3 would establish nothing. Leave the line unchanged rather than making it look re-verified.
- [x] 3.7 Re-verify the `ALTER SETTINGS` privilege claim at `docs/configuration.md:157` on 26.3 (named in the proposal's Impact but missed when this list was written). Grant a probe user `ALTER SETTINGS` on one table only, then confirm `MODIFY SETTING`/`RESET SETTING` are permitted and that row rewrites, partition drops, schema changes, `MODIFY TTL`, `TRUNCATE`, `DROP TABLE`, `GRANT`, `SYSTEM` commands and the same setting on an ungranted table are each refused. Update the version named. **Done:** unchanged on 26.3.21.7 — 2 allowed, 11 refused with `ACCESS_DENIED` (Code 497).

## 4. Document the upgrade for operators

- [x] 4.1 ~~Add a standalone operator upgrade page.~~ **Revised — no new shipped page.** A step-by-step upgrade runbook goes stale the moment the upgrade is done, so only the durable part is shipped: a block in `docs/configuration.md`'s ClickHouse schema-migrations section covering: the supported range; the **irreversibility** — corrected in group 8 from the release-note reading to what `system.settings_changes` actually says: `string_serialization_version` → `with_size_stream` and `serialization_info_version` → `with_types` at 25.11 (unreadable below 25.10; the latter surfaces as `Unknown version of serialization infos`), `object_serialization_version` → `v3` and `object_shared_data_serialization_version` → `advanced` at 25.12 (`JSON` columns unreadable below 25.12); that `<compatibility>` must be set in the *default profile* naming the rollback target *before* starting the newer server; and the staging-first verification gate (re-run the JSON case matrix against real `jwt_claims` data, read the `JSON` and body columns back over a bounded `request_time` window, and check whether `escape_variant_subcolumn_filenames = 0` is needed for pre-existing tables). State plainly that a backup is not optional.
- [x] 4.2 ~~Record the `async_insert` posture as "ADAS inherits the server default".~~ **Revised — ADAS pins it (see group 8).** The block now records that the server defaults `async_insert` to `1` from **26.2** and that ADAS pins `0` on the row-insert clients, with the reason (a column `DEFAULT` is evaluated once per async flush, and both RMT version columns are such a default). Also records why `deduplicate_insert = enable` does not reach ADAS: `non_replicated_deduplication_window` is still `0` and ADAS's tables are plain non-replicated `MergeTree`/`ReplacingMergeTree`.
- [x] 4.3 Add a `README.md` prose bullet naming the supported range and the one-way upgrade, linking to `docs/configuration.md#clickhouse-schema-migrations` (no env-var table — descriptions stay in `configuration.md`).
- [x] 4.4 Confirm no ClickHouse-version *property* was added or renamed by this change, so `docs/configuration.md` needs no new table row — only the version statement edited in 2.1 and the cross-link in 4.3.

## 5. Verify

- [x] 5.1 Run `./gradlew checkstyleMain checkstyleTest` and confirm it passes.
- [x] 5.2 Grep for remaining `25\.3` and `clickhouse-server:25` references outside `openspec/changes/archive/`, and confirm every survivor is a deliberate historical provenance record (3.6) rather than a missed pin.
- [x] 5.3 Confirm no Gradle dependency moved: `client-v2` and `clickhouse-jdbc` still `0.9.8`, `flyway-database-clickhouse` still `10.24.0`. The 0.10.0 driver bump is explicitly a separate change.
- [x] 5.4 Build checkpoint (Docker required): run `./gradlew clean build` and confirm green — in particular that Flyway provisions `dial_usage_log` on 26.3 with the `AES_256_GCM_SIV` body columns and the `jwt_claims`/`request_tags` `JSON` columns, that the Flight SQL JSON-column round trip passes, and that the query-budget tests still see `max_execution_time` / `max_memory_usage` / `max_bytes_before_external_group_by` honored. Record the resolved server version from the run. **Done:** green — 1758 tests, 132 classes, 0 failures against `clickhouse/clickhouse-server:26.3`, resolved **26.3.21.7**.

## 6. Verify an in-place upgrade over real pre-existing data

The suite proves ADAS on a *freshly provisioned* 26.3 — Testcontainers always starts empty. This group
covers what it structurally cannot: reading and writing parts that an older server wrote. Run against
the `local_env` stack, whose ClickHouse volume held 77,618 rows in 31 parts across 14 partitions written
by 25.3, with Flyway at V7 (V8/V9 still pending).

- [x] 6.1 Capture a baseline on 25.3 over the existing data: row counts (plain and `FINAL`), per-partition counts, numeric sums, non-null counts and total byte lengths of the `AES_256_GCM_SIV` body columns, non-empty counts for both `JSON` columns, and the rendered `json_extract_bool`/`json_extract_long` subcolumn expressions.
- [x] 6.2 Back up the ClickHouse volume before upgrading — the upgrade is one-way, so this is the step the runbook calls mandatory. **Done:** 1.9 GB archive, `tar tzf` exit 0 (a first attempt was truncated by a timeout and was discarded, not trusted).
- [x] 6.3 Recreate the container on `26.3` against the same volume (`docker compose up -d clickhouse`) and confirm it starts healthy. **Done:** 26.3.21.7, healthy.
- [x] 6.4 Re-run the identical baseline and diff. **Done:** the only differing line is `version` — 77,618 rows / 73,002 `FINAL`, all 14 partition counts, all numeric sums, both `JSON` column counts, and **5,052,894,060 + 2,909,688,263 bytes of AES-encrypted body content** all identical. 31 active parts, **0 detached parts, 0 recent errors**.
- [x] 6.5 Boot ADAS against the upgraded server and confirm Flyway validates the pre-existing history and applies the pending migrations. **Done:** `Successfully validated 9 migrations` — direct end-to-end proof the V1/V9 comment revert was necessary and sufficient — then V8 and V9 applied on 26.3 against 25.3-created tables (`rate_raw LowCardinality(Nullable(String))` present; `ttl_only_drop_parts = 1` on both targets). Health `UP`; the only logged errors are `BlobAccessException` from the unconfigured local blob endpoint, unrelated to ClickHouse.
- [x] 6.6 Exercise the read paths through ADAS's own API over the old parts: aggregate, `FINAL` dedup, both `JSON` columns, a `json_extract_*` over an `object` field, and an encrypted heavy column. **Done:** `count(*)` returned **73,002**, matching the 25.3 `FINAL` baseline exactly; `jwt_claims` came back as native nested JSON including an array; `json_extract_string` over the object field returned totally (the type default for an absent key, as specified); a 458,621-byte `request_body` decrypted.
- [x] 6.7 Confirm a 26.3 write into a table **created by 25.3** coexists with the old parts. **Done:** inserted one row (JSON with a boolean and an integer, plus an encrypted body), parts went 31 → 32, ADAS read the row back with JSON and body intact and the total moved 73,002 → 73,003. Test row removed afterwards by dropping its own partition; the volume is back to 77,618 rows in 31 parts.
- [x] 6.8 Note the scope limit honestly: the hop exercised was **25.3 → 26.3**, not a 25.7 → 26.3 hop. A 25.7-written part is no older in format than a 25.3-written one, so this is strong but not identical evidence; a staged upgrade of a non-critical deployment remains the gate.

## 7. Spec sync

- [x] 7.1 Confirm the shipped behavior matches the `data-sync` delta — the widened range in **Target table provisioning and schema** and the new **Supported ClickHouse server version** requirement, including that no version check was added. **Done:** no server-version inspection exists in `src/main` — every `version()` reference is ADAS's own row/window/evaluator versioning. The delta merges into `openspec/specs/data-sync/spec.md` at archive time.

## 8. Review corrections (post-implementation)

A review of the branch re-derived every version claim from the server itself (`system.settings_changes`,
`system.merge_tree_settings`, part files on disk) rather than from release notes, and found three claims
that did not survive. Each correction below is verified, not reasoned.

- [x] 8.1 **Correct the serialization floors.** The three settings were attributed to 26.3; they actually
  moved at **25.11** (`string_serialization_version`, `serialization_info_version`) and **25.12**
  (`object_shared_data_serialization_version`), and a fourth was missed entirely
  (`object_serialization_version` `v2` → `v3`, also 25.12). So the `JSON`-column floor is 25.12, not 25.8,
  and the door is inside the 25.x line rather than at 26.3 — which also means staging through 25.8 does
  not reduce it. Fixed in `docs/configuration.md`, `README.md`, the `data-sync` requirement, proposal and
  design.
- [x] 8.2 **Verify the `compatibility` advice instead of asserting it.** These are MergeTree settings, not
  query settings, so the mechanism was worth testing. Result: `<compatibility>25.8</compatibility>` in the
  **default profile** works — on 26.3.21.7 it reverts all five settings and writes a part matching 25.3's
  layout — while a per-query `SETTINGS compatibility='25.8'` changes nothing. Both facts are now stated.
  Open: whether background merges honour it (recorded in design.md).
- [x] 8.3 **Pin `async_insert = 0`; the "accept the default" decision was unsound.** Both RMT version
  columns are server-evaluated `DEFAULT now64(3)`, and the async buffer evaluates a column default once
  per flush. Measured on 26.3.21.7: eight inserts of one key shared one stamp and an arbitrary row
  survived (`v5`); on 25.3.14.14, distinct stamps and the newest won. `table-management` requires "every
  write lands with a strictly increasing RMT version", so this was a shipped SHALL being broken silently.
  Pinned on `UsageLogWriter` and `CatalogRowWriter` (rationale on `ClickHouseWriterClient.ASYNC_INSERT`);
  `INSERT … SELECT` and DDL verified not to use the buffer, so they are left alone.
- [x] 8.4 **Add the regression test the suite was missing.** Every existing dedup test supplies the
  version column explicitly, which is why 1,758 tests stayed green through 8.3.
  `RowWriteVersionMonotonicityIntegrationTest` asserts against `system.asynchronous_insert_log` — an exact
  oracle, unlike stamp distinctness, which is unmeasurable locally because these writes complete inside
  one millisecond. Negative control run: with the pin removed the assertion reads 5 instead of 0, and no
  other test in the suite changes colour.
- [x] 8.5 **Sweep the whole 25.4–26.3 window, not just the settings ADAS sets.** Enumerated in the
  proposal. The material ones: `use_skip_indexes_if_final` `0` → `1` at 25.6 (so the 25.3 floor does not
  use ADAS's `bloom_filter`/`minmax` indexes under `final = 1` at all — V3's stated purpose does not hold
  there), and `defer_partition_pruning_after_final` `0` → `1` at 26.3, measured at 6/6 parts against 1/6
  on a `ReplacingMergeTree` whose partition column is outside its sort key. `dial_usage_log` and
  `rate_analytics` are unaffected (`request_time` is in the sort key, confirmed by `EXPLAIN indexes=1`).
- [x] 8.6 **Re-run the `json_extract_bool` matrix the way the requirement is written** — a `String` column
  holding the raw document against a `JSON` column, rather than `toString()` of a `JSON` column, which
  renders numbers as quoted strings and produces a false divergence. The real result: one disagreement on
  25.3, at any magnitude above the `Int64` bound (verified at 2^63 and at the `UInt64` maximum), and none
  on 26.3. The Javadoc said "the one case"; it is a class of cases. Also settled that it is *not* a breach
  of `structured-query`'s requirement — that requirement exempts the `JSON` type's own storage choices —
  and made the exemption say so across server versions, rather than leaving the question in a comment.
- [x] 8.7 **Soften the floor-version scenario.** It claimed the floor "behaves identically"; 8.5 shows that
  is false for query planning. It now claims the same *results*, and says plainly that the floor is not
  CI-covered.
- [x] 8.8 **Drop the stale duplicate change directory** left staged in the index under the pre-rename path
  `openspec/changes/upgrade-clickhouse-26/` (an older draft naming 562 tests / 41 classes), which would
  otherwise have been committed alongside the archived copy.

## 9. Second review pass (post-archive)

A review re-derived every claim against a live 26.3.21.7 server (`system.settings_changes`,
`system.merge_tree_settings`, concurrent inserts against a scratch `ReplacingMergeTree`, and the
decompiled driver). Most survived — the serialization floors, the `INSERT … SELECT` exemption, the
`dynamicType` matrix, `ttl_only_drop_parts`, `non_replicated_deduplication_window` and the async
coalescing measurement (8 concurrent inserts → 1 row, 1 stamp, survivor `v5`, reproduced exactly). Four
did not, and two things were missing.

- [x] 9.1 **The pin does not restore "strictly increasing"; it narrows the tie window.** Measured with the
  pin in place: 8 *concurrent* inserts of one key produced 3 distinct stamps over 4 rows — two writes
  shared a millisecond. `now64(3)` is millisecond resolution, so the pin moves the tie window from a flush
  (~200 ms, many requests) to a clock tick but does not close it; on a tie `ReplacingMergeTree` keeps an
  unspecified row. The single-threaded ingest path stamps 1–2 ms apart and is unaffected; the catalog
  row-write path is HTTP and can be genuinely concurrent. Fixed by stating the guarantee at the stamp's
  resolution in `data-sync`'s new requirement, in `table-management`'s "strictly increasing" sentence
  (which was already overstated before this change), in `ClickHouseWriterClient.ASYNC_INSERT`,
  `TableDdlRenderer` and `docs/configuration.md`. The residue's real fix is the client-supplied version
  column already rejected in design.md as out of scope for a server upgrade.
- [x] 9.2 **`compatibility` reverts every default above the version it names, not just the part format.**
  Verified with `--compatibility`: at `25.8` it also gives back `defer_partition_pruning_after_final=0`,
  re-arming the `FINAL` partition-pruning regression 26.3's default exists to gate (plus
  `use_variant_as_common_type`, `allow_statistics_optimize`, `deduplicate_insert`); at a target below 25.6
  it gives back `use_skip_indexes_if_final=0`, i.e. the floor's query planning on a 26.3 server. The
  rollback advice shipped without this. Fixed in `docs/configuration.md`, the `data-sync` requirement and
  its documentation scenario, design.md's Risks, and the local runbook (with a per-row consequence table).
- [x] 9.3 **The `auto_statistics_types` claim was false for the pinned build.** `system.settings_changes`
  advertises `'' → 'minmax, uniq'` at 26.2 and 26.3, but the effective default in
  `system.merge_tree_settings` on 26.3.21.7 is empty — auto statistics are **off**, so they cost no merge
  CPU or disk. Corrected in the proposal, with the general lesson: a settings-changes row records an
  intended default, not the default the build ships. This was the one claim taken from `settings_changes`
  without checking the effective value — the same error group 8 corrected elsewhere.
- [x] 9.4 **`structured-query` was amended with no delta spec.** The change edited
  `openspec/specs/structured-query/spec.md` (the cross-version storage exemption) while listing only
  `data-sync` under Modified Capabilities and shipping only a `data-sync` delta; `openspec validate` passes
  either way, so nothing caught it. Added `specs/structured-query/spec.md` (MODIFIED **Supported function
  catalog**) and the missing Modified-Capabilities entry.
- [x] 9.5 **The pin was asserted on one of the two clients.** `RowWriteVersionMonotonicityIntegrationTest`
  covered only the catalog path. Added `usageLogWritesAreNotRoutedThroughTheServerAsyncInsertBuffer` for
  `UsageLogWriter` — the client that writes `dial_usage_log`/`rate_analytics`. Negative control run for the
  new case: with the pin removed it reads `5` instead of `0`. Also confirmed the oracle cannot go vacuous —
  `system.asynchronous_insert_log` does not exist on a fresh server, but `SYSTEM FLUSH LOGS` creates it,
  and the test flushes first.
- [x] 9.6 **Named the driver dependency where the bump will happen.** The Flyway path's insulation is real
  — `jdbc-v2` 0.9.8's `ConnectionImpl` puts `async_insert=0`/`wait_end_of_query=0` into its default
  `QuerySettings` (decompiled), and the proxy `com.clickhouse.jdbc.ClickHouseDriver` that Flyway resolves
  defaults to v2 — but it lived only in an archived design doc. Recorded in `build.gradle` beside the
  pinned version, where a bump is decided: whoever drops it must pin the setting on the migration path.
  No URL parameter was added; unknown properties reach `Client.Builder` through `JdbcConfiguration`, so
  adding one to the startup path buys nothing here and risks a startup abort.
- [x] 9.7 **Trimmed the prose.** `docs/configuration.md`'s two blockquotes were rewritten around a
  serialization-floor table instead of one dense paragraph each; the `ExprTranslator` provenance blocks
  were cut roughly in half (the enlarged `json_extract_bool` note spent ten lines on a case the spec
  exempts); the test's class Javadoc is shorter. The non-exposure inventory stays in the archived proposal
  and the local runbook rather than in the property reference.
- [x] 9.8 **Left the open items where they will be read.** design.md's open questions are now also in the
  maintainers' follow-up notes, because two of them gate
  the upgrade and the change that raised them is archived. The `table-management` validation stays a
  follow-up change rather than a rider, as decided.
- [x] 9.9 Re-ran `./gradlew clean build` green after all of the above (the group-5 build predated the
  `async_insert` pin and the new test).
