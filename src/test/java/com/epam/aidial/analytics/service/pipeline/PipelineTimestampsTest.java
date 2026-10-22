package com.epam.aidial.analytics.service.pipeline;

import org.junit.jupiter.api.RepeatedTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the precision contract behind {@link PipelineTimestamps}: a minted timestamp must survive a Postgres
 * {@code TIMESTAMPTZ} round-trip unchanged, or a write response reports an {@code updated_at} the sync feed's
 * {@code updated_since >=} filter cannot match. Repeated because the defect only shows on a clock that offers
 * sub-microsecond ticks (Linux does, macOS does not), so a single sample proves little on a developer machine.
 */
class PipelineTimestampsTest {

    @RepeatedTest(50)
    void mintsMicrosecondPrecisionOnly() {
        Instant now = PipelineTimestamps.now();

        assertThat(now.getNano() % 1_000)
                .describedAs("sub-microsecond component of %s, which Postgres would round away", now)
                .isZero();
    }
}
