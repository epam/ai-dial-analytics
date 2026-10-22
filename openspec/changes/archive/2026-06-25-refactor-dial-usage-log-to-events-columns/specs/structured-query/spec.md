## MODIFIED Requirements

### Requirement: Filter sub-grammar (CQL2-JSON)

The system SHALL model a recursive filter tree as a sealed `FilterNode` hierarchy discriminated by
the `op` key: logical nodes (`and`, `or`, `not`) carrying a list of child nodes, and predicate nodes
carrying an operator code (`eq`, `ne`, `co`, `nc`, `lt`, `gt`, `le`, `ge`, `in`) and a positional
`args` list of expressions. Because `op` is both the discriminator and operator data, routing SHALL
be performed by a custom deserializer wired at each use site, not by a declarative `@JsonTypeInfo` on
the interface.

#### Scenario: Nested boolean tree binds

- **WHEN** a filter `(event_kind = 'llm_call' AND total_tokens > 100) OR (...)` is deserialized
- **THEN** the root binds to a logical `OR` node whose children are logical `AND` nodes containing
  predicate nodes with their operator codes and operand expressions

#### Scenario: Both operands are general expressions

- **WHEN** a predicate `length(project_id) = 3` is deserialized
- **THEN** the predicate's `args` hold a function expression on the left and a value expression on the
  right, with no requirement that either operand be a bare column

#### Scenario: Logical-vs-predicate routing by op

- **WHEN** a node's `op` is `and`/`or`/`not`
- **THEN** it routes to the logical node type; otherwise it routes to the predicate node type

### Requirement: `in` predicate with array operand

The system SHALL treat `in` as an ordinary binary predicate whose right operand is typically an
`array` expression, without structurally encoding "left scalar / right set" — that constraint is
deferred to validation.

#### Scenario: Set membership binds

- **WHEN** `event_kind IN ('llm_call', 'embedding')` is deserialized
- **THEN** the predicate's `args` hold a field expression on the left and an array expression of two
  value literals on the right
