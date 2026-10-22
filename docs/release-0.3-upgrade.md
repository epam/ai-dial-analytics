# ADAS release-0.3 — upgrade notes

What an environment has to do to move from **release-0.2 (0.2.3)** to **release-0.3**. An environment
still on an earlier build first follows [`release-0.2-upgrade.md`](release-0.2-upgrade.md) to 0.2.3. Both
upgrades may share one deploy window, but each guide's pre-deploy checks apply.

Four things make this release more than a redeploy:

- a **coordinated deploy** with the Enrichment Runner, because the contract version rises from `2` to `3`;
- the **`on_ingest` trigger and `advanced.scan_every` are retired**, which changes how a row-grain
  enrichment is authored;
- an **ingest ledger** that changes what a backfill re-ingests by default;
- **row writes that used to be coerced silently are now refused**.

Everything else is additive: `FINAL` in queries, row-level policies, catalog export/import, and two
pipeline read-cost fixes that need no action.

---

## 1. Before you deploy

Check before starting the new build:

- **ClickHouse 25.3 LTS or newer**, now verified through **26.8 LTS** (0.2.3 was verified through
  26.3). The `V12` force-merge settings and the `ALTER SETTINGS` grant scope are re-verified on 26.8.
  Moving the server from 26.3 to 26.8 needs no ADAS change. Read the rollback note in
  [`clickhouse-server.md`](clickhouse-server.md#268-adds-a-softer-door-packed-skip-indexes) before you
  do: parts written by 26.8 pack their skip indexes, and 26.3 ignores those indexes until the part is
  re-merged.
- **Grants for the new ledger table.** `V13` creates `_dial_internal_ingested_blob` in the ingest
  database, and every ingest path reads and writes it. A writer narrowed per table also needs `SELECT`
  and `INSERT` on it, or startup schema validation fails. See
  [`clickhouse-server.md`](clickhouse-server.md#writer-credential).
- **`ALTER SETTINGS ON rate_analytics`** if `ALTER` is granted as sub-privileges (`V14`). Without it,
  startup fails at `V14`. See
  [`clickhouse-server.md`](clickhouse-server.md#narrowing-alter-to-sub-privileges).
- If `CLICKHOUSE_MIGRATIONS_ENABLED=false`, apply ClickHouse `V13` and `V14` by hand, in that order,
  **before** starting the new build.
- **Inventory the `on_ingest` pipelines**:
  `GET /v1/pipelines?kind=enrich` (`REGISTRY_READ`). `V44` turns each into a `schedule` pipeline whose
  cron is derived from its `advanced.scan_every`, **rounded up** to a cadence a cron expresses evenly
  (§4.1). A `30s` pipeline becomes once a minute. If you need it faster, re-declare its cron after the
  upgrade.

`V14` needs no free-space headroom: `rate_analytics` days hold a few dozen rows. This is unlike `V12` in
0.2.3.

## 2. Deploy order — ADAS and the Enrichment Runner ship as a pair

The runner must be its 0.3 build. ADAS now requires **`Adas-Contract-Version: 3`** on the four runner-only
endpoints (`scan`, `group-rows`, `materialize`, `rows`), and each side refuses the other's old version. The
compiled pipeline feed also no longer carries `on_ingest` or `advanced.scan_every`.

Follow the usual pair order ([`configuration.md`](configuration.md#release-and-deployment-order)):

1. **Scale the runner to 0** and wait for the pod to be gone.
2. **Deploy ADAS.** Until the rollout finishes, make **no `POST` or `PATCH` on `/v1/pipelines`**. An old
   pod still accepts `trigger.kind: on_ingest`, and `V44` has already narrowed the database constraint
   to `SCHEDULE` and `GROUP`, so that write fails with a server error.
3. **Deploy the matching runner build** (0.3). Its configuration changes too: `SCAN_DEFAULT_CADENCE`
   and `CLASS1_DEFAULT_CADENCE` are removed, and `GROUP_SCAN_CADENCE` (default `1m`) is new. Follow the
   runner repo's `docs/release-0.3-upgrade.md` for its side of the window.
4. **Scale the runner back to 1.** Its readiness stays DOWN until the first pipeline sync succeeds, so a
   Ready pod confirms the pair matches. On that first poll it reschedules every pipeline from its
   migrated cron, so no operator step is needed.

If the order goes wrong, both services stall and then recover by themselves once both speak contract
version `3`. The runner holds work that comes due during the skew and does not dead-letter it.

## 3. Migrations (automatic on startup)

| Migration | Effect |
|---|---|
| ClickHouse `V13` | Creates the ingest ledger `_dial_internal_ingested_blob`: one row per fully ingested blob, 13-month TTL (§4.2). |
| ClickHouse `V14` | Force-merges settled day partitions of `rate_analytics`, as `V12` does for the usage-log tables. Changes table settings only. |
| Postgres `V44` | Rewrites every `ON_INGEST` pipeline to `SCHEDULE` with a derived cron, gives every enrich `schedule` draft the default cron, removes `scan_every` from every enrich spec (group pipelines included), and narrows `pipeline_trigger_kind_check` to `SCHEDULE`, `GROUP`. Bumps no `generation`, so no Class-1 position or runner state is discarded. |
| Postgres `V45` | Adds `table_definition.row_policies` (default `{}`) and its `_aud` mirror. |
| Postgres `V46` | Adds a nullable, indexed `import_id` to `revinfo` and `audit_activity`. |

No migration that 0.2.3 already applied was edited or removed.

---

## 4. After deploy — required

### 4.1 `on_ingest` and `advanced.scan_every` are retired

`on_ingest` was a fixed-delay timer over the same scan a `schedule` pipeline runs. It never fired when a
row landed. A row-grain enrich pipeline now declares `schedule`. A group-grain one still declares `group`.

| Retired | Refusal | Replacement |
|---|---|---|
| `trigger.kind: on_ingest` | `422`, naming `schedule` | `trigger.kind: schedule` |
| `advanced.scan_every` | `400`, naming the field | `trigger.cron` on a `schedule` pipeline. A `group` pipeline's mark scan follows the runner's `GROUP_SCAN_CADENCE`. |

- **`trigger.cron` is now optional on an enrich `schedule` pipeline.** When it is omitted, ADAS stores
  and echoes a default: every minute, at a second derived from the pipeline name (`S * * * * *`), so
  pipelines do not all fire on second 0. Aggregate pipelines still require a cron.
- **Migrated pipelines** read back as `schedule` with a 6-field cron. Each one's ingest lag (~10 min)
  dwarfs the rounding. For sub-minute cadence, declare e.g. `*/30 * * * * *`; the runner polls no faster
  than every 10 s.
- **History keeps its old vocabulary in storage only.** Audit revisions written as `ON_INGEST` read back as
  `schedule`. The migration writes no audit revision; the next real write records the new trigger.

Update everything that authors pipelines: scripts, `adas-mcp`, the admin UI (drop *On ingest*), and
regenerated OpenAPI clients. The `usage-request-baggage-live` example in
[`release-0.2-upgrade.md`](release-0.2-upgrade.md) §4.4 uses both retired members. On 0.3, send
`"trigger": {"kind": "schedule"}` and drop `scan_every` from `advanced`.

### 4.2 The ingest ledger changes what gets re-ingested

Every blob whose rows have all been written is now recorded in `_dial_internal_ingested_blob`, and the
daily reconciliation run, the poller and the backfill skip what it records. A blob rewritten under the
same key has a new last-modified time, so it is ingested again. ClickHouse write volume drops by roughly
the reconciliation share. Duplicates still collapse if one is written anyway.

What changes for operators:

- **A backfill skips blobs already ingested by default.** To re-ingest them — repairing lost rows, or
  applying a changed mapping to history — add `"includeIngested": true` to
  `POST /v1/data-sync/backfill`. A day skipped entirely reports zero files processed.
- **A mapping or target change applies to new data only.** The daily run used to re-ingest yesterday
  with the current mapping. It no longer does, so history needs an `includeIngested` backfill.
- **The ledger starts empty.** The first reconciliation after the deploy re-ingests one day, once. Days
  ingested before the deploy are not in the ledger, so a backfill re-ingests them either way.
- A ledger read or write that fails fails the cycle or day, exactly as a failed insert does.

> **A backfill still re-fires your row-grain enrichments.** A re-ingested row gets a fresh `_ingested_at`,
> so every `schedule` pipeline scans it again. On an LLM pipeline an `includeIngested` backfill is a
> full-cost replay. Check the model budget first.

The `_dial_internal_` table-name prefix is now reserved: no table API may create such a table.

### 4.3 Row writes refuse values ClickHouse would silently change

`POST /v1/tables/{name}/rows` and the pipeline write-back (`rows`) now refuse, with
`422 validation_error` naming the row, column and value, what ClickHouse used to accept and store
differently:

- an integer outside its column's range (`3000000000` in an `integer` column wrapped to a negative number),
  a fraction or exponent for an integer column (`4.0` included), and a `decimal` with more than 26 integer
  digits;
- a boolean, empty string or other non-number for a numeric column (`true` stored `1`, `""` stored `0`);
- a `date` that is not a real `YYYY-MM-DD` day between `1970-01-01` and `2149-06-06` (`2026-02-30` stored
  `2026-03-02`), and a bare number for a `date`;
- a `timestamp` before 1900 or after 2299, and a zone-less or numeric element of a `timestamp` array;
- an `enum` value that is not one of the declared strings (a number used to map to an internal id);
- a null array element or map value (stored as the type's default).

A rejected value writes nothing from its batch. If a client relied on any of these coercions, fix the
values it sends. The OTel sink reads such values as absent instead: null, `0`, or the receive time for a
pre-1900 timestamp. The export does not fail.

---

## 5. New in 0.3 — no action needed

**`FINAL` in queries.** The SQL surfaces accept ClickHouse's `FINAL` table modifier, and the JSON DSL takes
an optional top-level `final`. `GET /v1/queries/entities` reports `supports_final` per entity.
`FORCE_FINAL_QUERIES` defaults to `true`: every user-facing read stays final whatever it says, so
**results do not change**. Two things apply even with the flag on:

- `FINAL` or `"final": true` on an `append` source with no enrichment is `400 illegal_final`, as
  ClickHouse refuses it on a plain `MergeTree`.
- An enrichment registered over a hand-written plain `MergeTree` now fails a final read that joins it.
  The seeded enrichments are all version-collapsing.

Turning the flag off makes SQL without `FINAL` read raw parts. The shipped Grafana dashboards and doc
examples are not yet migrated, so leave it on.

**Row-level policies.** `GET` / `PUT` / `DELETE /v1/tables/{name}/row-policies[/{role}]` (`TABLE_GRANT`)
confine a provider role to a slice of a table. ClickHouse enforces them through `additional_table_filters`.
Nothing is confined until a policy is stored. Once one is, a confined caller gets
`403 row_policy_confined` on pipeline arming, preview, refresh and the data plane over that table. A
column a policy references cannot be dropped or renamed (`409 column_in_row_policy`). Table
`permissions` objects gain `rows_restricted`. Policies do not reach derived tables, and a rollback stops
enforcing them silently. Read [`row-level-security.md`](row-level-security.md) before storing the first one.

**Catalog export / import.** `POST /v1/catalog/export`, `/import` and their `/preview` twins promote
tables and pipelines (never grants) between environments. Import needs `TABLE_CREATE`, plus
`TABLE_MODIFY`, `REGISTRY_ADMIN` or `TABLE_SENSITIVE` for what the bundle creates. Imported pipelines
land disabled. Runbook: [`catalog-promotion.md`](catalog-promotion.md). Every activity an import writes
carries an `importId`, and `POST /v1/activities` can filter on it.

**Cheaper pipeline reads.**

- An aggregate refresh whose grain reads one enrichment now joins only the rows its dirty keys reach. A
  `sessions_rollup`-style refresh uses several times less memory. Before this, such a refresh hit the 0.2.3
  pipeline budget once the enrichment grew to millions of rows.
- Cursor-bounded reads on a source with a `partition_column` now prune to the partitions their window
  can reach. A Class-1 probe reads orders of magnitude fewer rows.

Neither fix changes a result, so no rollup needs rebuilding.

**`now()` contract wording.** One value per call site per statement, as in ClickHouse. Rendered SQL is
unchanged. Only the `now` description in `GET /v1/queries/functions` changed.

## 6. Breaking changes at a glance

| Change | Symptom | Action |
|---|---|---|
| Runner data-plane needs `Adas-Contract-Version: 3` | `409` | Redeploy the runner in the same window (§2) |
| `trigger.kind: on_ingest` | `422` | Declare `schedule` (§4.1) |
| `advanced.scan_every` | `400` | Use `trigger.cron`, or the runner's `GROUP_SCAN_CADENCE` for `group` (§4.1) |
| `on_ingest` pipeline faster than once a minute | Migrated cadence rounded up | Re-declare the cron, e.g. `*/30 * * * * *` |
| Backfill over days already ingested on 0.3 | Nothing re-ingested, zero files processed | Add `"includeIngested": true` (§4.2) |
| Mapping or target change | Applies to new data only (the daily run re-ingested yesterday) | Backfill with `includeIngested` (§4.2) |
| Row write with an out-of-range, malformed or coerced value | `422 validation_error` (was stored changed) | Send valid values (§4.3) |
| `FINAL` on an `append` source with no enrichment | `400 illegal_final` | Drop `FINAL` (§5) |
| Final read joining an enrichment on a plain `MergeTree` | ClickHouse `ILLEGAL_FINAL` (was a silent no-op) | Make the enrichment version-collapsing |
| A table named `_dial_internal_*` | Refused | Pick another name |
| `POST /v1/pipelines/{name}/preview` reshaped: `statement` is the sample's `SELECT` (the refresh `INSERT` is gone); `sample_statement`, `group_by`, `measures` and `enrich` removed; `columns`, `total_rows` and coded `notes` added | Old members absent; `cursor` in the body is `400 bad_request`; an over-budget `enrich` preview is `422 pipeline_preview_budget_exceeded` (was `pipeline_read_budget_exceeded`) | Update preview clients (adas-mcp `adas_preview_pipeline`) |

## 7. New configuration

All three are new, all defaulted, none required. Details in [`configuration.md`](configuration.md).

| Variable | Default |
|---|---|
| `FORCE_FINAL_QUERIES` | `true` — every user-facing read stays final; `false` honours the query's `FINAL` |
| `CATALOG_IMPORT_MAX_FILE_SIZE` | `20MB` |
| `CATALOG_IMPORT_MAX_REQUEST_SIZE` | `25MB` |

The runner's configuration changes are in the runner repo's `docs/release-0.3-upgrade.md` (§2).
