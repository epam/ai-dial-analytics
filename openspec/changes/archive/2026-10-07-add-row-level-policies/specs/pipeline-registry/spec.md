## ADDED Requirements

### Requirement: A row-confined caller may not arm or run a pipeline over a table it is confined on

A pipeline reads its sources under the system identity, which no row policy confines (see
[table-access-control](../table-access-control/spec.md)). A caller confined by a row policy on a table
SHALL therefore not be able to have a pipeline read that table on its behalf.

The tables a pipeline **reads**, for this rule, SHALL be its declared input table, its target's catalog
`source_table` when the target is an enrichment, and every enrichment of either. That is deliberately a
superset of what any one declaration joins: the answer does not depend on a declaration that may not yet
resolve, and refusing a confined caller costs nothing it was entitled to. On any of these tables, a caller
on which row-policy evaluation confines its reads SHALL be refused with HTTP 403 and error code
`row_policy_confined`, naming the table, when it:

- writes a declaration that **arms** the pipeline — a registration or patch leaving it `enabled: true`,
  or an enable;
- requests a dry-run **preview** of the pipeline, of either kind; or
- requests an **on-demand refresh** of an aggregate pipeline.

The check SHALL run before any part of the request is applied. Naming the table discloses nothing: the
caller addressed a pipeline whose declaration already names it. A write that leaves the pipeline disabled
SHALL be stored as the deferred gate stores it, and the check SHALL run when that pipeline is armed. A
scheduled refresh SHALL NOT be subject to the check — it runs under the system identity, which carries no
provider role — and what it writes is governed by the target's own policies.

In `none` mode the check SHALL NOT apply.

#### Scenario: A confined administrator cannot arm a pipeline over its confined table

- **GIVEN** a caller holding `REGISTRY_ADMIN` whose role is confined by a row policy on a table, and a
  disabled aggregate pipeline whose input is that table
- **WHEN** it enables the pipeline, previews it, or refreshes it on demand
- **THEN** each request is refused `403 row_policy_confined` naming the table, and nothing runs

#### Scenario: A disabled declaration is stored and checked on arming

- **WHEN** the same caller registers a pipeline over the table with `enabled: false`
- **THEN** the declaration is stored
- **AND WHEN** it later enables the pipeline
- **THEN** the enable is refused `403 row_policy_confined`

#### Scenario: A confinement on the target's source is refused

- **GIVEN** a caller unconfined on an enrich pipeline's declared input but confined on its target
  enrichment's `source_table`
- **WHEN** it arms the pipeline
- **THEN** the request is refused `403 row_policy_confined` naming that source

#### Scenario: An unconfined caller reaches the gate

- **WHEN** a caller holding `REGISTRY_ADMIN`, unconfined on every table the pipeline reads, arms it
- **THEN** the request is processed under the existing rules, including the declaration gate
