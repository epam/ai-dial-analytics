## ADDED Requirements

### Requirement: Structured query envelope

The system SHALL provide an immutable request object model representing a single structured query
envelope with the fields `entity` (required), `filter`, `mode` (required), `select`, `group_by`,
`aggregate`, `having`, `sort`, and `page`. JSON keys SHALL be `snake_case` (`group_by`, `include_total`,
`value_type`, and the aggregate alias key `as`).

#### Scenario: Full row-mode envelope binds

- **WHEN** a row-mode JSON query with `entity`, `mode: "row"`, `filter`, `select`, `sort`, and an
  `offset` page is deserialized into the envelope record
- **THEN** every field binds to its typed component, `mode` resolves to `ROW`, and `select`/`sort`
  resolve to their element record types

#### Scenario: Full aggregate-mode envelope binds

- **WHEN** an aggregate-mode JSON query with `group_by`, `select` aggregate entries (alias `as`),
  `having`, and a `cursor` page is deserialized
- **THEN** `mode` resolves to `AGGREGATE`, `group_by` binds to a string list, the aggregate select
  entries bind with their aliases, and `having` binds to a filter node

#### Scenario: Mode is required

- **WHEN** a query omits `mode`
- **THEN** request validation rejects it as a bad request; `mode` must be supplied explicitly and is
  never inferred from `group_by`/`select`. The remaining sections stay optional, with mode-coherence
  left to the validation layer.

### Requirement: Filter sub-grammar (CQL2-JSON)

The system SHALL model a recursive filter tree as a sealed `FilterNode` hierarchy discriminated by
the `op` key: logical nodes (`and`, `or`, `not`) carrying a list of child nodes, and predicate nodes
carrying an operator code (`eq`, `ne`, `co`, `nc`, `lt`, `gt`, `le`, `ge`, `in`) and a positional
`args` list of expressions. Because `op` is both the discriminator and operator data, routing SHALL
be performed by a custom deserializer wired at each use site, not by a declarative `@JsonTypeInfo` on
the interface.

#### Scenario: Nested boolean tree binds

- **WHEN** a filter `(api_type = 'chat' AND total_tokens > 100) OR (...)` is deserialized
- **THEN** the root binds to a logical `OR` node whose children are logical `AND` nodes containing
  predicate nodes with their operator codes and operand expressions

#### Scenario: Both operands are general expressions

- **WHEN** a predicate `length(project_id) = 3` is deserialized
- **THEN** the predicate's `args` hold a function expression on the left and a value expression on the
  right, with no requirement that either operand be a bare column

#### Scenario: Logical-vs-predicate routing by op

- **WHEN** a node's `op` is `and`/`or`/`not`
- **THEN** it routes to the logical node type; otherwise it routes to the predicate node type

### Requirement: Expression grammar

The system SHALL model expressions as a sealed `Expr` hierarchy discriminated by the `type` key with
five kinds: `field` (column reference), `value` (literal whose `value` is always a JSON string
governed by `value_type`), `param` (runtime parameter), `fn` (function call with a nestable
expression `args` list), and `array` (collection whose items use the key `items`). `value_type` SHALL
be a closed enum: `string`, `integer`, `long`, `decimal`, `boolean`, `date`, `timestamp`, `uuid`,
`null`.

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

### Requirement: `in` predicate with array operand

The system SHALL treat `in` as an ordinary binary predicate whose right operand is typically an
`array` expression, without structurally encoding "left scalar / right set" — that constraint is
deferred to validation.

#### Scenario: Set membership binds

- **WHEN** `api_type IN ('chat', 'embedding')` is deserialized
- **THEN** the predicate's `args` hold a field expression on the left and an array expression of two
  value literals on the right

### Requirement: Aggregation, sort, and pagination request shapes

