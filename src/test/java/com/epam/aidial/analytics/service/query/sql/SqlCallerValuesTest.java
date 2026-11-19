package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.web.security.Privilege;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The SQL spelling of a caller value: how {@code caller.user_hash()} is read, placed and refused. */
class SqlCallerValuesTest {

    private static final String FROM = "SELECT count(*) AS n FROM dial_usage_log WHERE ";
    private static final ComparisonNode OWN_ROWS = new ComparisonNode(ComparisonOp.EQ,
            List.of(new FieldExpr("user_hash"), new ParamExpr("caller.user_hash")));

    private final SqlQueryFrontEnd frontEnd = frontEnd();

    private static SqlQueryFrontEnd frontEnd() {
        final CatalogQueryService entities = mock(CatalogQueryService.class);
        when(entities.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(entities.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(entities));
    }

    private FilterNode filter(String condition) {
        return frontEnd.translate(FROM + condition, Privilege.TABLE_READ, true).query().filter();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "user_hash = caller.user_hash()",
        "caller.user_hash() = user_hash",
        "user_hash = CALLER.USER_HASH()",
        "user_hash = caller.user_hash ( )",
        "user_hash = caller.user_hash(/* none */)",
        "user_hash = CAST(caller.user_hash() AS VARCHAR)"})
    void everyAcceptedSpellingTranslatesToTheParam(String condition) {
        assertThat(filter(condition)).isEqualTo(OWN_ROWS);
    }

