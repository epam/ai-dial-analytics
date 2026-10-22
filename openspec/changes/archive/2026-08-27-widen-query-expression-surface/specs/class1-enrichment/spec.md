## ADDED Requirements

### Requirement: Class-1 derivations may read map and array source columns

The map and array accessors SHALL be available to a `sql` output-variable expression on the same terms
they are available to a query: `map_value`, `map_contains` and `map_keys` over a `map` source column,
`array_has`/`array_has_any`/`array_has_all`/`array_length` over an `array` source column, and
`split_string` over a text column. This follows from validation running through the same Calcite front
end and the same closed function catalog as the read path.

This is what makes a `map` or `array` source column derivable at all. Previously both types were
projection-only in every position, so no Class-1 expression could read one, and a dimension carried
inside a map could only be projected — never lifted into a scalar, grain-bound column. Lifting a map
key into a typed enrichment column is now expressible, which is the supported route to giving such a
key its own `sensitive` flag, its own type, and its own place in a `group_by`: whole-column sensitivity
on the map cannot distinguish one key from another.

The governance rules SHALL apply unchanged. `map_value` returns text, `map_contains` boolean, and
`array_has*`/`array_length` scalars, so all six satisfy the scalar-not-aggregate and
single-projected-value rules and bind to a scalar-typed output variable. `map_keys` and `split_string`
return arrays, so each SHALL be accepted only when bound to an array-typed target column, under the
existing array-result rule, and their element type SHALL remain unchecked for the same reason every
computed array's is.

A `map`- or `array`-typed **result** SHALL remain rejected: the accessors exist to read those columns,
not to write them. An expression that projects a bare `map` column SHALL continue to fail validation,
as an `object`-typed result does.

#### Scenario: Map key lifted into a scalar enrichment column

- **WHEN** a `sql` evaluator declares an output expression calling `map_value` on a `map` source column
  with a literal key, bound to a `string`-typed output variable
- **THEN** the rule registers, the expression passing the scalar, single-value and type-compatibility
  gates

#### Scenario: Array membership derives a boolean enrichment column

- **WHEN** a `sql` evaluator declares an `array_has` call over an `array` source column bound to a
  `boolean`-typed output variable
- **THEN** the rule registers

#### Scenario: Map key presence derives a boolean enrichment column

- **WHEN** a `sql` evaluator declares a `map_contains` call on a `map` source column with a literal key,
  bound to a `boolean`-typed output variable
- **THEN** the rule registers

#### Scenario: Map keys bind only to an array column

- **WHEN** a `sql` evaluator declares a `map_keys` call bound to an `array`-typed output variable, and a
  second declares it bound to a `string`-typed one
- **THEN** the first registers and the second is rejected with HTTP 422 `rule_validation_failed`

#### Scenario: Split result binds only to an array column

- **WHEN** a `sql` evaluator declares a `split_string` call bound to an `array`-typed output variable,
  and a second declares the same expression bound to a `string`-typed one
- **THEN** the first registers and the second is rejected with HTTP 422 `rule_validation_failed`

#### Scenario: A bare map column is still not a derivable result

- **WHEN** a `sql` evaluator declares an output expression that is a bare `map` column reference
- **THEN** the rule is rejected with HTTP 422 `rule_validation_failed` — the accessors read a map, they
  do not make one bindable
