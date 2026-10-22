# structured-query Specification

## Purpose

The structured query capability defines a single structured query contract — sent in a request
body, never as SQL — that queryable analytics entities share. A client posts a top-level envelope
(`entity`, `filter`, `mode`, `select`, `group_by`, `aggregate`, `having`, `sort`, `page`) carrying a
CQL2-JSON filter tree (`op`/`args`), an expression grammar and pagination shapes (discriminated by
`type`), and a Jackson binding contract (discriminators, `snake_case` keys, `@JsonValue` enum wire
codes). The query is validated against the named entity's discovered schema, translated to ClickHouse
SQL, and executed; the response is a `{ rows, totalCount }` envelope.

This capability is the data-querying surface of ADAS. Execution runs at `POST /v1/queries/execute`.
Discovery of queryable entities and their flat field schemas is a separate capability — see
[query-schema-discovery](../query-schema-discovery/spec.md).

> **Note — experimental.** This is the first iteration of body-delivered querying. SQL is produced by
> building a jOOQ query model on a connection-less `SQLDialect.CLICKHOUSE` context and rendering it to
> a string; ClickHouse rendering of the ordered-set aggregates and `width_bucket` is provisional under
> jOOQ's experimental ClickHouse dialect (see the change's design notes).
## Requirements
### Requirement: Structured query envelope

The system SHALL provide an immutable request object model representing a single structured query
envelope with the fields `entity` (required), `filter`, `mode` (required), `select`, `group_by`,
`aggregate`, `having`, `sort`, `page`, and the optional boolean `final` (see "Read result deduplication").
JSON keys SHALL be `snake_case` (`group_by`, `include_total`, `value_type`, and the optional output-alias
key `as`). `final` SHALL be three-valued — `true`, `false`, or absent — and an absent `final` SHALL stay
absent through deserialization and re-serialization, never becoming `false`, because absent and `false`
read an `upsert_by_key` source differently.

#### Scenario: Full row-mode envelope binds

- **WHEN** a row-mode JSON query with `entity`, `mode: "row"`, `filter`, `select`, `sort`, and an
  `offset` page is deserialized into the envelope record
- **THEN** every field binds to its typed component, `mode` resolves to `ROW`, and `select`/`sort`
  resolve to their element record types

#### Scenario: Full aggregate-mode envelope binds

- **WHEN** an aggregate-mode JSON query with `group_by`, `select` aggregate entries (with or without
  the optional alias `as`), `having`, and a `cursor` page is deserialized
- **THEN** `mode` resolves to `AGGREGATE`, `group_by` binds to a string list, the aggregate select
  entries bind with their aliases where present and a null alias where absent, and `having` binds to a
  filter node

#### Scenario: Mode is required

- **WHEN** a query omits `mode`
- **THEN** request validation rejects it as a bad request; `mode` must be supplied explicitly and is
  never inferred from `group_by`/`select`. The remaining sections stay optional, with mode-coherence
  left to the validation layer.

#### Scenario: final binds three ways

- **WHEN** three otherwise identical queries are deserialized, one with `"final": true`, one with
  `"final": false`, and one without `final`
- **THEN** they bind to three distinct envelopes, and re-serializing the third emits no `final` key

### Requirement: Filter sub-grammar (CQL2-JSON)

The system SHALL model a recursive filter tree as a sealed `FilterNode` hierarchy discriminated by
the `op` key: logical nodes (`and`, `or`, `not`) carrying a list of child nodes, and predicate nodes
carrying an operator code (`eq`, `ne`, `co`, `nc`, `ico`, `inc`, `lt`, `gt`, `le`, `ge`, `in`) and a
positional `args` list of expressions. The contains family has four operators: `co`/`nc` are
**case-sensitive** contains / not-contains, and `ico`/`inc` are **case-insensitive** contains /
not-contains. (**BREAKING**: `co`/`nc` previously matched case-insensitively; clients wanting the
old behavior use `ico`/`inc`.) Because `op` is both the discriminator and operator data, routing
SHALL be performed by a custom deserializer wired at each use site, not by a declarative
`@JsonTypeInfo` on the interface.

#### Scenario: Nested boolean tree binds

- **WHEN** a filter `(event_kind = 'llm_call' AND total_tokens > 100) OR (...)` is deserialized
- **THEN** the root binds to a logical `OR` node whose children are logical `AND` nodes containing
  predicate nodes with their operator codes and operand expressions

#### Scenario: Both operands are general expressions

- **WHEN** a predicate `length(project_id) = 3` is deserialized
- **THEN** the predicate's `args` hold a function expression on the left and a value expression on the
  right, with no requirement that either operand be a bare column

#### Scenario: Logical-vs-predicate routing by op

- **WHEN** a node's `op` is `and`/`or`/`not`
- **THEN** it routes to the logical node type; otherwise it routes to the predicate node type

#### Scenario: Case-insensitive contains binds

- **WHEN** a predicate `{"op": "ico", "args": [...]}` or `{"op": "inc", "args": [...]}` is deserialized
- **THEN** it binds to a predicate node with the case-insensitive contains (or not-contains) operator

### Requirement: Expression grammar

The system SHALL model expressions as a sealed `Expr` hierarchy discriminated by the `type` key with
nine kinds: `field` (column reference), `value` (literal whose `value` is always a JSON string
governed by `value_type`), `param` (runtime parameter), `fn` (function call with a nestable
expression `args` list), `array` (collection whose items use the key `items`), `case` (conditional with a
`when` list of filter-node/expression branches and an optional `else`), `window` (a function call
qualified by `partition_by`, `order_by` and `frame`), `lambda` (a `params` name list and a `body`
expression, legal only as a higher-order function's lambda argument) and `lambda_param` (a reference
to one of those parameters, legal only inside such a body — see "Lambda expressions").
`value_type` SHALL be a closed enum: `string`,
`integer`, `long`, `decimal`, `boolean`, `date`, `timestamp`, `uuid`, `null`. Any other `type` value SHALL
be rejected with HTTP 400 as an unreadable request.

#### Scenario: Nested function expression binds

- **WHEN** `round(div(sum(total_tokens), count()), 2)` is deserialized as an expression
- **THEN** the nested `fn` records bind recursively, `count` binds with an empty args list, and the
  trailing integer literal binds as a value expression

#### Scenario: Null literal binds

- **WHEN** a `value` expression with `value_type: "null"` and `value: null` is deserialized
- **THEN** it binds to a value expression carrying the `NULL` value-type and a null value

#### Scenario: Array expression binds with items key

- **WHEN** an `array` expression `{ "type": "array", "items": [ <value>, <value> ] }` is deserialized
- **THEN** it binds to the array record using the `items` key, distinct from a function's `args`

#### Scenario: Case expression binds

- **WHEN** `{ "type": "case", "when": [ { "when": { "op": "gt", "args": [ <field>, <value> ] }, "then": <value> } ], "else": <value> }` is deserialized
- **THEN** it binds to the case record with one branch whose condition is a comparison node, and an else expression

#### Scenario: Window expression binds

- **WHEN** `{ "type": "window", "fn": { "type": "fn", "name": "row_number", "args": [] }, "partition_by": [ <field> ], "order_by": [ { "expr": <field>, "dir": "desc" } ] }` is deserialized
- **THEN** it binds to the window record with an absent frame

#### Scenario: Lambda expression binds

- **WHEN** `{ "type": "lambda", "params": ["x"], "body": { "type": "fn", "name": "not_empty", "args": [ { "type": "lambda_param", "name": "x" } ] } }` is deserialized
- **THEN** it binds to the lambda record with one parameter name and a body whose `lambda_param` reference is distinct from a `field` reference, so a walker collecting the columns the statement reads does not see `x` as one

#### Scenario: Unknown expression type rejected

- **WHEN** an expression with `"type": "subquery"` is posted to `POST /v1/queries/execute`
- **THEN** the request is rejected with HTTP 400 `bad_request`

### Requirement: `in` predicate with array operand

The system SHALL treat `in` as an ordinary binary predicate whose right operand is typically an
`array` expression, without structurally encoding "left scalar / right set" — that constraint is
deferred to validation.

#### Scenario: Set membership binds

- **WHEN** `event_kind IN ('llm_call', 'embedding')` is deserialized
- **THEN** the predicate's `args` hold a field expression on the left and an array expression of two
  value literals on the right

### Requirement: Aggregation, sort, and pagination request shapes

The system SHALL model aggregate select entries (a function expression with an **optional** output
alias bound from the `as` key), a sort item (`field`, `dir` ∈ `asc`/`desc`, and an optional `nulls` ∈
`first`/`last`), and a sealed `PageSpec` hierarchy
discriminated by `type` with an offset strategy (`offset`, `limit`, `include_total`) and a cursor
strategy (`cursor`, `limit`).

#### Scenario: Offset page binds

- **WHEN** `{ "type": "offset", "offset": 0, "limit": 25, "include_total": true }` is deserialized
- **THEN** it binds to the offset page record with `include_total` mapped from the snake_case key

#### Scenario: Cursor page binds with absent cursor

- **WHEN** `{ "type": "cursor", "limit": 100 }` (no `cursor`) is deserialized
- **THEN** it binds to the cursor page record with a null cursor (first page)

#### Scenario: Sort item null ordering binds

- **WHEN** a sort item `{ "field": "total_tokens", "dir": "asc", "nulls": "last" }` is deserialized
- **THEN** `nulls` binds to LAST; when `nulls` is omitted it is null and the SQL translation emits no
  NULLS clause, letting the database default apply (`ASC` → NULLS LAST, `DESC` → NULLS FIRST)

#### Scenario: Aggregate alias binds from `as`

- **WHEN** a select entry `{ "expr": { "type": "fn", "name": "count", "args": [] }, "as": "total" }`
  is deserialized
- **THEN** the alias component binds from the `as` key

#### Scenario: Select entry binds with no alias

- **WHEN** a select entry `{ "expr": { "type": "fn", "name": "count", "args": [] } }` is deserialized
- **THEN** it binds with a null alias component and is accepted in both modes, the output name being
  derived at translation time

### Requirement: Stable wire serialization

The system SHALL serialize the request model back to JSON that round-trips: deserialize → serialize →
deserialize SHALL produce an equal object, preserving the `op`/`type` discriminators, the
`snake_case` keys, and the lowercase enum wire codes (e.g. `eq`, `and`, `row`, `asc`, `string`,
`field`, `array`).

#### Scenario: Round-trip equality

- **WHEN** an envelope containing an `in` predicate with an array operand and an offset page is
  deserialized, re-serialized, and deserialized again
- **THEN** the second object equals the first and the serialized form contains the expected
  discriminator and snake_case keys

### Requirement: Query execution endpoint

The system SHALL execute a structured query submitted as a request body at
`POST /v1/queries/execute` and return its results. Execution SHALL be entity-agnostic: the request
SHALL be routed by its `entity` to a catalog-backed executor, where a valid `entity` is a
`ACTIVE` `source` table in the table-management catalog — of either write discipline, so an
`upsert_by_key` rollup source is queryable exactly like an `append` source. A query naming an entity
that is not a materialized source (unknown, an enrichment, or a non-materialized table) SHALL be
rejected with a validation error (HTTP 400). The query SHALL be translated to ClickHouse SQL — built
through a jOOQ query model on a connection-less `SQLDialect.CLICKHOUSE` render context and rendered to
a SQL string, then executed via the ClickHouse HTTP client — never assembled by raw string
concatenation.

#### Scenario: Row-mode query executes and returns rows

- **WHEN** a valid row-mode query for a queryable source (e.g. `dial_usage_log`) is posted to
  `/v1/queries/execute`
- **THEN** the response carries the projected rows as field-name → value maps

#### Scenario: Non-queryable entity is rejected

- **WHEN** a query names an entity that is not a materialized source (unknown, an enrichment, or a
  non-materialized table)
- **THEN** the request is rejected with HTTP 400

#### Scenario: An upsert-by-key source is queryable

- **WHEN** a query names an `ACTIVE` `source` whose write discipline is `upsert_by_key`
- **THEN** it is accepted and executed like any other source, with no aggregation-specific or
  engine-specific handling

### Requirement: Supported function catalog

The system SHALL accept in a structured query's expressions only functions from a closed catalog, and
SHALL reject any other function name with HTTP 400. Each catalog entry SHALL define the function's
group (scalar, aggregate, or ordered-set aggregate), arity, operand types, and return type. The
catalog is:

| Function | Group | Arity / signature | Returns |
|---|---|---|---|
| `abs` | scalar | `abs(numeric)` | numeric |
| `array_has` | scalar | `array_has(array, value)` (2 args) | boolean |
| `array_has_all` | scalar | `array_has_all(array, [value…])` (2 args) | boolean |
| `array_has_any` | scalar | `array_has_any(array, [value…])` (2 args) | boolean |
| `array_join` | scalar | `array_join(array)` | element type of `array` |
| `array_length` | scalar | `array_length(array)` | integer |
| `array_slice` | scalar | `array_slice(array, offset, length)` (3 args, bounds integer literals) | array of `array`'s element type |
| `array_to_string` | scalar | `array_to_string(array, delimiter)` (2 args, `delimiter` a string literal) | text |
| `arrayAll` | scalar | `arrayAll(x -> boolean, array)` (2 args, lambda first) | boolean (`true` for an empty array) |
| `arrayCount` | scalar | `arrayCount(x -> boolean, array)` (2 args, lambda first) | integer |
| `arrayExists` | scalar | `arrayExists(x -> boolean, array)` (2 args, lambda first) | boolean |
| `arrayFilter` | scalar | `arrayFilter(x -> boolean, array)` (2 args, lambda first) | array of `array`'s element type |
| `arrayMap` | scalar | `arrayMap(x -> expression, array)` (2 args, lambda first) | array of the body's type |
| `ceil` | scalar | `ceil(numeric)` | numeric (integral) |
| `concat` | scalar | `concat(value, value[, value…])` (2+ args, any scalar types) | text |
| `contains` | scalar | `contains(text, substring)` (2 args) | boolean |
| `date_add` | scalar | `date_add(unit, amount, timestamp)` (3 args) | timestamp |
| `date_bin` | scalar | `date_bin(amount, unit, timestamp)` (3 args) | timestamp (bucket start) |
| `date_sub` | scalar | `date_sub(unit, amount, timestamp)` (3 args) | timestamp |
| `date_trunc` | scalar | `date_trunc(unit, timestamp)` (2 args) | timestamp (period start) |
| `div` | scalar | `div(numeric, numeric)` (2 args) | numeric (null on zero/null divisor) |
| `equals` | scalar | `equals(a, b)` (2 args) | boolean |
| `floor` | scalar | `floor(numeric)` | numeric (integral) |
| `if` | scalar | `if(condition, then, else)` (3 args) | type of `then`/`else` |
| `json_extract_array` | scalar | `json_extract_array(json, key[, key…])` (2+ args) | array of text |
| `json_extract_bool` | scalar | `json_extract_bool(json, key[, key…])` (2+ args) | boolean |
| `json_extract_double` | scalar | `json_extract_double(json, key[, key…])` (2+ args) | numeric |
| `json_extract_long` | scalar | `json_extract_long(json, key[, key…])` (2+ args) | long |
| `json_extract_string` | scalar | `json_extract_string(json, key[, key…])` (2+ args) | text |
| `json_extract_string_ci` | scalar | `json_extract_string_ci(json, key)` (2 args) | text |
| `length` | scalar | `length(text)` | integer |
| `lower` | scalar | `lower(text)` | text |
| `map_contains` | scalar | `map_contains(map, key)` (2 args, `key` a single literal) | boolean |
| `map_keys` | scalar | `map_keys(map)` | array of text |
| `match` | scalar | `match(haystack, pattern)` (2 args) | boolean |
| `map_value` | scalar | `map_value(map, key)` (2 args, `key` a single literal) | text |
| `minus` | scalar | `minus(numeric, numeric)` (2 args) | numeric |
| `modulo` | scalar | `modulo(numeric, numeric)` (2 args) | numeric (null on zero/null right operand) |
| `multiply` | scalar | `multiply(numeric, numeric)` (2 args) | numeric |
| `not_empty` | scalar | `not_empty(text)` | boolean |
| `now` | scalar | `now()` (0 args) | timestamp |
| `plus` | scalar | `plus(numeric, numeric)` (2 args) | numeric |
| `round` | scalar | `round(numeric[, digits])` (1–2 args) | numeric |
| `split_string` | scalar | `split_string(text, delimiter)` (2 args) | array of text |
| `starts_with` | scalar | `starts_with(text, prefix)` (2 args) | boolean |
| `to_string` | scalar | `to_string(value)` (any scalar) | text |
| `trim` | scalar | `trim(text)` | text |
| `upper` | scalar | `upper(text)` | text |
| `width_bucket` | scalar | `width_bucket(operand, low, high, count)` (4 args) | integer (bucket) |
| `arg_max` | aggregate | `arg_max(value, order)` (2 args) | value's type |
| `avg` | aggregate | `avg(numeric)`; `distinct` allowed | numeric |
| `count` | aggregate | `count()` or `count(col)`; `distinct` allowed | long |
| `group_uniq_array` | aggregate | `group_uniq_array(col)` | array of col type |
| `groupArrayArray` | aggregate | `groupArrayArray(array)` | array of `array`'s element type (flattened) |
| `groupUniqArrayArray` | aggregate | `groupUniqArrayArray(array)` | array of `array`'s element type (flattened, distinct) |
| `max` | aggregate | `max(col)` | col type |
| `min` | aggregate | `min(col)` | col type |
| `sum` | aggregate | `sum(numeric)`; `distinct` allowed | numeric |
| `percentile_cont` | ordered-set aggregate | `percentile_cont(fraction, column)` | numeric (interpolated) |
| `percentile_disc` | ordered-set aggregate | `percentile_disc(fraction, column)` | type of `column` (an actual member) |

**The `distinct` quantifier rule.** The catalog SHALL mark each aggregate with whether it accepts the
`distinct` quantifier, and every surface that accepts an aggregate — the DSL, the SQL subset, and an
aggregate-pipeline measure — SHALL honour that one flag rather than deciding for itself. `count`, `sum`
and `avg` SHALL accept it. Every other aggregate SHALL reject it, and the catalog entry SHALL state
**why** rather than only that it is unsupported, so a client can tell a deliberate exclusion from an
unimplemented one:

| Aggregate | Reason `distinct` is refused |
|---|---|
| `min`, `max` | deduplicating the input cannot change the extreme value, so the quantifier would be a silent no-op |
| `percentile_cont`, `percentile_disc` | deduplicating changes which distribution is being measured — the percentile of a value set is a different statistic from the percentile of the observations, not the same answer computed differently |
| `group_uniq_array` | the result is already the group's distinct values |
| `groupUniqArrayArray` | the result is already the group's distinct elements |
| `groupArrayArray` | deduplicating the flattened elements would make it `groupUniqArrayArray` |
| `arg_max` | deduplicating values would detach a `value` from the `order` that selects it |

`group_uniq_array` SHALL return the distinct non-null values of its operand over the group, sorted
ascending, and SHALL return an empty array — never null — for a group holding no non-null value. It
SHALL NOT accept the `distinct` quantifier (the result is already a set). Its result SHALL be
projectable only: since the DSL has no array literal or comparison, an array result used as an operand
of another expression SHALL be rejected, the same restriction that applies to a declared `array`
column.

**Arithmetic scalars.** `plus`, `minus`, `multiply`, `div` and `modulo` SHALL each take two numeric
operands and
SHALL return a numeric result, in projection, `group_by`, `having` and filter position alike. Operand
types SHALL widen against each other across `integer`, `long` and `decimal`, and the call SHALL return
the widest of them, except that `div` SHALL always return `decimal` so that an integer-over-integer
ratio is not silently truncated. `modulo` SHALL follow the ordinary widening rule rather than `div`'s
exception, so an integer remainder stays an integer. A null operand SHALL yield null.

The **result schema** a translated statement advertises (the Arrow/Flight column type) SHALL name the
type the values actually arrive as, which for `plus`/`minus`/`multiply` is one rank wider than their
operands: ClickHouse promotes to avoid overflow, so an `integer`-by-`integer` product SHALL be
advertised as a 64-bit integer column and not as a 32-bit one, on the same rule `sum` already follows.
`modulo` SHALL NOT be promoted — a remainder cannot exceed its divisor, so the operand width holds.

`div` and `modulo` SHALL each return **null** when their right operand is zero or null, and SHALL NOT
fail the query. This is
normative and not the underlying engine's default: an aggregate ratio whose denominator is empty for
one group SHALL yield a null cell for that group while every other group returns its value. An
integer-typed zero right operand SHALL be covered by the same rule, so neither function ever raises a
division error regardless of operand types.

No row SHALL compute a division by zero, including a row whose result is NULL. ClickHouse still computes
a NULL row's hidden value, so `divide(a, nullIf(b, 0))` hides `inf`/`nan` there. Converting the result to
`Decimal` or an integer checks those hidden values too and fails, and an `INSERT … SELECT` into a
`decimal` or integer column performs exactly that conversion. The rendering therefore divides by a
non-zero stand-in and restores the NULL around the call. The result type is unchanged.

`modulo` SHALL return the remainder with the **sign of its left operand**, so
`modulo(-7, 3)` is `-1`; a caller wanting a non-negative cycle position must wrap the call in `abs`.
The sign rule is stated because the two common conventions disagree and a bucketing expression that
silently changes convention would mis-bucket every negative value.

`round(numeric[, digits])` SHALL round half away from zero, to `digits` decimal places when supplied
and to zero places otherwise; `digits` SHALL be an integer literal and MAY be negative. `floor` and
`ceil` SHALL take one numeric operand and round toward negative and positive infinity respectively.
All three SHALL return null for a null operand.

**Map key access.** `map_value(map, key)` SHALL return, as text, the value stored in a `map` field
under `key`, and SHALL return the **empty string** — never null and never an error — when the map does
not carry that key, is empty, or is null. This matches `json_extract_string`'s absent-key contract.

`map_contains(map, key)` SHALL be true when the map carries `key` and false — never null and never an
error — when it does not, is empty, or is null. It SHALL be the presence test that `map_value` cannot
give: because an absent key and a key stored with an empty value both yield the empty string,
`map_value` alone cannot distinguish them, and `map_contains` SHALL.

`map_keys(map)` SHALL return the map's keys as an array of text, in the map's stored order, and SHALL
return an empty array — never null — for an empty or null map. It answers which keys a
producer-defined map actually carries, which no other function in the catalog can ask.

In all three, `key` SHALL be a single string literal, SHALL NOT be variadic, and SHALL be matched
against the map's
keys **exactly and literally**. They are therefore NOT path expressions: unlike every
`json_extract_*` function, they SHALL NOT traverse nesting, and a key containing a dot (`user.email`)
SHALL be looked up as that one flat key rather than split into segments. A `map` field SHALL be legal
as the first argument of `map_value`, `map_contains` and `map_keys` despite being projection-only, and
SHALL remain projection-only in
every other position.

`map_value`'s result is an ordinary text scalar and `map_contains`'s an ordinary boolean, each carrying
no restriction: both SHALL be legal as a
`group_by` key, a `sort` key, a filter operand, and an aggregate's operand, and `map_contains` SHALL
additionally be legal as `if`'s condition.

`map_keys`' result is an **array**, so it SHALL be projectable only, on the same footing as
`json_extract_array`'s and `split_string`'s — with the same single exception: it SHALL be legal as the
first argument of an array-operand function, which is what makes `array_length(map_keys(m))` express a
map's size, `array_has(map_keys(m), 'k')` express the same test as `map_contains(m, 'k')`, and
`array_to_string(map_keys(m), ', ')` list the keys in one cell. That overlap is
deliberate; `map_contains` is the direct form.

**Array predicates.** `array_has(array, value)` SHALL be true when the array contains `value` and
false otherwise. `array_has_any(array, [value…])` SHALL be true when at least one listed value is
present; `array_has_all(array, [value…])` SHALL be true when every listed value is present. All three
SHALL return **false — never null and never an error** — when the array is empty or null, and SHALL
accept an array field as their first argument despite its being projection-only. Their second operand
SHALL be a literal for `array_has` and an array expression (§ the `array` expression kind) for the
other two; an empty value list SHALL be rejected with HTTP 400 rather than silently answering true or
false.

All three return boolean, so they SHALL be legal both in filter position and as `if`'s `condition`,
which is what lets a closed value set be turned into a distribution in a single aggregate query
(`sum(if(array_has(roles, 'admin'), price, 0))`).

`array_length(array)` SHALL return the element count as an integer, and SHALL return `0` — never null
— for an empty or null array.

Equality against a value set is the whole of what **these** functions provide. A test that is not an
equality — a prefix, a substring, a regex, a count of matching elements — is the separate concern of
the higher-order family, whose contract is stated by "Higher-order array functions"; `array_has_any`
over an enumerated set stays the form that can use a data-skipping index. Exploding an array into
one row per element is the separate concern of `array_join`, whose contract — its row-multiplying semantics,
its empty-array behaviour, and the one way it makes a `group_by` over individual elements
expressible — is stated by "Array element expansion" rather than here. Rendering an array as text and
narrowing one to a bounded window are likewise the separate concerns of `array_to_string` and
`array_slice`, stated by "Array text rendering and slicing". An `array`
field itself SHALL stay rejected as a `group_by` key wherever it was rejected before — that is, on the
SQL surfaces, which is where the projection-only restriction lives for every one of these types; only
a scalar result is groupable — the `array_join` call's element or the `array_to_string` call's text —
and only through the alias of the select entry
carrying it.

**Array-returning scalars compose into the array-operand functions.** `map_keys`, `split_string`,
`json_extract_array`, `array_slice`, `arrayFilter` and `arrayMap` all return arrays and are all
projection-only, and the array
accessors — plus `array_join`, `array_to_string` and `array_slice` — all accept an array in their first
argument, as the higher-order functions do in their second. A call of the first kind nested in the
array-operand position of the second SHALL therefore be accepted, and is the intended way to test,
measure, render, narrow or expand a computed array.

