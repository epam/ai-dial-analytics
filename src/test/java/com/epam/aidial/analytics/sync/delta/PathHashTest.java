package com.epam.aidial.analytics.sync.delta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PathHashTest {

    private static final List<String> HISTORY = List.of("{\"content\":\"u\",\"role\":\"user\"}");

    /** Absent, null and empty tools seed three different chains, and equal tools the same one. */
    @Test
    void toolsSeparateChains() {
        UUID absent = PathHash.prefixes("1:pu", "2026-01-01", null, HISTORY)[1];
        UUID nullTools = PathHash.prefixes("1:pu", "2026-01-01", "null", HISTORY)[1];
        UUID empty = PathHash.prefixes("1:pu", "2026-01-01", "[]", HISTORY)[1];

        assertThat(List.of(absent, nullTools, empty)).doesNotHaveDuplicates();
        assertThat(PathHash.prefixes("1:pu", "2026-01-01", "[]", HISTORY)[1]).isEqualTo(empty);
    }

    /** A body hash is one per scope, day and exact text. */
    @Test
    void bodyHashCoversScopeDayAndText() {
        UUID hash = PathHash.body("1:pu", "2026-01-01", "body");

        assertThat(PathHash.body("1:pu", "2026-01-01", "body")).isEqualTo(hash);
        assertThat(List.of(hash, PathHash.body("1:pv", "2026-01-01", "body"), PathHash.body("1:pu", "2026-01-02", "body"),
                PathHash.body("1:pu", "2026-01-01", "body ")).stream().distinct()).hasSize(4);
    }

    @Test
    void scopeAndDaySeparateChains() {
        UUID path = PathHash.prefixes("1:pu", "2026-01-01", null, HISTORY)[1];

        assertThat(PathHash.prefixes("1:pv", "2026-01-01", null, HISTORY)[1]).isNotEqualTo(path);
        assertThat(PathHash.prefixes("1:pu", "2026-01-02", null, HISTORY)[1]).isNotEqualTo(path);
    }
}
