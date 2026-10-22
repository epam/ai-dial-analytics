# structured-query — delta for sql-object-support

## MODIFIED Requirements

### Requirement: Response envelope

The system SHALL return query results as a response object carrying a `rows` array and a nullable
`totalCount`. Each row SHALL be a field-name → value map produced from the ClickHouse client's
records. Every projected column SHALL be present in every row, carrying a JSON `null` when its value
is null (notably an unmatched LEFT-JOINed enrichment column) — a null cell SHALL NOT be dropped from
the row object, so the row's key set matches the requested projection. Values SHALL be serialized in
their natural JSON form, with the ClickHouse client's internal array wrapper unwrapped **at every
depth**: an `array` cell SHALL serialize as a JSON array, and an `object` cell SHALL serialize as a
JSON object whose nested arrays are themselves JSON arrays — never the wrapper's own field shape
(`{"array": [...], "arrayOfObjects": [...]}`). Within an `object` cell the member layout is
whatever the ClickHouse `JSON` column returns, passed through unchanged rather than reshaped:
nested objects surface as **dotted path keys** (a stored `{"ctx": {"tags": [...]}}` reads back as
the member `"ctx.tags"`), and a member whose stored value is null is **not materialized by the
column at all**, so it is absent from the row object rather than present as `null`. A null element
*inside* an array (a stored `["a", null]`) SHALL serialize as JSON `null` in place, and no nested
null SHALL fail the request. The `totalCount` SHALL be
populated only for row-mode queries that opt in via the offset page's `include_total`, computed as a
separate count of the same filter; otherwise it SHALL be null.

#### Scenario: Total count returned when requested

- **WHEN** a row-mode query sets the offset page's `include_total` to true
- **THEN** the response `totalCount` is the count of all rows matching the filter, independent of the
  page's offset and limit

#### Scenario: Total count omitted by default

- **WHEN** a query does not request the total (or is aggregate-mode)
- **THEN** the response `totalCount` is null and only `rows` is populated

#### Scenario: Null projected cell is preserved in the row

- **WHEN** a row-mode query projects an enrichment column whose row has no matching enrichment record
  (so the LEFT-JOINed value is null)
- **THEN** the result row still contains that column's key with a JSON `null` value, rather than
  omitting the key

#### Scenario: Arrays nested inside an object cell serialize as JSON arrays

- **WHEN** a row-mode query projects an `object` column whose stored value contains an array member, directly or inside a nested object (e.g. `{"roles": ["admin"], "ctx": {"tags": ["a"]}}`)
- **THEN** the response renders those members as JSON arrays, not as the ClickHouse client's array-wrapper object

#### Scenario: Null array elements inside an object cell are preserved

- **WHEN** a row-mode query projects an `object` column whose stored value contains a null array element (e.g. `{"roles": ["a", null]}`)
- **THEN** the request succeeds and the element renders as JSON `null` in place

#### Scenario: Null object members are absent, not null

- **WHEN** a row-mode query projects an `object` column whose stored value had a null member (e.g. `{"sub": null}`)
- **THEN** the request succeeds and the `sub` key is absent from the object — the `JSON` column never materialized the path — rather than present with a `null` value

#### Scenario: Nested object members keep their dotted path keys

- **WHEN** a row-mode query projects an `object` column whose stored value nests an object (e.g. `{"ctx": {"tags": ["a"]}}`)
- **THEN** the member appears under the flattened key `"ctx.tags"` exactly as the `JSON` column returns it
