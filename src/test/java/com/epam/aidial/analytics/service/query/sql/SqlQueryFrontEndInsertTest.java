package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import com.epam.aidial.analytics.web.security.Privilege;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SqlQueryFrontEnd#planInsert}: extraction of the target, the column list and the source block from an
 * aggregate pipeline's {@code INSERT INTO … SELECT …} declaration, and the statement-level refusals. Runs offline
 * against the {@link DialUsageLogTestModel} fixture plus a small {@code events} entity, as {@link SqlQueryFrontEndTest}.
 */
class SqlQueryFrontEndInsertTest {

    private static final String HEAD = "INSERT INTO usage_daily (deployment, tokens) ";

    private static final List<OutputColumn> DEPLOYMENT_TOKENS = List.of(
            new OutputColumn(new FieldExpr("deployment"), null),
            new OutputColumn(new FnExpr("sum", false, List.of(new FieldExpr("total_tokens"))), "tokens"));

    private final SqlQueryFrontEnd frontEnd = newFrontEnd();

    private static SqlQueryFrontEnd newFrontEnd() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY, "events"));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        when(catalog.findModel(eq("events"), any())).thenReturn(Optional.of(eventsModel()));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    private static QueryEntityModel eventsModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("user_id", new QueryFieldBinding(
                "user_id", DSL.field(DSL.name("events", "user_id"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING));
        bindings.put("deployment", new QueryFieldBinding(
                "deployment", DSL.field(DSL.name("events", "deployment"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING));
        bindings.put("format", new QueryFieldBinding(
                "format", DSL.field(DSL.name("events", "format"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING));
        bindings.put("users.region", new QueryFieldBinding(
                "users.region", DSL.field(DSL.name("users", "region"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING));
        final EnrichmentJoin join = new EnrichmentJoin(
                DSL.table(DSL.name("users")),
                DSL.field(DSL.name("events", "user_id")).eq(DSL.field(DSL.name("users", "user_id"))), null, null, null, null,
                Set.of("users.region"), null);
        return new QueryEntityModel(
                "events", DSL.table(DSL.name("events")), bindings, List.of(join),
                List.of(new QuerySchemaFieldDto("users.region", QueryFieldType.STRING, null, null, "region", null, null, null, false, false)));
    }

    private SqlInsertPlan plan(String sql) {
        return frontEnd.planInsert(sql, Privilege.TABLE_READ);
    }

    // ------------------------------------------------------------------ extraction

    @Test
    void plainSourceIsExtracted() {
        final SqlInsertPlan plan = plan(HEAD + "SELECT deployment, sum(total_tokens) AS tokens FROM dial_usage_log GROUP BY deployment;");
        assertThat(plan.target()).isEqualTo("usage_daily");
        assertThat(plan.columns()).containsExactly("deployment", "tokens");
        final StructuredQuery query = plan.translation().query();
        assertThat(query.entity()).isEqualTo("dial_usage_log");
        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.select()).isEqualTo(DEPLOYMENT_TOKENS);
    }

    @Test
    void quotedTargetAndColumnsAreUnquoted() {
        final SqlInsertPlan plan = plan("INSERT INTO \"Usage_Daily\" (\"deployment\", tokens) "
                + "SELECT deployment, sum(total_tokens) AS tokens FROM dial_usage_log GROUP BY deployment");
        assertThat(plan.target()).isEqualTo("Usage_Daily");
        assertThat(plan.columns()).containsExactly("deployment", "tokens");
    }

    @Test
    void cteSourceFoldsIntoOneBlock() {
        final SqlInsertPlan plan = plan("""
                INSERT INTO usage_daily (chat_id, weighted)
                WITH chat AS (SELECT chat_id, total_tokens * 2 AS w FROM dial_usage_log WHERE chat_id <> '')
                SELECT chat_id, sum(w) AS weighted FROM chat GROUP BY chat_id""");
        assertThat(plan.columns()).containsExactly("chat_id", "weighted");
        assertThat(plan.translation().query().entity()).isEqualTo("dial_usage_log");
        assertThat(plan.translation().query().filter()).isNotNull();
    }

    @Test
    void parenthesizedWithSourceIsExtracted() {
        final SqlInsertPlan plan = plan(HEAD + "(WITH d AS (SELECT deployment, total_tokens FROM dial_usage_log) "
                + "SELECT deployment, sum(total_tokens) AS tokens FROM d GROUP BY deployment)");
        assertThat(plan.translation().query().entity()).isEqualTo("dial_usage_log");
        assertThat(plan.translation().query().select()).isEqualTo(DEPLOYMENT_TOKENS);
    }

    @Test
    void orderByWrappedSourceIsExtracted() {
        // The source's own position covers only the last sort item; the cut is taken from the column list.
        final SqlInsertPlan plan = plan(HEAD + "SELECT deployment, sum(total_tokens) AS tokens FROM dial_usage_log "
                + "GROUP BY deployment ORDER BY deployment, tokens");
        assertThat(plan.translation().query().sort()).hasSize(2);
        assertThat(plan.translation().query().select()).isEqualTo(DEPLOYMENT_TOKENS);
    }

    @Test
    void finalModifierDoesNotShiftTheCut() {
        final SqlInsertPlan plan = plan(HEAD + "SELECT deployment, sum(total_tokens) AS tokens FROM dial_usage_log FINAL "
                + "GROUP BY deployment");
        assertThat(plan.columns()).containsExactly("deployment", "tokens");
        assertThat(plan.translation().query().finalRead()).isTrue();
    }

    @Test
    void commentMarkerInsideLiteralIsCutRaw() {
        final SqlInsertPlan plan = plan("""
                INSERT INTO usage_daily
                  (deployment,
                   tokens)
                SELECT deployment, sum(total_tokens) AS tokens FROM dial_usage_log
                WHERE chat_id <> '--' GROUP BY deployment""");
        assertThat(plan.columns()).containsExactly("deployment", "tokens");
        assertThat(plan.translation().query().filter().toString()).contains("--");
    }

    @Test
    void closingParenInsideLiteralIsCutRaw() {
        final SqlInsertPlan plan = plan("INSERT INTO usage_daily (deployment, tokens)\tSELECT deployment, sum(total_tokens) AS tokens "
                + "FROM dial_usage_log WHERE chat_id <> 'x)' GROUP BY deployment");
        assertThat(plan.columns()).containsExactly("deployment", "tokens");
        assertThat(plan.translation().query().filter().toString()).contains("x)");
    }

    @Test
    void positionalGroupByResolves() {
        final SqlInsertPlan plan = plan(HEAD + "SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1");
        assertThat(plan.translation().query().groupBy()).hasSize(1);
        assertThat(plan.translation().query().select().getFirst().expr()).isEqualTo(new FieldExpr("deployment"));
    }

    @Test
    void reorderedPositionalGroupByResolves() {
        final SqlInsertPlan plan = plan("INSERT INTO usage_daily (deployment, chat_id, tokens) "
                + "SELECT deployment, chat_id, sum(total_tokens) FROM dial_usage_log GROUP BY 2, 1");
        final StructuredQuery query = plan.translation().query();
        assertThat(query.groupBy()).hasSize(2);
        assertThat(query.select().subList(0, 2)).extracting(OutputColumn::expr)
                .containsExactly(new FieldExpr("deployment"), new FieldExpr("chat_id"));
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void moreThanOneStatementRefused() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1; "
                + HEAD + "SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("exactly one statement");
    }

    @Test
    void windowFunctionRefusedByName() {
        // The planner seals the aggregate under the window, which used to surface as "carries DISTINCT".
        assertThatThrownBy(() -> plan(HEAD + "SELECT deployment, count(*) OVER () FROM dial_usage_log GROUP BY deployment"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(SqlQueryFrontEnd.WINDOW_REFUSAL);
    }

    @Test
    void windowFunctionInsideCteRefusedByName() {
        assertThatThrownBy(() -> plan(HEAD + "WITH t AS (SELECT deployment, total_tokens, "
                + "row_number() OVER (PARTITION BY deployment ORDER BY request_time) AS rn FROM dial_usage_log) "
                + "SELECT deployment, sum(total_tokens) FROM t GROUP BY deployment"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(SqlQueryFrontEnd.WINDOW_REFUSAL);
    }

    @Test
    void unprojectedGroupByExpressionRefusedByName() {
        // A second, unprojected bucket used to surface as "this one carries an aggregate".
        assertThatThrownBy(() -> plan("INSERT INTO usage_daily (day, tokens) SELECT date_trunc('day', request_time), "
                + "sum(total_tokens) FROM dial_usage_log GROUP BY date_trunc('day', request_time), date_trunc('hour', request_time)"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("the GROUP BY key")
                .hasMessageContaining("'hour'")
                .hasMessageContaining("is not projected by any select item");
    }

    @Test
    void projectedGroupByExpressionInAnotherCaseIsAccepted() {
        final SqlInsertPlan plan = plan("INSERT INTO usage_daily (day, tokens) SELECT DATE_TRUNC('day', request_time) AS day, "
                + "sum(total_tokens) FROM dial_usage_log GROUP BY date_trunc('day', request_time)");
        assertThat(plan.translation().query().groupBy()).hasSize(1);
    }

    @Test
    void bareSelectRefused() {
        assertThatThrownBy(() -> plan("SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("INSERT INTO <target> (<columns>) SELECT");
    }

    @Test
    void missingColumnListRefused() {
        assertThatThrownBy(() -> plan("INSERT INTO usage_daily SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("column list is required");
    }

    @Test
    void valuesSourceRefused() {
        assertThatThrownBy(() -> plan(HEAD + "VALUES ('gpt', 1)"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("must be a SELECT")
                .hasMessageContaining("VALUES");
    }

    @Test
    void joinRefused() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT l.deployment, sum(l.total_tokens) FROM dial_usage_log l "
                + "JOIN events e ON l.deployment = e.deployment GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("explicit JOIN")
                .hasMessageContaining("events");
    }

    @Test
    void selfJoinThroughCteCountsTwoReferences() {
        assertThatThrownBy(() -> plan(HEAD + "WITH t AS (SELECT deployment, total_tokens FROM dial_usage_log) "
                + "SELECT a.deployment, sum(b.total_tokens) FROM t a JOIN t b ON a.deployment = b.deployment GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("referenced 2 times");
    }

    @Test
    void setOperationRefused() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1 "
                + "UNION ALL SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("set operation (UNION)");
    }

    @Test
    void subqueryExpressionRefused() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT deployment, sum(total_tokens) FROM dial_usage_log "
                + "WHERE deployment IN (SELECT deployment FROM events) GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("subquery");
    }

    @Test
    void nestedAggregateRefused() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT deployment, sum(n) FROM "
                + "(SELECT deployment, chat_id, count(*) AS n FROM dial_usage_log GROUP BY deployment, chat_id) d GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("flatten to one aggregating block")
                .hasMessageContaining("an aggregate");
    }

    @Test
    void metadataSourceRefused() {
        assertThatThrownBy(() -> plan("INSERT INTO usage_daily (table_name) SELECT table_name FROM INFORMATION_SCHEMA.\"TABLES\""))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("INFORMATION_SCHEMA");
    }

    @Test
    void selectStarRefusedButCountStarAccepted() {
        assertThatThrownBy(() -> plan(HEAD + "WITH t AS (SELECT * FROM dial_usage_log) "
                + "SELECT deployment, sum(total_tokens) FROM t GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("SELECT *");
        assertThat(plan(HEAD + "SELECT deployment, count(*) FROM dial_usage_log GROUP BY 1").columns()).hasSize(2);
    }

    @Test
    void selectDistinctRefusedOnTheParseButCountDistinctAccepted() {
        // The planner folds a top-level DISTINCT over a GROUP BY away, so only the parse still shows it.
        assertThatThrownBy(() -> plan(HEAD + "SELECT DISTINCT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("SELECT DISTINCT");
        assertThat(plan(HEAD + "SELECT deployment, count(DISTINCT chat_id) FROM dial_usage_log GROUP BY 1").columns()).hasSize(2);
    }

    @Test
    void settingsClauseNamed() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1 SETTINGS max_threads = 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("SETTINGS clause is not supported");
    }

    @Test
    void backticksNamed() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT `deployment`, sum(total_tokens) FROM dial_usage_log GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("Backtick quoting is not supported");
    }

    @Test
    void formatClauseRefusedBeforeParsing() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1 FORMAT JSON"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("FORMAT clause is not supported");
    }

    @Test
    void trailingFormatClauseRefusedBeforeParsing() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1 FORMAT TSV;\n-- done"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("FORMAT clause is not supported");
    }

    @Test
    void formatJsonInsideTheStatementRefused() {
        assertThatThrownBy(() -> plan(HEAD + "SELECT deployment FORMAT JSON, sum(total_tokens) FROM dial_usage_log GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("FORMAT clause is not supported");
    }

    @Test
    void quotedColumnNamedFormatIsNotClause() {
        final SqlInsertPlan plan = plan("INSERT INTO usage_daily (\"format\", n) SELECT \"format\", count(*) AS n FROM events GROUP BY \"format\"");
        assertThat(plan.columns()).containsExactly("format", "n");
        assertThat(plan.translation().query().select().getFirst().expr()).isEqualTo(new FieldExpr("format"));
    }

    @Test
    void unquotedColumnNamedFormatIsNotClause() {
        final SqlInsertPlan plan = plan("INSERT INTO usage_daily (format, n) SELECT format, count(*) AS n FROM events GROUP BY format");
        assertThat(plan.columns()).containsExactly("format", "n");
        assertThat(plan.translation().query().select().getFirst().expr()).isEqualTo(new FieldExpr("format"));
    }

    @Test
    void aliasNamedFormatIsNotClause() {
        assertThat(plan(HEAD + "SELECT deployment AS format, sum(total_tokens) AS tokens FROM dial_usage_log GROUP BY format")
                .columns()).containsExactly("deployment", "tokens");
        assertThat(plan(HEAD + "SELECT deployment AS \"format\", sum(total_tokens) AS tokens FROM dial_usage_log GROUP BY 1")
                .columns()).containsExactly("deployment", "tokens");
    }

    @Test
    void formatInsideLiteralIsNotClause() {
        assertThat(plan(HEAD + "SELECT deployment, sum(total_tokens) FROM dial_usage_log WHERE chat_id <> 'FORMAT' GROUP BY 1")
                .columns()).hasSize(2);
    }

    @Test
    void columnNamedFormatBeforeSortDirectionIsNotClause() {
        // The ORDER BY is the validator's refusal; the front end must not mistake `format DESC` for a FORMAT clause.
        assertThat(plan("INSERT INTO usage_daily (format, n) SELECT format, count(*) AS n FROM events GROUP BY format ORDER BY format DESC")
                .columns()).containsExactly("format", "n");
    }

    @Test
    void qualifiedTargetRefused() {
        assertThatThrownBy(() -> plan("INSERT INTO \"default\".usage_daily (deployment, tokens) "
                + "SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("bare table name");
    }

    @Test
    void nestedDistinctNamesTheBlockNotTheGroupBy() {
        assertThatThrownBy(() -> plan(HEAD + "WITH d AS (SELECT DISTINCT chat_id, deployment FROM dial_usage_log) "
                + "SELECT deployment, count(*) FROM d GROUP BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("nested DISTINCT")
                .hasMessageNotContaining("already yields one row per key");
    }

    @Test
    void refusalPositionsPointIntoTheCallersText() {
        // The source is planned with the INSERT head blanked, not cut off, so line 2 stays line 2.
        assertThatThrownBy(() -> plan("INSERT INTO usage_daily (deployment, tokens)\n"
                + "SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY BY 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("line 2, column 67");
    }
}
