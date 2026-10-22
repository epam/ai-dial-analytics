## ADDED Requirements

### Requirement: Scope-based visibility and privilege authorization

A saved query SHALL have one of two scopes, with these rules in `oidc` mode:

- **`personal`** — readable only by the caller whose identity equals its `owner_id`. Reading a
  `personal` saved query SHALL require `SAVED_QUERY_READ`; creating, replacing, and deleting one
  SHALL require `SAVED_QUERY_WRITE`, and SHALL be permitted only on the caller's own rows.
- **`common`** — readable by any caller holding `SAVED_QUERY_READ`. Creating, replacing, or deleting
  a `common` saved query SHALL require `SAVED_QUERY_PUBLISH`; a caller without it SHALL receive
  `403 forbidden`.

`owner_id` SHALL be the author in both scopes. Changing a saved query's scope SHALL require
satisfying the write rule for **both** the stored scope and the requested scope, so a caller without
`SAVED_QUERY_PUBLISH` can neither promote its own row into `common` nor demote an existing `common`
row.

Because the deciding input is the request body's scope rather than the path, this authorization SHALL
be evaluated during request handling and SHALL surface as `403 forbidden` through the same
`ErrorView` contract as any other denial.

A saved query the caller may not read SHALL be indistinguishable from one that does not exist: every
read, replace, and delete addressed to such an id SHALL respond `404 not_found`, never `403`. The
visibility rule SHALL be applied as part of the lookup rather than as a check after fetching, so the
two cases share one code path.

A stored query body MAY name entities and columns the reading caller cannot otherwise reach. The
service SHALL NOT redact stored bodies: consistent with column names not being secret, a `common`
saved query is returned as written, and executing it remains subject to the reader's own
`TABLE_READ` on every resolved table.

#### Scenario: Personal rows are invisible to other callers

- **GIVEN** a `personal` saved query owned by one caller
- **WHEN** a different authenticated caller reads it by id
- **THEN** the response is `404` with machine code `not_found`, not `403`

#### Scenario: Personal rows of others are absent from the list

- **WHEN** a caller lists saved queries with `scope=personal`
- **THEN** the response contains only saved queries whose `owner_id` is that caller

#### Scenario: Common rows are readable with SAVED_QUERY_READ

- **WHEN** a caller holding `SAVED_QUERY_READ` lists or reads a `common` saved query
- **THEN** the response is `200` and includes it, with `owner_id` identifying its author

#### Scenario: Writing common requires SAVED_QUERY_PUBLISH

- **WHEN** a caller holding `SAVED_QUERY_WRITE` but not `SAVED_QUERY_PUBLISH` creates, replaces, or
  deletes a saved query with scope `common`
- **THEN** the response is `403` with machine code `forbidden`

#### Scenario: Reading without SAVED_QUERY_READ is forbidden

- **WHEN** a caller holding `TABLE_READ` but no saved-query privilege lists saved queries
- **THEN** the response is `403` with machine code `forbidden`

#### Scenario: A non-publisher cannot promote its own row to common

- **GIVEN** a `personal` saved query owned by a caller without `SAVED_QUERY_PUBLISH`
- **WHEN** that caller replaces it with a body whose scope is `common`
- **THEN** the response is `403` with machine code `forbidden` and the stored scope is unchanged

#### Scenario: Deleting another caller's personal row is not found

- **WHEN** a caller deletes a `personal` saved query it does not own
- **THEN** the response is `404` with machine code `not_found` and the saved query is not deleted

#### Scenario: A stored body naming unreadable tables is returned but not executable

- **GIVEN** a `common` saved query whose body names a table the reading caller cannot read
- **WHEN** that caller reads the saved query and then executes its body
- **THEN** the read returns the body as written, and the execution reports an unknown entity

### Requirement: A saved-query write is authorized against the scope in its body

