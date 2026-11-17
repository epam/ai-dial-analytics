## ADDED Requirements

### Requirement: An enrichment's read-side access is its source's

An enrichment SHALL carry no read-side access of its own. For a table of type `enrichment`, `TABLE_GET`, `TABLE_READ`
and `TABLE_SENSITIVE` SHALL be decided as for its `source_table`: a caller holds `TABLE_READ` or `TABLE_SENSITIVE` on
the enrichment when, and only when, it holds it on the source, globally or by the source's grant lists; and it holds
`TABLE_GET` on the enrichment when it holds it on the source — by any list that confers it there, the source's `modify`
included — or holds `modify` on the enrichment itself. A `*` stored in the source's `modify` list, which confers nothing
on the source, SHALL confer nothing on its enrichments either. This SHALL hold on every
surface that evaluates a per-table privilege — the catalog listing and read, schema discovery, query resolution on
every user read surface, the schema mutation surface, catalog export, pipeline authorization and the reported
`permissions` — because they share one evaluation.

`write` and `modify` SHALL remain the enrichment's own lists, evaluated on the enrichment. `modify` held on the
enrichment SHALL still confer `get` on it by implication, so a role that maintains an enrichment's schema can read its
definition without reading the source. Because the clearance on an enrichment is the source's, such a role changes an
enrichment's sensitive columns, or any column's `sensitive` flag, only when it also holds the source's clearance.

An enrichment SHALL accept no `get`, `read` or `sensitive` grant and no row policy, in `none` security mode as in
`oidc`, because the refusal constrains what state may be stored rather than who may act. Which rows a caller reads
through an entity SHALL be decided by the source's row policies, and an enrichment's values SHALL reach a caller only on the
source rows it reads.

State an earlier build stores on an enrichment — an old replica during a rolling upgrade, or one rolled back to — SHALL NOT widen
anything. A `get`, `read` or `sensitive` list stored there SHALL confer nothing, SHALL be returned as stored, and MAY be
cleared by a `PUT .../access` that sends it empty. A row policy stored there SHALL keep narrowing as it did — it only
ever withholds the enrichment's values on rows the source returns — SHALL be returned as stored, and MAY be removed with
`DELETE .../row-policies/{role}`. Whether another of the caller's roles lifts such a leftover policy SHALL be decided
by the same rule as on any table, against the enrichment's own stored lists, as when the policy was written.

The reason is that an enrichment is not a queryable entity: every statement that reaches its rows has its source as
`FROM`. Lists of its own could therefore only narrow what the source allows, and only for a caller reading the source
through a per-table grant — never for one holding `TABLE_READ` globally — which nothing in the API made visible. The
way to keep an enrichment column from some readers of its source is to mark it `sensitive`, which reserves it for the
source's clearance holders.

#### Scenario: A reader of the source sees the enrichment's columns

- **GIVEN** a caller with no global privilege whose provider role is in a source's `read` list and in no list of its
  enrichment
- **WHEN** it requests the entity's schema and queries an enrichment column
- **THEN** the enrichment's non-sensitive columns are present and the query resolves

#### Scenario: The source's clearance reaches the enrichment's sensitive columns

- **GIVEN** a caller whose provider role is in a source's `read` and `sensitive` lists, and an enrichment of it
  carrying a `sensitive` column
- **WHEN** the caller queries that column
- **THEN** the query resolves
- **AND WHEN** the role is removed from the source's `sensitive` list
- **THEN** the column is absent from the schema and referencing it fails as an unknown field

#### Scenario: The enrichment is listed with its source

- **GIVEN** a caller that may list a source and holds nothing on its enrichment
- **WHEN** it lists the catalog with `GET /v1/tables`
- **THEN** the enrichment appears, with the `get`, `read` and `sensitive` flags of its source

#### Scenario: An enrichment maintainer sees the definition it maintains

- **GIVEN** a caller with no global privilege whose provider role is in an enrichment's `modify` list and in no list of
  its source
- **WHEN** it reads `GET /v1/tables/{enrichment}`, and then queries the source's entity
- **THEN** the read is `200` with the enrichment's non-sensitive columns, and the query reports an unknown entity

#### Scenario: A source modify holder sees the enrichment's columns

- **GIVEN** a caller with no global privilege whose provider role is in a source's `modify` list only
- **WHEN** it requests the source entity's schema and reads `GET /v1/tables/{enrichment}`
- **THEN** the enrichment's non-sensitive columns are present in both

#### Scenario: A legacy everyone modify grant on the source shows no enrichment

- **GIVEN** a source whose stored `modify` list is `["*"]` and whose other lists do not name the caller's roles
- **WHEN** an authenticated caller with no global privilege lists the catalog and reads `GET /v1/tables/{enrichment}`
- **THEN** neither the source nor its enrichment is listed, and the read reports the enrichment as absent

#### Scenario: A list or policy left on an enrichment widens nothing

- **GIVEN** an enrichment that still stores a `read` list naming role `R` and a row policy for role `X`, written by an
  earlier build
- **WHEN** a caller carrying only `R` queries the source's entity, and a caller holding `TABLE_GRANT` reads the
  enrichment's access and row policies and then deletes `X`'s policy
- **THEN** the query reports an unknown entity, the reads return the `read` list and the policy as stored, and the
  delete answers `204`

#### Scenario: The catalog migration clears an existing enrichment's read-side lists and policies

- **GIVEN** an enrichment whose `read`, `sensitive`, `write` and `modify` lists and a row policy were stored by an
  earlier build, and a source with lists and policies of its own
- **WHEN** the catalog migration has run and a caller holding `TABLE_GRANT` reads both tables' access and row policies
- **THEN** the enrichment's `get`, `read` and `sensitive` are empty, its `write` and `modify` are as stored, and it
  has no row policy; the source's lists and policies are as stored

### Requirement: Row-policy evaluation by caller role

In `oidc` mode, for every table a caller reads through a user read surface, the rows it sees SHALL be
decided from the policies whose role is among the caller's raw provider roles:

- **none of the caller's roles has a policy on the table** — the caller SHALL see every row it may
  read;
- **another of the caller's roles may read the table and has no policy on it** — the caller SHALL see
  every row it may read: that role's unrestricted read stands on its own;
- **otherwise** — the caller SHALL see exactly the rows satisfying **at least one** of its policies'
  filters.

