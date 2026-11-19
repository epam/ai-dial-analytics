# Review

## What shipped

- `clickhouse/V18__dial_usage_log_budget.sql` appends `spending_limits_mode`, `budget_cost_object`,
  `budget_outcome`, `budget_policy` and `budget_assignment`, each `LowCardinality(Nullable(String))` except
  `budget_assignment`, which is `Nullable(String) CODEC(ZSTD(1))`.
- `postgres/V59__seed_budget_columns.sql` registers them: `string`, nullable, tag `cost`, `budget_policy` and
  `budget_assignment` sensitive, none heavy, ordinals 41 to 45.
- `mapping/dial-usage-log.yaml` maps them from `spending_limits.mode`, `.cost_object`, `.outcome`, `.policy` and
  `.assignment`.
- `docs/column-tags.md` (the `cost` row) and `local_env/seed/seed_dial_usage_log.py` (a budget on two users in
  three, derived from the user rather than the RNG).

## Verification

- `MappingEngineTest.carriesTheReportedSpendingLimitsAndNullWhenBudgetsDidNotApply` covers an assigned budget, a
  policy that applies by itself on an empty project, an exhausted shadow check, mode `off`, no object, a blank
  policy and an unknown outcome. `writesOnlyDeclaredColumnsInOrder`, `MappingFileLoaderTest` (40 columns, last
  `budget_assignment`) and `SchemaValidatorTest.liveSchema()` carry the
  new columns.
- `BudgetColumnsIngestIntegrationTest` ingests five records through the poller and reads the columns back from
  ClickHouse.
- `QuerySchemaDiscoveryIntegrationTest` and `TableManagementIntegrationTest` count 41 own columns and check the
  `cost` tag; `seededSpendingLimitColumnsAreNullableCostAttributes` checks the catalog metadata.
- `./gradlew clean build` passes.
- The hygiene sweep reports only the `created:` date in `.openspec.yaml`, which every change carries.

## Drift from design.md

None.
