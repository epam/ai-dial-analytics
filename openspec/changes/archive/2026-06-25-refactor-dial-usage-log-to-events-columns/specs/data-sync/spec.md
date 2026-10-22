## ADDED Requirements

### Requirement: Closed named deriver registry

The service SHALL support a fixed, named set of **derivers** — derivation functions that compute a
column value from one or more source nodes of the parsed record — referenced by columns in the
mapping, and SHALL NOT support arbitrary user-supplied expressions or code. A derived column entry
SHALL declare a `derive` name, an ordered non-empty `sources` list of dotted JSON paths, and MAY
declare an `args` map of static parameters; it MUST NOT also declare a `source`. The registry SHALL
include derivers sufficient to populate the calculated `events` columns: a deterministic UUID hash
over its concatenated sources (for `event_id`); a request-URI classifier (for `event_kind`); JSON
sub-document field extractors that re-parse a source value that is itself a JSON string (or read it
directly when already an object) and read a dotted `args.path` as a string or as an unsigned integer
(for `response_id`, `cache_creation_tokens`, `reasoning_tokens`, `mcp_method`, `mcp_tool_call_name`);
a JSON array length (for `number_request_messages`); a byte length of a source string (for
`request_body_bytes`, `response_body_bytes`); and an HTTP-success predicate over `response.status`
(for `success`). A column referencing a deriver name that is not in the registry, or a derived column
missing its `sources` list, SHALL cause a fatal configuration error at load time.

#### Scenario: Derived column is computed by its named deriver

- **WHEN** a column declares a `derive` name with a `sources` list (and optional `args`)
- **THEN** the inserted value is computed by the named deriver from the resolved source nodes, then
  subjected to the same absent/blank fallback (default, else null if nullable, else the kind's zero
  value) as a mapped column

#### Scenario: event_id is deterministic across re-ingests

- **WHEN** two records share the same `trace_id` and `core_span_id`
- **THEN** the configured `event_id` deriver produces the identical UUID for both, so it is a stable
  proxy for the natural `(trace_id, core_span_id)` deduplication key

#### Scenario: Embedded-JSON extraction reads a nested field

- **WHEN** a JSON-extraction deriver is configured with ordered `sources` and an `args.path`, and the
  first source whose value is present parses as a JSON document
- **THEN** the deriver returns that document's value at `args.path`, falling through to the next source
  when an earlier source is absent or lacks the path

#### Scenario: Unknown or malformed deriver entry is rejected

- **WHEN** a column references a deriver name that is not in the registry, or declares `derive` without
  a non-empty `sources` list, or declares both `source` and `derive`
- **THEN** the service fails to load the mapping with a configuration error

## MODIFIED Requirements

### Requirement: Log parsing and transformation

The service SHALL read each blob as a gzip-compressed, newline-delimited JSON (NDJSON) stream and
transform each record into a ClickHouse row according to an external column mapping (see "Configurable
column mapping"). For each declared **mapped** column the service SHALL resolve the configured source —
a dotted JSON path within the record, or the `$sourceFile` synthetic token — coerce the resolved value
to the column's declared extraction kind, apply the configured transform (if any), and resolve absent
or blank source values as follows: if the column declares a default, the service SHALL use that
default; otherwise, if the column is declared `nullable`, the service SHALL emit a JSON `null` for that
column; otherwise the service SHALL fall back to the kind's zero value (so a non-nullable column
without a producer value still inserts safely). For a declared **derived** column the service SHALL
instead resolve each path in the column's `sources` list to a node and compute the value via the named
deriver (see "Closed named deriver registry"), then apply the same absent/blank fallback. The merged
streaming `assembled_response` field MAY be read as a deriver source (e.g. for `response_id` and
body-derived token counts) even though it is not persisted as a column. Columns whose source is a JSON
array SHALL be mapped using the `string-array` transform with the `array<string>` kind. The transform
that converts an ISO-like timestamp to a ClickHouse `DateTime64` literal (replacing `T` with a space)
SHALL be available as a named transform. Monetary columns (`total_price`, `deployment_price`) SHALL be
coerced so the producer's decimal value is preserved exactly (passed through without lossy binary
floating-point conversion). `response.status` SHALL be mapped with the numeric (long) kind so it is
stored as an integer.

