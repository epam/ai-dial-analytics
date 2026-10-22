## ADDED Requirements

### Requirement: Function validation remedies name the SQL function catalog

When `POST /v1/queries/validate-sql` classifies an issue as one of the function-catalog codes, the
issue's `remedy` SHALL name `GET /v1/queries/sql-functions`, the catalog of spellings this surface
accepts, and SHALL NOT name `GET /v1/queries/functions`. The codes are:

- `unknown_function`: no function of that signature exists;
- `unsupported_function`: the function validated but has no lowering;
- `function_arity`: the argument count is wrong.

The DSL catalog lists names this surface refuses (`plus`, `minus`, `multiply`, `div`, `modulo`) and
signatures it cannot parse (`percentile_cont(fraction, column)`). So pointing a SQL caller at it
would send them to the source of the mistake.

The codes themselves and the rest of each issue are unchanged.

#### Scenario: An unknown function points at the SQL catalog

- **WHEN** a client validates `SELECT plus(total_tokens, 1) AS n FROM dial_usage_log`
- **THEN** the verdict is invalid with an issue of code `unknown_function` whose `remedy` names
  `GET /v1/queries/sql-functions`

#### Scenario: A wrong arity points at the SQL catalog

- **WHEN** a client validates `SELECT count() AS n FROM dial_usage_log`
- **THEN** the issue's code is `function_arity` and its `remedy` names `GET /v1/queries/sql-functions`
  and `count(*)`
