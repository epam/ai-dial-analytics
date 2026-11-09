# ADAS 0.24.0 — upgrade notes

What an environment has to do to move from **0.23.0** to **0.24.0**. An environment on an earlier build first
follows [`release-0.23.0-upgrade.md`](release-0.23.0-upgrade.md).

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
  older than 0.24.0 refuses a pipeline bound to `request_body_full`.
- `QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS` and `QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS` — new; see
  [`configuration.md`](configuration.md#request-body-rebuild).

| Change | Effect | Action |
|---|---|---|
| Request-delta storage, opt-in (`SYNC_REQUEST_BODY_MODE=delta`) | Readers of `request_body` see only a later turn's new messages | Only when you set `delta`: re-point pipelines and readers to `request_body_full`, as above |
