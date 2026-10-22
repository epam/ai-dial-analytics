## Why

Every read ADAS issues runs under `SETTINGS final = 1`, with no opt-out, so a caller cannot express what
ClickHouse lets them express: read the raw parts, or deduplicate one table of a join and not another. That
breaks the "a ClickHouse query executes identically" goal, and it charges every user-facing read the cost of
a `FINAL` merge whether or not the reader needs exact, duplicate-free rows. `FINAL` should be something a
query says, as it is in ClickHouse — with a migration window in which today's always-on behaviour stays the
default, so existing consumers do not change results the day this ships.

## What Changes

- **SQL surfaces** (`POST /v1/queries/execute-sql`, `execute-sql-csv`, `translate-sql`, Flight SQL) accept
  ClickHouse's `FINAL` table modifier after any entity reference — `FROM dial_usage_log FINAL`,
  `FROM dial_usage_log AS l FINAL`, on a `JOIN`ed entity, inside a CTE, derived table or set-operation
  branch. A final-marked entity deduplicates every table of it that collapses versions — its source when
  `upsert_by_key`, **and every enrichment it implicitly joins**; an unmarked entity reads raw parts.
- **JSON DSL** (`POST /v1/queries/execute`, `execute-csv`, `translate`) gains an optional top-level
  boolean `final`. Absent: final when the entity is *final-capable* (its source is `upsert_by_key`, or it
  has an enrichment); `true`: final; `false`: raw read.
- `FINAL` / `"final": true` on an `append` source with no enrichment is rejected with **HTTP 400
  `illegal_final`**, as ClickHouse rejects `FINAL` on a plain `MergeTree` (`ILLEGAL_FINAL`). An `append`
  source with an enrichment reads final through the enrichment (`FINAL` renders on the join only).
- Translation carries the mark losslessly both ways: a final JSON query (explicit, or absent over a
  collapsing source) renders `FINAL`; a SQL reference without `FINAL` translates to an explicit
  `"final": false`.
- **BREAKING (behind a flag):** the always-on `final = 1` is removed from user-facing reads. New property
  `query.force-final` (`FORCE_FINAL_QUERIES`, **default `true`**) keeps today's behaviour: while on, every
  version-collapsing entity of every user-facing read is read final whatever the query says. Turning it off
  is what changes results — for SQL written without `FINAL` and for JSON sending `"final": false`.
- **BREAKING (edge):** an enrichment registered over a hand-written plain `MergeTree` now fails a final read
  that joins it (`ILLEGAL_FINAL`), where the former `final = 1` setting was a silent no-op on it. The catalog
  already requires every enrichment to be version-collapsing; the seeded ones are.
- `illegal_final` is enforced whether or not the flag is on, so a client learns of it now, not the day the
  flag is turned off.
- Internal reads are **unchanged** and stay under `final = 1` regardless of the flag: enrichment scans,
  group fetch, Class-1 materialize, aggregate refresh and preview.
- The partition-local switch `do_not_merge_across_partitions_select_final = 1` is kept; on a user read it
  is attached when every final-marked entity of the statement is partition-local.
- `GET /v1/queries/entities` items gain `supports_final`, so a client knows where `FINAL` is legal.
- Out of scope: moving the shipped Grafana dashboards and doc examples onto `FINAL` — a follow-up, done
  panel by panel before the flag is turned off.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `structured-query`: "Read result deduplication" becomes opt-in per query with the `final` field, the
  `illegal_final` rule, the `query.force-final` migration flag, internal reads always final, and the
  partition-local rule restated over final-marked entities; "SQL translation" renders `FINAL` into the SQL
  instead of relying on the statement setting.
- `sql-query-translation`: new requirement for the `FINAL` table modifier (placement, scope over implicit
  enrichment joins, rejection on `append` sources and on derived tables / CTE names).
- `query-translation-endpoint`: new requirement for the lossless `FINAL` ↔ `"final"` mapping.
- `query-schema-discovery`: "Queryable entity catalog" items carry `supports_final`.
- `data-sync`: "Settled ingest day partitions are force-merged…" no longer claims every read deduplicates at
  query time with `final = 1`; a force-merge leaves final reads unchanged and collapses what a raw read sees.
- `table-management`: "Table write discipline" no longer promises that an `upsert_by_key` source
  deduplicates with no query-side opt-in.

`rest-sql-endpoint`, `flight-sql-endpoint` and the composite / set-operation requirements of
`sql-query-translation` say their statements run under "the same `final` deduplication" as a single-entity
read; that stays true — they share the rules restated above — so they need no delta.

## Impact

- **API:** new optional `final` on the structured-query body; new `supports_final` on entity catalog items;
  `FINAL` accepted in SQL; new error code `illegal_final` (400). No endpoint added or removed.
- **Config:** `query.force-final` / `FORCE_FINAL_QUERIES` (default `true`) in `application.yml` and one row
  in `docs/configuration.md`.
- **Code:** `StructuredQuery`, `StructuredQueryBuilder`, `StructuredQueryExecutor`, `FinalSettings`,
  `CompositeQueryBuilder`, the SQL front end (`SqlQueryFrontEnd` + a new pre-parse `FINAL` pass), the DSL→SQL
  renderer, `QueryEntityDto`, `DefaultExceptionHandler`, and a new `FinalDecision`.
  `ClickHouseInsertSelectExecutor` and the scan paths keep today's settings.
- **Consumers:** none change while the flag is on. With it off, SQL without `FINAL` (the Grafana
  dashboards, doc examples, MCP `adas_execute_sql` callers) reads raw parts; migrating them is the
  follow-up.
