## MODIFIED Requirements

### Requirement: Per-day failure isolation

A failure to ingest a single day's data (e.g. the store is unreachable for that day's listing) MUST
NOT abort the remaining days of the backfill. The service SHALL count the failed day, continue with
the subsequent days, and report the count of failed days in the run status. A run that completes with
one or more failed days SHALL still reach a terminal state, and the status SHALL make the failed-day
count observable so the operator can re-run the affected range.

Exhaustion of ClickHouse's disk space is the one exception, because it is not isolated to a day: every
remaining day would fail the same way, and attempting them adds load and partial writes to a store that
is already out of space. When a day fails because ClickHouse refused the write for lack of disk space,
the service SHALL stop the run immediately, leaving the remaining days unattempted, and SHALL reach the
failed terminal state even if earlier days succeeded — so a run that stopped early is distinguishable
from one that covered its whole range, whose state is `COMPLETED` however many individual days failed.
The status SHALL continue to report the range total alongside the completed and failed day counts, so
the unattempted remainder is derivable from it; the reason for stopping SHALL be reported in logs and
metrics rather than as a new status field.

The backfill capability SHALL remain enabled — the operator can re-trigger the same range once space is
available, and a re-trigger that still hits an exhausted disk SHALL again stop at its first failing day
rather than working through the range.

#### Scenario: A failing day does not abort the range

- **WHEN** listing or ingesting one day in the range fails while other days succeed
- **THEN** the service counts that day as failed, ingests the remaining days, and reaches a terminal
  state reporting the failed-day count

#### Scenario: Empty day completes cleanly

- **WHEN** a day in the range resolves to a prefix containing no blobs
- **THEN** that day completes successfully having ingested zero rows, and the backfill continues

#### Scenario: Disk exhaustion stops the remaining days

- **WHEN** a day in the range fails because ClickHouse refused the batch insert for lack of disk space
  and days remain unprocessed
- **THEN** the run stops without attempting the remaining days and reaches the failed terminal state,
  with the completed and failed day counts summing to less than the range total

#### Scenario: An early stop is distinguishable from a completed range

- **WHEN** one run stops on disk exhaustion after some days succeeded and another run covers its whole
  range with one day failed for an unrelated reason
- **THEN** the first reports the failed terminal state and the second reports the completed terminal
  state, so the two outcomes are not conflated

#### Scenario: The range can be re-triggered after space is restored

- **WHEN** the operator re-triggers a range that a previous run stopped on disk exhaustion
- **THEN** the trigger is accepted (the capability is not disabled by the earlier failure) and the run
  proceeds through the range, stopping again at its first failing day only if the disk is still
  exhausted
