## Context

DIAL Core emits `route_name` and `route_type` as flat top-level fields on analytics records that a route
served. It omits both fields on every other record. `proposal.md` quotes the producer code.

`dial_usage_log` is a system-owned, ACTIVE table. Adding a column to it involves four pieces:
- a ClickHouse `ALTER`;
- a PostgreSQL catalog row, which decides what is queryable;
- a mapping entry, which decides what is persisted;
- startup validation (`SchemaValidator`), which refuses a mapping entry whose column is missing or whose
  type family or nullability disagrees with the table.

The closest precedent is `2026-08-18-persist-assembled-response-and-bound-query-cost`, with
`clickhouse/V7` and `postgres/V29`. This change follows its shape.

## Goals / Non-Goals

**Goals:**
- Persist both fields verbatim on every newly ingested row. Store NULL when a field is absent.
- Make both columns queryable and discoverable on every query surface, with descriptions that state
  their NULL semantics and the non-uniqueness of `route_name`.

**Non-Goals:**
- Reclassifying `event_kind`, which still comes from the request URI.
- Backfilling rows ingested before the columns existed.
- Adding route dimensions to the recommended catalog's rollups or to Grafana dashboards.

## Decisions

### Physical type: `LowCardinality(Nullable(String))` for both columns

Route names come from a bounded configuration and `route_type` has two values today, so dictionary
encoding fits. `parent_deployment` and `response_upstream_uri` already use the same type, so
`SchemaValidator` and the catalog `string` type need no new handling.

**`route_type` as `Nullable(Enum8('global' = 1, 'application' = 2))` with catalog type `enum`: rejected.**
The domain is closed today, but the producer owns it, not ADAS.
- A third value from a newer Core would fail the ClickHouse insert. Because `RowBatchSink` flushes in
  batches, one such row fails the whole flush, and the run retries it forever.
- Widening an enum domain means dropping and re-adding the column (see `postgres/V33`), which loses the
  stored values.

A string costs nothing at this cardinality. The spec says that unknown values are stored as reported.

**Non-nullable `String` with `''` for absent: rejected.** For `operation_duration_ms`, "absent" meant only
an older producer. Here "absent" is the normal, frequent case: most rows are not route requests. NULL
says exactly "not served by a route". It is also what `route_type IS NOT NULL` filters on, and an
aggregate pipeline's nullability rules see it correctly.

### Column position: appended

`ADD COLUMN` without `AFTER` appends after `response_body_bytes`, so the physical order is `route_name`,
then `route_type`.
- The mapping YAML follows the DDL order.
- The catalog ordinals continue past the current maximum. `postgres/V38` left ordinal 38 as a gap, so the
  new rows take **39** and **40**. They are not placed next to `deployment`, because catalog order drives
  wildcard projection and must match the physical table.
- Tag-ordered discovery groups the columns with `deployment` regardless of ordinal.

**`ADD COLUMN … AFTER deployment`: rejected.** To keep ordinal tracking physical order, it would also
mean renumbering every later catalog ordinal on a table whose catalog only a migration can edit. Tag
order already controls presentation, so that churn buys nothing.

### Catalog metadata

- **Tag `deployment`.** That tag answers "what did they call?" and already covers `deployment`,
  `parent_deployment` and `execution_path`. A route is the same kind of answer. The tag is already in
  `dial_usage_log`'s `tag_order`, so no ordering migration is needed.
- **Not sensitive.** The values are operator configuration keys, not user content.
- **Not heavy.** The values are short.
- **Display names:** `Route name` and `Route type`.
- **Descriptions** state:
  - NULL means no route served the request;
  - rows from before the column existed read NULL;
  - `route_name` is unique only together with `route_type` and, for application routes, `deployment`.

### Mapping: direct, nullable, no transform

Each entry is `source: <field>`, `kind: string`, `nullable: true`. `MappingEngine`'s fallback already maps
an absent or blank value of a nullable column to NULL. So a producer-sent empty string also stores NULL,
which matches the producer's own contract of omitting rather than emitting an empty name. No deriver or
transform is added.

### Two migrations, one release

The ClickHouse migration is `V15__dial_usage_log_route.sql`. The catalog migration is
`V49__seed_route_columns.sql`. Both versions are the next free number in their folder, and no other
branch claims them. Their header comments follow the public-repo hygiene rules, because applied
migrations are frozen.

## Risks / Trade-offs

- **An operator runs with migrations disabled.** In that case `SchemaValidator` refuses to start until
  `V15` is applied. → This is intended fail-fast behavior. It is stated in the migration header and in the
  `data-sync` provisioning requirement, which already requires operators to apply every later migration.
- **ADAS is upgraded before DIAL Core.** → Both columns stay NULL. This is indistinguishable from
  "no route", but harmless, and it corrects itself when Core is upgraded.
- **Route names collide across kinds and applications.** → This is documented in the catalog
  descriptions and the spec. No synthetic key is derived.
- **`event_kind` still misses global routes.** → This is out of scope by decision. `route_type` is now
  the reliable signal, and the spec says `event_kind` is unchanged.

## Migration Plan

1. Deploy the release. Flyway applies `clickhouse/V15` (metadata-only) and `postgres/V49` at startup,
   then `SchemaValidator` reconciles the mapping.
2. With migrations disabled, an operator applies `V15`'s `ALTER` before rolling out the new image.
3. Rollback: an older image ignores the extra columns. Its mapping doesn't declare them, and the
   validator only checks declared columns. ClickHouse fills the columns with NULL on insert. Flyway's
   default `ignoreMigrationPatterns` (`*:future`) tolerates the already-applied `V15`/`V49`. The catalog
   rows stay visible, and reads return NULL for rows that older images ingest.
