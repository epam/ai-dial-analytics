## Context

The data-sync pipeline today is `blob → LogParser → RowMapper → UsageLogRow → toColumnMap →
UsageLogWriter`. `LogParser` (gzip NDJSON → `JsonNode`) and `UsageLogWriter` (batched `JSONEachRow`
insert) are already schema-agnostic. The schema coupling is concentrated in three places:

- `RowMapper.toRow` — reads ~19 fixed dotted JSON paths and applies two transforms
  (`normalizeTime`, `stringArray`) plus type coercions (`asLong`, `asString`).
- `UsageLogRow` — a record fixing the field set and Java types.
- `UsageLogRow.toColumnMap` — pins the exact ClickHouse column names and which fields are written.

Changing the table means editing all three and redeploying. The interview fixed four constraints:
single feed → single table; operator owns DDL (service issues none); fail-fast at startup on
config/table drift; mapping lives in a separate file referenced by `sync.mappingFile`.

Project conventions (`openspec/project.md`): env-driven config (`${ENV_VAR:default}`), base package
`com.epam.aidial.analytics`, Google Java Style, every new env var documented in `application.yml` +
`README.md`, and a change is done only when `./gradlew clean build` passes.

## Goals / Non-Goals

**Goals:**
- Make `dial_usage_log`'s column set, source paths, types, and transforms configurable via an
  external file, with zero behavior change under the shipped default mapping.
- Preserve the "drop sensitive payload fields" invariant *by construction* (allowlist).
- Detect config/table drift at boot and refuse to start, rather than failing mid-run.
- Keep `LogParser` and `UsageLogWriter` substantially unchanged.

**Non-Goals:**
- Service-issued DDL (`CREATE`/`ALTER`). The table stays operator-provisioned.
- Multiple feeds / multiple target tables in one run.
- An expression language for transforms. The transform set is closed and named.
- Changing the read path (`GET /v1/tables`) or the writer-credential isolation.

## Decisions

### 1. Mapping file format and binding
A separate YAML file (path from `sync.mappingFile`, env-driven) declares an ordered list of columns.
Each column entry:

```yaml
columns:
  - column: request_time          # ClickHouse column name (insert key)
    source: request.time          # dotted source path, or $sourceFile synthetic token
    kind: string                  # extraction kind: string | long | double | bool | array<string>
    transform: iso-to-ch-datetime # optional, from the closed registry
    default: "1970-01-01 00:00:00.000"  # optional fallback when source is absent/blank
```

Loaded at startup into an immutable `ColumnMapping` list (a `MappingConfig` bean). Chosen over
inline `application.yml` (`sync.columns`) so the mapping is swappable without redeploying app config
and stays readable at ~20 columns. Chosen over JSON for comment support and consistency with
existing YAML config.

**Rationale for a closed `kind` set rather than full ClickHouse types:** `JSONEachRow` lets
ClickHouse parse JSON scalars/arrays into the actual column types. The engine only needs to coerce
the source `JsonNode` into the right JSON shape (string/number/bool/array) and supply defaults. The
authoritative type lives in the table DDL; `kind` just selects the extraction strategy. This keeps
the engine from reimplementing ClickHouse's type system.

### 2. Mapping engine replaces RowMapper/UsageLogRow
A `MappingEngine.toRow(JsonNode record, String sourceFile)` walks the `ColumnMapping` list and
returns a `LinkedHashMap<String, Object>` (ordered for stable `JSONEachRow` output):

