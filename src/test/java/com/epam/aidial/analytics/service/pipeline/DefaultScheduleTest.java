package com.epam.aidial.analytics.service.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default cron's second is pinned to fixed names, so a change to the derivation, which would silently
 * diverge the write path from {@code V44__retire_on_ingest_trigger.sql}, fails here. The expected values are
 * {@code int.from_bytes(md5(name)[:4], 'big') % 60}, computed independently.
 */
class DefaultScheduleTest {

    @Test
    void derivesTheSecondFromTheNameMd5() {
        assertThat(DefaultSchedule.secondFor("turn-feedback-live")).isEqualTo(10);
        assertThat(DefaultSchedule.secondFor("usage-client-identity-live")).isEqualTo(23);
        assertThat(DefaultSchedule.secondFor("usage-request-baggage-live")).isEqualTo(19);
        assertThat(DefaultSchedule.secondFor("session-insights-live")).isEqualTo(9);
        assertThat(DefaultSchedule.secondFor("a")).isEqualTo(57);
    }

    @Test
    void defaultCronFiresEveryMinuteAtThatSecond() {
        final String cron = DefaultSchedule.cronFor("turn-feedback-live");

        assertThat(cron).isEqualTo("10 * * * * *");
        assertThat(CronExpression.isValidExpression(cron)).isTrue();
    }
}
