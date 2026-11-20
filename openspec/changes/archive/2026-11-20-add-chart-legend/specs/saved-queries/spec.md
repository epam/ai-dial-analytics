## MODIFIED Requirements

### Requirement: Saved query chart configuration

A saved query's `chart` SHALL be `{ type, x_field, y_field, legend }`, a client rendering preference that the
system stores and returns as sent, apart from the `y_field` normalization below:

- `type` — the chart type, a string;
- `x_field` — the result column that feeds the X axis, a string;
- `y_field` — the result columns that feed the Y axis, an **array** of strings, one element per series. A
  one-element array SHALL describe a single-series chart; several elements SHALL describe a multi-series
  chart plotted against the one `x_field`;
- `legend` — how the client shows the chart's legend, an object `{ visible, position }`:
  - `visible` — whether the legend is shown, a boolean;
  - `position` — where the legend sits, a string. The client knows `above`, `right`, `below` and `left`;
    like `type`, the system SHALL NOT check it against a list of values, and SHALL store and return any
    string as sent (the client renders an unknown value as `below`).

Every member SHALL be optional and nullable, including each member of `legend`: a partially configured chart
is a legitimate saved state, and an absent or `null` member SHALL be omitted from the response. An empty
`y_field` array is not omitted: it SHALL be stored and returned as `[]` (no series). Likewise a `legend` with
no members is not omitted: `{}`, or a `legend` whose members are all `null`, SHALL be returned as `{}`. The
system SHALL NOT validate `x_field` or `y_field` against the catalog, because they name result columns
(aliases, derived labels) rather than catalog columns, and SHALL return the `y_field` elements in the order
the caller supplied them.

For compatibility, a request whose `y_field` is a single string SHALL be accepted and read as a
one-element array (an empty string becomes `[""]`), and a chart stored before `y_field` became an array —
in a saved query, a saved-query revision snapshot, or a dashboard inline query — SHALL be returned with its
string `y_field` as a one-element array. The elements are not otherwise checked: a number or boolean, alone
or inside the array, SHALL be read as its string form (`5` becomes `["5"]`), and a `null` element SHALL be
kept and returned as `null` in its position. A `y_field` that is an object, or an array holding an object
or an empty array, SHALL be rejected with `400` and machine code `bad_request`. The outcome for an array
holding a non-empty array (`[["requests"]]`) is not defined by this requirement.

A chart stored before `legend` existed SHALL be returned without a `legend` member; no stored chart is
rewritten. A `legend` that is neither an object nor `null` (a string, number, boolean or array), a `legend`
whose `visible` or `position` is an object or an array, and a `legend` carrying a member other than
`visible` and `position`, SHALL be rejected with `400` and machine code `bad_request`; for an unknown member
the message names it. The outcome for a `visible` that is a string or number, or a `position` that is a
number or boolean, is not defined by this requirement.

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

#### Scenario: A legend round-trips wherever the chart is carried

- **WHEN** a caller saves a chart with `legend` `{ "visible": true, "position": "below" }` on a saved query,
  or on a dashboard widget's `inline_query`
- **THEN** the create response, every later read of that saved query or dashboard, the saved query's list
  entry, and its revision snapshot for that save carry `chart.legend` as
  `{ "visible": true, "position": "below" }`

#### Scenario: A hidden legend keeps visible false

- **WHEN** a caller saves a chart with `legend` `{ "visible": false, "position": "left" }`
- **THEN** the chart is returned with `legend` `{ "visible": false, "position": "left" }`; `visible` is kept,
  not omitted

#### Scenario: An unknown legend position is stored as sent

- **WHEN** a caller saves a chart with `legend` `{ "position": "top-left" }`
- **THEN** the request succeeds and the chart is returned with `legend` `{ "position": "top-left" }`, with no
  `visible` member

#### Scenario: A null or absent legend is omitted

- **WHEN** a caller saves a chart with `legend` `null` or no `legend`
- **THEN** the returned chart has no `legend` member

#### Scenario: A replace without a legend clears it

- **GIVEN** a saved query whose chart carries a `legend`
- **WHEN** a caller replaces the saved query with a chart that has no `legend`
- **THEN** the response and every later read carry no `legend` member, and the revision snapshot of the
  earlier save still carries it

#### Scenario: An empty legend is returned as an empty object

- **WHEN** a caller saves a chart with `legend` `{}`, or `{ "visible": null, "position": null }`
- **THEN** the returned chart carries `legend` `{}`

#### Scenario: A chart stored without a legend is returned without one

- **GIVEN** a saved query, a saved-query revision snapshot, or a dashboard inline query whose stored chart
  has no `legend` member
- **WHEN** a caller reads it
- **THEN** the chart is returned with no `legend` member

#### Scenario: A malformed legend is rejected

- **WHEN** a caller saves a chart whose `legend` is `"below"`, `true`, `5` or `[]`, whose `legend.visible`
  or `legend.position` is an object or an array, or whose `legend` carries another member
  (`{ "align": "start" }`)
- **THEN** the request is rejected with `400` and machine code `bad_request`, and for the unknown member the
  message names `align`
