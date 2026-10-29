## 1. Configuration

- [x] 1.1 `PayloadRetentionProperties`: `ttlDays` stays `null` when unset; `application.yml` binds `${SYNC_PAYLOAD_TTL_DAYS:}`
- [x] 1.2 `UsageLogRetentionProperties`: replace the provisioned-year reading of `null` with "unset"
- [x] 1.3 Property tests for unset on both records

## 2. Reconciliation

- [x] 2.1 `IngestTtlReconciler` skips a table whose property is unset and logs its live `TTL` at INFO
- [x] 2.2 Bound with both set stays in the constructor; with one set, check in `reconcile()` against the live `TTL` before any `ALTER`; with neither set, WARN on a live violation
- [x] 2.3 Live `TTL` to days: widest for the payload, narrowest for the usage log, like units compared directly; unreadable skips the bound with a WARN, missing skips it quietly; payload altered first when the usage log shrinks
- [x] 2.4 Unit tests for each branch; integration tests: unset leaves a hand-set `TTL`, removal keeps the last one, a set value bounded by the live one fails before any `ALTER`

## 3. Docs

- [x] 3.1 `docs/configuration.md` rows: unset keeps the live `TTL`; `90` restores the payload's provisioned value
- [x] 3.2 `docs/release-0.4-upgrade.md`: drop the hand-set-`TTL` warning and the breaking-change row; keep the new-configuration entries
- [x] 3.3 Javadoc and docs that describe unset as the default
- [ ] 3.4 `./gradlew clean build`; `openspec validate unset-retention-keeps-live-ttl --strict`
