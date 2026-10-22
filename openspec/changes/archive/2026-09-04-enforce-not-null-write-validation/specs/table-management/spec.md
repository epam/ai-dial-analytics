## MODIFIED Requirements

### Requirement: Write rows into a materialized table

The system SHALL accept row inserts at `POST /v1/tables/{name}/rows` from a body carrying a non-empty
`rows` array, each row a JSON object keyed by physical column name (the `source_name`s, and for an
enrichment its grain key). A value for an `array`-typed column SHALL be supplied as a JSON array of
its element type. The system SHALL insert the rows into the table's ClickHouse table using
the writer credential and return the inserted count. In `oidc` mode this endpoint SHALL be authorized
by `FULL_ADMIN` or a matching `write` access role on the table (see the `table-access-control`
capability). System-owned columns (the `_`-prefixed columns, including the enrichment version column
`_updated_at`) SHALL NOT be caller-settable: a row that names one SHALL be rejected with HTTP 422, and
these columns SHALL be omitted from the insert so ClickHouse stamps their defaults. It SHALL reject a
write to an unknown table (404), a write to a table that is not materialized (409),
and a row that is empty or references a column that is not a physical column of the table (422). This
is the minimal data-write surface for populating sources and enrichments; richer ingest/upsert
semantics are out of scope for this phase.

A value for a **`timestamp`-typed column** SHALL be an ISO-8601 date-time string carrying an
**explicit zone**, in exactly one of two forms:

- a `Z` zone designator — `2026-08-11T10:00:00Z`
- an explicit UTC offset, resolved to the instant it denotes — `2026-08-11T13:00:00+03:00`

The date and time SHALL be separated by `T`. The seconds field is optional (`2026-08-11T10:00Z`).
Fractional seconds are optional and accepted at any precision up to nanoseconds (nine digits),
truncated toward the past to the column's stored millisecond precision. This is the format the query path already returns for a
`timestamp` column, so a value read from the system SHALL be writable back to it unchanged.

Every accepted value SHALL carry an explicit zone: the system SHALL NOT infer a zone for a zone-less
value, so no input can be silently stored as an instant other than the one the caller meant.

For the same reason the date SHALL name a day that exists on the ISO calendar: a value whose
day-of-month is out of range for its month (`2026-02-29` in a non-leap year, `2026-04-31`) SHALL be
rejected and SHALL NOT be adjusted to the month's last day.

A value for a `timestamp`-typed column that is not of the above form SHALL be rejected with HTTP 422
and error code `validation_error`, and no row of the batch SHALL be inserted. The message SHALL name
the offending row's 1-based index within the batch, the column, and the rejected value, and SHALL
identify the specific defect with its correction rather than reporting only that the value is invalid.
It SHALL distinguish at least these cases:

- a value with no zone (`2026-08-11T10:00:00`) — reported as a missing zone, directing the caller to
  `Z` or an explicit offset
- a value using a space instead of `T` (`2026-08-11 10:00:00.000`) — reported as the wrong separator
- a date with no time (`2026-08-11`) — reported as a date rather than a timestamp
- a day that does not exist on the calendar (`2026-02-29` in a non-leap year) — reported as such,
  naming the offending date, rather than as an unrecognized shape
- a non-string JSON value, including a numeric epoch (`1786514400000`) — reported as requiring a
  string, the units of a bare number being ambiguous
- any other unparseable value — reported as not an ISO-8601 timestamp, with an example of the form

A `null` value for a nullable `timestamp` column SHALL remain accepted and SHALL be stored as null.

Values of every other declared type SHALL be passed through unchanged and SHALL NOT be inspected for
timestamp shape. In particular a `date`-typed value needs no normalization (`YYYY-MM-DD` is both its
ISO-8601 and its stored form), a `decimal`-typed value SHALL retain a caller-supplied string spelling
so its digits are not degraded through a JSON float, and a `string`-typed column SHALL accept a
timestamp-shaped value untouched.

The column-set validation SHALL be applied before timestamp validation, so a row naming an unknown or
system column SHALL still be rejected for that reason regardless of its values.

An enrichment's **grain key** is a physical column a row may key but is not part of the enrichment's own
declared columns; its declared type SHALL be taken from the **source** table, where the same column name
is declared. A grain key declared `timestamp` on the source is therefore validated as a timestamp on a
write to the enrichment, exactly as any other `timestamp` column is.

Because a timestamp is validated against the column's declared type, the accepted format is a property
of the write path itself and SHALL apply identically to every endpoint that writes rows through it,
including the rule-scoped write-back (see the `enrichment-runtime` capability).

**Every physical column declared non-nullable (`nullable: false`, and an enrichment's grain key, which
is always non-nullable) SHALL be required on every row of a write**: a row that omits such a column,
or that names it with a JSON `null` value, SHALL be rejected with HTTP 422 and error code
`validation_error` before any row of the batch is inserted. The message SHALL name the offending row's
1-based index within the batch and the column. This check applies identically to every endpoint that
writes rows through this path, including the rule-scoped write-back (see the `enrichment-runtime`
capability), and runs independently of timestamp validation — a row can be rejected for a missing
non-nullable column even when it carries no `timestamp`-typed column at all.

#### Scenario: Rows are inserted and become queryable

- **WHEN** `POST /v1/tables/{name}/rows` is called for a materialized table with rows keyed by physical
  column name
- **THEN** the rows are inserted via the writer credential, the response reports the inserted count,
  and a subsequent query returns them (enrichment rows resolve into their source via the grain join)

#### Scenario: Array values round-trip through write and query

- **WHEN** rows carrying a JSON array value for an `array`-typed column are written to a materialized
  table and a structured row query later projects that column
