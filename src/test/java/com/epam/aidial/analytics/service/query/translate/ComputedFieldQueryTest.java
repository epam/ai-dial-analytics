package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ComputedFields;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The computed fields in the DSL builder, rendered offline: the stand-in SQL a projection renders, the columns a keyed
 * derived payload table projects for it, the late-materialized shape keeping it in the outer statement, and the
 * projection-only rule refusing every other use (see {@code openspec/specs/structured-query/spec.md}, "Computed fields
 * are projection-only and completed after the statement runs").
 */
class ComputedFieldQueryTest {

    private static final String FULL = "dial_usage_log_payload.request_body_full";
    private static final String STATUS = "dial_usage_log_payload.request_body_rebuild";

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final JsonMapper json = new JsonMapperConfiguration().jsonMapper();
    private final StructuredQueryBuilder shaped = newBuilder();
    private final StructuredQueryBuilder unshaped = newBuilder().withoutReadShaping();

    private static StructuredQueryBuilder newBuilder() {
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
    }

    /** {@code dial_usage_log} with its payload enrichment, aligned on {@code request_time}, as the catalog builds it. */
    private static QueryEntityModel model() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("trace_id", binding("trace_id", "dial_usage_log", "trace_id", false));
        bindings.put("request_time", new QueryFieldBinding("request_time",
                DSL.field(DSL.name("dial_usage_log", "request_time"), JooqTypes.of(QueryFieldType.TIMESTAMP)), QueryFieldType.TIMESTAMP));
        bindings.put("dial_usage_log_payload.request_body", binding("dial_usage_log_payload.request_body", ComputedFields.TABLE, "request_body", true));
        bindings.put(FULL, QueryFieldBinding.computed(FULL, ComputedFields.REQUEST_BODY_FULL, ComputedFields.TABLE));
        bindings.put(STATUS, QueryFieldBinding.computed(STATUS, ComputedFields.REQUEST_BODY_REBUILD, ComputedFields.TABLE));
        final EnrichmentJoin join = EnrichmentJoin.keyed(DSL.table(DSL.name(ComputedFields.TABLE)),
                DSL.field(DSL.name("dial_usage_log", "event_id")), DSL.field(DSL.name(ComputedFields.TABLE, "event_id")),
                DSL.field(DSL.name("dial_usage_log", "request_time")), DSL.field(DSL.name(ComputedFields.TABLE, "request_time")),
                Set.of("dial_usage_log_payload.request_body", FULL, STATUS), null);
        return new QueryEntityModel("dial_usage_log", DSL.table(DSL.name("dial_usage_log")), bindings, List.of(join), List.of(), null, true);
    }

    private static QueryFieldBinding binding(String name, String table, String column, boolean heavy) {
        return new QueryFieldBinding(name, DSL.field(DSL.name(table, column), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING,
                null, heavy);
    }

    private StructuredQuery query(String body) {
        return json.readValue(body, StructuredQuery.class);
    }

    private static String select(String... entries) {
        return "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"filter\":" + TRACE + ",\"select\":[" + String.join(",", entries) + "]}";
    }

    private static String field(String name) {
        return "{\"expr\":{\"type\":\"field\",\"name\":\"" + name + "\"}}";
    }

    private static final String TRACE = "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"trace_id\"},"
            + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"trace-0001\"}]}";

    @Test
    void projectionRendersOneTupleCellPerFieldUnderItsOutputName() {
        final String sql = unshaped.build(ctx, model(), query(select(field("trace_id"), field(FULL), field(STATUS))))
                .getSQL(ParamType.INLINED);

        assertThat(sql)
                .contains("tuple(\"dial_usage_log_payload\".\"request_body\", toString(\"dial_usage_log_payload\".\"request_parent_hash\"),"
                        + " arrayMap(x -> toString(x), \"dial_usage_log_payload\".\"request_ancestor_hashes\"),"
                        + " \"dial_usage_log_payload\".\"request_cache_control\", toYYYYMMDD(\"dial_usage_log_payload\".\"request_time\"))"
                        + " \"" + FULL + "\"")
                .contains("\"dial_usage_log_payload\".\"request_body.null\"")
                .contains("\"dial_usage_log_payload\".\"request_cache_control.null\"")
                .contains(") \"" + STATUS + "\"");
    }

    /** Keyed, the payload is a derived table, which projects each column the stand-ins read once. */
    @Test
    void keyedDerivedTableProjectsThePhysicalColumnsTheStandInsRead() {
        final String sql = unshaped.build(ctx, model(), query(select(field(FULL), field(STATUS), field("dial_usage_log_payload.request_body"))))
                .getSQL(ParamType.INLINED);
        final String derived = sql.substring(sql.indexOf("left outer join (select"), sql.indexOf(") \"dial_usage_log_payload\""));

        assertThat(derived).contains("\"dial_usage_log_payload\".\"request_parent_hash\"", "\"dial_usage_log_payload\".\"request_ancestor_hashes\"",
                "\"dial_usage_log_payload\".\"request_cache_control\"", "\"dial_usage_log_payload\".\"request_body.null\"",
                "\"dial_usage_log_payload\".\"request_cache_control.null\"");
        assertThat(derived.split("\"dial_usage_log_payload\".\"request_body\"", -1)).hasSize(2);
        assertThat(derived.split("\"dial_usage_log_payload\".\"request_time\"", -1)).hasSize(3); // projected once, bounded once
        assertThat(derived).doesNotContain("tuple(");
    }

    /** Late-materialized: the page block projects keys only, and the outer statement renders the stand-in. */
    @Test
    void lateMaterializedPageKeepsTheStandInInTheOuterStatement() {
        final String sql = shaped.build(ctx, model(), query("{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"filter\":" + TRACE
                + ",\"select\":[" + field("trace_id") + "," + field(FULL) + "],\"page\":{\"type\":\"offset\",\"offset\":0,\"limit\":10}}"))
                .getSQL(ParamType.INLINED);
        final int page = sql.indexOf("__adas_lm");

        assertThat(page).isPositive();
        assertThat(sql.indexOf("tuple(")).isLessThan(sql.indexOf("from"));
        assertThat(sql.substring(sql.indexOf("from"))).doesNotContain("tuple(");
    }

    @Test
    void plainProjectionWithOrWithoutAnAliasIsAccepted() {
        unshaped.build(ctx, model(), query(select(field(FULL), "{\"expr\":{\"type\":\"field\",\"name\":\"" + STATUS + "\"},\"as\":\"status\"}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // a filter operand
        "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"" + STATUS + "\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"partial\"}]},\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"trace_id\"}}]}",
        // a null check
        "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"" + FULL + "\"},"
                + "{\"type\":\"value\",\"value_type\":\"null\",\"value\":null}]},\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"trace_id\"}}]}",
        // a sort key
        "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"trace_id\"}}],"
                + "\"sort\":[{\"field\":\"" + STATUS + "\",\"dir\":\"asc\"}]}",
        // a sort through the output name of a select entry reading one
        "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"" + FULL + "\"},\"as\":\"body\"}],"
                + "\"sort\":[{\"field\":\"body\",\"dir\":\"desc\"}]}",
        // a function argument
        "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"json_extract_string\",\"args\":["
                + "{\"type\":\"field\",\"name\":\"" + FULL + "\"},{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"model\"}]}}]}",
        // a case branch
        "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"case\",\"when\":[{\"when\":{\"op\":\"eq\",\"args\":["
                + "{\"type\":\"field\",\"name\":\"trace_id\"},{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"x\"}]},"
                + "\"then\":{\"type\":\"field\",\"name\":\"" + FULL + "\"}}]},\"as\":\"c\"}]}",
        // a window
        "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"window\",\"fn\":{\"type\":\"fn\",\"name\":\"row_number\","
                + "\"args\":[]},\"partition_by\":[{\"type\":\"field\",\"name\":\"" + STATUS + "\"}]},\"as\":\"rn\"}]}",
        // array_join
        "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"array_join\",\"args\":["
                + "{\"type\":\"field\",\"name\":\"" + FULL + "\"}]}}]}",
        // aggregate mode, grouped
        "{\"entity\":\"dial_usage_log\",\"mode\":\"aggregate\",\"group_by\":[\"" + STATUS + "\"],"
                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"" + STATUS + "\"}},{\"expr\":{\"type\":\"fn\",\"name\":\"count\",\"args\":[]}}]}",
        // aggregate mode, measured
        "{\"entity\":\"dial_usage_log\",\"mode\":\"aggregate\",\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"max\",\"args\":["
                + "{\"type\":\"field\",\"name\":\"" + FULL + "\"}]}}]}",
        // a having operand
        "{\"entity\":\"dial_usage_log\",\"mode\":\"aggregate\",\"group_by\":[\"trace_id\"],\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"trace_id\"}}],"
                + "\"having\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"" + STATUS + "\"},{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"x\"}]}}",
        // a distinct query
        "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"distinct\":true,\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"" + STATUS + "\"}}]}"
    })
    void everyOtherUseIsRefusedNamingTheField(String body) {
        assertThatThrownBy(() -> shaped.build(ctx, model(), query(body)))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("dial_usage_log_payload.request_body_")
                .hasMessageContaining("is projection-only")
                .hasMessageContaining("can only be selected");
    }

    @Test
    void countOverFilteredComputedFieldIsRefusedToo() {
        final StructuredQuery filtered = query("{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"filter\":{\"op\":\"eq\",\"args\":["
                + "{\"type\":\"field\",\"name\":\"" + STATUS + "\"},{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"x\"}]},"
                + "\"select\":[" + field("trace_id") + "]}");

        assertThatThrownBy(() -> shaped.buildCount(ctx, model(), filtered)).hasMessageContaining(STATUS);
    }

    @Test
    void reservedOutputNameIsRefusedBesideComputedField() {
        assertThatThrownBy(() -> shaped.build(ctx, model(), query(select(field(FULL),
                "{\"expr\":{\"type\":\"field\",\"name\":\"trace_id\"},\"as\":\"__adas_page_key\"}"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("__adas_page_key");
    }

    @Test
    void queryWithoutTheFieldsRendersExactlyAsBefore() {
        final StructuredQuery body = query(select(field("trace_id"), field("dial_usage_log_payload.request_body")));
        final QueryEntityModel model = model();
        final Map<String, QueryFieldBinding> catalogOnly = new LinkedHashMap<>(model.bindings());
        catalogOnly.remove(FULL);
        catalogOnly.remove(STATUS);
        final QueryEntityModel withoutComputed = new QueryEntityModel(model.entity(), model.table(), catalogOnly,
                List.of(EnrichmentJoin.keyed(DSL.table(DSL.name(ComputedFields.TABLE)), DSL.field(DSL.name("dial_usage_log", "event_id")),
                        DSL.field(DSL.name(ComputedFields.TABLE, "event_id")), DSL.field(DSL.name("dial_usage_log", "request_time")),
                        DSL.field(DSL.name(ComputedFields.TABLE, "request_time")), Set.of("dial_usage_log_payload.request_body"), null)),
                List.of(), null, true);

        assertThat(shaped.build(ctx, model, body).getSQL(ParamType.INLINED))
                .isEqualTo(shaped.build(ctx, withoutComputed, body).getSQL(ParamType.INLINED));
    }
}
