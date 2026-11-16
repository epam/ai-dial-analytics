# Row- and column-level security

A table's access is configured as one **entry** per raw provider role: `{"privileges": [...], "rows": ...,
"columns": ...}`. `privileges` are the per-table grants (`get`, `read`, `sensitive`, `write`, `modify`), `rows` is
the role's **row policy**, which narrows the rows it reads, and `columns` is its **column policy**, which narrows
the columns it sees. Entries are managed by a caller holding `TABLE_GRANT`. The behaviour is specified in
[`openspec/specs/table-access-control/spec.md`](../openspec/specs/table-access-control/spec.md); this page is the
operator's guide.

## Reading and writing an entry

| Call | Does |
|---|---|
| `GET /v1/tables/{name}/access` | Every role's entry, sorted by role, and the table's access `generation` |
| `GET /v1/tables/{name}/access?role={role}` | One role's entry and the `generation`; `404` when the role has none |
| `PUT /v1/tables/{name}/access?role={role}` | Replaces the role's **whole** entry; body `{"generation", "privileges", "rows", "columns"}` |
| `DELETE /v1/tables/{name}/access?role={role}` | Removes the role's entry (`204`), `404` when it has none; optional `&generation=<n>` makes it conditional |

- **A `PUT` replaces the whole entry.** An absent or `null` `privileges` means none; an absent `rows` or `columns`
  means `null`. To change one part, send the others as you read them. An entry left with no privileges, no `rows`
  and no `columns` is removed.
- **Every `PUT` carries the `generation` it read.** The table has one generation, and the service remembers at
  which generation each role's entry last changed (a removal included). A `PUT` is refused `409 access_conflict`,
  changing nothing, when **its own role's** entry changed after the generation it carries, or when that generation
  is above the table's: read the access again and reapply the change. A change to another role since your read does
  not conflict. A missing, `null` or non-integer `generation` is `422 validation_error`.
- **A retried `PUT` is safe.** A `PUT` that would store exactly the entry the role already has answers `200` with it
  and the current generation and writes nothing, whatever generation it carries, so retrying after a lost response
  never conflicts with your own write. A `PUT` or `DELETE` that changes an entry moves the generation by one; take
  the next one from the response.
- **A `DELETE` may carry `generation`.** Without it, it removes the entry whatever changed (`204`, or `404` when the
  role has none). With `&generation=<n>` it is conditional like a `PUT`: `409 access_conflict` when the role's entry
  changed after `n` or `n` is above the table's, `422` when `n` is not an integer, `404` when the role has no entry.
- **The response** is the entry as stored, the new `generation` and `ineffective_grants`, which reports a privilege
  that changes nothing (`already_global`, `sensitive_without_verb`, `lifts_row_policies`, `lifts_column_policies`)
  without failing the request. Your own permissions on the table are on `GET /v1/tables/{name}`.

The examples below read the generation first:

```bash
GEN=$(curl -s "$ADAS/v1/tables/dial_usage_log/access" -H "Authorization: Bearer $TOKEN" | jq .generation)
```

### A role containing `/`, `%` or other characters

The role is the `role` query parameter, URL-encoded like any query value, so every role is addressable: IdP group
`/analytics/viewers` is `?role=%2Fanalytics%2Fviewers` and is listed under `"/analytics/viewers"`; `team%ops` is
`?role=team%25ops`. The role is trimmed; a missing or blank `role` on `PUT` or `DELETE`, a blank `role` on `GET`, and a
`role` given more than once, is `400 bad_request`.

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=%2Fanalytics%2Fviewers" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"generation\": $GEN, \"privileges\": [\"read\"]}"
```

## Worked example: role `team-x` sees project `X-PRJ-1`

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=team-x" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<EOF
{"generation": $GEN, "privileges": ["read"],
 "rows": {"filter": {"op": "in", "args": [
   {"type": "field", "name": "project_id"},
   {"type": "array", "items": [{"type": "value", "value_type": "string", "value": "X-PRJ-1"}]}]}}}