A role **may read the table**, for the second case, when the table's `read` grant list names it (for a policy an
earlier build left on an enrichment, the enrichment's own stored `read` list), or when a
configured `roles-mapping` maps it to `TABLE_READ` globally **and** the caller holds `TABLE_READ` globally
on the request. Only such a role counts: the caller's other provider roles — IdP groups, Core roles that
confer nothing on the table — SHALL NOT lift a confinement, policy or not, because nearly every caller
carries some, and counting them would make a policy on a widely-held role meaningless. The second half of
the global condition keeps a role name mapped differently by two providers from being credited with a read
the caller's own provider does not give it.

`*` SHALL take part in this rule as a role every authenticated caller carries. A table whose `read` list
names `*` and that stores a policy for `*` therefore gives every caller the rows of that policy, plus the
rows of the policies on its other roles; and a caller one of whose own roles may read the table with no
policy SHALL still see every row, by the second case. A caller meant to be confined by the `*` policy
SHALL therefore have no other read path to the table without a policy.

A policy SHALL apply to its **role** whatever path that role reads the table by: a role holding
`TABLE_READ` globally, through a bundle or a privilege mapping, SHALL be confined by its own policy exactly
as a role reading through a per-table `read` grant. Confining a broadly-entitled role therefore takes a
policy on that role; one on a narrower role the same caller also carries does not reach it.

The user read surfaces are structured query execution, SQL execution, both CSV exports, the Flight SQL
surface, the totals a response reports, and every composite shape of those — joins, subqueries, derived
tables and set operations. A confined caller SHALL observe filtered rows as absent: counts, aggregates
and distinct values SHALL agree with the filtered rows, and no response SHALL indicate that rows were
withheld.

A policy SHALL narrow the rows **of the table it is stored on**. Within a flattened entity the source's policies SHALL
decide the rows, and each enrichment's values SHALL be returned for exactly the source rows returned, since an
enrichment accepts no policy of its own; a policy an earlier build left on an enrichment can only withhold that
enrichment's values on some of those rows (see "An enrichment's read-side access is its source's").

#### Scenario: A global reader is confined by its role's policy

- **GIVEN** provider role `X` mapped to `READ_ONLY_ANALYTIC`, and a policy for `X` on `dial_usage_log`
  admitting project `X-PRJ-1` only
- **WHEN** a caller carrying `X` queries `dial_usage_log` through the structured query surface, the SQL
  surface, a CSV export or Flight SQL
- **THEN** every response contains only rows of `X-PRJ-1`, and a count reports their number alone

#### Scenario: One policy for everyone, every row for an unpolicied reader

- **GIVEN** a table whose `read` list is `["*"]`, a policy for `*` of `user_hash = caller.user_hash`, and
  provider role `A` mapped to a global read with no policy on the table
- **WHEN** a caller carrying no role named on the table, and a caller carrying `A`, each query the table
- **THEN** the first sees only the rows whose `user_hash` is its own, and the second sees every row

#### Scenario: A role policy adds to the policy for everyone

- **GIVEN** the same table, and a policy for role `X` admitting project `X-PRJ-1`
- **WHEN** a caller carrying `X` queries the table
- **THEN** it sees its own rows and the rows of `X-PRJ-1`

#### Scenario: Two roles' policies are combined

- **GIVEN** policies for `X` admitting `X-PRJ-1` and for `Y` admitting `Y-PRJ-7`
- **WHEN** a caller carrying both roles queries the table
- **THEN** it sees the rows of both projects

#### Scenario: A reading role without a policy lifts the confinement

- **GIVEN** a policy for `X` admitting `X-PRJ-1`, and role `Y`, mapped to `TABLE_READ` globally, with no
  policy on the table
- **WHEN** a caller carrying both `X` and `Y` queries the table
- **THEN** it sees every row
- **AND WHEN** a policy for `Y` admitting `Y-PRJ-7` is stored
- **THEN** the same caller sees the rows of `X-PRJ-1` and `Y-PRJ-7` only

#### Scenario: A role that cannot read the table does not lift the confinement

- **GIVEN** a policy for `X` admitting `X-PRJ-1`, and role `G` with no policy and no read on the table — an
  unrelated IdP group
- **WHEN** a caller carrying both `X` and `G` queries the table
- **THEN** it sees only rows of `X-PRJ-1`

#### Scenario: A caller none of whose roles has a policy is unconfined

- **WHEN** a caller holding `TABLE_READ`, none of whose roles has a policy on the table, queries it
- **THEN** it sees every row

#### Scenario: A policy does not grant a read

- **GIVEN** a policy for role `Z` on a table, and no global or per-table read privilege for `Z`
- **WHEN** a caller carrying only `Z` queries the table
- **THEN** the query fails as though the table did not exist

#### Scenario: Every composite reference is filtered

- **GIVEN** a caller confined to `X-PRJ-1` on a table
- **WHEN** it submits a SQL query that reads the table in a join, an `IN` subquery and both arms of a
  `UNION ALL`
- **THEN** every reference contributes only rows of `X-PRJ-1`

#### Scenario: Enrichment values follow the source's rows

- **GIVEN** a caller confined by a policy on a source to project `X-PRJ-1`, and an enrichment of it holding a value for
  source rows of several projects
- **WHEN** it queries the flattened entity, naming the enrichment's column
- **THEN** the returned rows are the source rows of `X-PRJ-1`, each with its enrichment value, and a count or aggregate
  over the enrichment's column agrees with those rows alone

#### Scenario: A superseded row version is not resurrected

- **GIVEN** a deduplicated table on which a row's newer version no longer satisfies the caller's policy
  while its superseded version did
- **WHEN** the caller queries the table
- **THEN** the row is absent, because the policy is applied to the latest version only

### Requirement: Per-table authorization is inert in none mode

In `none` security mode the per-table authorization checks SHALL NOT be enforced; all per-table operations remain
open, reads included. Every non-`system` table SHALL be visible, gettable, readable and writable, every `system`
table visible, gettable and readable, and sensitive columns SHALL be served, exactly as before this capability gained
read-side keys. The mode SHALL be resolved from the single component
specified in `authentication`, so this behaviour cannot diverge between the HTTP and Flight surfaces.

Rules that constrain what state may be stored, rather than who may act, SHALL still apply in `none` mode, because a
document written while security was off goes live the moment a deployment switches to `oidc`. They include the refusal
of a read-side grant or row policy on an enrichment (see "An enrichment's read-side access is its source's"), the
validation of row policies (see "Row policies are inert in none mode"), and the grant-list validation of the
grant-management API (`table_is_system`, blank role names, `*` in `write` or `modify`).

#### Scenario: none mode does not evaluate access

