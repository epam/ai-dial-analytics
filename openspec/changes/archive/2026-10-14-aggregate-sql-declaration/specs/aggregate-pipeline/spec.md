## ADDED Requirements

### Requirement: An aggregate pipeline may be declared as one SQL statement

An `aggregate` pipeline's `spec` SHALL accept, in place of the form declaration (`trigger.group_by`,
`measures`, `filter`), a single aggregate-only field **`sql`** holding exactly one
`INSERT INTO <target> (<column>, …) SELECT …` statement. The two declarations SHALL be mutually
exclusive: a request carrying `sql` together with `trigger.group_by`, `measures` or `filter` SHALL be
rejected with HTTP 422 `pipeline_validation_failed` naming the conflicting field. `filter` is checked on
its own, because it is a field the `enrich` kind shares.

`target` SHALL stay required, and the statement's `INSERT` target SHALL equal it, or the declaration
SHALL be rejected with HTTP 422 `pipeline_validation_failed` naming both. `inputs` SHALL be optional for
an SQL declaration. When it is omitted, the input SHALL be the statement's single source. When it is
given, it SHALL equal that source, or the declaration SHALL be rejected with HTTP 422 naming both.

ADAS SHALL **parse and re-render** the statement and SHALL NOT pass the authored text to ClickHouse. The
text is an authoring format. What executes is a statement ADAS compiles from the parsed block, and every
governance gate of the form declaration applies to it (see the requirements that follow). The authored
text SHALL be stored **verbatim**, and `GET /v1/pipelines/{name}` SHALL return it unchanged, comments and
layout included. Export and audit SHALL carry the same text.

#### Scenario: An SQL declaration is stored and returned verbatim

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline whose `sql` is
  `INSERT INTO usage_daily (day, deployment, tokens) SELECT date_trunc('day', request_time), deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1, 2`
  with a `target` of `usage_daily` and no `inputs`
- **THEN** the pipeline is registered, its input is `dial_usage_log`, and `GET /v1/pipelines/{name}`
  returns the `sql` text byte for byte, with no `measures` and no `trigger.group_by`

#### Scenario: An SQL declaration mixed with a form declaration is refused

- **WHEN** a registration or patch carries `sql` together with `measures` or `trigger.group_by`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the conflicting field,
  and nothing is stored

#### Scenario: An SQL declaration with a filter is refused

- **WHEN** a registration or patch carries `sql` together with `filter`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `filter` and stating
  that the statement's own `WHERE` is the membership predicate

#### Scenario: An INSERT target other than the pipeline's target is refused

- **WHEN** the statement inserts into `usage_hourly` and the pipeline's `target` is `usage_daily`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both tables

#### Scenario: Declared inputs that disagree with the statement are refused

- **WHEN** the pipeline declares `inputs` of `dial_usage_log` and the statement reads `turns`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both tables

#### Scenario: The executed statement is ADAS's rendering, not the authored text

- **GIVEN** an armed SQL declaration whose text carries comments and a `date_trunc` with no time zone
- **WHEN** a refresh runs
- **THEN** the statement sent to ClickHouse is the one ADAS compiled, carrying the UTC pin, the window and
  the dirty-key restriction, and none of the authored comments, while the stored `sql` is unchanged

### Requirement: An SQL declaration is exactly one INSERT … SELECT naming every target column

The `sql` field SHALL hold exactly one statement, an `INSERT INTO <target> (<columns>) <query>` whose
source is a query. Any other shape SHALL be rejected with HTTP 422 `pipeline_validation_failed`, with a
message that names the clause at fault. A trailing `;` SHALL be tolerated.

The column list SHALL be present and SHALL name every column of the target except `_updated_at`, each
exactly once. This is the form path's rule that every target column is written and none defaults. The
column list SHALL map to the source's select list **by position**, and the two SHALL have the same length.
`_updated_at` SHALL NOT be named, because ADAS stamps it.

The SQL front end's known limits SHALL surface as a 422 naming the problem rather than a server error:
backtick quoting (only double-quoted identifiers are supported), a `SETTINGS` clause, and a `FORMAT`
clause. ADAS SHALL screen for `FORMAT` before parsing, because the parser can otherwise read it as JSON
value syntax and report a misleading error. The screen SHALL refuse a `FORMAT JSON`
and a trailing `FORMAT <name>` clause, and SHALL NOT refuse a column or alias that is merely named
`format`.

#### Scenario: More than one statement is refused

- **WHEN** `sql` holds two statements separated by `;`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that exactly one
  statement is accepted

#### Scenario: A bare SELECT is refused

- **WHEN** `sql` holds a `SELECT` with no `INSERT INTO`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the
  declaration is an `INSERT INTO <target> (<columns>) SELECT …`

#### Scenario: An INSERT with no column list is refused

- **WHEN** `sql` is `INSERT INTO usage_daily SELECT …` with no column list
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the column
  list is required

#### Scenario: A VALUES source is refused

- **WHEN** `sql` is `INSERT INTO usage_daily (day, deployment, tokens) VALUES (…)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the source
  must be a `SELECT` over the input

#### Scenario: A column list missing a target column is refused

- **WHEN** the target declares `day`, `deployment` and `tokens`, and the column list names only `day` and
  `deployment`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `tokens`

#### Scenario: A column list naming a target column twice is refused

- **WHEN** the column list names `deployment` twice
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the duplicated column

#### Scenario: A column list naming _updated_at is refused

- **WHEN** the column list names `_updated_at`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that ADAS stamps
  `_updated_at` itself

#### Scenario: A column list and a select list of different lengths are refused

- **WHEN** the column list names three columns and the `SELECT` projects four expressions
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` giving both counts

#### Scenario: A SETTINGS or FORMAT clause is refused

- **WHEN** the statement ends in a `SETTINGS` or a `FORMAT` clause
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the clause, rather
  than a validation error about grouping or conditions

#### Scenario: A column named format is not mistaken for a FORMAT clause

- **WHEN** the statement reads an input column, or names an alias, called `format`
- **THEN** the `FORMAT` screen does not refuse the statement

### Requirement: An SQL declaration reads its input once and nothing else

An SQL declaration's source SHALL reference the pipeline input exactly once, and SHALL reference no
other entity. Enrichments of the input SHALL be reachable only through their qualified flat names
(`"<enrichment>.<column>"`) and the implicit grain join, as in the form declaration (see **An aggregate
pipeline reads its input entity, not only its input table**). Each of the following SHALL be rejected
with HTTP 422 `pipeline_validation_failed` naming the construct:

- a reference to any entity other than the input, an enrichment table named directly included;
- a second reference to the input. References SHALL be counted per occurrence, so a self-join, or a CTE
  over the input read twice, is refused even though it names one table;
- an explicit `JOIN`;
- a set operation (`UNION`, `INTERSECT`, `EXCEPT`);
- a subquery used as an expression (scalar, `IN`, `EXISTS`);
- `SELECT *` at any level, because it expands differently under the declaring caller's visibility and the
  system identity's;
- a user-written `FINAL` modifier or hint, because pipeline reads collapse versions with the query-level
  `final = 1` setting and never with the keyword.

#### Scenario: A second entity is refused

- **WHEN** the statement reads `dial_usage_log` and also names `turns`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `turns` and stating
  that only the input and its enrichments may be read

#### Scenario: A self-join through a CTE counts as two references

- **WHEN** the statement declares `WITH t AS (SELECT … FROM dial_usage_log)` and reads `t` twice, joined
  to itself
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the input is
  referenced more than once

#### Scenario: An explicit join is refused

- **WHEN** the statement joins the input to one of its enrichments with an explicit `LEFT JOIN`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that enrichment
  columns are read through `"<enrichment>.<column>"` and the implicit grain join

#### Scenario: A set operation is refused

- **WHEN** the statement's source is a `UNION ALL` of two selects over the input
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the set operation

#### Scenario: A subquery expression is refused

- **WHEN** the statement's `WHERE` reads `deployment IN (SELECT …)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the subquery

#### Scenario: SELECT star is refused

- **WHEN** a CTE or derived table in the statement projects `*`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` asking for the columns to be
  named

#### Scenario: A user-written FINAL is refused

- **WHEN** the statement reads `FROM dial_usage_log FINAL`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that ADAS collapses
  versions itself

### Requirement: An SQL declaration flattens to one aggregating block

An SQL declaration's source SHALL translate to **one** aggregating query block, or SHALL be rejected
with HTTP 422 `pipeline_validation_failed` naming the clause. CTEs and derived tables that only filter
rows or compute columns SHALL be accepted, because they fold into that block. A source that seals into a
nested block (an aggregate, a `DISTINCT`, a sort or a window below the top level) SHALL be refused,
because the dirty-key restriction and the author's `WHERE` must apply at the same level for a dirty group
to be recomputed in full.

The block SHALL be an aggregation with a `GROUP BY`, and SHALL carry none of `HAVING`, `DISTINCT`,
`ORDER BY`, `LIMIT`/`OFFSET` or a window function. Each would make the written rows depend on which keys a
window happened to dirty, rather than on each key's own rows.

#### Scenario: A CTE that only filters and computes is accepted

- **WHEN** the statement reads from `WITH chat AS (SELECT chat_id, total_tokens * 2 AS weighted FROM dial_usage_log WHERE chat_id != '')`
  and groups `chat` by `chat_id`
- **THEN** the declaration is accepted as one block over `dial_usage_log`

#### Scenario: An aggregate below the top level is refused

- **WHEN** a derived table in the statement aggregates, and the outer select aggregates its result
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the statement
  must flatten to one aggregating block

#### Scenario: A statement with no GROUP BY is refused

- **WHEN** the statement's select carries no aggregation and no `GROUP BY`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the block must
  be an aggregation

#### Scenario: HAVING is refused

- **WHEN** the statement carries `HAVING count(*) > 10`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `HAVING`

#### Scenario: DISTINCT is refused

- **WHEN** the statement's select is `SELECT DISTINCT …`, at the top level or in a derived table
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `DISTINCT`

#### Scenario: ORDER BY is refused

- **WHEN** the statement carries an `ORDER BY`, at the top level or in a derived table
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `ORDER BY`

#### Scenario: LIMIT is refused

- **WHEN** the statement carries a `LIMIT` or an `OFFSET`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the clause

#### Scenario: A window function is refused

- **WHEN** the statement's select carries `row_number() OVER (…)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the window function

