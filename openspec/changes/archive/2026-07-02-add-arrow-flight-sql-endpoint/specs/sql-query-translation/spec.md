# sql-query-translation

The SQL → `StructuredQuery` mapping contract: how external SQL text is parsed and validated
with Apache Calcite against the query-schema-discovery catalog, lowered to a relational plan,
and translated into the structured query DSL — and which SQL constructs are rejected because
the DSL cannot express them.

## ADDED Requirements

### Requirement: SQL is validated against the query-schema catalog
SQL statements SHALL be parsed and validated with Calcite against a schema derived from the
same catalog as `GET /v1/queries/entities` and `GET /v1/queries/entities/schema/{name}`: each
ACTIVE queryable entity is a table whose columns are the entity's flat fields (source columns
plus dotted enrichment columns). Identifier matching SHALL be case-insensitive. Enrichment
columns SHALL be addressable only as double-quoted flat identifiers (e.g.
`"deployment_ref.display_name"`). Fields of type `object` or `array` SHALL be excluded from
the SQL-visible schema. References to unknown tables or columns SHALL be rejected at
validation time.

#### Scenario: Known entity and columns validate
- **WHEN** a client submits `SELECT request_id, "deployment_ref.display_name" FROM dial_usage_log`
- **THEN** validation succeeds and both columns resolve to the entity's flat fields

#### Scenario: Unknown column rejected
- **WHEN** a client submits `SELECT no_such_column FROM dial_usage_log`
- **THEN** translation fails with a validation error naming the unknown column, before execution

#### Scenario: Object field invisible to SQL
- **WHEN** an entity has a field of type `object`
- **THEN** selecting or filtering on that field fails validation as an unknown column

### Requirement: Supported SQL subset maps onto StructuredQuery
The translator SHALL map exactly the following SQL surface onto `StructuredQuery`, and SHALL
produce a query semantically equivalent to the corresponding structured-query JSON:
- single-entity `SELECT` (one table reference; the entity becomes `entity`)
- projection of columns and supported function calls, with `AS` aliases (`select`)
- `WHERE` as an AND/OR/NOT tree of supported predicates (`filter`)
- `GROUP BY` on columns or select aliases (`group_by`, `mode="aggregate"`); absence of
  aggregation yields `mode="row"`
- `HAVING` (`having`)
- `ORDER BY` with per-key `ASC`/`DESC` and `NULLS FIRST`/`LAST` (`sort`)
- `LIMIT`/`OFFSET` (`OffsetPage`; downstream clamping applies)
- `SELECT DISTINCT` in row mode (`distinct=true`)
- predicates: `=`, `<>`, `<`, `>`, `<=`, `>=` → `eq/ne/lt/gt/le/ge`; `IN (literal list)` →
  `in`; `IS NULL`/`IS NOT NULL` → `eq`/`ne` against a null literal
- functions, 1:1 with the DSL catalog: `lower, upper, length, trim, abs, width_bucket,
  date_bin` (scalar); `count` (incl. `COUNT(*)` and `COUNT(DISTINCT x)`), `sum`, `avg`, `min`,
  `max`, `percentile_cont`, `percentile_disc` (aggregate)

#### Scenario: Aggregate query round-trips
- **WHEN** a client submits `SELECT model, count() AS n FROM dial_usage_log WHERE price > 0 GROUP BY model HAVING count() > 10 ORDER BY n DESC LIMIT 50`
- **THEN** the translator produces a StructuredQuery with `mode="aggregate"`, `filter` on price, `group_by=["model"]`, a `count` FnExpr aliased `n`, `having` on count, sort on `n` desc, and an OffsetPage with limit 50