- **WHEN** the service runs in `none` mode
- **THEN** per-table operations are reachable without any grant check, and no table is hidden from a
  listing, discovery or query

#### Scenario: none mode still refuses a read grant or a row policy on an enrichment

- **WHEN** the service runs in `none` mode and a `PUT .../access` carries a non-empty `read` list for an enrichment,
  or a `PUT .../row-policies/{role}` targets an enrichment
- **THEN** each request is refused `409 enrichment_follows_source`

## MODIFIED Requirements

### Requirement: Catalog import writes no grants

A catalog import SHALL write no grant list on any table: a created table starts with empty `access` lists and a `system` table's
lists are never touched, as defined by [catalog-export-import](../catalog-export-import/spec.md) ("Import order and no grants").
Grants are each environment's own policy, so granting access on an imported table stays an operator action through
`PUT /v1/tables/{name}/access`, on the table itself for a source and on its source for an enrichment, whose read-side
access is its source's.

#### Scenario: Imported table starts ungranted

- **WHEN** a table that has grants on the source environment is exported and imported
- **THEN** the target's table has five empty grant lists, and the target's `dial_usage_log` grant lists are unchanged

### Requirement: Per-table privilege grants

The system SHALL store, for each catalog table, a grant list co-located on the table definition with
one set of role names per grantable privilege: `get`, `read`, `sensitive`, `write` and
`modify`. Each key SHALL be the privilege name minus its `TABLE_` prefix, lowercased. A `get` role
authorizes seeing the table and its non-sensitive column inventory; `read` its non-sensitive rows;
`write` row inserts; and `modify` schema changes (PATCH) and description updates (PUT) on its
non-sensitive columns. `sensitive` is the clearance (see `authentication`). It authorizes nothing on
its own, and it extends whichever of `get`, `read` and `modify` the same role holds on that table,
globally or by grant, to the table's sensitive columns and to the `sensitive` flag. There SHALL be no
`read_sensitive` key: a request carrying it SHALL be refused `400` naming the field.

An enrichment SHALL accept no `get`, `read` or `sensitive` grant: its read-side access is its source's (see "An
enrichment's read-side access is its source's"), and only its `write` and `modify` lists confer anything on it.

There SHALL be **no `list` key**. `TABLE_LIST` is a global privilege — held, it lists every table — and
on a single table it SHALL be conferred only by implication, by any key that already shows the table.
A per-table `list` would grant a table's name without its definition, and the definition discloses
nothing the name does not: column names are not a secret, only values are (a sensitive column is hidden
from a caller without the clearance for a consistent view, not to keep its name secret). A request carrying `list`
SHALL be refused `400` like any unknown member, never silently dropped.

`get` and `read` SHALL form a ladder in which a grant at one rung confers every rung below it,
down to listing. `modify` SHALL additionally confer `get`. `write` and `sensitive` SHALL each confer
nothing (see `authentication`).
Implication SHALL be derived when a check runs and SHALL NOT be written into the stored lists: a read
of the lists SHALL return exactly the grantees that were stored.

An **empty** list SHALL grant nothing, for every key uniformly: only a caller holding the
corresponding privilege globally is authorized. Role names are raw provider role names (the same
names used as identity-provider `roles-mapping` keys), matched against the caller's provider roles;
a bundle or privilege name SHALL NOT be a valid grantee.

The grantee `*` SHALL stand for **every authenticated caller**, whatever its provider roles and including a
caller that carries none: a list naming `*` grants its privilege to all of them. `*` SHALL be accepted in
the read-side lists (`get`, `read`, `sensitive`) and refused with HTTP 422 in `write` and `modify`; a `*`
already stored in `write` or `modify` (written while `*` was an ordinary grantee name) SHALL confer
nothing, so evaluation fails closed without a data migration, and SHALL NOT make a replacement that omits
that list fail. It SHALL
behave as a provider role every such caller carries, in grant evaluation, in permission reporting, in
row-policy evaluation and in the ineffective-grant report alike. The
system identity that background work runs under SHALL NOT carry it. A
mapping in an identity provider's `roles-mapping` is unaffected: `*` confers no global privilege. At
startup the system SHALL log every table that names `*` in a grant list or as a row-policy role, and where,
so a `*` stored while it was an ordinary grantee name is visible to the operator. An enrichment's leftover `get`,
`read` or `sensitive` list SHALL NOT be reported, because it confers nothing.

Grant lists on a `system` table SHALL be restricted by **key**, not refused wholesale: the read-side
keys (`get`, `read`, `sensitive`) SHALL be storable on a `system` table like any other — except on a `system`
enrichment, which, like every enrichment, accepts none —
while `write` and `modify` SHALL remain empty, because a `system` table is materialized and fed out
of band and every modifying operation on it is refused regardless of any list. Deleting a table SHALL
remove its grant lists.

#### Scenario: A read grant for everyone

- **GIVEN** a table whose `read` list is `["*"]`
- **WHEN** an authenticated caller none of whose provider roles is named in any list reads the table
- **THEN** the read is authorized, and the table's reported permissions show `read: true` for it

#### Scenario: A read grant for everyone on a table with role policies is reported

- **GIVEN** a table with a row policy for role `X` and none for `*`
- **WHEN** a grant replacement adds `*` to `read`
- **THEN** it is accepted, `ineffective_grants` carries an entry for `*` with reason `lifts_row_policies`,
  and a caller carrying `X` reads every row
- **AND** once a row policy is stored for `*`, the same replacement reports nothing and that caller reads
  the rows of either policy

#### Scenario: The startup report skips an enrichment's leftover read-side lists

- **GIVEN** an enrichment that still stores `*` in its `read` list and as a row-policy role, written by an earlier build
- **WHEN** the service starts
- **THEN** the startup log names the enrichment for its row policy only

#### Scenario: Everyone cannot be granted a write

- **WHEN** a grant replacement names `*` in `write` or `modify`
- **THEN** it is refused with HTTP 422 and the stored lists are unchanged

#### Scenario: A stored everyone write grant confers nothing

- **GIVEN** a table whose stored `write` list is `["*"]`
- **WHEN** an authenticated caller none of whose provider roles is named in any list writes to the table
- **THEN** the write is refused, and the table's reported permissions show `write: false` for it

#### Scenario: A stored everyone write grant does not block other edits

- **GIVEN** a table whose stored `write` list is `["*"]`
- **WHEN** a grant replacement sends only `read`
- **THEN** it is accepted and the stored `write` list is kept
- **AND** a replacement that sends `write` naming `*` is still refused with HTTP 422

#### Scenario: Grant lists persist per table

