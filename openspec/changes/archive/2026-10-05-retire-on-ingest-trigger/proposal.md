## Why

`trigger.kind: on_ingest` promises event-driven delivery it does not have. It is a fixed-delay timer
(`advanced.scan_every`, runner default 30s, 5m for `sql`) over the same cursor-incremental row scan a
`schedule` pipeline runs. Users read the name as "fires when a row lands" and file the ~10 min ingest
lag (the newest `dial_usage_log` row is typically around ten minutes behind wall clock) as a pipeline
fault. The two row-grain kinds differ only in their clock, and `advanced.scan_every` exists only to be
the second clock. It is also unvalidated: ADAS stores any string, `schedule` pipelines silently ignore
it, and a malformed value surfaces only as a runner warning. One row-grain trigger with one clock
removes the misleading name, the dead knob and the second vocabulary.

## What Changes

- **BREAKING** `trigger.kind: on_ingest` is retired. An enrich pipeline declares `schedule` (row grain)
  or `group` (group grain). Writing `on_ingest` is refused with HTTP 422 naming `schedule` as the
  replacement. Stored pipelines are migrated, so no read ever returns it.
- **BREAKING** `advanced.scan_every` is retired from the request surface and refused at the binding
  with HTTP 400 naming the field, like every other retired member. The `advanced` block keeps
  `rows_per_scan`, `rows_per_call`, `rate_rpm` and `sample_fraction`.
- An enrich `schedule` pipeline that declares no `trigger.cron` is stored with a **default cron:
  every minute, at a second derived from the pipeline name** (`S * * * * *`, `S` a stable hash of the
  name mod 60). The resolved cron is stored and echoed, so the declaration is explicit after the first
  write and pipelines don't all fire on second 0. Aggregate pipelines are unchanged: their cron
  stays required.
- A Flyway migration rewrites every stored `ON_INGEST` row to `SCHEDULE` with a cron derived from its
  stored `scan_every` (rounded *up* to the nearest cron-expressible cadence). It also removes the
  stored `scan_every` (`cadence`) key from every enrich spec, group pipelines included. It
  deliberately **does not bump `generation`**, because a bump discards a Class-1 pipeline's
  materialized position. The trigger-kind CHECK constraint narrows to `('SCHEDULE', 'GROUP')`.
- **BREAKING (runner contract)** `RunnerContract.VERSION` goes from 2 to 3. The compiled projection
  no longer carries `on_ingest` or `advanced.scan_every`. Paired with a runner change (separate
  OpenSpec change in `analytics-enrichment-runner`) that drops the fixed-delay branch, gives the group
  lane's mark scan its own `GROUP_SCAN_CADENCE` (default `1m`), and removes `SCAN_DEFAULT_CADENCE` and
  `CLASS1_DEFAULT_CADENCE`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enrichment-registry`: the trigger-kind closed set becomes `{schedule, group}`, and `on_ingest` gets a
  dedicated refusal. `advanced` loses `scan_every`, which joins the retired-member list. New default-cron
  rule for an enrich `schedule` trigger. Scenarios that use `on_ingest` as the example row-grain kind are
  restated with `schedule`. Requirements touched: "Static governance gate on arming and execution", "ENRICH
  pipeline declaration binding a transform to a target enrichment", "An ENRICH pipeline's read source is
  declarable", "A transform's template placeholders are validated against the pipeline's trigger grain",
  "Dry-run preview of an enrich declaration".
- `class1-enrichment`: "Class-1 expression governance" rule 5 (row-grain trigger only) names
  `schedule` alone.
- `enrichment-runtime`: "Scan projection and version isolation" scenario restated without `on_ingest`.
- `data-sync`: "Body columns are captured only by the payload table's mapping" names an enrichment
  scan rather than an `on_ingest` scan.
- `data-sync-backfill`: "Operator-triggered date-range backfill" names row-grain enrichment scans rather than
  on-ingest enrichments as what reacts to re-ingested rows.
- `pipeline-registry`: "A declaration field belonging to another kind is rejected" uses
  `advanced.rows_per_scan` as its nested cross-kind example, and the retired `advanced.scan_every` is a binding
  400 on either kind.

## Impact

- **API**: `POST`/`PATCH /v1/pipelines` (enrich): `trigger.kind: on_ingest` is a 422 and
  `advanced.scan_every` is a 400. Omitting `trigger.cron` on an enrich `schedule` trigger is now valid
  and stored resolved. `GET /v1/pipelines` (both views): `on_ingest` and `advanced.scan_every` no
  longer appear. OpenAPI descriptions on `TriggerRequest`, `PipelineRequests.trigger` and
  `AdvancedRequest` are updated.
- **Code**: `TriggerKind` (drops `ON_INGEST`), `PipelineMapper` (trigger parse, advanced mapping),
  `PipelineService` (kind/cron resolution, constraint messages), `EnrichSpec` (drops `cadence`),
  `EnrichSpecValidator`, `RunnerContract` (+ `RunnerContractShapeTest` golden list), the compiled
  projection DTOs, and `RunSink` (comment).
- **Database**: new `V44__retire_on_ingest_trigger.sql`, which updates `pipeline.trigger_kind`,
  `trigger_cron` and `spec`, and replaces `pipeline_trigger_kind_check`. No audit revision is written
  for the migrated rows (see design).
- **Config**: none in ADAS. The runner drops `SCAN_DEFAULT_CADENCE` and `CLASS1_DEFAULT_CADENCE` and adds
  `GROUP_SCAN_CADENCE` (runner `docs/configuration.md`).
- **Docs**: the release-0.3 upgrade doc (retired members, migration, deploy order), deferred until that doc
  exists; `docs/release-0.2-upgrade.md` is not edited. `docs/column-mapping.md` (on_ingest mention).
- **Cross-repo**: `analytics-enrichment-runner` (paired change, same contract version);
  `adas-mcp` (tool docs + OpenAPI snapshot); admin UI (drop the *On ingest* option and default
  *Schedule* to "Every minute").
- **Existing data**: enabled `on_ingest` `sql` pipelines (e.g. `scan_every: 60s`) become `schedule` with
  `S * * * * *`. A `group` pipeline such as `session-insights-live` (`scan_every: 5m`) loses its stored cadence and follows
  `GROUP_SCAN_CADENCE`.