#### Scenario: Row query with defaults
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log`
- **THEN** the translator produces `mode="row"` with no filter and offset paging with the default limit

#### Scenario: SELECT DISTINCT maps to distinct flag
- **WHEN** a client submits `SELECT DISTINCT model FROM dial_usage_log`
- **THEN** the translator produces a row-mode query with `distinct=true`, not an aggregate query

### Requirement: LIKE maps only to the contains operators
`LIKE` predicates SHALL be translated only when the pattern is exactly `'%text%'` where `text`
contains no `%` or `_` metacharacters: `LIKE` → `co`, `NOT LIKE` → `nc`. Because the DSL's
contains operators are case-insensitive, this translation is case-insensitive even though SQL
`LIKE` is case-sensitive; this deviation SHALL be documented to clients. Any other pattern
SHALL be rejected.

#### Scenario: Contains pattern accepted
- **WHEN** a client submits `WHERE model LIKE '%gpt%'`
- **THEN** the predicate translates to `co` on `model` with value `gpt`

#### Scenario: Prefix pattern rejected
- **WHEN** a client submits `WHERE model LIKE 'gpt%'`
- **THEN** translation fails with an unsupported-construct error

### Requirement: Literals translate with DSL value typing
SQL literals SHALL convert to `ValueExpr` entries typed by the DSL's `value_type` set (string,
integer, long, decimal, boolean, date, timestamp, uuid, null), with values encoded as the DSL
expects: timestamps as epoch-milliseconds, dates as ISO-8601 dates, numbers as plain decimal
strings. Validator-injected identity or widening casts within the same type family SHALL be
unwrapped and ignored; a `DATE` widened to `TIMESTAMP` (e.g. a date literal compared to a
timestamp column) SHALL convert to midnight UTC of that date. Explicit or narrowing `CAST`s
SHALL be rejected. SQL runtime parameters (`?`) SHALL be rejected.

#### Scenario: Timestamp literal
- **WHEN** a filter compares a timestamp field to `TIMESTAMP '2026-07-01 00:00:00'`
- **THEN** the ValueExpr carries value_type `timestamp` and the epoch-millis encoding of that instant

#### Scenario: Parameter marker rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE model = ?`
- **THEN** translation fails with an unsupported-construct error

### Requirement: Unsupported SQL fails closed before execution
Any SQL construct outside the supported subset SHALL be rejected at translation time with an
error identifying the construct, and nothing SHALL be sent to ClickHouse. Rejected constructs
include (non-exhaustively): multi-statement input; non-SELECT statements; explicit JOINs;
CTEs; subqueries in predicates (`IN (SELECT …)`, `EXISTS`) and derived tables containing
`ORDER BY`/`LIMIT` (a plain derived table that flattens losslessly into the single-entity shape
MAY be accepted, since its semantics are exactly expressible); UNION/INTERSECT/EXCEPT; window
functions; ROLLUP/CUBE/GROUPING SETS;
arithmetic operators (`+ - * /`); `CASE`; explicit `CAST`; `BETWEEN` (unless expressible — see
range expansion below); functions outside the DSL catalog; `IN` with a subquery or non-literal
elements. Range predicates that Calcite lowers into `SEARCH`/Sarg form SHALL be expanded back
into supported comparison conjunctions or `in` lists where the Sarg consists of point values
and/or simple bounds; Sargs that cannot be expanded losslessly SHALL be rejected.

#### Scenario: Join rejected
- **WHEN** a client submits `SELECT a.x FROM t1 a JOIN t2 b ON a.id = b.id`
- **THEN** translation fails with an unsupported-construct error and ClickHouse is not contacted

#### Scenario: IN list expanded from Sarg
- **WHEN** a client submits `WHERE model IN ('a', 'b', 'c')`
- **THEN** the predicate translates to the DSL `in` operator with a three-element literal array

#### Scenario: Range conjunction expanded from Sarg
- **WHEN** a client submits `WHERE price > 10 AND price <= 20`
- **THEN** the predicate translates to an `and` of `gt` and `le` comparisons

#### Scenario: Arithmetic rejected
- **WHEN** a client submits `SELECT price * 2 FROM dial_usage_log`
- **THEN** translation fails with an unsupported-construct error