### Requirement: An SQL declaration's group keys are exactly the target's non-nullable ordering key

The block's `GROUP BY` keys SHALL equal the target's `ordering_key` **as a set**, or the declaration SHALL
be rejected with HTTP 422 `pipeline_validation_failed` reporting both lists. Each key SHALL be matched to
the target column at the same position of the column list as the key's select expression. Order is not
compared, because the parser may reorder the group set.

Each key SHALL satisfy all of the following, or be rejected with HTTP 422 `pipeline_validation_failed`
naming the key:

- **It reads the input row.** A key SHALL reference at least one field of the input entity: a source
  column, an enrichment column, or a deterministic expression over them. A constant key is refused.
- **It is not nullable.** Under the pinned `transform_null_in = 0` a NULL key component never matches the
  dirty-key tuple `IN`, so a NULL group would never be recomputed. The refusal SHALL suggest wrapping the
  key in a non-null default such as `coalesce(<key>, '')`. A `filter` is not an answer here, as it is on
  the form path, because an SQL declaration has none (see **A nullable group key requires a declared
  filter**).
- **An enrichment column under it can represent absence.** An enrichment column that a key reads, bare or
  inside an expression, SHALL be declared nullable (see **An enrichment-sourced value is readable only
  where absence is representable**). The check applies to the enrichment column, not to the key's final
  nullability: `coalesce("<enrichment>.<column>", '')` is the author's explicit absence mapping, and it is
  sound only when the absence is a NULL to map.
- **Its type equals the target column's type.** A key's type SHALL equal its target column's declared
  type exactly, as on the form path. A time bucket SHALL bind to a temporal type wide enough for its unit
  (see **Target shape governance**), so an hour bucket into a `date` column is refused.
- **At most one key is a time bucket**, and a bucket SHALL have a step of one unit. A bucket such as
  `date_bin(2, 'hour', t)` is refused, because the refresh's pruning bound steps by one
  unit and would drop the rest of the bucket's rows.
- **A bucket is `date_trunc(<unit>, t)` or `date_bin(1, <unit>, t)` over a unit in `{hour, day, week, month}`**,
  the form path's set. Any other unit SHALL be refused `422 pipeline_validation_failed` as an unsupported
  bucket unit.
- **It calls no row-expanding function.** A key whose expression calls `array_join` SHALL be refused,
  because the refresh restricts each recompute by the key, and that restriction is not proven to select
  the same expanded rows as a full recompute. The form path cannot declare such a key either.

#### Scenario: A key set equal to the ordering key in another order is accepted

- **WHEN** the target's `ordering_key` is `(day, deployment)` and the statement groups by
  `deployment, date_trunc('day', request_time)`, writing them to `deployment` and `day`
- **THEN** the declaration is accepted

#### Scenario: A key set that differs from the ordering key is refused

- **WHEN** the target's `ordering_key` is `(day, deployment)` and the statement groups only by
  `date_trunc('day', request_time)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` reporting both key lists

#### Scenario: A constant key is refused

- **WHEN** the statement groups by `'all'` and writes it to an `ordering_key` column
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that a key must read
  the input row

#### Scenario: Two time buckets are refused

- **WHEN** the statement groups by `date_trunc('day', request_time)` and `date_trunc('hour', response_time)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that at most one time
  bucket is allowed

#### Scenario: A bucket with a step other than one is refused

- **WHEN** the statement groups by `date_bin(2, 'hour', request_time)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that a bucket must
  step by one unit

#### Scenario: A nullable key is refused with the coalesce remedy

- **WHEN** the statement groups by `"usage_client_identity.client_session_id"`, a nullable enrichment
  column, with no wrap
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the key, stating that
  a NULL group is never recomputed, and suggesting `coalesce("usage_client_identity.client_session_id", '')`

#### Scenario: A coalesced enrichment key is accepted

- **WHEN** the statement groups by `coalesce("usage_client_identity.client_session_id", '')` and writes it
  to a non-nullable `string` target column
- **THEN** the declaration is accepted

#### Scenario: A key over a non-nullable enrichment column is refused

- **WHEN** the statement groups by `coalesce("usage_scope.scope", '')` and `usage_scope.scope` is declared
  `nullable: false`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the column and its
  enrichment, and stating that an unmatched outer join renders it as its type default rather than as NULL

#### Scenario: An hour bucket into a date column is refused

- **WHEN** the statement writes `date_trunc('hour', request_time)` to a target column declared `date`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, because the hour would be
  truncated on insert and the day's buckets would collapse onto one

#### Scenario: A row-expanding key is refused

- **WHEN** the statement groups by `array_join(execution_path)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the key and stating
  that a row-expanding function cannot form a group key

#### Scenario: A key of a different type than its target column is refused

- **WHEN** the statement writes a `string` key to a target column declared `long`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the key, its type and
  the target column's type

### Requirement: An SQL declaration's expressions are deterministic and typed as the form path's are

Every expression of an SQL declaration's block SHALL call only functions the shared query function
catalog lists, and SHALL call none the catalog declares **non-deterministic**. This covers the select
list, the `WHERE`, the `GROUP BY`, and every folded CTE or derived table. A violation SHALL be rejected with HTTP 422
`pipeline_validation_failed` naming the function. The reason is the one given in **A non-deterministic
declaration is refused at the declaration gate**: a recomputed historical group must see the same rows
and the same values every time.

The measure expressions (the non-key select expressions) SHALL be held to the form path's type and
nullability rules, reproduced over the expression's own type rather than approximated. Each rule SHALL be
rejected with HTTP 422 `pipeline_validation_failed` naming the target column:

- an expression whose result carries a fraction (an `avg`, a division, a `sum` over a `decimal`) SHALL
  require a `decimal` target column;
- an expression whose result can be NULL SHALL require a nullable target column. A `/` or `%` result
  SHALL count as possibly NULL (a zero divisor yields NULL), and so SHALL a `CASE` with no `ELSE`. An
  expression reading an enrichment column SHALL count as nullable whatever that column declares, and the
  enrichment column SHALL be declared nullable (see **An enrichment-sourced value is readable only where
  absence is representable**). A column read **only in a `CASE WHEN` condition of a `CASE` inside an
  aggregate's arguments** is a row-membership read, as a form `measures[].where` is, so it SHALL NOT make
  the result nullable and its enrichment column SHALL NOT be held to the absence rule. A `CASE` above the
  aggregate, such as `CASE WHEN max(x) = '' THEN 0 ELSE 1 END`, decides the value written, so its condition
  gets no exemption;
- an array result SHALL bind to an `array` target column whose `element_type` equals the collected
  values' type, and an array-consuming aggregate SHALL be refused as on the form path (see
  **Array-consuming aggregates are query-time only**);
- an expression result with neither a numeric return class nor a numeric or array type (a `string`,
  `boolean`, `date` or `timestamp` result) SHALL bind to a target column of the same type. A string result
  MAY also bind to an `enum` or `uuid` column, because the planner types those operands as strings. ClickHouse
  would otherwise convert on insert, truncating silently (a timestamp into a `date`) or failing inside a
  refresh.

A `DISTINCT` quantifier inside an aggregate call SHALL be held to the form path's `distinct` rule, with
one exception: `min(DISTINCT x)` and `max(DISTINCT x)` SHALL be accepted in an SQL declaration, because
the quantifier cannot change their result, although the form path refuses `distinct` for them. Every other
`DISTINCT` aggregate the form path refuses SHALL stay refused with HTTP 422 `pipeline_validation_failed`
naming the function.

#### Scenario: A distinct min or max is accepted

- **WHEN** the statement writes `max(DISTINCT total_tokens)` to a target column of matching type
- **THEN** the declaration is accepted, unlike a form measure declaring `distinct: true` on `max`

#### Scenario: A distinct aggregate the form path refuses stays refused

- **WHEN** the statement writes `group_uniq_array(DISTINCT deployment)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the function

#### Scenario: A non-deterministic function is refused

- **WHEN** the statement's `WHERE` reads `request_time >= now() - INTERVAL 7 DAY`, or a measure calls
  `rand()`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the function

#### Scenario: A function outside the catalog is refused

- **WHEN** the statement calls `countIf(success)`, which `GET /v1/queries/sql-functions` does not list
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the function and
  pointing at the SQL function catalog

#### Scenario: An expression measure is accepted

- **WHEN** the statement's measures are `sum(prompt_tokens * deployment_price)` and
  `sum(CASE WHEN success THEN 1 ELSE 0 END)`, bound to target columns of matching type and nullability
- **THEN** the declaration is accepted

#### Scenario: A fractional expression into an integer column is refused

- **WHEN** the statement writes `sum(total_tokens) / count(*)` to a target column declared `long`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, because ClickHouse would
  truncate the fraction on insert

#### Scenario: A nullable measure into a non-nullable column is refused

- **WHEN** the statement writes `max("usage_scope.score")` to a target column declared non-nullable
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the target column and
  the enrichment column, because the grain join is outer

#### Scenario: A timestamp expression measure into a date column is refused

- **WHEN** the statement writes `max(date_trunc('hour', request_time))` to a target column declared `date`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the target column and
  both types

#### Scenario: An array measure of the wrong element type is refused

- **WHEN** the statement writes `group_uniq_array(status_code)` over a `long` column to an `array` target
  column whose `element_type` is `string`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both types

### Requirement: An SQL declaration is governed for sensitive columns and enrichment positions as the form path is

The sensitive-column, lineage, grant and enrichment-position rules of the form declaration SHALL apply to
an SQL declaration over **every** column its block references, in the select list, the `WHERE` and the
`GROUP BY`:

- The statement SHALL be resolved at the declaring caller's read entitlement. A column the caller cannot
  see because it is `sensitive` and the caller is not cleared SHALL be refused with HTTP 403
  `sensitive_column_not_entitled`, not reported as an unknown column. The system identity a scheduled
  refresh runs under sees every column, so the remaining rules bite for it and for a cleared caller.
