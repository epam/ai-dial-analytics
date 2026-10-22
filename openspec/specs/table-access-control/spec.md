# table-access-control Specification

## Purpose

This capability defines per-table access control for ADAS. Each catalog table definition carries grant
lists — one set of raw provider role names per grantable table privilege (`get`, `read`, `sensitive`,
`write`, `modify`) — which authorize a role on that table in addition to what it holds globally, and a
set of per-role **row policies** that narrow which of the table's rows a role reads, enforced by the
data store on every user read. Both are matched against the caller's provider roles, managed through
`TABLE_GRANT`-gated APIs and evaluated at request time, complementing the method-level authorization
defined by [authentication](../authentication/spec.md) and governing the per-table endpoints of
[table-management](../table-management/spec.md).

## Requirements
### Requirement: Grant evaluation is inert in none mode

In `none` security mode the per-table authorization checks SHALL NOT be enforced; all per-table
operations remain open, reads included. One rule is deliberately **not** inert: the laundering subset
rule on `sensitive` grants (see `enrichment-runtime`). It is not an authorization check — it never
asks who the caller is — but a constraint on what stored state may exist, and a grant document written
while security was off goes live the moment a deployment switches to `oidc`, when refusing it would be
too late. So a `PUT .../access` or a sensitive-flag schema change MAY be refused
`422 sensitive_grant_widens` in `none` mode.

Every other table SHALL be visible, gettable, readable and
writable, and sensitive columns SHALL be served, exactly as before this capability gained read-side
keys. The mode SHALL be resolved from the single component specified in `authentication`, so this
behaviour cannot diverge between the HTTP and Flight surfaces.

#### Scenario: none mode does not evaluate access

- **WHEN** the service runs in `none` mode
- **THEN** per-table operations are reachable without any grant check, and no table is hidden from a
  listing, discovery or query

#### Scenario: A laundering grant is refused even in none mode

- **GIVEN** the service runs in `none` mode
- **WHEN** a `PUT .../access` would give `sensitive` on an enrichment carrying a `sensitive`
  column to a role its source does not list under both `read` and `sensitive`
- **THEN** the request is refused `422 sensitive_grant_widens`, because the stored document would be
  invalid the moment the deployment switches to `oidc`

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

Grant lists on a `system` table SHALL be restricted by **key**, not refused wholesale: the read-side
keys (`get`, `read`, `sensitive`) SHALL be storable on a `system` table like any other,
while `write` and `modify` SHALL remain empty, because a `system` table is materialized and fed out
of band and every modifying operation on it is refused regardless of any list. Deleting a table SHALL
remove its grant lists.

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
corresponding privilege globally, each `read` grant on an enrichment table whose source the same
role cannot read, and each `sensitive` grant to a role that holds none of `TABLE_GET`, `TABLE_READ`
or `TABLE_MODIFY` on the table, globally or by any list. All three are grants that change nothing
(see `structured-query` for the second).

The response SHALL carry the five lists at the **top level**, exactly where `GET` carries them, so the
two share one shape and a client reading `write` keeps working. It SHALL additionally carry
`permissions` (the five effective flags) and `ineffective_grants`, an array whose entries carry the
`role`, the grant `key`, a stable `reason` code — `already_global`, `enrichment_read_inert` or
`sensitive_without_verb` — and a human-readable `message`. Both members are additive; nothing moves.

A `PUT` against a `system` table SHALL be accepted for the read-side keys. It SHALL be refused with
`409` and machine code `table_is_system` only when it carries a **non-empty** `write` or `modify`
list; an empty `write` or `modify` SHALL be accepted, so a five-key body succeeds. A request against a
non-existent table SHALL return `404 not_found`; a request with a blank role name SHALL return
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
  `{"read":["analytics-viewers"],"write":[],"modify":[]}` for a `system` table
- **THEN** the response is `200` and the `read` list is stored

#### Scenario: A write grant on a system table is refused

- **WHEN** the same caller `PUT`s `{"write":["ingest-bot"]}` for a `system` table
- **THEN** the response is `409` with machine code `table_is_system` and no list is changed

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
- otherwise each of `get`, `read`, `sensitive`, `write` and `modify` SHALL be `true` if the caller holds
  the corresponding privilege globally, or if one of its raw provider roles appears in a list that
  grants or implies it;
- `rows_restricted` SHALL be `true` exactly when row-policy evaluation confines the caller on the table
  (see "Row-policy evaluation"), on `system` and non-`system` tables alike;
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

#### Scenario: none mode reports full permissions

- **WHEN** the service runs in `none` security mode and any caller reads a non-`system` table
- **THEN** every flag in `permissions` except `rows_restricted` is `true`, and `rows_restricted` is
  `false`

### Requirement: Per-role row policies

The system SHALL store, for each catalog table, a set of **row policies** co-located on the table
definition, at most one per raw provider role. A policy SHALL be `{"filter": <filter node>}` — a predicate
in the structured query's filter grammar (see [structured-query](../structured-query/spec.md), "Filter
sub-grammar (CQL2-JSON)" and "Expression grammar") over that table's own columns.

