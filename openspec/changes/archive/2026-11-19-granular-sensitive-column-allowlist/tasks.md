## 1. Configuration

- [x] 1.1 Add a `SensitiveColumnPolicy` value type (`ALL`, `NONE`, bare names, `table.column` entries). Parse it from
      `List<String>`: trim entries, drop blank and repeated ones, and reject `*` or `none` mixed with other entries
      or an entry with an empty table or column part. Each error names `sync.sensitive-columns` and the entry.
- [x] 1.2 In `SyncProperties`, replace `Boolean sensitiveColumnsEnabled` with `List<String> sensitiveColumns`
      (unset means `*`, empty means `NONE`), exposed as a `SensitiveColumnPolicy`. Fail startup in
      `MappingConfiguration` when `sync.sensitive-columns-enabled` is bound (via `Binder`), naming
      `sync.sensitive-columns-enabled` and `sync.sensitive-columns`. Update the Javadoc.
- [x] 1.3 `application.yml`: replace the `sensitive-columns-enabled` line with
      `sensitive-columns: ${SYNC_SENSITIVE_COLUMNS:*}`.
- [x] 1.4 `SyncPropertiesTest`: default `*`; `none` and empty give `NONE`; a list keeps its entries; mixed keywords
      fail startup. `SensitiveColumnPolicyTest`: entries with an empty part fail, and the removed variable fails
      whatever its value (`false`, `true`, empty) with the replacement named; `SensitiveColumnIngestIntegrationTest`
      checks that failure through `MappingConfiguration.syncTargets`.

## 2. Exclusion resolution

- [x] 2.1 `SensitiveColumnExclusion.resolve` takes the policy. It excludes sensitive columns that no entry names on
      that table, and fails on an entry that names no column of any target (bare) or of its table (qualified,
      including a non-target table); an entry naming only a non-sensitive column logs a warning. It keeps the undefined-table check and runs the key-column check
      on the excluded set only.
- [x] 2.2 Log one `INFO` line per target table listing excluded and written sensitive columns. Reword the
      exception and log messages to name `sync.sensitive-columns`.
- [x] 2.3 `MappingConfiguration`: skip the catalog for `ALL`, and expose each target's excluded set to the
      request-delta stage (on `SyncTarget` or a dedicated bean). Update the class Javadoc and the `SyncTarget`
      Javadoc.
- [x] 2.4 `SensitiveColumnExclusionTest`: allowlist subset, qualified entry, unknown bare and qualified entries,
      non-sensitive column entry warns, a named column whose flag was cleared starts, a named sensitive key column passes, an excluded sensitive key column fails,
      undefined table fails under a list.

## 3. Request-delta interaction

- [x] 3.1 `RequestDeltaStages`: under `delta`, fail startup for a qualifying target whose excluded set holds
      `request_cache_control`, naming the table, `sync.sensitive-columns` and the column.
- [x] 3.2 Show the "mode has no effect" warning when no target qualifies and `request_body` is excluded from at
      least one target, instead of keying it on the old boolean.
- [x] 3.3 `RequestDeltaStagesTest`: the delta failure; no failure under `whole-with-path` with `request_body` only;
      the reworded warning, naming each excluded column a stage needs (`request_body`, `user_hash`). A delta target
      writing both columns is covered end to end by the `delta` + `request_body,request_cache_control` case of 4.1.

## 4. Integration tests

- [x] 4.1 `SensitiveColumnIngestIntegrationTest`: parameterize over `*`, `none`, `request_tags,jwt_claims` and
      `rate_analytics.comment`, and assert the stored payload and rate rows per the spec scenarios. Add the
      `delta` + `request_body,request_cache_control` case.
- [x] 4.2 `SensitiveColumnsDisabledStartupIntegrationTest`: switch to `sync.sensitive-columns=none`. Cover the failure
      on `sync.sensitive-columns-enabled=false` by calling `MappingConfiguration.syncTargets` with that environment
      (no extra Spring context, which would cost another Testcontainers-backed startup).

## 5. Documentation

- [x] 5.1 `docs/configuration.md`: replace the `SYNC_SENSITIVE_COLUMNS_ENABLED` row with a one-line
      `SYNC_SENSITIVE_COLUMNS` / `sync.sensitive-columns` row (default `*`) that says to write `none`, not an empty
      value, which tooling that prunes empty variables turns back into `*`.
- [x] 5.2 `docs/release-0.23-upgrade.md` (the change ships as 0.23.1): leave the 0.23.0 notes unchanged and add a
      closing `## 8. 0.23.1` section (value mapping, startup failure on the old variable, the new checks, the `delta` +
      `request_cache_control` rule, rollback), with a pointer paragraph under the introduction. In
      `docs/release-0.24-upgrade.md`, reword the one note that names `SYNC_SENSITIVE_COLUMNS_ENABLED=false` to point
      at 0.23 §8.
- [x] 5.3 Check `docs/heavy-columns.md` and `docs/recommended-catalog/README.md` for references to the old switch
      and update them if any exist.

## 6. Verification

- [x] 6.1 `openspec validate granular-sensitive-column-allowlist --strict` passes.
- [x] 6.2 `git grep -nP 'sensitive-columns-enabled|SENSITIVE_COLUMNS_ENABLED'` hits only the removed-variable check,
      its tests, the spec's removal clause and the release notes.
- [x] 6.3 Run `.claude/skills/pr-ready/hygiene-sweep.pl` on the branch.
- [x] 6.4 `./gradlew clean build` passes.