- When the statement cannot be planned at all, and its text mentions a `sensitive` column of the source
  the caller may not read, the refusal SHALL be HTTP 403 `sensitive_column_not_entitled` rather than the
  422 for the planning failure. The mention SHALL be found by an over-approximating token scan of the
  text, so the check fails closed: a protected name anywhere in an unplannable statement answers 403. With
  no declared `inputs`, the check SHALL run against every source table the text names.
- A target column whose select expression reads a `sensitive` column, of the input or of an enrichment,
  SHALL itself be declared `sensitive`, or the declaration SHALL be rejected with HTTP 422
  `pipeline_validation_failed` naming both columns (see **Sensitive-column governance on the aggregate
  path**). Lineage SHALL come from the parsed, inlined select expressions.
- A column read **only in a `CASE WHEN` condition of a `CASE` inside an aggregate's arguments** in a
  measure is a row-membership read, as a form `measures[].where` is. It SHALL be entitlement-gated and
  checked for clampability and aggregatability like every other read, and SHALL be exempt from lineage: it
  decides which rows feed the aggregate, not the value written, so it does not require the target column
  to be `sensitive`. A `CASE` condition in a group key, or in a `CASE` above the aggregate, decides the
  value written and SHALL count for lineage.
- The target's `sensitive` grants SHALL NOT widen past its input's (see **A rollup target may not grant a
  protected column more widely than its input**).
- An enrichment the statement reads SHALL be clampable, and one written by a group-triggered pipeline or
  by a pipeline ADAS does not execute SHALL be refused with HTTP 422 naming the enrichment (see **An
  enrichment whose materialized position cannot be established is not aggregatable**).
- The input SHALL carry the version and identity cursor columns a refresh windows over.

#### Scenario: A sensitive column the caller may not see is refused with 403

- **WHEN** a caller not cleared for `sensitive` columns previews or arms an SQL declaration whose `WHERE`
  alone reads the `sensitive` column `jwt_claims`
- **THEN** the response is HTTP 403 `sensitive_column_not_entitled`, not a 422 for an unknown column

#### Scenario: A sensitive source column landing in a plain target column is refused

- **WHEN** a cleared caller arms an SQL declaration writing `max(user_email)` to a target column not
  declared `sensitive`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both columns

#### Scenario: A sensitive column read only in a CASE condition does not need a sensitive target

- **WHEN** a caller entitled to the `sensitive` column `user_email` arms an SQL declaration writing
  `sum(CASE WHEN user_email != '' THEN 1 ELSE 0 END)` to a target column not declared `sensitive`
- **THEN** the declaration is accepted, because the column decides row membership and no value of it is
  written

#### Scenario: A sensitive column in a group key's CASE condition needs a sensitive target

- **WHEN** a cleared caller arms an SQL declaration grouping by
  `CASE WHEN user_email LIKE '%@example.com%' THEN 'internal' ELSE 'external' END` into a target column not
  declared `sensitive`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both columns

#### Scenario: An unplannable statement naming a protected column is refused with 403

- **WHEN** a caller not cleared for `sensitive` columns submits an SQL declaration that fails to parse and
  whose text mentions the `sensitive` source column `jwt_claims`
- **THEN** the response is HTTP 403 `sensitive_column_not_entitled`, not the 422 for the parse failure

#### Scenario: A non-clampable enrichment is refused

- **WHEN** the statement reads a column of an enrichment written by a group-triggered pipeline, or by a
  pipeline the Enrichment Runner executes
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the enrichment and the
  reason

#### Scenario: An input with no cursor columns is refused

- **WHEN** the statement's input declares no version or identity column
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the input cannot
  be windowed

### Requirement: An SQL declaration refreshes under the form path's contract

A refresh of an SQL declaration SHALL follow **Scheduled refresh recomputes every affected group key in
full** unchanged: a probe over the input's cursor tuple, then one `INSERT … SELECT` that recomputes every
dirty key in full into the `ReplacingMergeTree(_updated_at)` target, with the cursor advanced only after
the write. The compiled statements SHALL be built from the parsed block as follows.

- **Probe.** The probe SHALL apply the block's `WHERE` as the pipeline's filter, compiled as a `filter`
  is, so it joins the enrichments that `WHERE` names.
- **Dirty-key restriction.** The recompute SHALL add, as a condition of the block's own `WHERE`, the
  group-key tuple `IN` a dirty-key sub-select over the window. That sub-select SHALL apply the block's
  `WHERE` and the window, and SHALL join the enrichments its keys read.
- **Pushdown into the grain join.** The restriction SHALL be passed into the block's compilation, not
  appended after it, so it reaches the enrichment grain join. Where every key reads columns of one and the
  same enrichment, plainly or through an expression such as `coalesce("<enrichment>.<column>", '')`, that
  enrichment SHALL be emitted as a derived table restricted by the dirty keys, as **An aggregate pipeline
  reads its input entity, not only its input table** states for the form path. For a key expression the
  restriction SHALL stay exact. A row whose enrichment row the restriction drops reads as unmatched, and
  the key's image of an unmatched row (the expression with its enrichment columns NULL, `''` for the
  `coalesce` above) may itself be a dirty key. The derived table's `WHERE` SHALL therefore admit every
  enrichment row whenever that image is among the dirty keys, so such a refresh joins the enrichment
  unrestricted rather than miscounting.
- **Bucket bound.** A time-bucket key SHALL bound the recompute to the dirty buckets' span on the bucketed
  column, stepped by one unit inside the statement, as on the form path.
- **UTC.** A bucket function SHALL take no time-zone argument, and every time-bucket call SHALL be rendered
  pinned to UTC, as the form path's buckets are, so the same statement buckets identically on every
  deployment.
- **Rendering.** The recompute SHALL carry no `LIMIT` and no per-table `FINAL` keyword. Versions are
  collapsed by the statement-level `final = 1` and `transform_null_in = 0` the pipeline executor pins.
- **Insert.** The statement SHALL be prefixed with `INSERT INTO "<target>" (<columns>, "_updated_at")`,
  with the columns in the authored column-list order, and `now64(3)` SHALL be appended to the projection.
  It SHALL be executed only by the pipeline's `INSERT … SELECT` executor on the writer credential and
  under the pipeline read budget, never by a user-facing query executor.

The enrichment freshness clamp, the pipeline read budget and the run-state recording SHALL apply
unchanged.

#### Scenario: The dirty-key restriction is in the block WHERE

- **WHEN** an SQL declaration with a `WHERE` of `chat_id != ''` is compiled for a refresh
- **THEN** the recompute's `WHERE` carries both `chat_id != ''` and the group-key tuple `IN` a dirty-key
  sub-select that applies `chat_id != ''` and the window

#### Scenario: An enrichment key restricts the grain join to the dirty keys

- **GIVEN** an SQL declaration grouped only by `"usage_client_identity.client_session_id"` wrapped as
  `coalesce("usage_client_identity.client_session_id", '')`
- **WHEN** a refresh is compiled over a window whose dirty keys do not include `''`
- **THEN** the enrichment is emitted as a derived table restricted by the key expression `IN` the dirty-key
  sub-select, and the target receives the same rows an unrestricted recompute would write

#### Scenario: A dirty absence key keeps the restricted join exact

- **GIVEN** the same declaration, and a window that dirties the `''` key along with others
- **WHEN** a refresh runs over input rows whose enrichment rows carry keys outside the dirty set
- **THEN** those rows do not contribute to the `''` group, and the target receives the same rows an
  unrestricted recompute would write

#### Scenario: A bucketed SQL declaration bounds the recompute

- **WHEN** an SQL declaration grouping by `date_trunc('day', request_time)` refreshes over a window
  covering two days
- **THEN** the recompute is bounded on `request_time` to those days' span, stepped by one day, and the
  measures equal an unbounded recompute's

#### Scenario: A zone-less bucket is pinned to UTC

- **WHEN** an SQL declaration groups by `date_trunc('day', request_time)` with no time zone and the
  ClickHouse server runs in another zone
- **THEN** the compiled statement pins the bucket to UTC, and each bucket starts at UTC midnight

#### Scenario: The recompute carries no LIMIT and no FINAL keyword

- **WHEN** an SQL declaration is compiled for a refresh
- **THEN** the statement carries no `LIMIT`, no `FINAL` keyword after any table, and ends its projection
  with `now64(3)` written to `_updated_at`

#### Scenario: The probe joins the enrichments the statement's WHERE names

- **WHEN** an SQL declaration whose `WHERE` reads `"usage_scope.scope" = 'chat'` is compiled for a refresh
- **THEN** the probe applies that predicate as its filter and carries the `usage_scope` grain join, keyed
  on the window predicates

#### Scenario: Appended rows recompute to the full result

- **GIVEN** an armed SQL declaration that has refreshed its input
- **WHEN** rows are appended to the input, including rows for already-written groups, and a refresh runs,
  and then the same refresh runs again with no new rows
- **THEN** the target equals a full recompute of the statement over the whole input after the first run,
  and is unchanged by the second

#### Scenario: An SQL declaration and its form twin write the same rows

- **GIVEN** a form declaration grouping by a `day` bucket and summing a column, and an SQL declaration
  writing the same keys and sum with a zone-less `date_trunc('day', …)`
- **WHEN** both refresh over the same input into separate targets
- **THEN** the two targets hold identical rows

### Requirement: Preview of an SQL declaration samples the block

`POST /v1/pipelines/{name}/preview` SHALL serve an SQL declaration in the shape **Dry-run preview of an
aggregate declaration** states, running the gate in full first. The sample SHALL be the parsed block
rendered over the whole input, unwindowed, with at most `aggregate.preview-rows` groups, under the
preview budget. It SHALL be ordered by each group's latest input row and SHALL count every group as
`total_rows`, from the same statement, as the form path's is.

