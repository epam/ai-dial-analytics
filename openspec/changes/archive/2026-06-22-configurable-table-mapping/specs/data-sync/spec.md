## MODIFIED Requirements

### Requirement: Log parsing and transformation

The service SHALL read each blob as a gzip-compressed, newline-delimited JSON (NDJSON) stream and
transform each record into a ClickHouse row according to an external column mapping (see "Configurable
column mapping"). For each declared column the service SHALL resolve the configured source — a dotted
JSON path within the record, or the `$sourceFile` synthetic token — coerce the resolved value to the
column's declared extraction kind, apply the configured transform (if any), and apply the configured
default when the source value is absent or blank. Columns whose source is a JSON array SHALL be
mapped using the `string-array` transform with the `array<string>` kind. The transform that converts
an ISO-like timestamp to a ClickHouse `DateTime64` literal (replacing `T` with a space) SHALL be
available as a named transform, and `response.status` SHALL be mapped with the string kind so it is
kept verbatim without numeric conversion.

#### Scenario: NDJSON record is mapped via the configured mapping

- **WHEN** a blob contains a valid NDJSON usage-log record
- **THEN** the service produces a row whose keys are exactly the configured column names, each value
  resolved from its configured source path, coerced to its declared kind, and passed through its
  configured transform

#### Scenario: Response status is kept as a string

- **WHEN** a record's `response.status` is mapped with the string kind
- **THEN** the inserted value is the status as a string, with no numeric conversion

#### Scenario: Missing source falls back to the configured default

- **WHEN** a declared column's source value is absent or blank in the record and the column declares a
  default
- **THEN** the row uses the configured default for that column

### Requirement: Sensitive payload fields are dropped

The service SHALL persist only the columns declared in the configured mapping (allowlist semantics).
Because the heavy payload fields `assembled_response`, `request.body`, and `response.body` are not
declared as columns in the shipped mapping, they SHALL never be read from the record nor written to
ClickHouse, so no prompt/response content is persisted. A field that is not declared in the mapping
MUST NOT be written, regardless of its presence in the source record.

#### Scenario: Undeclared heavy fields are excluded from the inserted row

- **WHEN** a record contains `assembled_response`, `request.body`, and/or `response.body` and none of
  them are declared columns in the mapping
- **THEN** the inserted row contains none of those fields' contents

#### Scenario: Only declared columns are written

- **WHEN** a record contains fields that are not declared as columns in the mapping
- **THEN** the inserted row contains only the declared columns and none of the undeclared fields

## ADDED Requirements

### Requirement: Configurable column mapping

The service SHALL load the target table's column structure from an external mapping file whose path is
configured via the `sync.mappingFile` environment-driven property. The mapping SHALL declare an
ordered list of columns; each column entry SHALL specify a ClickHouse column name, a source (a dotted
JSON path or the `$sourceFile` synthetic token), and an extraction kind, and MAY specify a named
transform and a default value. The service MUST NOT hard-code column names, source paths, or types in
code. The `$sourceFile` token SHALL resolve to the originating blob name; any other `$`-prefixed
source token MUST be rejected as a configuration error at load time. The shipped default mapping SHALL
reproduce the existing `dial_usage_log` columns so that default behavior is unchanged.

#### Scenario: Columns come from the mapping file

- **WHEN** the service starts with a mapping file declaring a set of columns
- **THEN** the inserted rows contain exactly those columns, sourced and typed as the mapping declares

#### Scenario: Source file token is injected

- **WHEN** a column declares `$sourceFile` as its source
- **THEN** that column is populated with the originating blob name for every row

#### Scenario: Unknown synthetic token is rejected

- **WHEN** the mapping declares a `$`-prefixed source other than `$sourceFile`
- **THEN** the service rejects the mapping at load time as a configuration error

### Requirement: Closed named transform registry

The service SHALL support a fixed, named set of transforms referenced by columns in the mapping, and
SHALL NOT support arbitrary user-supplied expressions or code. The registry SHALL include at least
`iso-to-ch-datetime` (convert an ISO-like timestamp to a ClickHouse `DateTime64` literal) and
`string-array` (coerce a JSON array to a list of strings). A column referencing a transform name that
is not in the registry SHALL cause a fatal configuration error at load time.

#### Scenario: Known transform is applied

- **WHEN** a column declares the `iso-to-ch-datetime` transform and its source value is an ISO-like
  timestamp
- **THEN** the inserted value is the timestamp rendered as a ClickHouse `DateTime64` literal

#### Scenario: Unknown transform is rejected

- **WHEN** a column references a transform name that is not in the registry
- **THEN** the service fails to load the mapping with a configuration error

### Requirement: Fail-fast startup validation against the table schema

On startup, before any sync run can execute, the service SHALL introspect the target table's actual
columns and types (via `DESCRIBE TABLE`) and reconcile them with the configured mapping. Every
configured column MUST exist in the table, and each configured extraction kind MUST be compatible with
the table column's declared type. If reconciliation fails, the service SHALL refuse to start and
SHALL report the offending column(s). The service MUST continue to issue no DDL; the table remains
operator-provisioned. Columns populated by a table-side default (e.g. `_ingested_at`) MAY be absent
from the mapping.

#### Scenario: Matching mapping starts cleanly

- **WHEN** every configured column exists in the target table with a compatible type
- **THEN** the service starts and the scheduled sync job is allowed to run

#### Scenario: Mismatched mapping prevents startup

- **WHEN** a configured column is missing from the target table or its kind is incompatible with the
  table column's type
- **THEN** the service fails to start and reports the offending column(s)
