## MODIFIED Requirements

### Requirement: Every pipeline-internal read runs under a resource budget

Every statement ADAS issues on a pipeline's behalf SHALL execute under the configured `pipeline.budget.*`
ceilings, carried as ClickHouse per-query settings. They SHALL be the enrichment scan page, both phases of
a group fetch, the Class-1 materialize window probe, the Class-1 preview sample, the aggregate refresh
window probe, **the Class-1 materialize write (`INSERT … SELECT`)**, **and the aggregate refresh write
(`INSERT … SELECT`)**.

**No budgeted statement SHALL gate an unbudgeted one on the Class-1 or the aggregate path.** Where one statement exists only to fix the window
for another, as a probe does for its write, a ceiling on the first is in effect a ceiling on the second;
leaving the second exempt places the bound on whichever statement reads less and lets the larger one
consume whatever the node will give it. Which statements are bounded SHALL therefore be a property of the
pipeline's kind, and SHALL NOT depend on whether its declaration happens to carry a `filter`.

**The write SHALL narrow rather than fail.** A write refused by a ceiling SHALL halve its window and
retry, down to a floor of one source row, and SHALL report the narrowed window through the cursor and
`hasMore` it already returns. Only a write refused at that floor SHALL surface the refusal. This is the one
statement whose legitimate size genuinely exceeds any read's, and it is also the one whose contract already
expresses partial progress — so bounding it needs no new vocabulary and strands no pipeline. Narrowing here
is not the sampling that is forbidden below: the rows omitted are not dropped, they are left above the
returned cursor for the next call.

**Narrowing SHALL apply only to a refusal by the write's own ceiling.** A write refused for an exhaustion
the server attributes to the node SHALL surface that refusal without halving its window and without
issuing a further probe, because a smaller window does not address a condition the window's size did not
cause — it spends another statement on a node that is already out of memory. A write whose refusal leaves
no usable budget SHALL likewise report that no narrowed attempt was made, rather than reporting a failure
across retries that did not happen.

Exceeding a ceiling SHALL refuse that read with HTTP 422 `pipeline_read_budget_exceeded`, whose message
SHALL name the ceiling exceeded and its configured value. It SHALL NOT be 503: the cost of one of these
reads follows the physical layout of the tables it reads, so the identical request is refused identically
until the declaration, the layout or the budget changes, and a caller doing the correct thing for a 503
would retry forever. The refusal SHALL be reported as a distinct code from `query_budget_exceeded`, because
the caller and the remedy differ — a runner cannot narrow a time range, and the remedies that apply are
reading fewer heavy columns or aligning the partitioning of the enrichment holding them.

This is the refusal the server attributes to **this read's own ceiling**. A read that fails because the
node itself ran out of memory is not this refusal however similar its error code, and is reported as 503
`clickhouse_unavailable` — see the classification requirement above. The identical read succeeds once the
load clears, so a caller that dead-lettered it would discard work that was never too expensive.

The ceilings SHALL bound memory and wall-clock time, and SHALL NOT bound rows read, for the reason the
query surface gives: what these reads exhaust is memory, and a scan cap would refuse a bounded-but-wide
read while letting a narrow ruinous one through. A read SHALL be refused rather than sampled, narrowed or
truncated: a group labelled from a silently narrowed input is indistinguishable from one labelled from the
whole of it.

This requirement exists because these statements were left unbudgeted on the reasoning that each is
bounded by its own window, page size or limit. That reasoning does not hold. A group fetch's second phase reads the
declaration's bound columns for the members the selection policy kept — one, under
`member_select.limit: 1` — and where those columns live on an enrichment whose grain key prunes no
partitions, that single-row read costs gigabytes. Cost here is a property of the physical layout, not of
how many rows were asked for, so no limit a pipeline declares can bound it. Without a ceiling one such
read consumes whatever the node will give it: the observed failure was a ClickHouse pod OOM-killed by one
group fetch, taking every other capability down with it. The ceiling is a blast-radius bound, not an abuse
bound — the caller is trusted, and the point is that its cost is contained rather than shared.

The write's exemption was justified on the grounds that its cost is bounded by the window its probe
already fixed. That is only true when the probe and the write read the same way, and they do not: each
emits the grain join for the enrichments its *own* clauses reference, so a pipeline declaring no `filter`
has an unjoined, genuinely cheap probe and does its entire enrichment read inside the exempt write. On a
saturated ClickHouse node the single largest consumer can be exactly that statement — a Class-1
materialize write taking hundreds of MiB for tens of seconds, alongside concurrent ones, while the node
sits pinned at its server memory limit shedding queries. A pipeline that declares a `filter` is refused at
a two-column probe; one that declares none is never bounded at all.