- **WHEN** an admin sets a table's `write` roles to `["analytics-writer"]`
- **THEN** reading the table's access returns `write = ["analytics-writer"]` and every other key
  empty

#### Scenario: Deleting a table removes its grants

- **WHEN** a table that has grant lists is deleted from the catalog
- **THEN** the grant lists are removed with it

#### Scenario: A system table carries read grants and no write grants

- **WHEN** a caller holding `TABLE_GRANT` reads the access of a `system` table (e.g. the seeded
  `dial_usage_log`) after
  its `read` list has been set to `["analytics-viewers"]`
- **THEN** the response is `200` with `read = ["analytics-viewers"]`, `write = []` and `modify = []`

#### Scenario: A stored list is not expanded by implication

- **WHEN** a table's `read` list names a role and its `get` list is empty
- **THEN** reading the access returns that role under `read` only, while the role's holder can
  nevertheless list the table and read its schema

#### Scenario: A get grant lists the table

- **GIVEN** a caller with no global privilege whose provider role is in a table's `get` list
- **WHEN** it lists the catalog
- **THEN** that table appears in the listing

#### Scenario: A list key is refused

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"list":["analytics-viewers"]}` for a table
- **THEN** the response is `400` and no list is changed

#### Scenario: The removed read_sensitive key is refused

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"read_sensitive":["pii-readers"]}` for a table
- **THEN** the response is `400` naming `read_sensitive`, and no list is changed

#### Scenario: A table is fully delegated through grants

- **GIVEN** a non-system table whose `read`, `modify` and `sensitive` lists contain `finance-owners`
- **WHEN** a caller carrying that role and no global privilege reads the table, queries its sensitive
  columns, and patches a column's `sensitive` flag
- **THEN** every operation succeeds

#### Scenario: The clearance without a verb grants nothing

- **GIVEN** a table whose `sensitive` list contains `pii-analysts` and whose other lists do not
- **WHEN** a caller carrying only that role lists the catalog or queries the table
- **THEN** the table is absent from the listing and the query fails as though the table did not exist

#### Scenario: An enrichment stores only write and modify

- **WHEN** a caller holding `TABLE_GRANT` reads the access of an enrichment created through the API
- **THEN** the response is `200` with `get`, `read` and `sensitive` empty, and `write` and `modify` as stored

### Requirement: Grant-management API

The system SHALL expose an API to read and update a table's grant lists at
`/v1/tables/{name}/access`. `GET` SHALL return all five lists and SHALL require `TABLE_GRANT`,
because the lists disclose which provider roles exist and what they may do; a caller without
`TABLE_GRANT` SHALL receive `403 forbidden` on the read.

`PUT` SHALL require `TABLE_GRANT` and SHALL be a **merge**, not a full replace: a key present in the
request body SHALL replace that list, an **absent** key SHALL leave the stored list unchanged, and an
explicit empty array SHALL revoke the list. Role names SHALL be trimmed and de-duplicated. The
response SHALL carry the stored lists **and** the caller-effective permission flags they produce, so
an operator can see immediately when a grant has no effect.

`PUT` SHALL additionally report, without failing, each listed role that already holds the
corresponding privilege globally, and each `sensitive` grant to a role that holds none of `TABLE_GET`, `TABLE_READ`
or `TABLE_MODIFY` on the table, globally or by any list, nor `modify` on any enrichment of it. Both are grants that
change nothing; a source's clearance does change something for a role holding an enrichment's `modify`, because it
extends that `modify` to the enrichment's sensitive columns. It SHALL report in the
same array, with reason `lifts_row_policies`, a `read` grant to `*` on a table that stores row policies and none for
`*`: every caller then carries a role that reads with no policy, so by the evaluation rule those policies confine no
one. The grant SHALL still be accepted.

The response SHALL carry the five lists at the **top level**, exactly where `GET` carries them, so the
two share one shape and a client reading `write` keeps working. It SHALL additionally carry
`permissions` (the five effective flags) and `ineffective_grants`, an array whose entries carry the
`role`, the grant `key`, a stable `reason` code — `already_global`, `sensitive_without_verb` or `lifts_row_policies`
— and a human-readable `message`. Both members are additive; nothing moves.

A `PUT` against a `system` table other than an enrichment SHALL be accepted for the read-side keys. A `PUT` against
any `system` table SHALL be refused with `409` and machine code `table_is_system` only when it carries a **non-empty**
`write` or `modify` list; an empty `write` or `modify` SHALL be accepted, so a five-key body succeeds.

A `PUT` against an enrichment SHALL be refused with `409` and machine code `enrichment_follows_source` when it carries a
`get`, `read` or `sensitive` list that is **non-empty as sent**, before role names are trimmed — so `{"read":[" "]}` is
`409`, not `422` — naming the enrichment's source as the table to grant instead, and no
list SHALL change. An empty `get`, `read` or `sensitive` SHALL be accepted, so a body echoed from `GET` succeeds for an
enrichment that stores no leftover read-side list; one that does is cleared by sending those lists empty.

The checks SHALL run in this order, the first failing one deciding the response: a body that does not bind (`400`); a
caller without `TABLE_GRANT` (`403 forbidden`); an unknown table (`404`); a non-empty read-side list on an enrichment (`409 enrichment_follows_source`); then, list by
list in the order `get`, `read`, `sensitive`, `write`, `modify`: a blank role name (`422 validation_error`), the
`table_is_system` refusal of a non-empty `write` or `modify`, and `*` in `write` or `modify` (`422`). So a
body carrying both a non-empty `read` and a non-empty `write` for the `system` enrichment `dial_usage_log_payload`
answers `409 enrichment_follows_source`.

A request against a non-existent table SHALL return `404 not_found`; a request with a blank role name SHALL return
`422 validation_error`.

#### Scenario: A grant admin reads and updates grants

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s
  `{"write":["analytics-writer","analytics-writer"]}` for a table
- **THEN** the response is `200` with `write = ["analytics-writer"]` (de-duplicated) and every other
  list unchanged

#### Scenario: An absent key is left alone

- **GIVEN** a table whose `read` list is `["analytics-viewers"]`
- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"write":["ingest-bot"]}`
- **THEN** the response is `200`, `write = ["ingest-bot"]`, and `read` is still
  `["analytics-viewers"]`

#### Scenario: An explicit empty array revokes

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"read":[]}` for a table whose `read` list was
  non-empty
- **THEN** the response is `200` and `read = []`

#### Scenario: A caller without TABLE_GRANT cannot read grants

