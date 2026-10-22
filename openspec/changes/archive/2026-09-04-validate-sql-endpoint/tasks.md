# Tasks

## 1. Expose the validation boundary

- [x] 1.1 Add `StructuredQueryExecutor.validate(model, query, maxLimit)` beside `execute`: the same
      entity-coherence check and `queryBuilder.build(...)`, result discarded, no `materialize`.
- [x] 1.2 Add `StructuredQueryService.validate(StructuredQuery)` and `validate(CompositeQuery)`,
      mirroring both `execute` overloads so single-block and composite statements are covered.
- [x] 1.3 Widen `SqlQueryFrontEnd.PARSER_CONFIG` to package-visible, so the identifier probe parses with
      the same grammar and casing the front end validates with.

## 2. The validation service

- [x] 2.1 `SqlIssue` and `SqlValidation` result records — code, verbatim message, optional position,
      optional remedy; verdict, effective limit, issues, warnings.
- [x] 2.2 `SqlIssueClassifier`: the ordered message → (code, remedy) table, with `invalid_sql` as the
      fallback and the reserved-word case separated from a plain parse error by position rather than by a
      keyword list.
- [x] 2.3 `SqlIdentifierProbe`: parse-only enumeration of unresolvable entities and columns, with
      closest-catalog-name suggestions, collecting statement-declared names (aliases, CTEs, derived-table
      outputs) so none of them is reported.
- [x] 2.4 `SqlValidationService`: `plan()` → `queryService.validate(...)` on the happy path; classify
      plus probe on the failure path; effective limit and warnings.
- [x] 2.5 Warnings: `default_limit_applied`, `heavy_column_unbounded`, `unbounded_group_by`; only the
      first for a composite statement.

## 3. Web layer

- [x] 3.1 `SqlValidationResponseDto` and `SqlIssueDto`, with `effective_limit` as `@JsonProperty`
      snake_case and `@Schema` descriptions (the OpenAPI text is contractual).
- [x] 3.2 `SqlValidationController` — `POST /v1/queries/validate-sql`, `200` for every well-formed
      request, `@Operation` text stating what `valid: true` does and does not promise.
- [x] 3.3 No `DefaultExceptionHandler` change — confirmed: no new failure mode, and the `400` is the
      existing bean-validation mapping.
- [x] 3.4 `QueryTranslationController.translateSql` runs `queryService.validate(...)` on the translated
      document, so the endpoint applies the governance its own contract already claimed.

## 4. Tests

- [x] 4.1 `SqlIssueClassifierTest` — every pattern pinned to a message captured from a live rejection,
      so a reworded upstream message fails the build instead of degrading a code to the fallback.
- [x] 4.2 `SqlIdentifierProbeTest` — the findings, and chiefly the silences: table alias, select alias,
      CTE, derived-table output, qualified column, unparseable statement, no-entity-resolved.
- [x] 4.3 `SqlValidationServiceTest` — the real front end, executor and builders, with a ClickHouse
      client every case leaves untouched (`verifyNoInteractions`), covering verdicts and every warning.
- [x] 4.4 The transport-level assertions — envelope, snake_case `effective_limit`, positionless versus
      positioned issue, and the invalid-statement-is-200 / malformed-request-is-400 split — live in
      `integration/SqlValidationIntegrationTest` rather than a `@WebMvcTest` slice, per task 8.3.
- [x] 4.5 `integration/SqlValidationIntegrationTest` — for each of 15 statements, the verdict against
      what `execute-sql` actually does in the same context; plus the envelope and warning cases. Reuses
      the existing `@TestPropertySource` set so the Spring context is shared.
- [x] 4.6 `QueryTranslationControllerTest` — the governance pass is applied, and an over-maximum `LIMIT`
      is now `400`.

## 5. Docs and spec

- [x] 5.1 `docs/sql-reference.md` — the validate endpoint as the way to check a statement, and what
      `valid: true` does not promise.
- [x] 5.2 `README.md` — add the endpoint to the SQL surface list.
- [x] 5.3 No `docs/configuration.md` change — no config property added or renamed.
- [x] 5.4 Confirm the `rest-sql-endpoint` and `query-translation-endpoint` delta specs match what
      shipped, and run `openspec validate 2026-09-04-validate-sql-endpoint`.

## 6. Downstream (adas-mcp, separate repo)

- [x] 6.1 `adas_validate_sql` in `tools/sql.py`, registered via a new `register(mcp)` in the always-on
      read-only group — the point of the change, since `adas_execute_sql` is `full`-only.
- [x] 6.2 Docstring written as the assistant's instruction surface: never executes, `valid: true` means
      execute-sql will accept it, warnings are advisory, and the loop is validate → fix → validate.
- [x] 6.3 `tests/test_sql.py` and `tests/test_tools_smoke.py` — request shape, blank-sql short circuit,
      invalid-but-200 pass-through, and presence under `limited`.
