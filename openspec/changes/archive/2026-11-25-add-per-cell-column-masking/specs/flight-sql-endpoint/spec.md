## ADDED Requirements

### Requirement: An absent cell is an Arrow null and the stream carries no marks

A cell whose value is null because a masked field is absent on its row (see
[structured-query](../structured-query/spec.md), "A masked field reads as absent at every reference") SHALL be an Arrow
null in its vector, an `array`, `map` or `object` field included. A cell computed from an absent input SHALL carry its
computed value. The stream's schema SHALL be exactly the statement's output columns, with no `$masked` column and no
other flag column, and a masked column SHALL appear in catalog metadata and `INFORMATION_SCHEMA` as any nullable
column does, so a standard Flight SQL client reads the stream unchanged.

#### Scenario: An absent cell streams as an Arrow null

- **GIVEN** a caller for whom `total_price` is masked, present on the rows of project `P1` only
- **WHEN** it runs `SELECT project_id, total_price FROM dial_usage_log` over Flight SQL
- **THEN** the schema has exactly the two fields, and on every row of `P2` the `total_price` cell is null

#### Scenario: A masked array cell streams as an Arrow null

- **GIVEN** a caller for whom the `array` field `execution_path` is masked, present on the rows of `P1` only
- **WHEN** it selects `execution_path` over Flight SQL
- **THEN** on every row of `P2` the list cell is null, not an empty list
