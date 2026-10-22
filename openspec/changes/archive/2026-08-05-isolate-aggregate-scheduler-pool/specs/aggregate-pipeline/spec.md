## MODIFIED Requirements

### Requirement: Scheduled refreshes execute under a system identity

A scheduled refresh SHALL execute under an internal system identity entitled to read `sensitive` columns.
Column-level governance is decided from the acting identity, and a scheduler tick has no caller: without
this, a pipeline a `FULL_ADMIN` registered over a `sensitive` column would validate, preview, and refresh
on demand, yet be refused on every tick — permanently, and only where security is enabled. Scheduled
execution has to behave like the on-demand execution it automates.

The elevation SHALL be scoped to one pipeline's refresh and SHALL be unwound afterwards, including when the
refresh fails. A tick refreshes many pipelines on one thread, so an elevation left in place would silently
grant it to whichever pipeline ran next. The exposure the elevation would otherwise create is closed at
declaration time by the sensitive-target-column rule above, not at execution.

The aggregate tick SHALL run on a task scheduler of its own, separate from the one the service's ingestion
jobs use, and its size SHALL be configurable. Refreshes run serially inside a tick, so a slow recompute
does not merely lengthen the tick — on a shared pool it occupies a thread that ingestion also needs. The
two have different consequences when late: a delayed rollup serves a stale number, while delayed ingestion
loses ground against its source, so they SHALL NOT compete for the same budget. Isolation SHALL be the only
effect: refreshes remain serial within a tick, and ticks SHALL NOT overlap by default.

Introducing the dedicated scheduler SHALL NOT disturb the scheduler the ingestion jobs already use, nor the
configuration that sizes it.

#### Scenario: A pipeline over a sensitive column refreshes on schedule

- **WHEN** an enabled pipeline reads a `sensitive` input column into a `sensitive` target column and the
  scheduler tick fires
- **THEN** the refresh executes, rather than being refused for want of a caller

#### Scenario: The elevation does not outlive the refresh

- **WHEN** a scheduled refresh completes or fails
- **THEN** the acting identity in effect afterwards is the one that was in effect before it

#### Scenario: A slow refresh does not delay ingestion

- **WHEN** an aggregate refresh occupies its scheduler for longer than the ingestion jobs' interval
- **THEN** the scheduled poll and the daily sync still run on their own schedule, unblocked by it

#### Scenario: The ingestion scheduler keeps its configured size

- **WHEN** the service starts with the scheduled-task pool size configured
- **THEN** the ingestion jobs' scheduler is sized by that configuration, unaffected by the aggregate
  scheduler's presence