**Delimited-string splitting.** `split_string(text, delimiter)` SHALL return an array of text obtained
by splitting `text` on each occurrence of `delimiter`, with leading and trailing whitespace stripped
from every element. It SHALL return an empty array for a null or empty `text`, and SHALL treat a
`text` containing no delimiter as a single-element array. `delimiter` SHALL be a non-empty string
literal. Its array result SHALL be projectable only, on the same footing as `json_extract_array`'s and
`group_uniq_array`'s.

**Conditional and predicate scalars.** `if(condition, then, else)` SHALL evaluate to `then` when
`condition` is true and to `else` otherwise, and SHALL treat a null `condition` as false. `condition`
SHALL be a boolean-valued expression — a boolean column, or a call to `not_empty`, `starts_with`,
`contains`, `equals`, or `json_extract_bool`. A non-boolean `condition` SHALL be rejected with HTTP
400: the DSL has no comparison operator in expression position, so a comparison such as `length(x) > 0`
is NOT a valid condition and SHALL be written as `not_empty(x)`. `then` and `else` SHALL be of the same
type, except that the numeric types (`integer`, `long`, `decimal`) SHALL be mutually acceptable and the
call SHALL return the widest of them; any other mismatch SHALL be rejected with HTTP 400. `if` calls
SHALL nest, which is how a multi-branch classification is expressed.

Two branch shapes SHALL be rejected with HTTP 400 ahead of that comparison, each on its own terms. A
**null literal** branch SHALL be rejected: the DSL has no null in expression position, and a null *test*
is filter-grammar vocabulary (`eq`/`ne` against a null literal), so there is no way to write
`if(c, x, NULL)` and the rejection SHALL say where a null test belongs instead. A **non-scalar**
(`object`, `array` or `map`) branch SHALL be rejected: those values are projection-only, so a conditional
over them has nothing to return — and admitting one would make the call itself a non-scalar result that
the array restriction, which recognizes an array by the producing function's name, cannot see.

Branch types SHALL be compared in the **catalog's** type vocabulary, not in whatever type the rendering
layer happens to give a translated expression. The distinction is observable, and each of these SHALL hold:
a `timestamp` branch SHALL NOT unify with a `long` one (ClickHouse has no common type for the pair, so
accepting it would answer with a server error where this contract promises HTTP 400); a `timestamp` column
SHALL unify with a `timestamp` literal; and `object`, `array` and `map` SHALL be three distinct types
rather than one opaque one.

`not_empty(text)` SHALL be true exactly when its operand is neither null nor the empty string, and
SHALL be false — never null — for a null operand. This is what makes it usable as an `if` condition
over both nullable columns and ADAS's empty-string absence convention (see the `json_extract_*`
absence rule below), and it makes `if(not_empty(a), a, b)` the DSL's spelling of a first-non-absent
merge.

`starts_with(text, prefix)` and `contains(text, substring)` SHALL be true exactly when the operand
begins with, respectively contains, the second operand, and SHALL compare **case-sensitively**; a
caller wanting a case-insensitive test SHALL wrap both operands in `lower`. `equals(a, b)` SHALL be
true exactly when its operands are equal, and SHALL be the expression-position counterpart of the
filter grammar's `eq` comparison. All three SHALL be false — never null — when an operand is null.

`not_empty`, `starts_with` and `contains` SHALL take **text** operands (`string` or `uuid`), and SHALL
reject any other operand type with HTTP 400. `equals` SHALL take operands of any type but SHALL require
the two to be of the **same type** under the same widening rule `if`'s branches follow, rejecting any
other pair with HTTP 400: ClickHouse raises on a pair it has no common type for rather than answering
false, and these functions are contracted to return a boolean. Neither `equals` operand may be a **null
literal** — `x = NULL` is null for every row and so false for every row once the null-safety wrap
applies, which is a silent always-false and not what the name suggests; a null test SHALL be written in
`filter` as `eq`/`ne` against a null literal. All of these SHALL be enforced identically on the DSL and
SQL surfaces, so that the same expression is accepted, or rejected with HTTP 400, through either.

**Every function the catalog advertises as returning `boolean` SHALL serialize as a JSON boolean**, on
every surface — `not_empty`, `starts_with`, `contains`, `equals`, `json_extract_bool`, `map_contains`
and the three `array_has*` predicates alike. This is a rendering obligation rather than a free
consequence: ClickHouse answers every predicate as `UInt8`, and a null-safety wrap over one stays
`UInt8`, so each SHALL be wrapped as `toBool(…)` — without it the REST surfaces return `1`/`0` for one
advertised boolean and `true`/`false` for another in the same row, while the Arrow surfaces (which
carry the declared result kind) return booleans for both. One advertised return type SHALL have one
spelling, whichever endpoint asked.

`json_extract_string_ci(json, key)` SHALL behave as `json_extract_string` with a two-argument arity
except that it matches the JSON **key** case-insensitively: it SHALL return the value of the first
member whose key equals `key` ignoring case, and the empty string when no member matches. Only the key
is case-folded; the returned **value** SHALL be returned verbatim, in the case the document carries.
Unlike the other `json_extract_*` functions it SHALL NOT accept a multi-segment path — case-insensitive
matching is defined for a single top-level key only. Its evaluation reads the whole JSON document
rather than short-circuiting on a key match, so it is more expensive than `json_extract_string` and
SHALL be chosen only where the key's case is genuinely not known.

`json_extract_array(json, key[, key…])` SHALL return the array of text values at the literal path
inside a JSON text expression, and SHALL return an **empty array** — never null — when the path is
absent, unparseable, or names a member that is not a JSON array. Its result SHALL be projectable only,
under the same restriction that applies to `group_uniq_array` and to a declared `array` column: since
the DSL has no array literal or comparison, an array result used as an operand of another expression
SHALL be rejected.

**Both late extractors SHALL accept an `object` field as their JSON argument**, on the same terms as
the other four: the argument SHALL be either an expression yielding JSON **text** or an `object`-typed
field, and both forms SHALL return the same value for the same document. They SHALL reach it
differently. **`json_extract_string_ci` SHALL be the only extractor that reads an `object` field
through the document's serialized text**: it has no statically known subcolumn to address — not
knowing the key's spelling is the whole of what it offers — so over an `object` field it SHALL be
evaluated against `toJSONString(<column>)`. `json_extract_array` SHALL be evaluated against the
**addressed subcolumn's own re-serialization**, reading one stream rather than one per JSON path per
part. A rendering that
hands an `object` field to a native JSON *text* accessor SHALL NOT be used: on ClickHouse 26.3
`JSONExtractKeysAndValues` over a `JSON` operand returns an **empty key set instead of failing**, so
`json_extract_string_ci` would return the empty string for every row of an `object` column with no
error raised anywhere, and `JSONExtract(…, 'Array(String)')` over the same operand fails with
`ILLEGAL_TYPE_OF_ARGUMENT`. Reading through the serialized text is the whole of
`json_extract_string_ci`'s extra cost: it costs a full document materialization per row where the
other five extractors read one path. For both, a **composite** member reaches them as the column's
re-serialization rather than the source spelling — the same exception the other four carry over an
`object` field, in its own shape.

**`json_extract_array` SHALL NOT be rendered over the subcolumn's `toString`.** Stringifying an array
subcolumn yields ClickHouse's array syntax rather than JSON — `['a','b']` for a stored `["a","b"]` —
which a JSON extractor cannot parse, so that rendering returns the **empty array**, silently and
without error, for every array carrying a **quoted string, a null, or a composite** element. Elements
whose two syntaxes coincide — bare integers, floats and booleans — agree, so the defect is invisible
to any check that avoids the three kinds that diverge: over a `roles` member holding
quoted strings the stringifying form agrees with the serialized-document form on **no row at all**, while the
re-serializing form agrees on every one.

`json_extract_array`'s results SHALL be unchanged by how it reads: the same elements for an array
member, and the **empty array** — never null — for a path that is absent, unparseable, or names a
member that is not an array, including where an unmatched `LEFT JOIN` supplies the column's type
default.

The path rules stated below for the path-addressable extractors SHALL apply to these two unchanged:
the key arguments SHALL be **string literals**, never computed expressions, and a key that is empty
or contains a **backslash** SHALL be rejected with HTTP 400 whichever form the JSON argument takes.
`json_extract_string_ci` carries exactly one key argument; `json_extract_array` carries at least one.

For `arg_max`, `value` and `order` SHALL each be any resolvable field expression, and the call SHALL
return the `value` of the group's row bearing the greatest `order`. Its return type SHALL be `value`'s
type. A row SHALL compete only when **both** its `order` and its `value` are non-null: a row with a
null `order` is not ranked, and a row with a null `value` is skipped rather than winning with a null.
The result is therefore the latest **non-null** value, not the value of the latest row — a caller
reading it as "what this key settled on" gets the last row that recorded anything, which is a different
question when the value is nullable. The result SHALL be null exactly when no row of the group has both
operands non-null, so a target bound to it must be nullable. When two rows tie on the greatest `order`, which of their `value`s is returned SHALL be
unspecified — `order` must be genuinely discriminating for the result to be deterministic, and a
caller needing determinism must order by a column that is unique within the group. `distinct` SHALL
NOT be accepted for `arg_max`: deduplicating values would detach a `value` from the `order` that
selects it. The `order` operand may not be a **projection-only** field **on the SQL surface**, where
that classification already applies to every function's operands (see
[sql-query-translation](../sql-query-translation/spec.md)); the DSL surface applies no operand-type
restriction to `arg_max` beyond those it applies to `min` and `max`. The **`value`** operand is the
one exception, and is stated by "Array-valued aggregates over an array field": an `array` there is
legal on both surfaces, because "the latest roles of this user" is a question the result type can
carry. Apart from that carve-out `arg_max` names no operand types of its own, deliberately: it
inherits whatever the projection-only set holds, so a type added to that set later covers this
function without a spec edit. `arg_max`'s rendering emits the
native ClickHouse function name directly and is therefore NOT subject to the experimental-dialect
caveat stated above for the ordered-set aggregates and `width_bucket`.

An `arg_max` result is **not re-aggregatable**: unlike a `sum` or a `count`, it cannot be recomputed
from per-bucket results, because the winning row of the union is not derivable from the winning rows of
the parts without their `order` values. It shares this with `min`/`max` and a distinct count, and it is
the reason a materialized `arg_max` would need its own review before being combined across stored
rows.

For `percentile_cont`/`percentile_disc`, `fraction` SHALL be a decimal literal in the closed interval
`[0, 1]` and `column` SHALL be any resolvable field expression. The ClickHouse rendering of the
ordered-set aggregates and `width_bucket` is produced by jOOQ's experimental ClickHouse dialect; it
is verified end-to-end against a live ClickHouse by the Flight SQL integration tests
(`FlightSqlIntegrationTest.provisionalRenderingsExecuteOnRealClickHouse`), which execute
`percentile_cont`, `percentile_disc`, `width_bucket`, `date_bin`, and `date_trunc` through the shared
pipeline and assert correct values.

For `date_bin`, `amount` SHALL be a positive integer literal, `unit` SHALL be a string literal from
the closed set {`second`, `minute`, `hour`, `day`, `week`} (case-insensitive), and `timestamp` SHALL
be any resolvable field expression. `date_bin` SHALL truncate the timestamp down to the start of the
fixed-width interval `amount × unit`, aligned to a fixed epoch origin so bucket boundaries fall on
clean `:00`-second marks (e.g. a 5-minute bucket maps `12:03` to `12:00`). It SHALL render to native
ClickHouse `toStartOfInterval(timestamp, INTERVAL amount unit, 'UTC')`; this rendering is deterministic
(the native function name is emitted directly) and is NOT subject to the experimental-dialect caveat
above. Variable-width calendar units (month, quarter, year) SHALL NOT be accepted by `date_bin`; they
are `date_trunc`'s subject.

For `date_trunc`, `unit` SHALL be a string literal from the closed set {`second`, `minute`, `hour`,
`day`, `week`, `month`, `quarter`, `year`} (case-insensitive) and `timestamp` SHALL be any resolvable
field expression. `date_trunc` SHALL truncate the timestamp down to the start of the calendar period
named by `unit` — a `month` truncates to midnight on the 1st, a `week` to Monday (ISO), a `quarter` to
the first day of the quarter. It SHALL render to native ClickHouse
`toStartOfInterval(timestamp, INTERVAL 1 unit, 'UTC')`, deterministic for the same reason `date_bin`'s
rendering is, and it takes **no** amount argument: a period of `n` calendar months has no fixed width, so
there is no epoch-aligned grid for a multiplier to step along. `date_trunc` and `date_bin` overlap on the
fixed-width units and agree there by construction, both rendering the same `toStartOfInterval` call with
an amount of 1; `date_bin` remains the function that accepts a multiplier, and `date_trunc` the one that
accepts calendar units.

The alignment zone SHALL be **UTC** for both functions, pinned explicitly rather than inherited from the
operand's declared type — a ClickHouse `DateTime64` that declares no zone resolves to the server's, which
is deployment configuration, so the same query would bucket differently on two instances and the
divergence would be invisible in the output (both alignments render a bucket start of `00:00:00`). UTC
matches the read path, which converts a zoneless timestamp to epoch milliseconds at UTC. A caller-chosen
bucket zone is not offered; adding one would be an additive further argument, not a change to this
default.

The four **path-addressable** `json_extract_*` functions — `json_extract_string`,
`json_extract_long`, `json_extract_double`, `json_extract_bool` — SHALL extract a typed scalar from a
JSON-valued expression (`json_extract_string_ci` and `json_extract_array` are specified above and are
not governed by the paragraphs below except where they name them). The
first argument SHALL be either any resolvable expression yielding JSON **text** (typically a
`string` field) or an **`object`-typed field** — the two SHALL be interchangeable, yielding the same
value for the same document; each subsequent argument SHALL be a **string literal** naming one level
of the path, so `json_extract_long(request_body, 'usage', 'total_tokens')` reads a nested field. At
least two arguments SHALL be supplied. Path arguments SHALL be literals, never computed expressions,
so the accessed path is statically known. A path key SHALL NOT contain a **backslash**: over an
`object` field the key is emitted as a quoted identifier segment, and ClickHouse's identifier lexer
honours a backslash inside a double-quoted identifier as an escape that the quoting does not
neutralize. A path key SHALL NOT be **empty** either: an empty identifier segment is dropped rather
than rendered, so the call would address the enclosing path — the whole document for a single-key
call — instead of the key asked for. Such a key SHALL be rejected with HTTP 400 **whichever form the
JSON argument takes** — refusing it on the text form too, where it would be safe, is what keeps the
two forms interchangeable; a member name that is empty or contains a backslash is simply not
addressable through this DSL.
Where the JSON argument yields JSON **text**, each function SHALL render to its native ClickHouse
counterpart — `JSONExtractString`, `JSONExtractInt`, `JSONExtractFloat`, `JSONExtractBool`
respectively — emitted by name, so the rendering is deterministic and not subject to the
experimental-dialect caveat. In that form `json_extract_bool` SHALL additionally wrap its call as
`toBool(JSONExtractBool(…))`, because `JSONExtractBool` yields ClickHouse `UInt8` (`1`/`0`); without
the wrap the advertised `boolean` return type would serialize as a number. (The `object` form renders
differently — see the paragraph below — but SHALL return the same values.) Each function SHALL have a
**fixed** result type independent of its operands, per the table above, whichever form the JSON
argument takes.

Where the JSON argument is an **`object` field**, the functions SHALL render as native JSON
**subcolumn access** over the declared path rather than as the text accessors, because the text
accessors reject a ClickHouse `JSON` operand outright (`ILLEGAL_TYPE_OF_ARGUMENT`) and because
subcolumn access reads only the addressed path instead of the whole document. The path keys SHALL be
emitted as **quoted identifier segments**, never spliced into SQL as text, so a key containing quote
or statement-separator characters is treated as a literal key name (a backslash-bearing or empty key
never reaches the rendering — it is rejected above). The rendering SHALL be internal:
no caller-visible function, spelling, or option selects it, and the choice SHALL follow only from the
argument field's declared type.

This overload SHALL be **observationally indistinguishable** from the text form. For the same
document, `json_extract_*` over an `object` field and over a `string` field SHALL return equal values
in every case, specifically including: a present value; an absent path; a member stored as an
explicit JSON `null`; and a **type mismatch** between the requested function and the stored member
(e.g. `json_extract_string` over a member stored as a number SHALL yield that number's text form, not
a null). A rendering that returns null where the text form returns the type default SHALL NOT be
used. Apart from the exception stated below, no caller-visible behavior SHALL depend on whether a
document is physically stored as an `object` column or as JSON text in a `string` column. The
guarantee is over the **values these two renderings compute** from a stored document; it does not
extend to how the native `JSON` type itself chooses to store one — a column type's own storage
properties (which paths it materializes, and with what type) are outside this DSL's control. That
exemption holds across server versions: where two supported ClickHouse versions store the same member
as different physical types, the two renderings MAY agree on one version and disagree on the other, and
that difference SHALL be read as a property of the column type rather than as a breach of this
requirement. The known instance is an integer magnitude above the `Int64` bound, stored as `Float64` on
25.3 and as `UInt64` from 26.3: over an `object` field `json_extract_bool` yields `false` on 25.3 where
the text form yields `true`, and the two agree from 26.3.

**The exception is a member whose value is composite — a nested JSON document or an array — read as
text.** Over a `string` field, `json_extract_string` falls back to the member's raw serialization and
returns the source spelling; a native `JSON` column keeps typed values rather than that spelling, and
the two composite shapes lose it differently:

- A **nested document** is materialized under deeper sub-paths, so the addressed path's own value is
  empty and `json_extract_string` over an `object` field SHALL yield the **empty string** where the
  text form yields the sub-document's text.
- An **array** is materialized as an array value, rendered in ClickHouse's array syntax rather than
  JSON's: a stored `["p","q"]` SHALL read as `['p','q']` over an `object` field against `["p","q"]`
  over a `string` field, `[1,"q"]` as `['1','q']`, `[null,1]` as `[NULL,1]`, and `[{"x":1}]` as
  `['{"x":"1"}']`. The two spellings coincide only where the two syntaxes coincide — an all-integer,
  null-free array such as `[1,2]` — which SHALL NOT be relied on as a guarantee.

Re-serializing a composite member would not close the gap: it is rebuilt from the stored typed paths,
so member order and value spelling differ from the source text. A composite member is therefore **not
addressable as text** through this DSL; its own members SHALL remain addressable by naming them in
the path (`json_extract_long(<object field>, 'obj', 'a')`). The exception SHALL be limited to reading
a composite member as text: the other three functions SHALL return their type default for a composite
member on both sides, as they already do.

`json_extract_long` over an `object` field SHALL reproduce `JSONExtractInt`'s **numeric conversion**
rather than parse only integral text: a member stored as a fractional number SHALL yield that number
truncated toward zero (`1.5` → `1`, `-1.5` → `-1`), and a member stored as a numeric **string** SHALL
be converted the same way (`"1.5"` → `1`, `"1e3"` → `1000`), because the text accessor parses one. A
value whose magnitude does not fit `Int64` SHALL yield `0`, as the text form does, and a member of any
non-numeric type SHALL yield `0`. Reading only wholly integral text is not that conversion and
diverges on every fractional member, so the rendering SHALL fall back to a truncated numeric read.

`json_extract_bool` over an `object` field SHALL reproduce `JSONExtractBool`'s coercion rather than
compare the member's text: a member stored as a **boolean** yields its own value; a member stored as
an **integer** yields `value != 0`; a member of any other stored type — a fractional number (`1.5`
and `2.0` alike), a string (**including the string `"true"`**), an array, a nested document, an
absent path — yields `false`. Comparing the member's rendered text to `'true'` is not that coercion
and diverges in both directions, so the rendering SHALL consult the stored type.

Because a `json_extract_*` call is an ordinary expression in the grammar, an extraction over an
`object` field SHALL be usable wherever any scalar expression is — in `filter` and `having`
comparisons, as a `sort` or `group_by` key, as an aggregate argument, and as a `select` item. The
extraction's result is an ordinary scalar of the function's fixed type and SHALL carry no
`object`-derived restriction of its own.

Widening the JSON argument is **not** the whole of this capability's treatment of an `object` field: a
**bare** `object` field is a comparison operand in its own right, and "Object fields are comparable as
canonical JSON documents" is the single statement of what that means. That requirement governs the
execution path as well as the SQL surfaces, and it governs **comparison operands** only: the document
comparisons it lists SHALL be accepted by `POST /v1/queries/execute`, and every other comparison use of
a bare `object` field — an ordering comparison, a null test, the **right** operand of any comparison —
SHALL be rejected with HTTP 400 and `error` `bad_request` naming the field and the projection-only
restriction, before ClickHouse is contacted. A bare `array` or `map` field SHALL be rejected as a
comparison operand on the same terms. The SQL surfaces (see
[sql-query-translation](../sql-query-translation/spec.md) and
[query-translation-endpoint](../query-translation-endpoint/spec.md)) enforce the same **comparison**
classification rather than owning it, so a comparison legal on one surface is legal on all three.

How a bare `object` field behaves in any **non-comparison** position on the execution path is left
exactly as it is, neither newly accepted nor newly rejected: a `sort` or `group_by` key, a `distinct`
projection, and an argument to a function outside the `json_extract_*` carve-out remain restrictions the
SQL surfaces enforce and execution does not — the split the shipped "Query-level distinct projection"
requirement already states over `distinct`.

An `object` field that is `heavy` SHALL be accepted as a `json_extract_*` argument: heaviness governs
only wildcard expansion (the column is not projected implicitly), and an extraction transfers a
scalar rather than the document. A `sensitive` `object` field SHALL remain unresolvable for a caller
without `TABLE_SENSITIVE` on the owning table, failing as an unknown field exactly as it does in a
projection — extraction
grants no additional reach.

Consistent with ClickHouse's `JSONExtract*` semantics, an absent path, a null value, or unparseable
JSON SHALL yield the extracting type's default (empty string, `0`, `0.0`, `false`) rather than an
error; these functions are total by construction, over `object` and text sources alike. Callers that
must distinguish "absent" from "default" SHALL do so with an explicit filter on the underlying
document.

The `json_extract_*` functions SHALL be deterministic and SHALL grant no additional data reach: they
read only columns the caller can already resolve, and a `sensitive` column stays hidden from a
non-entitled caller by the existing schema-level filtering.

#### Scenario: Catalog function resolves

- **WHEN** a query uses a function listed in the catalog with a matching arity
- **THEN** the function is accepted and translated

#### Scenario: Non-catalog function is rejected

- **WHEN** a query uses a function name absent from the catalog
- **THEN** the query is rejected with HTTP 400

#### Scenario: Out-of-range percentile fraction is rejected

- **WHEN** a query calls `percentile_cont`/`percentile_disc` with a `fraction` outside `[0, 1]`, with
  a non-literal `fraction`, or with other than two arguments
- **THEN** the query is rejected with HTTP 400

#### Scenario: date_bin buckets a timestamp to the interval start

- **WHEN** a query selects `date_bin(5, 'minute', request_time)` aliased and groups by that alias
- **THEN** each row's timestamp is truncated to the start of its 5-minute bucket aligned to the epoch
  origin (a value at `12:03` yields `12:00`), rendering to
  `toStartOfInterval(request_time, INTERVAL 5 MINUTE, 'UTC')`

#### Scenario: date_bin alignment does not depend on the server's time zone

- **WHEN** a query calls `date_bin` over a timestamp column that declares no time zone
- **THEN** the rendered `toStartOfInterval` carries an explicit `'UTC'`, so buckets align at UTC boundaries
  rather than at boundaries in the ClickHouse server's configured zone

#### Scenario: date_bin with an invalid unit is rejected

- **WHEN** a query calls `date_bin` with a `unit` outside {`second`,`minute`,`hour`,`day`,`week`}
  (e.g. `month`)
- **THEN** the query is rejected with HTTP 400

#### Scenario: date_bin with wrong arity or non-positive amount is rejected

- **WHEN** a query calls `date_bin` with other than three arguments, a non-integer `amount`, or an
  `amount` that is zero or negative
- **THEN** the query is rejected with HTTP 400

#### Scenario: date_trunc buckets a timestamp to the calendar month

- **WHEN** a query selects `date_trunc('month', request_time)` aliased and groups by that alias
- **THEN** each row's timestamp is truncated to midnight on the first of its month, rendering to
  `toStartOfInterval(request_time, INTERVAL 1 MONTH, 'UTC')`, and the buckets are true calendar months
  rather than a fixed number of days

#### Scenario: date_trunc alignment does not depend on the server's time zone

- **WHEN** a query calls `date_trunc` over a timestamp column that declares no time zone
- **THEN** the rendered `toStartOfInterval` carries an explicit `'UTC'`

#### Scenario: date_trunc with an invalid unit or wrong arity is rejected

- **WHEN** a query calls `date_trunc` with a `unit` outside the closed set, with a non-literal `unit`, or
  with other than two arguments
- **THEN** the query is rejected with HTTP 400

#### Scenario: json_extract reads a top-level field

- **WHEN** a query selects `json_extract_string(request_body, 'model')`
- **THEN** the expression is accepted, renders to `JSONExtractString(request_body, 'model')`, and the
  result column is typed as text

#### Scenario: json_extract reads a nested field

- **WHEN** a query selects `json_extract_long(request_body, 'usage', 'total_tokens')`
- **THEN** the expression is accepted and each path key is passed through as a literal path segment

#### Scenario: json_extract_bool serializes as a boolean

- **WHEN** a query selects `json_extract_bool(request_body, 'stream')` over a body where that field is
  `true`
- **THEN** the response value is the JSON boolean `true`, not the numeric `1`

#### Scenario: Every boolean-returning scalar reads back as a boolean

- **WHEN** a row-mode query projects `not_empty`, `starts_with`, `contains`, `equals`, `map_contains`
  and `array_has` in one select
- **THEN** every value is the JSON boolean `true` or `false`, never the number `1` or `0` — one
  advertised return type, one spelling

#### Scenario: json_extract with a computed path is rejected

- **WHEN** a query calls a `json_extract_*` function with a non-literal path argument, or with fewer
  than two arguments
- **THEN** the query is rejected with HTTP 400

#### Scenario: json_extract over a missing field yields the type default

- **WHEN** `json_extract_long(request_body, 'absent_field')` is evaluated over a row whose body lacks
  that field or is not valid JSON
