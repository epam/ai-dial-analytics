# Catalog promotion

Moving user tables, columns and pipelines from one ADAS environment to another (for example staging → production) with
the catalog transfer endpoints. Behaviour is specified in
[`openspec/specs/catalog-export-import/spec.md`](../openspec/specs/catalog-export-import/spec.md); this is the
operator's sequence around it.

## 1. Export on the source environment

```bash
curl -s -X POST localhost:5000/v1/catalog/export/preview -H 'Content-Type: application/json' \
  -d '{"components":[{"type":"table","name":"usage_sentiment"}]}'      # what the closure pulls in, and why
curl -s -X POST localhost:5000/v1/catalog/export -H 'Content-Type: application/json' \
  -d '{"components":[{"type":"table","name":"usage_sentiment"}]}' -o bundle.json
```

An empty body (`{}`) exports everything exportable. System tables the bundle needs travel by name only, under
`required_system_tables`, never as tables. OTLP landing tables and PENDING/FAILED tables are left out and listed
under `skipped`. Access travels only on request (below); otherwise each environment sets its own (step 4).

To carry access too, add `"include_access": true` (this needs `TABLE_GRANT`):

```bash
curl -s -X POST localhost:5000/v1/catalog/export/preview -H 'Content-Type: application/json' \
  -d '{"include_access":true}'                                          # access, and what stays behind
curl -s -X POST localhost:5000/v1/catalog/export -H 'Content-Type: application/json' \
  -d '{"include_access":true}' -o bundle.json
```

The bundle then has an `access` member: every role's entry on each covered table, system tables included, in the
shape `GET /v1/tables/{name}/access` returns. Exporting everything covers every system table you may get that keeps an
entry once unportable state is left behind; a selection may name a system table to export its access. Only what a `PUT .../access` would accept now travels. Leftover state an
earlier build stored (an enrichment's read side, `write` or `modify` for `*` or on a system table, a role that is now a
bundle or privilege name, an entry naming a skipped enrichment) stays behind. **The export preview's `left_behind` is
the only report of it**: neither the bundle nor the import mentions it, so read it before you promote. A column policy
naming an enrichment (`<enrichment>.<column>`) brings that enrichment, and the pipeline that fills it, into the export,
so the import then also needs the privileges for creating them. Handle an access-carrying bundle as access
configuration: it says which roles may read what.

## 2. Preview on the target environment

```bash
curl -s -X POST 'localhost:5000/v1/catalog/import/preview?resolution_policy=SKIP_IF_EXISTS' \
  -F file=@bundle.json
# with the bundle's access:
curl -s -X POST 'localhost:5000/v1/catalog/import/preview?resolution_policy=SKIP_IF_EXISTS&include_access=true' \
  -F file=@bundle.json
```

The preview runs the same validation pass as the import and writes nothing. Check, in this order:

- **`validation_errors` and every `problems` list are empty.** Otherwise the import answers `422`.
- **`env_specific`.** Every value that is likely to mean something else here: `transform.model`,
  `advanced.rate_rpm`, `trigger.cron`, absolute dates in filters and table `ttl`. **A model must exist on the
  target**: nothing checks `transform.model` at arm time, so a missing deployment shows up only as the runner's
  call failures.
- **`required_system_tables`.** Each must exist here as an ACTIVE system table. Its columns are not compared:
  a pipeline that reads a column missing here reports it under `arm_problems` instead.
- **`differs` on `SKIP` entries.** Import never changes an existing object's definition, columns or pipeline (only
  its access, with `include_access=true`). Compare `prev` with `next` and fix
  drift through the object's own endpoints (`PATCH /v1/tables/{name}/schema`, `PATCH /v1/pipelines/{name}`).
- **`reused_name`.** The target once had a pipeline of this name that was enabled, then deleted. The Enrichment Runner resets the
  scan cursor, group state and queued work of a recreated pipeline, so it scans from the beginning. It keeps the deleted
  pipeline's dead letters and today's spend counters under the name: a requeue of an old dead letter replays the old payload
  into the new target, and the old spend counts toward the new pipeline's daily limit until midnight. Pass
  `acknowledge_reused_names=true` (a query parameter) only when that is acceptable.
- **`armable`.** Whether `PATCH enabled=true` would pass for each pipeline once the import is done, with the bundle's
  access applied when you import it.
