## Context

`AggregateRefreshService.preview` builds two things: the refresh statement (rendered, not executed) and a
sample query that *is* executed, through `StructuredQueryExecutor.executeScan`. The sample is compiled by
`AggregateCompiler.compilePreview` with `LIMIT aggregate.preview-rows` and no window predicates — that
omission is deliberate and documented at `AggregateRefreshService:157-160`.

`executeScan` is shared by **six** call sites: the preview sample, the refresh probe, `GroupFetchService`,
`ScanService`, and `MaterializeService` (probe and select). Any budget added here must therefore be opt-in
per call site — a ceiling that applied to all six would silently bound the enrichment paths too, which are
governed by their own scan limits and have never been asked to fit inside a preview's budget.

`executeScan` runs every query under one shared static `QuerySettings` (`FINAL_SETTINGS`, currently just
`final = 1`), so the budget has to *merge over* that constant rather than replace it. A budget that
silently dropped `final = 1` would change what the preview reports, not merely what it costs.

The failure that motivated this change is worth stating precisely, because it constrains the fix.
Previewing a `group_by request_body` declaration over tens of thousands of rows did not merely run slowly — it took the
ClickHouse container's host down, along with Postgres and the service's own database connection.
ClickHouse's own memory accounting did not stop it. That means a design resting on ClickHouse's default
server-level memory tracking would reproduce the failure; the ceiling has to be set explicitly, per
query, at a value below what the container can absorb.

## Goals / Non-Goals

**Goals:**

- A preview cannot exhaust ClickHouse, whatever declaration it was registered with.
- A refused preview is distinguishable, by stable code, from an outage and from a bad declaration.
- Legitimately large previews degrade (spill to disk) before they are refused.
- The budget is deployment-tunable without a rebuild.

**Non-Goals:**

- Narrowing what preview computes. Sampling or windowing the input is explicitly excluded by the spec —
  a reduced-input number presented as the declaration's output is worse than a refusal.
- Bounding refresh. It is window-bounded already, and a capacity ceiling there would make a legitimate
  large window fail as though it were malformed.
- Fixing the probe's whole-window row transfer, or reconciling preview's statement-versus-sample
  divergence. Both were found in the same testing pass and both belong to their own changes.
- Protecting the enrichment scan path, which shares `executeScan` but is bounded by its own scan limits.

## Decisions

### Apply the budget as per-query settings, not a client- or server-level configuration

Add an `executeScan` overload merging caller-supplied `QuerySettings` over `FINAL_SETTINGS`, and pass the
budget from the preview call site only. This keeps `final = 1` intact (settings compose), leaves the other
five callers untouched, and puts the ceiling in the same mechanism the codebase already uses to talk to
ClickHouse.

*Alternative considered — a dedicated ClickHouse user with a quota/settings profile for preview.* More
robust, since it cannot be bypassed by a code path forgetting to pass settings, and it survives a
malicious statement. Rejected for now: it adds a credential and a server-side profile to provision in
every environment, which is a heavier operational contract than this fix warrants. Worth revisiting if
more unbounded read paths appear.

*Alternative considered — enforcing in the application (timeout + row cap on the client).* Rejected: the
damage is done inside ClickHouse before the first row is returned, so a client-side timeout abandons the
result without stopping the work.

### Bound memory and time, not rows read

The three settings are `max_execution_time`, `max_memory_usage`, and `max_bytes_before_external_group_by`.

`max_rows_to_read` / `max_bytes_to_read` were considered and rejected as the primary control. They bound
the *scan*, but the observed failure was aggregation state, not scan volume: `group by deployment` over a
very large input is cheap in memory and would be refused by a scan cap, while `group by request_body` over
a small one is ruinous and would pass. Capping the resource that actually ran out keeps ordinary
coarse-grained previews over big inputs working, which is the common case.

### Spill before refusing

