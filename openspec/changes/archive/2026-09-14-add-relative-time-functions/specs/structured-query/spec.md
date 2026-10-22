## ADDED Requirements

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

A statement SHALL observe **one** instant: every `now()` call in one execution SHALL yield the same
value, so `date_sub('hour', 2, now())` and `now()` bound a window that cannot straddle a tick, and a
query filtering on one and projecting the other cannot return a row outside the window it reported.

**`date_add` / `date_sub`.** Both SHALL take exactly three arguments — a `unit` string literal, an
`amount` integer literal, and a `timestamp` expression — in that order, which is the order the
underlying engine's own function of the same name takes. Any other arity, a non-literal `unit` or
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

In an aggregate query, a projected `now()` SHALL be treated as the **constant it is**: one execution
observes one instant, so the call needs no `group_by` entry, exactly as a projected literal needs
none, and unlike a projected column — which is still rejected as ungrouped. An offset over a *column*
is not constant and is grouped like any other computed column. Both query surfaces SHALL agree on
this, since one document must not be accepted on one endpoint and refused on the other.

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
- **THEN** the query is accepted on both surfaces — the clock is a constant within the execution — while
  the same query projecting a column instead of `now()` is still rejected as ungrouped

#### Scenario: One execution observes one instant

- **WHEN** a query both filters on `date_sub('minute', 5, now())` and projects `now()`
- **THEN** every returned row's projected instant is the same value, and it is the one the filter's
  bound was computed from

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

## MODIFIED Requirements

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

Filtering an array is the whole of what **these** functions provide. Exploding an array into one row
per element is the separate concern of `array_join`, whose contract — its row-multiplying semantics,
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
`json_extract_array` and `array_slice` all return arrays and are all projection-only, and the array
accessors — plus `array_join`, `array_to_string` and `array_slice` — all accept an array in their first
argument. A call of the first kind nested in the
first argument of the second SHALL therefore be accepted, and is the intended way to test, measure,
render, narrow or expand a computed array.

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

**Both new extractors SHALL accept an `object` field as their JSON argument**, on the same terms as
the other four: the argument SHALL be either an expression yielding JSON **text** or an `object`-typed
field, and both forms SHALL return the same value for the same document. Neither can reuse the
`object` form's subcolumn rendering — a case-insensitive key match has no statically known subcolumn
to address, and an array subcolumn is typed `Dynamic` rather than an array of text — so over an
`object` field both SHALL be evaluated against the document's **serialized text**
(`toJSONString(<column>)`). A rendering that
hands an `object` field to a native JSON *text* accessor SHALL NOT be used: on ClickHouse 26.3
`JSONExtractKeysAndValues` over a `JSON` operand returns an **empty key set instead of failing**, so
`json_extract_string_ci` would return the empty string for every row of an `object` column with no
error raised anywhere, and `JSONExtract(…, 'Array(String)')` over the same operand fails with
`ILLEGAL_TYPE_OF_ARGUMENT`. Reading through the serialized text is the whole of the difference: it
costs a full document materialization per row where the other four extractors read one path, and a
**composite** member reaches these two as the column's re-serialization rather than the source
spelling — the same exception the other four carry over an `object` field, in its own shape.

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
selects it. Neither operand may be a **projection-only** field **on the SQL surface**, where that
classification already applies to every function's operands (see
[sql-query-translation](../sql-query-translation/spec.md)); the DSL surface applies no operand-type
restriction to `arg_max` beyond those it applies to `min` and `max`. `arg_max` names no operand types
of its own, deliberately: it inherits whatever the projection-only set holds, so a type added to that
set later covers this function without a spec edit. `arg_max`'s rendering emits the
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
without `FULL_ADMIN`, failing as an unknown field exactly as it does in a projection — extraction
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

- **WHEN** a caller without `FULL_ADMIN` calls `json_extract_string` on a `sensitive` `object` field
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

#### Scenario: Array extraction reads an object column

- **WHEN** `json_extract_array(jwt_claims, 'roles')` is evaluated over an `object`-typed column whose
  document carries that array, and over one whose document lacks it
- **THEN** the values are the array and the empty array respectively, and neither the row nor the
  query fails with a ClickHouse type error

#### Scenario: Aggregate ratio divides in projection

- **WHEN** an aggregate-mode query selects `div(sum(cached_prompt_tokens), sum(prompt_tokens))` grouped
  by `deployment`
- **THEN** the request succeeds and each row carries the ratio as a numeric value

#### Scenario: Division by a zero denominator yields null, not an error

- **WHEN** an aggregate-mode query selects `div(sum(a), sum(b))` and one group's `sum(b)` is `0`
- **THEN** the request succeeds, that group's cell is null, and every other group carries its computed
  ratio

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
