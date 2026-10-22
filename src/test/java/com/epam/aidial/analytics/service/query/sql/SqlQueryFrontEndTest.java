package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.OutputColumnNaming;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SQL → {@link StructuredQuery} translation coverage for the scenarios in
 * {@code specs/sql-query-translation/spec.md}: catalog validation, supported-subset round-trips, and
 * fail-closed rejection of inexpressible constructs. Runs fully offline against the
 * {@link DialUsageLogTestModel} fixture plus a small source+enrichment model.
 */
class SqlQueryFrontEndTest {

    private final SqlQueryFrontEnd frontEnd = newFrontEnd();

    private static SqlQueryFrontEnd newFrontEnd() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY, "events"));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        when(catalog.findModel(eq("events"), any())).thenReturn(Optional.of(enrichedModel()));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    /** Source {@code events} with enrichment {@code users} exposing the dotted column {@code users.region}. */
    private static QueryEntityModel enrichedModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("user_id", new QueryFieldBinding(
                "user_id", DSL.field(DSL.name("events", "user_id"), JooqTypes.of(QueryFieldType.STRING)),
                QueryFieldType.STRING));
        bindings.put("amount", new QueryFieldBinding(
                "amount", DSL.field(DSL.name("events", "amount"), JooqTypes.of(QueryFieldType.LONG)),
                QueryFieldType.LONG));
        bindings.put("users.region", new QueryFieldBinding(
                "users.region", DSL.field(DSL.name("users", "region"), JooqTypes.of(QueryFieldType.STRING)),
                QueryFieldType.STRING));
        final EnrichmentJoin join = new EnrichmentJoin(
                DSL.table(DSL.name("users")),
                DSL.field(DSL.name("events", "user_id")).eq(DSL.field(DSL.name("users", "user_id"))), null, null, null, null,
                Set.of("users.region"), null);
        return new QueryEntityModel(
                "events", DSL.table(DSL.name("events")), bindings, List.of(join),
                List.of(new QuerySchemaFieldDto("users.region", QueryFieldType.STRING, null, null, "region", null, null, null, false, false)));
    }

    // ------------------------------------------------------ catalog validation

    @Test
    void knownEntityAndColumnsValidate() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT event_kind, deployment FROM dial_usage_log");
        assertThat(translation.query().entity()).isEqualTo("dial_usage_log");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.ROW);
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FieldExpr("event_kind"), null),
                new OutputColumn(new FieldExpr("deployment"), null));
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("event_kind", ResultColumnKind.STRING),
                new ResultColumn("deployment", ResultColumnKind.STRING));
    }

    @Test
    void unknownColumnRejected() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT no_such_column FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("no_such_column");
    }

    @Test
    void unknownTableRejected() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT x FROM no_such_table"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("no_such_table");
    }

    @Test
    void quotedDottedEnrichmentColumnResolves() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT user_id, \"users.region\" FROM events");
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FieldExpr("user_id"), null),
                new OutputColumn(new FieldExpr("users.region"), null));
    }

    /**
     * A star expands over the entity's own columns of every declared type — {@code array} included —
     * and reaches none of the payload enrichment's, because a star never fans out into a grain join.
     * The exclusion is by OWNERSHIP, not by type or by the {@code heavy} flag: the two claim/header
     * documents are not heavy and are still excluded, purely because they now live on the enrichment.
     */
    @Test
    void selectStarExpandsOverSourceColumnsOfEveryType() {
        final SqlTranslation translation = frontEnd.translate("SELECT * FROM dial_usage_log");
        final List<String> names = translation.columns().stream().map(ResultColumn::name).toList();
        assertThat(names).contains(
                "event_id", "event_kind", "success", "total_price", "execution_path");
        assertThat(names).doesNotContain(
                "dial_usage_log_payload.request_body", "dial_usage_log_payload.assembled_response",
                "dial_usage_log_payload.jwt_claims", "dial_usage_log_payload.request_tags");
    }

    // ------------------------------------------------------- star expansion (heavy / enrichment)

    @Test
    void selectStarOmitsHeavyColumns() {
        final SqlTranslation translation = frontEnd.translate("SELECT * FROM dial_usage_log LIMIT 1");
        assertThat(translation.columns()).extracting(ResultColumn::name)
                .doesNotContain("dial_usage_log_payload.request_body", "dial_usage_log_payload.response_body")
                .contains("event_id", "request_body_bytes", "response_body_bytes");
        assertThat(translation.query().select()).extracting(OutputColumn::expr)
                .doesNotContain(new FieldExpr("dial_usage_log_payload.request_body"), new FieldExpr("dial_usage_log_payload.response_body"));
    }

    @Test
    void selectStarExpandsInCatalogOrder() {
        // The expansion's order is observable (rendered SQL, Arrow schema), so it must be the catalog's
        // column order — not the hash order of the bindings map.
        final SqlTranslation translation = frontEnd.translate("SELECT * FROM dial_usage_log LIMIT 1");
        assertThat(translation.columns()).extracting(ResultColumn::name)
                .startsWith("event_id", "event_kind", "request_time", "_ingested_at", "_source_file", "project_id");
    }

    @Test
    void explicitlyNamedHeavyColumnIsProjected() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT event_id, \"dial_usage_log_payload.response_body\" FROM dial_usage_log LIMIT 1");
        assertThat(translation.columns()).extracting(ResultColumn::name)
                .containsExactly("event_id", "dial_usage_log_payload.response_body");
    }

    @Test
    void heavyColumnStaysUsableInWhereAndOrderBy() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT * FROM dial_usage_log WHERE \"dial_usage_log_payload.response_body\" LIKE '%error%' ORDER BY \"dial_usage_log_payload.response_body\" LIMIT 5");
        assertThat(translation.query().filter()).isNotNull();
        assertThat(translation.query().sort()).extracting("field").containsExactly("dial_usage_log_payload.response_body");
        assertThat(translation.columns()).extracting(ResultColumn::name).doesNotContain("dial_usage_log_payload.response_body");
    }

    @Test
    void selectStarOmitsEnrichmentColumns() {
        // BREAKING (deliberate): a star expands to the source's columns only, so it never drags an
        // enrichment LEFT JOIN into the cheapest-looking query in the surface.
        final SqlTranslation translation = frontEnd.translate("SELECT * FROM events LIMIT 1");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("user_id", "amount");
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FieldExpr("user_id"), null),
                new OutputColumn(new FieldExpr("amount"), null));
    }

    @Test
    void enrichmentColumnStaysSelectableByItsQualifiedName() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT user_id, \"users.region\" FROM events LIMIT 1");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("user_id", "users.region");
    }

    @Test
    void qualifiedStarExpandsLikeBareStar() {
        final SqlTranslation aliased = frontEnd.translate("SELECT t.* FROM events t LIMIT 1");
        assertThat(aliased.columns()).extracting(ResultColumn::name).containsExactly("user_id", "amount");
        final SqlTranslation byTableName = frontEnd.translate("SELECT events.* FROM events LIMIT 1");
        assertThat(byTableName.columns()).extracting(ResultColumn::name).containsExactly("user_id", "amount");
    }

    @Test
    void starIsExpandedCaseInsensitively() {
        final SqlTranslation translation = frontEnd.translate("SELECT * FROM DIAL_USAGE_LOG LIMIT 1");
        assertThat(translation.columns()).extracting(ResultColumn::name).doesNotContain("dial_usage_log_payload.request_body");
    }

    @Test
    void countStarIsUnaffectedByStarExpansion() {
        final SqlTranslation translation = frontEnd.translate("SELECT count(*) FROM dial_usage_log");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("count");
    }

    @Test
    void starOverDerivedTableHonorsHeavinessAtBaseTable() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT * FROM (SELECT * FROM dial_usage_log) LIMIT 1");
        assertThat(translation.columns()).extracting(ResultColumn::name)
                .doesNotContain("dial_usage_log_payload.request_body", "dial_usage_log_payload.response_body")
                .contains("event_id");
    }

    @Test
    void allHeavyStarIsRejectedNamingTheEntity() {
        // Every visible column heavy → the star would expand to nothing; reject with the remedy rather
        // than emitting a zero-column statement.
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("blob", new QueryFieldBinding(
                "blob", DSL.field(DSL.name("dumps", "blob"), JooqTypes.of(QueryFieldType.STRING)),
                QueryFieldType.STRING, null, true));
        final QueryEntityModel dumps = new QueryEntityModel(
                "dumps", DSL.table(DSL.name("dumps")), bindings, List.of(),
                List.of(new QuerySchemaFieldDto("blob", QueryFieldType.STRING, null, null, "blob", null, null, null, false, true)));
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of("dumps"));
        when(catalog.findModel(eq("dumps"), any())).thenReturn(Optional.of(dumps));
        final SqlQueryFrontEnd heavyOnly = new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));

        assertThatThrownBy(() -> heavyOnly.translate("SELECT * FROM dumps"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("dumps")
                .hasMessageContaining("heavy");
        // The column itself stays queryable by name.
        assertThat(heavyOnly.translate("SELECT blob FROM dumps").columns())
                .extracting(ResultColumn::name).containsExactly("blob");
    }

    // ------------------------------------------------------- group_uniq_array

    @Test
    void groupUniqArrayTranslatesAndTypesAsAnArrayOfItsOperand() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT chat_id, group_uniq_array(deployment) AS deployments FROM dial_usage_log GROUP BY chat_id");

        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FieldExpr("chat_id"), null),
                new OutputColumn(new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment"))),
                        "deployments"));
        // The element kind comes from the operand, not from a declared column type — the one array result
        // ResultColumns cannot map.
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("chat_id", ResultColumnKind.STRING),
                new ResultColumn("deployments", ResultColumnKind.ARRAY, ResultColumnKind.STRING));
    }

    @Test
    void groupUniqArrayWithDistinctRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, group_uniq_array(DISTINCT deployment) FROM dial_usage_log GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("does not accept DISTINCT");
    }

    @Test
    void groupUniqArrayOverAnArrayFieldRejected() {
        // execution_path is itself an array: collecting one would build an array of arrays, which the DSL
        // has no type for. Still refused, but the message names the function that flattens instead — the
        // author's question has an answer on this surface, under another name.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, group_uniq_array(execution_path) FROM dial_usage_log GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("array field 'execution_path'")
                .hasMessageContaining("groupUniqArrayArray");
    }

    // ----------------------------------------- lambdas and higher-order calls

    @Test
    void theGrammarParsesTheLambdaAndTheFrontEndLowersIt() {
        // The Calcite-upgrade canary. This surface depends on exactly one thing the Babel grammar gives
        // it — that `x -> expr` parses as an expression — and on nothing else of Calcite's lambda
        // machinery, since SqlLambdaRewriter inlines the parameter before validation. If an upgrade
        // changed the grammar, every lambda query would fail at once; this fails first, naming the
        // dependency rather than a user's query.
        assertThatCode(() -> frontEnd.translate(
                "SELECT arrayExists(x -> not_empty(x), execution_path) AS f FROM dial_usage_log"))
                .as("the Babel grammar must keep parsing `x -> expr` in a function argument")
                .doesNotThrowAnyException();
    }

    @Test
    void higherOrderPredicateOverAnArrayFieldTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT chat_id FROM dial_usage_log"
                        + " WHERE arrayExists(x -> starts_with(x, 'coder-'), execution_path)");

        // The document carries the call in ClickHouse's own operand order — the lambda first — with the
        // parameter as its own expression kind rather than a field reference.
        assertThat(translation.query().filter()).isEqualTo(new ComparisonNode(ComparisonOp.EQ, List.of(
                new FnExpr("arrayExists", false, List.of(
                        new LambdaExpr(List.of("x"), new FnExpr("starts_with", false, List.of(
                                new LambdaParamExpr("x"), new ValueExpr(ValueType.STRING, "coder-")))),
                        new FieldExpr("execution_path"))),
                new ValueExpr(ValueType.BOOLEAN, "true"))));
    }

    @Test
    void higherOrderPredicateIsProjectableAndScalar() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT arrayCount(x -> not_empty(x), execution_path) AS steps FROM dial_usage_log");
        assertThat(translation.columns())
                .containsExactly(new ResultColumn("steps", ResultColumnKind.INT32));
    }

    @Test
    void lambdaBodyCapturesTheEnclosingRowColumn() {
        // ClickHouse resolves a lambda body against the enclosing row as well as the parameter, and so
        // does this surface: the captured column is an ordinary field reference in the document, which is
        // what makes it visible to every rule keyed on the columns a statement reads.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT arrayExists(x -> starts_with(x, deployment), execution_path) AS f FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("arrayExists", false, List.of(
                        new LambdaExpr(List.of("x"), new FnExpr("starts_with", false, List.of(
                                new LambdaParamExpr("x"), new FieldExpr("deployment")))),
                        new FieldExpr("execution_path"))),
                "f"));
    }

    @Test
    void lambdaParameterShadowsTheColumnOfTheSameName() {
        // Innermost binding wins, as in ClickHouse: `deployment` inside the body is the element, not the
        // column, so the document carries a parameter reference and no field reference at all.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT arrayExists(deployment -> not_empty(deployment), execution_path) AS f"
                        + " FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("arrayExists", false, List.of(
                        new LambdaExpr(List.of("deployment"), new FnExpr("not_empty", false,
                                List.of(new LambdaParamExpr("deployment")))),
                        new FieldExpr("execution_path"))),
                "f"));
    }

    @Test
    void higherOrderCallsNest() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT arrayExists(x -> starts_with(x, 'c'), arrayFilter(y -> not_empty(y), execution_path))"
                        + " AS f FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("arrayExists", false, List.of(
                        new LambdaExpr(List.of("x"), new FnExpr("starts_with", false, List.of(
                                new LambdaParamExpr("x"), new ValueExpr(ValueType.STRING, "c")))),
                        new FnExpr("arrayFilter", false, List.of(
                                new LambdaExpr(List.of("y"), new FnExpr("not_empty", false,
                                        List.of(new LambdaParamExpr("y")))),
                                new FieldExpr("execution_path"))))),
                "f"));
    }

    @Test
    void nestedLambdasOverTheSameArrayKeepTheirParametersApart() {
        // The hazard the parameter name exists for: both markers stand over the same array expression, so
        // only the name tells the inner parameter from the outer one.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT arrayExists(x -> arrayExists(y -> equals(y, x), execution_path), execution_path)"
                        + " AS f FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("arrayExists", false, List.of(
                        new LambdaExpr(List.of("x"), new FnExpr("arrayExists", false, List.of(
                                new LambdaExpr(List.of("y"), new FnExpr("equals", false, List.of(
                                        new LambdaParamExpr("y"), new LambdaParamExpr("x")))),
                                new FieldExpr("execution_path")))),
                        new FieldExpr("execution_path"))),
                "f"));
    }

    @Test
    void arrayReturningHigherOrderCallsTypeTheirElements() {
        assertThat(frontEnd.translate(
                "SELECT arrayFilter(x -> not_empty(x), execution_path) AS kept FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("kept", ResultColumnKind.ARRAY, ResultColumnKind.STRING));
        assertThat(frontEnd.translate(
                "SELECT arrayMap(x -> length(x), execution_path) AS sizes FROM dial_usage_log").columns())
                // The element kind follows the BODY, not the operand: a mapped array of lengths is
                // numeric where the operand it was mapped from is text.
                .containsExactly(new ResultColumn("sizes", ResultColumnKind.ARRAY, ResultColumnKind.INT64));
    }

    @Test
    void arrayConsumersTypeTheElementsOfTheNewArrayShapes() {
        // array_join and array_slice both answer from elementKind, so every shape that can produce an
        // array has to be answered THERE as well as where it is projected. Missing an arm does not fail:
        // it falls back to string, so array_join(arrayMap(x -> length(x), …)) would hand the Arrow writer
        // a text writer for Int64 values, and the same expression would type one way projected and
        // another way consumed.
        assertThat(frontEnd.translate(
                "SELECT array_join(arrayMap(x -> length(x), execution_path)) AS e FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("e", ResultColumnKind.INT64));
        assertThat(frontEnd.translate(
                "SELECT array_slice(arrayMap(x -> length(x), execution_path), 1, 2) AS e FROM dial_usage_log")
                .columns())
                .containsExactly(new ResultColumn("e", ResultColumnKind.ARRAY, ResultColumnKind.INT64));
        assertThat(frontEnd.translate(
                "SELECT array_join(arrayFilter(x -> not_empty(x), execution_path)) AS e FROM dial_usage_log")
                .columns())
                .containsExactly(new ResultColumn("e", ResultColumnKind.STRING));
        assertThat(frontEnd.translate(
                "SELECT chat_id, array_join(groupArrayArray(arrayMap(x -> length(x), execution_path))) AS e"
                        + " FROM dial_usage_log GROUP BY chat_id").columns())
                .containsExactly(
                        new ResultColumn("chat_id", ResultColumnKind.STRING),
                        new ResultColumn("e", ResultColumnKind.INT64));
    }

    @Test
    void arrayReturningHigherOrderCallIsProjectionOnly() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id FROM dial_usage_log ORDER BY arrayFilter(x -> not_empty(x), execution_path)"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("arrayFilter");
    }

    @Test
    void arrayReturningHigherOrderCallRendersAsText() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT array_to_string(arrayFilter(x -> not_empty(x), execution_path), ', ') AS path"
                        + " FROM dial_usage_log");
        assertThat(translation.columns())
                .containsExactly(new ResultColumn("path", ResultColumnKind.STRING));
    }

    @Test
    void higherOrderCallOverNonArrayOperandRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT arrayExists(x -> not_empty(x), deployment) AS f FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("arrayExists");
    }

    @Test
    void higherOrderCallWithoutLambdaRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT arrayExists(not_empty(deployment), execution_path) AS f FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("requires a lambda");
    }

    @Test
    void lambdaOutsideHigherOrderCallRejected() {
        // In an ordinary function's argument the grammar parses a lambda, so the rewrite is what refuses
        // it, naming the only position it is legal in.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT lower(x -> not_empty(x)) AS f FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("only valid as the first argument");
        // As a bare select item the grammar refuses it first, which is a rejection just the same.
        assertThatThrownBy(() -> frontEnd.translate("SELECT x -> not_empty(x) AS f FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void nonBooleanLambdaBodyRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT arrayExists(x -> upper(x), execution_path) AS f FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("arrayExists");
    }

    @Test
    void aggregateInsideLambdaBodyRejectedByName() {
        // Once inlined the body sits in the enclosing scope, where Calcite would report the ARRAY operand
        // as "not being grouped" — a message about neither the mistake nor its fix.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, arrayExists(x -> starts_with(x, max(deployment)), execution_path)"
                        + " FROM dial_usage_log GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("'max' is an aggregate")
                .hasMessageContaining("per element");
    }

    @Test
    void noClickHouseAliasOfAnExistingCatalogFunctionWasAdded() {
        // The checkpoint decision, pinned: the functions NEW in this change carry their ClickHouse
        // names, and the shipped catalog gains no ClickHouse spelling of its own. Aliasing the existing
        // 49 is its own change; this test is what stops one arriving by accident with this one.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT startsWith(deployment, 'gpt') FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT hasAny(execution_path, ARRAY['core']) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
        assertThatCode(() -> frontEnd.translate(
                "SELECT arrayExists(x -> not_empty(x), execution_path) FROM dial_usage_log"))
                .doesNotThrowAnyException();
    }

    @Test
    void theInternalParameterMarkerIsReserved() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT __lambdaParam(execution_path, 'x') AS f FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("unsupported function");
    }

    @Test
    void columnAbsentFromTheModelCannotBeCapturedInLambdaBody() {
        // This is the mechanism a sensitive column is governed by: CatalogQueryService omits it from the
        // model of a caller who may not see it, so capturing one inside a body is the same unknown-column
        // error as projecting it. Exercised here through an absent name, since the offline fixture
        // declares no sensitive column of its own.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT arrayExists(x -> starts_with(x, response_body), execution_path) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("response_body");
    }

    // ------------------------------------------------------------------ match

    @Test
    void regexPredicateTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT chat_id FROM dial_usage_log WHERE match(deployment, '^gpt-4')");
        assertThat(translation.query().filter()).isEqualTo(new ComparisonNode(ComparisonOp.EQ, List.of(
                new FnExpr("match", false, List.of(
                        new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "^gpt-4"))),
                new ValueExpr(ValueType.BOOLEAN, "true"))));
    }

    @Test
    void regexPredicateIsLegalInsideLambdaBody() {
        assertThatCode(() -> frontEnd.translate(
                "SELECT chat_id FROM dial_usage_log"
                        + " WHERE arrayExists(x -> match(x, '^coder-'), execution_path)"))
                .doesNotThrowAnyException();
    }

    // ------------------------------- array-valued aggregates over array fields

    @Test
    void flatteningAggregateOverAnArrayFieldTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT chat_id, groupUniqArrayArray(execution_path) AS steps FROM dial_usage_log"
                        + " GROUP BY chat_id");

        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FieldExpr("chat_id"), null),
                new OutputColumn(new FnExpr("groupUniqArrayArray", false,
                        List.of(new FieldExpr("execution_path"))), "steps"));
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("chat_id", ResultColumnKind.STRING),
                new ResultColumn("steps", ResultColumnKind.ARRAY, ResultColumnKind.STRING));
    }

    @Test
    void flatteningAggregateWithDistinctRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, groupArrayArray(DISTINCT execution_path) FROM dial_usage_log GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("does not accept DISTINCT");
    }

    @Test
    void flatteningAggregateOverScalarRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, groupUniqArrayArray(deployment) FROM dial_usage_log GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("groupUniqArrayArray");
    }

    @Test
    void flatteningAggregateOverAnAggregateRejected() {
        // The carve-out at that position admits an array VALUE; these two are themselves aggregates, so an
        // aggregate inside one is a nested aggregate ClickHouse refuses outright. Pinned on this surface
        // as well as on the DSL one, because the two must answer the same statement alike.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, groupUniqArrayArray(group_uniq_array(deployment)) FROM dial_usage_log"
                        + " GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void thePostAggregationRolePredicateTranslates() {
        // The motivating statement: the test applies to the union of the group's arrays, so the group's
        // own measures stay computed over every row rather than over the matching ones.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT chat_id, sum(total_tokens) AS tokens FROM dial_usage_log GROUP BY chat_id"
                        + " HAVING arrayExists(x -> starts_with(x, 'coder-'),"
                        + " groupUniqArrayArray(execution_path))");

        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.query().filter()).isNull();
        assertThat(translation.query().having()).isEqualTo(new ComparisonNode(ComparisonOp.EQ, List.of(
                new FnExpr("arrayExists", false, List.of(
                        new LambdaExpr(List.of("x"), new FnExpr("starts_with", false, List.of(
                                new LambdaParamExpr("x"), new ValueExpr(ValueType.STRING, "coder-")))),
                        new FnExpr("groupUniqArrayArray", false, List.of(new FieldExpr("execution_path"))))),
                new ValueExpr(ValueType.BOOLEAN, "true"))));
    }

    // ---------------------------------------------------------------- arg_max

    @Test
    void argMaxTranslatesAndTypesFromItsValueOperand() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT chat_id, arg_max(deployment, request_time) AS latest FROM dial_usage_log GROUP BY chat_id");

        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FieldExpr("chat_id"), null),
                new OutputColumn(new FnExpr("arg_max", false,
                        List.of(new FieldExpr("deployment"), new FieldExpr("request_time"))), "latest"));
        // The result column takes the VALUE operand's kind. Typing it from the last argument — the shape
        // the neighbouring min/max case uses, where arity 1 makes the two the same — would report this as
        // a TIMESTAMP while still returning deployment strings.
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("chat_id", ResultColumnKind.STRING),
                new ResultColumn("latest", ResultColumnKind.STRING));
    }

    @Test
    void argMaxKeepsOperandOrderRatherThanNormalizingIt() {
        // Reversing the operands is a different, equally valid query — the last-hop deployment vs the
        // request_time of the alphabetically-last deployment — so the translator must carry the order
        // through rather than canonicalizing it.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT chat_id, arg_max(request_time, deployment) AS t FROM dial_usage_log GROUP BY chat_id");

        assertThat(translation.query().select().get(1)).isEqualTo(
                new OutputColumn(new FnExpr("arg_max", false,
                        List.of(new FieldExpr("request_time"), new FieldExpr("deployment"))), "t"));
        assertThat(translation.columns()).element(1)
                .isEqualTo(new ResultColumn("t", ResultColumnKind.TIMESTAMP));
    }

    @Test
    void argMaxWithoutAnAliasTakesTheFunctionName() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT chat_id, arg_max(deployment, request_time) FROM dial_usage_log GROUP BY chat_id");

        assertThat(translation.columns()).containsExactly(
                new ResultColumn("chat_id", ResultColumnKind.STRING),
                new ResultColumn("arg_max", ResultColumnKind.STRING));
    }

    @Test
    void argMaxWithDistinctRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, arg_max(DISTINCT deployment, request_time) FROM dial_usage_log GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("does not accept DISTINCT");
    }

    @Test
    void argMaxWithWrongArityRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, arg_max(deployment) FROM dial_usage_log GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class);
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, arg_max(deployment, request_time, event_kind) FROM dial_usage_log GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void argMaxCarriesAnArrayValue() {
        // "the path as of this chat's latest request" — an array is legal as arg_max's VALUE, which is the
        // carve-out that makes the current-state reading of an array column expressible. The ordering key
        // stays scalar, and the result is an array.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT chat_id, arg_max(execution_path, request_time) AS last_path FROM dial_usage_log"
                        + " GROUP BY chat_id");
        assertThat(translation.columns())
                .filteredOn(column -> column.name().equals("last_path"))
                .singleElement()
                .satisfies(column -> assertThat(column.kind()).isEqualTo(ResultColumnKind.ARRAY));
    }

    @Test
    void argMaxOverAnArrayOrderKeyRejected() {
        // Only the VALUE operand takes an array; the key it ranks by stays scalar, so this position keeps
        // the projection-only message every other operand gives.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, arg_max(request_time, execution_path) FROM dial_usage_log GROUP BY chat_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("array field 'execution_path'")
                .hasMessageContaining("can only be projected");
    }

    // ----------------------------------------------------------- object fields

    @Test
    void objectFieldProjects() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT event_kind, \"dial_usage_log_payload.jwt_claims\" FROM dial_usage_log");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.ROW);
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FieldExpr("event_kind"), null),
                new OutputColumn(new FieldExpr("dial_usage_log_payload.jwt_claims"), null));
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("event_kind", ResultColumnKind.STRING),
                new ResultColumn("dial_usage_log_payload.jwt_claims", ResultColumnKind.OBJECT));
    }

    @Test
    void objectFieldInWhereRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" IS NOT NULL"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void objectFieldComparedToLiteralRejected() {
        // An object field IS a legal equality operand now, but only against a JSON document: 'x' is not
        // one, so this is rejected for the literal rather than for the field's type. Checked here even
        // though translate-sql runs no builder pass, so the emitted DSL is always one execute accepts.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" = 'x'"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("requires a JSON document");
    }

    @Test
    void objectFieldComparedToDocumentWithTrailingGarbageRejected() {
        // A literal that merely *starts* with a document is not one. The gate reads the whole literal, so
        // this is refused at translation time rather than travelling on to CAST(... AS JSON).
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" = '{\"a\":1} trailing'"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("requires a JSON document");
    }

    @Test
    void objectFieldEqualityAgainstJsonDocumentTranslates() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" = '{\"sub\":\"user-1\"}'").query();
        assertThat(query.filter()).isInstanceOf(ComparisonNode.class);
        final ComparisonNode filter = (ComparisonNode) query.filter();
        assertThat(filter.op()).isEqualTo(ComparisonOp.EQ);
        // The bare object field is the operand — no coercion wrapped around it, so it round-trips.
        assertThat(filter.args().getFirst()).isEqualTo(new FieldExpr("dial_usage_log_payload.jwt_claims"));
    }

    @Test
    void objectFieldInLikeTranslates() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" LIKE '%admin%'").query();
        final ComparisonNode filter = (ComparisonNode) query.filter();
        assertThat(filter.op()).isEqualTo(ComparisonOp.CO);
        assertThat(filter.args().getFirst()).isEqualTo(new FieldExpr("dial_usage_log_payload.jwt_claims"));
    }

    @Test
    void objectFieldInIlikeTranslates() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.request_tags\" ILIKE '%gpt-4%'").query();
        final ComparisonNode filter = (ComparisonNode) query.filter();
        assertThat(filter.op()).isEqualTo(ComparisonOp.ICO);
        assertThat(filter.args().getFirst()).isEqualTo(new FieldExpr("dial_usage_log_payload.request_tags"));
    }

    @Test
    void objectFieldInNotLikeTranslates() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" NOT LIKE '%admin%'").query();
        assertThat(query.filter()).isNotNull();
    }

    @Test
    void objectFieldInInListRejected() {
        // 'a'/'b' are not JSON documents; the IN list is rejected for its literals, not for the field.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" IN ('a', 'b')"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("requires a JSON document");
    }

    @Test
    void objectFieldInJsonDocumentListTranslates() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" IN ('{\"a\":1}', '{\"b\":2}')").query();
        assertThat(query.filter()).isNotNull();
    }

    @Test
    void objectFieldUnderOrderingComparisonRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" > '{\"a\":1}'"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void objectFieldComparedToAnotherObjectFieldRejected() {
        // The carve-out is the shape `<object field> <op> <character literal>` — the only one the DSL
        // surface accepts. A field on the right is not one, so translating it would hand back a document
        // /v1/queries/execute refuses.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.request_tags\" = \"dial_usage_log_payload.jwt_claims\""))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void objectFieldComparedToScalarFieldRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" = event_kind"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void objectFieldAsTheRightOperandOfJsonLiteralRejected() {
        // Mirrored shape: the literal on the left. The DSL surface refuses any projection-only field as a
        // right operand, so this must be refused here too — with the same sentence it uses.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE '{\"a\":1}' = \"dial_usage_log_payload.jwt_claims\""))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("the right operand of comparison 'eq'");
    }

    @Test
    void objectFieldAsTheRightOperandOfNonJsonLiteralRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE 'x' = \"dial_usage_log_payload.jwt_claims\""))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("the right operand of comparison 'eq'");
    }

    @Test
    void objectFieldInHavingRejectedBothWaysRound() {
        // The document carve-out is reachable from WHERE only, and not by a rule of its own: a HAVING
        // predicate may name only a grouped or aggregated expression, and grouping by an object field is
        // rejected — so both routes into HAVING close. Recorded so the pair stays visible together.
        assertThatThrownBy(() -> frontEnd.translate("SELECT event_kind, count(*) AS n FROM dial_usage_log"
                + " GROUP BY event_kind HAVING \"dial_usage_log_payload.request_tags\" ILIKE '%gpt%'"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("dial_usage_log_payload.request_tags");
        assertThatThrownBy(() -> frontEnd.translate("SELECT \"dial_usage_log_payload.request_tags\", count(*) AS n FROM dial_usage_log"
                + " GROUP BY \"dial_usage_log_payload.request_tags\" HAVING count(*) > 1"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.request_tags'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void objectFieldInGroupByRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT \"dial_usage_log_payload.jwt_claims\", count(*) FROM dial_usage_log GROUP BY \"dial_usage_log_payload.jwt_claims\""))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void objectFieldInOrderByRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT \"dial_usage_log_payload.jwt_claims\" FROM dial_usage_log ORDER BY \"dial_usage_log_payload.jwt_claims\""))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void selectDistinctOverObjectFieldRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT DISTINCT \"dial_usage_log_payload.jwt_claims\" FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void objectFieldAsAggregateArgumentRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT count(\"dial_usage_log_payload.jwt_claims\") FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    // ------------------------------------- json_extract over an object field (the one carve-out)

    @Test
    void jsonExtractOverObjectFieldProjects() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'sub') AS sub FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(
                        new FnExpr("json_extract_string", false,
                                List.of(new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "sub"))),
                        "sub"));
        // Typed by the function's fixed return type, not as an object column.
        assertThat(translation.columns()).containsExactly(new ResultColumn("sub", ResultColumnKind.STRING));
    }

    @Test
    void jsonExtractOverObjectFieldInWhereTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'sub') = 'user-1'");
        assertThat(translation.query().filter()).isEqualTo(new ComparisonNode(
                ComparisonOp.EQ,
                List.of(
                        new FnExpr("json_extract_string", false,
                                List.of(new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "sub"))),
                        new ValueExpr(ValueType.STRING, "user-1"))));
    }

    @Test
    void jsonExtractOverObjectFieldInGroupByTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'sub') AS sub, count(*) AS n FROM dial_usage_log"
                        + " GROUP BY JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'sub')");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.query().groupBy()).containsExactly("sub");
    }

    @Test
    void selectDistinctOverExtractedObjectPathTranslates() {
        // DISTINCT is restricted over a bare object field, not over a scalar extracted from one.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT DISTINCT JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'sub') AS sub FROM dial_usage_log");
        assertThat(translation.query().distinct()).isTrue();
        assertThat(translation.columns()).containsExactly(new ResultColumn("sub", ResultColumnKind.STRING));
    }

    @Test
    void jsonExtractTypesEachResultByItsFunction() {
        assertThat(frontEnd.translate("SELECT JSON_EXTRACT_LONG(\"dial_usage_log_payload.jwt_claims\", 'n') AS v FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("v", ResultColumnKind.INT64));
        assertThat(frontEnd.translate("SELECT JSON_EXTRACT_DOUBLE(\"dial_usage_log_payload.jwt_claims\", 'f') AS v FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("v", ResultColumnKind.FLOAT64));
        assertThat(frontEnd.translate("SELECT JSON_EXTRACT_BOOL(\"dial_usage_log_payload.jwt_claims\", 'b') AS v FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("v", ResultColumnKind.BOOL));
    }

    @Test
    void roundIsTypedFromItsValueNotItsDigitsLiteral() {
        // Typing the column from the last argument would read the INTEGER digits literal, advertise INT32,
        // and let the Arrow writer truncate 1.53 to 1.
        assertThat(frontEnd.translate("SELECT ROUND(total_price, 2) AS v FROM dial_usage_log").columns())
                .isEqualTo(frontEnd.translate("SELECT ROUND(total_price) AS v FROM dial_usage_log").columns())
                .doesNotContain(new ResultColumn("v", ResultColumnKind.INT32));
    }

    @Test
    void integerArithmeticIsTypedAsTheWiderTypeClickHouseReturns() {
        // ClickHouse's plus/minus/multiply promote one rank to avoid overflow — Int32 + Int32 arrives as
        // Int64 — so naming INT32 here would have the Arrow writer narrow the value with intValue() and
        // return a silently wrong number. The same rule sum() already applies to its own INT32 operand.
        assertThat(frontEnd.translate(
                "SELECT response_status + response_status AS v FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("v", ResultColumnKind.INT64));
        assertThat(frontEnd.translate("SELECT response_status * 2 AS v FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("v", ResultColumnKind.INT64));
        // modulo is the exception: a remainder cannot exceed its divisor, so ClickHouse keeps the type.
        assertThat(frontEnd.translate("SELECT response_status % 3 AS v FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("v", ResultColumnKind.INT32));
    }

    @Test
    void jsonExtractOverArrayFieldStillRejected() {
        // The carve-out is for object fields only. An array field never even reaches the
        // projection-only guard: Calcite types it as VARCHAR ARRAY, which does not match the
        // operator's CHARACTER operand, so validation rejects it first. Either way it fails closed
        // before execution — the assertion is on the rejection, not on which layer produced it.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT JSON_EXTRACT_STRING(execution_path, 'x') FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("JSON_EXTRACT_STRING")
                .hasMessageContaining("ARRAY");
    }

    @Test
    void jsonExtractOverObjectFieldWithComputedPathRejectedForThePath() {
        // Rejected for the non-literal path, not for the JSON argument's type.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", event_kind) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("string literal path keys");
    }

    @Test
    void objectFieldStillRejectedElsewhereWhenStatementAlsoExtracts() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'sub') AS sub FROM dial_usage_log ORDER BY \"dial_usage_log_payload.jwt_claims\""))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void objectColumnFilteredOutOfTheModelStaysUnknown() {
        // Governance chokepoint: the Calcite schema is a snapshot of CatalogQueryService.findModel,
        // whose bindings are already filtered by SensitiveColumnAccess. A caller who may not see a
        // sensitive object column gets a model without it — so SQL sees an unknown column, exactly
        // as discovery hides it. Making objects SQL-visible adds no reachability.
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>(DialUsageLogTestModel.model().bindings());
        bindings.remove("dial_usage_log_payload.jwt_claims");
        final QueryEntityModel filtered = new QueryEntityModel(
                DialUsageLogTestModel.ENTITY,
                DSL.table(DSL.name(DialUsageLogTestModel.ENTITY)),
                bindings,
                List.of(),
                DialUsageLogTestModel.model().baseSchema().stream()
                        .filter(f -> !"dial_usage_log_payload.jwt_claims".equals(f.name()))
                        .toList());
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(filtered));
        final SqlQueryFrontEnd restricted = new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));

        assertThatThrownBy(() -> restricted.translate("SELECT \"dial_usage_log_payload.jwt_claims\" FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("dial_usage_log_payload.jwt_claims");
        // …and the star expansion does not smuggle it back in.
        assertThat(restricted.translate("SELECT * FROM dial_usage_log").columns())
                .extracting(ResultColumn::name)
                .doesNotContain("dial_usage_log_payload.jwt_claims")
                .contains("dial_usage_log_payload.request_tags");
    }

    @Test
    void objectFieldAsScalarFunctionArgumentRejected() {
        // VARCHAR typing means Calcite accepts lower(dial_usage_log_payload.jwt_claims) — the guard must still reject it.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT lower(\"dial_usage_log_payload.jwt_claims\") FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'");
    }

    // ------------------------------------------------------------ array fields

    @Test
    void arrayFieldProjects() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT event_kind, execution_path FROM dial_usage_log");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.ROW);
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FieldExpr("event_kind"), null),
                new OutputColumn(new FieldExpr("execution_path"), null));
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("event_kind", ResultColumnKind.STRING),
                new ResultColumn("execution_path", ResultColumnKind.ARRAY, ResultColumnKind.STRING));
    }

    @Test
    void arrayFieldInWhereRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE execution_path IS NOT NULL"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void arrayFieldInGroupByRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT execution_path, count(*) FROM dial_usage_log GROUP BY execution_path"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void arrayFieldInOrderByRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT execution_path FROM dial_usage_log ORDER BY execution_path"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void selectDistinctOverArrayFieldRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT DISTINCT execution_path FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void arrayFieldAsAggregateArgumentRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT count(execution_path) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void arrayFieldAsScalarFunctionArgumentRejected() {
        // Calcite's own signature validation may reject this before the translator's check does;
        // either way it must fail closed as a translation error, never reach execution.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT length(execution_path) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    // ------------------------------------------------- array expansion (array_join)

    @Test
    void arrayJoinParsesAsFunctionCall() {
        // Measured, not assumed, the way `IF`'s non-reserved status is: if a Calcite upgrade ever reserved
        // ARRAY_JOIN, or bound the name to its own (Spark) two-operand operator at parse time, this fails
        // here rather than in every statement the DSL->SQL renderer emits.
        assertThat(reservedWords().isReservedWord("ARRAY_JOIN")).isFalse();
        assertThatCode(() -> frontEnd.translate("SELECT ARRAY_JOIN(execution_path) AS step FROM dial_usage_log"))
                .doesNotThrowAnyException();
    }

    @Test
    void arrayJoinOverArrayFieldTranslatesToScalarColumn() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT ARRAY_JOIN(execution_path) AS step FROM dial_usage_log");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.ROW);
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("array_join", false, List.of(new FieldExpr("execution_path"))), "step"));
        // The point of the whole exercise: the column is the array's ELEMENT, so it types as a string and
        // carries no array element kind. Typing it as an array here would hand the Arrow writer a list
        // writer for scalar values.
        assertThat(translation.columns()).containsExactly(new ResultColumn("step", ResultColumnKind.STRING));
    }

    @Test
    void arrayJoinGroupedByItsAliasTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT ARRAY_JOIN(execution_path) AS step, count(*) AS n FROM dial_usage_log GROUP BY step");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.query().groupBy()).containsExactly("step");
        // Grouping by the alias of an ARRAY-RETURNING call is refused (it would group by whole arrays);
        // array_join returns a scalar, so it is outside that rule by construction rather than by exception.
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("step", ResultColumnKind.STRING),
                new ResultColumn("n", ResultColumnKind.INT64));
    }

    @Test
    void arrayJoinOverComputedArrayTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT ARRAY_JOIN(SPLIT_STRING(event_kind, ',')) AS part FROM dial_usage_log");
        assertThat(translation.columns()).containsExactly(new ResultColumn("part", ResultColumnKind.STRING));
    }

    @Test
    void arrayJoinOverComputedArrayGroupedByItsAliasTranslatesAsOneBlock() {
        // The combination, not either half: an expansion of a computed array used as the grouping key. Both
        // spellings of the key are pinned because the DSL names the key against the select list either way,
        // so a client that repeats the expression must not be treated differently from one that aliases it.
        for (final String key : List.of("part", "ARRAY_JOIN(SPLIT_STRING(event_kind, ','))")) {
            final SqlTranslation translation = frontEnd.translate(
                    "SELECT ARRAY_JOIN(SPLIT_STRING(event_kind, ',')) AS part, sum(total_price) AS c"
                            + " FROM dial_usage_log GROUP BY " + key);
            assertThat(translation.query().mode()).as(key).isEqualTo(QueryMode.AGGREGATE);
            assertThat(translation.query().groupBy()).as(key).containsExactly("part");
            assertThat(translation.columns()).as(key).containsExactly(
                    new ResultColumn("part", ResultColumnKind.STRING),
                    new ResultColumn("c", ResultColumnKind.DECIMAL));
        }
    }

    @Test
    void arrayJoinInWhereTranslates() {
        // No positional guard: ClickHouse accepts arrayJoin in WHERE, so narrowing the subset here would
        // refuse a statement the engine runs.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE ARRAY_JOIN(execution_path) = 'a'");
        assertThat(translation.query().filter()).isNotNull();
    }

    @Test
    void arrayJoinInsideAnAggregateTranslates() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT count(ARRAY_JOIN(execution_path)) AS n FROM dial_usage_log");
        assertThat(translation.columns()).containsExactly(new ResultColumn("n", ResultColumnKind.INT64));
    }

    @Test
    void arrayJoinOverAggregateArrayCarriesTheOperandKind() {
        // The one array-returning call whose element kind follows its operand rather than being fixed at
        // string. Typing this INT64 is what keeps the Arrow writer and the DSL surface agreeing on a column
        // that a blanket string would have called text on both.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT ARRAY_JOIN(GROUP_UNIQ_ARRAY(total_tokens)) AS v FROM dial_usage_log");
        assertThat(translation.columns()).containsExactly(new ResultColumn("v", ResultColumnKind.INT64));
    }

    @Test
    void arrayJoinWithSecondOperandRejected() {
        // The other dialect's same-named function is array_join(array, delimiter) -> string. Our arity is
        // what makes that spelling fail closed instead of being read as an expansion with a stray literal.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT ARRAY_JOIN(execution_path, ',') AS step FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void arrayJoinOverNonArrayRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT ARRAY_JOIN(event_kind) AS k FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    // ------------------------------------------- text composition (concat / to_string)

    @Test
    void theTextFunctionsParseAsFunctionCalls() {
        // Measured, not assumed, as ARRAY_JOIN's and IF's non-reserved status is: a Calcite upgrade that
        // reserved one of these names would fail here rather than in every rendered statement carrying it.
        assertThat(reservedWords().isReservedWord("CONCAT")).isFalse();
        assertThat(reservedWords().isReservedWord("TO_STRING")).isFalse();
        assertThat(reservedWords().isReservedWord("ARRAY_TO_STRING")).isFalse();
        assertThat(reservedWords().isReservedWord("ARRAY_SLICE")).isFalse();
    }

    @Test
    void variadicConcatTranslatesOverMixedOperandTypes() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT CONCAT(project_id, ' -> ', total_tokens) AS edge FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("concat", false, List.of(
                        new FieldExpr("project_id"),
                        new ValueExpr(ValueType.STRING, " -> "),
                        new FieldExpr("total_tokens"))),
                "edge"));
        assertThat(translation.columns()).containsExactly(new ResultColumn("edge", ResultColumnKind.STRING));
    }

    @Test
    void theConcatenationOperatorLowersOntoConcat() {
        // || is bound by the grammar to an operator whose type checker is string-only, so both halves of the
        // treatment matter: the pre-validation swap AND the ANY-operand signature it swaps onto.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT project_id || ' / ' || event_kind AS label FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("concat", false, List.of(
                        new FnExpr("concat", false, List.of(
                                new FieldExpr("project_id"), new ValueExpr(ValueType.STRING, " / "))),
                        new FieldExpr("event_kind"))),
                "label"));
    }

    @Test
    void theConcatenationOperatorTakesNonTextOperand() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT 'depth ' || total_tokens AS label FROM dial_usage_log");
        assertThat(translation.columns()).containsExactly(new ResultColumn("label", ResultColumnKind.STRING));
    }

    @Test
    void toStringTranslatesAndTypesAsText() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT TO_STRING(total_tokens) AS t FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("to_string", false, List.of(new FieldExpr("total_tokens"))), "t"));
        assertThat(translation.columns()).containsExactly(new ResultColumn("t", ResultColumnKind.STRING));
    }

    @Test
    void castToCharacterTypeBecomesToString() {
        // The spelling generated SQL emits for a label; accepting TO_STRING while refusing this would leave
        // a surface only the DSL->SQL renderer can write.
        final SqlTranslation translation =
                frontEnd.translate("SELECT CAST(total_tokens AS VARCHAR) AS t FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("to_string", false, List.of(new FieldExpr("total_tokens"))), "t"));
    }

    @Test
    void castOfTextToTextStaysTheBareColumn() {
        // The identity case. The rewrite runs before validation, where no operand has a type yet, so it
        // cannot decide this one — which is why it emits a marker operator and the decision is taken here,
        // on the typed plan: a cast of text to text translates to the column alone, exactly as it did
        // before to_string existed. Wrapping it would answer the same values and a worse statement, as the
        // two positional tests below show.
        final SqlTranslation translation =
                frontEnd.translate("SELECT CAST(project_id AS VARCHAR) AS p FROM dial_usage_log");
        assertThat(translation.query().select())
                .containsExactly(new OutputColumn(new FieldExpr("project_id"), "p"));
    }

    @Test
    void castOfTextToTextInFilterStaysTheBareColumn() {
        // Where the identity case earns its keep: project_id is the leading column of the table's ORDER BY
        // and a LowCardinality(String), so a toString() wrapper here would put a computed expression on the
        // primary-key side of the comparison for nothing.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE CAST(project_id AS VARCHAR) = 'p1'");
        assertThat(translation.query().filter()).isEqualTo(comparison(
                ComparisonOp.EQ, new FieldExpr("project_id"), new ValueExpr(ValueType.STRING, "p1")));
    }

    @Test
    void castOfTextToTextAsGroupKeyStaysTheBareColumn() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT CAST(deployment AS VARCHAR) AS d, count(*) AS n FROM dial_usage_log GROUP BY d");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FieldExpr("deployment"), "d"),
                new OutputColumn(new FnExpr("count", false, List.of()), "n"));
        assertThat(translation.query().groupBy()).containsExactly("deployment");
    }

    @Test
    void castOfNonTextValueToTextIsToStringInEveryPosition() {
        // The other half of the marker's contract: where the rendering is real, it is a to_string call
        // wherever the caller wrote it — not only in the select list.
        assertThat(frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE CAST(total_tokens AS VARCHAR) = '42'")
                .query().filter())
                .isEqualTo(comparison(ComparisonOp.EQ,
                        new FnExpr("to_string", false, List.of(new FieldExpr("total_tokens"))),
                        new ValueExpr(ValueType.STRING, "42")));
    }

    @Test
    void unnamedCastAndConcatenationKeepThePostgresPlaceholderName() {
        // A derived name is read off the spelling the CALLER wrote, which is why the text re-binder runs
        // after alias injection rather than before it. With the two the other way round the injector saw
        // the operators the rewrite introduces and named an unaliased cast `to_string_cast` — an internal
        // marker's name, in a result column, an Arrow schema and the document's `as` — where this
        // statement, accepted long before to_string existed, has always answered `?column?`.
        final SqlTranslation cast = frontEnd.translate("SELECT CAST(project_id AS VARCHAR) FROM dial_usage_log");
        assertThat(cast.columns()).extracting(ResultColumn::name).containsExactly(OutputColumnNaming.UNNAMED);
        assertThat(cast.query().select())
                .containsExactly(new OutputColumn(new FieldExpr("project_id"), OutputColumnNaming.UNNAMED));
        // The rendering case names the same way: PostgreSQL names a cast after neither the target type nor
        // a function, and `||` is an operator, so both are `?column?` and not `to_string`/`concat`.
        assertThat(frontEnd.translate("SELECT CAST(total_tokens AS VARCHAR) FROM dial_usage_log").columns())
                .extracting(ResultColumn::name).containsExactly(OutputColumnNaming.UNNAMED);
        assertThat(frontEnd.translate("SELECT project_id || '-' FROM dial_usage_log").columns())
                .extracting(ResultColumn::name).containsExactly(OutputColumnNaming.UNNAMED);
    }

    @Test
    void theCastMarkerIsNotAnInboundFunctionName() {
        // TO_STRING_CAST is registered in the operator table so the validator can resolve the call the
        // pre-validation rewrite introduces, which also makes the name resolvable in a statement a client
        // wrote. It is in neither the DSL catalog nor GET /v1/queries/functions, and the subset rejects a
        // function outside that catalog, so the spelling is refused rather than silently meaning a cast --
        // over a non-text operand (where it would answer to_string) and over a text one (where the call
        // would vanish into the bare column) alike, in either casing.
        for (final String sql : List.of(
                "SELECT TO_STRING_CAST(total_tokens) AS t FROM dial_usage_log",
                "SELECT to_string_cast(project_id) AS t FROM dial_usage_log",
                "SELECT event_kind FROM dial_usage_log WHERE TO_STRING_CAST(total_tokens) = '42'")) {
            assertThatThrownBy(() -> frontEnd.translate(sql))
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessageContaining("unsupported function");
        }
    }

    @Test
    void castToNonCharacterTypeIsStillRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT CAST(project_id AS INTEGER) AS p FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("CAST");
    }

    @Test
    void concatWithOneOperandIsRejected() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT CONCAT(project_id) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void theTextFunctionsRejectProjectionOnlyOperand() {
        // These two declare ANY operands on purpose, so nothing upstream refuses a container: the guard is
        // theirs, and it has to fire at translation or translate-sql would answer with a document that only
        // fails when executed.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT CONCAT(execution_path, 'x') FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("execution_path");
        // QueryValidationException rather than SqlTranslationException, as it already is for an
        // array-valued call in a later argument of an array accessor: the array-returning name set is the
        // DSL's own classification and raises the DSL's exception. Both render as 400 `bad_request`.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT TO_STRING(SPLIT_STRING(event_kind, ',')) FROM dial_usage_log"))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("returns an array");
    }

    @Test
    void validatorInjectedCastToTextIsStillRejected() {
        // The trap the cast rewrite has to avoid: implicit coercion injects CAST(<non-text> AS VARCHAR) to
        // fit a text-family operand, so lowering every cast-to-character onto to_string would quietly widen
        // every text function to accept anything. Only what the CALLER wrote becomes to_string.
        assertThatThrownBy(() -> frontEnd.translate("SELECT not_empty(total_tokens) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
        assertThat(frontEnd.translate("SELECT not_empty(CAST(total_tokens AS VARCHAR)) AS present"
                + " FROM dial_usage_log").query().select())
                .containsExactly(new OutputColumn(new FnExpr("not_empty", false, List.of(
                        new FnExpr("to_string", false, List.of(new FieldExpr("total_tokens"))))), "present"));
    }

    // ------------------------------------------- array text and slicing

    @Test
    void arrayToStringOverAnArrayFieldTypesAsText() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT ARRAY_TO_STRING(execution_path, '>') AS path FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("array_to_string", false, List.of(
                        new FieldExpr("execution_path"), new ValueExpr(ValueType.STRING, ">"))),
                "path"));
        assertThat(translation.columns()).containsExactly(new ResultColumn("path", ResultColumnKind.STRING));
    }

    @Test
    void arrayToStringOverAnAggregateArrayGroupsByItsAlias() {
        // The motivating cell: a group's distinct values rendered as text. Grouping by the alias of an
        // array-RETURNING call is refused; this returns text, so it is outside that rule by construction.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT ARRAY_TO_STRING(execution_path, '>') AS path, count(*) AS n"
                        + " FROM dial_usage_log GROUP BY path");
        assertThat(translation.query().groupBy()).containsExactly("path");
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("path", ResultColumnKind.STRING),
                new ResultColumn("n", ResultColumnKind.INT64));
    }

    @Test
    void arraySliceProjectsAsAnArrayOfItsOperandsElement() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT ARRAY_SLICE(execution_path, 1, 3) AS head FROM dial_usage_log");
        assertThat(translation.columns())
                .containsExactly(new ResultColumn("head", ResultColumnKind.ARRAY, ResultColumnKind.STRING));
    }

    @Test
    void sliceComposesInsideTheArrayOperandFunctions() {
        assertThat(frontEnd.translate("SELECT ARRAY_TO_STRING(ARRAY_SLICE(GROUP_UNIQ_ARRAY(project_id), 1, 6),"
                + " ', ') AS models FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("models", ResultColumnKind.STRING));
        assertThat(frontEnd.translate(
                "SELECT ARRAY_LENGTH(ARRAY_SLICE(execution_path, 1, 3)) AS n FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("n", ResultColumnKind.INT64));
    }

    @Test
    void anExpansionOfSliceCarriesTheOperandsElementKind() {
        // A slice narrows an array rather than producing one, so the element kind recurses instead of being
        // fixed at string: typing this text would hand the Arrow writer the wrong writer.
        assertThat(frontEnd.translate("SELECT ARRAY_JOIN(ARRAY_SLICE(GROUP_UNIQ_ARRAY(total_tokens), 1, 3))"
                + " AS v FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("v", ResultColumnKind.INT64));
        assertThat(frontEnd.translate("SELECT ARRAY_JOIN(ARRAY_SLICE(execution_path, 1, 3))"
                + " AS step FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("step", ResultColumnKind.STRING));
    }

    @Test
    void sliceAliasIsRefusedAsGroupKey() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT ARRAY_SLICE(execution_path, 1, 3) AS head,"
                + " count(*) FROM dial_usage_log GROUP BY head"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("array_slice");
    }

    @Test
    void computedDelimiterOrBoundIsRejected() {
        // Literal-ness is checked here so the emitted document is well-formed; what the literal may BE is
        // the DSL translator's single rule.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT ARRAY_TO_STRING(execution_path, event_kind) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("literal");
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT ARRAY_SLICE(execution_path, 1, total_tokens) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("literal");
    }

    @Test
    void theArrayFunctionsRejectNonArrayOperand() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT ARRAY_TO_STRING(event_kind, ',') FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT ARRAY_SLICE(event_kind, 1, 3) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    private static SqlAbstractParserImpl.Metadata reservedWords() {
        return SqlBabelParserImpl.FACTORY.getParser(new StringReader("")).getMetadata();
    }

    @Test
    void identifierMatchingIsCaseInsensitive() {
        final SqlTranslation translation = frontEnd.translate("SELECT EVENT_KIND FROM DIAL_USAGE_LOG");
        assertThat(translation.query().entity()).isEqualTo("dial_usage_log");
        assertThat(((FieldExpr) translation.query().select().getFirst().expr()).name()).isEqualTo("event_kind");
    }

    // ------------------------------------------------------------- round trips

    @Test
    void rowQueryWithDefaults() {
        final StructuredQuery query = frontEnd.translate("SELECT event_kind FROM dial_usage_log").query();
        assertThat(query.mode()).isEqualTo(QueryMode.ROW);
        assertThat(query.filter()).isNull();
        assertThat(query.page()).isNull();
        assertThat(query.sort()).isNull();
        assertThat(query.distinct()).isFalse();
    }

    @Test
    void aggregateQueryRoundTrips() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT deployment, count(*) AS n FROM dial_usage_log "
                        + "WHERE total_price > 0 GROUP BY deployment HAVING count(*) > 10 "
                        + "ORDER BY n DESC LIMIT 50");
        final StructuredQuery query = translation.query();
        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.groupBy()).containsExactly("deployment");
        // The group key's name is what the DSL derives for a plain field anyway, so no alias is
        // carried; only the caller's own `AS n` is.
        assertThat(query.select()).containsExactly(
                new OutputColumn(new FieldExpr("deployment"), null),
                new OutputColumn(new FnExpr("count", false, List.of()), "n"));
        assertThat(query.filter()).isEqualTo(comparison(
                ComparisonOp.GT, new FieldExpr("total_price"), new ValueExpr(ValueType.DECIMAL, "0")));
        assertThat(query.having()).isEqualTo(comparison(
                ComparisonOp.GT, new FnExpr("count", false, List.of()), new ValueExpr(ValueType.INTEGER, "10")));
        assertThat(query.sort()).hasSize(1);
        assertThat(query.sort().getFirst().field()).isEqualTo("n");
        assertThat(query.sort().getFirst().dir()).isEqualTo(SortDir.DESC);
        assertThat(query.page()).isEqualTo(new OffsetPage(0, 50, false));
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("deployment", ResultColumnKind.STRING),
                new ResultColumn("n", ResultColumnKind.INT64));
    }

    @Test
    void selectDistinctMapsToDistinctFlag() {
        final StructuredQuery query = frontEnd.translate("SELECT DISTINCT deployment FROM dial_usage_log").query();
        assertThat(query.mode()).isEqualTo(QueryMode.ROW);
        assertThat(query.distinct()).isTrue();
        assertThat(query.select()).containsExactly(new OutputColumn(new FieldExpr("deployment"), null));
    }

    @Test
    void groupByWithoutAggregatesStaysAggregateMode() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT deployment, count(*) AS n FROM dial_usage_log GROUP BY deployment").query();
        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.distinct()).isFalse();
    }

    @Test
    void inListTranslatesToInOperator() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment IN ('a', 'b', 'c')").query();
        assertThat(query.filter()).isEqualTo(new ComparisonNode(ComparisonOp.IN, List.of(
                new FieldExpr("deployment"),
                new ArrayExpr(List.of(
                        new ValueExpr(ValueType.STRING, "a"),
                        new ValueExpr(ValueType.STRING, "b"),
                        new ValueExpr(ValueType.STRING, "c"))))));
    }

    @Test
    void rangeConjunctionExpands() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE total_price > 10 AND total_price <= 20").query();
        assertThat(query.filter()).isEqualTo(new LogicalNode(LogicalOp.AND, List.of(
                comparison(ComparisonOp.GT, new FieldExpr("total_price"), new ValueExpr(ValueType.DECIMAL, "10")),
                comparison(ComparisonOp.LE, new FieldExpr("total_price"), new ValueExpr(ValueType.DECIMAL, "20")))));
    }

    @Test
    void betweenExpandsToBounds() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE response_status BETWEEN 200 AND 299").query();
        assertThat(query.filter()).isEqualTo(new LogicalNode(LogicalOp.AND, List.of(
                comparison(ComparisonOp.GE, new FieldExpr("response_status"), new ValueExpr(ValueType.INTEGER, "200")),
                comparison(ComparisonOp.LE, new FieldExpr("response_status"), new ValueExpr(ValueType.INTEGER, "299")))));
    }

    @Test
    void notInTranslatesToNotIn() {
        final FilterNode filter = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment NOT IN ('a', 'b')").query().filter();
        assertThat(filter).isEqualTo(new LogicalNode(LogicalOp.NOT, List.of(
                new ComparisonNode(ComparisonOp.IN, List.of(
                        new FieldExpr("deployment"),
                        new ArrayExpr(List.of(
                                new ValueExpr(ValueType.STRING, "a"),
                                new ValueExpr(ValueType.STRING, "b"))))))));
    }

    @Test
    void likeContainsPatternTranslatesToCo() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment LIKE '%gpt%'").query();
        assertThat(query.filter()).isEqualTo(new ComparisonNode(ComparisonOp.CO, List.of(
                new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt"))));
    }

    @Test
    void notLikeTranslatesViaNot() {
        final FilterNode filter = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment NOT LIKE '%gpt%'").query().filter();
        // NOT LIKE may arrive as NOT(LIKE) or a negated LIKE operator; both are acceptable DSL forms.
        final ComparisonNode nc = new ComparisonNode(ComparisonOp.NC, List.of(
                new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt")));
        final LogicalNode notCo = new LogicalNode(LogicalOp.NOT, List.of(new ComparisonNode(
                ComparisonOp.CO, List.of(new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt")))));
        assertThat(filter).isIn(nc, notCo);
    }

    @Test
    void ilikeContainsPatternTranslatesToIco() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment ILIKE '%gpt%'").query();
        assertThat(query.filter()).isEqualTo(new ComparisonNode(ComparisonOp.ICO, List.of(
                new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt"))));
    }

    @Test
    void notIlikeTranslatesToIncOrNegatedIco() {
        final FilterNode filter = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment NOT ILIKE '%gpt%'").query().filter();
        // NOT ILIKE may arrive as NOT(ILIKE) or a negated ILIKE operator; both are acceptable DSL forms.
        final ComparisonNode inc = new ComparisonNode(ComparisonOp.INC, List.of(
                new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt")));
        final LogicalNode notIco = new LogicalNode(LogicalOp.NOT, List.of(new ComparisonNode(
                ComparisonOp.ICO, List.of(new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt")))));
        assertThat(filter).isIn(inc, notIco);
    }

    @Test
    void prefixLikePatternRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment LIKE 'gpt%'"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("contains patterns");
    }

    @Test
    void prefixIlikePatternRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment ILIKE 'gpt%'"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("contains patterns");
    }

    @Test
    void isNullTranslatesToEqNull() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment IS NULL").query();
        assertThat(query.filter()).isEqualTo(new ComparisonNode(ComparisonOp.EQ, List.of(
                new FieldExpr("deployment"), new ValueExpr(ValueType.NULL, null))));
    }

    @Test
    void timestampLiteralBecomesEpochMillis() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE request_time >= TIMESTAMP '2026-07-01 00:00:00'").query();
        final long expected = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli();
        assertThat(query.filter()).isEqualTo(comparison(
                ComparisonOp.GE, new FieldExpr("request_time"),
                new ValueExpr(ValueType.TIMESTAMP, Long.toString(expected))));
    }

    @Test
    void dateLiteralAgainstTimestampFieldWidensToMidnightUtc() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE request_time >= DATE '2026-07-01'").query();
        final long expected = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli();
        assertThat(query.filter()).isEqualTo(comparison(
                ComparisonOp.GE, new FieldExpr("request_time"),
                new ValueExpr(ValueType.TIMESTAMP, Long.toString(expected))));
    }

    @Test
    void percentileTranslates() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY total_price) AS p FROM dial_usage_log").query();
        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.select()).containsExactly(new OutputColumn(
                new FnExpr("percentile_cont", false, List.of(
                        new ValueExpr(ValueType.DECIMAL, "0.5"), new FieldExpr("total_price"))),
                "p"));
    }

    @Test
    void groupByAliasResolves() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT date_bin(5, 'minute', request_time) AS bucket, count(*) AS n FROM dial_usage_log "
                        + "GROUP BY bucket").query();
        assertThat(query.groupBy()).containsExactly("bucket");
    }

    @Test
    void uuidFieldComparisonRetypesStringLiteral() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE event_id = '00000000-0000-4000-8000-000000000001'").query();
        assertThat(query.filter()).isEqualTo(new ComparisonNode(ComparisonOp.EQ, List.of(
                new FieldExpr("event_id"),
                new ValueExpr(ValueType.UUID, "00000000-0000-4000-8000-000000000001"))));
    }

    @Test
    void longFieldComparisonRetypesIntegerLiteral() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE prompt_tokens > 100").query();
        assertThat(query.filter()).isEqualTo(new ComparisonNode(ComparisonOp.GT, List.of(
                new FieldExpr("prompt_tokens"), new ValueExpr(ValueType.LONG, "100"))));
    }

    @Test
    void bareBooleanColumnPredicate() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE success").query();
        assertThat(query.filter()).isEqualTo(new ComparisonNode(ComparisonOp.EQ, List.of(
                new FieldExpr("success"), new ValueExpr(ValueType.BOOLEAN, "true"))));
    }

    @Test
    void orderByUnselectedColumnSortsByFieldName() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log ORDER BY request_time").query();
        assertThat(query.sort()).hasSize(1);
        assertThat(query.sort().getFirst().field()).isEqualTo("request_time");
        assertThat(query.sort().getFirst().dir()).isEqualTo(SortDir.ASC);
        // The trim projection above the sort must not leak the sort key into the select list.
        assertThat(query.select()).containsExactly(new OutputColumn(new FieldExpr("event_kind"), null));
    }

    @Test
    void offsetAndLimitMapToOffsetPage() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log LIMIT 10 OFFSET 20").query();
        assertThat(query.page()).isEqualTo(new OffsetPage(20, 10, false));
    }

    @Test
    void dateBinGroupKeyResolvesThroughSelectAlias() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT date_bin(5, 'minute', request_time) AS bucket, count(*) AS n FROM dial_usage_log "
                        + "GROUP BY date_bin(5, 'minute', request_time)").query();
        final FnExpr bucket = new FnExpr("date_bin", false, List.of(
                new ValueExpr(ValueType.INTEGER, "5"),
                new ValueExpr(ValueType.STRING, "minute"),
                new FieldExpr("request_time")));
        assertThat(query.groupBy()).containsExactly("bucket");
        assertThat(query.select()).containsExactly(
                new OutputColumn(bucket, "bucket"),
                new OutputColumn(new FnExpr("count", false, List.of()), "n"));
    }

    @Test
    void dateTruncLowersToTheDslCalendarTruncation() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT date_trunc('month', request_time) AS m, count(*) AS n FROM dial_usage_log "
                        + "GROUP BY date_trunc('month', request_time)").query();
        assertThat(query.select()).containsExactly(
                new OutputColumn(new FnExpr("date_trunc", false, List.of(
                        new ValueExpr(ValueType.STRING, "month"),
                        new FieldExpr("request_time"))), "m"),
                new OutputColumn(new FnExpr("count", false, List.of()), "n"));
    }

    @Test
    void dateTruncRejectsCalcitesBigQueryOperandOrder() {
        // Calcite's own library ships DATE_TRUNC(date, unit) — operands reversed from the DSL's. Our
        // operator table is chained ahead of the standard one so the DSL signature wins; if that ever
        // stopped being true, the BigQuery form would start parsing and silently mean something else.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT date_trunc(request_time, 'month') FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void computedGroupKeyNamedAfterAnEntityColumnIsRejected() {
        // The DSL can only say "group by <name>", and a group-by name that is also an entity column
        // resolves to the column — so this statement must fail closed rather than execute grouped by
        // the unrelated "deployment" column.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT date_bin(1, 'hour', request_time) AS deployment, count(*) FROM dial_usage_log "
                        + "GROUP BY date_bin(1, 'hour', request_time)"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("'deployment'")
                .hasMessageContaining("also a column");
    }

    @Test
    void computedGroupKeyNamedAfterTheGroupedColumnIsRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT date_bin(1, 'hour', request_time) AS request_time, count(*) FROM dial_usage_log "
                        + "GROUP BY date_bin(1, 'hour', request_time)"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("'request_time'")
                .hasMessageContaining("also a column");
    }

    @Test
    void groupByColumnShadowedByAnAliasOfAnotherExpressionIsRejected() {
        // Two grouped columns projected under each other's names: the DSL group_by keys "deployment" and
        // "parent_deployment" would each be both a column and the alias of a different expression, a
        // shape the DSL rejects as ambiguous — so fail closed here with the SQL-level reason.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT deployment AS parent_deployment, parent_deployment AS deployment, count(*) "
                        + "FROM dial_usage_log GROUP BY deployment, parent_deployment"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("different expression");
    }

    @Test
    void groupByColumnProjectedUnderItsOwnNameStaysAccepted() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT request_time AS request_time, count(*) FROM dial_usage_log GROUP BY request_time").query();
        assertThat(query.groupBy()).containsExactly("request_time");
    }

    @Test
    void computedGroupKeyPrefersAnOutputNameThatIsNotAnEntityColumn() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT date_bin(1, 'hour', request_time) AS deployment, "
                        + "date_bin(1, 'hour', request_time) AS bucket, count(*) FROM dial_usage_log "
                        + "GROUP BY date_bin(1, 'hour', request_time)").query();
        assertThat(query.groupBy()).containsExactly("bucket");
    }

    @Test
    void widthBucketTranslates() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT width_bucket(total_price, 0, 100, 10) AS b FROM dial_usage_log").query();
        assertThat(query.select()).containsExactly(new OutputColumn(
                new FnExpr("width_bucket", false, List.of(
                        new FieldExpr("total_price"),
                        new ValueExpr(ValueType.INTEGER, "0"),
                        new ValueExpr(ValueType.INTEGER, "100"),
                        new ValueExpr(ValueType.INTEGER, "10"))),
                "b"));
    }

    @Test
    void countDistinctCarriesDistinctFlag() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT count(DISTINCT deployment) AS n FROM dial_usage_log").query();
        assertThat(query.select()).containsExactly(new OutputColumn(
                new FnExpr("count", true, List.of(new FieldExpr("deployment"))), "n"));
        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
    }

    @Test
    void scalarFunctionsTranslate() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT lower(event_kind) AS k, char_length(deployment) AS l, abs(total_price) AS a, "
                        + "trim(request_method) AS t FROM dial_usage_log").query();
        assertThat(query.select()).containsExactly(
                new OutputColumn(new FnExpr("lower", false, List.of(new FieldExpr("event_kind"))), "k"),
                new OutputColumn(new FnExpr("length", false, List.of(new FieldExpr("deployment"))), "l"),
                new OutputColumn(new FnExpr("abs", false, List.of(new FieldExpr("total_price"))), "a"),
                new OutputColumn(new FnExpr("trim", false, List.of(new FieldExpr("request_method"))), "t"));
    }

    @Test
    void everyLengthSpellingTranslatesToTheDslLengthFunction() {
        // The DSL catalog spelling `length` must be accepted alongside the standard synonyms — the
        // DSL→SQL renderer emits it verbatim, so rejecting it would break the round-trip guarantee.
        for (final String spelling : List.of("length", "char_length", "character_length")) {
            final StructuredQuery query = frontEnd.translate(
                    "SELECT " + spelling + "(deployment) AS l FROM dial_usage_log").query();
            assertThat(query.select()).as("spelling %s", spelling).containsExactly(
                    new OutputColumn(new FnExpr("length", false, List.of(new FieldExpr("deployment"))), "l"));
        }
    }

    @Test
    void jsonExtractFunctionsTranslateWithTheirFixedResultKinds() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT json_extract_string(request_method, 'model') AS s, "
                        + "json_extract_long(request_method, 'usage', 'total_tokens') AS n, "
                        + "json_extract_double(request_method, 'temperature') AS d, "
                        + "json_extract_bool(request_method, 'stream') AS b FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(
                new OutputColumn(new FnExpr("json_extract_string", false, List.of(
                        new FieldExpr("request_method"), new ValueExpr(ValueType.STRING, "model"))), "s"),
                new OutputColumn(new FnExpr("json_extract_long", false, List.of(
                        new FieldExpr("request_method"),
                        new ValueExpr(ValueType.STRING, "usage"),
                        new ValueExpr(ValueType.STRING, "total_tokens"))), "n"),
                new OutputColumn(new FnExpr("json_extract_double", false, List.of(
                        new FieldExpr("request_method"), new ValueExpr(ValueType.STRING, "temperature"))), "d"),
                new OutputColumn(new FnExpr("json_extract_bool", false, List.of(
                        new FieldExpr("request_method"), new ValueExpr(ValueType.STRING, "stream"))), "b"));
        // Result kinds are fixed per function name, independent of the operands.
        assertThat(translation.columns()).extracting(ResultColumn::kind).containsExactly(
                ResultColumnKind.STRING, ResultColumnKind.INT64, ResultColumnKind.FLOAT64, ResultColumnKind.BOOL);
    }

    @Test
    void jsonExtractInFilterTranslates() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE json_extract_string(request_method, 'model') = 'gpt-4o'")
                .query();
        assertThat(query.filter()).isEqualTo(new ComparisonNode(ComparisonOp.EQ, List.of(
                new FnExpr("json_extract_string", false, List.of(
                        new FieldExpr("request_method"), new ValueExpr(ValueType.STRING, "model"))),
                new ValueExpr(ValueType.STRING, "gpt-4o"))));
    }

    @Test
    void jsonExtractWithoutPathKeyIsRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT json_extract_string(request_method) AS s FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void jsonExtractWithComputedPathIsRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT json_extract_string(request_method, deployment) AS s FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("string literal path keys");
    }

    // ---------------------------------------------------------- fail closed

    @Test
    void joinHasNoDslDocumentButPlans() {
        // A join executes (see SqlCompositeTranslationTest) but has no StructuredQuery: translate() refuses it
        // with the composite message, and plan() hands back the composite plan instead.
        final String sql = "SELECT a.event_kind FROM dial_usage_log a JOIN dial_usage_log b ON a.event_id = b.event_id";
        assertThatThrownBy(() -> frontEnd.translate(sql))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT);
        assertThat(frontEnd.plan(sql)).isInstanceOf(SqlCompositeTranslation.class);
    }

    @Test
    void setOperationExecutesButHasNoDslDocument() {
        final String sql = "SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log";
        assertThatThrownBy(() -> frontEnd.translate(sql))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT);
        assertThat(frontEnd.plan(sql)).isInstanceOf(SqlCompositeTranslation.class);
    }

    @Test
    void bareUnionRejectedBecauseTheDatabaseRejectsIt() {
        // ClickHouse's union_default_mode has no default, so a bare UNION is an error there. Accepting it
        // as DISTINCT would return rows for a statement the database refuses.
        assertThatThrownBy(() -> frontEnd.plan(
                "SELECT event_kind FROM dial_usage_log UNION SELECT event_kind FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("UNION requires ALL or DISTINCT");
    }

    @Test
    void minusRejectedBecauseTheDatabaseHasNoSuchKeyword() {
        assertThatThrownBy(() -> frontEnd.plan(
                "SELECT event_kind FROM dial_usage_log MINUS SELECT event_kind FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("MINUS is not supported");
    }

    @Test
    void plainCteFlattensIntoTheSingleBlock() {
        // Calcite inlines the CTE at its reference, and a projection over a scan folds into one block — so a
        // CTE that only names a single-entity select still has a DSL document. A CTE that carries a limit or an
        // aggregate becomes a derived table instead (see SqlCompositeTranslationTest).
        final SqlTranslation translation = frontEnd.translate(
                "WITH x AS (SELECT event_kind FROM dial_usage_log) SELECT * FROM x");
        assertThat(translation.query().entity()).isEqualTo(DialUsageLogTestModel.ENTITY);
        assertThat(translation.query().select()).containsExactly(new OutputColumn(new FieldExpr("event_kind"), null));
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("event_kind");
    }

    @Test
    void recursiveCteRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "WITH RECURSIVE r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 3) SELECT n FROM r"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("recursive");
    }

    @Test
    void recursiveCteRejectedUnderTopLevelOrderBy() {
        // Calcite wraps the ORDER BY around the WITH, not the other way round, so a screen that tests the
        // parsed statement for SqlWith without peeling the wrapper first lets this shape straight through.
        assertThatThrownBy(() -> frontEnd.translate(
                "WITH RECURSIVE r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 3)"
                        + " SELECT n FROM r ORDER BY n"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("recursive");
    }

    @Test
    void starInsideCteExpandsUnderTopLevelOrderBy() {
        // `WITH … SELECT … ORDER BY x` parses as SqlOrderBy(SqlWith(…)): the ORDER BY wraps the whole
        // query it sorts. Missing that unwrap left every star in the shape to Calcite, which expands
        // against the table's FULL row type — heavy columns included — and it is the shape BI tools
        // generate, since they append their own ORDER BY to whatever the user wrote.
        final List<String> unsorted = frontEnd
                .translate("WITH c AS (SELECT * FROM dial_usage_log) SELECT * FROM c")
                .columns().stream().map(ResultColumn::name).toList();
        final List<String> sorted = frontEnd
                .translate("WITH c AS (SELECT * FROM dial_usage_log) SELECT * FROM c ORDER BY event_id")
                .columns().stream().map(ResultColumn::name).toList();
        assertThat(sorted).isEqualTo(unsorted);
        assertThat(sorted).doesNotContain("dial_usage_log_payload.request_body", "dial_usage_log_payload.response_body", "dial_usage_log_payload.assembled_response")
                .contains("event_id", "request_body_bytes");
    }

    @Test
    void unnamedItemIsAliasedUnderCteWithTopLevelOrderBy() {
        // The same missed unwrap skipped alias injection for this shape, leaving Calcite's EXPR$<n> to be
        // recovered downstream instead of the injected name.
        // An operator expression is named `?column?` by the injector and `multiply` by the downstream
        // recovery, so the two paths are told apart by the name that comes back.
        final SqlTranslation translation = frontEnd.translate(
                "WITH c AS (SELECT total_price FROM dial_usage_log) SELECT total_price * 2 FROM c ORDER BY 1");
        assertThat(translation.columns()).extracting(ResultColumn::name)
                .containsExactly(OutputColumnNaming.UNNAMED);
    }

    @Test
    void arithmeticLowersToTheCatalogFunctions() {
        // Arithmetic used to be outside the subset; it now lowers to the catalog's own names, so the
        // operator spelling and the DSL spelling are one function. No operator node enters the DSL.
        assertThat(frontEnd.translate("SELECT total_price * 2 FROM dial_usage_log").query().select())
                .singleElement()
                .satisfies(item -> assertThat(item.expr())
                        .isInstanceOfSatisfying(FnExpr.class,
                                fn -> assertThat(fn.name()).isEqualTo("multiply")));
    }

    @Test
    void everyArithmeticOperatorLowersToItsCatalogName() {
        assertThat(lowered("prompt_tokens + 1")).isEqualTo("plus");
        assertThat(lowered("prompt_tokens - 1")).isEqualTo("minus");
        assertThat(lowered("prompt_tokens * 2")).isEqualTo("multiply");
        assertThat(lowered("prompt_tokens / 2")).isEqualTo("divide-guarded");
        assertThat(lowered("prompt_tokens % 2")).isEqualTo("modulo");
        // Calcite's standard MOD is a second inbound spelling of the same operation.
        assertThat(lowered("MOD(prompt_tokens, 2)")).isEqualTo("modulo");
    }

    @Test
    void unaryMinusLowersToMinusAgainstZero() {
        // Calcite names unary and binary minus alike, so the arity decides. Getting this wrong would
        // build a two-argument call with one operand.
        assertThat(frontEnd.translate("SELECT -prompt_tokens FROM dial_usage_log").query().select())
                .singleElement()
                .satisfies(item -> assertThat(item.expr())
                        .isInstanceOfSatisfying(FnExpr.class, fn -> {
                            assertThat(fn.name()).isEqualTo("minus");
                            assertThat(fn.args()).hasSize(2);
                            assertThat(fn.args().getFirst())
                                    .isInstanceOfSatisfying(ValueExpr.class,
                                            zero -> assertThat(zero.value()).isEqualTo("0"));
                        }));
    }

    @Test
    void arithmeticCatalogNamesAreNotSqlFunctions() {
        // MINUS is a reserved set operator in the Babel grammar, so minus(a, b) cannot parse as a
        // function call. Rather than ship four-of-five, none of the arithmetic catalog names is
        // registered: SQL spells arithmetic with operators. The DSL keeps the names.
        assertThatThrownBy(() -> frontEnd.translate("SELECT minus(prompt_tokens, 1) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
        assertThatThrownBy(() -> frontEnd.translate("SELECT plus(prompt_tokens, 1) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void floorAndCeilRefuseTheDatetimeForm() {
        // FLOOR(ts TO HOUR) is date_trunc's job; lowering it to floor(x) would discard the unit.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT FLOOR(request_time TO HOUR) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("date_trunc");
    }

    @Test
    void isoStringLiteralsCoerceAgainstTimestampColumn() {
        for (final String literal : List.of("2026-08-20", "2026-08-20 00:00:00", "2026-08-20T00:00:00Z")) {
            assertThatCode(() -> frontEnd.translate("SELECT event_id FROM dial_usage_log"
                    + " WHERE request_time >= '" + literal + "'"))
                    .as("time literal '%s'", literal)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void unparseableTimeStringIsRejectedClearly() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE request_time >= 'last tuesday'"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("ISO-8601");
    }

    /** The catalog name an arithmetic operator lowers to; {@code div} reports its guarded spelling. */
    private String lowered(String expression) {
        final var select = frontEnd.translate(
                "SELECT " + expression + " AS x FROM dial_usage_log").query().select();
        final FnExpr fn = (FnExpr) select.getFirst().expr();
        return "div".equals(fn.name()) ? "divide-guarded" : fn.name();
    }

    @Test
    void caseTranslatesToTheCaseExpression() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT CASE WHEN success THEN 'y' ELSE 'n' END AS k FROM dial_usage_log");
        assertThat(translation.query().select()).singleElement().satisfies(column -> {
            assertThat(column.expr()).isInstanceOf(CaseExpr.class);
            final CaseExpr caseExpr = (CaseExpr) column.expr();
            assertThat(caseExpr.when()).hasSize(1);
            assertThat(caseExpr.otherwise()).isEqualTo(new ValueExpr(ValueType.STRING, "n"));
        });
        assertThat(translation.columns()).containsExactly(new ResultColumn("k", ResultColumnKind.STRING));
    }

    @Test
    void explicitCrossFamilyCastRejected() {
        // A cast across families is still refused — except to a character type, which IS the to_string
        // call and is now accepted as a second spelling of it (see castToCharacterTypeBecomesToString).
        // The scenario keeps its historical name so the spec history can be matched to it.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT CAST(project_id AS DECIMAL) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("CAST");
        assertThat(frontEnd.translate("SELECT CAST(total_price AS VARCHAR) AS t FROM dial_usage_log")
                .query().select())
                .containsExactly(new OutputColumn(
                        new FnExpr("to_string", false, List.of(new FieldExpr("total_price"))), "t"));
    }

    @Test
    void wideningCastUnwrapped() {
        final StructuredQuery query = frontEnd.translate(
                "SELECT CAST(response_status AS BIGINT) AS s FROM dial_usage_log").query();
        assertThat(query.select()).containsExactly(new OutputColumn(new FieldExpr("response_status"), "s"));
    }

    @Test
    void parameterMarkerRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE deployment = ?"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void subqueryHasNoDslDocumentButPlans() {
        final String sql = "SELECT event_kind FROM dial_usage_log WHERE deployment IN (SELECT deployment FROM dial_usage_log)";
        assertThatThrownBy(() -> frontEnd.translate(sql))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT);
        assertThat(frontEnd.plan(sql)).isInstanceOf(SqlCompositeTranslation.class);
    }

    @Test
    void windowFunctionTranslatesToTheWindowExpression() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT row_number() OVER (ORDER BY request_time) FROM dial_usage_log");
        assertThat(translation.query().select()).singleElement().satisfies(column ->
                assertThat(column.expr()).isInstanceOf(WindowExpr.class));
        assertThat(translation.columns()).containsExactly(new ResultColumn("row_number", ResultColumnKind.INT64));
    }

    /**
     * A window inside a select-list {@code CASE} is still a window in {@code select}: the statement
     * translates, and — the property `query-translation-endpoint` promises — the document it hands back is
     * one {@code POST /v1/queries/execute} accepts, so the builder must render it rather than refuse it as a
     * predicate operand.
     */
    @Test
    void windowInsideSelectListCaseTranslatesAndBuilds() {
        final String sql = "SELECT CASE WHEN row_number() OVER (ORDER BY request_time) > 5 THEN 'late'"
                + " ELSE 'early' END AS phase FROM dial_usage_log";
        assertThat(frontEnd.plan(sql)).isInstanceOf(SqlTranslation.class);
        final SqlTranslation translation = frontEnd.translate(sql);
        assertThat(translation.query().select()).singleElement().satisfies(column ->
                assertThat(column.expr()).isInstanceOf(CaseExpr.class));

        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        final String rendered = new StructuredQueryBuilder(expr, new FilterTranslator(expr))
                .build(new QueryDslConfiguration().clickhouseRenderContext(),
                        DialUsageLogTestModel.model(), translation.query())
                .getSQL(ParamType.INLINED);
        assertThat(rendered).containsIgnoringCase("case when row_number() over (order by");
        assertThat(rendered).containsIgnoringCase("then 'late' else 'early' end \"phase\"");
    }

    @Test
    void groupingSetsRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT deployment, count(*) FROM dial_usage_log GROUP BY ROLLUP(deployment)"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void multiStatementRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log; SELECT deployment FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("one SQL statement");
    }

    @Test
    void nonSelectRejected() {
        assertThatThrownBy(() -> frontEnd.translate("DELETE FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("only SELECT");
    }

    @Test
    void unsupportedFunctionRejected() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT reverse(deployment) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void limitZeroRejected() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT event_kind FROM dial_usage_log LIMIT 0"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("LIMIT 0");
    }

    // ------------------------------------------------ PostgreSQL-shaped output names

    @Test
    void unnamedAggregateTakesTheFunctionName() {
        final SqlTranslation translation = frontEnd.translate("SELECT count(*) FROM dial_usage_log");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("count");
        // No alias is carried: the DSL derives the same name on its own.
        assertThat(translation.query().select())
                .containsExactly(new OutputColumn(new FnExpr("count", false, List.of()), null));
    }

    @Test
    void unnamedScalarCallTakesTheFunctionName() {
        final SqlTranslation translation = frontEnd.translate("SELECT lower(deployment) FROM dial_usage_log");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("lower");
        assertThat(translation.query().select()).allSatisfy(column -> assertThat(column.as()).isNull());
    }

    @Test
    void explicitAliasIsPreserved() {
        final SqlTranslation translation = frontEnd.translate("SELECT count(*) AS n FROM dial_usage_log");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("n");
        assertThat(translation.query().select())
                .containsExactly(new OutputColumn(new FnExpr("count", false, List.of()), "n"));
    }

    @Test
    void derivedNameCollidingWithAnAliasIsSuffixed() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT count(*), count(deployment) AS \"count\" FROM dial_usage_log");
        // The caller's alias keeps `count`; the unnamed aggregate moves out of its way. The unnamed entry
        // needs no `as`: with the alias reserved, the DSL's own naming lands it on `count_1` anyway.
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("count_1", "count");
        assertThat(translation.query().select()).extracting(OutputColumn::as).containsExactly(null, "count");
        assertThat(OutputColumnNaming.resolve(translation.query().select())).containsExactly("count_1", "count");
    }

    @Test
    void twoUnnamedAggregatesAreSuffixedByPosition() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT count(*), count(DISTINCT deployment) FROM dial_usage_log");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("count", "count_1");
    }

    @Test
    void unnamedLiteralTakesThePostgresPlaceholderName() {
        final SqlTranslation translation = frontEnd.translate("SELECT 1 FROM dial_usage_log");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("?column?");
    }

    @Test
    void sqlSynonymIsNamedAsWritten() {
        final SqlTranslation translation = frontEnd.translate("SELECT char_length(deployment) FROM dial_usage_log");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("char_length");
        // The DSL call is `length`, so the name the caller wrote is carried as an explicit alias.
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("length", false, List.of(new FieldExpr("deployment"))), "char_length"));
    }

    @Test
    void orderedSetAggregateIsNamedByTheAggregate() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY total_price) FROM dial_usage_log");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("percentile_cont");
    }

    @Test
    void derivedNameIsOrderableWithoutAnAlias() {
        // Valid PostgreSQL: a bare ORDER BY name resolves against the output columns.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT deployment, count(*) FROM dial_usage_log GROUP BY deployment ORDER BY count DESC");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("deployment", "count");
        assertThat(translation.query().sort()).hasSize(1);
        assertThat(translation.query().sort().getFirst().field()).isEqualTo("count");
        assertThat(translation.query().sort().getFirst().dir()).isEqualTo(SortDir.DESC);
    }

    @Test
    void derivedNameIsGroupableAndFilterableWithoutAnAlias() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT date_bin(1, 'hour', request_time), count(*) FROM dial_usage_log "
                        + "GROUP BY date_bin HAVING count(*) > 1");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("date_bin", "count");
        assertThat(translation.query().groupBy()).containsExactly("date_bin");
    }

    @Test
    void starDoesNotSuffixAnUnnamedSibling() {
        // The star is expanded into named column identifiers before the alias injector runs, so the
        // literal is the only unnamed item and takes the bare placeholder.
        final SqlTranslation translation = frontEnd.translate("SELECT *, 1 FROM dial_usage_log LIMIT 1");
        assertThat(translation.columns()).extracting(ResultColumn::name).contains(OutputColumnNaming.UNNAMED);
    }

    @Test
    void starProjectionKeepsItsColumnNames() {
        final SqlTranslation translation = frontEnd.translate("SELECT * FROM dial_usage_log LIMIT 1");
        assertThat(translation.columns()).extracting(ResultColumn::name)
                .contains("deployment", "total_price")
                .noneMatch(name -> name.startsWith("EXPR$"));
    }

    @Test
    void selectDistinctIsUnaffectedByAliasInjection() {
        final SqlTranslation translation = frontEnd.translate("SELECT DISTINCT lower(deployment) FROM dial_usage_log");
        assertThat(translation.query().distinct()).isTrue();
        assertThat(translation.query().mode()).isEqualTo(QueryMode.ROW);
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("lower");
    }

    @Test
    void explicitAliasCollidingWithAnotherEntrysDerivedSuffixIsKept() {
        // `count_1` is what the second unnamed count would derive on its own, so a naive
        // "is the alias redundant?" check would drop it — and the DSL would then execute to `count`.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT count(*) AS foo, count(deployment) AS count_1 FROM dial_usage_log");
        assertThat(translation.columns()).extracting(ResultColumn::name).containsExactly("foo", "count_1");
        assertThat(translation.query().select()).extracting(OutputColumn::as).containsExactly("foo", "count_1");
    }

    @Test
    void producedSelectAlwaysResolvesToTheReportedColumnNames() {
        // The invariant the whole optional-alias scheme rests on: the DSL document a SQL statement
        // translates to executes to exactly the names its ResultColumn descriptors report.
        final List<String> statements = List.of(
                "SELECT count(*) FROM dial_usage_log",
                "SELECT count(*) AS n FROM dial_usage_log",
                "SELECT count(*), count(DISTINCT deployment) FROM dial_usage_log",
                "SELECT count(*), count(deployment) AS \"count\" FROM dial_usage_log",
                "SELECT count(*) AS foo, count(deployment) AS count_1 FROM dial_usage_log",
                "SELECT count(*) AS count_1, count(deployment) FROM dial_usage_log",
                "SELECT count(*) AS count_2, count(deployment), count(event_kind) FROM dial_usage_log",
                "SELECT lower(deployment), upper(deployment) AS lower_1 FROM dial_usage_log",
                "SELECT char_length(deployment) FROM dial_usage_log",
                "SELECT 1, 2 FROM dial_usage_log",
                "SELECT deployment, deployment AS deployment_1 FROM dial_usage_log",
                "SELECT * FROM dial_usage_log LIMIT 1");
        for (final String sql : statements) {
            final SqlTranslation translation = frontEnd.translate(sql);
            assertThat(OutputColumnNaming.resolve(translation.query().select()))
                    .as("statement %s", sql)
                    .isEqualTo(translation.columns().stream().map(ResultColumn::name).toList());
        }
    }

    // -------------------------------------------------- parse-error message hygiene

    @Test
    void parseFailureReportsTheTokenAndPositionWithoutTheGrammar() {
        // The message a caller sees for a one-character mistake. Calcite's expectation set runs to some
        // fifty lines and displaces the one line that locates the mistake.
        assertThatThrownBy(() -> frontEnd.translate("SELECT * FROM dial_usage_log WHERE success = = true OR"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).contains("Encountered").contains("line 1, column");
                    assertThat(ex.getMessage()).doesNotContain("Was expecting one of:");
                    assertThat(ex.getMessage().lines()).hasSizeLessThan(5);
                });
    }

    @Test
    void parseFailureInTheStatementScreenIsTrimmedToo() {
        // screen() parses before translate() does, on its own call site — the trim lives in the shared
        // helper precisely so neither site can be the one that still dumps the grammar.
        assertThatThrownBy(() -> frontEnd.translate("SELECT FROM"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("SQL parse error")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("Was expecting one of:"));
    }

    @Test
    void validationFailureKeepsItsTargetedMessage() {
        // The boundary the trim must not cross: a validation message carries no expectation marker and
        // must arrive naming the offending column, exactly as before.
        assertThatThrownBy(() -> frontEnd.translate("SELECT no_such_column FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("no_such_column");
    }

    @Test
    void unsupportedConstructKeepsItsMessage() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT price * 2 FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("Was expecting one of:"));
    }

    @Test
    void reservedWordAliasIsNamedWithItsPositionAndFix() {
        // Calcite stops at the AS, because the optional-alias production needs two tokens of lookahead and
        // `rows` is not an <IDENTIFIER> — so the unaided message accuses a token that is perfectly correct.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT deployment, count(*) AS rows FROM dial_usage_log GROUP BY deployment"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).contains("'rows'").contains("reserved word");
                    // `rows` starts at column 32; Calcite's own diagnosis points at the AS, at 29.
                    assertThat(ex.getMessage()).contains("column 32");
                    // The parser's own diagnosis survives: it is the half that is certainly true.
                    assertThat(ex.getMessage()).contains("SQL parse error");
                });
    }

    @Test
    void reservedWordAsBareAliasIsNamedToo() {
        // No AS: here the parse stops on the offender itself, so the other branch of the candidate rule.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT deployment, count(*) rows FROM dial_usage_log GROUP BY deployment"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).contains("'rows'").contains("reserved word"));
    }

    @Test
    void quotingOrRenamingTheReservedAliasParses() {
        assertThatCode(() -> frontEnd.translate(
                "SELECT deployment, count(*) AS \"rows\" FROM dial_usage_log GROUP BY deployment"))
                .doesNotThrowAnyException();
        assertThatCode(() -> frontEnd.translate(
                "SELECT deployment, count(*) AS row_count FROM dial_usage_log GROUP BY deployment"))
                .doesNotThrowAnyException();
    }

    @Test
    void plausibleButNonReservedAliasesStillParse() {
        // The reason a caller cannot reason their way out of this unaided: none of these is reserved under
        // BABEL, so the hint must not fire for them and the statements must simply work.
        for (final String alias : List.of("first", "last", "turns", "tokens", "spend", "conversations")) {
            final String sql = "SELECT deployment, count(*) AS " + alias
                    + " FROM dial_usage_log GROUP BY deployment";
            assertThatCode(() -> frontEnd.translate(sql)).as("alias %s", alias).doesNotThrowAnyException();
        }
    }

    @Test
    void ordinarySyntaxErrorGetsNoReservedWordHint() {
        // A failure of another shape is reported exactly as before — an unrecognised shape is left
        // un-improved rather than decorated with a guess.
        assertThatThrownBy(() -> frontEnd.translate("SELECT * FROM dial_usage_log WHERE success = = true OR"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("reserved word"));
    }

    @Test
    void nestedBlockCarryingTrailingClauseIsNamedWithItsOwnPosition() {
        // The value of the hint: Calcite's diagnosis lands on the trailing clause, four lines from the
        // parenthesis that is actually wrong. The block's own position is what a caller can act on.
        final String sql = """
                SELECT event_kind FROM (
                  (SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 10)
                  UNION ALL
                  SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 10
                ) ORDER BY event_kind""";
        assertThatThrownBy(() -> frontEnd.translate(sql))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> {
                    // Calcite's own diagnosis survives, as it does for every other parse failure.
                    assertThat(ex.getMessage()).contains("SQL parse error").contains("line 4");
                    // The block opens at line 1, column 24 — `SELECT event_kind FROM (`.
                    assertThat(ex.getMessage()).contains("The parenthesis at line 1, column 24");
                    assertThat(ex.getMessage()).contains("parenthesise the block's last query too");
                    // And it stands in for the reserved-word sentence, whose advice here — quote `ORDER`
                    // as an identifier — names the one part of the statement that is correct.
                    assertThat(ex.getMessage()).doesNotContain("reserved word");
                });
    }

    @Test
    void theNestedBlockGapIsNotAboutSetOperations() {
        // Same grammar gap with no set operation in sight: a redundant parenthesis around the only query
        // in a derived table is enough, because it is the leading `(` that decides the reading.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT count(*) AS n FROM ((SELECT event_kind FROM dial_usage_log) ORDER BY event_kind)"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .contains("The parenthesis at line 1, column 27")
                        .contains("leave its first one unparenthesised"));
    }

    @Test
    void parenthesisInsideLiteralNeitherOpensNorStartsBlock() {
        // The scan has to read quoting, or the `(` in the literal would be counted as an open block and
        // the sentence would point at it. The block is still the one at column 27.
        assertThatThrownBy(() -> frontEnd.translate("SELECT count(*) AS n FROM ((SELECT event_kind FROM"
                + " dial_usage_log WHERE event_kind = '(') UNION ALL SELECT event_kind FROM dial_usage_log"
                + " LIMIT 2)"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .contains("The parenthesis at line 1, column 27"));
    }

    @Test
    void reservedWordInsideSuchBlockStillGetsReservedWordHint() {
        // The two hints must not compete for failures that are not this shape: here the parse stops on an
        // alias, not on a trailing clause, so the reserved-word sentence is still the right one.
        assertThatThrownBy(() -> frontEnd.translate("SELECT n FROM ((SELECT count(*) AS rows FROM"
                + " dial_usage_log) UNION ALL SELECT event_id AS n FROM dial_usage_log)"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).contains("'rows'").contains("reserved word");
                    assertThat(ex.getMessage()).doesNotContain("opens a block");
                });
    }

    @Test
    void clauseKeywordUsedAsIdentifierKeepsTheReservedWordHint() {
        // The case the structural facts alone get wrong, and the reason the claim is verified by deleting
        // the clause and re-parsing: the parse stops on `limit`, in a block of exactly the offending
        // shape, but `limit` is an identifier here and quoting it is the right advice. LIMIT, OFFSET and
        // FETCH need no second word, so there is no `ORDER BY`-style guard to lean on.
        for (final String keyword : List.of("limit", "offset", "fetch")) {
            final String sql = "SELECT n FROM ((SELECT event_kind FROM dial_usage_log) UNION ALL SELECT "
                    + keyword + " AS n FROM dial_usage_log)";
            assertThatThrownBy(() -> frontEnd.translate(sql))
                    .as("%s", sql)
                    .isInstanceOf(SqlTranslationException.class)
                    .satisfies(ex -> {
                        assertThat(ex.getMessage()).contains("reserved word");
                        assertThat(ex.getMessage()).doesNotContain("opens a block");
                    });
        }
        // Not only after SELECT: the same word is legal as a GROUP BY key, so a "preceded by SELECT" rule
        // would not have closed this.
        assertThatThrownBy(() -> frontEnd.translate("SELECT n FROM ((SELECT event_kind AS n FROM"
                + " dial_usage_log) UNION ALL SELECT event_kind AS n FROM dial_usage_log GROUP BY limit)"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("opens a block"));
    }

    @Test
    void parenthesizedValueListIsNotClaimedAsQueryBlock() {
        // Character-identical to the shape — a block whose content opens with another `(` and ends with a
        // trailing LIMIT — but it holds values, not queries, so reparenthesising its "last query" is
        // advice about something that does not exist.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE event_kind IN (('a'), ('b') LIMIT 3)"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("opens a block"));
    }

    @Test
    void orderKeywordWithNoByIsNotClaimedAsTrailingClause() {
        // `ORDER` opens a trailing clause only with `BY` behind it. Without one the failure is an ordinary
        // misuse of a reserved word, and the older hint keeps it.
        assertThatThrownBy(() -> frontEnd.translate("SELECT count(*) AS n FROM ((SELECT event_kind FROM"
                + " dial_usage_log) UNION ALL SELECT event_kind FROM dial_usage_log ORDER event_kind)"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("opens a block"));
    }

    @Test
    void ordinaryTrailingClauseFailureIsNotClaimedEither() {
        // No block is open where the parse stopped, so nothing is named: a top-level failure is left
        // exactly as it was.
        assertThatThrownBy(() -> frontEnd.translate("SELECT event_kind FROM dial_usage_log ORDER BY"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("opens a block"));
    }

    // ------------------------------------------- derivation expression functions

    @Test
    void everyDerivationFunctionNameIsAvailableToTheParser() {
        // IF, CONTAINS and EQUALS are keywords of the Babel grammar — non-reserved ones, so they parse as
        // ordinary function calls. That is a property of Calcite, not of ADAS: an upgrade that promoted one
        // of them to reserved would break every statement the DSL→SQL renderer emits carrying it, silently
        // at parse time and everywhere at once. This is the assertion that would fail first.
        final SqlAbstractParserImpl.Metadata metadata =
                SqlBabelParserImpl.FACTORY.getParser(new StringReader("")).getMetadata();
        assertThat(List.of("IF", "CONTAINS", "EQUALS"))
                .allSatisfy(word -> assertThat(metadata.isReservedWord(word))
                        .as("Calcite reserves '%s'; the catalog name would need a different spelling", word)
                        .isFalse());
    }

    @Test
    void everyDerivationFunctionSpellingLowersToItsCatalogName() {
        // The catalog name IS the SQL name for all seven — the renderer emits catalog names verbatim, so a
        // spelling the front end refused would break the render/translate round-trip.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT if(not_empty(chat_id), 'chat', 'none') AS a,"
                        + " starts_with(deployment, 'gpt') AS b,"
                        + " contains(deployment, 'gpt') AS c,"
                        + " equals(deployment, 'gpt') AS d,"
                        + " json_extract_string_ci(\"dial_usage_log_payload.request_tags\", 'x-session-id') AS e"
                        + " FROM dial_usage_log");

        assertThat(translation.query().select()).extracting(column -> ((FnExpr) column.expr()).name())
                .containsExactly("if", "starts_with", "contains", "equals", "json_extract_string_ci");
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("a", ResultColumnKind.STRING),
                new ResultColumn("b", ResultColumnKind.BOOL),
                new ResultColumn("c", ResultColumnKind.BOOL),
                new ResultColumn("d", ResultColumnKind.BOOL),
                new ResultColumn("e", ResultColumnKind.STRING));
    }

    @Test
    void nestedConditionalTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT if(not_empty(chat_id), 'chat',"
                        + " if(starts_with(lower(deployment), 'gpt'), 'gpt', 'other')) AS kind"
                        + " FROM dial_usage_log");

        final FnExpr outer = (FnExpr) translation.query().select().getFirst().expr();
        assertThat(outer.name()).isEqualTo("if");
        assertThat(((FnExpr) outer.args().get(2)).name()).isEqualTo("if");
        assertThat(translation.columns()).containsExactly(new ResultColumn("kind", ResultColumnKind.STRING));
    }

    @Test
    void bareComparisonInProjectionIsStillRejected() {
        // Comparisons map onto the filter grammar only. if's condition has to come from a boolean-returning
        // function call, which is why not_empty exists.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT if(length(deployment) > 0, 'a', 'b') FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void mismatchedConditionalBranchesAreRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT if(not_empty(chat_id), 'text', 1) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void mixedNumericConditionalReportsTheWidenedKind() {
        // Calcite derives the least-restrictive type over the branches and ClickHouse returns it, so naming
        // the `then` branch's kind here would advertise an integer column and then deliver decimals. A
        // result schema that misdescribes its own column is the failure this guards.
        final SqlTranslation translation = frontEnd.translate(
                "SELECT if(not_empty(chat_id), total_tokens, total_price) AS n FROM dial_usage_log");

        assertThat(translation.columns())
                .containsExactly(new ResultColumn("n", ResultColumnKind.DECIMAL));
    }

    @Test
    void nonTextOperandToTextPredicateIsRejected() {
        // The DSL surface applies the same rule (see DerivationExpressionTranslatorTest); asserted on both
        // so a query cannot be a clean 400 through one endpoint and a ClickHouse error through the other.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT not_empty(total_tokens) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT contains(\"dial_usage_log_payload.jwt_claims\", 'x') FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void booleanFunctionCallIsPredicate() {
        // Reversed from the earlier rule. The filter grammar still has no node for a bare call — the call
        // lowers to the comparison it does have, `<call> = TRUE`, which is equivalent in predicate position
        // (a null-valued call filters the row out either way). The earlier rejection rested on every
        // boolean scalar having a comparison equivalent (`WHERE x <> ''`); the array predicates have none,
        // since the subset carries no membership operator.
        assertThatCode(() -> frontEnd.translate(
                "SELECT chat_id FROM dial_usage_log WHERE not_empty(chat_id)"))
                .doesNotThrowAnyException();
        assertThatCode(() -> frontEnd.translate(
                "SELECT chat_id FROM dial_usage_log WHERE ARRAY_HAS(execution_path, 'r')"))
                .doesNotThrowAnyException();
    }

    @Test
    void nonBooleanFunctionCallIsStillNotPredicate() {
        // The widening is scoped by the call's declared type: there is no truthiness rule for a string.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id FROM dial_usage_log WHERE lower(deployment)"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void standardCoalesceAndNullifLowerThroughCase() {
        // Neither is a catalog function: Calcite's standard convertlet expands both into the CASE the case
        // expression now models, so they translate without an entry of their own.
        final SqlTranslation coalesce = frontEnd.translate("SELECT coalesce(chat_id, trace_id) FROM dial_usage_log");
        assertThat(coalesce.query().select()).singleElement().satisfies(column ->
                assertThat(column.expr()).isInstanceOf(CaseExpr.class));
        final SqlTranslation nullif = frontEnd.translate("SELECT nullif(chat_id, '') FROM dial_usage_log");
        assertThat(nullif.query().select()).singleElement().satisfies(column -> {
            assertThat(column.expr()).isInstanceOf(CaseExpr.class);
            final CaseExpr caseExpr = (CaseExpr) column.expr();
            assertThat(caseExpr.when()).singleElement().satisfies(branch ->
                    assertThat(branch.then()).isEqualTo(new ValueExpr(ValueType.NULL, null)));
            assertThat(caseExpr.otherwise()).isEqualTo(new FieldExpr("chat_id"));
        });
    }

    @Test
    void caseInsensitiveExtractionOverAnObjectFieldTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT json_extract_string_ci(\"dial_usage_log_payload.request_tags\", 'x-session-id') AS sid FROM dial_usage_log");

        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("json_extract_string_ci", false,
                        List.of(new FieldExpr("dial_usage_log_payload.request_tags"), new ValueExpr(ValueType.STRING, "x-session-id"))),
                "sid"));
        assertThat(translation.columns()).containsExactly(new ResultColumn("sid", ResultColumnKind.STRING));
    }

    @Test
    void caseInsensitiveExtractionRejectsPathSegment() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT json_extract_string_ci(\"dial_usage_log_payload.request_tags\", 'a', 'b') FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void arrayExtractionOverAnObjectFieldTranslates() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT json_extract_array(\"dial_usage_log_payload.jwt_claims\", 'roles') AS roles FROM dial_usage_log");

        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("json_extract_array", false,
                        List.of(new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "roles"))),
                "roles"));
        assertThat(translation.columns()).containsExactly(
                new ResultColumn("roles", ResultColumnKind.ARRAY, ResultColumnKind.STRING));
    }

    @Test
    void arrayExtractionResultIsProjectionOnly() {
        // The object first argument is accepted; the array RESULT is what is restricted, which is a
        // different rule in a different place — the message must not blame dial_usage_log_payload.jwt_claims.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id FROM dial_usage_log ORDER BY json_extract_array(\"dial_usage_log_payload.jwt_claims\", 'roles')"))
                .isInstanceOf(SqlTranslationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("object field 'dial_usage_log_payload.jwt_claims'"));
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT length(json_extract_array(\"dial_usage_log_payload.jwt_claims\", 'roles')) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT chat_id, count(*) FROM dial_usage_log GROUP BY chat_id, json_extract_array(\"dial_usage_log_payload.jwt_claims\", 'roles')"))
                .isInstanceOf(SqlTranslationException.class);
        // But SELECT DISTINCT over the projected call is ACCEPTED, unlike DISTINCT over a declared array
        // column: the restriction is on using an array as an operand, and a distinct projection does not.
        // Asserted rather than left implicit because the two read alike and behave differently.
        assertThatCode(() -> frontEnd.translate(
                "SELECT DISTINCT json_extract_array(\"dial_usage_log_payload.jwt_claims\", 'roles') FROM dial_usage_log"))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------- the clock and interval arithmetic

    /**
     * Three populations spell the clock three ways — {@code now()} (engine/PostgreSQL),
     * {@code CURRENT_TIMESTAMP} (standard SQL and most BI clients) and {@code LOCALTIMESTAMP} ("no zone",
     * which on a UTC-only surface is the same instant). They must produce ONE document, not three that
     * behave alike, or the same query saved from two clients compares unequal.
     */
    @Test
    void theThreeClockSpellingsProduceOneDocument() {
        final FilterNode expected = comparison(
                ComparisonOp.GE, new FieldExpr("request_time"), new FnExpr("now", false, List.of()));
        for (final String clock : List.of("now()", "NOW()", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP")) {
            assertThat(frontEnd.translate(
                    "SELECT event_id FROM dial_usage_log WHERE request_time >= " + clock).query().filter())
                    .as("clock spelling '%s'", clock)
                    .isEqualTo(expected);
        }
    }

    /**
     * Sharing a name does not merge evaluations: each spelling lowers to its own {@code now} call, so a
     * two-bound window carries two call sites, which ClickHouse evaluates independently.
     */
    @Test
    void twoClockSpellingsInOneStatementStayTwoCalls() {
        final FilterNode expected = new LogicalNode(LogicalOp.AND, List.of(
                comparison(ComparisonOp.GE, new FieldExpr("request_time"),
                        new FnExpr("date_sub", false, List.of(
                                new ValueExpr(ValueType.STRING, "hour"),
                                new ValueExpr(ValueType.INTEGER, "1"),
                                new FnExpr("now", false, List.of())))),
                comparison(ComparisonOp.LT, new FieldExpr("request_time"), new FnExpr("now", false, List.of()))));
        assertThat(frontEnd.translate("SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL 1 HOUR"
                + " AND request_time < CURRENT_TIMESTAMP").query().filter())
                .isEqualTo(expected);
    }

    /**
     * {@code CURRENT_DATE} is a truncation of the clock, not a spelling of it; lowering it silently would
     * answer a date-typed request with a timestamp. {@code date_trunc('day', now())} says it exactly.
     */
    @Test
    void currentDateStaysRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE request_time >= CURRENT_DATE"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void nowRejectsAnArgument() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE request_time >= now('UTC')"))
                .isInstanceOf(SqlTranslationException.class);
    }

    /**
     * The engine-native {@code INTERVAL 2 HOUR} and the standard {@code INTERVAL '2' HOUR} reach the
     * translator as <b>different Rex shapes</b> — the first as {@code 2 * INTERVAL 1 HOUR}, the second as a
     * plain interval literal — so this is not a cosmetic duplication: handling only one spelling accepted
     * the standard form and refused the ClickHouse one, which is backwards for this surface.
     */
    @Test
    void everyInboundOffsetSpellingProducesOneDocument() {
        final FilterNode expected = comparison(
                ComparisonOp.GE,
                new FieldExpr("request_time"),
                new FnExpr("date_sub", false, List.of(
                        new ValueExpr(ValueType.STRING, "hour"),
                        new ValueExpr(ValueType.INTEGER, "2"),
                        new FnExpr("now", false, List.of()))));
        final List<String> spellings = List.of(
                "now() - INTERVAL 2 HOUR",
                "now() - INTERVAL '2' HOUR",
                "date_sub('hour', 2, now())",
                // The unit is a SQL keyword in the operator form and a string literal in the function
                // form, so the function form is the one a caller writes upper case. Its case is
                // canonicalised, or the two spellings would be two documents and the round trip below
                // would never settle.
                "date_sub('HOUR', 2, now())",
                "date_sub(' Hour ', 2, now())",
                // Sign folds into the function name, so the addition of a negative interval is the same
                // document as the subtraction of a positive one.
                "now() + INTERVAL -2 HOUR",
                // Reduces to an interval constant, so it lands on the same document rather than being a
                // separate feature.
                "timestampadd(HOUR, -2, now())");
        for (final String spelling : spellings) {
            assertThat(frontEnd.translate(
                    "SELECT event_id FROM dial_usage_log WHERE request_time >= " + spelling).query().filter())
                    .as("offset spelling '%s'", spelling)
                    .isEqualTo(expected);
        }
    }

    /**
     * Calcite lowers {@code WEEK} to days and {@code QUARTER} to months during validation, so those two
     * never reach the DSL as units of their own — they normalize to their exact equivalents. Pinned here
     * because a client who wrote {@code INTERVAL 1 WEEK} and reads back {@code date_sub('day', 7, …)} must
     * be able to find the normalization documented rather than read it as a defect.
     */
    @Test
    void weekAndQuarterNormalizeToTheirExactEquivalents() {
        assertThat(offsetIn("now() - INTERVAL 1 WEEK")).isEqualTo(offsetOf("day", 7));
        assertThat(offsetIn("now() - INTERVAL 1 QUARTER")).isEqualTo(offsetOf("month", 3));
        // A year is 12 months in the literal's own base unit and must come back as one year, not 12 months.
        assertThat(offsetIn("now() - INTERVAL 1 YEAR")).isEqualTo(offsetOf("year", 1));
        assertThat(offsetIn("now() - INTERVAL 1 MONTH")).isEqualTo(offsetOf("month", 1));
        assertThat(offsetIn("now() - INTERVAL 90 DAY")).isEqualTo(offsetOf("day", 90));
        assertThat(offsetIn("now() - INTERVAL 1440 MINUTE")).isEqualTo(offsetOf("minute", 1440));
        assertThat(offsetIn("now() - INTERVAL 30 SECOND")).isEqualTo(offsetOf("second", 30));
    }

    @Test
    void offsetOverColumnTranslatesInProjection() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT request_time + INTERVAL 1 DAY AS due FROM dial_usage_log");
        assertThat(translation.query().select()).containsExactly(new OutputColumn(
                new FnExpr("date_add", false, List.of(
                        new ValueExpr(ValueType.STRING, "day"),
                        new ValueExpr(ValueType.INTEGER, "1"),
                        new FieldExpr("request_time"))),
                "due"));
        assertThat(translation.columns())
                .containsExactly(new ResultColumn("due", ResultColumnKind.TIMESTAMP));
    }

    @Test
    void projectedClockIsTypedAsTimestamp() {
        assertThat(frontEnd.translate("SELECT now() AS t FROM dial_usage_log").columns())
                .containsExactly(new ResultColumn("t", ResultColumnKind.TIMESTAMP));
    }

    /**
     * A multi-field qualifier names two offsets in one operand, which no single catalog call carries, and
     * keeping one field would answer a different question than was asked. The message has to say that —
     * falling through to the numeric reading reported it as an unusable literal family, which reads as an
     * internal failure rather than as a statement to rewrite.
     */
    @Test
    void multiFieldIntervalQualifiersAreRejectedByName() {
        for (final String qualifier : List.of("INTERVAL '1-2' YEAR TO MONTH", "INTERVAL '2:30' HOUR TO MINUTE")) {
            assertThatThrownBy(() -> frontEnd.translate(
                    "SELECT event_id FROM dial_usage_log WHERE request_time >= now() - " + qualifier))
                    .as("qualifier '%s'", qualifier)
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessageContaining("multi-field INTERVAL qualifier");
        }
    }

    @Test
    void fractionalAndZeroOffsetsAreRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL 2.5 HOUR"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("whole, non-zero");
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL 0 HOUR"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("whole, non-zero");
    }

    /**
     * An offset too large for the {@code int} both consumers of the document require. Refused here as an
     * offset rule rather than later as a malformed integer literal — otherwise translate-sql would hand
     * back a document execute-sql cannot run, which is the asymmetry the two endpoints exist to rule out.
     */
    @Test
    void anOversizedOffsetIsRejectedAsAnOffset() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL 3000000000 SECOND"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void computedOffsetIsRejected() {
        // The DSL declares an offset's unit and amount as literals so they are checked once before the
        // statement runs; a per-row offset has nothing to lower onto.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL request_body_bytes HOUR"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("constant amount");
    }

    @Test
    void anIntervalStandingAloneIsRejectedAsAnInterval() {
        // Not "unsupported literal type INTERVAL_HOUR": the message names the construct and the one form
        // that consumes one, because an interval is not a value this surface has a type for.
        assertThatThrownBy(() -> frontEnd.translate("SELECT INTERVAL 2 HOUR AS i FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("only expressible as an offset of a timestamp");
        assertThatThrownBy(() -> frontEnd.translate("SELECT INTERVAL '2' HOUR AS i FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("only expressible as an offset of a timestamp");
    }

    @Test
    void bothTimestampDifferenceSpellingsAreRejected() {
        // The bare form is refused by Calcite's own type checking. The qualified form was NOT: it lowered
        // to the numeric minus of the two operands, which renders a difference in seconds — so a caller
        // asking for hours got a number ~3600x too large with nothing to flag it.
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT request_time - _ingested_at AS d FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT (request_time - _ingested_at) HOUR AS d FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("difference of two timestamps");
    }

    /**
     * The clock is a <b>constant</b> within one execution, so a projected {@code now()} needs no group-by
     * entry — exactly as a projected literal needs none, and unlike a projected column, which is still
     * rejected as ungrouped. Both surfaces have to agree: the document is built through the execute path's
     * own builder here, so a divergence fails this test rather than reaching a caller as a document that is
     * valid on only one endpoint.
     */
    @Test
    void projectedClockNeedsNoGroupByWhileColumnStillDoes() {
        final SqlTranslation translation =
                frontEnd.translate("SELECT now() AS t, count(*) AS c FROM dial_usage_log");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.query().groupBy()).isNull();

        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        assertThatCode(() -> new StructuredQueryBuilder(expr, new FilterTranslator(expr))
                .build(new QueryDslConfiguration().clickhouseRenderContext(),
                        DialUsageLogTestModel.model(), translation.query()))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT deployment, count(*) AS c FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("not being grouped");
    }

    @Test
    void relativeWindowSurvivesTheFullRoundTrip() {
        // DSL -> SQL -> DSL for the motivating query: what a saved dashboard body actually carries.
        final String sql = "SELECT date_bin(5, 'minute', request_time) AS b, count(*) AS c"
                + " FROM dial_usage_log WHERE request_time >= now() - INTERVAL 24 HOUR GROUP BY b";
        final StructuredQuery query = frontEnd.translate(sql).query();
        final String rendered = new StructuredQueryToSqlRenderer(new ValueExprToObjectMapper())
                .render(query, DialUsageLogTestModel.model());
        assertThat(rendered).contains("(now() - INTERVAL 24 HOUR)");
        assertThat(frontEnd.translate(rendered).query()).isEqualTo(query);
    }

    /**
     * The function spelling round-trips too, and with an upper-case unit — the spelling a caller who
     * treats the unit as a SQL keyword writes. Without canonicalising the unit literal the cycle never
     * settles: the renderer emits the keyword upper case, the reader lowered it, and
     * {@code translate-sql} → {@code translate} stopped being idempotent.
     */
    @Test
    void theUpperCaseFunctionSpellingRoundTripsToTheSameDocument() {
        final String sql = "SELECT event_id FROM dial_usage_log"
                + " WHERE request_time >= date_add('HOUR', 2, now())";
        final StructuredQuery query = frontEnd.translate(sql).query();
        assertThat(((ComparisonNode) query.filter()).args().get(1)).isEqualTo(
                new FnExpr("date_add", false, List.of(
                        new ValueExpr(ValueType.STRING, "hour"),
                        new ValueExpr(ValueType.INTEGER, "2"),
                        new FnExpr("now", false, List.of()))));
        final String rendered = new StructuredQueryToSqlRenderer(new ValueExprToObjectMapper())
                .render(query, DialUsageLogTestModel.model());
        assertThat(rendered).contains("(now() + INTERVAL 2 HOUR)");
        assertThat(frontEnd.translate(rendered).query()).isEqualTo(query);
    }

    // -------------------------------------------------------------- helpers

    private static ComparisonNode comparison(ComparisonOp op, Expr left, Expr right) {
        return new ComparisonNode(op, List.of(left, right));
    }

    /** The right-hand offset expression of {@code WHERE request_time >= <expression>}. */
    private Expr offsetIn(String expression) {
        final FilterNode filter = frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE request_time >= " + expression).query().filter();
        return ((ComparisonNode) filter).args().get(1);
    }

    private static FnExpr offsetOf(String unit, int amount) {
        return new FnExpr("date_sub", false, List.of(
                new ValueExpr(ValueType.STRING, unit),
                new ValueExpr(ValueType.INTEGER, Integer.toString(amount)),
                new FnExpr("now", false, List.of())));
    }
}