Set `max_bytes_before_external_group_by` strictly below `max_memory_usage`. A preview whose aggregation
state grows past the spill threshold goes to disk and completes slowly; only one that keeps growing past
the hard ceiling is refused. This gives the large-but-legitimate declaration a way to succeed and reserves
the error for the pathological one, which matters because the operator running a preview usually cannot
tell in advance which one they have.

### Refuse with 422 and a distinct code, not 503

A budget refusal is deterministic: the identical request fails identically until the declaration or the
budget changes. 503 promises a transient condition that retrying will clear, so a client backing off and
retrying — the correct response to 503 — would loop. 429 implies a rate that will replenish. The
actionable fix is on the caller's side of the contract (coarsen the grain, add a membership filter, ask
for a higher budget), which is what 422 already means elsewhere in this capability. The distinct code is
what keeps it separable from a genuine spec violation.

### Detect the overflow by ClickHouse error code, and fail safe when unrecognised

ClickHouse signals these as specific server error codes (`TIMEOUT_EXCEEDED`, `MEMORY_LIMIT_EXCEEDED`).
Map those to the new exception; anything unrecognised keeps falling through to the existing
`ClickHouseQueryException` → 400 path. Matching on the numeric code rather than message text is the less
brittle option, and the fallback means a driver or version change degrades to today's behaviour rather
than to a wrong one.

## Risks / Trade-offs

- **A default set too low refuses previews that used to work.** → Defaults are chosen to stop pathological
  declarations, not ordinary large ones; the budget is env-configurable; the error message names the
  budget that was exceeded so the remedy is obvious rather than requiring a log dive.
- **`max_memory_usage` is per query per node.** Several concurrent previews can still sum to more than the
  server has. → Preview is `FULL_ADMIN`-only and operator-driven, so concurrency is naturally low; a
  server-side quota profile is the answer if that assumption ever stops holding, and is noted above as the
  escalation path.
- **Spilling needs disk.** A spill threshold set generously on a node with little scratch space trades an
  OOM for a disk-full. → Document the relationship in `docs/configuration.md` so the two are tuned
  together.
- **Error-code matching couples us to ClickHouse's codes.** → Fallback path preserves current behaviour;
  the codes in question are long-standing and stable.
- **The budget protects preview only.** Another unbounded read path added later gets no protection for
  free. → Called out as a non-goal, with the quota-profile alternative recorded for when that becomes the
  pattern rather than the exception.

## Migration Plan

Additive and backward-compatible. New `aggregate.preview.*` keys ship with defaults, so an existing
deployment gets the ceiling without touching its configuration; no data migration and no client change.
Rollback is a configuration edit — raise the budgets, or set them high enough to be inert — with no code
revert required.

### Defaults: 30s wall-clock, 2 GiB ceiling, 1 GiB spill threshold

`max_execution_time=30s`, `max_memory_usage=2GiB`, `max_bytes_before_external_group_by=1GiB`.

Sized to refuse the declaration that motivated this change — a `group by request_body` over the whole input,
whose aggregation state is proportional to a large string column times the row count — while leaving an
ordinary coarse-grained preview over a much larger input working, since `group by deployment` costs
kilobytes of state no matter how many rows it reads. 30s is well beyond any preview an operator would wait
through and well below the point at which a runaway query has done real damage.

The ceiling must sit **below the ClickHouse container's memory limit** or it guarantees nothing: the
failure occurred precisely because no per-query ceiling was below what the host could absorb. 2 GiB is
chosen to hold on a small deployment; a deployment that gives ClickHouse substantially more can raise it,
which is why the value is configuration and not a constant.

The spill threshold sits at half the ceiling so a growing aggregation goes to disk with room to finish
rather than hitting the hard limit on the next allocation.

### A budget refusal is not recorded against the pipeline

Preview writes no state today, and `last_error` means "the last refresh failed" — a field the state view
and the health story are both built on. Recording a preview refusal there would make a pipeline that has
never run look like one that ran and failed, which is a worse signal than none. The refusal is in the
response, which is where the operator who caused it is already looking.

## Open Questions

None.
