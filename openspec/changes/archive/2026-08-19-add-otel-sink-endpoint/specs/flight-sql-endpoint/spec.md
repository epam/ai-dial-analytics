## ADDED Requirements

### Requirement: Map result columns travel as Utf8 JSON text

A `map` result column SHALL map to Arrow `Utf8` carrying the value's JSON serialization as text, exactly
as an `object` result column does and serialized with the same mapper — so a map cell appears over Flight
SQL as the same JSON object literal the REST surfaces produce for that cell, and a NULL cell as a null
`Utf8`.

In `CommandGetXdbcTypeInfo` and the XDBC type metadata, a `map` column SHALL surface as `VARCHAR`; it
SHALL NOT introduce a new XDBC type code.

#### Scenario: Map column streams as JSON text

- **WHEN** a Flight SQL client executes a statement projecting a `map` column
- **THEN** the Arrow schema types the column as `Utf8` and each cell carries the same JSON object text
  the REST surface returns for that cell

#### Scenario: Map column reports as VARCHAR in XDBC metadata

- **WHEN** a client reads the XDBC column metadata for a table carrying a `map` column
- **THEN** that column reports the `VARCHAR` type code