- **THEN** the value is `0` and neither the row nor the query fails

#### Scenario: json_extract reads a top-level field of an object column

- **WHEN** a query selects `json_extract_string(<object field>, 'model')` over a row whose document is
  `{"model": "gpt-4o", ...}`
- **THEN** the value is the string `gpt-4o` — identical to what the same call yields over a `string`
  column holding the same document as text

#### Scenario: json_extract reads a nested path of an object column

- **WHEN** a query selects `json_extract_long(<object field>, 'usage', 'total_tokens')` over a row
  whose document nests that path
- **THEN** the value is the nested number, typed `long`

#### Scenario: All four extractors work over an object column

- **WHEN** a query selects `json_extract_string`, `json_extract_long`, `json_extract_double`, and
  `json_extract_bool` over the same `object` field
- **THEN** each returns its declared fixed type, with `json_extract_bool` serializing as a JSON
  boolean rather than a number

#### Scenario: Object and text sources agree on the same document

- **WHEN** the same document is stored both in an `object` column and as JSON text in a `string`
  column, and each of the four `json_extract_*` functions is applied to both with the same path
- **THEN** the two sides return equal values for every function — for a present value, an absent
  path, a member stored as an explicit JSON `null`, and a requested type that does not match the
  stored member's type

#### Scenario: Extraction coerces a mismatched member type

- **WHEN** `json_extract_string(<object field>, 'n')` is evaluated over a document whose `n` member
  is the number `42`
- **THEN** the value is the string `42` — the same as the text form yields — rather than null or the
  empty string

#### Scenario: A path key containing SQL metacharacters is inert

- **WHEN** a `json_extract_*` call over an `object` field supplies a literal path key containing
  quote or statement-separator characters
- **THEN** the key is treated as a literal member name — the request succeeds, yields the type
  default because no such member exists, and no additional statement is executed

#### Scenario: A path key containing a backslash is rejected

- **WHEN** a `json_extract_*` call supplies a literal path key containing a backslash — over an
  `object` field or over a `string` field holding JSON text
- **THEN** the query is rejected with HTTP 400 naming the restriction, and no statement reaches
  ClickHouse

#### Scenario: An empty path key is rejected

- **WHEN** a `json_extract_*` call supplies an empty string as a literal path key — alone or among
  other keys, over an `object` field or over a `string` field holding JSON text
- **THEN** the query is rejected with HTTP 400 naming the restriction, and no statement reaches
  ClickHouse — rather than the call addressing the enclosing path

#### Scenario: json_extract_bool agrees on a member that is not a boolean

- **WHEN** `json_extract_bool` is applied over both an `object` column and a `string` column holding
  the same document to a member stored as the number `42`, to a member stored as the string `"true"`,
  and to a member stored as the number `1.5`
- **THEN** both sides return `true`, `false`, and `false` respectively

#### Scenario: json_extract_long agrees on a member that is not an integer

- **WHEN** `json_extract_long` is applied over both an `object` column and a `string` column holding
  the same document to a member stored as the number `1.5` and to a member stored as the string
  `"1.5"`
- **THEN** both sides return `1` for each — the value truncated toward zero, not `0`

#### Scenario: A composite member is not readable as text

- **WHEN** `json_extract_string` is applied over both an `object` column and a `string` column holding
  the same document `{"obj":{"a":1},"arr":["p","q"]}` to the `obj` member and to the `arr` member
- **THEN** for `obj` the `string` column yields the sub-document's text and the `object` column yields
  the empty string, and for `arr` the `string` column yields `["p","q"]` while the `object` column
  yields `['p','q']` — the documented exception in both of its shapes — while
  `json_extract_long(…, 'obj', 'a')` still yields `1` on both sides

#### Scenario: Filtering on a path inside an object column

- **WHEN** a row-mode query filters `json_extract_string(<object field>, 'model') = 'gpt-4o'`
- **THEN** the request succeeds and only rows whose document carries that path value are returned

#### Scenario: Grouping and aggregating on a path inside an object column

- **WHEN** an aggregate-mode query groups by an aliased `json_extract_string(<object field>, 'model')`
  select entry and aggregates `count()`
- **THEN** the request succeeds and returns one row per distinct extracted value with its count

#### Scenario: json_extract over an object column is total

- **WHEN** `json_extract_long(<object field>, 'absent_field')` is evaluated over a row whose document
  lacks that path
- **THEN** the value is `0` and neither the row nor the query fails

#### Scenario: The extracted value carries no object restriction

- **WHEN** a query sorts on, groups by, applies `distinct` to, or aggregates over the alias of a
  projected `json_extract_*` call on an `object` field
- **THEN** the request is accepted and behaves as it would for any other scalar of that type — the
  restriction attaches to the bare `object` field, never to a value extracted from it

#### Scenario: A heavy object field is extractable

- **WHEN** a query calls `json_extract_string` on an `object` field marked `heavy`
- **THEN** the request succeeds — heaviness excludes the column from wildcard expansion only, and the
  extraction returns a scalar

#### Scenario: A sensitive object field grants no extra reach

- **WHEN** a caller without `TABLE_SENSITIVE` on the owning table calls `json_extract_string` on a
  `sensitive` `object` field
- **THEN** the request is rejected because the field does not resolve for that caller, exactly as a
  projection of it would be

#### Scenario: arg_max returns the value of the row with the greatest order

- **WHEN** a client submits an aggregate query grouping `rate_analytics` by `response_id` with
  `arg_max(rate, request_time)`
- **THEN** each group's result carries the `rate` of that response's most recent rate event, not its
  greatest `rate`

#### Scenario: arg_max is rejected with a distinct quantifier

- **WHEN** a client submits `arg_max` with `distinct` set
- **THEN** the request is rejected with HTTP 400 naming `arg_max` and the unsupported quantifier

#### Scenario: arg_max ignores rows with a null order

- **WHEN** some rows of a group have a null `order` operand
- **THEN** those rows do not compete, and the result comes from the greatest `order` among the rest

#### Scenario: arg_max skips a latest row whose value is null

- **WHEN** the row of a group bearing the greatest `order` has a null `value`
- **THEN** that row is skipped and the result is the value of the next-latest row that has one, so the
  result is the latest non-null value rather than the latest row's value

#### Scenario: arg_max yields null when no row of the group qualifies

- **WHEN** every row of a group has a null `order`, or a null `value`, or both
- **THEN** the group's `arg_max` result is null rather than an arbitrary row's value

#### Scenario: arg_max ties on the greatest order do not error

- **WHEN** two rows of a group share the greatest `order` with differing `value`s
- **THEN** the query succeeds and returns one of the tied `value`s, which one being unspecified

#### Scenario: A conditional selects its branch

- **WHEN** a query selects `if(not_empty(project_id), project_id, json_extract_string(jwt_claims, 'user_id'))`
- **THEN** rows with a non-empty `project_id` yield that value and rows with an empty or null
  `project_id` yield the extracted claim

#### Scenario: Conditionals nest into a multi-branch classification

- **WHEN** a query selects `if(not_empty(a), 'opencode', if(starts_with(lower(ua), 'claude-cli'), 'claudecode', 'other'))`
- **THEN** the query is accepted and each row resolves to exactly one of the three literals

#### Scenario: A non-boolean condition is rejected

- **WHEN** a query selects `if(length(x), 'a', 'b')`, whose condition is an integer expression
- **THEN** the request is rejected with HTTP 400 naming the condition operand as non-boolean

#### Scenario: A comparison is still not an expression

- **WHEN** a query selects an expression whose condition is written as `length(x) > 0`
- **THEN** the request is rejected with HTTP 400 — comparison operators remain filter-only, and the
  message names `not_empty` as the expression-position form

#### Scenario: Mismatched branch types are rejected

- **WHEN** a query selects `if(not_empty(x), 'text', 1)`
- **THEN** the request is rejected with HTTP 400 naming the incompatible branch types

#### Scenario: Numeric branches widen

- **WHEN** a query selects `if(not_empty(x), <integer expression>, <decimal expression>)`
- **THEN** the query is accepted and the result column is decimal

#### Scenario: A timestamp branch does not unify with a long branch

- **WHEN** a query selects `if(not_empty(x), <timestamp column>, <long column>)`
- **THEN** the query is rejected with HTTP 400 naming both types, rather than accepted and answered with a
  ClickHouse error — the two are distinct types even where a rendering layer represents them alike

#### Scenario: A timestamp column and a timestamp literal unify

- **WHEN** a query selects `if(not_empty(x), <timestamp column>, <timestamp literal>)`
- **THEN** the query is accepted and the result column is a timestamp

#### Scenario: A null literal branch is rejected

- **WHEN** a query selects `if(not_empty(x), a, <null literal>)`
- **THEN** the query is rejected with HTTP 400, and the message says a null test belongs in `filter` as an
  `eq`/`ne` comparison against a null literal

#### Scenario: A non-scalar branch is rejected

- **WHEN** a query selects `if(not_empty(x), <object column>, <object column>)`, or the same with `array`
  or `map` columns
- **THEN** the query is rejected with HTTP 400 naming the projection-only restriction, so the conditional
  cannot become a back door to a non-scalar result

#### Scenario: not_empty is false for null and for empty

- **WHEN** `not_empty` is evaluated over a null cell and over a cell holding the empty string
- **THEN** both yield `false`, and neither yields null

#### Scenario: A non-text operand to a text predicate is rejected

- **WHEN** a query selects `not_empty(<long column>)`, or `contains(<object column>, 'x')`
- **THEN** the query is rejected with HTTP 400 naming the required text operand, on the DSL surface and the
  SQL surface alike

#### Scenario: equals requires comparable operands

- **WHEN** a query selects `equals(<string column>, 1)`
- **THEN** the query is rejected with HTTP 400 naming both types

#### Scenario: equals against a null literal is rejected

- **WHEN** a query selects `equals(a, <null literal>)`
- **THEN** the query is rejected with HTTP 400 rather than accepted as an expression that is false for
  every row, and the message names `filter`'s `eq`/`ne` as where a null test belongs

#### Scenario: Predicate scalars are case-sensitive

- **WHEN** a query selects `starts_with(user_agent, 'Claude-CLI')` over a row whose user agent is
  `claude-cli/2.1.170`
- **THEN** the result is `false`, and the same test wrapped in `lower` on both operands is `true`

#### Scenario: Case-insensitive extraction matches the key in any case

- **WHEN** a query selects `json_extract_string_ci(request_tags, 'x-claude-code-session-id')` over
  rows whose documents spell the key `x-claude-code-session-id` and `X-Claude-Code-Session-Id`
- **THEN** both rows return their session id

#### Scenario: Case-insensitive extraction does not alter the value

- **WHEN** `json_extract_string_ci` resolves a member whose value carries upper-case characters
- **THEN** the value is returned verbatim, with its case unchanged

#### Scenario: Case-insensitive extraction rejects a multi-segment path

- **WHEN** a query calls `json_extract_string_ci` with more than two arguments
- **THEN** the request is rejected with HTTP 400 stating that case-insensitive matching applies to a
  single top-level key

#### Scenario: Array extraction yields an empty array when absent

- **WHEN** `json_extract_array(jwt_claims, 'roles')` is evaluated over a document with no `roles`
  member, and over one whose `roles` member is a string rather than an array
- **THEN** both yield an empty array rather than null or an error

#### Scenario: An array-valued extraction is projection-only

- **WHEN** a query uses `json_extract_array(jwt_claims, 'roles')` as an operand of another function or
  as a filter comparand
- **THEN** the request is rejected with HTTP 400 under the same projection-only restriction that
  applies to `group_uniq_array` — exactly the positions that restriction already covers on the
  execution path, no wider (a sort or group key naming a projected array output is a **SQL-surface**
  rejection, see [sql-query-translation](../sql-query-translation/spec.md), and is unchanged here)

#### Scenario: Case-insensitive extraction reads an object column

- **WHEN** `json_extract_string_ci(request_tags, 'x-claude-code-session-id')` is evaluated over an
  `object`-typed column whose document spells the key `X-Claude-Code-Session-Id`
- **THEN** the value is returned, identical to what the same call yields over a `string` column
  holding the same document as text — and specifically NOT the empty string, which is what a native
  JSON *text* accessor over a `JSON` operand would silently produce

#### Scenario: Array extraction reads an object column through the addressed subcolumn

- **WHEN** `json_extract_array(jwt_claims, 'roles')` is evaluated over an `object`-typed column whose
  document carries that array, and over one whose document lacks it
- **THEN** the values are the array and the empty array respectively, and neither the row nor the
  query fails with a ClickHouse type error
- **AND** the statement reads the `roles` subcolumn rather than the whole column's serialized text
- **AND** an array of **text** elements returns those elements, not the empty array

#### Scenario: Aggregate ratio divides in projection

- **WHEN** an aggregate-mode query selects `div(sum(cached_prompt_tokens), sum(prompt_tokens))` grouped
  by `deployment`
- **THEN** the request succeeds and each row carries the ratio as a numeric value

#### Scenario: Division by a zero denominator yields null, not an error

- **WHEN** an aggregate-mode query selects `div(sum(a), sum(b))` and one group's `sum(b)` is `0`
- **THEN** the request succeeds, that group's cell is null, and every other group carries its computed
  ratio

#### Scenario: A null-on-zero quotient converts into a decimal column

- **WHEN** `div(count(), sum(total_tokens))` is written into a `Nullable(Decimal(38, 12))` column and one
  group's `sum(total_tokens)` is `0`
- **THEN** that group's value is NULL and the conversion does not fail with `DECIMAL_OVERFLOW`

#### Scenario: Division by a zero integer denominator yields null

- **WHEN** `div` is applied to two `long`-typed operands and the divisor is `0`
- **THEN** the result is null and the query does not fail with a division error

#### Scenario: Integer division is not truncated

- **WHEN** `div` is applied to two `long`-typed operands whose quotient is not an integer
- **THEN** the result is a decimal carrying the fractional part, not a truncated integer

#### Scenario: Arithmetic is accepted in HAVING

- **WHEN** an aggregate-mode query filters in `having` on `div(sum(a), sum(b))` against a numeric
  literal
- **THEN** the request succeeds and only groups satisfying the comparison are returned

#### Scenario: Rounding to a given number of places

- **WHEN** `round(div(sum(total_tokens), count()), 2)` is evaluated
- **THEN** the result carries at most two decimal places

#### Scenario: Modulo returns a remainder and keeps integer type

- **WHEN** `modulo(a, 3)` is evaluated over `long`-typed values
- **THEN** each result is the integer remainder, not a decimal

#### Scenario: Modulo by zero yields null, not an error

- **WHEN** `modulo(a, b)` is evaluated over a row whose `b` is `0`, and over one whose `b` is null
- **THEN** both yield null and the query does not fail

#### Scenario: Modulo takes the sign of its left operand

- **WHEN** `modulo(-7, 3)` is evaluated
- **THEN** the result is `-1`

#### Scenario: Map key is read and grouped

- **WHEN** an aggregate-mode query selects `map_value(point_attributes, 'model')` as a `group_by` key
  alongside `sum(point_value)`
- **THEN** the request succeeds and returns one row per distinct key value with its sum

#### Scenario: Map key with a dot is matched literally

- **WHEN** `map_value(point_attributes, 'user.email')` is evaluated over a map carrying the flat key
  `user.email`
- **THEN** the stored value is returned, rather than the empty string that a path traversal into a
  nested `user` member would give

#### Scenario: Absent map key yields the empty string

- **WHEN** `map_value(point_attributes, 'model')` is evaluated over a row whose map lacks that key, and
  over a row whose map is empty
- **THEN** both yield the empty string, and neither the row nor the query fails

#### Scenario: Map key presence is distinguished from an empty value

- **WHEN** `map_contains(point_attributes, 'model')` and `map_value(point_attributes, 'model')` are
  evaluated over a row storing that key with an empty value, and over a row lacking the key entirely
- **THEN** `map_contains` is true then false, while `map_value` is the empty string in both — the
  presence test distinguishing what the value read cannot

#### Scenario: Map keys are listed

- **WHEN** `map_keys(point_attributes)` is evaluated over a row whose map carries three entries, and
  over a row whose map is empty
- **THEN** the results are a three-element array of the key names and an empty array respectively

#### Scenario: Map size via the array accessor

- **WHEN** `array_length(map_keys(point_attributes))` is evaluated
- **THEN** the result is the map's entry count as an integer

#### Scenario: Map keys result is projection-only

- **WHEN** a query uses `map_keys(...)` as an operand of a non-accessor expression or as a filter
  comparand
- **THEN** the request is rejected with HTTP 400 under the same projection-only restriction that
  applies to `json_extract_array` — exactly the positions that restriction already covers on the
  execution path, no wider (a `sort` or `group_by` key naming a projected `map_keys` output is a
  **SQL-surface** rejection, see [sql-query-translation](../sql-query-translation/spec.md), and is
  unchanged here)

#### Scenario: Map field outside an accessor's first argument is still refused

- **WHEN** a query uses a `map` field as `map_value`'s or `map_contains`'s second argument, or as an
  argument of any function that is not one of the three map accessors
- **THEN** the request is rejected with HTTP 400 naming the field and the projection-only restriction.
  A `map` field as a **filter operand** is likewise refused, and on **every** surface — see "Object
  fields are comparable as canonical JSON documents", which governs comparison operands of every
  non-scalar type and admits none for `map`. A `map` field as a `group_by` key or a `sort` key is
  refused by the **SQL surface** only — see "Map fields are projection-only when translating to SQL"
  below, which is where that restriction lives

#### Scenario: Array membership filters rows

- **WHEN** a query filters on `array_has(user_roles, 'admin')`
- **THEN** the request succeeds and only rows whose array carries that element are returned

#### Scenario: Array membership over an empty array is false

- **WHEN** `array_has`, `array_has_any` and `array_has_all` are evaluated over a row whose array is
  empty or null
- **THEN** each yields false, and neither the row nor the query fails

#### Scenario: Array predicate drives a conditional aggregate

- **WHEN** an aggregate-mode query selects
  `sum(if(array_has(user_roles, 'admin'), deployment_price, 0))`
- **THEN** the request succeeds and the sum covers only rows whose array carries that element

#### Scenario: Array predicate with an empty value list is rejected

- **WHEN** a query submits `array_has_any` or `array_has_all` with an empty array as the second operand
- **THEN** the request is rejected with HTTP 400

#### Scenario: Array length counts elements

- **WHEN** `array_length(user_roles)` is evaluated over a row with three elements and over a row whose
  array is empty
- **THEN** the values are `3` and `0` respectively

#### Scenario: Array field is still not a group-by key

- **WHEN** an aggregate-mode query uses an `array` field directly as a `group_by` key and is submitted
  to `POST /v1/queries/translate` or as SQL