- **WHEN** a caller holding `TABLE_READ` but not `TABLE_GRANT` `GET`s a table's access
- **THEN** the response is `403 forbidden`

#### Scenario: Grants on an unknown table are not found

- **WHEN** a caller holding `TABLE_GRANT` requests access for a table that does not exist
- **THEN** the response is `404 not_found`

#### Scenario: Read grants on a system table are accepted

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s
  `{"read":["analytics-viewers"],"write":[],"modify":[]}` for a `system` table that is not an enrichment, such as
  `dial_usage_log`
- **THEN** the response is `200` and the `read` list is stored

#### Scenario: A write grant on a system table is refused

- **WHEN** the same caller `PUT`s `{"write":["ingest-bot"]}` for a `system` table
- **THEN** the response is `409` with machine code `table_is_system` and no list is changed

#### Scenario: A blank role is reported before a system table's write refusal

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"get":[" "],"write":["ingest-bot"]}` for `dial_usage_log`
- **THEN** the response is `422 validation_error` and no list is changed

#### Scenario: A read grant on an enrichment is refused

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"read":["analytics-viewers"]}`, or a non-empty `get` or
  `sensitive`, for an enrichment
- **THEN** the response is `409` with machine code `enrichment_follows_source`, the message names the enrichment's
  source, and no list is changed

#### Scenario: A blank read-side role on an enrichment counts as non-empty

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"read":[" "]}` for an enrichment
- **THEN** the response is `409` with machine code `enrichment_follows_source`, not `422`, and no list is changed

#### Scenario: The enrichment refusal comes first

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"read":["analytics-viewers"],"write":["ingest-bot"]}` for the
  `system` enrichment `dial_usage_log_payload`, or `{"read":["analytics-viewers"],"modify":[" "]}` for any enrichment
- **THEN** the response is `409` with machine code `enrichment_follows_source`, and no list is changed

#### Scenario: A source clearance used by an enrichment maintainer is not reported

- **GIVEN** a role holding nothing on a source and `modify` on one of its enrichments
- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"sensitive":["<that role>"]}` for the source
- **THEN** the response is `200` and `ineffective_grants` carries no `sensitive_without_verb` entry for the role

#### Scenario: An enrichment's write and modify are still granted

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"get":[],"read":[],"sensitive":[],"write":["labeler"],"modify":[]}`
  for an enrichment
- **THEN** the response is `200` and `write = ["labeler"]`

#### Scenario: An ineffective grant is reported

- **WHEN** a caller holding `TABLE_GRANT` grants `read` to a role that already holds `TABLE_READ`
  globally
- **THEN** the response is `200` and reports that the grant changes nothing for that role