There SHALL be no unrestricted policy shape: a role that may read the table and has no policy there already
reads every row (see "Row-policy evaluation"). A body without a `filter` SHALL be refused
`422 validation_error`; a member other than `filter`, like any body that does not bind to the request type,
SHALL be refused by the strict request mapper as `400 bad_request` (see "Row-policy management API").

A row policy SHALL **narrow** a read and SHALL NOT **grant** one. Whether a caller may read a table at
all SHALL remain decided by its global privileges and the table's grant lists, unchanged; a policy on a
role that has no read path to the table SHALL confer nothing.

A role name SHALL follow the grantee rules of the grant lists: a raw provider role name, trimmed,
non-blank, and not a bundle or privilege name. Row policies SHALL be storable on `system` tables, which
carry the shared data a policy most often narrows. Deleting a table SHALL remove its row policies.

The filter SHALL be validated against the table's **own** stored columns — every one of them, sensitive
columns included, independent of what the writing caller may see — and SHALL be refused when it:

- references a column the table does not store, including a dotted enrichment field (a policy narrows
  the table it is stored on; an enrichment is narrowed by a policy of its own);
- uses a `param` expression;
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

- **WHEN** a policy's filter contains a `param` expression, an aggregate function, or a window
  expression
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
and so SHALL a `DELETE` naming a role that has no policy on the table. A body that does not bind to the
request type — malformed JSON, or a member other than `filter` — SHALL be `400 bad_request`, as on every endpoint behind the strict request mapper. A
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

### Requirement: Row-policy evaluation

In `oidc` mode, for every table a caller reads through a user read surface, the rows it sees SHALL be
decided from the policies whose role is among the caller's raw provider roles:

- **none of the caller's roles has a policy on the table** — the caller SHALL see every row it may
  read;
- **another of the caller's roles may read the table and has no policy on it** — the caller SHALL see
  every row it may read: that role's unrestricted read stands on its own;
- **otherwise** — the caller SHALL see exactly the rows satisfying **at least one** of its policies'
  filters.

A role **may read the table**, for the second case, when the table's `read` grant list names it, or when a
configured `roles-mapping` maps it to `TABLE_READ` globally **and** the caller holds `TABLE_READ` globally
on the request. Only such a role counts: the caller's other provider roles — IdP groups, Core roles that
confer nothing on the table — SHALL NOT lift a confinement, policy or not, because nearly every caller
carries some, and counting them would make a policy on a widely-held role meaningless. The second half of
the global condition keeps a role name mapped differently by two providers from being credited with a read
the caller's own provider does not give it.

A policy SHALL apply to its **role** whatever path that role reads the table by: a role holding
`TABLE_READ` globally, through a bundle or a privilege mapping, SHALL be confined by its own policy exactly
as a role reading through a per-table `read` grant. Confining a broadly-entitled role therefore takes a
policy on that role; one on a narrower role the same caller also carries does not reach it.

The user read surfaces are structured query execution, SQL execution, both CSV exports, the Flight SQL
surface, the totals a response reports, and every composite shape of those — joins, subqueries, derived
tables and set operations. A confined caller SHALL observe filtered rows as absent: counts, aggregates
and distinct values SHALL agree with the filtered rows, and no response SHALL indicate that rows were
withheld.

A policy SHALL narrow the rows **of the table it is stored on**. Within a flattened entity a policy on an
enrichment SHALL withhold that enrichment's rows only: the source rows they would have joined to remain,
with the enrichment's columns reading as absent values, as for a source row that has no enrichment row.

#### Scenario: A global reader is confined by its role's policy

- **GIVEN** provider role `X` mapped to `READ_ONLY_ANALYTIC`, and a policy for `X` on `dial_usage_log`
  admitting project `X-PRJ-1` only
- **WHEN** a caller carrying `X` queries `dial_usage_log` through the structured query surface, the SQL
  surface, a CSV export or Flight SQL
- **THEN** every response contains only rows of `X-PRJ-1`, and a count reports their number alone

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

#### Scenario: An enrichment policy withholds enrichment values, not source rows

- **GIVEN** a caller unconfined on a source and confined by a policy on one of its enrichments
- **WHEN** it queries the flattened entity
- **THEN** every source row it may read is returned, and the enrichment's columns are absent values on
  the rows whose enrichment row the policy withholds

#### Scenario: A superseded row version is not resurrected

- **GIVEN** a deduplicated table on which a row's newer version no longer satisfies the caller's policy
  while its superseded version did
- **WHEN** the caller queries the table
- **THEN** the row is absent, because the policy is applied to the latest version only

### Requirement: Row policies are enforced by the data store on every reference

The system SHALL enforce row policies in the data store, by sending with each user read the ClickHouse
setting `additional_table_filters`, so that the store applies the filter to **every** reference of a
confined table that the statement makes, however the statement is shaped. The following SHALL hold:

- **Every confined table.** The setting SHALL carry an entry for every table in the catalog on which the
  caller is confined, not only the tables the statement is believed to reference. An entry for an
  unreferenced table is inert, while a missing entry would leave a reference unfiltered.
- **Fully-qualified keys.** Each entry SHALL be keyed by the table's database-qualified name in the
  database the read client's session resolves unqualified names against — the configured ClickHouse
  database, or `default` when none is configured — so the key and the rendered reference cannot resolve
  to different tables.
- **Rendered by the service.** The predicate SHALL be rendered from the stored filter by the service, as
  the structured query surface renders a filter, with every value a literal and the predicate escaped
  as a string value of the setting. No caller-supplied text SHALL reach the setting.
- **User reads only.** The setting SHALL be sent on user read surfaces and SHALL NOT be sent on a
  pipeline read, a materialize or aggregate `INSERT … SELECT`, or any read under the system identity.
- **Not overridable.** No caller SHALL be able to set, clear or replace `additional_table_filters`, and
  no internally merged setting SHALL override it. Any future facility through which a caller supplies
  query settings SHALL admit only an explicit allow-list of settings, and that allow-list SHALL NOT
  contain `additional_table_filters`.
- **No table functions.** No user read surface SHALL let a caller reference a table through a table
  function (`merge`, `remote`, `cluster`, `file`, `url`, …): the store does not apply the setting to a
  table read through such a function.

The ClickHouse version upgrades SHALL re-verify these properties: a version on which the setting is not
applied after `FINAL`, or is not applied to every reference, SHALL NOT be adopted.

#### Scenario: A table the query does not name still carries an entry

- **GIVEN** a caller confined on tables `A` and `B`
- **WHEN** it queries `A` alone
- **THEN** the setting sent with the read carries entries for both `A` and `B`

#### Scenario: A caller cannot clear the filter from query text

- **WHEN** a confined caller submits SQL carrying `SETTINGS additional_table_filters = {}`, at the top
  level or inside a subquery
- **THEN** the request is refused, and no unfiltered row is returned

#### Scenario: A table function is not reachable

- **WHEN** a confined caller submits SQL reading the table through `merge(...)` or another table
  function
- **THEN** the request is refused as a bad request, and no row is returned

#### Scenario: A pipeline write is not filtered

- **GIVEN** an aggregate pipeline whose input table carries policies for some roles
- **WHEN** its scheduled refresh runs under the system identity
- **THEN** the refresh reads every input row

#### Scenario: A hostile value stays a literal

- **WHEN** a policy filter compares a column with the value `P0') OR 1 OR ('`
- **THEN** a confined caller sees only rows whose column equals that exact string

### Requirement: Row policies do not propagate to derived tables

A policy SHALL govern only the table it is stored on. A table derived from it — an enrichment target or
an aggregate target, populated under the system identity from unfiltered reads — SHALL be governed only
by its **own** policies and grant lists. The system SHALL NOT infer, copy or require a policy on a
derived table from its source's.

This is an accepted risk, not an oversight: a role confined on a source and able to read an aggregate of
it reads that aggregate unconfined unless the aggregate carries a policy for the role. The operator
closes it by storing policies on the derived tables a confined role may read.

#### Scenario: An aggregate of a confined source is not confined

- **GIVEN** role `X` confined to `X-PRJ-1` on a source, holding `TABLE_READ` globally, and an aggregate
  target of that source carrying no policy for `X`
- **WHEN** a caller carrying `X` queries the aggregate target
- **THEN** it sees every row of the aggregate
- **AND WHEN** a policy for `X` admitting `X-PRJ-1` is stored on the aggregate target
- **THEN** it sees only that project's aggregate rows

### Requirement: Row policies are inert in none mode

In `none` security mode the system SHALL NOT enforce row policies: no read SHALL carry the filter, and
every caller SHALL see every row. The policy API SHALL still require nothing beyond what `none` mode
requires of every endpoint, and a policy written in `none` mode SHALL still be validated — its role, its
filter against the table's stored columns, and the schema guard that protects the columns it references —
because a stored policy takes effect the moment the deployment switches to `oidc`, when refusing it would
be too late.

#### Scenario: none mode returns every row

- **WHEN** the service runs in `none` mode and a table carries policies
- **THEN** every query returns every row

#### Scenario: An invalid policy is refused in none mode

- **WHEN** the service runs in `none` mode and a policy naming a column the table does not store is
  `PUT`
- **THEN** the request is refused `422 validation_error`


### Requirement: Catalog import writes no grants

A catalog import SHALL write no grant list on any table: a created table starts with empty `access` lists and a `system` table's
lists are never touched, as defined by [catalog-export-import](../catalog-export-import/spec.md) ("Import order and no grants").
Grants are each environment's own policy, so granting access on an imported table stays an operator action through
`PUT /v1/tables/{name}/access`.

#### Scenario: Imported table starts ungranted

- **WHEN** a table that has grants on the source environment is exported and imported
- **THEN** the target's table has five empty grant lists, and the target's `dial_usage_log` grant lists are unchanged
