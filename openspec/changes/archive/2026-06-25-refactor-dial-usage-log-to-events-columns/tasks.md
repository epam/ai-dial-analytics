## 1. Deriver mechanism (ingest)

- [x] 1.1 Add `Deriver` functional interface (`Object derive(List<JsonNode> sources, Map<String,Object> args)`) in `sync/mapping`.
- [x] 1.2 Add `DeriverRegistry` (`@Component`, mirrors `TransformRegistry`; constructor takes the `JsonMapper` bean) with `get`/`contains`/`names`, registering: `hash-uuid`, `classify-event-kind`, `json-extract-string`, `json-extract-uint`, `json-array-length`, `byte-length`, `success-from-status`.
- [x] 1.3 Implement `hash-uuid` (deterministic UUIDv5 over a fixed namespace + joined source texts; never null) and `success-from-status` (`200 <= status <= 299`).
- [x] 1.4 Implement `classify-event-kind` (regex over `request_uri`: `…/chat/completions`→`llm_call`, `…/embeddings`→`embedding`, `…/mcp`→`mcp`, `…/route/…`→`route`, else `""`).
- [x] 1.5 Implement `json-extract-string` / `json-extract-uint` (try each source in order; accept an object node or a JSON-encoded string; read `args.path`; default `""`/`0`) and `json-array-length` (parse source, walk `args.path`, return array size else `0`) and `byte-length` (UTF-8 byte length; `0` if blank).
- [x] 1.6 Extend `ColumnMapping` record with `derive`, `sources`, `args` + `isDerived()`; add a compact convenience constructor delegating the existing 6-arg form so current call sites still compile.
- [x] 1.7 Add the derived branch to `MappingEngine.resolve(...)` (walk `sources` → call deriver → `applyFallback`); inject `DeriverRegistry`.
- [x] 1.8 Update `MappingFileLoader.parseColumn(...)` to branch on `derive`: require `kind` + non-empty `sources`, reject `source`, validate the deriver name, read optional `args`; keep the mapped path otherwise. Add `DeriverRegistry` constructor param.
- [x] 1.9 Wire `DeriverRegistry` into `configuration/MappingConfiguration.mappingConfig(...)` (pass to `new MappingFileLoader(...)`).

## 2. Schema (DDL + mapping + validator)

- [x] 2.1 Rewrite `src/main/resources/db/migration/V1__create_dial_usage_log.sql` to the 38-column events schema (events DDL order); keep `ReplacingMergeTree(_ingested_at)`, daily partition, `ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)`, 1-year TTL; rename `idx_user_id`→`idx_user_hash`; update the header comment (drop `assembled_response` from the persisted-PII note).
- [x] 2.2 Rewrite `src/main/resources/mapping/dial-usage-log.yaml`: direct entries (rename `user_id`→`user_hash` keeping `source: user.id`; drop `api_type`/`user_title`/`request_protocol`/`assembled_response`); derived entries for all calculated columns; leave `_ingested_at` + the 3 blocked columns unmapped; refresh the header comment.
- [x] 2.3 Add `UUID` → `TypeFamily.STRING` to `SchemaValidator.classify(...)` so `event_id` (string kind) reconciles cleanly.

## 3. Query surface

- [x] 3.1 Rewrite `DialUsageLogSchema.orderedColumns()` to the new queryable set (`api_type`→`event_kind`, `user_id`→`user_hash`, add the new scalar dims/metrics; map `event_id`→UUID, `success`→BOOLEAN; keep bodies/JSON/prices/`parent_deployment`/`core_parent_span_id` excluded).

## 4. ClickHouse JSON-type enablement

- [x] 4.1 Add `src/test/resources/clickhouse/experimental.xml` (default profile with `allow_experimental_json_type=1`) and mount it in `AbstractClickHouseIntegrationTest` via `withCopyFileToContainer(... , "/etc/clickhouse-server/users.d/experimental.xml")`.
- [x] 4.2 Add `local_env/clickhouse/users.d/experimental.xml` and a `:ro` volume entry in `local_env/docker-compose.yml`.
- [x] 4.3 Build checkpoint: verified `CREATE TABLE` with `JSON` succeeds on the pinned 24.8 image (with `allow_experimental_json_type`); no `String` fallback needed.

## 5. Tests

- [x] 5.1 Add `DeriverRegistryTest`: `hash-uuid` determinism/stability; `classify-event-kind` per URI pattern + default; `json-extract-string`/`-uint` (nested path, source fallback order, missing→default, object vs string input); `json-array-length`; `byte-length` (multi-byte UTF-8); `success-from-status` boundaries (199/200/299/300).
- [x] 5.2 Extend `MappingEngineTest` with derived-column coverage (a record yielding `event_id`, `event_kind`, `success`, `*_bytes`, MCP fields).
- [x] 5.3 Extend `MappingFileLoaderTest`: parse a derived entry; reject unknown `derive`; reject derived entry missing `sources`; reject entry declaring both `source` and `derive`.
- [x] 5.4 Update `SchemaValidatorTest.liveSchema()` to the 38-column table (incl. `UUID`, `Bool`, unmapped `JSON`/`UInt32` blocked cols); keep matching/missing/incompatible/nullability cases retargeted to surviving columns.
- [x] 5.5 Update `DataSyncServiceTest` NDJSON fixture + assertions for derived outputs (`event_id` non-empty, `event_kind` classified, `success`); keep batch/skip/drop assertions.
- [x] 5.6 Update `AbstractClickHouseIntegrationTest` `COLUMNS` + `Row` (rename `user_id`→`user_hash`, drop `api_type`, satisfy non-nullable calc columns) and retarget the structured-query integration tests + `DialUsageLogQueryRenderTest` + `QueryTranslatorValidationTest` (`api_type`→`event_kind`, `user_id`→`user_hash`; base-schema field-list assertion to the new set).

## 6. Docs & validation

- [x] 6.1 Update `docs/configuration.md`: document the derived-column syntax (`derive`/`sources`/`args`) + the registered derivers, and add the `allow_experimental_json_type` operator note.
- [x] 6.2 `./gradlew checkstyleMain checkstyleTest` + targeted unit tests (`sync.mapping.*`, `*DataSyncServiceTest`) pass; full `./gradlew clean build` passes on a Docker host (integration suite + JSON-type checkpoint).
- [x] 6.3 End-to-end smoke via `local_env`: ingest a sample gzip NDJSON log; confirm `event_id` stable, `event_kind`/`success`/`mcp_*`/`*_bytes` populated, blocked columns empty, dropped columns gone; `POST /v1/queries/execute` filtering on `event_kind` returns rows; `GET /v1/queries/entities/schema/dial_usage_log` lists the new fields.
