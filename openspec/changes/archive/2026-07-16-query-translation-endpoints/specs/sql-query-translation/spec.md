# sql-query-translation Delta

## MODIFIED Requirements

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
- `LIMIT`/`OFFSET` (`OffsetPage`; downstream limit governance applies — a `LIMIT` above the
  structured-query maximum is rejected, not clamped)
- `SELECT DISTINCT` in row mode (`distinct=true`)
- predicates: `=`, `<>`, `<`, `>`, `<=`, `>=` → `eq/ne/lt/gt/le/ge`; `IN (literal list)` →
  `in`; `IS NULL`/`IS NOT NULL` → `eq`/`ne` against a null literal
- functions, 1:1 with the DSL catalog: `lower, upper, length, trim, abs, width_bucket,
  date_bin` (scalar); `count` (incl. `COUNT(*)` and `COUNT(DISTINCT x)`), `sum`, `avg`, `min`,
  `max`, `percentile_cont`, `percentile_disc` (aggregate). Every DSL catalog name SHALL be an
  accepted SQL spelling — the DSL→SQL renderer emits catalog names verbatim, so a rejected
  catalog spelling would break the render/translate round-trip. For `length`, the standard SQL
  synonyms `char_length` and `character_length` SHALL also be accepted, all mapping to the DSL
  `length` function.

#### Scenario: Aggregate query round-trips
- **WHEN** a client submits `SELECT model, count(*) AS n FROM dial_usage_log WHERE price > 0 GROUP BY model HAVING count(*) > 10 ORDER BY n DESC LIMIT 50`
- **THEN** the translator produces a StructuredQuery with `mode="aggregate"`, `filter` on price, `group_by=["model"]`, a `count` FnExpr aliased `n`, `having` on count, sort on `n` desc, and an OffsetPage with limit 50

#### Scenario: Row query with defaults
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log`
- **THEN** the translator produces `mode="row"` with no filter and offset paging with the default limit

#### Scenario: SELECT DISTINCT maps to distinct flag
- **WHEN** a client submits `SELECT DISTINCT model FROM dial_usage_log`
- **THEN** the translator produces a row-mode query with `distinct=true`, not an aggregate query

#### Scenario: Every length spelling maps to the DSL length function
- **WHEN** a client submits `SELECT length(deployment) FROM dial_usage_log`, or the same statement spelled `char_length(...)` or `character_length(...)`
- **THEN** each translates to the DSL `length` function on `deployment`

### Requirement: LIKE maps only to the contains operators

`LIKE` and `ILIKE` predicates SHALL be translated only when the pattern is exactly `'%text%'`
where `text` contains no `%` or `_` metacharacters, and SHALL map to the DSL contains operators
with standard SQL semantics preserved: `LIKE` → `co` / `NOT LIKE` → `nc` (case-sensitive,
standard SQL semantics), and `ILIKE` → `ico` / `NOT ILIKE` → `inc` (case-insensitive). There is
no case-sensitivity deviation: each SQL form translates to the DSL operator with identical
semantics. Any other pattern (prefix/suffix wildcards, metacharacters inside the text, an
`ESCAPE` clause, a non-literal pattern) SHALL be rejected.

#### Scenario: Case-sensitive contains pattern accepted

- **WHEN** a client submits `WHERE model LIKE '%gpt%'`
- **THEN** the predicate translates to `co` on `model` with value `gpt` (case-sensitive)

#### Scenario: Case-insensitive contains pattern accepted

- **WHEN** a client submits `WHERE model ILIKE '%gpt%'`
- **THEN** the predicate translates to `ico` on `model` with value `gpt`

#### Scenario: Negated contains patterns map to the not-contains operators

- **WHEN** a client submits `WHERE model NOT LIKE '%gpt%'` or `WHERE model NOT ILIKE '%gpt%'`
- **THEN** the predicates translate to `nc` and `inc` respectively, on `model` with value `gpt`

#### Scenario: Prefix pattern rejected

- **WHEN** a client submits `WHERE model LIKE 'gpt%'`
- **THEN** translation fails with an unsupported-construct error
