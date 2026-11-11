## Why

Data-sync captures raw user content and credentials into the system tables: prompt and response
bodies, the merged answer, JWT claims, request headers, cache markers and rating comments. The
catalog already flags each of these columns `sensitive`, but that flag only governs who can *read*
them. A deployment that must not store this content at all has no supported way to say so. Dropping
the payload ingest target also loses that table's key, lineage and identity columns, and it leaves
`rate_analytics.comment` untouched. Overriding the whole `sync.targets` list or hand-editing mapping
files is fragile and is not documented as a contract.

## What Changes

- New ingest setting `sync.sensitive-columns-enabled` (`SYNC_SENSITIVE_COLUMNS_ENABLED`), default
  `true`. At the default, ingest is unchanged and the catalog is not consulted for it.
- When it is `false`, each ingest target leaves out of its rows every mapped column that the catalog
  flags `sensitive` on that target's table, so the column takes its ClickHouse default (`NULL` for
  the `Nullable(String)` bodies and `comment`, `{}` for the `JSON` columns `jwt_claims` and
  `request_tags`). The set comes from the catalog flag, not from a hard-coded list, so a column seeded
  `sensitive` later is covered without a code change.
- Rows are still written. Keys, partition and grain columns, identity and every non-sensitive column
  are kept, so deduplication, retention and the `dial_usage_log` ↔ `dial_usage_log_payload` grain join
  behave as before.
- Startup fails if a column the ingest cannot do without (a deduplication-key, partition, ordering or
  grain column of the target's table) is flagged `sensitive`. Leaving it out would corrupt dedup or
  the join.
- Startup also fails if a target names a table the catalog does not define: its sensitivity is unknown, and
  writing it as mapped would store what the switch is meant to keep out.
- Request-delta storage: when `request_body` is left out, no request-delta stage is built for that
  target. Its chain and cache-marker columns keep their defaults, so `request_cache_control` (sensitive)
  is never written. Startup logs a warning if `SYNC_REQUEST_BODY_MODE` is not `whole`.
- Startup logs, per target, the columns being left out.
- The setting applies only to rows written while it is in effect. Rows already stored are not changed.
- Unaffected: catalog flags, read-side masking, the `/v1` API, and the schema of the ClickHouse tables.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: a new requirement, "Sensitive columns can be excluded from ingest", covering the
  switch, how it resolves columns from the catalog, the startup guards and the interaction with
  request-delta storage. "The request body storage mode is configurable" gains a clause saying the
  mode has no effect on a target whose `request_body` is excluded.

## Impact

- **Config**: one new property, `sync.sensitive-columns-enabled` / `SYNC_SENSITIVE_COLUMNS_ENABLED`
  (`application.yml`, `SyncProperties`, one row in `docs/configuration.md`).
- **Code**:
  - `MappingConfiguration` builds each target's `MappingConfig` without the excluded columns before
    it compiles the `MappingEngine`, reading the sensitive flags from `ColumnMappingRepository` only
    when the switch is off.
  - `RequestDeltaStages` then sees the reduced mapping.
  - `SchemaValidator` validates only the columns that are still mapped.
  - The scheduled sync, the poller and backfill all share the `syncTargets` bean, so all three are
    covered.
- **Data**: no migration. The excluded columns stay physically present and read back at their
  defaults on new rows. Queries, pipelines and saved queries that read them see `NULL` / `{}` for
  rows ingested while the switch is off.
- **APIs**: none.
