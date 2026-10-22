package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
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
import com.epam.aidial.analytics.web.security.Privilege;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query surface's object-path pruning against a real ClickHouse (the {@code structured-query} spec's "A keyed
 * grain join is bounded by an aligned partition column"): every statement is run beside the same query rendered by
 * {@link StructuredQueryBuilder#withoutPathPruning()}, and the two results must be identical byte for byte in
 * {@code TabSeparatedWithNamesAndTypes}.
 *
 * <p>The fixture carries the shapes pruning could get wrong: source rows with no payload row, documents missing the
 * addressed path, a key with three unmerged versions whose paths differ, and a heavy document with many paths.
 */
class QueryPathPruningIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PAYLOAD = "dial_usage_log_payload";
    private static final String CLAIMS = PAYLOAD + ".jwt_claims";
    private static final String ALIAS = "\"jwt_claims.email\"";

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
        for (int i = 1; i <= 10; i++) {
            final String at = "00:00:%02d.000".formatted(i);
            final String trace = "t%02d".formatted(i);
            execute("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, deployment, trace_id,"
                    + " core_span_id) VALUES ('" + eventIdFor(trace) + "', '" + fixtureTime(at) + "', 'chat', 'p1', 'd"
                    + (i % 3) + "', '" + trace + "', 's')");
            if (i == 4 || i == 9) {
                continue; // no payload row at all
            }
            final String claims = switch (i) {
                // no email path at all
                case 2 -> "{\"sub\":\"u2\",\"n\":2}";
                // a heavy document: many paths beside the addressed one
                case 5 -> heavyDocument();
                case 7 -> "{}";
                default -> "{\"email\":\"user" + (i % 2) + "@x.io\",\"n\":" + i + ",\"ok\":true,\"r\":1.5,"
                        + "\"roles\":[\"a\",\"b" + i + "\"],\"nested\":{\"k\":\"v" + i + "\"}}";
            };
            insertPayload(i, at, claims, "2026-01-01 00:00:00.000");
        }
        // t06: two older versions whose email differs from the newest.
        insertPayload(6, "00:00:06.000", "{\"email\":\"old@x.io\"}", "2025-01-01 00:00:00.000");
        insertPayload(6, "00:00:06.000", "{\"email\":\"mid@x.io\",\"n\":60}", "2025-06-01 00:00:00.000");
    }

    @AfterEach
    void restartMerges() {
        execute("SYSTEM START MERGES " + PAYLOAD);
    }

    private static String heavyDocument() {
        final StringBuilder doc = new StringBuilder("{\"email\":\"heavy@x.io\"");
        for (int k = 0; k < 300; k++) {
            doc.append(",\"p").append(k).append("\":\"").append("v".repeat(200)).append('"');
        }
        return doc.append('}').toString();
    }

    private void insertPayload(int i, String at, String claims, String updatedAt) {
        final String trace = "t%02d".formatted(i);
        execute("INSERT INTO " + PAYLOAD + " (event_id, request_time, _updated_at, trace_id, core_span_id, jwt_claims)"
                + " VALUES ('" + eventIdFor(trace) + "', '" + fixtureTime(at) + "', '" + updatedAt + "', '" + trace
                + "', 's', '" + claims + "')");
    }

    private QueryEntityModel model() {
        return catalog.findModel("dial_usage_log", Privilege.TABLE_READ).orElseThrow();
    }

    private static final FilterNode WINDOW = new ComparisonNode(ComparisonOp.GE, List.of(new FieldExpr("request_time"),
            new ValueExpr(ValueType.TIMESTAMP, String.valueOf(LocalDateTime.parse(fixtureTime("00:00:00.000")
                    .replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()))));

    private static FieldExpr field(String name) {
        return new FieldExpr(name);
    }

    private static ValueExpr str(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static FnExpr fn(String name, Expr... args) {
        return new FnExpr(name, false, List.of(args));
    }

    private static Expr path(String extractor, String key) {
        return fn(extractor, field(CLAIMS), str(key));
    }

    private static OutputColumn col(Expr expr, String as) {
        return new OutputColumn(expr, as);
    }

    private static FilterNode and(FilterNode... args) {
        return new LogicalNode(LogicalOp.AND, List.of(args));
    }

    private String run(QueryEntityModel model, StructuredQuery query, SelectQuery<Record> select) {
        final boolean partitionLocal = model.partitionLocal() && builder.readsFinal(model, query);
        return queryText(select.getSQL(ParamType.INLINED)
                + (partitionLocal ? " SETTINGS do_not_merge_across_partitions_select_final = 1" : ""));
    }

    /** Asserts the pruned statement reads the alias and returns exactly the whole-column statement's rows. */
    private void assertIdentical(StructuredQuery query) {
        final QueryEntityModel model = model();
        final SelectQuery<Record> pruned = builder.build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
        final SelectQuery<Record> whole = builder.withoutPathPruning()
                .build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
        assertThat(pruned.getSQL(ParamType.INLINED)).as("pruned statement").contains(ALIAS);
        assertThat(whole.getSQL(ParamType.INLINED)).as("reference statement").doesNotContain(ALIAS);
        final String expected = run(model, query, whole);
        assertThat(run(model, query, pruned)).as(query.toString()).isEqualTo(expected);
        assertThat(expected.lines().count()).as("rows returned").isGreaterThan(2);
    }

    private void assertIdenticalCounts(StructuredQuery query) {
        final QueryEntityModel model = model();
        final SelectQuery<Record> pruned = builder.buildCount(renderContext, model, query);
        final SelectQuery<Record> whole = builder.withoutPathPruning().buildCount(renderContext, model, query);
        assertThat(pruned.getSQL(ParamType.INLINED)).contains(ALIAS);
        assertThat(run(model, query, pruned)).isEqualTo(run(model, query, whole));
    }

    @Test
    void groupedEmailReturnsTheWholeColumnGroups() {
        // p3's shape: unmatched rows and documents without the path group under the extractor's default.
        assertIdentical(new StructuredQuery("dial_usage_log", WINDOW, QueryMode.AGGREGATE, false,
                List.of(col(path("json_extract_string", "email"), "e"), col(fn("count"), "c")), List.of("e"), null,
                List.of(new SortItem("c", SortDir.DESC, null), new SortItem("e", SortDir.ASC, null)), null));
    }

    @Test
    void everyExtractorInTheProjectionReturnsTheWholeColumnValues() {
        assertIdentical(new StructuredQuery("dial_usage_log", WINDOW, QueryMode.ROW, false,
                List.of(col(field("trace_id"), null), col(path("json_extract_string", "email"), "e"),
                        col(path("json_extract_long", "n"), "n"), col(path("json_extract_double", "r"), "r"),
                        col(path("json_extract_bool", "ok"), "ok"), col(path("json_extract_array", "roles"), "roles"),
                        col(fn("json_extract_string", field(CLAIMS), str("nested"), str("k")), "nk")),
                null, null, List.of(new SortItem("trace_id", SortDir.ASC, null)), null));
    }

    @Test
    void filteredPathReturnsTheWholeColumnRowsAndCount() {
        final StructuredQuery query = new StructuredQuery("dial_usage_log",
                and(WINDOW, new ComparisonNode(ComparisonOp.NE, List.of(path("json_extract_string", "email"),
                        str("user0@x.io")))),
                QueryMode.ROW, false, List.of(col(field("trace_id"), null), col(path("json_extract_long", "n"), "n")),
                null, null, List.of(new SortItem("trace_id", SortDir.ASC, null)), new OffsetPage(0, 50, true));
        assertIdentical(query);
        assertIdenticalCounts(query);
    }

    @Test
    void heavyDocumentUnderAnExtractorReturnsTheWholeColumnValue() {
        assertIdentical(new StructuredQuery("dial_usage_log", WINDOW, QueryMode.AGGREGATE, false,
                List.of(col(field("deployment"), null), col(fn("max", path("json_extract_string", "email")), "m"),
                        col(fn("sum", path("json_extract_long", "n")), "s")),
                List.of("deployment"), null, List.of(new SortItem("deployment", SortDir.ASC, null)), null));
    }
}
