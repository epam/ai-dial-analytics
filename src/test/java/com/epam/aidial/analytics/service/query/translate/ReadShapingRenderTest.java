package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.HigherOrderFunctions;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
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
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder.LimitPolicy;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Render-only coverage of the single-entity read path's shaping (the {@code structured-query} spec's
 * late-materialization, conjunct-pushdown and call-pushdown requirements), over a hand-built model shaped like
 * {@code dial_usage_log}: a payload enrichment holding two heavy text columns and a light object column, both
 * partition-aligned with the source on {@code request_time}, and an identity enrichment keyed on the same grain key
 * but not aligned.
 */
class ReadShapingRenderTest {

    private static final String PAYLOAD = "dial_usage_log_payload";
    private static final String BODY = PAYLOAD + ".request_body";
    private static final String RESPONSE = PAYLOAD + ".response_body";
    private static final String TAGS = PAYLOAD + ".request_tags";
    private static final String NOTE = PAYLOAD + ".note";
    private static final String STATUS = PAYLOAD + ".status_code";
    private static final String EMAIL = "usage_client_identity.user_email";
    private static final String KIND = "usage_client_identity.client_kind";
    private static final String TRACES = "usage_client_identity.traces";

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
        bind(bindings, BODY, PAYLOAD, "request_body", QueryFieldType.STRING, true);
        bind(bindings, RESPONSE, PAYLOAD, "response_body", QueryFieldType.STRING, true);
        bind(bindings, TAGS, PAYLOAD, "request_tags", QueryFieldType.OBJECT, false);
        bind(bindings, NOTE, PAYLOAD, "note", QueryFieldType.STRING, false);
        bind(bindings, STATUS, PAYLOAD, "status_code", QueryFieldType.LONG, false);
        bind(bindings, EMAIL, "usage_client_identity", "user_email", QueryFieldType.STRING, false);
        bind(bindings, KIND, "usage_client_identity", "client_kind", QueryFieldType.ENUM, false);
        bind(bindings, TRACES, "usage_client_identity", "traces", QueryFieldType.STRING, true);

        final EnrichmentJoin payload = EnrichmentJoin.keyed(DSL.table(DSL.name(PAYLOAD)),
                DSL.field(DSL.name("dial_usage_log", "event_id")), DSL.field(DSL.name(PAYLOAD, "event_id")),
                DSL.field(DSL.name("dial_usage_log", "request_time")), DSL.field(DSL.name(PAYLOAD, "request_time")),
                Set.of(BODY, RESPONSE, TAGS, NOTE, STATUS), null);
        final EnrichmentJoin identity = EnrichmentJoin.keyed(DSL.table(DSL.name("usage_client_identity")),
                DSL.field(DSL.name("dial_usage_log", "event_id")),
                DSL.field(DSL.name("usage_client_identity", "event_id")), null, null, Set.of(EMAIL, KIND, TRACES), null);
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