`POST`, `PUT` and `DELETE` on `/v1/saved-queries` SHALL require `SAVED_QUERY_WRITE` for a
`personal`-scope write and `SAVED_QUERY_PUBLISH` for a `common`-scope one. A replace that changes
scope SHALL satisfy the requirement for **both** the stored and the requested scope, so a caller
holding only `SAVED_QUERY_WRITE` can neither promote its own row into `common` nor demote an existing
`common` row to hide it.

Because scope arrives in the request body, the endpoint gate cannot decide which privilege applies.
The declaration on each of the three mappings SHALL therefore be a **deferred** one, with the check
made at the single service-layer chokepoint that has the scope in hand. That chokepoint SHALL be
total: it decides both scopes, not only `common`. Deciding only `common` left two holes at once — an
authenticated caller holding no saved-query privilege could create a `personal` row, and a
`SAVED_QUERY_PUBLISH` holder was refused at a gate demanding `SAVED_QUERY_WRITE`, so the privilege
whose entire purpose is curating the shared catalogue authorized nothing on its own.

#### Scenario: A publish-only caller curates the shared catalogue

- **WHEN** a caller holding only `SAVED_QUERY_PUBLISH` creates, replaces or deletes a `common` saved
  query
- **THEN** the request succeeds — and the same caller is still refused `403 forbidden` on every read
  endpoint, because `SAVED_QUERY_PUBLISH` confers no read (see `authentication`)

#### Scenario: A publish-only caller may not write a personal query

- **WHEN** the same caller creates a `personal` saved query
- **THEN** the response is `403 forbidden`, naming `SAVED_QUERY_WRITE`

#### Scenario: A caller holding no saved-query privilege cannot write

- **WHEN** an authenticated caller holding neither privilege creates a `personal` saved query
- **THEN** the response is `403 forbidden`; the deferred gate admits the call and the chokepoint
  refuses it

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
operator granting `SAVED_QUERY_PUBLISH` without `TABLE_READ_SENSITIVE` should understand that the bound
literal, not the column, is what travels. The remaining structural bound is unchanged and does the real
work: the stored body carries *bound literals*, never rows, and executing it is still subject to the
reader's own `TABLE_READ` on every resolved table. A `personal` saved query remains readable only by its
owner and therefore discloses nothing to anyone else.

#### Scenario: A literal bound to a sensitive column is accepted

- **WHEN** a caller holding `SAVED_QUERY_PUBLISH` and `TABLE_READ_SENSITIVE` creates a saved query whose
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
- **WHEN** a caller holding `SAVED_QUERY_READ` but not `TABLE_READ_SENSITIVE` — one for whom that column
  is unresolvable
  on the query endpoints — reads that saved query, by id or in the `common` list
- **THEN** the read succeeds and the body is returned verbatim, literal included: the read path applies no
  sensitivity check, and the saved query is neither hidden nor refused on account of the column it
  references

#### Scenario: A stored body is returned without redaction

- **WHEN** any caller reads a saved query it is permitted to read
- **THEN** the stored `query` or `sql` is returned exactly as persisted, including any literal bound to a
  `sensitive` column

## REMOVED Requirements

### Requirement: Scope-based visibility and authorization

**Reason**: The rule is unchanged in shape but its authorization vocabulary is replaced: "an
application role" becomes `SAVED_QUERY_READ` for reads and `SAVED_QUERY_WRITE` for personal writes,
and the `common` gate becomes `SAVED_QUERY_PUBLISH` rather than `FULL_ADMIN` — separating the
authority to curate shared definitions from the authority to read sensitive columns, which today are
the same role. Replaced by **Scope-based visibility and privilege authorization**.

**Migration**: None for an unmodified `roles-mapping`: `FULL_ADMIN` expands to every privilege
including `SAVED_QUERY_PUBLISH`, and `READ_ONLY_ADMIN` expands to `SAVED_QUERY_READ` and
`SAVED_QUERY_WRITE`, so both roles keep exactly the access they have today. A deployment that wants
a curator who is not an administrator maps that provider role to `SAVED_QUERY_PUBLISH` explicitly.
