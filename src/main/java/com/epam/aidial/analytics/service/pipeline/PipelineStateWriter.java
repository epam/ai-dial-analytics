package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Transactional writer for a pipeline's runtime state, kept as its own bean so its
 * {@code @Transactional} boundaries apply (a self-invoked {@code @Transactional} method on the
 * orchestrating service would bypass the Spring proxy) — the same split
 * {@code CatalogSchemaWriter} uses for the table-management write flows.
 *
 * <p>The cursor advances in its own committed transaction <b>after</b> the ClickHouse write returns, and
 * a failure records the error without advancing it. That ordering is the whole contract: an unadvanced
 * cursor means the next run re-executes the same window, which is safe precisely because the recompute is
 * idempotent. Advancing first would turn any write failure into permanently skipped input.
 *
 * <p><b>Nothing here stamps {@code updated_at}, deliberately.</b> Every field these methods write is
 * {@code @NotAudited} on {@link PipelineEntity} so that a tick creates no Envers revision — a state write is
 * not a control-plane change and has no place in the audit trail. Stamping {@code updated_at}, which is
 * audited, defeated exactly that: a scheduled pipeline wrote two revisions per fire (one per
 * {@code REQUIRES_NEW} transaction below) whose audited columns were byte-identical, burying real
 * declaration changes in the activity feed. {@code updated_at} therefore means <em>the declaration last
 * changed</em>, which is also what the runner's sync feed needs it to mean — the same reasoning
 * {@code PipelineRepository.advanceMaterializedThrough} already documents for the position write. Run state
 * has its own timestamps: {@code last_run_at} and {@code next_run_at}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineStateWriter {

    private final PipelineRepository pipelines;

    /**
     * Records a successful run: advances the cursor when the run consumed a window (a no-op run passes
     * nulls and leaves it untouched), records whether input remains, records what held the window back,
     * stamps {@code last_run_at}, and clears any prior error.
     *
     * @param clamp the enrichment position this run's window was held at, or null when nothing held it.
     *     <b>Always written, including the null</b> — a stale clamp left in place from an earlier run would
     *     report a rollup as held back after the enrichment caught up, which is worse than not reporting it
     *     at all: the indicator has to mean something whenever it is present
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String name, Long cursorVersion, String cursorIdentity, long rowsWritten,
            boolean hasMore, EnrichmentFreshness.Bound clamp) {
        final PipelineEntity pipeline = load(name);
        if (cursorVersion != null && cursorIdentity != null) {
            pipeline.setCursorVersion(cursorVersion);
            pipeline.setCursorIdentity(cursorIdentity);
        }
        pipeline.setHasMore(hasMore);
        pipeline.setClampedBy(clamp == null ? null : clamp.enrichment());
        pipeline.setClampedToVersion(clamp == null ? null : clamp.version());
        pipeline.setClampedToIdentity(clamp == null ? null : clamp.identity());
        pipeline.setLastRunAt(Instant.now());
        pipeline.setLastError(null);
        pipelines.save(pipeline);
        log.debug("Pipeline '{}' refreshed: {} row(s) written", name, rowsWritten);
    }

    /**
     * Records a failed run: stamps {@code last_run_at} and the message, leaving the cursor where it was so
     * the window is retried. Runs in its own transaction so the record survives the caller's rollback.
     *
     * <p><b>The message is stored verbatim, and a pipeline read reports it to any caller holding an
     * application role</b> — including one not entitled to the {@code sensitive} columns the refresh ran
     * over, since a scheduled refresh executes under an elevated system identity. ADAS composes no row
     * content into it, but an upstream driver exception may quote the value that failed. That residual is
     * accepted rather than overlooked (see the {@code pipeline-registry} spec, "A pipeline read carries no
     * row values"); if it is ever to be closed, close it <em>here</em>, so the value never reaches the
     * column, rather than by filtering the read per caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String name, String message) {
        final PipelineEntity pipeline = load(name);
        pipeline.setLastRunAt(Instant.now());
        pipeline.setLastError(message);
        pipelines.save(pipeline);
    }

    /** Sets the next scheduled run; null disarms the pipeline (a disabled one is never due). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setNextRunAt(String name, Instant nextRunAt) {
        final PipelineEntity pipeline = load(name);
        pipeline.setNextRunAt(nextRunAt);
        pipelines.save(pipeline);
    }

    private PipelineEntity load(String name) {
        return pipelines.findById(name)
                .orElseThrow(() -> new PipelineNotFoundException("pipeline '" + name + "' not found"));
    }
}