#### Scenario: NDJSON record is mapped via the configured mapping

- **WHEN** a blob contains a valid NDJSON usage-log record
- **THEN** the service produces a row whose keys are exactly the configured column names, each value
  resolved from its configured source path (or computed by its configured deriver), coerced to its
  declared kind, and passed through its configured transform

#### Scenario: Derived column is computed at ingest

- **WHEN** a declared column specifies a `derive` name with `sources` instead of a single `source`
- **THEN** the service computes its value with the named deriver from the resolved source nodes and
  applies the column's absent/blank fallback

#### Scenario: Response status is stored as a number

- **WHEN** a record's `response.status` is mapped with the numeric kind
- **THEN** the inserted value is the status as an integer suitable for a `UInt16` column

#### Scenario: Absent value in a nullable column becomes null

- **WHEN** a declared column is nullable, has no configured default, and its source value is absent or
  blank in the record
- **THEN** the row emits a JSON `null` for that column rather than a zero or empty value

#### Scenario: Absent value in a non-nullable column without a default falls back to zero

- **WHEN** a declared column is not nullable, has no configured default, and its source value is absent
  or blank
- **THEN** the row uses the extraction kind's zero value so the insert remains valid

#### Scenario: Missing source falls back to the configured default

- **WHEN** a declared column's source value is absent or blank in the record and the column declares a
  default
- **THEN** the row uses the configured default for that column

#### Scenario: Monetary value is preserved exactly

- **WHEN** a record carries a `token_usage.price` or `token_usage.deployment_price` decimal value
- **THEN** the inserted value preserves the producer's decimal precision without binary
  floating-point rounding

### Requirement: Persisted columns are governed by the mapping (allowlist)

The service SHALL persist only the columns declared in the configured mapping (allowlist semantics):
a field that is not declared MUST NOT be written, regardless of its presence in the source record.
The shipped default mapping declares the heavy payload columns `request_body` and `response_body`, so
raw prompt/response content IS persisted to ClickHouse. The merged streaming response
`assembled_response` is NOT persisted as a column; it is read at ingest only as a deriver source. Whether
heavy content is captured is therefore controlled entirely by the mapping (removing those columns from
the mapping stops capturing them). When captured, access to those columns and their retention MUST be
governed operationally — column-level access controls plus a retention/TTL policy — because the service
applies no masking or redaction of its own.

#### Scenario: Declared heavy payload columns are captured

- **WHEN** a record contains `request.body` and/or `response.body` and the mapping declares the
  corresponding columns
- **THEN** the inserted row contains those fields' values

#### Scenario: assembled_response is not persisted

- **WHEN** a record contains `assembled_response`
- **THEN** the inserted row contains no `assembled_response` column, though its value MAY feed derived
  columns such as `response_id`

#### Scenario: Only declared columns are written

- **WHEN** a record contains fields that are not declared as columns in the mapping
- **THEN** the inserted row contains only the declared columns and none of the undeclared fields

### Requirement: Target table is operator-provisioned

The service SHALL assume the target ClickHouse table (`dial_usage_log`) already exists and MUST NOT
execute DDL. The table definition SHALL be provided as a documentation artifact for operators to
apply. The documented definition SHALL declare the `events` column set: a synthetic `event_id UUID`
and an `event_kind` classifier; the direct columns carried from the source (timestamps, ids,
`user_hash`, tokens, prices, request/response bodies, etc.); the calculated columns (`response_id`,
`cache_creation_tokens`, `reasoning_tokens`, `number_request_messages`, `success`, `mcp_method`,
`mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`); and the blocked columns
(`operation_duration_ms`, `jwt_claims`, `request_tags`) which exist in the table but are not yet
populated. The two JSON columns use ClickHouse's `JSON` type, whose use on the pinned server version
SHALL be enabled operationally (e.g. `allow_experimental_json_type`). For record-level deduplication
to function, the operator-provisioned table SHALL use the `ReplacingMergeTree` engine versioned by
`_ingested_at`, with a sort key (`ORDER BY`) ending in the deduplication key columns `trace_id` and
`core_span_id`, partitioned by `toYYYYMMDD(request_time)`. The documented definition SHALL also pin UTC
on its timestamp columns, store monetary columns as `Decimal`, store `response_status` as `UInt16`,
declare the producer-optional columns as `Nullable`, and apply a row-level retention `TTL`.

