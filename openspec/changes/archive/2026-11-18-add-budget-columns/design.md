## Context

DIAL Core writes a nested `spending_limits` object (`mode`, `cost_object`, `outcome`, `policy`, `assignment`) on the
analytics record of a token caller's model call whose limits it checks, and omits it on every other record.
`proposal.md` describes the producer's contract.

`dial_usage_log` is a system-owned, ACTIVE table. Adding a column to it involves four pieces, as in
`2026-10-23-add-route-columns`, which this change follows:
- a ClickHouse `ALTER`;
- a PostgreSQL catalog row, which decides what is queryable;
- a mapping entry, which decides what is persisted;
- startup validation (`SchemaValidator`), which refuses a mapping entry whose column is missing or whose type
  family or nullability disagrees with the table.

## Goals / Non-Goals

**Goals:**
- Persist the five fields verbatim on every newly ingested row, and store NULL when a field is absent.
- Make the columns queryable and discoverable on every query surface, with descriptions that state their NULL
  semantics.

**Non-Goals:**
- Deriving a cost object from `project_id` for rows without the object.
- Counting refused requests, which have no row.
- Backfilling rows ingested before the columns existed.
- Spending-limit dimensions in the recommended catalog or Grafana dashboards.

## Decisions

### Five flat columns, not one JSON column

A consumer groups and filters spend by mode, cost object, outcome and policy. Flat strings group directly and
dictionary-encode well. **One `JSON` column `spending_limits`: rejected.** Every query would need a path extraction,
the catalog type would be `object`, and the five values are a closed, small set that the producer defines.

**A derived key (`cost_object/policy`): rejected.** A policy path is unique on its own, and an assignment path
already contains its cost object. A consumer that needs a combined key groups on the columns it needs, as with the
route columns.

### Physical type: `LowCardinality(Nullable(String))`, not `Enum`

Modes, outcomes, cost objects and policies come from a small set of values or of configuration, so dictionary
encoding fits. `route_name`, `route_type` and `parent_deployment` already use this type, so `SchemaValidator` and the
catalog `string` type need no new handling.

**`budget_assignment` is `Nullable(String) CODEC(ZSTD(1))`.** Assignments can be per user, so the column can hold
one value per user, and past some ten thousand distinct values per part `LowCardinality`'s dictionaries cost memory
and CPU instead of saving them. `core_parent_span_id` uses the same type, which is still the `string` kind.

**`Enum8` for `spending_limits_mode` and `budget_outcome`: rejected,** for the reason the route columns rejected
it. The producer owns the domain: a new value would fail the ClickHouse insert and its whole batch, and widening an
enum means dropping and re-adding the column.

**Non-nullable `String` with `''` for absent: rejected.** Absent is the normal case for API key calls and
application and router rows. NULL says exactly "budgets did not apply", and `spending_limits_mode IS NOT NULL`
filters on it.

### Column names

`spending_limits_mode` names the mode after the producer's object, since it describes the check, not a budget.
The other four take a `budget_` prefix: they keep together in name order and avoid a bare `outcome`, `policy` or
`assignment` column that reads ambiguously next to `project_id`. **`spending_limits_` on all five: rejected** as
long names for the columns a consumer groups by most.

### Column position: appended

`ADD COLUMN` without `AFTER` appends after `route_type`, in the producer's key order: `spending_limits_mode`,
`budget_cost_object`, `budget_outcome`, `budget_policy`, `budget_assignment`. The mapping YAML follows the DDL order,
and the catalog ordinals continue past the current maximum (40, from the route columns), so they are **41** to
**45**. Tag-ordered discovery groups the columns with the other `cost` columns regardless of ordinal.

### Catalog metadata

- **Tag `cost`.** That role tag groups the columns a consumer uses to answer "what did it cost?": `total_price` and
  `deployment_price`. `docs/column-tags.md` says that only the two prices are measures in it. The spending-limit columns answer "who paid for it, and under which limits?". The tag is
  already in `dial_usage_log`'s `tag_order`, so no ordering migration is needed. **Tag `principal` (with
  `project_id`): rejected.** A budget is not who made the request. An application call carries the initiating
  user's budget.
- **`budget_policy` and `budget_assignment` sensitive.** They hold paths an administrator chooses, and nothing stops
  one from naming a user (a per-user assignment at `project-a/jane.doe@example.com`), which would link `user_hash`
  back to a person. As with `request_tags`, they are classified by what they can store. `dial_usage_log` is a
  system table, so the flag changes only by a later migration. A reader without the clearance can still group spend
  by mode, cost object and outcome.
- **The other three not sensitive.** They hold modes, outcomes and a cost object, which is a project id;
  `project_id`, which can hold the same value, is not sensitive either.
- **Not heavy.** The values are short.
- **Display names:** `Spending limits mode`, `Budget cost object`, `Budget outcome`, `Budget policy`,
  `Budget assignment`.
- **Descriptions** state when the value is NULL and that rows from before the column existed read NULL. For
  `budget_cost_object`, the description says it can differ from `project_id`. For `budget_outcome`, it says
  `no_budget` and `exhausted` appear in shadow mode only. For the mode and the outcome, it says other values are
  stored as reported.

### Mapping: direct, nullable, no transform

Each entry is `source: spending_limits.<field>`, `kind: string`, `nullable: true`. `MappingEngine`'s fallback
already maps an absent or blank value of a nullable column to NULL, including when the whole object is absent. No
deriver or transform is added.

### Two migrations, one release

The ClickHouse migration is `V18__dial_usage_log_budget.sql`, and the catalog migration is
`V59__seed_budget_columns.sql`. Both are the next free number in their folder, and no other branch claims them.
Each carries a one-line header that points at the spec.

## Risks / Trade-offs

- **An operator runs with migrations disabled.** `SchemaValidator` refuses to start until `V18` is applied.
  → Intended fail-fast behavior, stated in the `data-sync` provisioning requirement.
- **ADAS is upgraded before DIAL Core.** → The five columns stay NULL, which is indistinguishable from "budgets did
  not apply", and corrects itself when Core is upgraded.
- **A consumer sums `total_price` by budget across every row.** → Only model rows carry the object, and on a model
  row `total_price` equals `deployment_price`.
- **A consumer reads `budget_outcome = 'exhausted'` as a count of refusals.** → In enforce mode a refused request
  has no row. The column description says so.
- **A policy or assignment is renamed.** → The producer then charges a new budget with new counters, so the rows
  follow the producer.

## Migration Plan

1. Deploy the release. Flyway applies `clickhouse/V18` (metadata only) and `postgres/V59` at startup, then
   `SchemaValidator` reconciles the mapping.
2. With migrations disabled, an operator applies `V18`'s `ALTER` before rolling out the new image.
3. Rollback: an older image ignores the extra columns, because its mapping doesn't declare them and the validator
   checks only declared columns. ClickHouse fills the columns with NULL on insert. Flyway's default
   `ignoreMigrationPatterns` (`*:future`) tolerates the already-applied `V18`/`V59`.