`columns` SHALL list each select position the block groups by as `key` and every other as `measure`, named
by its target column, with `type` the type the engine yields for the rendered expression and `target_type`
the target column's type. A time-bucket key's `type` SHALL be reported as the form path's bucket is: the
target column's temporal type when the bucket's unit allows it, and `timestamp` otherwise. The
`held_by_enrichment`, `enrichment_behind`, `target_type_narrowing`, `value_out_of_target_range` and
`no_matching_rows` notes SHALL be produced as they are for the form path.

`statement` SHALL be the sample's own `SELECT`. Preview SHALL NOT return the refresh `INSERT`, neither the
authored text nor ADAS's rendering of it. A select item whose target column name equals a field of the
input entity that the item does not itself project SHALL be aliased `__c<i>` (its position) in
`statement`, so the alias cannot shadow that field elsewhere in the sample. The returned rows and
`columns` SHALL still be named by the target columns.

#### Scenario: An SQL declaration previews a bounded sample

- **WHEN** `POST /v1/pipelines/{name}/preview` is called on a disabled SQL-declared pipeline
- **THEN** the response carries at most `aggregate.preview-rows` rows, `columns` marking each group key as
  `key` and each other position as `measure`, and a `total_rows` counting every group of the whole input

#### Scenario: An SQL declaration's preview reports a lagging enrichment

- **GIVEN** an SQL declaration reading an enrichment whose freshness bound is older than the newest
  matching input row
- **WHEN** it is previewed
- **THEN** the preview carries an `enrichment_behind` note naming the enrichment and giving the gap

#### Scenario: An SQL declaration's preview carries no INSERT

- **WHEN** an SQL-declared pipeline is previewed
- **THEN** `statement` is the sample's own `SELECT` and the response carries no `INSERT` statement, neither
  the authored `sql` nor its compiled refresh

#### Scenario: A target name shadowing an input field is aliased positionally

- **GIVEN** an SQL declaration writing `sum(total_tokens)` to a target column named `prompt_tokens`, a field
  the input also carries
- **WHEN** it is previewed
- **THEN** `statement` aliases that select item `__c<i>` rather than `prompt_tokens`, while the rows and
  `columns` name it `prompt_tokens`

#### Scenario: An SQL declaration's preview runs the gate

- **GIVEN** a disabled pipeline whose stored `sql` groups by a nullable column
- **WHEN** it is previewed
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, the refusal arming would give,
  and no sample is read

## MODIFIED Requirements

### Requirement: Aggregate spec vocabulary

An `aggregate` pipeline's `spec` SHALL declare `measures` over its input, plus an optional `filter`
and an optional `trigger.group_by` (the **form declaration**), unless it declares `sql` instead (the
**SQL declaration**, see **An aggregate pipeline may be declared as one SQL statement**). The two are
mutually exclusive. `measures` SHALL be required for the form declaration only, and an SQL declaration
SHALL carry no `measures`, no `filter` and no `trigger.group_by`. The form declaration SHALL be
engine-agnostic: it names no ClickHouse function and no engine construct. The rules below describe the
form declaration's members; an SQL declaration is held to the same outcomes by the SQL requirements
named there.

**`trigger.group_by`** SHALL be an ordered list of key parts, each either:

- a plain **field of the input entity** — a declared column of the input source, or an exposed column
  of one of its ACTIVE enrichments addressed by its qualified flat name — or
- a **time bucket** `{trunc: {col, unit}, as: <name>}` over a declared `date`/`timestamp` column, with
  `unit` from the closed set `{hour, day, week, month}` (week starting Monday/ISO; month starting at
  midnight on the 1st). The bucketed column SHALL likewise be any `date`/`timestamp` field of the
  input entity.

**`trigger.group_by` MAY be omitted**, in which case it SHALL be **derived from the target's
`ordering_key`**: one plain key part per key column, in the target's own key order, admitted only
when **every** `ordering_key` column is an input column of the same name. A declaration whose target
`ordering_key` names a column the input does not carry under that name — a namespaced enrichment
column reached through an alias, say — SHALL be rejected with HTTP 422 asking for the explicit form,
**at the declaration gate** (see [pipeline-registry](../pipeline-registry/spec.md)) rather than at
the moment the declaration is stored: an aggregate pipeline is always registered disabled, so a
registration arms nothing and the refusal lands when the pipeline is armed, previewed,
or executed. The grain is the same fact on both objects, and the equality check below has nothing to
disagree with when only one of them states it.

An SQL declaration SHALL NOT have its group key derived: its keys are the statement's own `GROUP BY`,
and derivation SHALL leave `trigger.group_by` empty for it rather than fill it from the `ordering_key`.

A derived `trigger.group_by` SHALL be **expanded into the stored spec** by the derivation half of the
gate, which runs on **every** declaration write, rather than being re-derived behind every consumer.
The derivation source cannot move — an `ordering_key` column can neither be dropped nor renamed — so
the expansion is stable across writes, it keeps the target-binding equality check meaningful for a
declaration that *did* state its keys, and it makes the echo below automatic. The visible consequence
is that the authored projection shows the expanded key, because that is what was stored.

Derivation SHALL be **best-effort and non-destructive**: where it cannot run — a write that leaves the
declaration with no resolvable input entity, or a target whose `ordering_key` does not derive — the
stored group key SHALL be left as it was rather than replaced with an empty one. Erasing it would be
worse than never having derived it: the target-binding equality check, the enrichment drop/rename
guard and the column-writer resolution all read the *stored* keys, so a write that silently emptied
them would widen what an unrelated schema change is allowed to do. An empty stored list SHALL be
read as "not declared yet", never as "declared to group by nothing", since no valid aggregate
declaration has zero group keys.

Derivation matches by **name**, and cannot detect a name that means something else: an input column
sharing the ordering key's name but not what the author meant would group the wrong thing silently.
The derived key SHALL therefore be **echoed expanded** on the pipeline read, where an author sees it
before the pipeline is armed. The preview does not echo it, and shows instead the expression it compiled
to in its `statement` (see "Dry-run preview of an aggregate declaration").

**At most one** time bucket SHALL appear in a `trigger.group_by`. A `unit` outside the supported set SHALL be
rejected with HTTP 422 stating the supported units. The set is narrower than the truncation function the
query catalog offers: a bucket finer than an hour would carry about as many keys as the rows it
summarizes, and a refresh costs per dirty key. Widening it further is additive.

A `month` bucket SHALL be a **calendar** month, not a fixed number of days, and SHALL be compiled through
the catalog's calendar truncation rather than its fixed-width binning. The pruning bound a bucketed
refresh applies SHALL step by one calendar month accordingly, inside the statement, so the step past the
last dirty bucket stays in the same calendar the bucketing used.

A `month` bucket SHALL be bindable to a `date` or a `timestamp` target column: a month start is midnight
on the 1st, so either temporal type holds it without loss.

**`measures`** SHALL be a non-empty list of `{name, fn, column?, where?, distinct?}` entries. `fn` SHALL
come from the aggregate functions of the shared query function catalog (`count`, `sum`, `avg`, `min`,
`max`, `group_uniq_array`), and `column` SHALL be a field of the input entity.

**An omitted `column` SHALL be resolved by `fn`.** For `count` it SHALL keep meaning `count(*)` — a row
count over the group, naming no column. For **every other** function it SHALL default to the measure's
own `name`, which is the common case: the measure is named after the input column it aggregates. The
rule is deliberately function-aware rather than a flat "defaults to `name`": `count` with no column is
a row count that no column name can express, and `count(<column>)` skips NULLs where `count(*)` does
not, so a flat default would silently change what a column-less count counts.

A defaulted `column` cannot detect an input that carries **two** plausibly-named columns, where the
one the author meant is not the one whose name matches the measure — a live shape, not a hypothetical.
The resolved `column` SHALL therefore be **echoed expanded** on the pipeline read, in the same place
and for the same reason as a derived `trigger.group_by`.

A `group_uniq_array` measure SHALL bind only to an `array` target column — it collects the
group's distinct non-null values as a set, sorted so that a recompute of unchanged rows writes the
identical value and the target's collapse stays a no-op — and that column's declared `element_type`
SHALL equal its input column's declared type, whose values the set holds unconverted (see "An array
measure's element type must match its input"). A measure SHALL be **one
function over one column**: no expression is permitted inside a measure. Per-row arithmetic SHALL be a
derived column on the input, and ratios or other expressions *over* measures SHALL be computed at query
time over the stored additive measures — never stored, because a ratio cannot be re-aggregated across
buckets.

The SQL declaration is the deliberate exception: its measures are select expressions, so per-row
arithmetic inside an aggregate (`sum(a * b)`) and an expression over aggregates (`sum(x) / count(*)`) are
admitted within the rules of **An SQL declaration's expressions are deterministic and typed as the form
path's are**. A stored ratio is correct per group, because each dirty group is recomputed in full, but it
still cannot be re-aggregated across groups. Storing one is the author's choice.

A catalog aggregate requiring **more than one argument** SHALL be rejected with HTTP 422 naming the function
and its signature: a measure carries exactly one input column and cannot express such a call. No aggregate
currently in the catalog takes two, so this is a guard on future additions rather than a live restriction —
but it has to be enforced at declaration, because unrejected the declaration would validate, store and
enable, and the arity failure would then be raised inside every scheduled refresh, recorded only in
`state.last_error`. The pipeline SHALL be keyed on the catalog descriptor's required-argument count rather than
on a list of function names, so an addition is covered without a change here.

The arity rule SHALL apply to the form declaration's `measures` only. An SQL declaration calls a catalog
aggregate with the arguments its own signature takes, so a two-argument aggregate such as `arg_max` is
admitted there as a measure, subject to the SQL type rules.

**`measures[].distinct`**, when `true`, SHALL deduplicate the values of the measure's input column before
the function is applied, so the measure counts or aggregates the group's *distinct* values rather than its
rows. It SHALL default to `false`, and a declaration that omits it SHALL compile exactly as it does
without the field — the quantifier is additive and changes no stored declaration's meaning. A measure
whose `distinct` is `false` SHALL NOT echo it: a response that repeats a default carries no
information, and on the live registry it is the single most repeated member of the whole declaration.
An omitted `distinct` on the wire SHALL continue to bind as `false`.

