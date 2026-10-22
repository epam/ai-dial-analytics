## Why

`POST /v1/pipelines/{name}/preview` bounds the rows it *returns* but nothing about the work it does to
produce them. The sample is deliberately not window-restricted — preview exists to answer "are these the
right numbers", which a window would obscure — so the compiled sample aggregates the pipeline's **entire
input** every time it is called.

Against a `dial_usage_log` of only tens of thousands of rows, previewing a declaration that grouped by
`request_body` (a large `string` column) ran an unbounded full-input `GROUP BY` that exhausted ClickHouse
and took the container host down with it — ClickHouse, Postgres, and the service's own database
connection. The declaration was never enabled and never wrote a row: **registering a pipeline that is
refused the moment anyone tries to run it is currently enough to take the data plane offline.**

The preview gate sits between declaring a rollup and letting it write, so it is precisely the endpoint an
operator is expected to call on a declaration nobody has vetted yet. It cannot be the endpoint that costs
the most to call.

## What Changes

- The preview query executes under an explicit **resource budget** — wall-clock, memory, and
  spill-to-disk thresholds — applied to the preview statement only. The refresh path is untouched: it is
  already bounded by its window, and a refresh that fails halfway is a correctness question, not a
  capacity one.
- A preview that exceeds its budget is refused with the shared `ErrorView` contract and a **stable error
  code naming the budget**, so an operator can tell "this declaration is too expensive to preview" from
  "ClickHouse is down" (503) and from "this declaration is invalid" (422). Today both surface as a
  generic failure, or as nothing at all because the server dies first.
- The budget is env-driven configuration (`AGGREGATE_PREVIEW_*`), so a deployment sitting on a large
  input can raise it deliberately rather than discovering the ceiling by hitting it.
- Preview's *scope* is explicitly **not** narrowed. Sampling or windowing the input would make the
  numbers unrepresentative and defeat the gate. This change bounds the cost, not the question.

## Capabilities

### New Capabilities

None. This constrains an endpoint that already exists.

### Modified Capabilities

- `aggregate-pipeline`: the "Register, preview, enable lifecycle" requirement gains the preview resource
  budget and its refusal behaviour; the "Aggregate pipeline errors use the shared error contract"
  requirement gains the new budget-exceeded code alongside the existing 404/409/422/503 set.

## Impact

- **Code**: `AggregateRefreshService.preview` and the query executor path it calls (the budget must reach
  the ClickHouse client as query settings, the way `final=1` already does); `AggregateProperties` gains
  the budget members; `DefaultExceptionHandler` gains a handler for the new failure; a new exception type
  in the pipeline service package.
- **API**: additive — one new stable `error` code on an existing endpoint. No request or response shape
  changes, no breaking change for clients.
- **Config**: new `aggregate.preview.*` keys in `application.yml` as bare `${ENV:default}` lines, with
  descriptions added **only** to `docs/configuration.md` per project convention.
- **Operational**: a deployment whose legitimate previews are heavier than the defaults must raise the
  budget. Defaults are therefore chosen to refuse pathological declarations, not ordinary large ones.
- **Not affected**: refresh, the scheduler, the compiled statement returned by preview (which already
  carries the real refresh window), and every other capability. The enrichment Class-1 materialize path
  shares `ClickHouseInsertSelectExecutor` but not the preview path, so it is out of scope.
