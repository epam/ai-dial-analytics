package com.epam.aidial.analytics.service.table;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit coverage for {@link InheritedTtl#parse}: only the single-rule shape the server renders is inherited. */
class InheritedTtlTest {

    private static final String ENGINE = "MergeTree PARTITION BY toYYYYMMDD(d) ORDER BY id";

    @Test
    void dateColumnTtlIsInheritedUnwrappedWithoutDropParts() {
        final Optional<InheritedTtl> ttl = InheritedTtl.parse(
                ENGINE + " TTL d + toIntervalDay(30) SETTINGS index_granularity = 8192", "d");

        assertThat(ttl).contains(new InheritedTtl(new TableDdlRenderer.TtlDdl("d", 30, TtlUnit.DAY, false), false));
    }

    @Test
    void wrappedTimestampTtlIsInheritedWithDropParts() {
        final Optional<InheritedTtl> ttl = InheritedTtl.parse(ENGINE + " TTL toDateTime(request_time) + toIntervalYear(1)"
                + " SETTINGS index_granularity = 8192, ttl_only_drop_parts = 1", "request_time");

        assertThat(ttl).contains(new InheritedTtl(
                new TableDdlRenderer.TtlDdl("request_time", 1, TtlUnit.YEAR, true), true));
    }

    @Test
    void dateTimeColumnTtlAtTheEndOfTheClauseIsInherited() {
        final Optional<InheritedTtl> ttl = InheritedTtl.parse(ENGINE + " TTL ts + toIntervalYear(1)", "ts");

        assertThat(ttl).contains(new InheritedTtl(new TableDdlRenderer.TtlDdl("ts", 1, TtlUnit.YEAR, false), false));
    }

    @Test
    void multiRuleTtlIsNotInherited() {
        assertThat(InheritedTtl.parse(ENGINE + " TTL d + toIntervalDay(30) WHERE x = 1, d + toIntervalDay(60)"
                + " SETTINGS index_granularity = 8192", "d")).isEmpty();
        assertThat(InheritedTtl.parse(ENGINE + " TTL d + toIntervalDay(30), d + toIntervalDay(60)", "d")).isEmpty();
    }

    @Test
    void ttlOverAnotherColumnThanThePartitionColumnIsNotInherited() {
        assertThat(InheritedTtl.parse(ENGINE + " TTL expires_at + toIntervalDay(30) SETTINGS index_granularity = 8192",
                "d")).isEmpty();
    }

    @Test
    void noTtlInheritsNothing() {
        assertThat(InheritedTtl.parse(ENGINE + " SETTINGS index_granularity = 8192, ttl_only_drop_parts = 1", "d"))
                .isEmpty();
        assertThat(InheritedTtl.parse(null, "d")).isEmpty();
    }
}