- **THEN** the request is rejected with HTTP 400 — the array predicates filter arrays but do not make an
  array groupable. As for every projection-only type, the restriction is the SQL surface's: `POST
  /v1/queries/execute` is unchanged by this capability's array predicates, neither newly accepting nor
  newly rejecting a bare `array` field in that position

#### Scenario: Delimited string splits with trimmed elements

- **WHEN** `split_string(topics, ',')` is evaluated over the value `"git, code, merge"`
- **THEN** the result is a three-element array whose elements carry no surrounding whitespace

#### Scenario: Split of a value without the delimiter is a single element

- **WHEN** `split_string(topics, ',')` is evaluated over a value containing no comma, and over an empty
  value
- **THEN** the results are a one-element array and an empty array respectively

#### Scenario: split_string result is projection-only

- **WHEN** a query uses `split_string(...)` as an operand of another expression that is not an array
  accessor, or as a filter comparand
- **THEN** the request is rejected with HTTP 400 under the same projection-only restriction that
  applies to `json_extract_array`, and on the same terms: a `sort` or `group_by` key naming a projected
  `split_string` output is a **SQL-surface** rejection rather than an execution-path one

#### Scenario: Distinct is refused with a stated reason

- **WHEN** a client submits `min`, `max`, `percentile_cont` or `percentile_disc` with `distinct` set
- **THEN** the request is rejected with HTTP 400 and the message names the function and why the
  quantifier does not apply to it

#### Scenario: Bare object field as a comparison operand outside the extractor carve-out

- **WHEN** a query submitted to `POST /v1/queries/execute` uses a bare `object` field as a **comparison
  operand** rather than as the JSON argument of a `json_extract_*` call
- **THEN** the outcome is the one "Object fields are comparable as canonical JSON documents" states —
  accepted for a document comparison, otherwise rejected with HTTP 400 and `error` `bad_request` naming
  the field and the projection-only restriction, with ClickHouse not contacted. Non-comparison uses — a
  `sort` key, a `group_by` key, a `distinct` projection, a non-extractor function argument — are outside
  this requirement and keep the surface split stated above: the SQL surfaces reject them, execution does
  not

### Requirement: Query validation and allowlist

The system SHALL validate a structured query before and during translation against the entity's
**discovered schema as visible to the caller's role**, rejecting invalid queries with HTTP 400.
Validation SHALL cover: entity resolution (unknown entity rejected), field resolution (every
referenced field must resolve against the entity's flat schema), function resolution against the
closed **Supported function catalog** (including arity and, for `percentile_cont`/`percentile_disc`,
a `fraction` decimal literal in `[0, 1]`), `in` operand shape (an array of value literals),
**comparison operand type** (a non-scalar field — `object`, `array` or `map` — is accepted only where
the "Object fields are comparable as canonical JSON documents" requirement admits it, and the
JSON-literal rule stated there is enforced here), literal
parsing per `value_type`, and pagination governance (offset ≥ 0; cursor pagination rejected; limit
within `[0, 1000]` — a limit above the 1000-row maximum or a negative limit is rejected, never
silently clamped; a limit of `0` or an omitted page applies the default of 100). Because field
resolution runs against the role-visible schema, a column marked `sensitive` is absent from a
caller's model where it is not entitled, so referencing it in any clause (`select`, `filter`,
`group_by`,
`having`, `sort`) — including the empty-projection expansion that selects all source columns — SHALL
be rejected as an unknown field (HTTP 400), indistinguishable from a truly non-existent field. When
security is disabled (`security.mode=none`) or the caller holds `TABLE_SENSITIVE` on the owning
table, sensitive columns are
present and resolve normally. Semantically invalid queries that nonetheless translate SHALL surface
ClickHouse's rejection as HTTP 400 rather than 500. Per-field capability flags, mode-coherence
enforcement, and array element type-homogeneity are NOT enforced in this implementation and remain
available as a future tightening.

Comparison-operand type checking SHALL happen **before** the query is rendered, so a rejected operand
SHALL produce the stable, field-naming validation error rather than a relayed ClickHouse type error,
and ClickHouse SHALL NOT be contacted. This closes the case where a non-scalar operand previously
reached ClickHouse — returning a `Code 43`/`Code 117` type error for most operators, and, for `eq`
against a well-formed JSON literal on an `object` field, silently matching no rows at all.

#### Scenario: Unknown field is rejected

- **WHEN** a query references a field name that does not resolve against the entity's discovered schema
- **THEN** the query is rejected with HTTP 400 before execution

#### Scenario: Sensitive field is unknown to a non-admin caller

- **WHEN** a caller holding no `TABLE_SENSITIVE` on the owning table submits a query referencing a
  `sensitive` column in any
  clause (`select`, `filter`, `group_by`, `having`, or `sort`)
- **THEN** the query is rejected with HTTP 400 as an unknown field, the same as a non-existent field,
  and the response does not disclose that the column exists

#### Scenario: Empty projection excludes sensitive columns for a non-admin

- **WHEN** such a caller submits a row-mode query with no `select` against an
  entity that has sensitive columns
- **THEN** the expanded projection returns only the non-sensitive source columns; sensitive columns
  are neither returned nor referenced

#### Scenario: Unsupported function is rejected

- **WHEN** a query uses a function name outside the Supported function catalog
- **THEN** the query is rejected with HTTP 400

#### Scenario: Cursor pagination is rejected

- **WHEN** a query supplies a cursor-strategy page
- **THEN** the query is rejected with HTTP 400 (cursor pagination is not supported on this path)

#### Scenario: Limit above the maximum is rejected

- **WHEN** a query supplies an offset page with `limit` greater than 1000 (e.g. `50000`)
- **THEN** the query is rejected with HTTP 400 and a message naming the requested limit and the
  1000-row maximum; no rows are returned

#### Scenario: Negative limit is rejected

- **WHEN** a query supplies an offset page with a negative `limit`
- **THEN** the query is rejected with HTTP 400

#### Scenario: Invalid literal is rejected

- **WHEN** a value literal cannot be parsed to its declared `value_type` (e.g. a non-numeric `integer`)
- **THEN** the query is rejected with HTTP 400

#### Scenario: Non-scalar comparison operand is rejected before execution

- **WHEN** a query compares an `array` or `map` field, or applies an ordering comparison or a null test
  to an `object` field
- **THEN** the query is rejected with HTTP 400 naming the field and the projection-only restriction,
  the message carries no ClickHouse internals, and ClickHouse is not contacted

#### Scenario: ClickHouse rejects the query

- **WHEN** a query is well-formed but ClickHouse rejects the rendered SQL (e.g. a type mismatch)
- **THEN** the request is rejected with HTTP 400 and no raw ClickHouse internals are exposed

### Requirement: Read result deduplication (latest-version-wins)

The system SHALL let a user-facing query choose, per entity it reads, between the **final** view of the
data — deduplicated, latest-version-wins, exactly as if a physical merge had completed — and the **raw**
view that the table's parts hold before background merges, with ClickHouse's `FINAL` semantics. For any
table backed by a `ReplacingMergeTree` (an `upsert_by_key` source and every enrichment), a final read
SHALL reflect only the surviving (latest-version) row per sort key regardless of whether ClickHouse has
merged the parts yet; a raw read SHALL return every row the parts hold, not-yet-merged versions included.
User-facing reads are those of `POST /v1/queries/execute`, `POST /v1/queries/execute-csv`,
`POST /v1/queries/execute-sql`, `POST /v1/queries/execute-sql-csv` and the Flight SQL / ADBC endpoint; the
rule SHALL hold identically across them, because they all delegate to the shared execution pipeline.

**The unit is the entity.** A final read of an entity SHALL deduplicate every table of it that collapses
versions — its source when the source is `upsert_by_key`, and every enrichment table the query joins for it
(an enrichment always collapses) — and its separate `count(*)` total-count statement likewise; a raw read
SHALL deduplicate none of them. An entity is **final-capable** when anything it reads collapses versions:
its source is `upsert_by_key`, or it has at least one active enrichment the caller may read. Capability SHALL
be decided at `TABLE_READ` — the bar execution resolves the entity at — on every surface, including translation
(resolved at `TABLE_GET`) and discovery (`TABLE_LIST`), so an enrichment the caller may get but not read
never makes an entity final-capable on one surface and not on another. An `append`
source with an enrichment is therefore final-capable: its final read deduplicates the enrichment and reads
the source's plain `MergeTree` as it is, so the grain join stays 0..1. There is no way to read an entity's source final and one of its
enrichments raw or the reverse, because a caller never names an enrichment join. A composite SQL statement
reads each of its entity references final or raw independently (see
[sql-query-translation](../sql-query-translation/spec.md) "FINAL table modifier").

**What a query asks for.** A structured query SHALL carry an optional top-level boolean `final`:

- `true` — read the entity final;
- `false` — read it raw;
- absent or `null` — read it final when it is final-capable and raw otherwise, so a query that never
  mentions `final` over an `append` source with no enrichment stays valid.

A SQL entity reference is read final when it carries the `FINAL` modifier and raw when it does not.

**`FINAL` is legal only where something can honour it.** An explicit `"final": true`, or a SQL `FINAL`,
on an entity that is not final-capable — an `append` source (a plain `MergeTree`, which ClickHouse refuses
`FINAL` on with `ILLEGAL_FINAL`) with no enrichment — SHALL be rejected with HTTP 400, `error` =
`illegal_final`, and a message naming the entity and stating that nothing it reads collapses versions; no
statement is executed. `FINAL` SHALL never be rendered on a plain `MergeTree` table. The same rejection SHALL
apply to `POST /v1/queries/translate` and `POST /v1/queries/translate-sql`, so a query that translates is
one that executes. Over the Flight SQL endpoint the same condition SHALL fail with Flight status
`INVALID_ARGUMENT` carrying the same message.

**The migration flag.** While `query.force-final` (`FORCE_FINAL_QUERIES`, default `true`) is on, every
user-facing read SHALL read every final-capable entity final, whatever the query says — `"final": false`
and a SQL reference without `FINAL` included — which is the always-on deduplication the system shipped
before `final` existed. An entity that is not final-capable is read raw under the flag, since it has nothing
to collapse. The flag SHALL NOT suspend the `illegal_final` rejection. Turning the flag off
SHALL change the result of exactly two kinds of read: a SQL reference without `FINAL` to a final-capable
entity, and a structured query sending `"final": false` for one. The flag governs what
is **executed**; `POST /v1/queries/translate` and `translate-sql` SHALL translate what the query says, not
what the flag would force.

**Internal reads are always final.** The pipeline's own reads — the enrichment scan, group fetch, the
Class-1 materialize and aggregate-refresh `INSERT … SELECT`, and the aggregate dry-run preview — SHALL keep
deduplicating every table they read, whatever `query.force-final` says and with no opt-out, through the
query-level `final = 1` execution setting (a no-op on an `append` source's plain `MergeTree`). Their
exactly-once and window guarantees rest on that and are not a caller's choice.

**Deduplication SHALL stay inside a partition wherever the catalog can vouch for it.** Under `FINAL`
ClickHouse assumes that two versions of one key may sit in different partitions, so it merges across every
partition of a version-collapsing table before it can apply a filter — and from ClickHouse 26.3
(`defer_partition_pruning_after_final`, default on) it stops using the table's partition and MinMax indexes
altogether for any table whose partition column is not part of its sorting key. For such a table a one-row
lookup reads a granule from every part of the table. The system SHALL therefore attach ClickHouse's
`do_not_merge_across_partitions_select_final = 1` to a statement whenever **every entity it reads final**
is *partition-local* — meaning that for the entity's source and each of its active enrichments, all
versions of a key are guaranteed to land in one partition. A table is partition-local when any of the
following holds:

- it declares no partition column;
- its partition column is one of its ordering-key columns (the key itself fixes the partition);
- it is an enrichment whose partition column is aligned with its source's — the same column name on both
  sides, carrying the same value for a grain key (the catalog's partition-alignment claim) — and that
  source is itself partition-local.

An `append` source (plain `MergeTree`) collapses nothing, so it never disqualifies an entity. The rule is
evaluated per entity from the catalog, never inferred from data, and a single non-qualifying table
withholds the setting for every read of that entity: the read then runs merging across partitions —
slower, never wrong. An entity read raw is not consulted, because the setting affects only tables read
under `FINAL`; a statement that reads no entity final SHALL carry the setting not at all. A composite
statement SHALL carry the setting only when every entity it reads final qualifies. The setting SHALL be
decided the same way on every statement the pipeline issues — row, count, scan, composite and the Class-1
materialize and aggregate-refresh `INSERT … SELECT` — where a pipeline read, being final throughout, is
decided over every entity it reads.

This is a cost rule, not a result rule: for a partition-local table the two modes return identical rows,
because a key whose versions all live in one partition is deduplicated the same way whether or not other
partitions are consulted. What the rule rests on is the immutability of the partition column per key, and
the catalog SHALL keep that an invariant of its own declarations: a source's partition column inside its
ordering key cannot change without changing the key, and an enrichment populated from the same record as
its source carries the source's partition value for the grain key by construction.

**The catalog SHALL describe the engine.** Whether an entity may be read final, what an absent `final`
resolves to, and whether its source takes the `FINAL` modifier are decided from the declared `write` facets
of its tables, so each facet SHALL agree with its engine (see
[table-management](../table-management/spec.md) "Table write discipline"). A source recorded `append` that is
physically a `ReplacingMergeTree` would never take `FINAL`, returning unmerged versions the engine means to
collapse; and one recorded `upsert_by_key` that is physically a plain `MergeTree` would be rendered `FINAL`
and fail in ClickHouse with `ILLEGAL_FINAL`, surfacing as the ClickHouse query rejection (HTTP 400
`bad_request`). The same holds for an enrichment registered over a hand-written plain `MergeTree`: its kind
says it collapses, so a final read that joins it renders `FINAL` on it and ClickHouse refuses the statement —
where the former query-level `final = 1` setting silently did nothing on that table. The internal reads, which deduplicate through the execution
setting, resolve against the table's actual engine and stay correct either way; the partition-local
decision reads the declared facet too, which is why the facet's agreement with the engine is a requirement
and not a courtesy.

#### Scenario: Source with unmerged duplicate rows returns one row per key

- **WHEN** a `ReplacingMergeTree` source (e.g. `dial_usage_log`) holds multiple not-yet-merged versions
  sharing its sort key and a row-mode query that is read final selects them
- **THEN** the response returns only the latest version per sort key, not the raw unmerged duplicates

#### Scenario: A raw read returns the unmerged versions

- **WHEN** `query.force-final` is off and a structured query with `"final": false` (or a SQL statement
  without `FINAL`) selects a key of `dial_usage_log` that has two not-yet-merged versions
- **THEN** the response returns both versions, as the same statement without `FINAL` returns them in
  ClickHouse

#### Scenario: An absent final reads a collapsing source final

- **WHEN** `query.force-final` is off and a structured query over `dial_usage_log` omits `final`
- **THEN** the query is read final and returns one row per sort key

#### Scenario: Total count reflects deduplicated rows

- **WHEN** a row-mode offset query with `include_total` that is read final runs against a source that has
  unmerged duplicate rows
- **THEN** the returned `totalCount` counts each sort key once (the deduplicated count), matching the
  deduplicated row set rather than the raw part-level row count

#### Scenario: Re-patched enrichment does not fan out source rows

- **WHEN** a query that is read final references an enrichment column and that enrichment has multiple
  unmerged versions for a grain key that matches source rows
- **THEN** each matching source row appears exactly once, joined to the latest enrichment version,
  because the enrichment side is deduplicated with the source

#### Scenario: Recomputed upsert-by-key source row reads as the latest value

- **WHEN** an `upsert_by_key` source receives a second write for a key it already holds (a rollup bucket
  recomputed with a new value), the parts have not merged, and a query that is read final selects it
- **THEN** the query returns one row for that key carrying the later write's values, so a re-written key
  reads as a replacement rather than as two rows

#### Scenario: Append source is unaffected

- **WHEN** a query that omits `final` runs against an `append` source (a plain `MergeTree`, no
  version-collapsing engine) with no enrichment, with `query.force-final` on or off
- **THEN** the source is read raw, no `FINAL` is applied, and every row the source holds is returned

#### Scenario: FINAL on an append source is rejected

- **WHEN** a structured query sends `"final": true` for an `append` source with no enrichment, or a SQL
  statement writes `FINAL` after one, with `query.force-final` on or off
- **THEN** the request is rejected with HTTP 400, `error` = `illegal_final`, naming the entity, and no
  statement is executed

#### Scenario: An append source reads final through its enrichments

- **WHEN** an `append` source has an enrichment holding two unmerged versions for a grain key, and a query
  that omits `final` (or says `"final": true`, or writes `FINAL`) projects the enrichment column
- **THEN** the statement renders `FINAL` on the enrichment and not on the source, and the source row is
  returned once with the latest enrichment value; with `"final": false` and `query.force-final` off it is
  returned once per enrichment version

#### Scenario: An enrichment the caller cannot read does not make the entity final-capable

- **WHEN** a caller holds `list` and `get` but not `read` on the only enrichment of an `append` source it may
  read, and sends `"final": true` (or writes `FINAL`) to execute or to translate
- **THEN** both are rejected with `illegal_final`, and a query omitting `final` is read and translated raw

#### Scenario: The flag forces final reads

- **WHEN** `query.force-final` is on and a structured query with `"final": false`, or a SQL statement
  without `FINAL`, reads a key of `dial_usage_log` that has two not-yet-merged versions
- **THEN** the response returns one row for the key, as it did before `final` existed

#### Scenario: Internal reads stay final with the flag off

- **WHEN** `query.force-final` is off and a Class-1 materialize, an aggregate refresh or an enrichment scan
  reads a source key that has two not-yet-merged versions
- **THEN** the read sees one version of the key, the latest, exactly as with the flag on

#### Scenario: The declared discipline predicts what a read returns

- **WHEN** a caller reads a source's `write` facet and then queries that source's own columns omitting `final`
- **THEN** an `upsert_by_key` source returns one row per ordering key and an `append` source returns every
  row, so the declaration accounts for any difference between an ADAS read and a raw `SELECT`

#### Scenario: Partition-local entity reads deduplicate within partitions

- **WHEN** a query reads final an entity whose source and every active enrichment are partition-local (for
  example `dial_usage_log`, whose partition column is in its ordering key, together with
  `dial_usage_log_payload`, an enrichment partitioned by the same `request_time` its source partitions by,
  and unpartitioned enrichments)
- **THEN** every statement the read issues carries `do_not_merge_across_partitions_select_final = 1`, and a
  single-row lookup of a heavy enrichment column prunes to the partition that holds the row instead of
  merging every partition of the enrichment

#### Scenario: Partition-local reads return the same rows as cross-partition reads

- **WHEN** a partition holds several not-yet-merged versions of one key and a final read of a
  partition-local entity selects that key
- **THEN** the read returns exactly one row for the key carrying the latest version, a LEFT-JOINed
  enrichment never fans the source out, and the result equals what the same statement returns without the
  partition-local setting

#### Scenario: A non-qualifying table withholds the partition-local setting for its entity

- **WHEN** an entity's source or one of its active enrichments is an `upsert_by_key` table whose partition
  column is neither in its ordering key nor aligned with a partition-local source's partition column, and a
  query reads that entity final
- **THEN** the statement does not carry `do_not_merge_across_partitions_select_final`, merging across
  partitions, and the setting is withheld for every final read of that entity — including ones that do not
  reference the non-qualifying enrichment

#### Scenario: A raw read carries no partition-local setting

- **WHEN** `query.force-final` is off and a query reads a partition-local entity raw
- **THEN** the statement carries neither `FINAL` nor `do_not_merge_across_partitions_select_final`

#### Scenario: A composite statement qualifies only when every entity it reads does

- **WHEN** a SQL statement joins two entities, one partition-local and one not, and marks both `FINAL`
- **THEN** the statement does not carry the partition-local setting; when both qualify it carries it as a
  single-entity read would; and when only the partition-local one is marked `FINAL` (with
  `query.force-final` off) it carries it, because the entity read raw is not consulted

#### Scenario: Write-side reads follow the same rule

- **WHEN** a Class-1 materialize or an aggregate refresh writes its target with an `INSERT … SELECT` over a
  partition-local entity
- **THEN** the statement carries `do_not_merge_across_partitions_select_final = 1` alongside `final = 1`,
  and over a non-qualifying entity it carries `final = 1` only

### Requirement: SQL translation

The system SHALL translate a validated structured query into ClickHouse SQL by building a jOOQ query
model on the connection-less `SQLDialect.CLICKHOUSE` render context and rendering it to a SQL string
with literals inlined (the ClickHouse HTTP client executes a raw SQL string). Every inlined string
literal SHALL escape the backslash as well as the quote, because ClickHouse reads a backslash inside a
literal as an escape: a value matches exactly the characters the caller sent, and no value can close its
literal early and run the rest of itself as SQL. Field bindings SHALL be
sourced from the table-management catalog; allowlisted fields SHALL expand to their physical columns,
qualified by their owning table. Source columns SHALL be referenced by their bare flat name; enrichment
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
query's **whole** `filter` SHALL still be applied, unchanged, in the statement's own `WHERE`; an
enrichment conjunct SHALL NOT be moved out of it, and SHALL be copied into a derived table only as
"Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table" allows. A query with no
`filter`, or whose `filter` has no source-only top-level conjunct (an enrichment-only filter, or an `or`
mixing source and enrichment columns), SHALL join the enrichment table unkeyed, unless that requirement
or "Scalar calls over a heavy text column are computed inside the enrichment's derived table" emits it as
a derived table. On the single-entity read path a row-mode page whose heavy enrichment columns are
projection-only SHALL instead join those enrichments as "Projection-only heavy enrichment columns are
fetched for the returned page only" states. The same rule SHALL apply to
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
  (for example `trace_id = 'x'`)
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
  top-level conjunct), or the query has no `filter`
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

### Requirement: Inlined string literals are always matched literally

Every string literal the service renders into ClickHouse SQL SHALL escape both the backslash and the
quote, so the value is matched as exactly the characters it carries and can never close its literal
early. The rule binds every rendering, not only the query endpoints: filter values and contains (`co`/`nc`/`ico`/`inc`, SQL `LIKE`/`ILIKE`) patterns, row-policy predicates,
group-key lookups, and the statements a materialize or aggregate refresh renders. A contains pattern
SHALL additionally escape `%`, `_` and the backslash for `LIKE` before the literal is rendered, so the
caller's text is a substring to find, never a wildcard pattern. ClickHouse rejects an `ESCAPE` clause and
uses the backslash as its `LIKE` escape, so the pattern carries no `ESCAPE` clause.

#### Scenario: Percent and underscore in a contains value are not wildcards

- **WHEN** rows hold `100%`, `1000`, `a_b` and `axb`, and a query filters with `co` on `100%`, then on
  `a_b`
- **THEN** the first returns only `100%` and the second only `a_b`

#### Scenario: A backslash in a contains value matches one backslash

- **WHEN** rows hold `a\b` and `ab`, and a query filters with `co` on `a\b`, or ad-hoc SQL filters with
  `LIKE '%a\b%'`
- **THEN** only the row `a\b` is returned

#### Scenario: A backslash-quote in a contains value cannot escape the pattern

- **WHEN** a query filters with `co` on the value `x\' OR 1=1 --`
- **THEN** the pattern renders as one escaped literal and returns only rows containing those exact
  characters, here none

#### Scenario: Internal renderings escape the same way

- **WHEN** a row-policy predicate, a group-key lookup, or a materialize or aggregate statement inlines a
  string value holding a backslash or a quote
- **THEN** the value is escaped the same way as a query filter value and matched as exactly its characters

### Requirement: Response envelope

The system SHALL return query results as a response object carrying a `rows` array and a nullable
`totalCount`. Each row SHALL be a field-name → value map produced from the ClickHouse client's
records. Every projected column SHALL be present in every row, carrying a JSON `null` when its value
is null (notably an unmatched LEFT-JOINed enrichment column) — a null cell SHALL NOT be dropped from
the row object, so the row's key set matches the requested projection. Values SHALL be serialized in
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

### Requirement: Global aggregate returns a single row

