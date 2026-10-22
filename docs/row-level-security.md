# Row-level security

A **row policy** narrows which rows of a table a provider role reads. It is attached to a raw provider
role on one table, the same way the per-table grant lists are, and it is managed by a caller holding
`TABLE_GRANT`. The behaviour is specified in
[`openspec/specs/table-access-control/spec.md`](../openspec/specs/table-access-control/spec.md); this page
is the operator's guide.

## Worked example: role `team-x` sees project `X-PRJ-1`

```bash
curl -X PUT "$ADAS/v1/tables/dial_usage_log/row-policies/team-x" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"filter": {"op": "in", "args": [
        {"type": "field", "name": "project_id"},
        {"type": "array", "items": [{"type": "value", "value_type": "string", "value": "X-PRJ-1"}]}]}}'
```

The `filter` is the structured query's own filter grammar (the `filter` of `POST /v1/queries/execute`),
over the table's own columns. Every reader carrying `team-x` now sees only `X-PRJ-1` rows of
`dial_usage_log` — through the DSL, SQL, both CSV exports and Flight SQL, in every join, subquery and
`UNION` — and `GET /v1/tables/dial_usage_log` reports `permissions.rows_restricted: true` to it.

`GET /v1/tables/{name}/row-policies` lists a table's policies; `DELETE .../row-policies/{role}` removes one.

## The rules, briefly

- **A policy narrows; it never grants.** The role still needs a read path — a global `TABLE_READ`, a
  bundle, or a `read` grant on the table. A policy on a role with none of those confers nothing.
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
- **Unrelated roles count for nothing.** A caller's IdP groups and Core roles that give no read on the table
  never lift a confinement, whether or not they have a policy.
- **Writes are not narrowed.** A confined role holding `write` may insert any row.

## Derived tables are not covered — set their policies too

Enrichment and aggregate targets are populated by pipelines that read the whole source. **A policy on the
source does not propagate**: a role confined on `dial_usage_log` that can read an aggregate of it sees every
project's totals there. For each derived table a confined role may read, either:

- store a policy for the role on that table (e.g. `project_id IN (...)` on an aggregate grouped by
  `project_id`), or
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
"the last 90 days"); it may not use `param`, aggregate or window functions, subqueries or enrichment
fields. A column a policy filters on cannot be dropped or renamed, nor left out when a failed table's
schema is posted again, until the policy is changed (`409 column_in_row_policy`).

## Seeing a policy applied

ADAS enforces policies by sending ClickHouse the `additional_table_filters` setting with each read. To see
it on the server:

```sql
SELECT event_time, query, Settings['additional_table_filters'] AS row_filter
FROM system.query_log
WHERE type = 'QueryFinish' AND has(mapKeys(Settings), 'additional_table_filters')
ORDER BY event_time DESC
LIMIT 10;
```

ADAS's own debug log names the confined tables (never the predicate), and the query span carries the
`adas.row_policy.tables` attribute.

## Before rolling back

A build without this feature ignores the `row_policies` column, so a rollback silently stops enforcing
every stored policy. Remove the policies — or the confined roles' read paths — first if a rollback must not
widen reads.
