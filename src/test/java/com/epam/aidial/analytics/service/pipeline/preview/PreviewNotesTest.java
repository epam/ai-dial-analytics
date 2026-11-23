package com.epam.aidial.analytics.service.pipeline.preview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The gap an {@code enrichment_behind} note gives, so an author can tell cadence from a stalled enrichment. */
class PreviewNotesTest {

    @Test
    void humanizesTheGap() {
        assertThat(PreviewNotes.humanize(250)).isEqualTo("250ms");
        assertThat(PreviewNotes.humanize(65_000)).isEqualTo("1m 5s");
        assertThat(PreviewNotes.humanize(90_061_000)).isEqualTo("1d 1h 1m 1s");
    }

    @Test
    void anEnrichmentBehindNoteNamesTheEnrichmentAndTheGap() {
        final PreviewNote note = PreviewNotes.enrichmentBehind("usage_sentiment", 7_200_000L, 0L, false);

        assertThat(note.code()).isEqualTo(PreviewNoteCode.ENRICHMENT_BEHIND);
        assertThat(note.severity()).isEqualTo(PreviewNoteSeverity.INFO);
        assertThat(note.subject()).isEqualTo("usage_sentiment");
        assertThat(note.message()).contains("2h past the materialized position of enrichment 'usage_sentiment'").contains("left out of the sample");
    }

    @Test
    void withoutInstantsTheNoteKeepsSubjectAndGapButPrintsNoVersion() {
        final PreviewNote note = PreviewNotes.enrichmentBehind("usage_sentiment", 1_700_007_200_000L, 1_700_000_000_000L, true, false);

        assertThat(note.code()).isEqualTo(PreviewNoteCode.ENRICHMENT_BEHIND);
        assertThat(note.subject()).isEqualTo("usage_sentiment");
        assertThat(note.message()).contains("2h past the materialized position of enrichment 'usage_sentiment'")
                .doesNotContain("2023-");
        assertThat(PreviewNotes.enrichmentBehind("usage_sentiment", 5_000L, 5_000L, true, false).message())
                .contains("at the materialized position of enrichment 'usage_sentiment' and past it only by identity")
                .doesNotContain("1970-");
        assertThat(PreviewNotes.enrichmentBehind("usage_sentiment", 1_700_007_200_000L, 1_700_000_000_000L, true, true).message())
                .contains("2023-11-15T00:13:20Z, 2h past").contains("2023-11-14T22:13:20Z);");
    }

    @Test
    void heldWithPositionGivesTheGapRatherThanClaimingNoPosition() {
        final PreviewNote note = PreviewNotes.heldByEnrichment("usage_sentiment", 3_600_000L, 0L);

        assertThat(note.code()).isEqualTo(PreviewNoteCode.HELD_BY_ENRICHMENT);
        assertThat(note.message()).contains("past the materialized position of enrichment 'usage_sentiment'")
                .contains("1h past it").doesNotContain("no usable materialized position");
    }

    @Test
    void behindOnlyByIdentitySaysSoRatherThanZeroGap() {
        final PreviewNote note = PreviewNotes.enrichmentBehind("usage_sentiment", 5_000L, 5_000L, true);

        assertThat(note.message()).contains("at the materialized position of enrichment 'usage_sentiment'")
                .contains("past it only by identity").doesNotContain("0ms");
    }

    @Test
    void heldOnlyByIdentitySaysSoRatherThanZeroGap() {
        final PreviewNote note = PreviewNotes.heldByEnrichment("usage_sentiment", 5_000L, 5_000L);

        assertThat(note.message()).contains("past the materialized position of enrichment 'usage_sentiment'")
                .contains("at that position and past it only by identity").doesNotContain("0ms");
    }
}
