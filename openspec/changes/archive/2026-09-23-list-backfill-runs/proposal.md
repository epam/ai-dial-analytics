## Why

A backfill run can be found only by the caller that started it. `POST /v1/data-sync/backfill` returns the
`runId` once (`DataSyncBackfillController.java:38-52`), and the only read is `GET /{runId}` (`:54-64`).
A UI opened after the trigger, a second operator, or the same operator after closing the tab cannot learn
that a backfill is running, which run it is, or how far it has got. The only thing that tells them is the
`409` a second trigger gets, and that carries no run id.

The endpoint answers two questions:

- **Is a backfill running, and what is its live status?**
- **How did the recent ones end?** This is the gap a UI polling a single run falls into: a run that
  finishes between two polls would otherwise vanish, leaving the UI without its outcome.

The service already holds the answer. `DataBackfillService` keeps recent runs in an in-memory registry,
newest last (`DataBackfillService.java:45-64`), and one of them is the active run whenever one exists.
Nothing reads the registry except the by-id lookup. This change adds the collection read that the by-id
read implies, and shrinks the registry from 16 runs to **5**.

The list is a **runtime cache, not history**. It is cleared on every restart and redeploy, and it is
per instance. Keeping 5 is the maintainers' decision:

- Backfills run one at a time, so 5 is ample to answer "what just happened".
- A small cache makes the restart-loss limitation less misleading than a long pseudo-history that
  silently resets on every deploy would.

Durable history is a non-goal (see below).

It lands **after** `2026-09-23-backfill-run-outcome-reporting` and depends on it. That change:

- makes the registry trustworthy to list: a rejected submission is withdrawn rather than left `running`
  forever, and every abort reaches a terminal state;
- keeps registration ahead of submission, so the registry's insertion order is the runs' start order;
- adds `failedDates` to the status entry this list reuses;
- names this change as the one that makes its `runs.remove` assertable (its `tasks.md` 5.4).

Listing today's registry would publish exactly the stuck-`running` entries that change removes.

## What Changes

- **Retention drops from 16 runs to 5.** `MAX_RETAINED_RUNS` in `DataBackfillService` becomes `5`. One
  number governs both the list and `GET /{runId}`: there is no separate list cap and no configuration
  property.
- **`GET /v1/data-sync/backfill`**: lists the runs this instance retains, newest started first, as
  `{"runs": [ … ]}`. Each entry is exactly the `BackfillStatusResponse` that `GET /{runId}` returns, so a
  UI renders both with one component.

  ```json
  {
    "runs": [
      {"runId": "f1d2…", "from": "2026-05-26", "to": "2026-06-24", "daysTotal": 30, "state": "running",
       "currentDate": "2026-06-03", "daysCompleted": 8, "daysFailed": 0, "…": "…"},
      {"runId": "9ab0…", "from": "2026-05-01", "to": "2026-05-07", "daysTotal": 7, "state": "completed",
       "daysCompleted": 6, "daysFailed": 1, "failedDates": ["2026-05-04"], "…": "…"}
    ]
  }
  ```

- **Optional `?state=running|completed|failed` filter**, in the lowercase wire spelling the status
  already uses. `?state=running` answers "is a backfill active, and which one" with zero or one entry.
  An unrecognised value is `400 bad_request`, naming the accepted set. No match is `200 {"runs": []}`,
  never `404`.
- **No pagination.** The list is bounded by the 5-run retention. A status entry stays around 5 KB even
  at the 366-day cap with every day failed (the budget the prior change set for `failedDates`), so the
  whole list is about 25 KB at worst.
- **The per-instance scope becomes explicit in the spec.** Run state, the registry, the list and the
  single-in-flight guard are all per service instance. During a rolling-update overlap, a list served by
  one pod can omit a run held by the other, just as `GET /{runId}` can already `404` on the wrong pod.
  This change states that rather than solving it.
- **Authorization is unchanged.** The new `GET` is covered by the `/v1/**` rule
  (`OidcSecurityConfiguration.java:125`), so a read-only admin can list and read runs, and only a full
  admin can trigger (`@FullAdminOnly`, `DataSyncBackfillController.java:39`).

