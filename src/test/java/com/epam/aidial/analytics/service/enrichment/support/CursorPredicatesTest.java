package com.epam.aidial.analytics.service.enrichment.support;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partition floor is what lets a cursor-bounded read prune; these pin where it is rendered and, as
 * importantly, where it is not — an unpartitioned source must render the statement it rendered before the
 * floor existed, and the upper bounds never carry one.
 */
class CursorPredicatesTest {

    private static final DSLContext CTX = DSL.using(SQLDialect.DEFAULT);
    private static final long V = 1_791_531_540_399L;
    private static final String FLOOR = "\"dial_usage_log\".\"request_time\" >= (select min(\"request_time\") from"
            + " \"dial_usage_log\" where \"_ingested_at\" >= fromUnixTimestamp64Milli(" + V + ") settings final=0)";

    private static final CursorColumns PARTITIONED =
            new CursorColumns("_ingested_at", "event_id", "dial_usage_log", "request_time");
    private static final CursorColumns UNPARTITIONED = new CursorColumns("_ingested_at", "event_id");

    private static String sql(Condition condition) {
        return CTX.renderInlined(condition);
    }

    private static String sql(List<Condition> conditions) {
        return CTX.renderInlined(DSL.and(conditions));
    }

    @Test
    void seekOnPartitionedSourceCarriesTheFloor() {
        assertThat(sql(CursorPredicates.seekPast(PARTITIONED, V, "id")))
                .contains("(toUnixTimestamp64Milli(\"_ingested_at\"), toString(\"event_id\")) > (" + V + ", 'id')")
                .contains(FLOOR);
    }

    @Test
    void seekFromDecodedCursorCarriesTheFloor() {
        assertThat(sql(CursorPredicates.windowAndSeek(PARTITIONED, null, null, List.of(Long.toString(V), "id"))))
                .contains("(toUnixTimestamp64Milli(\"_ingested_at\"), toString(\"event_id\")) > (" + V + ", 'id')")
                .contains(FLOOR);
    }

    /** Both lower bounds admit only rows past the later of the two, so one floor there is exact and enough. */
    @Test
    void windowAndCursorTogetherCarryOneFloorAtTheLaterBound() {
        final Instant earlierFrom = Instant.ofEpochMilli(V - 86_400_000L);
        final String bothBounds = sql(CursorPredicates.windowAndSeek(PARTITIONED, earlierFrom, null, List.of(Long.toString(V), "id")));
        assertThat(bothBounds).contains(FLOOR);
        assertThat(bothBounds.split("min\\(", -1)).hasSize(2);

        final Instant laterFrom = Instant.ofEpochMilli(V + 1);
        final String laterWindow = sql(CursorPredicates.windowAndSeek(PARTITIONED, laterFrom, null, List.of(Long.toString(V), "id")));
        assertThat(laterWindow).contains("fromUnixTimestamp64Milli(" + (V + 1) + ") settings final=0)");
        assertThat(laterWindow.split("min\\(", -1)).hasSize(2);
    }

    @Test
    void windowLowerBoundCarriesTheFloorButUpperBoundDoesNot() {
        assertThat(sql(CursorPredicates.window(PARTITIONED, Instant.ofEpochMilli(V), null))).contains(FLOOR);
        assertThat(sql(CursorPredicates.window(PARTITIONED, null, Instant.ofEpochMilli(V)))).doesNotContain("min(");
    }

    @Test
    void upperBoundAndSafetyLagCarryNoFloor() {
        assertThat(sql(CursorPredicates.atMost(PARTITIONED, V, "id"))).doesNotContain("min(");
        assertThat(sql(CursorPredicates.safetyLag(PARTITIONED, Duration.ofSeconds(5)))).doesNotContain("min(");
    }

    /**
     * The Class-1 preview's spelling: every reference to the version column qualified with the source, so the
     * sample's WHERE agrees with its select list and order rather than leaning on ClickHouse's left-table preference.
     * The partition floor's inner references stay bare — its own FROM is their only scope.
     */
    @Test
    void qualifiedWindowAndSafetyLagNameTheSourcesVersionColumn() {
        assertThat(sql(CursorPredicates.qualifiedSafetyLag(PARTITIONED, Duration.ofSeconds(5))))
                .isEqualTo("(\"dial_usage_log\".\"_ingested_at\" <= now64(3) - toIntervalMillisecond(5000))");
        assertThat(sql(CursorPredicates.qualifiedWindow(PARTITIONED, Instant.ofEpochMilli(V), Instant.ofEpochMilli(V + 1))))
                .isEqualTo("((\"dial_usage_log\".\"_ingested_at\" > fromUnixTimestamp64Milli(" + V + ")) and "
                        + "(\"dial_usage_log\".\"_ingested_at\" <= fromUnixTimestamp64Milli(" + (V + 1) + ")) and (" + FLOOR + "))");
        // The unqualified forms the runner's statements render are unchanged.
        assertThat(sql(CursorPredicates.safetyLag(PARTITIONED, Duration.ofSeconds(5))))
                .isEqualTo("(\"_ingested_at\" <= now64(3) - toIntervalMillisecond(5000))");
    }

    @Test
    void unpartitionedSourceRendersExactlyTheBareSeek() {
        assertThat(sql(CursorPredicates.seekPast(UNPARTITIONED, V, "id")))
                .isEqualTo("((toUnixTimestamp64Milli(\"_ingested_at\"), toString(\"event_id\")) > (" + V + ", 'id'))");
        assertThat(sql(CursorPredicates.window(UNPARTITIONED, Instant.ofEpochMilli(V), null)))
                .isEqualTo("(\"_ingested_at\" > fromUnixTimestamp64Milli(" + V + "))");
        assertThat(sql(CursorPredicates.windowAndSeek(UNPARTITIONED, null, null, List.of(Long.toString(V), "id"))))
                .isEqualTo("((toUnixTimestamp64Milli(\"_ingested_at\"), toString(\"event_id\")) > (" + V + ", 'id'))");
    }
}
