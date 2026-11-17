## MODIFIED Requirements

### Requirement: Catalog import writes no grants

A catalog import SHALL write no access entry on any table: a created table starts with none and a `system` table's
entries are never touched, as defined by [catalog-export-import](../catalog-export-import/spec.md) ("Import order and
no grants"). Access is each environment's own policy, so granting access on an imported table stays an operator action
through `PUT /v1/tables/{name}/access?role=<role>`, on the table itself for a source and on its source for an enrichment,
whose read-side access is its source's.

#### Scenario: Imported table starts ungranted

- **WHEN** a table that has entries on the source environment is exported and imported
- **THEN** the target's table has no entry, and the target's `dial_usage_log` entries are unchanged

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

A caller value SHALL be written in the condition as `caller.user_hash` or `caller.project`, exactly, and SHALL become
the `param` of that name, subject to the placement rule of "Caller values in row policies". A string literal in the
condition SHALL remain a literal whatever it spells, including the text of a caller value's name.

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

#### Scenario: A literal spelling a caller value stays a literal

- **WHEN** a policy is written as `"rows": {"where": "user_hash = 'caller.user_hash'"}`
- **THEN** the stored filter compares `user_hash` with the string `caller.user_hash`, and no caller's value is
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
  {"<role>": <entry>, ...}}`, the members of `roles` sorted by role, with an empty `roles` object when the table has
  no entry.
- `GET /v1/tables/{name}/access?role=<role>` SHALL return `{"generation": <integer>, "role": "<role>", ...entry}`, and
  `404 not_found` when the role has no entry on the table.
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
grants nothing. Its `columns` narrow a caller only where the role governs the caller's access to the table (see
"Column visibility by caller role"), a global read privilege included, and are ignored otherwise. Its `rows` take part
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

`ineffective_grants` SHALL report, without failing the request, each privilege in the entry that changes nothing, as
an object carrying `role`, `key` (the privilege), a stable `reason` and a human-readable `message`:

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
  restriction, so by "Column visibility by caller role" those restrictions hide nothing.

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

#### Scenario: A role containing a slash or a percent sign is addressed percent-encoded

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

#### Scenario: A role the firewall refuses cannot be addressed

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
enrichment's do not. Nothing else SHALL change a generation or a mark: a `PUT` that changes no entry, a description
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
is **row-unrestricted** when it has no row policy on the table. A column SHALL be **visible** to the caller when some
row-unrestricted governing role allows it, or when every governing role allows it; otherwise it SHALL be **hidden**.

Each entry is one unit — a role's readers see that role's columns on that role's rows — and the rows a caller reads
are the union of its roles' row reach (see "Row-policy evaluation by caller role"), which does not record which role
admitted a row. A column SHALL therefore be shown only when it is allowed on every row the caller may read: by one role
that admits every row, or by every role that admits any. When no governing role carries a row policy, the visible
columns are the union of the governing roles' lists; when the caller is confined, they are their intersection. The rule
MAY hide a column the caller could have seen on the rows one role alone admits: it fails closed rather than pair one
role's rows with another role's columns. The rule applies unchanged to a caller that may not read the table, whose
governing roles are get-governing: a governing role's row policy still makes it an intersection there, although such
a caller reads no rows; that narrowing is accepted rather than given a separate rule. A role that does not govern — an IdP group, a role conferring only `write`, a
role conferring only `get` to a caller that may read — SHALL take no part, so it SHALL NOT lift a restriction. That
includes a role whose entry carries `rows` but which has no read path: its row policy adds its rows to a confined
caller carrying it, by row-policy evaluation, but its `columns` are not consulted, so on those rows too the caller sees
only the columns the rule above admits from its governing roles. A caller with no governing role is covered below.

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
`dial_usage_log_payload.request_cache_control`.

The clearance SHALL stack on top: a sensitive column SHALL be visible only when the column policies allow it **and**
the caller holds the clearance on the table. A column policy SHALL NOT grant anything: whether the caller may get or
read the table at all is decided by its privileges, unchanged.

For an enrichment, the governing roles and their policies SHALL be its source's, the policy naming the enrichment's
column `c` as `<enrichment>.c`; when the caller may not read the source, a role holding `get` on the enrichment through
the enrichment's own `modify` list SHALL also govern it, as a row-unrestricted role with no column policy.

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
- **THEN** it reads the rows of `P1` and of `P2` with columns `a` and `project` only, and its `GET /v1/tables/t` reports
  `permissions.columns_restricted = true`

#### Scenario: A role with no row policy shows the columns it allows on every row

- **GIVEN** an entry for `R` with `read`, `rows` admitting `project = 'P1'` and `columns` `["a", "project"]`, and an
  entry for `U` with `read`, no `rows` and `columns` `null`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads every row with every column

#### Scenario: A column every confining role lists is visible

- **GIVEN** an entry for `R` with `read`, `rows` admitting `project = 'P1'` and `columns` `["a", "c"]`, and an entry for
  `U` with `read`, `rows` admitting `project = 'P2'` and `columns` `["b", "c"]`
- **WHEN** a caller carrying both requests the entity's schema and queries the table
- **THEN** both show `c` only, over the rows of `P1` and of `P2`

#### Scenario: Gaining a column-restricted role can hide a confined role's columns

- **GIVEN** a table with columns `project`, `x` and `y`; an entry for `A` with `read`, `rows` admitting
  `project = 'P1'` and `columns` `null`; and an entry for `B` with `read`, no `rows` and `columns` `["x"]`
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads every row with column `x` only, because showing `A`'s other columns would show them on the rows
  only `B` admits
- **AND WHEN** `B`'s entry also carries `rows`, admitting `project = 'P2'`
- **THEN** it reads the rows of `P1` and of `P2`, still with column `x` only
- **AND WHEN** instead `B`'s `columns` list `x`, `y` and `project`
- **THEN** it reads every row with all three columns

#### Scenario: A rows-only entry without a read path adds rows but no columns

- **GIVEN** an entry for `X` with `read`, `rows` admitting `project = 'P1'` and `columns` `["a", "project"]`, and an
  entry for `N` with no privileges, `rows` admitting `project = 'P2'` and `columns` `null`, where no global privilege
  gives `N` a read
- **WHEN** a caller carrying both runs `SELECT * FROM t`
- **THEN** it reads the rows of `P1` and of `P2`, with columns `a` and `project` only

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
and TTL validation never reveals its type or nullability; and while the stored TTL is on a column hidden from the
caller by a column policy, any `ttl` member (set, replace or remove) SHALL be refused `403 column_not_entitled`
naming that column, and the TTL left unchanged — where the column is also sensitive and the caller lacks the
clearance, the response is `403 column_not_entitled` too. The list item's `column_count` SHALL not
count it (see [table-management](../table-management/spec.md), "Read and list table definitions"), and an import
entry's `prev` SHALL omit it (see [catalog-export-import](../catalog-export-import/spec.md), "Import entries"). No
response SHALL mark a column as withheld or carry a placeholder for it; as for an unentitled sensitive column, a
definition's structural fields are returned as stored and a conflict refusal such as `409 column_in_column_policy`
names the columns it concerns.

The same parity covers two surfaces that resolve against the writer's own view. Sessions-source validation (see
[instance-settings](../instance-settings/spec.md), "Sessions-source validation") resolves the bound fields against the
writing caller's flat schema, so a field hidden from that caller by a column policy is reported as absent, exactly as
an unentitled sensitive one is. And a saved query or inline dashboard body that binds a literal to a column a column
policy hides from other readers is stored and returned to them verbatim: that is the disclosure already accepted for a
sensitive column (see [saved-queries](../saved-queries/spec.md), "A sensitive value may be persisted in a saved query
body"), not a new one.

A revision snapshot SHALL hide a column that either the column policies the table had at that revision or the
table's current column policies hide from the caller, so neither a restriction recorded then nor one added since is
bypassed by reading an older revision. A revision recorded before column policies existed is judged by the current
policies alone. A column SHALL be matched by the name it carries in the revision: a current allowlist that lists only a
renamed column's new name hides it in a revision taken before the rename. An enrichment snapshot SHALL be judged by its
source's current policies, as its read-side flags are resolved. A snapshot's `permissions.columns_restricted` SHALL be
judged over the revision's own columns.

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