## Non-goals

- **A failure reason on the status** (`failureReason`). The prior change deferred it to "the
  run-listing change if anywhere". A list makes it more valuable, because a UI showing several `failed`
  runs cannot say why. It is still a separate contract decision: it reverses the shipped clause that the
  reason for stopping "SHALL be reported in logs and metrics rather than as a new status field".
  Bundling it would turn an additive read into a reversal.
- **Cancel or pause.** The enrichment runner has both (`POST /v1/backfills/{runId}/{pause|resume|cancel}`);
  ADAS has neither. A list is the precondition for a cancel button, but cancel is new write behaviour
  with its own semantics (for example, what happens to the day in progress). It is a candidate for a
  follow-up change.
- **Durable run history.** Persisting runs to Postgres would let the list survive restarts and span
  pods, and would make a long history meaningful. It touches the worker lifecycle and restart semantics,
  and is a possible future change. Until then the list is deliberately short, so it is not mistaken for
  history.
- **Push or streaming** (SSE, webhooks). Polling a list of at most 5 small entries is cheap, and the
  status's own progress cadence is per blob.
- **Coordinating across pods.** Electing one pod to hold the registry and guard, or a Postgres lock for
  the guard, is the real fix for the per-instance scope. It is a larger change and a possible future
  one; this change only documents the limitation.
- **Re-coding the backfill DTOs to snake_case.** The list reuses the camelCase entry unchanged. The
  prior change records the backfill DTOs as a known exception in `config.yaml`, and the envelope's one
  key, `runs`, is the same in either casing.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync-backfill`: gains two **ADDED** requirements. *Backfill runs are listable* covers the
  collection read, its filter, ordering, bound and error mapping. *Backfill run state is per service
  instance* makes explicit what the existing "held in memory" clause implies once there is more than one
  pod. No existing requirement is modified, so this delta cannot go stale against the prior change's
  MODIFIED requirements.

## Impact

- **APIs**: one new endpoint, `GET /v1/data-sync/backfill[?state=…]`, returning
  `200 {"runs": [BackfillStatusResponse…]}` or `400 bad_request`. There is no new error code.
- **Visible narrowing**: `GET /{runId}` now returns `404` for a run older than the 5 most recently
  started, where it used to hold 16. A caller holding an old `runId` loses it sooner. Name this in the
  release notes.
- **Code**:
  - `service/DataBackfillService.java`: `list(State)`, snapshotting the registry under its lock, and
    `MAX_RETAINED_RUNS` changed from 16 to 5.
  - `web/dto/backfill/BackfillRunListResponse.java` (new): the envelope.
  - `web/controller/backfill/DataSyncBackfillController.java`: the `GET` handler plus an `@InitBinder`
    for `state`, in the style of `PipelineController.bindKindByWireCode` (`PipelineController.java:281-304`).
  - `DefaultExceptionHandler` is unchanged: `InvalidRequestParameterException` already maps to
    `400 bad_request` with its message echoed.
- **Tests**: `DataBackfillServiceTest` (ordering, filter, eviction, a rejected submission never listed),
  `DataSyncBackfillControllerTest` (shape, empty list, `400`), and an OIDC auth integration test (a
  read-only admin can list and read but not trigger).
- **Exposure**: `/v1/data-sync` is not on the Traefik allowlist (`docs/kubernetes-exposure.md:86-87`), so
  a browser UI reaches this endpoint through the admin backend, as it would the existing status read.
  Changing the allowlist is not part of this change.
- **Config / migration / credential**: none. Adding no property, the change adds nothing to
  `docs/configuration.md`.
- **Cross-repo follow-up (adas-mcp, not a task here)**:
  - add a read-tier tool `adas_list_backfill_runs` to `src/adas_mcp/tools/backfill.py` `register()`,
    with a docstring documenting the envelope, the filter, and the per-instance caveat;
  - regenerate `swagger/adas-openapi.json` with `--sort-keys`.

  This rides with the prior change's own adas-mcp follow-up.
