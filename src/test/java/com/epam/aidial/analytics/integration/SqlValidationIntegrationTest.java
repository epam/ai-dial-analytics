package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for {@code POST /v1/queries/validate-sql} against a real ClickHouse and catalog.
 *
 * <p>The load-bearing tests here are the agreement ones: for each statement, the verdict is checked
 * against what {@code /v1/queries/execute-sql} <em>actually does</em> with the same statement in the same
 * context. That equivalence is the endpoint's entire promise, and it is the one property a unit test
 * cannot establish — the verdict and the execution have to be produced by the same running catalog for
 * the comparison to mean anything.
 */
class SqlValidationIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String VALIDATE_SQL = "/v1/queries/validate-sql";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    private ResultActions validate(String sql) throws Exception {
        return mockMvc.perform(post(VALIDATE_SQL).contentType(MediaType.APPLICATION_JSON).content(body(sql)));
    }

    private ResultActions executeSql(String sql) throws Exception {
        return mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON).content(body(sql)));
    }

    private static String body(String sql) {
        return "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    // ------------------------------------------------------ agreement with execute-sql

    /**
     * Statements execute-sql accepts. A composite one is included deliberately: it is the case
     * {@code /translate-sql} rejects despite execute-sql running it, and the reason exposing that endpoint
     * instead of this one would have misled every caller writing a join.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT event_kind FROM dial_usage_log LIMIT 10",
        "SELECT project_id, count(*) AS n FROM dial_usage_log GROUP BY project_id LIMIT 10",
        "SELECT event_kind FROM dial_usage_log WHERE request_time >= TIMESTAMP '2020-01-01 00:00:00' LIMIT 5",
        "SELECT * FROM dial_usage_log LIMIT 1",
        "SELECT a.project_id, count(*) AS n FROM dial_usage_log a"
                + " JOIN dial_usage_log b ON a.event_id = b.event_id GROUP BY a.project_id LIMIT 10",
        "SELECT count(*) AS n FROM (SELECT event_id FROM dial_usage_log LIMIT 5) t",
        "SELECT event_kind FROM dial_usage_log UNION ALL SELECT event_kind FROM dial_usage_log",
        "SELECT event_kind FROM dial_usage_log UNION DISTINCT SELECT event_kind FROM dial_usage_log",
        "SELECT event_kind FROM dial_usage_log INTERSECT SELECT event_kind FROM dial_usage_log",
        "SELECT event_kind FROM dial_usage_log EXCEPT ALL SELECT event_kind FROM dial_usage_log",
        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'dial_usage_log'",
    })
    void verdictAgreesWithExecuteForAcceptedStatements(String sql) throws Exception {
        fixture().row("chat", "p1", 100).insert();

        validate(sql)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.issues", hasSize(0)));

        executeSql(sql).andExpect(status().isOk());
    }

    /** Statements execute-sql rejects, each for a different reason in a different stage of the pipeline. */
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT event_kind FROM dial_usage_log LIMIT 50000",
        "SELECT no_such_column FROM dial_usage_log",
        "SELECT event_kind FROM no_such_entity",
        "SELECT no_such_fn(event_kind) FROM dial_usage_log",
        "SELECT event_kind, count(*) AS rows FROM dial_usage_log GROUP BY event_kind",
        // A bare UNION, which ClickHouse refuses too; the ALL and DISTINCT spellings are valid and
        // covered by the accepted-statement cases above.
        "SELECT event_kind FROM dial_usage_log UNION SELECT event_kind FROM dial_usage_log",
        "SELECT event_kind FROM dial_usage_log MINUS SELECT event_kind FROM dial_usage_log",
        "SELECT event_kind FROM dial_usage_log WHERE execution_path IS NULL",
        "INSERT INTO dial_usage_log (event_kind) VALUES ('x')",
    })
    void verdictAgreesWithExecuteForRejectedStatements(String sql) throws Exception {
        fixture().row("chat", "p1", 100).insert();

        validate(sql)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[0].code").exists())
                .andExpect(jsonPath("$.issues[0].message").exists());

        executeSql(sql).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------ the envelope

    @Test
    void reportsTheEffectiveLimitAndTheDefaultLimitWarning() throws Exception {
        validate("SELECT event_kind FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.effective_limit").value(100))
                .andExpect(jsonPath("$.warnings[0].code").value("default_limit_applied"));
    }

    @Test
    void reportsAnExplicitLimitAsTheEffectiveOne() throws Exception {
        validate("SELECT event_kind FROM dial_usage_log LIMIT 25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective_limit").value(25))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    /**
     * A governance rejection is a property of the translated query rather than of a token, so it carries
     * no position — the `line`/`column` keys are absent rather than null or zero.
     */
    @Test
    void classifiesAnOverLimitStatementWithItsRemedy() throws Exception {
        validate("SELECT event_kind FROM dial_usage_log LIMIT 50000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.effective_limit").doesNotExist())
                .andExpect(jsonPath("$.issues", hasSize(1)))
                .andExpect(jsonPath("$.issues[0].code").value("limit_above_maximum"))
                .andExpect(jsonPath("$.issues[0].remedy").exists())
                .andExpect(jsonPath("$.issues[0].line").doesNotExist())
                .andExpect(jsonPath("$.issues[0].column").doesNotExist());
    }

    /**
     * A DSL-only name is an unknown function on this surface, and the remedy sends the caller to the SQL
     * catalog. The DSL catalog lists {@code plus}, so naming it here would send the caller back to the
     * source of the mistake.
     */
    @Test
    void unknownFunctionPointsAtTheSqlCatalog() throws Exception {
        validate("SELECT plus(total_tokens, 1) AS n FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[0].code").value("unknown_function"))
                .andExpect(jsonPath("$.issues[0].remedy").value(containsString("GET /v1/queries/sql-functions")));
    }

    @Test
    void wrongArityPointsAtTheSqlCatalog() throws Exception {
        validate("SELECT count() AS n FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[0].code").value("function_arity"))
                .andExpect(jsonPath("$.issues[0].remedy").value(containsString("GET /v1/queries/sql-functions")))
                .andExpect(jsonPath("$.issues[0].remedy").value(containsString("count(*)")));
    }

    /**
     * A token-level diagnosis does carry a position, and it is anchored on the offending word rather than
     * on the token the parser stopped at. Calcite reports the preceding `AS` at column 26; `rows` — the
     * reserved word actually at fault — starts at 29, and that is what the issue must point to.
     */
    @Test
    void reportsPositionOfTokenLevelDiagnosis() throws Exception {
        validate("SELECT chat_id, count(*) AS rows FROM dial_usage_log GROUP BY chat_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[0].code").value("reserved_word_alias"))
                .andExpect(jsonPath("$.issues[0].line").value(1))
                .andExpect(jsonPath("$.issues[0].column").value(29));
    }

    /** Several misspellings are reported together, so the caller need not discover them one call at a time. */
    @Test
    void reportsEveryUnknownColumnInOneCall() throws Exception {
        final String response = validate(
                "SELECT nope_one, nope_two FROM dial_usage_log WHERE nope_three = 'x'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues", hasSize(3)))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("nope_one").contains("nope_two").contains("nope_three");
    }

    /**
     * The suggestion has to survive the merge: the pipeline's own rejection names `event_kindd` too, so
     * the probe's finding is not reported twice — but its closest-name suggestion is the actionable part
     * and must land on the one issue that is reported.
     */
    @Test
    void suggestsTheClosestCatalogColumn() throws Exception {
        validate("SELECT event_kindd FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues", hasSize(1)))
                .andExpect(jsonPath("$.issues[0].code").value("unknown_column"))
                .andExpect(jsonPath("$.issues[0].remedy").value("did you mean 'event_kind'?"));
    }

    // ------------------------------------------------------ warnings

    /**
     * The warning's reason for existing: this statement passes every check and is still refused at
     * execution, because the budget is enforced by ClickHouse as the statement runs. Only the verdict and
     * the warning are asserted — the refusal itself depends on how much data the box holds, so pinning it
     * here would make the test a measurement of the container rather than of the endpoint.
     */
    @Test
    void warnsAboutUnboundedHeavyColumnRead() throws Exception {
        validate("SELECT event_id, \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log WHERE chat_id = 'c' LIMIT 10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.warnings[0].code").value("heavy_column_unbounded"))
                .andExpect(jsonPath("$.warnings[0].remedy").exists());
    }

    @Test
    void dropsHeavyColumnWarningInsideTimeBound() throws Exception {
        validate("SELECT event_id, \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log"
                + " WHERE request_time >= TIMESTAMP '2020-01-01 00:00:00' LIMIT 10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
    }

    /** A composite statement receives only the limit note — its blocks reference relation columns. */
    @Test
    void compositeStatementGetsOnlyTheLimitWarning() throws Exception {
        validate("SELECT a.project_id FROM dial_usage_log a JOIN dial_usage_log b ON a.event_id = b.event_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andExpect(jsonPath("$.warnings[0].code").value("default_limit_applied"));
    }

    // ------------------------------------------------------ the request/statement split

    @Test
    void blankSqlIsBadRequestNotVerdict() throws Exception {
        mockMvc.perform(post(VALIDATE_SQL).contentType(MediaType.APPLICATION_JSON).content("{\"sql\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.valid").doesNotExist());
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post(VALIDATE_SQL).contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }
}