An aggregate-mode query with no `group_by` keys (a global aggregate, e.g. a bare `count`) SHALL be
rendered without any `GROUP BY` clause, so that per standard SQL semantics it returns exactly one row
even when the filter matches no rows. (Previously an empty group-key list rendered jOOQ's "group by
nothing" form `GROUP BY (SELECT 1)`, which returns zero rows over an empty input.)

#### Scenario: Count over an empty match

- **WHEN** an aggregate query selects `count(*)` with no group_by and a filter that matches no rows
- **THEN** the result contains exactly one row with the count value `0`

#### Scenario: Count over matching rows

- **WHEN** an aggregate query selects `count(*)` with no group_by and a filter matching N rows
- **THEN** the result contains exactly one row with the count value N

### Requirement: Keyset scan mode

The query pipeline SHALL support a **keyset (seek) scan mode** in addition to offset/limit
querying, built inside the existing `StructuredQueryBuilder`/executor rather than as a separate
query path. In scan mode the builder SHALL: read the **base source only** with no enrichment LEFT
JOINs; push projection down to a caller-resolved column set; apply `final=1`; emit a keyset
predicate comparing an ordered cursor tuple (`(col_a, col_b, …) > (:c1, :c2, …)`); `ORDER BY` that
same tuple ascending; and apply `LIMIT`. Scan mode SHALL be reachable only through the governed
rule-scoped scan endpoint (`enrichment-runtime` capability); the general `POST /v1/queries/execute`
endpoint SHALL continue to reject cursor pagination (that surface stays deferred).

#### Scenario: Scan mode emits a keyset predicate and tuple ordering

- **WHEN** the builder produces a scan-mode query for a source with a cursor tuple `(v, id)`
- **THEN** the SQL reads the source only (no enrichment joins), selects the projected columns,
  applies `final=1`, includes `WHERE ... (v, id) > (:c1, :c2)` combined with the rule filter, and
  orders by `(v, id)` ascending with a `LIMIT`

#### Scenario: Cursor pagination on the general execute endpoint stays rejected

- **WHEN** a `CursorPage` request is submitted to `POST /v1/queries/execute`
- **THEN** the request is rejected as before (cursor pagination remains unsupported on the general endpoint)

### Requirement: Output column naming when `as` is omitted

A select entry's `as` key SHALL be optional for every expression kind and in both query modes; no
query SHALL be rejected for omitting it. When `as` is absent (or blank), the system SHALL derive the
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

### Requirement: Query execution runs under a resource budget

The system SHALL execute every structured query under an explicit, configured **resource budget** — a
wall-clock ceiling and a memory ceiling — applied as per-query ClickHouse server settings on the
executed statement, and SHALL refuse a query that exceeds either ceiling rather than letting it run
unbounded.

The budget is required because a query's cost is not bounded by anything the caller supplies. `limit`
bounds the rows returned, not the rows read: an entity's `heavy` columns are unbounded in size per
row, and a filter on a column that is not a prefix of the source's ordering key prunes nothing, so a
single small-result query can read the whole table's payload columns. Without a ceiling that read
continues after the caller's HTTP client has given up, so the cost is paid in full and the caller
learns nothing about why.

The budget SHALL bound the query's **cost**, never its **result**. The system SHALL NOT sample, narrow,
or silently truncate a query's input or output to stay inside the budget: a partial answer returned as
though it were complete is worse than a refusal, because the caller cannot tell the difference. A
query that cannot be completed within its budget SHALL be refused with an error naming the ceiling it
exceeded.

The budget SHALL apply to the query surface only — the paths that serve a caller-supplied query. The
enrichment scan, group-fetch, materialize, and aggregate-refresh paths share the executor but are
bounded by their own windows, page sizes, and limits; the dry-run preview has its own budget. A ceiling
imposed on those paths would make a legitimate large window fail as though it were malformed.

The wall-clock ceiling SHALL bound the **caller's request**, not each statement it runs. A query that
also asks for a total count runs a second statement, and that statement SHALL inherit what remains of the
ceiling rather than being granted a fresh one: a request able to occupy twice the configured ceiling puts
the caller past its own read timeout, which is the failure the budget exists to remove.

The ceilings SHALL be deployment-configurable, so an installation whose ordinary queries are heavier
than the defaults can raise them without a code change. A configured value that would leave a ceiling
bounding nothing — a spill threshold at or above the memory ceiling, or a wall-clock ceiling below the
granularity the store enforces — SHALL be refused at startup rather than accepted and silently ignored.

#### Scenario: An ordinary query is unaffected

- **WHEN** a query completes inside the configured ceilings
- **THEN** it returns its rows exactly as before, with no truncation, sampling, or added metadata

#### Scenario: A query exceeding the wall-clock ceiling is refused

- **WHEN** an executed query exceeds the configured execution-time ceiling
- **THEN** the response is HTTP 422 with `error` `query_budget_exceeded`, and the message names the
  ceiling that was exceeded and its configured value

#### Scenario: A query exceeding the memory ceiling is refused

- **WHEN** an executed query exceeds the configured memory ceiling
- **THEN** the response is HTTP 422 with `error` `query_budget_exceeded`, and the message names the
  ceiling that was exceeded and its configured value

#### Scenario: An unbounded heavy-column read is refused, not dropped

- **WHEN** a query reads a `heavy` column that its returned page does not bound — in its `filter`, inside an
  aggregate, or in a block that is not eligible for late materialization (see "Projection-only heavy
  enrichment columns are fetched for the returned page only") — with no predicate that prunes the source's
  partitions, so the read exceeds a ceiling
- **THEN** the caller receives the `query_budget_exceeded` error rather than an empty or transport-level
  failure, and the statement is refused by ClickHouse rather than continuing to run

#### Scenario: A projection-only heavy enrichment column is bounded by the page, not the window

- **WHEN** a row-mode query projects a `heavy` enrichment column only in its `select`, under a `limit`, with no
  predicate that prunes the source's partitions
- **THEN** the heavy column is read for the returned page's rows only, so its cost no longer grows with the
  window; the source scan that chooses the page still runs under the same budget, and a source scan that
  exceeds a ceiling is still refused with `query_budget_exceeded`

#### Scenario: A total count shares the request's ceiling

- **WHEN** a row-mode query requests a total count, so a second statement runs for the same request
- **THEN** that statement runs under what remains of the wall-clock ceiling, and the caller's total wait
  stays within the single configured ceiling

#### Scenario: A refusal is deterministic, not a transient failure

- **WHEN** a query is refused for exceeding its budget
- **THEN** the status is 422 rather than 503, because re-running the identical query is refused
  identically and a retrying client would loop

#### Scenario: An unrelated ClickHouse failure is not reported as a budget refusal

- **WHEN** the executed statement fails for a reason other than the budget's ceilings
- **THEN** the existing error mapping for that failure applies unchanged, and `query_budget_exceeded`
  is not returned

#### Scenario: The scan and refresh paths keep their own bounds

- **WHEN** an enrichment scan, group fetch, materialize, or aggregate refresh runs a statement through
  the same executor
- **THEN** it is not subject to the query budget, and its own window, page size, and limits govern it

#### Scenario: A node-wide exhaustion is not reported as a budget refusal

- **WHEN** an executed query fails because ClickHouse itself ran out of memory rather than because the
  query exceeded its own ceiling
- **THEN** the response is HTTP 503 `clickhouse_unavailable` rather than 422 `query_budget_exceeded`
- **AND** over Flight the failure is `UNAVAILABLE` rather than `INVALID_ARGUMENT`

### Requirement: Wildcard projection excludes heavy columns

A **wildcard projection** — a row-mode query whose `select` is omitted or empty — SHALL expand to the
entity's source columns that are **not** marked `heavy` in the table-management catalog (see the
`table-management` capability's "Heavy column metadata"), in their existing order and under their own
names. Enrichment columns SHALL continue to be excluded from the expansion, unchanged — and a SQL
`SELECT *` SHALL now expand to this same field set (see the `sql-query-translation` capability), so the
DSL and SQL wildcards resolve to one rule.

The exclusion SHALL apply **only** to the wildcard expansion. A `heavy` column named explicitly in
`select` SHALL be projected and returned exactly as before, and a `heavy` column SHALL remain fully
usable in `filter`, `group_by`, `having`, `sort`, and as a function argument. Heaviness SHALL NOT
change any value, type, or null semantics — a heavy column is either projected in full or not at all.

A wildcard projection whose expansion would be **empty** — every source column visible to the caller
is heavy — SHALL be rejected with HTTP 400 (`bad_request`), with a message naming the entity and
directing the caller to name the columns explicitly. The system SHALL NOT execute a zero-column query.

Because both flags are evaluated over the caller's role-visible model, the two filters compose: a
column that is `sensitive` and invisible to the caller is absent regardless of `heavy`, and a visible
heavy column is omitted from the wildcard for every caller alike, `FULL_ADMIN` included.

#### Scenario: Omitted select skips a heavy column

- **WHEN** a row-mode query with no `select` runs against an entity whose columns include one marked
  `heavy`
- **THEN** the response rows carry every non-heavy source column and no key for the heavy column

#### Scenario: Explicitly selected heavy column is returned

- **WHEN** a row-mode query names a `heavy` column in its `select`
- **THEN** the column is projected and its values are returned in full, exactly as for a non-heavy
  column

#### Scenario: Heavy column stays usable in other clauses

- **WHEN** a row-mode query with no `select` filters and sorts on a `heavy` column
- **THEN** the filter and sort apply normally and the response rows simply omit that column

#### Scenario: All-heavy wildcard is rejected

- **WHEN** a row-mode query with no `select` names an entity whose every caller-visible source column
  is marked `heavy`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the entity, and no query is
  executed

#### Scenario: Marking a column heavy is reversible mid-flight

- **WHEN** a wildcard query runs, the column is then marked `heavy` via
  `PATCH /v1/tables/{name}/schema`, and the same query runs again
- **THEN** the first response carries the column and the second does not, with no restart and no
  cached schema

### Requirement: Map cells serialize as JSON objects

A projected `map` column SHALL be typed in the derived result schema as an object column — a map cell
travels as a JSON object exactly as an `object` cell does — and SHALL serialize in the response
envelope's `rows` as a JSON object whose values are strings. An empty map SHALL serialize as `{}`, never
as `null` and never as a quoted JSON string. A wildcard projection over an entity carrying `map` columns
SHALL include them, subject to the existing heavy-column exclusion.

#### Scenario: Map projection returns a JSON object

- **WHEN** a row-mode query projects a `map` column holding `{"http.method": "GET"}`
- **THEN** the response envelope types the column as an object column and the row carries
  `{"http.method": "GET"}` as a JSON object

#### Scenario: Empty map cell serializes as an empty object

- **WHEN** a row-mode query projects a `map` column whose stored value is the empty map
- **THEN** the row carries `{}` for that column

### Requirement: Map fields are projection-only when translating to SQL

A `map` field SHALL be projection-only in the DSL→SQL translation subset, on the same footing as an
`array` field: the SQL dialect has no map literal and no comparison over one, so a
`StructuredQuery` that uses a `map` field as a `distinct` projection, a `group_by` key, a `sort` key, or
a filter operand SHALL be rejected by `POST /v1/queries/translate` with HTTP 400 and `error`
`bad_request`, naming the field and the projection-only restriction. Carrying a `map` field as a plain
projection SHALL translate.

An `object` field is **not** on this footing: it carries a canonical text rendering that both surfaces
can name, so the document comparisons listed in "Object fields are comparable as canonical JSON
documents" translate rather than being rejected. Every other use of an `object` field — a `distinct`
projection, a `group_by` key, a `sort` key, an ordering comparison, a null test — is rejected on the
same terms as a `map` field's.

#### Scenario: Map field projects in a translated query

- **WHEN** `POST /v1/queries/translate` is given a query projecting a `map` field
- **THEN** the translation succeeds and the rendered SQL selects that column

#### Scenario: Map field as a sort key is refused by translation

- **WHEN** `POST /v1/queries/translate` is given a query sorting by a `map` field
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction

#### Scenario: Map field as a group_by key is refused by translation

- **WHEN** `POST /v1/queries/translate` is given a query grouping by a `map` field
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction

#### Scenario: Map field as a filter operand is refused by translation

- **WHEN** `POST /v1/queries/translate` is given a query comparing a `map` field with any operator
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction

### Requirement: Query-level distinct projection

The structured query envelope SHALL carry an optional boolean `distinct` flag that deduplicates whole
result rows over the projected columns. It SHALL be listed among the envelope's members alongside
`entity`, `filter`, `mode`, `select`, `group_by`, `having`, `sort` and `page`, and SHALL default to
`false` when absent or null rather than failing deserialization.

`distinct` SHALL be available in row mode and in aggregate mode, and SHALL apply to the projected
output columns — including a projected expression and a projected alias, not only a bare column. It
SHALL compose with `sort` and with paging, deduplication applying before the page window is taken so a
page carries the requested number of distinct rows.

A `distinct` projection of a projection-only field (`object`, `array` or `map`) SHALL be **accepted on
the execution path** and rejected only when translating to SQL. The restriction on those types is a
property of the SQL subset, not of the DSL — the DSL can project such a column, and deduplicating whole
rows over a projected one is well defined — so `POST /v1/queries/execute` SHALL run it while
`POST /v1/queries/translate` SHALL reject it with HTTP 400 (see
[sql-query-translation](../sql-query-translation/spec.md), which states the same split for a `group_by`
or `sort` key).

This requirement documents behaviour the service already implements; it is stated here because the
capability's expressive power is otherwise indistinguishable from the SQL surface's, which led clients
to conclude the flag did not exist.

#### Scenario: Envelope distinct deduplicates rows

- **WHEN** a row-mode query sets `distinct` to true and projects one column over rows carrying two
  duplicate values and one unique value
- **THEN** the response carries two rows

#### Scenario: Distinct over several projected columns

- **WHEN** a row-mode query sets `distinct` to true, projects a column and a `date_trunc` expression,
  and sorts on the expression's alias
- **THEN** the response carries one row per distinct combination of the two outputs

#### Scenario: Distinct defaults to false when absent

- **WHEN** a query omits `distinct`, or sends it as null
- **THEN** the query is accepted and rows are not deduplicated

#### Scenario: Distinct over a projection-only field executes but does not translate

- **WHEN** a query sets `distinct` to true and projects an `array` field
- **THEN** `POST /v1/queries/execute` returns rows, while `POST /v1/queries/translate` given the same
  query is rejected with HTTP 400 naming the field and the projection-only restriction

### Requirement: Enum fields are string-comparable scalars in the DSL

An `enum` field SHALL behave as an ordinary scalar throughout the DSL: it may be projected,
filtered, grouped, aggregated with the aggregations a string supports, and named in `sort`. It is
NOT projection-only, so unlike `object`, `array` and `map` it has no restriction on where it may
appear.

The DSL has no `enum` literal, and SHALL NOT gain one — a caller names an enum value with the
string it is. A `string` literal and an `enum` field SHALL therefore be comparable: wherever the
DSL requires two operands to be of the same type, `enum` and `string` SHALL unify (to `enum`), the
way the numeric types already widen against each other. An `enum` field compared against a
non-string, non-enum operand SHALL still be rejected.

An `enum` field SHALL NOT be accepted as a **text operand** of any string function — `starts_with`,
`contains`, `lower`, `upper`, `trim`, `length`, `not_empty` and `split_string` — and such a request
SHALL be rejected with a message naming the offending type. This is not a stylistic restriction: ClickHouse defines comparison over an enum
but not the string functions, so `startsWith(<enum column>, 'x')` raises at the server. Rejecting it
during validation turns a query that would fail mid-execution into an input error that says what to
fix. (A caller needing a string function over the domain declares the column `string` instead.)

**`to_string` and `concat` are the deliberate exception**, and the rule above SHALL NOT be read as
covering them: they *render* a value rather than inspecting its text, and the engine defines that
rendering over an enum (it yields the label). An `enum` operand SHALL therefore be accepted by
`to_string`, by `concat`, and by `array_to_string`'s element rendering — which is also the way a
caller reaches the inspecting functions over an enum domain, by rendering first
(`starts_with(to_string(status), 'run')`).

The rule follows the operation, not its spelling: the filter grammar's contains operators (`co`,
`nc`, `ico`, `inc`) are the same `LIKE` the `contains` function expresses, so they SHALL apply the
same text-operand check and reject an `enum` left operand the same way. A rejection SHALL NOT depend
on which of the two spellings a caller used.

**The enforcement widens beyond `enum`.** The text-operand check the function catalog states for
`not_empty`, `starts_with` and `contains` SHALL apply to `lower`, `upper`, `trim`, `length` and
`split_string` as well — the signatures the catalog already advertises as taking `text`. Any
non-text operand SHALL therefore be rejected with HTTP 400 naming its type, not only an `enum` one:
`lower(<a long field>)` was previously translated and left to fail inside ClickHouse, and is now an
input error like every other type mismatch on this surface. This is a consequence of the enum rule
rather than its purpose — the check has to live on each of those functions for the enum rule to
hold — and it is recorded here because it changes what a caller sees for the other types too.

Because that check is applied inside `having` too, a **derived select output** SHALL carry the type of
the expression it projects rather than a fixed one: an alias over a text-valued expression
(`max(model)`, `map_value(attributes, 'user')`) SHALL be accepted as a text operand, and an alias over
a numeric one SHALL be rejected as its own type names. No operand SHALL be judged by a type its
expression does not have.

Sorting an `enum` field SHALL order by the column's **declared value order**, not alphabetically —
the physical consequence of ClickHouse comparing an enum by its numeric id, surfaced here so a
caller sorting by a severity column gets severity order.

#### Scenario: A string literal filters an enum field

- **WHEN** a query filters an `enum` field with `eq` against the string literal `"running"`
- **THEN** the query is accepted and returns only rows whose value is `running`

#### Scenario: A string function over an enum field is rejected

- **WHEN** a query applies a string function (for example `starts_with`) to an `enum` field
- **THEN** the request is rejected with a validation error naming `enum` as the offending type,
  rather than being sent to ClickHouse and failing there

#### Scenario: A contains filter operator over an enum field is rejected

- **WHEN** a query filters an `enum` field with `co` (or `nc`/`ico`/`inc`) against a string literal
- **THEN** the request is rejected with the same validation error naming `enum`, rather than a
  `LIKE` being sent to ClickHouse

#### Scenario: A contains filter over a text-valued aggregate output is accepted

- **WHEN** an aggregate query selects a text-valued expression under an alias and filters `having`
  on that alias with `co` against a string literal
- **THEN** the query is accepted, because the alias is judged by the type of the expression it
  projects rather than by a fixed one

#### Scenario: A non-text operand to a widened string function is rejected

- **WHEN** a query applies `lower` (or `upper`/`trim`/`length`) to a `long` field
- **THEN** the request is rejected with HTTP 400 naming `long` as the offending type, rather than
  being translated and failing inside ClickHouse

#### Scenario: An incompatible literal against an enum field is rejected

- **WHEN** a query compares an `enum` field against a numeric or timestamp literal
- **THEN** the request is rejected with a validation error

#### Scenario: Sorting by an enum field follows declared order

- **WHEN** a query sorts ascending by an `enum` field whose column declares
  `["low", "medium", "high"]`
- **THEN** rows are returned in that declared order, not in alphabetical order

#### Scenario: An enum field is projected and grouped like a string

- **WHEN** a query groups by an `enum` field and counts
- **THEN** the query is accepted and returns one row per distinct enum value, each value rendered
  as its string

#### Scenario: Rendering an enum field as text is accepted

- **WHEN** a query selects `to_string(status)`, `concat('status: ', status)` or
  `starts_with(to_string(status), 'run')` over an `enum` field
- **THEN** each is accepted and the enum's label is what reaches the text, rather than the
  enum-operand rejection the inspecting functions give

### Requirement: Object fields are comparable as canonical JSON documents

An `object`-typed field SHALL be usable as the left operand of the comparison operators `co`, `nc`,
`ico`, `inc`, `eq`, `ne` and `in`, in a `filter`, on every surface that accepts a
structured query. The operand rule SHALL apply identically to a `having` comparison — the two clauses
share one operand classification — but whether such a `having` *succeeds* depends on the query's
grouping, and on which surface it runs:

- When the bare `object` field is **not** a `group_by` key it is neither grouped nor aggregated, so the
  request SHALL be rejected — for the ungrouped reference, not for the operand.
- When the query **groups by that same `object` field**, the execution path
  (`POST /v1/queries/execute`) SHALL accept it: `group_by` on an `object` field is a non-comparison use,
  which this requirement leaves to the surface split stated below, and ClickHouse groups a native
  `JSON` column. The SQL surfaces SHALL still reject it, because `group_by` on an `object` field is
  refused there — so this is one of the positions where the two surfaces deliberately diverge.

The comparison SHALL be defined over the document's **canonical JSON text** — the
serialization in which object keys are ordered lexicographically at every nesting level, insignificant
whitespace is removed, and numeric values are normalized, while the distinction between a JSON string
and a JSON number is preserved.

`co`/`nc` SHALL match (or not match) a case-sensitive substring of that canonical text and `ico`/`inc`
its case-insensitive counterpart, on the same terms these operators carry for a character field. `eq`,
`ne` and `in` SHALL compare **documents**, not texts: the right-hand literal SHALL be canonicalized by
the same rules before comparison, so equality is independent of the key order, whitespace and number
format the caller writes. A literal that is not itself a well-formed JSON document SHALL be rejected
with HTTP 400 and `error` `bad_request`, naming the field and the literal, before ClickHouse is
contacted. Literals used with `co`/`nc`/`ico`/`inc` are ordinary substring patterns and SHALL carry no
JSON well-formedness requirement.

Because the comparison reads the whole document, it SHALL match a key name as readily as a value, and
SHALL match the escaped form in which the canonical text carries a value. The `json_extract_*` family
remains the instrument for reading one key, and a caller needing a key-scoped test SHALL use it rather
than a document comparison.

The following **comparison** uses of an `object` field SHALL remain rejected on every such surface, with
HTTP 400 and `error` `bad_request`, naming the field and the projection-only restriction: the ordering
comparisons `lt`, `gt`, `le` and `ge`; `eq` or `ne` against a **null literal**; and the **right**
operand of any comparison (the carve-out is the shape `<object field> <operator> <literal>`, and the SQL
subset admits no other, so no other would round-trip). Ordering is not defined over documents, and a
`JSON` column is never null — so `eq`/`ne` against a null literal would be a constant predicate rather
than a document test.

The **non-comparison** projection-only uses — a `sort` key, a `group_by` key, a `distinct` projection,
and an argument to any function outside the `json_extract_*` carve-out — are outside this requirement
and SHALL keep the surface split they already have: they are rejected by the SQL surfaces (see
[sql-query-translation](../sql-query-translation/spec.md) and
[query-translation-endpoint](../query-translation-endpoint/spec.md)), while their behaviour on the
execution path (`POST /v1/queries/execute`) is left exactly as it is, neither newly accepted nor newly
rejected.

An `array`- or `map`-typed field SHALL NOT be usable as an operand of **any** comparison, and such a
use SHALL be rejected with HTTP 400 and `error` `bad_request`, naming the field and the projection-only
restriction, before ClickHouse is contacted. Neither type has a canonical text rendering the SQL subset
can name, so neither comparison would round-trip.

#### Scenario: Case-insensitive document search matches

- **WHEN** a query filters `{"op": "ico", "args": [{"type": "field", "name": "request_tags"}, {"type": "value", "value_type": "string", "value": "gpt-4"}]}`
- **THEN** the request succeeds and returns exactly the rows whose `request_tags` document contains
  `gpt-4` case-insensitively anywhere in its canonical text

#### Scenario: Document equality ignores key order

- **WHEN** a query filters an `object` field with `eq` against the literal `{"b":2,"a":1}`
- **THEN** rows stored as `{"a":1,"b":2}` and rows stored as `{"b":2,"a":1}` both match

#### Scenario: Document equality ignores whitespace and number format

- **WHEN** a query filters an `object` field with `eq` against the literal `{ "a" : 1.0 }`
- **THEN** rows stored as `{"a":1}` and rows stored as `{"a":1.0}` both match

#### Scenario: Document inequality is the complement

- **WHEN** a query filters an `object` field with `ne` against a JSON literal
- **THEN** the result is exactly the rows the corresponding `eq` filter excludes

#### Scenario: Document membership under `in`

- **WHEN** a query filters an `object` field with `in` against an array of two JSON literals
- **THEN** the result is the union of the rows each literal would match under `eq`

#### Scenario: Non-JSON literal is rejected

- **WHEN** a query filters an `object` field with `eq` against the literal `x`, which is not a
  well-formed JSON document
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  literal, and ClickHouse is not contacted

#### Scenario: Substring pattern needs no JSON well-formedness

- **WHEN** a query filters an `object` field with `co` against the literal `"role":`
- **THEN** the request succeeds — the pattern is a substring, not a document

#### Scenario: Object field in an ungrouped having comparison

- **WHEN** an aggregate-mode query grouping by an ordinary scalar field compares an `object` field with
  `ico` in its `having`
- **THEN** the operand passes the same classification a `filter` comparison would, but the request is
  rejected with HTTP 400 and `error` `bad_request` because the bare field is neither grouped nor
  aggregated — the carve-out admits the operand, not an ungrouped reference

#### Scenario: Object field in a having comparison over its own group key

- **WHEN** an aggregate-mode query submitted to `POST /v1/queries/execute` groups by an `object` field
  and compares that same field with `ico` in its `having`
- **THEN** the request succeeds and returns the matching groups — the field is grouped, so the reference
  is legal, and `group_by` on an `object` field is a non-comparison use the execution path does not
  restrict. `POST /v1/queries/translate` SHALL reject the same document, because the SQL surfaces refuse
  an `object` `group_by` key

#### Scenario: Ordering comparison on an object field is rejected

- **WHEN** a query filters an `object` field with `gt` against a JSON literal
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction, and ClickHouse is not contacted

#### Scenario: Null test on an object field is rejected

- **WHEN** a query filters an `object` field with `eq` or `ne` against a null literal
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction, and ClickHouse is not contacted

#### Scenario: Comparison on an array field is rejected

- **WHEN** a query filters an `array`-typed field with any comparison operator, including `eq` against
  a null literal
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction, and ClickHouse is not contacted

#### Scenario: Comparison on a map field is rejected

- **WHEN** a query filters a `map`-typed field with any comparison operator
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction, and ClickHouse is not contacted

#### Scenario: The key-scoped extractor is unaffected

- **WHEN** a query filters on `json_extract_string_ci(request_tags, 'x-session-id')` compared with a
  string literal
- **THEN** the request is accepted exactly as before — the document-comparison rules apply to a bare
  `object` field operand, never to an extraction's result

### Requirement: Conditional case expression

The expression grammar SHALL include a `case` kind: `{"type": "case", "when": [{"when": <filter node>,
"then": <expr>}, …], "else": <expr>}`. `when` SHALL carry at least one branch; each branch's `when` is a
node of the **filter grammar** (a comparison, `and`/`or`/`not`, `eq`/`ne` against a null literal, or the
`in` predicate), so a comparison IS a legal condition here — this is the one expression position that
admits the filter grammar, and the reason `case` exists alongside `if`. `else` MAY be omitted or null, in
which case an unmatched row yields null. Branches are evaluated in order and the first matching `then`
wins. `then` and `else` values SHALL unify under the same rule as `if`'s branches: the numeric types widen
against each other and the result is the widest; any other type mismatch and a non-scalar (`object`,
`array`, `map`) branch SHALL each be rejected with HTTP 400 naming the problem. Unlike `if`, a branch that
is a null literal SHALL be accepted — it renders as SQL `NULL` and takes the other branches' type — as long
as at least one branch is not a null literal; a `case` whose every branch is null SHALL be rejected with
HTTP 400. A `case` expression SHALL be accepted wherever any scalar expression is — `select`, `filter` and
`having` operands, `group_by` (through its select alias), `sort` (through its alias), and aggregate
arguments — and SHALL render as SQL `CASE WHEN … THEN … [ELSE …] END`. Its output name when `as` is omitted
SHALL be `case`.

#### Scenario: Case classifies rows in an aggregate
- **WHEN** an aggregate-mode query selects `{"type":"case","when":[{"when":{"op":"gt","args":[<total_tokens>,<1000>]},"then":<'large'>}],"else":<'small'>}` aliased `size` and groups by `size`
- **THEN** the query executes and returns one row per class with its aggregate

#### Scenario: Case without else yields null
- **WHEN** a row-mode query selects a `case` with one branch and no `else`
- **THEN** rows the branch does not match carry null in that column

#### Scenario: Mismatched case branches rejected
- **WHEN** a query selects a `case` whose `then` is a string literal and whose `else` is an integer literal
- **THEN** the request is rejected with HTTP 400 naming the incompatible branch types

#### Scenario: Null branch takes the other branches' type
- **WHEN** a query selects `{"type":"case","when":[{"when":{"op":"eq","args":[<chat_id>,<''>]},"then":<null literal>}],"else":<chat_id>}`
- **THEN** the query executes and rows with an empty `chat_id` carry null in that column

#### Scenario: Empty case rejected
- **WHEN** a query selects `{"type":"case","when":[]}`
- **THEN** the request is rejected with HTTP 400

### Requirement: Window expression

The expression grammar SHALL include a `window` kind: `{"type": "window", "fn": <fn expr>, "partition_by":
[<expr>, …], "order_by": [{"expr": <expr>, "dir": "asc"|"desc", "nulls": "first"|"last"}, …], "frame":
{"unit": "rows"|"range", "start": <bound>, "end": <bound>}}` where a bound is `{"kind":
"unbounded_preceding"|"preceding"|"current_row"|"following"|"unbounded_following", "offset": <n>}` and
`offset` is required exactly for `preceding`/`following`. `partition_by`, `order_by` and `frame` MAY each
be omitted; an omitted `frame` leaves the engine's standard default frame in force. `fn` SHALL be either a
zero-argument call to one of the window-only functions `row_number`, `rank`, `dense_rank`, or a call to one
of the aggregates `count`, `sum`, `avg`, `min`, `max`; `distinct` on the inner call SHALL be rejected with
HTTP 400, and any other function name SHALL be rejected with HTTP 400 naming it.

A `window` expression SHALL be accepted in `select` (and therefore in `sort` through its alias) and
nowhere else: one appearing in `filter`, `having`, `group_by`, inside an aggregate call's arguments or
inside another `window` SHALL be rejected with HTTP 400 naming the position. Anywhere within a `select`
entry's expression counts as `select` — including the condition of a `case` branch, which is a filter tree
standing inside an expression rather than a predicate — so `case` over a window is accepted while the same
`case` used in `filter` or `having` is rejected. In aggregate mode every field
reference inside a `window` expression — in `fn`'s arguments, `partition_by` or `order_by` — SHALL be a
`group_by` key or lie inside an aggregate call, whether the `window` is the select entry itself or is nested
inside one; a bare non-key field there SHALL be rejected with HTTP 400.
The expression SHALL render as `<fn> OVER (PARTITION BY … ORDER BY … <frame>)`. Its output name when `as`
is omitted SHALL be the inner function's name (`row_number`, `sum`, …). The result type SHALL be `long`
for the ranking functions and the aggregate's own type otherwise.

#### Scenario: row_number per partition
- **WHEN** a row-mode query selects `{"type":"window","fn":{"type":"fn","name":"row_number","args":[]},"partition_by":[<project_id>],"order_by":[{"expr":<request_time>,"dir":"asc"}]}` aliased `rn`
- **THEN** the query executes and `rn` numbers each project's rows from 1 in request-time order

#### Scenario: Running sum with a rows frame
- **WHEN** a row-mode query selects a `window` whose `fn` is `sum(total_tokens)`, ordered by `request_time`, with frame `rows` from `unbounded_preceding` to `current_row`
- **THEN** the query executes and the column carries the cumulative sum

#### Scenario: Window over an aggregate in aggregate mode
- **WHEN** an aggregate-mode query grouped by `project_id` selects `sum(total_tokens)` aliased `t` and a `window` whose `fn` is `rank` ordered by `sum(total_tokens)` descending
- **THEN** the query executes and ranks the groups by their sums

#### Scenario: Window in filter rejected
- **WHEN** a query's `filter` compares a `window` expression to a literal
- **THEN** the request is rejected with HTTP 400 stating that window expressions are allowed in `select` only

#### Scenario: Window inside a select-list case branch condition
- **WHEN** a row-mode query selects a `case` whose single branch condition compares `row_number()` over `request_time` to a literal
- **THEN** the query executes and renders as `CASE WHEN row_number() OVER (…) > … THEN … END`

#### Scenario: Window inside a case in the filter rejected
- **WHEN** the same `case` is used as an operand of a `filter` comparison instead
- **THEN** the request is rejected with HTTP 400 stating that window expressions are allowed in `select` only

#### Scenario: Non-key field inside a window in aggregate mode rejected
- **WHEN** an aggregate-mode query grouped by `project_id` selects a `window` whose `partition_by` names `event_kind`
- **THEN** the request is rejected with HTTP 400 naming the field

#### Scenario: Unsupported window function rejected
- **WHEN** a query selects a `window` whose `fn` is named `lag`
- **THEN** the request is rejected with HTTP 400 naming `lag`

### Requirement: Window-only functions extend the catalog

The function catalog SHALL additionally contain the three **window-only** functions `row_number`, `rank`
and `dense_rank`, each in the group `window`, each with zero arguments, each returning `long`, and each
with `distinct_supported` false. They SHALL be accepted only as the `fn` of a `window` expression: a plain
`fn` call to any of them outside a window SHALL be rejected with HTTP 400 stating that the function
requires an `OVER` clause. The aggregates `count`, `sum`, `avg`, `min` and `max` keep their catalog entries
unchanged and are additionally accepted as the `fn` of a `window` expression.

#### Scenario: Ranking function outside a window rejected
- **WHEN** a query selects `{"type":"fn","name":"row_number","args":[]}` directly
- **THEN** the request is rejected with HTTP 400 stating that `row_number` requires a window (`OVER`) clause

#### Scenario: Ranking functions are catalog members
- **WHEN** the function catalog is consulted for `row_number`, `rank` and `dense_rank`
- **THEN** each is described with group `window`, no arguments and a `long` return type

### Requirement: Array element expansion

The function catalog SHALL additionally contain the scalar function `array_join(array)`, which
expands an array into **one output row per element**. It SHALL take exactly one operand, mirroring the
underlying engine's own function; a call with any other operand count SHALL be rejected with HTTP 400
and `error` `bad_request`. It is the only catalog function whose call changes the number of rows a
query returns, and its declared return type is the **element type** of its operand — not an array, and
not the operand's own type.

**Operand.** The operand SHALL be an array-valued expression: an `array` field (accepted there despite
the projection-only rule, exactly as the array accessors accept one) or a call to an
array-returning catalog function — the scalars `map_keys`, `split_string` and `json_extract_array`,
or the aggregate `group_uniq_array`. A non-array operand SHALL be rejected with HTTP 400 and `error` `bad_request` naming the expected operand, before
the engine is contacted.

**Empty arrays.** A row whose array is empty SHALL produce **no** output rows. There SHALL be no
outer/left variant of this function: the engine's function has none, and a variant that kept such a
row would be surface this system invented rather than exposed.

**Result.** The expanded value SHALL carry the array's element type, so it is an ordinary scalar for
every purpose — a filter operand, a sort key, an aggregate's operand, a group key by alias — carrying
no projection-only restriction of its own. For an `array` field that element type is the one schema
discovery publishes as `element_type`. For a computed array it SHALL be derived from the producing
call: `map_keys`, `split_string` and `json_extract_array` yield text whatever they read and SHALL
therefore be typed `string`, while `group_uniq_array` collects its own operand's values and SHALL
therefore carry that operand's type. Both query surfaces SHALL derive the same type, or one document
would be typed differently depending on the endpoint it arrived through.

**Legal position.** `array_join` SHALL be legal wherever any other scalar call is legal, including in
`filter` and `having`, inside an aggregate function's arguments, and inside a `window` expression. The
system SHALL NOT impose positional restrictions of its own on it: expansion is the engine's own
behaviour in each of those positions, and a narrower surface here would refuse queries the engine
accepts.

**Multiple expansions.** Two select entries expanding **different** expressions SHALL produce the
engine's cartesian product of the two element sets; two entries expanding the **same** expression SHALL
advance together over one expansion rather than multiplying. Neither SHALL be rejected. Element-wise
pairing of two different arrays is not expressible through this function.

**Grouping by an expanded element.** An aggregate-mode query SHALL be able to group by an expanded
element by giving the `array_join` select entry an alias and naming that alias in `group_by`. This is
the one way a `group_by` over individual array elements is expressible; naming the `array` field itself
in `group_by` SHALL stay rejected as before.

**Interaction with `include_total`.** A row-mode query whose `select` contains an `array_join` call and
whose `page` sets `include_total` true SHALL be rejected with HTTP 400 and `error` `bad_request`,
stating that the total count is taken over source rows and therefore does not describe an expanded
result. Omitting `include_total` (or setting it false) SHALL be accepted. The refusal SHALL NOT extend
to aggregate mode, where `include_total` is already inert — no count is taken and the response's total
is null — so an aggregate-mode query combining `array_join` with `include_total` true SHALL be accepted
and its total SHALL be absent as for any other aggregate query.

**Interaction with query-level `distinct`.** A row-mode query MAY combine `distinct` with
`array_join`; deduplication SHALL apply to the expanded rows, so `distinct` over a single `array_join`
projection SHALL return each element value once.

#### Scenario: Expanding an array column into rows

- **WHEN** a row-mode query selects `{"type":"fn","name":"array_join","args":[{"type":"field","name":"topics"}]}` aliased `topic`
- **THEN** a source row whose `topics` is `["git","code"]` contributes two result rows, `git` and `code`,
  and each carries the array's published `element_type`

#### Scenario: Empty array drops the row

- **WHEN** the same query runs over a source row whose `topics` is empty
- **THEN** that row contributes no result rows

#### Scenario: Grouping by an expanded element

- **WHEN** an aggregate-mode query selects `array_join(topics)` aliased `topic` together with
  `count()` aliased `requests`, and sets `group_by` to `["topic"]`
- **THEN** the response carries one row per distinct topic with its request count, and no row for a
  source row that had no topics

#### Scenario: Expanding a computed array

- **WHEN** a query selects `array_join(split_string(tags, ','))`, `array_join(map_keys(attrs))` or
  `array_join(json_extract_array(payload, 'items'))`
- **THEN** each is accepted, on the same footing as the array accessors' first argument

#### Scenario: Expanding an aggregate's array result

- **WHEN** an aggregate-mode query selects `array_join(group_uniq_array(total_tokens))` over a numeric
  column
- **THEN** the call is accepted and the expanded value is typed as that column's numeric type, not as
  text — so a `having` or `sort` over the entry's alias is validated as a number

#### Scenario: Expansion is accepted in every scalar position

- **WHEN** a query uses an `array_join` call as a comparison operand in `filter`, inside an aggregate
  function's arguments, or inside a `window` expression
- **THEN** each is accepted and executes with the engine's own expansion semantics for that position

#### Scenario: Two different expansions multiply

- **WHEN** a row-mode query selects `array_join(a)` and `array_join(b)` over one source row whose `a`
  and `b` each hold two elements
- **THEN** the query returns four rows, the cartesian product of the two element sets

#### Scenario: Two identical expansions do not multiply

- **WHEN** a row-mode query selects `array_join(topics)` twice, under two different aliases, over one
  source row holding two topics
- **THEN** the query returns two rows, the two aliases carrying the same element in each

#### Scenario: Non-array operand rejected

- **WHEN** a query selects `array_join(model)` over a scalar column
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request` naming the expected array
  operand, with the engine not contacted

#### Scenario: Wrong operand count rejected

- **WHEN** a query selects `array_join(topics, 'left')` or `array_join()`
- **THEN** each is rejected with HTTP 400 and `error` `bad_request`: the function takes exactly one
  operand and has no mode or outer variant

#### Scenario: include_total refused alongside an expansion

- **WHEN** a row-mode query selects an `array_join` call and its `page` sets `include_total` true
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request` stating that the total is
  counted over source rows and does not describe an expanded result

#### Scenario: include_total accepted alongside an aggregate expansion

- **WHEN** an aggregate-mode query selects `array_join(topics)` aliased `topic` with `count()`, groups by
  `topic`, and its `page` sets `include_total` true
- **THEN** the request is accepted and answered with the grouped rows, the total being absent as it is
  for every aggregate-mode query

#### Scenario: Distinct over an expansion

- **WHEN** a row-mode query sets `distinct` true and selects only `array_join(topics)`
- **THEN** each distinct element value is returned once

#### Scenario: array_join is a catalog member

- **WHEN** the function catalog is consulted for `array_join`
- **THEN** it is described in group `scalar`, with signature `array_join(array)`, a `returns` of
  `element_of_argument`, `distinct_supported` false, and exactly one required expression argument

### Requirement: Text composition and value rendering

The function catalog SHALL contain the scalar functions `concat(value, value[, value…])` and
`to_string(value)`.

**`to_string(value)`** SHALL render one **scalar** value as text and SHALL return **null for a null
operand** — a cast of nothing is nothing. This is deliberately *not* the empty-string absence contract
`map_value` and `json_extract_string` carry: `to_string` is the function the SQL surface's
`CAST(x AS VARCHAR)` becomes, so normalizing null here would change the meaning of that cast over a
nullable column, and a projected `to_string(price)` should leave an empty cell rather than claim the
empty string. The never-null contract belongs to the two functions that *compose* text and would
otherwise lose a whole label to one absent operand — `concat` and `array_to_string` — each of which
SHALL guard its operands itself.

A non-scalar operand (`object`, `array`, `map`, or a call returning an
array) SHALL be rejected with HTTP 400 naming the operand: those values are projection-only and have no
scalar text. The rendered text SHALL be the store's own canonical rendering of the value, which this
contract fixes as follows so that a label does not change shape between releases:

| Operand type | Rendered as |
|---|---|
| `string`, `uuid` | the value verbatim |
| `enum` | the enum's label, not its numeric position |
| `integer`, `long` | plain decimal digits with a leading `-` when negative |
| `decimal` | plain decimal text with trailing zeros **trimmed** — a `Decimal(38, 12)` holding 1.5 renders `1.5`, not `1.500000000000` |
| `boolean` | `true` or `false`, never `1`/`0` — and **null when the operand is null**, on the same footing as every other row: a nullable `boolean` column SHALL NOT render as `false` |
| `timestamp` | `YYYY-MM-DD hh:mm:ss.fff` in UTC |
| `date` | `YYYY-MM-DD` |

`to_string` SHALL be the **only** way to render a value as text on its own, and it SHALL admit an
`enum` operand even though the text functions (`lower`, `starts_with`, `contains`, `length`, `trim`,
`upper`, `not_empty`) reject one: it renders rather than inspects, so the engine restriction those
functions fail closed on does not apply.

**`concat(value, value[, value…])`** SHALL take **two or more** operands, SHALL render each exactly as
`to_string` renders it, and SHALL return their concatenation as text. It SHALL never return null: an
operand whose **value** is null contributes the empty string, which is PostgreSQL `concat()`'s own
behaviour and differs deliberately from `||`'s null propagation in standard SQL — a dashboard label
reading `a -> ` is more useful than a whole row reading null, and the catalog's other composing text
functions already normalize absence to the empty string.

That absorption is about a null **value**, not a null **literal**. A literal `NULL` in expression
position SHALL be rejected with HTTP 400 naming the operand, here as everywhere else in the catalog
(`equals`, the numeric functions and the text functions all refuse one): a literal has no type, this
surface has no null in expression position, and `concat` SHALL NOT be the single exception that
accepts one. So `concat(deployment, parent_deployment)` over a row whose `parent_deployment` is null
yields the deployment alone, while `CONCAT(deployment, NULL)` — and equally `deployment || NULL`,
which lowers onto it — is a 400. A single operand SHALL be rejected with HTTP 400 naming the
arity, since that call is `to_string` and one operation SHALL have one spelling. Operand types SHALL
NOT be required to match. Both SHALL be advertised `distinct_supported: false`, and the SQL surface
SHALL refuse the quantifier outright (`CONCAT(DISTINCT a, b)` fails to validate); on the DSL surface a
`distinct` flag on either call SHALL be **ignored**, as it is for every other scalar in the catalog —
the flag is read only where a function defines it, which is the aggregates.

The two null rules differ **on purpose**, and the difference is what makes both functions worth having:
`to_string` preserves an absent value and `concat` absorbs one.

Both results are ordinary text scalars carrying **no restriction**: each SHALL be legal as a
projection item, a `group_by` key, a `sort` key, a filter operand, an aggregate's operand and an
argument of another scalar call. Neither SHALL change the number of rows a query returns.

#### Scenario: Concatenating a label from two columns
- **WHEN** a row-mode query selects `{"type":"fn","name":"concat","args":[{"type":"field","name":"parent_deployment"},{"type":"value","value_type":"string","value":" -> "},{"type":"field","name":"deployment"}]}` aliased `edge`
- **THEN** the query is accepted and each row's `edge` is the two column values joined by ` -> `, typed
  as text

#### Scenario: A number in a label
- **WHEN** a query selects `concat('depth ', to_string(array_length(execution_path)))`
- **THEN** the query is accepted and the cell reads `depth 3` for a row whose array holds three
  elements

#### Scenario: A null operand contributes the empty string
- **WHEN** a query selects `concat(deployment, '/', chat_id)` over a row whose `chat_id` is null
- **THEN** the cell is the deployment followed by `/`, and is not null

#### Scenario: A literal NULL operand is rejected
- **WHEN** a query selects `concat(deployment, null)`, or the SQL surface's `deployment || NULL`
- **THEN** the request fails with HTTP 400 and `error` `bad_request`, the message naming the operand as a
  null literal — the absorption above being a contract about a null value, not about a typeless literal

#### Scenario: to_string over each scalar family
- **WHEN** a query selects `to_string(x)` for `x` of each scalar type
- **THEN** each cell carries the rendering the table above states, and a null `x` yields **null** — the
  cast semantics — while the same null inside a `concat` contributes the empty string

#### Scenario: A null boolean renders as null, not as false
- **WHEN** a query selects `to_string(flag)` and `concat(widget_id, ':', flag)` over a table whose
  `flag` is a **nullable** `boolean` column, for a row whose `flag` is null
- **THEN** the `to_string` cell is **null** — not the string `false` — and the `concat` cell ends in
  `:`, the two absence contracts holding for a boolean exactly as they do for a nullable number or a
  nullable string

#### Scenario: A single-operand concat is rejected
- **WHEN** a query selects `concat(deployment)`
- **THEN** the request fails with HTTP 400 and `error` `bad_request`, the message naming the
  two-operand minimum and `to_string` as the single-value form

#### Scenario: A non-scalar operand is rejected
- **WHEN** a query selects `to_string(execution_path)`, `to_string(point_attributes)` or
  `concat(map_keys(point_attributes), 'x')`
- **THEN** the request fails with HTTP 400 and `error` `bad_request`, the message naming the operand and
  the projection-only restriction

#### Scenario: A rendered label is groupable
- **WHEN** an aggregate-mode query selects `concat(parent_deployment, ' -> ', deployment)` aliased
  `edge` together with `count()`, and names `edge` in `group_by`
- **THEN** the query is accepted and returns one row per distinct rendered label

### Requirement: Array text rendering and slicing

The function catalog SHALL contain the scalar functions `array_to_string(array, delimiter)` and
`array_slice(array, offset, length)`. Both take an array as their **first argument** under the
array-operand carve-out — a declared `array` column or a call returning an array (`map_keys`,
`split_string`, `json_extract_array`, `group_uniq_array`, `array_slice`) — and neither SHALL change
the number of rows a query returns.

**`array_to_string(array, delimiter)`** SHALL render the array as one text value: each element
rendered exactly as `to_string` renders a scalar of that type, joined by `delimiter` in the array's own
order. It SHALL return the **empty string** — never null — for an empty array. `delimiter` SHALL be a
string literal and MAY be empty. Its result is an ordinary text scalar carrying no restriction: it
SHALL be legal as a `group_by` key, a `sort` key, a filter operand and an argument of another scalar
call. This is what makes a `group_uniq_array` result renderable as a cell of text, which nothing else
in the catalog can do.

"Exactly as `to_string` renders a scalar of that type" SHALL include `to_string`'s **boolean** row: a
`boolean` element SHALL render as `true`/`false`, never as `1`/`0`, whichever physical type the
element sits on. The element type SHALL be derived the same way `array_join`'s result type is (see
"Array element expansion"), so a `group_uniq_array` over a boolean expression renders as
`false,true` and not as `0,1`. An element whose type cannot be derived SHALL render as every
non-boolean type does.

