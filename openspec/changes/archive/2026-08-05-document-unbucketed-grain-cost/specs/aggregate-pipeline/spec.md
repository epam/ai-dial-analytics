## MODIFIED Requirements

### Requirement: Scheduled refresh recomputes every affected group key in full

The system SHALL refresh an enabled `aggregate` pipeline on its declared cron, and on demand via
`POST /v1/pipelines/{name}/refresh`. One refresh SHALL run as two statements:

1. a **probe** that fixes the window end over the input's cursor tuple (its version and identity
   columns), applying the pipeline's `filter`, the configured safety lag, and a keyset seek past the
   stored cursor; then
2. a single `INSERT … SELECT` on the writer credential that recomputes, **in full**, every group key the
   probed window touched — the set of dirty keys being a sub-select over exactly that window — and stamps
   the target's version column.

Recomputing each affected key in full SHALL be the contract, not an optimization detail. The target
collapses by key, so a write **replaces** rather than accumulates: a partial per-window aggregate would
overwrite a bucket's running total with only the newest slice. Full recomputation of the dirty keys is
the only shape that is correct under `upsert_by_key`, and it gives two further properties:

- **Idempotence** — a retried or duplicated run re-probes the same window, derives the same key set, and
  writes the same values, which the target collapses. A refresh that fails or times out therefore leaves
  the target unchanged for that window and is safe to repeat, and concurrent runs on multiple instances
  need no coordination.
- **Late-arriving data self-heals** — a row arriving for an already-materialized bucket re-dirties that
  key, and the next refresh recomputes the whole bucket rather than adding to it.

A refresh SHALL advance the pipeline's stored cursor only after the write succeeds. A refresh whose probe
matches nothing SHALL be a no-op that leaves the cursor unmoved.

Refresh SHALL be bounded: the probe SHALL apply a configured window limit, and a run that fills its limit
SHALL report that more work remains so the next tick continues from the new cursor.

When the declaration carries a time bucket, the recompute SHALL additionally be bounded to the span of the
buckets the window dirtied, on the bucketed column itself. This narrows nothing — every row of a dirty
bucket lies inside that span, so the recomputed values are identical — but it is the only bound the engine
can prune on: a tuple of *expressions* tested against a sub-select gives the partition and primary-key
indexes nothing to work with, so without it every refresh reads the whole input. The bound SHALL be derived
and stepped inside the statement rather than marshalled through the service, so the step past the last
bucket stays in the same calendar and time zone the bucketing used.

**When the declaration carries no time bucket, there is no such bound, and the recompute reads the whole
input on every refresh.** This is a property of the grain, not a gap in the implementation. The bucket
bound exists only because a time bucket *implies* it: every row of bucket `b` has its bucketed column
inside `[b, b + one unit)`, so the dirty buckets' own span bounds the rows that can belong to them, on a
raw column an index can prune. A plain group key carries no such implication — a key's rows may lie
anywhere in the input — so the only restriction left is the dirty-key tuple `IN`, which the indexes cannot
use.

A plain group key SHOULD therefore be a primary-key prefix of the input, or carry an index that prunes
usefully at the expected dirty-set size. A key that is neither costs a full input read per refresh, and
that cost is chosen at declaration time — the only moment it can still be changed. Narrowing the
pipeline's `filter` on a column that *is* a primary-key prefix prunes both halves of the write and is the
cheapest lever where such a column is populated.

The absence of a bound SHALL NOT be closed by bounding the recompute on a time column. The recompute must
see **every** row of every dirty key, and neither candidate column carries that guarantee for a plain
key: bounding on the window's own ingest-time axis excludes a key's rows ingested before the window, and
bounding on the event-time column excludes a key's rows whose events fall outside the window's span.
Either would produce plausible measures with nothing recording the loss — precisely the failure the
"recompute every affected group key in full" contract exists to prevent.

A time bucket SHALL be aligned in **UTC**, pinned explicitly in the compiled statement rather than inherited
from the bucketed column's declared zone (which, when the column declares none, is the ClickHouse server's
— deployment configuration). A `day` bucket therefore means UTC midnight on every deployment, and the same
declaration over the same rows is reproducible across them. Leaving it to the column would make a rollup's
day boundary a property of where it runs, and a server-zone change would re-align every future bucket while
already-materialized ones kept the old boundary — two alignments in one column, indistinguishable in the
output, since both render a bucket start of `00:00:00`.

#### Scenario: A time-bucketed refresh does not read the whole input

- **WHEN** a pipeline grouping by a time bucket refreshes over a window covering a few buckets
- **THEN** the recompute is bounded to those buckets' span on the bucketed column, and the measures are the
  same as they would be unbounded

#### Scenario: A grain with no time bucket has no prunable bound

- **WHEN** a pipeline grouping only by plain columns refreshes
- **THEN** the recompute carries the dirty-key restriction alone, with no bound on any raw column, so it
  reads the whole input; the measures are correct, and the cost follows from the grain rather than from
  the size of the window

#### Scenario: Bucket alignment does not depend on the server's time zone

- **WHEN** a pipeline groups by a `day` bucket over a timestamp column that declares no time zone
- **THEN** the compiled statement pins the alignment to UTC, so each bucket starts at UTC midnight rather
  than at midnight in the ClickHouse server's zone

#### Scenario: A bucket recomputed after new rows arrive holds the total, not the increment

- **WHEN** a rollup bucket has been materialized from 10 input rows, 5 more rows land in the same
  bucket, and a refresh runs
- **THEN** the bucket's measures reflect all 15 rows

#### Scenario: Re-running a refresh changes nothing

- **WHEN** a refresh runs and then the identical refresh is run again with no new input rows
- **THEN** the target's rows and measures are unchanged

#### Scenario: A late row corrects its bucket rather than double-counting

- **WHEN** a row whose event time falls in an already-materialized bucket is ingested and a refresh runs
- **THEN** that bucket is recomputed to include the late row and no second row appears for the key

#### Scenario: A failed write leaves the cursor unmoved

- **WHEN** the `INSERT … SELECT` fails
- **THEN** the target is unchanged for that window, the stored cursor is not advanced, and the failure is
  reported without leaking ClickHouse internals

#### Scenario: An empty window is a no-op

- **WHEN** a refresh runs and the probe matches no rows
- **THEN** no write is issued and the cursor is unchanged

#### Scenario: Only due pipelines run

- **WHEN** the scheduler tick fires and an enabled pipeline's next run time has not arrived
- **THEN** that pipeline is not refreshed on this tick
