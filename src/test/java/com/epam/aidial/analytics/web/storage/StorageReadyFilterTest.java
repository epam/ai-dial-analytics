package com.epam.aidial.analytics.web.storage;

import com.epam.aidial.analytics.clickhouse.replication.StorageReadyGate;
import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** {@code /v1} answers {@code 503 clickhouse_unavailable} until the storage phase opens the gate. */
class StorageReadyFilterTest {

    private final StorageReadyGate gate = new StorageReadyGate();
    private final StorageReadyFilter filter = new StorageReadyFilter(gate, new JsonMapperConfiguration().jsonMapper());

    @Test
    void refusesBeforeTheGateOpensWithTheStableErrorView() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/queries/execute");
        request.setServletPath("/v1/queries/execute");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"error\":\"clickhouse_unavailable\"")
                .contains("/v1/queries/execute");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void passesOnceTheGateIsOpen() throws Exception {
        gate.open();
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/queries/execute");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
