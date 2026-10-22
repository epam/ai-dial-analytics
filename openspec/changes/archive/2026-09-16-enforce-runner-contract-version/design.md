## Context

Two shipped releases (`fold-rule-into-pipeline`, `simplify-pipeline-authoring`) deleted a vocabulary
rather than deprecating one, deliberately, so the two services are never simultaneously correct across
the cut. The sanctioned answer is a coordinated window with the runner scaled to zero. Nothing enforces
it: both repos auto-deploy on merge to the default branch, and an analysis of
each deploy order traces what it actually does. The critical arm is ADAS-ahead-of-the-runner for a row-grain LLM
pipeline: the data-plane endpoints were unchanged by the release, so the transport keeps working while
the definition the caller holds is wrong, and the scan cursor — which advances at enqueue and is never
re-examined — drains the backlog producing nothing, with no dead letter, no metric and lag falling to
zero. The runner image deployed today would refuse that particular entry (the same release added an
admission arm that rejects an entry carrying no `outputs`), so what is being designed against is the
class rather than that instance — and `fold-evaluator-into-pipeline` is the next member of it, since an
entry with no separately-addressed evaluator still carries `outputs` and still passes.

Three properties of the target Kubernetes deployment topology bound the design:

- Both ADAS and the enrichment runner are single-replica `RollingUpdate` with an
  effective `maxSurge 1 / maxUnavailable 0`, so the incoming pod must pass its probes before the outgoing
  one is terminated.
- ADAS's **liveness** probe targets the aggregate `/actuator/health` (failureThreshold 3, period 10s), so
  any new health contributor that can report DOWN restarts ADAS rather than reporting a condition.
- The runner reaches ADAS at `http://adas.<namespace>.svc.cluster.local.:80` — an in-cluster Service
  with no ingress or proxy in the path, so a custom header cannot be stripped between the two.

## Goals / Non-Goals

**Goals:**

- No deploy order can produce a wrong label, an advanced cursor over unprocessed rows, or a charged model
  call whose result cannot be written.
- A skew is loud where it is cheap to see and self-heals when the second service lands, with no operator
  action.
- The guards that matter do not depend on the other service behaving: ADAS's refusals are decided from
  the request alone.

**Non-Goals:**

- Removing the deploy window. Exact matching keeps it; it only changes what happens when it is not
  followed.
- Rollback compatibility, which this project does not require.
- Intra-service concerns: nothing here touches Flyway, migration ordering, or a service's own rolling
  update. The `V8`-class hazard (a service renaming state under its own outgoing pod) is out of scope.
- The `generation`-bump trap: real, but operator-triggered and a stall rather than a corruption.

## Decisions

### 1. One opaque integer, compared for equality — not a range, a semver, or a media type

A range (serve N and N−1) would remove the window entirely, and was rejected for the same reason
`pipeline-registry/spec.md` records the byte-identical requirement being withdrawn: it buys one rolling
window at the price of a permanent second spelling for every fact. Path versioning (`/v2/pipelines`) and
vendor media types were rejected as far larger surfaces for the same information. Semver was rejected
because nothing would consume the ordering — there is no "compatible range" to express when the rule is
"both halves ship together".

The number starts at `1` and is opaque: only equality is defined, so nothing can grow a partial-ordering
policy later by accident.

### 2. A response header, set unconditionally; a request header, required only on the data plane

An unknown *request* parameter or header is silently ignored by any Spring service, so the runner cannot
detect an old ADAS by sending something. An unknown *response* header is the one signal an older build
cannot forge, which makes its absence the definition of "older than the contract". A header rather than an
envelope field because one component covers all five calls uniformly, where a body member would have to be
added to five response records and would not exist on an error response at all.

**The response header is set by a servlet filter, not by the interceptor that requires it**, and the two
halves are deliberately different components. "Absent echo" means "older than the contract" to the runner,
so every response ADAS can produce under the prefix must carry it — and a `HandlerInterceptor` does not
see the ones that never reach a handler. `config.yaml` already records that auth errors render in two
independent places: a `401` and a URL-level `403` are written by `RestAuthenticationEntryPoint` /
`RestAccessDeniedHandler` inside the security filter chain, and an unmapped path under the prefix resolves
with no handler at all. A wrong or expired API key would therefore answer without the echo and be reported
by the runner as a version skew — the one diagnosis this header exists to make unambiguous, spent on the
most common operational error there is. A `OncePerRequestFilter` on `/v1/pipelines/*`, ordered ahead of
the security chain, has no such blind spot.

