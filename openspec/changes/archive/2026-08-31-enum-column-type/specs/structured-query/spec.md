## ADDED Requirements

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
