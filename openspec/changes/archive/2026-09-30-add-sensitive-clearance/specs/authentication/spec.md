## MODIFIED Requirements

### Requirement: Sensitive column authorization

A catalog column MAY be marked `sensitive`. Sensitivity SHALL be governed by one table-scoped
**clearance**, `TABLE_SENSITIVE`, that acts on the table verbs rather than being one. On its own it
SHALL confer nothing. A verb SHALL reach a table's sensitive columns only when the caller holds
**both that verb and `TABLE_SENSITIVE` on the table that owns the column**. Each SHALL be resolved
independently as the union of what the caller holds globally and what that table grants it, so a
global verb combined with a per-table clearance qualifies, and so does the reverse.

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
Ownership SHALL be decided per physical table, so within one flattened entity a caller MAY see the
source's sensitive columns and not an enrichment's, or the reverse.

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

- **WHEN** a caller holds `TABLE_READ` and `TABLE_SENSITIVE` on a source table and only `TABLE_READ`
  on one of its enrichments, and both carry sensitive columns
- **THEN** the flattened entity exposes the source's sensitive columns and omits the enrichment's

#### Scenario: Security disabled shows sensitive columns

- **WHEN** `security.mode=none` and a caller requests schema discovery or queries a sensitive column
- **THEN** the sensitive column is visible in discovery and resolvable in queries (the filter fails
  open when authentication is not enforced)

### Requirement: Application privileges and bundles

The service SHALL define a closed set of **application privileges** as the unit of authorization,
each named `<OBJECT>_<VERB>` over the object classes `TABLE`, `REGISTRY`, `AUDIT`, `SAVED_QUERY` and
`DATA_SYNC`: `TABLE_LIST`, `TABLE_GET`, `TABLE_READ`, `TABLE_SENSITIVE`, `TABLE_WRITE`,
`TABLE_MODIFY`, `TABLE_CREATE`, `TABLE_DELETE`, `TABLE_GRANT`, `REGISTRY_READ`, `REGISTRY_ADMIN`,
`AUDIT_READ`, `DATA_SYNC_OPERATE`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` and
`SAVED_QUERY_PUBLISH`. `TABLE_SENSITIVE` is the clearance (see **Sensitive column authorization**),
not a verb, and it replaces the former `TABLE_READ_SENSITIVE`. There SHALL be no
`TABLE_READ_SENSITIVE` privilege.

Six of them — `TABLE_LIST`, `TABLE_GET`, `TABLE_READ`, `TABLE_SENSITIVE`, `TABLE_WRITE` and
`TABLE_MODIFY` — are **table-scoped**: they MAY be held globally, over every table, or checked on a
single table (see `table-access-control`), and a caller's effective access SHALL be the union of the
two. Five of those six SHALL be **grantable** on a single table. `TABLE_LIST` SHALL NOT be: held
globally it lists every table, and on one table it SHALL be held only by implication from a grant that
already shows the table. The remaining privileges are global-only.
A `TABLE_` prefix SHALL NOT itself imply table scope.

The service SHALL additionally define **bundles** — reserved names that expand to a privilege set:

| Bundle | Privileges |
|---|---|
| `FULL_ADMIN` | every privilege |
| `READ_ONLY_ADMIN` | `TABLE_READ`, `REGISTRY_READ`, `AUDIT_READ`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` |
| `READ_ONLY_ANALYTIC` | `TABLE_READ`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` |
| `READ_ONLY_SENSITIVE_ANALYTIC` | `TABLE_READ`, `TABLE_SENSITIVE`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` |

A bundle SHALL be expanded when configuration is parsed, and SHALL NOT be observable by any
authorization check thereafter: no endpoint, filter or evaluator SHALL branch on a bundle name.
`FULL_ADMIN` and `READ_ONLY_ADMIN` SHALL expand to exactly the authority the corresponding
application role carries today, with **one declared exception**: `GET /v1/tables/{name}/access` now
requires `TABLE_GRANT`, which `READ_ONLY_ADMIN` does not hold, so a caller that could read grant
lists under that bundle receives `403`. The grant lists disclose which provider roles exist and what
each may do, which is not a catalog read (see `table-access-control`). `READ_ONLY_ADMIN` and
`READ_ONLY_ANALYTIC` hold no clearance, so under them the catalog read surface omits sensitive
columns (see `table-management`). Apart from that, an unmodified `roles-mapping` changes nothing
observable. Bundle names SHALL NOT be valid grantees in a table's access lists, which name raw
provider roles.