A **null element** SHALL contribute the **empty string in its own position** rather than being
dropped, so `['a', null, 'b']` joined on `,` is `a,,b` — the rendered cell carrying one delimiter per
gap between the operand's elements, whatever any of them hold. This is the absence contract
`concat` carries for an operand, and it is a deliberate departure from both same-named engine
functions: ClickHouse's `arrayStringConcat` and PostgreSQL's `array_to_string` (given no
`null_string`) both omit a null element and would answer `a,b`. It is rarely reachable —
`group_uniq_array` drops nulls, and a ClickHouse array column's elements are non-nullable — but it is
the contract wherever it is.

**`array_slice(array, offset, length)`** SHALL return the sub-array beginning at `offset` and holding
at most `length` elements. `offset` SHALL be a **non-zero** integer literal, **1-based**, counting from
the end when negative; `length` SHALL be a **positive** integer literal. A slice reaching past the end
of the array SHALL return the elements that exist rather than failing, and an `offset` past the end
SHALL return an empty array. A zero `offset`, a non-positive `length`, or a computed value for either
SHALL be rejected with HTTP 400 naming the argument.

`array_slice`'s result is an **array**, so it SHALL be projectable only, on the same footing as
`map_keys`, `split_string` and `json_extract_array`, and SHALL itself be legal at argument 0 of the
array-operand functions. That composition is the point of having it:
`array_to_string(array_slice(group_uniq_array(deployment), 1, 6), ', ')` is a capped, rendered cell,
and `array_join(array_slice(execution_path, 1, 3))` expands only the first three elements.

The **element type** of a slice SHALL be its operand's element type, unchanged — unlike the
text-producing array functions, whose element type is fixed at `string`, a slice narrows an array
rather than producing one. An `array_join` over a slice SHALL therefore carry the operand's element
type, and a projected slice SHALL be advertised with the operand's element type.

#### Scenario: Rendering a group's distinct values as one cell
- **WHEN** an aggregate-mode query grouped by `chat_id` selects
  `array_to_string(group_uniq_array(deployment), ', ')` aliased `models`
- **THEN** the query is accepted and each row's `models` is the group's distinct deployments joined by
  `, `, typed as text, with the empty string for a group holding no non-null value

#### Scenario: A capped rendered cell
- **WHEN** the same query wraps the operand as `array_to_string(array_slice(group_uniq_array(deployment), 1, 6), ', ')`
- **THEN** at most six values appear in each cell

#### Scenario: A rendered array is groupable and sortable
- **WHEN** an aggregate-mode query selects `array_to_string(execution_path, '>')` aliased `path` with
  `count()`, groups by `path` and sorts by it
- **THEN** the query is accepted — the result is text, not an array

#### Scenario: Boolean elements render as true and false
- **WHEN** a query selects `array_to_string(group_uniq_array(success), ',')` over a boolean column, and
  `array_to_string(group_uniq_array(not_empty(parent_deployment)), ',')` over a boolean-valued call
- **THEN** both cells read `false,true` — the same text `to_string` gives for each element — and
  neither reads `0,1`, even though the second array's elements are physically `UInt8`

#### Scenario: An empty array renders as the empty string
- **WHEN** a query selects `array_to_string(execution_path, ',')` over a row whose array is empty
- **THEN** the cell is the empty string and is not null

#### Scenario: A slice is projection-only
- **WHEN** a query uses `array_slice(...)` as an operand of an expression that is not one of the
  array-operand functions, or as a filter comparand
- **THEN** the request fails with HTTP 400 and `error` `bad_request`, the message naming the function
  and the projection-only restriction — exactly the positions that restriction already covers on the
  execution path, no wider (a `sort` or `group_by` key naming a projected `array_slice` output is a
  **SQL-surface** rejection, see [sql-query-translation](../sql-query-translation/spec.md), on the same
  footing as a projected `map_keys` output, and this capability's `/execute` path resolves such a key
  against the select output as it does for every other computed projection)

#### Scenario: A slice keeps its operand's element type
- **WHEN** an aggregate-mode query selects `array_join(array_slice(group_uniq_array(total_tokens), 1, 3))`
- **THEN** the expanded column is advertised as a numeric column, not as text

#### Scenario: Invalid bounds are rejected
- **WHEN** a query selects `array_slice(execution_path, 0, 3)`, `array_slice(execution_path, 1, 0)` or
  a slice whose `offset`/`length` is a column reference
- **THEN** each request fails with HTTP 400 and `error` `bad_request`, the message naming the argument
  at fault

#### Scenario: A computed delimiter is rejected
- **WHEN** a query selects `array_to_string(execution_path, deployment)`
- **THEN** the request fails with HTTP 400 and `error` `bad_request`, the message stating that the
  delimiter must be a string literal

### Requirement: A keyed grain join is bounded by an aligned partition column

A keyed enrichment join SHALL restrict the enrichment side by the source's partition values, in addition to
its grain keys, whenever the catalog declares the enrichment's partition column to be aligned with its
source's. The restriction SHALL be
`enrichment.partition_column IN (SELECT source.partition_column FROM source WHERE <source predicates>)`,
emitted inside the derived table's `WHERE` alongside the grain-key restriction and drawn from the same
predicates — except where "A partition-only key on an aligned enrichment is transferred, not keyed" replaces
both sets with the transferred bounds, because every source predicate keying the join is a bound on the
partition column itself.

Alignment SHALL be declared, never inferred. Both definitions SHALL declare a partition column and the two
SHALL be the **same column name**; the shared name is the whole of the claim, namely that for a given grain
key both tables hold the same value in that column. A source that declares no partitioning, an enrichment
that declares none, and a pair whose columns are named differently SHALL all be emitted exactly as before,
keyed on the grain key alone. Partition **granularity** SHALL NOT be compared: the restriction is a set of
column values, so it prunes correctly whatever expression either table partitions that column by.

The restriction SHALL be placed in the derived table rather than on the join condition. Both prune the
scan, but only the inner form also narrows what the derived table projects, and projecting a heavy column
across every part is the cost being removed.

**Where a statement declares the `object` paths it addresses, the derived table SHALL project those paths
and not the whole `object` column.** Where every expression in the statement that references an `object`
column addresses a *compile-time literal* path within it, the derived table SHALL project those paths as individual
subcolumns, each aliased to its own dotted path (`request_tags.baggage`) as a single quoted identifier, and
the outer statement SHALL address that alias. Where any reference addresses no statically known path, the
whole column SHALL be projected as before.

**A statement SHALL declare which paths it addresses, and one that declares nothing SHALL project whole
columns.** The paths are derived from the statement's own clauses and handed to the join, rather than
discovered by the join from the statement, because the join is emitted before those clauses are rendered
and is built from referenced *names*. A caller that renders a clause the declaration did not account for
would address a column its own join no longer projects, so the safe direction is explicit: no declaration
means no pruning, and the statement renders exactly as it did before this requirement existed. This is what
lets the rule be adopted per call site rather than everywhere at once.

The statements that declare their usage SHALL be the Class-1 materialize probe and its write, and the
query surface: the single-entity read statement (including the page block and the outer statement of a
late-materialized read), its `include_total` count statement, and every block of a composite SQL statement.
The enrichment scan, the group-fetch projection and the aggregate refresh and its preview SHALL declare none
and SHALL render exactly as they did before this requirement. Naming them is what makes the previous paragraph
falsifiable: "adopted per call site" with no list is a claim no reader can check.

