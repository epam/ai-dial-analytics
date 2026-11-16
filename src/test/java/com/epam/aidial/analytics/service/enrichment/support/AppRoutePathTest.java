package com.epam.aidial.analytics.service.enrichment.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AppRoutePathTest {

    @ParameterizedTest
    @ValueSource(strings = {"insights", "v1/insights", "a.b/c_d/e-f/~g", "Insights"})
    void wellFormedRoutesAreAccepted(String route) {
        assertThat(AppRoutePath.problem(route)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "../admin", "a/./b", "a/..", "/insights", "insights/", "a//b", "insights?x=1",
        "insights#top", "%2e%2e/admin", "in sights", "naïve"})
    void malformedRoutesAreRefused(String route) {
        assertThat(AppRoutePath.problem(route)).isPresent();
    }

    @Test
    void lengthIsBoundedAt256() {
        assertThat(AppRoutePath.problem("a".repeat(256))).isEmpty();
        assertThat(AppRoutePath.problem("a".repeat(257))).hasValueSatisfying(p -> assertThat(p).contains("256"));
    }
}
