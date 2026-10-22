## ADDED Requirements

### Requirement: The group projection reads one heavy column per statement

The projection phase of a group fetch SHALL issue one statement per `heavy` bound column rather than one
statement projecting all of them. Every bound column that is not `heavy` SHALL be projected by the first
of those statements, and a declaration binding no `heavy` column SHALL be read by exactly one statement.
Each statement SHALL carry the same member lookup, the same safety lag, the same group-key predicate and
the same grain joins as a single combined statement would, so the split changes what is read per
statement and nothing else.

Heaviness SHALL be read from the catalog's column flag. A bound column with no binding in the
role-filtered model SHALL be treated as `heavy`: it cannot arise on this path, because entitlement over
the whole declaration is enforced before either phase runs, and of the two ways to misjudge a column's
cost an extra statement is the one that cannot exhaust a budget.

The statements' results SHALL be merged onto the members they were selected for, matched by the member
lookup key every statement projects. A member SHALL be kept only when **every** statement found it, and
that SHALL be determined by counting the statements that matched the member, not by testing whether a
projected value is present: a `heavy` column is legitimately NULL, and inferring absence from a NULL
would drop the member. A member that one statement found and another did not SHALL be treated exactly as
a member lost between the two phases already is — omitted, with `truncated: true`.

This requirement exists because the cost of projecting a `heavy` column is very nearly independent of the
bytes that column returns, and additive across the columns one statement projects. A lookup on a grain key
uncorrelated with the enrichment's partitioning lands a candidate granule in every part of the partitions
it reads, and the read buffers for all of a statement's columns are held at once; that can be on the order
of a GiB per `heavy` column to return a single member, for a kilobyte-sized value and a value hundreds of
times larger alike. So a declaration binding two `heavy` columns costs twice one binding a single column, and the
pair exceeds a per-statement ceiling that either half clears on its own. Splitting by column makes the
peak the largest single column instead of the sum of all of them, which is what stops a declaration's
`heavy`-column count from deciding whether its group fetch runs at all. Partition pruning does not
substitute for this: the partition a group fetch reads is the current one, which is where an ingesting
source's unmerged parts are, so pruning to it removes the other days and not the cost.

A budget refusal from one of these statements SHALL name the columns that statement projected, so the
refusal identifies which of a declaration's bound columns does not fit.

#### Scenario: Two heavy columns are projected by one statement each

- **GIVEN** a group pipeline binding two `heavy` columns and one inexpensive column
- **WHEN** a group's rows are assembled
- **THEN** the projection phase issues one statement per `heavy` column
- **AND** neither statement names the other's `heavy` column
- **AND** the inexpensive column is projected by the first of them
- **AND** the assembled member carries the values of all three

#### Scenario: One heavy column is read as before

- **GIVEN** a group pipeline binding a single `heavy` column
- **WHEN** a group's rows are assembled
- **THEN** the projection phase issues exactly one statement

#### Scenario: A NULL heavy column keeps its member

- **GIVEN** a group pipeline binding two `heavy` columns
- **AND** a member whose second `heavy` column is NULL
- **WHEN** a group's rows are assembled
- **THEN** that member is returned with that column NULL, rather than omitted as unfound

#### Scenario: A refusal names the columns of the read it refused

- **GIVEN** a group pipeline binding two `heavy` columns
- **WHEN** one projection statement exceeds the pipeline read budget
- **THEN** the refusal names the columns that statement projected