`distinct` SHALL be accepted only for a function the shared catalog marks `distinct_supported`, and
rejected with HTTP 422 otherwise, naming the function. The acceptability SHALL be read from the catalog
descriptor rather than from a list of function names here, so the pipeline surface and the query surface
cannot disagree about the same function: the query DSL and the SQL front end already accept the quantifier
for `count`, `sum` and `avg` and already refuse it for `group_uniq_array`, whose result is the group's
distinct values already. A rejection for `group_uniq_array` SHALL say so rather than report a generic
unsupported-flag error, matching what the query surface answers.

`distinct: true` SHALL require a `column`. A `count` with no column is a row count, and there is no value
to deduplicate — accepting the pair would store a declaration whose only honest rendering is the
non-distinct one.

`distinct` SHALL compose with `where`: the predicate chooses which of the group's rows the measure reads,
and `distinct` deduplicates the values on those rows. The two are independent, and neither weakens the
one-function-over-one-column rule — `distinct` is a quantifier on the operand, not an expression over it.

`distinct` SHALL introduce no new nullability consequence, and "A nullable measure result requires a
nullable target column" SHALL apply to a distinct measure unchanged: a distinct `count` of nothing is `0`
exactly as a plain `count` of nothing is, and a distinct `sum`/`avg` over a group holding no non-null
value is NULL for the reason the pipeline already gives — its input column is nullable. The quantifier
deduplicates values that are already there; it cannot make a result NULL that would otherwise not be.

**`measures[].where`**, when present, SHALL be a predicate in the same bounded SQL-boolean grammar as
`filter`, over declared and entitled fields of the input entity, scoping *that measure* to a subset of its
group's rows. It SHALL be validated at declaration, and its referenced columns SHALL run the same
sensitive-column entitlement check `filter`'s do — a predicate reads its columns even though it derives
nothing from them, and without the check a measure predicate would be a way to test a protected value one
comparison at a time. It SHALL NOT narrow the rows the declaration's other measures read, and SHALL NOT
narrow the dirty-key set a refresh recomputes: a key whose only matching rows fall outside the window must
still be recomputed in full.

A `where` does **not** relax the one-function-over-one-column rule above. It is a row-*membership*
predicate — it chooses which of the group's rows the function reads, and the function still reads one
column of each. That distinction is what makes it additive rather than a loophole: the declarations it
enables have no other expressible form, because `filter` is one predicate over the whole declaration and
narrowing it narrows every measure with it. A whole-chain cost that must sum only the rows starting a
chain is the motivating case.

**A row-membership predicate is not a substitute for `distinct`**, and the two SHALL NOT be conflated: a
`where` selects rows by a property each row carries on its own, so it cannot express "one row per distinct
value of a column" — which row of a group is the representative is a property of the group, not of the
row. Counting the entities in a group whose grain is coarser than its input's row grain is therefore a
`distinct` count and nothing else. Attempting it with a predicate is not merely imprecise but can select
no row at all, silently reporting `0` for a group that holds many entities, when the rows the predicate
would have matched are rows the declaration's `filter` necessarily excludes.

**`filter`**, when present, SHALL be a predicate in the same bounded SQL-boolean grammar the query
façade and enrich declarations use, over declared and entitled fields of the input entity. It is optional in
general and **required** when a `trigger.group_by` key reads a nullable column — see "A nullable group key requires
a declared filter".

**`freshness`** SHALL NO LONGER be accepted on the wire, and SHALL NO LONGER be echoed on a read. It
declared one legal value through three levels of nesting and carried no information: `periodic` is the
only implemented mode, and every other value was already refused. It SHALL be refused at the binding
with HTTP 400 `bad_request` naming the field, on registration and on patch alike.

A stored spec carrying no `freshness` SHALL read back as `periodic`, so no stored declaration changes
meaning and no re-arm can start failing; the guard rejecting any other mode SHALL be kept, as the
check on a hand-edited stored row. An **explicitly null** stored `freshness` SHALL also validate: an
explicit null is honoured where an absent member falls back to the default, so the mapper SHALL leave
the member untouched rather than writing a null through it.

**`trigger`** SHALL be `{kind: "schedule", cron: <expression>}`. Any other trigger kind SHALL be
rejected with HTTP 422.

#### Scenario: A measure carrying an expression is rejected

- **WHEN** a `measures` entry supplies an expression instead of a single column
- **THEN** the request is rejected with HTTP 422 pointing at derived columns and query-time expressions

#### Scenario: A measure naming a multi-argument aggregate is rejected

- **WHEN** a `measures` entry names a catalog aggregate whose signature takes two required arguments
- **THEN** the request is rejected with HTTP 422 naming the function, its argument count and its signature,
  and stating that a measure carries exactly one input column

#### Scenario: A distinct count counts entities, not rows

- **GIVEN** an input whose rows are one hop and a group holding 1,200 hops across 40 distinct
  `trace_id` values
- **WHEN** a measure declares `fn: "count"`, `column: "trace_id"` and `distinct: true`
- **THEN** the measure writes `40`

#### Scenario: A distinct quantifier is accepted for a function the catalog marks distinct_supported

- **WHEN** a measure declares `distinct: true` with `fn` of `count`, `sum` or `avg` and a `column`
- **THEN** the declaration is accepted

#### Scenario: A distinct quantifier on a function that does not support it is rejected

- **WHEN** a measure declares `distinct: true` with `fn` of `min` or `max`
- **THEN** the request is rejected with HTTP 422 naming the function, and the decision is taken from the
  catalog descriptor's `distinct_supported` rather than from a list of names in this capability

#### Scenario: A distinct quantifier on an array measure is rejected as redundant

- **WHEN** a measure declares `fn: "group_uniq_array"` with `distinct: true`
- **THEN** the request is rejected with HTTP 422 stating that the function already returns the group's
  distinct values, the same answer the query surface gives for the equivalent `DISTINCT`

#### Scenario: A distinct count with no column is rejected

- **WHEN** a measure declares `fn: "count"` and `distinct: true` but omits `column`
- **THEN** the request is rejected with HTTP 422 stating that a distinct count needs a column to
  deduplicate

#### Scenario: A distinct measure composes with its own predicate

- **GIVEN** a measure declaring `fn: "count"`, `column: "trace_id"`, `distinct: true` and
  `where: "success = true"`
- **WHEN** a refresh compiles the statement
- **THEN** the measure counts the distinct `trace_id` values of the group's rows satisfying the predicate,
  and a group whose rows all fail it writes `0`

#### Scenario: An omitted distinct quantifier changes nothing

- **GIVEN** a pipeline declared before this capability accepted `distinct`
- **WHEN** a refresh compiles its statement
- **THEN** every measure renders exactly as it did before, since an absent `distinct` is `false`

#### Scenario: A measure predicate scopes only its own measure

- **GIVEN** a declaration whose `chain_price` measure declares `where: "parent_deployment IS NULL"` and
  whose `total_tokens` measure declares none
- **WHEN** a refresh compiles the statement
- **THEN** `chain_price` aggregates only the group's rows satisfying the predicate, `total_tokens` aggregates
  every row of the group, and the predicate appears in neither half's `WHERE` clause

#### Scenario: A malformed measure predicate is rejected at declaration

- **WHEN** a `measures[].where` fails the bounded SQL-boolean grammar, or names a field the input entity
  does not publish
- **THEN** the request is rejected with HTTP 422, rather than stored and left to fail inside every refresh

#### Scenario: A measure predicate over a sensitive column the caller may not read is refused

- **WHEN** a `measures[].where` references a sensitive input column the calling identity is not entitled to
- **THEN** the request is refused with HTTP 403, the same as for a `filter` over that column

#### Scenario: An unsupported bucket unit is rejected

- **WHEN** a `trigger.group_by` time bucket declares a `unit` outside `{hour, day, week, month}`
- **THEN** the request is rejected with HTTP 422 stating the supported units

#### Scenario: A month bucket produces calendar months

- **WHEN** a pipeline groups by a `month` time bucket over a timestamp column
- **THEN** each bucket starts at UTC midnight on the first of its month, months of differing lengths each
  form exactly one bucket, and the refresh's pruning bound steps by one calendar month

#### Scenario: A month bucket bound to a date target column is accepted

- **WHEN** a `month` bucket's `as` name is bound to a target column declared `date`
- **THEN** the declaration is accepted, since a month start carries no time-of-day to lose

#### Scenario: Two time buckets are rejected

- **WHEN** a `trigger.group_by` declares two `trunc` parts
- **THEN** the request is rejected with HTTP 422

#### Scenario: A declared freshness is refused by name

- **WHEN** a registration or patch carries `freshness`, whatever its `mode`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is stored

#### Scenario: A stored spec with no freshness still validates

- **WHEN** a stored aggregate spec carrying no `freshness` member is re-validated on a patch or an
  enable
- **THEN** it reads back as `periodic` and the validation passes

#### Scenario: A stored spec with an explicit null freshness still validates

- **WHEN** a stored aggregate spec carries `"freshness": null` explicitly
- **THEN** re-validation passes, rather than reporting that a mode is required

#### Scenario: A read no longer echoes freshness

- **WHEN** `GET /v1/pipelines/{name}` is called for an `aggregate` pipeline in either projection
- **THEN** the response carries no `freshness` member

#### Scenario: group_by is derived from the target's ordering key

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with no `trigger.group_by`, whose
  target's `ordering_key` is a single column the input carries under the same name
- **THEN** the pipeline is accepted, the stored spec carries that column as its one plain group key,
  and the compiled projection echoes it expanded

#### Scenario: A group_by that cannot be derived is refused at the declaration gate

- **GIVEN** an `aggregate` pipeline registered with no `trigger.group_by`, whose target's
  `ordering_key` names a column the input carries only as a namespaced enrichment column
- **WHEN** the registration is stored
- **THEN** it is accepted with no group key derived, because an aggregate registers disabled and
  arms nothing
- **AND WHEN** the pipeline is armed, or previewed
- **THEN** the request is rejected with HTTP 422 asking for the explicit `trigger.group_by` form

