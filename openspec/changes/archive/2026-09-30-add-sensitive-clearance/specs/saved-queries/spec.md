## MODIFIED Requirements

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
oversight. A `common` saved query is readable by every caller holding `SAVED_QUERY_READ`, so a
literal
bound to a `sensitive` column in a `common` body SHALL be returned verbatim **including to a caller who is
not entitled to that column** — a caller for whom the same column is unresolvable on the query surfaces.
The system SHALL NOT apply a sensitivity check when reading a saved query, and SHALL NOT hide a saved query
because its body references a `sensitive` column: `common` rows stay readable by any
`SAVED_QUERY_READ` holder and
column names stay visible, as this capability's visibility rules already require. The bound on the
disclosure is the write rule, not the read rule: writing `common` requires `SAVED_QUERY_PUBLISH`.

**That bound is now weaker, and stating it is the point.** While `common` writes required
`FULL_ADMIN` — the only role entitled to sensitive columns — the author of a `common` saved query was
necessarily someone who could already read every value it could bind. `SAVED_QUERY_PUBLISH` is
separately grantable and implies no table privilege, so a deployment that maps a curator to it alone
creates an author who may publish a literal it could not itself have read from the table. Separating
curation from PII entitlement is the whole purpose of the split (see **Scope-based visibility and
privilege authorization**), so this is a consequence to accept knowingly rather than a regression: an
operator granting `SAVED_QUERY_PUBLISH` without `TABLE_READ` and `TABLE_SENSITIVE` should understand that the bound
literal, not the column, is what travels. The remaining structural bound is unchanged and does the real
work: the stored body carries *bound literals*, never rows, and executing it is still subject to the
reader's own `TABLE_READ` on every resolved table. A `personal` saved query remains readable only by its
owner and therefore discloses nothing to anyone else.

#### Scenario: A literal bound to a sensitive column is accepted

- **WHEN** a caller holding `SAVED_QUERY_PUBLISH`, `TABLE_READ` and `TABLE_SENSITIVE` creates a saved query whose
  filter compares a `sensitive` column to a literal value
- **THEN** the saved query is created and the stored body retains the literal exactly as submitted

#### Scenario: A SQL body binding a sensitive literal is accepted

- **WHEN** the same caller creates a `sql`-kind saved query whose `WHERE` clause compares a
  `sensitive` column to a literal
- **THEN** the saved query is created

#### Scenario: A sensitive literal reached through a select alias is accepted

- **WHEN** the same caller creates an aggregate saved query that projects an aggregate over a
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

- **GIVEN** an entitled caller has created a `common` saved query whose filter binds a literal to a
  `sensitive` column
- **WHEN** a caller holding `SAVED_QUERY_READ` but not `TABLE_SENSITIVE` — one for whom that column
  is unresolvable
  on the query endpoints — reads that saved query, by id or in the `common` list
- **THEN** the read succeeds and the body is returned verbatim, literal included: the read path applies no
  sensitivity check, and the saved query is neither hidden nor refused on account of the column it
  references

#### Scenario: A stored body is returned without redaction

- **WHEN** any caller reads a saved query it is permitted to read
- **THEN** the stored `query` or `sql` is returned exactly as persisted, including any literal bound to a
  `sensitive` column