The request-side requirement stays in a `HandlerInterceptor` because it needs the resolved handler method
to read the exemption annotation (decision 3), which a filter does not have.

Requiring it on the request is deliberately narrower than setting it on the response. Only the four
runner-only surfaces require it, because closing the **data plane** is what makes the loss unreachable: an
old runner that misreads the feed cannot act on what it read. Requiring it on the compiled *listing* was
considered and rejected — it would break `adas-mcp`'s `?view=compiled` reads and the post-deploy
verification that uses them, while adding nothing.

### 3. Deny-by-default in one interceptor, with the operator surfaces marked exempt

A `HandlerInterceptor` (the pattern `CorrelationIdInterceptor` / `LoggingWebConfiguration` already
establishes) requires the header on **every** `POST /v1/pipelines/{name}/…` handler except those annotated
`@RunnerContractExempt`, which is applied to `preview` and `refresh`.

The alternative — annotating the four runner endpoints as *requiring* it — was rejected because the
failure modes are asymmetric. A future runner endpoint that forgets an opt-in annotation is a silent hole
of exactly the kind this change exists to close; a future operator endpoint that forgets the exemption is
a 409 on first call, found immediately. Per-method `@RequestHeader` parameters were rejected for the same
reason, plus they would put the same four-line check in four places.

`PipelineDataController` is `@RequestMapping("/v1/pipelines/{name}")` and holds exactly six `POST`
mappings, so "every POST under the prefix except the two exempt ones" is a closed statement about one
class rather than a pattern that has to be kept in step with a list. The one list that does have to be
kept in step is `OidcSecurityConfiguration`'s, which enumerates the same four paths as
`authenticated()` — an opt-in enumeration of exactly what this interceptor is deny-by-default about. They
stay separate lists (one is an authorization rule, the other a contract rule, and collapsing them would
make a security decision follow from a versioning constant) with a comment on each naming the other.

The check runs in `preHandle`, before the pipeline lookup, the service-layer grant check and any
ClickHouse read, so a skewed caller cannot consume a read budget. It runs *after* the security filter
chain, so an unauthenticated caller still gets `401` rather than `409` — the right order: "who are you"
precedes "which build are you". Exceptions thrown in `preHandle` are resolved by
`DefaultExceptionHandler` like any other, so the refusal renders as an ordinary `ErrorView` — and it
carries the contract header, because the filter of decision 2 has already set it.

### 4. `409` with two distinct codes

`409` over `422`: the request is well-formed and its declaration is valid — what conflicts is the pair of
build versions, not the content. `426 Upgrade Required` describes it more precisely but is unused in this
service and in the runner's HTTP client; `412`/`428` read as being about resource preconditions. Two codes
rather than one (`runner_contract_required`, `runner_contract_mismatch`) because "a caller that predates
the contract" and "a caller on the wrong side of a bump" are different operational situations, and the
distinction is free.

The runner's `RetryableStatus` treats only 5xx and 429 as retryable, so a 4xx is permanent to it — which is
correct here: retrying a version skew cannot succeed, and the runner's own half stops calling once it sees
the missing echo.

### 5. The write-back guard tests column presence, not value, under its own error code

A row must name at least one declared output column; a named output column carrying `null` is accepted,
because a mapping that maps a sentinel to `null` is a declared outcome (there is one such mapping live).
This is safe against the current runner: `OutputMapper.map` puts every `output.column()` key
unconditionally, and the runner's `JsonMapper` is configured with `contentInclusion = ALWAYS`, so a
null-valued map entry is serialized rather than dropped. Had either been otherwise, an all-null row would
have been a legitimate shape and the guard would have had to move to the runner.

Worth naming because it crosses the repo boundary: `422` is not in the runner's `RetryableStatus`, so if
this guard ever does fire, `UpsertService` archives the batch to the DLQ **with its computed payload**
rather than retrying it — recoverable by requeue, and the right outcome for a refusal that would keep
refusing. It is a new DLQ source, unreachable from a correct runner but not from a future or third-party
client, which is the whole reason the guard is version-independent.

This guard is version-independent on purpose. It is the mirror of the principle already written into the
runner's admission gate — a remote invariant is not a safe guard for a local, irreversible loss — applied
in the direction ADAS owns: ADAS holds the target and the mapping, so ADAS is where "this write can write
no label" is decidable no matter which client version sent it.