#### Scenario: A write that cannot re-derive leaves the stored group key untouched

- **GIVEN** a disabled `aggregate` pipeline whose `trigger.group_by` was derived from its target's
  `ordering_key`
- **WHEN** a declaration patch leaves the declaration with no resolvable input entity, so the key
  cannot be re-derived
- **THEN** the stored group key is left as it was rather than stored empty, and the target-binding
  and drop/rename guards that read it keep seeing what the pipeline will group by

#### Scenario: A patch may omit a group_by the registration omitted

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a complete declaration with no `trigger.group_by` for
  a pipeline registered without one
- **THEN** the patch is accepted, rather than demanding a field the registration said was optional

#### Scenario: A column-less count compiles to a row count

- **WHEN** a measure declares `{"name": "hop_count", "fn": "count"}` with no `column`
- **THEN** it compiles to a row count over the group, not to a count of a column named `hop_count`,
  and the registration is accepted

#### Scenario: A column-less non-count measure defaults to its own name

- **WHEN** a measure declares `{"name": "prompt_tokens", "fn": "sum"}` with no `column`, and the input
  carries a `prompt_tokens` column
- **THEN** it compiles as a sum over that column, and the compiled projection echoes
  `column: "prompt_tokens"` so the resolution is visible

#### Scenario: A defaulted column that is not the one meant is visible before arming

- **WHEN** a measure declares `{"name": "total_price", "fn": "sum"}` on an input carrying both
  `total_price` and `deployment_price`
- **THEN** the declaration is accepted as a sum over `total_price`, and the pipeline read echoes that
  resolved column, while the preview's `statement` shows the sum over it

#### Scenario: An unknown aggregate function is rejected

- **WHEN** a measure names a function outside the catalog's aggregate group
- **THEN** the request is rejected with HTTP 422

#### Scenario: A group key naming an enrichment column is accepted

- **WHEN** a `trigger.group_by` key names an ACTIVE enrichment's exposed column by its qualified flat name, and
  the pipeline declares a `filter`
- **THEN** the declaration is accepted, `inputs` still naming the source the enrichment hangs off

#### Scenario: An unknown qualified name names the enrichment it was resolved against

- **WHEN** a declaration references `"<enrichment>.<column>"` where the enrichment exists but publishes no
  such column
- **THEN** the request is rejected with HTTP 422 naming the enrichment as well as the column, so a typo in
  either half is diagnosable

#### Scenario: An SQL declaration needs no measures

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline carrying `sql` and no `measures`
- **THEN** the declaration is not refused for the missing `measures`, and the stored spec carries none

#### Scenario: An SQL declaration's group key is not derived

- **WHEN** an SQL-declared pipeline is written, armed or read
- **THEN** its stored `trigger.group_by` stays empty, the read echoes no derived key, and the keys are the
  ones the statement's `GROUP BY` names

#### Scenario: An SQL declaration may store a ratio of aggregates

- **WHEN** an SQL declaration writes `sum(total_tokens) / count(*)` to a nullable `decimal` target column
- **THEN** the declaration is accepted, unlike a form measure carrying an expression

### Requirement: Target shape governance

The target of an `aggregate` pipeline SHALL be an existing, materialized (`ACTIVE`) `source` table whose
write discipline is `upsert_by_key`. A target that is unknown, an `enrichment`, not yet materialized, or
written `append` SHALL be rejected with HTTP 422 — an `append` target would accumulate one row per
refresh instead of replacing the recomputed key, silently multiplying every measure.

In the form declaration, the target's `ordering_key` SHALL equal the pipeline's `trigger.group_by` output names, **in order**. The rollup's
grain is therefore not declared twice and cannot drift from the pipeline that fills it. Where the
declaration omitted `trigger.group_by` the check is satisfied by construction, because the stored key
was expanded *from* the `ordering_key`; where the declaration stated its keys, the check is what holds
the two statements together, and it SHALL be kept for exactly that case.

An SQL declaration has no `trigger.group_by`, so this equality SHALL NOT be applied to it. Its `GROUP BY`
keys SHALL instead equal the `ordering_key` **as a set**, each key matched to the target column at the
same position of the `INSERT` column list (see **An SQL declaration's group keys are exactly the target's
non-nullable ordering key**). Order is not compared, because the parser may reorder the group set.

Being a `source`, the target MAY declare the optional scan-metadata pair, which is what makes the rollup
itself scannable by an enrich pipeline. It SHOULD do so **only when its `trigger.group_by` yields a single
column**: the target collapses by its whole `ordering_key`, so with a multi-column grain no single column
identifies a row, and `identity_column` could only name part of the key. That is not a cosmetic
inaccuracy — one refresh stamps a single `now64(3)` into `_updated_at` for every row it writes, so rows
sharing that one key member are indistinguishable to the keyset seek and all but one are silently skipped.
A single-column-grain rollup declares `identity_column` = its group key and `version_column` =
`_updated_at`, which the refresh restamps on every recomputed key, so an enrich pipeline sourced on the rollup re-reads
exactly the keys the last refresh touched.

In the form declaration, every `trigger.group_by` output name and every `measures[].name` SHALL resolve to a declared column of the
target, and the column's declared type SHALL accept the part's or function's result type. A target column
that no `trigger.group_by` part or measure writes SHALL be rejected, since it could only ever hold a default.

