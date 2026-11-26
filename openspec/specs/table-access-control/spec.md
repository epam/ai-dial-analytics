# table-access-control Specification

## Purpose

This capability defines per-table access control for ADAS. Each catalog table definition carries at most one
access **entry** per raw provider role: the table privileges it grants (`get`, `read`, `write`, `modify`,
`sensitive`) in addition to what the role holds globally, a **row policy** that narrows which rows it reads, enforced
by the data store on every user read, and a **column policy** that narrows which columns it sees. An enrichment's
entries carry only `write` and `modify`: its read-side access, rows and columns are its source's. Entries are matched
against the caller's provider roles at request time and managed one role at a time through the `TABLE_GRANT`-gated
role-keyed access API, guarded by one optimistic-locking **access generation** per table, or promoted between
environments by a catalog import that asks for them (see [catalog-export-import](../catalog-export-import/spec.md)). This complements the
method-level authorization defined by [authentication](../authentication/spec.md) and governs the per-table endpoints
of [table-management](../table-management/spec.md).
## Requirements
### Requirement: Per-table privilege grants

The system SHALL store, for each catalog table, at most one access **entry** per raw provider role, co-located on the
table definition (see "Role-keyed access API"). An entry's `privileges` SHALL be a subset of `get`, `read`,
`sensitive`, `write` and `modify`, each the name of a grantable table-scoped privilege minus its `TABLE_` prefix,
lowercased. The **grant list** of a privilege, the term the rest of this capability uses, SHALL be the set of roles
whose entry carries it. A `get` role authorizes seeing the table and its column inventory; `read` its rows; `write`
row inserts; and `modify` schema changes (PATCH) and description updates (PUT) — each, for `get`, `read` and
`modify`, on the non-sensitive columns column visibility leaves visible to the caller, decided across all of its
governing roles (see "Column visibility by caller role").
`sensitive` is the clearance (see `authentication`). It authorizes nothing on its own, and it extends whichever of
`get`, `read` and `modify` the same role holds on that table, globally or by grant, to the table's sensitive columns
and to the `sensitive` flag. There SHALL be no `read_sensitive` privilege: an entry naming it SHALL be refused
`422 validation_error` naming the value.

An enrichment SHALL accept no `get`, `read` or `sensitive` grant: its read-side access is its source's (see "An
enrichment's read-side access is its source's"), and only its `write` and `modify` grant lists confer anything on it.

There SHALL be **no `list` privilege**. `TABLE_LIST` is a global privilege — held, it lists every table — and on a
single table it SHALL be conferred only by implication, by any privilege that already shows the table. A per-table
`list` would grant a table's name without its definition, and the definition discloses nothing the name does not:
column names are not a secret, only values are (a column is hidden from a caller without the clearance or outside its
column policies for a consistent view, not to keep its name secret). An entry naming `list` SHALL be refused
`422 validation_error` like any unknown privilege, never silently dropped.

`get` and `read` SHALL form a ladder in which a grant at one rung confers every rung below it, down to listing.
`modify` SHALL additionally confer `get`. `write` and `sensitive` SHALL each confer nothing (see `authentication`).
Implication SHALL be derived when a check runs and SHALL NOT be written into a stored entry: a read of an entry SHALL
return exactly the privileges that were stored.

An empty grant list SHALL grant nothing, for every privilege uniformly: only a caller holding the corresponding
privilege globally is authorized. Role names are raw provider role names (the same names used as identity-provider
`roles-mapping` keys), matched against the caller's provider roles; a bundle or privilege name SHALL NOT be a valid
role.

The role `*` SHALL stand for **every authenticated caller**, whatever its provider roles and including a caller that
carries none: an entry for `*` grants its privileges to all of them. `*` SHALL be accepted with `get`, `read` and
`sensitive`, and refused with HTTP 422 with `write` and `modify`; a `*` already stored with `write` or `modify`
(written while `*` was an ordinary role name) SHALL confer nothing, so evaluation fails closed without a data
migration, and SHALL be dropped by the next `PUT` of the `*` entry. It SHALL behave as a provider role every such
caller carries, in grant evaluation, in permission reporting, in row-policy and column-policy evaluation and in the
ineffective-grant report alike. The system identity that background work runs under SHALL NOT carry it. A mapping in
an identity provider's `roles-mapping` is unaffected: `*` confers no global privilege. At startup the system SHALL log
every table on which the `*` entry carries a privilege or a row policy, and which, so a `*` stored while it was an
ordinary role name is visible to the operator. An enrichment's leftover `get`, `read` or `sensitive` for `*` SHALL NOT
be reported, because it confers nothing.

Entries on a `system` table SHALL be restricted by **privilege**, not refused wholesale: `get`, `read` and `sensitive`,
row policies and column policies SHALL be storable on a `system` table like any other — except on a `system`
enrichment, which, like every enrichment, accepts none of them — while `write` and `modify` SHALL remain absent,
because a `system` table is materialized and fed out of band and every modifying operation on it is refused regardless
of any grant. Deleting a table SHALL remove its entries.

#### Scenario: A read grant for everyone

- **GIVEN** a table on which the entry of `*` carries `read`
- **WHEN** an authenticated caller none of whose provider roles has an entry reads the table
- **THEN** the read is authorized, and the table's reported permissions show `read: true` for it

#### Scenario: A read grant for everyone on a table with role policies is reported

- **GIVEN** a table on which role `X`'s entry carries `rows` and `*` has no entry
- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": ["read"]}` for `*`
- **THEN** it is accepted, `ineffective_grants` carries an entry for `*` with reason `lifts_row_policies`, and a caller
  carrying `X` reads every row
- **AND WHEN** the `*` entry is instead stored with `read` and `rows`
- **THEN** that `PUT` reports nothing, and that caller reads the rows of either policy

#### Scenario: The startup report skips an enrichment's leftover read-side lists

- **GIVEN** an enrichment on which an earlier build stored, for `*`, a `read` grant and a row policy
- **WHEN** the service starts
- **THEN** the startup log names the enrichment for its row policy only

#### Scenario: Everyone cannot be granted a write

- **WHEN** a caller `PUT`s an entry carrying `write` or `modify` for `*` on a non-`system` table
- **THEN** it is refused with HTTP 422 and the stored entries are unchanged

#### Scenario: A stored everyone write grant confers nothing

- **GIVEN** a table on which the stored entry of `*` carries `write`
- **WHEN** an authenticated caller none of whose provider roles has an entry writes to the table
- **THEN** the write is refused, and the table's reported permissions show `write: false` for it

#### Scenario: A stored everyone write grant does not block other edits

- **GIVEN** a table on which the stored entry of `*` carries `write`
- **WHEN** a caller `PUT`s an entry for another role
- **THEN** it is accepted and the `*` entry is unchanged
- **AND WHEN** it `PUT`s `{"generation": <current>, "privileges": ["read"]}` for `*`
- **THEN** it is accepted and the `*` entry carries `["read"]` only

#### Scenario: Grant lists persist per table

- **WHEN** an admin `PUT`s `{"generation": <current>, "privileges": ["write"]}` for `analytics-writer` on a table with
  no entries
- **THEN** `GET .../access` returns `roles` with exactly one entry, `{"analytics-writer": {"privileges": ["write"],
  "rows": null, "columns": null}}`

#### Scenario: Deleting a table removes its grants

- **WHEN** a table that has entries is deleted from the catalog
- **THEN** its entries are removed with it

#### Scenario: A system table carries read grants and no write grants

- **WHEN** a caller holding `TABLE_GRANT` reads the access of a `system` table (e.g. the seeded `dial_usage_log`)
  after `analytics-viewers` has been granted `read` there
- **THEN** the response is `200` with an entry for `analytics-viewers` carrying `["read"]`, and no entry carries
  `write` or `modify`

#### Scenario: A stored list is not expanded by implication

- **WHEN** a role's entry carries `read` only
- **THEN** reading the entry returns `["read"]`, while the role's holder can nevertheless list the table and read its
  schema

#### Scenario: A get grant lists the table

- **GIVEN** a caller with no global privilege whose provider role's entry on a table carries `get`
- **WHEN** it lists the catalog
- **THEN** that table appears in the listing

#### Scenario: A list key is refused

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `"privileges": ["list"]` for a role
- **THEN** the response is `422 validation_error` naming `list`, and nothing is stored

#### Scenario: The removed read_sensitive key is refused

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `"privileges": ["read_sensitive"]` for a role
- **THEN** the response is `422 validation_error` naming `read_sensitive`, and nothing is stored

#### Scenario: A table is fully delegated through grants

- **GIVEN** a non-system table on which `finance-owners`' entry carries `read`, `modify` and `sensitive`
- **WHEN** a caller carrying that role and no global privilege reads the table, queries its sensitive columns, and
  patches a column's `sensitive` flag
- **THEN** every operation succeeds

#### Scenario: The clearance without a verb grants nothing

- **GIVEN** a table on which `pii-analysts`' entry carries `sensitive` only
- **WHEN** a caller carrying only that role lists the catalog or queries the table
- **THEN** the table is absent from the listing and the query fails as though the table did not exist

#### Scenario: An enrichment stores only write and modify

- **WHEN** a caller holding `TABLE_GRANT` reads the access of an enrichment created through the API
- **THEN** the response is `200` and no entry carries `get`, `read`, `sensitive`, `rows` or `columns`

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
**Role-keyed access API**), so the order is recoverable rather than silently wrong.

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

The system SHALL report, on every table read surface (`GET /v1/tables` list items and `GET /v1/tables/{name}`), the
calling identity's effective per-table permissions as a `permissions` object with boolean `get`, `read`, `sensitive`,
`write`, `modify`, `grant`, `rows_restricted` and `columns_restricted` fields. There SHALL be no `list` flag: a caller that sees
a table at all may list it, so the flag could only ever read `true`. The reported flags SHALL agree with what per-table
evaluation, row-policy evaluation, column-visibility evaluation and system-table protection would decide for that
caller, **after** implication is applied:

- a `system` table SHALL report `write=false, modify=false` for every caller, including one holding every privilege
  (all modifying operations are refused on system tables), while its read-side flags SHALL be evaluated from the grant
  lists like any other table's;
- an enrichment SHALL report its source's `read` and `sensitive`, a `rows_restricted` that is `true` when the caller
  is confined on the source or by a policy an earlier build left on the enrichment, a `columns_restricted` decided by
  its source's column policies, and a `get` that is its source's or implied by its own `modify`; its `write` and
  `modify` SHALL be evaluated from its own grant lists;
- otherwise each of `get`, `read`, `sensitive`, `write` and `modify` SHALL be `true` if the caller holds the
  corresponding privilege globally, or if one of its raw provider roles appears in a grant list that grants or implies
  it;
- `rows_restricted` SHALL be `true` exactly when row-policy evaluation confines the caller on the table (see
  "Row-policy evaluation by caller role", and the enrichment bullet above), on `system` and non-`system` tables alike;
- `columns_restricted` SHALL be `true` exactly when column-visibility evaluation hides or masks at least one of the
  table's own columns from the caller (see "Column visibility by caller role", row policies of the governing roles
  included), on `system` and non-`system` tables alike; a
  column hidden only by the clearance does not set it, and it is `false` for a caller with no governing role on the
  table, as `rows_restricted` is for a caller that may not read it;
- `grant` SHALL be `true` exactly when the caller holds `TABLE_GRANT`, and so may call the table's access API (see
  "Role-keyed access API"). `TABLE_GRANT` is held only globally, never through an entry, so `grant` SHALL be the
  same on every table the caller sees, `system` tables and enrichments included: both accept entries. An entry
  carrying every privilege SHALL NOT set it. It SHALL agree with the rule that withholds a revision snapshot's
  entries from a caller without `TABLE_GRANT`;
- in `none` security mode `grant` SHALL be `true` on every table, every other flag but `rows_restricted` and
  `columns_restricted` SHALL be `true` for non-`system`
  tables, the read-side flags SHALL be `true` for `system` tables, and `rows_restricted` and `columns_restricted`
  SHALL be `false` (no authorization is enforced).

`sensitive` SHALL report whether the caller holds the clearance on the table, not whether it can read sensitive
values. It counts among the read-side flags. A consumer SHALL derive "reads sensitive values" as `read && sensitive`,
"sees sensitive columns" as `get && sensitive`, and "may change them or the flag" as `modify && sensitive`.
`grant` SHALL NOT change what the caller may do: the access API keeps its own `TABLE_GRANT` check.
`rows_restricted` and `columns_restricted` SHALL NOT disclose the policies themselves; those are readable only where `TABLE_GRANT`
is required: through the access API, a revision snapshot (see [audit-trail](../audit-trail/spec.md)), and a catalog export or import
called with `include_access` (see [catalog-export-import](../catalog-export-import/spec.md), "Access export" and "Access import").

The reporting SHALL share its role-matching, implication, row-policy and column-visibility evaluation with the
evaluation used for enforcement so the two cannot diverge. Reporting SHALL NOT change enforcement behaviour.

#### Scenario: Matching write grant is reported

- **GIVEN** a table on which provider role `analytics-writer`'s entry carries `write` only
- **WHEN** a caller carrying provider role `analytics-writer` and no global privilege reads the table via
  `GET /v1/tables/{name}`
- **THEN** the table carries `permissions.write = true` and `permissions.modify = false`

#### Scenario: Implication is reflected in the reported flags

- **GIVEN** a table on which provider role `analytics-viewers`' entry carries `read` only
- **WHEN** a caller carrying that role reads the table
- **THEN** the table carries `permissions.read = true` and `permissions.get = true`, and `permissions.sensitive =
  false`

#### Scenario: The clearance is reported on its own flag

- **GIVEN** a table on which provider role `finance-owners`' entry carries `modify` and `sensitive`
- **WHEN** a caller carrying that role and no global privilege reads the table
- **THEN** the table carries `permissions.get = true`, `permissions.modify = true`, `permissions.sensitive = true` and
  `permissions.read = false`

#### Scenario: A full-privilege caller sees full permissions on a non-system table

- **WHEN** a caller holding the `FULL_ADMIN` bundle, none of whose roles has an entry on the table, reads a
  non-`system` table with no entries
- **THEN** every flag in `permissions` except `rows_restricted` and `columns_restricted` is `true`, and those two are
  `false`

#### Scenario: No matching grant is reported as no permissions

- **WHEN** a caller with no global privilege, none of whose provider roles has an entry on a table, reads that table
  through a surface it can reach
- **THEN** every flag in `permissions` is `false`, `columns_restricted` included even where another role's entry on
  the table carries `columns`

#### Scenario: A system table reports no write permissions but honours read grants

- **WHEN** a caller holding the `FULL_ADMIN` bundle reads a `system` table (e.g. the seeded `dial_usage_log`)
- **THEN** the table carries `permissions.write = false` and `permissions.modify = false`, and `permissions.read =
  true`

#### Scenario: A confined caller is reported as row-restricted

- **GIVEN** an entry for role `X` on a table whose `rows` admits one project
- **WHEN** a caller carrying `X` and holding `TABLE_READ` reads the table's definition
- **THEN** the table carries `permissions.read = true` and `permissions.rows_restricted = true`
- **AND WHEN** the caller also carries another role that may read the table and has no row policy on it
- **THEN** the table carries `permissions.rows_restricted = false`

#### Scenario: A column-restricted caller is reported

- **GIVEN** an entry for role `X` with `read` and `columns` naming some but not all of the table's columns
- **WHEN** a caller carrying only `X` reads the table's definition
- **THEN** the table carries `permissions.columns_restricted = true`
- **AND WHEN** the list names every column of the table
- **THEN** the table carries `permissions.columns_restricted = false`

#### Scenario: A caller with a masked column is reported as column-restricted

- **GIVEN** an entry for `R` with `read`, `rows` and `columns` naming some of the table's columns, and an entry for `U`
  with `read`, `rows` and `columns` `null`
- **WHEN** a caller carrying both reads the table's definition
- **THEN** every column is listed and the table carries `permissions.columns_restricted = true`, because the columns
  `R` does not list are masked

#### Scenario: An enrichment reports its source's read-side flags

