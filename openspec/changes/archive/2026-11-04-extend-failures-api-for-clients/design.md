## Context

The failure ledger is an append-only ClickHouse table. It is aggregated per `(kind, subject)` and derived into
`pending` or `parked` in `IngestFailureService`. The list endpoint already derives every outstanding subject and then
cuts the page. Backfill runs are in-memory only and have a UUID `run_id`.

## Goals / Non-Goals

**Goals:** answer the five client questions in the proposal with additive API changes.

**Non-Goals:**
- Paging past 1000 entries with an offset or cursor. `total` says how many there are, and filters narrow the list.
- Refusing a requeue while the sweep is off. The requeue stays valid and is attempted once the sweep is on.
- Persisting backfill runs.

## Decisions

- **Totals are computed from the same read as the page.** The service already holds every outstanding subject in
  memory, so `total` and `by_state` cost one pass and no extra query. `by_state` disregards the `state` filter. A
  status tab bar can then show both counts from the list it is already showing. A `by_state` that honoured the filter
  would always report zero for the other state.
- **No separate preview endpoint for the count.** A requeue selects with the same `IngestFailureFilter` over the same
  outstanding set as the list. The list's `total` for the same filters is therefore the count a retry would requeue,
  and `retry_sweep` is on the list too. `subjects` and `targets` are not list parameters, but a client that names
  subjects already knows how many it named.
- **`targets` is a new member, not a change to `subjects`.** Making `subjects` accept objects would break the existing
  string form. Its entries are strict `{kind, subject}` objects. List entries carry more members, so a client cannot
  post them back unchanged and has to pick out the two fields.
- **Attribution is a column, not a path filter.** Filtering on the latest failure's `path=backfill` would still mix
  runs, and would lose the subject once the sweep retried and failed it (`path=retry`). The run id is written on
  `failed` events only, and a subject's runs are those after its latest `resolved` event. A requeue therefore keeps
  the attribution, so "requeue the parked failures of run X" still selects them after an earlier requeue. A
  resolution ends it.
- **The column is part of `V17`**, which creates the failure ledger, rather than a separate `ALTER` migration: the
  ledger ships in the same release as this change.
- **The run id travels as an `IngestSource(path, backfillRunId)`**, which `DataSyncService.runForDate` takes in place
  of the `IngestPath`. An overload taking either type would make the `any()`-matched mocks ambiguous. The failure
  service keeps its `IngestPath` methods, which delegate with no run.
- **The sweep status is computed from the configuration.** `next_sweep_at` is the next fire time of
  `sync.retry.cron` in the server's default zone, which is how `@Scheduled` evaluates it. `enabled` is `false` when
  `sync.retry.enabled` is off or the cron is `-`: in both cases nothing attempts a pending subject.

## Risks / Trade-offs

- `next_sweep_at` is the scheduled start. A sweep still running delays the next one, and one sweep attempts at most
  `max_per_sweep` subjects. A large requeue therefore takes several sweeps. `max_per_sweep` is reported so that a
  client can say so.
