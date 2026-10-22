## MODIFIED Requirements

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

Exceeding a ceiling SHALL refuse that read with HTTP 422 `pipeline_read_budget_exceeded`, whose message
SHALL name the ceiling exceeded and its configured value. It SHALL NOT be 503: the cost of one of these
reads follows the physical layout of the tables it reads, so the identical request is refused identically
until the declaration, the layout or the budget changes, and a caller doing the correct thing for a 503
would retry forever. The refusal SHALL be reported as a distinct code from `query_budget_exceeded`, because
the caller and the remedy differ — a runner cannot narrow a time range, and the remedies that apply are
reading fewer heavy columns or aligning the partitioning of the enrichment holding them.

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
