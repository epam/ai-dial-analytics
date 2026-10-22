# flight-sql-endpoint — delta for sql-object-support

## MODIFIED Requirements

### Requirement: Arrow result schema and type mapping
Result columns SHALL be mapped to Arrow types by a single normative mapping: string→Utf8,
integer→Int32, long→Int64, decimal→Decimal128(38,9), boolean→Bool, date→DateDay,
timestamp→Timestamp(MILLI, UTC), uuid→Utf8. An `array` result column SHALL map to
Arrow `List` with a single child field named `item` whose type is the element type's scalar
mapping from the same table (e.g. an array of `string` → `List<item: Utf8>`); array cells
SHALL be written as list values whose elements use the same value coercion as the corresponding
scalar kind, an empty array SHALL be encoded as an empty (non-null) list, and a NULL cell as a
null list. An `object` result column SHALL map to Arrow `Utf8` carrying the value's JSON
serialization as text (Arrow has no schemaless-JSON type): each cell SHALL carry exactly the JSON
the REST surfaces produce for that same cell, serialized with the same mapper, so the two surfaces
never disagree about a value — an object cell therefore appears as a JSON object literal, never as
a Java-style `toString` rendering — and a NULL cell SHALL be an Arrow null. All fields SHALL be nullable. The schema returned
by `getFlightInfo` SHALL be identical to the schema of the `doGet` stream for the same
statement. Result rows SHALL be delivered in batches bounded by `flight.batch-rows` and
`flight.batch-max-bytes`. NULL cells (including unmatched enrichment LEFT JOIN columns) SHALL
be preserved as Arrow nulls.

#### Scenario: Schema agreement
- **WHEN** getFlightInfo and doGet are called for the same statement
- **THEN** the doGet stream's schema equals the FlightInfo schema field-for-field

#### Scenario: Null preservation
- **WHEN** a selected enrichment column has no matching enrichment row
- **THEN** the corresponding Arrow cell is null, not absent and not a default value

#### Scenario: Array column streams as a List vector
- **WHEN** a client runs `SELECT execution_path FROM dial_usage_log LIMIT 5` via Flight SQL
- **THEN** getFlightInfo advertises `execution_path` as `List<item: Utf8>`, and the doGet stream delivers each row's value as an Arrow list of Utf8 elements matching the JSON endpoint's values for the same query

#### Scenario: Empty array is an empty list, not null
- **WHEN** a streamed row's array cell is an empty ClickHouse array
- **THEN** the Arrow cell is a non-null list of length zero

#### Scenario: Object column streams as JSON text
- **WHEN** a client runs `SELECT jwt_claims FROM dial_usage_log LIMIT 5` via Flight SQL and a row's value is a nested object
- **THEN** getFlightInfo advertises `jwt_claims` as `Utf8`, and the doGet stream delivers that cell as a JSON document string that parses back to the same value the JSON endpoint returns for the same query

#### Scenario: Empty object is an empty JSON document
- **WHEN** a streamed row's object cell holds an empty object
- **THEN** the Arrow cell is the non-null string `{}`
