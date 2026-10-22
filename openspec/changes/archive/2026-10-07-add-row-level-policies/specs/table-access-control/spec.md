## ADDED Requirements

### Requirement: Per-role row policies

The system SHALL store, for each catalog table, a set of **row policies** co-located on the table
definition, at most one per raw provider role. A policy SHALL be exactly one of:

- `{"filter": <filter node>}` — a predicate in the structured query's filter grammar (see
  [structured-query](../structured-query/spec.md), "Filter sub-grammar (CQL2-JSON)" and "Expression
  grammar") over that table's own columns; or
- `{"all_rows": true}` — an explicit unrestricted policy.

A body carrying both members, neither, `all_rows` with any value other than `true`, or any other member
SHALL be refused — the first three as a shape failure, `422 validation_error`; a member outside the two,
like any body that does not bind to the request type, by the strict request mapper as `400 bad_request`
(see "Row-policy management API"). There SHALL be no empty filter meaning "no condition": unrestricted is spelled
`all_rows`, so it cannot be produced by accident.

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

#### Scenario: Both shapes at once are refused

- **WHEN** a policy body carries both `filter` and `all_rows: true`, or `all_rows: false`, or neither
- **THEN** the request is refused `422 validation_error`

#### Scenario: An unknown member does not bind

- **WHEN** a policy body carries a member other than `filter` and `all_rows`, e.g. `{"where": {}}`
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
  It is idempotent: repeating it changes nothing.
- `DELETE /v1/tables/{name}/row-policies/{role}` SHALL remove that role's policy and answer `204`.

A caller without `TABLE_GRANT` SHALL receive `403 forbidden`. An unknown table SHALL be `404 not_found`,
and so SHALL a `DELETE` naming a role that has no policy on the table. A body that does not bind to the
request type — malformed JSON, or a member other than `filter` and `all_rows` — SHALL be `400 bad_request`, as on every endpoint behind the strict request mapper. A
body that binds but whose shape or meaning is invalid, and an invalid role, SHALL be `422 validation_error`
naming the offending member. Every change SHALL be recorded in the audit trail as
a change to the table definition.

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

#### Scenario: A bundle name is not a policy role

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s a policy for role `READ_ONLY_ANALYTIC`
- **THEN** the request is refused `422 validation_error`

### Requirement: Row-policy evaluation

In `oidc` mode, for every table a caller reads through a user read surface, the rows it sees SHALL be
decided from the policies whose role is among the caller's raw provider roles:

- **none of the caller's roles has a policy on the table** — the caller SHALL see every row it may
  read;
- **one of those policies is `all_rows`** — the caller SHALL see every row it may read;
- **otherwise** — the caller SHALL see exactly the rows satisfying **at least one** of those policies'
  filters.

These are ClickHouse's permissive row-policy semantics. A policy SHALL follow the **role**, not the path
by which the caller came to read the table: a role holding `TABLE_READ` globally, through a bundle or a
privilege mapping, SHALL be confined by its policy exactly as a role reading through a per-table `read`
grant. Holding a second role without a policy SHALL NOT widen a confined caller; only an `all_rows`
policy, or a filter matching more rows, SHALL.

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

#### Scenario: A role without a policy does not widen a confined caller

- **GIVEN** a policy for `X` admitting `X-PRJ-1`, and role `Y`, holding `TABLE_READ` globally, with no
  policy on the table
- **WHEN** a caller carrying both `X` and `Y` queries the table
- **THEN** it sees only rows of `X-PRJ-1`
- **AND WHEN** an `all_rows` policy is stored for `Y`
- **THEN** the same caller sees every row

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

## MODIFIED Requirements

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
  — at least one of its roles has a policy there and none of those policies is `all_rows` — on `system`
  and non-`system` tables alike;
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
- **AND WHEN** an `all_rows` policy is stored for another of the caller's roles
- **THEN** the table carries `permissions.rows_restricted = false`

#### Scenario: none mode reports full permissions

- **WHEN** the service runs in `none` security mode and any caller reads a non-`system` table
- **THEN** every flag in `permissions` except `rows_restricted` is `true`, and `rows_restricted` is
  `false`