- **GIVEN** a source on which role `X`'s entry carries `read` and a row policy, and an enrichment of it on which `X`'s
  entry carries `write`
- **WHEN** a caller carrying `X` and no global privilege reads the enrichment's definition
- **THEN** the enrichment carries `permissions.read = true`, `permissions.rows_restricted = true`, `permissions.write
  = true` and `permissions.modify = false`

#### Scenario: An enrichment reports its source's column restriction

- **GIVEN** a source on which role `X`'s entry carries `read` and `columns` naming the source's columns and none of
  enrichment `e`'s
- **WHEN** a caller carrying only `X` reads `GET /v1/tables/e`
- **THEN** `e` carries `permissions.columns_restricted = true` and lists no column
- **AND WHEN** `X`'s `columns` names every column of `e` as `e.<column>`
- **THEN** `e` carries `permissions.columns_restricted = false`

#### Scenario: A policy left on an enrichment is reported as row-restricted

- **GIVEN** a source on which role `X`'s entry carries `read` and no row policy, and an enrichment of it that still
  stores a row policy for `X`, written by an earlier build
- **WHEN** a caller carrying only `X` and no global privilege reads the enrichment's definition
- **THEN** the enrichment carries `permissions.read = true` and `permissions.rows_restricted = true`

#### Scenario: An enrichment whose source is missing grants no read

- **GIVEN** a revision snapshot of an enrichment whose source table no longer exists
- **WHEN** a caller holding `TABLE_GET` globally and no other global privilege reads it
- **THEN** the read answers `200`, not an error, and the snapshot carries `permissions.get = true`, `permissions.read
  = false` and `permissions.sensitive = false`

#### Scenario: none mode reports full permissions

- **WHEN** the service runs in `none` security mode and any caller reads a non-`system` table
- **THEN** every flag in `permissions` except `rows_restricted` and `columns_restricted` is `true`, and those two are
  `false`

#### Scenario: The grant flag follows TABLE_GRANT

- **WHEN** a caller holding `TABLE_GET` and `TABLE_GRANT` globally, and no other privilege, reads a table
- **THEN** the table carries `permissions.grant = true` and `permissions.read = false`
- **AND WHEN** a caller holding only `TABLE_GET` globally reads it
- **THEN** the table carries `permissions.grant = false`

#### Scenario: An entry does not confer the grant flag

- **GIVEN** a table on which role `X`'s entry carries `get`, `read`, `sensitive`, `write` and `modify`
- **WHEN** a caller carrying `X` and no global privilege reads the table
- **THEN** the table carries `permissions.modify = true` and `permissions.grant = false`

#### Scenario: The grant flag is reported on system tables and enrichments

- **WHEN** a caller holding the `FULL_ADMIN` bundle reads a `system` table and an enrichment
- **THEN** both carry `permissions.grant = true`, the `system` table with `permissions.write = false`

### Requirement: Per-role row policies

