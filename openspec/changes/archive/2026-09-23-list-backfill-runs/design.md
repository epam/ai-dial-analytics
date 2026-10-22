## Context

The backfill surface is a trigger and a by-id read (`DataSyncBackfillController.java:38-64`). The
`runId` exists on the wire exactly once, in the `202`. The registry behind the by-id read is:

- a `Collections.synchronizedMap` over an insertion-ordered `LinkedHashMap`, evicting its eldest entry
  beyond `MAX_RETAINED_RUNS = 16` (`DataBackfillService.java:46, 58-64`), which this change lowers to 5;
- guarded by an `AtomicBoolean` for single-in-flight (`:57`);
- worked by one daemon thread (`:52-56`).

The field comment at `:45` already states the invariant a list relies on: "the active run is always the
newest, so it is never evicted".

This change is written against the code as `2026-09-23-backfill-run-outcome-reporting` leaves it, and
lands after it. Three things it makes true are load-bearing here:

1. **`start` registers before submitting and withdraws the run on rejection** (its design, "Register
   before submitting; withdraw the run on rejection"). Insertion order is therefore start order, and the
   registry never holds a run the worker was refused.
2. **Every run reaches a terminal state**, including on `Error`; a catalog-read failure now fails only
   its day. A listed `running` entry is therefore really running.
3. **`BackfillStatusResponse` carries `failedDates`** (`@JsonInclude(NON_EMPTY)`), so a list of full
   entries is enough for a UI to offer "re-run these days" without a second call.

Topology: no ADAS manifest states a replica count. The archived designs record one replica in steady
state (`archive/2026-08-05-isolate-aggregate-scheduler-pool/design.md:27-28`) but a guaranteed overlap on
deploy: RollingUpdate with effective `maxSurge 1 / maxUnavailable 0`
(`archive/2026-09-18-fold-evaluator-into-pipeline/design.md:588-593`). The registry and the guard are
process fields, and nothing coordinates them across pods. No shipped spec says so.

## Goals / Non-Goals

**Goals:**

- A caller without the `runId` can find the active run and read its live progress.
- A caller can see how the last few runs ended, including their failed dates, from one read. This
  covers a run that finished between two polls, which a UI watching only the active run would lose.
- The list is honest about being a runtime cache: short, cleared on restart, and per instance.
- The per-instance scope of all backfill state is stated in the spec, not left for a caller to discover
  during a deploy.

**Non-Goals:**

- Durable history, `failureReason`, cancel or pause, push notifications, and cross-pod coordination
  (see the proposal's Non-goals).
- Any change to the trigger, the status entry, or an error code. The by-id read changes only in how
  long it remembers a run (see the next decision).

## Decisions

### Decision: Retain 5 runs, one number for both reads

`MAX_RETAINED_RUNS` drops from 16 to 5. This is the maintainers' decision, recorded with its reasons:

- **5 is ample.** Backfills run one at a time, so 5 entries always hold the active run plus the four
  before it. That is enough to show a run that ended between two polls, and the few before it for
  context.
- **Short is honest.** The registry is cleared on every restart and deploy. Sixteen entries read like a
  history that then silently resets; five read like what they are, a cache of what just happened.
- **One number.** The list and `GET /{runId}` read the same registry, so a run is listable exactly as
  long as it is readable by id. A separate, smaller list cap over a larger registry would make "listed"
  and "known" disagree, and would need a rule for which one the spec means.
- *Cost*: `GET /{runId}` forgets a run after 5 newer starts instead of 16. That is a visible narrowing,
  and the proposal names it. No test pins 16 (checked: nothing in `src/test` references
  `MAX_RETAINED_RUNS` or exercises more than one or two sequential runs).
- *Alternative — keep 16*: rejected by the maintainers, for the "pseudo-history" reason above.
- *Alternative — make it a property*: rejected. It is a UI-shaped constant, not an operational knob, and
  a property would add a `docs/configuration.md` row for no operator need.

### Decision: The collection root, returning full status entries in an envelope

`GET /v1/data-sync/backfill` returns `{"runs": [BackfillStatusResponse…]}`.

- *Why the collection root*: it is the REST collection the existing `POST` (create) and `GET /{runId}`
  (member) already imply, so it needs no new path segment. It cannot clash with `/{runId}`: a
  `PathPattern` capture never matches an empty segment, so the bare path goes only to the bare
  `@GetMapping`. A trailing slash, `GET /v1/data-sync/backfill/`, matches neither — trailing-slash
  matching is off by default in Spring 7 — and stays `404 not_found`, never reaching `/{runId}`.
- *Why an envelope*: `config.yaml` context requires that list responses be objects, never bare arrays,
  so counts or metadata can be added later without breaking clients. `{"runs": […]}` follows
  `PipelineListDto`'s `{"pipelines": […]}`.
- *Why full entries rather than a summary*: at most 5 entries of about 5 KB worst case is about 25 KB,
  and usually a few hundred bytes each. Reusing the by-id representation means one UI component, one
  OpenAPI schema, and no second read to show progress or failed dates.
- *Alternative — `GET /backfill/active`* as a single resource: rejected, chiefly because a run that
  finishes between two polls leaves the UI with no outcome — the resource now says "nothing active",
  and the run it was watching is gone from it. Also, "nothing active" has no good answer. A `404` reads as "unknown resource" and is the by-id read's code for an unknown run; a `204`
  has no body to evolve. It also answers only the active question, so history would need a second
  endpoint. `?state=running` on the list gives the same answer as 0 or 1 entries, and the list gives the
  history.
- *Alternative — `GET /backfill/runs`*: rejected. It is a subpath for what is already this resource's
  collection, and it would read as a nested resource.
- *Alternative — put the active `runId` in the `409`*: rejected on two counts:
  - In `message`, it is prose, and the handler's standing rule is that clients branch on `error` and
    never parse `message`.
  - As a new `ErrorView` member, it breaks the fixed `{status, error, message, path, method}` contract
    for one endpoint.

  It would also only help a caller who had just tried to trigger.

### Decision: Newest started first, from the registry's insertion order

The service copies the registry's values under the map's lock, reverses them, and snapshots each.

- *Why insertion order is start order*: `start` puts the run before submitting it (prior change). The
  map is insertion-ordered (`new LinkedHashMap<>()` without `accessOrder`), and a `get` does not reorder
  it.
- *Why not sort by `startedAt`*: equivalent, but it adds a comparator whose ties (two starts in the same
  instant) would need a rule. Insertion order already is the start order the invariant at `:45` names.
- *Locking*: `Collections.synchronizedMap` does not make iteration atomic, so its contract requires
  holding the map's monitor while iterating. The service takes `synchronized (runs)`, copies the values
  into a list, and releases the lock before snapshotting. `BackfillRun.snapshot()` takes the run's own
  monitor. The worker takes run monitors but never the map's while holding one (`start` calls `put` and
  `remove` without holding any run monitor), so there is no lock-order inversion. Snapshotting outside
  the map lock keeps the lock short.
- Each entry is internally consistent (one `snapshot()` per run). The list as a whole is not one atomic
  cut across runs, and needs not be: only one run can be changing.

### Decision: `state` filter, bound by its wire spelling

`?state=running|completed|failed`, bound through an `@InitBinder("state")` that matches
`State.name().toLowerCase(Locale.ROOT)`. That mirrors how `BackfillStatusResponse.of` renders it
(`BackfillStatusResponse.java:51`). An unmatched value throws `InvalidRequestParameterException` naming
the accepted set.

- *Why a binder*: Spring's enum converter is `Enum.valueOf`, case-sensitive on the constant name, so
  `?state=running` — the only spelling a client has seen — would otherwise fail to convert.
  `PipelineController.bindKindByWireCode` (`PipelineController.java:281-304`) exists for exactly this
  reason, and this copies it.
- *Error mapping*: `InvalidRequestParameterException`, whether thrown bare or wrapped in the binder's
  `MethodArgumentTypeMismatchException`, is already `400 bad_request` with its message echoed
  (`DefaultExceptionHandler.java:81-121, 138-148`). No new handler and no new code.
- *Empty value*: `?state=` reaches the editor as `""`, matches nothing and is the same `400`, rather than
  silently meaning "no filter". Omitting the parameter is how a caller asks for every run.
- *Published values*: `BackfillRun.State` has no `@JsonValue`, so springdoc would publish the enum as
  `RUNNING`/`COMPLETED`/`FAILED`. The parameter therefore declares its schema explicitly: a string
  with `allowableValues` `running`, `completed`, `failed` and no default.
- *Case*: case-insensitive, like `kind`. A strict match would be stricter than the precedent for no gain.
- *Alternative — no filter, the client filters*: workable at 5 entries. But `state=running` is the
  question the UI actually asks, and naming it in the API makes the "0 or 1" guarantee a spec statement
  instead of a client inference.

### Decision: No pagination, stated as a spec bound

Retention caps the list at 5. Pagination parameters would be a contract to honour forever for a
collection that cannot grow past one page. If retention ever becomes persistent (see the non-goals),
pagination arrives with it.

### Decision: Authorize by the existing `/v1/**` rule, and pin it with a test

No annotation. `/v1/**` → `FULL_ADMIN` or `READ_ONLY_ADMIN` (`OidcSecurityConfiguration.java:125`) is
exactly the by-id read's rule. The trigger's `@FullAdminOnly` is unchanged. Nothing tests backfill
authorization today, so this adds a test that pins both halves: read-only can list, and read-only cannot
trigger. A future annotation on the class would silently change either one.

### Decision: Make the per-instance scope a requirement, not a footnote

The base spec says run state is in memory and may be lost on restart. It does not say it is
per-instance, and with a list that gap becomes visible. A UI that shows "no backfill running" from one
pod while the other pod is mid-run, during a deploy, is showing something the service never promised.
This change states the scope, including that the single-in-flight guard is per-pod, which already holds
today.

It does not fix it:

- Persisting runs to Postgres would make the list and by-id read deployment-wide.
- A Postgres advisory lock or row lock would make the guard deployment-wide.

Together they are the real change, and they touch the worker lifecycle and restart semantics. That is
recorded as a candidate future change.

## Risks / Trade-offs

- **[A refused trigger can evict the oldest retained run]** → accepted. `start` puts the run before
  submitting it (prior change), and a `put` into a full registry evicts the eldest through
  `removeEldestEntry` before the rejection's `remove` withdraws the new one. The registry briefly
  holds the refused run, and ends up holding 4. Submission is refused only while the executor is
  shutting down, so this happens only at shutdown. That is why the spec says "at most" 5, and why a
  refused trigger is absent only "once the refusal has been returned".

- **[A list read during a deploy overlap can miss the active run]** → stated in the spec. The overlap is
  one pod's startup time, and the same risk already applies to `GET /{runId}` today. A UI should phrase
  an empty `state=running` as "no backfill running on this instance", or simply "none shown".
- **[Clients may start polling the list instead of the by-id read]** → both are O(5) map work under a
  short lock. Polling either at UI cadence is negligible.
- **[`startedAt` ties]** → irrelevant under insertion order. Recorded only because a sort-based
  implementation would need to handle them.
- **[Ordering depends on the prior change]** → if this landed first, a rejected submission would be
  listed as `running` forever. The tasks and proposal state the dependency, and the rejected-submission
  test here fails against the pre-change code, which enforces the order.

## Migration Plan

One new `GET`, with no schema, config or data change. Two observable changes for an existing caller:

- A bare `GET /v1/data-sync/backfill` today is `405 method_not_allowed` (the path exists only for
  `POST`) and becomes `200`.
- `GET /{runId}` returns `404` for a run older than the 5 most recently started, where it held 16. This
  is the visible narrowing the proposal names. Deploy after
`2026-09-23-backfill-run-outcome-reporting`. A client that does not call the endpoint sees no change.
