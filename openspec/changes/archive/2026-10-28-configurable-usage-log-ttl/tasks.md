## 1. Configuration

- [x] 1.1 Add `UsageLogRetentionProperties` (`sync.usage-log.ttl-days`, `sync.usage-log.table`): unset means the provisioned year, `-1` no TTL, `0` and below `-1` rejected naming the property
- [x] 1.2 Bind both keys in `application.yml` (`SYNC_USAGE_LOG_TTL_DAYS`, `SYNC_TABLE`)
- [x] 1.3 Unit tests for the record: default, positive, `-1`, `0`/`-2` rejected, blank table

## 2. Reconciliation

- [x] 2.1 Generalise `PayloadTtlReconciler` into `IngestTtlReconciler` reconciling the usage log then the payload table, each skipped independently when absent
- [x] 2.2 Reject a payload retention longer than the usage-log retention in the constructor, naming both properties
- [x] 2.3 Call it from `ClickHouseMigrationRunner`
- [x] 2.4 Unit tests: year default matches `toIntervalYear(1)`, `365` days does not, cross-property check (longer, equal, `-1` combinations)
- [x] 2.5 Integration test on a copy of the migrated `dial_usage_log`: default leaves the year, shorter value replaces it, `-1` removes it, hand-set value reverted

## 3. Docs and specs

- [x] 3.1 `docs/configuration.md`: one row for `SYNC_USAGE_LOG_TTL_DAYS`; mention the bound on the payload row
- [x] 3.2 Replace fixed "one year" wording for `dial_usage_log` where it states the retention as fixed (`docs/`, `InheritedTtl` Javadoc, mapping comments)
- [x] 3.3 Record the `dial_usage_log` `MODIFY TTL` measurement in design.md D5
- [x] 3.4 `./gradlew clean build`; `openspec validate configurable-usage-log-ttl`
