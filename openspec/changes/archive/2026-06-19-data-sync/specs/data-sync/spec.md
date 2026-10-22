## ADDED Requirements

### Requirement: Scheduled daily ingestion

The service SHALL run a data-sync job automatically on a daily schedule driven by a configurable
cron expression. The job MUST run in-process and MUST NOT be triggerable via an HTTP endpoint in
this phase. The scheduler MUST be configured such that a new run cannot start while a previous run
is still in progress.

#### Scenario: Job runs on schedule

- **WHEN** the configured cron time is reached and no sync is currently running
- **THEN** the service starts a sync run without any external trigger

#### Scenario: Overlapping runs are prevented

- **WHEN** the cron time is reached while a previous sync run is still in progress
- **THEN** the service does not start a second concurrent run

### Requirement: Date-derived prefix file discovery

The service SHALL determine which files to ingest by resolving a date-derived blob prefix of the
form `logs/{yyyy}/{MM}/{dd}/` and listing all blobs under that prefix. The target date SHALL be the
current date minus a configurable day offset (default 1, i.e. the previous day). The service MUST
NOT require an exact blob key, since blob names contain an unpredictable suffix.

#### Scenario: Prefix is resolved from the day offset

- **WHEN** a sync run starts with day offset 1 on 2026-06-19
- **THEN** the service lists blobs under the prefix `logs/2026/06/18/`

#### Scenario: All files under the prefix are processed

- **WHEN** the resolved prefix contains multiple blobs
- **THEN** the service processes every blob found under that prefix

#### Scenario: Empty prefix completes cleanly

- **WHEN** the resolved prefix contains no blobs
- **THEN** the run completes successfully having ingested zero rows and reports zero files processed

### Requirement: Source-file deduplication

The service SHALL skip any blob whose name already appears in the target table's `_source_file`
column, so that repeated daily runs and post-restart re-runs do not insert duplicate rows. Each
ingested row SHALL record the originating blob name in `_source_file`.

#### Scenario: Already-ingested file is skipped

- **WHEN** a blob's name is already present in the table's `_source_file` column
- **THEN** the service skips that blob without re-inserting its rows and counts it as skipped

#### Scenario: New file is ingested with provenance

- **WHEN** a blob's name is not present in `_source_file`
- **THEN** the service ingests its rows, each recording that blob name in `_source_file`

### Requirement: Log parsing and transformation

The service SHALL read each blob as a gzip-compressed, newline-delimited JSON (NDJSON) stream and
transform each record into a ClickHouse row. The transform SHALL flatten the nested log fields into
the target table columns and SHALL keep `response.status` as a string value without numeric
conversion.

#### Scenario: NDJSON record is mapped to a row

- **WHEN** a blob contains a valid NDJSON usage-log record
- **THEN** the service maps its fields to the corresponding `dial_usage_log` columns and keeps
  `response_status` as a string

### Requirement: Sensitive payload fields are dropped

The service SHALL NOT write the heavy payload fields `assembled_response`, `request.body`, and
`response.body` to ClickHouse. These fields MUST be dropped during transformation so that no
prompt/response content is persisted in the analytics store.

#### Scenario: Heavy fields are excluded from the inserted row

- **WHEN** a record contains `assembled_response`, `request.body`, and/or `response.body`
- **THEN** the inserted row contains none of those fields' contents

### Requirement: Malformed lines are skipped

The service SHALL skip any NDJSON line that cannot be parsed, log a warning for it, and count it,
without failing the rest of the run. A malformed line MUST NOT abort ingestion of the remaining
lines in the file or the remaining files in the run.

#### Scenario: A bad line does not abort the run

- **WHEN** a blob contains a line that cannot be parsed as a valid record
- **THEN** the service logs a warning, increments the skipped-rows count, and continues processing
  the remaining lines and files

### Requirement: Batched inserts via a dedicated writer credential

The service SHALL insert rows into ClickHouse in batches of a configurable size, using a ClickHouse
credential that is separate from the read-only credential used by the query/discovery path. The
read path's ClickHouse access MUST remain read-only.

#### Scenario: Rows are inserted in batches

- **WHEN** a file yields more rows than the configured batch size
- **THEN** the service inserts the rows in multiple batches of at most the configured size

#### Scenario: Writer credential is isolated from the read path

- **WHEN** the sync job writes to ClickHouse
- **THEN** it uses the dedicated writer credential, and the read/discovery path continues to use its
  own read-only credential

### Requirement: Ingest observability

The service SHALL expose ingest observability through logs and Micrometer metrics, including counts
of files processed, files skipped, rows inserted, rows skipped, run outcome, run duration, and the
timestamp of the last successful run. No HTTP status endpoint is provided in this phase.

#### Scenario: Metrics reflect a completed run

- **WHEN** a sync run completes
- **THEN** the corresponding metrics (files processed/skipped, rows inserted/skipped, duration, and
  last-success timestamp) are updated to reflect that run

### Requirement: Target table is operator-provisioned

The service SHALL assume the target ClickHouse table (`dial_usage_log`) already exists and MUST NOT
execute DDL. The table definition SHALL be provided as a documentation artifact for operators to
apply.

#### Scenario: Service does not create the table

- **WHEN** the sync job runs
- **THEN** it inserts into the pre-existing target table and does not issue any `CREATE TABLE` or
  other DDL statement
