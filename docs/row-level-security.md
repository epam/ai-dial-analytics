# Row- and column-level security

This guide is for administrators who limit which rows and columns a role sees in a table, and for developers of
clients that manage table access. It covers what to set and what to watch for. The full contract, with every rule and
error code, is [`openspec/specs/table-access-control/spec.md`](../openspec/specs/table-access-control/spec.md).

## Concepts

- **One entry per role.** A table's access is one entry per raw provider role:
  `{"privileges": [...], "rows": ..., "columns": ...}`. `privileges` are the per-table grants (`get`, `read`,
  `sensitive`, `write`, `modify`). `rows` is the role's **row policy**, which limits the rows it reads. `columns` is
  its **column policy**, which limits the columns it sees.
- **Who manages it.** A caller holding `TABLE_GRANT`. `permissions.grant` on `GET /v1/tables/{name}` tells a client
  whether the caller holds it.
- **A policy narrows, it never grants.** The role still needs a read path: a global `TABLE_READ`, a bundle, or a
  `read` grant. A policy applies whatever the path, so a role mapped to a global read is narrowed by an entry with
  `"privileges": []` and the policy.
- **`*`** stands for every authenticated caller (see [Everyone](#everyone-the--grantee)).
- **Enrichments follow their source.** An enrichment is always read through its source, so the source's entries
  decide its rows and columns. Nothing is set on the enrichment itself.
- **Aggregate targets need their own entries.** Their pipelines read the whole source, so neither row nor column
  policies carry over.
- **Not narrowed:** row inserts (`write`), pipelines' own background reads, and everything in `none` security mode,
  where policies are stored but not applied.

## The API

| Call | Does |
|---|---|
| `GET /v1/tables/{name}/access` | Every role's entry, the table's `generation` and `ineffective_grants` |
| `GET /v1/tables/{name}/access?role={role}` | One role's entry; `404` when it has none |
| `PUT /v1/tables/{name}/access?role={role}` | Replaces the role's **whole** entry: `{"generation", "privileges", "rows", "columns"}` |
| `DELETE /v1/tables/{name}/access?role={role}` | Removes the entry (`204`, or `404` when there is none) |

- **The role is a URL-encoded query value.** IdP group `/analytics/viewers` is `?role=%2Fanalytics%2Fviewers`.
  A blank or repeated `role` is `400`, and so is a missing one on `PUT` or `DELETE`.
- **A `PUT` replaces the whole entry.** A part you leave out is cleared. To change one part, send the others as you
  read them. An entry left empty is removed.
- **Send the `generation` you read.** A `PUT` is refused `409 access_conflict` only when that same role's entry
  changed since your read: read again and reapply. A `PUT` that changes nothing answers `200`, so retrying is safe.
  A missing or non-integer `generation` is `422`.
- **`DELETE` is unconditional** unless you add `&generation=<n>`.
- **Writes to one table take turns.** One that waits longer than `TABLES_LOCK_TIMEOUT` (default `10s`, see
  [`configuration.md`](configuration.md#table-definition-writes)) is refused `409 table_busy` and can be retried.
- **The response** is the stored entry, the new `generation` and any [warnings](#warnings-ineffective_grants).

The examples below read the generation first:

```bash
GEN=$(curl -s "$ADAS/v1/tables/dial_usage_log/access" -H "Authorization: Bearer $TOKEN" | jq .generation)
```

## Worked example: role `team-x` sees project `X-PRJ-1`

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=team-x" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<EOF
{"generation": $GEN, "privileges": ["read"], "rows": {"where": "project_id IN ('X-PRJ-1')"}}
EOF
```

`where` is the condition you would write after `WHERE`. It is translated to the structured query's `filter`, which is
what is stored and returned, so `rows` can also be sent as that filter directly:

```json
{"filter": {"op": "in", "args": [
  {"type": "field", "name": "project_id"},
  {"type": "array", "items": [{"type": "value", "value_type": "string", "value": "X-PRJ-1"}]}]}}
```

Every reader carrying `team-x` now sees only `X-PRJ-1` rows of `dial_usage_log`, through the DSL, SQL, CSV export
and Flight SQL, in every join and subquery, and `GET /v1/tables/dial_usage_log` reports
`permissions.rows_restricted: true` to it. To remove the policy, `PUT` the entry with `"rows": null`, or `DELETE` it
when nothing else is left.

A policy may use the table's own columns, scalar functions and relative time (`now`, `date_add`, `date_sub`). It may
not use aggregate or window functions, subqueries or enrichment fields. The predicate runs on every read by a confined
caller, so filter on ordering-key columns where you can (`project_id` leads the sort key of `dial_usage_log`). A
column a policy uses cannot be dropped or renamed until the policy changes (`409 column_in_row_policy`).

**Several roles combine with OR.** A caller in `team-x` and `team-y` sees both teams' rows. A caller who also holds a
role that reads the table with **no** policy sees every row, so to narrow such a caller, give the broad role a policy
too. Roles that give no read on the table never lift a policy. An entry with `rows` but no read path adds its rows to
a caller confined by another role: do not use one to narrow anyone.

## Column policies

`columns` is an allowlist. `null` means every column; a list limits the role to the columns it names; `[]` is `422`.

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=team-x" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<EOF
{"generation": $GEN, "privileges": ["read"],
 "columns": ["model", "project_id", "request_time", "dial_usage_log_payload.request_body"]}
EOF
```

- **Names** are the exposed column names that queries use, matched case-sensitively. A list that omits a reserved
  column such as `_ingested_at` hides it.
- **Enrichment columns** are listed on the source as `<enrichment>.<column>`. A source list that does not name one
  hides it. The computed fields of `dial_usage_log_payload` show only when both `request_body` and
  `request_cache_control` do.
- **A hidden column is absent everywhere**: schema discovery, `SELECT *`, CSV export, Flight SQL,
  `GET /v1/tables/{name}` and revision snapshots. Referencing it fails as an unknown field, so a saved query that
  selects it fails for that caller only.
- **`sensitive` still applies.** A sensitive column shows only when the column policy allows it **and** the caller
  holds the clearance.
- **Renames and drops.** A rename updates every list. A drop that would leave a list empty is refused
  `409 column_in_column_policy`: edit those entries first.
- `permissions.columns_restricted` on `GET /v1/tables/{name}` is `true` when column policies hide at least one column
  from the caller.

### How several roles combine

Only the roles through which a caller reads the table count. A column shows when a role **with no row policy**
allows it, or when **every** such role allows it. The service cannot tell which role admitted a row, so it never shows
one role's columns on rows only another role admits.

This means **gaining a column-restricted role can hide columns.** A caller with role `A` (rows of project `P1`, all
columns) who gains role `B` (all rows, `"columns": ["x"]`) sees only `x`, on every row. To keep `A`'s columns, list
them on `B`, or leave `B` without a column policy. Giving `B` a row policy does **not** help: with both roles
confined, only the columns both allow show.

### Pipelines and export

Pipeline authoring, the runner's data plane and catalog export refuse a hidden column with
`403 column_not_entitled`. Export never drops it silently.

- **A restricted runner's list must name the columns it reads back**, not only those its pipeline names: the read
  source's `identity_column` and `version_column`, the target's grain key, a group pipeline's group key, and for an
  aligned target the source's partition column. On `dial_usage_log` that is `event_id`, `_ingested_at` and
  `request_time`. Otherwise every scan, group assembly or materialize is refused, naming the missing column.
- **A malformed `enrich` fragment** (`filter`, `ready_when.signal`, `prefer_sql`, an expression) answers
  `403 column_not_entitled` to a column-restricted author, because the parser cannot say which column failed. An
  author without the clearance on a source with `sensitive` columns, such as `dial_usage_log`, gets
  `403 sensitive_column_not_entitled` the same way. Debug it as an author with the clearance and no column
  restriction.
- **Cursor values** in `GET /v1/pipelines` are shown to any `REGISTRY_READ` holder. To keep them from a restricted
  identity, do not grant it `REGISTRY_READ`.
- **A confined caller** cannot arm, preview or refresh a pipeline over a table it is confined on
  (`403 row_policy_confined`): the pipeline would read those rows unfiltered.

## Own rows: comparing a column with the caller

A policy may compare a `string` column with a value of the caller: `caller.user_hash` (the hash DIAL Core writes to
`user_hash`; it needs the `logging-*` settings in [`configuration.md`](configuration.md)) or `caller.project` (the
project of a project API key). One policy then gives every caller its own rows:

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=user" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<EOF
{"generation": $GEN, "privileges": ["read"], "rows": {"where": "user_hash = caller.user_hash"}}
EOF
```

A caller value may appear only in `=` or `!=` against a `string` column. A caller without the value matches no row,
even under `!=`. An `OR` still applies, so `user_hash = caller.user_hash OR project_id = caller.project` serves users
and project keys alike.

## Everyone: the `*` grantee

`*` stands for every authenticated caller. It may hold `get`, `read` and `sensitive` (`write` or `modify` is `422`),
and may carry `rows` and `columns`. One entry gives every user their own rows, while administrators, whose global read
has no policy, still see everything:

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=*" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @- <<EOF
{"generation": $GEN, "privileges": ["read"], "rows": {"where": "user_hash = caller.user_hash"}}
EOF
```

Keep the `rows` in that entry. A `read` for `*` with no `rows` lets every caller read every row and lifts every other
role's policies, because every caller then holds a role that reads with no policy. A user meant to be confined must
have no other unrestricted way to read the table.

### Warnings: `ineffective_grants`

The `PUT` response and both `GET`s report warnings in `ineffective_grants` without failing the request. Each item is
`{role, key, reason, message}`.

| Reason | Means |
|---|---|
| `already_global` | The role already holds this privilege globally |
| `sensitive_without_verb` | `sensitive` for a role that can neither get, read nor modify the table or its enrichments |
| `lifts_row_policies` | `read` for `*` with no `rows`, while another role has `rows`: those policies confine no one |
| `lifts_column_policies` | `read` for `*` with neither `rows` nor `columns`, while another role has `columns`: they hide nothing |
| `everyone_reads_all_rows` | `read` for `*` with no `rows`: every caller reads every row. Shown for as long as the entry stays |

## Checking that a policy is applied

- **As the caller:** `GET /v1/tables/{name}` with its credential shows `permissions.rows_restricted`,
  `permissions.columns_restricted` and the columns it sees.
- **In ClickHouse:** ADAS sends each row policy as the `additional_table_filters` setting.

  ```sql
  SELECT event_time, query, Settings['additional_table_filters'] AS row_filter
  FROM system.query_log
  WHERE type = 'QueryFinish' AND has(mapKeys(Settings), 'additional_table_filters')
  ORDER BY event_time DESC
  LIMIT 10;
  ```

- **In ADAS telemetry:** the query span carries `adas.row_policy.tables`. When the caller lacks a value a policy uses,
  the span carries `adas.row_policy.unknown.<name>` (for example `adas.row_policy.unknown.caller.user_hash`).

Column policies are applied before the query is built, so a hidden column never reaches ClickHouse.

## Pitfalls

- **A broad role lifts row policies.** See [Worked example](#worked-example-role-team-x-sees-project-x-prj-1).
- **A narrow column list can hide columns from other roles.** See
  [How several roles combine](#how-several-roles-combine).
- **Aggregate targets are not narrowed.** A role confined on `dial_usage_log` that can read an aggregate of it sees
  every project's totals there. Give the role its own `rows` and `columns` on the aggregate, or no read path to it.
- **Derived values can leak.** An enrichment value computed from other rows or tables may summarise rows the caller
  cannot read. Confine the whole group with the source's policy, or keep such a value off the enrichment.
- **Revisions.** A snapshot hides a column that the revision's policies or the table's current ones hide, so a
  restriction added later also hides it in older revisions. A deleted table's revisions are judged by the policies it
  had when it was deleted.

## Before rolling back

An older build ignores column policies (and a build without row policies ignores row policies), so a rollback makes
the rows and columns they hide visible. Delete the column policies first, or the restricted roles' read paths, if a
rollback must not widen reads.
