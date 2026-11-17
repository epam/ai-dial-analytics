## MODIFIED Requirements

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
- `columns_restricted` SHALL be `true` exactly when column-visibility evaluation hides at least one of the table's own
  columns from the caller (see "Column visibility by caller role", row policies of the governing roles included), on
  `system` and non-`system` tables alike; a
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
`rows_restricted` and `columns_restricted` SHALL NOT disclose the policies themselves; those are readable only through
the access API.

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

## ADDED Requirements

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