The wall-clock ceiling SHALL bound the **materialize call**, not each statement or attempt. The call's
clock SHALL start before its first window probe. Every later statement the call issues SHALL run on what
remains of that one ceiling after the statements before it. That covers the re-probe that establishes
whether the freshness clamp withheld rows, each narrowing re-probe, and each write attempt. A call with
nothing usable left SHALL surface the refusal rather than begin a statement it cannot finish, so one call
occupies at most one configured timeout plus the time to refuse. A statement refused while running on a
remainder SHALL still name the configured timeout, with what was left of it beside it, because the remainder
is no value an operator can set. A narrowing sequence
bounded per attempt would occupy a multiple of the configured time and outlast the caller's own read
timeout; the server would then finish into a closed socket, which the caller reports as a transport failure
and retries — the livelock a deterministic refusal exists to replace. A ceiling that bounded the write
attempts while the probe and each re-probe ran on a fresh one still let a call run for about three times
the configured timeout, which exceeds the runner's read timeout at the defaults. The runner's read timeout
SHALL therefore be documented as paired with this ceiling on a per-call basis.

The aggregate refresh write carries the same ceilings and SHALL NOT narrow. A refresh recomputes every
affected group key in full, so a prefix of its window is not a smaller correct write. A write refused by
its ceiling SHALL fail that run as any other refresh failure does: the rollup's position does not move, the
failure is recorded against the pipeline, and an on-demand refresh answers HTTP 422
`pipeline_read_budget_exceeded`. The ceiling is what keeps one hung recompute from holding the aggregate
scheduler, which runs refreshes serially on a pool of one by default.

#### Scenario: A narrowing sequence does not outlast the budget it runs under

- **GIVEN** a Class-1 materialize whose write is refused by the resource budget
- **WHEN** it narrows and retries
- **THEN** each attempt runs under the time remaining from the call's own ceiling
- **AND** once that ceiling is spent the refusal is reported rather than another attempt started

#### Scenario: The probe's time counts against the call's single ceiling

- **GIVEN** a Class-1 materialize whose first window probe takes most of the configured timeout
- **WHEN** the call goes on to re-probe and write
- **THEN** those statements run only under the time the probe left
- **AND** the whole call ends, successfully or with HTTP 422 `pipeline_read_budget_exceeded`, within one
  configured timeout plus the time to refuse

#### Scenario: A clamp re-probe runs on the remaining time

- **GIVEN** a clamped Class-1 pipeline whose window probe returned nothing
- **WHEN** the call re-probes with the clamp lifted to decide whether to record the drained horizon
- **THEN** that re-probe runs under the time remaining from the call's ceiling, not under a fresh one

#### Scenario: An aggregate write refused by its ceiling fails the run without narrowing

- **GIVEN** an aggregate pipeline whose refresh write exceeds the `pipeline.budget.*` ceiling
- **WHEN** the refresh runs
- **THEN** the run fails with no narrowed retry, the rollup's position does not move, and the failure is
  recorded against the pipeline
- **AND** an on-demand refresh answers HTTP 422 `pipeline_read_budget_exceeded`

#### Scenario: A materialize write refused by the ceiling narrows its window instead of failing

- **GIVEN** a Class-1 pipeline whose write over the probed window exceeds the memory ceiling
- **WHEN** the materialize call runs
- **THEN** the write is retried over a window narrowed from the rows the probe matched
- **AND** the response reports the rows written, a cursor at the narrowed window's end, and `hasMore: true`
- **AND** no refusal is surfaced to the caller

#### Scenario: A write refused at a single row surfaces the refusal

- **GIVEN** a Class-1 pipeline whose write exceeds the ceiling for one source row
- **WHEN** the materialize call runs
- **THEN** the response is HTTP 422 `pipeline_read_budget_exceeded`
- **AND** the cursor does not advance

#### Scenario: Whether a pipeline is bounded does not depend on its filter

- **GIVEN** two Class-1 pipelines reading the same enrichment over the same window, one declaring a
  `filter` that names an enrichment column and one declaring none
- **WHEN** each is materialized
- **THEN** both pipelines' enrichment reads execute under the ceiling

#### Scenario: A group fetch that exceeds the memory ceiling is refused, not served

