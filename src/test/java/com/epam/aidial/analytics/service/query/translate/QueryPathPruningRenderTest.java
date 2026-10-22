package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder.LeafRestriction;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder.LimitPolicy;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Render-only coverage of the query surface declaring its object-path usage (the {@code structured-query} spec's
 * "A keyed grain join is bounded by an aligned partition column"): a keyed or shaped enrichment derived table
 * projects only the paths the statement addresses, and every outer reference reads the alias it projects them under.
 */
class QueryPathPruningRenderTest {

    private static final String PAYLOAD = "dial_usage_log_payload";
    private static final String CLAIMS = PAYLOAD + ".jwt_claims";
    private static final String BODY = PAYLOAD + ".request_body";
    private static final String NOTE = PAYLOAD + ".note";
    private static final String EMAIL = "usage_client_identity.user_email";

    /** The pruned path as the outer statement addresses it: one dotted identifier inside the derived table. */
    private static final String ALIAS = "\"dial_usage_log_payload\".\"jwt_claims.email\"";
    /** The pruned path as the derived table projects it from the real column. */
    private static final String SUBCOLUMN = "\"dial_usage_log_payload\".\"jwt_claims\".\"email\"";

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final StructuredQueryBuilder builder = newBuilder();

    private static StructuredQueryBuilder newBuilder() {
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
    }

