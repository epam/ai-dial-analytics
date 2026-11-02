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

    @Test
    void scopeAndDaySeparateChains() {
        UUID path = PathHash.prefixes("1:pu", "2026-01-01", null, HISTORY)[1];

        assertThat(PathHash.prefixes("1:pv", "2026-01-01", null, HISTORY)[1]).isNotEqualTo(path);
        assertThat(PathHash.prefixes("1:pu", "2026-01-02", null, HISTORY)[1]).isNotEqualTo(path);
    }
}
