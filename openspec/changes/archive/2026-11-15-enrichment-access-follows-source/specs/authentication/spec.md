## MODIFIED Requirements

### Requirement: Sensitive column authorization

A catalog column MAY be marked `sensitive`. Sensitivity SHALL be governed by one table-scoped
**clearance**, `TABLE_SENSITIVE`, that acts on the table verbs rather than being one. On its own it
SHALL confer nothing. A verb SHALL reach a table's sensitive columns only when the caller holds
**both that verb and `TABLE_SENSITIVE` on the table that owns the column**. Each SHALL be resolved
independently as the union of what the caller holds globally and what that table grants it, so a
global verb combined with a per-table clearance qualifies, and so does the reverse. For a column of an enrichment,
`TABLE_READ` and `TABLE_SENSITIVE` SHALL be resolved on the enrichment's source, `TABLE_GET` on the source or by
implication from `modify` held on the enrichment, and `TABLE_MODIFY` on the enrichment itself (see
`table-access-control`, "An enrichment's read-side access is its source's").

The clearance SHALL modify exactly three verbs:

- `TABLE_READ`: sensitive **values** SHALL be readable only with the clearance.
- `TABLE_GET`: sensitive columns SHALL appear in the catalog read surface (`GET /v1/tables`,
  `GET /v1/tables/{name}`) only with the clearance.
- `TABLE_MODIFY`: sensitive columns SHALL be changeable, and any column's `sensitive` flag
  declarable or changeable, only with the clearance (see **Changing sensitivity requires the
  clearance**).

It SHALL NOT modify `TABLE_WRITE`, a blind insert, or any global-only privilege.

In `oidc` mode, for any caller lacking the clearance on the owning table, that table's sensitive
columns SHALL be filtered out of the resolved query model before it is used. They SHALL therefore be
absent from schema discovery and SHALL NOT be referenceable in any clause of a structured or SQL query
(`select`, `filter`, `group_by`, `having`, `sort`). They SHALL also be absent from the catalog read
surface and from the column set a schema mutation resolves against (see `table-management`).
Because an enrichment's clearance is its source's, one flattened entity SHALL have one clearance: a caller that sees
the source's sensitive columns SHALL see every enrichment's, and one that does not SHALL see none.

A caller lacking the clearance SHALL NOT be able to distinguish a sensitive column from a non-existent
one on the query surface or the catalog read surface. Referencing it in a query SHALL fail as an
ordinary unknown-field validation error (HTTP 400), and naming it in a schema mutation SHALL fail as an
unknown column (HTTP 422).

Hiding a column is a **working view, not a secrecy guarantee for its name**. The audit trail, the
registry surfaces, a table's structural fields (`ordering_key`, `partition_by`, `grain_key`), and a
name collision on a schema `add` or `rename` MAY still name a hidden column. Only its values, and its
presence in the catalog, discovery and query views, SHALL be withheld.

When security is disabled (`security.mode=none`) the filter SHALL fail open: all columns, including
sensitive ones, SHALL be visible. The filter SHALL be applied at a single chokepoint in the
catalog-backed query model, so it SHALL cover schema discovery, `POST /v1/queries/execute`,
`POST /v1/queries/execute-sql`, the translation endpoints, the Flight SQL surface and the
empty-projection ("select all") expansion uniformly. The catalog read surface and the schema mutation
surface SHALL apply the same per-table predicate.

#### Scenario: Non-admin cannot see or reference a sensitive column

- **WHEN** a caller holding `TABLE_READ` but not `TABLE_SENSITIVE` requests schema discovery for
  an entity that has a sensitive column, and separately submits a query that references that column
- **THEN** the sensitive column is absent from the discovery response, and the query is rejected with
  HTTP 400 as an unknown field — the response does not reveal that the column exists

#### Scenario: The catalog read surface still names the column

- **WHEN** a caller holding `TABLE_GET` and `TABLE_SENSITIVE` on the owning table reads that table's
  definition through `GET /v1/tables/{name}`