#### Scenario: A clearance without a verb is reported
- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"sensitive":["pii-analysts"]}` for a table on which
  `pii-analysts` holds no `get`, `read` or `modify`, globally or by any list
- **THEN** the response is `200`, the list is stored, and `ineffective_grants` carries an entry with
  `role: "pii-analysts"`, `key: "sensitive"` and `reason: "sensitive_without_verb"`

### Requirement: Per-table privilege evaluation

For every operation whose required privilege is table-scoped, a caller SHALL be authorized when it
holds that privilege **globally**, or when one of its raw provider roles appears in the table's grant
list for that privilege or for any list that confers it by implication. Otherwise the request SHALL
be denied. There SHALL be no separate bypass for any role name: a caller holding every privilege is
authorized because it holds them globally, and the evaluator SHALL contain no check on a role or
bundle name.

For an enrichment, "the table's grant list" SHALL be its source's for `TABLE_GET`, `TABLE_READ` and
`TABLE_SENSITIVE`, and its own for `TABLE_WRITE` and `TABLE_MODIFY`; `TABLE_GET` SHALL also be conferred by the
enrichment's own `modify` list (see "An enrichment's read-side access is its source's").

A table with entirely empty grant lists SHALL deny every caller that does not hold the privilege
globally, for read as well as for write.

**Because effective access is a union, narrowing a team's access takes two steps and the first is a
`roles-mapping` edit, not a grant.** A provider role mapped to `READ_ONLY_ANALYTIC` keeps reading every
table however the grants are set, so an operator SHALL remap that role to a narrower privilege list — or
to nothing at all — and only then grant it the tables it should see. An identity-provider change is
needed only where one provider role has to be split into global and restricted readers. A grant made
before the remap SHALL be reported as ineffective rather than accepted silently (see
**Caller-effective privilege reporting**), so the order is recoverable rather than silently wrong.

#### Scenario: A grant does not narrow a globally-entitled role

- **GIVEN** a provider role mapped to `READ_ONLY_ANALYTIC`
- **WHEN** an operator grants it `read` on one table, intending to restrict it to that table
- **THEN** the role still reads every table, and the response reports the grant as ineffective with
  reason `already_global`

Refusal SHALL follow the non-disclosure rule in `authentication`: a missing read privilege reports the
table as absent, a missing write privilege is `403 forbidden`.

#### Scenario: Matching write grant is allowed to write only

- **GIVEN** a table whose `write` list contains role `analytics-writer`
- **WHEN** a caller carrying provider role `analytics-writer` inserts rows
- **THEN** the insert succeeds; and a description update (PUT) by the same caller is `403 forbidden`

#### Scenario: A global privilege holder is authorized without a list entry

- **WHEN** a caller holding `TABLE_WRITE` globally writes to a table with empty grant lists
- **THEN** the operation is allowed, decided by the privilege rather than by a role-name check

#### Scenario: Write-only caller can write but not read

- **GIVEN** a caller with no global privilege whose provider role is in a table's `write` list
- **WHEN** it inserts rows into that table
- **THEN** the insert succeeds
- **AND WHEN** it lists the catalog
- **THEN** that table is absent from the listing

#### Scenario: A read grant authorizes a caller with no global privilege

- **GIVEN** a caller with no global privilege whose provider role is in a table's `read` list
- **WHEN** it queries that table
- **THEN** the query resolves and executes, and the table appears in its catalog listing and
  discovery

#### Scenario: An enrichment's write grant does not confer its source's read

- **GIVEN** a caller with no global privilege whose provider role is in an enrichment's `write` list and in no list of
  its source
- **WHEN** it lists the catalog and queries the source's entity
- **THEN** neither the source nor the enrichment is listed, and the query reports an unknown entity

### Requirement: Caller-effective privilege reporting

The system SHALL report, on every table read surface (`GET /v1/tables` list items and
`GET /v1/tables/{name}`), the calling identity's effective per-table permissions as a `permissions`
object with boolean `get`, `read`, `sensitive`, `write`, `modify` and `rows_restricted` fields. There
SHALL be no `list` flag: a caller that sees a table at all may list it, so the flag could only ever read
`true`. The reported flags SHALL agree with what per-table evaluation, row-policy evaluation and
system-table protection would decide for that caller, **after** implication is applied:

- a `system` table SHALL report `write=false, modify=false` for every caller, including one holding
  every privilege (all modifying operations are refused on system tables), while its read-side flags
  SHALL be evaluated from the grant lists like any other table's;
- an enrichment SHALL report its source's `read` and `sensitive`, a `rows_restricted` that is `true` when the caller
  is confined on the source or by a policy an earlier build left on the enrichment, and a `get` that is its source's
  or implied by its own `modify`; its `write` and `modify` SHALL be evaluated from its own lists;
- otherwise each of `get`, `read`, `sensitive`, `write` and `modify` SHALL be `true` if the caller holds
  the corresponding privilege globally, or if one of its raw provider roles appears in a list that
  grants or implies it;
- `rows_restricted` SHALL be `true` exactly when row-policy evaluation confines the caller on the table (see
  "Row-policy evaluation by caller role", and the enrichment bullet above), on `system` and non-`system` tables alike;
- in `none` security mode every flag but `rows_restricted` SHALL be `true` for non-`system` tables, the
  read-side flags SHALL be `true` for `system` tables, and `rows_restricted` SHALL be `false` (no
  authorization is enforced).

`sensitive` SHALL report whether the caller holds the clearance on the table, not whether it can read
sensitive values. It counts among the read-side flags. A consumer SHALL derive "reads sensitive values"
as `read && sensitive`, "sees sensitive columns" as `get && sensitive`, and "may change them or the
flag" as `modify && sensitive`. `rows_restricted` SHALL NOT disclose the policy itself; the filter is
readable only through the row-policy API.

The reporting SHALL share its role-matching, implication and row-policy evaluation with the evaluation
used for enforcement so the two cannot diverge. Reporting SHALL NOT change enforcement behaviour.

#### Scenario: Matching write grant is reported

- **GIVEN** a table whose `write` list contains provider role `analytics-writer` and whose other
  lists are empty
- **WHEN** a caller carrying provider role `analytics-writer` and no global privilege reads the table
  via `GET /v1/tables/{name}`
- **THEN** the table carries `permissions.write = true` and `permissions.modify = false`

#### Scenario: Implication is reflected in the reported flags

- **GIVEN** a table whose `read` list contains provider role `analytics-viewers` and whose `get`
  list is empty
- **WHEN** a caller carrying that role reads the table
- **THEN** the table carries `permissions.read = true` and `permissions.get = true`, and
  `permissions.sensitive = false`

#### Scenario: The clearance is reported on its own flag

- **GIVEN** a table whose `modify` and `sensitive` lists contain provider role `finance-owners` and whose
  other lists are empty
- **WHEN** a caller carrying that role and no global privilege reads the table
- **THEN** the table carries `permissions.get = true`, `permissions.modify = true`,
  `permissions.sensitive = true` and `permissions.read = false`

#### Scenario: A full-privilege caller sees full permissions on a non-system table

- **WHEN** a caller holding the `FULL_ADMIN` bundle, none of whose roles has a row policy on the table,
  reads a non-`system` table with empty grant lists
- **THEN** every flag in `permissions` except `rows_restricted` is `true`, and `rows_restricted` is
  `false`

#### Scenario: No matching grant is reported as no permissions

- **WHEN** a caller with no global privilege, whose provider roles appear in none of a table's grant
  lists and none of its row policies, reads that table through a surface it can reach
- **THEN** every flag in `permissions` is `false`

#### Scenario: A system table reports no write permissions but honours read grants

- **WHEN** a caller holding the `FULL_ADMIN` bundle reads a `system` table (e.g. the seeded
  `dial_usage_log`)
- **THEN** the table carries `permissions.write = false` and `permissions.modify = false`, and
  `permissions.read = true`

#### Scenario: A confined caller is reported as row-restricted

- **GIVEN** a policy for role `X` on a table admitting one project
- **WHEN** a caller carrying `X` and holding `TABLE_READ` reads the table's definition
- **THEN** the table carries `permissions.read = true` and `permissions.rows_restricted = true`
- **AND WHEN** the caller also carries another role that may read the table and has no policy on it
- **THEN** the table carries `permissions.rows_restricted = false`

#### Scenario: An enrichment reports its source's read-side flags

- **GIVEN** a source whose `read` list and a row policy both name role `X`, and an enrichment of it whose `write` list
  names `X`
- **WHEN** a caller carrying `X` and no global privilege reads the enrichment's definition
- **THEN** the enrichment carries `permissions.read = true`, `permissions.rows_restricted = true`,
  `permissions.write = true` and `permissions.modify = false`

#### Scenario: A policy left on an enrichment is reported as row-restricted

- **GIVEN** a source whose `read` list names role `X` and which has no row policy, and an enrichment of it that still
  stores a row policy for `X`, written by an earlier build
- **WHEN** a caller carrying only `X` and no global privilege reads the enrichment's definition
- **THEN** the enrichment carries `permissions.read = true` and `permissions.rows_restricted = true`

#### Scenario: An enrichment whose source is missing grants no read

- **GIVEN** a revision snapshot of an enrichment whose source table no longer exists
- **WHEN** a caller holding `TABLE_GET` globally and no other global privilege reads it
- **THEN** the read answers `200`, not an error, and the snapshot carries `permissions.get = true`,
  `permissions.read = false` and `permissions.sensitive = false`

#### Scenario: none mode reports full permissions

- **WHEN** the service runs in `none` security mode and any caller reads a non-`system` table
- **THEN** every flag in `permissions` except `rows_restricted` is `true`, and `rows_restricted` is
  `false`

### Requirement: Per-role row policies

The system SHALL accept and store, for each catalog table other than an enrichment, a set of **row policies** co-located on the
table definition, at most one per raw provider role. A policy SHALL be `{"filter": <filter node>}` — a predicate
in the structured query's filter grammar (see [structured-query](../structured-query/spec.md), "Filter
sub-grammar (CQL2-JSON)" and "Expression grammar") over that table's own columns. An enrichment SHALL accept none: the
rows a caller reads through it are its source's (see "An enrichment's read-side access is its source's").

There SHALL be no unrestricted policy shape: a role that may read the table and has no policy there already
reads every row (see "Row-policy evaluation by caller role"). A body without a `filter` SHALL be refused
`422 validation_error`; a member other than `filter`, like any body that does not bind to the request type,
SHALL be refused by the strict request mapper as `400 bad_request` (see "Row-policy management API").

A row policy SHALL **narrow** a read and SHALL NOT **grant** one. Whether a caller may read a table at
all SHALL remain decided by its global privileges and the table's grant lists, unchanged; a policy on a
role that has no read path to the table SHALL confer nothing.

A role name SHALL follow the grantee rules of the grant lists: a raw provider role name, trimmed,
non-blank, and not a bundle or privilege name. The grantee `*` SHALL be a valid policy subject, with the
meaning it has in a grant list: its policy is one every authenticated caller carries. Row policies SHALL be storable on
`system` tables other than enrichments, which carry the shared data a policy most often narrows. Deleting a table SHALL remove its row policies.

The filter SHALL be validated against the table's **own** stored columns — every one of them, sensitive
columns included, independent of what the writing caller may see — and SHALL be refused when it:

- references a column the table does not store, including a dotted enrichment field (a policy narrows
  the table it is stored on);
- uses a `param` expression other than a caller value (see "Caller values in row policies");
- calls a function outside the function catalog's scalar group, or one the catalog declares as calling a
  service outside the data store;
- fails any validation the structured query surface would apply to the same filter; or
- renders to a predicate longer than 16 KiB.

A filter MAY call the relative time functions (`now`, `date_add`, `date_sub`): a policy is evaluated on
a read, where a moving window is meaningful, never on a derivation path.

A stored filter SHALL be returned in the structured query's stable wire serialization, so a policy read
back is a filter the query surface accepts.

#### Scenario: A policy narrows a role to one project

- **WHEN** a caller holding `TABLE_GRANT` stores for role `X` on `dial_usage_log` the policy
  `{"filter": {"op": "in", "args": [{"type": "field", "name": "project_id"}, {"type": "array", "items": [{"type": "value", "value_type": "string", "value": "X-PRJ-1"}]}]}}`
- **THEN** the policy is stored and reading the table's row policies returns it under `X`

#### Scenario: A policy names a column the table does not store

- **WHEN** a policy's filter references a field the table does not store, or a dotted enrichment field
- **THEN** the request is refused `422 validation_error` naming the field, and nothing is stored

#### Scenario: A policy filter may not take a parameter or aggregate

- **WHEN** a policy's filter contains a `param` expression that is not a caller value, an aggregate
  function, or a window expression
- **THEN** the request is refused `422 validation_error`, and nothing is stored

#### Scenario: A policy without a filter is refused

- **WHEN** a policy body carries no `filter`
- **THEN** the request is refused `422 validation_error`

#### Scenario: An unknown member does not bind

- **WHEN** a policy body carries a member other than `filter`, e.g. `{"where": {}}` or `{"all_rows": true}`
- **THEN** the request is refused `400 bad_request`, and nothing is stored

#### Scenario: A sensitive column may constrain a policy

- **WHEN** a caller holding `TABLE_GRANT` but no clearance stores a policy filtering on a sensitive
  column of the table
- **THEN** the policy is accepted, because it is validated against the table's stored columns rather
  than the caller's view

#### Scenario: A relative time window is a valid policy

- **WHEN** a policy's filter is `request_time >= date_sub('day', 90, now())`
- **THEN** the policy is accepted

### Requirement: Row-policy management API

The system SHALL expose the row policies of a table at `/v1/tables/{name}/row-policies`, every method
requiring `TABLE_GRANT`, because a policy discloses which provider roles exist and what each may see:

- `GET /v1/tables/{name}/row-policies` SHALL return `{"policies": {"<role>": <policy>, ...}}`, an empty
  object when the table has none.
- `PUT /v1/tables/{name}/row-policies/{role}` SHALL create or replace that role's policy and return it.
  It is idempotent: repeating it changes nothing, and records no audit revision.
- `DELETE /v1/tables/{name}/row-policies/{role}` SHALL remove that role's policy and answer `204`.

A caller without `TABLE_GRANT` SHALL receive `403 forbidden`. An unknown table SHALL be `404 not_found`,
and so SHALL a `DELETE` naming a role that has no policy on the table. A `PUT` naming an enrichment SHALL be refused
`409 enrichment_follows_source`, naming the enrichment's source as the table to write the policy on, and nothing SHALL
be stored. `GET` and `DELETE` naming an enrichment SHALL behave as on any table, so a policy an earlier build stored
there can be read and removed. For a `PUT` the checks SHALL run in this order, on every table: a body that does not
bind (`400`); a caller without `TABLE_GRANT` (`403 forbidden`); an unknown table (`404`); an enrichment (`409 enrichment_follows_source`); then the remaining checks,
each `422 validation_error` and in no specified order among themselves: a body carrying both or neither of `filter`
and `where`, the translation of a `where`, the role, and the policy's own validation. So a `where` on an
enrichment is refused `409` before it is translated, a body that would otherwise fail validation likewise, and a body
carrying both forms for a table that does not exist answers `404`. A body that does not bind to the
request type — malformed JSON, or a member other than `filter` and `where` — SHALL be `400 bad_request`, as
on every endpoint behind the strict request mapper. A
body that binds but whose shape or meaning is invalid, and an invalid role, SHALL be `422 validation_error`
naming the offending member. Every change SHALL be recorded in the audit trail as
a change to the table definition, readable as `row_policies` on the table's revision snapshot by a caller
holding `TABLE_GRANT` (see [audit-trail](../audit-trail/spec.md), "Historical table snapshots").

#### Scenario: Create, read and remove a policy

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s a policy for role `X`, then `GET`s the table's row
  policies, then `DELETE`s `X`'s policy
- **THEN** the `PUT` returns `200` with the stored policy, the `GET` lists it under `X`, the `DELETE`
  answers `204`, and a further `GET` returns `{"policies": {}}`

#### Scenario: A caller without TABLE_GRANT cannot read policies

- **WHEN** a caller holding `TABLE_READ` but not `TABLE_GRANT` `GET`s a table's row policies
- **THEN** the response is `403 forbidden`

#### Scenario: Removing an absent policy is not found

- **WHEN** a caller holding `TABLE_GRANT` `DELETE`s the policy of a role that has none on the table
- **THEN** the response is `404 not_found`

#### Scenario: Repeating a PUT records no revision

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s the same policy for role `X` twice
- **THEN** both responses are `200` with the stored policy, and only the first records an `Update`
  activity for the table

#### Scenario: A bundle name is not a policy role

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s a policy for role `READ_ONLY_ANALYTIC`
- **THEN** the request is refused `422 validation_error`

#### Scenario: A policy on an enrichment is refused

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s a policy, written as `filter` or as `where`, for any role on an
  enrichment
- **THEN** the response is `409` with machine code `enrichment_follows_source`, the message names the enrichment's
  source, and nothing is stored

#### Scenario: The enrichment refusal precedes the policy's own validation

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s, on an enrichment, a policy for role `READ_ONLY_ANALYTIC`, a body
  carrying both `filter` and `where`, or a `where` naming a column the enrichment does not store
- **THEN** each response is `409` with machine code `enrichment_follows_source`
- **AND WHEN** it `DELETE`s the policy of a role that has none on the enrichment
- **THEN** the response is `404 not_found`

#### Scenario: An unknown table is reported before an invalid body

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s a body carrying both `filter` and `where` for a table that does not
  exist
- **THEN** the response is `404 not_found`

### Requirement: Row policies do not propagate to derived tables

A policy SHALL govern only the table it is stored on. An aggregate target — a source populated under the system
identity from unfiltered reads of another table — SHALL be governed only by its **own** policies and grant lists. The
system SHALL NOT infer, copy or require a policy on an aggregate target from its input's. An enrichment is not such a
table: it is read only through its source, whose policies decide its rows (see "An enrichment's read-side access is its
source's"). The values a pipeline derives into an enrichment from **another** table, or from **other
source rows** — a group-trigger pipeline, or any enrichment whose grain is coarser than one source row — are not
narrowed by the policies that hide those rows: a caller confined on the source reads, on each row it may read, a value
that may summarise rows it may not. This is the same accepted risk as for aggregate targets. The remedy is a
policy on the source that confines the whole group, or keeping such a value off the enrichment.

This is an accepted risk, not an oversight: a role confined on a source and able to read an aggregate of
it reads that aggregate unconfined unless the aggregate carries a policy for the role. The operator
closes it by storing policies on the aggregate targets a confined role may read.

#### Scenario: An aggregate of a confined source is not confined

- **GIVEN** role `X` confined to `X-PRJ-1` on a source, holding `TABLE_READ` globally, and an aggregate
  target of that source carrying no policy for `X`
- **WHEN** a caller carrying `X` queries the aggregate target
- **THEN** it sees every row of the aggregate
- **AND WHEN** a policy for `X` admitting `X-PRJ-1` is stored on the aggregate target
- **THEN** it sees only that project's aggregate rows

### Requirement: A row policy may be written as a SQL condition

`PUT /v1/tables/{name}/row-policies/{role}` SHALL accept `{"where": "<condition>"}` as an alternative to
`{"filter": <filter node>}`. The condition is the text that would follow `WHERE` in a SQL statement over
the table, in the SQL subset the ad-hoc SQL surface accepts (see
[rest-sql-endpoint](../rest-sql-endpoint/spec.md)). Exactly one of `where` and `filter` SHALL be given: a
body carrying both, or neither, SHALL be refused `422 validation_error`.

The system SHALL translate the condition into a filter node and from there treat the policy exactly as one
written as `filter`: the same validation against the table's own columns, the same refusals, the same size
limit, the same storage and the same enforcement. The condition text SHALL NOT be stored, SHALL NOT be sent
to the data store, and SHALL NOT be returned: the response and every later read of the policy carry the
stored `filter`.

The condition SHALL be resolved against every column the table stores, sensitive columns included,
whatever the writing caller may itself see or read.

A caller value SHALL be written in the condition as `caller.user_hash` or `caller.project`, exactly, and
SHALL become the `param` of that name, subject to the placement rule of "Caller values in row policies". A
string literal in the condition SHALL remain a literal whatever it spells, including the text of a caller
value's name.

A condition that does not parse, does not validate against the table, or uses a construct the SQL surface
cannot express as a single filter SHALL be refused `422 validation_error` with the reason. A condition that
holds a subquery or a sequence value, names an enrichment's column by its dotted name, or closes itself to add another clause,
SHALL be refused on its text, before any name in
it is resolved, with one message whatever it names: the refusal SHALL NOT tell whether a table or column
outside the policy's own table exists. An enrichment accepts no policy in either form (see "Row-policy management
API").

#### Scenario: A condition is stored as its filter

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"where": "project_id IN ('X-PRJ-1', 'X-PRJ-2')"}` for
  role `X`
