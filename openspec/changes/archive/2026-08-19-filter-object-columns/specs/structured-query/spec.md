## MODIFIED Requirements

### Requirement: Supported function catalog

The system SHALL accept in a structured query's expressions only functions from a closed catalog, and
SHALL reject any other function name with HTTP 400. Each catalog entry SHALL define the function's
group (scalar, aggregate, or ordered-set aggregate), arity, operand types, and return type. The
catalog is:

| Function | Group | Arity / signature | Returns |
|---|---|---|---|
| `abs` | scalar | `abs(numeric)` | numeric |
| `date_bin` | scalar | `date_bin(amount, unit, timestamp)` (3 args) | timestamp (bucket start) |
| `date_trunc` | scalar | `date_trunc(unit, timestamp)` (2 args) | timestamp (period start) |
| `json_extract_bool` | scalar | `json_extract_bool(json, key[, key…])` (2+ args) | boolean |
| `json_extract_double` | scalar | `json_extract_double(json, key[, key…])` (2+ args) | numeric |
| `json_extract_long` | scalar | `json_extract_long(json, key[, key…])` (2+ args) | long |
| `json_extract_string` | scalar | `json_extract_string(json, key[, key…])` (2+ args) | text |
| `length` | scalar | `length(text)` | integer |
| `lower` | scalar | `lower(text)` | text |
| `trim` | scalar | `trim(text)` | text |
| `upper` | scalar | `upper(text)` | text |
| `width_bucket` | scalar | `width_bucket(operand, low, high, count)` (4 args) | integer (bucket) |
| `avg` | aggregate | `avg(numeric)`; `distinct` allowed | numeric |
| `count` | aggregate | `count()` or `count(col)`; `distinct` allowed | long |
| `group_uniq_array` | aggregate | `group_uniq_array(col)` | array of col type |
| `max` | aggregate | `max(col)` | col type |
| `min` | aggregate | `min(col)` | col type |
| `sum` | aggregate | `sum(numeric)`; `distinct` allowed | numeric |
| `percentile_cont` | ordered-set aggregate | `percentile_cont(fraction, column)` | numeric (interpolated) |
| `percentile_disc` | ordered-set aggregate | `percentile_disc(fraction, column)` | type of `column` (an actual member) |

`group_uniq_array` SHALL return the distinct non-null values of its operand over the group, sorted
ascending, and SHALL return an empty array — never null — for a group holding no non-null value. It
SHALL NOT accept the `distinct` quantifier (the result is already a set). Its result SHALL be
projectable only: since the DSL has no array literal or comparison, an array result used as an operand
of another expression SHALL be rejected, the same restriction that applies to a declared `array`
column.

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

The four `json_extract_*` functions SHALL extract a typed scalar from a JSON-valued expression. The
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
properties (which paths it materializes, and with what type) are outside this DSL's control.

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

Widening the JSON argument SHALL be the whole of the change to this capability: how a **bare**
`object` field behaves in any other position on the execution path is left exactly as it is, neither
newly accepted nor newly rejected. (The projection-only restriction that rejects such uses belongs to
the SQL surfaces — see [sql-query-translation](../sql-query-translation/spec.md) and
[query-translation-endpoint](../query-translation-endpoint/spec.md) — not to execution.)

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