#### Scenario: Service does not create the table

- **WHEN** the sync job runs
- **THEN** it inserts into the pre-existing target table and does not issue any `CREATE TABLE` or
  other DDL statement

#### Scenario: Documented table definition supports record-level dedup

- **WHEN** an operator provisions the table from the documented definition
- **THEN** the table is a `ReplacingMergeTree` whose sort key ends in `trace_id` and `core_span_id`, so
  re-ingested rows for the same span collapse on merge

#### Scenario: Blocked columns exist but are unpopulated

- **WHEN** the table is provisioned and ingestion runs
- **THEN** the `operation_duration_ms`, `jwt_claims`, and `request_tags` columns exist in the table and
  are left at their column defaults, because no mapping entry populates them

### Requirement: Fail-fast startup validation against the table schema

On startup, before any sync run can execute, the service SHALL introspect the target table's actual
columns and types (via `DESCRIBE TABLE`) and reconcile them with the configured mapping. Every
configured column MUST exist in the table, and each configured extraction kind MUST be compatible with
the table column's declared type, normalizing `Nullable(...)`, `LowCardinality(...)`, `Decimal`,
unsigned-integer, `UUID`, `Bool`, and timezone-qualified `DateTime` spellings before comparison. A
column declared `nullable` in the mapping MUST be backed by a `Nullable(...)` table column, and a
column not declared nullable MUST NOT be. If reconciliation fails, the service SHALL refuse to start
and SHALL report the offending column(s). The service MUST continue to issue no DDL; the table remains
operator-provisioned. Table columns that the mapping does not populate — including columns with a
table-side default (e.g. `_ingested_at`) and the not-yet-populated blocked columns
(`operation_duration_ms`, `jwt_claims`, `request_tags`) — MAY be absent from the mapping.

#### Scenario: Matching mapping starts cleanly

- **WHEN** every configured column exists in the target table with a compatible type and matching
  nullability
- **THEN** the service starts and the scheduled sync job is allowed to run

#### Scenario: Mismatched mapping prevents startup

- **WHEN** a configured column is missing from the target table, its kind is incompatible with the
  table column's type, or its declared nullability disagrees with the table column
- **THEN** the service fails to start and reports the offending column(s)

#### Scenario: Unmapped blocked column is permitted

- **WHEN** the table declares the blocked columns (`operation_duration_ms`, `jwt_claims`,
  `request_tags`) and the mapping does not declare them
- **THEN** reconciliation succeeds, because the mapping need not populate every table column

### Requirement: Configurable column mapping

The service SHALL load the target table's column structure from an external mapping file whose path is
configured via the `sync.mappingFile` environment-driven property. The mapping SHALL declare an
ordered list of columns. A **mapped** column entry SHALL specify a ClickHouse column name, a `source`
(a dotted JSON path or the `$sourceFile` synthetic token), and an extraction kind, and MAY specify a
named transform and a default value. A **derived** column entry SHALL instead specify a `derive` name
and an ordered non-empty `sources` list (and MAY specify an `args` map and a default), and MUST NOT
specify a `source`. The service MUST NOT hard-code column names, source paths, or types in code —
derived columns keep their column name and source paths in the mapping, with only the reusable
derivation logic in code (see "Closed named deriver registry"). The `$sourceFile` token SHALL resolve
to the originating blob name; any other `$`-prefixed source token MUST be rejected as a configuration
error at load time. The shipped default mapping SHALL produce the `events` column set.

#### Scenario: Columns come from the mapping file

- **WHEN** the service starts with a mapping file declaring a set of columns
- **THEN** the inserted rows contain exactly those columns, sourced (or derived) and typed as the
  mapping declares

#### Scenario: Derived column entry is loaded

- **WHEN** a column entry declares a `derive` name with a `sources` list
- **THEN** the service loads it as a derived column populated at ingest by the named deriver

#### Scenario: Source file token is injected

- **WHEN** a column declares `$sourceFile` as its source
- **THEN** that column is populated with the originating blob name for every row

#### Scenario: Unknown synthetic token is rejected

- **WHEN** the mapping declares a `$`-prefixed source other than `$sourceFile`
- **THEN** the service rejects the mapping at load time as a configuration error
