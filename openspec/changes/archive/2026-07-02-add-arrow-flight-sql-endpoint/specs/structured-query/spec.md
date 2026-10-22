# structured-query (delta)

Behavior fix surfaced by the Flight SQL integration tests, applying to the shared execution
pipeline (REST and Flight alike).

## ADDED Requirements

### Requirement: Global aggregate returns a single row
An aggregate-mode query with no `group_by` keys (a global aggregate, e.g. a bare `count`) SHALL
be rendered without any `GROUP BY` clause, so that per standard SQL semantics it returns exactly
one row even when the filter matches no rows. (Previously an empty group-key list rendered
jOOQ's "group by nothing" form `GROUP BY (SELECT 1)`, which returns zero rows over an empty
input.)

#### Scenario: Count over an empty match
- **WHEN** an aggregate query selects `count(*)` with no group_by and a filter that matches no rows
- **THEN** the result contains exactly one row with the count value `0`

#### Scenario: Count over matching rows
- **WHEN** an aggregate query selects `count(*)` with no group_by and a filter matching N rows
- **THEN** the result contains exactly one row with the count value N
