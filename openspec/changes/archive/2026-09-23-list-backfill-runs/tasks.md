## 0. Precondition

- [x] 0.1 Confirm `2026-09-23-backfill-run-outcome-reporting` is applied (ideally archived) before
      starting. This change reads the registry that change repairs: register-before-submit with
      `runs.remove` on rejection, terminal finality, and `failedDates` on `BackfillStatusResponse`. Task
      4.4 fails against the pre-change code, by design.

## 1. Service: list the registry

- [x] 1.1 In `service/DataBackfillService.java`, add `public List<BackfillRun.Snapshot> list(BackfillRun.State
      state)`, where `state` may be null for all runs.
      - Copy `runs.values()` into a new list inside `synchronized (runs) { … }`. `Collections.synchronizedMap`
        requires the caller to hold the map's monitor while iterating.
      - Release the lock, reverse the copy (newest started first), filter by `state` when non-null, and map
        each run through `snapshot()`.
      - Filter on the snapshot's state, not the run's, so the entry and the filter agree.
      - Do not snapshot while holding the map lock. The javadoc should state the ordering, the bound
        (retention), and that the list is per instance.
- [x] 1.2 No visibility change is needed: `BackfillRun` and `BackfillRun.State` are already public
      (`BackfillRun.java:14, 19`), so the controller can bind `?state=` to the enum directly. Do not widen
      anything else on `BackfillRun`.
- [x] 1.3 Lower `MAX_RETAINED_RUNS` in `DataBackfillService` (`:46`) from `16` to `5`. That one constant
      governs both the list and `GET /{runId}`: do not add a separate list cap or a property.
- [x] 1.4 Update the registry field comment (`DataBackfillService.java:45`) only if the prior change has
      not already made it accurate. It must still say the active run is the newest and is never evicted,
      because the list's ordering rests on it.

## 2. DTO: the envelope

- [x] 2.1 Add `web/dto/backfill/BackfillRunListResponse.java`: a record with one component,
      `List<BackfillStatusResponse> runs`, and a static `of(List<BackfillRun.Snapshot>)` that maps through
      `BackfillStatusResponse.of`.
      - Match the DTO package's camelCase style: no `@JsonProperty`, since `runs` is the same in either
        casing.
      - Annotate the component with `@ArraySchema(schema = @Schema(implementation =
        BackfillStatusResponse.class))` and a description saying the list is newest first, bounded by
        retention (at most the 5 most recently started runs), and a runtime cache rather than a
        history: cleared on restart and redeploy, and per instance, so during a rolling-update overlap it
        can omit a run the other instance holds. Put the same caveat in the `@Operation` description
        (task 3.1).
      - Never emit `null` for an empty list. `{"runs": []}` is the contract, so do not put
        `@JsonInclude(NON_EMPTY)` on this component.

## 3. Controller: the endpoint

