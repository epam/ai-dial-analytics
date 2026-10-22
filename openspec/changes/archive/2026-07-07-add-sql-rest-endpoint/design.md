# Design — add-sql-rest-endpoint

## Context

ADAS has two query front doors today:

- `POST /v1/queries/execute` — the structured query DSL over HTTP. Controller
  `web/controller/query/StructuredQueryController` binds a `StructuredQuery` envelope, calls
  `StructuredQueryService.execute(query)`, and returns
  `StructuredQueryResultDto(rows, totalCount)` — the `{ "rows": [...], "totalCount": ... }`
  JSON envelope.
- Arrow Flight SQL (gRPC, `flight.enabled` gated) — accepts raw SQL `SELECT` text.
  `AdasFlightSqlProducer` calls `SqlQueryFrontEnd.translate(sql)` (screen → Calcite Babel parse
  → validate against the query-schema catalog → lower to RelNode → translate to
  `StructuredQuery`), then executes via the **same** `StructuredQueryService`, streaming Arrow
  batches back.

So the SQL acceptance contract (`sql-query-translation` spec) and the execution/governance
pipeline (`structured-query` spec) are already shared. What's missing is an HTTP transport for
SQL: a JSON-speaking client cannot submit SQL without adopting gRPC + Arrow.

Failure mapping today: `SqlTranslationException` is handled only by `FlightErrorMapper`
(→ gRPC `INVALID_ARGUMENT`); `DefaultExceptionHandler` has no handler for it.

## Goals / Non-Goals

**Goals:**

- `POST /v1/queries/execute-sql`: accept the same SQL subset Flight accepts, return the same
  JSON envelope the DSL endpoint returns.
- Zero drift: one translator, one execution pipeline, one governance surface. The new endpoint
  adds a transport, not a second query engine.
- Stable `ErrorView` mapping for every failure mode, including SQL translation failures.

**Non-Goals:**

- No new SQL surface: the supported subset is exactly `sql-query-translation` (single-entity
  SELECT, no joins/CTEs/arithmetic/etc.). Extending SQL support is out of scope.
- No `totalCount` opt-in for SQL (SQL has no `include_total` syntax); `totalCount` is never
  populated (and the shared NON_NULL serialization omits the key, as on the DSL endpoint). No
  cursor paging.
- No changes to the Flight endpoint, its config, or its concurrency/memory governance (those
  guards exist because Arrow streams hold direct memory; the REST path materializes ≤ 1000 rows
  as JSON like the DSL endpoint already does).
- No per-endpoint auth rules beyond the existing `/v1/**` policy.

## Decisions

### D1: Route — `POST /v1/queries/execute-sql` on the existing query controller family

A sibling of `/v1/queries/execute` under the same `/v1/queries` prefix, implemented as a new
`SqlQueryController` in `web/controller/query/` (keeping `StructuredQueryController` unchanged
rather than overloading it — different request DTO, different swagger docs, same response DTO).

*Alternative considered*: content negotiation on `/v1/queries/execute` (e.g. `text/plain` body
= SQL). Rejected: implicit, hostile to OpenAPI documentation, and breaks the "responses/requests
are objects" convention.

### D2: Request body — `{ "sql": "<SELECT ...>" }` JSON object

A small record DTO (`web/dto/query/SqlQueryRequestDto`) with a single required `sql` field
(`@NotBlank`), documented with `@Schema`. Object-not-scalar follows the project API convention
and leaves room for future fields (e.g. an `include_total` opt-in) without a breaking change.

*Alternative considered*: raw `text/plain` SQL body. Rejected for the same extensibility and
convention reasons.

### D3: Execution — compose `SqlQueryFrontEnd` + `StructuredQueryService`, nothing new

Controller flow mirrors `AdasFlightSqlProducer.streamQuery` minus Arrow:

```java
SqlTranslation translation = sqlQueryFrontEnd.translate(request.sql());
QueryResultPage page = structuredQueryService.execute(translation.query());
return new StructuredQueryResultDto(page.rows(), page.totalCount());
```