- Resolve `source`: `$sourceFile` → the blob name; otherwise split the dotted path and walk
  `node.path(seg)` segment by segment (same semantics as today's `node.path(...).path(...)`).
- Coerce by `kind` (`asString("")`, `asLong(0)`, `asDouble(0)`, `asBoolean(false)`, array→`List`).
- Apply `transform` if present.
- Apply `default` when the resolved value is absent/blank.

`UsageLogRow` and `RowMapper` are deleted. `UsageLogWriter.insert` and `DataSyncService.processFile`
change to carry `Map<String, Object>` rows instead of `UsageLogRow`. Existing `RowMapperTest`
becomes `MappingEngineTest`.

**Allowlist by construction:** only declared columns are read and written. Heavy fields
(`assembled_response`, `request.body`, `response.body`) are absent from the default mapping and
therefore unreachable — no denylist to maintain. This is the key reason for the allowlist model over
a "dump the whole record" approach.

### 3. Closed transform registry
A `Map<String, Transform>` keyed by name, seeded with exactly the two transforms in use:
- `iso-to-ch-datetime` — replace `T` with space (today's `normalizeTime`).
- `string-array` — coerce a JSON array to `List<String>` (today's `stringArray`); paired with
  `kind: array<string>`.

An unknown transform name is a fatal config error at load time. New transforms are added in code, by
name — deliberately not user-scriptable.

### 4. Fail-fast startup validation against the table
On startup (after the mapping loads, before the scheduler can fire), the service runs
`DESCRIBE TABLE <sync.table>` via the writer client and reconciles:
- Every configured `column` MUST exist in the table (excluding `_ingested_at`, which is
  `DEFAULT now()` and intentionally unmapped).
- The configured `kind` MUST be compatible with the table's declared type (a small compatibility
  map, e.g. `long` ↔ `Int*/UInt*`, `string` ↔ `String/LowCardinality(String)`, `array<string>` ↔
  `Array(String)`, `string`+`iso-to-ch-datetime` ↔ `DateTime64`).
- Failure throws at boot with a message naming the offending column(s), so the app does not start.

Implemented as an `ApplicationRunner`/`@PostConstruct` (or `InitializingBean`) on a startup-validator
bean. This introduces a boot-time dependency on ClickHouse reachability, accepted per the interview.

## Risks / Trade-offs

- **Boot-time ClickHouse dependency** → If ClickHouse is unreachable at startup, the service won't
  start. Accepted (fail-fast was the chosen drift behavior). Mitigation: the error message clearly
  distinguishes "CH unreachable" from "schema mismatch."
- **Type-compatibility map is approximate** → ClickHouse has many type spellings
  (`Nullable`, `LowCardinality`, `Enum`, modifiers). Mitigation: normalize the `DESCRIBE` type
  string (strip `Nullable(...)`/`LowCardinality(...)` wrappers) before comparison; on an unrecognized
  spelling, warn-and-allow rather than hard-fail, so exotic-but-valid tables aren't blocked.
- **Silent drop via mapping mistake** → Removing a column from the mapping silently stops populating
  it (data loss, not an error, since the table column just takes its default). Mitigation: documented
  behavior; the shipped default mapping reproduces today's schema exactly so the baseline is correct.
- **`$sourceFile` is special-cased** → A source value starting with `$` is reserved. Mitigation:
  document the reserved prefix; only `$sourceFile` is recognized, any other `$token` is a config
  error at load.
- **Mapping/DDL still maintained in two places** → Operators must `ALTER TABLE` *and* edit the
  mapping. Mitigation: fail-fast validation guarantees they can't diverge silently; this is the
  accepted cost of "operator owns DDL."

## Migration Plan

1. Ship a default mapping file (e.g. `config/mapping/dial-usage-log.yaml`) reproducing the current 19
   columns + `_source_file`, with `sync.mappingFile` defaulting to it.
2. Deploy. Behavior is identical to today; the startup validator confirms the shipped mapping matches
   the existing `dial_usage_log` table.
3. Rollback: revert the binary. No data migration occurred (operator owns DDL; no schema change was
   issued by the service).

## Open Questions

- Exact location/packaging of the default mapping file (classpath resource vs. external config dir).
  Leaning classpath resource as the default with `sync.mappingFile` overriding to an external path.
- Whether to expose a startup-validation toggle (strict vs. warn-only) for local development runs where CH
  may be absent. Default strict per the interview; a local override can be added later if it bites.