- **THEN** the write succeeds and the query returns the value as a JSON array with the elements
  preserved in order

#### Scenario: A timestamp read from the query path can be written back unchanged

- **WHEN** a row is written, read back through the query path (which returns a `timestamp` as ISO-8601
  with a `Z` designator), and that returned value is submitted verbatim in a new write
- **THEN** the write is accepted and stores the same instant

#### Scenario: An ISO-8601 timestamp with an explicit offset is stored as the instant it denotes

- **WHEN** a row supplies `2026-08-11T13:00:00+03:00` for a `timestamp`-typed column
- **THEN** the write is accepted and a subsequent query returns `2026-08-11T10:00:00Z` — the same
  instant, expressed in the column's UTC zone

#### Scenario: Optional seconds and sub-millisecond precision are accepted

- **WHEN** rows supply `2026-08-11T10:00Z` and `2026-08-11T10:00:00.123456Z` for a `timestamp`-typed
  column
- **THEN** both writes are accepted, the first storing zero seconds and the second truncated toward the
  past to millisecond precision

#### Scenario: A timestamp without a zone is rejected as ambiguous

- **WHEN** a row supplies `2026-08-11T10:00:00` for a `timestamp`-typed column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, and the message
  reports the missing zone and directs the caller to `Z` or an explicit offset rather than assuming one

#### Scenario: The space-separated form is rejected with a message naming the separator

- **WHEN** a row supplies `2026-08-11 10:00:00.000` for a `timestamp`-typed column
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, and the message
  reports the wrong separator and gives the `T`-separated equivalent

#### Scenario: A day that does not exist on the calendar is rejected, not shifted

- **WHEN** a row supplies `2026-02-29T10:00:00Z` for a `timestamp`-typed column (2026 is not a leap year)
- **THEN** the request is rejected with HTTP 422 and error code `validation_error` naming the offending
  date, nothing is inserted, and no row is stored as `2026-02-28T10:00:00Z`
- **AND WHEN** a row supplies `2024-02-29T10:00:00Z`, a day that does exist
- **THEN** the write is accepted and stores that instant

#### Scenario: A date-only value and a numeric epoch are rejected

- **WHEN** one row supplies `2026-08-11` and another supplies the number `1786514400000` for a
  `timestamp`-typed column
- **THEN** each request is rejected with HTTP 422 and error code `validation_error`, the first reported
  as a date rather than a timestamp and the second as requiring a string value

#### Scenario: An invalid timestamp anywhere in a batch inserts nothing

- **WHEN** a batch of rows is written in which one row carries an invalid value for a
  `timestamp`-typed column
- **THEN** the request is rejected with HTTP 422, the message names that row's 1-based index within the
  batch, and no row of the batch is inserted

#### Scenario: An enrichment's timestamp grain key is validated from the source's declared type

- **WHEN** rows are written to an enrichment whose grain key is a column declared `timestamp` on its source
- **THEN** an ISO-8601 value with an explicit zone for that grain key is accepted, and a space-separated
  value for it is rejected with HTTP 422 `validation_error` naming the grain-key column

#### Scenario: A decimal value keeps its string spelling

- **WHEN** a row supplies a `decimal`-typed column as a string such as `"94.25"`
- **THEN** the value is passed to the store as supplied, without being coerced through a JSON float

#### Scenario: A timestamp-shaped value in a string column is not inspected

- **WHEN** a row supplies an ISO-8601 or space-separated timestamp-shaped value for a `string`-typed
  column
- **THEN** the value is stored verbatim as a string and is neither normalized nor rejected

#### Scenario: An unknown column is rejected for that reason regardless of its values

- **WHEN** a row names a column that is not a physical column of the table and also carries an invalid
  value for a `timestamp`-typed column
- **THEN** the request is rejected with HTTP 422 for the unknown column

#### Scenario: Write to a non-materialized table is rejected

- **WHEN** `POST /v1/tables/{name}/rows` is called for a table that is not `ACTIVE` (still `PENDING`,
  or `FAILED`)
- **THEN** the request is rejected with HTTP 409

#### Scenario: Write with an unknown column is rejected

- **WHEN** a row references a column that is not a physical column of the table
- **THEN** the request is rejected with HTTP 422

#### Scenario: Write attempting to set a system column is rejected

- **WHEN** a row attempts to set a `_`-prefixed system column such as `_updated_at`
- **THEN** the request is rejected with HTTP 422 and ClickHouse stamps the column default on the (otherwise valid) inserts

#### Scenario: A write access role authorizes a non-admin write

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `write` access role on the table `POST`s rows
- **THEN** the write is authorized and processed

#### Scenario: A row omitting a non-nullable column is rejected

- **WHEN** a row is written to a table that declares one of its physical columns `nullable: false`, and
  that row does not name the column at all
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, the message names
  the offending row's 1-based index and the column, and no row of the batch is inserted

#### Scenario: A row explicitly nulling a non-nullable column is rejected

- **WHEN** a row is written to a table that declares one of its physical columns `nullable: false`, and
  that row names the column with a JSON `null` value
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, the message names
  the offending row's 1-based index and the column, and no row of the batch is inserted

#### Scenario: A row omitting or nulling an enrichment's grain key is rejected

- **WHEN** a row is written to an enrichment table and either omits the grain key column or names it
  with a JSON `null` value
- **THEN** the request is rejected with HTTP 422 and error code `validation_error` naming the grain-key
  column, since the grain key is always non-nullable regardless of the source column's own
  nullability

#### Scenario: A nullable column may still be omitted or explicitly nulled

- **WHEN** a row is written to a table and omits, or explicitly nulls, a physical column declared
  `nullable: true`
- **THEN** the write is accepted and the column is stored as null (omitted) or explicitly null, exactly
  as before this change
