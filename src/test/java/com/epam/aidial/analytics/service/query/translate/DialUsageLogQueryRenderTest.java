package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.NullsOrder;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Renders structured queries for {@code dial_usage_log} through the full translation stack on the
 * connection-less CLICKHOUSE render context, asserting the emitted SQL string. Assertions are
 * fragment-based (not exact SQL) so they stay robust to jOOQ formatting; they verify the table,
 * projection, filter literal inlining, grouping, ordering and limit are present. Exotic functions
 * (percentile/width_bucket) are intentionally not asserted here — their ClickHouse rendering must be
 * verified against a live server.
 */
class DialUsageLogQueryRenderTest {

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final QueryEntityModel model = DialUsageLogTestModel.model();
    private final StructuredQueryBuilder builder = newBuilder();

    private static StructuredQueryBuilder newBuilder() {
        ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
    }

    private String render(StructuredQuery query) {
        return builder.build(ctx, model, query).getSQL(ParamType.INLINED);
    }

    @Test
    void rendersRowModeProjectionWithFilterSortAndLimit() {
        FilterNode filter = new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("event_kind"), new ValueExpr(ValueType.STRING, "chat")));
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                filter,
                QueryMode.ROW,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("project_id"), null),
                        new OutputColumn(new FieldExpr("total_tokens"), null)),
                null,
                null,
                List.of(new SortItem("total_tokens", SortDir.DESC, null)),
                new OffsetPage(0L, 10, false));

        String sql = render(query);

        assertThat(sql)
                .containsIgnoringCase("dial_usage_log")
                .containsIgnoringCase("project_id")
                .containsIgnoringCase("total_tokens")
                .containsIgnoringCase("event_kind")
                .contains("'chat'")
                .containsIgnoringCase("order by")
                .containsIgnoringCase("limit");
    }

    @Test
    void rendersCaseSensitiveContainsAsPlainLike() {
        FilterNode filter = new ComparisonNode(
                ComparisonOp.CO, List.of(new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "GPT")));
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("deployment"), null)), null, null, null, null);

        String sql = render(query);

        assertThat(sql).containsIgnoringCase("like").contains("'%GPT%'");
        // co is case-sensitive: no ILIKE and no lower()-folding may appear.
        assertThat(sql).doesNotContainIgnoringCase("ilike").doesNotContainIgnoringCase("lower(");
    }

    @Test
    void rendersCaseInsensitiveContainsWithCaseFolding() {
        FilterNode filter = new ComparisonNode(
                ComparisonOp.ICO, List.of(new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "GPT")));
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("deployment"), null)), null, null, null, null);

        String sql = render(query);

        // jOOQ renders likeIgnoreCase as ILIKE or as lower(x) LIKE lower(pattern), dialect-dependent;
        // either way the match is case-insensitive, unlike the plain LIKE that co now emits.
        assertThat(sql).satisfiesAnyOf(
                s -> assertThat(s).containsIgnoringCase("ilike"),
                s -> assertThat(s).containsIgnoringCase("lower("));
    }

    @Test
    void rendersAggregateModeWithGroupByAndCount() {
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("event_kind"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "total_requests")),
                List.of("event_kind"),
                null,
                List.of(new SortItem("total_requests", SortDir.DESC, null)),
                null);

        String sql = render(query);

        assertThat(sql)
                .containsIgnoringCase("dial_usage_log")
                .containsIgnoringCase("event_kind")
                .containsIgnoringCase("count(")
                .containsIgnoringCase("group by")
                .containsIgnoringCase("total_requests");
    }

    @Test
    void rendersDateBinAsToStartOfInterval() {
        // date_bin renders the native ClickHouse function directly, so (unlike width_bucket/percentile)
        // its rendering is deterministic and asserted here.
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(
                                new FnExpr(
                                        "date_bin",
                                        false,
                                        List.of(
                                                new ValueExpr(ValueType.INTEGER, "5"),
                                                new ValueExpr(ValueType.STRING, "minute"),
                                                new FieldExpr("request_time"))),
                                "bucket"),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("bucket"),
                null,
                null,
                null);

        String sql = render(query);

        assertThat(sql)
                .containsIgnoringCase("toStartOfInterval(")
                .containsIgnoringCase("request_time")
                .containsIgnoringCase("INTERVAL 5 MINUTE")
                .containsIgnoringCase("group by")
                // The alignment zone is pinned, not inherited from the column: a zoneless DateTime64 would
                // otherwise align in the ClickHouse server's zone, making the same query bucket differently
                // per deployment — invisibly, since both alignments render a bucket start of 00:00:00.
                .contains("INTERVAL 5 MINUTE, 'UTC')");
    }

    @Test
    void rendersGroupUniqArraySorted() {
        // Like date_bin, this lowers to native ClickHouse functions, so its rendering is deterministic.
        // The arraySort wrapper is asserted because the aggregate pipeline's idempotence depends on it:
        // groupUniqArray's element order is a hash-set artefact, so an unsorted result would make a
        // recompute of unchanged rows write a "new" value for every key it touched.
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(
                                new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment"))),
                                "deployments")),
                List.of("chat_id"),
                null,
                null,
                null);

        String sql = render(query);

        assertThat(sql)
                .containsIgnoringCase("arraySort(groupUniqArray(")
                .containsIgnoringCase("deployment")
                .containsIgnoringCase("group by")
                .containsIgnoringCase("deployments");
    }

    @Test
    void rendersHigherOrderCallInClickHouseShape() {
        // The lambda renders first and the parameter as a BARE identifier: ClickHouse does not accept a
        // quoted one in that position, which is why the parameter's spelling is validated before this.
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(
                        new FnExpr("arrayExists", false, List.of(
                                new LambdaExpr(List.of("x"), new FnExpr("starts_with", false, List.of(
                                        new LambdaParamExpr("x"),
                                        new ValueExpr(ValueType.STRING, "coder-")))),
                                new FieldExpr("execution_path"))),
                        "coder")),
                null,
                null,
                null,
                null);

        final String sql = render(query);

        // The lambda is the FIRST operand and its parameter is bare; the body keeps the boolean
        // normalisation every predicate in this translator carries.
        assertThat(sql)
                .containsIgnoringCase("arrayExists(x -> ")
                .containsIgnoringCase("startsWith(x, 'coder-')")
                .containsIgnoringCase("execution_path");
        assertThat(sql).doesNotContain("\"x\" ->");
    }

    @Test
    void rendersCapturedColumnInsideLambdaBody() {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(
                        new FnExpr("arrayExists", false, List.of(
                                new LambdaExpr(List.of("x"), new FnExpr("starts_with", false, List.of(
                                        new LambdaParamExpr("x"), new FieldExpr("deployment")))),
                                new FieldExpr("execution_path"))),
                        "matches")),
                null,
                null,
                null,
                null);

        assertThat(render(query))
                .containsIgnoringCase("arrayExists(x -> ")
                .containsIgnoringCase("startsWith(x, \"dial_usage_log\".\"deployment\")");
    }

    @Test
    void lambdaParameterShadowsTheColumnOfTheSameName() {
        // ClickHouse's own rule, and the one the structured-query spec states. It is also what the SQL
        // surface does — SqlLambdaRewriter substitutes every bare identifier equal to the parameter — and
        // the two have to agree: StructuredQueryToSqlRenderer emits a `field` as that same bare
        // identifier, so a document resolving this `field` to the column would render SQL in which it is
        // the parameter. Here the body must render the BARE parameter, never the qualified column.
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(
                        new FnExpr("arrayExists", false, List.of(
                                new LambdaExpr(List.of("deployment"), new FnExpr("starts_with", false, List.of(
                                        new FieldExpr("deployment"),
                                        new ValueExpr(ValueType.STRING, "coder-")))),
                                new FieldExpr("execution_path"))),
                        "matches")),
                null,
                null,
                null,
                null);

        final String sql = render(query);

        assertThat(sql)
                .containsIgnoringCase("arrayExists(deployment -> ")
                .containsIgnoringCase("startsWith(deployment, 'coder-')");
        assertThat(sql).doesNotContain("startsWith(deployment, \"dial_usage_log\".\"deployment\")");
        assertThat(sql).doesNotContain("\"dial_usage_log\".\"deployment\"");
    }

    @Test
    void capturedEnrichmentColumnInsideLambdaBodyEmitsItsJoin() {
        // A captured column is an ordinary column read, so the walker that derives which enrichments to
        // join must see it. Bindings resolve a qualified column whether or not its table is joined, so
        // missing it renders valid-looking SQL naming a table nothing joined — an "Unknown identifier"
        // from the server rather than rows.
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                new ComparisonNode(ComparisonOp.EQ, List.of(
                        new FnExpr("arrayExists", false, List.of(
                                new LambdaExpr(List.of("x"), new FnExpr("equals", false, List.of(
                                        new LambdaParamExpr("x"),
                                        new FieldExpr(DialUsageLogTestModel.ENRICHMENT_COLUMN)))),
                                new FieldExpr("execution_path"))),
                        new ValueExpr(ValueType.BOOLEAN, "true"))),
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("event_id"), null)),
                null,
                null,
                null,
                null);

        final String sql = builder.build(ctx, DialUsageLogTestModel.modelWithEnrichment(), query)
                .getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("join \"usage_client_identity\"")
                .containsIgnoringCase("\"usage_client_identity\".\"client_session_id\"");
    }

    @Test
    void rendersTheFlatteningAggregateAsTheNativeCombinator() {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(new FnExpr("groupUniqArrayArray", false,
                                List.of(new FieldExpr("execution_path"))), "steps")),
                List.of("chat_id"),
                null,
                null,
                null);

        assertThat(render(query)).containsIgnoringCase("groupUniqArrayArray(");
    }

    @Test
    void rendersArgMaxAsTheNativeTwoOperandClickHouseCall() {
        // arg_max emits the native function name directly, the way date_bin's toStartOfInterval does, so
        // its rendering is asserted here rather than being left to a live server like percentile and
        // width_bucket. Nothing asks jOOQ's experimental dialect to know argMax — the template is
        // hand-built, exactly as arraySort(groupUniqArray(...)) above is.
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(
                                new FnExpr("arg_max", false,
                                        List.of(new FieldExpr("deployment"), new FieldExpr("request_time"))),
                                "latest_deployment")),
                List.of("chat_id"),
                null,
                null,
                null);

        String sql = render(query);

        assertThat(sql)
                .containsIgnoringCase("argMax(")
                .containsIgnoringCase("deployment")
                .containsIgnoringCase("request_time")
                .containsIgnoringCase("group by")
                .containsIgnoringCase("latest_deployment");
        // Operand order is load-bearing and silently plausible when reversed: the value comes first, the
        // ranking column second. argMax(request_time, deployment) would run and return timestamps.
        assertThat(sql.replaceAll("\\s+", "")).containsIgnoringCase(
                "argMax(\"dial_usage_log\".\"deployment\",\"dial_usage_log\".\"request_time\")");
    }

    @Test
    void emitsClientNullOrderingOnSortKeys() {
        // §6, D8: a client-supplied `nulls` renders an explicit NULLS FIRST/LAST clause; when omitted
        // no NULLS clause is emitted and the database default applies.
        StructuredQuery descNullsLast = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.ROW,
                false,
                null,
                null,
                null,
                List.of(new SortItem("total_tokens", SortDir.DESC, NullsOrder.LAST)),
                null);
        assertThat(render(descNullsLast)).containsIgnoringCase("total_tokens").containsIgnoringCase("nulls last");

        StructuredQuery ascNullsFirst = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.ROW,
                false,
                null,
                null,
                null,
                List.of(new SortItem("total_tokens", SortDir.ASC, NullsOrder.FIRST)),
                null);
        assertThat(render(ascNullsFirst)).containsIgnoringCase("total_tokens").containsIgnoringCase("nulls first");

        StructuredQuery noNulls = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.ROW,
                false,
                null,
                null,
                null,
                List.of(new SortItem("total_tokens", SortDir.DESC, null)),
                null);
        assertThat(render(noNulls)).doesNotContainIgnoringCase("nulls");
    }

    @Test
    void rendersInPredicateAsLiteralList() {
        FilterNode filter = new ComparisonNode(
                ComparisonOp.IN,
                List.of(
                        new FieldExpr("event_kind"),
                        new ArrayExpr(List.of(
                                new ValueExpr(ValueType.STRING, "chat"), new ValueExpr(ValueType.STRING, "embedding")))));
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, filter, QueryMode.ROW, false, null, null, null, null, null);

        String sql = render(query);

        assertThat(sql).contains("'chat'").contains("'embedding'").containsIgnoringCase("in (");
    }

    @Test
    void omittedProjectionSelectsAllBoundColumns() {
        // No select -> the wildcard expansion is projected explicitly (the hand-built table has no
        // metadata to expand into a literal *), in catalog order.
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false, null, null, null, null, null);

        String sql = render(query);

        assertThat(sql).containsIgnoringCase("request_time").containsIgnoringCase("_ingested_at");
        assertThat(sql.indexOf("event_id")).isLessThan(sql.indexOf("event_kind"));
    }

    // ------------------------------------------------------------- wildcard projection / heavy columns

    @Test
    void omittedProjectionOmitsHeavyColumns() {
        // request_body/response_body are heavy in the catalog (V18), so the wildcard leaves them out —
        // while the small *_bytes columns describing them stay.
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false, null, null, null, null, null);

        String sql = render(query);

        // Quoted-name assertions: the *_bytes columns contain the payload names as a prefix, so the
        // closing quote is what distinguishes "not projected" from "projected under a longer name".
        assertThat(sql)
                .doesNotContain("\"dial_usage_log_payload.request_body\"").doesNotContain("\"dial_usage_log_payload.response_body\"")
                .contains("\"request_body_bytes\"").contains("\"response_body_bytes\"");
    }

    @Test
    void explicitlySelectedHeavyColumnIsProjected() {
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("dial_usage_log_payload.response_body"), null)),
                null, null, null, null);

        assertThat(render(query)).contains("dial_usage_log_payload.response_body");
    }

    @Test
    void heavyColumnStaysUsableInFilterAndSort() {
        FilterNode filter = new ComparisonNode(
                ComparisonOp.CO, List.of(new FieldExpr("dial_usage_log_payload.response_body"), new ValueExpr(ValueType.STRING, "error")));
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, filter, QueryMode.ROW, false, null, null, null,
                List.of(new SortItem("dial_usage_log_payload.response_body", SortDir.ASC, null)), null);

        String sql = render(query);

        assertThat(sql).containsIgnoringCase("like").contains("'%error%'").containsIgnoringCase("order by");
        // Filtered and sorted on, but not projected: the wildcard still leaves the heavy column out.
        assertThat(sql.substring(0, sql.indexOf("from"))).doesNotContain("\"dial_usage_log_payload.response_body\"");
    }

    @Test
    void allHeavyWildcardIsRejected() {
        final QueryEntityModel allHeavy = DialUsageLogTestModel.allHeavyModel();
        StructuredQuery query = new StructuredQuery(
                allHeavy.entity(), null, QueryMode.ROW, false, null, null, null, null, null);

        assertThatThrownBy(() -> builder.build(ctx, allHeavy, query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining(allHeavy.entity())
                .hasMessageContaining("heavy");
    }

    // ------------------------------------------------ optional aliases / derived output names

    @Test
    void unaliasedAggregateRendersItsDerivedName() {
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("event_kind"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), null)),
                List.of("event_kind"),
                null,
                List.of(new SortItem("count", SortDir.DESC, null)),
                null);

        String sql = render(query);

        // The aggregate is aliased "count" and the sort references that output column.
        assertThat(sql).containsIgnoringCase("count(*) \"count\"").containsIgnoringCase("order by \"count\" desc");
    }

    @Test
    void collidingUnaliasedAggregatesRenderSuffixedNames() {
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FnExpr("count", false, List.of()), null),
                        new OutputColumn(new FnExpr("count", true, List.of(new FieldExpr("deployment"))), null)),
                List.of(),
                null,
                null,
                null);

        String sql = render(query);

        assertThat(sql).containsIgnoringCase("\"count\"").containsIgnoringCase("\"count_1\"");
    }

    @Test
    void rowModeProjectionAliasesEveryColumnWithItsResolvedName() {
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.ROW,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("deployment"), null),
                        new OutputColumn(new FnExpr("lower", false, List.of(new FieldExpr("deployment"))), null)),
                null,
                null,
                null,
                null);

        String sql = render(query);

        // The computed column carries its derived name, so the executor's row keys match the SQL.
        assertThat(sql).containsIgnoringCase("\"lower\"");
    }

    @Test
    void groupByPrefersEntityColumnOverCollidingDerivedName() {
        // The entity owns a column named "count"; PostgreSQL resolves an ambiguous GROUP BY name as the
        // input column, so the grouping must be the column, not the aggregate's output.
        final QueryEntityModel withCountColumn = DialUsageLogTestModel.modelWithColumn("count");
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FnExpr("count", false, List.of()), null)),
                List.of("count"),
                new ComparisonNode(
                        ComparisonOp.GT, List.of(new FieldExpr("count"), new ValueExpr(ValueType.INTEGER, "1"))),
                null,
                null);

        String sql = builder.build(ctx, withCountColumn, query).getSQL(ParamType.INLINED);

        // Both GROUP BY and HAVING address the qualified physical column, never the bare output name.
        assertThat(sql).containsIgnoringCase("group by \"dial_usage_log\".\"count\"");
        assertThat(sql).containsIgnoringCase("having \"dial_usage_log\".\"count\" > 1");
    }

    @Test
    void groupByRejectsAnExplicitAliasShadowingTheGroupedColumn() {
        // date_bin(...) AS "request_time" grouped by "request_time": the column and the bucket output are
        // two different groupings under one name, so the document fails closed instead of picking one.
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(
                                new FnExpr("date_bin", false, List.of(
                                        new ValueExpr(ValueType.INTEGER, "1"),
                                        new ValueExpr(ValueType.STRING, "hour"),
                                        new FieldExpr("request_time"))),
                                "request_time"),
                        new OutputColumn(new FnExpr("count", false, List.of()), null)),
                List.of("request_time"),
                null,
                null,
                null);

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("request_time")
                .hasMessageContaining("ambiguous");
    }

    @Test
    void groupByAcceptsAnAliasThatRepeatsTheProjectedFieldName() {
        // {field: event_kind, as: "event_kind"} is not shadowing: both readings group by the same column.
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("event_kind"), "event_kind"),
                        new OutputColumn(new FnExpr("count", false, List.of()), null)),
                List.of("event_kind"),
                null,
                null,
                null);

        assertThat(render(query)).containsIgnoringCase("group by \"dial_usage_log\".\"event_kind\"");
    }

    @Test
    void explicitAliasStillShadowsAnEntityColumnInHaving() {
        // An explicit alias is a deliberate naming choice, so having keeps resolving it to the output.
        final QueryEntityModel withCountColumn = DialUsageLogTestModel.modelWithColumn("count");
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FnExpr("count", false, List.of()), "count")),
                List.of(),
                new ComparisonNode(
                        ComparisonOp.GT, List.of(new FieldExpr("count"), new ValueExpr(ValueType.INTEGER, "1"))),
                null,
                null);

        String sql = builder.build(ctx, withCountColumn, query).getSQL(ParamType.INLINED);

        assertThat(sql).containsIgnoringCase("having count(*) > 1");
    }

    /**
     * A derived select output is judged in {@code having} by the type of the expression it projects.
     * {@code max(project_id)} is text, so the contains family's text check passes and the LIKE renders;
     * a fixed alias type would refuse this filter as a comparison over a number.
     */
    @Test
    void havingAcceptsContainsFilterOverTextValuedOutput() {
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("event_kind"), null),
                        new OutputColumn(
                                new FnExpr("max", false, List.of(new FieldExpr("project_id"))), "latest")),
                List.of("event_kind"),
                new ComparisonNode(ComparisonOp.CO, List.of(
                        new FieldExpr("latest"), new ValueExpr(ValueType.STRING, "acme"))),
                null,
                null);

        String sql = render(query);

        assertThat(sql).containsIgnoringCase("having").containsIgnoringCase("like").contains("'%acme%'");
    }

    /** The other half of the same rule: a numeric output is refused, and the message names its own type. */
    @Test
    void havingRejectsContainsFilterOverNumericOutput() {
        StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FnExpr("count", false, List.of()), "requests")),
                List.of(),
                new ComparisonNode(ComparisonOp.CO, List.of(
                        new FieldExpr("requests"), new ValueExpr(ValueType.STRING, "1"))),
                null,
                null);

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("requires a text");
    }
}
