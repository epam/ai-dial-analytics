## ADDED Requirements

### Requirement: Catalog metadata statements execute over REST

`POST /v1/queries/execute-sql` SHALL accept a statement against `INFORMATION_SCHEMA` and return its
rows in the standard response envelope, so the SQL surface is the same wherever ADAS accepts SQL and a
client is not forced onto Flight to read the catalog.

The response SHALL use the same shape as a data query — a `rows` array of column-name → value objects,
with `totalCount` omitted. Values SHALL be JSON strings for the character columns and JSON numbers for
`ORDINAL_POSITION` and the other integer columns, with `null` where the catalog has no value.

A metadata statement SHALL NOT be subject to the default page limit or the maximum-limit ceiling,
which govern ClickHouse execution; its result size is bounded by the catalog.

#### Scenario: Column listing over REST

- **WHEN** a client posts `SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'dial_usage_log'`
- **THEN** the response is `200` with one `rows` entry per field of that entity and no `totalCount` key

#### Scenario: Metadata statement returns more rows than the default limit

- **WHEN** a client posts an unbounded `SELECT` against `INFORMATION_SCHEMA.COLUMNS` whose result
  exceeds the default page limit
- **THEN** every row is returned rather than the result being limited or the request rejected