EOF
```

The `filter` is the structured query's own filter grammar (the `filter` of `POST /v1/queries/execute`),
over the table's own columns. Every reader carrying `team-x` now sees only `X-PRJ-1` rows of
`dial_usage_log` — through the DSL, SQL, both CSV exports and Flight SQL, in every join, subquery and
`UNION` — and `GET /v1/tables/dial_usage_log` reports `permissions.rows_restricted: true` to it.

A role that reads the table through a global mapping (such as `READ_ONLY_ANALYTIC`) needs no `read` grant: send
`"privileges": []` with the `rows`. To remove the policy, `PUT` the entry again with `"rows": null`, or `DELETE`
the entry when the role holds nothing else.

### The same policy written as SQL

Instead of `filter`, `rows` can be given as `where`: the condition you would write after `WHERE`.

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=team-x" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<EOF
{"generation": $GEN, "privileges": ["read"], "rows": {"where": "project_id IN ('X-PRJ-1')"}}
EOF
```

The text is an input format. It is translated to a `filter`, and that filter is what is stored, returned
and enforced, so the response shows the tree and not your SQL. Everything a `filter` may not hold is
refused in SQL too: subqueries, other tables, aggregates.

An enrichment takes no policy in either form: a `PUT .../access?role={role}` naming one with a `rows` (or a `columns`,
or a `get`, `read` or `sensitive` privilege) is refused `409 enrichment_follows_source`, and the message names its
source. Its rows and columns are its source's (see below).

## The rules, briefly

- **A policy narrows; it never grants.** The role still needs a read path — a global `TABLE_READ`, a
  bundle, or a `read` grant on the table. A policy on a role with none of those confers no read.
- **A policy applies to its role whatever path that role reads by.** A role mapped to `READ_ONLY_ANALYTIC`
  (global read) is confined by its own policy exactly like a per-table grantee. You do **not** need to
  remap a role to narrow it.
- **No policy, no confinement.** A caller none of whose roles has a policy on a table sees every row it
  can read there.
- **Several policies combine with OR.** A caller in `team-x` and `team-y` sees both teams' rows.
- **A reading role without a policy lifts the confinement.** A caller in `team-x` (policy) and `analysts`
  sees every row when `analysts` may read the table — a `read` grant on it, or a mapping to global
  `TABLE_READ` — and has no policy there. So to narrow someone who also holds a broad role, give the broad
  role a policy too.
- **Unrelated roles never lift a confinement.** A caller's IdP groups and Core roles that give no read on the table
  never lift it, whether or not they have a policy.
- **An entry with `rows` and no read path adds rows.** Policies combine with OR across every role a confined caller
  carries, whether or not that role may read the table. So an entry carrying `rows` for a role with no `read` grant
  and no global read gives a caller carrying only that role nothing, but **adds** its rows to a caller confined by
  another role that also carries it. Do not use such an entry to narrow anyone.
- **Writes are not narrowed.** A confined role holding `write` may insert any row.

## Column policies

An entry's `columns` is an **allowlist**: `null` places no restriction, and a list restricts the role to the
columns it names. There is no deny form, and an empty list is refused `422` (a role meant to see no column
should not read the table).

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=team-x" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<EOF
{"generation": $GEN, "privileges": ["read"],
 "columns": ["model", "project_id", "request_time", "dial_usage_log_payload.request_body"]}
