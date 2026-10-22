## Why

A measure over a nullable input column returns NULL when its group contains no non-null value —
`sum`, `max`, `min` and `avg` all do. If the target column is declared non-nullable, ClickHouse does not
refuse that NULL: it converts it to the column's type default and stores **`0`**.

Observed live. A daily rollup over `dial_usage_log` produced buckets where every row's `total_tokens` was
NULL. Preview showed them honestly as `null`. The refresh stored `0`, `0`, `0` — and a stored `0` is
indistinguishable from a genuine zero total. "No token data for this deployment-day" and "this
deployment-day used zero tokens" are different facts, and the rollup collapses them into the second one,
which is the one a consumer will believe and sum into a report.

The capability already legislates against exactly this failure mode one paragraph over: an `avg` bound to
a `long` column is rejected because "ClickHouse converts rather than refusing on insert, which would store
a truncated mean or total as an exact whole number". Same engine behaviour, same silence, same class of
wrong number — and nullability is currently unguarded. `AggregateSpecValidator` and `AggregateCompiler`
contain no nullability handling at all.

This is not a rare shape. The service's own scan source declares eleven `Nullable(...)` columns, including
`total_tokens` and `total_price`, the two most obvious things anyone would roll up.

**The catalog does not currently know that.** `column_mapping.nullable` was added with a `FALSE` default
and the `dial_usage_log` seed predates it, so every one of that table's columns is recorded as
non-nullable while eleven of them can hold NULL. (`rate_analytics`, seeded after the flag existed, is
correct — this is one table's gap, not a missing convention.) A rule keyed on the catalog would therefore
be **inert on exactly the case that motivated it**, so this change aligns the catalog with the physical
DDL as part of the same work. That alignment is worth doing on its own account: schema discovery
publishes the flag, so consumers have been told these columns cannot be null while reads return nulls
from them.

## What Changes

- Target shape governance gains a nullability rule: a measure that can return NULL SHALL require a
  nullable target column, rejected with HTTP 422 naming the column and the input column that makes it
  nullable.
- Which measures can return NULL is derived from the **input column's** nullability, not the function:
  `count` never returns NULL; `sum`, `avg`, `min` and `max` return NULL exactly when their input column is
  nullable and the group is empty of values.
- The rule is enforced at registration and re-applied on the refresh path, like every other rule on this
  path, so a column that turns nullable *after* a pipeline is registered stops the refresh instead of
  silently changing what the stored zeros mean.

Not a breaking change: the aggregate pipeline capability is not deployed anywhere yet, so no registered
pipeline and no materialised rollup exists to migrate. The rule ships before the first one is written.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `aggregate-pipeline`: target shape governance gains the nullable-measure rule, alongside the existing
  fractional-measure and time-bucket-width rules it is a sibling of.
- `table-management`: gains a requirement that a built-in source's seeded `nullable` flag agrees with the
  column's physical ClickHouse type — the sibling of the existing seeded-`sensitive` requirement, except
  that this value is not an operator's to choose, since the DDL decides it.

## Impact

- **Code**: `AggregateSpecValidator` gains the rule (it already resolves both the input binding and the
  target column, so the nullability of each is in hand); the validation error joins the existing
  `pipeline_validation_failed` 422 family.
- **Catalog**: a Flyway migration setting `nullable = TRUE` on the eleven `dial_usage_log` columns whose
  ClickHouse type is `Nullable(...)`. A `column_mapping` update only — nullability is fixed at DDL, so
  nothing materializes as an `ALTER`.
- **API**: no shape change to any endpoint. One more declaration is refused, with a message naming the
  fix. Schema discovery starts reporting `nullable: true` for those eleven columns, which is a visible
  change to `GET /v1/queries/schema` and `GET /v1/tables/dial_usage_log` — and is the value those
  surfaces should always have reported.
- **Migration**: no data migration. The aggregate capability is undeployed, so there are no stored
  pipelines to audit and no rollup rows carrying the zeros this rule prevents.
- **Not affected**: group keys (a key is the input value itself and its exact-type rule already governs
  it), `count`, the compiler, and the refresh algorithm.