In an SQL declaration the same two rules SHALL be applied to the `INSERT` column list. Every target column
except `_updated_at` SHALL be listed exactly once, so no column is left to hold a default (see **An SQL
declaration is exactly one INSERT … SELECT naming every target column**). Each listed column's declared
type SHALL accept the select expression at its position (see **An SQL declaration's expressions are
deterministic and typed as the form path's are**).

For a group key the type rule is exact rather than merely compatible: a plain key's target column SHALL be
declared the same type as the input column it groups. A group key is the input value itself, not an
aggregate of it, so a mismatch is a bad declaration — and left to execution it either surfaces as an engine
rejection attributed to the wrong cause, or is silently coerced.

A **time bucket's** target column SHALL be a temporal type wide enough for the bucket's own width: an
`hour` bucket SHALL require `timestamp`, while a `day` or `week` bucket (which starts at midnight) MAY be
either `date` or `timestamp`. An `hour` bucket bound to a `date` column is the worst case of the whole
binding, and the reason the pipeline is per-unit rather than merely "temporal": the bucket start is truncated to
midnight on insert, and because the bucket is part of the target's `ordering_key` the engine then collapses
all 24 of a day's rows onto one arbitrary survivor. There is no error and no duplicate row — 23 buckets
simply do not exist.

A measure whose result carries a fraction SHALL require a `decimal` target column: `avg` always, and `sum`
over a `decimal` input column. The shared catalog types `avg` and `sum` alike (both `numeric`), so this rule
cannot come from the return type — and ClickHouse converts rather than refusing on insert, which would store
a truncated mean or total as an exact whole number. `min`/`max` need no such rule: they return the input
value itself.

**Which name addresses which side.** An `input` column — `group_by[].column`, `group_by[].trunc.column`,
`measures[].column`, and any column in `filter` — is named by its **exposed** catalog name, the same
spelling the query DSL and the filter grammar use, because that is what the compiled `SELECT` resolves
against. A `target` column — `group_by[].as` and `measures[].name` — is named by its **physical** name,
because that is what the `INSERT` column list and the target's `ordering_key` are. Validating either side
against the other's spelling would accept declarations that cannot compile, and reject ones that can, on any
aliased column.

An SQL declaration follows the same split: the statement's source reads input columns by their exposed
names, and its `INSERT` column list names target columns by their physical names.

The rest of this requirement SHALL apply to both declarations: the `upsert_by_key` target, the
scan-metadata guidance, the exact group-key type rule, the time-bucket width rule, the fractional-measure
rule and the `system`-target refusal. For the scan-metadata guidance, an SQL declaration's grain is its
`GROUP BY` key set.

A `system`-owned target SHALL reject pipeline registration, consistent with every other mutation of a
system table.

#### Scenario: An append target is rejected

- **WHEN** a pipeline's `target` is a `source` whose write discipline is `append`
- **THEN** the request is rejected with HTTP 422 explaining that a rollup target must be
  `upsert_by_key`

#### Scenario: A target whose ordering key differs from group_by is rejected

- **WHEN** the target's `ordering_key` is `(day, deployment)` and `trigger.group_by` yields
  `(deployment, day)`
- **THEN** the request is rejected with HTTP 422 reporting both key lists

#### Scenario: A measure with no matching target column is rejected

- **WHEN** a `measures` entry names `cost` and the target declares no `cost` column
- **THEN** the request is rejected with HTTP 422

#### Scenario: A type-incompatible target column is rejected

- **WHEN** a `count` measure is bound to a target column declared `string`
- **THEN** the request is rejected with HTTP 422

#### Scenario: A group key bound to a differently-typed target column is rejected

- **WHEN** a plain group key over a `string` input column is bound to a target column declared `long`, or a
  time bucket is bound to a target column that is neither `date` nor `timestamp`
- **THEN** the request is rejected with HTTP 422

#### Scenario: An hour bucket bound to a date target column is rejected

- **WHEN** a `trigger.group_by` time bucket with `unit: "hour"` is bound to a target column declared `date`
- **THEN** the request is rejected with HTTP 422, because the hour would be truncated on insert and the
  day's 24 bucket rows would collapse onto one under the target's `ordering_key`

#### Scenario: A day bucket bound to a date target column is accepted

- **WHEN** a `trigger.group_by` time bucket with `unit: "day"` or `"week"` is bound to a target column declared
  `date`
- **THEN** the declaration is accepted, because such a bucket starts at midnight and loses nothing

#### Scenario: A fractional measure bound to an integer target column is rejected

- **WHEN** an `avg` measure, or a `sum` over a `decimal` input column, is bound to a target column declared
  `long` or `integer`
- **THEN** the request is rejected with HTTP 422, because ClickHouse would truncate the fraction on insert
  and the rollup would read as an exact whole number

#### Scenario: An unwritten target column is rejected

- **WHEN** the target declares a column that no group key or measure writes
- **THEN** the request is rejected with HTTP 422

#### Scenario: An SQL declaration binds its keys by set and its columns by the INSERT list

- **GIVEN** a target whose `ordering_key` is `(day, deployment)` and which declares `day`, `deployment`
  and `tokens`
- **WHEN** an SQL declaration is `INSERT INTO usage_daily (deployment, day, tokens) SELECT deployment, date_trunc('day', request_time), sum(total_tokens) FROM dial_usage_log GROUP BY 1, 2`
- **THEN** the declaration is accepted, although its keys are listed in another order than the
  `ordering_key`, because the key set is compared as a set and every target column is in the column list
- **AND WHEN** the column list omits `tokens`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `tokens`

### Requirement: A nullable group key requires a declared filter

A `group_by` key — plain column or time bucket — whose input column is **nullable** SHALL be rejected with
HTTP 422 unless the pipeline declares a `filter`, naming the key, the nullable input column, and the
consequence. An **enrichment-sourced** group key SHALL count as nullable for this rule whatever its
declared nullability, because the grain join is outer and decides the matter independently of the flag.
Note that this makes the rule total rather than doing the whole job: a group key may only read an
enrichment column that is *declared* nullable in the first place (see **An enrichment-sourced value is
readable only where absence is representable**), so the join and the declaration agree. The rule SHALL be
enforced at registration and re-applied at execution, like its measure sibling above.

**This rule SHALL govern the form declaration only.** An SQL declaration carries no `filter`, and its
`WHERE` SHALL NOT be accepted as the answer in its place. A nullable key in an SQL declaration SHALL be
refused outright, with HTTP 422 `pipeline_validation_failed` suggesting a non-null default such as
`coalesce(<key>, '')` (see **An SQL declaration's group keys are exactly the target's non-nullable
ordering key**). The argument below against an outright refusal does not carry over: it rests on the form
vocabulary having no way to express the correct declaration, and the SQL declaration has one, the
author's own absence mapping inside the key.

A NULL reaching a group key is silently wrong in one of two ways, and which one depends on the key's form:

- **A plain column** — NULL is its own group, and the target cannot represent it. The group-key output names
  *are* the target's `ordering_key`, and ClickHouse refuses a nullable sorting key, so no target shape
  exists that could hold the value. The engine stores the column's type default instead — an empty string, a
  zero, an epoch date — and the rollup carries a key indistinguishable from measured data.
- **A time bucket** — NULL cannot be bucketed. The pruning bound is a comparison on the raw column, so every
  row missing that value drops out of the recompute and out of the rollup, whose totals then stop
  reconciling with the input with nothing recording why.

For an enrichment-sourced key the consequence is the more severe of the two: the row leaves the rollup
entirely rather than being mis-keyed, and — unlike a NULL in a source column — the value may arrive
later, at which point nothing re-dirties the group. The `filter` this requirement demands is the
declaration-time half of that guard; the runtime half — clamping a refresh window to what its enrichments
have been materialized through — is specified by `aggregate-enrichment-freshness`.

This is the nullable-measure failure met a third time — an engine that coerces rather than refusing turns a
declaration mistake into plausible output — but with that rule's remedy unavailable: a measure can be told
to declare its target column nullable, and a group key cannot.

**A declared `filter` SHALL be accepted as the answer, and the predicate SHALL NOT be parsed for it.**
Inferring whether an arbitrary boolean excludes NULLs is something this gate would do badly, and doing it
badly means either refusing a correct declaration or admitting a wrong one. What the gate can establish is
whether the declarer considered the question at all, and a pipeline carrying no membership predicate has
not. So `filter: "col IS NOT NULL"` — the honest fix — keeps working, while the declaration that silently
ignores the NULLs is refused. Rejecting a nullable key outright is the alternative and is worse: it would
leave the correct declaration with no way to be expressed.

The two forms are held to one rule deliberately. A bucket dropping rows is not obviously better than a
plain key fabricating one, and accepting the bucket while refusing the plain column would split the same
defect on a distinction that has nothing to do with it.

#### Scenario: A nullable plain group key with no filter is rejected

- **WHEN** a pipeline groups by a nullable input column and declares no `filter`
- **THEN** the request is rejected with HTTP 422 naming the group key and the nullable input column, because
  the NULL group would be stored as the target column's type default and read as real data

#### Scenario: A nullable bucket column with no filter is rejected

- **WHEN** a pipeline's `group_by` time bucket reads a nullable date/timestamp column and no `filter` is
  declared
- **THEN** the request is rejected with HTTP 422, because those rows can be bucketed into nothing and would
  silently leave the rollup

#### Scenario: A declared filter admits the nullable group key

- **WHEN** the same pipeline declares any `filter`
- **THEN** the declaration is accepted, the predicate is not inspected for null-handling, and the filter
  stands as the declarer's statement that the NULLs are accounted for

#### Scenario: A non-nullable group key needs no filter

- **WHEN** every `group_by` key reads a non-nullable input column
- **THEN** the declaration is accepted with no `filter`, because no NULL can reach a key

#### Scenario: The rule is re-applied at execution

- **WHEN** a measure's input column becomes nullable after the pipeline was registered and a refresh runs
- **THEN** the refresh is refused and the failure recorded, rather than writing type defaults into a
  target column that cannot represent the absence of a value

#### Scenario: An enrichment group key with no filter is rejected

- **WHEN** a pipeline groups by an enrichment column declared non-nullable and declares no `filter`
- **THEN** the request is rejected with HTTP 422 naming the key and the enrichment it is drawn from,
  because the grain join can yield NULL for it regardless of the declaration

#### Scenario: An SQL declaration's WHERE does not admit a nullable key

- **WHEN** an SQL declaration groups by a nullable input column and its `WHERE` reads
  `<column> IS NOT NULL`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the key and suggesting
  a `coalesce` wrap, and the `WHERE` is not inspected for null-handling

### Requirement: Scheduled refresh recomputes every affected group key in full

The system SHALL refresh an enabled `aggregate` pipeline on its declared cron, and on demand via
`POST /v1/pipelines/{name}/refresh`. One refresh SHALL run as two statements:

1. a **probe** that fixes the window end over the input's cursor tuple (its version and identity
   columns), applying the pipeline's `filter` (for an SQL declaration, the statement's own `WHERE`), the
   configured safety lag, and a keyset seek past the stored cursor; then
2. a single `INSERT … SELECT` on the writer credential that recomputes, **in full**, every group key the
   probed window touched — the set of dirty keys being a sub-select over exactly that window — and stamps
   the target's version column.

Recomputing each affected key in full SHALL be the contract, not an optimization detail. The target
collapses by key, so a write **replaces** rather than accumulates: a partial per-window aggregate would
overwrite a bucket's running total with only the newest slice. Full recomputation of the dirty keys is
the only shape that is correct under `upsert_by_key`, and it gives two further properties:

- **Idempotence** — a retried or duplicated run re-probes the same window, derives the same key set, and
  writes the same values, which the target collapses. A refresh that fails or times out therefore leaves
  the target unchanged for that window and is safe to repeat, and concurrent runs on multiple instances
  need no coordination.
- **Late-arriving data self-heals** — a row arriving for an already-materialized bucket re-dirties that
  key, and the next refresh recomputes the whole bucket rather than adding to it.

A refresh SHALL advance the pipeline's stored cursor only after the write succeeds. A refresh whose probe
matches nothing SHALL be a no-op that leaves the cursor unmoved.

Refresh SHALL be bounded: the probe SHALL apply a configured window limit, and a run that fills its limit
SHALL report that more work remains so the next tick continues from the new cursor.

When the declaration carries a time bucket, the recompute SHALL additionally be bounded to the span of the
buckets the window dirtied, on the bucketed column itself. This narrows nothing — every row of a dirty
bucket lies inside that span, so the recomputed values are identical — but it is the only bound the engine
can prune on: a tuple of *expressions* tested against a sub-select gives the partition and primary-key
indexes nothing to work with, so without it every refresh reads the whole input. The bound SHALL be derived
and stepped inside the statement rather than marshalled through the service, so the step past the last
bucket stays in the same calendar and time zone the bucketing used.

**When the declaration carries no time bucket, there is no such bound, and the recompute reads the whole
input on every refresh.** This is a property of the grain, not a gap in the implementation. The bucket
bound exists only because a time bucket *implies* it: every row of bucket `b` has its bucketed column
inside `[b, b + one unit)`, so the dirty buckets' own span bounds the rows that can belong to them, on a
raw column an index can prune. A plain group key carries no such implication — a key's rows may lie
anywhere in the input — so the only restriction left is the dirty-key tuple `IN`, which the indexes cannot
use.

A plain group key SHOULD therefore be a primary-key prefix of the input, or carry an index that prunes
usefully at the expected dirty-set size. A key that is neither costs a full input read per refresh, and
that cost is chosen at declaration time — the only moment it can still be changed. Narrowing the
pipeline's `filter` on a column that *is* a primary-key prefix prunes both halves of the write and is the
cheapest lever where such a column is populated.

The absence of a bound SHALL NOT be closed by bounding the recompute on a time column. The recompute must
see **every** row of every dirty key, and neither candidate column carries that guarantee for a plain
key: bounding on the window's own ingest-time axis excludes a key's rows ingested before the window, and
bounding on the event-time column excludes a key's rows whose events fall outside the window's span.
Either would produce plausible measures with nothing recording the loss — precisely the failure the
"recompute every affected group key in full" contract exists to prevent.

A time bucket SHALL be aligned in **UTC**, pinned explicitly in the compiled statement rather than inherited
from the bucketed column's declared zone (which, when the column declares none, is the ClickHouse server's
— deployment configuration). A `day` bucket therefore means UTC midnight on every deployment, and the same
declaration over the same rows is reproducible across them. Leaving it to the column would make a rollup's
day boundary a property of where it runs, and a server-zone change would re-align every future bucket while
already-materialized ones kept the old boundary — two alignments in one column, indistinguishable in the
output, since both render a bucket start of `00:00:00`.

