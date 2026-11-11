## ADDED Requirements

### Requirement: Saved query chart configuration

A saved query's `chart` SHALL be `{ type, x_field, y_field }`, a client rendering preference that the
system stores and returns as sent, apart from the `y_field` normalization below:

- `type` — the chart type, a string;
- `x_field` — the result column that feeds the X axis, a string;
- `y_field` — the result columns that feed the Y axis, an **array** of strings, one element per series. A
  one-element array SHALL describe a single-series chart; several elements SHALL describe a multi-series
  chart plotted against the one `x_field`.

Every member SHALL be optional and nullable: a partially configured chart is a legitimate saved state, and
an absent or `null` member SHALL be omitted from the response. An empty `y_field` array is not omitted: it
SHALL be stored and returned as `[]` (no series). The system SHALL NOT validate `x_field` or `y_field`
against the catalog, because they name result columns (aliases, derived labels) rather than catalog
columns, and SHALL return the `y_field` elements in the order the caller supplied them.

For compatibility, a request whose `y_field` is a single string SHALL be accepted and read as a
one-element array (an empty string becomes `[""]`), and a chart stored before `y_field` became an array —
in a saved query, a saved-query revision snapshot, or a dashboard inline query — SHALL be returned with its
string `y_field` as a one-element array. The elements are not otherwise checked: a number or boolean, alone
or inside the array, SHALL be read as its string form (`5` becomes `["5"]`), and a `null` element SHALL be
kept and returned as `null` in its position. A `y_field` that is an object, or an array holding an object
or an empty array, SHALL be rejected with `400` and machine code `bad_request`. The outcome for an array
holding a non-empty array (`[["requests"]]`) is not defined by this requirement.

The same chart model SHALL apply wherever a chart is carried, including a dashboard's `inline_query`.

#### Scenario: A multi-series chart round-trips

- **WHEN** a caller creates a saved query whose `chart` is
  `{ "type": "line", "x_field": "day", "y_field": ["requests", "errors"] }`
- **THEN** the response and every later read carry `chart.y_field` as `["requests", "errors"]`, in that
  order

#### Scenario: A one-element array is a single-series chart

- **WHEN** a caller saves a chart with `y_field` `["requests"]`
- **THEN** it is returned as `["requests"]`

#### Scenario: A legacy string y_field is read as a one-element array

- **WHEN** a caller saves a chart with `y_field` `"requests"`
- **THEN** the request succeeds and the chart is returned with `y_field` `["requests"]`

#### Scenario: A chart stored with a string y_field is returned as an array

- **GIVEN** a saved query, a saved-query revision snapshot, or a dashboard inline query whose stored chart
  has `y_field` `"requests"`
- **WHEN** a caller reads it
- **THEN** the chart is returned with `y_field` `["requests"]`

#### Scenario: A null y_field is omitted

- **WHEN** a caller saves a chart with `y_field` `null` or no `y_field`
- **THEN** the returned chart has no `y_field` member

#### Scenario: An empty y_field array is returned as an empty array

- **WHEN** a caller saves a chart with `y_field` `[]`
- **THEN** the returned chart carries `y_field` `[]`

#### Scenario: Scalar and null elements are kept, not rejected

- **WHEN** a caller saves a chart with `y_field` `5`, or `[1, true, null]`
- **THEN** the request succeeds and the chart is returned with `y_field` `["5"]`, or `["1", "true", null]`

#### Scenario: A malformed y_field is rejected

- **WHEN** a caller saves a chart whose `y_field` is an object, or an array holding an object or an empty
  array (`{}`, `[{}]`, `[[]]`)
- **THEN** the request is rejected with `400` and machine code `bad_request`
