package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.QueryTranslationService;
import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.SqlTranslation;
import com.epam.aidial.analytics.web.security.Privilege;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer coverage for the translation pair: response envelopes ({@code {"query": …}} /
 * {@code {"sql": …}}), the shared 400 {@code ErrorView} mappings, and that neither endpoint touches
 * any execution collaborator (translation never contacts ClickHouse).
 */
@WebMvcTest(QueryTranslationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JsonMapperConfiguration.class)
class QueryTranslationControllerTest {

    private static final String SQL_BODY = "{\"sql\":\"SELECT project_id FROM dial_usage_log WHERE deployment ILIKE '%gpt%'\"}";

    private static final StructuredQuery TRANSLATED = new StructuredQuery(
            "dial_usage_log",
            new ComparisonNode(ComparisonOp.ICO, List.of(
                    new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt"))),
            QueryMode.ROW,
            false,
            List.of(new OutputColumn(new FieldExpr("project_id"), null)),
            null, null, null, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SqlQueryFrontEnd sqlQueryFrontEnd;

    @MockitoBean
    private QueryTranslationService translationService;

    @MockitoBean
    private StructuredQueryService queryService;

    // ---------------------------------------------------------- translate-sql

    @Test
    void translateSqlReturnsDslDocumentEnvelope() throws Exception {
        // TABLE_GET on both passes: translation resolves against the catalog model and never reaches
        // ClickHouse, so a caller that may only `get` a table can translate a statement over it.
        BDDMockito.given(sqlQueryFrontEnd.translate(
                        "SELECT project_id FROM dial_usage_log WHERE deployment ILIKE '%gpt%'",
                        Privilege.TABLE_GET))
                .willReturn(new SqlTranslation(TRANSLATED, List.of(), false));

        mockMvc.perform(post("/v1/queries/translate-sql").contentType(MediaType.APPLICATION_JSON).content(SQL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.entity").value("dial_usage_log"))
                .andExpect(jsonPath("$.query.mode").value("row"))
                .andExpect(jsonPath("$.query.filter.op").value("ico"))
                .andExpect(jsonPath("$.query.filter.args[0].type").value("field"))
                .andExpect(jsonPath("$.query.filter.args[0].name").value("deployment"))
                .andExpect(jsonPath("$.query.filter.args[1].value").value("gpt"))
                .andExpect(jsonPath("$.query.select[0].expr.name").value("project_id"));

        BDDMockito.verify(queryService).validate(TRANSLATED, Privilege.TABLE_GET);
        BDDMockito.verifyNoInteractions(translationService);
    }

    /**
     * The row governance lives in the builder, which the front end's translation does not run — so without
     * the endpoint's own validation pass this answered 200 with a document {@code /v1/queries/execute} then
     * rejected, contradicting the endpoint's contract that what it returns is executable as returned.
     */
    @Test
    void translateSqlAppliesTheRowGovernance() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.translate(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(Privilege.class)))
                .willReturn(new SqlTranslation(TRANSLATED, List.of(), true));
        BDDMockito.willThrow(new QueryValidationException("page limit 50000 exceeds the maximum of 1000 rows"))
                .given(queryService).validate(TRANSLATED, Privilege.TABLE_GET);

        mockMvc.perform(post("/v1/queries/translate-sql").contentType(MediaType.APPLICATION_JSON).content(SQL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("page limit 50000 exceeds the maximum of 1000 rows"));
    }

    @Test
    void translateSqlMapsTranslationErrorToBadRequest() throws Exception {
        BDDMockito.given(sqlQueryFrontEnd.translate(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(Privilege.class)))
                .willThrow(new SqlTranslationException("unsupported SQL construct: JOIN"));

        mockMvc.perform(post("/v1/queries/translate-sql").contentType(MediaType.APPLICATION_JSON).content(SQL_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("unsupported SQL construct: JOIN"))
                .andExpect(jsonPath("$.method").value("POST"));
    }

    @Test
    void translateSqlRejectsBlankSql() throws Exception {
        mockMvc.perform(post("/v1/queries/translate-sql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        BDDMockito.verifyNoInteractions(sqlQueryFrontEnd, translationService, queryService);
    }

    // -------------------------------------------------------------- translate

    @Test
    void translateReturnsSqlEnvelope() throws Exception {
        BDDMockito.given(translationService.toSql(ArgumentMatchers.any(StructuredQuery.class)))
                .willReturn("SELECT \"project_id\" FROM \"dial_usage_log\"");

        mockMvc.perform(post("/v1/queries/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value("SELECT \"project_id\" FROM \"dial_usage_log\""));

        BDDMockito.verifyNoInteractions(sqlQueryFrontEnd, queryService);
    }

    @Test
    void translateMapsValidationErrorToBadRequest() throws Exception {
        BDDMockito.given(translationService.toSql(ArgumentMatchers.any(StructuredQuery.class)))
                .willThrow(new QueryValidationException("entity 'nope' is not queryable; supported entities: [dial_usage_log]"));

        mockMvc.perform(post("/v1/queries/translate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"nope\",\"mode\":\"row\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("entity 'nope' is not queryable; supported entities: [dial_usage_log]"));
    }

    @Test
    void translateRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/v1/queries/translate").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        BDDMockito.verifyNoInteractions(sqlQueryFrontEnd, translationService, queryService);
    }
}
