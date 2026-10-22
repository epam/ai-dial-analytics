package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.service.query.sql.SqlMetadataResult;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.SqlTranslation;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SqlQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JsonMapperConfiguration.class, SqlStatementRunner.class})
class SqlQueryControllerTest {

    private static final String BODY = "{\"sql\":\"SELECT project_id FROM dial_usage_log\"}";

    private static final StructuredQuery TRANSLATED =
            new StructuredQuery("dial_usage_log", null, QueryMode.ROW, false, null, null, null, null, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SqlQueryFrontEnd sqlQueryFrontEnd;

    @MockitoBean
    private StructuredQueryService queryService;

    @Test
    void returnsRowsWithNullTotalCount() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.plan("SELECT project_id FROM dial_usage_log"))
                .willReturn(new SqlTranslation(TRANSLATED, List.of(), false));
        final Map<String, Object> row = new HashMap<>();
        row.put("project_id", "p1");
        row.put("deployment_ref.display_name", null);
        BDDMockito.given(queryService.execute(TRANSLATED)).willReturn(new QueryResultPage(List.copyOf(row.keySet()), List.of(row), null));

        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"))
                .andExpect(jsonPath("$.rows[0].['deployment_ref.display_name']").value((Object) null))
                // NON_NULL value inclusion omits a null totalCount from the JSON (matching the DSL endpoint).
                .andExpect(jsonPath("$.totalCount").doesNotExist());
    }

    @Test
    void mapsSqlTranslationErrorToBadRequest() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.plan(ArgumentMatchers.anyString()))
                .willThrow(new SqlTranslationException("unsupported SQL construct: JOIN"));

        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("unsupported SQL construct: JOIN"));

        BDDMockito.verifyNoInteractions(queryService);
    }

    @Test
    void mapsQueryValidationToBadRequest() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.plan(ArgumentMatchers.anyString()))
                .willReturn(new SqlTranslation(TRANSLATED, List.of(), false));
        BDDMockito.given(queryService.execute(TRANSLATED))
                .willThrow(new QueryValidationException("limit 50000 exceeds the maximum of 1000"));

        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("limit 50000 exceeds the maximum of 1000"));
    }

    @Test
    void mapsClickHouseUnavailableToServiceUnavailable() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.plan(ArgumentMatchers.anyString()))
                .willReturn(new SqlTranslation(TRANSLATED, List.of(), false));
        BDDMockito.given(queryService.execute(TRANSLATED))
                .willThrow(new ClickHouseUnavailableException("down", new IllegalStateException("connect")));

        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("clickhouse_unavailable"));
    }

    @Test
    void rejectsBlankSqlWithBadRequest() throws Exception {
        mockMvc.perform(post("/v1/queries/execute-sql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        BDDMockito.verifyNoInteractions(sqlQueryFrontEnd, queryService);
    }

    @Test
    void rejectsMissingSqlWithBadRequest() throws Exception {
        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        BDDMockito.verifyNoInteractions(sqlQueryFrontEnd, queryService);
    }

    @Test
    void rejectsMalformedJsonWithBadRequest() throws Exception {
        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void returnsCatalogRowsWithoutExecutingQuery() throws Exception {
        final Map<String, Object> row = new HashMap<>();
        row.put("COLUMN_NAME", "event_kind");
        row.put("DATA_TYPE", "CHARACTER VARYING");
        BDDMockito.given(sqlQueryFrontEnd.plan(ArgumentMatchers.anyString()))
                .willReturn(new SqlMetadataResult(
                        List.of(new ResultColumn("COLUMN_NAME", ResultColumnKind.STRING),
                                new ResultColumn("DATA_TYPE", ResultColumnKind.STRING)),
                        List.of(row)));

        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].COLUMN_NAME").value("event_kind"))
                .andExpect(jsonPath("$.rows[0].DATA_TYPE").value("CHARACTER VARYING"))
                .andExpect(jsonPath("$.totalCount").doesNotExist());

        // The catalog answered from ADAS's own metadata; nothing was sent to ClickHouse.
        BDDMockito.verifyNoInteractions(queryService);
    }
}