A query-surface statement SHALL derive its usage from every clause it renders against the joined enrichments:
its `select` expressions, `filter`, `having`, `sort` keys and `group_by` keys, and the enrichment conjuncts and
scalar calls that read shaping pushes into a derived table (their copies in the outer statement address the
same paths). A `sort` or `group_by` key naming an `object` column, and an `object` column projected bare, SHALL
make that column whole. Every expression the outer statement renders SHALL address a pruned path through its
alias, and an expression rendered *inside* the derived table (a pushed conjunct's `WHERE` copy, a pushed call)
SHALL keep addressing the enrichment's own column, which is in scope there. The count statement SHALL derive
its usage from the clauses it renders, which is its filter.

On the query surface this is what keeps a keyed join cheap for a path read: `json_extract_string(jwt_claims, 'email')`
grouped over a month of history goes from exceeding the memory budget to completing in about a second, and
the same path as a filter roughly halves its time and memory, returning the same rows.

A path key containing a dot SHALL fall back to the whole column. The alias joins the column name and the
path with dots into one identifier, so the single key `a.b` and the two-segment path `a`,`b` would collide
on `request_tags.a.b`; one derived table projecting both under one name resolves the outer reference to
whichever it chooses, and one of the two reads silently returns the other's value. The fallback SHALL be per column rather than per statement: one
unprunable reference costs its own column its pruning and no other's. The references that SHALL fall back
are `json_extract_string_ci` and any use of the column as a value in its own right —
a bare projection, a comparison, or an argument to a function taking the whole document.
`json_extract_array` SHALL NOT fall back: it addresses a compile-time literal path like the four scalar
extractors, so a statement whose only reference to an `object` column is an array extraction SHALL prune
that column's projection.

Without this, keying a join makes an `object` column *more* expensive to read rather than less. ADAS
renders a single-path read of an `object` field as native subcolumn access, which reads an order of
magnitude less data than the serialized form; a derived table projecting the whole column has materialized every path before that
expression runs, so the optimization survives only where the join is left unkeyed — the opposite of the
intent. The same single-path filter returns instantly through an unkeyed join
and cannot complete even a window of a few minutes through a keyed one.

An empty source-predicate set SHALL emit no restriction of either kind. A statement with no source
restriction has no value set to offer, and bounding an enrichment by an empty set would restrict it to
nothing.

This requirement exists because restricting a join to a grain key bounds what it *matches* without bounding
what it *reads*. Where an enrichment's grain key is uncorrelated with its partitioning — a hashed
`event_id` over daily partitions — a lookup for one key value lands a candidate granule in every part,
because the row genuinely might be in any of them, and the grain-key set cannot exclude one. That is on
the order of a GiB per heavy column to return a single row, on every path that reads
a body, keyed or unkeyed. The value that excludes the other partitions is the source's own partition
column, and a statement that identifies a row usually already carries it.

The restriction is only as sound as the declaration behind it, which is why nothing derives it from two
columns happening to share a name at query time. A false claim does not fail: it restricts the enrichment
to partitions that cannot hold the row, and the outer join then yields the column's type default —
indistinguishable from a row that legitimately has no enrichment.

#### Scenario: An aligned enrichment is bounded by the source's partition values

- **GIVEN** a source partitioned on `day` and an enrichment declaring `day` as its own partition column
- **AND** a query filtering on a source column alone and selecting an enrichment column
- **WHEN** the statement is rendered
- **THEN** the derived table carries
  `enrichment.day IN (SELECT source.day FROM source WHERE <filter>)`
- **AND** it also carries the grain-key restriction over the same predicates
- **AND** the join condition remains the single grain-key equality

#### Scenario: An enrichment that only co-stores the column name is not aligned

- **GIVEN** a source partitioned on `day` and an enrichment that holds a column named `day` but declares no
  partition column
- **WHEN** the statement is rendered
- **THEN** the derived table carries the grain-key restriction only
- **AND** no predicate names the enrichment's `day`

#### Scenario: Differently-named partition columns are not aligned

- **GIVEN** a source partitioned on `day` and an enrichment partitioned on `created_on`
- **WHEN** the statement is rendered
- **THEN** the join is keyed on the grain key alone

#### Scenario: A single-path reference prunes the derived table's projection

- **GIVEN** a statement that declares its object-path usage
- **AND** an enrichment holding an `object` column and a statement whose only reference to it is
  `json_extract_string(<column>, 'k')`
- **WHEN** the keyed join is rendered
- **THEN** the derived table projects `<column>.k` aliased as the single identifier `<column>.k`
- **AND** the whole `object` column does not appear in the derived table's projection
- **AND** the outer expression addresses that alias

#### Scenario: A case-insensitive reference falls back to the whole column

- **GIVEN** a statement that declares its object-path usage
- **AND** the same enrichment and a statement referencing it with `json_extract_string_ci`
- **WHEN** the keyed join is rendered
- **THEN** the derived table projects the whole `object` column, exactly as before

#### Scenario: An array extraction prunes the derived table's projection

- **GIVEN** a statement that declares its object-path usage
- **AND** an enrichment holding an `object` column and a statement referencing it with
  `json_extract_array(<column>, 'roles')` and `json_extract_string(<column>, 'sub')`
- **WHEN** the keyed join is rendered
- **THEN** the derived table projects `<column>.roles` and `<column>.sub` as aliased subcolumns
- **AND** the whole `object` column does not appear in the derived table's projection
- **AND** neither reference costs the other its pruning

#### Scenario: One unprunable reference does not cost another column its pruning

- **GIVEN** a statement that declares its object-path usage
- **AND** a statement referencing one `object` column by a literal path and a second `object` column
  through `json_extract_string_ci`
- **WHEN** the keyed join is rendered
- **THEN** the first column is projected as its addressed paths and the second as the whole column

#### Scenario: A statement that declares no object-path usage projects whole columns

- **GIVEN** a keyed join for an enrichment holding an `object` column
- **AND** a statement that addresses a literal path within it but declares no object-path usage
- **WHEN** the statement is rendered
- **THEN** the derived table projects the whole `object` column
- **AND** the outer statement addresses it as nested subcolumn segments, exactly as before this requirement

#### Scenario: A query with no source predicates is joined unkeyed

- **GIVEN** an aligned source and enrichment
- **AND** a query with no filter
- **WHEN** the statement is rendered
- **THEN** the enrichment is joined as a bare table with no restriction of either kind

#### Scenario: A query-surface path read prunes its keyed derived table

- **GIVEN** a query over a source with a source-only filter that groups by
  `json_extract_string(<object column>, 'email')` of an enrichment
- **WHEN** the statement is rendered
- **THEN** the enrichment's derived table projects `<object column>.email` aliased as that single identifier and
  not the whole `object` column
- **AND** the outer projection and `GROUP BY` address that alias
- **AND** the result equals the result of the same statement rendered with whole columns

#### Scenario: A column referenced whole and by path stays whole on the query surface

- **GIVEN** a query that filters on `json_extract_string(<object column>, 'email')` and also projects the bare
  `<object column>`
- **WHEN** the statement is rendered
- **THEN** the derived table projects the whole `object` column and the filter addresses it as nested subcolumn
  segments

#### Scenario: A pushed enrichment conjunct keeps its inner copy on the real column

- **GIVEN** a query whose filter has a pushed enrichment conjunct beside a literal-path read of an `object`
  column of the same enrichment
- **WHEN** the statement is rendered
- **THEN** the derived table's `WHERE` copy of the conjunct addresses the enrichment's own columns
- **AND** every reference in the outer statement to the pruned path addresses its alias

#### Scenario: The count statement prunes as its filter reads

- **GIVEN** an offset-paged query with `include_total` whose filter reads `json_extract_string(<object column>,
  'email')` through a keyed join
- **WHEN** the count statement is rendered
- **THEN** its derived table projects `<object column>.email` and not the whole column

#### Scenario: The aggregate refresh still declares no usage

- **GIVEN** an aggregate pipeline whose block reads `json_extract_string(<object column>, 'email')` through a
  restricted grain join
- **WHEN** its refresh statement is compiled
- **THEN** the derived table projects the whole `object` column, exactly as before this change

### Requirement: The cost of reading a native JSON column scales with paths times parts

A reader of a native `JSON` (`object`) column through its serialized text SHALL be specified as costing one
read stream per JSON path per part, rather than as a cost per row. Registration of a pipeline whose
`filter` or output expressions read an `object` column through `json_extract_string_ci` SHALL report
that choice back to the author, naming `json_extract_string` as the cheaper single-path alternative,
and SHALL NOT refuse it.

Registration SHALL NOT report `json_extract_array`. It addresses a literal path and reads one subcolumn,
so there is no cheaper spelling to name, and a note saying otherwise would send an author looking for one
that does not exist.

Reporting rather than refusing is the correct enforcement point because affordability is a property of the
table's physical layout, not of the declaration: the same expression is cheap on a compacted table and
ruinous on a fragmented one, and the layout changes under the declaration without the declaration
changing. A static refusal would reject affordable uses and still admit unaffordable ones, and
`json_extract_string_ci` is sometimes genuinely required, because the key's case is not always knowable.
The resource budget is what bounds the cost; registration's job is to make sure the author chose the
expensive form knowingly.

The previous characterisation — that these readers materialize the whole document per row — understates
the cost by the part count, which is what made the trap invisible. A
`dial_usage_log_payload.request_tags` column can be small on disk in total, yet carry hundreds of
`request_tags.*` files (one or two per path) in each of hundreds of active parts; at ~4 MiB of read buffer per stream a 2 GiB
ceiling is exhausted by buffers alone. The cost is therefore independent of both the window and the data
volume: a window matching **zero** rows is refused identically to one matching every row.

The cost SHALL be specified as amplified by `FINAL`, which every Class-1 materialize write sets: over
the same two `JSON` columns, the whole columns cost on the order of a GiB at peak and over a minute, against an
order of magnitude less memory and time for the pruned subcolumns. Pruning is therefore what keeps a
Class-1 write inside the resource ceiling, not a saving on top of one that would fit anyway.

#### Scenario: A case-insensitive read over an object column is reported at registration

- **GIVEN** a pipeline whose filter calls `json_extract_string_ci` over an `object` column
- **WHEN** it is registered or patched
- **THEN** the declaration is accepted
- **AND** the response reports that the expression reads the whole document, one stream per path per part,
  and names `json_extract_string` as the single-path alternative

#### Scenario: A single-path read over an object column is reported as nothing

- **GIVEN** a pipeline whose filter calls `json_extract_string` over an `object` column
- **WHEN** it is registered or patched
- **THEN** the declaration is accepted with no such report

#### Scenario: A case-insensitive read is never refused

- **GIVEN** a pipeline whose filter calls `json_extract_string_ci` over an `object` column whose part count
  would make the read exceed the configured ceiling
- **WHEN** it is registered
- **THEN** registration succeeds, and the cost is bounded at read time by the resource budget instead

#### Scenario: An array extraction is not reported at registration

- **GIVEN** a pipeline whose Class-1 output expression reads an `object` column through
  `json_extract_array`
- **WHEN** it is registered or patched
- **THEN** the declaration is accepted with no note for that expression

### Requirement: Query execution requires TABLE_READ on every resolved table

`POST /v1/queries/execute` SHALL require `TABLE_READ` — held globally or granted on the table — for
**every** table the query resolves, the entity's source and each enrichment it reads alike. A caller
lacking it on any resolved table SHALL be refused as though that table did not exist: the response
SHALL be `400 bad_request` reporting an unknown entity or unknown field, never `403`, and no error
path SHALL confirm the table's or column's existence.

#### Scenario: A readable entity executes

- **GIVEN** a source table whose `read` list contains the caller's provider role
- **WHEN** the caller executes a query against that entity
- **THEN** the query resolves and executes normally

#### Scenario: An unreadable entity is unknown

- **WHEN** a caller with no read privilege on a source executes a query naming that entity
- **THEN** the response is `400 bad_request` reporting an unknown entity, and does not reveal that
  the entity exists

### Requirement: Composite entity visibility is decided per owning table

A queryable entity is a source table flattened with its active enrichments. Read visibility SHALL be
decided **per owning physical table** at the single point where the resolved query model is
assembled, so the same entity resolves to different flat schemas for different callers.

An entity SHALL be visible if and only if its **source** is readable by the caller. For a visible
entity, the columns of each enrichment the caller cannot read SHALL be **absent** from the model, so
they are omitted from discovery and referencing one fails as an ordinary unknown field. Omitting an
enrichment SHALL NOT change the result's row count, because the grain join contributes at most one
row per source row.

A `read` grant on an enrichment table alone SHALL confer nothing on the query surface: an enrichment
is not a queryable entity, so there is no statement that can reach its rows. The service SHALL NOT
serve an enrichment-only read through its source's entity, because the grain key is the source's
column and the statement's `FROM` is the source table, so doing so would disclose the source's row
population and its grain-key values.

#### Scenario: An unreadable enrichment's columns are absent

- **GIVEN** an entity whose source the caller may read and one of whose enrichments it may not
- **WHEN** the caller requests that entity's schema and then queries it
- **THEN** the enrichment's columns are absent from the schema, referencing one fails as an unknown
  field, and the row count matches what a fully-privileged caller receives

#### Scenario: An entity is invisible when its source is

- **GIVEN** an entity whose source the caller may not read but one of whose enrichments grants it
  `read`
- **WHEN** the caller lists entities or queries that entity
- **THEN** the entity is absent from discovery and the query reports an unknown entity

#### Scenario: Sensitive columns are decided per owning table too

- **GIVEN** an entity whose source and one enrichment both carry sensitive columns, and a caller
  holding `read` on both tables and `sensitive` on the source only
- **WHEN** the caller queries the entity
- **THEN** the source's sensitive columns resolve and the enrichment's do not

### Requirement: Lambda expressions

The system SHALL accept a `lambda` expression — the eighth `Expr` kind — spelled
`{ "type": "lambda", "params": ["x"], "body": <expression> }`. It exists to carry the predicate or
projection a higher-order array function applies to each element, and it SHALL be legal **only** as
the designated lambda argument of such a function ("Higher-order array functions"). A `lambda` in any
other position — a `select` entry, a filter operand, an ordinary function argument, a `group_by` or
`sort` key — SHALL be rejected with HTTP 400 `bad_request` naming the position.

`params` SHALL hold exactly one name. The grammar admits a list because ClickHouse's own
multi-parameter forms (`arrayMap((x, y) -> …, a, b)`) are spelled that way, but every function in
this catalog takes one array, so a `params` list of any other length SHALL be rejected with HTTP 400
rather than silently ignoring the extra names. Each name SHALL match `[a-z_][a-z0-9_]{0,63}` and
SHALL be rejected with HTTP 400 otherwise — the parameter is the one caller-supplied token that
reaches the rendered statement as a bare identifier, because ClickHouse does not accept a quoted
identifier in that position.

**The parameter is referenced by its own expression kind.** A parameter SHALL be named by
`{ "type": "lambda_param", "name": "x" }`, resolved against the parameters of the enclosing
lambdas — innermost first. A `lambda_param` naming no parameter in scope SHALL be rejected with
HTTP 400 naming the name and the parameters that are in scope, and a `lambda_param` outside any
lambda body SHALL be rejected likewise.

**The body's scope is the parameter plus everything the statement can already see.** A `field`
expression inside a body SHALL resolve against the enclosing statement's columns exactly as it does
outside one, so a body MAY capture a column of the enclosing row —
`arrayExists(x -> starts_with(x, deployment), roles)` — as it does in ClickHouse. A parameter SHALL
shadow a column of the same name, innermost binding winning, which is also ClickHouse's rule: inside
the body of a lambda declaring `"params": ["deployment"]`, **both** `{ "type": "lambda_param", "name":
"deployment" }` and `{ "type": "field", "name": "deployment" }` SHALL resolve to the parameter, and
neither to the column. This is not a preference between two defensible readings. The SQL surface
shadows (an identifier equal to the parameter is the parameter), and the DSL → SQL renderer emits a
`field` as exactly that identifier — so a `field` resolving to the column here would render a
statement meaning something else than the document it was rendered from.

A captured column is an ordinary column read and SHALL be governed as one: it SHALL count as a
column the statement reads for every rule keyed on that (heavy-column and partition-pruning
advisories, enrichment-join derivation), and a column the caller may not see SHALL be rejected as an
unknown field inside a body exactly as it is in a projection — the sensitive-column rules are not
weakened by a lambda.

An **aggregate** or **window** call SHALL NOT appear inside a lambda body, and SHALL be rejected with
HTTP 400 naming the call: the body is evaluated per element of one row's array, which is not a
position an aggregate can occupy. An aggregate may of course produce the *array* the body is applied
to (see "Array-valued aggregates over an array field").

#### Scenario: A lambda binds as the first argument of a higher-order call

- **WHEN** `{ "type": "fn", "name": "arrayExists", "args": [ { "type": "lambda", "params": ["x"], "body": { "type": "fn", "name": "starts_with", "args": [ { "type": "lambda_param", "name": "x" }, { "type": "value", "value_type": "string", "value": "coder-" } ] } }, { "type": "field", "name": "usage_client_identity.user_roles" } ] }` is posted to `POST /v1/queries/execute`
- **THEN** the expression binds, the body's `x` resolves to the array's element type, and the query executes

#### Scenario: A lambda outside a higher-order call is rejected

- **WHEN** a `select` entry's expression is a bare `lambda`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the position a lambda is not legal in

#### Scenario: A lambda body captures a column of the enclosing row

- **WHEN** a body references `{ "type": "field", "name": "deployment" }` alongside its parameter `x`
- **THEN** the query executes, comparing each element of the array against that row's `deployment` value, as the same expression does in ClickHouse

#### Scenario: A parameter shadows a column of the same name

- **WHEN** a lambda declares `"params": ["deployment"]` and its body references `{ "type": "lambda_param", "name": "deployment" }`
- **THEN** the reference resolves to the parameter, not to the column

#### Scenario: A field naming the parameter resolves to the parameter

- **WHEN** a lambda declares `"params": ["deployment"]` and its body references `{ "type": "field", "name": "deployment" }`
- **THEN** the reference resolves to the parameter and renders as the bare parameter identifier, never as the qualified column — the same reading the SQL surface gives the same statement

#### Scenario: A sensitive column cannot be captured

- **WHEN** a caller who may not see sensitive columns captures one inside a lambda body
- **THEN** the request is rejected with HTTP 400 as an unknown field, exactly as projecting that column is

#### Scenario: An unbound parameter reference is rejected

- **WHEN** a body references `{ "type": "lambda_param", "name": "y" }` while the lambda's only parameter is `x`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming `y` and the parameters in scope

#### Scenario: An aggregate inside a lambda body is rejected

- **WHEN** a body calls `max(deployment)`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the aggregate, because a body is evaluated per element of one row's array

#### Scenario: An illegal parameter name is rejected

- **WHEN** a lambda declares `"params": ["x; drop"]`
- **THEN** the request is rejected with HTTP 400 `bad_request`, and no statement is rendered

#### Scenario: More than one parameter is rejected

- **WHEN** a lambda declares `"params": ["x", "y"]`
- **THEN** the request is rejected with HTTP 400 `bad_request` stating that one parameter is accepted

### Requirement: Higher-order array functions

The system SHALL accept five higher-order array functions, spelled and ordered exactly as
ClickHouse spells and orders them — **the lambda first, the array second**:

| Function | Returns | Empty array |
|---|---|---|
| `arrayExists(x -> boolean, array)` | boolean | `false` |
| `arrayAll(x -> boolean, array)` | boolean | `true` |
| `arrayCount(x -> boolean, array)` | integer | `0` |
| `arrayFilter(x -> boolean, array)` | array of the operand's element type | empty array |
| `arrayMap(x -> expression, array)` | array of the body's type | empty array |

Argument 0 SHALL be a `lambda` and nothing else; argument 1 SHALL be an array — an `array` field, or
an array-returning call, both of which are otherwise projection-only. This is the same positional
carve-out the array-operand functions already have, at the position ClickHouse puts the array in. The
body of the first three SHALL evaluate to boolean; a non-boolean body SHALL be rejected with HTTP 400.
An **empty** array SHALL produce the value in the table above — never null and never an error — which
is ClickHouse's own answer for each. These functions are rendered without the null-normalising wrapper
the older `array_has*` family carries: their contract is ClickHouse's, so a caller reading the
ClickHouse documentation reads this surface correctly.

`arrayExists`, `arrayAll` and `arrayCount` return scalars, so their results SHALL carry no
projection-only restriction and SHALL be legal in filter position, in `having`, as a `group_by` or
`sort` key, and as `if`'s `condition`. `arrayFilter` and `arrayMap` return arrays, so their results
SHALL be projection-only on the same footing as `group_uniq_array`'s and `split_string`'s — legal as
a projection item, and legal in the array-operand position of an array-operand function, which is
what makes `array_to_string(arrayFilter(x -> starts_with(x, 'coder-'), roles), ', ')` express "the
coder roles this user holds" in one cell.

A higher-order call SHALL be legal as the array argument of another, so
`arrayExists(x -> …, arrayFilter(y -> …, roles))` composes.

**Cost.** A higher-order call reads every element of the array and cannot use a data-skipping index,
where `array_has_any` over an enumerated value set can. The catalog entry SHALL say so: these are
the expressive functions, not the fast ones, and on a large scan the enumerated form remains the
cheaper shape.

#### Scenario: A prefix test over an array field filters rows

- **WHEN** a query filters on `arrayExists(x -> starts_with(x, 'coder-'), "usage_client_identity.user_roles")`
- **THEN** only rows whose array holds at least one element with that prefix are returned, and the array field is not rejected as projection-only

#### Scenario: A higher-order result is groupable when it is scalar

- **WHEN** a query groups by `arrayCount(x -> starts_with(x, 'coder-'), "usage_client_identity.user_roles")`
- **THEN** the query is accepted and groups by the per-row count

#### Scenario: An array-returning higher-order call is projection-only

- **WHEN** a query sorts by `arrayFilter(x -> starts_with(x, 'coder-'), "usage_client_identity.user_roles")`
- **THEN** the request is rejected with HTTP 400 under the same projection-only restriction that governs `group_uniq_array`

#### Scenario: An array-returning higher-order call renders as text

- **WHEN** a query projects `array_to_string(arrayFilter(x -> starts_with(x, 'coder-'), "usage_client_identity.user_roles"), ', ')`
- **THEN** the query is accepted and the column is typed as text

#### Scenario: A non-boolean predicate body is rejected

- **WHEN** a query calls `arrayExists(x -> upper(x), roles)`
- **THEN** the request is rejected with HTTP 400 stating that the lambda must evaluate to a boolean

#### Scenario: An empty array answers without an error

- **WHEN** `arrayExists`, `arrayAll` and `arrayCount` are evaluated over a row whose array is empty
- **THEN** they answer `false`, `true` and `0` respectively, and none of them answers null

#### Scenario: A lambda parameter is typed as the array's element

- **WHEN** a body calls a text function over the parameter of a `string` array
- **THEN** the call is accepted, and the same body over an array of another element type is rejected
  naming the operand type — the same operand check a call outside a lambda gets

### Requirement: Regular-expression matching

The system SHALL accept `match(haystack, pattern)`, returning boolean — ClickHouse's RE2 match,
under ClickHouse's own name. It completes the text-predicate family alongside `starts_with` and
`contains`, and is what makes a role test that is neither a prefix nor an exact value expressible
inside a lambda body (`arrayExists(x -> match(x, '^coder-(a|b)$'), roles)`).

Both operands SHALL be ordinary expressions, as in ClickHouse — the pattern is not restricted to a
literal. A pattern the engine cannot compile SHALL surface through the existing ClickHouse-failure
response rather than a new error code; it is not detected before execution, exactly as any other
statement that validates here and fails there.

#### Scenario: A regex predicate filters rows

- **WHEN** a query filters on `match(deployment, '^gpt-4')`
- **THEN** only rows whose `deployment` matches the expression are returned

#### Scenario: A regex predicate is legal inside a lambda body

- **WHEN** a query filters on `arrayExists(x -> match(x, '^coder-'), "usage_client_identity.user_roles")`
- **THEN** the query is accepted and evaluates the expression per element

### Requirement: Array-valued aggregates over an array field

The system SHALL accept two aggregates whose operand is an `array` field or an array-returning call,
spelled as ClickHouse spells them:

- `groupUniqArrayArray(array)` — the group's arrays **flattened and deduplicated**, returning an
  array of the operand's element type. It is what makes "the set of roles this user held anywhere in
  the window" a value, and therefore what makes the role test expressible **after** aggregation
  rather than as a row filter.
- `groupArrayArray(array)` — the group's arrays flattened with duplicates and each row's element
  order preserved; the order *between* rows SHALL be unspecified.

Both SHALL return an empty array — never null — for a group whose arrays are all empty, and neither
SHALL accept `distinct`: `groupUniqArrayArray` already returns a set, and deduplicating
`groupArrayArray` would make it the other function. Both results are arrays and SHALL therefore be
projection-only, exactly as `group_uniq_array`'s is.

**Neither SHALL accept an aggregate as its operand.** The carve-out that admits an array at that
position exists for an array *value* — an `array` field, or a scalar call producing one such as
`arrayFilter`, `map_keys` or `split_string` — and these two are themselves aggregates, so
`groupUniqArrayArray(group_uniq_array(x))` is a nested aggregate, which ClickHouse refuses outright.
It SHALL be rejected with HTTP 400 naming the inner call, on both surfaces and for every aggregate
(`max(x)` as much as an array-valued one), rather than reaching the engine as a server error.

**This narrows the operand rule stated for `arg_max` under "Supported function catalog".** An array
SHALL be legal as `arg_max`'s **`value`** operand, making `arg_max(roles, request_time)` — "the roles
as of this user's last request" — expressible; the result is then an array and is projection-only in
its turn. The `order` operand SHALL remain scalar. An array-valued **aggregate** SHALL remain rejected
in that position: `arg_max` is itself an aggregate, so `arg_max(group_uniq_array(x), ts)` is a nested
aggregate, which ClickHouse refuses outright — the carve-out admits an array *value*, not an
aggregate. No other aggregate SHALL accept an array operand:
`sum`, `min`, `max`, `avg` and `count` over an array stay rejected, and `group_uniq_array(<array>)`
stays rejected because its result would be an array of arrays, which the DSL has no type for — its
rejection message SHALL name `groupUniqArrayArray` as the function that flattens instead.

Together with "Higher-order array functions" this is what makes the two post-aggregation readings of
a role question expressible in **one** statement: *ever in the window* —
`having arrayExists(x -> starts_with(x, 'coder-'), groupUniqArrayArray(roles))` — and *as of the
last request* — the same test over `arg_max(roles, request_time)`. Both leave the aggregate's own
measures (`sum(price)`, `count()`) computed over the unfiltered group, which is precisely what a row
filter on the roles cannot do.

**Cost.** Each holds an accumulator per group whose size grows with the group's distinct elements.
The catalog entry SHALL say so; the `unbounded_group_by` advisory already reported by
`POST /v1/queries/validate` for an unfiltered `GROUP BY` covers the scan side.

#### Scenario: The union of a group's arrays is a value

- **WHEN** a query groups by `"usage_client_identity.user_ref"` and projects `groupUniqArrayArray("usage_client_identity.user_roles")`
- **THEN** the column holds each user's distinct roles across the whole window, typed as an array of string

#### Scenario: Cost is filtered on a role held at any point in the window

- **WHEN** a query groups by user, projects `sum(deployment_price)`, and applies `having arrayExists(x -> starts_with(x, 'coder-'), groupUniqArrayArray("usage_client_identity.user_roles"))`
- **THEN** the returned cost is the user's whole-window cost, not only the rows carrying the role, and users holding no such role are absent

#### Scenario: The latest value of an array column is an array

- **WHEN** a query projects `arg_max("usage_client_identity.user_roles", request_time)` grouped by user
- **THEN** the column holds the roles of that user's latest row, typed as an array, and is projection-only

#### Scenario: An ordinary aggregate over an array stays rejected

- **WHEN** a query projects `sum(execution_path)` or `group_uniq_array(execution_path)`
- **THEN** the request is rejected with HTTP 400 naming the field and the projection-only restriction, and the `group_uniq_array` message names `groupUniqArrayArray` as the flattening alternative

#### Scenario: An aggregate is not an arg_max value

- **WHEN** a query projects `arg_max(group_uniq_array(deployment), request_time)`
- **THEN** the request is rejected with HTTP 400 naming `group_uniq_array`, because an aggregate inside an aggregate is not expressible

#### Scenario: An aggregate is not a flattening aggregate's operand

- **WHEN** a query projects `groupUniqArrayArray(group_uniq_array(deployment))` or `groupArrayArray(max(deployment))`
- **THEN** the request is rejected with HTTP 400 naming the inner call as an aggregate, because an aggregate inside an aggregate is not expressible

#### Scenario: An array-valued scalar is a flattening aggregate's operand

- **WHEN** a query projects `groupUniqArrayArray(arrayFilter(x -> not_empty(x), execution_path))`
- **THEN** the query executes, because the carve-out admits an array value and a scalar call is one

#### Scenario: distinct is rejected on both aggregates

- **WHEN** a query sets `distinct` on `groupUniqArrayArray` or `groupArrayArray`
- **THEN** the request is rejected with HTTP 400 rather than the flag being ignored

### Requirement: Relative time expressions

The function catalog SHALL additionally contain three scalar functions that together express a window
relative to the moment a query runs, so a **stored** query can mean "the last two hours" rather than
two particular instants:

| Function | Group | Arity / signature | Returns |
|---|---|---|---|
| `now` | scalar | `now()` (0 args) | timestamp |
| `date_add` | scalar | `date_add(unit, amount, timestamp)` (3 args) | timestamp |
| `date_sub` | scalar | `date_sub(unit, amount, timestamp)` (3 args) | timestamp |

**`now()`.** The function SHALL take no arguments; a call carrying any argument SHALL be rejected with
HTTP 400 and `error` `bad_request`. Its value SHALL be the current instant **in UTC at millisecond
precision**, and SHALL be read from the **data store's** clock at execution rather than from this
service's, because the comparison it takes part in is evaluated there — a caller reconciling a result
against the store's own clock must not be shown a third one. Its declared return type is `timestamp`,
so it is comparable with a `timestamp` column without a cast.

**What one statement observes is what ClickHouse observes.** Each `now()` **call site** SHALL yield
one value for the whole executed statement — the same value in every row it returns and every row it
filters — and SHALL NOT be re-read per row. Separate call sites SHALL be evaluated independently, as
ClickHouse evaluates them: two `now()` calls in one statement MAY yield values one or more
milliseconds apart when they straddle a clock tick. This service SHALL NOT coordinate them — it SHALL
NOT substitute a literal, an injected clock or a bound parameter for the call — because a ClickHouse
query must behave here exactly as it does in ClickHouse.

The consequence for callers is the one ClickHouse already has. A window written with two calls —
`request_time >= date_sub('hour', 1, now()) AND request_time < now()`, or in SQL
`request_time >= now() - INTERVAL 1 HOUR AND request_time < now()` — MAY see its two bounds a
millisecond apart, so a row stamped exactly at an edge can fall in or out of the window by that margin,
and a query filtering on one call and projecting another MAY report a projected instant that differs
from the one its filter bound was computed from by the same margin. A caller that needs both bounds
from one instant has to supply that instant itself, as a literal; two calls are not guaranteed to
agree. An `include_total` count is a
**separate** statement (see "Response envelope") and SHALL evaluate its own `now()`, so its total MAY
be taken against a window that ended a moment later than the page's.

**`date_add` / `date_sub`.** Both SHALL take exactly three arguments — a `unit` string literal, an
`amount` integer literal, and a `timestamp` expression — in that order. The name and order are borrowed
from the underlying engine's own function of the same name, which takes the same three in the same
order; the borrowing is deliberately inexact, because the engine spells the unit as a bare interval
keyword while this DSL spells it as a **string literal**, as every other unit-taking function here does
(`date_trunc('day', …)`, `date_bin(5, 'minute', …)`). A call written for this DSL is therefore not
itself valid engine SQL, and nothing depends on it being so: the rendered statement carries the infix
interval form, not the function. What the shared order buys is one less thing to re-learn when moving
between the two. Any other arity, a non-literal `unit` or
`amount`, an `amount` that is not a positive integer, or a `unit` outside the set below SHALL be
rejected with HTTP 400 and `error` `bad_request` naming what was expected. `date_add` SHALL return the
operand advanced by `amount × unit` and `date_sub` the operand moved back by it; the direction is
carried by the function name, which is why a negative `amount` is refused rather than silently
inverting the call.

The accepted `unit` values SHALL be exactly `second`, `minute`, `hour`, `day`, `month`, `year`. This is
**narrower than `date_trunc`'s** unit set, deliberately: `week` and `quarter` name a *calendar
alignment* that no other unit can express, which is why `date_trunc` needs them, but as an *offset* one
week is exactly seven days and one quarter exactly three months, so admitting them would give one
operation two spellings and cost a stable round trip for nothing.

`second`, `minute`, `hour` and `day` SHALL be fixed-width offsets. `month` and `year` SHALL be
**calendar** steps evaluated in UTC — one month before 31 March is 28 February (or 29), not a fixed
number of days — matching the underlying engine and PostgreSQL, and differing from a day-count offset
in exactly the cases where a day count is the wrong answer.

**Legal position.** All three SHALL be legal wherever any other scalar call is legal — in `filter`, in
the select list, in `having`, inside an aggregate's arguments, inside a `window` expression, and as a
sort key through the select entry's alias.

In an aggregate query, a projected `now()` SHALL be treated as the **constant it is**: each call site
yields one value for the whole statement, so the call needs no `group_by` entry, exactly as a projected
literal needs none, and unlike a projected column — which is still rejected as ungrouped. An offset
over a *column* is not constant and is grouped like any other computed column. Both query surfaces
SHALL agree on this, since one document must not be accepted on one endpoint and refused on the other.

**Composition is the point.** No further function is added for "today", "yesterday" or "the start of
last month": `date_trunc('day', now())` is today's start, `date_sub('day', 1, date_trunc('day',
now()))` yesterday's, and the catalog stays closed over one clock and one offset rather than growing a
vocabulary of named periods.

#### Scenario: A relative window filters on the last two hours

- **WHEN** a query filters `request_time` `>=` `date_sub('hour', 2, now())`
- **THEN** the query is accepted and returns only rows whose `request_time` falls within the two hours
  preceding execution

#### Scenario: The same query run later covers a later window

- **WHEN** the identical stored body of the previous scenario is executed again at a later time
- **THEN** the window it covers has moved with the clock, and no part of the request had to be rewritten

#### Scenario: now() takes no arguments

- **WHEN** a query calls `now('UTC')` or `now(0)`
- **THEN** the response is HTTP 400 with `error` `bad_request`

#### Scenario: A non-positive or fractional amount is rejected

- **WHEN** a query calls `date_sub('hour', 0, now())`, `date_sub('hour', -2, now())`, or
  `date_sub('hour', 1.5, now())`
- **THEN** the response is HTTP 400 with `error` `bad_request` stating that `amount` must be a positive
  integer literal

#### Scenario: A non-literal unit or amount is rejected

- **WHEN** a query supplies `date_sub`'s `unit` or `amount` as a field reference or a nested call
  rather than as a literal
- **THEN** the response is HTTP 400 with `error` `bad_request`

#### Scenario: An unsupported unit is rejected and names the accepted set

- **WHEN** a query calls `date_sub('week', 1, now())` or `date_sub('fortnight', 1, now())`
- **THEN** the response is HTTP 400 with `error` `bad_request` listing the accepted units, which do not
  include `week` or `quarter`

#### Scenario: A month offset steps the calendar, not a day count

- **WHEN** a query evaluates `date_sub('month', 1, <a timestamp of 31 March>)`
- **THEN** the result is the last day of February of that year, not 3 March

#### Scenario: The offset composes with the truncation functions

- **WHEN** a query groups by `date_bin(5, 'minute', request_time)` and filters
  `request_time >= date_sub('day', 1, date_trunc('day', now()))`
- **THEN** the query is accepted, the filter bounds the scan from the start of yesterday, and the
  buckets are unaffected by the filter's own expression

#### Scenario: A projected clock needs no group-by entry, while a column still does

- **WHEN** an aggregate query projects `now()` alongside `count()` with no `group_by`
- **THEN** the query is accepted on both surfaces — the call is a constant within the statement — while
  the same query projecting a column instead of `now()` is still rejected as ungrouped

#### Scenario: Each now() call site is one value across every row

- **WHEN** a row query over several rows projects `now()` twice, as `a` and as `b`
- **THEN** every row carries the same `a` and every row carries the same `b`; `a` and `b` are each the
  execution-time instant and MAY differ from each other by a clock tick, exactly as the same statement
  does in ClickHouse

#### Scenario: Two-bound windows are not coordinated by this service

- **WHEN** a query filters `request_time >= date_sub('hour', 1, now()) AND request_time < now()`
- **THEN** the statement sent to the data store carries two independent `now64(3, 'UTC')` calls, with no
  literal or parameter substituted for either, so its bounds are whatever the data store's two
  evaluations return

### Requirement: The catalog declares which functions are non-deterministic

Every catalog entry SHALL declare whether the function is **deterministic** — whether two evaluations
over the same input rows are guaranteed to produce the same value. `now()` SHALL be declared
non-deterministic; every other catalog function SHALL be declared deterministic.

The declaration exists because the catalog is the single fact the rest of the system reasons from. Two
capabilities depend on a derivation being reproducible — Class-1 enrichment, whose exactly-once
*effect* guarantee rests on re-running a window producing the same values, and the aggregate pipeline,
which writes derived numbers people read as fact — and both previously got that for free, since no
non-deterministic function existed to reject. Each SHALL now refuse a function this property marks
non-deterministic (see [class1-enrichment](../class1-enrichment/spec.md) and
[aggregate-pipeline](../aggregate-pipeline/spec.md)). The refusal SHALL be driven by the declared
property and SHALL NOT be a list of names maintained beside the catalog: a second list would imply the
catalog is open, and would rot the first time a function is added without remembering it exists.

The query surfaces themselves SHALL impose **no** restriction from this property. A query is a read
whose result the caller consumes immediately; a moving window is exactly what makes it useful.

#### Scenario: Determinism is discoverable per function

- **WHEN** a client lists the function catalog
- **THEN** each entry states whether the function is deterministic, and `now` is the only entry stating
  it is not

#### Scenario: A query is not restricted by the property

- **WHEN** a caller executes a query, translates one, or saves one whose body calls `now()`
- **THEN** the request is accepted — the property governs the derivation paths, not the query surfaces

### Requirement: Projection-only heavy enrichment columns are fetched for the returned page only

A block SHALL be **late-materialized** on the single-entity read path — `POST /v1/queries/execute`,
`POST /v1/queries/execute-sql`, their CSV twins and the Flight SQL endpoint — when all of these hold: it is in row mode and not
`distinct`; it renders a `LIMIT`; it references at least one `heavy` column of a keyable enrichment; every heavy
column of every such enrichment is referenced only by `select` entries — not by `filter`, `sort` (directly, or
through the name of a `select` entry that references it), `group_by` or `having`; no `select` entry contains a
window expression or `array_join`; it calls no non-deterministic catalog function other than `now`; every such
enrichment is joined on the same source grain key and the same aligned source partition column (or on the same
grain key, none of them aligned); and no `select` output is named `__adas_lm_key` or `__adas_lm_part`. Any other
block SHALL render as the "SQL translation" requirement describes. Composite SQL blocks, the translation-only
endpoints and every pipeline statement SHALL NOT be late-materialized.

A late-materialized block SHALL be rendered as one statement that evaluates its page's keys **once**:

```
SELECT <the block's projection, unchanged>
FROM source [FINAL]
<every other enrichment join, exactly as "SQL translation" renders it>
LEFT JOIN heavy_enrichment [FINAL]
  ON source.grain_key = heavy_enrichment.grain_key
 AND (heavy_enrichment.grain_key, heavy_enrichment.partition_col) IN (K)
