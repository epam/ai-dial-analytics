package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.QueryBudgetExceededException;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QueryCsvController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JsonMapperConfiguration.class, CsvResultWriter.class, SqlStatementRunner.class})
class QueryCsvControllerTest {

    private static final String DSL_BODY = "{\"entity\":\"dial_usage_log\",\"mode\":\"row\"}";
    private static final String SQL = "SELECT request_id, model FROM dial_usage_log";
    private static final String SQL_BODY = "{\"sql\":\"" + SQL + "\"}";
    private static final String CSV_TYPE = "text/csv;charset=UTF-8";

    private static final StructuredQuery TRANSLATED =
            new StructuredQuery("dial_usage_log", null, QueryMode.ROW, false, null, null, null, null, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StructuredQueryService queryService;

    @MockitoBean
    private SqlQueryFrontEnd sqlQueryFrontEnd;

    private static Map<String, Object> row(Object... keyValues) {
        final Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    /** A strict parse: jsonPath alone passes on malformed JSON, so an ErrorView body is proven by reading it. */
    private static JsonNode errorView(MvcResult result) throws Exception {
        return new JsonMapperConfiguration().jsonMapper()
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // --- execute-csv -------------------------------------------------------------------------------

    @Test
    void dslQueryReturnsCsvAttachmentWithTotalCountHeader() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willReturn(new QueryResultPage(List.of("project_id", "total_tokens"),
                        List.of(row("project_id", "p1", "total_tokens", 42), row("project_id", null, "total_tokens", 7)),
                        5L));

        mockMvc.perform(post("/v1/queries/execute-csv").contentType(MediaType.APPLICATION_JSON).content(DSL_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(CSV_TYPE))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"result.csv\""))
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(content().string("project_id,total_tokens\r\np1,42\r\n,7\r\n"));
    }

    @Test
    void emptyDslResultIsHeaderOnlyWithoutTotalCount() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willReturn(new QueryResultPage(List.of("request_id", "model"), List.of(), null));

        mockMvc.perform(post("/v1/queries/execute-csv").contentType(MediaType.APPLICATION_JSON).content(DSL_BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Total-Count"))
                .andExpect(content().string("request_id,model\r\n"));
    }

    @Test
    void acceptJsonStillYieldsCsv() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willReturn(new QueryResultPage(List.of("a"), List.of(row("a", "x")), null));

        mockMvc.perform(post("/v1/queries/execute-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(DSL_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(CSV_TYPE))
                .andExpect(content().string("a\r\nx\r\n"));
    }

    @Test
    void dslValidationFailureUnderAcceptCsvIsErrorViewJson() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willThrow(new QueryValidationException("unknown field 'nope'"));

        final MvcResult result = mockMvc.perform(post("/v1/queries/execute-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("text/csv")
                        .content(DSL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        final JsonNode body = errorView(result);
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("error").stringValue()).isEqualTo("bad_request");
        assertThat(body.get("message").stringValue()).isEqualTo("unknown field 'nope'");
        assertThat(body.get("method").stringValue()).isEqualTo("POST");
    }

    @Test
    void limitAboveMaximumIsRejectedLikeTheJsonEndpoint() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willThrow(new QueryValidationException("limit 50000 exceeds the maximum of 1000"));

        final MvcResult result = mockMvc.perform(post("/v1/queries/execute-csv")
                        .contentType(MediaType.APPLICATION_JSON).content(DSL_BODY))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(errorView(result).get("message").stringValue()).isEqualTo("limit 50000 exceeds the maximum of 1000");
    }

    @Test
    void budgetExhaustionUnderAcceptCsvIs422ErrorView() throws Exception {
        BDDMockito.given(queryService.execute(ArgumentMatchers.any(StructuredQuery.class)))
                .willThrow(new QueryBudgetExceededException("max_execution_time 30s exceeded", null));

        final MvcResult result = mockMvc.perform(post("/v1/queries/execute-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("text/csv")
                        .content(DSL_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(errorView(result).get("error").stringValue()).isEqualTo("query_budget_exceeded");
    }

    @Test
    void malformedDslBodyUnderAcceptCsvIsErrorViewJson() throws Exception {
        final MvcResult result = mockMvc.perform(post("/v1/queries/execute-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("text/csv")
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(errorView(result).get("error").stringValue()).isEqualTo("bad_request");
    }

    // --- execute-sql-csv ---------------------------------------------------------------------------

    @Test
    void sqlStatementReturnsCsvWithHeaderFromThePage() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.plan(SQL)).willReturn(new SqlTranslation(TRANSLATED, List.of(), false));
        BDDMockito.given(queryService.execute(TRANSLATED)).willReturn(new QueryResultPage(List.of("request_id", "model"),
                List.of(row("request_id", "r1", "model", "gpt, \"4\"")), null));

        mockMvc.perform(post("/v1/queries/execute-sql-csv").contentType(MediaType.APPLICATION_JSON).content(SQL_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(CSV_TYPE))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"result.csv\""))
                .andExpect(header().doesNotExist("X-Total-Count"))
                .andExpect(content().string("request_id,model\r\nr1,\"gpt, \"\"4\"\"\"\r\n"));
    }

    @Test
    void unnamedSqlExpressionKeepsItsDerivedHeaderName() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.plan(ArgumentMatchers.anyString()))
                .willReturn(new SqlTranslation(TRANSLATED, List.of(), false));
        BDDMockito.given(queryService.execute(TRANSLATED))
                .willReturn(new QueryResultPage(List.of("count"), List.of(row("count", 12L)), null));

        mockMvc.perform(post("/v1/queries/execute-sql-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT count(*) FROM dial_usage_log\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("count\r\n12\r\n"));
    }

    @Test
    void catalogStatementReturnsEveryRowWithoutExecuting() throws Exception {
        final List<Map<String, Object>> rows = new ArrayList<>();
        IntStream.range(0, 150).forEach(i -> rows.add(row("COLUMN_NAME", "c" + i, "ORDINAL_POSITION", i + 1)));
        BDDMockito.given(sqlQueryFrontEnd.plan(ArgumentMatchers.anyString())).willReturn(new SqlMetadataResult(
                List.of(new ResultColumn("COLUMN_NAME", ResultColumnKind.STRING),
                        new ResultColumn("ORDINAL_POSITION", ResultColumnKind.INT32)),
                rows));

        final MvcResult result = mockMvc.perform(post("/v1/queries/execute-sql-csv")
                        .contentType(MediaType.APPLICATION_JSON).content(SQL_BODY))
                .andExpect(status().isOk())
                .andReturn();

        final String[] records = result.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\r\n");
        assertThat(records).hasSize(151);
        assertThat(records[0]).isEqualTo("COLUMN_NAME,ORDINAL_POSITION");
        assertThat(records[150]).isEqualTo("c149,150");
        BDDMockito.verifyNoInteractions(queryService);
    }

    @Test
    void emptyCatalogResultIsHeaderOnly() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.plan(ArgumentMatchers.anyString())).willReturn(new SqlMetadataResult(
                List.of(new ResultColumn("TABLE_NAME", ResultColumnKind.STRING)), List.of()));

        mockMvc.perform(post("/v1/queries/execute-sql-csv").contentType(MediaType.APPLICATION_JSON).content(SQL_BODY))
                .andExpect(status().isOk())
                .andExpect(content().string("TABLE_NAME\r\n"));
    }

    @Test
    void sqlTranslationFailureUnderAcceptCsvIsErrorViewJson() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.plan(ArgumentMatchers.anyString()))
                .willThrow(new SqlTranslationException("unknown column 'no_such_column'"));

        final MvcResult result = mockMvc.perform(post("/v1/queries/execute-sql-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("text/csv")
                        .content(SQL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        final JsonNode body = errorView(result);
        assertThat(body.get("error").stringValue()).isEqualTo("bad_request");
        assertThat(body.get("message").stringValue()).isEqualTo("unknown column 'no_such_column'");
        BDDMockito.verifyNoInteractions(queryService);
    }

    @Test
    void blankSqlUnderAcceptCsvIsErrorViewJson() throws Exception {
        final MvcResult result = mockMvc.perform(post("/v1/queries/execute-sql-csv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("text/csv")
                        .content("{\"sql\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(errorView(result).get("error").stringValue()).isEqualTo("bad_request");
        BDDMockito.verifyNoInteractions(sqlQueryFrontEnd, queryService);
    }
}