    private String unshaped(StructuredQuery query) {
        return builder.withoutReadShaping().build(ctx, model(), query, StructuredQueryBuilder.MAX_LIMIT)
                .getSQL(ParamType.INLINED);
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

    private static OutputColumn col(Expr expr) {
        return new OutputColumn(expr, null);
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

    private static StructuredQuery row(FilterNode filter, List<OutputColumn> select, List<SortItem> sort, OffsetPage page) {
        return new StructuredQuery("dial_usage_log", filter, QueryMode.ROW, false, select, null, null, sort, page);
    }

    private static List<SortItem> newestFirst() {
        return List.of(new SortItem("request_time", SortDir.DESC, null));
    }

    private static StructuredQuery lastTenWithBodies() {
        return row(WINDOW, List.of(col(field("trace_id")), col(field(BODY))), newestFirst(), new OffsetPage(0, 10, false));
    }

    /** Every {@code IN (...)} page-key subquery the statement renders, by its text. */
    private static List<String> pageKeySubqueries(String sql) {
        final List<String> found = new ArrayList<>();
        final Matcher matcher = Pattern.compile("in \\((select \"__adas_lm_key\".*? \"__adas_lm\")\\)",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    // ---------------------------------------------------------------------------------------------------------
    // A. Late materialization

    @Test
    void lastPageWithBodiesJoinsThePayloadForThePageKeysInTheOuterScope() {
        final String sql = render(lastTenWithBodies());

        assertThat(sql)
                // The payload is the bare table under FINAL, restricted through the join condition.
                .containsIgnoringCase("left outer join \"dial_usage_log_payload\" final on ((\"dial_usage_log\".\"event_id\""
                        + " = \"dial_usage_log_payload\".\"event_id\") and (\"dial_usage_log\".\"request_time\""
                        + " = \"dial_usage_log_payload\".\"request_time\") and ((\"dial_usage_log_payload\".\"event_id\","
                        + " \"dial_usage_log_payload\".\"request_time\") in (select \"__adas_lm_key\", \"__adas_lm_part\" from (")
                .containsIgnoringCase("where (\"dial_usage_log\".\"request_time\" >= fromUnixTimestamp64Milli(1791590400000) and"
                        + " (((\"dial_usage_log\".\"event_id\", \"dial_usage_log\".\"request_time\") in (select")
                .containsIgnoringCase("\"dial_usage_log\".\"event_id\" is null")
                .containsIgnoringCase("\"dial_usage_log\".\"request_time\" is null")
                // The caller's projection and page are untouched.
                .containsIgnoringCase("\"dial_usage_log_payload\".\"request_body\" \"dial_usage_log_payload.request_body\"")
                .endsWithIgnoringCase("order by \"dial_usage_log\".\"request_time\" desc limit 10 offset 0");
        final List<String> keys = pageKeySubqueries(sql);
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
        // The page block reads no heavy column and carries the filter, the sort and the page's end.
        assertThat(keys.get(0))
                .doesNotContainIgnoringCase("request_body")
                .doesNotContainIgnoringCase("dial_usage_log_payload")
                .containsIgnoringCase("\"dial_usage_log\".\"request_time\" >=")
                .containsIgnoringCase("order by \"dial_usage_log\".\"request_time\" desc limit 10")
                .containsIgnoringCase("\"dial_usage_log\".\"event_id\" \"__adas_lm_key\"")
                .containsIgnoringCase("\"dial_usage_log\".\"request_time\" \"__adas_lm_part\"");
        // No derived payload table, so no key sub-query over the window either.
        assertThat(sql).doesNotContainIgnoringCase("\"dial_usage_log_payload\".\"event_id\" in (select");
    }

    @Test
    void offsetPageFetchesKeysUpToItsEnd() {
        final String sql = render(row(WINDOW, List.of(col(field("trace_id")), col(field(BODY))), newestFirst(),
                new OffsetPage(20, 10, false)));

        final String keys = pageKeySubqueries(sql).getFirst();
        assertThat(keys).containsIgnoringCase("limit 30").doesNotContainIgnoringCase("offset");
        assertThat(sql).endsWithIgnoringCase("limit 10 offset 20");
    }

    @Test
    void omittedPageLateMaterializesTheDefaultPage() {
        final String sql = render(row(WINDOW, List.of(col(field("trace_id")), col(field(BODY))), newestFirst(), null));

        assertThat(pageKeySubqueries(sql).getFirst()).containsIgnoringCase("limit 100");
        assertThat(sql).endsWithIgnoringCase("desc limit 100");
    }

    @Test
    void offsetPageBeyondTheRowCeilingStillCutsThePageBlockAtItsEnd() {
        final String sql = render(row(WINDOW, List.of(col(field("trace_id")), col(field(BODY))), newestFirst(),
                new OffsetPage(5000, 1000, false)));

        assertThat(pageKeySubqueries(sql).getFirst()).containsIgnoringCase("limit 6000");
    }

    @Test
    void sortThroughSelectAliasKeepsThatEntryInThePageBlockUnderItsName() {
        final StructuredQuery query = row(WINDOW,
                List.of(col(fn("lower", field("deployment")), "deployment"), col(field(BODY))),
                List.of(new SortItem("deployment", SortDir.ASC, null)), new OffsetPage(0, 10, false));

        final String keys = pageKeySubqueries(render(query)).getFirst();

        assertThat(keys)
                .containsIgnoringCase("lower(\"dial_usage_log\".\"deployment\") \"deployment\"")
                .containsIgnoringCase("order by \"deployment\" asc");
    }

    @Test
    void lightColumnOfThePayloadInTheFilterIsJoinedForThePageBlockAndForThePage() {
        final StructuredQuery query = row(and(WINDOW, cmp(ComparisonOp.ICO, field(NOTE), str("x"))),
                List.of(col(field("trace_id")), col(field(BODY))), newestFirst(), new OffsetPage(0, 10, false));

        final String sql = render(query);
        final String keys = pageKeySubqueries(sql).getFirst();

        // The page block joins the payload bounded by the window (transferred onto its own request_time), for the
        // light column only.
        assertThat(keys)
                .containsIgnoringCase("\"dial_usage_log_payload\".\"note\"")
                .doesNotContainIgnoringCase("request_body")
                .containsIgnoringCase("\"dial_usage_log_payload\".\"request_time\" >=");
        // The outer statement still filters on it, through the page-restricted join.
        assertThat(sql).containsIgnoringCase("\"dial_usage_log_payload\".\"note\" ilike '%x%'");
    }

    @Test
    void nonAlignedHeavyEnrichmentUsesOneColumnPageKey() {
        final String sql = render(row(WINDOW, List.of(col(field("trace_id")), col(field(TRACES))), newestFirst(),
                new OffsetPage(0, 10, false)));

        assertThat(sql)
                .containsIgnoringCase("\"usage_client_identity\".\"event_id\" in (select \"__adas_lm_key\" from (")
                .containsIgnoringCase("(\"dial_usage_log\".\"event_id\" in (select \"__adas_lm_key\" from (")
                .containsIgnoringCase("or \"dial_usage_log\".\"event_id\" is null")
                .doesNotContainIgnoringCase("__adas_lm_part");
    }

    @Test
    void theUnshapedBuilderRendersTodaysJoin() {
        final String sql = unshaped(lastTenWithBodies());

        // A window-only filter: the payload's derived table is bounded by the window on its own request_time.
        assertThat(sql)
                .doesNotContainIgnoringCase("__adas")
                .containsIgnoringCase("from \"dial_usage_log_payload\" final where \"dial_usage_log_payload\".\"request_time\" >=");
    }

    @Test
    void heavyColumnOutsideTheProjectionKeepsTodaysJoin() {
        final List<StructuredQuery> queries = List.of(
                // in the filter
                row(and(WINDOW, cmp(ComparisonOp.ICO, field(BODY), str("x"))), List.of(col(field(BODY))), null,
                        new OffsetPage(0, 10, false)),
                // sorted directly
                row(WINDOW, List.of(col(field(BODY))), List.of(new SortItem(BODY, SortDir.ASC, null)),
                        new OffsetPage(0, 10, false)),
                // sorted through the output name of an entry that reads it
                row(WINDOW, List.of(col(fn("length", field(BODY)), "len"), col(field(BODY))),
                        List.of(new SortItem("len", SortDir.ASC, null)), new OffsetPage(0, 10, false)));
        for (final StructuredQuery query : queries) {
            assertThat(render(query)).as(query.toString()).doesNotContainIgnoringCase("__adas_lm");
        }
    }

    @Test
    void shapesWhosePageCannotBeChosenFirstKeepTodaysJoin() {
        final List<OutputColumn> withBody = List.of(col(field("trace_id")), col(field(BODY)));
        final List<StructuredQuery> queries = List.of(
                new StructuredQuery("dial_usage_log", WINDOW, QueryMode.ROW, true, withBody, null, null, newestFirst(),
                        new OffsetPage(0, 10, false)),
                row(WINDOW, List.of(col(field(BODY)), col(new WindowExpr(fn("row_number"), null, null, null), "rn")),
                        newestFirst(), new OffsetPage(0, 10, false)),
                row(WINDOW, List.of(col(field(BODY)), col(fn("array_join",
                        fn("split_string", field("trace_id"), str("-"))), "part")), newestFirst(),
                        new OffsetPage(0, 10, false)),
                row(WINDOW, List.of(col(field("trace_id"), "__adas_lm_key"), col(field(BODY))), newestFirst(),
                        new OffsetPage(0, 10, false)),
                // two heavy enrichments on different source keys
                row(WINDOW, List.of(col(field(BODY)), col(field(TRACES))), newestFirst(), new OffsetPage(0, 10, false)));
        for (final StructuredQuery query : queries) {
            assertThat(pageKeySubqueries(render(query))).as(query.toString()).isEmpty();
        }
    }

    @Test
    void aggregateOverBodyIsNotLateMaterialized() {
        final StructuredQuery query = new StructuredQuery("dial_usage_log", WINDOW, QueryMode.AGGREGATE, false,
                List.of(col(field("deployment")), col(fn("max", field(BODY)), "m")), List.of("deployment"), null, null,
                null);

        assertThat(render(query)).doesNotContainIgnoringCase("__adas_lm");
    }

    @Test
    void theCountIsNeverLateMaterialized() {
        final StructuredQuery query = row(WINDOW, List.of(col(field("trace_id")), col(field(BODY))), newestFirst(),
                new OffsetPage(0, 10, true));

        // Read final, the 0..1 payload join cannot change a count, so the count joins only what its filter reads.
        assertThat(count(query)).doesNotContainIgnoringCase("__adas").doesNotContainIgnoringCase("dial_usage_log_payload");
        // Read raw, an unmerged enrichment version fans the row out in the page and so in the count: kept as is.
        assertThat(count(query.withFinalRead(false))).containsIgnoringCase("\"dial_usage_log_payload\"");
    }

    @Test
    void compositeAndPipelineOverloadsRenderUnshaped() {
        final StructuredQuery query = lastTenWithBodies();
        final String composite = builder.build(ctx, model(), query, StructuredQueryBuilder.MAX_LIMIT, LimitPolicy.GOVERNED)
                .getSQL(ParamType.INLINED);
        final String nested = builder.build(ctx, model(), query, StructuredQueryBuilder.MAX_LIMIT, LimitPolicy.NESTED,
                StructuredQueryBuilder.LeafRestriction.NONE).getSQL(ParamType.INLINED);
        final StructuredQuery pushable = row(and(WINDOW, cmp(ComparisonOp.ICO, field(BODY), str("e"))),
                List.of(col(fn("length", field(BODY)), "len")), null, new OffsetPage(0, 10, false));
        final String compositePushable = builder.build(ctx, model(), pushable, StructuredQueryBuilder.MAX_LIMIT,
                LimitPolicy.GOVERNED).getSQL(ParamType.INLINED);

        assertThat(composite).isEqualTo(unshaped(query)).doesNotContainIgnoringCase("__adas");
        assertThat(nested).doesNotContainIgnoringCase("__adas");
        assertThat(compositePushable).isEqualTo(unshaped(pushable)).doesNotContainIgnoringCase("__adas");
    }

    // ---------------------------------------------------------------------------------------------------------
    // B. Conjunct pushdown

    /** {@code table}'s derived table in {@code sql}: the text from its {@code FROM … FINAL} to its alias. */
    private static String derived(String sql, String table) {
        final Matcher matcher = Pattern.compile("from \"" + table + "\" final(.*?)\\) \"" + table + "\" on",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        assertThat(matcher.find()).as(table + " derived table in " + sql).isTrue();
        return matcher.group(1);
    }

    private static String derivedPayload(String sql) {
        return derived(sql, PAYLOAD);
    }

    /** The {@code WHERE} of {@code table}'s derived table, or {@code ""} when it has none. */
    private static String derivedWhere(String sql, String table) {
        final String body = derived(sql, table);
        final int where = body.toLowerCase().indexOf(" where ");
        return where < 0 ? "" : body.substring(where);
    }

    /** The statement's own {@code WHERE}, after every join. */
    private static String outerWhere(String sql) {
        final String lower = sql.toLowerCase();
        return sql.substring(lower.lastIndexOf(") \"" + PAYLOAD + "\" on"));
    }

    private static StructuredQuery countingWhere(FilterNode filter) {
        return row(filter, List.of(col(field("trace_id"))), null, new OffsetPage(0, 10, true));
    }

    @Test
    void allowListedConjunctsAreCopiedIntoTheDerivedTableAndKeptOutside() {
        final Map<FilterNode, String> shapes = new LinkedHashMap<>();
        shapes.put(cmp(ComparisonOp.ICO, field(NOTE), str("error")), "\"dial_usage_log_payload\".\"note\" ilike '%error%'");
        shapes.put(cmp(ComparisonOp.CO, field(NOTE), str("x")), "\"dial_usage_log_payload\".\"note\" like '%x%'");
        shapes.put(cmp(ComparisonOp.EQ, field(NOTE), str("v")), "\"dial_usage_log_payload\".\"note\" = 'v'");
        shapes.put(cmp(ComparisonOp.EQ, field(STATUS), new ValueExpr(ValueType.LONG, "500")),
                "\"dial_usage_log_payload\".\"status_code\" = 500");
        shapes.put(cmp(ComparisonOp.IN, field(NOTE), new ArrayExpr(List.of(str("a"), str("b")))),
                "\"dial_usage_log_payload\".\"note\" in (");
        // Over a light column: over a heavy one the call itself is also pushed (and read back as a column).
        shapes.put(cmp(ComparisonOp.EQ, fn("starts_with", field(NOTE), str("{")), new ValueExpr(ValueType.BOOLEAN, "true")),
                "startswith(\"dial_usage_log_payload\".\"note\", '{')");
        shapes.put(cmp(ComparisonOp.EQ, fn("contains", field(NOTE), str("tool")), new ValueExpr(ValueType.BOOLEAN, "true")),
                "position(\"dial_usage_log_payload\".\"note\", 'tool')");
        for (final Map.Entry<FilterNode, String> shape : shapes.entrySet()) {
            final StructuredQuery query = countingWhere(and(WINDOW, shape.getKey()));
            final String sql = render(query);
            assertThat(derivedPayload(sql)).as(shape.getKey().toString())
                    .containsIgnoringCase(shape.getValue())
                    .containsIgnoringCase("\"dial_usage_log_payload\".\"request_time\" >=");
            assertThat(outerWhere(sql)).as(shape.getKey().toString()).containsIgnoringCase(shape.getValue());
            assertThat(derivedPayload(count(query))).as("count " + shape.getKey()).containsIgnoringCase(shape.getValue());
        }
    }

    @Test
    void bodySearchIsDecidedInsideTheDerivedTableAndTheBodyIsNotJoined() {
        final StructuredQuery query = countingWhere(and(WINDOW, cmp(ComparisonOp.ICO, field(BODY), str("error"))));

        for (final String sql : List.of(render(query), count(query))) {
            assertThat(derivedWhere(sql, PAYLOAD)).containsIgnoringCase("\"request_body\" ilike '%error%'");
            assertThat(sql)
                    .containsIgnoringCase("(\"dial_usage_log_payload\".\"request_body\" ilike '%error%') \"__adas_c0\"")
                    .doesNotContainIgnoringCase("\"dial_usage_log_payload\".\"request_body\" from")
                    .doesNotContainIgnoringCase("\"dial_usage_log_payload\".\"request_body\" \"");
            assertThat(outerWhere(sql)).containsIgnoringCase("and \"dial_usage_log_payload\".\"__adas_c0\")")
                    .doesNotContainIgnoringCase("ilike");
        }
    }

    @Test
    void bodySearchBesideRawBodyReadIsCopiedAndTheBodyStaysJoined() {
        final StructuredQuery query = row(and(WINDOW, cmp(ComparisonOp.ICO, field(BODY), str("error"))),
                List.of(col(field("trace_id")), col(field(BODY))), List.of(new SortItem(BODY, SortDir.ASC, null)),
                new OffsetPage(0, 10, false));

        final String sql = render(query);

        assertThat(derivedWhere(sql, PAYLOAD)).containsIgnoringCase("\"request_body\" ilike '%error%'");
        assertThat(outerWhere(sql)).containsIgnoringCase("\"request_body\" ilike '%error%'");
        assertThat(sql).doesNotContainIgnoringCase("__adas");
    }

    @Test
    void conjunctsTrueOnAnUnmatchedRowStayOutsideOnly() {
        final List<FilterNode> shapes = List.of(
                cmp(ComparisonOp.EQ, field(NOTE), new ValueExpr(ValueType.NULL, null)),
                cmp(ComparisonOp.NE, field(NOTE), new ValueExpr(ValueType.NULL, null)),
                cmp(ComparisonOp.NE, field(NOTE), str("v")),
                cmp(ComparisonOp.INC, field(NOTE), str("v")),
                cmp(ComparisonOp.NC, field(NOTE), str("v")),
                cmp(ComparisonOp.LT, fn("length", field(NOTE)), new ValueExpr(ValueType.LONG, "5")),
                cmp(ComparisonOp.GT, field(STATUS), new ValueExpr(ValueType.LONG, "5")),
                new LogicalNode(LogicalOp.NOT, List.of(cmp(ComparisonOp.EQ, field(NOTE), str("v")))),
                new LogicalNode(LogicalOp.OR, List.of(cmp(ComparisonOp.EQ, field(NOTE), str("v")),
                        cmp(ComparisonOp.EQ, field(NOTE), str("w")))),
                cmp(ComparisonOp.EQ, field(NOTE), str("")),
                cmp(ComparisonOp.EQ, field(STATUS), new ValueExpr(ValueType.LONG, "0")),
                cmp(ComparisonOp.IN, field(NOTE), new ArrayExpr(List.of(str("a"), str("")))),
                cmp(ComparisonOp.ICO, field(NOTE), str("")),
                cmp(ComparisonOp.EQ, fn("starts_with", field(NOTE), str("")), new ValueExpr(ValueType.BOOLEAN, "true")),
                cmp(ComparisonOp.EQ, fn("starts_with", field(NOTE), str("a")), new ValueExpr(ValueType.BOOLEAN, "false")),
                // a document comparison: the default {} serializes to '{}'
                cmp(ComparisonOp.ICO, field(TAGS), str("{")),
                // an enum column
                cmp(ComparisonOp.EQ, field(KIND), str("web")),
                // two fields
                cmp(ComparisonOp.EQ, field(NOTE), field(EMAIL)));
        for (final FilterNode shape : shapes) {
            final String sql = render(countingWhere(and(WINDOW, shape)));
            for (final String table : List.of(PAYLOAD, "usage_client_identity")) {
                if (!sql.toLowerCase().contains("from \"" + table + "\" final")) {
                    continue;
                }
                assertThat(derivedWhere(sql, table)).as(shape + " in " + table)
                        .doesNotContainIgnoringCase("\"note\"")
                        .doesNotContainIgnoringCase("\"status_code\"")
                        .doesNotContainIgnoringCase("\"request_tags\"")
                        .doesNotContainIgnoringCase("\"client_kind\"")
                        .doesNotContainIgnoringCase("\"user_email\"");
            }
        }
    }

    @Test
    void unkeyedEnrichmentIsNarrowedByItsOwnConjunctAlone() {
        final StructuredQuery query = countingWhere(cmp(ComparisonOp.ICO, field(BODY), str("error")));

        final String derived = derivedPayload(render(query));

        assertThat(derived)
                .containsIgnoringCase("where \"dial_usage_log_payload\".\"request_body\" ilike '%error%'")
                .doesNotContainIgnoringCase(" in (select");
        assertThat(derivedPayload(count(query))).containsIgnoringCase("ilike '%error%'");
    }

    @Test
    void conjunctOnLightEnrichmentNarrowsItInTheLatePageBlockAndOuterStatement() {
        final StructuredQuery query = row(and(WINDOW, cmp(ComparisonOp.EQ, field(EMAIL), str("a@b"))),
                List.of(col(field("trace_id")), col(field(BODY))), newestFirst(), new OffsetPage(0, 10, false));

        final String sql = render(query);

        assertThat(pageKeySubqueries(sql)).hasSize(2);
        // Copied into the identity derived table and kept outside, in the page block (rendered twice) and the outer.
        assertThat(sql.split("(?i)\"usage_client_identity\".\"user_email\" = 'a@b'")).hasSize(7);
        assertThat(derivedWhere(sql, "usage_client_identity")).containsIgnoringCase("\"user_email\" = 'a@b'");
    }

    // ---------------------------------------------------------------------------------------------------------
    // C. Call pushdown

    private static StructuredQuery averageBodyLength() {
        return new StructuredQuery("dial_usage_log", WINDOW, QueryMode.AGGREGATE, false,
                List.of(col(field("deployment")), col(fn("avg", fn("length", field(BODY))))), List.of("deployment"), null,
                null, null);
    }

    @Test
    void averageBodyLengthJoinsNumberNotBody() {
        final String sql = render(averageBodyLength());

        assertThat(derivedPayload(sql)).isNotNull();
        assertThat(sql)
                .containsIgnoringCase("length(\"dial_usage_log_payload\".\"request_body\") \"__adas_c0\"")
                .containsIgnoringCase("avg(\"dial_usage_log_payload\".\"__adas_c0\") \"avg\"")
                .doesNotContainIgnoringCase("\"dial_usage_log_payload\".\"request_body\" from \"dial_usage_log_payload\"");
        assertThat(sql.split("(?i)\"request_body\"")).hasSize(2);
    }

    @Test
    void sharedCallsRenderOneDerivedColumn() {
        final StructuredQuery query = new StructuredQuery("dial_usage_log", WINDOW, QueryMode.AGGREGATE, false,
                List.of(col(fn("max", fn("length", field(BODY))), "longest"),
                        col(fn("min", fn("length", field(BODY))), "shortest"),
                        col(fn("max", fn("json_extract_string", field(RESPONSE), str("model"))), "model")),
                null, null, null, null);

        final String sql = render(query);

        assertThat(sql)
                .containsIgnoringCase("max(\"dial_usage_log_payload\".\"__adas_c0\") \"longest\"")
                .containsIgnoringCase("min(\"dial_usage_log_payload\".\"__adas_c0\") \"shortest\"")
                .containsIgnoringCase("jsonextractstring(\"dial_usage_log_payload\".\"response_body\", 'model') \"__adas_c1\"")
                .containsIgnoringCase("max(\"dial_usage_log_payload\".\"__adas_c1\") \"model\"");
        assertThat(sql.split("(?i)length\\(")).hasSize(2);
    }

    @Test
    void rawReferenceOrAnUnlistedCallKeepsTheWholeColumn() {
        final FilterNode longBody =
                cmp(ComparisonOp.GT, fn("length", field(BODY)), new ValueExpr(ValueType.LONG, "1000"));
        final List<FilterNode> beside = List.of(
                // a raw read
                cmp(ComparisonOp.INC, field(BODY), str("x")),
                // a call outside the list
                cmp(ComparisonOp.ICO, fn("lower", field(BODY)), str("x")),
                // starts_with with an empty literal: true on '', so not the result type's default
                cmp(ComparisonOp.EQ, fn("starts_with", field(BODY), str("")), new ValueExpr(ValueType.BOOLEAN, "true")),
                // a call inside a lambda body is evaluated per element
                cmp(ComparisonOp.EQ, fn("array_length", fn(HigherOrderFunctions.ARRAY_FILTER,
                        new LambdaExpr(List.of("x"), fn("equals", new LambdaParamExpr("x"),
                                fn("json_extract_string", field(BODY), str("a")))),
                        fn("split_string", field("trace_id"), str("-")))), new ValueExpr(ValueType.LONG, "1")));
        for (final FilterNode other : beside) {
            final String sql = count(countingWhere(and(WINDOW, longBody, other)));
            assertThat(sql).as(other.toString()).doesNotContainIgnoringCase("__adas_c");
            assertThat(sql).as(other.toString())
                    .containsIgnoringCase("\"dial_usage_log_payload\".\"request_body\" from \"dial_usage_log_payload\"");
        }
    }

    @Test
    void filterCallIsPushedForTheCountToo() {
        final StructuredQuery query = countingWhere(and(WINDOW,
                cmp(ComparisonOp.GT, fn("length", field(BODY)), new ValueExpr(ValueType.LONG, "1000"))));

        assertThat(count(query))
                .containsIgnoringCase("length(\"dial_usage_log_payload\".\"request_body\") \"__adas_c0\"")
                .containsIgnoringCase("\"dial_usage_log_payload\".\"__adas_c0\" > 1000");
        assertThat(render(query)).containsIgnoringCase("\"dial_usage_log_payload\".\"__adas_c0\" > 1000");
    }

    @Test
    void pushedCallOverAnUnkeyedEnrichmentStillGetsDerivedTable() {
        final StructuredQuery query = new StructuredQuery("dial_usage_log", null, QueryMode.AGGREGATE, false,
                List.of(col(fn("max", fn("length", field(BODY))), "longest")), null, null, null, null);

        final String derived = derivedPayload(render(query));

        assertThat(derived).doesNotContainIgnoringCase("where");
    }

    @Test
    void outputNamesAreDecidedOnTheCallAsWritten() {
        final StructuredQuery query = averageBodyLength();
        final List<String> shaped = builder.build(ctx, model(), query, StructuredQueryBuilder.MAX_LIMIT).getSelect()
                .stream().map(Field::getName).toList();
        final List<String> plain = builder.withoutReadShaping().build(ctx, model(), query, StructuredQueryBuilder.MAX_LIMIT)
                .getSelect().stream().map(Field::getName).toList();

        assertThat(shaped).isEqualTo(plain).containsExactly("deployment", "avg");
    }

    @Test
    void lateMaterializationTakesPrecedenceOverCallPushdown() {
        final StructuredQuery query = row(WINDOW, List.of(col(fn("length", field(BODY)), "len"), col(field("trace_id"))),
                newestFirst(), new OffsetPage(0, 10, false));

        final String sql = render(query);

        assertThat(sql).contains("__adas_lm_key").doesNotContain("__adas_c");
        assertThat(Arrays.asList(sql.split("(?i)\"dial_usage_log_payload\" final on"))).hasSize(2);
    }
}