EOF
```

- **Names are the columns' exposed names**, the `name` `GET /v1/tables/{name}` returns and queries use, never a
  physical `source_name`, matched case-sensitively. Sensitive and heavy columns may be listed. A reserved column the
  catalog exposes, such as `dial_usage_log`'s `_ingested_at` and `_source_file`, is a column like any other: a list
  that omits it hides it. Names are stored de-duplicated and sorted; an unknown or blank name is `422` naming it.
- **An enrichment's columns are named on its source** as `<enrichment>.<column>`, for example
  `dial_usage_log_payload.request_body`. A list on the source that does not name an enrichment column hides it. The
  enrichment itself takes no column policy (`409 enrichment_follows_source`).
- **Each entry is one unit: a role's columns go with that role's rows.** A caller's **governing roles** on a table
  are the roles through which it reads it (or, when it may not read it, gets it), `*` included. A column is visible
  when some governing role **with no row policy** allows it, or when **every** governing role allows it ("allows":
  no column policy, or one that lists the column). So with no row policies the lists are combined (a union); for a
  confined caller they are intersected. The service cannot tell which role admitted a row, so it never shows one
  role's columns on rows only another role admits.

  > **Operator warning.** A caller carrying a narrower confining role loses columns a wider role would show it on
  > that role's own rows. Example: `R` reads project `P1` with `["a", "project"]` and `U` reads project `P2` with
  > every column; a caller in both sees `a` and `project` only, on both projects. To widen it, widen the narrower
  > role's list, or give the role meant to see everything no row policy.

  > **Gaining a column-restricted role can hide columns.** A caller holding role `A` — `rows` limited to project
  > `P1`, every column — that also gains role `B` — every row, `"columns": ["x"]` — sees **only `x`**, on every row it
  > reads. It now reads rows only `B` admits, and `B` shows nothing but `x` on them; since the service cannot tell
  > which role admitted a row, showing `A`'s other columns would show them on `B`'s rows too. Giving `B` a row policy
  > does not help: with both roles confined, only the columns both allow are shown. To keep `A`'s columns, list on `B`
  > the columns `A`'s readers need, or leave `B` without a column restriction.
- **Roles that do not govern take no part.** A role that only gets the table does not widen what a reader sees, nor
  does a role conferring only `write`, nor an IdP group with no read path. An entry with `rows` and no read path adds
  rows (see above) but its `columns` are never consulted.
- **A policy applies whatever path the role reads by.** To confine a role mapped to a global read, store an entry
  for it with `"privileges": []` and `columns`. A `*` entry's `columns` confine only callers that get or read the
  table through `*`'s own grant: `*` with `columns` and no privileges confines no one.
- **A caller with no governing role is not narrowed.** A caller holding neither `TABLE_GET` nor `TABLE_READ` on the
  table — one holding only `TABLE_LIST`, or a pipeline author holding only `REGISTRY_ADMIN` — sees every column as
  far as column policies go; its verb bars already decide what it reaches.
- **Computed fields follow the columns they return.** `dial_usage_log_payload`'s computed fields are visible only
  when both `dial_usage_log_payload.request_body` and `dial_usage_log_payload.request_cache_control` are.
- **`sensitive` stacks on top.** A sensitive column is visible only when the column policies allow it **and** the
  caller holds the clearance. A column policy grants nothing: whether the caller may get or read the table at all is
  decided by its privileges.
- **A hidden column is treated like an unentitled sensitive one.** It is absent from schema discovery, `SELECT *`,
  a structured query with no fields, CSV export, Flight SQL and `INFORMATION_SCHEMA`, `GET /v1/tables/{name}`, the
  list item's `column_count`, the import preview's `prev` and revision snapshots; referencing it fails as an unknown
  field, and a schema `PATCH` naming it is `422` unknown column (while the stored TTL is on it, any `ttl` member is
  `403 column_not_entitled`). A saved query or dashboard widget selecting it fails
  for that caller and still works for one who sees it.
- **What is not narrowed.** Row inserts (`write`), background work under the system identity (a pipeline's own
  reads see every column), and everything in `none` security mode, where column policies are stored and validated
  but not evaluated.
- **`permissions.columns_restricted`** on `GET /v1/tables` and `GET /v1/tables/{name}` is `true` when column policies
  hide at least one of the table's own columns from the caller (for an enrichment, by its source's policies). A
  column hidden only by the clearance does not set it, and it is `false` for a caller with no governing role. It does
  not disclose the policies; those are readable through the access API only.

### Renames, drops and the generation

A rename (`PATCH /v1/tables/{name}/schema`) rewrites every list that names the column, so the policy keeps
meaning the same column. A drop, a column a re-defined `PENDING` or `FAILED` table no longer declares, and the
columns of a deleted enrichment are pruned from every list. When pruning would leave a list empty, the operation is
refused `409 column_in_column_policy`, naming the columns and roles, before any DDL: edit those entries first. In
one `PATCH`, drops prune first and renames then rewrite, so a column added under a dropped name is never listed.
A column a row policy references is refused `409 column_in_row_policy` first.

An operation that rewrites an entry moves the access `generation` of the table **whose entries it rewrote**: the
table's own, or, for an enrichment's column or an enrichment's delete, its **source's** (the source's entries hold
the qualified names), never the enrichment's. Only the roles whose lists it rewrote count as changed, so a `PUT` for
another role carrying the earlier generation still succeeds. A schema change that rewrites no entry, a description
update and row writes leave the generation alone.

### Concurrent changes to one table

Access and schema writes to one table take turns. A write that finds another change to the same table in progress
waits for it at most `TABLES_LOCK_TIMEOUT` (default `10s`, see
[`configuration.md`](configuration.md#table-definition-writes)); past that it is refused `409 table_busy`, changes
nothing, and can be retried as sent.

### Pipelines and export

Where a path refuses `403 sensitive_column_not_entitled`, it refuses a policy-hidden column with
`403 column_not_entitled`, checked first: pipeline authoring (`enrich` and `aggregate` registration, enable, patch,
preview and an aggregate refresh on request), the runner's data plane under its own identity, and catalog export.
A schema `PATCH` carrying any `ttl` member while the stored TTL is on a policy-hidden column is refused
`403 column_not_entitled` too. An author with no governing role on a table is not refused for its column policies.

- **A restricted runner's list must name what the runner reads back**, not only what the declaration names:
  - the scan: the read source's `identity_column` and `version_column` (its cursor columns), the target's grain
    key and a group pipeline's group key — on `dial_usage_log`, `event_id` and `_ingested_at` — and, for a target
    aligned with its source, the source's partition column (`request_time` on `dial_usage_log`), which every row
    returns as `__partition_v`;
  - group assembly (`group-rows` and the group preview): the group key and the read source's `version_column` and
    `identity_column`;
  - `materialize`: the read source's `version_column` and `identity_column`, whose values its `nextCursor`
    carries.

  Otherwise every scan, group assembly or materialize it requests is refused `403 column_not_entitled` naming the
  missing column.
- **Parse failures surface as `column_not_entitled`.** A stored or submitted `filter`, `ready_when.signal`,
  `prefer_sql`, enrich expression or aggregate-spec `filter`/`measures[].where` that references a hidden column
  fails to parse as unknown, and the failure does not say which column caused it. For a caller restricted on a table
  the pipeline reads, such a failure is answered `403 column_not_entitled`, even when the real cause is a column the
  table no longer has. A caller restricted by no column policy but lacking the clearance on such a table, when it or
  one of its active enrichments has a `sensitive` column, gets `403 sensitive_column_not_entitled` for the same
  failure, so on `dial_usage_log`, whose payload enrichment has `sensitive` columns, an uncleared author gets no
  parse diagnostic for these fragments. An aggregate SQL statement that fails to plan is refused only for a protected column its text
  mentions; any other planning failure stays `422`.
- **Previews drop the version instants instead of failing.** A `sql` enrich preview and an aggregate preview do not
  judge the version and identity columns. When the read source's `version_column` is hidden from the caller, an
  `enrichment_behind` note still names the enrichment and gives the gap, but carries neither the newest sampled
  version nor the enrichment's materialized position. `lag_seconds` (scan, materialize, refresh on request,
  `GET /v1/pipelines/{name}`) is always returned: it is a duration, not a column value.
- **The pipeline read is unchanged.** `GET /v1/pipelines` and `GET /v1/pipelines/{name}` return cursor and position
  values (`state.cursor_*`, `state.materialized_through_*`, `state.clamp.cursor_*`) to any `REGISTRY_READ` holder,
  as they do regardless of table privileges and sensitive flags. Column policies do not change that: to keep those
  values from a column-restricted identity, do not grant it `REGISTRY_READ`.
- **Export** refuses a bundle table with a column hidden from the caller, and never drops it silently. A bundle
  carries no access entries.

## Enrichments follow their source; aggregate targets need their own policies

An enrichment is only ever read through its source, so **the source's entries decide its rows and columns**: a
caller confined on `dial_usage_log` reads the values of `dial_usage_log_payload` on exactly the usage-log rows it
reads, and sees the enrichment columns the source's lists name. Nothing needs to be set on the enrichment.

One caveat: a value a pipeline derives from **another table**, or from **other source rows** (a group
trigger, or any enrichment coarser than one source row), is not narrowed by the policies that hide those
rows. A confined caller reads, on each row it may read, a value that may summarise rows it may not. Either
confine the whole group with the source's policy, or keep such a value off the enrichment.

Aggregate targets are populated by pipelines that read the whole source. **Neither row nor column policies
propagate**: a role confined on `dial_usage_log` that can read an aggregate of it sees every project's totals
there, and a count derived from a column hidden from it on the source is visible on the target. For each aggregate
target a restricted role may read, either:

- store an entry for the role on that table with its own `rows` (e.g. `project_id IN (...)` on an aggregate grouped
  by `project_id`) and `columns`, or
- leave the role without a read path to it.

A confined caller also cannot arm, preview or refresh a pipeline over a table it is confined on, nor use
the pipeline data plane over it (`403 row_policy_confined`): a pipeline would read those rows unfiltered on
its behalf.

## Keep the predicate cheap

The predicate runs on every read of the table by a confined caller. Filter on **ordering-key columns**
where you can: on `dial_usage_log`, `project_id` leads the sort key, so a project policy prunes the scan as
well as any `WHERE project_id …` would. A predicate wrapping a column in a function (`lower(col) = …`) or
filtering a non-key column reads more of the table.

A policy may use scalar functions and the relative time functions (`now`, `date_add`, `date_sub` — e.g.
"the last 90 days"); it may not use aggregate or window functions, subqueries or enrichment fields, nor
any `param` other than the two caller values below. A column a policy filters on cannot be dropped or renamed, nor left out or given another type when a
failed table's schema is posted again, until the policy is changed (`409 column_in_row_policy`).

## Own rows: comparing a column with the caller

A policy may compare a `string` column with a value of the caller reading the table: `caller.user_hash`
(the hash DIAL Core writes to `user_hash`; it needs the `logging-*` settings in
[`configuration.md`](configuration.md)) or `caller.project` (the project of a project API key). One policy
on one role then gives every caller its own rows:

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=user" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<EOF
{"generation": $GEN, "privileges": ["read"],
 "rows": {"filter": {"op": "eq", "args": [
   {"type": "field", "name": "user_hash"},
   {"type": "param", "name": "caller.user_hash"}]}}}
EOF
```