- **THEN** the response is `200` with a `filter` equal to the one the same policy written as `filter`
  stores, and a caller carrying `X` sees only the rows of those two projects

#### Scenario: A caller value in a condition

- **WHEN** a policy is written as `{"where": "user_hash = caller.user_hash"}`
- **THEN** the stored filter compares `user_hash` with the `param` `caller.user_hash`, and each caller
  reads its own rows

#### Scenario: A literal spelling a caller value stays a literal

- **WHEN** a policy is written as `{"where": "user_hash = 'caller.user_hash'"}`
- **THEN** the stored filter compares `user_hash` with the string `caller.user_hash`, and no caller's value
  is substituted

#### Scenario: Both forms at once are refused

- **WHEN** a body carries both `where` and `filter`
- **THEN** the request is refused `422 validation_error` and no policy is stored

#### Scenario: A subquery in a condition is refused

- **WHEN** a policy is written as `{"where": "project_id IN (SELECT project_id FROM other_table)"}`
- **THEN** the request is refused `422 validation_error` and no policy is stored
- **AND** the message is the same whether or not `other_table` exists

## REMOVED Requirements

### Requirement: Row-policy evaluation

**Reason**: Replaced by "Row-policy evaluation by caller role", which carries the same evaluation rules; an enrichment no longer stores row policies, so the rule that an enrichment policy withholds only that enrichment's values is gone.

**Migration**: None for callers. A policy that was stored on an enrichment is removed by the catalog migration. A policy that blanked an enrichment's values on some rows has no equivalent afterwards. A source policy can name only the source's own columns, so a condition on an enrichment column is refused `422`, and it hides whole rows rather than the enrichment's values on them. The options left are marking the enrichment's columns `sensitive`, which hides them on every row from callers without the source's clearance, or a source policy on source columns, which hides those rows entirely.

### Requirement: Grant evaluation is inert in none mode

**Reason**: Replaced by "Per-table authorization is inert in none mode". The one rule that used to stay live in
`none` mode, the sensitive-grant subset rule on derived tables, is removed (see `enrichment-runtime`), and the
refusal of read-side grants on an enrichment takes its place as the stored-state rule that applies in both modes.

**Migration**: None.
