## ADDED Requirements

### Requirement: A masked field reads as absent at every reference

A field whose column is masked for the caller (see [table-access-control](../table-access-control/spec.md), "Column
visibility by caller role") SHALL be part of the entity's schema, and a star projection SHALL include it under the
rules that apply to any field (a heavy masked field stays out of the wildcard). Every reference to it — projection,
filter, grouping, ordering, `having`, join condition, function, lambda and `case` operand, window argument, partition
and ordering, aggregate input, array expansion, object-path access, a distinct projection, and the separate count
behind `totalCount` — SHALL read its **masked value**: the stored value on a row where the column's mask condition is
true, and the absent value on every other row. The reference a query SHALL agree with is the entity's **flattened
rows** — each source row the caller reads, with its enrichment values attached by the grain join on the stored grain
key and partition — in which every absent cell is then replaced by its absent value, an `array`, `map` or `object`
cell read as the paragraph on those types below states. A query SHALL return exactly the rows and values it would
return over that reference, so no clause, function or aggregate observes a stored value on a row where the cell is
absent; the only queries this does not cover are those refused by "Set operations over a masked output" and a query
carrying a table's name, refused below. Which rows the query reads is decided by row-policy evaluation, unchanged.

The grain join is part of the entity, not a reference the query makes: because it runs on the stored keys before
cells are replaced, a masked key or partition field does not detach an enrichment value, and each enrichment column is
decided by its own visibility and mask. Every restriction the service
derives from the caller's filter to narrow that join — the enrichment's key set, partition bounds transferred from the
source, an aligned lookup's partition pre-selection — SHALL admit every source row the filter admits, and the
late-materialized page every source row of the page, so none of them SHALL change which enrichment values those rows
carry.

The absent value SHALL be `NULL` for a field of a scalar type. An absent scalar cell SHALL be indistinguishable from
a stored null of that type in its value and in every value, error and error message computed from it, including
through functions that read a null's underlying value or that fail for particular values: none SHALL depend on the
stored value of an absent cell (it differs from a stored null only in being marked, see "Masked inputs are reported
per output cell"). For an `array`, `map` or `object` field, which the data store cannot make `NULL` inside an
expression, the absent value SHALL be the type's empty value (`[]`, `{}`, `{}`) wherever the field is an operand, and
an output cell that returns the bare field SHALL carry null, as a `NULL` cell does on that surface. Where rows are
compared rather than computed on — a distinct projection, a grouping key, a `union` that removes duplicates — an
absent cell of such a field SHALL differ from every stored value, the stored empty value included, as its null output
cell differs from a stored `[]` or `{}`; absent cells SHALL equal one another. A computed field over a masked column
SHALL be null on every row where a column it returns is absent.

Besides the grain join, which attaches enrichment values by the stored keys as part of the reference itself, three
disclosures SHALL be accepted, and are the only ways a stored value of an absent cell may affect a response:
- **The mask outcome.** Whether a cell is absent is itself the outcome of the mask condition (see "Column visibility
  by caller role").
- **Filter cost.** A pruned filter, or a conjunct copied into an enrichment's derived table, costs more or less
  depending on stored values, at the granularity of the data store's indexes. Repeated probes can recover an absent
  value that the caller's own filter isolates in a granule (see "A comparison on a masked field keeps the table's
  pruning" and "Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table").
- **Row order.** Where a query states no total order — no `sort`, ties under one, a page cut without a total order —
  rows come back in the order the data store produces, which tracks the table's storage order. So do the values
  inside an order-sensitive aggregate (such as an array of the group's values) and a ranking window without an
  ordering. That order is decided by the stored ordering-key and partition values, absent cells included, so a masked
  ordering-key or partition column's absent cells can often be read from the present values around them. This is
  kept so that masking does not impose a sort on every query. An administrator who needs such a column's absent
  values kept SHALL list it on every governing role, which makes it visible, or keep it off every role whose rows
  interleave with another role's in storage order. Which rows and values a query with a total order returns never
  depends on it; where the query leaves the choice open (an unordered page cut, `any`), the choice can.

"SQL translation" states the corresponding exceptions for a masked field: it expands to its masked value, and a
rewritten conjunct takes the form the next requirement gives it.

A query that references a masked field SHALL be refused `400 bad_request`, on every surface that resolves it, when it
carries a name that equals, case-sensitively, the name of a table it reads (its source or an enrichment), or begins
with that name followed by `.`. That covers a
lambda parameter, and every resolved output name at every level of the statement: an explicit `as` or SQL alias, a
name derived from a field or function, and a name the service generates for an operand's output. The mask is evaluated over the stored row's columns, which
the statement names by table, and a declared name equal to a table could capture those names. A query that names a
masked field fails today with `unknown field`, because the field is hidden. A query whose only reference to a masked
field is a wildcard ran today over the visible columns, and is refused once the wildcard includes the masked field:
that is a breaking change for such a query. A query that references no masked field SHALL be unaffected. "Output column naming when `as` is
omitted" states the exception for a derived name. `POST /v1/queries/validate-sql` SHALL report such a statement `valid: false` with
the refusal as `issues[0]` (code `invalid_sql`), and saving a saved query or dashboard widget whose body carries one
SHALL be refused `400 bad_request`.

#### Scenario: Row order follows storage order, absent cells included

- **GIVEN** a caller for whom `deployment`, an ordering-key column, is masked, present on its own rows and absent on
  others' rows of the same project
- **WHEN** it projects `user_hash` and `deployment` over that project with no `sort`
- **THEN** the rows return the values the reference holds, in an order that may follow the stored `deployment`, so an
  absent row can sit between present rows of the same value; no absent cell carries a value

#### Scenario: A filter on a masked field matches only present cells

- **GIVEN** a caller for whom `deployment` is masked, present on the rows of project `P1` only
- **WHEN** it filters `deployment = 'd1'`
- **THEN** only rows of `P1` whose `deployment` is `d1` match, even where a row of another project stores `d1`

#### Scenario: An aggregate counts and sums present cells only

- **GIVEN** a caller for whom `total_price` is masked, present on the rows of `P1` only
- **WHEN** it runs `count(total_price)`, `sum(total_price)` and `count()` over the rows of `P1` and `P2`
- **THEN** the first two cover the rows of `P1` only, and `count()` counts the rows of both projects

#### Scenario: An absent cell is null to null tests and grouping

- **GIVEN** a caller for whom `user_hash` is masked, present on the rows of `P1` only
- **WHEN** it filters `user_hash` `is null`, and separately groups by `user_hash`
- **THEN** the filter matches every row of `P2`, and the grouping puts every row of `P2` in the group whose key is
  null

#### Scenario: A value-dependent failure does not depend on an absent cell

- **GIVEN** a caller for whom `total_tokens` is masked, absent on a row of `P2` that stores a value for which a
  function fails, such as a `width_bucket` whose bucket count is computed from the cell
- **WHEN** it projects that expression over the row, once with the stored value one that would make it fail and once
  one that would not
- **THEN** both queries return the same result for the row, as for a row that stores a null

#### Scenario: An output named after the table is refused when a masked field is referenced

- **GIVEN** a caller for whom `deployment` is masked on `dial_usage_log`
- **WHEN** it projects `project_id` as `dial_usage_log` together with `deployment`
- **THEN** the response is `400 bad_request`
- **AND WHEN** it projects `project_id` as `dial_usage_log` and references no masked field
- **THEN** the query runs
- **AND WHEN** it posts the first statement to `POST /v1/queries/execute-sql` and to `validate-sql`, and saves it as a
  `sql` saved query
- **THEN** execution answers `400 bad_request`, validation `200` with `valid: false`, and the save `400 bad_request`
  with nothing stored

#### Scenario: A dotted output name led by a table name is refused

- **GIVEN** a caller for whom `deployment` is masked on `dial_usage_log`
- **WHEN** it projects the literal `'P1'` as `dial_usage_log.project_id`, groups by that name, and keeps groups whose
  `max(deployment)` equals `'d2'`; or sorts by that name and then by `deployment`
- **THEN** each query is refused `400 bad_request`

#### Scenario: A lambda cannot read an absent cell through a parameter name

- **GIVEN** a caller for whom `deployment` is masked, absent on the rows of `P2`
- **WHEN** it maps a Boolean array through a lambda whose body returns `deployment`, and whose parameter is named like
  a name the service uses internally, such as `__adas_mcond_0`
- **THEN** on the rows of `P2` every element is null
- **AND WHEN** the parameter is named `dial_usage_log`, the table the query reads
- **THEN** the response is `400 bad_request`

#### Scenario: A null test on a masked partition field keeps the enrichment values

- **GIVEN** a caller for whom `request_time`, the partition column `dial_usage_log_payload` is aligned on, is masked,
  present on the rows of `P1` only, while `dial_usage_log_payload.request_body` is visible to it
- **WHEN** it filters `request_time` `is null` and projects `dial_usage_log_payload.request_body`
- **THEN** every row of `P2` matches, and each carries its stored `request_body`

#### Scenario: A masked array field is empty as an operand and null as a cell

- **GIVEN** a caller for whom the `array` field `execution_path` is masked, present on the rows of `P1` only
- **WHEN** it filters on `execution_path` containing `'d1'`, and separately projects `execution_path` and its length
  with no filter
- **THEN** the filter matches no row of `P2`, and in the projection the rows of `P2` carry a null `execution_path`
  cell and a length of `0`
- **AND WHEN** it projects `execution_path` with `distinct`, and a row of `P1` stores an empty array
- **THEN** the response carries one row with an empty `execution_path`, unmarked, and one with a null
  `execution_path`, marked

### Requirement: A comparison on a masked field keeps the table's pruning

A top-level conjunct of a query's filter that compares a masked scalar field, referenced directly, with an operand
that contains no subquery and is not the null literal, nor an `in` list holding one — by `=`, `<>`, `<`, `>`, `<=`, `>=`, `in`, or a string-match
operator — or tests it `is not null`, SHALL be evaluated as the field's mask condition conjoined with the same comparison on the stored
column, the other operand reading masked values as it otherwise would. The operand may be a literal, a relative-time
expression or a caller value. An absent cell never satisfies such a comparison, so the two forms select the same rows;
the second lets the data store prune with the stored column's ordering key, partition key and skip indexes as it does
for a caller allowed the column on every row.

The pruning reads the stored column, so the amount of data the statement reads, and with it the query's latency and
whether it exceeds the query budget (`422 query_budget_exceeded`), can depend on values stored in cells that are absent
for the caller: a block of rows whose absent cells store a matching value is read and then discarded by the mask. The
rows and values returned never depend on them. This cost channel SHALL be an accepted disclosure, in exchange for
keeping a filter on a masked key or partition column as cheap as for a caller allowed the column. It is open only to
a caller that already reads those rows. It discloses what the data store's indexes hold for the granules the caller's
filter reaches: the ordering-key value where each granule starts, a skip index's summary of a granule, and a part's
partition-key range. Where the caller's visible conjuncts narrow the read to a granule that an absent row starts, or in
which it is the only row whose value the caller does not know, repeated equality or range probes can recover that
row's stored value. No response carries the value. An administrator who needs such a column's absent values kept
SHALL list it on every governing role.

A comparison with the null literal, or an `in` list that holds one, is not rewritten. `NULL IN (…, NULL, …)` is true
under ClickHouse's `transform_null_in = 1`, which query statements do not pin, so such a list stays unrewritten; a list
without a null item is not true on `NULL` under either setting. A comparison with the null literal is a null test, not
a comparison: an
`is null` test, which an absent cell satisfies, SHALL NOT take this form. Every other reference to a masked field,
including such a comparison under `or` or `not`, or against a subquery, SHALL read the masked value as "A masked field reads as absent at every
reference" states. The form SHALL
apply to the count behind `totalCount` and to each entity of a composite query, and it SHALL NOT change any result.

#### Scenario: A key-column filter on a masked field prunes as for an unmasked caller

- **GIVEN** a caller for whom `deployment`, an ordering-key column, is masked
- **WHEN** it filters `deployment = 'd1'` over a time range
- **THEN** the rows returned are those whose `deployment` is present and equals `d1`, and the statement reads no more
  rows than the same filter reads for a caller allowed `deployment` on the same rows

#### Scenario: A relative-time window on a masked time column prunes

- **GIVEN** a caller for whom `request_time`, the partition column, is masked, present on the rows of `P1` only
- **WHEN** it filters `request_time >= date_sub('day', 1, now())`
- **THEN** the rows returned are the rows of `P1` from the last day, and the statement reads no more rows than the
  same filter reads for a caller allowed `request_time` on the same rows

#### Scenario: The comparison under or is not rewritten and still returns the same rows

- **GIVEN** the same caller
- **WHEN** it filters `deployment = 'd1' or project_id = 'P2'`
- **THEN** the rows returned are those whose present `deployment` equals `d1`, together with the rows of `P2`

### Requirement: Masked inputs are reported per output cell

An output cell SHALL be **marked** when its value was computed with an absent input:

- in row mode, when its expression references a masked field that is absent on that row;
- for an aggregate output, when its input references a masked field absent on at least one row of its group — the
  value covers the present cells only and is partial;
- for a grouping output, when the masked field it references is absent on at least one row of its group;
- for a window output, when its argument, partition or ordering references a masked field absent on at least one row
  the window computes that row's value from — for an aggregate, the rows of its frame; for `row_number`, `rank` and
  `dense_rank`, every row from the start of its partition to the current row, whatever frame the window declares;
- for an output whose expression contains a scalar subquery, when that subquery's own output would be marked by these
  rules;
- in a composite query, when an output reads an operand's output column that is marked on the operand row it comes
  from, or references a masked field of its own as above;
- under a distinct projection, or a `union` that removes duplicates, when the cell is marked on at least one of the
  rows that collapse into the returned row. Marks SHALL NOT take part in deciding which rows are equal, so the rows
  returned are those the reference returns, and `sort`, paging and `having` apply as they do without masking. A
  distinct projection that projects a window output SHALL run as a window computed before the rows are collapsed,
  as the SQL surface plans it, and SHALL be marked by the same rule.

An output that references no masked field, directly, through an operand's output column or through a scalar subquery,
SHALL never be marked. A subquery that yields a set or a truth value (`in`,
`exists`) SHALL mark nothing wherever it appears, a projected `case` included, and neither SHALL a scalar subquery
that only filters; the outer operands compared with them mark by the rules above. A cell that is null only because an
outer join found no matching operand row SHALL NOT be marked. How a surface reports the marks is that surface's concern: the JSON
surfaces carry them in `$masked` (see "Response envelope"), while CSV export and Flight SQL carry none.

#### Scenario: A row-mode cell over an absent input is marked

- **GIVEN** a caller for whom `total_price` is masked, present on the rows of `P1` only
- **WHEN** it projects `total_price` and `concat(project_id, '-x')` over the rows of `P1` and `P2`
- **THEN** on the rows of `P2` `total_price` is marked and the other column is not

#### Scenario: A partial aggregate is marked

- **GIVEN** the same caller
- **WHEN** it groups by `project_id` and projects `sum(total_price)` and `count()`
- **THEN** the sum of the `P1` group is unmarked, the sum of the `P2` group is null and marked, and neither count is
  marked
- **AND WHEN** it groups by `deployment` instead, and a deployment's group mixes rows of `P1` and `P2`
- **THEN** that group's sum is marked and covers its `P1` rows only

#### Scenario: A distinct projection returns the same rows and ORs the marks

- **GIVEN** a caller for whom `route_name` is masked, present on the rows of `P1` only, where a row of `P1` stores a
  null `route_name` and every row of `P2` is absent
- **WHEN** it projects `route_name` with `distinct`
- **THEN** exactly one row has a null `route_name`, and it is marked

#### Scenario: A distinct projection with a window output over a masked field runs

- **GIVEN** a caller for whom `total_price` is masked, present on the rows of `P1` only
- **WHEN** it runs a `distinct` projection of `project_id` and `sum(total_price)` over a window partitioned by
  `project_id`, through the DSL and through SQL
- **THEN** both return one row per project, with the `P1` sum unmarked and the `P2` sum null and marked

#### Scenario: A ranking window with a narrow frame is marked by the rows ahead of it

- **GIVEN** a caller for whom `total_price` is masked, absent on one row
- **WHEN** it projects `row_number()` over a window ordered by `total_price` with nulls first and a frame of the
  current row only, so the absent row sorts first
- **THEN** every later row's number is marked, because the absent row is counted ahead of it

#### Scenario: A union all keeps each branch's composite cell

- **GIVEN** a caller for whom `execution_path` is masked, absent on the rows of `P2`
- **WHEN** it unions all `execution_path` from one branch with `arrayConcat(execution_path, ['x'])` from another
- **THEN** a `P2` row from the first branch carries a null cell and one from the second carries `['x']`, both marked

#### Scenario: A union that removes duplicates ORs the marks

- **GIVEN** a caller for whom `route_name` is masked, absent on the rows of `P2`, while a row of `P1` stores a null
  `route_name`
- **WHEN** it unions `route_name` from the rows of `P1` with `route_name` from the rows of `P2`, removing duplicates
- **THEN** one row carries a null `route_name`, and it is marked

#### Scenario: Marks travel through a derived table

- **GIVEN** a caller for whom `total_price` is masked, absent on the rows of `P2`
- **WHEN** it selects `cost` from a derived table that projects `total_price` as `cost`, or from a CTE that does
- **THEN** on the rows of `P2` `cost` is null and marked

#### Scenario: An unmatched outer join is not marked

- **GIVEN** a caller for whom a joined operand's `total_price` is masked
- **WHEN** a left join finds no operand row for a row of the preserved side
- **THEN** that row's `total_price` cell is null and unmarked

#### Scenario: Expanding an absent array yields no element and no mark

- **GIVEN** a caller for whom the `array` field `execution_path` is masked, present on the rows of `P1` only
- **WHEN** it expands `execution_path` into one row per element and counts the rows per element
- **THEN** the counts cover the elements of the rows of `P1` only, and no count is marked: an absent array expands
  to no element, so no expanded row carries an absent input

#### Scenario: A share computed with a scalar subquery is marked when the subquery is partial

- **GIVEN** a caller for whom `total_price` is masked, present on the rows of `P1` only
- **WHEN** it projects `sum(total_price) / (select sum(total_price) from dial_usage_log)` grouped by `project_id`
- **THEN** every group's share is marked, because the subquery's total covers the rows of `P1` only

### Requirement: Set operations over a masked output

An `intersect` or `except` SHALL be refused `400 bad_request`, naming the field, when an output column of either of its
operands depends on a masked field — references it directly or through an operand's output column, or through a
scalar subquery; a set-valued `in` or `exists` subquery, which marks nothing, does not count. The refusal SHALL apply wherever a query is resolved, so every surface accepts the same queries: DSL and SQL
execution, CSV export, Flight SQL (`INVALID_ARGUMENT`), `POST /v1/queries/validate-sql`, which reports the statement
`valid: false` with the refusal as `issues[0]` (code `invalid_sql`), and the validation of a saved query or dashboard
widget body on save, which refuses
it as it refuses any body its execution path rejects. `translate-sql` refuses every set operation for every caller
already. The refusal holds whatever the caller's other columns, because a row-comparing set
operation has no single row a mark could come from. A `union`, with or without duplicates, SHALL run, marked as "Masked
inputs are reported per output cell" states. A masked field that an operand only filters on SHALL NOT cause the
refusal. A caller none of whose fields is masked SHALL see no change. An operand that projects a wildcard depends on
every masked field the wildcard includes, so a wildcard `intersect` or `except` that ran today over the visible
columns is refused once a masked field joins the wildcard: that is a breaking change for such a statement. The refusal SHALL take precedence over the SQL
subset's acceptance of `INTERSECT` and `EXCEPT` with any branch (see
[sql-query-translation](../sql-query-translation/spec.md), "Set operations combine query blocks").

#### Scenario: An intersect over a masked output is refused

- **GIVEN** a caller for whom `user_hash` is masked
- **WHEN** it posts `SELECT user_hash FROM dial_usage_log WHERE project_id = 'P1' INTERSECT SELECT user_hash FROM
  dial_usage_log WHERE project_id = 'P2'` to `POST /v1/queries/execute-sql`
- **THEN** the response is `400 bad_request` naming `user_hash`

#### Scenario: The intersect refusal on Flight and on validation

- **GIVEN** the same caller and statement
- **WHEN** it runs the statement over Flight SQL, and posts it to `POST /v1/queries/validate-sql`
- **THEN** Flight answers `INVALID_ARGUMENT`, and validation answers `200` with `valid: false` and the refusal as
  `issues[0]`
- **AND WHEN** it saves the statement as a `sql` saved query
- **THEN** the save is refused `400 bad_request` and nothing is stored

#### Scenario: A wildcard intersect is refused once a masked field joins the wildcard

- **GIVEN** a caller for whom a non-heavy column of `dial_usage_log` is masked
- **WHEN** it posts `SELECT * FROM dial_usage_log WHERE project_id = 'P1' INTERSECT SELECT * FROM dial_usage_log WHERE
  project_id = 'P2'`
- **THEN** the response is `400 bad_request` naming the masked field, where before masking the statement ran over the
  visible columns

#### Scenario: An intersect that only filters on a masked field runs

- **GIVEN** the same caller
- **WHEN** it intersects two selections of `project_id` whose filters read `user_hash`
- **THEN** the query runs, and the filters read the masked value of `user_hash`

### Requirement: `$masked` is a reserved output column name

A top-level output column — one that becomes a key of the response rows — SHALL NOT be named `$masked`, whether the
name is given (`as` in the DSL, an alias in SQL) or derived. A column of a nested block, a derived table or a CTE
never reaches the response and MAY be so named.
The name SHALL be matched exactly and case-sensitively, as JSON member names are, so `$Masked` is an ordinary name.
A query naming one SHALL be refused `400 bad_request` on the HTTP surfaces and `INVALID_ARGUMENT` on Flight SQL, on
every surface that resolves a query's output names — execution, CSV export, translation and validation — whatever the
caller's column visibility, so a query's validity never depends on the caller's roles. `POST /v1/queries/validate-sql`
SHALL report it `valid: false` with the refusal as `issues[0]` (code `invalid_sql`), and saving a saved query or
dashboard widget whose body names one SHALL be refused `400 bad_request`.

#### Scenario: A DSL alias $masked is refused

- **WHEN** a caller posts a row-mode query projecting `{"field": "project_id", "as": "$masked"}`
- **THEN** the response is `400 bad_request` and nothing is executed

#### Scenario: A SQL alias $masked is refused

- **WHEN** a caller posts `SELECT project_id AS "$masked" FROM dial_usage_log` to `POST /v1/queries/execute-sql`
- **THEN** the response is `400 bad_request`
- **AND WHEN** it posts the same statement to `POST /v1/queries/translate-sql`, or runs it over Flight SQL
- **THEN** translation answers `400 bad_request` and Flight answers `INVALID_ARGUMENT`

#### Scenario: A saved query naming $masked is not stored

- **WHEN** a caller saves a `sql` saved query, or a dashboard with an inline widget, whose body projects a column as
  `$masked`
- **THEN** the save is refused `400 bad_request` and nothing is stored
- **AND WHEN** the name is given to a column of a derived table the body reads from, not to a top-level output
- **THEN** the save succeeds

#### Scenario: A differently cased name is an ordinary alias

- **WHEN** a caller projects `project_id` as `$Masked`
- **THEN** the query runs and each row carries the key `$Masked`

## MODIFIED Requirements

### Requirement: Response envelope

The system SHALL return query results as a response object carrying a `rows` array and a nullable
`totalCount`. Each row SHALL be a field-name → value map produced from the ClickHouse client's
records. Every projected column SHALL be present in every row, carrying a JSON `null` when its value
is null (notably an unmatched LEFT-JOINed enrichment column) — a null cell SHALL NOT be dropped from
the row object, so the row's key set matches the requested projection, plus the `$masked` member on a row that
carries one. Values SHALL be serialized in
their natural JSON form, with the ClickHouse client's internal array wrapper unwrapped **at every
depth**: an `array` cell SHALL serialize as a JSON array, and an `object` cell SHALL serialize as a
JSON object whose nested arrays are themselves JSON arrays — never the wrapper's own field shape
(`{"array": [...], "arrayOfObjects": [...]}`). Within an `object` cell the member layout is
whatever the ClickHouse `JSON` column returns, passed through unchanged rather than reshaped:
nested objects surface as **dotted path keys** (a stored `{"ctx": {"tags": [...]}}` reads back as
the member `"ctx.tags"`), and a member whose stored value is null is **not materialized by the
column at all**, so it is absent from the row object rather than present as `null`. A null element
*inside* an array (a stored `["a", null]`) SHALL serialize as JSON `null` in place, and no nested
null SHALL fail the request. The `totalCount` SHALL be
populated only for row-mode queries that opt in via the offset page's `include_total`, computed as a
separate count of the same filter; otherwise it SHALL be null.

A row with at least one marked cell (see "Masked inputs are reported per output cell") SHALL carry one more member,
`$masked`: a JSON array of the marked output columns' names, in projection order, each named as its key in the row. A
row with no marked cell SHALL NOT carry `$masked`, so a caller none of whose columns is masked receives exactly the
rows it received before masking existed, for any query that does not name an output `$masked`. `$masked` is never an
output column (see "`$masked` is a reserved output
column name"), so it cannot collide with one. A marked cell SHALL keep its key in the row, carrying its value: null for
an absent field, or what the expression computed from the absent value.

#### Scenario: Total count returned when requested

- **WHEN** a row-mode query sets the offset page's `include_total` to true
- **THEN** the response `totalCount` is the count of all rows matching the filter, independent of the
  page's offset and limit

#### Scenario: Total count omitted by default

- **WHEN** a query does not request the total (or is aggregate-mode)
- **THEN** the response `totalCount` is null and only `rows` is populated

#### Scenario: Null projected cell is preserved in the row

- **WHEN** a row-mode query projects an enrichment column whose row has no matching enrichment record
  (so the LEFT-JOINed value is null)
- **THEN** the result row still contains that column's key with a JSON `null` value, rather than
  omitting the key

#### Scenario: Arrays nested inside an object cell serialize as JSON arrays

- **WHEN** a row-mode query projects an `object` column whose stored value contains an array member, directly or inside a nested object (e.g. `{"roles": ["admin"], "ctx": {"tags": ["a"]}}`)
- **THEN** the response renders those members as JSON arrays, not as the ClickHouse client's array-wrapper object

#### Scenario: Null array elements inside an object cell are preserved

- **WHEN** a row-mode query projects an `object` column whose stored value contains a null array element (e.g. `{"roles": ["a", null]}`)
- **THEN** the request succeeds and the element renders as JSON `null` in place

#### Scenario: Null object members are absent, not null

- **WHEN** a row-mode query projects an `object` column whose stored value had a null member (e.g. `{"sub": null}`)
- **THEN** the request succeeds and the `sub` key is absent from the object — the `JSON` column never materialized the path — rather than present with a `null` value

#### Scenario: Nested object members keep their dotted path keys

- **WHEN** a row-mode query projects an `object` column whose stored value nests an object (e.g. `{"ctx": {"tags": ["a"]}}`)
- **THEN** the member appears under the flattened key `"ctx.tags"` exactly as the `JSON` column returns it

#### Scenario: A row with absent cells lists them in $masked

- **GIVEN** a caller for whom `total_price` and `user_hash` are masked, present on the rows of `P1` only
- **WHEN** it projects `project_id`, `user_hash` and `total_price` as `cost`
- **THEN** a row of `P1` is `{"project_id": "P1", "user_hash": "…", "cost": …}` with no `$masked`, and a row of `P2`
  is `{"project_id": "P2", "user_hash": null, "cost": null, "$masked": ["user_hash", "cost"]}`

#### Scenario: A caller with no masked column sees no $masked

- **WHEN** a caller none of whose columns is masked runs any query
- **THEN** no row of the response carries `$masked`

### Requirement: Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table

A top-level conjunct of the filter SHALL be **copied** into an enrichment's derived-table `WHERE` on the single-entity
read path, in the row statement and in the `include_total` count statement (the filter flattened as "SQL translation"
flattens it for keying), beside its grain-key and partition restrictions (or the transferred partition bounds that replace them), when it names exactly one
field, that field belongs to that enrichment and is of type `string`, `integer`, `long` or `decimal`, and the
conjunct is one of:

- `co` or `ico` with a non-empty string literal;
- `eq` against a non-null literal that is not the field type's default (`''` for `string`, `0` for a number), the
  literal written on either side;
- `in` whose every literal is non-null and not the field type's default;
- `eq` of `starts_with(field, literal)` or `contains(field, literal)` against `true`, with a non-empty literal.

A conjunct whose field is masked for the caller (see "A masked field reads as absent at every reference") SHALL still
be copied, over the stored value, while the statement's own `WHERE` keeps the masked or rewritten form. Every listed
conjunct is `NULL` or false on an absent cell, so that form implies the copy and the rows returned are unchanged.
Where the derived table computes the conjunct as `__adas_c<n>`, the outer `WHERE` SHALL read `<mask> AND
"__adas_c<n>"`, the mask being the field's mask condition over the source row.

The conjunct SHALL also stay in the statement's own `WHERE`. An enrichment that receives a copied conjunct but has
no source-only conjunct to key it SHALL be joined as a derived table restricted by its copied conjuncts alone.
When the conjunct's field is `heavy` and the statement reads it nowhere but in copied conjuncts and in calls
"Scalar calls over a heavy text column are computed inside the enrichment's derived table" pushes, the derived
table SHALL NOT project the field: it SHALL compute each copied conjunct over it as a column aliased
`__adas_c<n>`, and the statement's own `WHERE` SHALL read that column in place of the conjunct. On a matched row
the column holds the conjunct's value, which the copy has made true; on an unmatched row it reads `0` or NULL, both
rejected, which is what the conjunct gives an unmatched row — so the build holds a flag per matching row instead
of the column the conjunct was decided on. No
other conjunct SHALL be copied — in particular not `IS NULL`, `IS NOT NULL`, `ne`, `nc`, `inc`, `lt`, `le`, `gt`,
`ge`, a `not`, an `or`, a `coalesce`, a comparison over an `object`, `map`, `array`, `enum`, `boolean`, `uuid`,
`date` or `timestamp` field, or a conjunct naming more than one field.

Copying SHALL NOT change any result. Every allow-listed conjunct is false (or NULL) on the value an unmatched
enrichment row reads — the column's type default under the single-entity statements' `join_use_nulls = 0`, or
NULL for a `Nullable` column — so a source row whose enrichment row the copy removes is rejected by the outer
`WHERE` either way, and a kept row's enrichment row satisfies the copy because it satisfies the outer conjunct.
`IS NOT NULL` is excluded because it is true on a non-nullable column's default. A copied conjunct inside a
`FINAL` derived table SHALL filter the newest version of each key, not an older one; ClickHouse applies a
non-sorting-key `WHERE` after the merge, and the read path sends no setting that moves it before.

#### Scenario: A body search narrows the payload build

- **WHEN** a query counts rows with `request_time >= T AND dial_usage_log_payload.request_body ico 'error'`
- **THEN** the payload derived table carries the `ILIKE '%error%'` condition beside the transferred
  `request_time >= T` bound, with no key set, because the payload is partition-aligned on `request_time` and the
  window is the only source-only conjunct
- **AND** with a further source conjunct such as `deployment = 'd'` the condition stands beside the
  `event_id IN` and `request_time IN` restrictions instead
- **AND** the derived table projects `(request_body ILIKE '%error%') AS "__adas_c0"` and not `request_body`, and
  the outer `WHERE` reads `"dial_usage_log_payload"."__adas_c0"` in place of the conjunct

#### Scenario: A body search beside a raw read of the body keeps the body joined

- **WHEN** the same conjunct stands in a query that also reads `dial_usage_log_payload.request_body` raw
- **THEN** the derived table carries the copied condition and projects `request_body`, and the outer `WHERE`
  carries the conjunct itself

#### Scenario: A literal written before the field is copied as well

- **WHEN** a query's filter carries, beside `request_time >= T`, an `eq` whose first operand is the literal `'w'` and
  whose second is a light `string` column of the payload
- **THEN** the payload derived table carries `'w' = <column>`, and so does the outer `WHERE`

#### Scenario: Conjuncts that are true on an unmatched row stay outside

- **WHEN** a query's filter carries, beside `request_time >= T`, a conjunct on an enrichment column that is
  `IS NULL`, `IS NOT NULL`, `ne`, `inc`, `lt` over `length(...)`, a `not`, an `or`, or `eq` against `''`
- **THEN** that conjunct appears only in the outer `WHERE`, not in the derived table

#### Scenario: An unkeyed enrichment is narrowed by its own conjunct

- **WHEN** a query's filter is only `dial_usage_log_payload.request_body ico 'error'`
- **THEN** the payload is joined as a derived table restricted by that condition alone, with no key sub-query

#### Scenario: The newest version decides

- **GIVEN** a payload key whose older version matches a copied conjunct and whose newest version does not
- **WHEN** a final-read query with that conjunct runs
- **THEN** the source row is not returned, exactly as the unshaped statement returns

#### Scenario: Copying returns the unshaped rows

- **GIVEN** source rows with no payload row, payload rows that match and fail the conjunct, and several
  versions of one key
- **WHEN** a query with a copied conjunct and the same query rendered unshaped run against ClickHouse
- **THEN** both return identical rows and identical `include_total` counts

### Requirement: Scalar calls over a heavy text column are computed inside the enrichment's derived table

A `heavy` enrichment field of type `string` SHALL NOT be projected by its enrichment's derived table on the single-entity
read path, in the row and count statements, when the statement references it **only** as the first argument of
allow-listed calls. The derived table SHALL instead project each distinct allow-listed call over it once,
aliased `__adas_c<n>`, and every occurrence of that call in the statement SHALL read that column. The allow-list
is: `length`; `json_extract_string`, `json_extract_long`, `json_extract_double`, `json_extract_bool`,
`json_extract_array`, `json_extract_string_ci`; and `starts_with` and `contains` with a non-empty literal. Every
argument after the first SHALL be a literal. A field referenced anywhere else — raw, inside another function, inside
a lambda, or in a call outside the list — SHALL be projected whole, and its calls SHALL be rendered over it as
today. An enrichment carrying a pushed call but no key or copied conjunct SHALL be joined as a derived table with
no `WHERE`. A heavy field a late-materialized block fetches for its page is not pushed. A heavy field masked for the
caller SHALL be pushed as well. The outer statement SHALL read each pushed column through the field's mask, where the
source row the mask reads is in scope, taking as the absent value the call's value on an absent cell: the scalar masked
form for the calls that return `NULL` over `NULL`, and `''` for `json_extract_string_ci` and `[]` for
`json_extract_array`, whose input is read as `''` when absent. Its references SHALL carry the mask into the row's
flags.

The calls are allow-listed because their value on an unmatched row is already the default of their result type,
which is what a column computed inside the derived table reads on an unmatched row: over a `Nullable` column each
returns NULL (or, for the two array/ci extractions, which read an absent document as `''`, the empty array and
the empty string), and over a non-nullable column each returns `0`, `''`, `false` or `[]` for `''`. So a pushed
call SHALL return, for matched and unmatched rows alike, exactly what the call over the joined column returns
unshaped. Output names and validation are decided on the call as written, never on the pushed column.

#### Scenario: An average body length joins a number, not a body

- **WHEN** an aggregate query selects `avg(length(dial_usage_log_payload.request_body))` grouped by `deployment`
  over a `request_time` window
- **THEN** the payload derived table projects `length(request_body) AS "__adas_c0"` and not `request_body`, and
  the aggregate reads `"dial_usage_log_payload"."__adas_c0"` under the output name `avg`

#### Scenario: A raw reference keeps the whole column

- **WHEN** the same query also selects `dial_usage_log_payload.request_body` itself, or wraps it in a call outside
  the allow-list
- **THEN** the derived table projects `request_body` and the call renders over it

#### Scenario: Pushed calls return the unshaped values for unmatched rows

- **GIVEN** source rows with no payload row and payload rows whose body is NULL, `''` and a JSON document
- **WHEN** each allow-listed call is evaluated through a pushed column and through the unshaped join
- **THEN** both return identical values, NULLs included, for every row

### Requirement: A lookup on an aligned enrichment pre-selects the source's partitions

A statement whose `WHERE` carries a qualifying conjunct `C` over an aligned enrichment `E` SHALL gain
`partitionExpr(source.p) IN PS` in its own `WHERE`, alongside `C`, where
`PS = (SELECT DISTINCT partitionExpr(p) FROM E WHERE C [AND B] SETTINGS final = 0)`, `p` is the alignment column and
`B` is the statement's source-only bounds on the source's partition column, rendered on `E.p` instead. A bound is a
top-level source-only comparison conjunct that reads the source's partition column and nothing else and holds no
subquery — `request_time >= now() - INTERVAL 1 DAY`, say. When the source's partition column is masked for the caller,
a bound SHALL be only such a conjunct that "A comparison on a masked field keeps the table's pruning" rewrites, so
every row it admits satisfies the comparison on the stored value; a null test is not a bound there. A qualifying
conjunct `C` MAY be over a masked enrichment field: `C` is `NULL` or false on an absent cell, so the source rows the
statement's own masked `WHERE` admits are among those whose partitions `PS` names. `B` exists so that a statement which already bounds its time
does not pay a read of `C`'s column over all of `E`'s history to name the days it named itself; without a bound `PS`
reads `C`'s column across every partition of `E`.

`PS` SHALL NOT be repeated inside `E`'s derived table. ClickHouse evaluates an uncorrelated subquery once per scope, so
a copy there is a second read of `C`'s column, and it prunes nothing more: the analyzer carries the source's
restriction into `E`'s derived table, and into every other aligned join, through the tuple join's `source.p = E.p`
(measured on ClickHouse 26.3 and 26.8 under `do_not_merge_across_partitions_select_final = 1`, which every aligned read
carries). A join of the same statement without an aligned partition pair has no such equality; it SHALL be keyed on
`partitionExpr(source.p) IN PS` as one of its source-only key predicates, so that it reads the grain keys of the
selected partitions rather than hashing its whole table. When the statement is late-materialized, the restriction
SHALL appear in the page block only, and the outer statement is confined to the page's keys instead. Only a block with
a heavy late enrichment is late-materialized beside a pre-selection (see "Projection-only enrichment columns are
fetched for the returned page only"). A count for
`include_total` SHALL take the same restriction as the page.

On the read path a top-level `AND` conjunct SHALL qualify only when all of these hold: it references one
column of one aligned enrichment alone; that column is not heavy; it has the form `col = <literal>` or
`col IN (<literals>)`; no literal is the column type's default value (`''` for text, `0` for a number); and it is
false on an unmatched row (the null-rejecting notion of "Null-rejecting enrichment-only conjuncts narrow the
enrichment's derived table"). Any other conjunct — a range, a substring match, a function call over the column,
`NOT IN`, `!=`, a comparison with the default value, a subquery, a lookup on a heavy column — SHALL NOT qualify, and
the statement SHALL be emitted exactly as before. A heavy column is left out because the pre-selection reads the
lookup column across every partition, which for a body is the read the shaping exists to avoid; a subquery because its
values may include the default an unmatched row reads. The pipeline paths (enrichment-runtime, aggregate-pipeline)
apply the same rewrite to the lookups they build themselves, an `IN (<subquery>)` among them. `C` SHALL be rendered
over the bare column in the pre-selection, never through `toString` or another wrapper.

The rewrite SHALL be exact: the result of a rewritten statement SHALL equal the result of the unrewritten
one. An admitted row's matched enrichment row satisfies `C` and shares the row's partition value, so it satisfies `B`
on `E.p` too and the row's partition is in `PS`; reading `PS` with `final = 0` only adds stale versions and can only
widen it; keying a join on a conjunct of the statement's own `WHERE` never changes which rows it returns; and an
unmatched row fails `C` and was never admitted.

When several conjuncts qualify, each SHALL contribute its own restriction. A statement with no qualifying
conjunct SHALL be unchanged.

#### Scenario: A session lookup prunes both tables to the session's days

- **GIVEN** an aligned `usage_client_identity` of `dial_usage_log`
- **WHEN** a query filters `"usage_client_identity.client_session_id" = 's1'`, and the block is not late-materialized
- **THEN** the statement restricts `dial_usage_log` to the partitions holding an enrichment row of `s1`, its plan
  reads the enrichment's derived table in those partitions only, `PS` occurs once per scope that needs it, and the
  statement returns the same rows as without the restriction

#### Scenario: A time-bounded lookup bounds the pre-selection's own read

- **WHEN** a query filters `request_time >= <t>` and `"usage_client_identity.client_session_id" = 's1'`
- **THEN** `PS` reads `usage_client_identity` only in the partitions at or after `<t>`, and the statement returns the
  same rows as without the restriction

#### Scenario: A join without a partition pair is keyed on the pre-selection

- **GIVEN** a query that also reads an enrichment of `dial_usage_log` keyed on `chat_id`
- **WHEN** it filters `"usage_client_identity.client_session_id" = 's1'`
- **THEN** that enrichment's derived table is keyed on the `chat_id`s of the selected partitions' source rows, and the
  statement returns the same rows as without the restriction

#### Scenario: A late-materialized page and its count are pre-selected

- **WHEN** a lookup query projects a heavy payload column and is read late-materialized with `include_total`
- **THEN** the page block and the count each carry the restriction, the outer statement does not, and the rows and
  the total equal the unrewritten statement's

#### Scenario: A default-valued lookup does not qualify

- **WHEN** a query filters `"usage_client_identity.client_session_id" = ''`, or a numeric enrichment column `= 0`
- **THEN** no partition pre-selection is emitted and the statement returns the unmatched source rows as
  before

#### Scenario: A negated or ranged conjunct does not qualify

- **WHEN** a query filters an aligned enrichment column with `NOT IN`, `!=` or a range
- **THEN** no partition pre-selection is emitted

#### Scenario: An IN list lookup qualifies

- **WHEN** a query filters an aligned enrichment column with `IN ('s1', 's2')`
- **THEN** the pre-selection is emitted, and the result equals the unrewritten statement's

#### Scenario: A lookup on a heavy column does not qualify

- **WHEN** a query filters `"dial_usage_log_payload.response_body" = 'x'`
- **THEN** no partition pre-selection is emitted

#### Scenario: The lookup is rendered on the bare column

- **WHEN** a pre-selection is emitted for a lookup on a `string` column
- **THEN** `PS` does not wrap the column in `toString`

### Requirement: A partition-only key on an aligned enrichment is transferred, not keyed

On the query surface, a keyed join to a partition-aligned enrichment SHALL be restricted by the source's
partition-column bounds themselves, rendered on the enrichment's alignment column, **in place of** both key sets
(`grain_key IN (…)` and `partition_column IN (…)`), when every source predicate that keys it is such a bound. A
bound is a top-level source-only comparison of the statement's filter that reads the source's partition column
and nothing else, and holds no subquery. When that column is masked for the caller, a bound SHALL be only such a
comparison that "A comparison on a masked field keeps the table's pruning" rewrites; any other conjunct on it — a null
test among them — keys the join as a non-bound predicate does, so the join still attaches every row the filter
admits.

The statements this applies to are the single-entity read statement (including the page block of a
late-materialized read), its `include_total` count statement, and the entity blocks of a composite SQL statement.
The enrichment scan, the group fetch, the Class-1 materialize and the aggregate refresh and its preview SHALL
render exactly as they did before this requirement.

The join SHALL keep every other part of its derived table: the projection, the projection pruning of `object`
paths, the pushed enrichment conjuncts and computed columns, and the `FINAL` modifier. Only the two key sets are
replaced. The join condition SHALL remain the aligned pair's tuple equality.

The key sets SHALL be kept, exactly as before, when any source predicate that keys the join is not a bound. That
covers:
- an equality or range on any other source column;
- a bound on the partition column that holds a subquery, or that reads another column as well;
- a disjunction;
- a key conjunct a composite join's reduction hands to the block.

A point lookup such as `trace_id = X` therefore keeps the grain-key set that confines its read to one granule. A
join with no aligned pair and an unkeyed join SHALL render exactly as before, and a late-materialized join SHALL render
as "Projection-only enrichment columns are fetched for the returned page only" states.

The transfer is sound for the same reason the partition restriction is: the alignment invariant gives each
admitted source row an enrichment row with the same partition value, so the bound holds for that enrichment row
whenever it holds for the source row, and the `LEFT JOIN` result is unchanged. The derived table then holds every
enrichment row of the bounded partitions. For a row-grain aligned enrichment that is no more than one row per
source row the window admits.

The key sets cost more than they restrict for a window-only read. Each holds every value the window selects (millions
of values over a month of history) and is built in full before the scan. The grain-key set prunes nothing
on a table sorted by a random key, and the partition set restates a range ClickHouse can apply directly.

Removing both builds is what makes a window-only read cheap. With a pushed conjunct narrowing the enrichment (an
equality on an enrichment column, or a path filter on an `object` column) it uses about an order of magnitude less
time and memory. A read that joins per-user totals back over a long window fits a memory budget that the two sets
exceeded. Full-window aggregates run several times faster, with peak memory unchanged or lower. Point lookups by
`trace_id`, `event_id` or `chat_id` are unchanged, because they are not window-only.

#### Scenario: A window-only read transfers its window onto the aligned enrichment

- **GIVEN** a source partitioned on `day` and an enrichment aligned with it on `day`
- **AND** a query whose filter is `day >= T` and which selects and sorts by an enrichment column, so the block is
  not late-materialized (see "Projection-only enrichment columns are fetched for the returned page only")
- **WHEN** the statement is rendered
- **THEN** the enrichment's derived table carries `enrichment.day >= T`
- **AND** it carries neither `enrichment.grain_key IN (SELECT …)` nor `enrichment.day IN (SELECT …)`
- **AND** the join condition still compares the grain key and `day`

#### Scenario: A window-only page projecting the aligned enrichment only is page-keyed instead

- **GIVEN** the same aligned pair and a row-mode query whose filter is `day >= T`, which selects an enrichment column
  and neither filters nor sorts on one
- **WHEN** the statement is rendered
- **THEN** the block is late-materialized: the enrichment is joined as its own table for the page's keys, with
  neither a transferred window nor a key set, and the page block does not join it

#### Scenario: A pushed enrichment conjunct stays beside the transferred window

- **GIVEN** the same aligned pair and a filter `day >= T AND enrichment_column = X`
- **WHEN** the statement is rendered
- **THEN** the derived table carries `enrichment.day >= T` and `enrichment_column = X`
- **AND** no key set

#### Scenario: Any other source predicate keeps both key sets

- **GIVEN** the same aligned pair, a filter `day >= T AND deployment = 'd'`, and a query that selects and sorts by an
  enrichment column
- **WHEN** the statement is rendered
- **THEN** the derived table carries both key sets over both conjuncts, exactly as before

#### Scenario: A join without an aligned pair is keyed as before

- **GIVEN** an enrichment keyed on another source column with no partition pair, and a window-only filter
- **WHEN** the statement is rendered
- **THEN** its derived table carries its grain-key set over the window, exactly as before

#### Scenario: The count statement transfers as its page does

- **GIVEN** an offset-paged query with `include_total` whose filter is `day >= T AND enrichment_column = X`
- **WHEN** the count statement is rendered
- **THEN** the enrichment's derived table carries `enrichment.day >= T` and no key set

#### Scenario: A composite reduction keeps the key sets

- **GIVEN** a composite SQL join whose entity operand carries a window and receives a one-hop reduction of its
  join key
- **WHEN** the operand's block is rendered
- **THEN** its aligned enrichment joins carry both key sets

#### Scenario: The aggregate refresh and its preview keep the key sets

- **GIVEN** an SQL aggregate declaration whose `WHERE` is only a bound on the source's partition column and whose
  block reads an aligned enrichment
- **WHEN** its refresh statement and its preview are rendered
- **THEN** that enrichment's derived table carries both key sets in each, exactly as before

#### Scenario: The transferred read returns the keyed read's rows

- **GIVEN** an aligned source and enrichment holding rows inside and outside a window
- **WHEN** the same window-only query, sorted on the enrichment column so it is not late-materialized, runs rendered
  with the transfer and with both key sets
- **THEN** both return identical rows

### Requirement: SQL translation

The system SHALL translate a validated structured query into ClickHouse SQL by building a jOOQ query
model on the connection-less `SQLDialect.CLICKHOUSE` render context and rendering it to a SQL string
with literals inlined (the ClickHouse HTTP client executes a raw SQL string). Every inlined string
literal SHALL escape the backslash as well as the quote, because ClickHouse reads a backslash inside a
literal as an escape: a value matches exactly the characters the caller sent, and no value can close its
literal early and run the rest of itself as SQL. Field bindings SHALL be
sourced from the table-management catalog; allowlisted fields SHALL expand to their physical columns,
qualified by their owning table. A field masked for the caller (see "A masked field reads as absent at every
reference") SHALL instead expand to its masked value. Source columns SHALL be referenced by their bare flat name; enrichment
columns SHALL be referenced by their table-qualified flat name `<enrichment_table>.<column>` (the same
name discovery publishes). When a query references columns contributed by one or more enrichments of
the source, the translator SHALL emit a `LEFT JOIN` of each referenced enrichment table on the shared grain key
`source.grain_key = enrichment.grain_key` (the same column name on both sides), joining only
the enrichments actually referenced across the
query's `select`/`filter`/`sort`/`group_by`; enrichment joins are single-key with 0..1 cardinality and
SHALL NOT change the source row multiplicity **when the query is read final**. That 0..1 cardinality
holds regardless of pending background merges because a final read deduplicates the enrichment too (see
the read deduplication requirement), so an enrichment grain key with unmerged versions resolves to a
single latest row and never fans out the source. A query that is **not** read final joins the
enrichment's raw parts, so a grain key with unmerged versions MAY fan out the source row once per
version — exactly what the same join returns in ClickHouse without `FINAL`. For a user-facing read the
deduplication SHALL be rendered into this SQL: a final-read query SHALL carry ClickHouse's `FINAL`
modifier on its source table reference when the source is `upsert_by_key` (never on an `append` source's
plain `MergeTree`, which refuses it) and on every enrichment table it joins — inside the keyed join's
derived table when the join is keyed — and a query that is not read final SHALL carry it on none of
them. The key sub-query of a keyed join (`SELECT source.grain_key FROM source WHERE <key predicates>`)
SHALL NOT carry `FINAL`: the latest version of a key is one of its raw versions, so the raw key set is
a superset of the final one and keying still never changes which rows the statement returns, while
the sub-query does not pay a second merge. The internal pipeline reads that render through this
translator (enrichment scans, group fetch, Class-1 materialize, aggregate refresh and preview) SHALL
render no `FINAL` and keep deduplicating through the query-level `final = 1` execution setting, as the
read deduplication requirement states. Whether deduplication merges across partitions or stays inside
each one is decided by the read-deduplication requirement ("Deduplication SHALL stay inside a partition
wherever the catalog can vouch for it"); the 0..1 guarantee of a final read holds either way, because
an enrichment's partition column is immutable for a grain key, so no key ever has versions in two
partitions.
**The join SHALL be keyed by the source-only top-level conjuncts of the query's `filter`.** The filter's
top-level `and` SHALL be flattened, recursing into nested `and`s only; an `or`, a `not`, a comparison
and a subquery predicate are each one conjunct, never split further. A conjunct is **source-only** when
every field it references (the operands of a subquery predicate included, its body not) is a source
column. When at least one conjunct is source-only, the enrichment side of every referenced join SHALL
be emitted as a derived table — the grain key plus the enrichment columns the query references —
restricted to `enrichment.grain_key IN (SELECT source.grain_key FROM source WHERE <key predicates>)`
and aliased to the enrichment's own name, where `<key predicates>` are those source-only conjuncts
alone, so ClickHouse's right-side hash build covers the grain keys they select rather than the whole
enrichment table — except where "A partition-only key on an aligned enrichment is transferred, not keyed"
replaces the key sets with the transferred partition bounds, for a join to a partition-aligned enrichment
whose every key predicate is a bound on the source's partition column. The keying SHALL be statement-wide: a conjunct naming one enrichment's column SHALL
NOT prevent the source-only conjuncts beside it from keying that enrichment's join or any other. The
query's **whole** `filter` SHALL still be applied, unchanged, in the statement's own `WHERE` — except that a reference
to a masked field reads its masked value, and a conjunct that "A comparison on a masked field keeps the table's
pruning" rewrites takes that form, which selects the same rows; an
enrichment conjunct SHALL NOT be moved out of it, and SHALL be copied into a derived table only as
"Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table" allows. A query with no
`filter`, or whose `filter` has no source-only top-level conjunct (an enrichment-only filter, or an `or`
mixing source and enrichment columns), SHALL join the enrichment table unkeyed, unless that requirement
or "Scalar calls over a heavy text column are computed inside the enrichment's derived table" emits it as
a derived table. On the single-entity read path a late-materialized row-mode page SHALL instead join its
late enrichments for the page's keys, as "Projection-only enrichment columns are fetched for the returned page
only" defines them and states. The same rule SHALL apply to
the `include_total` count statement. Keying changes which rows the join build reads, never which rows
the statement returns, whatever shape the enrichment conjuncts take — including `IS NULL` and
`coalesce` over an enrichment column: the key predicates are a subset of the outer `WHERE`'s
conjuncts, so every source row the outer `WHERE` keeps passes them, its enrichment row (if it has one)
is in the derived table, and its `LEFT JOIN` result is the one the unkeyed join gives it. A projected enrichment column SHALL be returned under its
qualified flat name as the result-row key. Comparison operators SHALL map to SQL per the wire
contract: `eq`/`ne`/`lt`/`gt`/`le`/`ge` to direct comparisons, `co`/`nc` to **case-sensitive**
`LIKE`/`NOT LIKE` contains patterns, `ico`/`inc` to **case-insensitive** `LIKE`/`NOT LIKE`
contains patterns, `in` to `IN`, and `eq`/`ne` against a null literal to `IS NULL`/`IS
NOT NULL`. Aggregate functions SHALL translate to their SQL aggregates. Every entry of a non-empty
`select` SHALL be rendered with an explicit SQL alias carrying its resolved output name (see the
output-column-naming requirement) in both modes, so the executed statement itself declares the
result-row keys instead of relying on the database's own naming of an unaliased expression; an
omitted/empty row-mode `select` SHALL keep projecting the source's own **non-heavy** columns under
their own names (see "Wildcard projection excludes heavy columns").
Aggregate mode SHALL NOT require caller-supplied aliases and SHALL build `group_by`/`having`/`sort`
against the base fields together with the resolved select output names. Where a name matches both an
entity column and a select output, resolution SHALL follow PostgreSQL clause by clause: `sort` SHALL
resolve to the **select output**; `group_by` SHALL resolve to the **entity column**, falling back to the
select output for a name that is not a bound field (so a computed group key such as a `width_bucket`
output stays groupable); and `having` SHALL resolve to the entity column unless the caller supplied that
name as an explicit `as` alias. A `group_by` key that is both a bound entity column **and** the explicit
`as` alias of a select entry projecting a different expression SHALL be rejected as ambiguous
(`400 bad_request` naming the key): the two readings group differently and neither the DSL document nor
its SQL rendering — a bare `GROUP BY "<name>"`, which names the column on re-parse — can distinguish
them. An alias that repeats the name of the field it projects is not such a collision, since both
readings group by the same column. The offset pagination strategy SHALL be applied with a default limit of
100 and a maximum of 1000.

#### Scenario: Row projection renders against the entity table

- **WHEN** a validated row-mode query projects two fields from the source with a filter, sort, and
  offset page
- **THEN** the rendered SQL selects those columns from the entity table with the filter, `ORDER BY`,
  and `LIMIT` present, and no enrichment is joined because none is referenced

#### Scenario: Query referencing an enrichment column joins that enrichment

- **WHEN** a validated query references an enrichment column by its qualified flat name
  `<enrichment_table>.<column>`
- **THEN** the rendered SQL `LEFT JOIN`s that enrichment on `source.grain_key = enrichment.grain_key`
  and selects/filters the enrichment column through the join, rendering the qualified flat name as a
  single quoted alias

#### Scenario: Two enrichments exposing the same column name are queryable via their qualified names

- **WHEN** a validated query references two columns that share a bare name but belong to different
  enrichments, each named by its qualified flat name (`<enrichment_a>.<col>` and `<enrichment_b>.<col>`)
- **THEN** both enrichments are `LEFT JOIN`ed and both columns are projected as distinct result fields

#### Scenario: A source-only filter keys the enrichment join

- **WHEN** a validated query projects an enrichment column and its `filter` names source columns only
  (for example `trace_id = 'x'`), and the block is not late-materialized (see "Projection-only enrichment columns
  are fetched for the returned page only")
- **THEN** the rendered SQL `LEFT JOIN`s a derived table over the enrichment — its grain key and the
  referenced enrichment columns, restricted to `enrichment.grain_key IN (SELECT source.grain_key FROM
  source WHERE <filter>)` — aliased to the enrichment's name and joined on the same grain-key
  equality, and the statement's own `WHERE` still carries the filter

#### Scenario: A mixed and is keyed by its source-only conjuncts

- **WHEN** a validated query's `filter` is `request_time >= T AND trace_id = 'x' AND
  <enrichment>.<column> = 'v'` and the source and enrichment are partition-aligned on `request_time`
- **THEN** the enrichment is joined as a derived table restricted to `enrichment.grain_key IN (SELECT
  source.grain_key FROM source WHERE request_time >= T AND trace_id = 'x')` and to
  `enrichment.request_time IN (SELECT source.request_time FROM source WHERE request_time >= T AND
  trace_id = 'x')`
- **AND** neither key sub-query names the enrichment column
- **AND** the statement's own `WHERE` carries the whole filter, the enrichment conjunct included
- **AND** the `include_total` count statement is keyed by the same two conjuncts

#### Scenario: A filter on one enrichment keys another enrichment's join

- **WHEN** a validated query's `filter` is `request_time >= T AND <enrichment_a>.<column> = 'v'` and it
  projects a column of `<enrichment_b>`, and neither enrichment is partition-aligned with the source on
  `request_time`
- **THEN** both enrichments are joined as derived tables keyed by `request_time >= T` alone

#### Scenario: A filter naming an enrichment column joins the enrichment unkeyed

- **WHEN** a validated query's `filter` references enrichment columns only (it has no source-only
  top-level conjunct), or the query has no `filter`, and the block is not late-materialized
- **THEN** the rendered SQL `LEFT JOIN`s the enrichment table itself, with no key sub-query

#### Scenario: A mixed or is left unkeyed

- **WHEN** a validated query's `filter` is `trace_id = 'x' OR <enrichment>.<column> = 'v'`
- **THEN** the filter has no source-only top-level conjunct, and the rendered SQL `LEFT JOIN`s the
  enrichment table itself, with no key sub-query

#### Scenario: An enrichment IS NULL conjunct beside a time window is keyed and returns the same rows

- **GIVEN** source rows inside and outside a time window, some inside it with an enrichment row and some
  without one
- **WHEN** a validated query's `filter` is `request_time >= T AND <enrichment>.<column> IS NULL`
- **THEN** the enrichment join is keyed by `request_time >= T` (or, when the enrichment is partition-aligned
  with the source on `request_time`, bounded by that transferred window instead)
- **AND** the query returns exactly the rows the same statement returns with the enrichment joined
  unkeyed: every in-window source row whose enrichment row is missing or holds a null in that column,
  and no out-of-window row

#### Scenario: Contains operator maps to case-sensitive LIKE

- **WHEN** a query uses the `co` operator on a string field
- **THEN** the translator emits a case-sensitive `LIKE '%value%'` predicate that matches only
  exact-case occurrences

#### Scenario: Case-insensitive contains maps to case-insensitive LIKE

- **WHEN** a query uses the `ico` operator on a string field
- **THEN** the translator emits a case-insensitive `LIKE '%value%'` predicate

#### Scenario: Aggregate query groups by base field and select alias

- **WHEN** an aggregate-mode query groups by a field and selects an aliased aggregate
- **THEN** the translator builds GROUP BY against the field and resolves `having`/`sort` references
  against the base fields plus the select alias

#### Scenario: Unaliased aggregate select renders an explicit derived alias

- **WHEN** an aggregate-mode query selects `count()` with no `as`
- **THEN** the rendered SQL aliases the aggregate as `"count"`, and `group_by`/`having`/`sort` may
  reference `count`

#### Scenario: Derived output name does not shadow an entity column in having or group_by

- **WHEN** an entity owns a column named `count`, and an aggregate-mode query groups by that column,
  selects an unaliased `count()` aggregate, and filters `having` on `count`
- **THEN** both `group_by` and `having` resolve to the entity column, and the aggregate is still
  returned under its derived output name

#### Scenario: Computed select output stays groupable by name

- **WHEN** an aggregate-mode query selects a `width_bucket` expression aliased `bucket` and groups by
  `bucket`, which is not an entity column
- **THEN** `group_by` falls back to the select output and groups by that output column

#### Scenario: group_by key that is both a column and the alias of another expression is rejected

- **WHEN** an aggregate-mode query selects `date_bin(1, 'hour', request_time)` under the explicit alias
  `request_time` (the name of an entity column) and groups by `request_time`
- **THEN** the request is rejected with `400 bad_request` naming the ambiguous key, no query is
  executed, and the message points at renaming the alias to a name that is not a column of the entity

#### Scenario: Alias repeating its own field name stays groupable

- **WHEN** an aggregate-mode query selects `{field: event_kind, as: "event_kind"}` plus an aggregate and
  groups by `event_kind`
- **THEN** the query is accepted and groups by the entity column, because the alias and the column name
  the same grouping

#### Scenario: A final-read query renders FINAL on the source and every joined enrichment

- **WHEN** a validated query that is read final references columns of two enrichments, one joined keyed
  and one unkeyed
- **THEN** the rendered SQL carries `FINAL` after the source table reference, after the unkeyed
  enrichment table, and after the enrichment table inside the keyed join's derived table, and the key
  sub-query `SELECT source.grain_key FROM source WHERE <key predicates>` carries none

#### Scenario: A query not read final renders no FINAL

- **WHEN** a validated query that is not read final references an enrichment column
- **THEN** the rendered SQL carries `FINAL` on no table reference, and a grain key of the enrichment
  with two unmerged versions returns its source row twice

#### Scenario: A backslash in a string value cannot escape its literal

- **WHEN** a filter compares a string field with the value `x\' OR 1=1 --`, over the DSL or ad-hoc SQL
- **THEN** the value renders as one escaped literal and the query returns only rows whose field equals
  those exact characters, here none

#### Scenario: A backslash in a string value matches literally

- **WHEN** a filter compares a string field with the four-character value `a\nb`
- **THEN** it matches a row holding a backslash followed by `n`, never a row holding a newline

### Requirement: Output column naming when `as` is omitted

A select entry's `as` key SHALL be optional for every expression kind and in both query modes; no
query SHALL be rejected for omitting it, except that a query referencing a masked field is refused when a resolved
name, given or derived, equals a table it reads or begins with that name followed by `.` (see "A masked field reads as
absent at every reference"). When `as` is absent (or blank), the system SHALL derive the
output column name the way PostgreSQL derives an unnamed target-list name, restricted to the
expressions this DSL can express:

- a field expression SHALL yield the field's own name **verbatim**, including the dots of an
  enrichment column's qualified flat name (`deployment_ref.display_name` stays whole — the dot is part
  of the name, not a table qualifier);
- a function expression (scalar, aggregate, or ordered-set aggregate, with or without `distinct`)
  SHALL yield the lowercase catalog function name (`count`, `sum`, `date_bin`, `percentile_cont`);
- a value literal SHALL yield `?column?`, PostgreSQL's placeholder for a name it cannot derive;
- an array expression SHALL yield `array` (unreachable in a projection today, since array literals are
  only valid as an `in` right operand).

Because rows are name-keyed maps and result schemas are flat field lists, output names SHALL be unique
— unlike PostgreSQL, which tolerates duplicates. Uniqueness SHALL be established deterministically from
the select list's order in two passes: explicit `as` values SHALL be reserved first, in list order, an
exact repeat of an earlier explicit alias taking the first free `<name>_<n>` (n from 1); derived names
SHALL then be assigned in list order, each taking the first free candidate from `<base>`, `<base>_1`,
`<base>_2`, … An explicit alias SHALL therefore always appear under exactly the name the caller asked
for, and derived names SHALL move out of its way regardless of position in the list.

The resolved names SHALL be the response-row keys, the rendered SQL aliases, and the reference names
available to `group_by`, `sort`, and `having` — one resolution shared by execution, DSL→SQL rendering,
and SQL translation, so those three can never disagree.

#### Scenario: Unaliased aggregate takes the function name

- **WHEN** an aggregate-mode query groups by `model` and selects `{ "expr": { "type": "fn", "name": "count", "args": [] } }` with no `as`
- **THEN** the request succeeds and each row carries the keys `model` and `count`

#### Scenario: Unaliased scalar function takes the function name

- **WHEN** a row-mode query selects `{ "expr": { "type": "fn", "name": "lower", "args": [{ "type": "field", "name": "deployment" }] } }` with no `as`
- **THEN** the request succeeds and each row carries the key `lower`

#### Scenario: Colliding derived names are suffixed by position

- **WHEN** an aggregate-mode query selects two unaliased `count` entries (e.g. `count()` and `count(distinct model)`)
- **THEN** the request succeeds and the rows carry the keys `count` and `count_1`, in select-list order

#### Scenario: An explicit alias keeps its exact name against a colliding derived name

- **WHEN** a query selects an unaliased `count()` entry followed by an entry aliased `count`
- **THEN** the entry aliased `count` is returned under `count`, and the unaliased aggregate is returned
  under `count_1`

#### Scenario: Repeated explicit aliases are disambiguated

- **WHEN** a query selects two entries both aliased `total`
- **THEN** the first is returned under `total` and the second under `total_1`, rather than one silently
  overwriting the other in the row map

#### Scenario: Projected literal takes PostgreSQL's placeholder name

- **WHEN** a row-mode query projects a value literal with no `as`
- **THEN** the request succeeds and the row key is `?column?`

#### Scenario: Enrichment column keeps its qualified flat name

- **WHEN** a row-mode query selects the enrichment field `deployment_ref.display_name` with no `as`
- **THEN** the row key is the full qualified flat name `deployment_ref.display_name`

#### Scenario: Derived name is referenceable from group_by, sort, and having

- **WHEN** an aggregate-mode query selects an unaliased `count()` and orders by `count` descending, or
  filters `having` on `count`
- **THEN** the reference resolves to that select output and the request succeeds without any `as`
