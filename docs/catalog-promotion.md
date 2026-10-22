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
under `skipped`. Grants never travel: each environment sets its own (step 4).

## 2. Preview on the target environment

```bash
curl -s -X POST 'localhost:5000/v1/catalog/import/preview?resolution_policy=SKIP_IF_EXISTS' \
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
- **`differs` on `SKIP` entries.** Import never changes an existing object. Compare `prev` with `next` and fix
  drift through the object's own endpoints (`PATCH /v1/tables/{name}/schema`, `PATCH /v1/pipelines/{name}`).
- **`reused_name`.** The target once had a pipeline of this name that was enabled, then deleted. The Enrichment Runner resets the
  scan cursor, group state and queued work of a recreated pipeline, so it scans from the beginning. It keeps the deleted
  pipeline's dead letters and today's spend counters under the name: a requeue of an old dead letter replays the old payload
  into the new target, and the old spend counts toward the new pipeline's daily limit until midnight. Pass
  `acknowledge_reused_names=true` (a query parameter) only when that is acceptable.
- **`armable`.** Whether `PATCH enabled=true` would pass for each pipeline once the import is done.

## 3. Import

```bash
curl -s -X POST 'localhost:5000/v1/catalog/import?resolution_policy=SKIP_IF_EXISTS' \
  -F file=@bundle.json
```

`FAIL_IF_EXISTS` refuses (`409`) when any object already exists; `SKIP_IF_EXISTS` keeps those and imports the
rest. Every object is created as its own committed step. If one fails, what the import created is deleted
again (`outcome: rolled_back`, per-entry `status`). That is best effort: with `outcome: rollback_failed`, look at
the entries marked `rollback_failed`. Re-running the same bundle with `SKIP_IF_EXISTS` recreates anything
missing, but refuses a table left behind PENDING or FAILED: delete it (`DELETE /v1/tables/{name}`) first.

## 4. Grant access

Imported tables have **no grants**: only callers holding the global privileges can see or read them. Grant
each table on this environment with `PUT /v1/tables/{name}/access`, using this environment's provider role
names. Grant a source table before its enrichments, so a `sensitive` grant on an enrichment finds the matching
grant on its source.

## 5. Arm the pipelines

Every pipeline lands **disabled**, whatever `exported_enabled` says.

1. **Enable the enrichment-producing (`enrich`) pipelines first**, then the aggregates that read their
   enrichments: an aggregate's freshness clamp needs the enrichment's pipeline running.
2. **Preview, then arm** each pipeline: `POST /v1/pipelines/{name}/preview`, then
   `PATCH /v1/pipelines/{name}` with `{"enabled": true}`, which re-runs the arm-time checks.
3. **Expect aggregates to rebuild from zero.** A new aggregate pipeline has no cursor, so its first runs
   rescan the whole input: about `ceil(filter-matching rows / 100,000)` minutes at one window per tick.

## 6. What does not travel

- **Grants.** Neither user-table nor system-table grants; see step 4.
- **Data.** Tables are created empty; source rows, enrichment labels and rollup rows stay where they were.
- **Runner state.** Scan cursors, dead letters and spend counters belong to the Enrichment Runner.
- **Data-sync backfill.** Run any backfill of `dial_usage_log` separately (`POST /v1/data-sync/backfill`).
- **OTLP sinks and saved queries.** Recreate sinks on the target; saved queries are owner-scoped.
