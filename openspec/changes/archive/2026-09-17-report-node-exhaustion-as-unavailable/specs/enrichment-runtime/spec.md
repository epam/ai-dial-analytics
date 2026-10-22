## MODIFIED Requirements

### Requirement: A ClickHouse resource failure is reported as unavailable, not as a bad request

A failure in which ClickHouse ran out of a resource SHALL be reported as HTTP 503
`clickhouse_unavailable`, on every path that talks to ClickHouse — read, row insert, `INSERT … SELECT`,
DDL and the usage-log batch alike. The classification SHALL be made in one shared place, from the numeric
server error code, and SHALL cover at least out of memory, unable to allocate, execution timeout, too many
simultaneous queries, no free connection, server socket timeout, network error, aborted, not enough space,
too many parts, and cancelled server-side.

A failure in which ClickHouse rejected the statement — unknown column, unknown table, type mismatch, syntax
— SHALL remain a statement rejection (HTTP 4xx). An unrecognised code SHALL be treated as a rejection
rather than as an outage: a real defect misreported as transient would have every caller retry a statement
that can never succeed.

Where a statement carries a resource budget, the budget's own refusal SHALL take precedence over this
classification **only where the server attributes the exhaustion to that statement's own ceiling**. The
per-query ceiling the service set and the node running out of memory under unrelated load arrive as the
**same error code**, and the code alone cannot say whose ceiling was reached: the first is deterministic
and the identical statement is refused identically (422, see above), the second is a bystander's report
and the identical statement succeeds once the load clears (503). The service SHALL therefore attribute the
refusal from the server's own account of which limit was reached, and an out-of-memory refusal it cannot
attribute to the statement's own ceiling SHALL be reported as 503 `clickhouse_unavailable`.

This is the one question for which the server's **message** is read rather than its numeric code, against
the rule stated above, and the exception SHALL be bounded to that one question. It is justified twice.
The code is already known to mean resource exhaustion, so the fallback is the 503 this requirement opens
with rather than a guess at a statement defect — the conservative direction for an exception carved out of
a rule is to require proof, not to assume it. And the ceiling the service itself set is the form a test can
provoke against a real server, so the recognised half stays pinned by the suite while the unrecognised half
defaults to the answer that loses no work.

The alternative default destroys work silently: a node-wide exhaustion reported as the budget's own refusal
is a 4xx, a first-party caller's contract makes a 4xx permanent, and ordinary node contention becomes a
dead-letter. That is not hypothetical — under node-wide memory pressure such refusals arrive in bursts,
**none of them the per-query ceiling.**

Attribution SHALL be a **positive identification of the statement's own ceiling**, not a search for
markers of the alternatives. The service sets exactly one memory ceiling — the per-query one — so a
refusal it cannot identify as that ceiling names a limit the service did not set, whoever set it: the
node's, the user's, or one a later server version introduces. All of those are environmental, they depend
on what else is executing rather than on this statement's shape, and the identical statement succeeds once
that load clears. Identifying the one ceiling the service owns therefore covers every alternative
including the ones that do not exist yet, where enumerating the alternatives would silently stop covering
them the moment one is added.

Because a write's narrowing is triggered by the budget's own refusal, a write refused for an exhaustion
attributed to the node SHALL surface that refusal rather than narrow its window: halving a window does not
address a condition the window did not cause.

A server-side resource refusal SHALL NOT be re-executed by the client transport before the service
classifies it. A refusal the service reports as deterministic is not deterministic in cost if the driver
has already run it several times, and those attempts are invisible to the budget's own wall-clock
accounting — so a refusal issued to protect a node under pressure would instead multiply the pressure it
exists to refuse.

This requirement exists because a resource failure reported as a client error both mislabels the failure
and destroys work. `POST /v1/pipelines/{name}/rows` answered `400 bad_request` — "check field/function/type
compatibility" — for rows whose every value was valid, because ClickHouse was out of memory; the runner
correctly treated a 400 as non-retryable and discarded them, so enrichment rows were lost that way. The
statement was never wrong, and the caller was told it was.

#### Scenario: An insert refused for lack of memory is retryable

