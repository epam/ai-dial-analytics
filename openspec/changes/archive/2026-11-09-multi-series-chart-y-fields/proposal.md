## Why

A saved query's chart names exactly one result column for its Y axis (`chart.y_field` is a string), so the
Query Builder cannot persist a multi-line chart that plots several series against one X axis. Turning
`y_field` into an array lets a chart carry several series, and a one-element array keeps the current
single-series behavior.

## What Changes

- **BREAKING (response shape)**: `chart.y_field` in saved-query and dashboard `inline_query` responses is
  an array of result-column names instead of a single string. A one-element array is the single-series
  chart of today.
- Requests accept `y_field` as an array. A legacy single string is still accepted and read as a
  one-element array, so existing clients keep working while they migrate.
- Charts already stored with a string `y_field` (saved queries, saved-query revision snapshots, dashboard
  inline queries) are returned as a one-element array without a data migration.
- ADAS still never validates the chart's names against the catalog; apart from the `y_field` normalization
  above, it stores and returns the chart as sent.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `saved-queries`: adds a requirement defining the chart configuration (`type`, `x_field`, `y_field` as an
  array of series, legacy string tolerance).
- `dashboards`: the "Inline query" requirement points at that chart model and its "returned as sent"
  scenario names the chart normalization (a string `y_field` comes back as a one-element array) as an
  exception alongside the `result_view` default; the "Widget model" round-trip scenario names the same
  exception.

## Impact

- API: `POST`/`PUT /v1/saved-queries`, `GET /v1/saved-queries[/{id}]`,
  `GET /v1/saved-queries/{id}/revision/{revision}`, and the dashboard endpoints that carry
  `inline_query.chart`; the OpenAPI schema of `chart.y_field` becomes `array<string>`.
- Specs: `saved-queries` (added chart requirement) and `dashboards` (modified "Widget model" and "Inline query").
- Code: the chart record in `service.query.saved.model`; tests that build or assert on charts.
- Docs: the breaking change and its rollback condition go into `docs/release-0.24-upgrade.md`.
- No schema migration, no config key, no new dependency. Audit snapshots are stored as they were written,
  but the revision endpoint reads them through the same tolerant chart model, so an old snapshot's string
  `y_field` is returned as a one-element array.