- **THEN** the sensitive column is present in the returned column inventory with `sensitive: true`
- **AND WHEN** the caller from the previous scenario, which lacks the clearance, reads the same
  definition
- **THEN** the sensitive column is absent from the returned column inventory

#### Scenario: FULL_ADMIN sees and can reference a sensitive column

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the owning table, as `FULL_ADMIN`
  does globally, requests schema discovery and queries a sensitive column
- **THEN** the sensitive column appears in the discovery response and the query resolves and executes
  normally

#### Scenario: The clearance alone confers nothing

- **WHEN** a caller holds `TABLE_SENSITIVE` on a table and no verb on it, globally or by grant
- **THEN** the table is absent from the caller's catalog listing and discovery, and a query naming it
  fails as though the table did not exist

#### Scenario: Verb and clearance combine across scopes

- **WHEN** a caller holds `TABLE_READ` globally and is granted `sensitive` on one table only
- **THEN** it reads that table's sensitive values, and every other table's sensitive columns stay
  hidden from it

#### Scenario: The privilege is evaluated per owning table

- **GIVEN** a source and one of its enrichments that both carry sensitive columns
- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the source queries the flattened entity
- **THEN** both the source's and the enrichment's sensitive columns resolve
- **AND WHEN** a caller holding `TABLE_READ` on the source without the clearance queries it
- **THEN** neither table's sensitive columns resolve

#### Scenario: Security disabled shows sensitive columns

- **WHEN** `security.mode=none` and a caller requests schema discovery or queries a sensitive column
- **THEN** the sensitive column is visible in discovery and resolvable in queries (the filter fails
  open when authentication is not enforced)

### Requirement: Changing sensitivity requires the clearance

A privilege SHALL be evaluated on two axes: what it **discloses**, and what it lets its holder
**change in order to disclose more**. The operation that widens access to a sensitive column is
changing whether the column is sensitive. It SHALL therefore require the same clearance that governs
access to that column.

Accordingly, every operation that declares or changes a column's `sensitive` flag SHALL require
`TABLE_MODIFY` **and** `TABLE_SENSITIVE` on that table, each global or granted. This applies in either
direction, on a `PATCH .../schema` `update`, on an `add` that declares the column `sensitive`, and on a
`POST .../schema` definition. `TABLE_GRANT` SHALL NOT be required, and SHALL NOT suffice on its own.

A request by a caller lacking the clearance that attempts it SHALL be rejected with `403` and the
`ErrorView` error code `forbidden`. The column concerned is one the caller can see (a new column, or a
visible non-sensitive one), so the refusal discloses nothing. A request that names a column **hidden**
from the caller is instead rejected as naming an unknown column (see `table-management`).

The restriction that a cursor column may not be made sensitive (see `table-management`) SHALL continue to apply on
top of this.

#### Scenario: A modify grantee without the clearance cannot declassify a column
- **WHEN** a caller holding `TABLE_MODIFY` but not `TABLE_SENSITIVE` on a table submits a schema patch
  clearing `sensitive` on one of its columns
- **THEN** the request is rejected with `422` as naming an unknown column, because that column is
  hidden from the caller, and the column remains sensitive

#### Scenario: A modify grantee without the clearance cannot classify a column
- **WHEN** the same caller submits a schema patch setting `sensitive: true` on a visible column, or
  adds a column declared `sensitive: true`
- **THEN** the request is rejected with `403` and error code `forbidden`, and nothing is changed

#### Scenario: A delegated owner changes the flag in both directions
- **WHEN** a caller whose provider role is in a table's `modify` and `sensitive` lists, holding no
  global privilege, sets `sensitive` on one column and clears it on another
- **THEN** both changes are applied, subject to the cursor-column restriction

#### Scenario: A grant holder alone may not change the flag
- **WHEN** a caller holding `TABLE_GRANT` but neither `TABLE_MODIFY` nor `TABLE_SENSITIVE` on the
  table submits a schema patch changing `sensitive`
- **THEN** the request is rejected with `403 forbidden`, because the schema endpoint requires
  `TABLE_MODIFY`

