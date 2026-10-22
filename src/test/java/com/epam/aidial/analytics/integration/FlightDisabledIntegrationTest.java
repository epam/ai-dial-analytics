package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.query.arrow.ArrowAllocators;
import com.epam.aidial.analytics.web.flight.AdasFlightSqlProducer;
import com.epam.aidial.analytics.web.flight.FlightSqlServerLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disabled-by-default (spec "Disabled by default" scenario): with the stock
 * configuration ({@code flight.enabled=false}) none of the Flight beans exist — no gRPC listener,
 * no allocator, nothing holding resources. Reuses the default integration-test context.
 */
class FlightDisabledIntegrationTest extends AbstractClickHouseIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noFlightBeansWhenDisabled() {
        assertThat(context.getBeansOfType(FlightSqlServerLifecycle.class)).isEmpty();
        assertThat(context.getBeansOfType(AdasFlightSqlProducer.class)).isEmpty();
        assertThat(context.getBeansOfType(ArrowAllocators.class)).isEmpty();
    }
}
