# structured-query (delta)

Pagination governance change in the shared execution pipeline (REST and Flight alike):
out-of-range page limits are rejected instead of silently clamped/defaulted.

## MODIFIED Requirements

### Requirement: Query validation and allowlist

The system SHALL validate a structured query before and during translation against the entity's
**discovered schema**, rejecting invalid queries with HTTP 400. Validation SHALL cover: entity
resolution (unknown entity rejected), field resolution (every referenced field must resolve against
the entity's flat schema), function resolution against the closed **Supported function catalog**
(including arity and, for `percentile_cont`/`percentile_disc`, a `fraction` decimal literal in
`[0, 1]`), `in` operand shape (an array of value literals), literal parsing per `value_type`, and
pagination governance (offset ≥ 0; cursor pagination rejected; limit within `[0, 1000]` — a limit
above the 1000-row maximum or a negative limit is rejected, never silently clamped; a limit of `0`
or an omitted page applies the default of 100). Semantically invalid queries that nonetheless
translate SHALL surface ClickHouse's rejection as HTTP 400 rather than 500. Per-field capability
flags, mode-coherence enforcement, and array element type-homogeneity are NOT enforced in this
implementation and remain available as a future tightening.

#### Scenario: Unknown field is rejected

- **WHEN** a query references a field name that does not resolve against the entity's discovered schema
- **THEN** the query is rejected with HTTP 400 before execution

#### Scenario: Unsupported function is rejected

- **WHEN** a query uses a function name outside the Supported function catalog
- **THEN** the query is rejected with HTTP 400

#### Scenario: Cursor pagination is rejected

- **WHEN** a query supplies a cursor-strategy page
- **THEN** the query is rejected with HTTP 400 (cursor pagination is not supported on this path)

#### Scenario: Limit above the maximum is rejected

- **WHEN** a query supplies an offset page with `limit` greater than 1000 (e.g. `50000`)
- **THEN** the query is rejected with HTTP 400 and a message naming the requested limit and the
  1000-row maximum; no rows are returned

#### Scenario: Negative limit is rejected

- **WHEN** a query supplies an offset page with a negative `limit`
- **THEN** the query is rejected with HTTP 400

#### Scenario: Invalid literal is rejected

- **WHEN** a value literal cannot be parsed to its declared `value_type` (e.g. a non-numeric `integer`)
- **THEN** the query is rejected with HTTP 400

#### Scenario: ClickHouse rejects the query

- **WHEN** a query is well-formed but ClickHouse rejects the rendered SQL (e.g. a type mismatch)
- **THEN** the request is rejected with HTTP 400 and no raw ClickHouse internals are exposed
