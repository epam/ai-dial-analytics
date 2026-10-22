## ADDED Requirements

### Requirement: Every pipeline-internal read runs under a resource budget

Every read ADAS issues on a pipeline's behalf SHALL execute under the configured `pipeline.budget.*`
ceilings, carried as ClickHouse per-query settings. The reads SHALL be the enrichment scan page, both
phases of a group fetch, the Class-1 materialize window probe, the Class-1 preview sample and the aggregate
refresh window probe. The materialize write (`INSERT … SELECT`) SHALL NOT carry them: its cost is bounded
by the window its probe already fixed, and its legitimate size is larger than any read's.

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

This requirement exists because these reads were left unbudgeted on the reasoning that each is bounded by
its own window, page size or limit. That reasoning does not hold. A group fetch's second phase reads the
declaration's bound columns for the members the selection policy kept — one, under
`member_select.limit: 1` — and where those columns live on an enrichment whose grain key prunes no
partitions, that single-row read costs gigabytes. Cost here is a property of the physical layout, not of
how many rows were asked for, so no limit a pipeline declares can bound it. Without a ceiling one such
read consumes whatever the node will give it: one group fetch can get a ClickHouse pod OOM-killed,
taking every other capability down with it. The ceiling is a blast-radius bound, not an abuse
bound — the caller is trusted, and the point is that its cost is contained rather than shared.

#### Scenario: A group fetch that exceeds the memory ceiling is refused, not served

- **Given** a group pipeline binding two heavy columns from an enrichment that cannot be partition-pruned
- **When** the runner requests the group's rows
- **Then** the response is HTTP 422 `pipeline_read_budget_exceeded`
- **And** the message names the memory ceiling and its configured value
- **And** no partial group is returned

#### Scenario: A refusal on one pipeline leaves the rest of the service serving

- **Given** a pipeline read that would exhaust the ClickHouse node's memory
- **When** it is refused by its own ceiling
- **Then** concurrent queries on the query surface continue to be served

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

Where a read carries a resource budget, the budget's own refusal SHALL take precedence over this
classification. With a ceiling attached, the ceiling is the cause and the refusal is deterministic
(422, see above); with no ceiling attached there is none to blame, so the same code means the server itself
ran out and 503 stands.

This requirement exists because a resource failure reported as a client error both mislabels the failure
and destroys work. `POST /v1/pipelines/{name}/rows` could answer `400 bad_request` — "check
field/function/type compatibility" — for rows whose every value was valid, because ClickHouse was out of
memory; the runner correctly treats a 400 as non-retryable and discards them, so enrichment rows are lost.
The statement was never wrong, and the caller was told it was.

#### Scenario: An insert refused for lack of memory is retryable

- **Given** a valid enrichment row set
- **And** ClickHouse failing the insert with `MEMORY_LIMIT_EXCEEDED`
- **When** the runner writes the rows
- **Then** the response is HTTP 503 `clickhouse_unavailable`
- **And** no message suggests the rows are malformed

#### Scenario: A genuinely malformed statement is still a rejection

- **Given** a statement naming a column the table does not have
- **When** it is executed
- **Then** the response is a 4xx statement rejection, not 503

### Requirement: The group projection keys its grain joins with the member lookup

The second phase of a group fetch SHALL hand the predicates that confine the source to the grain-join
emitter as the statement's source-only predicate set, not merely add them to the `WHERE`. The predicates
SHALL be the per-member lookup-key disjunction and the safety lag.

Those predicates are source-only by construction: the lookup key is the source's ordering key plus its
identity column, and the safety lag names its version column. The service SHALL verify that before handing
them over, and SHALL fall back to an unkeyed join if it does not hold, rather than emitting a key subquery
that would need the very join it is deciding whether to emit.

The group-key predicate SHALL NOT be among them. It resolves against the joined field when an enrichment
supplies the group key, which is the ordinary case for a group trigger, and a predicate naming an
enrichment column cannot travel into the key subquery.

This matters more in this phase than anywhere else in the service, because this is the phase that projects
the declaration's heavy columns and it projects them for a handful of rows. Unkeyed, an enrichment holding
them whose grain key is uncorrelated with its partitioning is read across every one of its parts to return
those few — on the order of 1 GiB per heavy column for a single member.

#### Scenario: The member lookup keys the joins it also filters by

- **Given** a group pipeline binding columns from an enrichment aligned with its source's partitioning
- **When** the group's rows are assembled
- **Then** the enrichment is emitted as a derived table restricted to the selected members' grain keys and
  partition values
- **And** the statement's own `WHERE` still carries the same predicates

#### Scenario: A group key supplied by an enrichment does not travel into the key subquery

- **Given** a group pipeline whose `group_by` names an enrichment column
- **When** the group's rows are assembled
- **Then** the key subquery carries the lookup key and the safety lag only