WHERE <the block's whole filter>
  AND ((source.grain_key, source.partition_col) IN (K)
       OR source.grain_key IS NULL OR source.partition_col IS NULL)
ORDER BY <the block's sort> LIMIT <limit> OFFSET <offset>
```

where `K` is `SELECT "__adas_lm_key", "__adas_lm_part" FROM (<page block>) AS "__adas_lm"` and the **page block**
is the same block with every `select` entry that references a heavy column removed (an entry a `sort` item names
is kept, under its resolved output name), projecting the source grain key as `__adas_lm_key` and the source
partition column as `__adas_lm_part`, ordered by the block's `sort`, with `LIMIT <offset + limit>` and no
`OFFSET`. Without an aligned partition column, `K` and both `IN` tuples carry the grain key alone. The two
references to `K` SHALL both stand in the outer statement's scope — the outer `WHERE` and the heavy join's `ON` —
and never inside a derived table, because ClickHouse builds one set per distinct subquery within a scope and
re-evaluates a subquery referenced from a nested scope: two evaluations of a page whose order has ties, or that
reads rows ingested in between, can choose different rows, and a row whose page key is missing from the heavy
join's set would read its heavy column as unmatched with no error. A materialized CTE SHALL NOT be used for this
(ClickHouse gates it behind a setting that is off by default).

The late-materialized statement SHALL return exactly the rows, column names, column order, values and
NULL/default semantics the unshaped statement returns, up to the order of rows that tie on the sort, which neither
statement fixes: the page block selects the top `offset + limit` rows of the same filtered relation, the outer
`WHERE` keeps those rows (and any other row sharing one of their keys, which ranks after them), and the outer
`ORDER BY`/`LIMIT`/`OFFSET` then cuts the same page; the heavy join's extra condition is true for every kept row,
so its match is the grain-key match the unshaped join gives it. A row whose grain key or partition column is NULL
is kept by the `IS NULL` arms and reads its heavy columns as unmatched, as it does unshaped. The `include_total`
count statement SHALL NOT be late-materialized. On the single-entity read path a count that is read final SHALL
join only the enrichments its `filter` references: read final, every grain join is 0..1, so a join the filter
does not read cannot change the count, and joining it would build the columns the page projects — a heavy body —
over the whole window. A count that is not read final SHALL keep every join its page has, because an unmerged
enrichment version fans a row out in the page and must fan it out in the count too.

#### Scenario: The last page of requests with their bodies reads bodies for the page only

- **WHEN** a row-mode query selects `trace_id` and `dial_usage_log_payload.request_body`, filters
  `request_time >= T`, sorts by `request_time` descending and pages `limit 10`
- **THEN** the rendered SQL joins `dial_usage_log_payload` (under `FINAL` when read final) with
  `("dial_usage_log_payload"."event_id", "dial_usage_log_payload"."request_time") IN (K)` in its `ON`
- **AND** the outer `WHERE` carries the whole filter and `("dial_usage_log"."event_id", "dial_usage_log"."request_time")
  IN (K)` with the two `IS NULL` arms, where `K` is the same subquery text
- **AND** `K`'s page block projects no heavy column, carries the filter and the sort, and has `LIMIT 10`

#### Scenario: An offset page fetches keys for the rows up to its end

- **WHEN** the same query pages `limit 10, offset 20`
- **THEN** the page block has `LIMIT 30` and no `OFFSET`, and the outer statement keeps `LIMIT 10 OFFSET 20`

#### Scenario: The total of a body page does not read bodies

- **WHEN** the same query, read final, also asks for `include_total`
- **THEN** the count statement joins no payload table, and its total equals the unshaped count's

#### Scenario: Late materialization returns the unshaped rows

- **GIVEN** source rows with no payload row, a payload key with several unmerged versions, rows tying on the
  sort key, and a body larger than 1 MB
- **WHEN** a late-materialized query and the same query rendered unshaped run against ClickHouse, with and
  without an `offset`
- **THEN** both return identical column names, types and values, row for row

#### Scenario: The page keys are evaluated once

- **WHEN** the late-materialized shape runs with a page block ordered by a per-row random value
- **THEN** every row the outer `WHERE` keeps finds its heavy row, on every run

#### Scenario: A heavy column outside the projection keeps today's join

- **WHEN** a row-mode query filters, sorts or groups on a heavy enrichment column, or sorts by the output name of
  a `select` entry that reads one
- **THEN** the block is not late-materialized

#### Scenario: Shapes whose page cannot be chosen first keep today's join

- **WHEN** a row-mode query that projects a heavy enrichment column is `distinct`, or projects a window
  expression or `array_join`, or is in aggregate mode
- **THEN** the block is not late-materialized

#### Scenario: A light column of the heavy enrichment may appear anywhere

- **WHEN** a row-mode query projects `dial_usage_log_payload.request_body` and filters on
  `dial_usage_log_payload.request_tags`
- **THEN** the block is late-materialized, the page block joins the payload for `request_tags` only, and the
  outer statement reads both columns from the page-restricted join

#### Scenario: Composite blocks keep today's join

- **WHEN** a composite SQL statement's block projects a heavy enrichment column under a `LIMIT`
- **THEN** the block renders as "SQL translation" describes, with no page-key subquery

### Requirement: Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table

A top-level conjunct of the filter SHALL be **copied** into an enrichment's derived-table `WHERE` on the single-entity
read path, in the row statement and in the `include_total` count statement (the filter flattened as "SQL translation"
flattens it for keying), beside its grain-key and partition restrictions (or the transferred partition bounds that replace them), when it names exactly one
field, that field belongs to that enrichment and is of type `string`, `integer`, `long` or `decimal`, and the
conjunct is one of:

- `co` or `ico` with a non-empty string literal;
- `eq` against a non-null literal that is not the field type's default (`''` for `string`, `0` for a number);
- `in` whose every literal is non-null and not the field type's default;
- `eq` of `starts_with(field, literal)` or `contains(field, literal)` against `true`, with a non-empty literal.

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
no `WHERE`. A heavy field a late-materialized block fetches for its page is not pushed.

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
subquery — `request_time >= now() - INTERVAL 1 DAY`, say. `B` exists so that a statement which already bounds its time
does not pay a read of `C`'s column over all of `E`'s history to name the days it named itself; without a bound `PS`
reads `C`'s column across every partition of `E`.

`PS` SHALL NOT be repeated inside `E`'s derived table. ClickHouse evaluates an uncorrelated subquery once per scope, so
a copy there is a second read of `C`'s column, and it prunes nothing more: the analyzer carries the source's
restriction into `E`'s derived table, and into every other aligned join, through the tuple join's `source.p = E.p`
(measured on ClickHouse 26.3 and 26.8 under `do_not_merge_across_partitions_select_final = 1`, which every aligned read
carries). A join of the same statement without an aligned partition pair has no such equality; it SHALL be keyed on
`partitionExpr(source.p) IN PS` as one of its source-only key predicates, so that it reads the grain keys of the
selected partitions rather than hashing its whole table. When the statement is late-materialized, the restriction
SHALL appear in the page block only: the outer statement is confined to the page's keys, which ClickHouse carries into
every aligned join as well. A count for `include_total` SHALL take the same restriction as the page.

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
- **WHEN** a query filters `"usage_client_identity.client_session_id" = 's1'`
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

### Requirement: A composite join's operand-only conjuncts restrict that operand

A top-level `WHERE` conjunct of a composite block SHALL be evaluated inside a join operand's own block
instead of above the join when the block reads from that join and the conjunct references columns of exactly
one operand, where that operand is an entity reference. It then confines the rows that operand
reads, and it keys that operand's enrichment joins exactly as a single-entity filter does: its source-only
conjuncts key every join, and a conjunct reaching into an enrichment is kept beside them.

A conjunct SHALL move only where removing it from above the join cannot change the result:

- into **either** operand of an `INNER` join or of a `CROSS` join;
- into the **preserved** operand of an outer join only: the left operand of a `LEFT` join and the right operand
  of a `RIGHT` join;
- into **neither** operand of a `FULL` join.

A conjunct SHALL NOT move into the null-supplying operand of an outer join. There it would decide which rows are
null-extended instead of which joined rows are kept. It SHALL stay above the join.

A conjunct SHALL stay above the join when:

- it references no column;
- it references columns of both operands;
- it reaches through a nested join;
- it contains a subquery predicate, a scalar subquery or a lambda.

Only the operands of the join the block reads directly SHALL receive conjuncts; a join nested inside an operand
is left as it is. The `ON` condition and the `HAVING` clause SHALL NOT move.

A moved conjunct is the same predicate over the same row before the join instead of after it, and it is not
applied twice. The statement's rows SHALL be exactly those it returned before this requirement.

#### Scenario: An inner self-join's bounded side is restricted in its own block

- **WHEN** a caller posts
  `SELECT b."usage_client_identity.user_email" AS parent_user, count(*) AS c FROM dial_usage_log a JOIN dial_usage_log b ON a.trace_id = b.trace_id AND a.core_parent_span_id = b.core_span_id WHERE a.request_time >= TIMESTAMP '2026-10-13 00:00:00' AND a.request_time < TIMESTAMP '2026-10-14 00:00:00' GROUP BY 1 ORDER BY c DESC LIMIT 20`
- **THEN** both `request_time` conjuncts are rendered in the `WHERE` of `a`'s operand block and not above the
  join
- **AND** the result equals that of the statement rendered without the move

#### Scenario: A LEFT join moves a conjunct into its preserved side only

- **GIVEN** `a LEFT JOIN b ON a.k = b.k WHERE a.x = 1 AND b.y = 2`
- **WHEN** the statement is rendered
- **THEN** `a.x = 1` is rendered in `a`'s operand block
- **AND** `b.y = 2` stays above the join

#### Scenario: A RIGHT join moves a conjunct into its preserved side only

- **GIVEN** `a RIGHT JOIN b ON a.k = b.k WHERE a.x = 1 AND b.y = 2`
- **WHEN** the statement is rendered
- **THEN** `b.y = 2` is rendered in `b`'s operand block
- **AND** `a.x = 1` stays above the join

#### Scenario: A FULL join moves nothing

- **GIVEN** `a FULL JOIN b ON a.k = b.k WHERE a.x = 1 AND b.y = 2`
- **WHEN** the statement is rendered
- **THEN** both conjuncts stay above the join and neither operand block has a `WHERE`

#### Scenario: A conjunct over both operands stays above the join

- **GIVEN** `a JOIN b ON a.k = b.k WHERE a.x = b.y`
- **WHEN** the statement is rendered
- **THEN** `a.x = b.y` stays above the join

### Requirement: An equi-join operand is reduced to the keys its restricted partner can produce

The **reduced** side of a composite join SHALL also be restricted inside its own block when the other entity
operand (the **restricted** side) receives moved conjuncts of which at least one references its source columns
alone, and the join's `ON` condition has a top-level equality between a bare source column of each side. The
reduced side is restricted to
`reduced.k IN (SELECT restricted.k FROM <restricted source table> WHERE <the restricted side's moved source-only conjuncts>)`.
`k` is the first such equality in the `ON` condition. The restriction also keys the reduced side's enrichment joins,
as a source-only conjunct of its own filter would.

The restriction SHALL be built from the restricted side's moved **source-only** conjuncts only. It SHALL never
include a conjunct reaching into an enrichment, which would need that enrichment joined inside the key subquery.
Like the grain-join key subquery, it carries no `FINAL` modifier of its own. Under the same predicates, the raw key set is a superset of the
deduplicated one, so it never excludes a key the restricted side can produce.

A side SHALL be reduced only where the join discards that side's unmatched rows anyway:

- either side of an `INNER` join;
- the null-supplying side of an outer join, reduced by the preserved side's keys: the right operand of a `LEFT`
  join and the left operand of a `RIGHT` join;
- never the preserved side of an outer join, and neither side of a `FULL` or `CROSS` join.

A reduction SHALL go one hop. A side that is restricted only by a reduction SHALL NOT itself reduce its partner,
and no reduction SHALL be carried through a nested join. When both sides of an `INNER` join carry moved
source-only conjuncts, each SHALL be reduced by the other's own moved conjuncts.

A reduction SHALL NOT change the statement's rows. A row of the reduced side whose key the restricted side cannot
produce has no partner, so the join drops it in every allowed position. A `NULL` key never satisfies the
equality, so excluding it changes nothing.

Without the reduction, an operand with no bound of its own reads its whole source, and hashes the whole of every
enrichment it joins, whatever the other side selects. With the reduction,
the inner self-join scenario below goes from a memory-budget refusal (unchanged by the move alone) to well under a
second and a fraction of the budget, returning the rows of an independently written reference.

#### Scenario: The unbounded side of a self-join is reduced by the bounded side's keys

- **GIVEN** the self-join of "An inner self-join's bounded side is restricted in its own block"
- **WHEN** the statement is rendered
- **THEN** `b`'s operand block carries
  `trace_id IN (SELECT trace_id FROM dial_usage_log WHERE <a's two request_time conjuncts>)`
- **AND** `b`'s identity enrichment join is keyed by that restriction
- **AND** the statement completes inside the query budget, with the rows of the statement rendered without the
  reduction

#### Scenario: A LEFT join reduces its null-supplying side only

- **GIVEN** `a LEFT JOIN b ON a.k = b.k WHERE a.x = 1`
- **WHEN** the statement is rendered
- **THEN** `b`'s operand block carries `k IN (SELECT k FROM <a's source> WHERE x = 1)`
- **AND** a row of `a` with no partner in `b` is still returned, null-extended

#### Scenario: The preserved side of an outer join is never reduced

- **GIVEN** `a LEFT JOIN b ON a.k = b.k WHERE b.y = 2`
- **WHEN** the statement is rendered
- **THEN** neither operand block carries a restriction and `b.y = 2` stays above the join

#### Scenario: An enrichment conjunct does not build a reduction

- **GIVEN** `a JOIN b ON a.k = b.k WHERE a."<enrichment>.col" = 'x'`
- **WHEN** the statement is rendered
- **THEN** the conjunct moves into `a`'s operand block
- **AND** `b` is not reduced, because `a` carries no moved source-only conjunct

#### Scenario: NULL and duplicate keys keep the unreduced result

- **GIVEN** a restricted side whose keys include `NULL` and repeated values, and an INNER join with that side
- **WHEN** the statement is executed with and without the reduction
- **THEN** both return the same rows, with the same multiplicity

### Requirement: A partition-only key on an aligned enrichment is transferred, not keyed

On the query surface, a keyed join to a partition-aligned enrichment SHALL be restricted by the source's
partition-column bounds themselves, rendered on the enrichment's alignment column, **in place of** both key sets
(`grain_key IN (…)` and `partition_column IN (…)`), when every source predicate that keys it is such a bound. A
bound is a top-level source-only comparison of the statement's filter that reads the source's partition column
and nothing else, and holds no subquery.

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
join with no aligned pair, an unkeyed join and a late-materialized join SHALL render exactly as before.

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
- **AND** a query whose filter is `day >= T` and which selects an enrichment column
- **WHEN** the statement is rendered
- **THEN** the enrichment's derived table carries `enrichment.day >= T`
- **AND** it carries neither `enrichment.grain_key IN (SELECT …)` nor `enrichment.day IN (SELECT …)`
- **AND** the join condition still compares the grain key and `day`

#### Scenario: A pushed enrichment conjunct stays beside the transferred window

- **GIVEN** the same aligned pair and a filter `day >= T AND enrichment_column = X`
- **WHEN** the statement is rendered
- **THEN** the derived table carries `enrichment.day >= T` and `enrichment_column = X`
- **AND** no key set

#### Scenario: Any other source predicate keeps both key sets

- **GIVEN** the same aligned pair and a filter `day >= T AND deployment = 'd'`
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
- **WHEN** the same window-only query runs rendered with the transfer and with both key sets
- **THEN** both return identical rows

## Implementation notes

- Request object model: `com.epam.aidial.analytics.service.query.model` — `StructuredQuery`,
  `FilterNode`/`LogicalNode`/`ComparisonNode`, `Expr`/`FieldExpr`/`ValueExpr`/`ParamExpr`/`FnExpr`/
  `ArrayExpr`, `OutputColumn`, `SortItem`, `PageSpec`/`OffsetPage`/`CursorPage`, and enums
  `QueryMode`/`LogicalOp`/`ComparisonOp`/`SortDir`/`ValueType`.
- Custom routing: `FilterNodeDeserializer` (wired via `@JsonDeserialize(using=…)` at each use site,
  never on the `FilterNode` interface, to avoid inheritance-driven recursion).
- Binding requires the shared `JsonMapper` to disable `FAIL_ON_NULL_FOR_PRIMITIVES` so absent
  primitive flags (`distinct`, `include_total`) default instead of failing.
- Execution endpoint + dispatch: `web.controller.query.StructuredQueryController` (`/v1/queries`),
  `service.query.StructuredQueryService` resolves the entity via `service.query.CatalogQueryService`
  (rejecting anything but an `ACTIVE` `source`) and executes through
  `service.query.repository.StructuredQueryExecutor`.
- Translation (render-only jOOQ): `service.query.translate.{StructuredQueryBuilder, FilterTranslator,
  ExprTranslator, ValueExprToObjectMapper}` build the model on the `SQLDialect.CLICKHOUSE`
  `DSLContext` bean (`configuration.QueryDslConfiguration`); `StructuredQueryExecutor` renders
  `getSQL(ParamType.INLINED)` and runs it via `clickhouse.ClickHouseClientWrapper.query(sql)`.
  Response DTO: `web.dto.query.StructuredQueryResultDto`; row carrier: `QueryResultPage`.
- Error mapping (via `web/handler/DefaultExceptionHandler` + `ErrorView`):
  `QueryValidationException` → 400 `bad_request`; `ClickHouseQueryException` → 400 `bad_request`;
  ClickHouse connectivity failure → 503 `clickhouse_unavailable`.
- Tests: `StructuredQueryDeserializationTest` (binding round-trip), `DialUsageLogQueryRenderTest`
  (CLICKHOUSE SQL render), `StructuredQueryControllerTest` (execute + error mapping).