The system SHALL accept and store, for each catalog table other than an enrichment, at most one **row policy** per
raw provider role, as the `rows` member of the role's entry (see "Role-keyed access API"). A stored policy SHALL be
`{"filter": <filter node>}` — a predicate in the structured query's filter grammar (see
[structured-query](../structured-query/spec.md), "Filter sub-grammar (CQL2-JSON)" and "Expression grammar") over that
table's own columns. An enrichment SHALL accept none: the rows a caller reads through it are its source's (see "An
enrichment's read-side access is its source's").

There SHALL be no unrestricted policy shape: a role that may read the table and has no policy there already reads
every row (see "Row-policy evaluation by caller role"), so `rows: null` means no policy. A `rows` object carrying
neither `filter` nor `where` SHALL be refused `422 validation_error`; a `rows` member other than `filter` and `where`,
like any body that does not bind to the request type, SHALL be refused by the strict request mapper as
`400 bad_request` (see "Role-keyed access API").

A row policy SHALL **narrow** a read and SHALL NOT **grant** one. Whether a caller may read a table at all SHALL remain
decided by its global privileges and the table's grant lists, unchanged. A policy on a role with no read path to the
table SHALL grant no read: a caller carrying only that role reads nothing, while a caller confined by another of its
roles also reads that policy's rows (see "Row-policy evaluation by caller role"). Such an entry's `columns` narrow what
the rows it adds show and widen nothing: a column the list leaves out is absent on the rows only it adds (see "Column visibility
by caller role", row-contributing roles).

A policy's role SHALL follow the grantee rules: a raw provider role name, trimmed, non-blank, and not a bundle or
privilege name. `*` SHALL be a valid policy subject, with the meaning it has in a grant: its policy is one every
authenticated caller carries. Row policies SHALL be storable on `system` tables other than enrichments, which carry
the shared data a policy most often narrows. Deleting a table SHALL remove its row policies.

The filter SHALL be validated against the table's **own** stored columns — every one of them, sensitive columns and
columns a column policy hides included, independent of what the writing caller may see — and SHALL be refused when
it:

- references a column the table does not store, including a dotted enrichment field (a policy narrows the table it is
  stored on);
- uses a `param` expression other than a caller value (see "Caller values in row policies");
- calls a function outside the function catalog's scalar group, or one the catalog declares as calling a service
  outside the data store;
- fails any validation the structured query surface would apply to the same filter; or
- renders to a predicate longer than 16 KiB.

A filter MAY call the relative time functions (`now`, `date_add`, `date_sub`): a policy is evaluated on a read, where
a moving window is meaningful, never on a derivation path.

A stored filter SHALL be returned in the structured query's stable wire serialization, so a policy read back is a
filter the query surface accepts.

#### Scenario: A policy narrows a role to one project

- **WHEN** a caller holding `TABLE_GRANT` stores for role `X` on `dial_usage_log` an entry whose `rows` is
  `{"filter": {"op": "in", "args": [{"type": "field", "name": "project_id"}, {"type": "array", "items": [{"type": "value", "value_type": "string", "value": "X-PRJ-1"}]}]}}`
- **THEN** the entry is stored and reading `X`'s entry returns that policy under `rows`

#### Scenario: A policy names a column the table does not store

- **WHEN** a policy's filter references a field the table does not store, or a dotted enrichment field
- **THEN** the request is refused `422 validation_error` naming the field, and nothing is stored

#### Scenario: A policy filter may not take a parameter or aggregate

- **WHEN** a policy's filter contains a `param` expression that is not a caller value, an aggregate function, or a
  window expression
- **THEN** the request is refused `422 validation_error`, and nothing is stored

#### Scenario: A policy without a filter is refused

- **WHEN** an entry's `rows` is `{}`
- **THEN** the request is refused `422 validation_error`

#### Scenario: An unknown member does not bind

- **WHEN** an entry's `rows` carries a member other than `filter` and `where`, e.g. `{"all_rows": true}`
- **THEN** the request is refused `400 bad_request`, and nothing is stored

#### Scenario: A sensitive column may constrain a policy

- **WHEN** a caller holding `TABLE_GRANT` but no clearance stores a policy filtering on a sensitive column of the table
- **THEN** the policy is accepted, because it is validated against the table's stored columns rather than the caller's
  view

#### Scenario: A relative time window is a valid policy

- **WHEN** a policy's filter is `request_time >= date_sub('day', 90, now())`
- **THEN** the policy is accepted

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

### Requirement: Row policies are inert in none mode

In `none` security mode the system SHALL NOT enforce row policies: no read SHALL carry the filter, and every caller
SHALL see every row a query selects, no policy removing any. A query's own filter still applies, a caller value in it
included, and in `none` mode a caller value is unknown (see [authentication](../authentication/spec.md), "Caller
values derived from the credential"). The access API SHALL still require nothing beyond what `none` mode requires of every endpoint,
and a policy written in `none` mode SHALL still be validated — its role, its filter against the table's stored
columns, and the schema guard that protects the columns it references — because a stored policy takes effect the
moment the deployment switches to `oidc`, when refusing it would be too late.

#### Scenario: none mode returns every row

- **WHEN** the service runs in `none` mode and a table carries policies
- **THEN** every query returns every row its own filter selects, as if the table carried no policy

#### Scenario: An invalid policy is refused in none mode

- **WHEN** the service runs in `none` mode and an entry whose policy names a column the table does not store is `PUT`
- **THEN** the request is refused `422 validation_error`

### Requirement: Caller values in row policies

A row policy filter SHALL accept a **caller value**: a `param` expression whose `name` is exactly
`caller.user_hash` or `caller.project`. The set is closed and the names are matched case-sensitively; a
`param` with any other name, including one that differs only in case, SHALL be refused
`422 validation_error`. No other content of the caller's credential SHALL be reachable from a policy.

A caller value is a string whose content is not known when the policy is stored. It SHALL appear only as
a direct operand of an `eq` or `ne` comparison whose other operand is a field of the table's `string` type.
Anywhere else — a function argument, a `case` branch, an array item, a comparison with a field of another
type or with anything that is not a field — the policy SHALL be refused `422 validation_error`. The
comparison MAY sit under any logical operator, `not` included. A stored filter SHALL be returned with the `param` expression intact, never with a
resolved value. The 16 KiB limit SHALL be checked with each caller value taken at its maximum length of
512 bytes, so a policy that was accepted cannot exceed the limit on a read.

A query MAY use the same two caller values under its own rules (see
[structured-query](../structured-query/spec.md), "Caller values in a query filter"). A caller value in a query only
narrows what that query returns: it SHALL NOT change which policies confine the caller, nor lift or add a
confinement.

How each value is derived from the caller's credential is specified in
[authentication](../authentication/spec.md), "Caller values derived from the credential".

#### Scenario: An own-rows policy is stored

- **WHEN** a caller holding `TABLE_GRANT` stores for role `user` on `dial_usage_log` the policy
  `{"filter": {"op": "eq", "args": [{"type": "field", "name": "user_hash"}, {"type": "param", "name": "caller.user_hash"}]}}`
- **THEN** the policy is stored, and reading it back returns the `param` expression unchanged

#### Scenario: A name outside the closed set is refused

- **WHEN** a policy's filter contains `{"type": "param", "name": "caller.email"}` or
  `{"type": "param", "name": "Caller.User_Hash"}`
- **THEN** the request is refused `422 validation_error`, and nothing is stored

#### Scenario: A caller value is compared with a non-string column

- **WHEN** a policy's filter compares `caller.project` with a numeric, enum or object column
- **THEN** the request is refused `422 validation_error`, and nothing is stored

#### Scenario: A caller value outside a direct comparison is refused

- **WHEN** a policy's filter uses a caller value as a function argument, e.g.
  `coalesce(caller.user_hash, '') = user_hash`, inside a `case` expression, as an item of an `in` list,
  or compares it with a literal
- **THEN** the request is refused `422 validation_error`, and nothing is stored

### Requirement: Caller values resolve per read and an absent one is unknown

On every read that carries a caller's row filters, each caller value in a confining policy SHALL be
replaced by the caller's own value, inlined as an escaped string literal. A caller value SHALL be
resolved from the identity of the request being served and SHALL NOT be cached across callers.

A caller value SHALL be **unknown** when the caller's credential yields none, when it is blank, or when it
is longer than 512 bytes. A comparison with an unknown caller value SHALL evaluate as SQL `NULL` does in a
predicate: it SHALL match no row, its negation SHALL match no row, and a disjunction SHALL still match
through another branch that is true. An unknown value SHALL NOT be rendered as an empty string or
any other constant, because rows written for callers without that identity store an empty value.

A filter's shape remains its author's responsibility where the value is known: `NOT (user_hash =
caller.user_hash)` selects every other caller's rows.

Everything else about evaluation is unchanged: which policies confine a caller, that a role reading the
table with no policy lifts the confinement, that several confining policies are combined with `OR`, and
that a pipeline refuses a confined caller.

#### Scenario: A user reads only its own rows

- **WHEN** role `user` carries the policy `user_hash = caller.user_hash` on `dial_usage_log`, and a caller
  holding only that role, whose user hash is `aaaa…01`, reads the table
- **THEN** the read returns only rows whose `user_hash` is `aaaa…01`, including the enrichment columns
  joined to those rows

#### Scenario: A role without a policy still reads every row

- **WHEN** the same caller also holds a role that may read the table and has no policy there
- **THEN** the read returns every row

#### Scenario: One policy serves user and project callers

- **WHEN** a role carries `user_hash = caller.user_hash OR project_id = caller.project`, and it is held by
  a user whose hash is known and who has no project, and by a project key of project `P1` with no user
- **THEN** the user reads the rows with its own hash, and the project key reads the rows of `P1`

#### Scenario: A caller with no value reads nothing

- **WHEN** role `user` carries `user_hash = caller.user_hash` and a caller holding only that role has no
  user hash
- **THEN** the read succeeds and returns no rows, including rows whose `user_hash` is empty

#### Scenario: Negation of an unknown value matches nothing

- **WHEN** a role carries `NOT (user_hash = caller.user_hash)` or `user_hash != caller.user_hash`, and a
  caller holding only that role has no user hash
- **THEN** the read returns no rows

#### Scenario: A blank value is unknown

- **WHEN** a caller's credential yields an empty or whitespace-only value for a caller value a confining
  policy references
- **THEN** the value is unknown and the comparison matches no row

### Requirement: A row policy may be written as a SQL condition

`PUT /v1/tables/{name}/access?role=<role>` SHALL accept `"rows": {"where": "<condition>"}` as an alternative to
`"rows": {"filter": <filter node>}`. The condition is the text that would follow `WHERE` in a SQL statement over the
table, in the SQL subset the ad-hoc SQL surface accepts (see [rest-sql-endpoint](../rest-sql-endpoint/spec.md)).
Exactly one of `where` and `filter` SHALL be given: a `rows` carrying both, or neither, SHALL be refused
`422 validation_error`.

The system SHALL translate the condition into a filter node and from there treat the policy exactly as one written as
`filter`: the same validation against the table's own columns, the same refusals, the same size limit, the same
storage and the same enforcement. The condition text SHALL NOT be stored, SHALL NOT be sent to the data store, and
SHALL NOT be returned: the response and every later read of the entry carry the stored `filter`.

The condition SHALL be resolved against every column the table stores, sensitive columns and columns a column policy
hides included, whatever the writing caller may itself see or read.

A caller value SHALL be written in the condition as `caller.user_hash()` or `caller.project()`, the spelling a query
uses and matched as a query matches it (see [sql-query-translation](../sql-query-translation/spec.md), "Caller values
in SQL"), or as `caller.user_hash` or `caller.project` without the parentheses, exactly; either spelling SHALL become
the `param` of that name, subject to the placement rule of "Caller values in row policies". An `IN` list holding a
caller value, in either spelling, SHALL be stored as the disjunction of the equalities it abbreviates, and a `NOT IN`
list as the `not` of that disjunction; in the `filter`
form a caller value as an `in` item stays refused. A string literal
in the condition SHALL remain a literal whatever it spells, including the text of a caller value's name in either
spelling.

A condition that does not parse, does not validate against the table, or uses a construct the SQL surface cannot
express as a single filter SHALL be refused `422 validation_error` with the reason. A condition that holds a subquery
or a sequence value, names an enrichment's column by its dotted name, or closes itself to add another clause, SHALL be
refused on its text, before any name in it is resolved, with one message whatever it names: the refusal SHALL NOT tell
whether a table or column outside the policy's own table exists. An enrichment accepts no policy in either form (see
"Role-keyed access API").

#### Scenario: A condition is stored as its filter

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s for role `X` an entry with `"rows": {"where": "project_id IN
  ('X-PRJ-1', 'X-PRJ-2')"}`
- **THEN** the response is `200` with a `rows.filter` equal to the one the same policy written as `filter` stores, and
  a caller carrying `X` sees only the rows of those two projects

#### Scenario: A caller value in a condition

- **WHEN** a policy is written as `"rows": {"where": "user_hash = caller.user_hash"}`
- **THEN** the stored filter compares `user_hash` with the `param` `caller.user_hash`, and each caller reads its own
  rows

#### Scenario: The call spelling in a condition

- **WHEN** a policy is written as `"rows": {"where": "user_hash = caller.user_hash()"}`
- **THEN** the stored filter is the one `"user_hash = caller.user_hash"` stores, comparing `user_hash` with the
  `param` `caller.user_hash`

#### Scenario: A literal spelling a caller value stays a literal

- **WHEN** a policy is written as `"rows": {"where": "user_hash = 'caller.user_hash'"}`
- **THEN** the stored filter compares `user_hash` with the string `caller.user_hash`, and no caller's value is
  substituted

#### Scenario: An IN list with a caller value is stored as a disjunction

- **WHEN** a policy is written as `"rows": {"where": "user_hash IN ('a', caller.user_hash)"}` or as
  `"rows": {"where": "user_hash IN ('a', caller.user_hash())"}`
- **THEN** both store the same filter, an `or` of the `eq` comparisons with `'a'` and with the `param`
  `caller.user_hash`
- **AND** the same two spellings with `NOT IN` both store the `not` of that `or`

#### Scenario: A literal spelling the call stays a literal

- **WHEN** a policy is written as `"rows": {"where": "user_hash = 'caller.user_hash()'"}`
- **THEN** the stored filter compares `user_hash` with the string `caller.user_hash()`, and no caller's value is
  substituted

#### Scenario: Both forms at once are refused

- **WHEN** an entry's `rows` carries both `where` and `filter`
- **THEN** the request is refused `422 validation_error` and nothing is stored

#### Scenario: A subquery in a condition is refused

- **WHEN** a policy is written as `"rows": {"where": "project_id IN (SELECT project_id FROM other_table)"}`
- **THEN** the request is refused `422 validation_error` and nothing is stored
- **AND** the message is the same whether or not `other_table` exists

### Requirement: An enrichment's read-side access is its source's

An enrichment SHALL carry no read-side access of its own. For a table of type `enrichment`, `TABLE_GET`, `TABLE_READ`
and `TABLE_SENSITIVE` SHALL be decided as for its `source_table`: a caller holds `TABLE_READ` or `TABLE_SENSITIVE` on
the enrichment when, and only when, it holds it on the source, globally or by the source's grant lists; and it holds
`TABLE_GET` on the enrichment when it holds it on the source — by any grant list that confers it there, the source's
`modify` included — or holds `modify` on the enrichment itself. A `*` stored in the source's `modify` grant list, which
confers nothing on the source, SHALL confer nothing on its enrichments either. Which of the enrichment's columns a
caller sees SHALL be decided by the source's column policies, naming them as `<enrichment>.<column>` (see "Column
visibility by caller role"). This SHALL hold on every surface that evaluates a per-table privilege — the catalog
listing and read, schema discovery, query resolution on every user read surface, the schema mutation surface, catalog
export, pipeline authorization and the reported `permissions` — because they share one evaluation.

`write` and `modify` SHALL remain the enrichment's own grant lists, evaluated on the enrichment. `modify` held on the
enrichment SHALL still confer `get` on it by implication, so a role that maintains an enrichment's schema can read its
definition without reading the source. Because the clearance on an enrichment is the source's, such a role changes an
enrichment's sensitive columns, or any column's `sensitive` flag, only when it also holds the source's clearance.

An enrichment SHALL accept no `get`, `read` or `sensitive` grant, no row policy and no column policy, in `none`
security mode as in `oidc`, because the refusal constrains what state may be stored rather than who may act. Which
rows a caller reads through an entity SHALL be decided by the source's row policies, and an enrichment's values SHALL
reach a caller only on the source rows it reads.

State an earlier build stores on an enrichment — an old replica during a rolling upgrade, or one rolled back to —
SHALL NOT widen anything. A `get`, `read` or `sensitive` grant stored there SHALL confer nothing, SHALL be returned as
stored, and MAY be cleared by a `PUT .../access?role=<role>` of that role's entry or removed with
`DELETE .../access?role=<role>`. A row policy stored there SHALL keep narrowing as it did — it only ever withholds the
enrichment's values on rows the source returns — SHALL be returned as stored, and MAY be cleared or removed the same
way. Whether another of the caller's roles lifts such a leftover policy SHALL be decided by the same rule as on any
table, against the enrichment's own stored grant lists, as when the policy was written.

The reason is that an enrichment is not a queryable entity: every statement that reaches its rows has its source as
`FROM`. Access of its own could therefore only narrow what the source allows, and only for a caller reading the source
through a per-table grant — never for one holding `TABLE_READ` globally — which nothing in the API made visible. The
way to keep an enrichment column from some readers of its source is a column policy on the source that does not name
it, or marking it `sensitive`, which reserves it for the source's clearance holders.

#### Scenario: A reader of the source sees the enrichment's columns

- **GIVEN** a caller with no global privilege whose provider role's entry on a source carries `read`, with no column
  policy, and which has no entry on its enrichment
- **WHEN** it requests the entity's schema and queries an enrichment column
- **THEN** the enrichment's non-sensitive columns are present and the query resolves

#### Scenario: The source's clearance reaches the enrichment's sensitive columns

- **GIVEN** a caller whose provider role's entry on a source carries `read` and `sensitive`, and an enrichment of it
  carrying a `sensitive` column
- **WHEN** the caller queries that column
- **THEN** the query resolves
- **AND WHEN** `sensitive` is removed from that entry
- **THEN** the column is absent from the schema and referencing it fails as an unknown field

#### Scenario: The enrichment is listed with its source

- **GIVEN** a caller that may list a source and holds nothing on its enrichment
- **WHEN** it lists the catalog with `GET /v1/tables`
- **THEN** the enrichment appears, with the `get`, `read` and `sensitive` flags of its source

#### Scenario: An enrichment maintainer sees the definition it maintains

- **GIVEN** a caller with no global privilege whose provider role's entry on an enrichment carries `modify` and which
  has no entry on its source
- **WHEN** it reads `GET /v1/tables/{enrichment}`, and then queries the source's entity
- **THEN** the read is `200` with the enrichment's non-sensitive columns, and the query reports an unknown entity

#### Scenario: A source modify holder sees the enrichment's columns

- **GIVEN** a caller with no global privilege whose provider role's entry on a source carries `modify` only
- **WHEN** it requests the source entity's schema and reads `GET /v1/tables/{enrichment}`
- **THEN** the enrichment's non-sensitive columns are present in both

#### Scenario: A legacy everyone modify grant on the source shows no enrichment

- **GIVEN** a source on which the stored entry of `*` carries `modify` and no entry names the caller's roles
- **WHEN** an authenticated caller with no global privilege lists the catalog and reads `GET /v1/tables/{enrichment}`
- **THEN** neither the source nor its enrichment is listed, and the read reports the enrichment as absent

#### Scenario: A list or policy left on an enrichment widens nothing

- **GIVEN** an enrichment on which an earlier build stored a `read` grant for role `R` and a row policy for role `X`
- **WHEN** a caller carrying only `R` queries the source's entity, and a caller holding `TABLE_GRANT` reads the
  enrichment's access and then deletes `X`'s entry
- **THEN** the query reports an unknown entity, the read returns both entries as stored, and the delete answers `204`

#### Scenario: The catalog migration clears an existing enrichment's read-side lists and policies

- **GIVEN** an enrichment whose `read`, `sensitive`, `write` and `modify` lists and a row policy were stored by an
  earlier build, and a source with lists and policies of its own
- **WHEN** the catalog migration has run and a caller holding `TABLE_GRANT` reads both tables' access
- **THEN** no entry on the enrichment carries `get`, `read`, `sensitive` or `rows`, its `write` and `modify` grants are
  as stored; the source's grants and policies are as stored

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
open, reads included. Every non-`system` table SHALL be visible, gettable, readable and writable, every `system` table
visible, gettable and readable, and every column, sensitive or restricted by a column policy, SHALL be served. The mode
SHALL be resolved from the single component specified in `authentication`, so this behaviour cannot diverge between
the HTTP and Flight surfaces.

Rules that constrain what state may be stored, rather than who may act, SHALL still apply in `none` mode, because a
configuration written while security was off goes live the moment a deployment switches to `oidc`. They include every
check of the access API that is not a privilege check (see "Role-keyed access API"): the refusal of a read-side grant,
row policy or column policy on an enrichment, the generation check, the role, `table_is_system`, `*` with `write` or
`modify`, and the validation of row and column policies.

#### Scenario: none mode does not evaluate access

- **WHEN** the service runs in `none` mode
- **THEN** per-table operations are reachable without any grant check, and no table or column is hidden from a
  listing, discovery or query

#### Scenario: none mode still refuses a read grant or a row policy on an enrichment

- **WHEN** the service runs in `none` mode and a `PUT .../access?role=<role>` for an enrichment carries `read`, a `rows` or a
  `columns`
- **THEN** each request is refused `409 enrichment_follows_source`

#### Scenario: none mode still checks the generation

- **WHEN** the service runs in `none` mode and a `PUT .../access?role=<role>` changing that role's entry carries a
  `generation` older than the role's last change
- **THEN** the request is refused `409 access_conflict`

### Requirement: Role-keyed access API

The system SHALL expose a table's whole access configuration as one resource per raw provider role, under
`/v1/tables/{name}/access`. Every method SHALL require `TABLE_GRANT`, because an entry discloses which provider roles
exist and what each may do; a caller without it SHALL receive `403 forbidden`.

An **entry** SHALL be `{"privileges": [...], "rows": ..., "columns": ...}`: `privileges` a subset of `get`, `read`,
`sensitive`, `write` and `modify` (see "Per-table privilege grants"), `rows` either `null` or the role's row policy
(see "Per-role row policies"), and `columns` either `null` or the role's column policy (see "Per-role column
policies"). A stored entry SHALL be returned with `privileges` de-duplicated in the order `get`, `read`, `sensitive`,
`write`, `modify`, `rows` as `{"filter": <filter node>}` or `null`, and `columns` as a sorted list or `null`.

- `GET /v1/tables/{name}/access`, with no `role` parameter, SHALL return `{"generation": <integer>, "roles":
  {"<role>": <entry>, ...}, "ineffective_grants": [...]}`, the members of `roles` sorted by role, with an empty
  `roles` object when the table has no entry, and the report described below covering every stored entry (see
  "Ineffective grants are reported on read").
- `GET /v1/tables/{name}/access?role=<role>` SHALL return `{"generation": <integer>, "role": "<role>", ...entry,
  "ineffective_grants": [...]}`, the report covering that entry only, and `404 not_found` when the role has no entry
  on the table.
- `PUT /v1/tables/{name}/access?role=<role>` SHALL take `{"generation": <integer>, "privileges": [...], "rows": ...,
  "columns": ...}` and SHALL **replace the role's whole entry**: an absent or `null` `privileges` SHALL mean `[]`, and
  an absent `rows` or `columns` SHALL mean `null`. Nothing about another role's entry SHALL change. The response SHALL be `200`
  with `{"generation", "role", "privileges", "rows", "columns", "ineffective_grants"}`: the entry as stored, the
  table's generation after the request, and the report described below. It SHALL NOT carry a `permissions` member; the caller's own permissions
  are reported by `GET /v1/tables/{name}`.
- `DELETE /v1/tables/{name}/access?role=<role>` SHALL remove the role's entry and answer `204`, or `404 not_found` when
  the role has no entry on the table. It SHALL carry no body; it MAY carry a `generation` query parameter, which makes
  the removal conditional (see "Access configuration generation").

An entry whose `privileges` is empty and whose `rows` and `columns` are both `null` SHALL NOT be stored: a `PUT` of one
SHALL remove the role's entry if it has one, and SHALL answer `200` with that empty entry, after which a
`GET .../access?role=<role>` answers `404`. An entry MAY carry `rows`, `columns` or both with an empty `privileges`: it then
grants nothing. Its `columns` narrow a caller where the role governs the caller's access to the table (see
"Column visibility by caller role"), a global read privilege included, and where its `rows` add rows to a confined
caller as a row-contributing role, narrowing what those rows show; they are ignored otherwise. Its `rows` take part
in "Row-policy evaluation by caller role", which combines the policy of every role a confined caller carries whether
or not that role may read the table: on a role with no read path to the table, `rows` therefore **adds** its rows to a
confined caller that also carries the role, rather than narrowing anything, and gives a caller carrying only that
role no read.

The role SHALL be carried as the `role` query parameter: the raw provider role as an ordinary URL-encoded query value,
decoded once. Any character SHALL be allowed, so every role is addressable: role `/analytics/viewers` is addressed as
`.../access?role=%2Fanalytics%2Fviewers`, `team%ops` as `?role=team%25ops` and `a;b` as `?role=a%3Bb`, and each is
listed under its decoded name. The decoded value SHALL be trimmed on every method, so `?role=%20X` names `X`. A `PUT`,
a `DELETE` or a one-role `GET` whose `role` is missing, or blank once trimmed, SHALL be refused `400 bad_request`
before any other check, and so SHALL one carrying `role` more than once, rather than joining the values into one role.
On a `PUT` the role SHALL follow the grantee rules: a bundle or privilege name SHALL be refused
`422 validation_error`. `*` SHALL be an ordinary key with the meaning given in "Per-table privilege grants". `GET`
and `DELETE` naming a role that has no entry, however spelled, SHALL answer `404`. The role SHALL NOT be accepted as a
path segment: `/v1/tables/{name}/access/<anything>` is an unknown path (`404 not_found`).

A `rows` member in a request SHALL be `{"filter": <filter node>}` or `{"where": "<condition>"}` (see "A row policy may
be written as a SQL condition"); it is stored and returned as `{"filter": ...}`.

`ineffective_grants` SHALL report, without failing the request, each privilege in the entry that changes nothing, and
each standing warning below, as an object carrying `role`, `key` (the privilege), a stable `reason` and a
human-readable `message`. It SHALL be an array, empty when nothing is reported. Items SHALL follow the entry's
`privileges` order, and for one privilege the order `lifts_row_policies`, `lifts_column_policies`,
`everyone_reads_all_rows`, `already_global`, `sensitive_without_verb`. The reasons are:

- `already_global` — the role already holds that privilege globally;
- `sensitive_without_verb` — `sensitive` for a role that holds none of `TABLE_GET`, `TABLE_READ` or `TABLE_MODIFY` on
  the table, globally or by any entry, nor `modify` on any enrichment of it (a source's clearance does change
  something for a role holding an enrichment's `modify`, because it extends that `modify` to the enrichment's
  sensitive columns);
- `lifts_row_policies` — `read` in the entry of `*` with `rows` `null`, on a table where another role's entry carries
  `rows`: every caller then carries a role that reads with no policy, so by "Row-policy evaluation by caller role"
  those policies confine no one;
- `lifts_column_policies` — `read` in the entry of `*` with `rows` and `columns` both `null`, on a table where another
  role's entry carries `columns`: every caller then carries a reading role with neither a row nor a column
  restriction, so by "Column visibility by caller role" those restrictions hide nothing;
- `everyone_reads_all_rows` — `read` in the entry of `*` with `rows` `null`, on a table that is not an enrichment,
  whatever the entry's `columns`: every authenticated caller then reads every row of the table. This is a standing
  warning, not an ineffective grant: it is reported for as long as the configuration stays, a table meant to be
  readable by everyone keeps carrying it, and it is reported beside `lifts_row_policies` and
  `lifts_column_policies` when those apply, so this one reason identifies a table open to everyone with no row
  restriction. It SHALL NOT suppress any other reason.

Stored state no `PUT` can produce any more — a `*` entry carrying `write` or `modify`, or an enrichment's leftover
`get`, `read` or `sensitive` — SHALL be judged by these same rules only; no reason reports that such a privilege
confers nothing. The report SHALL be built the same way in `none` security mode.

A `PUT` against a `system` table SHALL be refused `409 table_is_system` when the entry carries `write` or `modify`; its
other privileges, `rows` and `columns` SHALL be accepted (an enrichment, `system` or not, is governed by the
enrichment refusal below).

A `PUT` against an enrichment SHALL be refused `409 enrichment_follows_source`, naming the enrichment's source as the
table to configure instead, when the entry **as sent** carries `get`, `read` or `sensitive`, or a `rows` or `columns`
that is not `null` — so `"columns": []` and `"rows": {}` are `409`, not `422`. Nothing SHALL change. `GET` and `DELETE`
naming an enrichment SHALL behave as on any table, so state an earlier build stored there can be read and removed.

A `PUT`'s checks SHALL run in this order, the first failing one deciding the response:

1. a missing, blank or repeated `role` (see above), or a body that does not bind — malformed JSON, a member other than
   `generation`, `privileges`, `rows` and `columns`, a `rows` member other than `filter` and `where`, or a member of the
   wrong JSON type, including an item of `privileges` or `columns` that is not a JSON string (a number, `null` or a
   nested array) (`400 bad_request`). `generation` binds any JSON value and is judged by step 5;
2. a caller without `TABLE_GRANT` (`403 forbidden`);
3. an unknown table (`404 not_found`);
4. the enrichment refusal (`409 enrichment_follows_source`);
5. a `generation` that is absent, `null`, not a JSON integer, or outside the range of a signed 64-bit integer
   (`422 validation_error`);
6. an entry that passes every check below and equals, as it would be stored, the role's stored entry (an absent
   entry being the empty one): the request SHALL answer `200` with that entry and the table's current generation and
   SHALL change nothing, whatever `generation` it carries (see "Access configuration generation");
7. a conflicting `generation` (`409 access_conflict`, see "Access configuration generation");
8. the role (`422 validation_error`);
9. `write` or `modify` on a `system` table (`409 table_is_system`);
10. the remaining checks, each `422 validation_error` and in no specified order among themselves: an unknown privilege
   value, `*` with `write` or `modify`, a `rows` carrying both or neither of `filter` and `where`, the translation of a
   `where`, the row policy's own validation, and the column policy's validation.

A `DELETE`'s checks SHALL run in the order: a missing, blank or repeated `role` (`400 bad_request`), `403 forbidden`, unknown
table (`404 not_found`), a `generation` parameter that is not an integer within the signed 64-bit range
(`422 validation_error`), no entry for the role (`404 not_found`), and a conflicting `generation`
(`409 access_conflict`); a `DELETE` without a `generation` SHALL NOT answer `409 access_conflict`. A `GET` of one
role's entry SHALL check a blank or repeated `role` (`400`), `403`, then the table (`404`), then the entry (`404`);
`GET .../access` SHALL check `403`, then the table (`404`).

These rules constrain what may be stored, so they SHALL apply in `none` security mode too. Every `PUT` or `DELETE`
that changes the stored entry SHALL be recorded in the audit trail as one change to the table definition (see
[audit-trail](../audit-trail/spec.md)); a `PUT` that leaves the entry as it was SHALL record none.

#### Scenario: Create, read and remove an entry

- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/t/access` at `generation` 4, `PUT`s
  `{"generation": 4, "privileges": ["read"], "rows": {"where": "project_id = 'P-1'"}}` for role `X`, reads
  `GET /v1/tables/t/access?role=X`, then `DELETE`s `X`'s entry
- **THEN** the `PUT` answers `200` with `generation` 5, `privileges` `["read"]`, `rows` carrying the equivalent
  `filter` and `columns` `null`; the `GET` returns the same entry with `"role": "X"`; the `DELETE` answers `204`; and a
  further `GET /v1/tables/t/access` returns `"roles": {}` at `generation` 6

#### Scenario: A PUT replaces the whole entry

- **GIVEN** role `X`'s entry carries `read`, a `rows` policy and `columns` `["a", "b"]`
- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["read"]}` for `X`
- **THEN** the stored entry is `{"privileges": ["read"], "rows": null, "columns": null}`, and no other role's entry
  changed

#### Scenario: Privileges are de-duplicated and ordered

- **WHEN** a caller `PUT`s `"privileges": ["modify", "get", "modify"]` for a role
- **THEN** the response and every later read carry `["get", "modify"]`

#### Scenario: An empty entry removes the role

- **GIVEN** role `X` has an entry carrying `write`
- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": []}` for `X`
- **THEN** the response is `200` with an empty entry, `GET .../access?role=X` answers `404 not_found`, and `X` is
  absent from `GET .../access`
- **AND WHEN** a caller `PUT`s `{"generation": <current>, "privileges": []}` for role `Z`, which has no entry
- **THEN** the response is `200` with an empty entry and the generation unchanged, and no revision or activity is
  recorded for the table

#### Scenario: Reading one role that has no entry

- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/t/access?role=nobody`
- **THEN** the response is `404 not_found`

#### Scenario: Removing an absent entry is not found

- **WHEN** a caller holding `TABLE_GRANT` `DELETE`s the entry of a role that has none on the table
- **THEN** the response is `404 not_found`

#### Scenario: An unknown table is not found on reading or removing entries

- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/nope/access` or `GET /v1/tables/nope/access?role=X`,
  or `DELETE`s `/v1/tables/nope/access?role=X`, for a table that does not exist
- **THEN** each response is `404 not_found`

#### Scenario: A role containing a slash, percent sign or semicolon is an ordinary query value

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["read"]}` to
  `/v1/tables/t/access?role=%2Fanalytics%2Fviewers`, then to `?role=team%25ops`, then to `?role=a%3Bb`, in either
  security mode
- **THEN** each answers `200` with `role` `"/analytics/viewers"`, `"team%ops"` and `"a;b"` respectively,
  `GET /v1/tables/t/access` lists all three under those names, `GET` with each `role` value returns its entry, and a
  `DELETE` with each answers `204`

#### Scenario: A missing or blank role is a bad request

- **WHEN** a caller `PUT`s an entry to, or `DELETE`s, `/v1/tables/t/access` with no `role` parameter, or with
  `?role=%20`
- **THEN** the response is `400 bad_request`, before the privilege check, and nothing is stored
- **AND WHEN** a caller `PUT`s an entry to, `DELETE`s, or reads `/v1/tables/t/access?role=a&role=b`
- **THEN** each response is `400 bad_request`, and no entry for a role `a,b` or any other is stored

#### Scenario: The role is not a path segment

- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/t/access/X`
- **THEN** the response is `404 not_found`, as for any unknown path

#### Scenario: A role sent as a path segment meets the request firewall

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s an entry for role `a;b` as a path segment,
  `/v1/tables/t/access/a%3Bb`, instead of as `?role=a%3Bb`
- **THEN** the response is `400 bad_request` as an `ErrorView`, because the request firewall refuses an encoded `;` in
  any path, and nothing is stored

#### Scenario: The role is trimmed on read and removal

- **GIVEN** role `X` has an entry on `t`
- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/t/access?role=%20X%20`
- **THEN** the response is `X`'s entry with `"role": "X"`

#### Scenario: A stale generation is reported before an invalid role

- **GIVEN** an entry an earlier build stored for role `READ_ONLY_ANALYTIC`, removed after a client read the access
- **WHEN** that client `PUT`s an entry for `READ_ONLY_ANALYTIC` carrying the generation it read
- **THEN** the response is `409 access_conflict`, not `422`

#### Scenario: Two concurrent deletes of one entry

- **WHEN** two callers holding `TABLE_GRANT` `DELETE` the same role's entry concurrently
- **THEN** one receives `204` and the other `404 not_found`

#### Scenario: A DELETE does not conflict with a write to another role

- **GIVEN** entries for roles `X` and `Y`
- **WHEN** a caller's `PUT` for `Y`, carrying the current generation, locks the table's row first and a `DELETE` of
  `X`'s entry waits for it
- **THEN** the `PUT` answers `200`, the `DELETE` then answers `204`, and the generation has moved by two
- **AND WHEN** instead the `DELETE` commits first and the `PUT` carries the generation read before it
- **THEN** the `DELETE` answers `204`, the `PUT` answers `200`, because `Y`'s entry did not change after that read,
  and the generation has moved by two

#### Scenario: A caller without TABLE_GRANT is refused

- **WHEN** a caller holding `TABLE_READ` but not `TABLE_GRANT` reads `GET .../access`, reads `GET .../access?role=X`,
  `PUT`s or `DELETE`s an entry
- **THEN** each response is `403 forbidden`

#### Scenario: The privilege check precedes the table lookup

- **WHEN** a caller without `TABLE_GRANT` `PUT`s an entry for a table that does not exist
- **THEN** the response is `403 forbidden`

#### Scenario: An unknown table is reported before the generation and the body

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s, for a table that does not exist, a body with no `generation` whose
  `rows` carries both `filter` and `where`
- **THEN** the response is `404 not_found`

#### Scenario: A body that does not bind is refused first

- **WHEN** a caller `PUT`s `{"generation": 1, "list": ["x"]}`, or `{"generation": 1, "rows": {"all_rows": true}}`, or
  `{"generation": 1, "privileges": "read"}`, or `privileges` or `columns` given as `[["read"]]`, `[null]` or `[1]`
- **THEN** each response is `400 bad_request` naming the offending member where it is unknown, and nothing is stored

#### Scenario: A null privileges list means none

- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": null, "columns": ["a"]}` for a role
- **THEN** the response is `200` and the entry carries `privileges` `[]` and `columns` `["a"]`

#### Scenario: A bundle name is not a role

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s an entry with the current generation for role `READ_ONLY_ANALYTIC`
- **THEN** the response is `422 validation_error`

#### Scenario: The role is checked before a system table's write refusal

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["write"]}` for role
  `READ_ONLY_ADMIN` on `dial_usage_log`
- **THEN** the response is `422 validation_error`

#### Scenario: Read grants on a system table are accepted

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["read"], "columns":
  ["project_id", "model"]}` for `analytics-viewers` on `dial_usage_log`
