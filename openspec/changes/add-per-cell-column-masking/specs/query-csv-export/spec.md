## ADDED Requirements

### Requirement: An absent cell is an empty field and the export carries no marks

A cell whose value is null because a masked field is absent on its row (see
[structured-query](../structured-query/spec.md), "A masked field reads as absent at every reference") SHALL be written
as a null cell is: an empty, unquoted field. A cell computed from an absent input SHALL be written as its computed value
is. The export SHALL carry no `$masked` column, no other flag column and no marker in the header or the trailer: the
columns and the header SHALL be exactly the query's output columns, as for a caller with no masked column.

#### Scenario: An absent cell is written as an empty field

- **GIVEN** a caller for whom `total_price` is masked, present on the rows of project `P1` only
- **WHEN** it exports `project_id, total_price` as CSV over the rows of `P1` and `P2`
- **THEN** the header is `project_id,total_price`, and on every row of `P2` the `total_price` field is empty and
  unquoted
