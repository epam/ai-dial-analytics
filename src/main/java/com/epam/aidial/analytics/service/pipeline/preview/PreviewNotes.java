package com.epam.aidial.analytics.service.pipeline.preview;

import java.time.Duration;
import java.time.Instant;

/**
 * The enrichment notes every arm reports, worded once so the same condition reads the same on either kind.
 */
public final class PreviewNotes {

    private PreviewNotes() {
    }

    /** An enrichment the declaration reads has no usable freshness bound, so an enabled run would write nothing yet. */
    public static PreviewNote heldByEnrichment(String enrichment) {
        return PreviewNote.about(PreviewNoteCode.HELD_BY_ENRICHMENT, enrichment, "enrichment '" + enrichment
                + "' has recorded no usable materialized position yet, so an enabled run would write nothing "
                + "until it has one; the rows are late, not absent");
    }

    /**
     * An enrichment has a position, but every matching input row lies past it, so a clamped read — and an enabled
     * run — holds all of them back until it catches up. The gap is given, as for {@link #enrichmentBehind}, so an
     * author can tell ordinary lag from a stalled enrichment. A newest row at the position's own version is past it
     * only by the identity tiebreak, and is said so rather than as a zero gap.
     */
    public static PreviewNote heldByEnrichment(String enrichment, long newestMillis, long boundMillis) {
        return PreviewNote.about(PreviewNoteCode.HELD_BY_ENRICHMENT, enrichment, "every matching input row lies past "
                + "the materialized position of enrichment '" + enrichment + "' (" + Instant.ofEpochMilli(boundMillis)
                + "); the newest reaches " + Instant.ofEpochMilli(newestMillis) + ", "
                + (newestMillis == boundMillis
                        ? "at that position and past it only by identity"
                        : humanize(newestMillis - boundMillis) + " past it")
                + ". An enabled run would write nothing until the enrichment catches up; the rows are late, not absent");
    }

    /**
     * Matching input lies above an enrichment's freshness bound — by the given gap, or, at the bound's own version,
     * only by the identity tiebreak.
     *
     * @param sampledUnclamped whether the sample read those rows anyway (an {@code aggregate} or Class-1 sample),
     *                         so their enrichment columns read as unmatched, rather than leaving them out (a
     *                         clamped row-grain sample)
     */
    public static PreviewNote enrichmentBehind(String enrichment, long newestMillis, long boundMillis,
            boolean sampledUnclamped) {
        final String consequence = sampledUnclamped
                ? "the sample reads those rows' columns of it as unmatched (NULL, or the type default for a "
                        + "non-nullable column), and an enabled run would not write them until it catches up"
                : "those rows are left out of the sample, as an enabled run would leave them until it catches up";
        final String position = "the materialized position of enrichment '" + enrichment + "' (" + Instant.ofEpochMilli(boundMillis) + ")";
        final String distance = newestMillis == boundMillis
                ? "at " + position + " and past it only by identity"
                : humanize(newestMillis - boundMillis) + " past " + position;
        return PreviewNote.about(PreviewNoteCode.ENRICHMENT_BEHIND, enrichment, "matching input reaches "
                + Instant.ofEpochMilli(newestMillis) + ", " + distance + "; " + consequence);
    }

    /** {@code millis} as hours, minutes and seconds — the gap an author compares with the enrichment's cadence. */
    static String humanize(long millis) {
        final Duration gap = Duration.ofMillis(Math.max(0L, millis));
        if (gap.toSeconds() < 1) {
            return gap.toMillis() + "ms";
        }
        final StringBuilder text = new StringBuilder();
        if (gap.toDays() > 0) {
            text.append(gap.toDays()).append("d ");
        }
        if (gap.toHoursPart() > 0) {
            text.append(gap.toHoursPart()).append("h ");
        }
        if (gap.toMinutesPart() > 0) {
            text.append(gap.toMinutesPart()).append("m ");
        }
        if (gap.toSecondsPart() > 0) {
            text.append(gap.toSecondsPart()).append('s');
        }
        return text.toString().trim();
    }
}
