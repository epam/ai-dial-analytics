## MODIFIED Requirements

### Requirement: Widget model

A widget SHALL consist of:

- `id` — client-assigned, non-blank, at most 64 characters, unique within its dashboard;
- exactly one query source: `saved_query_id` (a reference to a saved query) or `inline_query` (a query the
  widget holds itself; see **Inline query**);
- `title`, `description` — optional author-written text, returned to every viewer;
- `row_limit` — required, a whole number from 1 to 1000;
- `view` — optional: `query`, `table`, `single_value`, `time_series`, `share` or `heatmap`; absent means the
  query's own view;
- `value_column` — optional, the aggregate a single-value widget shows;
- `change_direction` — optional: `higher_is_better` or `lower_is_better`;
- `settings` — optional, any JSON object the client keeps with the widget (see **Settings**);
- `layout` — required `{x, y, w, h}`, integers on a 12-column grid;
- `title`, `description`, `value_column` and `saved_query_id` — each at most 1000 characters.

The system SHALL store `view`, `value_column` and `change_direction` as given and SHALL NOT check them
against the query the widget runs: whether a column exists or suits a view is decided when the client runs
and renders the widget.

#### Scenario: A widget round-trips unchanged

- **WHEN** a dashboard is created with a widget carrying every member
- **THEN** reading it back returns that widget with every member unchanged, apart from the chart
  normalization of the saved-query chart model (see **Inline query**)

#### Scenario: Optional widget members are omitted when absent

- **WHEN** a dashboard is created with a widget carrying only `id`, `saved_query_id`, `row_limit` and
  `layout`
- **THEN** the returned widget has no `view`, `title`, `description`, `value_column`, `change_direction` or
  `settings` member, rather than members with a `null` value

### Requirement: Inline query

An `inline_query` SHALL be `{ name, query | sql, result_view?, chart? }`: a required non-blank `name` of at
most 1000 characters, exactly one body — a structured `query` or a `sql` string, a blank `sql` counting as
absent and being neither stored nor returned — an optional `result_view` (`table` or `chart`, defaulting to `table` as a saved query's does), and
an optional `chart` in the saved-query chart model (see [saved-queries](../saved-queries/spec.md), "Saved
query chart configuration"), stored and returned as that model defines it, so a string `y_field` is
returned as a one-element array. An absent or blank `name` SHALL be `422
validation_error`.

It SHALL carry no `time`, `tag`, `scope`, `source`, owner, `generation` or timestamp: the widget runs under
its dashboard's time intent and belongs to its dashboard. Those are not members of an inline query, so a
client copying a saved query into a widget SHALL copy only the members above; any other member is an
unrecognised field and SHALL be rejected with the service-wide `400 bad_request` naming it.

On every create and replace the system SHALL validate **every** inline body exactly as the saved-queries
capability validates a saved query body (see [saved-queries](../saved-queries/spec.md), "Saved query body
validation" and "A saved query is not a template"): against the **calling caller's** view of the catalog,
by the same translation the query surface performs, without executing it. A body the translation rejects
SHALL be reported with the status and machine code the query surface gives it, usually `400 bad_request`,
and with that message prefixed by `widget <id>: ` so the client can tell which widget failed. A parameter
expression, an absent
or doubled body, or a body larger than the saved-query body maximum SHALL be `422 validation_error`. A body
binding a literal to a `sensitive` column SHALL be accepted, as a saved query body is.

An inline query is a snapshot. When a client copies a saved query into a widget, the copy SHALL NOT follow
the saved query afterwards, and the service SHALL keep no link between them.

#### Scenario: An inline SQL widget is stored

- **WHEN** a caller creates a dashboard whose widget holds an inline `sql` body the caller may execute
- **THEN** the response is `201` and the widget's `inline_query` is returned as sent, with `result_view`
  filled in when it was absent and the chart normalized per the saved-query chart model (a string `y_field`
  returned as a one-element array)

#### Scenario: An inline chart with several series round-trips

- **WHEN** a caller creates a dashboard whose widget's `inline_query` carries a `chart` with `y_field`
  `["requests", "errors"]`
- **THEN** reading the dashboard back returns that chart with `y_field` `["requests", "errors"]`, in that
  order

#### Scenario: An untranslatable inline body is rejected

- **WHEN** a caller creates a dashboard whose widget holds an inline `sql` body the translation front end
  rejects
- **THEN** the response is `400` with machine code `bad_request` and the message
  `POST /v1/queries/execute-sql` gives for that SQL, prefixed by `widget <id>: `, and nothing is persisted

#### Scenario: An inline body naming an entity the caller cannot resolve is rejected

- **WHEN** a caller creates a dashboard whose inline body reads an entity the caller cannot resolve
- **THEN** the response is `400` with machine code `bad_request`, and does not reveal whether the entity is
  absent or merely invisible to the caller

#### Scenario: An inline query defaults its result view

- **WHEN** a widget's `inline_query` carries a `name` and a `sql` body and no `result_view`
- **THEN** the response is `201` and the stored `inline_query` has `result_view` of `table`

#### Scenario: A saved query copied whole into a widget is rejected

- **WHEN** a widget's `inline_query` carries a saved query's `time` or `scope` member
- **THEN** the response is `400` with machine code `bad_request` naming that member, and nothing is
  persisted

#### Scenario: An inline query with two bodies is rejected

- **WHEN** a widget's `inline_query` carries both `query` and `sql`
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A replace revalidates every inline body

- **WHEN** a caller replaces a dashboard, changing only its `name`, and one of its stored inline bodies reads
  an entity the caller cannot resolve
- **THEN** the response is `400` with machine code `bad_request` and the dashboard is unchanged

#### Scenario: A widget reading a deleted table blocks every replace that keeps it

- **GIVEN** a dashboard whose widget's inline body reads a table that has since been deleted
- **WHEN** a caller allowed to write the dashboard replaces it, keeping that widget and changing only `name`
- **THEN** the response is `400` naming the widget and the dashboard is unchanged, although the dashboard's
  `permissions.write` reads `true`, since the flags do not translate bodies; a replace that removes or
  rewrites that widget succeeds
