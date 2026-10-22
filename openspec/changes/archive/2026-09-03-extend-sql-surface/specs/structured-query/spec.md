## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Expression grammar

The system SHALL model expressions as a sealed `Expr` hierarchy discriminated by the `type` key with
seven kinds: `field` (column reference), `value` (literal whose `value` is always a JSON string
governed by `value_type`), `param` (runtime parameter), `fn` (function call with a nestable
expression `args` list), `array` (collection whose items use the key `items`), `case` (conditional with a
`when` list of filter-node/expression branches and an optional `else`), and `window` (a function call
qualified by `partition_by`, `order_by` and `frame`). `value_type` SHALL be a closed enum: `string`,
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

#### Scenario: Unknown expression type rejected

- **WHEN** an expression with `"type": "subquery"` is posted to `POST /v1/queries/execute`
- **THEN** the request is rejected with HTTP 400 `bad_request`
