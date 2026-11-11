# ADAS 0.24 — upgrade notes

What an environment has to do to move from **0.23** to **0.24**. An environment on an earlier build first
follows [`release-0.23-upgrade.md`](release-0.23-upgrade.md).

## Request bodies rebuilt on read

New computed fields `dial_usage_log_payload.request_body_full` and `request_body_rebuild` return a chat turn's whole
request when it is stored in delta form (see [`heavy-columns.md`](heavy-columns.md#request-bodies-in-delta-form)).
Nothing changes under `SYNC_REQUEST_BODY_MODE=whole`, the default.

- **Before setting `SYNC_REQUEST_BODY_MODE=delta`, re-point every pipeline whose `transform.inputs` binds
  `dial_usage_log_payload.request_body` to `dial_usage_log_payload.request_body_full`** (and bind
  `request_body_rebuild` too if the pipeline should tell a `partial` rebuild apart), and move saved queries and other
  readers that need a turn's whole request the same way. `request_body` keeps returning what is stored;
  `request_body_full` returns the rebuilt request, and under `whole` and `whole-with-path` it returns the stored body
  marked `stored`, so re-pointing first is safe. ADAS does not rewrite pipelines.
- **Rollback.** Re-point such pipelines back to `request_body` and switch ingest to `whole-with-path` first: a build
  older than 0.24 refuses a pipeline bound to `request_body_full`.
- `QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS` and `QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS` — new; see
  [`configuration.md`](configuration.md#request-body-rebuild).

## Multi-series charts

A chart's `y_field` — on a saved query and on a dashboard widget's `inline_query` — is now an array of result
columns, one per series (see [`saved-queries`](../openspec/specs/saved-queries/spec.md)). Responses always carry the
array, so a client that reads `y_field` as a string must move to the array; a one-element array is the old
single-series chart. Requests may still send a string, and charts stored before the upgrade are returned as a
one-element array without a migration.

- **Rollback.** A build older than 0.24 cannot read a chart stored as an array: reading that saved query, the
  scope list it appears in, its revision snapshot, or a dashboard holding such a widget fails. Before rolling back,
  rewrite each stored `y_field` array to its first element in `saved_query.chart`, `saved_query_aud.chart` and
  `dashboard.widgets` (the nested `inline_query.chart`).

| Change | Effect | Action |
|---|---|---|
| Request-delta storage, opt-in (`SYNC_REQUEST_BODY_MODE=delta`) | Readers of `request_body` see only a later turn's new messages | Only when you set `delta`: re-point pipelines and readers to `request_body_full`, as above |
| Chart `y_field` is an array (breaking response shape) | Saved-query and dashboard responses return `y_field` as an array; a string is still accepted on write | Move clients that read `y_field` to the array; before a rollback, rewrite stored arrays as above |
