## Why

DIAL Core charges a token caller's model calls to budgets. A budget is a cost policy that grants a cost object (a
project) a day or month limit. When Core checks a model call's limits, the analytics record of that call now
describes the check in a nested object, written right after `project`:

```json
"project": {"id": "project-a"},
"spending_limits": {"mode": "enforce", "cost_object": "project-a", "outcome": "paid",
                    "policy": "engineering-pro", "assignment": "project-a/a1"},
```

These properties come from the producer's code and tests, not from assumption:

- **`mode`** is Core's spending-limits mode, `off`, `shadow` or `enforce`. With `off` it is the only key.
- **`cost_object`** is the cost object Core charges: the token's project claim, or Core's configured default cost
  object when the token has none. In that second case `project.id` is empty, so `project_id` cannot stand in for
  the cost object.
- **`outcome`** is `paid` (a budget pays), `no_budget` (none covers the caller and the model) or `exhausted` (every
  such budget is used up). Core refuses the last two in `enforce` mode and writes no row for a refused request, so
  they appear in `shadow` mode only.
- **`policy`** and **`assignment`** name the budget that paid, with `paid` only. `assignment` is omitted for a policy
  that applies by itself.
- **The whole object is omitted, never `null`,** for an API key caller and on every row that is not a token
  caller's model call, including application and router rows. An application or router row rolls up the cost of the
  calls below it, so naming a budget there would count the spend twice.

ADAS persists only the columns its mapping declares, so today it drops the object during ingest. `dial_usage_log`
can break spend down by project (`project_id`) and user, but not by budget, the "package" a user was given. It also
cannot tell a request under spending limits off from one charged to a budget.

## What Changes

- Add five physical columns to `dial_usage_log` with a new ClickHouse migration: `spending_limits_mode`,
  `budget_cost_object`, `budget_outcome`, `budget_policy` and `budget_assignment`, all
  `Nullable(String)`, dictionary-encoded (`LowCardinality`) except `budget_assignment`.
  - NULL in all five means "budgets did not apply to this request".
  - Rows ingested before this change read NULL until they are re-ingested.
- Register the five columns in the PostgreSQL catalog with a new catalog migration:
  - type `string`, nullable;
  - tag `cost`, next to `total_price` and `deployment_price`;
  - `budget_policy` and `budget_assignment` sensitive, the other three not; none heavy;
  - ordinals after the current maximum, matching the physical append order.
- Map the five columns in `mapping/dial-usage-log.yaml` as direct, nullable string sources from
  `spending_limits.*`. The change needs no new transform or deriver, and no configuration surface.
- `spending_limits_mode` and `budget_outcome` are free strings, not ClickHouse `Enum`s, so a new value from Core
  ingests without an ADAS release.
- `project_id` is unchanged. The budget columns are not derived from it.

No breaking change:
- The columns are additive and nullable.
- Existing queries, pipelines and dashboards are unaffected.
- An ADAS upgraded ahead of DIAL Core stores NULL in the five columns.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`:
  - Gains a requirement that the producer-reported spending-limits check is persisted, and that a record
    without it stores NULL.
  - The table-provisioning requirement's column inventory adds the five columns to the producer-optional
    direct set.
- `table-management`: the "Seeded column nullability matches the physical table" requirement's list of
  nullable `dial_usage_log` columns adds the five columns.

## Impact

- **ClickHouse:** a new migration `src/main/resources/db/migration/clickhouse/V18__dial_usage_log_budget.sql`.
  It runs `ALTER TABLE … ADD COLUMN`, which changes metadata only. It doesn't touch the sort key or the
  partitioning.
- **Catalog:** a new migration `src/main/resources/db/migration/postgres/V59__seed_budget_columns.sql`. This
  surfaces the columns through `/v1/queries` schema discovery, `/v1/tables/dial_usage_log`, SQL, Flight SQL
  and CSV export. No `/v1` endpoint contract changes.
- **Mapping:** five entries in `src/main/resources/mapping/dial-usage-log.yaml`.
- **Startup validation:** `SchemaValidator` refuses the mapping until the DDL is applied. Operators who run
  with `clickhouse.migrations.enabled=false` must apply `V18` before they upgrade.
- **Tests:** updated column counts and lists in the mapping, schema-validator, query-model, discovery and
  table-management tests; an end-to-end ingest assertion.
- **Docs and local tooling:** `docs/column-tags.md` (the `cost` tag row) and
  `local_env/seed/seed_dial_usage_log.py`.
- **Not in scope:**
  - Counting refused requests: the producer writes no row for them.
  - A backfill of historical rows.
  - Budget dimensions in the recommended catalog's rollups or in Grafana dashboards.
  - Row-level security on the budget columns. Existing row policies keyed on `project_id` are unchanged.