This inherits everything by construction: catalog validation, the closed function catalog,
enrichment LEFT JOINs, default limit 100, max limit 1000 rejected-not-clamped, read-only
screening (single statement, SELECT-only), fail-closed rejection of unsupported constructs
before any ClickHouse contact, and the row→JSON mapping that seeds every projected column and
preserves nulls. `page.totalCount()` is naturally `null` because the translator only ever emits
offset pages without `include_total`.

*Alternative considered*: a dedicated service layer between controller and the two components.
Rejected: it would be a one-line pass-through today; introduce it only if the endpoint grows
logic (e.g. a totalCount opt-in).

### D4: SQL front-end beans must not be Flight-gated

`SqlQueryFrontEnd` and its collaborators (`CalciteQueryCatalog`, translators) are `service/`
components and must be instantiable when `flight.enabled=false`. Verify during implementation;
if any is gated behind the Flight `@ConditionalOnProperty`, lift the gate to the Flight-only
beans (`web/flight/*`). The REST endpoint itself is unconditional — always on, like
`/v1/queries/execute`.

*Alternative considered*: gating the endpoint behind a new `sql-rest.enabled` property.
Rejected: the DSL endpoint it is equivalent to (same governance, same data) has no such gate,
and no new config keeps `docs/configuration.md` untouched.

### D5: Error mapping — add `SqlTranslationException` → 400 `bad_request`

One new `@ExceptionHandler(SqlTranslationException.class)` in `DefaultExceptionHandler`,
echoing the translation message (these messages are constructed by our translator — they name
the offending construct/column and contain no backend internals). Everything else reuses
existing mappings:

| Failure | Status / code | Handler |
|---|---|---|
| Parse error, unsupported construct, unknown table/column (`SqlTranslationException`) | 400 `bad_request` | **new** |
| Structured-query validation (`QueryValidationException`) | 400 `bad_request` | existing |
| ClickHouse rejects rendered SQL (`ClickHouseQueryException`) | 400 `bad_request` | existing |
| ClickHouse unreachable (`ClickHouseUnavailableException`) | 503 `clickhouse_unavailable` | existing |
| Missing/blank `sql`, malformed JSON body | 400 `bad_request` | existing |
| Auth failures (oidc mode) | 401/403 | existing (filter chain) |

### D6: Security — inherit `/v1/**`

No annotations, no new rules: in `oidc` mode the endpoint requires `FULL_ADMIN` or
`READ_ONLY_ADMIN` like every `/v1/**` route (read-only path, so no `@FullAdminOnly`); in `none`
mode it is open. This exactly mirrors both existing query transports.

### D7: Testing — controller slice + integration equivalence

- `@WebMvcTest(SqlQueryController.class)` + MockMvc (template: `StructuredQueryControllerTest`),
  with `SqlQueryFrontEnd`/`StructuredQueryService` mocked: happy path JSON shape, blank/missing
  `sql` → 400, `SqlTranslationException` → 400 `bad_request`, ClickHouse-down → 503.
- Testcontainers integration test (template: `StructuredQueryExecutionIntegrationTest` /
  `FlightSqlIntegrationTest` support classes): same logical query via
  `POST /v1/queries/execute-sql` and `POST /v1/queries/execute` returns identical rows;
  governance cases (LIMIT 50000 rejected 400, default limit applied); translation-rejection
  case (JOIN → 400, ClickHouse untouched); runs with `flight.enabled` **false** to prove the
  translator works without the gRPC listener.

## Risks / Trade-offs

- [Long-running SQL holds an HTTP worker thread] → Bounded by the same reality as the DSL
  endpoint (max 1000 rows, single-entity queries, read-only client); no new exposure. Flight's
  stream gate is not replicated because there is no Arrow memory to protect.
- [`SqlTranslationException` messages leak schema names] → They already flow to Flight clients
  and name only catalog entities/columns the same caller can list via
  `GET /v1/queries/entities`; acceptable.
- [Case-insensitive LIKE deviation surprises REST users] → Same deviation as Flight
  (`sql-query-translation`), documented in the endpoint's API docs.
- [Flight-gated bean discovered late] → D4 makes the check an explicit implementation task with
  a `flight.enabled=false` integration test as the proof.

## Open Questions

- None blocking. A future `include_total`/count opt-in (request-body field) is deliberately left
  for a follow-up change; the object-shaped request body (D2) keeps it non-breaking.
