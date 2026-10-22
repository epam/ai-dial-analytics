## Why

A `source` schema may name a column declared `nullable: true` in its `ordering_key` or as its
`partition_by`, and nothing rejects it. ClickHouse does — it refuses both a nullable sorting key and a
nullable partition key while `allow_nullable_key` is disabled (which ADAS never enables):

```
CREATE TABLE t (a Nullable(DateTime), b String) ENGINE = MergeTree PARTITION BY toYYYYMMDD(a) ORDER BY b
→ Code: 44. DB::Exception: Partition key contains nullable columns, but merge tree setting
  `allow_nullable_key` is disabled. (ILLEGAL_COLUMN)   -- verified on clickhouse-server 26.3.27.3

CREATE TABLE t (a Nullable(DateTime), b String) ENGINE = MergeTree ORDER BY (a)
→ Code: 44. DB::Exception: Sorting key contains nullable columns, ... (ILLEGAL_COLUMN)
```

Because `POST /v1/tables/{name}/schema` persists the columns and keys *before* issuing the
`CREATE TABLE`, such a request does not fail as a clean 422 with nothing written: it persists the
catalog rows, then surfaces the raw ClickHouse error and leaves the definition in `status=FAILED`
carrying a schema that can never materialize. That is exactly the failure mode
`TableColumnRules.requireKeyable` was introduced to prevent for `map` columns, and the one
`TableTtlRules` already prevents for a nullable TTL column — nullability on the physical keys is the
remaining hole in the same rule.

## What Changes

- A `source`'s physical keys SHALL reference non-nullable columns: every `ordering_key` entry and the
  `partition_by` column are rejected with HTTP 422 when the named column is declared `nullable: true`
  (explicitly, or by an enrichment's nullable-by-default resolution — see Impact).
- The refusal happens at validation time, before any catalog row is written and before any DDL is
  issued, so the definition is left untouched in `PENDING`/`FAILED` — the same
  "reject before persisting anything" guarantee the rest of the schema validation already gives.
- The 422 message names the field and column and says why, in the style of the existing TTL and
  scan-metadata nullability refusals (`ordering_key column 'x' must be non-nullable (ClickHouse
  rejects a nullable sorting key)`).
- **Not a breaking change in practice**: any request the new rule rejects is a request that already
  failed at `CREATE TABLE`. What changes is the *shape* of the failure — a 422 naming the problem and
  nothing persisted, instead of a 500-class ClickHouse error and a poisoned `FAILED` definition.
- Explicitly **out of scope**: an enrichment's `grain_key`. Its grain column is materialized
  non-nullable regardless of the source column's own flag (`TableSchemaCreationService.enrichmentSpec`
  renders it with `nullable = false`), so ClickHouse accepts it and there is no failure to prevent.
  Adding a restriction there would reject working enrichments for a reason ClickHouse does not have.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-management`: the "Schema validation" requirement gains the non-nullability rule for
  `ordering_key` and `partition_by`, with a scenario for each refusal plus one pinning the deliberate
  exception (an enrichment's `grain_key` over a nullable source column stays accepted). The rule joins
  the ones already stated in that same enumeration for the `identity_column`/`version_column` pair
  ("neither SHALL be nullable") and for a `ttl` column. The define-and-materialize requirement needs no
  delta of its own — it already defers to "Schema validation" for internal consistency.

## Impact

- **APIs**: `POST /v1/tables/{name}/schema` only. `POST /v1/tables` cannot carry keys (identity-only,
  422 today) and `PATCH /v1/tables/{name}/schema` cannot change keys or a column's nullability (both
  frozen after materialization), so no other surface can reach the rule.
- **Code**: `TableColumnRules.requireKeyable` (extended with the column's nullability) and its six
  call sites in `CatalogSchemaWriter` (draft persist) and `TableSchemaCreationService` (materialize
  spec build).
- **Catalog / migrations**: none. Nullability is fixed at DDL and the rule is checked at declaration
  time only.
- **Seeded built-ins**: unaffected — `dial_usage_log` and `rate_analytics` are seeded `ACTIVE` and
  never re-validated, and both already satisfy the rule (their key columns `project_id`,
  `deployment`, `request_time`, `trace_id`, `core_span_id` are none of the columns
  `V21__backfill_dial_usage_log_nullability.sql` marks nullable).
- **Enrichments**: an enrichment's own user columns default to `nullable: true`, but an enrichment
  declares no `ordering_key`/`partition_by` (its ordering is its grain key), so the default cannot
  trip the new rule. A `source` column defaults non-nullable, so only an explicit `nullable: true` on
  a source column can.
- **Config / docs**: no new properties, no `docs/configuration.md` change.
