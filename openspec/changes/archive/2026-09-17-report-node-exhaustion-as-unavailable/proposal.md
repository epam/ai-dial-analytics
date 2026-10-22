## Why

ClickHouse error code **241** means two different things and ADAS reports both as its own budget refusal.
One is the per-query ceiling ADAS itself set via `max_memory_usage` — deterministic, and the identical
statement will be refused identically until the declaration, the layout or the budget changes. The other is
the **node** running out of memory because of everything else on it — transient, and the identical
statement succeeds once the load clears. `ClickHouseBudget.translate` switches on `server.getCode()` alone
(`:147-151`) and renders both as `422 pipeline_read_budget_exceeded`, with a message naming *our* ceiling
and advising the caller to narrow a declaration that did not cause the pressure.

The misreport changes retry semantics across a service boundary. The Enrichment Runner treats 4xx as
permanent by contract, so ordinary node contention becomes a **poison dead-letter** — work that will never
be retried, for a reason that had nothing to do with the work. Under node-wide memory pressure such refusals
arrive in bursts, **and none of them need be the per-query ceiling.**

Two further defects in the same mechanism surfaced while designing this and are fixed here because they
are the same question — what ADAS does when ClickHouse refuses for a resource:

- A materialize write refused by a **timeout** reports that it failed "across narrowing retries" when it
  ran none, and pays for a re-probe whose result it can never use.
- The ClickHouse driver **retries every 241 up to four times** before ADAS sees it, so a refusal ADAS
  intends to be deterministic is executed four times against a node already under pressure.

## What Changes

- **Attribute a 241 from the server's own account of which limit was reached.** A refusal the server marks
  as node-wide, or as an overcommit victim, is reported `503 clickhouse_unavailable`; the statement's own
  ceiling stays `422`. A 241 that **cannot** be attributed falls back to 503, which is already the outer
  rule for a resource failure — the 422 is a narrow exception carved out of it, and an exception should
  require proof rather than assume it.
- **BREAKING for a caller keying on the status**, in the direction the contract already documents: a
  node-wide exhaustion that used to answer `422 pipeline_read_budget_exceeded` or `422
  query_budget_exceeded` now answers `503 clickhouse_unavailable`. Over Flight the same failure moves from
  `INVALID_ARGUMENT` to `UNAVAILABLE`. No caller loses a code; one condition is re-pointed at the code that
  already existed for it.
- The narrowing refusal message distinguishes a write that narrowed from one that never got to, and the
  loop stops re-probing when the budget cannot fund another attempt.
- **Separable:** the ClickHouse read client stops letting the driver retry a server-side refusal.

A **per-user** memory breach is also re-pointed at 503, for the same reason and correctly: ADAS never sets
`max_memory_usage_for_user`, so if that ceiling binds it was set on the server or the profile and depends
on what else ADAS is running concurrently — environmental and transient, not a property of the refused
statement.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enrichment-runtime`: *A ClickHouse resource failure is reported as unavailable, not as a bad request*
  currently gives the budget's refusal unconditional precedence (`:1239-1242`) and states the
  classification is made "from the numeric server error code" (`:1228-1230`). Both need qualifying: one
  code is ambiguous by construction, and precedence belongs only to a refusal the server attributes to the
  statement's own ceiling. *Every pipeline-internal read runs under a resource budget* needs its "It SHALL
  NOT be 503" (`:1142-1148`) scoped to that same attribution, and a sentence recording that a write
  refused for node-wide exhaustion no longer narrows.
- `structured-query`: gains a scenario making explicit what its existing rule already implies — a
  statement failing for a reason other than the budget's ceilings keeps its own mapping (`:1801-1804`), so
  a node-wide exhaustion is not `query_budget_exceeded`. No requirement loosens.

## Impact

- **Surfaces**: the status of one failure condition on `POST /v1/queries/execute`, `execute-sql`, the four
  runner data-plane endpoints, the aggregate preview, and the Flight SQL producer. No request or response
  *shape* changes anywhere.
- **Code**: `clickhouse/ClickHouseBudget.java` (the shared decision),
  `service/enrichment/MaterializeService.java` (message + re-probe), `clickhouse/ClickHouseClientWrapper.java`
  (driver retry). `web/flight/FlightErrorMapper.java` and `web/handler/DefaultExceptionHandler.java` need
  **no** edit — both dispatch on exception type and already map `ClickHouseUnavailableException`.
- **Cross-service**: closes runner item **R1** with **no runner code change**. The runner's own spec
  (`resilience-and-dlq:268-272`) forbids branching on an upstream `error` machine code and accepts
  dead-lettering a capacity refusal "until that contract exists"; fixing the *status* satisfies that clause,
  so the runner's existing 5xx-transient rule becomes correct unchanged. `RunnerContract.VERSION` is **not**
  bumped — no endpoint's request or response shape moves.
- **Docs**: `docs/sql-reference.md`'s error table presents a budget refusal as flatly deterministic and
  needs the distinction; `docs/clickhouse-server.md` must record that the 241 message text is now
  load-bearing and which server versions it was verified against, or the next upgrade has no reason to
  re-check it.
- **No config key, no migration, no new credential.**
