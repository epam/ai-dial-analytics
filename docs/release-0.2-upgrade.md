# ADAS release-0.2 — upgrade notes

What an environment has to do to move to **release-0.2 (0.2.3)**. Pick your starting point:

- **Running 0.2.2** — read [§0.1](#01-from-022-to-023). It links to the few later sections that apply
  to you.
- **Running 0.2.1** — read [§0.2](#02-from-021-to-022), then §0.1. Both fit in one deploy window; there
  is no need to stop at 0.2.2.
- **Running release-0.1 (0.1.2)** — read §1–§7. They describe the whole path to 0.2.3, including
  everything §0 lists.

Five things make this release more than a redeploy: a **destructive migration** that drops five
columns from `dial_usage_log`, a **coordinated deploy** with the Enrichment Runner, a **rename** that
every query, dashboard and pipeline touching request/response content has to follow, the
**evaluator registry folding into pipelines**, which changes how an enrichment is authored, and
**privileges replacing the two admin roles**, which keeps an existing role mapping working but refuses
to start on a value it does not recognise.

---

## 0. Already on release-0.2

### 0.1 From 0.2.2 to 0.2.3

0.2.3 leaves the pipeline authoring API alone, and **the runner does not need redeploying**: the contract
version stays `2`, so the 0.2.2 runner keeps working and ADAS rolls out on its own (skip §2). What
changes is authorization, one ClickHouse migration, and how long a pipeline call may run.

1. **Before deploying, check every role mapping** (§4.5). The two application roles become
   *privileges*. `FULL_ADMIN` and `READ_ONLY_ADMIN` keep working as bundle names, but a value in
   `SECURITY_ROLES_MAPPING`, `API_KEY_ROLES_MAPPING` or a provider's `roles-mapping` that names neither
   a privilege nor a bundle now **stops ADAS from starting** — it used to be dropped silently. So does a
   `SECURITY_MODE` other than `none` or `oidc`.
2. **Before deploying, prepare ClickHouse for `V12`** (§1). It makes ClickHouse merge each settled day
   of `dial_usage_log_payload` into one part, dropping the duplicate copy of every row, but the first
   startup sets off a burst of large merges that need free disk. A writer granted `ALTER` as
   sub-privileges also needs `ALTER SETTINGS ON dial_usage_log_payload`, or startup fails at `V12`.
3**After deploying, watch for `422 pipeline_read_budget_exceeded`.** A materialize call now gets one
   `PIPELINE_BUDGET_TIMEOUT` for all its statements rather than one each, and an aggregate refresh is
   budgeted for the first time. A refresh refused by the budget is refused again on every tick, so its
   rollup stalls until the budget is raised (§5).

The rows marked **0.2.3** in §5 are the ones new to you. There are three new, optional logging
settings (§6), and query results can now be downloaded as CSV (§7).

### 0.2 From 0.2.1 to 0.2.2

0.2.2 has no ClickHouse migration and does not touch ingested data. What it changes is the
enrichment authoring API: **evaluators are gone**, and an enrichment's definition now lives on its
pipeline as a `transform` block. Stored pipelines are converted automatically.

1. **Deploy ADAS and the runner as a pair**, in the order in §2. The runner must be its 0.2.2 build:
   the contract version rises from `1` to `2`, and each side refuses the other's old version.
   Complete the runner guide's "Before the window" checklist first. **Make no pipeline writes while
   ADAS is rolling** (§2).
2. **Two Postgres migrations run on startup**, `V41` and `V42` (§3). ADAS also adds a
   `pipeline_generation` column to every existing enrich target. If that fails, ADAS logs a warning
   and keeps starting.
3. **Update anything that authors pipelines or evaluators** — scripts, `adas-mcp`, generated
   OpenAPI clients — to the `transform` shape (§4.3). Pipelines you already have need nothing, and
   should not be edited until the runner has drained the work it carried over (runner guide §1,
   "After the window").
4. **Backfill clients**: a concurrent-run `409` now carries `error: backfill_conflict` (was
   `conflict`), and runs can be listed (§4.2).

The rows marked **0.2.2** in §5 are the ones new to you, on top of the **0.2.3** ones from §0.1.
There is one new, optional setting (§6).
If the deploy happens in the wrong order, both services stall and then recover by themselves once
both speak contract version `2`. The runner paired with ADAS 0.2.1 (runner 0.2.0) holds its work rather than
dead-lettering it, so the dead-letter warning in §2 applies only to an upgrade from 0.1.2.

---

## 1. Before you deploy

> **Deploying deletes pre-split payload content.** ClickHouse migration `V11` drops `request_body`,
> `response_body`, `assembled_response`, `jwt_claims` and `request_tags` from `dial_usage_log`. There
> is no copy-forward step. Anything ingested before the cutover is gone and is **not recoverable from
> the database** — only by re-ingesting from blob storage (§4.2), which reaches back as far as your
> blob retention. If you need that history in ClickHouse, copy it out first.

Check before starting the new build:

- **ClickHouse 25.3 LTS or newer** (verified through 26.3). No version check is performed — a missing
  feature fails at the statement that needs it.
- The server has an **`encryption_codecs` / `aes_256_gcm_siv` key**. `V10` creates encrypted body
  columns and `CREATE TABLE` fails without it. See [`clickhouse-server.md`](clickhouse-server.md).
- The writer credential has **`ALTER DROP COLUMN`** on `dial_usage_log` and, if `ALTER` is granted as
  sub-privileges, **`ALTER SETTINGS`** on `dial_usage_log` and `dial_usage_log_payload` (`V12`). See
  [`clickhouse-server.md`](clickhouse-server.md#narrowing-alter-to-sub-privileges).
- If `CLICKHOUSE_MIGRATIONS_ENABLED=false`, apply `V10`, `V11` and `V12` by hand, in that order,
  **before** starting the new build — see
  [`heavy-columns.md`](heavy-columns.md#upgrading-an-existing-deployment).
- In `SECURITY_MODE=oidc`, **every `roles-mapping` value is a privilege or bundle name** (§4.5), and
  `SECURITY_MODE` itself is `none` or `oidc` (any letter case). Anything else fails startup.

`V11` needs no free-space headroom: `DROP COLUMN` removes column files rather than rewriting the
survivors. The `ALTER` returns before the space is back — watch `system.mutations` for the reclaim.

**`V12` does need headroom** when the payload table already holds data, as it does on 0.2.1 or 0.2.2.
It sets a force-merge interval that also applies to parts already on disk, so at the first startup every
payload day older than two days becomes a merge candidate at once — on a deployment with weeks of history, dozens of days,
each up to tens of GiB. A merge holds about twice its day's size on disk until it finishes, and several run in
parallel. Check free disk against the largest payload days before deploying, and watch `system.merges`
and free disk for the first hour. On a tight volume, stage it as described in
[`clickhouse-server.md`](clickhouse-server.md#settled-ingest-days-are-force-merged). Coming from 0.1.2
the payload table is still empty when `V12` runs, so it costs nothing then. The `V12` settings are
verified on ClickHouse 26.3 only.

## 2. Deploy order — ADAS and the Enrichment Runner ship as a pair

**Coming from 0.2.2, skip this section.** 0.2.3 keeps contract version `2`, so ADAS rolls out on its
own and the runner stays up.

**First complete the runner guide's §1 "Before the window"** (runner repo,
`docs/release-0.2-upgrade.md`): requeue the DLQ on the old runner, make sure no backfill is running,
and keep every enrich pipeline enabled. Then:

1. **Scale the runner to 0** and wait for the pod to be gone. A rolling update is not enough.
2. **Deploy ADAS.** Until the rollout finishes, make **no `POST`, `PATCH` or `DELETE` on
   `/v1/pipelines`** — from an operator, a script or `adas-mcp`. Old and new ADAS pods serve side by
   side during the roll, and a pipeline write handled by an old pod silently drops the `transform`
   block `V41` just folded in. Nothing guards against this.
3. **Deploy the matching runner build** (0.2.2). It runs its own migrations, one of which has a
   precondition; see the runner's `docs/release-0.2-upgrade.md`.
4. **Scale the runner back to 1.** Its readiness stays DOWN until the first pipeline sync succeeds, so
   a Ready pod confirms the pair matches.

ADAS now requires the header **`Adas-Contract-Version: 2`** on the four runner-only endpoints
(`scan`, `group-rows`, `materialize`, `rows`); absent or mismatched is a `409`. The compiled pipeline
feed also speaks a new vocabulary that an older runner cannot read.

**If you deploy ADAS without taking the 0.1.2 runner down first**, the old runner still *charges* its
queued DIAL calls and then has every write-back refused — `409` is non-retryable, so results are
dead-lettered on the first attempt, groups can be stamped clean, and the DLQ circuit breaker can pause
the pipeline. Recovery is manual, and requeuing into a *disabled* pipeline destroys the items. Nothing
is lost while the runner sits at 0, but Class-1 materialize positions stop advancing, so dependent
rollups clamp for a cadence after it returns — keep the window short. Full note in
[`configuration.md`](configuration.md#release-and-deployment-order).

## 3. Migrations (automatic on startup)

| Migration | Effect |
|---|---|
| ClickHouse `V10` | Creates `dial_usage_log_payload` — 90-day TTL, partitioned by day. |
| ClickHouse `V11` | **Destructive.** Drops the five columns from `dial_usage_log`. |
| Postgres `V38` | Catalogs the payload table as a system enrichment keyed on `event_id`; repoints the five column mappings. |
| Postgres `V39` | `ADD COLUMN evaluator_version.outputs`. No row rewritten. |
| Postgres `V40` | Declares the payload table's partition column so grain joins prune. |
| Postgres `V41` | Folds each enrich pipeline's evaluator into its stored definition as `transform`. Bumps no `generation`, so no runner state or Class-1 position is discarded. The old `evaluator_name` / `evaluator_version` columns stay, unused. |
| Postgres `V42` | Lets a disabled pipeline be stored incomplete (a draft); the completeness checks now apply only to enabled rows. |
| ClickHouse `V12` | Force-merges a day partition of `dial_usage_log_payload` and `dial_usage_log` once all its parts are 2 days old, which drops the duplicate every row is ingested with. Changes table settings only, but they apply to existing parts too (§1). |
| Postgres `V43` | Sets the default of `table_definition.access` to `{}`. No row rewritten. |

An environment on 0.2.1 already has ClickHouse `V10`–`V11` and Postgres `V38`–`V40`, and runs
`V41`–`V43` and ClickHouse `V12`. One on 0.2.2 runs only `V12` and `V43`. No migration that 0.1.2,
0.2.1 or 0.2.2 already applied was edited or removed, so there is no Flyway checksum hazard and no
version collision.

---

## 4. After deploy — required

### 4.1 Repoint the five renamed columns

They still belong to the `dial_usage_log` entity; only the name changed. Add the prefix and quote the
whole thing:

| Old | New |
|---|---|
| `request_body` | `"dial_usage_log_payload.request_body"` |
| `response_body` | `"dial_usage_log_payload.response_body"` |
| `assembled_response` | `"dial_usage_log_payload.assembled_response"` |
| `jwt_claims` | `"dial_usage_log_payload.jwt_claims"` |
| `request_tags` | `"dial_usage_log_payload.request_tags"` |

`request_body_bytes` and `response_body_bytes` did **not** move.

Update every ad-hoc and saved query, DSL query, Grafana panel, BI model, pipeline `filter` and pipeline
`transform` (its `inputs` and `sql` outputs). The bare names no longer resolve anywhere — there is
deliberately no alias, so a stale reference fails with an unknown-field error on its next use.

Four things to know:

- **`SELECT *` never returns them.** They are enrichment columns, and a wildcard never fans out into a
  grain join. Name them explicitly.
- **Always bound a body query with `request_time`.** The payload table is partitioned by day and
  `V40` teaches the join to prune on it; without a time predicate a point read still scans every
  partition. Filter on source columns (`request_time`, `trace_id`, `project_id`), never on a body.
- **Past 90 days the payload row is gone** while the event survives for a year. Body columns then read
  `NULL`; `jwt_claims` and `request_tags` read `{}`, because ClickHouse has no nullable JSON. So
  `request_tags IS NULL` never means "payload gone" — test a body column for `NULL` instead.
- Because `{}` is indistinguishable from "the request carried no headers", a query that counts
  requests *lacking* a header will silently misclassify everything older than 90 days.

To fix a pipeline that still names a bare column, read it with `GET /v1/pipelines/{name}`, correct its
`filter` or `transform`, and send the **whole** declaration back with `PATCH /v1/pipelines/{name}`
(§4.3).

### 4.2 Re-ingest payload history

`dial_usage_log_payload` starts empty. To recover bodies, claims and headers for past days, re-ingest
from blob storage with a key holding `DATA_SYNC_OPERATE` to trigger the run and `REGISTRY_READ` to
poll its status (the `FULL_ADMIN` bundle covers both):

```bash
curl -s -X POST localhost:5000/v1/data-sync/backfill \
  -H 'Api-Key: <full-admin-key>' -H 'Content-Type: application/json' \
  -d '{"from":"2026-06-17","to":"2026-09-14"}'
# -> 202 {"runId":"...","from":"...","to":"...","days":90}

curl -s localhost:5000/v1/data-sync/backfill/<runId> -H 'Api-Key: <full-admin-key>'
```

Dates are UTC and inclusive. One run at a time (a second trigger gets `409 backfill_conflict`).
Re-ingesting a day is safe — duplicates collapse. A single request is capped at `BACKFILL_MAX_DAYS`
(366), but the payload TTL is 90 days, so anything older than that is written and expires immediately.

Reading the outcome:

- `state` is `running`, `completed` or `failed`. `failed` means every day failed or the run stopped
  early. A `completed` run can still have `daysFailed > 0`; `failedDates` (0.2.2) names the days to
  re-run.
- `GET /v1/data-sync/backfill` (0.2.2) lists runs, optionally filtered with
  `?state=running|completed|failed`. A read-only admin key is enough.
- Run state is in memory: each instance keeps only its last 5 runs (16 before 0.2.2), a restart loses
  them, and an interrupted range must be re-triggered.

> **A backfill re-fires your enrichments.** A re-ingested row gets a fresh `_ingested_at`, so every
> `on_ingest` pipeline picks it up again by itself — convenient for `sql` pipelines, but on an LLM
> pipeline a 90-day backfill is a full-cost replay. Check the model budget before triggering one.
> Disabling a pipeline for the duration does not avoid the cost, it only defers it: the runner's scan
> cursor survives the disable, and the re-ingested rows are still ahead of it when the pipeline is
> re-enabled. The toggle also bumps the pipeline's `generation`, which drops its in-flight work.

### 4.3 Update pipeline authoring clients

Stored pipelines keep working untouched: nothing needs re-registering, re-draining or re-spending,
and stored prompts render bit-identically. What changes is what the API **accepts on write**. Old
field names are now rejected with `400` instead of being silently understood.

**Evaluators are folded into pipelines.** An `enrich` pipeline carries its own definition in a
`transform` block: `type` (`llm` or `sql`), `model`, `preset`, `params`, `request_template`, `inputs`
and `outputs`. `POST /v1/evaluators` is gone (`405`). `GET /v1/evaluators` stays as a read-only
archive of the definitions as they were before the fold. To remove an enrichment,
`DELETE /v1/pipelines/{name}`.

**To change one, send the whole declaration.** A `PATCH` that carries declaration members replaces
the stored declaration rather than merging into it: whatever it omits is cleared. On an enabled
pipeline a partial declaration is refused (`422`), and on a disabled one it may not make a complete
declaration incomplete.
Read the pipeline with `GET /v1/pipelines/{name}`, edit it, and send it back. A `PATCH` carrying only
`enabled` just flips the pipeline.

| Retired | Replacement |
|---|---|
| `POST /v1/evaluators`; pipeline `evaluator_name`, `evaluator_version` | the pipeline's own `transform` block |
| pipeline `vars` (earlier `input_bindings`); evaluator `input_vars` | `transform.inputs` — `llm` only |
| pipeline `output_bindings`; evaluator `output_vars` | `transform.outputs` — target column → prose for `llm` (optional, defaults to the column's `description`), or the expression as a bare string for `sql`. **Declaration order is load-bearing.** |
| `cadence`, `batch_scan_limit`, `batch_chunk`, `sampling` | `advanced.scan_every`, `.rows_per_scan`, `.rows_per_call`, `.sample_fraction` |
| `priority`, `freshness` | removed |

Worth knowing:

- A pipeline folded from an older evaluator may carry `transform.output_vars` or
  `transform.response_schema`. `output_vars` is refused on `POST` but accepted on `PATCH`, so a
  read-modify-write client doesn't lose it; `response_schema` is accepted on both. A new declaration
  authors `outputs`.
- Because an `llm` output's prose now defaults to the column `description`, editing a description on
  an `llm` target bumps the pipeline's `generation` — which drops the runner's in-flight work for it
  (runner guide §3).
- The provenance column `pipeline_generation` replaces `evaluator_version` on new rows.
  `evaluator_version` values that are already stamped stay readable.

Regenerate any OpenAPI-derived client. On reads, `GET /v1/pipelines` takes `?view=source` (default) or
`?view=compiled`; the runner feed needs `?kind=enrich&enabled=true&view=compiled`.

### 4.4 Eval-run cost reporting — switch to `usage_request_baggage`

Eval runs got cost-per-run by filtering `dial_usage_log.request_tags.baggage`. That column now expires
after 90 days. The baggage header alone is therefore lifted into its own enrichment kept for **1 year**,
matching the event.

Filter on `usage_request_baggage.baggage` with a "contains" match on the whole `key=value` pair, e.g.
`eval.run.id=<uuid>`. It is stored raw and unparsed, so every key a producer sends survives. The table
is sparse — a request without a baggage header simply has no row.

Three calls set it up, in order:

```jsonc
// 1. POST /v1/tables
{"name": "usage_request_baggage", "type": "enrichment", "source_table": "dial_usage_log",
 "tag_order": ["tracing", "provenance"],
 "description": "Per-event W3C baggage propagation header, lifted out of the usage log's captured request tags so it can be kept for a year."}

// 2. POST /v1/tables/usage_request_baggage/schema
{"columns": [
   {"source_name": "baggage", "name": "baggage", "type": "string", "nullable": true,
    "display_name": "Baggage", "tag": "tracing",
    "description": "The request's baggage header verbatim, read out of the captured request tags."},
   {"source_name": "request_time", "name": "request_time", "type": "timestamp", "nullable": false,
    "display_name": "Request time", "tag": "provenance",
    "description": "The parent event's request_time, copied here solely to drive this table's one-year TTL."}],
 "grain_key": "event_id", "cardinality": "zero_or_one",
 "ttl": {"column": "request_time", "interval": 1, "unit": "year"}}

// 3. POST /v1/pipelines  — a sql transform's outputs are BARE EXPRESSION STRINGS
{"name": "usage-request-baggage-live", "kind": "enrich", "target": "usage_request_baggage",
 "inputs": ["dial_usage_log"],
 "filter": "json_extract_string(\"dial_usage_log_payload.request_tags\", 'baggage') <> ''",
 "trigger": {"kind": "on_ingest"},
 "transform": {"type": "sql", "outputs": {
   "baggage": "json_extract_string(\"dial_usage_log_payload.request_tags\", 'baggage')",
   "request_time": "request_time"}},
 "advanced": {"scan_every": "60s", "rows_per_scan": 20000},
 "enabled": true}
```

Use plain **`json_extract_string`**, not `json_extract_string_ci`. The `_ci` form renders as
`toJSONString(<column>)`, reads the whole document, and is precisely what the new pipeline read budget
refuses — ADAS now returns an advisory `notes` entry when you register one. Ingest lower-cases
`request_tags` keys, so the case-sensitive form is equivalent; rows written before that transform
shipped keep their original case, and a backfill (§4.2) rewrites them through the current mapping.

Because it derives from `request_tags`, baggage only exists while the payload row does — run the
backfill **before** expecting history here, and the pipeline fills it in on its next scan.

### 4.5 Privileges replace the two admin roles (0.2.3)

This applies only in `SECURITY_MODE=oidc`. In `none` mode everything stays open.

ADAS used to authorize with two roles, `FULL_ADMIN` and `READ_ONLY_ADMIN`. It now checks sixteen
privileges named `<OBJECT>_<VERB>` — `TABLE_READ`, `REGISTRY_ADMIN`, `DATA_SYNC_OPERATE` and so on.
A `roles-mapping` value may name a privilege or one of four bundles, mixed in one list:

| Bundle | Grants |
|---|---|
| `FULL_ADMIN` | Every privilege — exactly what the old role could do. |
| `READ_ONLY_ADMIN` | `TABLE_READ`, `REGISTRY_READ`, `AUDIT_READ`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE` — the old role, less the two catalog details below. |
| `READ_ONLY_ANALYTIC` (new) | `TABLE_READ`, `SAVED_QUERY_READ`, `SAVED_QUERY_WRITE`. |
| `READ_ONLY_SENSITIVE_ANALYTIC` (new) | The same plus `TABLE_SENSITIVE`, so sensitive values can be read without admin rights. |

The list of privileges and what each one allows is in
[`configuration.md`](configuration.md#authentication).

**Before deploying**, read `SECURITY_ROLES_MAPPING`, `API_KEY_ROLES_MAPPING` and every provider's
`roles-mapping`. A value that is neither a privilege nor a bundle stops ADAS at startup. A mapping that
uses only `FULL_ADMIN` and `READ_ONLY_ADMIN` needs no edit. `API_KEY_ROLES_MAPPING` may now be left
empty when `API_KEY_ENABLED=true`.

**Who notices a difference:**

- **`FULL_ADMIN`**: nobody.
- **`READ_ONLY_ADMIN`**: queries behave as before, but two catalog reads change.
  - `GET /v1/tables` and `GET /v1/tables/{name}` no longer list sensitive columns, and `column_count`
    no longer counts them. These users saw those columns there before, though never their values.
  - `GET /v1/tables/{name}/access` answers `403`; it now needs `TABLE_GRANT`.

  To restore either, add the privilege to the role's mapping, e.g. `["READ_ONLY_ADMIN", "TABLE_GRANT"]`.
  Be careful with `TABLE_SENSITIVE`: next to `TABLE_READ` it also lets the role **read sensitive values**,
  which it never could before.
- **Tokens whose roles map to nothing** were refused almost everywhere. They are still authenticated,
  can call `GET /v1/queries/functions` and `/v1/queries/sql-functions`, and reach whatever a per-table
  grant naming one of their raw provider roles allows. A table they cannot see answers `404` (or unknown
  entity on a query) instead of `403`.
- **The Enrichment Runner**: nothing to do. `scan`, `group-rows` and `materialize` used to need only
  `write` on the target enrichment, and now also need `read` on the table the pipeline reads. A runner
  mapped to `FULL_ADMIN`, or to `READ_ONLY_ADMIN` plus per-table `write` grants, already has that: it
  needed one of those roles to read the pipeline feed at all. Only a runner given a narrower custom
  mapping must carry `REGISTRY_READ`, `read` on each source and `write` on each target, plus `sensitive`
  on a source whose sensitive columns a pipeline references — otherwise `403 pipeline_access_denied`.
- **OTLP exporters**: unchanged. Ingest still needs `write` on the sink's landing table, or `FULL_ADMIN`.

**Clients of `/v1/tables/{name}/access`:**

- There are five grant lists instead of two: `get`, `read`, `sensitive`, `write`, `modify`.
- `PUT` now **merges**. A key you leave out keeps its stored list, and `[]` revokes it. It used to
  replace the whole document, so a client that revoked by leaving a key out must now send `[]`.
- `system` tables such as `dial_usage_log` accept the read-side lists. A non-empty `write` or `modify`
  on one is still `409 table_is_system`.
- The `PUT` response adds `permissions` and `ineffective_grants`. The second flags a grant that changes
  nothing, such as one to a role that already holds the privilege globally.
- `permissions` on table responses carries five flags — `get`, `read`, `sensitive`, `write`, `modify` —
  instead of two. The two old flags keep their names.

**Sensitive columns in schema changes.** Setting or clearing a column's `sensitive` flag, or declaring
a new column sensitive, needs both `TABLE_MODIFY` and `TABLE_SENSITIVE` on the table; without them it
is `403`. A caller lacking the clearance cannot see an existing sensitive column at all, so renaming,
dropping or updating one answers `422` unknown column. `FULL_ADMIN` holds both.

To narrow a team's access, edit its mapping first: effective access is the union of the global
mapping and the per-table grants, so a grant alone never takes anything away. The details are in
[`configuration.md`](configuration.md#authentication).

---

## 5. Breaking changes at a glance

The last column names the patch that introduced a change. A blank means it was already in 0.2.1.

| Change | Symptom | Action | New in |
|---|---|---|---|
| Five payload columns renamed | unknown-field error | Repoint to the qualified name (§4.1) | |
| Runner data-plane needs `Adas-Contract-Version: 2` | `409` | Redeploy the runner in the same window (§2) | 0.2.2 (was `1`) |
| `POST /v1/evaluators` removed | `405` | Declare a `transform` on the pipeline (§4.3) | 0.2.2 |
| `evaluator_name`, `evaluator_version`, `vars` on a pipeline | `400` | Move into `transform` (§4.3) | 0.2.2 |
| Other retired pipeline/evaluator write fields | `400` | Rename (§4.3) | |
| A `sql` transform carrying `model`, `preset`, `params` or `request_template` | `422` | Drop them — a `sql` transform takes only `outputs` | 0.2.2 |
| `PATCH` that would leave a disabled pipeline's complete declaration incomplete | `422 pipeline_validation_failed`, nothing changed | Send the whole declaration | 0.2.2 |
| `rows` / `materialize` on a disabled pipeline | `409 pipeline_conflict` (was accepted) | Enable the pipeline first | 0.2.2 |
| Concurrent backfill trigger | `409` code is `backfill_conflict` (was `conflict`) | Update callers keying on the code | 0.2.2 |
| `view=compiled` without `kind=enrich` | `400` (was silently ignored) | Add `kind=enrich` | |
| Pipeline-internal reads are now budgeted | `422 pipeline_read_budget_exceeded` | Tune `PIPELINE_BUDGET_MAX_MEMORY` | |
| ClickHouse resource exhaustion | `503` (was `400 bad_request`) | Update callers keying on `400` | |
| ClickHouse out of memory under other load | `503` (was `422 *_budget_exceeded`) | Retry it; only a caller's **own** ceiling is still `422` | |
| `aggregate` pipeline registered with `enabled: true` | `422` | Register disabled, `/preview`, then `PATCH` | |
| `ordering_key` / `partition_by` naming a nullable column | `422` (was a raw ClickHouse error) | Declare the column `nullable: false` | |
| `ORDER BY` on an alias shadowing a real column name | `400` (was silently sorted by the alias) | Rename the alias | |
| A `roles-mapping` value that is neither a privilege nor a bundle, or a `SECURITY_MODE` other than `none`/`oidc` | ADAS fails to start (the value used to be ignored) | Fix the value (§4.5) | 0.2.3 |
| `READ_ONLY_ADMIN` reading `GET /v1/tables[/{name}]` | Sensitive columns missing, lower `column_count` | Add `TABLE_SENSITIVE` to the mapping, only if the role may read sensitive values (§4.5) | 0.2.3 |
| `GET /v1/tables/{name}/access` without `TABLE_GRANT` | `403` | Add `TABLE_GRANT` to the mapping | 0.2.3 |
| `PUT /v1/tables/{name}/access` leaving a key out | The stored list is kept (was cleared) | Send `[]` to revoke | 0.2.3 |
| Changing a `sensitive` flag, or a sensitive column, without `TABLE_SENSITIVE` | `403`, or `422` unknown column | Hold `TABLE_MODIFY` and `TABLE_SENSITIVE` on the table | 0.2.3 |
| A table the caller cannot see | `404` or unknown entity (was `403`) | Update callers keying on `403` | 0.2.3 |
| A materialize call whose statements together exceed `PIPELINE_BUDGET_TIMEOUT` | `422 pipeline_read_budget_exceeded` (each statement used to get its own timeout) | Raise `PIPELINE_BUDGET_TIMEOUT`, keeping it below the runner's HTTP read timeout | 0.2.3 |
| An aggregate refresh over `PIPELINE_BUDGET_*` | Refused on every tick, so the rollup stalls (refreshes were unbudgeted) | Raise the budget, or lower `AGGREGATE_WINDOW_LIMIT` | 0.2.3 |
| SQL text in DEBUG logs | No longer printed: the SQL, HTTP-body and JDBC-bind loggers are pinned at `INFO` | None — by design, they print literals and bodies | 0.2.3 |

## 6. New configuration

All eight are new, all defaulted, none required. Details in [`configuration.md`](configuration.md).

| Variable | Default |
|---|---|
| `SYNC_PAYLOAD_TABLE` | `dial_usage_log_payload` |
| `SYNC_PAYLOAD_MAPPING_FILE` | `classpath:mapping/dial-usage-log-payload.yaml` |
| `PIPELINE_BUDGET_TIMEOUT` | `120s` — keep below the runner's HTTP read timeout |
| `PIPELINE_BUDGET_MAX_MEMORY` | `2GB` — raise if your heaviest legitimate pipeline read is larger |
| `ENRICHMENT_PROVENANCE_RECONCILE_ENABLED` | `true` — adds `pipeline_generation` to existing enrich targets at startup (new in 0.2.2) |
| `LOG_CONFIG_PATH` | `/app/log-config/logging.levels.json` — a file of log levels re-read at runtime; a missing file changes nothing (new in 0.2.3) |
| `LOG_CONFIG_INTERVAL` | `10s` — how often that file is re-read (new in 0.2.3) |
| `EXECUTION_TRACE_ENABLED` | `true` — call tracing, active only while a class logs at `TRACE` (new in 0.2.3) |

To use the level file in Kubernetes, mount it from a ConfigMap **as a directory** at `/app/log-config`;
a `subPath` mount never sees updates. The file format is in
[`configuration.md`](configuration.md#logging). The level-file poll shares the `SCHEDULING_POOL_SIZE`
pool with the two ingestion jobs, so keep that at 3 or more (the default is 4).

The `roles-mapping` settings keep their names but now take privilege and bundle names (§4.5).

No JDK, Postgres or container changes. The only new dependency is the AWS SDK STS module, which
backs S3 workload identity (§7). Do not bump `clickhouse-java` off `0.9.8` — it pins `async_insert`,
which a 26.x server would otherwise default on. 0.2.3 moves Jackson to 2.21.6 / 3.1.6 for
CVE-2026-68497.

## 7. New capability, no action required

- **Query surface**: `array_join`, `concat`, `to_string`, `array_to_string`, `array_slice`; `||` and
  `CAST(… AS VARCHAR)` now lower onto them. The top-N idiom
  (`… GROUP BY x ORDER BY count(*) DESC LIMIT 5`) no longer 400s. See
  [`sql-reference.md`](sql-reference.md).
- **SQL, new in 0.2.2**: `UNION` / `INTERSECT` / `EXCEPT` with ClickHouse semantics; array lambdas
  (`arrayExists`, `arrayAll`, `arrayCount`, `arrayFilter`, `arrayMap`) and flattening aggregates such
  as `groupUniqArrayArray`; clearer errors when a nested clause fails to parse. The full function list
  is served at `GET /v1/queries/sql-functions`.
- **Draft pipelines (0.2.2)**: a disabled pipeline can be saved with an incomplete declaration and is
  fully validated only when it is enabled or previewed.
- **Blob sources**: an `aws-s3` source with a blank `BLOB_IDENTITY` / `BLOB_CREDENTIAL` pair now
  authenticates with EKS workload identity (IRSA), and a blank `SYNC_DATE_PATH_PATTERN` supports
  producers that write no date directory. Existing configurations behave as before.
- **Pipeline read efficiency**: partition-aligned grain joins, per-heavy-column projection splitting,
  and pruned JSON extraction. Together these let declarations that were refused at the memory ceiling
  run — including any enrichment binding two heavy columns.
- **CSV downloads (0.2.3)**: `POST /v1/queries/execute-csv` and `POST /v1/queries/execute-sql-csv`
  take the same body as `execute` and `execute-sql` and return the same result as an RFC 4180
  attachment. The limits are the same too (1000 rows at most), so this is a download, not a bulk export.
  A `totalCount` travels in the `X-Total-Count` header. See [`sql-reference.md`](sql-reference.md).
- **Narrower access (0.2.3)**: per-table `get`, `read` and `sensitive` grants, including on `system`
  tables, and the `READ_ONLY_ANALYTIC` and `READ_ONLY_SENSITIVE_ANALYTIC` bundles (§4.5). A runner can
  now operate a sensitive-reading pipeline on per-table grants alone.
- **Logging (0.2.3)**: log levels changeable at runtime through the level file (§6), time-boxed with
  `validTill`; one access line per `/v1/pipelines/**` request, so expect more INFO lines from runner
  traffic; one outcome line per scan and materialize call; and a ClickHouse `query_id` of the form
  `adas-{pipeline}-{stage}-{uuid}` on every pipeline statement, which ties a log line to its
  `system.query_log` row.
- **Disk (0.2.3)**: once `V12`'s first merges finish, a settled payload day holds one copy of each row
  instead of about two.
- **Metrics**: `enrichment.runner.contact.staleness.seconds` and
  `enrichment.runner.contract.refusals`; in 0.2.2, `adas.enrichment.provenance.reconciled` and
  `adas.enrichment.provenance.reconcile_failed` for the startup provenance-column step; in 0.2.3,
  `enrichment.runner.attempt.staleness.seconds`, which also moves on runner calls that were refused.
  Additive; no renames.
- **Grafana**: six dashboards rebuilt on `POST /v1/queries/execute-sql`, shipped with the transport
  dashboard at `grafana/dashboards/`. Installing them needs the `yesoreyeram-infinity-datasource`
  plugin and a datasource with uid `adas-rest-sql`; see
  [grafana-dashboards.md](grafana-dashboards.md).
