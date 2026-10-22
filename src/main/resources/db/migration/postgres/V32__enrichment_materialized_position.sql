-- How far ADAS has materialized each Class-1 enrichment, so an aggregate refresh that joins that
-- enrichment can hold its window at a point where the enrichment's values are actually present.
--
-- WHY THIS IS RECORDED RATHER THAN ASKED FOR. An enrichment row is written later than the source row it
-- derives from, so an unclamped refresh reads source rows whose enrichment cells are still absent, writes
-- the resulting group, and nothing ever corrects it: those source rows do not move, so no later window
-- revisits them, and the target's upsert leaves the wrong value in place. The obvious source for "how far
-- has this enrichment got" would be the scan cursor, and it is the wrong fact twice over: it lives in the
-- enrichment runner's database, and the incremental-scan contract advances it at chunk ENQUEUE, so it
-- marks what is queued for evaluation rather than what is written. But on the Class-1 (`type=sql`) path
-- ADAS is itself the writer and the write is synchronous — MaterializeService probes a window, writes it
-- with one server-side INSERT … SELECT, and is then holding the exact (version, identity) tuple it wrote
-- through, on the same axis the aggregate refresh windows on. ADAS does not need to be told its own
-- writes, so this is an observation, not a report, and no cross-service contract is added.

-- The position itself: the end tuple of the last window ADAS wrote for this rule. Advanced monotonically —
-- a replay at an earlier cursor must not lower it.
ALTER TABLE rule
    ADD COLUMN materialized_through_version  BIGINT,
    ADD COLUMN materialized_through_identity TEXT;

-- TWO FACTS, NOT ONE, and the second is what keeps the first usable. `probeWindow` applies the rule's
-- `filter_sql`, so the recorded position is the last row MATCHING the filter — and non-matching rows sit
-- ABOVE it, permanently unenriched by construction. A rule with no filter tracks its source's tail and
-- never shows this; a filtered one (conversation-insights-live filters on event_kind, request_body_bytes
-- and a request_time floor) would pin every dependent rollup at its last match indefinitely while looking
-- perfectly healthy. So the empty-probe branch records WHEN IT LOOKED instead: "nothing left matching, as
-- of now". The clamp then uses max(materialized_through, drained_at − scan safety lag), which releases the
-- window for a drained rule and still holds it for a backlogged one.
ALTER TABLE rule
    ADD COLUMN drained_at TIMESTAMPTZ;

-- A materialize carrying from/to bounds is a BACKFILL: it rewrites values for source rows consumers may
-- already have read. That is a different fact from progress — recording its position would drag the mark
-- backwards — and it is the one condition under which a dependent rollup is wrong in a way no future
-- refresh corrects, because a source row whose enrichment value moved from A to B did not move its own
-- cursor. The live lane passes no bounds, so the two are distinguishable in ADAS's own request handler and
-- the discriminator costs nothing.
--
-- Deliberately NOT qualified by `generation` below, unlike the position: a rule edit does not make an
-- already-published wrong rollup right again, so this fact must survive one.
ALTER TABLE rule
    ADD COLUMN history_rederived_at TIMESTAMPTZ;

-- The generation the position and drained horizon were recorded under; a record whose generation has since
-- moved is treated as ABSENT. `generation` is already the invalidation signal and it covers a case that is
-- easy to miss: RuleGenerationService.onVersionColumnRepointed bumps every rule reading a source whose
-- version_column was repointed, which does not make a stored (version, identity) tuple stale so much as
-- MEANINGLESS — the axis it is expressed on no longer exists. That bump is transactional with the repoint
-- by design, so keying off it is exact rather than approximate. The cost is that any accepted rule mutation
-- discards a valid mark, including one as inert as `cadence`; it is re-established on the next materialize,
-- and discarding is the safe direction.
ALTER TABLE rule
    ADD COLUMN materialized_generation BIGINT;

-- The mark lives on `rule`, not on the enrichment's table_definition, because rule deletion is a plain
-- delete while the enrichment table and its rows survive it. A mark on the table would be inherited by the
-- NEXT rule registered against it — a position that rule never earned. No index is added for the clamp's
-- lookup: `rule.target_enrichment` is already UNIQUE (one rule per enrichment), so the constraint's own
-- index serves it.

-- What the last refresh's window was actually held back by, so a rollup kept behind its input by a lagging
-- enrichment is distinguishable from a healthy one that simply had less input to read. This is a fact about
-- the LAST RUN and so cannot be derived at read time, unlike the rebuild flag and the unclamped-reads
-- report, which are facts about now. NULL means the last refresh was not clamped, which is what makes the
-- indicator mean something when it is present. Plain TEXT rather than a foreign key: it is a state
-- snapshot, and an enrichment that is later deleted must not be undeletable because a stale snapshot names
-- it.
ALTER TABLE pipeline
    ADD COLUMN clamped_by         TEXT,
    ADD COLUMN clamped_to_version BIGINT,
    ADD COLUMN clamped_to_identity TEXT;

-- The anchor the rebuild flag compares a re-derivation against: the point from which this rollup's current
-- contents were built. A re-derivation recorded AFTER this instant may have moved values the rollup had
-- already aggregated; one recorded before it cannot have, because the rollup has read everything since.
--
-- It is not `created_at`, and the difference is the whole reason for the column: re-declaring a pipeline
-- resets its cursor and makes the next run rescan its input from the beginning, which is a rebuild in
-- everything but name and must clear the flag. Both documented rebuild shapes then clear it — delete and
-- re-register, or re-declare while disabled — and no rebuild operation is added, which the capability
-- requires. Existing rows take the migration instant: no pipeline reads an enrichment column yet, so none
-- can have aggregated a re-derived value.
ALTER TABLE pipeline
    ADD COLUMN rebuilt_at TIMESTAMPTZ NOT NULL DEFAULT now();
