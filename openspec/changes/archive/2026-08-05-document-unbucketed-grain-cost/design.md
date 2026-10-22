## Context

`AggregateCompiler.bucketBound` (`:162-232`) carries a long javadoc explaining why an unbounded recompute
is expensive: "ClickHouse cannot use a partition or primary-key index against a tuple of *expressions*
tested against a sub-select, so the index has nothing to work with… a rollup refreshed every few minutes
would full-scan its input every few minutes." It then narrows that cost using the bucketed column — but
only when a bucket exists. `bucketKey` (`:233`) returns null for a declaration whose `group_by` is plain
columns only, and `bucketBound` returns `List.of()`.

Until this point every pipeline was time-bucketed, so the distinction never surfaced.
`conversations_rollup` — `group_by: [{column: "chat_id"}]` over `dial_usage_log` — is the first rollup
without a bucket. It drains correctly, and `chat_id` is not a prefix of the input's
`ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)`; it is covered only by a
`bloom_filter` skip index (clickhouse `V1`), whose pruning weakens as the dirty set grows.

This change is documentation. It exists because the cost is decided by the grain, at declaration time,
and nothing told the person declaring it.

## Goals / Non-Goals

**Goals:**

- Say in the `aggregate-pipeline` spec what applies when a declaration has no time bucket, so the bucket
  bound stops reading as a universal property of refresh.
- Give an actionable rule for choosing a plain group key, and state the cost of ignoring it.
- Make the reasoning durable: record why the two obvious "fixes" are wrong, at the place where someone
  will be tempted to write one.

**Non-Goals:**

- Any change to compiled SQL, refresh behaviour, or an existing rollup.
- Introducing an operator-declared recompute bound (see Decisions).
- Measuring the real cost of the unbounded recompute — that is an ops task, tracked separately, and this
  change does not wait on it.
- Changing the grain of `conversations_rollup`, or adding an index to `dial_usage_log`.

## Decisions

**Document the gap rather than close it.** Three options were on the table: (a) grain guidance in the
spec; (b) an operator-declared recompute bound, e.g. "no key spans more than N days", rendered as a bound
on a named column; (c) measure the real cost first and accept it. This change ships (a). (c) is an ops
task that can run in parallel and would only *inform* (b).

**(b) is deferred, not rejected.** It would be a *correctness promise the service cannot verify* — exactly
the shape of the existing `version_column` monotonicity promise — so it needs both the documentation
posture of one and a reason to exist. A declaration that under-states its key span silently drops rows
from the recompute, which is the same failure mode the "in full" contract was written to prevent, only
now the caller opted into it. Worth the trade only if the measured cost demands it; there are currently
two pipelines and one drained input.

**Put the trap in the spec, not only in the code comment.** The obvious reading of "no bound here" is
"a bound is missing", and both candidate bounds are locally plausible: the window already has an
ingest-time axis, and the input already has an event-time column. Each silently drops rows — on
`_ingested_at`, a key's rows ingested before this window; on the event-time column, a key's rows whose
events fall outside the window's span — and neither leaves a trace. A future reader who only sees the
code will re-derive the wrong answer; the requirement is the durable home.

**Keep the guidance normative but SHOULD, not SHALL.** The service cannot check that a group key is a
primary-key prefix of the input — the input's `ORDER BY` is not modelled in the catalog for seeded
system tables — and a rollup with a poorly-chosen grain is expensive, not wrong. Refusing such a
declaration would block a correct rollup over a cost concern that may not apply at the deployment's data
volume.

**Three homes, one statement.** The spec requirement is the source of truth; `docs/configuration.md` gets
one sentence because that is where an operator sizing a pipeline is already reading; and
`AggregateCompiler.bucketBound`'s javadoc gets it at the `return List.of()` branch because that is where
a developer is standing when the question occurs to them. The javadoc already explains the bucketed case
at length, which is precisely why its silence on the other case misleads.

**Note the unavailable lever.** Narrowing the pipeline `filter` on `project_id` — which *is* the leading
column of `dial_usage_log`'s sort key, so it would prune both halves of the write — is the natural
suggestion, and it does not work where `project_id` is `''` on every row. The column is mapped
(from `project.id`), so this is a property of such data rather than a mapping gap, and the lever remains
valid wherever project ids are populated. Recording this stops the next person re-discovering it.

## Risks / Trade-offs

- **Guidance without enforcement can be ignored** → accepted deliberately. The alternative is refusing
  declarations the service cannot prove are expensive. The cost is visible in
  `aggregate.refresh.duration` and `aggregate.lag.seconds`, both already published per pipeline.
- **The spec now says an existing live rollup has a cost characteristic nobody has measured** →
  mitigated by stating it as a property of the shape rather than as a defect, and by leaving the
  measurement to option (c). `conversations_rollup` is correct today and this change does not alter it.
- **Someone reads "no prunable bound" as a bug to fix** → this is the specific risk the trap paragraph
  and the new scenario exist to close, in the requirement rather than only in a comment.