The system SHALL model aggregate select entries (a function expression with an output alias bound
from the `as` key), a sort item (`field`, `dir` ∈ `asc`/`desc`, and an optional `nulls` ∈
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
SHALL be routed by its `entity` to the matching execution repository, and a query naming an entity
that has no registered repository SHALL be rejected with a validation error (HTTP 400) that names the
supported entities. The query SHALL be translated to ClickHouse SQL — built through a jOOQ query
model on a connection-less `SQLDialect.CLICKHOUSE` render context and rendered to a SQL string, then
executed via the ClickHouse HTTP client — never assembled by raw string concatenation.

#### Scenario: Row-mode query executes and returns rows

- **WHEN** a valid row-mode query for a supported entity (e.g. `dial_usage_log`) is posted to
  `/v1/queries/execute`
- **THEN** the response carries the projected rows as field-name → value maps

#### Scenario: Unknown entity is rejected

- **WHEN** a query naming an entity with no registered repository is posted
- **THEN** the request is rejected with HTTP 400 and the error names the supported entities

### Requirement: Supported function catalog

The system SHALL accept in a structured query's expressions only functions from a closed catalog, and
SHALL reject any other function name with HTTP 400. Each catalog entry SHALL define the function's
group (scalar, aggregate, or ordered-set aggregate), arity, operand types, and return type. The
catalog is:

| Function | Group | Arity / signature | Returns |
|---|---|---|---|
| `lower` | scalar | `lower(text)` | text |
| `upper` | scalar | `upper(text)` | text |
| `length` | scalar | `length(text)` | integer |
| `trim` | scalar | `trim(text)` | text |
| `abs` | scalar | `abs(numeric)` | numeric |
| `width_bucket` | scalar | `width_bucket(operand, low, high, count)` (4 args) | integer (bucket) |
| `count` | aggregate | `count()` or `count(col)`; `distinct` allowed | long |
| `sum` | aggregate | `sum(numeric)`; `distinct` allowed | numeric |
| `avg` | aggregate | `avg(numeric)`; `distinct` allowed | numeric |
| `min` | aggregate | `min(col)` | col type |
| `max` | aggregate | `max(col)` | col type |
| `percentile_cont` | ordered-set aggregate | `percentile_cont(fraction, column)` | numeric (interpolated) |
| `percentile_disc` | ordered-set aggregate | `percentile_disc(fraction, column)` | type of `column` (an actual member) |

For `percentile_cont`/`percentile_disc`, `fraction` SHALL be a decimal literal in the closed interval
`[0, 1]` and `column` SHALL be any resolvable field expression. The ClickHouse rendering of the
ordered-set aggregates and `width_bucket` is provisional under jOOQ's experimental ClickHouse dialect
and must be verified against a live ClickHouse before being relied upon.

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

### Requirement: Query validation and allowlist

The system SHALL validate a structured query before and during translation against the entity's
**discovered schema**, rejecting invalid queries with HTTP 400. Validation SHALL cover: entity
resolution (unknown entity rejected), field resolution (every referenced field must resolve against
the entity's flat schema), function resolution against the closed **Supported function catalog**
(including arity and, for `percentile_cont`/`percentile_disc`, a `fraction` decimal literal in
`[0, 1]`), `in` operand shape (an array of value literals), literal parsing per `value_type`, and
pagination governance (offset ≥ 0; cursor pagination rejected; limit clamped to its bounds).
Semantically invalid queries that nonetheless translate SHALL surface ClickHouse's rejection as HTTP
400 rather than 500. Per-field capability flags, mode-coherence enforcement, and array element
type-homogeneity are NOT enforced in this implementation and remain available as a future tightening.

#### Scenario: Unknown field is rejected

- **WHEN** a query references a field name that does not resolve against the entity's discovered schema
- **THEN** the query is rejected with HTTP 400 before execution

#### Scenario: Unsupported function is rejected

- **WHEN** a query uses a function name outside the Supported function catalog
- **THEN** the query is rejected with HTTP 400

#### Scenario: Cursor pagination is rejected

- **WHEN** a query supplies a cursor-strategy page
- **THEN** the query is rejected with HTTP 400 (cursor pagination is not supported on this path)

#### Scenario: Invalid literal is rejected

- **WHEN** a value literal cannot be parsed to its declared `value_type` (e.g. a non-numeric `integer`)
- **THEN** the query is rejected with HTTP 400

#### Scenario: ClickHouse rejects the query

- **WHEN** a query is well-formed but ClickHouse rejects the rendered SQL (e.g. a type mismatch)
- **THEN** the request is rejected with HTTP 400 and no raw ClickHouse internals are exposed

### Requirement: SQL translation

The system SHALL translate a validated structured query into ClickHouse SQL by building a jOOQ query
model on the connection-less `SQLDialect.CLICKHOUSE` render context and rendering it to a SQL string
with literals inlined (the ClickHouse HTTP client executes a raw SQL string). Allowlisted fields SHALL
expand to their physical columns. Comparison operators SHALL map to SQL per the wire contract:
`eq`/`ne`/`lt`/`gt`/`le`/`ge` to direct comparisons, `co`/`nc` to case-insensitive `LIKE`/`NOT LIKE`
with wildcards, `in` to `IN`, and `eq`/`ne` against a null literal to `IS NULL`/`IS NOT NULL`.
Aggregate functions SHALL translate to their SQL aggregates. Aggregate mode SHALL require aliased
select entries and SHALL build `group_by`/`having`/`sort` against the base fields together with the
select aliases. The offset pagination strategy SHALL be applied with a default limit of 100 and a
maximum of 1000.

#### Scenario: Row projection renders against the entity table

- **WHEN** a validated row-mode query projects two fields with a filter, sort, and offset page
- **THEN** the rendered SQL selects those columns from the entity table with the filter, `ORDER BY`,
  and `LIMIT` present

#### Scenario: Contains operator maps to case-insensitive LIKE

- **WHEN** a query uses the `co` operator on a string field
- **THEN** the translator emits a case-insensitive `LIKE '%value%'` predicate

#### Scenario: Aggregate query groups by base field and select alias

- **WHEN** an aggregate-mode query groups by a field and selects an aliased aggregate
- **THEN** the translator builds GROUP BY against the field and resolves `having`/`sort` references
  against the base fields plus the select alias

### Requirement: Response envelope

The system SHALL return query results as a response object carrying a `rows` array and a nullable
`totalCount`. Each row SHALL be a field-name → value map produced from the ClickHouse client's
records. The `totalCount` SHALL be populated only for row-mode queries that opt in via the offset
page's `include_total`, computed as a separate count of the same filter; otherwise it SHALL be null.

#### Scenario: Total count returned when requested

- **WHEN** a row-mode query sets the offset page's `include_total` to true
- **THEN** the response `totalCount` is the count of all rows matching the filter, independent of the
  page's offset and limit

#### Scenario: Total count omitted by default

- **WHEN** a query does not request the total (or is aggregate-mode)
- **THEN** the response `totalCount` is null and only `rows` is populated