    private static QueryEntityModel model() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "event_id", "dial_usage_log", "event_id", QueryFieldType.UUID, false);
        bind(bindings, "request_time", "dial_usage_log", "request_time", QueryFieldType.TIMESTAMP, false);
        bind(bindings, "trace_id", "dial_usage_log", "trace_id", QueryFieldType.STRING, false);
        bind(bindings, "deployment", "dial_usage_log", "deployment", QueryFieldType.STRING, false);
        bind(bindings, CLAIMS, PAYLOAD, "jwt_claims", QueryFieldType.OBJECT, false);
        bind(bindings, BODY, PAYLOAD, "request_body", QueryFieldType.STRING, true);
        bind(bindings, NOTE, PAYLOAD, "note", QueryFieldType.STRING, false);
        bind(bindings, EMAIL, "usage_client_identity", "user_email", QueryFieldType.STRING, false);

        final EnrichmentJoin payload = EnrichmentJoin.keyed(DSL.table(DSL.name(PAYLOAD)),
                DSL.field(DSL.name("dial_usage_log", "event_id")), DSL.field(DSL.name(PAYLOAD, "event_id")),
                DSL.field(DSL.name("dial_usage_log", "request_time")), DSL.field(DSL.name(PAYLOAD, "request_time")),
                Set.of(CLAIMS, BODY, NOTE), null);
        final EnrichmentJoin identity = EnrichmentJoin.keyed(DSL.table(DSL.name("usage_client_identity")),
                DSL.field(DSL.name("dial_usage_log", "event_id")),
                DSL.field(DSL.name("usage_client_identity", "event_id")), null, null, Set.of(EMAIL), null);
        return new QueryEntityModel("dial_usage_log", DSL.table(DSL.name("dial_usage_log")), bindings,
                List.of(payload, identity), List.of(), null, true, true);
    }

    private static void bind(Map<String, QueryFieldBinding> bindings, String flatName, String table, String col,
            QueryFieldType type, boolean heavy) {
        final Field<?> field = DSL.field(DSL.name(table, col), JooqTypes.of(type));
        bindings.put(flatName, new QueryFieldBinding(flatName, field, type, null, heavy));
    }

    private String render(StructuredQuery query) {
        return builder.build(ctx, model(), query, StructuredQueryBuilder.MAX_LIMIT).getSQL(ParamType.INLINED);
    }

    private String count(StructuredQuery query) {
        return builder.buildCount(ctx, model(), query).getSQL(ParamType.INLINED);
    }

    private static FieldExpr field(String name) {
        return new FieldExpr(name);
    }

    private static ValueExpr str(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static FnExpr fn(String name, Expr... args) {
        return new FnExpr(name, false, List.of(args));
    }

    private static OutputColumn col(Expr expr, String as) {
        return new OutputColumn(expr, as);
    }

    private static FilterNode cmp(ComparisonOp op, Expr left, Expr right) {
        return new ComparisonNode(op, List.of(left, right));
    }

    private static FilterNode and(FilterNode... args) {
        return new LogicalNode(LogicalOp.AND, List.of(args));
    }

    private static final FilterNode WINDOW =
            cmp(ComparisonOp.GE, field("request_time"), new ValueExpr(ValueType.TIMESTAMP, "1791590400000"));

    private static final Expr EMAIL_PATH = fn("json_extract_string", field(CLAIMS), str("email"));

    private static StructuredQuery emailGroups(FilterNode filter) {
        return new StructuredQuery("dial_usage_log", filter, QueryMode.AGGREGATE, false,
                List.of(col(EMAIL_PATH, "e"), col(fn("count"), "c")), List.of("e"), null,
                List.of(new SortItem("c", SortDir.DESC, null)), new OffsetPage(0, 20, false));
    }

    private static StructuredQuery rows(FilterNode filter, List<OutputColumn> select, OffsetPage page) {
        return new StructuredQuery("dial_usage_log", filter, QueryMode.ROW, false, select, null, null,
                List.of(new SortItem("request_time", SortDir.DESC, null)), page);
    }

    /** The select list of the payload's derived table, i.e. what the join builds its hash table from. */
    private static String payloadProjection(String sql) {
        final Matcher matcher = Pattern.compile(
                "left outer join \\(select (.*?) from \"dial_usage_log_payload\"", Pattern.CASE_INSENSITIVE).matcher(sql);
        assertThat(matcher.find()).as("payload derived table in: %s", sql).isTrue();
        return matcher.group(1);
    }

    private static int occurrences(String sql, String needle) {
        return sql.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void groupedPathReadProjectsOnlyThatSubcolumn() {
        final String sql = render(emailGroups(WINDOW));

        assertThat(payloadProjection(sql))
                .isEqualToIgnoringCase("\"dial_usage_log_payload\".\"event_id\", \"dial_usage_log_payload\".\"request_time\", "
                        + SUBCOLUMN + " \"jwt_claims.email\"");
        // The derived table projects the subcolumn once; every outer reference reads its alias.
        assertThat(occurrences(sql, SUBCOLUMN)).isEqualTo(1);
        assertThat(sql).contains(ALIAS);
    }

    @Test
    void filteredPathReadProjectsOnlyThatSubcolumn() {
        final String sql = render(rows(and(WINDOW, cmp(ComparisonOp.EQ, EMAIL_PATH, str("a@b.c"))),
                List.of(col(field("trace_id"), null)), new OffsetPage(0, 10, false)));

        assertThat(payloadProjection(sql)).doesNotContainIgnoringCase("\"jwt_claims\",")
                .containsIgnoringCase(SUBCOLUMN + " \"jwt_claims.email\"");
        assertThat(occurrences(sql, SUBCOLUMN)).isEqualTo(1);
        assertThat(sql).contains(ALIAS);
    }

    @Test
    void columnAlsoProjectedWholeStaysWhole() {
        final String sql = render(rows(and(WINDOW, cmp(ComparisonOp.EQ, EMAIL_PATH, str("a@b.c"))),
                List.of(col(field(CLAIMS), null)), new OffsetPage(0, 10, false)));

        assertThat(payloadProjection(sql)).containsIgnoringCase("\"dial_usage_log_payload\".\"jwt_claims\"")
                .doesNotContain("jwt_claims.email");
        assertThat(sql).doesNotContain(ALIAS);
    }

    @Test
    void columnSortedByNameStaysWhole() {
        final StructuredQuery query = new StructuredQuery("dial_usage_log", WINDOW, QueryMode.ROW, false,
                List.of(col(EMAIL_PATH, "e")), null, null, List.of(new SortItem(CLAIMS, SortDir.ASC, null)),
                new OffsetPage(0, 10, false));

        assertThat(render(query)).doesNotContain(ALIAS);
    }

    @Test
    void columnGroupedByNameStaysWhole() {
        final StructuredQuery query = new StructuredQuery("dial_usage_log", WINDOW, QueryMode.AGGREGATE, false,
                List.of(col(field(CLAIMS), null), col(fn("max", EMAIL_PATH), "m")), List.of(CLAIMS), null, null,
                new OffsetPage(0, 10, false));

        assertThat(render(query)).doesNotContain(ALIAS);
    }

    @Test
    void pushedConjunctKeepsTheRealColumnInsideAndTheOuterPathReadsTheAlias() {
        // note ico 'x' is pushed into the payload's derived table; the path read beside it is pruned.
        final String sql = render(rows(and(WINDOW, cmp(ComparisonOp.ICO, field(NOTE), str("x"))),
                List.of(col(EMAIL_PATH, "e")), new OffsetPage(0, 10, false)));

        assertThat(sql)
                .containsIgnoringCase("\"dial_usage_log_payload\".\"note\" ilike '%x%'")
                .contains(ALIAS);
        assertThat(occurrences(sql, SUBCOLUMN)).isEqualTo(1);
        assertThat(payloadProjection(sql)).containsIgnoringCase("\"dial_usage_log_payload\".\"note\"");
    }

    @Test
    void pushedHeavyCallIsUnaffectedByPruning() {
        // An aggregate, so the body is not fetched late but read through the call computed in the derived table.
        final String sql = render(new StructuredQuery("dial_usage_log", WINDOW, QueryMode.AGGREGATE, false,
                List.of(col(EMAIL_PATH, "e"), col(fn("max", fn("length", field(BODY))), "len")), List.of("e"), null,
                null, new OffsetPage(0, 10, false)));

        assertThat(payloadProjection(sql))
                .containsIgnoringCase("length(\"dial_usage_log_payload\".\"request_body\")")
                .containsIgnoringCase(SUBCOLUMN + " \"jwt_claims.email\"")
                .doesNotContainIgnoringCase("\"request_body\",");
        assertThat(sql).contains(ALIAS);
    }

    @Test
    void lateMaterializedReadPrunesItsLightJoins() {
        // The body is projection-only, so it is fetched late; the email path read keys the light payload join.
        final String sql = render(rows(and(WINDOW, cmp(ComparisonOp.EQ, EMAIL_PATH, str("a@b.c"))),
                List.of(col(field("trace_id"), null), col(field(BODY), null)), new OffsetPage(0, 10, false)));

        assertThat(sql).contains("__adas_lm").contains(ALIAS);
        assertThat(sql).doesNotContainIgnoringCase("\"dial_usage_log_payload\".\"jwt_claims\",")
                .doesNotContainIgnoringCase("\"dial_usage_log_payload\".\"jwt_claims\" from");
    }

    @Test
    void countPrunesAsItsFilterReads() {
        final String sql = count(rows(and(WINDOW, cmp(ComparisonOp.EQ, EMAIL_PATH, str("a@b.c"))),
                List.of(col(field("trace_id"), null)), new OffsetPage(0, 10, true)));

        assertThat(payloadProjection(sql)).containsIgnoringCase(SUBCOLUMN + " \"jwt_claims.email\"")
                .doesNotContainIgnoringCase("\"jwt_claims\",");
        assertThat(sql).contains(ALIAS);
    }

    @Test
    void unshapedReadAlsoPrunes() {
        final String sql = builder.withoutReadShaping().build(ctx, model(), emailGroups(WINDOW),
                StructuredQueryBuilder.MAX_LIMIT).getSQL(ParamType.INLINED);

        assertThat(payloadProjection(sql)).containsIgnoringCase(SUBCOLUMN + " \"jwt_claims.email\"");
        assertThat(sql).contains(ALIAS);
    }

    @Test
    void compositeBlockPrunes() {
        final String sql = builder.build(ctx, model(), emailGroups(WINDOW), 0, LimitPolicy.NESTED)
                .getSQL(ParamType.INLINED);

        assertThat(sql).contains(ALIAS);
    }

    @Test
    void pipelineBlockDeclaresNoUsage() {
        final String sql = builder.build(ctx, model(), emailGroups(WINDOW), StructuredQueryBuilder.MAX_LIMIT,
                LimitPolicy.NESTED, LeafRestriction.NONE).getSQL(ParamType.INLINED);

        assertThat(payloadProjection(sql)).containsIgnoringCase("\"dial_usage_log_payload\".\"jwt_claims\"")
                .doesNotContain("jwt_claims.email");
        assertThat(sql).doesNotContain(ALIAS);
    }
}
