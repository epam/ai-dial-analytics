## ADDED Requirements

### Requirement: A string output binds to an enum target column

A rule output variable declared `string` SHALL be bindable to a target column of type `enum`: an
enum column holds strings, and ClickHouse validates the written value against the declared domain
at insert time. Without this the governance gate's exact-type match would reject the binding and
make an enum column unwritable by any enrichment.

`enum` SHALL NOT be accepted as an output variable's declared logical type. The rule declares the
value it produces; the column declares the domain that value must fall into. Accepting `enum` as a
variable type would advertise a domain check the gate does not perform — the same reasoning that
already refuses a parameterized `array<...>` spelling. The declaration SHALL be refused with a
message saying to declare the output as `string` and let the target column carry the domain.

Binding SHALL remain exact in the other direction: an output declared with any non-`string`,
non-numeric type SHALL still be rejected against an `enum` column.

#### Scenario: A string output binds to an enum column

- **WHEN** a rule declares a `string` output variable bound to a target column of type `enum`
- **THEN** the rule is accepted, and a materialization writing a value from the column's declared
  domain succeeds

#### Scenario: A value outside the domain fails at write time, not registration

- **WHEN** a rule bound to an `enum` column produces a value outside that column's declared domain
- **THEN** the rule remains valid at registration and the failure surfaces when the materialization
  writes, through the existing materialization failure contract

#### Scenario: Declaring an output variable as enum is refused

- **WHEN** a rule declares an output variable whose logical type is `enum`
- **THEN** the declaration is rejected with a message directing the author to declare `string` and
  let the target column declare the domain

#### Scenario: A non-string output against an enum column is still rejected

- **WHEN** a rule declares a `timestamp` output variable bound to a target column of type `enum`
- **THEN** the binding is rejected by the governance gate
