## ADDED Requirements

### Requirement: A nullable measure result requires a nullable target column

A measure whose result can be NULL SHALL require a nullable target column, or the declaration SHALL be
rejected with HTTP 422 naming both the measure's target column and the input column that makes the result
nullable.

A measure result can be NULL exactly when its **input column** is nullable: `sum`, `avg`, `min` and `max`
over a nullable column all return NULL for a group that contains no non-null value. `count` never returns
NULL and is therefore never subject to this rule. The determination SHALL come from the input column's
declared nullability rather than from the function, because the function alone cannot distinguish
`sum` over a non-nullable column (never NULL) from `sum` over a nullable one (NULL for an all-null group).

The rule exists because ClickHouse does not refuse a NULL written to a non-nullable column — it converts
it to the column's type default and stores **`0`**. The rollup then reports a zero that a consumer cannot
distinguish from a measured zero: "there was no data for this key" and "this key's total was zero" are
different facts, and only the second one survives. Nothing in the target records which it was, and nothing
fails, so the error is discoverable only by recomputing against the source.

This is the same failure mode, and the same remedy, as the fractional-measure rule in target shape
governance: an engine that coerces on insert rather than refusing turns a declaration mistake into a
plausible-looking number. The rules are siblings and SHALL both be enforced at registration and re-applied
at execution, so a column that becomes nullable after a pipeline is registered stops the refresh instead
of silently changing what the stored zeros mean.

#### Scenario: A nullable measure bound to a non-nullable target column is rejected

- **WHEN** a `sum`, `avg`, `min` or `max` measure reads a nullable input column and the target column it
  writes is declared non-nullable
- **THEN** the request is rejected with HTTP 422 naming the target column and the nullable input column,
  because ClickHouse would store the type default and the rollup would read as a measured zero

#### Scenario: A nullable measure bound to a nullable target column is accepted

- **WHEN** the same measure's target column is declared nullable
- **THEN** the declaration is accepted, and a group with no non-null value stores NULL rather than a
  default

#### Scenario: A count measure is exempt

- **WHEN** a `count` measure is bound to a non-nullable target column
- **THEN** the declaration is accepted, because `count` cannot return NULL

#### Scenario: A measure over a non-nullable input is exempt

- **WHEN** a `sum` measure reads a non-nullable input column and writes a non-nullable target column
- **THEN** the declaration is accepted, because the result cannot be NULL

#### Scenario: The rule is re-applied at execution

- **WHEN** a measure's input column becomes nullable after the pipeline was registered and a refresh runs
- **THEN** the refresh is refused and the failure recorded, rather than writing type defaults into a
  target column that cannot represent the absence of a value