- **THEN** the response is `200` and the entry is stored

#### Scenario: A write grant on a system table is refused

- **WHEN** a caller `PUT`s an entry carrying `write` for any role on a `system` table
- **THEN** the response is `409 table_is_system` and nothing is stored

#### Scenario: The system refusal precedes the other body checks

- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": ["write"]}` for `*` on `dial_usage_log`
- **THEN** the response is `409 table_is_system`, not `422`

#### Scenario: A read-side entry on an enrichment is refused

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s, for an enrichment, an entry carrying `get`, `read` or `sensitive`, a
  non-`null` `rows`, or a non-`null` `columns`, including `"columns": []`
- **THEN** each response is `409 enrichment_follows_source`, the message names the enrichment's source, and nothing is
  stored

#### Scenario: The enrichment refusal precedes the generation and the body

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s for an enrichment `{"privileges": ["read"]}` with no `generation`, or
  a body with a stale `generation` and `rows` carrying both `filter` and `where`, or an entry for role
  `READ_ONLY_ANALYTIC` carrying `rows`
- **THEN** each response is `409 enrichment_follows_source`

#### Scenario: An enrichment's write and modify are still granted

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"generation": <current>, "privileges": ["write"]}` for `labeler` on
  an enrichment
- **THEN** the response is `200` and the entry carries `["write"]`

#### Scenario: State left on an enrichment is read and removed

- **GIVEN** an enrichment on which an earlier build stored, for role `R`, a `read` grant and a row policy
- **WHEN** a caller holding `TABLE_GRANT` reads `GET .../access?role=R` on it and then `DELETE`s that entry
- **THEN** the read returns the entry as stored, and the `DELETE` answers `204`

#### Scenario: The response carries no permissions member

- **WHEN** a `PUT` succeeds
- **THEN** its body carries `generation`, `role`, `privileges`, `rows`, `columns` and `ineffective_grants`, and no
  `permissions`

#### Scenario: An ineffective grant is reported

- **WHEN** a caller `PUT`s `read` for a role that already holds `TABLE_READ` globally
- **THEN** the response is `200` and `ineffective_grants` carries `{"role": <that role>, "key": "read", "reason":
  "already_global", ...}`

#### Scenario: A clearance without a verb is reported

- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": ["sensitive"]}` for `pii-analysts` on a table on
  which that role holds no `get`, `read` or `modify`, globally or by any entry
- **THEN** the response is `200`, the entry is stored, and `ineffective_grants` carries an entry with `key:
  "sensitive"` and `reason: "sensitive_without_verb"`

#### Scenario: A source clearance used by an enrichment maintainer is not reported

- **GIVEN** a role holding nothing on a source and `modify` on one of its enrichments
- **WHEN** a caller `PUT`s `sensitive` for that role on the source
- **THEN** `ineffective_grants` carries no `sensitive_without_verb` entry for it

#### Scenario: A read for everyone that lifts column policies is reported

- **GIVEN** a table on which role `X`'s entry carries `columns` and `*` has no entry
- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": ["read"]}` for `*`
- **THEN** the entry is stored and `ineffective_grants` carries an entry for `*` with `key: "read"` and `reason:
  "lifts_column_policies"`
- **AND WHEN** the same `PUT` also carries `columns`, or `rows`
- **THEN** no such entry is reported

#### Scenario: Repeating a PUT records no revision

- **WHEN** a caller `PUT`s for a role exactly the entry it already has, with the current generation or an older one
- **THEN** the response is `200` with the entry and the generation unchanged, and no revision or activity is recorded
  for the table

### Requirement: Access configuration generation

Each table SHALL carry one access `generation`, a non-negative integer covering every role's entry on it. It SHALL be
`0` for a table created after this capability and for every table that existed before it. `GET .../access`,
`GET .../access?role=<role>` and every successful `PUT` response SHALL carry it.

The service SHALL also record, for each role, the generation at which that role's entry last changed: its
**last-change mark**. A change that removes the entry SHALL set the mark too, and the mark SHALL be kept after the entry
is gone, so a client that read the entry before its removal still conflicts. A role whose entry has not changed since
this capability has mark `0`. No endpoint SHALL return the marks.

A `PUT /v1/tables/{name}/access?role=<role>` SHALL carry the `generation` the client last read. A `generation` that is
absent, `null`, not a JSON integer (`"7"`, `7.5`, `7.0`), or outside the range of a signed 64-bit integer SHALL be refused
`422 validation_error`. Otherwise:

1. when the entry the request would store equals the role's stored entry (an absent entry being the empty one), the
   request SHALL answer `200` with that entry and the current generation, SHALL write and record nothing, and SHALL
   leave the generation and the marks as they were, whatever `generation` it carries, so a client may retry a `PUT`
   whose response it lost;
2. otherwise, when the `generation` is greater than the table's, which no client can have read, or less than the
   role's last-change mark, the request SHALL be refused `409 access_conflict` and nothing SHALL change;
3. otherwise the entry SHALL be written, the table's generation incremented by exactly one, and the role's mark set
   to the new generation.

A change to another role's entry after the client's read SHALL NOT make its `PUT` conflict. The `409` message SHALL
tell the caller to read the access again and reapply its change, and SHALL NOT name a generation, neither the current
one nor the one sent, so a client re-reads rather than retrying with a fresher number.

A `DELETE /v1/tables/{name}/access?role=<role>` MAY carry a `generation` query parameter. Without it the removal SHALL
NOT be conditioned on any generation. With it, a value that is not an integer within the signed 64-bit range SHALL be
refused `422 validation_error`; a role with no entry SHALL still answer `404 not_found`; and a value greater than the
table's generation, or less than the role's last-change mark, SHALL be refused `409 access_conflict` with nothing
changed. A successful `DELETE` SHALL increment the generation by exactly one and set the role's mark to the new
generation.

A schema operation that rewrites a stored entry's `columns` (see [table-management](../table-management/spec.md),
"Column policies follow a rename and refuse an emptying drop") SHALL increment, by exactly one, the generation of the
table **whose entries it rewrites**, and SHALL set the mark of every role whose list it changed to the new generation;
a role whose list it left as it was keeps its mark. Such operations are a rename or drop through
`PATCH /v1/tables/{name}/schema`, a re-define through `POST /v1/tables/{name}/schema` that drops a listed column, and
the delete of an enrichment. For an enrichment's column and for an enrichment's delete, those entries are its
**source's** (they name the column as `<enrichment>.<column>`), so the source's generation and marks move and the
enrichment's do not. A catalog import called with `include_access=true` (see
[catalog-export-import](../catalog-export-import/spec.md), "Access import") SHALL move them exactly as a `PUT` of each
entry it imports would, while carrying and checking no client `generation`: by one, setting that role's mark, for each
entry it changes, and not at all for an entry equal to the stored one. Nothing else SHALL change a generation or a mark: a `PUT` that changes no entry, a description
update, a schema operation that rewrites no entry, a row write and a pipeline change leave them as they were, so none
of them makes a concurrent access edit conflict. A refused request SHALL change nothing.