- [x] 3.1 In `web/controller/backfill/DataSyncBackfillController.java`, add a bare `@GetMapping` method
      named `listBackfillRuns` (springdoc derives the operationId from the method name).
      - It takes `@RequestParam(name = "state", required = false) BackfillRun.State state` and returns
        `BackfillRunListResponse.of(service.list(state))`.
      - Annotate in the class's existing style: `@Operation(summary, description)`, a `200`
        `@ApiResponse` with the envelope schema, and a `400` `@ApiResponse` for an unknown or empty
        `state`.
      - Declare the parameter's published schema explicitly: `@Parameter(description = …, schema =
        @Schema(type = "string", allowableValues = {"running", "completed", "failed"}))`.
        `BackfillRun.State` has no `@JsonValue`, so without this springdoc publishes the uppercase
        constant names, which the binder accepts but the wire never shows.
      - No `@FullAdminOnly`: it is a read, authorized by the `/v1/**` rule.
- [x] 3.2 Add `@InitBinder("state")` that registers a `PropertyEditorSupport` for `BackfillRun.State`,
      modelled on `PipelineController.bindKindByWireCode` (`PipelineController.java:281-304`).
      - Match `name().toLowerCase(Locale.ROOT)` case-insensitively.
      - On no match, throw `InvalidRequestParameterException("state must be one of [running, completed,
        failed]")`, building the list from `State.values()`.
      - Include the javadoc sentence explaining why: Spring's enum converter is case-sensitive on the
        constant name.
      - `DefaultExceptionHandler` needs no change. Confirm the wrapped exception reaches
        `authoredParameterMessage` (`DefaultExceptionHandler.java:138-148`) and the message is echoed.
- [x] 3.3 Update the controller's class javadoc (`:25-29`) to mention the collection read.

## 4. Tests

- [x] 4.1 `DataBackfillServiceTest`, ordering and filter:
      - Start three one-day runs one after another, waiting for each to reach a terminal state.
        Re-trigger through `await().ignoreException(BackfillInProgressException.class)`, since the guard
        is released in `finally` after the terminal transition (the race the prior change's task 5.2
        documents).
      - Assert `list(null)` returns their `runId`s newest first.
      - Make one of the three fail (stub `runForDate` unsuccessful for that date) and assert:
        `list(State.FAILED)` returns only the all-failed run; `list(State.COMPLETED)` returns the others;
        `list(State.RUNNING)` is empty.
- [x] 4.2 `DataBackfillServiceTest`, the active run: with `runForDate` blocked on a latch (the pattern in
      `reflectsPerBlobProgressBeforeDayCompletes`), assert that `list(State.RUNNING)` holds exactly the
      started run with state `RUNNING` and its `currentDate`. Release the latch, await terminal, and
      assert `list(State.RUNNING)` is empty.
- [x] 4.3 `DataBackfillServiceTest`, eviction at 5: start 6 one-day runs in sequence, using the retry
      from 4.1. Assert `list(null)` has 5 entries (the 6th to the 2nd, newest first), does not contain
      the first `runId`, and that
      `status(firstRunId)` throws `BackfillNotFoundException`, so list and by-id read agree on
      retention.
- [x] 4.4 `DataBackfillServiceTest`, a refused trigger never appears. This is the assertion the prior
      change's task 5.4 deferred here.
      - Start and finish one run.
      - Call the package-private `shutdown()`, and assert `start` throws `RejectedExecutionException`.
      - Assert `list(null)` still holds exactly the one earlier run.
- [x] 4.5 `DataSyncBackfillControllerTest`, with the service mocked:
      - `GET /v1/data-sync/backfill` returns `$.runs` in the mocked order, each entry with the same
        members `statusReturnsRunView` asserts. Reuse the `snapshot(...)` helper as the prior change
        updated it.
      - An empty mocked list gives `{"runs": []}`: assert `$.runs` exists and has length 0, not merely
        that it is absent.
      - `?state=running` and `?state=RUNNING` both pass `State.RUNNING` to the service. Verify with the
        mock.
      - `?state=paused` is `400` with `$.error == "bad_request"`, and the message names all three
        values. Assert that the service was never called.
      - `?state=` (empty) is the same `400`, naming the accepted values, and the service is never
        called. Confirm that Spring hands `""` to the `@InitBinder` editor rather than treating it as
        absent; if it does not, reject the empty value in the handler with the same
        `InvalidRequestParameterException`.
      - Entries carry `failedDates` through the component-level `NON_EMPTY` the prior change put on
        `BackfillStatusResponse`, which holds under the test's default mapper. Like the prior change's
        task 5.7, do not add `@Import(JsonMapperConfiguration.class)` to get it.
- [x] 4.6 Authorization, new `BackfillAuthIntegrationTest extends AbstractOidcAuthIntegrationTest`. It
      is modelled on `TableAuthIntegrationTest.readOnlyCanReadButCannotMutate` (`:105-116`), and must add
      no `@SpringBootTest` properties or `@DynamicPropertySource`, per the base class's context-cache
      note.
      - A read-only token gets `200` on `GET /v1/data-sync/backfill`.
      - A read-only token gets `403` with `$.error == "forbidden"` on `POST /v1/data-sync/backfill`.
        Send a strictly valid body (a past one-day `from`/`to`) so the request reaches authorization
        rather than failing strict request reading first.
      - A full-admin token gets `200` on the list.
      - No token gets `401`.
      - Do **not** add a full-admin trigger. The context is shared across the auth tests and
        `backfill.enabled` is true, so it would start a real run in the singleton service and leak its
        in-flight slot and registry entry into every other test in the context.
- [x] 4.7 `OpenApiPublishedContractIntegrationTest`: add a test in the style of
      `kindPublishesNoDefaultAndOffersTheUnimplementedKindLast` (`:73-82`). It reads the `state`
      parameter of `GET /v1/data-sync/backfill` with the existing `parameter(...)` and `values(...)`
      helpers, and asserts that the published values are exactly `running`, `completed`, `failed` (all
      lowercase) and that the schema publishes no default.

## 5. Spec, docs, build

- [x] 4.8 Grep `src/test` for anything that assumes more than 5 retained runs (a test starting six or
      more runs and then reading an early one by id). None was found when this change was written;
      re-check at apply time and fix any hit.
- [x] 5.1 No configuration property is added, so `docs/configuration.md` gains nothing. Confirm this
      rather than assuming it. `openspec/config.yaml` needs no new convention: the envelope and
      wire-spelled enum filters are already the stated norms.
- [x] 5.2 Run `openspec validate 2026-09-23-list-backfill-runs --strict` and resolve anything it flags.
      This delta is ADDED-only, so it cannot go stale against the prior change's MODIFIED requirements.
      Still confirm neither added requirement's name collides with an existing one in
      `openspec/specs/data-sync-backfill/spec.md` after the prior change is archived.
- [x] 5.3 At archive, reword the base spec's Purpose in `openspec/specs/data-sync-backfill/spec.md`:
      "runs one at a time on a background worker" becomes "runs one at a time per instance on a background
      worker". A delta does not carry the Purpose, so the archive merge will not do it. Leave
      `docs/release-0.2-upgrade.md` alone; it is a shipped release's guide, as the prior change decided.
- [x] 5.4 Record the adas-mcp follow-up in the MR description, since it is a cross-repo change and not
      done here:
      - add a read-tier `adas_list_backfill_runs(state=None)` in `src/adas_mcp/tools/backfill.py`
        `register()`, with a docstring documenting the envelope, ordering, filter and per-instance
        caveat;
      - regenerate `swagger/adas-openapi.json` with `--sort-keys`.
- [x] 5.5 Run `./gradlew clean build` and confirm it passes (compile + checkstyle + tests).