- **`access`, with `include_access=true`.** One entry per table and role: `CREATE`, `REPLACE` or `SKIP`, with `prev`
  (this environment's entry) beside `next`. Review every role name and row-policy value: role names are copied as they
  are, and may mean another group, or nobody, on this environment's identity provider; a literal such as a project id
  may mean nothing here (policies on `caller.user_hash` and `caller.project` travel safely). Review every `REPLACE`
  whose `prev` holds something its `next` drops, such as a leftover row policy on an enrichment: replacing it can widen
  reads. Edit the bundle, or skip the access, until each entry is right. Importing access needs `TABLE_GRANT`, and
  still `TABLE_CREATE`, even for a bundle that carries only access.

While a preview with access runs, it holds locks on the covered tables whose access it would change (`CREATE` or
`REPLACE`), system tables included, so do not edit or import access on them at the same time: such a write waits up
to `tables.lock-timeout` and then answers `409 table_busy`.

## 3. Import

```bash
curl -s -X POST 'localhost:5000/v1/catalog/import?resolution_policy=SKIP_IF_EXISTS' \
  -F file=@bundle.json
# with the bundle's access:
curl -s -X POST 'localhost:5000/v1/catalog/import?resolution_policy=SKIP_IF_EXISTS&include_access=true' \
  -F file=@bundle.json
```

`FAIL_IF_EXISTS` refuses (`409`) when any object already exists; `SKIP_IF_EXISTS` keeps those and imports the
rest. Add `include_access=true` to import the bundle's access as well (an existing entry never conflicts). Every object
is created as its own committed step; the access is written last, in one transaction, so it lands whole or not at
all. If one fails, what the import created is deleted
again (`outcome: rolled_back`, per-entry `status`). That is best effort: with `outcome: rollback_failed`, look at
the entries marked `rollback_failed`. Re-running the same bundle with `SKIP_IF_EXISTS` recreates anything
missing, but refuses a table left behind PENDING or FAILED: delete it (`DELETE /v1/tables/{name}`) first.

## 4. Grant access

Imported with `include_access=true` (steps 2 and 3), each role in the bundle has the bundle's entry on each covered
table, system tables included; roles the bundle does not name keep theirs. An import never removes an entry: remove a
role this environment should not keep with `DELETE /v1/tables/{name}/access?role={role}`.

Imported without it, tables have **no grants** of their own: only callers holding the global privileges can see or read
them, except that an imported enrichment of a source this environment already grants is readable at once by
that source's readers. Grant each source on this environment with one `PUT /v1/tables/{name}/access?role={role}` per
role, using this environment's provider role names (see [`row-level-security.md`](row-level-security.md)). An
enrichment follows its source's `get`, `read` and `sensitive`, row policies and column policies, and takes none of its
own (`409 enrichment_follows_source`); grant it only `write` or `modify`.

## 5. Arm the pipelines

Every pipeline lands **disabled**, whatever `exported_enabled` says.

1. **Enable the enrichment-producing (`enrich`) pipelines first**, then the aggregates that read their
   enrichments: an aggregate's freshness clamp needs the enrichment's pipeline running.
2. **Preview, then arm** each pipeline: `POST /v1/pipelines/{name}/preview`, then
   `PATCH /v1/pipelines/{name}` with `{"enabled": true}`, which re-runs the arm-time checks.
3. **Expect aggregates to rebuild from zero.** A new aggregate pipeline has no cursor, so its first runs
   rescan the whole input: about `ceil(filter-matching rows / 100,000)` minutes at one window per tick.

## 6. What does not travel

- **Access, unless asked for.** Without `include_access`, neither user-table nor system-table access; with it, only
  what a `PUT` accepts now, and an import never removes an entry; see step 4.
- **Data.** Tables are created empty; source rows, enrichment labels and rollup rows stay where they were.
- **Runner state.** Scan cursors, dead letters and spend counters belong to the Enrichment Runner.
- **Data-sync backfill.** Run any backfill of `dial_usage_log` separately (`POST /v1/data-sync/backfill`).
- **OTLP sinks and saved queries.** Recreate sinks on the target; saved queries are owner-scoped.
- **Instance settings.** `app-routes` and `sessions-source` belong to the instance, not the bundle. An imported
  `dial_app_route` pipeline previews `armable: false` naming `app-routes` until its pair is listed: allow each
  pair on the target with `PUT /v1/settings/app-routes` before arming (step 5), and set `sessions-source` there
  if the target serves sessions.
