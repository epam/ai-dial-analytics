## ADDED Requirements

### Requirement: A sensitive value may be persisted in a saved query body

A saved query body SHALL be accepted regardless of whether it binds a literal value to a column the
catalog marks `sensitive`. The system SHALL NOT apply any sensitivity-derived restriction to the
saved-query write path, on create or on replace, for `query`-kind or `sql`-kind bodies. Such a body SHALL
be subject to exactly the same validation as any other body — the translation described by *Saved query
body validation* — and to no additional check.

The metadata database that holds saved queries is inside the same trusted perimeter as the queried data
store, so persisting a value read from that store copies nothing across a boundary. There is
consequently no machine code for a sensitivity-related write refusal: the system SHALL NOT emit
`sensitive_literal_not_allowed` or any successor code for this condition.

Sensitive-column **read** entitlement is unaffected. Whether a caller can resolve a `sensitive` column at
all SHALL continue to be decided by the caller's resolved view of the entity, as
[authentication](../authentication/spec.md) defines it. A caller who may not see a `sensitive` column
therefore still fails at field resolution with the ordinary unresolvable-field error, and the system SHALL
NOT distinguish a column that is restricted from one that is absent.

A stored body SHALL be returned exactly as persisted. The read path SHALL NOT redact, mask, or omit any
part of a body on account of sensitivity, for any caller permitted to read that saved query.

This has a consequence at `common` scope that the system SHALL exhibit deliberately rather than by
oversight. A `common` saved query is readable by every caller holding an application role, so a literal
bound to a `sensitive` column in a `common` body SHALL be returned verbatim **including to a caller who is
not entitled to that column** — a caller for whom the same column is unresolvable on the query surfaces.
The system SHALL NOT apply a sensitivity check when reading a saved query, and SHALL NOT hide a saved query
because its body references a `sensitive` column: `common` rows stay readable by any application role and
column names stay visible, as this capability's visibility rules already require. The bound on the
disclosure is the write rule, not the read rule: writing `common` requires `FULL_ADMIN`, the only role
entitled to `sensitive` columns, so such a value can only be published by a caller entitled to it. A
`personal` saved query remains readable only by its owner and therefore discloses nothing to anyone else.

#### Scenario: A literal bound to a sensitive column is accepted

- **WHEN** a `FULL_ADMIN` caller creates a saved query whose filter compares a `sensitive` column to a
  literal value
- **THEN** the saved query is created and the stored body retains the literal exactly as submitted

#### Scenario: A SQL body binding a sensitive literal is accepted

- **WHEN** a `FULL_ADMIN` caller creates a `sql`-kind saved query whose `WHERE` clause compares a
  `sensitive` column to a literal
- **THEN** the saved query is created

#### Scenario: A sensitive literal reached through a select alias is accepted

- **WHEN** a `FULL_ADMIN` caller creates an aggregate saved query that projects an aggregate over a
  `sensitive` column under an alias and compares that alias to a literal in `having`
- **THEN** the saved query is created

#### Scenario: Replacing a body with a sensitive literal is accepted

- **WHEN** a caller replaces an existing saved query with a body that binds a literal to a `sensitive`
  column
- **THEN** the replacement succeeds and the stored body is the submitted one

#### Scenario: A caller who cannot see a sensitive column is still stopped at resolution

- **WHEN** a caller who may not see `sensitive` columns creates a saved query referencing one
- **THEN** the response is `400` with machine code `bad_request` as an unresolvable field, and the
  response does not reveal whether the column exists

#### Scenario: A common sensitive literal is readable by a caller not entitled to the column

- **GIVEN** a `FULL_ADMIN` caller has created a `common` saved query whose filter binds a literal to a
  `sensitive` column
- **WHEN** a caller holding a non-`FULL_ADMIN` application role — one for whom that column is unresolvable
  on the query endpoints — reads that saved query, by id or in the `common` list
- **THEN** the read succeeds and the body is returned verbatim, literal included: the read path applies no
  sensitivity check, and the saved query is neither hidden nor refused on account of the column it
  references

#### Scenario: A stored body is returned without redaction

- **WHEN** any caller reads a saved query it is permitted to read
- **THEN** the stored `query` or `sql` is returned exactly as persisted, including any literal bound to a
  `sensitive` column

## REMOVED Requirements

### Requirement: No sensitive literal is persisted

**Reason**: The requirement rested on the metadata database being a weaker perimeter than the queried
data store. PostgreSQL is now a trusted environment on the same footing as the data store, so refusing
the write defends no boundary while blocking the most ordinary query a caller entitled to a `sensitive`
column would want to save. Because saved queries are not parameterized, the refusal had no in-place
remedy — the query could not be saved in any form.

**Migration**: No client action is required. Requests that previously succeeded are unaffected; requests
that previously failed with `422 sensitive_literal_not_allowed` now succeed. The machine code
`sensitive_literal_not_allowed` is retired and will no longer be returned by any endpoint — a client that
branched on it can delete that branch. The behaviors this requirement also carried are preserved by
*A sensitive value may be persisted in a saved query body*: stored bodies are still returned without
redaction, and a caller who cannot see a `sensitive` column is still stopped by the ordinary
unresolvable-field `400 bad_request` without any signal that the column exists. What the removal does
change, and what the replacing requirement states outright, is that a `common` saved query can now carry a
sensitive literal to every caller with an application role — a disclosure this requirement used to prevent
by making the body impossible to store.