Every `roles-mapping` (`security.defaults.roles-mapping`, `providers.<name>.roles-mapping`,
`security.api-key.roles-mapping`) SHALL accept bundle names and privilege names in the same list.
A token that is neither SHALL **fail startup** with an error naming the offending value and the
property that carried it; it SHALL NOT be silently dropped. `TABLE_READ_SENSITIVE` is such a token:
there SHALL be no alias for it.

A bundle's declared set SHALL be **stored minimally and closed under implication** when it is
expanded, so the table above lists what each bundle declares rather than everything it confers:
`READ_ONLY_ANALYTIC` declares `TABLE_READ` and thereby also confers `TABLE_GET` and `TABLE_LIST`.
Storing the closure instead would duplicate the ladder in four places and let a bundle drift out of
step with it.

#### Scenario: A bundle expands to its privileges, closed under implication
- **WHEN** a provider role is mapped to `READ_ONLY_ANALYTIC` and a caller presents a token carrying
  that role
- **THEN** the caller holds `TABLE_READ`, `SAVED_QUERY_READ` and `SAVED_QUERY_WRITE` as declared,
  plus `TABLE_GET` and `TABLE_LIST` by implication, and no other privilege

#### Scenario: The sensitive analytic bundle pairs the verb with the clearance
- **WHEN** a provider role is mapped to `READ_ONLY_SENSITIVE_ANALYTIC`
- **THEN** its holder holds `TABLE_READ` and `TABLE_SENSITIVE` globally, plus `TABLE_GET` and
  `TABLE_LIST` by implication, and reads every table's sensitive values

#### Scenario: A read of grant lists is the one thing READ_ONLY_ADMIN loses
- **WHEN** a caller mapped to `READ_ONLY_ADMIN` reads `GET /v1/tables/{name}/access`
- **THEN** the response is `403 forbidden`, while every catalog, registry, audit and saved-query read
  that bundle reached before still succeeds

#### Scenario: Bundles and privileges mix in one mapping
- **WHEN** a provider role is mapped to `["TABLE_GET", "SAVED_QUERY_READ"]` and another to
  `["FULL_ADMIN"]`
- **THEN** both mappings are accepted and each caller holds exactly the union of what its roles name

#### Scenario: An unknown mapping value fails startup
- **WHEN** a `roles-mapping` value names neither a bundle nor a privilege
- **THEN** startup fails with an error naming the value and the property, and the service does not
  begin serving

#### Scenario: The removed privilege name fails startup
- **WHEN** a `roles-mapping` value names `TABLE_READ_SENSITIVE`
- **THEN** startup fails with an error naming that value and the property that carried it

#### Scenario: A caller with only per-table grants is authenticated
- **WHEN** a valid token maps to no privilege at all
- **THEN** the caller is **authenticated**, holds no global privilege, and MAY still be authorized
  for a specific table by a matching per-table grant

### Requirement: The table read ladder and privilege implication

The three table read privileges SHALL form a ladder — `TABLE_LIST`, then `TABLE_GET`, then
`TABLE_READ` — in which holding a rung SHALL confer every rung below it. The clearance
`TABLE_SENSITIVE` SHALL NOT be a rung: it SHALL imply nothing, and nothing SHALL imply it.

Implication SHALL be admitted only where it is **disclosure-monotone** — the implied privilege
reveals a strict subset of what the granting privilege already reveals — and SHALL be transitive.
The complete set of direct implications SHALL be: `TABLE_GET → TABLE_LIST`,
`TABLE_READ → TABLE_GET`, and `TABLE_MODIFY → TABLE_GET`.
`TABLE_WRITE` SHALL imply nothing: a caller holding only `TABLE_WRITE` on a table SHALL NOT be able
to list it, read its schema, or read its rows. In particular the service SHALL NOT admit
`TABLE_WRITE → TABLE_READ`.