- **GIVEN** a group pipeline binding two heavy columns from an enrichment that cannot be partition-pruned
- **WHEN** the runner requests the group's rows
- **THEN** the response is HTTP 422 `pipeline_read_budget_exceeded`
- **AND** the message names the memory ceiling and its configured value
- **AND** no partial group is returned

#### Scenario: A refusal on one pipeline leaves the rest of the service serving

- **GIVEN** a pipeline read that would exhaust the ClickHouse node's memory
- **WHEN** it is refused by its own ceiling
- **THEN** concurrent queries on the query surface continue to be served

## ADDED Requirements

### Requirement: A scan call reports its outcome in one log line

Each call to `POST /v1/pipelines/{name}/scan` that returns a page SHALL log one INFO line naming the
pipeline, its generation, the rows returned, `has_more`, `lag_s`, the statement duration, the call's total
duration and the ClickHouse `query_id` of the scan statement. The line SHALL carry no row values, no cursor
tuple and no SQL. A call that returns an empty page SHALL log that line only when the pipeline's previous
scan call on the same replica returned rows, or on its first call, so a caught-up pipeline polling on a
short cadence does not log one line per poll.

The state used for that throttle is held in memory per replica. Several replicas therefore each log their
own first empty page, which is acceptable because the pod name is on the exported log resource.

#### Scenario: A page of rows is logged once

- **WHEN** a scan call returns a page of rows
- **THEN** exactly one INFO line reports the pipeline, the generation, the row count, `has_more`, `lag_s`,
  the durations and the `query_id`, and no row value or cursor tuple

#### Scenario: Repeated empty pages are logged once

- **WHEN** a pipeline's scan calls return an empty page several times in a row on one replica
- **THEN** only the first of them logs an outcome line

### Requirement: A runner's attempts are visible separately from its successful contact

The service SHALL publish, per `enrich` pipeline, the time since the runner last **attempted** one of that
pipeline's data-plane endpoints, as a series separate from the existing last-contact staleness gauge. An
attempt SHALL be recorded for every call to an existing, enabled `enrich` pipeline, whatever its outcome, so a
call refused after the pipeline resolved — for authorization, a budget, or any other reason — still counts as
an attempt. It is recorded when the call completes. A call that succeeded resolved the pipeline on its way, so
recording it SHALL cost no registry read of its own; only a refused call is checked against the registry,
and a call refused as an unknown pipeline is not checked at all. A call refused because the pipeline is
disabled SHALL NOT be recorded: disabling drops the pipeline's series, and a straggling call that re-created
one would leave a gauge climbing for a pipeline nothing is meant to call. A call refused by the runner
contract check SHALL NOT be recorded as an attempt, because that check runs before the pipeline is looked up
and the only name available is the unvalidated path segment.

The existing last-contact gauge SHALL keep its meaning, "last successfully called", unchanged. The two
series together separate a runner that is down (both climb) from a runner that calls and is refused (the
attempt series stays low while contact climbs). The attempt series SHALL follow the same lifecycle rules as
the contact series: it is registered on the first attempt, measured against the present at observation
time, and dropped by every path that deletes or disables the pipeline.

The service SHALL NOT log a "runner contact stale" warning. Each replica records only the calls the load
balancer routed to it, so a replica that is rarely routed to would warn falsely. The signal is the gauge's
minimum across replicas.

#### Scenario: A refused call moves the attempt series but not contact

- **WHEN** the runner calls an existing `enrich` pipeline's data-plane endpoint and the call is refused after
  the pipeline was resolved
- **THEN** that pipeline's attempt series is reset to zero and its last-contact staleness keeps climbing

#### Scenario: A disabled pipeline's refusal records no attempt

- **WHEN** the runner calls a disabled `enrich` pipeline's data-plane endpoint and is refused with `409`
- **THEN** no attempt is recorded, and the series its disabling dropped is not re-created

#### Scenario: A served call costs no extra registry read

- **WHEN** a data-plane call succeeds
- **THEN** its attempt and its contact are recorded without a registry read beyond the ones the call made

#### Scenario: A contract refusal records no attempt

- **WHEN** a data-plane call is refused for a missing or mismatched contract version
- **THEN** no attempt is recorded and no attempt series is created for the name in the path

#### Scenario: A deleted or disabled pipeline leaves no attempt series

- **WHEN** an `enrich` pipeline that has been attempted is deleted or disabled
- **THEN** its attempt series is removed together with its contact series

#### Scenario: No stale-contact warning is logged

- **WHEN** no data-plane call reaches a replica for a pipeline for a long time
- **THEN** that replica logs no warning about stale runner contact, and only the gauges report it