The check and the write SHALL be atomic: of two writes to the same role that read the same generation and would each
change its entry, exactly one SHALL succeed and the other SHALL answer `409 access_conflict`, while two writes to
different roles from the same read SHALL both succeed. Of two concurrent `DELETE`s of one entry without a
`generation`, one answers `204` and the other `404 not_found`.

The generation and the marks SHALL NOT be part of the audited state, of a revision snapshot or of a catalog export.

#### Scenario: A PUT with the current generation succeeds

- **GIVEN** a table at access `generation` 7
- **WHEN** a caller holding `TABLE_GRANT` `PUT`s a changed entry with `generation` 7
- **THEN** the response is `200` with `generation` 8

#### Scenario: A stale PUT is refused and changes nothing

- **GIVEN** table `usage` at access `generation` 42, on which role `viewers`' entry last changed at generation 42
- **WHEN** a caller `PUT`s a different entry for `viewers` with `generation` 41
- **THEN** the response is `409 access_conflict`, its message names no generation (it contains neither `42` nor
  `41`), and a subsequent read returns every entry unchanged at `generation` 42

#### Scenario: A PUT without a usable generation is refused

- **WHEN** a caller `PUT`s an entry whose `generation` is absent, `null`, `"7"`, `7.5`, `7.0` or `9223372036854775808`
- **THEN** the response is `422 validation_error` and nothing is stored

#### Scenario: The generation is checked before the rest of the body

- **WHEN** a caller `PUT`s, for a role whose entry changed after the `generation` it carries, `"columns": []`
- **THEN** the response is `409 access_conflict`, not `422`

#### Scenario: A missing generation is reported before staleness and the body

- **WHEN** a caller `PUT`s an entry with no `generation` and `"columns": []`
- **THEN** the response is `422 validation_error` naming `generation`

#### Scenario: Two writes from the same read

- **GIVEN** two clients that both read a table's access at `generation` 5
- **WHEN** one `PUT`s an entry for role `X` and the other an entry for role `Y`, both with `generation` 5
- **THEN** both receive `200`, and the table is at `generation` 7

#### Scenario: Two writes to one role from the same read

- **GIVEN** two clients that both read a table's access at `generation` 5
- **WHEN** both `PUT` different entries for role `X` with `generation` 5
- **THEN** exactly one receives `200` and the other `409 access_conflict`

#### Scenario: A retried PUT is answered without a write

- **GIVEN** a `PUT` for role `X` carrying `generation` 5 succeeded, moving the table to 6, and role `Y` changed after
  it, moving the table to 7
- **WHEN** the client sends the identical `PUT` again, carrying `generation` 5
- **THEN** the response is `200` with `X`'s entry and `generation` 7, the generation does not move, and no revision is
  recorded
- **AND WHEN** it `PUT`s a different entry for `X` carrying `generation` 5
- **THEN** the response is `409 access_conflict`

#### Scenario: A generation above the table's is refused

- **GIVEN** a table at access `generation` 3
- **WHEN** a caller `PUT`s a changed entry, or `DELETE`s an entry, carrying `generation` 4
- **THEN** the response is `409 access_conflict` and nothing changes

#### Scenario: A DELETE moves the generation

- **GIVEN** a table at access `generation` 3 with an entry for role `X`
- **WHEN** a caller `DELETE`s `X`'s entry
- **THEN** a subsequent `GET .../access` returns `generation` 4, a `PUT` for `X` carrying `generation` 3 answers
  `409 access_conflict`, and a `PUT` for role `Y`, unchanged since, carrying `generation` 3 answers `200`

#### Scenario: A DELETE with a generation is conditional on its own role

- **GIVEN** a table at access `generation` 6, on which role `X`'s entry last changed at generation 5 and role `Y`'s at
  generation 6
- **WHEN** a caller sends `DELETE .../access?role=X&generation=5`
- **THEN** the response is `204`
- **AND WHEN** it sends `DELETE .../access?role=Y&generation=5`
- **THEN** the response is `409 access_conflict` and `Y`'s entry is kept
- **AND WHEN** it sends `DELETE .../access?role=Y&generation=5.0`, or `DELETE .../access?role=nobody&generation=7`
- **THEN** the responses are `422 validation_error` and `404 not_found` respectively

#### Scenario: A DELETE without a generation is unconditional

- **GIVEN** role `Y`'s entry changed after a client read the access
- **WHEN** the client `DELETE`s `Y`'s entry without a `generation`
- **THEN** the response is `204`

#### Scenario: A schema change does not move the generation

- **GIVEN** a table at access `generation` 2 whose entries name no column the change touches
- **WHEN** its description is updated and a column is added
- **THEN** `GET .../access` still returns `generation` 2, and a `PUT` carrying `generation` 2 succeeds

#### Scenario: A rename marks only the roles whose lists it rewrote

- **GIVEN** a table at access `generation` 2 on which role `X`'s entry lists `a` and role `Y`'s does not
- **WHEN** column `a` is renamed
- **THEN** the table is at `generation` 3, a `PUT` changing `X`'s entry carrying `generation` 2 answers
  `409 access_conflict`, and a `PUT` changing `Y`'s entry carrying `generation` 2 answers `200`

#### Scenario: An enrichment column rename moves the source's generation

- **GIVEN** `dial_usage_log` at access `generation` 4, on which role `X`'s entry names `deployment_ref.display_name`,
  and its enrichment `deployment_ref` at access `generation` 1
- **WHEN** `deployment_ref`'s column `display_name` is renamed to `label`
- **THEN** `dial_usage_log` is at `generation` 5 and `deployment_ref` still at `generation` 1

#### Scenario: A catalog import moves the generation as PUTs would

- **GIVEN** a table at access `generation` 3 with entries for roles `X` and `Y`
- **WHEN** a catalog import with `include_access=true` replaces `X`'s entry and carries an entry for `Y` equal to the stored one
- **THEN** the table is at `generation` 4, a `PUT` of a different entry for `X` carrying `generation` 3 answers `409 access_conflict`,
  and a `PUT` of a different entry for `Y` carrying `generation` 3 answers `200`

### Requirement: Per-role column policies

An entry's `columns` SHALL be the role's **column policy**: `null` places no restriction on the columns the role sees,
and a non-empty list restricts it to the listed columns, as "Column visibility by caller role" evaluates. The policy
SHALL be an allowlist only: there SHALL be no form that excludes or denies a column. An empty list SHALL be refused
`422 validation_error`, because a role meant to see no column should not read the table.

