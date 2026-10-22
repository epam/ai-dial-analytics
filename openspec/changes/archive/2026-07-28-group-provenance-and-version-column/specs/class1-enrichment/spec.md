## MODIFIED Requirements

### Requirement: Probe-then-write window semantics

A materialize call SHALL choose its window **before** it executes it, in two statements. First a cheap
two-column probe SHALL select the source's `(version, identity)` tuples matching the rule's filter,
the cursor keyset, the safety-lag, and any `from`/`to` bound, ordered by that tuple and limited to
`limit`; the **last** returned tuple SHALL be the window end, and `hasMore` SHALL be true exactly when
the probe filled its limit. Second, a single `INSERT … SELECT` SHALL write the derived values for
source rows in `(cursorFrom, windowEnd]` only. Both statements SHALL read with `final=1`.

Because the window is fixed before the write, a call that times out, fails, or is retried SHALL
re-execute an **identical** window. A single ClickHouse `INSERT` is atomic and the target enrichment
is a `ReplacingMergeTree` collapsing by grain, so re-running a window SHALL be a no-op in effect: the
`final=1` result is unchanged. This is the exactly-once *effect* guarantee at window granularity, the
counterpart of the row path's per-grain guarantee.

An empty probe SHALL write nothing, return `rowsWritten: 0`, `hasMore: false`, and echo the input
cursor unchanged, so a caller that advances only on success never skips a window.

The write SHALL stamp the provenance columns the target physically carries — `evaluator_version` with
the rule's resolved pinned version and `enriched_at` with the write time — and SHALL skip any provenance
column the target lacks, matching the write-back path's best-effort lineage. It SHALL also skip every
provenance column a Class-1 rule cannot describe, each for a structural reason rather than a missing
value: `model` (a `sql` evaluator makes no model call), `group_version` and `truncated` (governance
forbids a `sql` evaluator the `group` trigger, and a set-based window is never trimmed). The version
column `_updated_at` SHALL be stamped by ClickHouse, never by ADAS.

#### Scenario: Re-running an identical call leaves the result unchanged

- **WHEN** the same materialize call is issued twice against an unchanged source
- **THEN** the second call re-executes the same window and a subsequent `final=1` read returns the
  same values, with one row per grain key

#### Scenario: An interrupted call leaves the cursor unmoved

- **WHEN** a materialize call fails or times out
- **THEN** no cursor advance is reported to the caller, and retrying re-executes the same window

#### Scenario: An empty window echoes the cursor

- **WHEN** no source rows match above the cursor and below the safety-lag horizon
- **THEN** the response is `rowsWritten: 0`, `hasMore: false`, and `nextCursor` equal to the input cursor

#### Scenario: Provenance absent from the target is skipped, not failed

- **WHEN** the target enrichment does not physically carry `enriched_at` or `evaluator_version`
- **THEN** those columns are omitted from the write and the write still succeeds

#### Scenario: Provenance a sql rule cannot describe is skipped

- **WHEN** the target enrichment physically carries `model`, `group_version`, or `truncated`
- **THEN** those columns are omitted from the materialize write and the write still succeeds
