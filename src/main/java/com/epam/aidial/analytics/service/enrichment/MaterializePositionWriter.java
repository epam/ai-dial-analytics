package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.observability.CursorPosition;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records what a Class-1 materialize did, against the ENRICH pipeline it ran for: the position it wrote
 * through, or that the pipeline was found drained, or that its history was re-derived. The first and the last can coincide
 * — a full replay both writes a window and rewrites values a consumer may already have read — which is how
 * a backfill that carries no window on the wire is still detected; see {@link #recordMaterialized}.
 *
 * <p>Its own bean with its own {@code @Transactional} boundary, for the reason {@code PipelineStateWriter}
 * is: a self-invoked {@code @Transactional} method on {@code MaterializeService} would bypass the Spring
 * proxy and silently run without one. {@code REQUIRES_NEW} because the record must commit independently of
 * whatever the caller is doing — and because the fact being recorded is already true. The ClickHouse write
 * has returned; those rows exist whether or not this bookkeeping commits.
 *
 * <p>Which is also why <b>the caller swallows a failure here</b> rather than these methods doing it: the
 * write succeeded and the caller's cursor is about to advance past a window that really was written, so
 * answering with an error would make the runner re-execute it for the sake of a watermark. A lost record
 * costs one enrichment cadence of clamp precision — a dependent rollup holds its window slightly longer
 * than necessary, the safe direction. The catch belongs <em>outside</em> the transaction: swallowing inside
 * one that has been marked rollback-only only defers the failure to commit.
 *
 * <p><b>Which branch records what is the whole correctness story</b>, and it is easy to get backwards:
 *
 * <ul>
 *   <li>a window was probed and written → the position, taken from the probed window's end tuple;</li>
 *   <li>the probe found nothing → the drained instant, and <b>not</b> a position. The empty branch echoes
 *       the <em>caller's</em> cursor by contract, so recording that would persist what the runner asserted
 *       rather than what ADAS observed;</li>
 *   <li>the call carried {@code from}/{@code to} → the re-derivation, and neither of the other two. A
 *       backfill rewrites values consumers may already have read, which is a different fact from progress,
 *       and its position would drag the mark backwards.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaterializePositionWriter {

    private final PipelineRepository pipelines;

    /**
     * Records the position a written window reached. Monotonic: a replay at an earlier cursor re-executes an
     * already-written range and must not lower the mark.
     *
     * <p><b>And records a re-derivation when the window did not carry the pipeline forward.</b> A backfill
     * that names a {@code from}/{@code to} window is recognised by the caller, but a <em>full replay</em>
     * carries a cursor and no window, so it arrives looking exactly like a live tick — and it is the default
     * shape of the runner's own backfill trigger, and the way a transform-expression bump is re-applied to
     * history. What separates the two is the effect rather than the request: a live tick writes rows the
     * pipeline has never been complete through; a replay rewrites rows it has. The caller decides that,
     * through {@link EnrichmentFreshness#reDerivesHistory}, against the pipeline as it stood before this
     * call — see {@code reDerived}.
     *
     * @param generation the pipeline's generation as read at the start of this materialize, which the record
     *     is qualified by — a mark whose generation has since moved is treated as absent
     * @param reDerived whether this window rewrote rows the pipeline was already complete through. Decided
     *     by the caller and passed in rather than looked up here, and that is <b>not</b> a style choice:
     *     {@code PipelineEntity} carries {@code @JdbcTypeCode(SqlTypes.JSON)} columns, whose dirty check is
     *     unreliable, and the entity has no {@code @DynamicUpdate}. Loading one inside this transaction
     *     therefore makes the flush at commit issue a full-row {@code UPDATE} from the in-memory state,
     *     silently overwriting the very columns the statements below just set — a lost write with no error
     *     anywhere. <b>Nothing in this class may load a {@code PipelineEntity}</b>; it writes through bulk
     *     updates only, for exactly that reason
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMaterialized(
            String pipelineName, long generation, long version, String identity, boolean reDerived) {
        discardMarkOfOtherGeneration(pipelineName, generation);
        if (pipelines.advanceMaterializedThrough(pipelineName, version, identity) == 0) {
            log.debug("Pipeline '{}' already materialized at or past {}; mark unchanged",
                    pipelineName, CursorPosition.of(version, identity));
        }
        if (reDerived) {
            log.info("Pipeline '{}' rewrote a window ending at {}, at or below what it was already "
                            + "complete through; recording that its history was re-derived",
                    pipelineName, CursorPosition.of(version, identity));
            pipelines.recordHistoryRederived(pipelineName, Instant.now());
        }
    }

    /**
     * Records that the pipeline was found drained, leaving any written position in place.
     *
     * @param probedAt when the probe that found nothing ran — passed in rather than read here, because the
     *     horizon has to be the instant the probe evaluated its own {@code now64(3)} at. Reading the clock
     *     at record time would push the horizon past rows the probe never examined, which is the one
     *     direction this signal must never move
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDrained(String pipelineName, long generation, Instant probedAt) {
        discardMarkOfOtherGeneration(pipelineName, generation);
        pipelines.recordDrained(pipelineName, probedAt);
    }

    /**
     * Discards a mark stored under another generation, logging the discard when one was stored.
     *
     * <p>A discarded position holds every dependent rollup's freshness clamp until the pipeline materializes
     * again under its new generation, which is what makes it the answer to "why did the rollup freeze". A
     * pipeline's first mark ever claims a generation where none was stored; that is not a discard and is not
     * logged. The stored generation comes from the discarding statement itself, so no concurrent writer can
     * interleave between reading and replacing it.
     */
    private void discardMarkOfOtherGeneration(String pipelineName, long generation) {
        pipelines.discardMarkOfOtherGeneration(pipelineName, generation)
                .map(PipelineRepository.DiscardedMark::getStoredGeneration)
                .ifPresent(stored -> log.info("pipeline={} position discarded: stored_gen={} -> gen={}",
                        pipelineName, stored, generation));
    }

    /**
     * Records that the pipeline's history was re-derived as of now, and neither position. Not qualified by
     * generation, so {@link PipelineRepository#discardMarkOfOtherGeneration} is deliberately not called
     * here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordHistoryRederived(String pipelineName) {
        pipelines.recordHistoryRederived(pipelineName, Instant.now());
    }
}