It gets its **own** error code, `write_output_column_required`, rather than joining
`write_column_not_allowed`. Both are 422s about a write's column set, which is the argument for reusing
one — but a machine code exists so a client can act on it without reading prose, and the two remedies are
opposite: `write_column_not_allowed` says *remove* the named column, this says *add* one the declaration
names. Publishing a code whose stated remedy is wrong half the time is worse than publishing a second
code, and the cost is one exception class and one handler — the same price the two 409 codes pay in
decision 4, for the same reason.

### 6. Refuse an unservable compiled read rather than resolve it

`?view=compiled` without `kind=enrich` currently returns the unresolved object under the compiled name.
Resolving it instead would cost a per-pipeline lookup across a cross-kind listing for entries with nothing
to resolve — which is why the gate exists — so the combination is refused (400 for the listing, 422 for a
single non-`enrich` read) rather than served or silently downgraded. This is the same defect class as the
one the whole change addresses: a plausible response that is not the one asked for.

### 7. A metric, never a health contributor, for "the runner has stopped calling"

Per-pipeline staleness is derived at **scrape** time from a stored last-contact instant, following
`AggregateMetrics`' reasoning: a stored lag value freezes exactly when calls stop, which is the condition
the series exists to expose. It is not a `HealthIndicator` because ADAS's liveness probe reads the
aggregate health — a contributor reporting DOWN for "no runner" would restart ADAS every 30s.

A gauge derived at scrape time is only honest while something can still move its input, so the
registration and deregistration rules carry the same weight as the value:

- **Registered on first contact**, like `aggregate.lag.seconds`, so a pipeline the runner has never
  called has no series rather than a series climbing since startup. The two *refusal counters* go the
  other way and are registered at startup, because they are untagged by pipeline and a counter that
  appears only once the condition has happened cannot be alerted on beforehand.
- **Not tagged by pipeline, for the refusals.** The refusal is decided before the pipeline lookup, so the
  only name in hand is the path segment the caller sent — unvalidated, and chosen by exactly the client
  that is already misbehaving. Tagging it would let a skewed caller mint registry series.
- **Dropped when the pipeline is deleted *or disabled*.** Deletion is the case `AggregateMetrics` already
  handles. Disabling is the one that matters more here and has no aggregate analogue: the runner's feed is
  `enabled=true`, so a disabled pipeline leaves it and the runner correctly stops calling — a climbing
  "the runner has stopped calling" series for a pipeline an operator deliberately switched off is a false
  alarm raised by the intended behaviour, and it would be the *loudest* series on the dashboard.
- **Both ADAS deletion paths, not one.** `PipelineService.delete` calls `AggregateMetrics.forget`;
  `deletePipelineTargetingTable`, which removes a pipeline because its target table was dropped, does
  not. That is a live instance of the leak `forget`'s own javadoc says it exists to prevent, and the new
  gauge would inherit it verbatim, so it is fixed for both metrics rather than worked around for one.

Re-enabling or re-creating a pipeline re-registers the series on the next call, which is the correct
starting point: staleness measured from a contact that actually happened.

### 8. A code constant, no configuration key

No new property, in either repo. The reasoning is the one the runner already applies to hard-coding
`view=compiled`: the only wrong setting disables everything at once, and silently. `docs/configuration.md`
therefore gains nothing.

### 9. A test pins every shape the contract covers, next to the constant

The version is only honest if it is bumped. A unit test asserts a golden list held beside the constant, so
a change to a shape the contract covers fails the build naming the bump. The list covers what the
requirement covers, which is more than the feed: the compiled projection's member set **and** the
component names of the four data-plane endpoints' request and response records. Pinning only the
projection would leave the larger half of the stated trigger — "any of these four endpoints' request or
response shapes" — asserted by nothing, and renaming `next_cursor` is exactly as breaking to the runner
as dropping `grain_key`.

This is a build-time consistency test, not a deploy guard — the deploy guards are the refusals above — so
it is allowed to be blunt: the failure message says which members moved and that `RunnerContract.VERSION`
must be raised, and updating the golden list without raising the version is a reviewable one-line lie
rather than an invisible one.

## Risks / Trade-offs

- **The constant is not bumped when the shape changes** → decision 9's test fails the build and names it;
  the in-flight `fold-evaluator-into-pipeline` change is the first that must bump it.
