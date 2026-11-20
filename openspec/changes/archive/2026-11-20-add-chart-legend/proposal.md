## Why

The client lets a user show or hide a chart's legend and place it above, right of, below or left of the
plot, but the saved-query chart model has nowhere to keep that choice. Today a chart that carries `legend`
is refused with `400 bad_request` naming the unrecognized field, so the client cannot save it, and every
reopened chart falls back to the client default.

## What Changes

- The saved-query chart model gains an optional, nullable `legend` member:
  `{ "visible": <boolean>, "position": <string> }`, both members optional.
- `legend.position` is a client rendering preference stored and returned as sent, never checked against a
  list of values, the same as `chart.type`. The client knows `above`, `right`, `below` and `left`, and maps
  any other value to `below` itself.
- An absent or `null` `legend` is omitted from the response, as every other absent chart member is.
- The member applies wherever the chart model is carried: a saved query (`/v1/saved-queries`, list entries
  included), its revision snapshot (`GET /v1/saved-queries/{id}/revision/{revision}`), and a dashboard
  widget's `inline_query` (`/v1/dashboards`).
- Stored charts without `legend` are returned without it. No data migration, no new configuration.
- The committed OpenAPI document gains the `legend` schema.

Not breaking: the field is additive on both request and response. A build without it still reads a stored
`legend` (stored JSON is read leniently) and leaves it out of the response, but refuses any write that carries
one. So the server ships before a client starts sending `legend`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `saved-queries`: the "Saved query chart configuration" requirement adds the optional `legend` member
  (`visible`, `position`) and its scenarios. The dashboards spec already defers to this model for an
  `inline_query` chart, so it needs no delta of its own.

## Impact

- Code: `SavedQueryChart` (service model, shared by saved queries, revision snapshots and dashboard inline
  queries) gains the member, and a new `SavedQueryChartLegend` record is added next to it.
- API: additive member on `chart` in saved-query and dashboard request and response bodies;
  `docs/openapi.json` regenerated.
- Storage: none. Charts are stored as `jsonb` (`saved_query.chart`, its audit table, and `dashboard.widgets`),
  so the new member rides along with no schema change.
- Tests: integration round-trips for a saved query, its list entry, its revision snapshot and a dashboard
  inline query; the legacy no-legend read; the malformed-legend 400s.
