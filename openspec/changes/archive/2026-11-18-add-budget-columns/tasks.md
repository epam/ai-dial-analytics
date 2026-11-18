## 1. Physical columns

- [x] 1.1 Add `src/main/resources/db/migration/clickhouse/V18__dial_usage_log_budget.sql`: one
  `ALTER TABLE dial_usage_log` adding `spending_limits_mode`, `budget_cost_object`, `budget_outcome`,
  `budget_policy` and `budget_assignment`, each
  `LowCardinality(Nullable(String))` with `IF NOT EXISTS`, appended in that order. One-line header pointing at
  the `data-sync` spec.
- [x] 1.2 Confirm that no in-flight branch claims ClickHouse `V18` or PostgreSQL `V59`.

## 2. Catalog registration

- [x] 2.1 Add `src/main/resources/db/migration/postgres/V59__seed_budget_columns.sql`, modelled on
  `V49__seed_route_columns.sql`: five `column_mapping` rows, `'string'`, `nullable TRUE`, tag `'cost'`,
  `sensitive FALSE`, `heavy FALSE`, display names `Spending limits mode`, `Budget cost object`, `Budget outcome`,
  `Budget policy`, `Budget assignment`, ordinals `41` to `45`. The descriptions say when the value is NULL and that pre-existing rows read NULL;
  `budget_cost_object`'s says it can differ from `project_id`; `budget_assignment`'s says it is NULL for a
  policy that applies by itself; `budget_outcome`'s says `no_budget` and `exhausted` appear in shadow mode only.

## 3. Mapping

- [x] 3.1 In `src/main/resources/mapping/dial-usage-log.yaml`, append the five columns after `route_type`,
  each `source: spending_limits.<field>`, `kind: string`, `nullable: true`, and update the header comment where it names
  the appended columns.

## 4. Tests

- [x] 4.1 `MappingFileLoaderTest`: the last column is `budget_assignment`.
- [x] 4.2 `MappingEngineTest`: add a test covering an assigned
  budget, a self-applying policy (no `assignment`) on an empty `project.id` with `cost_object` `ORG`, an exhausted
  shadow check, mode `off`, no object (all NULL), a blank `policy` and an unknown outcome. Extend
  `writesOnlyDeclaredColumnsInOrder` with the five columns. (Test name:
  `carriesTheReportedSpendingLimitsAndNullWhenBudgetsDidNotApply`.)
- [x] 4.3 `SchemaValidatorTest.liveSchema()`: add the five `LowCardinality(Nullable(String))` columns.
- [x] 4.4 `DialUsageLogTestModel`: add the five catalog columns.
- [x] 4.5 `QuerySchemaDiscoveryIntegrationTest` and `TableManagementIntegrationTest`: raise the
  `dial_usage_log` column, description and display-name counts by five; assert the five columns are under tag
  `cost`; add `seededSpendingLimitColumnsAreNullableCostAttributes` for type, nullable, tag, sensitive, heavy and
  display name.
- [x] 4.6 Add `BudgetColumnsIngestIntegrationTest`: through the poller path, ingest records with an assigned
  budget, a self-applying policy and an empty project, an exhausted shadow check, mode `off`, and no object, and
  read the five columns back from ClickHouse.
- [x] 4.7 `local_env/seed/seed_dial_usage_log.py`: fill the five columns on some model rows.

## 5. Docs

- [x] 5.1 `docs/column-tags.md`: add the five columns to the `cost` tag row.
- [x] 5.2 Confirm that no other doc needs an edit: `docs/configuration.md` (no property),
  `docs/heavy-columns.md` (not heavy), `docs/recommended-catalog/adas-catalog.json`.

## 6. Verify

- [x] 6.1 Run `openspec validate add-budget-columns --strict`.
- [x] 6.2 Run the public-repo hygiene sweep (`.claude/skills/pr-ready/hygiene-sweep.pl`) over the branch.
- [x] 6.3 Run `./gradlew clean build`.
