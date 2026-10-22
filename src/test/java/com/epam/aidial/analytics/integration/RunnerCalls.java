package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A {@code POST} to one of the four runner-only data-plane endpoints, carrying the contract header the
 * Enrichment Runner sends.
 *
 * <p>Every such call in these integration tests goes through here rather than through a bare
 * {@code post(...)}, so what the tests exercise is the shape a real runner call has. The refusals
 * themselves — a missing header, a wrong one, and the four paths they apply to — are asserted in
 * {@code PipelineDataContractTest} and {@code RunnerContractHeaderIntegrationTest}, which build their
 * requests by hand for exactly that reason.
 */
final class RunnerCalls {

    /** {@code post(url)} with a matching {@code Adas-Contract-Version}. */
    static MockHttpServletRequestBuilder runnerPost(String url) {
        return post(url).header(RunnerContract.HEADER, RunnerContract.VERSION_VALUE);
    }

    private RunnerCalls() {
    }
}
