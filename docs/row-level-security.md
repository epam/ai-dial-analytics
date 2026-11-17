# Row- and column-level security

For administrators who limit which rows and columns a role sees, and for developers of clients that manage table
access. The full contract, with every rule and error code, is
[`openspec/specs/table-access-control/spec.md`](../openspec/specs/table-access-control/spec.md).

## Concepts

- **One entry per role.** A table's access is one entry per raw provider role:
  `{"privileges": [...], "rows": ..., "columns": ...}`. `privileges` are the per-table grants (`get`, `read`,
  `sensitive`, `write`, `modify`), `rows` is a **row policy** and `columns` a **column policy**.
- **Who manages it:** a caller holding `TABLE_GRANT`. `permissions.grant` on `GET /v1/tables/{name}` says whether the
  caller holds it.
- **A policy narrows, it never grants.** The role still needs a read path (a global `TABLE_READ`, a bundle or a `read`
  grant). To narrow a role that reads through a global mapping, store an entry with `"privileges": []` and the policy.
- **`*`** stands for every authenticated caller (see [Everyone](#everyone-the--grantee)).
- **Enrichments follow their source.** The source's entries decide an enrichment's rows and columns. An enrichment's
  own entry holds only `write` and `modify`.
- **Aggregate targets need their own entries.** Their pipelines read the whole source, so no policy carries over.
- **Not narrowed:** row inserts, pipelines' own background reads, and everything in `none` security mode.

## The API

| Call | Does |
|---|---|
| `GET /v1/tables/{name}/access` | Every role's entry, the table's `generation` and `ineffective_grants` |
| `GET /v1/tables/{name}/access?role={role}` | One role's entry; `404` when it has none |
| `PUT /v1/tables/{name}/access?role={role}` | Replaces the role's **whole** entry: `{"generation", "privileges", "rows", "columns"}` |
| `DELETE /v1/tables/{name}/access?role={role}` | Removes the entry; optional `&generation=<n>` makes it conditional |

- **URL-encode the role:** group `/analytics/viewers` is `?role=%2Fanalytics%2Fviewers`.
- **A `PUT` replaces the whole entry.** Send the parts you do not change as you read them. An empty entry is removed.
- **Send the `generation` you read.** The `PUT` is refused `409 access_conflict` only when that role changed since your
  read; read again and reapply. An unchanged `PUT` answers `200`, so a retry is safe.
- **Writes to one table take turns.** One that waits past `TABLES_LOCK_TIMEOUT` (default `10s`) gets
  `409 table_busy`; retry it.

## Example: role `team-x` sees project `X-PRJ-1`

```bash
GEN=$(curl -s "$ADAS/v1/tables/dial_usage_log/access" -H "Authorization: Bearer $TOKEN" | jq .generation)
curl -X PUT "$ADAS/v1/tables/dial_usage_log/access?role=team-x" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"generation\": $GEN, \"privileges\": [\"read\"], \"rows\": {\"where\": \"project_id IN ('X-PRJ-1')\"}}"
```

`where` is what you would write after `WHERE`; it is stored and returned as the equivalent structured `filter`.
Readers carrying `team-x` now see only `X-PRJ-1` rows on every query surface, and `GET /v1/tables/dial_usage_log`
reports `permissions.rows_restricted: true` to them. A policy may use the table's own columns, scalar functions and
relative time, but no aggregates, subqueries or enrichment fields. Prefer ordering-key columns: the predicate runs on
every read.

**Several roles combine with OR.** A caller in `team-x` and `team-y` sees both teams' rows. A caller who also holds a
role that reads the table with **no** policy sees every row, so give that role a policy too.

## Column policies

`columns` is an allowlist: `null` means every column, `[]` is `422`.

```json
{"generation": 7, "privileges": ["read"], "columns": ["model", "project_id", "dial_usage_log_payload.request_body"]}
```

- Names are the exposed column names, case-sensitive. Enrichment columns are listed on the source as
  `<enrichment>.<column>`.
- A hidden column is absent everywhere, revision snapshots included, and referencing it fails as an unknown field.
- `sensitive` still applies on top: a sensitive column needs the policy **and** the clearance.
- A rename updates every list. A drop that would empty a list is refused `409 column_in_column_policy`.
- `permissions.columns_restricted` is `true` when a policy hides at least one column from the caller.

**Gaining a column-restricted role can hide columns.** A column shows when a role with no row policy allows it, or
when every role the caller reads through allows it. So a caller with role `A` (rows of project `P1`, all columns) who
gains role `B` (all rows, `"columns": ["x"]`) sees only `x`. List `A`'s columns on `B`, or leave `B` without a column
policy; giving `B` a row policy does not help.

### Pipelines and export

Pipeline authoring, the runner and catalog export refuse a hidden column with `403 column_not_entitled`. A restricted
runner's list must also name the columns it reads back: on `dial_usage_log`, `event_id`, `_ingested_at` and, for an
aligned target, `request_time`.

## Own rows: comparing a column with the caller

A policy may compare a `string` column with `caller.user_hash` (needs the `logging-*` settings in
[`configuration.md`](configuration.md)) or `caller.project` (the project of a project API key), using `=` or `!=`:

```json
{"generation": 7, "privileges": ["read"], "rows": {"where": "user_hash = caller.user_hash"}}
```

A caller without the value matches no row.

## Everyone: the `*` grantee

`*` may hold `get`, `read` and `sensitive` (`write` and `modify` are `422`), with `rows` and `columns`. Storing the
own-rows entry above for `*` gives every user their own rows, while administrators, whose global read has no policy,
still see everything. Keep the `rows` on it: a `read` for `*` without `rows` lets every caller read every row and lifts
every other role's row policies.

`ineffective_grants`, in the `PUT` response and both `GET`s, warns without failing:

| Reason | Means |
|---|---|
| `already_global` | The role already holds this privilege globally |
| `sensitive_without_verb` | `sensitive` for a role that can neither get, read nor modify the table or modify one of its enrichments |
| `lifts_row_policies` | `read` for `*` without `rows` while another role has `rows`: those policies confine no one |
| `lifts_column_policies` | `read` for `*` without `rows` or `columns` while another role has `columns` |
| `everyone_reads_all_rows` | `read` for `*` without `rows`: every caller reads every row (shown while the entry stays) |

## Watch out for

- **Aggregate targets.** A role confined on `dial_usage_log` that can read an aggregate of it sees every project's
  totals there, unless the aggregate has its own entry for the role.
- **Derived values.** An enrichment value computed from other rows may summarise rows the caller cannot read.
- **Checking a policy.** Read `GET /v1/tables/{name}` as the caller: `permissions.rows_restricted`,
  `columns_restricted` and the listed columns show what applies. In ClickHouse, row policies appear as the
  `additional_table_filters` setting in `system.query_log`.
- **Rollback.** An older build ignores column policies, so a rollback reveals the columns they hide. Delete them
  first if that must not happen.
