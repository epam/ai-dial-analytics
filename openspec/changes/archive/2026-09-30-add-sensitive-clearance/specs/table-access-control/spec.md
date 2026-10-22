## MODIFIED Requirements

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

### Requirement: Caller-effective privilege reporting

The system SHALL report, on every table read surface (`GET /v1/tables` list items and
`GET /v1/tables/{name}`), the calling identity's effective per-table permissions as a `permissions`
object with boolean `get`, `read`, `sensitive`, `write` and `modify` fields. There SHALL be no
`list` flag: a caller that sees a table at all may list it, so the flag could only ever read `true`. The reported
flags SHALL agree with what per-table evaluation and system-table protection would decide for that
caller, **after** implication is applied:

- a `system` table SHALL report `write=false, modify=false` for every caller, including one holding
  every privilege (all modifying operations are refused on system tables), while its read-side flags
  SHALL be evaluated from the grant lists like any other table's;
- otherwise each flag SHALL be `true` if the caller holds the corresponding privilege globally, or if
  one of its raw provider roles appears in a list that grants or implies it;
- in `none` security mode every flag SHALL be `true` for non-`system` tables, and the read-side flags
  SHALL be `true` for `system` tables (no authorization is enforced).

`sensitive` SHALL report whether the caller holds the clearance on the table, not whether it can read
sensitive values. It counts among the read-side flags. A consumer SHALL derive "reads sensitive values"
as `read && sensitive`, "sees sensitive columns" as `get && sensitive`, and "may change them or the
flag" as `modify && sensitive`.

The reporting SHALL share its role-matching and implication implementation with the evaluation used
for enforcement so the two cannot diverge. Reporting SHALL NOT change enforcement behaviour.

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

- **WHEN** a caller holding the `FULL_ADMIN` bundle reads a non-`system` table with empty grant lists
- **THEN** every flag in `permissions` is `true`

#### Scenario: No matching grant is reported as no permissions

- **WHEN** a caller with no global privilege, whose provider roles appear in none of a table's grant
  lists, reads that table through a surface it can reach
- **THEN** every flag in `permissions` is `false`

#### Scenario: A system table reports no write permissions but honours read grants

- **WHEN** a caller holding the `FULL_ADMIN` bundle reads a `system` table (e.g. the seeded
  `dial_usage_log`)
- **THEN** the table carries `permissions.write = false` and `permissions.modify = false`, and
  `permissions.read = true`

#### Scenario: none mode reports full permissions

- **WHEN** the service runs in `none` security mode and any caller reads a non-`system` table
- **THEN** every flag in `permissions` is `true`
