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

## Sensitive columns kept out of ingest, opt-in

New `SYNC_SENSITIVE_COLUMNS_ENABLED` (see [`configuration.md`](configuration.md#data-sync-schedule)). Under `false`,
ingest stops writing the columns the catalog flags `sensitive` on each target table, and new rows hold their
ClickHouse defaults there (see [`data-sync`](../openspec/specs/data-sync/spec.md)). Nothing changes under `true`, the
default.

- **Under `false`, startup fails** when a target table has no catalog definition, or when a column it would leave
  out is a deduplication-key, grain, identity, ordering-key, partition, version or TTL column.
- Rows already stored keep their values, and switching back to `true` restores the columns on new rows only. Under
  `SYNC_REQUEST_BODY_MODE=delta`, a parent re-ingested under `false` loses its body, so its delta children can no
  longer be rebuilt.
- **Rollback.** A build older than 0.24 ignores the variable and writes sensitive columns again.

## Sessions-source setting

New endpoints `GET` and `PUT /v1/settings/sessions-source` store which source table holds session rows and which of
its fields play the session roles (see [`instance-settings`](../openspec/specs/instance-settings/spec.md)). Reading
needs the new privilege `SETTINGS_READ`, which every read-only bundle now carries; writing needs `SETTINGS_WRITE`,
which only `FULL_ADMIN` carries. Flyway migration `V53` creates the backing table at startup.

- **A `roles-mapping` that names raw privileges** rather than a bundle gains neither privilege. Add `SETTINGS_READ`
  to the roles whose holders use the sessions view, or they get `403` on the read.
- **Rollback.** A build older than 0.24 does not know `SETTINGS_READ` or `SETTINGS_WRITE` and fails startup on a
  `roles-mapping` that names either. Remove them before rolling back. The new table is ignored and may be left in place.

| Change | Effect | Action |
|---|---|---|
| Request-delta storage, opt-in (`SYNC_REQUEST_BODY_MODE=delta`) | Readers of `request_body` see only a later turn's new messages | Only when you set `delta`: re-point pipelines and readers to `request_body_full`, as above |
| Sensitive-column ingest switch, opt-in (`SYNC_SENSITIVE_COLUMNS_ENABLED=false`) | New rows hold defaults in the columns the catalog flags `sensitive` | None by default; under `false`, startup fails on an undefined target table or a sensitive key column |
| Sessions-source setting, new privileges `SETTINGS_READ` / `SETTINGS_WRITE` | Bundles gain them; raw-privilege mappings do not | Add `SETTINGS_READ` to raw-privilege mappings that need the setting; remove both names before a rollback |