Each name SHALL be either a catalog column the table stores — sensitive and heavy columns included — or
`<enrichment>.<column>`, naming a catalog column of an enrichment whose `source_table` is this table. A name SHALL be a
column's **exposed** `name`, the name `GET /v1/tables/{name}` returns and queries use, never its physical
`source_name` where the two differ, and SHALL be matched exactly as the catalog stores that exposed name,
case-sensitively, everywhere a column policy is checked or evaluated. Every judge maps through it: policy validation, the
rename and drop rewriting (see [table-management](../table-management/spec.md), "Column policies follow a rename and
refuse an emptying drop"), the query model, and every path that holds a physical name — the scan's `identity_column`,
`version_column` and group key, or a raw-name fallback in a projection — which SHALL judge the catalog column that name
maps to by that column's exposed name, checking both spellings as the sensitive-column guard does. A name
that is blank, not stored, or qualified by a table that is not an enrichment of this table SHALL be refused
`422 validation_error` naming it; a physical column the catalog does not expose, such as an enrichment's grain key or
alignment column, is not stored in this sense. A reserved `_`-prefixed column the catalog does expose — the seeded
`dial_usage_log` exposes `_ingested_at`, its `version_column`, and `_source_file` — is a stored column like any other:
it may be listed, and a list that omits it hides it. Names SHALL be de-duplicated and stored and returned in sorted
order.

The policy SHALL be validated against the table's stored columns and those of its enrichments, independent of what the
writing caller may itself see. An enrichment SHALL accept no column policy (see "Role-keyed access API"): its columns
are governed by its source's entries. Column policies SHALL be storable on `system` tables other than enrichments.
Deleting a table SHALL remove its column policies.

#### Scenario: An allowlist is stored

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `"columns": ["model", "project_id", "model"]` for role `X` on
  `dial_usage_log`
- **THEN** the entry is stored with `columns` `["model", "project_id"]`

#### Scenario: An empty allowlist is refused

- **WHEN** a caller `PUT`s `"columns": []` for a role on a source table
- **THEN** the response is `422 validation_error` and nothing is stored

#### Scenario: An unknown column is refused

- **WHEN** a caller `PUT`s `columns` naming a column the table does not store, a stored column spelled in another
  case, or `""`
- **THEN** the response is `422 validation_error` naming the value, and nothing is stored

#### Scenario: A list names exposed names, not physical ones

- **GIVEN** a table whose `identity_column` is `event_id` and whose `version_column` is a column with exposed `name`
  `ingest_time` and physical `source_name` `ingested`, and a row-grain pipeline reading it whose target is keyed on
  `event_id` and not aligned and whose declaration names only `project_id`
- **WHEN** a caller `PUT`s `"columns": ["event_id", "ingest_time", "project_id"]` for role `X`, and then
  `"columns": ["event_id", "ingested", "project_id"]`
- **THEN** the first is stored and the second is refused `422 validation_error` naming `ingested`
- **AND WHEN** a runner holding `read` on the table only through `X`, with the first list, requests a scan of the
  pipeline
- **THEN** the scan succeeds
- **AND WHEN** `X`'s list is replaced by `["event_id", "project_id"]` and the runner requests the scan again
- **THEN** the response is `403 column_not_entitled` naming `ingest_time`, never `ingested`
- **AND WHEN** `ingest_time` is renamed to `loaded_at` while `X`'s list names it
- **THEN** `X`'s entry names `loaded_at`

#### Scenario: An enrichment column is named by its qualified name

- **GIVEN** `dial_usage_log_payload`, an enrichment of `dial_usage_log` with column `request_body`
- **WHEN** a caller `PUT`s `"columns": ["project_id", "dial_usage_log_payload.request_body"]` on `dial_usage_log`
- **THEN** the entry is stored
- **AND WHEN** the list instead names `request_body` unqualified, or `other_table.request_body` for a table that is not
  an enrichment of `dial_usage_log`
- **THEN** the response is `422 validation_error`

#### Scenario: A sensitive column may be listed by a caller without the clearance

- **WHEN** a caller holding `TABLE_GRANT` but no clearance lists a sensitive column of the table
- **THEN** the entry is stored, because it is validated against the stored columns rather than the caller's view

### Requirement: Column visibility by caller role

In `oidc` mode, which of a table's columns a caller sees SHALL be decided from the column policies of its **governing
roles** on that table:

- when the caller may read the table, its governing roles SHALL be those of its raw provider roles, `*` included, that
  **may read the table** as "Row-policy evaluation by caller role" defines it — named by the table's `read` grant list,
  or mapped by a configured `roles-mapping` to `TABLE_READ` globally while the caller holds `TABLE_READ` globally on the
  request;
- when the caller may not read the table, they SHALL be the roles through which it holds `TABLE_GET` there: named by
  the `get`, `read` or `modify` grant list (a `*` in `modify` excepted), or mapped to a privilege conferring `TABLE_GET`
  globally while the caller holds `TABLE_GET` globally.

A role mapped to a global privilege SHALL be judged as row-policy evaluation judges it, with the same cross-provider
approximation: a role name some configured provider maps to the privilege counts for any caller that holds the
privilege globally on the request, whichever of its roles conferred it.

A governing role **allows** a column when it has no column policy on the table, or has one that lists the column; it
is **row-unrestricted** when it has no row policy on the table. A **row-contributing** role is one the caller carries
whose row policy enters the caller's row filter (see "Row-policy evaluation by caller role") but which does not
govern, such as an entry carrying `rows` on a role with no read path. It allows a column when its own column policy
allows it **and** every governing role allows it, so it only narrows and never widens. Each of the table's columns
SHALL be, for the caller, exactly one of:

- **visible**, when some row-unrestricted governing role allows it, or when every governing role and every
  row-contributing role allows it;
- **masked**, when it is not visible and at least one governing role allows it;
- **hidden**, when no governing role allows it.

Each entry is one unit — a role's readers see that role's columns on that role's rows — and the rows a caller reads
are the union of its roles' row reach (see "Row-policy evaluation by caller role"). A visible column SHALL read its
stored value on every row the caller reads. A masked column's **mask condition** SHALL be the disjunction of the row
policies of the governing roles and the row-contributing roles that allow it, every one of which carries a row
policy, since a row-unrestricted governing role allowing the column would make it visible. On each row the caller reads, a masked column SHALL read its stored value
when the mask condition is true on the row and SHALL be **absent** otherwise — where it is false and where it is
unknown alike — so a masked column is never shown on a row that no governing or row-contributing role allowing it
admits. The mask condition SHALL be
evaluated with the caller values the row filter is evaluated with on the same request (see "Caller values resolve per
read and an absent one is unknown"), so a policy whose caller value is unknown, or whose comparison is unknown on a
row because a column it reads is null there, admits that row to the mask no more than it admits it to the row filter. How each surface returns an absent cell, and how a query
reads one, is specified by that surface: see [structured-query](../structured-query/spec.md), "A masked field reads as
absent at every reference", and the CSV-export and Flight SQL capabilities. A hidden column SHALL be treated as
"Hidden columns are absent from every read surface" states; a masked column is not hidden.

When no governing role carries a row policy, no column is masked and the visible columns are the union of the
governing roles' lists.

A column that would be masked SHALL be hidden instead, as the rule without masking would decide, when any table of its
**entity** — the governed table (the column's own table, or for an enrichment column its source) and every enrichment
registered on it, whatever its status — stores a column whose physical name (`source_name`) equals the name of any
table of that entity. That covers a source column named after the source or an enrichment, and an enrichment column
named after its own enrichment. A query names the governed
table's columns, and each enrichment's, by that table's name, and the data store resolves such a qualified name to the
like-named column of the source, reading that column's subcolumns in place of the named table's columns. A mask
could then admit a row its policy does not, or return a source column's derived value as an enrichment value. Hiding
keeps the guarantee on such a table, at the cost of the columns masking would have shown there.

Gaining a role SHALL NOT remove a column from the rows the caller's other roles admit: a column a confined role allows
stays readable on that role's rows, whatever other roles the caller gains — except on a table where the hiding fallback
above applies, which keeps the rule without masking. The rule applies
unchanged to a caller that may not read the table, whose governing roles are get-governing; such a caller reads no
rows, so for it a masked column differs from a visible one in being reported masked and counted in
`columns_restricted`, in the refusals a query over a masked field meets on translation and validation (see
[structured-query](../structured-query/spec.md)), in the TTL refusal below, which a caller holding `modify` without
`read` meets too, and in the pipeline refusal (see "Pipeline paths refuse a masked column"). A role that does not
govern — an IdP group, a role conferring only `write`, a role conferring only `get` to a caller that may read — SHALL
take no part, so it SHALL NOT lift a restriction and its row policy SHALL NOT enter a mask condition, unless it is a
row-contributing role. A role whose entry carries `rows` but which has no read path adds its rows to a confined caller
carrying it, by row-policy evaluation, and its `columns` then narrow what those rows show: a column its list leaves out
is masked, and absent on the rows only it adds. A rows-only entry whose `columns` is `null` narrows nothing: it shows
the governing roles' columns on the rows it adds. A caller with no governing role is covered
below.

A masked column SHALL be listed, named and referenced wherever a visible column is: schema discovery (which marks it,
see [query-schema-discovery](../query-schema-discovery/spec.md), "A masked field is marked"), the catalog read and
revision snapshots, `column_count`, the schema the query, translation, CSV-export and Flight SQL surfaces resolve
against, a schema change other than as a TTL column, catalog export and an import entry's `prev`. Where a
requirement elsewhere speaks of the columns "visible" to a caller — on those schema surfaces, or as the columns the
verbs `get`, `read` and `modify` act on (see "Per-table privilege grants") — a masked column is among them, its values
readable on the rows its mask admits. A TTL SHALL NOT be over a masked column: a `ttl.column` naming it is refused
`403 column_not_entitled`, and a stored TTL on it refuses every `ttl` member, because watching which rows a TTL removes
would disclose stored values of absent cells. The remaining
exceptions are reporting, where it
counts as restricted (see "Caller-effective privilege reporting"), and the paths that read without a row filter, which
refuse it (see "Pipeline paths refuse a masked column").

Masking discloses, row by row, whether a governing or row-contributing role that allows the column admits the row: a
cell is present
exactly where its mask condition is true. On the rows that role itself admits, this is what its row reach already
discloses. On rows only another of the caller's roles admits, it is new, and it holds even when the policy reads a
column the caller cannot see, such as a sensitive one. That disclosure is accepted as the cost of showing each role's
columns on its own rows, as the persisted literal of a saved query is accepted (see
[saved-queries](../saved-queries/spec.md), "A sensitive value may be persisted in a saved query body").

A column policy SHALL apply to its role whatever path that role reads the table by, a global privilege included, so
confining a broadly-entitled role takes an entry on that role. A `*` entry's `columns` therefore confine only callers
that get or read the table through `*`'s grant: `*` holds no global privilege, so a `*` entry carrying `columns` and no
privileges confines no one.

A caller with **no governing role** on a table — one holding neither `TABLE_GET` nor `TABLE_READ` there, such as a
caller holding only `TABLE_LIST`, or a pipeline author holding only `REGISTRY_ADMIN` — SHALL NOT be narrowed by the
table's column policies: every column is visible to it as far as column policies go, the clearance still applies, and
its `columns_restricted` is `false`. The verb bars already decide what such a caller reaches; column policies narrow
only a caller that gets or reads the table through at least one governing role.

A computed field, which is not a catalog column and so cannot be named by a policy, SHALL be visible exactly when every
catalog column whose values it returns is visible — for the computed fields of `dial_usage_log_payload`, its
`request_body` and `request_cache_control`, named in a source entry as `dial_usage_log_payload.request_body` and
`dial_usage_log_payload.request_cache_control`. It SHALL be masked when none of those columns is hidden and at least
one is masked, and absent on every row where any of them is absent; otherwise it SHALL be hidden.

The clearance SHALL stack on top: a sensitive column SHALL be visible or masked only when the column policies make it
so **and** the caller holds the clearance on the table; without the clearance it is hidden. A column policy SHALL NOT
grant anything: whether the caller may get or read the table at all is decided by its privileges, unchanged.

For an enrichment, the governing roles and their policies SHALL be its source's, the policy naming the enrichment's
column `c` as `<enrichment>.c`; when the caller may not read the source, a role holding `get` on the enrichment through
the enrichment's own `modify` list SHALL also govern it, as a row-unrestricted role with no column policy. A masked
enrichment column's mask condition SHALL be evaluated on the source row its value is joined to, so an enrichment value
is present exactly where the source row satisfies it.

Column policies SHALL narrow what a caller sees and acts on through `get`, `read` and `modify` — the verbs the
clearance extends — and SHALL NOT narrow `write`: a row insert is judged as before. They SHALL NOT apply to the system
identity that background work runs under, so a pipeline's own reads see every column. In `none` security mode they
SHALL NOT be evaluated and every column SHALL be visible.

#### Scenario: A role sees only its listed columns

- **GIVEN** a table with columns `a`, `b` and `c`, and an entry for role `X` carrying `read` and `columns` `["a", "b"]`
- **WHEN** a caller carrying only `X` runs `SELECT * FROM t`
- **THEN** the result has columns `a` and `b` only
- **AND WHEN** it selects or filters on `c`
- **THEN** the query fails as it does for an unknown field

#### Scenario: Two roles' lists are combined when neither carries a row policy

- **GIVEN** entries for `X` with `read` and `columns` `["a"]`, and for `Y` with `read` and `columns` `["b"]`, neither
  carrying `rows`
- **WHEN** a caller carrying both requests the entity's schema
- **THEN** it carries `a` and `b`, and not `c`

#### Scenario: One role's columns are not shown on another role's rows

- **GIVEN** a table with columns `project`, `a` and `c`; an entry for `R` with `read`, `rows` admitting `project = 'P1'`
  and `columns` `["a", "project"]`; and an entry for `U` with `read`, `rows` admitting `project = 'P2'` and `columns`
  `null`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads the rows of `P1` and of `P2` with columns `project`, `a` and `c`, where `c` carries its stored value
  on the rows of `P2` and is absent on the rows of `P1`, and its `GET /v1/tables/t` reports
  `permissions.columns_restricted = true`

#### Scenario: A role with no row policy shows the columns it allows on every row

- **GIVEN** an entry for `R` with `read`, `rows` admitting `project = 'P1'` and `columns` `["a", "project"]`, and an
  entry for `U` with `read`, no `rows` and `columns` `null`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads every row with every column

#### Scenario: A column every confining role lists is visible

- **GIVEN** a table with columns `project`, `a`, `b`, `c` and `d`; an entry for `R` with `read`, `rows` admitting
  `project = 'P1'` and `columns` `["a", "c"]`; and an entry for `U` with `read`, `rows` admitting `project = 'P2'` and
  `columns` `["b", "c"]`
- **WHEN** a caller carrying both requests the entity's schema
- **THEN** it lists `a`, `b` and `c`, with `a` and `b` marked masked, and lists neither `project` nor `d`
- **AND WHEN** it queries the table
- **THEN** it reads the rows of `P1` and of `P2`; `c` carries its stored value on every row, `a` only on the rows of
  `P1` and `b` only on the rows of `P2`, each absent on the other project's rows

#### Scenario: Gaining a column-restricted role masks a confined role's columns to its rows

- **GIVEN** a table with columns `project`, `x` and `y`; an entry for `A` with `read`, `rows` admitting
  `project = 'P1'` and `columns` `null`; and an entry for `B` with `read`, no `rows` and `columns` `["x"]`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads every row with columns `project`, `x` and `y`; `x` carries its stored value on every row, and
  `project` and `y` carry theirs on the rows of `P1` and are absent on every other row, because only `A` allows them
- **AND WHEN** `B`'s entry also carries `rows`, admitting `project = 'P2'`
- **THEN** it reads the rows of `P1` and of `P2`; `x` carries its stored value on both, and `project` and `y` on the
  rows of `P1` only
- **AND WHEN** instead `B`'s `columns` list `x`, `y` and `project`
- **THEN** it reads every row with all three columns, none absent

#### Scenario: Gaining a column-restricted role can hide a confined role's columns

- **GIVEN** a table `t` with columns `project`, `t`, `x` and `y`, so the hiding fallback applies; an entry for `A`
  with `read`, `rows` admitting `project = 'P1'` and `columns` `null`; and an entry for `B` with `read`, no `rows`
  and `columns` `["x"]`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads every row with column `x` only, and nothing is marked masked, because masking would show `A`'s
  other columns on the rows only `B` admits and the fallback keeps the rule without masking

#### Scenario: A masked column follows the caller value of the policy that allows it

- **GIVEN** a table whose `read` list is `["*", "V"]`: a `*` entry with `read`, `rows` admitting
  `user_hash = caller.user_hash()` and `columns` `null`, and an entry for `V` with `read`, `rows` admitting
  `project = 'P2'` and `columns` `["project"]`
- **WHEN** a caller carrying `V` whose own `user_hash` is `u1` queries the table
- **THEN** it reads its own rows and the rows of `P2`; `project` carries its stored value on every one of them, and
  every other column carries its stored value on the rows whose `user_hash` is `u1` and is absent on the rest
- **AND WHEN** the same caller's `user_hash` is unknown on the request
- **THEN** it reads the rows of `P2` only, with every column but `project` absent on all of them, and each such cell
  is reported absent by the surface that returns it

#### Scenario: A table with a column named after it hides instead of masking

- **GIVEN** a table `t` that stores a column named `t`, an entry for `A` with `read`, `rows` admitting `p = 'P1'` and
  `columns` `null`, and an entry for `B` with `read`, `rows` admitting `p = 'P2'` and `columns` `["p"]`
- **WHEN** a caller carrying both requests the entity's schema
- **THEN** it lists `p` only, as the intersection of the two lists, and nothing is marked masked

#### Scenario: An older revision lists a masked column

- **GIVEN** a caller whose governing roles on a non-system table mask a column under the current policies
- **WHEN** it reads an older revision snapshot of the table, recorded before the column policies existed
- **THEN** the snapshot lists the masked column, as the current catalog read does

#### Scenario: A null policy column makes the cell absent

- **GIVEN** an entry for `A` with `read`, `rows` admitting `route_name = 'r1'` and `columns` `null`, and an entry for `B`
  with `read`, `rows` admitting `project = 'P2'` and `columns` `["project"]`, and a row of `P2` whose `route_name` is
  null
- **WHEN** a caller carrying both queries the table
- **THEN** on that row every column but `project` is absent and reported absent, exactly as on a row of `P2` whose
  `route_name` is `r2`

#### Scenario: A caller that only gets the table lists a masked column

- **GIVEN** an entry for `G1` with `get`, `rows` admitting `project = 'P1'` and `columns` `["a"]`, and an entry for `G2`
  with `get`, `rows` admitting `project = 'P2'` and `columns` `["b"]`
- **WHEN** a caller carrying both and no read on the table reads `GET /v1/tables/t`
- **THEN** the definition lists `a` and `b`, and `permissions.columns_restricted` is `true`

#### Scenario: A rows-only entry without a read path adds rows but no columns

- **GIVEN** an entry for `X` with `read`, `rows` admitting `project = 'P1'` and `columns` `["a", "project"]`, and an
  entry for `N` with no privileges, `rows` admitting `project = 'P2'` and `columns` `null`, where no global privilege
  gives `N` a read
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads the rows of `P1` and of `P2`, with columns `a` and `project` only
- **AND WHEN** `N`'s `columns` are `["project"]` instead
- **THEN** it reads the rows of `P1` and of `P2`; `project` carries its stored value on every row, and `a` carries its
  stored value on the rows of `P1` and is absent on the rows of `P2`, which only `N` adds

#### Scenario: An unrestricted reading role lifts the restriction

- **GIVEN** an entry for `X` with `read` and `columns` `["a"]`, and role `Y`, mapped to `TABLE_READ` globally, with no
  entry
- **WHEN** a caller carrying both queries the table
- **THEN** it sees `a`, `b` and `c`

#### Scenario: A role that only gets the table does not widen a reader's columns

- **GIVEN** an entry for `X` with `read` and `columns` `["a"]`, and an entry for `G` with `get` only
- **WHEN** a caller carrying both requests the entity's schema and queries the table
- **THEN** both show `a` only

#### Scenario: A global reader is confined by its role's column policy

- **GIVEN** provider role `X` mapped to `READ_ONLY_ANALYTIC`, and an entry for `X` with no privileges and `columns`
  `["a"]`
- **WHEN** a caller carrying only `X` queries the table
- **THEN** it sees `a` only

#### Scenario: A caller that only gets the table is shown its listed columns

- **GIVEN** an entry for `G` with `get` and `columns` `["a"]`
- **WHEN** a caller carrying only `G` reads `GET /v1/tables/t`
- **THEN** the definition lists `a` only

#### Scenario: The clearance still applies to a listed column

- **GIVEN** a sensitive column `s` and an entry for `X` with `read` and `columns` `["a", "s"]`, and no clearance for `X`
- **WHEN** a caller carrying only `X` requests the schema
- **THEN** it carries `a` and not `s`
- **AND WHEN** `X` is granted `sensitive`
- **THEN** it carries `a` and `s`
- **AND WHEN** instead `X`'s entry also carries `rows`, and the caller carries a second reading role with `rows` and
  `columns` `["a"]`, without the clearance
- **THEN** it carries `a` and not `s`: the clearance hides `s`, which is not masked

#### Scenario: A source's allowlist hides the enrichment columns it does not name

- **GIVEN** an entry for `X` on `dial_usage_log` with `read` and `columns` `["project_id", "deployment_ref.display_name"]`
- **WHEN** a caller carrying only `X` requests the entity's schema
- **THEN** it carries `project_id` and `"deployment_ref.display_name"`, and no other column of the source or of its
  enrichments

#### Scenario: An enrichment maintainer sees the columns it maintains

- **GIVEN** a caller whose only role is in an enrichment's `modify` list and in no entry of its source
- **WHEN** it reads `GET /v1/tables/{enrichment}`
- **THEN** every non-sensitive column of the enrichment is listed

#### Scenario: A write is not narrowed

- **GIVEN** an entry for `W` with `write` and `columns` `["a"]`
- **WHEN** a caller carrying only `W` inserts a row with values for `a` and `b`
- **THEN** the insert succeeds

#### Scenario: A column policy does not grant a read

- **GIVEN** an entry for `Z` with no privileges and `columns` `["a"]`, and no global read for `Z`
- **WHEN** a caller carrying only `Z` queries the table
- **THEN** the query fails as though the table did not exist

#### Scenario: A caller with no governing role is not narrowed

- **GIVEN** a table with columns `a`, `b` and `c`, none sensitive, on which role `X`'s entry carries `read` and
  `columns` `["a"]`
- **WHEN** a caller holding `TABLE_LIST` globally and nothing else, carrying no role with an entry, lists the catalog
- **THEN** the table's list item carries `column_count: 3` and every flag in its `permissions` is `false`,
  `columns_restricted` included

#### Scenario: A computed field follows the columns it returns

- **GIVEN** an entry for `X` on `dial_usage_log` with `read` and `columns` naming `dial_usage_log_payload.request_body`
  but not `dial_usage_log_payload.request_cache_control`, and the clearance where those columns are sensitive
- **WHEN** a caller carrying only `X` requests the entity's schema
- **THEN** it carries `dial_usage_log_payload.request_body` and neither of the computed fields
  `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild`
- **AND WHEN** the list names both columns
- **THEN** it carries both computed fields
- **AND WHEN** instead `X`'s `columns` name `dial_usage_log_payload.request_body` only, as at first, its entry also
  carries `rows` admitting `project_id = 'P2'`, and the caller also carries `Y`, whose entry carries `read`, `rows`
  admitting `project_id = 'P1'` and `columns` `null`
- **THEN** both computed fields are listed and marked masked, and a query returns them on the rows of `P1` and
  absent on the rows of `P2`

#### Scenario: none mode shows every column

- **WHEN** the service runs in `none` mode and a table carries column policies
- **THEN** every query and schema read exposes every column

### Requirement: Hidden columns are absent from every read surface

A column hidden from the caller SHALL be treated on every user surface exactly as a sensitive column the caller holds
no clearance for: it SHALL be absent from schema discovery, from the catalog read (`GET /v1/tables/{name}` and every
response carrying the table representation), from revision snapshots, and from the schema the SQL, structured-query,
translation, CSV-export and Flight SQL surfaces resolve against, Flight's catalog metadata and `INFORMATION_SCHEMA`
included. A star projection SHALL omit it. Referencing it anywhere in a query — projection, filter, grouping, ordering,
join or function argument — SHALL fail as an unknown field, with the status that surface gives an unknown field, and
a saved query or dashboard widget referencing it SHALL fail the same way when that caller runs it. A schema change
naming it SHALL be refused as naming an unknown column (`422`), while an `add` or `rename.to` colliding with it is
still refused `422` as a name that already exists, as for a hidden sensitive column (see
[table-management](../table-management/spec.md), "Modify table schema via PATCH"). A `ttl.column` SHALL resolve
against the columns visible to the caller only, so naming a hidden column fails as naming an undeclared one (`422`)
and TTL validation never reveals its type or nullability; a `ttl.column` naming a column masked for the caller, or one
the same request adds that the caller's column policies would mask, SHALL be refused `403 column_not_entitled` naming
it, before that validation; and while the stored TTL is on a column hidden from the caller by a column policy, or
masked for it, any `ttl` member (set, replace or remove) SHALL be refused `403 column_not_entitled` naming that column,
and the TTL left unchanged — where a column a column policy hides is also sensitive and the caller lacks the
clearance, the response is `403 column_not_entitled` too. A sensitive column the caller lacks the clearance for is
hidden, not masked, whatever the column policies decide, so the TTL rules of a hidden sensitive column apply to it. The list item's `column_count` SHALL not
count it (see [table-management](../table-management/spec.md), "Read and list table definitions"), and an import
entry's `prev` SHALL omit it (see [catalog-export-import](../catalog-export-import/spec.md), "Import entries"). No
response SHALL mark a hidden column as withheld or carry a placeholder for it; as for an unentitled sensitive column,
a definition's structural fields are returned as stored and a conflict refusal such as `409 column_in_column_policy`
names the columns it concerns. A masked column is not hidden: it is listed on these surfaces like a visible one, and
schema discovery and query results mark it and its absent cells as "Column visibility by caller role" refers to.

The same parity covers two surfaces that resolve against the writer's own view. Sessions-source validation (see
[instance-settings](../instance-settings/spec.md), "Sessions-source validation") resolves the bound fields against the
writing caller's flat schema, so a field hidden from that caller by a column policy is reported as absent, exactly as
an unentitled sensitive one is. And a saved query or inline dashboard body that binds a literal to a column a column
policy hides from other readers is stored and returned to them verbatim: that is the disclosure already accepted for a
sensitive column (see [saved-queries](../saved-queries/spec.md), "A sensitive value may be persisted in a saved query
body"), not a new one.

A revision snapshot SHALL hide a column that either the column policies the table had at that revision or the
column policies the table has **now** hide from the caller, so neither a restriction recorded then nor one added since
is bypassed by reading an older revision. A revision recorded before column policies existed is judged by the
policies it has now alone. The table's policies now SHALL be those of the **life** of the table that contains the
revision, a life running from a creation of the table to its next deletion: the live table's while that life has not
ended, and otherwise those of its state as recorded at the deletion that ended it. A table deleted since the revision
therefore keeps judging its revisions by the policies it last had, and a table created again under the same name
begins a new life whose policies SHALL NOT judge an earlier life's revisions. A column SHALL be matched by the name it carries in the revision: a current allowlist that lists only a
renamed column's new name hides it in a revision taken before the rename. An enrichment snapshot SHALL be judged by its
source's policies now. While the enrichment's own life has not ended, those are the policies of the source's life
that contains the revision, resolved the same way: the live source while that life has not ended, otherwise its state
as recorded at the deletion that ended it. Once the enrichment's life has ended, they are the source's policies as
they stood just before that deletion, so a later enrichment of the same name, granted columns on the source, does not
judge an earlier life's revisions. When no recorded state of the table or of its source can be found, every column of
the snapshot SHALL be hidden. A snapshot's `permissions.columns_restricted` SHALL be
judged over the revision's own columns, except on a system table's snapshot, which carries none and reports it `true`
exactly when a column policy, the revision's or the current one, hides or masks a column for the caller (see
[audit-trail](../audit-trail/spec.md), "Historical table snapshots").

#### Scenario: Every query surface omits a hidden column

- **GIVEN** a caller from whom column `c` of table `t` is hidden
- **WHEN** it requests `t`'s schema through discovery, runs `SELECT *` over SQL, a structured query with no explicit
  fields, a CSV export, and a Flight SQL query and `INFORMATION_SCHEMA.COLUMNS` read
- **THEN** none of them carries `c`

#### Scenario: Referencing a hidden column fails as unknown

- **WHEN** the same caller translates or executes a query that filters, groups or orders on `c`
- **THEN** the request fails exactly as for a column `t` does not have

#### Scenario: A saved query over a hidden column fails for that caller

- **GIVEN** a saved query, and a dashboard widget running it, that selects `c`, authored by a caller who sees `c`
- **WHEN** the caller from whom `c` is hidden runs the saved query and the widget
- **THEN** each run fails exactly as for a column `t` does not have, while the author's runs succeed

#### Scenario: The catalog read omits a hidden column

- **WHEN** the same caller reads `GET /v1/tables/t`
- **THEN** `c` is absent from its columns and `permissions.columns_restricted` is `true`

#### Scenario: An older revision hides a column the current policies hide

- **GIVEN** a revision of `t` at which the caller's only role had no column policy, and a later entry for that role
  with `columns` `["k"]`
- **WHEN** the caller requests `t`'s snapshot at that revision
- **THEN** only `k` is listed and `permissions.columns_restricted` is `true`

#### Scenario: A schema change cannot name a hidden column

- **GIVEN** a caller holding `modify` on `t` from whom `c` is hidden
- **WHEN** it patches `c`'s description
- **THEN** the request is refused as naming an unknown column

#### Scenario: A TTL cannot reach a policy-hidden column

- **GIVEN** a table with a `timestamp` column `c` hidden by a column policy from a caller holding `modify` on it
- **WHEN** the caller `PATCH`es `{"ttl":{"column":"c","interval":1,"unit":"day"}}`
- **THEN** the request is refused `422` as naming an undeclared column, and no `ALTER` is issued
- **AND WHEN** the table's stored TTL is on `c` and the same caller sends `{"ttl":{}}`
- **THEN** the response is `403 column_not_entitled` and the TTL is unchanged

#### Scenario: A deleted table's revisions are judged by the policies it last had

- **GIVEN** a revision `R` of table `t` recorded before column policies existed, a later entry for role `X` with
  `columns` `["k"]`, and then the deletion of `t`
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `t`'s snapshot at `R`
- **THEN** it shows column `k` only and `permissions.columns_restricted = true`

#### Scenario: A table created again does not judge an earlier life's revisions

- **GIVEN** a revision `R` of table `t` at which role `X` had no column policy, a later entry for `X` with `columns`
  `["k"]`, the deletion of `t`, and a new table `t` created with no entry
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `t`'s snapshot at `R`
- **THEN** it shows column `k` only
- **AND WHEN** the new `t` gains an entry for `X` with `columns` `["m"]` and the caller requests a snapshot of the new
  `t` recorded after that entry
- **THEN** that snapshot is judged by `["m"]`, and the snapshot at `R` still shows `k` only

#### Scenario: An enrichment snapshot is judged by its deleted source's last policies

- **GIVEN** an enrichment `e` of source `s`, a revision `R` of `e`, an entry on `s` for role `X` with `read` and
  `columns` naming `s`'s columns and none of `e`'s, and then the deletion of `e` and of `s`
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `e`'s snapshot at `R`
- **THEN** it lists no column
- **AND WHEN** `X`'s entry on `s` instead carried `read` with no `columns` before both deletions
- **THEN** the snapshot lists every column of `e` at `R`

#### Scenario: An enrichment created again does not judge an earlier life's revisions

- **GIVEN** a revision `R` of enrichment `e` of a live source `s`, at which role `X`'s entry on `s` carries `read` and
  `columns` `["k"]`; then `e` is deleted, created again with a column `label`, and `X`'s `columns` become
  `["k", "e.label"]`
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `e`'s snapshot at `R`
- **THEN** it does not show `label`

#### Scenario: Each life of a table recreated twice is judged by its own deletion

- **GIVEN** table `t` with a revision `R1` in its first life, which ends with role `X`'s `columns` `["k"]`; a second
  life with a revision `R2`, which ends with `X`'s `columns` `["m"]`; and a third, live life with no entry
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `t`'s snapshots at `R1` and at `R2`
- **THEN** the snapshot at `R1` is judged by `["k"]` and the one at `R2` by `["m"]`

### Requirement: Pipeline and export paths refuse a hidden column

Every path that refuses `403 sensitive_column_not_entitled` SHALL also refuse a column hidden from the calling
identity, with HTTP 403 and machine code `column_not_entitled`, naming the column, and SHALL NOT report it as an
unknown column. Those paths refuse `sensitive_column_not_entitled` for a sensitive column the calling identity may not
read or see: pipeline authoring (`enrich` and `aggregate` registration, enable, patch and preview, and an aggregate's
refresh on request), the runner's data-plane endpoints under the calling identity, and catalog export of a table the bundle
carries under `tables` (an export of only a table's access carries none of its columns; see
[catalog-export-import](../catalog-export-import/spec.md), "Selective export"). A column that
is both hidden and sensitive without the clearance
SHALL be refused `column_not_entitled`. Every column a request references SHALL be judged for a column policy before
any is judged for the clearance: on `enrich` registration, enable and patch the column-policy check SHALL run where
each fragment of the declaration is resolved, and the clearance check of the resolved bound inputs and grain key SHALL
run once, after every other check, so a hidden column anywhere in the request answers `column_not_entitled`. A fragment
that fails to parse because it names a column hidden from the caller's schema is re-attributed where it is parsed, as
the enrichment-registry requirement states. Export SHALL never drop a hidden column
silently. Work running under the system identity SHALL NOT be refused for a column policy. A caller with no governing
role on a table (see "Column visibility by caller role") is not refused for its column policies, so an author holding
only `REGISTRY_ADMIN` may still reference that table's non-sensitive columns, as the pipeline-authoring rules allow.

When one request judges several tables — an export bundle, or a pipeline reading a source and its enrichments — and
both refusals apply, in one table or in different ones, the response SHALL be `column_not_entitled`: every table the
request judges SHALL be checked for policy-hidden columns before any is checked for the clearance, so the code does
not depend on the order the tables are visited in.

The columns a path reads SHALL be every column it returns or derives from. For the runner's scan these are, besides
the declaration's `filter`, `trigger.ready_when.signal` and bound inputs, the read source's `identity_column` and
`version_column`, the target's grain key, a group pipeline's group key and, for a row-trigger pipeline whose target is
aligned with the source it reads, the source's partition column, whose value every row returns as `__partition_v`,
because the scan's page returns them to the caller. For group assembly (`POST /v1/pipelines/{name}/group-rows` and
the group preview built on it) they are, besides its bound inputs, `filter` and `trigger.member_select`, the group key
and the read source's `version_column` and `identity_column`, which every member row returns. For a materialize they
are, besides its `filter`, its expressions (the alignment column of an aligned target included) and the target's grain
key, the read source's `version_column` and `identity_column`, because its `nextCursor` carries their values readably.
Each is judged by the exposed name of the catalog column it maps to (see "Per-role column policies"). A restricted
runner identity's governing roles must therefore allow those columns too — on the seeded `dial_usage_log`, `event_id`
and `_ingested_at`, and `request_time` for an aligned target — or every scan, group assembly or materialize it
requests is refused. A physical column the catalog does not expose cannot be named by a column policy and is not
judged for one.

A `lag_seconds` value — on a scan page, a materialize, an aggregate refresh on request, or `GET /v1/pipelines/{name}`
— is a duration derived from a version, not a column value: it SHALL NOT be a reason to judge a column, and SHALL be
returned to a caller from whom the version column is hidden. A `sql` enrich preview and an aggregate preview do not
judge the read source's `version_column` and `identity_column`, so they keep working for a caller from whom either is
hidden; for a caller from whom the read source's `version_column` is hidden — by a column policy, or as a sensitive
column it lacks the clearance for — an `enrichment_behind` note SHALL carry no instant read from that column: neither
the newest sampled version nor the enrichment's materialized position. The note SHALL still be raised when the
enrichment is behind, name the enrichment as its `subject`, and give the gap, a derived duration like `lag_seconds`.
The pipeline registry reads are not among the judged paths: `GET /v1/pipelines` (also the runner's sync feed) and
`GET /v1/pipelines/{name}` return a pipeline's cursor and position values (`state.cursor_version`,
`state.cursor_identity`, `state.materialized_through_version`, `state.materialized_through_identity`,
`state.clamp.cursor_version`, `state.clamp.cursor_identity`) to any `REGISTRY_READ` holder, as they do regardless of
table privileges and sensitive flags; column policies leave that registry-level disclosure as it is, and `REGISTRY_READ`
is the privilege that governs it, so the judgement above and the note's missing instants keep those values off the
judged paths' own responses but do not hide them from a caller that also holds `REGISTRY_READ`.

Where such a path parses a pipeline-scoped fragment of a stored or submitted declaration against the calling
identity's schema view — a `filter`, a `trigger.ready_when.signal`, a `prefer_sql`, an `enrich` pipeline's
expressions, and an aggregate spec's `filter` and `measures[].where` — a reference to a column hidden from that
identity fails to parse as an unknown column, and the failure does not say which column caused it. Such a parse failure
SHALL be re-attributed to `403 column_not_entitled` when any table the declaration reads — its read source and that
source's active enrichments — has a column hidden from the caller by a column policy; otherwise to
`403 sensitive_column_not_entitled` when one has a sensitive column the caller lacks the clearance for; and otherwise it
SHALL be reported unchanged (see [enrichment-runtime](../enrichment-runtime/spec.md),
[enrichment-registry](../enrichment-registry/spec.md) and [class1-enrichment](../class1-enrichment/spec.md)). Because
a column-restricted caller almost always has some hidden column, it receives every such parse failure of a declaration
over a table that restricts it as `403 column_not_entitled` — including one caused by a column the table no longer has
— while an unrestricted caller receives the parse diagnostic.

An aggregate's SQL statement is not re-attributed that way: it keeps its text-mention rule (see
[aggregate-pipeline](../aggregate-pipeline/spec.md), "An SQL declaration is governed for sensitive columns and
enrichment positions as the form path is"). When the statement cannot be planned, a protected column its text mentions
is refused — `403 column_not_entitled` for one hidden by a column policy, checked across every table the rule covers
before `403 sensitive_column_not_entitled` for an unentitled sensitive one — and every other planning failure stays
the `422` it is today, for a column-restricted caller too.

#### Scenario: An author cannot register a pipeline over a column it cannot see

- **GIVEN** a caller holding `REGISTRY_ADMIN` from whom column `c` of the input table is hidden
- **WHEN** it registers a pipeline whose declaration binds `c` as an input
- **THEN** the response is `403 column_not_entitled` naming `c`, and nothing is stored
- **AND WHEN** it registers a pipeline whose `filter` or expression reads `c`
- **THEN** the response is `403 column_not_entitled`, re-attributed from the parse failure, which names no column

#### Scenario: A runner scan over a hidden column is refused

- **GIVEN** a runner identity from whom a column the pipeline reads is hidden
- **WHEN** it requests a scan
- **THEN** the response is `403 column_not_entitled` and no row is read

#### Scenario: A restricted runner's list must name the scan's cursor columns

- **GIVEN** a row-grain pipeline reading `dial_usage_log`, whose target is keyed on `event_id` and aligned with
  `dial_usage_log` and whose declaration names only `project_id` and `model`, and a runner identity whose only
  governing role on `dial_usage_log` carries `read` and `columns` `["event_id", "model", "project_id",
  "request_time"]`
- **WHEN** it requests a scan
- **THEN** the response is `403 column_not_entitled` naming `_ingested_at`, and no row is read
- **AND WHEN** the list also names `_ingested_at`
- **THEN** the scan succeeds

#### Scenario: A restricted runner's list must name an aligned target's partition column

- **GIVEN** a row-grain pipeline reading `dial_usage_log`, whose target is keyed on `event_id` and aligned with
  `dial_usage_log` and whose declaration names only `project_id` and `model`, and a runner identity whose only
  governing role on `dial_usage_log` carries `read` and `columns` `["_ingested_at", "event_id", "model",
  "project_id"]`
- **WHEN** it requests a scan
- **THEN** the response is `403 column_not_entitled` naming `request_time`, and no row is read

#### Scenario: A restricted caller's list must name materialize's cursor columns

- **GIVEN** an enabled row-grain `sql` pipeline reading `dial_usage_log`, whose target is keyed on `event_id` and not
  aligned and whose expressions read only `project_id`, and a caller holding what materialize requires whose only
  governing role on `dial_usage_log` carries `read` and `columns` `["event_id", "project_id"]`
- **WHEN** it calls `materialize`
- **THEN** the response is `403 column_not_entitled` naming `_ingested_at`, and no row is written
- **AND WHEN** the list also names `_ingested_at`
- **THEN** the call succeeds

#### Scenario: A restricted runner's list must name the version column group assembly returns

- **GIVEN** a group pipeline reading `dial_usage_log`, whose target is keyed on `chat_id`, whose `trigger.group_by` is
  `chat_id`, which declares no `filter` or `trigger.member_select` and whose bound inputs are only `chat_id` and
  `model`, and a runner identity whose only governing role on `dial_usage_log` carries `read` and `columns`
  `["chat_id", "event_id", "model"]`
- **WHEN** it calls `POST /v1/pipelines/{name}/group-rows` for a group
- **THEN** the response is `403 column_not_entitled` naming `_ingested_at`, and no row is read
- **AND WHEN** the list also names `_ingested_at`
- **THEN** the group is returned

#### Scenario: A preview keeps working when the version column is hidden, and its note omits the version

- **GIVEN** an enabled row-grain `sql` enrich pipeline and an aggregate pipeline, each reading `dial_usage_log` and a
  column of its enrichment `deployment_ref`, whose materialized position is behind the newest matching row, and a
  caller holding what preview requires whose only governing role on `dial_usage_log` carries `read` and `columns`
  naming every column either declaration reads (the enrich target's grain key included), but not `_ingested_at`
- **WHEN** it previews each pipeline
- **THEN** each preview succeeds and carries an `enrichment_behind` note whose `subject` is `deployment_ref` and whose
  message gives the gap but no instant
- **AND WHEN** the list also names `_ingested_at`
- **THEN** each note's message also gives the newest sampled version and the enrichment's materialized position

#### Scenario: lag_seconds is returned when the version column is hidden

- **GIVEN** an aggregate pipeline reading `dial_usage_log`, and a caller holding what an on-demand refresh requires
  whose only governing role on `dial_usage_log` carries `read` and `columns` naming every column the declaration reads,
  but not `_ingested_at`
- **WHEN** it refreshes the aggregate on request, and reads `GET /v1/pipelines/{name}`
- **THEN** both succeed and carry `lag_seconds`

#### Scenario: A policy-hidden column is reported before an unentitled sensitive one in another table

- **GIVEN** tables `t1`, with a sensitive column the caller lacks the clearance for, and `t2`, with a column hidden from
  the caller by a column policy, both gettable by the caller
- **WHEN** it exports both, in either selection order
- **THEN** the response is `403 column_not_entitled`

#### Scenario: A stored filter over a hidden column is refused, not reported malformed

- **GIVEN** a stored pipeline whose `filter` references column `c` of its read source, and a runner identity holding
  `read` and the clearance on that source from whom `c` is hidden by a column policy
- **WHEN** it requests a scan
- **THEN** the response is `403 column_not_entitled`, not `422`

#### Scenario: An unplannable aggregate statement is refused only for a column it mentions

- **GIVEN** an author from whom column `c` of the input table is hidden by a column policy
- **WHEN** it previews an aggregate SQL declaration that fails to plan and whose text mentions `c`
- **THEN** the response is `403 column_not_entitled` naming `c`
- **AND WHEN** the statement fails to plan and its text mentions no hidden or unentitled sensitive column
- **THEN** the response is the `422` for the planning failure

#### Scenario: An author holding only REGISTRY_ADMIN is not narrowed

- **GIVEN** a table on which role `X`'s entry carries `read` and `columns` `["a"]`, and a non-sensitive column `b`
- **WHEN** a caller holding only `REGISTRY_ADMIN`, carrying no role with an entry on the table, registers a pipeline
  whose declaration reads `b`
- **THEN** the registration is accepted

#### Scenario: An export with a hidden column is refused

- **WHEN** a caller holding `REGISTRY_READ` and `get` on a table, from whom one of its columns is hidden, exports it under `tables`
- **THEN** the response is `403 column_not_entitled`
- **AND WHEN** the caller, also holding `TABLE_GRANT`, exports only the table's access (`access_only` with `include_access`)
- **THEN** the response is `200`

#### Scenario: A scheduled refresh is unaffected

- **WHEN** an aggregate pipeline's scheduled refresh reads a column hidden from every role
- **THEN** it reads the column as before

### Requirement: Column policies do not propagate to derived tables

A column policy SHALL govern only the table it is stored on, and, through qualified names, its enrichments. An
aggregate target SHALL be governed only by its **own** entries: the system SHALL NOT infer, copy or require a column
policy on it from its input's. A value an aggregate derives from a column hidden on its input is visible on the target
to every caller the target's own entries let see that target column. This is the accepted risk stated for row policies
(see "Row policies do not propagate to derived tables"); the operator closes it with an entry on the target.

#### Scenario: An aggregate of a hidden column is not hidden

- **GIVEN** role `X` from whom `user_hash` of a source is hidden, and an aggregate target counting distinct
  `user_hash` with no entry for `X`
- **WHEN** a caller carrying `X` and holding `TABLE_READ` globally queries the target
- **THEN** it sees the count column

### Requirement: Ineffective grants are reported on read

Both reads of the access API SHALL carry `ineffective_grants`, built by the rules of "Role-keyed access API" over the
entries **as stored now**, so a client shows the same warnings after a reload as after a save. The whole-configuration
read SHALL report every stored entry, its items grouped by role in the order of `roles`; the one-role read SHALL report
that entry only. Each item SHALL have the shape a `PUT` reports, and the array SHALL be empty, never absent, when
nothing is reported.

A reason that depends on state outside the reported entry SHALL be judged against that state as it stands at the
read: the table's other entries, the `*` entry a role is carried with, the `modify` grants on the table's
enrichments, and the role mapping in force. A warning SHALL therefore appear once another change makes it true, and
disappear once another change makes it false, though the reported entry was saved before. The report SHALL NOT change
any decision of the access API, and the reads keep their `TABLE_GRANT` check and their order of checks.

#### Scenario: A read for everyone over a row policy is reported on read

- **GIVEN** a table on which role `X`'s entry carries `rows`, and the entry of `*` carries `read` with `rows` `null`
- **WHEN** a caller holding `TABLE_GRANT` reads `GET /v1/tables/t/access`
- **THEN** `ineffective_grants` is `[{"role": "*", "key": "read", "reason": "lifts_row_policies", ...}, {"role": "*",
  "key": "read", "reason": "everyone_reads_all_rows", ...}]`, in that order
- **AND** `GET /v1/tables/t/access?role=*` carries both items, and `GET /v1/tables/t/access?role=X` carries
  `"ineffective_grants": []`

#### Scenario: A warning appears when another role's change makes it true

- **GIVEN** a table on which the entry of `*` carries `read` and no other entry exists
- **WHEN** an entry carrying `rows` is then stored for role `X`, and a caller reads `GET /v1/tables/t/access`
- **THEN** `ineffective_grants` carries an item for `*` with reason `lifts_row_policies`
- **AND WHEN** `X`'s entry is removed
- **THEN** the read no longer carries a `lifts_row_policies` item, and still carries `everyone_reads_all_rows`

#### Scenario: A clearance warning disappears when the role gains a verb

- **GIVEN** a table on which role `pii-analysts`' entry carries `sensitive` only and the role holds no verb on the table
- **WHEN** a caller reads `GET /v1/tables/t/access?role=pii-analysts`
- **THEN** it carries an item with reason `sensitive_without_verb`
- **AND WHEN** the entry of `*` is then given `get`, or `pii-analysts` is given `modify` on an enrichment of the table
- **THEN** the same read carries `"ineffective_grants": []`

#### Scenario: A read for everyone with no row policy is reported on save and on read

- **GIVEN** a non-enrichment table with no entry
- **WHEN** a caller `PUT`s `{"generation": <current>, "privileges": ["read"]}` for `*`
- **THEN** the response's `ineffective_grants` carries `{"role": "*", "key": "read", "reason":
  "everyone_reads_all_rows", ...}`, and so does a later `GET /v1/tables/t/access`
- **AND WHEN** the entry of `*` is replaced by one carrying `read` and `rows`
- **THEN** neither the response nor a later read carries that item
- **AND WHEN** it is replaced by one carrying `read`, `columns` and no `rows`
- **THEN** the item is reported again

#### Scenario: A read for everyone without read is not reported

- **WHEN** the entry of `*` carries `get` only, or carries no privilege and a `rows`
- **THEN** no `everyone_reads_all_rows` item is reported

#### Scenario: State left on an enrichment is not reported as a read for everyone

- **GIVEN** an enrichment on which an earlier build stored, for `*`, a `read` grant with no row policy
- **WHEN** a caller holding `TABLE_GRANT` reads the enrichment's access configuration
- **THEN** no `everyone_reads_all_rows` item is reported, because the enrichment's rows are its source's to grant

#### Scenario: A table with nothing to report

- **GIVEN** a table with no entry, and a table on which role `X`'s entry carries `read` and `rows`, `X` holding no
  global privilege
- **WHEN** a caller holding `TABLE_GRANT` reads the access configuration of each
- **THEN** both carry `"ineffective_grants": []`

### Requirement: Catalog import writes access only on request

A catalog import SHALL write no access entry on any table unless it is called with `include_access=true`: without it, a created table
starts with none and a `system` table's entries are never touched, as defined by
[catalog-export-import](../catalog-export-import/spec.md) ("Import order and access"). With it, the import upserts each role entry the
bundle carries onto the target table, system tables included, as that capability's "Access import" defines, through the same body
rules as `PUT /v1/tables/{name}/access?role=<role>`, so an import can store only what a `PUT` could. Access is each environment's own
policy unless the operator asks for it to travel, so granting access on an imported table otherwise stays an operator action through
`PUT /v1/tables/{name}/access?role=<role>`, on the table itself for a source and on its source for an enrichment, whose read-side
access is its source's.

#### Scenario: Imported table starts ungranted

- **WHEN** a table that has entries on the source environment is imported without `include_access`, whether or not it was exported
  with it
- **THEN** the target's table has no entry, and the target's `dial_usage_log` entries are unchanged

#### Scenario: Imported access obeys the PUT rules

- **WHEN** a bundle whose `access` gives `*` the `write` privilege on a user table `t` is imported with `include_access=true`
- **THEN** that access entry is `FAIL` with a problem naming `validation_error`, the import is `422` with `error`
  `catalog_import_invalid`, and `t`'s access is unchanged

### Requirement: Pipeline paths refuse a masked column

Every path "Pipeline and export paths refuse a hidden column" lists, catalog export excepted, SHALL refuse a column
masked for the calling identity (see "Column visibility by caller role") exactly as it refuses a hidden one: HTTP 403
with machine code `column_not_entitled`, naming the column, judged with the hidden columns before any column is judged
for the clearance, over the same columns that requirement says the path reads. Those paths are pipeline authoring
(`enrich` and `aggregate` registration, enable, patch and preview, and an aggregate's refresh on request) and the
runner's data-plane endpoints under the calling identity. They read without the row filter and without the mask, and
a pipeline writes what it derives into a table where the row a value came from no longer decides who may read it, so
a column the caller may read on some rows only SHALL NOT reach them. A fragment that parses because the masked column
is in the caller's schema SHALL still be refused for it, as a column the path reads. The re-attribution of a parse
failure to `column_not_entitled` keeps keying on a column a column policy hides: a masked column parses, so it is
never the cause of one, and a caller whose restricted columns are all masked receives a fragment's genuine parse
diagnostic, although its `columns_restricted` is `true`. For the same reason a masked column is not a protected column
for an unplannable aggregate SQL statement (see [aggregate-pipeline](../aggregate-pipeline/spec.md)): a planning
failure whose text mentions a masked column, and no hidden or unentitled sensitive one, SHALL stay the `422` planning
diagnostic. A statement that does plan and reads a masked column is refused `403 column_not_entitled` by the rule
above.

A masked column SHALL be judged at the point in each path where a hidden one is, so the refusal SHALL NOT change the
order in which a path already decides `403 row_policy_confined` (see [pipeline-registry](../pipeline-registry/spec.md),
and [enrichment-runtime](../enrichment-runtime/spec.md), "The pipeline data plane refuses a row-confined caller") and
`403 column_not_entitled`; a registration or patch that leaves the pipeline disarmed is judged when it is armed, as
for a hidden column. Where the confinement check comes first, as on an armed registration and on the runner's data
plane, a masked column reaches this refusal only for a caller that is not confined — one carrying a row-unrestricted
reading role whose column policy leaves out a column a confined role of the caller allows.

Where one of these paths withholds a value read from a column hidden from the caller instead of refusing it, it SHALL
withhold it for a masked column too. In particular, an `enrichment_behind` note SHALL carry no instant read from the
read source's `version_column` when that column is masked for the caller, exactly as when it is hidden. Those values
come from reads without the row filter, so they can come from rows on which the cell is absent for the caller. A
`lag_seconds` value and the note's gap stay returned, as for a hidden version column.

Catalog export SHALL NOT refuse a masked column: a bundle carries the column's definition, which the caller may see,
and no stored value. Work running under the system identity SHALL NOT be refused for a masked column, as for a hidden
one.

#### Scenario: An author cannot register a pipeline over a masked column

- **GIVEN** a caller holding `REGISTRY_ADMIN` whose governing roles on `dial_usage_log` are `A`, with `read`, `rows`
  admitting `project_id = 'P1'` and `columns` `null`, and `B`, with `read`, no `rows` and `columns` listing every
  column of the table but `deployment` — so it reads every row and `deployment` alone is masked
- **WHEN** it registers an armed (`enabled: true`) `enrich` pipeline whose bound input reads `deployment`
- **THEN** the response is `403 column_not_entitled` naming `deployment`, and nothing is stored

#### Scenario: A confined caller is refused for its confinement first

- **GIVEN** the same caller without `B`, so it is confined to `P1`, and a second role with `read`, `rows` admitting
  `project_id = 'P2'` and `columns` `["project_id"]`, so `deployment` is masked
- **WHEN** it registers the same armed pipeline
- **THEN** the response is `403 row_policy_confined`, as for any confined caller

#### Scenario: A runner scan over a masked column is refused

- **GIVEN** a runner identity that is not confined on a pipeline's read source but for which a column the scan returns
  is masked
- **WHEN** it calls the pipeline's scan
- **THEN** the response is `403 column_not_entitled` naming that column

#### Scenario: A masked column is refused before an unentitled sensitive one

- **GIVEN** a caller that is not confined, and a pipeline declaration that reads a column masked for it and a
  sensitive column of another table it lacks the clearance for
- **WHEN** the caller registers it armed
- **THEN** the response is `403 column_not_entitled`, whatever order the tables are visited in

#### Scenario: An unplannable aggregate statement mentioning only a masked column keeps its planning diagnostic

- **GIVEN** a caller that is not confined, for which `deployment` is masked on an aggregate pipeline's input and no
  column of it is hidden
- **WHEN** it previews an aggregate whose SQL statement mentions `deployment` and cannot be planned
- **THEN** the response is the `422` planning diagnostic
- **AND WHEN** it previews an aggregate whose SQL statement plans and reads `deployment`
- **THEN** the response is `403 column_not_entitled` naming `deployment`
- **AND WHEN** that statement also names an output after its input table, which a query beside a masked field may not
- **THEN** the response is still `403 column_not_entitled` naming `deployment`: a pipeline reads without the mask, so
  the name rule a mask brings does not apply to it

#### Scenario: A preview's note omits the instants of a masked version column

- **GIVEN** a caller holding `REGISTRY_ADMIN` that is not confined on an `enrich` pipeline's read source, for which
  the source's `version_column` is masked, and an enrichment behind its source
- **WHEN** it requests a `sql` enrich preview
- **THEN** the preview runs, and its `enrichment_behind` note names the enrichment and gives the gap, but carries
  neither the newest sampled version nor the enrichment's materialized position

#### Scenario: An export carries a masked column

- **GIVEN** a caller holding `REGISTRY_READ` and `get` on a table, whose governing roles there mask one of its columns
- **WHEN** it exports the table
- **THEN** the export succeeds and the bundle's table definition lists that column