An SQL declaration's time bucket SHALL be held to the same alignment. A bucket function takes no
time-zone argument, and every bucket call SHALL be compiled pinned to UTC (see **An SQL declaration
refreshes under the form path's contract**).

#### Scenario: A time-bucketed refresh does not read the whole input

- **WHEN** a pipeline grouping by a time bucket refreshes over a window covering a few buckets
- **THEN** the recompute is bounded to those buckets' span on the bucketed column, and the measures are the
  same as they would be unbounded

#### Scenario: A grain with no time bucket has no prunable bound

- **WHEN** a pipeline grouping only by plain columns refreshes
- **THEN** the recompute carries the dirty-key restriction alone, with no bound on any raw column, so it
  reads the whole input; the measures are correct, and the cost follows from the grain rather than from
  the size of the window

#### Scenario: Bucket alignment does not depend on the server's time zone

- **WHEN** a pipeline groups by a `day` bucket over a timestamp column that declares no time zone
- **THEN** the compiled statement pins the alignment to UTC, so each bucket starts at UTC midnight rather
  than at midnight in the ClickHouse server's zone

#### Scenario: A bucket recomputed after new rows arrive holds the total, not the increment

- **WHEN** a rollup bucket has been materialized from 10 input rows, 5 more rows land in the same
  bucket, and a refresh runs
- **THEN** the bucket's measures reflect all 15 rows

#### Scenario: Re-running a refresh changes nothing

- **WHEN** a refresh runs and then the identical refresh is run again with no new input rows
- **THEN** the target's rows and measures are unchanged

#### Scenario: A late row corrects its bucket rather than double-counting

- **WHEN** a row whose event time falls in an already-materialized bucket is ingested and a refresh runs
- **THEN** that bucket is recomputed to include the late row and no second row appears for the key

#### Scenario: A failed write leaves the cursor unmoved

- **WHEN** the `INSERT … SELECT` fails
- **THEN** the target is unchanged for that window, the stored cursor is not advanced, and the failure is
  reported without leaking ClickHouse internals

#### Scenario: An empty window is a no-op

- **WHEN** a refresh runs and the probe matches no rows
- **THEN** no write is issued and the cursor is unchanged

#### Scenario: Only due pipelines run

- **WHEN** the scheduler tick fires and an enabled pipeline's next run time has not arrived
- **THEN** that pipeline is not refreshed on this tick

#### Scenario: An SQL declaration's probe filters by its WHERE

- **GIVEN** an armed SQL declaration whose `WHERE` excludes every row of the next window
- **WHEN** a refresh runs
- **THEN** the probe matches nothing, no write is issued and the cursor is unchanged, exactly as for a
  form declaration whose `filter` excludes those rows

### Requirement: An aggregate declaration's enrichment references are protected against drop and rename

An enrichment an aggregate declaration reads SHALL be a dependency of that pipeline, protected exactly as
its declared input already is. `DELETE /v1/tables/{name}` naming such an enrichment SHALL be refused with
HTTP 409 naming the pipelines that read it, and a schema patch that would **drop or rename** a column of
that enrichment which a declaration names SHALL be refused with HTTP 409 on the same basis.

The protection is the same one the enrich declaration has always had, stated here because the aggregate
path did not have it. Its absence was not a decision: the guards parse declarations, and they parsed only
the enrichment registry's — so an enrichment a rollup grouped on was protected only when an enrich
declaration happened to read it too. That is coincidence rather than a guard, and retiring the unrelated
declaration made an enrichment several rollups read droppable, with the failure arriving at the next
refresh instead of at the delete.

The references SHALL be resolved from the declaration's own positions: each group key's input column, each
measure's column, the membership `filter`, and each measure's own `where`. An enrichment column has exactly
one spelling in a declaration's namespace — the qualified flat name — so those four positions are where a
reference can appear, and nothing repoints that name on a rename.

**An SQL declaration SHALL have its references resolved by a token scan of its stored text, never by
parsing it.** A guard runs on operations about other tables, so a parse there would cost a full SQL
front-end pass per stored declaration, and one unparseable draft would either fail an unrelated delete
or, swallowed into an empty set, silently let it through. The scan SHALL collect every qualified
`<enrichment>.<column>` token, and the contents of every double-quoted identifier, lowercased, so that a
reference spelled `alias."<enrichment>.<column>"` is found. The same scan SHALL supply the declaration's
bare source-column reads to the source half of [enrichment-runtime](../enrichment-runtime/spec.md), "A
schema edit may not repoint what a stored declaration reads". For that half the leading
`INSERT INTO <target> (<columns>)` prefix SHALL be excluded, so a target column name does not pin an
input column of the same name. If the prefix cannot be recognised, the whole text SHALL be scanned. A
false positive (a name inside a string literal or a comment) SHALL be accepted: it costs a spurious 409,
where a false negative would let a column be dropped under a stored declaration. An SQL declaration's
enrichment references do not depend on its input resolving, so an unparseable draft contributes whatever
its text names to the **enrichment** half of the guards. Its bare source-column reads SHALL be protected
only once the draft's input table has been derived, because a bare name means nothing until it is tied to
a source; an unparseable draft with no derived input pins no source column. Arming re-validates the
statement against the schema as it stands then, so a source column dropped under such a draft fails at
the gate rather than inside a refresh.

**What a pipeline reads SHALL be resolved from those positions as they are stored, so the protection
SHALL be stated as covering a declaration that is complete and no further.** A declaration may now be
stored before it is finished (see [pipeline-registry](../pipeline-registry/spec.md)), and an incomplete
one under-declares its reads by construction: an enrichment column the author has not yet typed into a
group key, a measure or a filter contributes no reference, so the column stays droppable and the
enrichment stays deletable. The bound applies to **`DELETE /v1/tables/{name}` exactly as it applies to
the schema patch** — both resolve the same references from the same stored declarations, and bounding
one while stating the other absolutely would describe a distinction the resolution does not make.

**The consequence SHALL be explicit rather than left to be discovered**, because it is the price of the
bound and it is a price worth naming: completing such a declaration afterwards fails at the declaration
gate, naming the column as unknown, and the author retypes or re-declares. What does not happen is the
failure this guard exists to prevent — a stored declaration silently reading a column that was dropped
under it — because a declaration that was never gated cannot be armed, previewed or executed without
being gated first, against the catalog as it stands then.

The column check SHALL be **per column**, not per table: a schema change to a column of the same enrichment
that no declaration names SHALL be permitted. A guard that froze an enrichment's whole schema because one
of its columns entered a rollup would be as real a failure as the missing guard it replaced.

A declaration whose input no longer resolves SHALL contribute no references rather than failing the
operation. The alternative is a delete or a patch about one table failing because an unrelated declaration
has drifted, which is the same posture the enrich guard takes.

**This SHALL be reported, not repaired.** The refusal names the pipelines; retiring or re-declaring them
stays the operator's action, and no cascade is performed.

#### Scenario: Deleting an enrichment a rollup groups on is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose column an aggregate declaration's group key
  reads, and no enrich declaration reads it
- **THEN** the request is refused with HTTP 409 naming that pipeline, and the enrichment is still readable

#### Scenario: A reference from a measure's predicate counts

- **WHEN** the only position naming the enrichment column is a measure's `where`, or the pipeline `filter`
- **THEN** the delete is refused just as it is for a group key, because a reference is a reference wherever
  the statement reads it

#### Scenario: Dropping or renaming the referenced column is refused

- **WHEN** a schema patch would drop or rename a column of that enrichment which the declaration names
- **THEN** the request is refused with HTTP 409 naming that pipeline

#### Scenario: An unreferenced column of the same enrichment stays changeable

- **WHEN** a schema patch drops a column of that enrichment which no declaration names
- **THEN** the patch succeeds

#### Scenario: An incomplete declaration protects nothing it has not yet named

- **GIVEN** an `aggregate` pipeline registered with `name`, `kind` and `target` alone, whose author
  intends to group on an enrichment column but has not declared the group key
- **WHEN** `DELETE /v1/tables/{name}` names that enrichment, or a schema patch drops that column
- **THEN** the request succeeds, because the stored declaration carries no reference to it — the same
  answer at the delete and at the patch

#### Scenario: Completing the declaration afterwards fails at the gate rather than silently

- **GIVEN** that enrichment column was dropped while the declaration was incomplete
- **WHEN** the author completes the declaration naming it and arms or previews the pipeline
- **THEN** the request is rejected with HTTP 422 naming the column as unknown, and no refresh has read
  or ever reads a dropped column

#### Scenario: The dependency releases when the reader goes

- **WHEN** the last pipeline reading that enrichment is deleted and the delete is retried
- **THEN** it succeeds

#### Scenario: An enrichment named only in an SQL declaration is protected

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose column only an SQL declaration reads, as
  `coalesce("usage_client_identity.client_session_id", '')`
- **THEN** the request is refused with HTTP 409 naming that pipeline

#### Scenario: An aliased quoted reference is found

- **WHEN** an SQL declaration reads the enrichment column as `u."usage_client_identity.client_session_id"`
  through a CTE alias `u`, and a schema patch would drop or rename `client_session_id` on that enrichment
- **THEN** the request is refused with HTTP 409 naming that pipeline

#### Scenario: A bare source column read only by an SQL declaration is protected

- **WHEN** a schema patch would drop or rename the input column `deployment`, which only an SQL
  declaration's `GROUP BY` reads, as a bare identifier
- **THEN** the request is refused with HTTP 409 `conflict` naming that pipeline

#### Scenario: A target column name in the INSERT list does not pin an input column

- **GIVEN** an SQL declaration whose column list names the target column `day`, and whose `SELECT` never
  reads the input column also named `day`
- **WHEN** a schema patch drops the input column `day`
- **THEN** the patch succeeds

#### Scenario: A name inside a string literal may refuse a change

- **WHEN** an SQL declaration's `WHERE` compares a column with the literal `'usage_scope.scope'`, and a
  schema patch would drop `scope` on the `usage_scope` enrichment
- **THEN** the request may be refused with HTTP 409 naming that pipeline, a false positive the token scan
  accepts so that it can never miss a real reference