- **A proxy strips the header, and a correct runner is refused by a correct ADAS** → the runner reaches
  ADAS through an in-cluster Service with no ingress in the path (verified). Post-deploy verification
  curls the endpoint with and without the header through that exact URL, while the runner is still at 0.
- **An unrelated ADAS failure is reported as a version skew** → the echo is set in a filter ahead of the
  security chain (decision 2), which runs before the `DispatcherServlet` resolves a handler at all, so
  `401`, `403`, an unmapped-path `404`, a `422` and a `500` all carry it and only a genuinely older build
  answers without one. The header is set on the initial dispatch and survives the error forward, which
  does not reset the response's headers.
- **The old runner's queued write-backs are refused and archived on the first attempt** → `409` is not
  retryable to the predecessor, so there is no retry ladder to wait out; the DLQ item carries the computed
  result, so it is recoverable by requeue, but the pipeline must be **enabled** at requeue time or the
  stored item is destroyed. A group pipeline additionally comes out stamped clean and possibly paused.
  Listed in the deploy steps rather than left to be discovered.
- **Refusing `view=compiled` without `kind=enrich` breaks a read that works today** → it is an operator
  read that was silently answering with the wrong projection; the refusal names the fix, and `adas-mcp`
  sends both parameters on the path that matters.
- **Class-1 positions freeze while a skew lasts, clamping dependent rollups** → unchanged from the
  sanctioned window's behaviour, self-healing within one cadence, and now attributable through the
  runner-contact metric instead of appearing as a mystery in ADAS.
- **This change is itself a contract change** → its own deploy is the last one relying on the runbook.
  Neither order loses work that a requeue cannot recover (see below), but ADAS-first is survived by the
  predecessor rather than self-healed by it, so the runbook still earns its place for this one deploy.

## Migration Plan

The window is unchanged — *capture the baseline → scale the runner to 0 → deploy ADAS → deploy the runner →
scale to 1* — and both halves must ship. What changes is what happens if it is not followed:

- **ADAS first:** the old runner sends no header, so every `scan` / `group-rows` / `materialize` / `rows`
  call is refused 409. That order is **recoverable, not free** — the predecessor image has none of the
  runner-side machinery this pair adds, so the refusal lands on its pre-existing arms:
  - **Cursors are untouched.** The scan and materialize ticks fail on the refused call before anything is
    committed, and re-fire on cadence.
  - **Model calls do not stop.** A chunk already queued makes no ADAS call before its DIAL call, so the
    predecessor derives and *charges* it; only the write that follows is refused. Removing this cost is
    exactly what the runner half's paid-work gate is for.
  - **A refused write-back is archived on its *first* attempt**, not after `WORKER_MAX_ATTEMPTS`: `409` is
    not in the runner's `RetryableStatus`, so `UpsertService` takes its permanent-rejection branch straight
    away, dead-lettering the item with its computed rows (and, for a group write, stamping and charging it).
  - **A refused `group-rows` call stamps the group clean.** The predecessor reads a non-retryable `4xx`
    there as a poison group: dead-lettered, stamped computed so the sweep does not revisit it, and counted
    against the DLQ circuit breaker — which can auto-pause the pipeline and does not reset itself.

  Nothing is lost that a requeue cannot recover, but nothing recovers on its own either: requeue the
  archived items with the pipeline **enabled**, and check `/v1/pipelines/paused` afterwards. Class-1
  positions freeze meanwhile, so `sessions_rollup` and `session-insights-live` clamp and self-heal after
  the runner lands.
- **Runner first:** every ADAS response lacks the echo, so the new pod never completes a sync, never marks
  readiness, and is restarted by its startup probe. `maxUnavailable: 0` keeps the outgoing pod serving
  against the old ADAS — still a correct pair — until ADAS lands; then the new pod goes Ready and the old
  one is terminated. No operator action.

There is no rollback and none is required: an ADAS rolled back to a build without the contract would be
refused by the new runner, which is the same safe stall.

## Open Questions

None blocking. One follow-on, recorded rather than resolved here: the in-flight
`fold-evaluator-into-pipeline` change removes the evaluator as a separately-addressed object and is the
first change that must raise the contract version — and the two guards that saved the last skew
(`ScanService`'s sql-evaluator 422 and the runner's `outputs` gate) are worth re-checking against whatever
projection it serves.