    @Test
    void anInListIsTheDisjunctionItAbbreviates() {
        assertThat(filter("user_hash IN (caller.user_hash(), 'x')")).isEqualTo(new LogicalNode(LogicalOp.OR, List.of(
                OWN_ROWS,
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("user_hash"), new ValueExpr(ValueType.STRING, "x"))))));
    }

    @Test
    void longInListIsRefusedBeforeTheCallerValueIsReached() {
        // Calcite plans a list this long as a subquery over literal rows, which is refused before the caller value is
        // reached, exactly as a long list without one is.
        final String items = String.join(", ", java.util.Collections.nCopies(20, "'x'"));
        assertThatThrownBy(() -> frontEnd.plan(FROM + "user_hash IN (" + items + ", caller.user_hash())", Privilege.TABLE_READ, true))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(e -> assertThat(SqlIssueClassifier.classify(e.getMessage()).code()).isEqualTo("in_list_too_long"));
    }

    @Test
    void theComparisonSitsUnderAnyLogicalOperator() {
        assertThat(filter("NOT (user_hash = caller.user_hash()) AND project_id <> caller.project()").toString())
                .contains("name=caller.user_hash").contains("name=caller.project");
    }

    @Test
    void literalSpellingTheCallStaysLiteral() {
        assertThat(filter("user_hash = 'caller.user_hash()'")).isEqualTo(new ComparisonNode(ComparisonOp.EQ,
                List.of(new FieldExpr("user_hash"), new ValueExpr(ValueType.STRING, "caller.user_hash()"))));
    }

    @Test
    void bareSpellingStaysColumnOfRelationNamedCaller() {
        assertThat(frontEnd.translate("SELECT caller.user_hash FROM dial_usage_log AS caller", Privilege.TABLE_READ, true)
                .query().select().getFirst().expr()).isEqualTo(new FieldExpr("user_hash"));
    }

    @Test
    void compositeStatementReadsTheCallInEveryBlock() {
        assertThat(frontEnd.plan("SELECT count(*) AS n FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id AND a.user_hash = caller.user_hash()", Privilege.TABLE_READ, true))
                .isInstanceOf(SqlCompositeTranslation.class);
        assertThat(frontEnd.plan(FROM + "deployment IN (SELECT deployment FROM dial_usage_log WHERE user_hash = caller.user_hash())",
                Privilege.TABLE_READ, true)).isInstanceOf(SqlCompositeTranslation.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT caller.user_hash() AS h FROM dial_usage_log",
        FROM + "upper(caller.user_hash()) = user_hash",
        FROM + "CAST(caller.user_hash() AS INTEGER) = response_status",
        FROM + "prompt_tokens = caller.user_hash()",
        FROM + "event_id = caller.user_hash()",
        FROM + "user_hash > caller.user_hash()",
        FROM + "user_hash LIKE caller.user_hash()",
        FROM + "caller.user_hash() IS NULL",
        FROM + "caller.user_hash() IS NOT NULL",
        FROM + "caller.user_hash() = caller.project()",
        FROM + "NOT (CASE WHEN user_hash = caller.user_hash() THEN TRUE ELSE FALSE END)",
        FROM + "coalesce(user_hash = caller.user_hash(), TRUE)",
        "SELECT user_hash, count(*) AS n FROM dial_usage_log GROUP BY user_hash HAVING caller.user_hash() IS NULL"})
    void misplacedCallerValueIsRefusedNamingIt(String statement) {
        assertThatThrownBy(() -> frontEnd.plan(statement, Privilege.TABLE_READ, true))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("caller value 'caller.")
                .satisfies(e -> assertThat(SqlIssueClassifier.classify(e.getMessage()).code()).isEqualTo("unsupported_construct"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        FROM + "user_hash = caller . user_hash()",
        FROM + "user_hash = caller.user_hash /* c */ ()",
        FROM + "user_hash = CALLER_USER_HASH()",
        FROM + "user_hash = caller_user_hash ()",
        FROM + "user_hash = \"caller_user_hash\"()",
        FROM + "user_hash = caller.email()"})
    void anyOtherSpellingIsAnUnknownFunction(String statement) {
        assertThatThrownBy(() -> frontEnd.plan(statement, Privilege.TABLE_READ, true))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(e -> assertThat(SqlIssueClassifier.classify(e.getMessage()).code()).isEqualTo("unknown_function"));
    }

    @Test
    void anArgumentIsAnArityErrorNamingTheCallerValue() {
        assertThatThrownBy(() -> frontEnd.plan(FROM + "user_hash = caller.user_hash('x')", Privilege.TABLE_READ, true))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("'caller.user_hash'")
                .satisfies(e -> assertThat(SqlIssueClassifier.classify(e.getMessage()).code()).isEqualTo("function_arity"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_hash = caller.user_hash()", "user_hash = CALLER_USER_HASH()", "user_hash = U&\"caller_user_hash\"()"})
    void statementThatDoesNotOptInReachesNoCallerValue(String condition) {
        // A pipeline declaration is translated without the rewrite: every spelling is an unknown function there.
        assertThatThrownBy(() -> frontEnd.translate(FROM + condition))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("No match found for function signature");
    }

    @Test
    void catalogMetadataStatementRefusesCallerValue() {
        assertThatThrownBy(() -> frontEnd.plan("SELECT caller.user_hash() AS h FROM INFORMATION_SCHEMA.\"TABLES\"",
                Privilege.TABLE_READ, true))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("INFORMATION_SCHEMA")
                .satisfies(e -> assertThat(SqlIssueClassifier.classify(e.getMessage()).code()).isEqualTo("unsupported_construct"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        FROM + "user_hash = caller.user_hash() OR project_id = U&\"caller\\005fproject\"()",
        FROM + "user_hash = caller.user_hash() OR project_id = 'x' --\r OR project_id = caller_project()",
        FROM + "user_hash = caller.user_hash() OR project_id = 'x' //\r OR project_id = CALLER_PROJECT()"})
    void forgedCallIsRefusedNextToLegitimateOne(String statement) {
        assertThatThrownBy(() -> frontEnd.plan(statement, Privilege.TABLE_READ, true))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(e -> assertThat(SqlIssueClassifier.classify(e.getMessage()).code()).isEqualTo("unknown_function"));
    }

    @Test
    void lineCommentEndsAtEitherTerminatorAndPositionsSurviveTabsAndCrlf() {
        assertThat(filter("project_id = 'x' --\r OR user_hash = caller.user_hash()").toString()).contains("name=caller.user_hash");
        assertThat(frontEnd.translate("SELECT count(*) AS n\r\n\tFROM dial_usage_log\r\n\t\tWHERE user_hash =\tcaller.user_hash()",
                Privilege.TABLE_READ, true).query().filter()).isEqualTo(OWN_ROWS);
    }

    @Test
    void nameLikeInternalOperatorThatIsNoCallStaysUsable() {
        assertThat(frontEnd.plan("WITH caller_project (a) AS (SELECT deployment FROM dial_usage_log) SELECT a FROM caller_project",
                Privilege.TABLE_READ, true)).isNotNull();
    }

    @Test
    void havingComparesStringAggregateOutputThroughItsAlias() {
        final SqlTranslation translation = frontEnd.translate("SELECT project_id, max(user_hash) AS m FROM dial_usage_log"
                + " GROUP BY project_id HAVING m = caller.user_hash()", Privilege.TABLE_READ, true);
        assertThat(translation.query().having().toString()).contains("name=caller.user_hash").contains("name=max");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT lower(project_id) AS p, count(*) AS n FROM dial_usage_log GROUP BY p HAVING p = caller.project()",
        "WITH c AS (SELECT lower(user_hash) AS h FROM dial_usage_log) SELECT count(*) AS n FROM c WHERE h = caller.user_hash()",
        FROM + "lower(user_hash) = caller.user_hash()",
        FROM + "CAST(prompt_tokens AS VARCHAR) = caller.user_hash()"})
    void computedTextOperandIsAcceptedWhereverItStands(String statement) {
        assertThat(frontEnd.plan(statement, Privilege.TABLE_READ, true)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT project_id, max(event_id) AS m FROM dial_usage_log GROUP BY project_id HAVING m = caller.user_hash()",
        FROM + "upper(caller.user_hash()) = caller.project()",
        // The derived table folds into the outer block, so the column reference stands for a literal and reads no column.
        "SELECT count(*) AS n FROM (SELECT 'x' AS k, user_hash FROM dial_usage_log) d WHERE k = caller.user_hash()"})
    void operandOutsideTheAdmittedShapeIsRefusedAtPlanTime(String statement) {
        assertThatThrownBy(() -> frontEnd.plan(statement, Privilege.TABLE_READ, true))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("caller value 'caller.");
    }

    @Test
    void theRewriteSkipsLiteralsCommentsAndLongerNames() throws Exception {
        final String sql = "SELECT 'caller.user_hash()', E'\\'caller.user_hash()' -- caller.user_hash()\n"
                + "// caller.user_hash()\r/* caller.user_hash() */ x.caller.user_hash(), xcaller.user_hash(), caller.user_hash()";
        final org.apache.calcite.sql.SqlNode screened = org.apache.calcite.sql.parser.SqlParser.create("SELECT 1").parseQuery();
        final SqlCallerValues.Rewrite rewrite = SqlCallerValues.rewrite(sql, screened);
        assertThat(rewrite.sql()).hasSameSizeAs(sql)
                .isEqualTo(sql.substring(0, sql.length() - "caller.user_hash()".length()) + "caller_user_hash()");
        assertThat(rewrite.firstCallerValue()).isEqualTo("caller.user_hash");
        assertThat(SqlCallerValues.rewrite("SELECT caller.user_hash FROM t AS caller", screened).applied()).isFalse();
    }
}
