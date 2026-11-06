package com.epam.aidial.analytics.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The failure-ledger message of a blob's skipped records: counts per cause, first line numbers, never content. */
class SkippedRecordsTest {

    @Test
    void describesEachCauseWithItsCountAndLines() {
        SkippedRecords skips = new SkippedRecords();
        skips.add(SkippedRecords.Cause.MALFORMED_LINE, 14);
        skips.add(SkippedRecords.Cause.MISSING_DEDUP_KEY, 203);
        skips.add(SkippedRecords.Cause.MALFORMED_LINE, 87);

        assertThat(skips.describe()).isEqualTo("malformed_line: 2 (lines 14, 87); missing_dedup_key: 1 (line 203)");
        assertThat(skips.count(SkippedRecords.Cause.MALFORMED_LINE)).isEqualTo(2);
        assertThat(skips.count(SkippedRecords.Cause.OVERSIZED_LINE)).isZero();
    }

    @Test
    void keepsOnlyTheFirstLinesButCountsEvery() {
        SkippedRecords skips = new SkippedRecords();
        for (long line = 1; line <= 1_000; line++) {
            skips.add(SkippedRecords.Cause.OVERSIZED_LINE, line);
        }

        assertThat(skips.lines(SkippedRecords.Cause.OVERSIZED_LINE)).hasSize(SkippedRecords.LINES_KEPT).startsWith(1L, 2L);
        assertThat(skips.describe()).startsWith("oversized_line: 1000 (lines 1, 2, 3").endsWith("10)");
    }

    @Test
    void cutsLineNumbersBeforeCountsWhenTheMessageWouldBeTooLong() {
        SkippedRecords skips = new SkippedRecords();
        long huge = 1_000_000_000_000_000L;
        for (int i = 0; i < SkippedRecords.LINES_KEPT; i++) {
            skips.add(SkippedRecords.Cause.MALFORMED_LINE, huge + i);
            skips.add(SkippedRecords.Cause.OVERSIZED_LINE, huge + i);
            skips.add(SkippedRecords.Cause.MISSING_DEDUP_KEY, huge + i);
        }

        String message = skips.describe();

        assertThat(message).hasSizeLessThanOrEqualTo(IngestFailureEvent.MAX_MESSAGE_LENGTH)
                .contains("malformed_line: 10", "oversized_line: 10", "missing_dedup_key: 10");
    }

    @Test
    void isEmptyUntilSomethingIsSkipped() {
        SkippedRecords skips = new SkippedRecords();

        assertThat(skips.isEmpty()).isTrue();
        assertThat(skips.describe()).isEmpty();
    }
}
