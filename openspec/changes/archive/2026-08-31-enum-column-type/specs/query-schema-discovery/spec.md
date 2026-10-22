## ADDED Requirements

### Requirement: Enum fields carry their declared domain

A flat field backed by a catalog column of type `enum` (see
[table-management](../table-management/spec.md), "Enum column type") SHALL be listed with
`type: "enum"` and SHALL carry that column's declared domain as `enum_values` — a JSON array of
strings in declared order — on `GET /v1/queries/entities/schema/{name}`. A field backed by a
column of any other type SHALL carry an absent or null `enum_values`.

Publishing the domain is the point of the type on this surface: it lets a consumer build a value
picker or validate a filter from discovery alone, rather than sampling the data with a
`SELECT DISTINCT`.

`enum_values` SHALL follow the same visibility rules as the rest of a field's metadata: a field
hidden from the caller (a `sensitive` column read by a non-`FULL_ADMIN` caller) does not appear at
all, and therefore neither does its domain.

#### Scenario: Enum field lists its domain

- **WHEN** a table has an `enum` column `status` declaring `enum_values: ["pending", "running"]`
  and the caller requests that entity's schema
- **THEN** the corresponding flat field carries `type: "enum"` and
  `enum_values: ["pending", "running"]` in that order

#### Scenario: Non-enum field carries no domain

- **WHEN** the entity's schema is requested for a `string` column
- **THEN** that field carries `type: "string"` and a null or absent `enum_values`

#### Scenario: A hidden sensitive enum column exposes no domain

- **WHEN** a non-`FULL_ADMIN` caller requests the schema of an entity holding a `sensitive` `enum`
  column
- **THEN** the field is absent from the response entirely, along with its domain
