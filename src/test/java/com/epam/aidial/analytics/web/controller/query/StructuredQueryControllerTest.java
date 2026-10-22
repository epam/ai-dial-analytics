package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StructuredQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JsonMapperConfiguration.class)
class StructuredQueryControllerTest {

    private static final String BODY = "{\"entity\":\"dial_usage_log\",\"mode\":\"row\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StructuredQueryService queryService;

    @Test
    void returnsRowsAndTotalCount() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willReturn(new QueryResultPage(List.of("project_id", "total_tokens"),
                        List.of(Map.of("project_id", "p1", "total_tokens", 42)), 5L));

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(42))
                .andExpect(jsonPath("$.totalCount").value(5));
    }

    @Test
    void mapsQueryValidationToBadRequest() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willThrow(new QueryValidationException("unknown field 'nope'"));

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("unknown field 'nope'"));
    }

    @Test
    void mapsClickHouseQueryErrorToBadRequest() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willThrow(new ClickHouseQueryException("rejected", new IllegalStateException("type error")));

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void mapsClickHouseUnavailableToServiceUnavailable() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willThrow(new ClickHouseUnavailableException("down", new IllegalStateException("connect")));

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("clickhouse_unavailable"));
    }

    @Test
    void rejectsMissingModeWithBadRequest() throws Exception {
        // mode is required (D1); a body without it fails bean validation before reaching the service.
        mockMvc.perform(post("/v1/queries/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"dial_usage_log\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void rejectsMalformedJsonWithBadRequest() throws Exception {
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void rejectsWrongHttpMethodWithMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/v1/queries/execute"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"));
    }
}
