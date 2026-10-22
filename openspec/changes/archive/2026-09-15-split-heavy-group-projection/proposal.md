## Why

With `bound-and-prune-pipeline-reads` shipped, a group pipeline such as `session-insights-live` can still
fail to fetch a group. The catalog now declares `dial_usage_log_payload`'s partition column, the keyed grain join
now carries `payload.request_time IN (SELECT request_time FROM dial_usage_log WHERE …)`, and a point read
of **one** heavy payload column returns its row. A point read of **two** is still refused at the 2 GiB
ceiling — which is what an evaluator binding `vars.request` → `request_body` and
`vars.response` → `assembled_response` needs.

Measured through the shipped path, on one event addressed by `event_id` plus a partition bound:

| projection | outcome |
|---|---|
| `request_body` alone | returns the row |
| `assembled_response` alone | returns the row |
| both, same row, same predicate | `422`, 2 GiB exceeded |

The two columns can differ by orders of magnitude in size for the same row, and *neither* alone
fails while the pair does. So the cost is not the bytes returned; it is a near-fixed per-heavy-column
overhead, the candidate granule each part contributes to a lookup on a hashed grain key, on the order of a
GiB per column. Two columns in one statement hold both at once and exceed a ceiling either clears alone.

Partition pruning did not remove that, and could not: `dial_usage_log` ingest emits hundreds of parts per day, so
the day a group fetch reads — always the current one, under a 30-minute idle window — is the partition
holding nearly all of the unmerged parts. Pruning to a day is pruning to the expensive day.

## What changes

The projection phase reads **one heavy column per statement** instead of all of them in one. Peak memory
becomes the largest single heavy column rather than the sum of all of them, which is the difference
between a declaration binding two of them working and not working. Inexpensive bound columns ride along
with the first read, so the common single-heavy-column declaration issues exactly the statements it does
today.

Nothing else changes: same members, same predicates, same grain joins and partition bounds per statement,
same response. A budget refusal now names the columns of the read that was refused, because with the
projection split by column that is the one thing the message could not say before.

## What this does not change

**Per-statement wall-clock budgets stay per statement.** A shared clock across the fetch would make ADAS
win the race against the runner's read timeout, and the runner classifies any 4xx as permanent: a clean
`422 pipeline_read_budget_exceeded` becomes a poison dead-letter with no stored payload, where losing the
race to a socket timeout produces a requeueable one. The recoverable loser is the one to keep until the
runner reclassifies budget refusals. Each split statement is a point read, so the sum stays far below the
ceiling in any case.

**This is a mitigation, not a structural fix.** Per-column cost scales with the part count of the
partition being read, and that count grows through the day, so this buys a 2× margin on a moving number.
The durable fix is fewer parts per point read — partitioning the payload table by hour (the catalog
already permits it: alignment compares column names and deliberately not granularity), or writing fewer,
larger parts at ingest. Both are larger changes than restoring the pipeline.
