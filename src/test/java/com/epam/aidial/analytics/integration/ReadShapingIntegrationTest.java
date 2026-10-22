package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
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
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.security.Privilege;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read path's shaping against a real ClickHouse (the {@code structured-query} spec's late-materialization,
 * conjunct-pushdown and call-pushdown requirements): every shaped statement is run beside the same query rendered
 * by {@link StructuredQueryBuilder#withoutReadShaping()}, and the two results must be identical byte for byte in
 * {@code TabSeparatedWithNamesAndTypes} — names, types, values, NULLs and type defaults.
 *
 * <p>The fixture carries every shape the rewrites could get wrong: source rows with no payload row; a payload key
 * with three unmerged versions (merges stopped), whose oldest version matches the body search and whose newest does
 * not; payload rows whose body is NULL and {@code ''}; a body over 1 MB; and rows tying on {@code request_time}.
 */
class ReadShapingIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PAYLOAD = "dial_usage_log_payload";
    private static final String BODY = PAYLOAD + ".request_body";
    private static final String RESPONSE = PAYLOAD + ".response_body";

    @Autowired
    private CatalogQueryService catalog;

    @Autowired
    private StructuredQueryBuilder builder;

    @Autowired
    private DSLContext renderContext;

    @BeforeEach
    void seed() {
        truncate();
        execute("SYSTEM STOP MERGES " + PAYLOAD);
        for (int i = 1; i <= 12; i++) {
            // t08..t10 tie on request_time; everything else is a second apart.
            final String at = i >= 8 && i <= 10 ? "00:00:08.000" : "00:00:%02d.000".formatted(i);
            final String trace = "t%02d".formatted(i);
            execute("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, deployment, trace_id,"
                    + " core_span_id) VALUES ('" + eventIdFor(trace) + "', '" + fixtureTime(at) + "', 'chat', 'p1', '"
                    + (i % 2 == 0 ? "gpt" : "claude") + "', '" + trace + "', 's')");
            if (i == 3 || i == 7) {
                continue; // no payload row at all
            }
            final String body = switch (i) {
                case 5 -> "concat('{\"model\":\"big\",\"pad\":\"', repeat('z', 600000), repeat('y', 600000), '\"}')";
                case 11 -> "NULL";
                case 12 -> "''";
                default -> "'{\"model\":\"m" + i + "\",\"n\":" + i + ",\"ok\":true,\"tags\":[\"a\",\"b" + i + "\"]}'";
            };
            final String response = i == 4 ? "NULL" : "'resp-" + i + "'";
            insertPayload(i, at, body, response, "2026-01-01 00:00:00.000");
        }
        // t06: two older versions, the oldest of which matches the body search; the newest does not.
        insertPayload(6, "00:00:06.000", "'old needle version'", "'resp-old'", "2025-01-01 00:00:00.000");
        insertPayload(6, "00:00:06.000", "'middle needle version'", "'resp-mid'", "2025-06-01 00:00:00.000");
        // t02: matches the body search in its newest version.
        insertPayload(2, "00:00:02.000", "'{\"model\":\"m2\",\"note\":\"needle\"}'", "'resp-2b'", "2026-06-01 00:00:00.000");
    }

    @AfterEach
    void restartMerges() {
        execute("SYSTEM START MERGES " + PAYLOAD);
    }

    private void insertPayload(int i, String at, String body, String response, String updatedAt) {
        final String trace = "t%02d".formatted(i);
        execute("INSERT INTO " + PAYLOAD + " (event_id, request_time, _updated_at, trace_id, core_span_id, request_body,"
                + " response_body) VALUES ('" + eventIdFor(trace) + "', '" + fixtureTime(at) + "', '" + updatedAt + "', '"
                + trace + "', 's', " + body + ", " + response + ")");
    }

    private QueryEntityModel model() {
        return catalog.findModel("dial_usage_log", Privilege.TABLE_READ).orElseThrow();
    }

    private static String epochMillis(String timeOfDay) {
        return String.valueOf(LocalDateTime.parse(fixtureTime(timeOfDay).replace(' ', 'T'))
                .toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    private static final FilterNode WINDOW = new ComparisonNode(ComparisonOp.GE,
            List.of(new FieldExpr("request_time"), new ValueExpr(ValueType.TIMESTAMP, epochMillis("00:00:00.000"))));

    private static FieldExpr field(String name) {
        return new FieldExpr(name);
    }

    private static FnExpr fn(String name, Expr... args) {
        return new FnExpr(name, false, List.of(args));
    }

    private static ValueExpr str(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static OutputColumn col(Expr expr) {
        return new OutputColumn(expr, null);
    }

    private static OutputColumn col(Expr expr, String as) {
        return new OutputColumn(expr, as);
    }

    private static StructuredQuery row(FilterNode filter, List<OutputColumn> select, List<SortItem> sort, OffsetPage page) {
        return new StructuredQuery("dial_usage_log", filter, QueryMode.ROW, false, select, null, null, sort, page);
    }

    private static List<SortItem> sort(Object... fieldsAndDirs) {
        final List<SortItem> items = new ArrayList<>();
        for (int i = 0; i < fieldsAndDirs.length; i += 2) {
            items.add(new SortItem((String) fieldsAndDirs[i], (SortDir) fieldsAndDirs[i + 1], null));
        }
        return items;
    }

    /** {@code select} rendered against the live model, with the settings the read path sends for it. */
    private String run(QueryEntityModel model, StructuredQuery query, SelectQuery<Record> select) {
        final boolean partitionLocal = model.partitionLocal() && builder.readsFinal(model, query);
        return queryText(select.getSQL(ParamType.INLINED)
                + (partitionLocal ? " SETTINGS do_not_merge_across_partitions_select_final = 1" : ""));
    }

    /** Runs {@code query} shaped and unshaped, asserts the shaped SQL contains {@code marker}, returns both results. */
    private String[] both(StructuredQuery query, String marker) {
        final QueryEntityModel model = model();
        final SelectQuery<Record> shaped = builder.build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
        final SelectQuery<Record> plain =
                builder.withoutReadShaping().build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
        assertThat(shaped.getSQL(ParamType.INLINED)).as("shaped statement").contains(marker);
        assertThat(plain.getSQL(ParamType.INLINED)).as("unshaped statement").doesNotContain("__adas");
        return new String[] {run(model, query, shaped), run(model, query, plain)};
    }

    private void assertIdentical(StructuredQuery query, String marker) {
        final String[] results = both(query, marker);
        assertThat(results[0]).as(query.toString()).isEqualTo(results[1]);
        assertThat(results[0].lines().count()).as("rows returned").isGreaterThan(2);
    }

    private void assertIdenticalCounts(StructuredQuery query, String marker) {
        final QueryEntityModel model = model();
        final SelectQuery<Record> shaped = builder.buildCount(renderContext, model, query);
        final SelectQuery<Record> plain = builder.withoutReadShaping().buildCount(renderContext, model, query);
        assertThat(shaped.getSQL(ParamType.INLINED)).contains(marker);
        assertThat(run(model, query, shaped)).isEqualTo(run(model, query, plain));
    }

    // ---------------------------------------------------------------------------------------------------------
    // A. Late materialization

    @Test
    void lateMaterializedPagesReturnTheUnshapedRows() {
        final List<OutputColumn> select = List.of(col(field("trace_id")), col(field("request_time")), col(field(BODY)),
                col(field(RESPONSE)), col(fn("length", field(BODY)), "len"));
        // Pages that do not cut through the tie at 00:00:08, with and without an offset, newest and oldest first.
        assertIdentical(row(WINDOW, select, sort("request_time", SortDir.DESC), new OffsetPage(0, 2, false)),
                "__adas_lm_key");
        assertIdentical(row(WINDOW, select, sort("request_time", SortDir.ASC), new OffsetPage(2, 5, false)),
                "__adas_lm_key");
        assertIdentical(row(WINDOW, select, sort("request_time", SortDir.DESC), new OffsetPage(5, 7, false)),
                "__adas_lm_key");
        assertIdentical(row(WINDOW, select, sort("trace_id", SortDir.ASC), new OffsetPage(3, 6, false)), "__adas_lm_key");
        // Every row, under the default page. (A raw read cannot be exercised here: the application runs with
        // query.force-final, so `final: false` still reads final; ReadShapingRenderTest covers the raw shape.)
        assertIdentical(row(WINDOW, select, sort("trace_id", SortDir.ASC), null), "__adas_lm_key");
    }

    @Test
    void theTotalOfBodyPageJoinsNoPayloadAndCountsTheSame() {
        final StructuredQuery query = row(WINDOW, List.of(col(field("trace_id")), col(field(BODY))),
                sort("request_time", SortDir.DESC), new OffsetPage(0, 2, true));
        final QueryEntityModel model = model();

        assertThat(builder.buildCount(renderContext, model, query).getSQL(ParamType.INLINED)).doesNotContain(PAYLOAD);
        assertIdenticalCounts(query, "count(*)");
        assertThat(queryText(builder.buildCount(renderContext, model, query).getSQL(ParamType.INLINED)))
                .endsWith("\n12\n");
    }

    @Test
    void pageCuttingThroughTieReturnsValidPageWithEveryBody() {
        final List<OutputColumn> select = List.of(col(field("trace_id")), col(field("request_time")), col(field(BODY)));
        final StructuredQuery query = row(WINDOW, select, sort("request_time", SortDir.ASC), new OffsetPage(7, 2, false));
        final String[] results = both(query, "__adas_lm_key");

        // The page boundary falls inside the three rows tying at 00:00:08: either statement may pick any two of them.
        assertThat(column(results[0], 1)).isEqualTo(column(results[1], 1));
        // Whichever rows the shaped statement picked, each carries its own body, never an unmatched default.
        for (final String line : results[0].lines().skip(2).toList()) {
            final String[] cells = line.split("\t", -1);
            final String expected = queryScalar("SELECT request_body AS b FROM " + PAYLOAD + " FINAL WHERE trace_id = '"
                    + cells[0] + "'", "b");
            assertThat(cells[2]).as(cells[0]).isEqualTo(expected);
        }
    }

    @Test
    void thePageKeysAreEvaluatedOnce() {
        // The D1 shape with a page block ordered by a per-row random value: a second evaluation of the page keys
        // would pick other rows, and every one of those would read its body as unmatched.
        final String keys = "(SELECT \"__adas_lm_key\", \"__adas_lm_part\" FROM (SELECT l.event_id AS \"__adas_lm_key\","
                + " l.request_time AS \"__adas_lm_part\" FROM dial_usage_log AS l FINAL"
                + " WHERE l.event_id IN (SELECT event_id FROM " + PAYLOAD + ") ORDER BY rand() LIMIT 4) AS \"__adas_lm\")";
        final String sql = "SELECT count() AS kept, countIf(p.event_id = toUUID('00000000-0000-0000-0000-000000000000'))"
                + " AS unmatched FROM dial_usage_log AS s FINAL LEFT JOIN " + PAYLOAD + " AS p FINAL"
                + " ON s.event_id = p.event_id AND (p.event_id, p.request_time) IN " + keys
                + " WHERE ((s.event_id, s.request_time) IN " + keys + " OR s.event_id IS NULL OR s.request_time IS NULL)"
                + " SETTINGS do_not_merge_across_partitions_select_final = 1";
        for (int run = 0; run < 20; run++) {
            assertThat(queryText(sql).lines().skip(2).toList()).as("run " + run).containsExactly("4\t0");
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // B. Conjunct pushdown

    @Test
    void copiedBodySearchReturnsTheUnshapedRowsAndCount() {
        final FilterNode needle = new LogicalNode(LogicalOp.AND, List.of(WINDOW,
                new ComparisonNode(ComparisonOp.ICO, List.of(field(BODY), str("needle")))));
        final StructuredQuery query = row(needle, List.of(col(field("trace_id")), col(field(RESPONSE))),
                sort("trace_id", SortDir.ASC), new OffsetPage(0, 10, true));

        final String[] results = both(query, "ilike '%needle%')) \"dial_usage_log_payload\"");
        assertThat(results[0]).isEqualTo(results[1]);
        // t06's older versions match, its newest does not: only t02 is returned, through either statement.
        assertThat(column(results[0], 0)).containsExactly("t02");
        assertIdenticalCounts(query, "ilike '%needle%'))");
    }

    @Test
    void copiedEqualityOverAnUnkeyedJoinReturnsTheUnshapedRows() {
        final FilterNode resp = new ComparisonNode(ComparisonOp.EQ, List.of(field(RESPONSE), str("resp-9")));
        final StructuredQuery query = row(resp, List.of(col(field("trace_id"))), sort("trace_id", SortDir.ASC),
                new OffsetPage(0, 10, true));

        final String[] results = both(query, "where \"dial_usage_log_payload\".\"response_body\" = 'resp-9')");
        assertThat(results[0]).isEqualTo(results[1]);
        assertThat(column(results[0], 0)).containsExactly("t09");
        assertIdenticalCounts(query, "where \"dial_usage_log_payload\".\"response_body\" = 'resp-9')");
    }

    // ---------------------------------------------------------------------------------------------------------
    // C. Call pushdown

    @Test
    void pushedAggregateCallsReturnTheUnshapedValues() {
        final StructuredQuery averages = new StructuredQuery("dial_usage_log", WINDOW, QueryMode.AGGREGATE, false,
                List.of(col(field("deployment")), col(fn("avg", fn("length", field(BODY)))),
                        col(fn("max", fn("length", field(RESPONSE))), "longest"), col(fn("count")),
                        col(fn("max", fn("json_extract_string", field(BODY), str("model"))), "model")),
                List.of("deployment"), null, sort("deployment", SortDir.ASC), null);
        assertIdentical(averages, "\"__adas_c0\"");

        final StructuredQuery perDay = new StructuredQuery("dial_usage_log", WINDOW, QueryMode.AGGREGATE, false,
                List.of(col(fn("date_trunc", str("day"), field("request_time")), "day"),
                        col(fn("max", fn("length", field(RESPONSE))), "longest")),
                List.of("day"), null, sort("day", SortDir.ASC), null);
        final String[] results = both(perDay, "\"__adas_c0\"");
        assertThat(results[0]).isEqualTo(results[1]);
    }

    @Test
    void everyPushedCallReturnsTheUnshapedValueForMatchedAndUnmatchedRows() {
        final List<Expr> calls = List.of(
                fn("length", field(BODY)),
                fn("json_extract_string", field(BODY), str("model")),
                fn("json_extract_long", field(BODY), str("n")),
                fn("json_extract_double", field(BODY), str("n")),
                fn("json_extract_bool", field(BODY), str("ok")),
                fn("json_extract_array", field(BODY), str("tags")),
                fn("json_extract_string_ci", field(BODY), str("MODEL")),
                fn("starts_with", field(BODY), str("{")),
                fn("contains", field(BODY), str("needle")));
        for (final Expr call : calls) {
            // Sorted by the call's own output, so the block is not late-materialized and the call is pushed.
            final StructuredQuery query = row(WINDOW, List.of(col(field("trace_id")), col(call, "v")),
                    sort("v", SortDir.ASC, "trace_id", SortDir.ASC), null);
            assertIdentical(query, "\"__adas_c0\"");
        }
    }

    @Test
    void pushedCallsOverNonNullableHeavyColumnReturnTheUnshapedValues() {
        // The shipped heavy columns are Nullable(String); a user enrichment's heavy text column may not be. A
        // throwaway pair of tables and a hand-built model cover that type, unmatched rows included.
        execute("DROP TABLE IF EXISTS rs_src");
        execute("DROP TABLE IF EXISTS rs_enr");
        execute("CREATE TABLE rs_src (k String, at DateTime64(3, 'UTC')) ENGINE = MergeTree ORDER BY k");
        execute("CREATE TABLE rs_enr (k String, at DateTime64(3, 'UTC'), body String, _updated_at DateTime64(3))"
                + " ENGINE = ReplacingMergeTree(_updated_at) ORDER BY k");
        try {
            execute("INSERT INTO rs_src VALUES ('a', '2026-01-01 00:00:01'), ('b', '2026-01-01 00:00:02'),"
                    + " ('c', '2026-01-01 00:00:03'), ('d', '2026-01-01 00:00:04')");
            execute("INSERT INTO rs_enr VALUES ('a', '2026-01-01 00:00:01', '{\"model\":\"x\",\"n\":2,\"ok\":true,"
                    + "\"tags\":[\"t\"]}', now64()), ('b', '2026-01-01 00:00:02', '', now64())");
            final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
            bindings.put("k", new QueryFieldBinding("k", DSL.field(DSL.name("rs_src", "k"), JooqTypes.of(QueryFieldType.STRING)),
                    QueryFieldType.STRING));
            bindings.put("rs_enr.body", new QueryFieldBinding("rs_enr.body",
                    DSL.field(DSL.name("rs_enr", "body"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING,
                    null, true));
            final EnrichmentJoin join = EnrichmentJoin.keyed(DSL.table(DSL.name("rs_enr")),
                    DSL.field(DSL.name("rs_src", "k")), DSL.field(DSL.name("rs_enr", "k")),
                    DSL.field(DSL.name("rs_src", "at")), DSL.field(DSL.name("rs_enr", "at")), Set.of("rs_enr.body"), null);
            final QueryEntityModel model = new QueryEntityModel("rs_src", DSL.table(DSL.name("rs_src")), bindings,
                    List.of(join), List.of(), null, false, false);
            final List<Expr> calls = List.of(
                    fn("length", field("rs_enr.body")),
                    fn("json_extract_string", field("rs_enr.body"), str("model")),
                    fn("json_extract_long", field("rs_enr.body"), str("n")),
                    fn("json_extract_double", field("rs_enr.body"), str("n")),
                    fn("json_extract_bool", field("rs_enr.body"), str("ok")),
                    fn("json_extract_array", field("rs_enr.body"), str("tags")),
                    fn("json_extract_string_ci", field("rs_enr.body"), str("MODEL")),
                    fn("starts_with", field("rs_enr.body"), str("{")),
                    fn("contains", field("rs_enr.body"), str("model")));
            for (final Expr call : calls) {
                final StructuredQuery query = new StructuredQuery("rs_src", null, QueryMode.ROW, false,
                        List.of(col(field("k")), col(call, "v")), null, null, sort("v", SortDir.ASC, "k", SortDir.ASC),
                        null);
                final SelectQuery<Record> shaped = builder.build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
                final SelectQuery<Record> plain = builder.withoutReadShaping()
                        .build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
                assertThat(shaped.getSQL(ParamType.INLINED)).contains("\"__adas_c0\"");
                final String expected = queryText(plain.getSQL(ParamType.INLINED));
                assertThat(queryText(shaped.getSQL(ParamType.INLINED))).as(call.toString()).isEqualTo(expected);
                assertThat(expected.lines().count()).isEqualTo(6);
            }
        } finally {
            execute("DROP TABLE IF EXISTS rs_src");
            execute("DROP TABLE IF EXISTS rs_enr");
        }
    }

    /** Column {@code index} of every data row of a {@code TabSeparatedWithNamesAndTypes} result. */
    private static List<String> column(String result, int index) {
        return result.lines().skip(2).map(line -> line.split("\t", -1)[index]).toList();
    }
}
