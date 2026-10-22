## ADDED Requirements

### Requirement: The merged assembled response is persisted

The service SHALL persist the producer's merged streaming response, `assembled_response`, to a column
of `dial_usage_log` when the configured mapping declares one, storing the producer's value verbatim
with no reassembly, parsing, or normalization of its own. The column SHALL be nullable, so a record
that carries no `assembled_response` stores `NULL` rather than an empty string and "the producer sent
nothing" stays distinguishable from "the producer sent an empty response".

The value SHALL be stored as reported **whatever its shape**. For a non-streamed call and for a
DIAL-normalized stream the producer emits a merged JSON message; for an upstream-native stream it
emits the SSE transcript verbatim. The service SHALL store both without discriminating between them,
because deciding which shape arrived requires parsing a payload the service otherwise never reads, and
a column that silently dropped the transcript shape would report "no response" for a request that had
one. A consumer that needs a message therefore SHALL parse the column defensively; that a stored value
does not parse as JSON is a property of the producer, not an ingest failure.

Persisting the merged response SHALL NOT change how it feeds derived columns: it remains available as
a deriver source, and a column derived from it is unaffected by whether it is also stored.

#### Scenario: The assembled response is stored when the mapping declares it

- **WHEN** a record carries `assembled_response` and the mapping declares the corresponding column
- **THEN** the inserted row stores the producer's value verbatim

#### Scenario: A record without an assembled response stores null

- **WHEN** a record carries no `assembled_response` and the mapping declares the column as nullable
- **THEN** the inserted row stores `NULL` for it, and the record is ingested normally

#### Scenario: An SSE transcript is stored unchanged

- **WHEN** a record's `assembled_response` is an SSE transcript rather than a JSON document
- **THEN** the inserted row stores that transcript verbatim, and ingest neither fails nor empties the
  column

#### Scenario: Deriving from the assembled response is unchanged

- **WHEN** a column derives its value from `assembled_response` and the mapping also declares
  `assembled_response` as a stored column
- **THEN** the derived column's value is exactly what it would be were the column not stored

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
body-derived token counts) and MAY additionally be persisted as a column of its own (see "The merged
assembled response is persisted"); the two uses are independent. Columns whose source is a JSON
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
The shipped default mapping declares the heavy payload columns `request_body`, `response_body`, and
`assembled_response`, so raw prompt content, raw response content, and the producer's merged response
all ARE persisted to ClickHouse. Whether heavy content is captured is therefore controlled entirely by
the mapping (removing those columns from the mapping stops capturing them). When captured, access to
those columns and their retention MUST be governed operationally — column-level access controls plus a
retention/TTL policy — because the service applies no masking or redaction of its own.

`assembled_response` is captured **in addition to** `response_body` rather than instead of it, and the
two overlap: for a streamed call the raw body is a chunk transcript while the merged response is the
same content assembled. Capturing both is a deliberate cost — one more unbounded-size column of
verbatim user-visible content per row — accepted because the merged response is the only form in which
a consumer can read an answer without reassembling a stream, and because dropping the raw body would
lose the transport-level detail an operator needs to diagnose a malformed stream. An installation that
does not want the merged response captured removes its column from the mapping, exactly as for the
other two.

#### Scenario: Declared heavy payload columns are captured

- **WHEN** a record contains `request.body`, `response.body`, and/or `assembled_response` and the
  mapping declares the corresponding columns
- **THEN** the inserted row contains those fields' values

#### Scenario: An undeclared payload column is not captured

- **WHEN** a record contains `assembled_response` and the mapping does not declare a column for it
- **THEN** the inserted row contains no `assembled_response` column, though its value MAY still feed
  derived columns such as `response_id`

#### Scenario: Only declared columns are written

- **WHEN** a record contains fields that are not declared as columns in the mapping
- **THEN** the inserted row contains only the declared columns and none of the undeclared fields
