package com.epam.aidial.analytics.service.pipeline;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Mints the {@code created_at}/{@code updated_at} values the pipeline registry stores.
 *
 * <p>At microsecond precision, because that is what {@code TIMESTAMPTZ} holds: a raw
 * {@link Instant#now()} carries nanoseconds on a platform whose clock offers them (Linux does, macOS
 * does not), and Postgres rounds them on the way in. The rounded value is what a later read returns,
 * so an unrounded one makes a create/update response report a timestamp that was never stored.
 *
 * <p>That matters because {@code updated_at} is the sync feed's watermark: {@code GET /v1/pipelines} selects
 * {@code updated_at >= updated_since}, so a caller that takes {@code updated_at} from a write response and
 * passes it back would silently skip the pipeline whenever the stored value rounded <em>down</em> past it.
 *
 * <p>Lives beside the registry it stamps rather than in {@code service.enrichment.support}, where the
 * enrichment control plane left it: both writers of these columns are registry writes
 * ({@link PipelineService} and {@code PipelineGenerationService}), so the deciding argument is the data it
 * describes — the same one that moved {@link EnrichmentFreshness} here.
 */
public final class PipelineTimestamps {

    private PipelineTimestamps() {
    }

    /** The current instant, truncated to the precision the registry can store. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
