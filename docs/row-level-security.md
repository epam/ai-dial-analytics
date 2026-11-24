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
- `sensitive` still applies on top: a sensitive column needs the policy **and** the clearance, or it is hidden.
- A rename updates every list. A drop that would empty a list is refused `409 column_in_column_policy`.

**Each role shows its columns on its rows.** For one caller, each column is one of:

| State | When | What the caller gets |
|---|---|---|
| visible | a role with no row policy allows it, or every role the caller reads through and every rows-only entry it carries allows it | its value on every row |
| masked | not visible, but some role the caller reads through allows it | its value on the rows an allowing role's `rows` admit, absent (null) on the others |
| hidden | no role the caller reads through allows it | nothing: it is absent everywhere, revision snapshots included, and referencing it fails as an unknown field |

So a caller with role `A` (rows of project `P1`, all columns) who gains role `B` (all rows, `"columns": ["x"]`) reads
every row: `x` everywhere, and `A`'s other columns on the rows of `P1` only. Gaining a role never removes a column from
the rows your other roles admit.

A **rows-only entry**, one carrying `rows` on a role with no read path, adds its rows to a confined caller, and its
`columns` narrow what those rows show: a column its list leaves out is absent on the rows only it adds. With
`"columns": null` it shows the other roles' columns there.

**How a masked column reads.**

- Every clause reads the masked value: filters, grouping, sorting, joins, functions and aggregates never see a value
  that is absent to the caller. An absent cell reads as `NULL`, or as an empty array, map or object inside an
  expression; an output that is the bare composite column returns `null`.
- A JSON row lists the output columns it holds absent or partial cells for under the reserved member `$masked`. An
  aggregate or window cell is partial, and listed, when any row it summarizes had the cell absent; a row-collapsing
  distinct or `UNION` marks a row when any row it merged did. Clients that sum a masked column must read `$masked`.
- Expanding an absent array yields no element, and so no mark: a count over the expansion covers the present rows only.
- CSV export and Flight SQL return absent cells as empty fields and Arrow nulls, with no flag.
- Schema discovery marks the field `"masked": true`, and `permissions.columns_restricted` is `true` when a policy
  hides **or** masks at least one column. A system table's revision snapshot carries no columns to judge, so there it
  is `true` whenever a column policy confines the caller (see
  [`audit-trail`](../openspec/specs/audit-trail/spec.md), "Historical table snapshots").
- Two query shapes are refused `400` beside a masked field: `INTERSECT` or `EXCEPT` over an output that reads it, and
  an output name or lambda parameter that equals a table the query reads or begins with `<table>.`. An output named
  after the enrichment field it projects, as one projected without `as` is, is allowed. A wildcard query can meet either
  refusal once a masked column joins the wildcard.
- A DSL `distinct` projection with a window output is refused `400` when an output reads a masked field, until it is
  supported; the SQL surface runs it.
- `$masked` is refused as an output name on every surface, for every caller.

**Hiding instead of masking.** On a table where the source, or one of its enrichments, stores a column whose physical
name is the name of the source or of one of its enrichments, a column that would be masked is hidden instead. The data
store reads a table-qualified name through such a column, so no mask could be trusted there.

### Pipelines, TTL and export

Pipeline authoring and the runner refuse a hidden **or masked** column with `403 column_not_entitled`: a pipeline reads
without a row filter or a mask. A restricted runner's list must also name the columns it reads back: on
`dial_usage_log`, `event_id`, `_ingested_at` and, for an aligned target, `request_time`. A TTL may not be on a masked
column (`403 column_not_entitled`), since which rows it removes would disclose the values absent to the caller. Catalog
export refuses a hidden column and exports a masked one.

### What masking discloses

Masking shows each role's columns on its rows, and accepts these disclosures to do so. Avoid each by listing the
column on every role the caller reads through and on every rows-only entry, which makes it visible.

- **Which rows an allowing role admits.** A cell is present exactly where an allowing role's `rows` admit the row. On
  that role's own rows this is no news; on rows only another role admits it is, even when the policy reads a column
  the caller cannot see, such as a sensitive one. Keep a policy over a sensitive column off a role whose users hold
  other reading roles.
- **Filter cost.** A filter on a masked column keeps the table's key and partition pruning, so how much a query reads,
  and with it its latency and its share of the query budget, depends on stored values, granule by granule. The same
  holds for an enrichment conjunct copied into its join and for an aligned lookup's partition pre-selection. Repeated
  probes can recover a single row's value when the caller's filter isolates it in a granule.
- **Row order.** Without a total sort, rows come back in roughly storage order, which follows the ordering-key and
  partition values. An absent key cell can be read off its neighbours. List key columns on every reading role, or keep
  them off roles whose rows interleave with another role's in storage order.
- **Enrichment keys.** The enrichment join runs on stored keys, so a visible enrichment column keyed by a masked source
  column still shows the value its absent key selects. Allow the key wherever its enrichment's columns are allowed.

A sort on a masked column that leads the ordering key cannot read in key order: a raw read of such a table scans every
row its filter selects before keeping the page.

## Own rows: comparing a column with the caller

A policy may compare a `string` column with `caller.user_hash()` (needs the `logging-*` settings in
[`configuration.md`](configuration.md)) or `caller.project()` (the project of a project API key), using `=` or `!=`:

```json
{"generation": 7, "privileges": ["read"], "rows": {"where": "user_hash = caller.user_hash()"}}
```

A caller without the value matches no row. The older spelling without the parentheses, `caller.user_hash`, is still
accepted in a policy and stores the same filter.

### Your own rows in a query

A role that reads the table with no policy lifts the own-rows policy, so an administrator, whom an own-rows policy on
`*` also matches, reads every row. To see only its own, the query asks for them with the same value:

```sql
SELECT * FROM dial_usage_log WHERE user_hash = caller.user_hash()
```

The value is filled in for whoever runs the query, so one saved query or dashboard widget shows each viewer its own
rows. See [`sql-reference.md`](sql-reference.md), "Your own rows: caller values".

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
