## ADDED Requirements

### Requirement: Unnamed SQL select expressions take PostgreSQL-derived names

The translator SHALL NOT surface the planner's synthetic `EXPR$<n>` names. An output column that the
SQL statement does not name — no `AS` alias, not a bare column reference, not a star — SHALL take a
PostgreSQL-derived name, assigned **before the statement is validated** so that the derived name is a
real alias in the validated statement:

- a function-syntax call SHALL take its lowercase function name as written, so `count(*)` → `count` and
  `char_length(x)` → `char_length` (the spelling the caller used, even where the DSL maps that call onto
  another catalog function);
- an aggregate wrapped in `WITHIN GROUP` SHALL take the wrapped aggregate's name, so
  `percentile_cont(0.5) WITHIN GROUP (ORDER BY x)` → `percentile_cont`;
- any other unnamed expression SHALL take `?column?`.

The resulting name list SHALL be made unique by the same positional `_<n>` suffixing the
structured-query naming contract defines, so a derived name can never produce two identically named
outputs. An `AS` alias, a bare column reference, and star-expanded columns SHALL keep the name the SQL
statement gives them.

The derived name SHALL be used consistently in the produced `StructuredQuery` `select`, in the
`ResultColumn` descriptors that carry the result schema (REST SQL row keys and the Flight SQL Arrow
schema), and in any `group_by`/`sort` reference the translator resolves through a select output.

Because the derived name is present before validation, it SHALL be referenceable from the statement
exactly as an explicit `AS` alias is, with PostgreSQL's per-clause ambiguity resolution: a bare
`ORDER BY <name>` SHALL resolve to the output column, while a bare `GROUP BY <name>` that matches both
an input column and an output column SHALL resolve to the **input column**.

A `GROUP BY` on a **computed expression** is expressible in the DSL only as the name of the select
output carrying that expression, and a `group_by` name that is also a column of the entity resolves to
the column (input columns win, above). The translator SHALL therefore, for a computed group key, use a
select output whose name is **not** a column of the entity, and SHALL reject the statement with a 400
`bad_request` naming the colliding name when every select output carrying that expression is named
after an entity column — it SHALL NOT emit a `group_by` name that would regroup the query by an
unrelated column. This applies to derived and explicit names alike, since both can shadow a column.

The mirror shape SHALL fail the same way: when the statement groups by a plain **column** while a select
item is explicitly aliased after that column but projects a different expression, the DSL `group_by` name
would again be both a column and a foreign alias — a key the structured-query surface rejects as
ambiguous — so the translator SHALL reject the statement with a 400 `bad_request` naming the collision
rather than emit a document that only fails when executed.

The produced DSL SHALL omit a select entry's `as` only where omitting it leaves the **whole** select
list resolving, by the structured-query naming contract, to exactly the same output names; otherwise
the resolved name SHALL be carried as an explicit `as`. Redundancy is therefore judged against the
produced select list itself, never against a hypothetical list in which no entry carries an alias: an
entry that keeps its alias reserves that name and shifts the names the remaining derived entries take,
so an explicit alias of the shape `<base>_<n>` can be indispensable even though some other entry would
derive that very name. A DSL document produced from SQL SHALL therefore always execute to exactly the
reported `ResultColumn` names.

#### Scenario: Unnamed aggregate takes the function name

- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log`
- **THEN** the single result column is named `count` (never `EXPR$0`), and the produced DSL select entry
  carries no `as`

#### Scenario: Unnamed scalar call takes the function name

- **WHEN** a client submits `SELECT lower(deployment) FROM dial_usage_log`
- **THEN** the result column is named `lower` and the produced DSL select entry carries no `as`

#### Scenario: Explicit alias is preserved

- **WHEN** a client submits `SELECT count(*) AS n FROM dial_usage_log`
- **THEN** the result column is named `n` and the produced DSL select entry carries `"as": "n"`

#### Scenario: Derived name colliding with an alias is suffixed

- **WHEN** a client submits `SELECT count(*), count(deployment) AS "count" FROM dial_usage_log`
- **THEN** the aliased column is named `count`, the unnamed aggregate is named `count_1`, and both names
  appear in the `ResultColumn` list in select order

#### Scenario: An alias colliding with another entry's derived suffix is kept

- **WHEN** a client submits `SELECT count(*) AS foo, count(deployment) AS count_1 FROM dial_usage_log`
- **THEN** both entries carry their `as` (`foo` and `count_1`) — dropping `count_1` as "already derivable"
  would make the produced DSL execute to `foo`/`count` instead of the reported `foo`/`count_1`

#### Scenario: Unnamed literal takes PostgreSQL's placeholder name

- **WHEN** a client submits a statement projecting a bare literal (e.g. `SELECT 1 FROM dial_usage_log`)
- **THEN** the result column is named `?column?`

#### Scenario: A derived name is orderable without an alias

- **WHEN** a client submits `SELECT model, count(*) FROM dial_usage_log GROUP BY model ORDER BY count DESC`
- **THEN** the statement is accepted (the derived name resolves like an explicit alias, as in
  PostgreSQL) and the sort is resolved against the `count` output

#### Scenario: Computed group key named after an entity column is rejected

- **WHEN** a client submits `SELECT date_bin(1, 'hour', request_time) AS deployment, count(*) FROM dial_usage_log GROUP BY date_bin(1, 'hour', request_time)`
- **THEN** the statement is rejected with a 400 `bad_request` naming `deployment` as both the output name
  and a column of the entity — the query is never executed grouped by the `deployment` column

#### Scenario: Grouped column shadowed by a select alias of another expression is rejected

- **WHEN** a client submits `SELECT deployment AS parent_deployment, parent_deployment AS deployment, count(*) FROM dial_usage_log GROUP BY deployment, parent_deployment`
- **THEN** the statement is rejected with a 400 `bad_request` naming the alias/column collision, because
  each `group_by` name would be both a column and the alias of a different expression

#### Scenario: Grouped column projected under its own name stays accepted

- **WHEN** a client submits `SELECT request_time AS request_time, count(*) FROM dial_usage_log GROUP BY request_time`
- **THEN** `group_by` is `["request_time"]` and the statement translates normally — the alias names the
  same column the grouping does

#### Scenario: Computed group key uses the output name that is not an entity column

- **WHEN** a client submits `SELECT date_bin(1, 'hour', request_time) AS deployment, date_bin(1, 'hour', request_time) AS bucket, count(*) FROM dial_usage_log GROUP BY date_bin(1, 'hour', request_time)`
- **THEN** `group_by` is `["bucket"]`, which resolves to the hour-bucket output rather than to the
  `deployment` column

#### Scenario: SQL synonym is named as written

- **WHEN** a client submits `SELECT char_length(deployment) FROM dial_usage_log`
- **THEN** the result column is named `char_length` — the spelling the caller used — while the produced
  DSL entry is a `length` call carrying `"as": "char_length"`

#### Scenario: Ordered-set aggregate is named by the aggregate, not the WITHIN GROUP wrapper

- **WHEN** a client submits `SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY price) FROM dial_usage_log`
- **THEN** the result column is named `percentile_cont`

#### Scenario: Star projection keeps its column names

- **WHEN** a client submits `SELECT * FROM dial_usage_log`
- **THEN** every result column keeps its own source column name and no derived name is introduced

## MODIFIED Requirements

### Requirement: Supported SQL subset maps onto StructuredQuery

The translator SHALL map exactly the following SQL surface onto `StructuredQuery`, and SHALL
produce a query semantically equivalent to the corresponding structured-query JSON:
- single-entity `SELECT` (one table reference; the entity becomes `entity`)
- projection of columns and supported function calls, with **optional** `AS` aliases (`select`) — an
  unnamed expression takes a derived name instead of being rejected or named `EXPR$n`
- `WHERE` as an AND/OR/NOT tree of supported predicates (`filter`)
- `GROUP BY` on columns or select aliases, or on a computed expression that also appears in the
  select list under an output name that is not an entity column (`group_by`,
  `mode="aggregate"`); absence of aggregation yields `mode="row"`
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

#### Scenario: Aggregate query without an alias round-trips
- **WHEN** a client submits `SELECT model, count(*) FROM dial_usage_log GROUP BY model ORDER BY count(*) DESC`
- **THEN** the translator produces an aggregate query whose outputs are named `model` and `count`, with
  the sort resolved against the `count` output
