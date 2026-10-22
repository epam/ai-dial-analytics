## ADDED Requirements

### Requirement: Map fields are discoverable

Schema discovery SHALL report a `map`-typed catalog column as a field whose `type` is `map`, with no
`element_type`. The field SHALL appear in the entity's schema like any other column — subject to the same
sensitivity, tag ordering and heavy-column metadata — so a client can see that the field exists and that
it is a map before attempting to project it.

#### Scenario: Discovery reports a map field

- **WHEN** `GET /v1/queries/entities/{entity}/schema` is read for an entity whose source table carries a
  `map` column
- **THEN** that field is listed with `type` `map` and carries no `element_type`
