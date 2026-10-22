package com.epam.aidial.analytics.service.pipeline.preview;

import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The newest sampled position is the greatest cursor tuple, and is past a bound by the tuple, not the version. */
class SampledPositionTest {

    private static Map<String, Object> row(Long version, String identity) {
        final Map<String, Object> row = new HashMap<>();
        row.put("v", version);
        row.put("id", identity);
        return row;
    }

    @Test
    void newestIsTheGreatestTupleAndIgnoresRowsWithoutVersion() {
        final SampledPosition newest = SampledPosition.newest(
                List.of(row(10L, "b"), row(10L, "c"), row(9L, "z"), row(null, "zz")), "v", "id");

        assertThat(newest).isEqualTo(new SampledPosition(10L, "c"));
        assertThat(SampledPosition.newest(List.of(), "v", "id")).isNull();
    }

    @Test
    void rowAtTheBoundsVersionIsPastItByIdentity() {
        final EnrichmentFreshness.Bound bound = new EnrichmentFreshness.Bound("e", 10L, "m");

        assertThat(new SampledPosition(10L, "z").isPast(bound)).isTrue();
        assertThat(new SampledPosition(10L, "m").isPast(bound)).isFalse();
        assertThat(new SampledPosition(9L, "z").isPast(bound)).isFalse();
        assertThat(new SampledPosition(99L, "z").isPast(new EnrichmentFreshness.Bound("e", null, null))).isFalse();
    }
}
