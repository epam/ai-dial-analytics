# ADAS SQL reference

The SQL surface ADAS accepts over `POST /v1/queries/execute-sql`, the Arrow Flight SQL endpoint, the
validation-only `POST /v1/queries/validate-sql` and the translation-only
`POST /v1/queries/translate-sql`. All three share one front end: the statement is
parsed and validated with Apache Calcite against the same entity catalog `GET /v1/queries/entities`
publishes, lowered onto the structured query DSL, and executed through the governed pipeline behind
`POST /v1/queries/execute`. Nothing outside the subset below reaches ClickHouse: an unsupported
construct is rejected up front with an error naming it.

The dialect is standard, PostgreSQL-shaped `SELECT`. It is **not** ClickHouse SQL: ClickHouse-only
syntax and functions are refused. Every spelling you can write — functions, operators such as `+` and `||`,
and constructs such as `CASE` — is listed by `GET /v1/queries/sql-functions`, with its SQL signature, its
aliases, the DSL function it lowers onto and a runnable example. `GET /v1/queries/functions` is the DSL's
catalog: five of its names (`plus`, `minus`, `multiply`, `div`, `modulo`) are not callable in SQL, and some
of its signatures are written differently here.

**Set operations are the exception, deliberately.** Where `UNION`/`INTERSECT`/`EXCEPT` mean different
things to ClickHouse and to the standard, ADAS means ClickHouse's — so a set operation written for
ClickHouse returns the rows ClickHouse returns. The three differences are tabulated under
[Set operations](#set-operations); if you are used to standard SQL here, read that table before
assuming a bare `INTERSECT` or a trailing `ORDER BY` does what it does elsewhere.

The normative contract lives in `openspec/specs/sql-query-translation/spec.md`; this page is the
reader-friendly summary of it.

## Where SQL is accepted

| Surface | How | Result | Row governance |
|---|---|---|---|
| `POST /v1/queries/execute-sql` | `{"sql": "SELECT ..."}` | `{"rows": [...]}`, the same envelope as `/v1/queries/execute`; `totalCount` is never set | default `LIMIT` 100, maximum 1000 |
| `POST /v1/queries/execute-sql-csv` | `{"sql": "SELECT ..."}` | the same result as `execute-sql`, as an RFC 4180 CSV attachment (header row always present; see below) | same as `execute-sql` |
| Arrow Flight SQL (`FLIGHT_ENABLED=true`, port 32010) | `CommandStatementQuery` with the SQL text | typed Arrow record batches | `FLIGHT_MAX_ROWS` (default 10 000) caps explicit and implicit limits; a truncated page is never returned |
| `POST /v1/queries/validate-sql` | `{"sql": "SELECT ..."}` | `{valid, effective_limit, issues[], warnings[]}`, no execution — always 200 for a well-formed request | reported rather than applied: an over-maximum `LIMIT` is an issue |
| `POST /v1/queries/translate-sql` | `{"sql": "SELECT ..."}` | `{"query": <StructuredQuery>}`, no execution | default `LIMIT` 100, maximum 1000 (same builder pass as execution); composite statements (set operations, joins, derived tables, subqueries) return 400 because they have no DSL document |
| `POST /v1/queries/translate` | a StructuredQuery document | `{"sql": "SELECT ..."}` in this dialect | none |

Governance applies to the **outermost** statement only. A `LIMIT` above the maximum is rejected, never
clamped. A `LIMIT`/`OFFSET` inside a derived table, CTE or subquery runs exactly as written, and an inner
block with no `LIMIT` receives no default.

Every statement runs under the [query budget](configuration.md#query-execution-budget)
(`QUERY_BUDGET_TIMEOUT`, `QUERY_BUDGET_MAX_MEMORY`, `QUERY_BUDGET_SPILL_THRESHOLD`; exceeding it is
422 `query_budget_exceeded`), with ClickHouse `FINAL` deduplication and the caller's column
visibility applied in every block.

Security follows the per-endpoint privilege policy: in `oidc` mode the caller needs `TABLE_READ` on every
table the statement resolves, held globally or granted on that table; a `sensitive` column is invisible
without the `TABLE_SENSITIVE` clearance on the table that owns it and is reported as an unknown
column.

### CSV results

`POST /v1/queries/execute-sql-csv` (and `POST /v1/queries/execute-csv` for a StructuredQuery document)
returns exactly what its JSON counterpart returns, under the same limits and errors, as a
`text/csv; charset=UTF-8` attachment named `result.csv`. The file is UTF-8 without a BOM, with CRLF line
ends. A header row of the column names comes first, even when no row matches. In each cell:

- a null is an empty field and the empty string is `""`;
- arrays, objects and maps are compact JSON text;
- nothing is escaped for spreadsheets, so a cell starting with `=` is written as-is.

A DSL `totalCount` travels in the `X-Total-Count` header. Failures are the usual `ErrorView` JSON,
even when the request sends `Accept: text/csv`.

## Checking a statement before you run it

`POST /v1/queries/validate-sql` runs every check `execute-sql` runs except the ClickHouse round trip, over
the whole accepted surface — joins and `INFORMATION_SCHEMA` included — and reports a verdict instead of
rows. A well-formed request is always `200`; `valid` describes the statement. Only a malformed request (a
missing or blank `sql`) is a `400`.

```bash
curl -s localhost:5000/v1/queries/validate-sql -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT event_kind, count() AS rows FROM dial_usage_log GROUP BY event_kind"}'
```

```json
{
  "valid": false,
  "issues": [
    { "code": "reserved_word_alias",
      "message": "SQL parse error: Incorrect syntax near the keyword 'AS' at line 1, column 33. 'rows' at line 1, column 36 is a reserved word …",
      "line": 1, "column": 36,
      "remedy": "rename the identifier, or double-quote it" }
  ],
  "warnings": []
}
```

Each issue carries a stable `code` you can branch on, the verbatim diagnosis, a position where the
diagnosis has one, and a `remedy` where there is a concrete fix. A diagnosis ADAS does not recognise
comes back as `invalid_sql` with its message intact. `issues[0]` is the rejection `execute-sql` itself
would return; any further entries are additional unresolvable identifiers, reported together so several
misspellings need not be found one request at a time.

**`valid: true` means `execute-sql` will not reject the statement — not that execution will succeed.**
The query budget is enforced by ClickHouse as the statement runs, so a valid statement can still come
back `422 query_budget_exceeded`. `warnings` names the shapes that most often do: a `heavy` column read
with no filter on a timestamp field, a `GROUP BY` with no such filter, and the default `LIMIT` being
applied silently. Warnings are advisory heuristics and never affect `valid`; a composite statement gets
only the limit note, because its blocks reference relation columns rather than one entity's fields.

## Statement shape

- Exactly one `SELECT` statement per request. A single trailing `;` is ignored; two statements, or two
  trailing separators, are rejected. Nothing but `SELECT` is accepted.
- An optional non-recursive `WITH` clause may precede the `SELECT`.
- Clause order: `SELECT [DISTINCT] ... FROM ... [JOIN ...] [WHERE ...] [GROUP BY ...] [HAVING ...]
  [ORDER BY ...] [LIMIT n] [OFFSET m]`.
- Identifiers match case-insensitively, quoted or not. Enrichment columns are addressable only as
  double-quoted flat identifiers: `"deployment_ref.display_name"`, or `a."deployment_ref.display_name"`
  under a join alias.
- Entities resolve bare (`FROM dial_usage_log`) or qualified by the fixed data schema
  (`FROM adas.dial_usage_log`, `FROM "adas"."dial_usage_log"`). Any other qualifier fails validation.
- An unquoted alias that is a reserved word (`AS rows`) fails to parse; the error names the word and
  suggests quoting or renaming it. Parse errors report the offending token and position, not the
  grammar.
- Runtime parameters (`?`) are not accepted.

## Projection

- Columns, expressions and function calls, each with an optional `AS alias`.
- **Unnamed expressions** take PostgreSQL-derived names, never `EXPR$n`: a function call takes its
  lowercase name as written (`count(*)` is `count`, `char_length(x)` is `char_length`), an ordered-set
  aggregate takes the aggregate's name, anything else is `?column?`. Duplicates are suffixed `_1`,
  `_2`, ... in select order. Derived names are real aliases: `ORDER BY count` works.
- `SELECT *` and `alias.*` expand, before validation, to the entity's **non-heavy source columns** in
  catalog order. Heavy columns and enrichment columns are never part of a star; name them explicitly
  to get them — the seeded raw payloads are both, and are named
  `"dial_usage_log_payload.request_body"` and so on. Over a join, `*` is the concatenation of each
  operand's expansion in operand order, with duplicate names made unique (`trace_id`, `trace_id_1`).
  A star whose expansion would be empty is rejected. `COUNT(*)` is unaffected.
- `SELECT DISTINCT` is accepted in row mode. It is refused over a bare `array`, `object` or `map`
  column (see column-type rules) but allowed over a scalar extracted from one.
- A bare comparison in the select list (`SELECT a > b`) is rejected; wrap it in `CASE` or use a
  boolean function.

## Expressions

### Literals and types

| Literal | Notes |
|---|---|
| `'text'` | character string; compared against a `timestamp` column it coerces to a timestamp when spelled `'2026-08-20'`, `'2026-08-20 00:00:00'` or `'2026-08-20T00:00:00Z'`, otherwise rejected |
| `123`, `-7`, `1.5` | integer, long, decimal |
| `TRUE`, `FALSE` | boolean |
| `DATE '2026-08-20'` | date; widened to midnight UTC when compared to a timestamp |
| `TIMESTAMP '2026-07-01 00:00:00'` | timestamp, encoded as epoch milliseconds |
| `NULL` | null literal, allowed in `IS [NOT] NULL` and as a `CASE` branch |
| character literals of different lengths | keep their own length (`'small'` never becomes `'small '`) |

### The clock and relative windows

`now()` is the current instant, in UTC at millisecond precision, read from the ClickHouse server's clock
at execution. `CURRENT_TIMESTAMP` and `LOCALTIMESTAMP` are accepted spellings of the same function and
translate to the same query. `CURRENT_DATE` is not accepted — write `date_trunc('day', now())`.

Each call is one value across every row of the statement, but separate calls are evaluated separately,
exactly as in ClickHouse, and may land on either side of a millisecond tick. So a window written with
two calls — `request_time >= now() - INTERVAL 1 HOUR AND request_time < now()` — may see bounds a
millisecond apart, as the same statement does in ClickHouse; if both bounds must come from one instant,
pass that instant as a literal.

Interval arithmetic offsets a timestamp. These all translate to the same query:

```sql
WHERE request_time >= now() - INTERVAL 2 HOUR        -- ClickHouse spelling
WHERE request_time >= now() - INTERVAL '2' HOUR      -- standard spelling
WHERE request_time >= date_sub('hour', 2, now())     -- function spelling
WHERE request_time >= date_trunc('day', now())       -- today so far
```

A statement written this way means "the last two hours" at every later run, rather than the two instants
it happened to be written with — which is the point of saving one.

- Units are `second`, `minute`, `hour`, `day`, `month`, `year`. `WEEK` and `QUARTER` may be written and
  normalize to `7 DAY` and `3 MONTH` — exact identities for an offset, so `INTERVAL 1 WEEK` reads back
  from `/v1/queries/translate-sql` as `date_sub('day', 7, …)`. That is the same query, not a defect.
- `month` and `year` are calendar steps in UTC: one month before 31 March is 28 (or 29) February, not a
  fixed number of days. `second` through `day` are fixed-width.
- The amount must be a whole, non-zero number of its unit. `INTERVAL 2.5 HOUR` and `INTERVAL 0 HOUR` are
  rejected; write `INTERVAL 150 MINUTE` for the first.
- The offset must be constant: `INTERVAL <column> HOUR` is rejected, because the DSL declares a unit and
  amount as literals so they are checked before the statement runs.
- A multi-field qualifier (`INTERVAL '1-2' YEAR TO MONTH`) is rejected — it names two offsets at once.
- An `INTERVAL` is not a value of its own: it is only an operand of `+`/`-` over a timestamp, so
  `SELECT INTERVAL 2 HOUR` is rejected.
- Subtracting one timestamp from another is not supported, in either spelling. `(ts1 - ts2) HOUR` is
  rejected rather than answered in seconds.
- Absolute bounds are still written as literals (`TIMESTAMP '2026-07-01 00:00:00'`), and the two mix
  freely.

There is no `today()` or `yesterday()`: they are `date_trunc('day', now())` and
`date_sub('day', 1, date_trunc('day', now()))`.

**`now()` is refused in a pipeline declaration** — a `filter`, a `ready_when.signal`, a Class-1
expression, an aggregate `filter` or measure `where` — with `422 pipeline_validation_failed`. A stored
declaration is re-run on a schedule and has to be reproducible. `date_add`/`date_sub`/`date_trunc` over
a **column** are accepted there; it is the clock that is refused.

`CAST` is accepted only where the validator would insert an identity or widening cast within the same
type family (for example an integer literal against a `long` column). Any explicit narrowing or
cross-family `CAST` is rejected. A `string` literal compared with a `uuid` column is retyped to `uuid`.

### Arithmetic

`+`, `-`, `*`, `/`, `%` and unary minus over numeric operands, with standard precedence and
parentheses, in the select list, `WHERE`, `HAVING` and `ON`, over columns and over aggregate results
alike (`sum(a) / sum(b)`). `MOD(a, b)` is a second spelling of `%`.

- `/` always yields a fractional result; an integer-over-integer ratio is not truncated.
- `/` and `%` yield `NULL` for a zero or null right operand, never an engine error. `%` keeps the sign
  of the left operand.
- The DSL catalog names `plus`, `minus`, `multiply`, `div`, `modulo` are **not** callable as SQL
  functions; write the operator.
- `round(value[, digits])`, `floor(value)`, `ceil(value)`, `abs(value)` are ordinary function calls.
  `floor(ts TO hour)` is not accepted; use `date_trunc`.

### CASE

```sql
CASE WHEN <predicate> THEN <expr> [WHEN ... THEN ...] [ELSE <expr>] END   -- searched
CASE <expr> WHEN <value> THEN <expr> [...] [ELSE <expr>] END              -- simple
```

- Accepted wherever an expression is: select list, `WHERE`, `HAVING`, `GROUP BY` (through its alias),
  aggregate arguments (`sum(CASE WHEN ... THEN x ELSE 0 END)`), `ORDER BY` through its alias.
- A `WHEN` predicate is anything the filter grammar accepts, comparisons included.
- `THEN`/`ELSE` branches must unify: numeric types widen against each other; any other mismatch
  (`THEN 'yes' ELSE 1`) is a validation error, never a silent coercion to text. A branch may be
  `NULL` as long as another branch has a type. An omitted `ELSE` yields `NULL`.
- `COALESCE(a, b, ...)` and `NULLIF(a, b)` are accepted; they lower onto `CASE`.
- A window function may appear inside a select-list `CASE`.

### Lambdas

```sql
<param> -> <expression>
```

A lambda is the per-element body of a higher-order array call, and it is legal **nowhere else** —
not in a select list, not as an ordinary function argument. It is written in ClickHouse's own operand
order, the lambda first and the array second:

```sql
SELECT count(*) FROM dial_usage_log
WHERE arrayExists(x -> starts_with(x, 'coder-'), "usage_client_identity.user_roles")
```

- Exactly **one** parameter. ClickHouse's multi-parameter forms (`arrayMap((x, y) -> ..., a, b)`) are
  not accepted, since every function here takes one array.
- The parameter name must match `[a-z_][a-z0-9_]{0,63}`. It is the one thing you write that reaches
  ClickHouse as a bare, unquoted identifier — the engine accepts nothing else in that position — so
  anything outside that spelling is a `400` and no statement is rendered. `__lambdaParam` is reserved.
- The parameter is typed as the **element type of the array**, so a text function over the parameter
  of a `string` array resolves and the same call over a numeric array is a `400`.
- The body may also read **columns of the same row**: `arrayExists(x -> starts_with(x, deployment),
  execution_path)` compares each element against that row's `deployment`. A captured column is an
  ordinary column read — it counts towards the columns the statement reads, and one you may not see
  is an unknown column inside a body exactly as it is in a projection.
- A parameter **shadows** a column of the same name, innermost binding winning. Inside
  `arrayExists(deployment -> ..., execution_path)` the identifier `deployment` is the parameter, not
  the column; rename the parameter when you want the row's value.
- An **aggregate** or a **window** call inside a body is rejected naming the call — a body is
  evaluated per element of one row's array. An aggregate may of course produce the array itself.
- Lambdas nest, and each body sees both parameters.
- A higher-order call does not change a statement's row count; `ARRAY_JOIN` remains the only
  construct that does.

### Functions

Function names are case-insensitive. Every name below is also the DSL wire name, so a statement
rendered from a DSL document reads back unchanged. Functions not listed are rejected, including
ClickHouse-native names. `GET /v1/queries/sql-functions` serves this list in machine-readable form,
each entry with a runnable example.

**Scalar**

| Function | Returns | Notes |
|---|---|---|
| `lower(text)`, `upper(text)`, `trim(text)` | string | |
| `length(text)` | integer | `char_length` and `character_length` are accepted synonyms |
| `not_empty(text)` | boolean | true when neither null nor empty |
| `starts_with(text, prefix)`, `contains(text, substring)` | boolean | case-sensitive; false, never null, on a null operand |
| `match(haystack, pattern)` | boolean | ClickHouse's RE2 regular-expression match. Both operands are ordinary text expressions, so the pattern need not be a literal; a pattern RE2 cannot compile is not detected here and fails in the engine, coming back through the usual ClickHouse-failure response |
| `equals(a, b)` | boolean | expression-position equality |
| `if(condition, then, else)` | type of the branches | condition must be a boolean column or a boolean function call, not a comparison; branches unify like `CASE` |
| `abs(v)`, `round(v[, digits])`, `floor(v)`, `ceil(v)` | numeric | `digits` is an integer literal, may be negative |
| `width_bucket(operand, low, high, count)` | integer | histogram bucket 1..count; 0 below `low`, `count + 1` at or above `high` |
| `date_bin(amount, unit, ts)` | timestamp | `amount` positive integer literal; `unit` in `second`, `minute`, `hour`, `day`, `week`; aligned to the epoch |
| `date_trunc(unit, ts)` | timestamp | `unit` in `second`, `minute`, `hour`, `day`, `week`, `month`, `quarter`, `year` |
| `now()` | timestamp | the current instant, UTC, millisecond precision, from the ClickHouse server's clock; one value per call across all rows, two calls evaluated separately. Also spelled `CURRENT_TIMESTAMP` / `LOCALTIMESTAMP`. Refused in a pipeline declaration — see [The clock and relative windows](#the-clock-and-relative-windows) |
| `date_add(unit, amount, ts)`, `date_sub(unit, amount, ts)` | timestamp | `unit` in `second`, `minute`, `hour`, `day`, `month`, `year`; `amount` a positive integer literal — direction is carried by the name. Also spelled `ts ± INTERVAL amount unit`, which is what a rendered statement carries |
| `json_extract_string(json, key[, key...])` | string | literal path; empty when absent |
| `json_extract_long(json, key...)`, `json_extract_double(json, key...)` | long / numeric | 0 when absent |
| `json_extract_bool(json, key...)` | boolean | false when absent |
| `json_extract_string_ci(json, key)` | string | exactly one top-level key, matched ignoring case |
| `json_extract_array(json, key...)` | array of string | result is projection-only, except as argument 0 of an array-operand function |
| `map_value(map, key)` | string | `key` is a single string literal, matched as one flat key |
| `map_contains(map, key)` | boolean | `key` is a single string literal |
| `map_keys(map)` | array of string | result is projection-only, except as argument 0 of an array-operand function |
| `split_string(text, delimiter)` | array of string | `delimiter` is a string literal; result is projection-only, except as argument 0 of an array-operand function |
| `array_has(array, value)` | boolean | |
| `array_has_any(array, [v, ...])`, `array_has_all(array, [v, ...])` | boolean | non-empty value list |
| `array_join(array)` | element type of the operand | expands the array into rows: one output row per element, and none at all for an empty array. The only function that changes how many rows a statement returns |
| `array_length(array)` | integer | 0 for an empty or null array |
| `array_slice(array, offset, length)` | array of the operand's element type | bounds are integer literals; `offset` is 1-based and non-zero (negative counts from the end), `length` positive. A slice past the end returns what exists; an offset past the end returns an empty array. Result is projection-only, except as argument 0 of an array-operand function |
| `array_to_string(array, delimiter)` | string | joins the elements, each rendered as `to_string` renders it; `''` for an empty array, never null. A null element keeps its position as an empty slot (`['a', null, 'b']` → `a,,b`), unlike ClickHouse's `arrayStringConcat` and PostgreSQL's `array_to_string`, which drop it. `delimiter` is a string literal and may be empty |
| `arrayExists(lambda, array)` | boolean | true when any element satisfies the lambda; `false` for an empty array. Spelled `arrayExists(x -> starts_with(x, 'coder-'), roles)` — **the lambda first, the array second**, as in ClickHouse |
| `arrayAll(lambda, array)` | boolean | true when every element satisfies it, and `true` for an empty array |
| `arrayCount(lambda, array)` | integer | how many elements satisfy it; `0` for an empty array |
| `arrayFilter(lambda, array)` | array of the operand's element type | the matching elements, empty for an empty array; result is projection-only, except in an array-operand position |
| `arrayMap(lambda, array)` | array of the body's type | the body applied to each element, empty for an empty array; result is projection-only, except in an array-operand position |
| `concat(value, value[, value...])` | string | two or more operands of **any** scalar type, each rendered as `to_string` renders it; never null — a null operand contributes `''` (PostgreSQL `concat()` semantics). `a \|\| b` is a second spelling of it |
| `to_string(value)` | string | any scalar as text; **null stays null** (cast semantics — it is `concat` that absorbs a null), a nullable boolean included: a null `boolean` renders as null, never as `false`. A decimal's trailing zeros are trimmed, a boolean prints `true`/`false`, a timestamp `YYYY-MM-DD hh:mm:ss.fff` in UTC, a date `YYYY-MM-DD`, an enum its label. A `CAST(x AS VARCHAR)` you write is a second spelling of it wherever `x` is **not** already text; over a column this surface types as text (`string`, `uuid`, `enum`, `object`, `map`) the cast is the no-op it has always been and the column passes through |

The **array-operand functions** are the ones that take an array, and each has **one** position where an
`array` column or an array-returning call may stand — no other argument of any function accepts one:

| Function | The array's position |
|---|---|
| `array_has`, `array_has_any`, `array_has_all`, `array_length`, `array_join`, `array_to_string`, `array_slice` | the **first** argument |
| `arrayExists`, `arrayAll`, `arrayCount`, `arrayFilter`, `arrayMap` | the **second** argument — the first is the lambda, as in ClickHouse |
| `groupUniqArrayArray`, `groupArrayArray` | their only argument |
| `arg_max` | the **`value`** argument only; `order` stays scalar |

That is what makes `array_join(split_string(tags, ','))`,
`array_to_string(array_slice(group_uniq_array(model), 1, 6), ', ')` and
`arrayExists(x -> starts_with(x, 'coder-'), groupUniqArrayArray("usage_client_identity.user_roles"))`
expressible. The three aggregate positions admit an array *value* only: an aggregate inside one of them
(`groupUniqArrayArray(group_uniq_array(x))`, `arg_max(group_uniq_array(x), ts)`) is a nested aggregate
and is rejected, while an array-valued *scalar* call (`arrayFilter`, `map_keys`, `split_string`,
`json_extract_array`, `array_slice`) is accepted.

`array_to_string` is how an array becomes readable: its result is ordinary text, so unlike the array it
renders it can be grouped by, sorted on and compared. `array_slice` is the only array-operand function
whose own result is an array, so it stays projection-only and composes inside the others.

**Text out of non-text.** `to_string` and `concat` accept operands of any scalar type — they render
rather than inspect — which is also the way to reach the inspecting text functions over an `enum`
column (`starts_with(to_string(status), 'run')`). Two inbound spellings lower onto them: the `||`
operator becomes `concat` (so `'depth ' || n` is accepted even though standard `||` is string-only), and
a `CAST(x AS VARCHAR)` you write becomes `to_string` — over an operand that needs rendering. A cast the
**validator** injects to coerce a non-text operand does not: `not_empty(total_tokens)` is still a 400
naming the operand's type, and a caller who means it writes the cast or the call explicitly. A declared
length (`VARCHAR(50)`) is ignored, since the store has no bounded string type.

A cast of something this surface **already types as text** stays the no-op it has always been: the column
passes through untouched, in a projection, a `WHERE` and a `GROUP BY` alike, so a generated
`CAST(project_id AS VARCHAR) = 'p1'` still compares the column itself. One consequence worth knowing: an
`enum` is text here, so the cast spelling is *not* a way to reach the inspecting functions over one —
`starts_with(CAST(status AS VARCHAR), 'run')` is refused just as `starts_with(status, 'run')` is, and
`starts_with(to_string(status), 'run')` is the form that works.

`array_join` is the row-expanding one: each source row yields one row per element of its array, a row
whose array is empty yields none, and the produced value is an ordinary scalar of the array's element
type carrying none of an array's restrictions. It is accepted wherever a scalar call is — the select
list, `WHERE`, `HAVING`, inside an aggregate or a window — and its result is grouped or sorted through
the alias of the select item carrying it (`SELECT array_join(execution_path) AS step, count(*) ... GROUP BY step`).
Two select items expanding different arrays produce the cartesian product of their elements; two
expanding the same expression advance together over one expansion. There is no outer variant.

One engine behaviour to know about: an `ORDER BY` whose key does **not** depend on the expansion,
combined with a `LIMIT`, bounds **source** rows rather than expanded ones — ClickHouse sorts and limits
before it expands. `SELECT array_join(execution_path) AS step FROM t ORDER BY length(trace_id) LIMIT 5`
therefore expands only the five rows with the shortest `trace_id`, and returns nothing at all if each of
those holds an empty array, where SQL as written describes five expanded rows. A `LIMIT` with no
`ORDER BY` bounds expanded rows as expected. Order by the expansion's own alias (`ORDER BY step`), or
apply the limit outside a subquery that expands, when the bound must count expanded rows.

**Aggregate**

| Function | Returns | `DISTINCT` | Notes |
|---|---|---|---|
| `count(*)`, `count(expr)` | long | yes | |
| `sum(expr)`, `avg(expr)` | numeric | yes | |
| `min(expr)`, `max(expr)` | operand type | no | |
| `arg_max(value, order)` | type of `value` | no | value of the row with the greatest non-null `order`. `value` may be an `array` — "the roles as of this user's last request" — and the result is then an array, projection-only in its turn; `order` stays scalar, and an aggregate is not a legal `value` |
| `group_uniq_array(expr)` | array | no | sorted distinct non-null values; result is projection-only. Its operand is scalar — over an array column, the answer would be a set of arrays, which this surface has no type for, so write `groupUniqArrayArray` instead |
| `groupUniqArrayArray(array)` | array of the operand's element type | no | the group's arrays **flattened and deduplicated** into one. What makes a test over the values a group held anywhere in the window expressible in `HAVING`, with the group's own measures still computed over every row. Empty, never null, for a group whose arrays are all empty; result is projection-only |
| `groupArrayArray(array)` | array of the operand's element type | no | the same flattening, keeping duplicates and each row's element order; the order *between* rows is unspecified |
| `percentile_cont(f) WITHIN GROUP (ORDER BY expr)` | numeric | no | `f` is a numeric literal in [0, 1] |
| `percentile_disc(f) WITHIN GROUP (ORDER BY expr)` | operand type | no | |

`FILTER (WHERE ...)` on an aggregate is not accepted. A `DISTINCT` quantifier on a function marked
"no" is rejected rather than ignored.

The two flattening aggregates are what put an array test **after** aggregation rather than in the
`WHERE`, which is a different question and a different answer:

```sql
SELECT "usage_client_identity.user_ref" AS user_ref, sum(deployment_price) AS total_cost
FROM dial_usage_log
WHERE request_time >= date_sub('day', 30, now())
GROUP BY user_ref
HAVING arrayExists(r -> starts_with(r, 'coder-'), groupUniqArrayArray("usage_client_identity.user_roles"))
```

`total_cost` here is the user's whole-window cost, not only the rows carrying the role. Each of the
three array-valued aggregates holds an accumulator per group whose size grows with the group's
elements, so an unfiltered `GROUP BY` over one is worth bounding by time — the `unbounded_group_by`
advisory from `POST /v1/queries/validate` covers the scan side.

**Window** (only under `OVER`): `row_number()`, `rank()`, `dense_rank()` return long; `count`, `sum`,
`avg`, `min`, `max` may also be windowed. See [Window functions](#window-functions).

## Predicates

Usable in `WHERE`, `HAVING`, `ON` and `CASE WHEN`, combined with `AND`, `OR`, `NOT` and parentheses.

| Predicate | Notes |
|---|---|
| `a = b`, `a <> b`, `a != b`, `a < b`, `a > b`, `a <= b`, `a >= b` | operands are columns, literals, expressions or a scalar subquery |
| `x IN (v1, v2, ...)`, `x NOT IN (...)` | literal list |
| `x [NOT] IN (SELECT ...)`, `(x, y) [NOT] IN (SELECT ...)` | uncorrelated subquery with a matching column count |
| `[NOT] EXISTS (SELECT ...)` | uncorrelated subquery; its select list is ignored |
| `x BETWEEN lo AND hi` | expanded to `x >= lo AND x <= hi` |
| `x IS NULL`, `x IS NOT NULL` | not on `array`, `object` or `map` columns |
| `x LIKE '%text%'`, `x NOT LIKE '%text%'` | case-sensitive contains only |
| `x ILIKE '%text%'`, `x NOT ILIKE '%text%'` | case-insensitive contains only |
| `boolean_column` | a bare boolean column is a predicate (`WHERE success`) |
| `boolean_function(...)` | `not_empty`, `starts_with`, `contains`, `match`, `equals`, `array_has*`, `arrayExists`, `arrayAll`, `map_contains`, `json_extract_bool` used bare (`WHERE not_empty(chat_id)`) |

`LIKE` patterns are restricted to exactly `'%text%'` with no `%` or `_` inside `text`, no `ESCAPE`, and
a literal pattern. Prefix and suffix patterns (`'gpt%'`) are rejected. A non-boolean bare expression
(`WHERE lower(x)`) is rejected; quantified comparisons (`= SOME`, `> ALL`) are rejected.

## Grouping and aggregation

- `GROUP BY` keys are columns, select aliases, or computed expressions that also appear in the select
  list under an alias that is not an entity column name. Grouping by an alias whose name is also a
  column of the entity, or aliasing a column's name onto a different expression while grouping by that
  column, is rejected as ambiguous.
- Every non-aggregated select item must be grouped; ADAS does not wrap it in an implicit
  arbitrary-value aggregate.
- `HAVING` accepts the same predicates as `WHERE` over grouped keys and aggregates, arithmetic over
  aggregates included (`HAVING sum(a) / sum(b) > 0.5`).
- A global aggregate (`SELECT count(*) FROM t`) returns one row.
- `ROLLUP`, `CUBE` and `GROUPING SETS` are not accepted. Aggregates over aggregates are written with a
  derived table.

## ORDER BY, LIMIT, OFFSET

- `ORDER BY` keys are columns, select aliases or derived output names, each with optional `ASC` /
  `DESC` and `NULLS FIRST` / `NULLS LAST`. Defaults are `ASC NULLS LAST` and `DESC NULLS FIRST`.
- A bare expression in `ORDER BY` is **accepted** even when the select list does not carry it
  (`SELECT deployment FROM ... GROUP BY deployment ORDER BY count(*) DESC LIMIT 8`,
  `SELECT event_id FROM ... ORDER BY length(event_kind)`). The ordered, bounded computation becomes an
  inner block and the projection applies over it, so the `LIMIT` you wrote still bounds the rows
  returned and the rows come back in the order you asked for (the ordering is re-applied over the inner
  block, not left inside it). Such a statement executes but has **no structured-query document**, so
  `POST /v1/queries/translate-sql` reports that rather than a DSL query; aliasing the expression in the
  select list and ordering by the alias keeps the statement translatable.
  (Superseded: this used to be rejected with "an ORDER BY expression must be a column or appear in the
  SELECT list", which remains the message only when the expression resolves against neither the
  projection nor a source column.)
- A select alias that is named after a **column** while projecting a different expression cannot also be
  ordered by that column (`SELECT length(event_kind) AS request_time ... ORDER BY t.request_time`): the
  name would resolve to the alias, so the statement is rejected. Alias the expression to a name that is
  not a column of the source — the same rule `GROUP BY` follows.
- An `ORDER BY` inside a derived table, a CTE body or a set-operation branch is executed whether or not
  a `LIMIT`/`OFFSET` goes with it (`SELECT c FROM (SELECT k, sum(p) AS c FROM t GROUP BY k ORDER BY c
  DESC) x` comes back in descending `c` order). Standard SQL guarantees no order there, since the clause
  sorts an intermediate result; ClickHouse honours it, and so does this surface.
- `LIMIT n [OFFSET m]`. On REST the outer `LIMIT` defaults to 100 and may not exceed 1000; on Flight the
  cap is `FLIGHT_MAX_ROWS`. Inner blocks are not governed.
- `array`, `object` and `map` columns cannot be sort keys.

## Joins

```sql
SELECT l.trace_id, r.rate
FROM dial_usage_log l
JOIN usage_rates r ON l.trace_id = r.trace_id
```

- Kinds: `[INNER] JOIN`, `LEFT [OUTER] JOIN`, `RIGHT [OUTER] JOIN`, `FULL [OUTER] JOIN`, `CROSS JOIN`,
  and a comma-separated `FROM` list. Left-deep chains of any length; the same entity may appear twice
  under distinct aliases (self-join).
- Operands: a queryable entity (optionally aliased), a derived table, or a CTE reference. Each is
  validated against the catalog like a single-entity statement, including sensitive-column masking and
  per-operand enrichment columns.
- `ON` accepts any predicate of the subset, but for every kind except `CROSS JOIN` it must contain at
  least one **equality between an expression of each side**. `USING (col, ...)` is the equivalent
  equality conjunction. A join with no equality (`ON a.x > b.y`) is rejected; use `CROSS JOIN` with a
  `WHERE` clause for an unconditional pairing.
- Unqualified column names that exist in more than one operand are rejected as ambiguous.
- Outer joins yield **`NULL`** for every column of an unmatched side regardless of the column's physical
  nullability (ADAS executes composite statements with `join_use_nulls = 1`). This is the one place a
  composite statement differs from a single-entity one: a non-nullable enrichment column with no
  enrichment row reads as `NULL` inside a join and as its type default in a plain statement.
- Each operand is rendered as a derived table projecting only the columns the statement references, so
  a join never reads heavy columns it does not name.
- `WHERE`, `GROUP BY`, `HAVING`, `DISTINCT`, `ORDER BY`, `LIMIT` and the column-type rules apply to the
  joined result exactly as to one entity.
- Joins have no DSL equivalent: `translate-sql` answers 400 for them. Use `validate-sql` to check a join
  before running it — it validates the whole `execute-sql` surface, composite statements included.

## Common table expressions and derived tables

```sql
WITH chats AS (SELECT project_id, total_tokens FROM dial_usage_log WHERE event_kind = 'chat')
SELECT project_id, sum(total_tokens) AS t FROM chats GROUP BY project_id
```

- `WITH name AS (SELECT ...), ...` declares non-recursive CTEs; a CTE may reference one
  declared before it. Each reference is inlined as a derived table; a CTE used twice is evaluated twice.
  A CTE whose body flattens into the outer block becomes one flat statement.
- `WITH RECURSIVE` is rejected. A CTE or subquery body may be a set operation — see
  [Set operations](#set-operations).
- A `FROM` item may be a parenthesised `SELECT` of any accepted shape, aliased or not (an unaliased
  derived table gets a generated alias). The inner block may carry `WHERE`, `GROUP BY`/`HAVING`,
  `DISTINCT`, `ORDER BY`, `LIMIT`/`OFFSET` and window functions; the outer block may filter, aggregate,
  sort and page over its columns. Nesting depth is not limited.
- One shape the parser cannot read: a `FROM` item or CTE body may not **both** open with a `(` of its own
  and carry a trailing `ORDER BY`/`LIMIT`/`OFFSET`/`FETCH` — `FROM ((SELECT a FROM t) ORDER BY a)` is a
  parse error, `FROM (SELECT a FROM t ORDER BY a)` is not. It is hit most often with a set operation whose
  first branch is parenthesised; see
  [A nested block cannot open with `(`](#a-nested-block-cannot-open-with--and-also-carry-a-trailing-clause).
- This is how the following are expressed: a filter above `ORDER BY`/`LIMIT`, an aggregate over an
  aggregate, a filter on a window result (top-N per group), `DISTINCT` combined with grouping.
- A derived table's columns keep the types the inner block reports, so the type-keyed carve-outs
  (`map` accessors, `object` document comparisons, `enum` affinity) still hold across the boundary.

## Subqueries

| Form | Position |
|---|---|
| `x [NOT] IN (SELECT col FROM ...)` | `WHERE`, `HAVING` |
| `(x, y) [NOT] IN (SELECT a, b FROM ...)` | same, with a matching column count |
| `[NOT] EXISTS (SELECT ... FROM ...)` | same; `SELECT *` inside `EXISTS` is reduced to a literal |
| `(SELECT agg(col) FROM ...)` scalar subquery | as a comparison operand or as a select item; **not** as a function argument |

- Subqueries must be **uncorrelated**: a reference to a column of an enclosing block is rejected with a
  message suggesting a join.
- A subquery may itself join or nest. No default limit applies inside it.
- A scalar subquery that returns more than one row fails at execution with 400.
- `IN`/`NOT IN` over null values follow the engine's semantics.

## Set operations

```sql
SELECT model FROM dial_usage_log
UNION ALL
SELECT model FROM rate_analytics
```

| Form | Meaning |
|---|---|
| `UNION ALL` | concatenate both branches |
| `UNION DISTINCT` | concatenate, then deduplicate |
| `INTERSECT ALL`, `INTERSECT` | rows in both, keeping the lower multiplicity |
| `INTERSECT DISTINCT` | rows in both, once each |
| `EXCEPT ALL`, `EXCEPT` | left rows minus the right's multiplicity |
| `EXCEPT DISTINCT` | left rows not in the right, once each |

A branch is any accepted query block: a single entity with `WHERE`/`GROUP BY`/`HAVING`/`DISTINCT`, a
join, a derived table, a CTE reference, a parenthesised block with its own `ORDER BY`/`LIMIT`, or another
set operation. A chain may have any number of branches. A set operation is accepted wherever a query
block is — at the top level, as a CTE body, as a `FROM` item, and as an `IN`/`EXISTS`/scalar-subquery
operand.

`INTERSECT` binds tighter than `UNION` and `EXCEPT`, which bind equally, left to right. Parenthesise a
branch to override that.

Branches are matched **by position**, must project the same number of columns, and take their result
names from the **first** branch. Corresponding column types must unify; where they do not, the statement
is a 400 naming the column and the two branch types — even in a case ClickHouse itself would have found a
common type for. Mixing text with a number is the common one: `SELECT event_kind AS v … UNION ALL SELECT
total_tokens AS v …` is rejected rather than silently read as text in both branches.

### Where this follows ClickHouse rather than the SQL standard

These three are the reason a set operation written for ClickHouse returns the same rows here. In each,
the standard (and PostgreSQL) says something else, so a statement copied from a standard-SQL reference
may not mean what it looks like.

| Written | Here, as in ClickHouse | Standard SQL would say |
|---|---|---|
| `INTERSECT` with no keyword | `INTERSECT ALL` | `INTERSECT DISTINCT` |
| `EXCEPT` with no keyword | `EXCEPT ALL` | `EXCEPT DISTINCT` |
| `A UNION ALL B ORDER BY x LIMIT n` | orders and truncates **B alone** | orders and truncates the combined result |

The trailing clause belongs to the chain's **final `SELECT`**, whatever the precedence: in
`A UNION ALL B INTERSECT ALL C ORDER BY x LIMIT n` it orders and truncates `C`, and only then is the
intersection with `B` taken. A `WITH` in front changes nothing — the clause still binds to the last
branch, not to the combined result. A trailing `ORDER BY` with no `LIMIT` beside it sorts that last
branch just the same: `A UNION ALL B ORDER BY x` returns all of `A` followed by all of `B` in `x` order.

The executed statement always spells `ALL` or `DISTINCT` out, so a server whose `union_default_mode`,
`intersect_default_mode` or `except_default_mode` was reconfigured cannot change the result of a
statement that was accepted.

To order or limit the **combined** result, wrap the operation — the spelling that means the same thing
to both:

```sql
SELECT model FROM (
  SELECT model FROM dial_usage_log
  UNION ALL
  SELECT model FROM rate_analytics
) ORDER BY model LIMIT 10
```

### Refused

- **A bare `UNION`** (`A UNION B`), naming `ALL`/`DISTINCT`. ClickHouse's `union_default_mode` has no
  default and refuses the statement itself; accepting it as `DISTINCT` would return rows for a query the
  database rejects.
- **`MINUS`**, naming `EXCEPT`. ClickHouse has no such keyword.
- **A trailing `ORDER BY`/`LIMIT`/`OFFSET` after a closing parenthesis** — around the final branch
  (`A UNION ALL (B) ORDER BY x`), around the whole operation (`(A UNION ALL B) ORDER BY x`) or around the
  nested operation that ends it (`A UNION ALL (B INTERSECT ALL C) ORDER BY x`). The clause belongs to an
  unparenthesised final `SELECT`, so ClickHouse answers a syntax error. Wrap the operation as above
  instead. A parenthesised branch that is **not** the last one is fine:
  `(A ORDER BY x LIMIT 2) UNION ALL B ORDER BY x LIMIT 3` executes, each clause bounding its own branch —
  with one exception when the operation is nested, below.
- **A set operation over `INFORMATION_SCHEMA`**, or one mixing a catalog relation with an entity. Catalog
  relations are answered in process by an evaluator that has no set-operation support.
- ClickHouse's `SELECT * EXCEPT (col)` column-exclusion syntax, which is a different construct. It is
  the one refusal here whose message does not name what went wrong: the statement parses and then fails
  validation with `Unknown identifier '*'`. Name the columns you want instead.

### A nested block cannot open with `(` and also carry a trailing clause

Inside a `FROM` item or a CTE body, the parenthesised block may not **both** begin with a `(` of its own
**and** end with a trailing `ORDER BY`/`LIMIT`/`OFFSET`/`FETCH`. So this does not parse, and fails with a
message naming the block:

```sql
-- rejected: the block after FROM starts with "(" and ends with ORDER BY
SELECT model FROM (
  (SELECT model FROM dial_usage_log ORDER BY request_time DESC LIMIT 10)
  UNION ALL
  SELECT model FROM rate_analytics ORDER BY request_time DESC LIMIT 10
) ORDER BY model
```

Either spelling works and both mean the same thing — parenthesise the **last** branch too, or leave the
**first** one bare:

```sql
-- parenthesise every branch (each clause still bounds its own branch)
SELECT model FROM (
  (SELECT model FROM dial_usage_log ORDER BY request_time DESC LIMIT 10)
  UNION ALL
  (SELECT model FROM rate_analytics ORDER BY request_time DESC LIMIT 10)
) ORDER BY model

-- or leave the first branch unparenthesised, and the trailing clause binds to the last one as usual
SELECT model FROM (
  SELECT model FROM dial_usage_log
  UNION ALL
  SELECT model FROM rate_analytics ORDER BY request_time DESC LIMIT 10
) ORDER BY model
```

This is a limitation of the parser's grammar, not a rule of this surface — ClickHouse executes all three
— so it is reported as a parse error rather than as a named refusal. It is **not** specific to set
operations: a redundant parenthesis around the only query in a derived table
(`FROM ((SELECT a FROM t) ORDER BY a)`) and a parenthesised join with a trailing `LIMIT` fail the same
way. It applies **only** to a `FROM` item and a CTE body: the same text is accepted at the top level
(`(A) UNION ALL B ORDER BY x LIMIT 2`), as an `IN`/`EXISTS`/scalar-subquery operand, and whenever the
parenthesised branch is not the first thing in the block
(`FROM (A UNION ALL (B) UNION ALL C LIMIT 2)`).

### Governance

The row cap bounds the **combined** result: no `LIMIT` on it means the default 100 over REST, or the
`flight.max-rows` clamp over Flight. A branch's own `LIMIT` — whether parenthesised inside the branch or
written after the last one, which is a branch limit here — is executed as written, neither defaulted nor
capped. Note the consequence: `A UNION ALL B LIMIT 10` returns up to 100 rows, because the 10 bounds `B`
and nothing bounds the combination.

Each branch is validated and governed exactly as a standalone statement: catalog resolution, the closed
function catalog, `final` deduplication, enrichment joins, `sensitive` columns invisible, and a branch's
`*` expanding to that branch's non-heavy source columns. One `query.budget.*` ceiling covers the whole
statement.

A set-operation statement executes on `execute-sql`, Flight and `validate-sql`, but has no structured
query DSL equivalent, so `POST /v1/queries/translate-sql` refuses it — as it does a join or a subquery.

## Window functions

```sql
<fn> OVER ([PARTITION BY expr, ...]
           [ORDER BY expr [ASC|DESC] [NULLS FIRST|LAST], ...]
           [ROWS|RANGE BETWEEN <bound> AND <bound>])
```

- `<fn>` is `row_number()`, `rank()`, `dense_rank()`, or `count(expr | *)`, `sum(expr)`, `avg(expr)`,
  `min(expr)`, `max(expr)`. Every other name under `OVER` (`lag`, `lead`, `ntile`, `first_value`, ...)
  is rejected by name. `DISTINCT` inside a window function is rejected.
- Frame bounds: `UNBOUNDED PRECEDING`, `<n> PRECEDING`, `CURRENT ROW`, `<n> FOLLOWING`,
  `UNBOUNDED FOLLOWING`. The standard default frame is used when none is written.
- A window function is accepted only in the **select list** (including inside a select-list `CASE`) and,
  through its alias, in `ORDER BY`. In `WHERE`, `HAVING` or `GROUP BY` it is a 400; filter on a window
  result through a derived table:

  ```sql
  SELECT trace_id, project_id FROM (
    SELECT trace_id, project_id,
           row_number() OVER (PARTITION BY project_id ORDER BY total_tokens DESC) AS rn
    FROM dial_usage_log) t
  WHERE rn = 1
  ```

- In an aggregate query the window's operands must be group keys or aggregate calls:
  `sum(sum(total_tokens)) OVER ()` for a share-of-total, `rank() OVER (ORDER BY sum(x) DESC)` to rank
  groups.
- Ranking functions type as long; a windowed aggregate keeps the aggregate's own type.

## Rules by column type

| Column type | Allowed | Rejected |
|---|---|---|
| `string`, `integer`, `long`, `decimal`, `boolean`, `date`, `timestamp`, `uuid` | everything above | |
| `enum` | projection, `=`, `<>`, `IN`, ordering comparisons, `GROUP BY`, `ORDER BY`, comparison with string literals, `to_string`/`concat` (which render rather than inspect); typed `VARCHAR` on the SQL surface | string functions (`lower`, `upper`, `trim`, `length`, `not_empty`, `starts_with`, `contains`, `split_string`) and `LIKE`/`ILIKE` — reachable by rendering first: `starts_with(to_string(status), 'run')` |
| `array` | projection; the array position of an array-operand function — the first argument of `array_has`, `array_has_any`, `array_has_all`, `array_length`, `array_join`, `array_to_string`, `array_slice`, the **second** of `arrayExists`, `arrayAll`, `arrayCount`, `arrayFilter`, `arrayMap`, the only one of `groupUniqArrayArray`/`groupArrayArray`, and `arg_max`'s `value` | any predicate (`IS NULL`, `IN`, comparisons), `GROUP BY`, `ORDER BY`, `DISTINCT`, any other function argument, arithmetic |
| `object` (JSON) | projection; argument 0 of `json_extract_*`; as the **left** operand of `=`, `<>`, `IN`, `LIKE`, `ILIKE`, `NOT LIKE`, `NOT ILIKE` against character literals in `WHERE` (`=`/`<>`/`IN` literals must parse as JSON and compare as canonical documents) | `IS [NOT] NULL`, `<`/`>`/`<=`/`>=`, right-operand position, comparison with a non-literal, `GROUP BY`, `ORDER BY`, `DISTINCT`, any other function argument |
| `map` | projection; argument 0 of `map_value`, `map_contains`, `map_keys` | every predicate including `LIKE`, `GROUP BY`, `ORDER BY`, `DISTINCT`, `json_extract_*`, any other function argument |
| computed arrays (`group_uniq_array`, `groupUniqArrayArray`, `groupArrayArray`, `map_keys`, `split_string`, `json_extract_array`, `array_slice`, `arrayFilter`, `arrayMap`, and `arg_max` over an array) | projection (also under `DISTINCT`); the array position of an array-operand function, as listed for the `array` row above | `GROUP BY` (also via alias), `ORDER BY`, predicates, other function arguments; an array-valued **aggregate** in one of the three aggregate array positions (that is a nested aggregate) |
| `heavy` columns | everything, when named explicitly | never part of a `*` expansion |
| enrichment columns (`"enrichment.column"`) | select, filter, sort, group, join operand qualified | never part of a `*` expansion |
| `sensitive` columns | with the grant, everything | without it, reported as an unknown column |

Results of the scalar extractors (`json_extract_string`, `map_value`, `array_length`, `array_join`,
`array_to_string`, ...)
are ordinary scalars and carry none of the restrictions of the column they read, so they may be grouped,
sorted and compared. `array_join` is the one whose scalar result also multiplies rows; grouping and
sorting reach it through the select item's alias.

## Catalog metadata: INFORMATION_SCHEMA

Three relations describe the same catalog as `/v1/queries/entities`, filtered by the caller's column
visibility, and are answered in process without touching ClickHouse:

| Relation | Columns |
|---|---|
| `INFORMATION_SCHEMA.TABLES` | `TABLE_CATALOG` (empty), `TABLE_SCHEMA` (`adas` or `INFORMATION_SCHEMA`), `TABLE_NAME`, `TABLE_TYPE` (`TABLE` / `SYSTEM_TABLE`) |
| `INFORMATION_SCHEMA.COLUMNS` | the 17 standard columns from `TABLE_CATALOG` through `INTERVAL_PRECISION`; `ORDINAL_POSITION` 1-based, `IS_NULLABLE` always `YES`, `DATA_TYPE` in the Dremio vocabulary (`CHARACTER VARYING`, `INTEGER`, `BIGINT`, `DECIMAL`, `BOOLEAN`, `DATE`, `TIMESTAMP`, `ARRAY`) |
| `INFORMATION_SCHEMA.SCHEMATA` | `CATALOG_NAME`, `SCHEMA_NAME`, `SCHEMA_OWNER`, `TYPE`, `IS_MUTABLE` |

Over these relations the evaluator supports projection, `DISTINCT`, `WHERE`, `ORDER BY`,
`LIMIT`/`OFFSET`, and non-`DISTINCT` aggregates with `GROUP BY`/`HAVING`, with standard SQL semantics
and no page limit. It rejects, as 400: joins between catalog relations, subqueries, window functions,
`ROLLUP`/`CUBE`, `COUNT(DISTINCT ...)` (use `SELECT DISTINCT`), `HAVING` without `GROUP BY`, and any
expression computed over a grouping-less aggregate. Mixing a catalog relation with a data entity in one
statement is rejected. `CATALOGS` and `VIEWS` do not exist. Names resolve case-insensitively, quoted or
not.

## Not supported

Rejected before execution, with a message naming the construct:

- anything but a single `SELECT` (DDL, DML, `SHOW`, `DESCRIBE`, `EXPLAIN`, batches)
- a bare `UNION` (write `UNION ALL` or `UNION DISTINCT`), `MINUS` (write `EXCEPT`), and a trailing
  `ORDER BY`/`LIMIT` after a closing parenthesis — see [Set operations](#set-operations); the operators
  themselves are supported
- a `FROM` item or CTE body that both opens with a `(` of its own and carries a trailing
  `ORDER BY`/`LIMIT`/`OFFSET`/`FETCH` — a parse error naming the block, with the two spellings that work:
  [A nested block cannot open with `(`](#a-nested-block-cannot-open-with--and-also-carry-a-trailing-clause)
- `WITH RECURSIVE`
- correlated subqueries; `LATERAL`/`APPLY`; scalar subqueries as function arguments
- `ROLLUP`, `CUBE`, `GROUPING SETS`; `FILTER (WHERE ...)`
- quantified comparisons (`= SOME`, `> ALL`, `UNIQUE`)
- general `CAST`; `?` parameters
- `LIKE` patterns other than `'%text%'`; `ESCAPE`
- a join whose `ON` has no equality between its sides
- `ORDER BY` on a bare expression not in the select list
- window functions other than `row_number`, `rank`, `dense_rank`, `count`, `sum`, `avg`, `min`, `max`;
  windows in `WHERE`/`HAVING`/`GROUP BY`; `DISTINCT` inside `OVER`
- any function outside the catalog above, including ClickHouse-native functions and the DSL arithmetic
  names spelled as calls (`plus(a, b)`)
- a lambda anywhere but as the first argument of a higher-order array call, a lambda of more than one
  parameter, a parameter name outside `[a-z_][a-z0-9_]{0,63}`, and the reserved identifier
  `__lambdaParam` — see [Lambdas](#lambdas)
- `CURRENT_DATE`; an `INTERVAL` anywhere but as an operand of `+`/`-` over a timestamp; a multi-field
  interval qualifier (`INTERVAL '1-2' YEAR TO MONTH`); a non-constant, fractional or zero interval
  amount; subtracting one timestamp from another, in either spelling — see
  [The clock and relative windows](#the-clock-and-relative-windows)
- `IN` lists with non-literal, non-subquery elements

## Errors

| Situation | REST | Flight |
|---|---|---|
| parse error, unsupported construct, unknown table or column, validation failure, ClickHouse rejecting the rendered statement | 400 `bad_request` | `INVALID_ARGUMENT` |
| query budget exceeded | 422 `query_budget_exceeded` | `INVALID_ARGUMENT` (deterministic, so deliberately not the retryable `RESOURCE_EXHAUSTED`) |
| ClickHouse unreachable, **or out of a resource** — memory under other load, disk, query slots, connections, too many parts | 503 `clickhouse_unavailable` | `UNAVAILABLE` |
| Flight statement slots saturated | n/a | `RESOURCE_EXHAUSTED` |
| credential missing or rejected | 401 / 403 | `UNAUTHENTICATED` |
| an invalid statement on `validate-sql` | 200 with `valid: false` — the verdict is the result; only a malformed request is 400 | n/a |

All REST errors use the `ErrorView` body `{status, error, message, path, method}`.

The budget row and the unavailable row overlap on one ClickHouse error code — `MEMORY_LIMIT_EXCEEDED` —
told apart by which ceiling the server's message names. A query that exceeds **its own** budget is refused
deterministically — the identical query is refused identically, so
retrying loops. A query killed because the **server** was out of memory under unrelated load is an
outage: the identical query succeeds once that load clears, so it is retryable and reported as such.

## Examples

```sql
-- classification, grouped by the CASE alias
SELECT CASE WHEN total_tokens >= 300 THEN 'large' WHEN total_tokens >= 100 THEN 'medium' ELSE 'small' END AS size,
       count(*) AS n, sum(total_tokens) AS t
FROM dial_usage_log GROUP BY size ORDER BY size;

-- hourly buckets with a ratio
SELECT date_bin(1, 'hour', request_time) AS hour, deployment,
       sum(cached_prompt_tokens) / sum(prompt_tokens) AS cache_rate
FROM dial_usage_log
WHERE request_time >= '2026-08-01' AND request_time < '2026-08-02'
GROUP BY hour, deployment ORDER BY hour, deployment;

-- join with an outer side and a computed cost
SELECT l.project_id, count(*) AS n, sum(l.total_tokens * r.rate) AS cost
FROM dial_usage_log l LEFT JOIN usage_rates r ON l.trace_id = r.trace_id
GROUP BY l.project_id ORDER BY cost DESC NULLS LAST;

-- CTE + IN subquery + scalar subquery
WITH priced AS (SELECT trace_id FROM usage_rates WHERE rate > 4)
SELECT trace_id, total_tokens, (SELECT avg(total_tokens) FROM dial_usage_log) AS mean
FROM dial_usage_log
WHERE trace_id IN (SELECT trace_id FROM priced) ORDER BY trace_id;

-- running total and share of total
SELECT project_id, sum(total_tokens) AS t,
       round(100 * sum(total_tokens) / sum(sum(total_tokens)) OVER (), 1) AS pct,
       rank() OVER (ORDER BY sum(total_tokens) DESC) AS r
FROM dial_usage_log GROUP BY project_id ORDER BY r;

-- JSON and map accessors. The claim/header documents live on the payload enrichment, so they are
-- addressed by their quoted qualified names and the grain join is emitted for you.
SELECT json_extract_string("dial_usage_log_payload.jwt_claims", 'sub') AS subject, count(*) AS n
FROM dial_usage_log
WHERE json_extract_bool("dial_usage_log_payload.jwt_claims", 'admin')
  AND "dial_usage_log_payload.request_tags" ILIKE '%gpt-4%'
GROUP BY subject ORDER BY n DESC LIMIT 20;

-- one row per array element, counted by element
SELECT array_join(execution_path) AS step, count(*) AS n
FROM dial_usage_log GROUP BY step ORDER BY n DESC;

-- catalog discovery
SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'adas' AND TABLE_NAME = 'dial_usage_log' ORDER BY ORDINAL_POSITION;
```
