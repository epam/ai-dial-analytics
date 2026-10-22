## 0. Ordering note

- [x] 0.1 This change's `enrichment-registry` delta restates the governance gate **cumulatively**,
      including the evaluator-type dispatch introduced by `class1-sql-enrichment`. That change was
      implemented first, so the restated requirement matches the shipped code

## 1. Group trigger in the registry (A1)

- [x] 1.1 Confirm the next free Postgres migration number (expected `V14`) — `V14` free after this
      phase's `V12`/`V13`
- [x] 1.2 `db/migration/postgres/V15__group_trigger.sql`: `group_by TEXT` + `ready_when JSONB` on
      `rule`; relax the `trigger_kind` CHECK to `('ON_INGEST','SCHEDULE','GROUP')`; add
      `rule_group_by_iff_group`. `rule_cron_iff_schedule` left alone — it already forces
      `trigger_cron IS NULL` for `GROUP`
- [x] 1.3 `TriggerKind` += `GROUP`; `RuleService.parseTrigger`'s allowed-list message updated
- [x] 1.4 New `model/ReadyWhen` (`signal`, `idle`, `maxStaleness`, `costCeiling`), JSONB-mapped; added
      `groupBy` + `readyWhen` to `RuleEntity` and `RuleRegistration`
- [x] 1.5 `CreateRuleRequest` / `RuleDto` gain `group_by` + `ready_when` (new `ReadyWhenDto` with
      snake_case wire names); MapStruct maps the nested record both ways, so the sync feed inlines them
- [x] 1.6 `RuleGovernanceValidator.validateGroupTrigger`: `group_by` equals the target grain;
      `group_by`/`ready_when` rejected on a non-group trigger; `ready_when.signal` parsed via
      `FilterSqlValidator` with its columns entitlement-checked; durations parsed via
      `DurationStyle.detectAndParse` (accepts `10m` and `PT10M`) and required positive; positive
      `cost_ceiling`; at least one readiness condition; `GROUP` rejected for a `type=sql` evaluator

## 2. Group-aware scan (A2a)

- [x] 2.1 `ScanService`: for a `GROUP` rule add `group_by` and the `ready_when.signal` columns to the
      projection; everything else (keyset, cursor, safety-lag, `final=1`, sensitive re-check) unchanged
- [x] 2.2 Confirmed the version/identity columns are already projected under their own names, so the
      activity timestamp needed no new code

## 3. Group assembly endpoint (A2b)

- [x] 3.1 `configuration/GroupFetchProperties` (`default-rows`, `max-rows`, `max-bytes` as `DataSize`)
      + bare `application.yml` entries
- [x] 3.2 `support/SensitiveProjectionGuard` was already extracted by `class1-sql-enrichment`; reused here
- [x] 3.3 `service/enrichment/GroupFetchService`: 422 for a non-group rule, `RuleAuthorizer`, the shared
      sensitive guard, then `WHERE toString(group_by) = :key AND <filter>` + safety-lag,
      `ORDER BY (version, identity)`, `final=1`
- [x] 3.4 Row cap via a `limit + 1` fetch and byte cap via accumulated serialized size; either sets
      `truncated`. A single member larger than the byte cap is still returned — an empty group would be
      indistinguishable from "no members" and would strand the caller
- [x] 3.5 `web/dto/enrichment/GroupFetch{Request,Response}` + `POST /v1/rules/{id}/groups:fetch` on
      `RuleDataController`, added to the `oidc` URL matcher; unknown/empty group → 200 with `rows: []`

## 4. Tests + build

- [x] 4.1 `GroupEnrichmentIntegrationTest` (14 tests) — a `group` rule registers with `group_by` +
      `ready_when`, and the sync feed inlines them
- [x] 4.2 Same class: governance rejects `group_by` ≠ grain, an unparseable `ready_when.signal`,
      `group_by` on a non-group trigger, a bad duration, a non-positive ceiling, an empty `ready_when`,
      and a `type=sql` evaluator on the group trigger
- [x] 4.3 `RuleDataAuthorizationIntegrationTest`: a granted-but-non-entitled caller gets 403
      `sensitive_column_not_entitled` from `groups:fetch` on a body-reading group rule; `FULL_ADMIN`
      is served. (The registration-side rejection is unreachable in `oidc` mode — rule create is
      `@FullAdminOnly` — which the shipped registry spec already records)
- [x] 4.4 `GroupEnrichmentIntegrationTest`: the dirty-mark scan projects `group_by` + the signal column
      + the activity timestamp, and keeps row-scan paging semantics
- [x] 4.5 Same class: `groups:fetch` returns members filtered/ordered; the row cap sets `truncated`;
      an unknown group returns `rows: []` with 200; a non-group rule is 422; an unknown rule is 404
- [x] 4.6 Covered by 4.3
- [x] 4.7 Same class: a `/v1/queries/execute` query joins the `chat_id`-grained enrichment onto
      `dial_usage_log`, the 0..1 LEFT JOIN does not inflate the three source rows, and the group label
      resolves for both members of its group and null for the ungrouped row
- [x] 4.8 `docs/configuration.md` group for the group-fetch caps
- [x] 4.9 `./gradlew clean build` green (Docker/Testcontainers PG+CH) — 728 tests, 0 failures
- [x] 4.10 `openspec validate group-enrichment --strict` passes

## 5. Knock-on change

- [x] 5.1 `RuleServiceTest.rejectsDisallowedTriggerKind` used `group` as its disallowed example, which
      this change makes valid; it now uses `push`. Worth revisiting if `push` is ever added
