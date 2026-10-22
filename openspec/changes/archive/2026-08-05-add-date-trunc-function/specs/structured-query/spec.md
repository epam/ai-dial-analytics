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
| `max` | aggregate | `max(col)` | col type |
| `min` | aggregate | `min(col)` | col type |
| `sum` | aggregate | `sum(numeric)`; `distinct` allowed | numeric |
| `percentile_cont` | ordered-set aggregate | `percentile_cont(fraction, column)` | numeric (interpolated) |
| `percentile_disc` | ordered-set aggregate | `percentile_disc(fraction, column)` | type of `column` (an actual member) |

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

The four `json_extract_*` functions SHALL extract a typed scalar from a JSON-valued text expression.
The first argument SHALL be any resolvable expression yielding JSON text; each subsequent argument
SHALL be a **string literal** naming one level of the path, so `json_extract_long(request_body,
'usage', 'total_tokens')` reads a nested field. At least two arguments SHALL be supplied. Path
arguments SHALL be literals, never computed expressions, so the accessed path is statically known.
Each function SHALL render to its native ClickHouse counterpart — `JSONExtractString`,
`JSONExtractInt`, `JSONExtractFloat`, `JSONExtractBool` respectively — emitted by name, so the
rendering is deterministic and not subject to the experimental-dialect caveat. Each SHALL have a
**fixed** result type independent of its operands, per the table above. `json_extract_bool` SHALL
additionally wrap its call as `toBool(JSONExtractBool(…))`, because `JSONExtractBool` yields ClickHouse
`UInt8` (`1`/`0`); without the wrap the advertised `boolean` return type would serialize as a number.

Consistent with ClickHouse's `JSONExtract*` semantics, an absent path, a null value, or unparseable
JSON SHALL yield the extracting type's default (empty string, `0`, `0.0`, `false`) rather than an
error; these functions are total by construction. Callers that must distinguish "absent" from
"default" SHALL do so with an explicit filter on the underlying text.

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