- [x] 6.4 Regenerated `src/adas_mcp/swagger/adas-openapi.json` against the local ADAS and compared
      **structurally** rather than by line count (1222 insertions / 545 deletions is mostly hash-order
      churn). The structural diff shows the snapshot was **two changes stale** before this one: added
      `/v1/queries/validate-sql` + `SqlIssueDto`/`SqlValidationResponseDto` (this change), and picked up
      `extend-sql-surface` (the `CaseExpr`/`WindowExpr`/`CompositeQuery`/`Relation` schema family, the
      `window` function group, reworded `execute-sql`/`translate-sql` descriptions) and
      `read-only-admin-reads-pipelines` (the revision-endpoint gate wording). Conventions preserved —
      4-space indent, ASCII-escaped, trailing newline, byte-identical round trip.

## 7. Verification

- [x] 7.1 `./gradlew clean build` passes unmodified: **158 test classes, 2508 tests, 0 failures, 0
      skipped**, no checkstyle violations, in **2m 57s**. `build.gradle` is at HEAD — no heap bump.

## 8. The full-suite OOM (blocker found during verification)

`./gradlew clean build` dies with `OutOfMemoryError` in the test worker at Gradle's default 512 MB heap.
**Pre-existing, not caused by this change**: `SqlValidationIntegrationTest` declares no
`@TestPropertySource` of its own (it inherits `AbstractClickHouseIntegrationTest`, sharing that context),
and the only context this change added was one `@WebMvcTest` slice.

The source is the number of **distinct Spring test-context cache keys**, not a leak — a
`@TestPropertySource`/`@SpringBootTest` property set is part of the cache key, the cache holds every
context for the worker JVM's lifetime, and the suite has ~20 of them. The
`BatchSpanProcessor_WorkerThread` in the crash line is a bystander: it is simply the first thread to
allocate after the heap is exhausted, and all OTel export is disabled by default here. Raising the heap
was rejected as a symptom fix.

- [x] 8.1 Fold the four OIDC auth integration tests onto one shared `AbstractOidcAuthIntegrationTest`
      (`+ OpaqueUserInfoServerSupport`), so four bespoke property sets become one context: **−3 contexts**.
      *Authored in a separate session and carried here from `stash@{0}`; its `build.gradle` heap hunk was
      deliberately dropped, and the stash is left in place rather than popped.*
- [x] 8.2 Fold `EnrichReadsEntityIntegrationTest`'s one-property `@TestPropertySource` onto the shared
      five-constant set — it was a strict subset, so it forked a context for nothing, which is exactly
      what `AbstractClickHouseIntegrationTest`'s own javadoc warns against. Verified its fixture still
      passes under the shared values (notably the 4 KB group-fetch cap): **−1 context**.
- [x] 8.3 Drop `SqlValidationControllerTest`, the slice this change added. Its assertions — the envelope,
      snake_case `effective_limit`, the positionless issue, and the invalid-statement-200 vs
      malformed-request-400 split — are all carried by `SqlValidationIntegrationTest` against real
      ClickHouse, with the position assertions moved there so nothing is lost: **−1 context**.
- [x] 8.4 Confirmed: the reduced context set fits 512 MB with room to spare. A full unmodified
      `clean build` completes in **2m 57s** with **zero** `OutOfMemoryError` occurrences — faster than the
      ~10 minutes the suite took before this work, so the consolidation improved throughput rather than
      merely surviving. Neither `spring.test.context.cache.maxSize` nor a heap increase is needed.
- [x] 8.5 **Fixed a class-initialization deadlock in the stashed helper** — the actual reason the suite
      appeared to take 30+ minutes. `OpaqueUserInfoServerSupport` started its stub server on a daemon
      thread whose body was a lambda belonging to the class being initialized: the initializing thread
      held the class-init monitor and then `join()`ed, while the new thread could not enter a method of a
      class still initializing on another thread. It **hung rather than failed**, so it read as slowness
      and would have wedged CI indefinitely. The thread's entry point moved into a nested `Starter` class
      with the claim constants passed as arguments, using a named `Runnable` record rather than a lambda
      (a lambda's synthetic method belongs to its capturing class, which would reintroduce the cycle), and
      exceptions are carried back to the joining thread. The four auth classes went from never finishing
      to **41 tests in 49 s**.
- [x] 7.2 Walked 15 statements by hand against the local instance: every verdict agreed with what
      `execute-sql` actually did (valid → 200, invalid → 400), and every classifier code fired on a real
      rejection. `translate-sql` now answers 400 for `LIMIT 50000` (it answered 200 before). The
      `heavy_column_unbounded` warning is confirmed against a live refusal: an unbounded heavy read is
      reported valid + warned and `execute-sql` returns **422 `query_budget_exceeded`**, while the same
      statement with a `request_time` bound loses the warning and returns 200.
- [x] 7.3 Live verification found a defect the tests missed: for a single misspelled column the probe's
      closest-name suggestion was dropped by the duplicate-name filter, leaving only the generic remedy.
      The suggestion is now merged onto the primary issue, with assertions in
      `SqlValidationServiceTest` and `SqlValidationIntegrationTest` that would have caught it.
