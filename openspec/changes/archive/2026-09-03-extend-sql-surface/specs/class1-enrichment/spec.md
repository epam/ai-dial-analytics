## ADDED Requirements

### Requirement: The Class-1 gate refuses a window function explicitly

Now that the SQL front end translates `OVER` into a DSL `window` expression (see
[sql-query-translation](../sql-query-translation/spec.md), "Window functions translate to the DSL window
expression"), the "Scalar, not aggregate" rule of the Class-1 expression governance gate SHALL be enforced
for windows by an explicit check rather than as a side effect of the query mode. A window keeps its block
in row mode, so the mode assertion that rejects an aggregate does not reject it.

The gate SHALL therefore reject, with HTTP 422 `rule_validation_failed`, a `type=sql` output-variable
expression that contains a `window` expression anywhere in its tree — including one nested inside a `CASE`
branch — and the rejection message SHALL name the window as the reason. A rule predicate (`filter_sql`, a
group rule's `ready_when.signal`) SHALL likewise carry no window expression; the front end already refuses
`OVER` in a `WHERE`, and the gate SHALL surface any such predicate as the same 422 rather than as an
internal error.

The reason is the exactly-once *effect* guarantee, not grain: a Class-1 expression is rendered into
`INSERT INTO <target> … SELECT … FROM <source> WHERE <window predicates>`, so a window function would be
computed over whichever source rows the materialization window happened to cover. `row_number() OVER ()`
would write a value that depends on the window's boundaries, and re-materializing an overlapping window —
which the probe-then-write contract explicitly permits after a timeout or a retry — would write a
different value for the same source row. The position rules the query surfaces apply to a window
(`select`-only, never in a filter) do not run on the enrichment path, which renders the analysed
expression directly.

#### Scenario: A window expression in an output-variable expression is rejected

- **WHEN** a `type=sql` rule declares an output-variable expression containing a window function (e.g.
  `row_number() over ()` or `sum(total_tokens) over (order by request_time)`)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, and the message
  names the window function as the reason

#### Scenario: A window expression nested inside a conditional is rejected

- **WHEN** a `type=sql` rule declares an output-variable expression whose `CASE` branch contains a window
  function
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A window expression in a rule predicate is rejected

- **WHEN** a rule's `filter_sql` contains a window function
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`
