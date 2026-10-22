package com.epam.aidial.analytics.service.pipeline.preview;

import com.epam.aidial.analytics.service.enrichment.support.CursorPredicates;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;

import java.util.List;
import java.util.Map;

/**
 * The newest input position a sample read — the cursor tuple {@code (version, identity)} of its newest row —
 * for comparing with an enrichment's freshness bound.
 *
 * <p>The whole tuple, not the version alone: the clamp bounds by {@code (version, identity)}, so a row at the
 * bound's own version but past it by identity is past the bound, and reads the enrichment as unmatched like any
 * other.
 */
public record SampledPosition(long version, String identity) {

    /**
     * The newest position among {@code rows}, read from the helper columns {@code versionKey} and
     * {@code identityKey}; null when no row carries a version.
     */
    public static SampledPosition newest(List<Map<String, Object>> rows, String versionKey, String identityKey) {
        SampledPosition newest = null;
        for (final Map<String, Object> row : rows) {
            if (!(row.get(versionKey) instanceof Number version)) {
                continue;
            }
            final Object identity = row.get(identityKey);
            final SampledPosition position =
                    new SampledPosition(version.longValue(), identity == null ? "" : identity.toString());
            if (newest == null || position.compareTo(newest) > 0) {
                newest = position;
            }
        }
        return newest;
    }

    /** Whether this position lies past {@code bound}, as the clamp orders positions; false for a bound that withholds everything. */
    public boolean isPast(EnrichmentFreshness.Bound bound) {
        return !bound.withholdsEverything()
                && CursorPredicates.compareCursors(version, identity, bound.version(), bound.identity()) > 0;
    }

    private int compareTo(SampledPosition other) {
        return CursorPredicates.compareCursors(version, identity, other.version, other.identity);
    }
}
