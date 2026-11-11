## Context

Data-sync builds its ingest targets once at startup (`MappingConfiguration.syncTargets`): it loads each
`sync.targets[i]` mapping file into a `MappingConfig` and compiles it into a `MappingEngine`. The scheduled run, the
poller and backfill all consume that one `List<SyncTarget>` bean. `RequestDeltaStages` and `SchemaValidator`
read the same bean.

The catalog (PostgreSQL `column_mapping`) already flags the columns that hold raw content or credentials
`sensitive`. With the seeded catalog those are six columns of `dial_usage_log_payload` and `comment` on
`rate_analytics`. The flag only drives read-side masking today. System tables reject `PATCH /v1/tables/{name}/schema`,
so their flags change only through catalog migrations.

`request_cache_control` is not mapped at all. The request-delta stage writes it at flush, along with the chain
hashes, and only on a target whose mapping maps `request_body`, `project_id` and `user_hash`.

## Goals / Non-Goals

**Goals:**

- One switch that stops the service from storing every column the catalog flags `sensitive` on the ingest
  targets, while keeping rows, keys, the grain join and dedup intact.
- No new list of sensitive columns to maintain. The catalog flag is the single source.
- Zero cost and zero behaviour change at the default.

**Non-Goals:**

- Purging or rewriting sensitive values already stored. Retention (`SYNC_PAYLOAD_TTL_DAYS`) or manual
  ClickHouse work covers that.
- Dropping the physical columns or changing catalog flags.
- Per-table or per-column switches. A custom mapping file still covers a finer-grained need.
- Governing user tables or pipelines that copy sensitive data. They now read the defaults, and the existing
  aggregate governance already covers their read side.

## Decisions

### Exclude at mapping-compile time, not at insert time

The configured `MappingConfig` is filtered before the `MappingEngine` is built. The excluded columns are
simply not in the target's mapping.

- The engine never resolves, transforms or inline-strips their source values, so the content is never
  materialised for the row and costs no CPU.
- `JSONEachRow` omits the keys, so ClickHouse applies the column defaults (`NULL` / `{}`). We rely on
  `input_format_defaults_for_omitted_fields`, which is on by default. Unmapped columns such as `_ingested_at`
  already depend on it.
- `RequestDeltaStages` then sees no `request_body` and does not qualify the payload target, so
  `request_cache_control` and the chain columns are never written. That needs no special case beyond a
  clearer warning.
- `SchemaValidator` validates only mapped columns, so it needs no change.

*Rejected:* stripping keys from each row in `RunSink` / `UsageLogWriter`. The engine would still extract and
process the content, and the delta stage would still cut and hash a body that is then thrown away.

*Rejected:* nulling values inside `MappingEngine`. An explicit `null` into a non-nullable `JSON` column fails
the insert. Writing `{}` by hand would duplicate the table's defaults in code.

### Resolve from the catalog at startup, only when the switch is off

`syncTargets` takes `ColumnMappingRepository` and `TableDefinitionRepository`. It queries them only when
`sync.sensitive-columns-enabled=false`, in one batched lookup over the configured target tables
(`findByTableNameInOrderByTableNameAscOrdinalAsc`).

- Spring Boot's Flyway initializer runs before the JPA `EntityManagerFactory`, which the repositories depend
  on. The catalog migrations have therefore run by the time this bean is built, unlike the ClickHouse
  migrations, which `RequestDeltaStages` must defer for.
- With the switch off, `POSTGRES_MIGRATIONS_ENABLED=false` still works as long as the catalog was migrated
  out of band. An unreachable catalog fails startup, as every other catalog dependency does.
- A restart picks up catalog changes. Re-resolving per run was rejected: for system tables the flags change
  only by migration, which already implies a restart. Re-resolving would also require rebuilding engines and
  delta stages mid-flight.

*Rejected:* a hard-coded column list in config or code. It drifts from the catalog the moment a new
sensitive column is seeded. That is exactly the failure the switch exists to prevent.

### Guard the columns ingest depends on

The switch must not be able to break the table, so startup fails if any of these columns is in the excluded set:

- the dedup key `trace_id`, `core_span_id`, `request_time`;
- the target table's `grain_key`, `identity_column`, `ordering_key`, `partition_column`, `version_column` or
  `ttl_column` from `table_definition`.

With the seeded catalog none of these is sensitive, so the guard is a safety net against a future migration.
Failing loudly beats silently dropping every row (blank key), collapsing all rows onto one `event_id`, or landing
rows in the epoch partition where retention drops them.

### Targets missing from the catalog fail startup

A custom target can name a table that is not in the catalog, for example a renamed payload table. Its sensitivity is
unknown, so startup fails naming it, and the operator defines the table in the catalog first. *Rejected:* writing it
as mapped with a warning. For a setting whose purpose is to keep content out, that degrades silently: bodies,
claims and headers would still be stored, with one log line as the only sign.

### Property shape

`Boolean sensitiveColumnsEnabled` on `SyncProperties`, normalised `null → true` the same way
`inlineFilesDropTruncated` is, bound from `${SYNC_SENSITIVE_COLUMNS_ENABLED:true}`. A positive name with default
`true` reads naturally next to the other `sync.*` booleans, and keeps today's behaviour as the default.

## Risks / Trade-offs

- [Consumers that expect content get `NULL` / `{}`] Saved queries, dashboards, pipelines or the rebuilt-request
  computed fields that read the excluded columns see defaults for rows written while the switch is off. They
  do not error. → The spec states it, the startup `INFO` log lists the excluded columns, and the
  `configuration.md` row says what turning it off loses.
- [Toggling leaves a mixed history] Rows written under each setting coexist in the same partitions. → This is
  expected and stated in the spec. Re-ingesting a day under `true` restores values only for that day's rows.
- [Delta chains across a toggle] Rows written under `delta` before the switch was turned off keep their cut
  bodies. New rows carry no path, so they never become parents or children. Rebuilt bodies of older rows are
  unaffected, unless a parent is re-ingested while the switch is off: it is rewritten with no body and no path, so a
  child stored from another blob can no longer be rebuilt, as for a parent re-ingested under `whole`. → The spec
  states it next to the re-ingest rule, and the request-body-mode warning flags the mode as inert.
- [Catalog drift from the physical names] Matching uses `column_mapping.source_name`, which is the ClickHouse
  column name and the mapping's `column`. A mapping column with no catalog row is not excluded. → For the
  seeded tables every sensitive column has a catalog row. Tests assert the seeded exclusion set.

## Migration Plan

No schema or catalog migration. To enable, set `SYNC_SENSITIVE_COLUMNS_ENABLED=false` and restart. To roll
back, unset it and restart. Existing rows are untouched in both directions. One new config key, no new
credential.

## Open Questions

None.