- **GIVEN** a valid enrichment row set
- **AND** ClickHouse failing the insert with `MEMORY_LIMIT_EXCEEDED`
- **WHEN** the runner writes the rows
- **THEN** the response is HTTP 503 `clickhouse_unavailable`
- **AND** no message suggests the rows are malformed

#### Scenario: A genuinely malformed statement is still a rejection

- **GIVEN** a statement naming a column the table does not have
- **WHEN** it is executed
- **THEN** the response is a 4xx statement rejection, not 503

#### Scenario: A node-wide memory exhaustion under a budget is not the budget's refusal

- **GIVEN** a pipeline read carrying the `pipeline.budget.*` ceilings
- **AND** ClickHouse refusing it because the node's own memory limit was reached, not the read's ceiling
- **WHEN** the read is issued
- **THEN** the response is HTTP 503 `clickhouse_unavailable`
- **AND** no message names the read's memory ceiling or advises narrowing the declaration

#### Scenario: The statement's own ceiling is still a deterministic refusal

- **GIVEN** a pipeline read whose own configured memory ceiling is exceeded
- **WHEN** the read is issued
- **THEN** the response is HTTP 422 `pipeline_read_budget_exceeded` naming the ceiling and its value

#### Scenario: An unattributable out-of-memory refusal is reported as an outage

- **GIVEN** a budgeted statement refused for memory
- **AND** a server account of the limit the service cannot attribute to that statement's own ceiling
- **WHEN** the refusal is classified
- **THEN** the response is HTTP 503 `clickhouse_unavailable`

#### Scenario: A ceiling the service did not set is not the service's refusal

- **GIVEN** a budgeted statement refused for exceeding a memory ceiling applied to the whole node or to
  the account the service connects as, rather than to the statement
- **WHEN** the refusal is classified
- **THEN** the response is HTTP 503 `clickhouse_unavailable`
- **AND** no message names the statement's configured ceiling

#### Scenario: A write refused by node-wide exhaustion is not narrowed

- **GIVEN** a Class-1 materialize write under the `pipeline.budget.*` ceilings
- **WHEN** ClickHouse refuses it because the node itself ran out of memory
- **THEN** the write is reported as HTTP 503 `clickhouse_unavailable`
- **AND** its window is not halved and no further probe is issued

#### Scenario: A resource refusal reaches the service once

- **GIVEN** a statement ClickHouse refuses with a server-side resource error
- **WHEN** the refusal is returned to the service
- **THEN** the transport has not re-executed the statement on the service's behalf

### Requirement: Every pipeline-internal read runs under a resource budget

Every statement ADAS issues on a pipeline's behalf SHALL execute under the configured `pipeline.budget.*`
ceilings, carried as ClickHouse per-query settings. They SHALL be the enrichment scan page, both phases of
a group fetch, the Class-1 materialize window probe, the Class-1 preview sample, the aggregate refresh
window probe, **and the Class-1 materialize write (`INSERT … SELECT`)**.

**No budgeted statement SHALL gate an unbudgeted one on the Class-1 path.** Where one statement exists only to fix the window
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
has an unjoined, genuinely cheap probe and does its entire enrichment read inside the exempt write. Under
node-wide memory pressure the single largest consumer on the ClickHouse node can be exactly that statement —
a Class-1 materialize write in the hundreds of MiB running for tens of seconds, while the node sits at its
server memory limit shedding queries. A pipeline that declares a `filter` is refused at a two-column probe;
one that declares none is never bounded at all.

The ceiling on a write that narrows SHALL bound the **call**, not each attempt: every retry SHALL run on
what remains of the budget's wall-clock ceiling after the attempts before it, and a budget with nothing
usable left SHALL surface the refusal rather than begin an attempt it cannot finish. A narrowing sequence
bounded per attempt would occupy a multiple of the configured time and outlast the caller's own read
timeout; the server would then finish into a closed socket, which the caller reports as a transport failure
and retries — the livelock a deterministic refusal exists to replace.

#### Scenario: A narrowing sequence does not outlast the budget it runs under

- **GIVEN** a Class-1 materialize whose write is refused by the resource budget
- **WHEN** it narrows and retries
- **THEN** each attempt runs under the time remaining from the call's own ceiling
- **AND** once that ceiling is spent the refusal is reported rather than another attempt started

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

