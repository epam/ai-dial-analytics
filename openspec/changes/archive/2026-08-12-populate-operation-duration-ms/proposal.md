## Why

`operation_duration_ms` is the last **blocked** column of `dial_usage_log`: created by `V1`, deliberately
absent from `mapping/dial-usage-log.yaml`, and therefore stored as the ClickHouse default `0` on every
row ever ingested. It was blocked for one reason — DIAL Core did not emit it.

It does now. [`epam/ai-dial-core@8b1634e2`](https://github.com/epam/ai-dial-core/commit/8b1634e212f013390585ce2385baef5aacf1e961)
appends `operation_duration_ms` to every analytics log record:

```java
append(entry, ",\"operation_duration_ms\":", false);
append(entry, Long.toString(logContext.getOperationDurationMs()), true);
```

Three properties of that emission matter here, all read from the commit rather than assumed:

- **Top-level and unconditional.** The field sits beside `deployment`/`execution_path`, not nested, and
  its append is not gated by any `analytics.*` setting — unlike the `claims`/`headers` objects in the
  same commit, which the new `AnalyticsSettings` can switch off. Every record from a core at or after
  this commit carries it.
- **A plain JSON number of milliseconds**, never negative: both the live path
  (`ProxyContext.calculateOperationDurationMs`) and the background-job path clamp with `Math.max(0, …)`,
  because both bounds are wall-clock and a backwards clock adjustment must not produce a negative
  duration.
- **Two measurement windows, one column.** A live request measures from the core accepting the request
  to having the full response body; a path that never produces a response body (e.g. a deployment
  without an endpoint) falls back to the current time. A background job has no response body to measure
  against, so it measures accept → log emission, which includes the poller's detection lag (up to
  `backgroundJob.maxPollIntervalMs`) and, for an expired job, the whole time the core waited before
  giving up.

The column is already fully described downstream — catalog row, `performance` tag, `Duration (ms)`
display name, the `long` query field type, `docs/column-tags.md` — so everything but the ingest step is
in place. The consequence of the missing step is measurable: a `conversations_rollup` pipeline reports
`duration_ms` and `avg_duration_ms` as `0` for every conversation, which is an ingest gap presenting as
data.

## What Changes

- Add `operation_duration_ms` to `mapping/dial-usage-log.yaml` as a direct mapped column
  (`source: operation_duration_ms`, `kind: long`), positioned where the DDL puts it — after
  `deployment_price`, before `response_status` — so the mapping keeps matching `V1`'s column order.
- Keep the column **non-nullable `UInt32`**: no ClickHouse migration, no catalog `nullable` flip. See
  `design.md` for why nullable was considered and rejected; the cost is that a record from a core older
  than the commit above stores `0` rather than "unknown", which is exactly today's behavior and is now
  stated in the spec rather than left implicit.
- Retire the "blocked column" concept from the `data-sync` spec and the docs: with this entry the shipped
  mapping populates every user column of `dial_usage_log`, and `_ingested_at` is the only table column it
  leaves to a table-side default.
- Correct a stale claim in `docs/configuration.md`, which still lists `jwt_claims`/`request_tags` among
  the unmapped blocked columns although they have been mapped since
  `2026-07-09-populate-jwt-claims-request-tags`.

No migration, no DDL, no new transform or deriver, no configuration surface. Operators upgrading ADAS
without upgrading DIAL Core see no change; operators on a current core see real durations on newly
ingested rows.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: gains a behavioral requirement that a producer-reported operation duration is persisted
  (the sibling of the existing cache-write-token guarantee), and loses the "blocked column exists but is
  unpopulated" guarantee. The table-provisioning requirement's column inventory moves
  `operation_duration_ms` from blocked to direct, and the startup-validation requirement's
  permissibly-unmapped example narrows to `_ingested_at`.

## Impact

- `src/main/resources/mapping/dial-usage-log.yaml` — one added column entry; header comment loses the
  blocked-column paragraph.
- `openspec/specs/data-sync/spec.md` — via the delta under `specs/`.
- `docs/configuration.md` — default-mapping and startup-validation bullets.
- `src/test/java/.../sync/mapping/MappingEngineTest.java`, `MappingFileLoaderTest.java`,
  `SchemaValidatorTest.java` — fixture, column count, declared-order list, and the unmapped-column test's
  subject.
- Runtime: one more field read per record on the ingest hot path. No effect on dedup (the column is not
  in the sort key), on the ingest control flow, or on any other column.
- Historical rows keep their stored `0`; re-ingesting their blobs through the backfill endpoint would
  store `0` again, since those records predate the producer field. Fix-forward only.
- Existing aggregate pipelines over the column (such as `conversations_rollup`) need no change and start
  reporting real numbers as soon as rows carrying the field arrive.

Not in scope: the PostgreSQL catalog description text (`'Wall-clock duration of the operation, in
milliseconds.'`), which stays accurate enough for both measurement windows and does not justify a
migration of its own; and any backfill of the historical `0`s.