`SAVED_QUERY_PUBLISH` SHALL imply nothing. A fifth edge `SAVED_QUERY_PUBLISH → SAVED_QUERY_READ` fails
the admission test on inspection: the claim that publishing already sees every saved query holds only for
the revision endpoint and the audit feed, while `GET /v1/saved-queries/{id}` and the listing resolve
visibility through a repository predicate with no such bypass. Admitting it would let a `PUBLISH`-only
caller read another user's `personal` saved query at `GET /{id}/revision/{rev}` while `GET /{id}` on the
same row still answered `404` — a disclosure, and an incoherent one.

Nor SHALL it imply `SAVED_QUERY_WRITE`, for the reason `TABLE_WRITE` implies nothing pointed the other
way: the two name different scopes rather than two rungs of one, and a write privilege is not a
disclosure. What makes `SAVED_QUERY_PUBLISH` authorize something on its own is that each saved-query
write endpoint defers its check to the point where the request body's `scope` is known (see
`saved-queries`).

Implication SHALL be **derived when a check runs**, never materialised into stored grant lists: a
grant list SHALL return exactly the grantees that were written to it.

#### Scenario: The top rung reads without a separate read grant
- **WHEN** a caller holds `TABLE_READ`, now the top rung, on a table and nothing else
- **THEN** the caller can list the table, read its non-sensitive schema, and read its non-sensitive
  rows, with no `get` grant alongside

#### Scenario: The clearance does not read without a verb
- **WHEN** a caller holds `TABLE_SENSITIVE` on a table and nothing else
- **THEN** the caller cannot list the table, read its schema, or read its rows

#### Scenario: Write confers no read
- **WHEN** a caller holds only `TABLE_WRITE` on a table
- **THEN** the table is absent from catalog listings and query discovery for that caller, and any
  attempt to read it fails as though the table did not exist

#### Scenario: Publishing confers no read and no personal write
- **WHEN** a caller holds only `SAVED_QUERY_PUBLISH`
- **THEN** it may create, replace and delete `common` saved queries, while a read endpoint is refused
  `403 forbidden` because that requires `SAVED_QUERY_READ`, and a write at `personal` scope is refused
  because that requires `SAVED_QUERY_WRITE`

#### Scenario: Modify confers schema visibility
- **WHEN** a caller holds only `TABLE_MODIFY` on a table
- **THEN** the caller can read that table's definition and its non-sensitive column inventory, but
  not its rows

#### Scenario: A stored grant list is returned as written
- **WHEN** a grant list names a role for `read` only, and the caller then reads the access lists
- **THEN** the response lists that role under `read` alone, with no derived entry under `get`

## ADDED Requirements

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

The subset rule on derived tables (see `enrichment-runtime`) and the restriction that a cursor column
may not be made sensitive (see `table-management`) SHALL continue to apply on top of this.

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
- **THEN** both changes are applied, subject to the cursor-column restriction and the derived-table
  subset rule

#### Scenario: A grant holder alone may not change the flag
- **WHEN** a caller holding `TABLE_GRANT` but neither `TABLE_MODIFY` nor `TABLE_SENSITIVE` on the
  table submits a schema patch changing `sensitive`
- **THEN** the request is rejected with `403 forbidden`, because the schema endpoint requires
  `TABLE_MODIFY`

## REMOVED Requirements

### Requirement: Privileges that permit self-elevation are gated at the grant level

**Reason**: Superseded by **Changing sensitivity requires the clearance**. Gating the flag on
global-only `TABLE_GRANT` stopped a table from being fully delegated. The clearance closes the same
self-elevation path. Only a caller that already holds the clearance can change a flag, so clearing one
never gives the changer a read it did not already have.

**Migration**: Grant `modify` together with `sensitive` on the table, or hold both globally. A role that
changed flags through `TABLE_GRANT` alone now also needs `TABLE_MODIFY` and `TABLE_SENSITIVE`.
