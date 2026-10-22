## 1. Mapping configuration model and loading

- [x] 1.1 Add `sync.mappingFile` to `SyncProperties` (env-driven path) and document it in `application.yml` + `README.md`
- [x] 1.2 Define the mapping model: a `ColumnMapping` record (`column`, `source`, `kind`, optional `transform`, optional `default`) and a `MappingConfig` holder for the ordered column list
- [x] 1.3 Implement loading of the YAML mapping file into an immutable `MappingConfig` bean at startup, failing with a clear error on unreadable/invalid file
- [x] 1.4 Validate at load time: reject unknown `$`-prefixed source tokens (only `$sourceFile` allowed) and unknown transform names
- [x] 1.5 Ship the default mapping file reproducing the current `dial_usage_log` columns + `_source_file`, and default `sync.mappingFile` to it

## 2. Transform registry

- [x] 2.1 Define a `Transform` abstraction and a closed, name-keyed registry
- [x] 2.2 Implement `iso-to-ch-datetime` (port `normalizeTime`) and `string-array` (port `stringArray`) transforms
- [x] 2.3 Unit-test each transform, including blank/missing inputs and the configured-default fallback

## 3. Mapping engine (replaces RowMapper/UsageLogRow)

- [x] 3.1 Implement `MappingEngine.toRow(JsonNode record, String sourceFile)` returning an ordered `Map<String,Object>`: resolve source (dotted path or `$sourceFile`), coerce by kind, apply transform, apply default
- [x] 3.2 Delete `RowMapper` and `UsageLogRow`; wire `MappingEngine` into `DataSyncService.processFile`
- [x] 3.3 Change `UsageLogWriter.insert` to accept generic row maps instead of `UsageLogRow` (serialization stays `JSONEachRow`)
- [x] 3.4 Replace `RowMapperTest` with `MappingEngineTest` covering path resolution, each kind, `$sourceFile` injection, allowlist (undeclared/heavy fields excluded), and `response_status` kept as string

## 4. Fail-fast startup validation

- [x] 4.1 Add a `DESCRIBE TABLE` introspection call to the writer client returning the table's columns and declared types
- [x] 4.2 Implement a startup validator (runs after mapping load, before the scheduler can fire) that checks every configured column exists and each kind is compatible with the table type
- [x] 4.3 Normalize ClickHouse type spellings (strip `Nullable(...)`/`LowCardinality(...)`) for comparison; warn-and-allow on unrecognized spellings; allow table-default columns (e.g. `_ingested_at`) to be unmapped
- [x] 4.4 On mismatch, fail startup with a message naming the offending column(s) and distinguishing "ClickHouse unreachable" from "schema mismatch"
- [x] 4.5 Test the validator for both match (starts) and mismatch (missing column, incompatible kind → refuses to start)

## 5. Verification

- [x] 5.1 Confirm default mapping yields byte-identical inserts to the previous hard-coded behavior (regression check against existing data-sync tests)
- [x] 5.2 Run `./gradlew clean build` (compile + checkstyle + tests) and confirm it passes
- [x] 5.3 Confirm the `data-sync` spec matches shipped behavior (delta merges cleanly at archive time)