Written as SQL, the same policy is `"rows": {"where": "user_hash = caller.user_hash"}`.

A caller value is allowed only as one side of `eq` or `ne` against a `string` column. A caller without the
value matches no row, also under `ne` and `not`; an `or` with another branch still applies, so
`user_hash = caller.user_hash OR project_id = caller.project` serves users and project keys alike. Roles
that should read every row simply get no policy.

## Everyone: the `*` grantee

When users share no common role, name `*` instead of a role. It stands for every authenticated caller, in
the read-side privileges (`get`, `read`, `sensitive`; never `write` or `modify`), as the subject of a row policy
and of a column policy. One entry gives every user their own rows of a table and leaves administrators unconfined:

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=*" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<EOF
{"generation": $GEN, "privileges": ["read"], "rows": {"where": "user_hash = caller.user_hash"}}
EOF
```

Keep the `rows` in that entry. A `read` for `*` with no `rows` lets every caller read every row, and lifts the
policies of every other role on the table, because every caller then carries a role that reads with no policy.
The entry is accepted, and its response reports it under `ineffective_grants` with reason `lifts_row_policies`
(and `lifts_column_policies` when another entry carries `columns` and the `*` entry has none).

The rules above apply to `*` as to any role. A caller sees the rows of the `*` policy plus those of the
policies on its own roles, and a caller one of whose roles reads the table with no policy (an
administrator's global read, or a `read` grant by name) sees every row. So a user meant to be confined
must have no other unpolicied way to read the table.

## Seeing a policy applied

ADAS enforces row policies by sending ClickHouse the `additional_table_filters` setting with each read. To see
it on the server:

```sql
SELECT event_time, query, Settings['additional_table_filters'] AS row_filter
FROM system.query_log
WHERE type = 'QueryFinish' AND has(mapKeys(Settings), 'additional_table_filters')
ORDER BY event_time DESC
LIMIT 10;
```

ADAS's own debug log names the confined tables (never the predicate), and the query span carries the
`adas.row_policy.tables` attribute. When a policy references a caller value the caller does not have, the
log names it and the span carries `adas.row_policy.unknown.<name>` (for example
`adas.row_policy.unknown.caller.user_hash`), never the value itself.

Column policies are applied when the service builds the caller's schema, so a hidden column never reaches
ClickHouse in a user query; check what a caller sees with `GET /v1/tables/{name}` or schema discovery under its
credential.

## Before rolling back

A build without row policies ignores the `row_policies` column, and one without column policies ignores the
`column_policies` column, so a rollback silently stops enforcing them: rows and columns they hide become visible.
Remove the policies — or the restricted roles' read paths — first if a rollback must not widen reads.
