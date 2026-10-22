## MODIFIED Requirements

### Requirement: Log parsing and transformation

The service SHALL read each blob as a gzip-compressed, newline-delimited JSON (NDJSON) stream and
transform each record into a ClickHouse row according to an external column mapping (see "Configurable
column mapping"). For each declared column the service SHALL resolve the configured source — a dotted
JSON path within the record, or the `$sourceFile` synthetic token — coerce the resolved value to the
column's declared extraction kind, apply the configured transform (if any), and resolve absent or
blank source values as follows: if the column declares a default, the service SHALL use that default;
otherwise, if the column is declared `nullable`, the service SHALL emit a JSON `null` for that column;
otherwise the service SHALL fall back to the kind's zero value (so a non-nullable column without a
producer value still inserts safely). Columns whose source is a JSON array SHALL be mapped using the
`string-array` transform with the `array<string>` kind. The transform that converts an ISO-like
timestamp to a ClickHouse `DateTime64` literal (replacing `T` with a space) SHALL be available as a
named transform. Monetary columns (`total_price`, `deployment_price`) SHALL be coerced so the
producer's decimal value is preserved exactly (passed through without lossy binary floating-point
conversion). `response.status` SHALL be mapped with the numeric (long) kind so it is stored as an
integer.

#### Scenario: NDJSON record is mapped via the configured mapping

- **WHEN** a blob contains a valid NDJSON usage-log record
- **THEN** the service produces a row whose keys are exactly the configured column names, each value
  resolved from its configured source path, coerced to its declared kind, and passed through its
  configured transform

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

### Requirement: Target table is operator-provisioned

The service SHALL assume the target ClickHouse table (`dial_usage_log`) already exists and MUST NOT
execute DDL. The table definition SHALL be provided as a documentation artifact for operators to
apply. For record-level deduplication to function, the operator-provisioned table SHALL use the
`ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key (`ORDER BY`) ending in the
deduplication key columns `trace_id` and `core_span_id`, partitioned by `toYYYYMMDD(request_time)`.
The documented definition SHALL also pin UTC on its timestamp columns, store monetary columns as
`Decimal`, store `response_status` as `UInt16`, declare the producer-optional columns as `Nullable`,
and apply a row-level retention `TTL`.

#### Scenario: Service does not create the table

- **WHEN** the sync job runs
- **THEN** it inserts into the pre-existing target table and does not issue any `CREATE TABLE` or
  other DDL statement

#### Scenario: Documented table definition supports record-level dedup

- **WHEN** an operator provisions the table from the documented definition
- **THEN** the table is a `ReplacingMergeTree` whose sort key ends in `trace_id` and `core_span_id`, so
  re-ingested rows for the same span collapse on merge

### Requirement: Fail-fast startup validation against the table schema

On startup, before any sync run can execute, the service SHALL introspect the target table's actual
columns and types (via `DESCRIBE TABLE`) and reconcile them with the configured mapping. Every
configured column MUST exist in the table, and each configured extraction kind MUST be compatible with
the table column's declared type, normalizing `Nullable(...)`, `LowCardinality(...)`, `Decimal`,
unsigned-integer, and timezone-qualified `DateTime` spellings before comparison. A column declared
`nullable` in the mapping MUST be backed by a `Nullable(...)` table column, and a column not declared
nullable MUST NOT be. If reconciliation fails, the service SHALL refuse to start and SHALL report the
offending column(s). The service MUST continue to issue no DDL; the table remains operator-provisioned.
Columns populated by a table-side default (e.g. `_ingested_at`) MAY be absent from the mapping.

#### Scenario: Matching mapping starts cleanly

- **WHEN** every configured column exists in the target table with a compatible type and matching
  nullability
- **THEN** the service starts and the scheduled sync job is allowed to run

#### Scenario: Mismatched mapping prevents startup

- **WHEN** a configured column is missing from the target table, its kind is incompatible with the
  table column's type, or its declared nullability disagrees with the table column
- **THEN** the service fails to start and reports the offending column(s)

### Requirement: Ingest observability

The service SHALL expose ingest observability through logs and Micrometer metrics, including counts
of files processed, rows inserted, rows skipped (unparseable lines), rows dropped (records lacking the
deduplication key), run outcome, run duration, and the timestamp of the last successful run. Because
deduplication is now performed by the table engine rather than by skipping whole files, the
files-skipped metric SHALL no longer denote already-ingested files. No HTTP status endpoint is
provided in this phase.

#### Scenario: Metrics reflect a completed run

- **WHEN** a sync run completes
- **THEN** the corresponding metrics (files processed, rows inserted/skipped/dropped, duration, and
  last-success timestamp) are updated to reflect that run

#### Scenario: Dropped records are counted

- **WHEN** a run encounters records lacking a deduplication key
- **THEN** the dropped-rows metric is incremented by the number of such records

## REMOVED Requirements

### Requirement: Source-file deduplication

**Reason**: File-level deduplication cannot prevent record-level duplicates (e.g. a record
re-delivered under a different blob name, or a partially-ingested file) and prevents the safety-net
run from re-reading files. It is replaced by primary-key deduplication performed by the table engine.

**Migration**: The `_source_file` column is retained as ingest provenance but is no longer the dedup
key. Deduplication now relies on the `ReplacingMergeTree` engine collapsing rows that share the
`(trace_id, core_span_id)` key (see "Primary-key deduplication"). Operators MUST migrate the table to
the new engine and sort key; the `_source_file` existence pre-check is removed.

## ADDED Requirements

### Requirement: Primary-key deduplication

The service SHALL rely on the target table's `ReplacingMergeTree` engine to deduplicate records by
their primary key, the combination of `trace_id` and `core_span_id` (one proxy request maps to one
span and one log record). The service SHALL NOT pre-check for already-ingested files or rows; every
run re-parses and re-inserts the discovered records, and the engine collapses duplicate spans on merge,
retaining the copy with the greatest `_ingested_at`. Each ingested row SHALL continue to record the
originating blob name in `_source_file` as provenance. Because engine deduplication is eventual,
documentation SHALL state that reads requiring exact, duplicate-free results use `FINAL` or
`GROUP BY`/`argMax`.

#### Scenario: Re-ingested span collapses to one row

- **WHEN** the same usage record (identical `trace_id` and `core_span_id`) is ingested more than once
- **THEN** after merge the table retains a single row for that span, keeping the latest-ingested copy

#### Scenario: No file or row is skipped as already-ingested

- **WHEN** a sync run processes a blob whose records were ingested by a previous run
- **THEN** the service re-parses and re-inserts the records without an existence pre-check, and the
  engine collapses the duplicates

#### Scenario: Provenance is still recorded

- **WHEN** a record is ingested
- **THEN** its row records the originating blob name in `_source_file`, even though that column is not
  the deduplication key

### Requirement: Records without a deduplication key are dropped

The service SHALL exclude from ingestion any record whose `trace_id` or `core_span_id` is absent or
blank, because such records would otherwise collapse together under the deduplication key and lose
data. Each excluded record SHALL be logged and counted in a dropped-rows metric, and its exclusion
MUST NOT abort ingestion of the remaining records or files.

#### Scenario: Record missing the dedup key is dropped

- **WHEN** a record has an absent or blank `trace_id` or `core_span_id`
- **THEN** the service does not insert that record, logs it, increments the dropped-rows metric, and
  continues processing the remaining records and files
