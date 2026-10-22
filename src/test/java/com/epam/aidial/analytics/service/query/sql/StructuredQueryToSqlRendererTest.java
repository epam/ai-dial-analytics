package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.FinalDecision;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.FrameBound;
import com.epam.aidial.analytics.service.query.model.FrameBoundKind;
import com.epam.aidial.analytics.service.query.model.FrameUnit;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.NullsOrder;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowFrame;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
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
 * DSL → external-dialect SQL rendering coverage for the scenarios in
 * {@code specs/query-translation-endpoint/spec.md}: exact rendering of every operator, function and
 * literal encoding, fail-closed rejection of SQL-inexpressible constructs, and — the round-trip
 * guarantee — that rendered SQL is accepted by the real {@link SqlQueryFrontEnd} and translates back
 * to the original query. Runs fully offline against the {@link DialUsageLogTestModel} fixture.
 */
class StructuredQueryToSqlRendererTest {

    private static final String ENTITY = DialUsageLogTestModel.ENTITY;
    private static final QueryEntityModel MODEL = DialUsageLogTestModel.model();
    private static final QueryEntityModel ENRICHED_MODEL = enrichedModel();
    private static final QueryEntityModel MAP_MODEL = mapModel();

    private final StructuredQueryToSqlRenderer renderer = new StructuredQueryToSqlRenderer(new ValueExprToObjectMapper());
    private final SqlQueryFrontEnd frontEnd = newFrontEnd();
    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final StructuredQueryBuilder builder = newBuilder();

    private static SqlQueryFrontEnd newFrontEnd() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any()))
                .thenReturn(List.of(ENTITY, ENRICHED_MODEL.entity(), MAP_MODEL.entity()));
        when(catalog.findModel(eq(ENTITY), any())).thenReturn(Optional.of(MODEL));
        when(catalog.findModel(eq(ENRICHED_MODEL.entity()), any())).thenReturn(Optional.of(ENRICHED_MODEL));
        when(catalog.findModel(eq(MAP_MODEL.entity()), any())).thenReturn(Optional.of(MAP_MODEL));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    /**
     * An entity carrying a {@code map} column, which {@link DialUsageLogTestModel} has none of — so without
     * it the three map accessors have no DSL→SQL coverage at all and their round trip rests entirely on the
     * Docker-gated integration test, which exercises the opposite direction (SQL → DSL).
     */
    private static QueryEntityModel mapModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("metric_name", new QueryFieldBinding(
                "metric_name", DSL.field(DSL.name("metrics", "metric_name"), JooqTypes.of(QueryFieldType.STRING)),
                QueryFieldType.STRING));
        bindings.put("point_value", new QueryFieldBinding(
                "point_value", DSL.field(DSL.name("metrics", "point_value"), JooqTypes.of(QueryFieldType.DECIMAL)),
                QueryFieldType.DECIMAL));
        bindings.put("point_attributes", new QueryFieldBinding(
                "point_attributes",
                DSL.field(DSL.name("metrics", "point_attributes"), JooqTypes.of(QueryFieldType.MAP)),
                QueryFieldType.MAP));
        return new QueryEntityModel(
                "metrics", DSL.table(DSL.name("metrics")), bindings, List.of(),
                List.of(
                        new QuerySchemaFieldDto("metric_name", QueryFieldType.STRING, null, null, "metric_name",
                                null, null, null, false, false),
                        new QuerySchemaFieldDto("point_value", QueryFieldType.DECIMAL, null, null, "point_value",
                                null, null, null, false, false),
                        new QuerySchemaFieldDto("point_attributes", QueryFieldType.MAP, null, null, "point_attributes",
                                null, null, null, false, false)));
    }

    private static StructuredQueryBuilder newBuilder() {
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
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
                List.of(
                        new QuerySchemaFieldDto("user_id", QueryFieldType.STRING, null, null, "user_id", null, null, null, false, false),
                        new QuerySchemaFieldDto("amount", QueryFieldType.LONG, null, null, "amount", null, null, null, false, false),
                        new QuerySchemaFieldDto("users.region", QueryFieldType.STRING, null, null, "region", null, null, null, false, false)));
    }

    private String render(StructuredQuery query) {
        return renderer.render(query, MODEL);
    }

    private static StructuredQuery rowQuery(FilterNode filter) {
        return new StructuredQuery(
                ENTITY, filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)), null, null, null, null);
    }

    private static ComparisonNode comparison(ComparisonOp op, Expr left, Expr right) {
        return new ComparisonNode(op, List.of(left, right));
    }

    private static ValueExpr string(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    // ------------------------------------------------------------- arithmetic and accessors

    @Test
    void arithmeticRendersAsInfixOperatorsAndRoundTrips() {
        // The audience for rendered SQL is external BI clients, for which the operator form is portable.
        // Safe because the SQL front end lowers each operator back onto the same catalog function.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("div", false, List.of(
                                new FieldExpr("total_tokens"), new FieldExpr("prompt_tokens"))),
                        "ratio")),
                null, null, null, null);

        assertThat(render(query)).contains("/").doesNotContain("div(");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void modulusRendersAsPercentNeverAsMod() {
        // One canonical rendering per operation: the subset reads MOD too, but emitting both spellings
        // would make the round-trip depend on which the client sent.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("modulo", false, List.of(
                                new FieldExpr("total_tokens"), new ValueExpr(ValueType.INTEGER, "3"))),
                        "r")),
                null, null, null, null);

        assertThat(render(query)).contains("%").doesNotContainIgnoringCase("mod(");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    // ------------------------------------------------------------- the clock and the offsets

    /**
     * {@code count()} used to be the catalog's only nullary function and SQL spells it {@code count(*)}, so
     * a second nullary is worth its own assertion: {@code now()} renders as itself, not as
     * {@code CURRENT_TIMESTAMP} — one operation gets one rendering, or the round trip stops being
     * single-valued.
     */
    @Test
    void theClockRendersAsNowAndRoundTrips() {
        final StructuredQuery query = rowQuery(comparison(
                ComparisonOp.GE, new FieldExpr("request_time"), new FnExpr("now", false, List.of())));

        assertThat(render(query)).contains("now()").doesNotContainIgnoringCase("current_timestamp");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void offsetsRenderAsInfixIntervalAndRoundTrip() {
        final StructuredQuery query = rowQuery(comparison(
                ComparisonOp.GE,
                new FieldExpr("request_time"),
                new FnExpr("date_sub", false, List.of(
                        string("hour"),
                        new ValueExpr(ValueType.INTEGER, "2"),
                        new FnExpr("now", false, List.of())))));

        assertThat(render(query))
                .contains("(now() - INTERVAL 2 HOUR)")
                .doesNotContain("date_sub(");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void forwardOffsetOverColumnRendersAsPlusIntervalAndRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("date_add", false, List.of(
                                string("day"),
                                new ValueExpr(ValueType.INTEGER, "1"),
                                new FieldExpr("request_time"))),
                        "due")),
                null, null, null, null);

        assertThat(render(query)).contains("+ INTERVAL 1 DAY").doesNotContain("date_add(");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void nestedOffsetAndTruncationRoundTripUnchanged() {
        // "since the start of yesterday" — the composition the catalog offers instead of a yesterday()
        // function. Two levels of nesting, so the parenthesising has to hold.
        final StructuredQuery query = rowQuery(comparison(
                ComparisonOp.GE,
                new FieldExpr("request_time"),
                new FnExpr("date_sub", false, List.of(
                        string("day"),
                        new ValueExpr(ValueType.INTEGER, "1"),
                        new FnExpr("date_trunc", false, List.of(
                                string("day"), new FnExpr("now", false, List.of())))))));

        assertThat(render(query)).contains("(date_trunc('day', now()) - INTERVAL 1 DAY)");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    /**
     * The renderer validates the unit and amount rather than trusting the document, because a document can
     * reach it unvalidated — a stored saved-query body, a translate-only call — and it must not be able to
     * emit a statement its own front end would refuse.
     */
    @Test
    void offsetRenderingRefusesUnitOrAmountTheSubsetCannotCarry() {
        assertThatThrownBy(() -> render(rowQuery(comparison(
                ComparisonOp.GE,
                new FieldExpr("request_time"),
                new FnExpr("date_sub", false, List.of(
                        string("week"), new ValueExpr(ValueType.INTEGER, "1"),
                        new FnExpr("now", false, List.of())))))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("unit must be one of");
        assertThatThrownBy(() -> render(rowQuery(comparison(
                ComparisonOp.GE,
                new FieldExpr("request_time"),
                new FnExpr("date_sub", false, List.of(
                        string("hour"), new ValueExpr(ValueType.INTEGER, "-2"),
                        new FnExpr("now", false, List.of())))))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("positive integer");
        assertThatThrownBy(() -> render(rowQuery(comparison(
                ComparisonOp.GE,
                new FieldExpr("request_time"),
                new FnExpr("date_sub", false, List.of(
                        string("hour"), new ValueExpr(ValueType.INTEGER, "2")))))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("three arguments");
    }

    @Test
    void nestedArithmeticIsParenthesisedSoItRoundTripsUnchanged() {
        // multiply(plus(a, b), c) must not render as a + b * c. Parenthesising unconditionally is what
        // makes this hold without a precedence table.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("multiply", false, List.of(
                                new FnExpr("plus", false, List.of(
                                        new FieldExpr("prompt_tokens"), new FieldExpr("completion_tokens"))),
                                new ValueExpr(ValueType.INTEGER, "2"))),
                        "x")),
                null, null, null, null);

        assertThat(render(query)).contains("(").contains("+").contains("*");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void arrayAccessorOverAnArrayFieldRendersAndRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("array_length", false, List.of(new FieldExpr("execution_path"))), "n")),
                null, null, null, null);

        assertThat(render(query)).contains("array_length(\"execution_path\")");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void arrayExpansionRendersAndRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("array_join", false, List.of(new FieldExpr("execution_path"))), "step")),
                null, null, null, null);

        assertThat(render(query)).contains("array_join(\"execution_path\")");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void arrayExpansionOverComputedArrayRoundTrips() {
        // split_string's array result is projection-only everywhere except an array-operand position, and
        // array_join is one — the same carve-out array_length(split_string(...)) relies on.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("array_join", false, List.of(
                                new FnExpr("split_string", false, List.of(
                                        new FieldExpr("deployment"), string(","))))),
                        "part")),
                null, null, null, null);

        assertThat(render(query)).contains("array_join(split_string(");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void arrayExpansionGroupedByItsAliasRoundTrips() {
        // The motivating shape: the expansion is the group key, reached through the entry's alias because
        // the array column itself stays ungroupable.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(
                                new FnExpr("array_join", false, List.of(new FieldExpr("execution_path"))), "step"),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("step"), null, null, null);

        assertThat(render(query)).contains("array_join(\"execution_path\")").contains("GROUP BY");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void arrayExpansionInFilterRoundTrips() {
        // No positional guard on either surface: the engine accepts an expansion in WHERE, so refusing it
        // here would make the renderer narrower than both the DSL and the SQL subset.
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                comparison(ComparisonOp.EQ,
                        new FnExpr("array_join", false, List.of(new FieldExpr("execution_path"))),
                        string("core")),
                QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)),
                null, null, null, null);

        assertThat(render(query)).contains("array_join(\"execution_path\")");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    // ------------------------------------------------ text composition and array text

    @Test
    void concatRendersAsTheCatalogNameAndRoundTrips() {
        // Never the || operator: the renderer emits the catalog spelling so one operation has one
        // rendering, and the SQL front end lowers || onto the same function on the way back.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FnExpr("concat", false, List.of(
                        new FieldExpr("deployment"), string(" -> "), new FieldExpr("event_kind"))), "edge")),
                null, null, null, null);

        assertThat(render(query))
                .contains("concat(\"deployment\", ' -> ', \"event_kind\")")
                .doesNotContain("||");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void toStringRendersAsTheCatalogNameNeverCast() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("to_string", false, List.of(new FieldExpr("total_tokens"))), "t")),
                null, null, null, null);

        assertThat(render(query))
                .contains("to_string(\"total_tokens\")")
                .doesNotContain("CAST");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void renderedConcatIsGroupableAndRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FnExpr("concat", false, List.of(
                                new FieldExpr("deployment"), string(" -> "), new FieldExpr("event_kind"))), "edge"),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("edge"), null, null, null);

        assertThat(render(query)).contains("concat(").contains("GROUP BY");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void arrayToStringRendersAndRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FnExpr("array_to_string", false, List.of(
                        new FieldExpr("execution_path"), string(">"))), "path")),
                null, null, null, null);

        assertThat(render(query)).contains("array_to_string(\"execution_path\", '>')");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void cappedRenderedCellRoundTrips() {
        // The motivating cell, three calls deep: the group's distinct values, capped, rendered as text.
        // Every nesting level sits in an array-operand position, which is what makes it expressible.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(new FnExpr("array_to_string", false, List.of(
                                new FnExpr("array_slice", false, List.of(
                                        new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment"))),
                                        new ValueExpr(ValueType.INTEGER, "1"),
                                        new ValueExpr(ValueType.INTEGER, "6"))),
                                string(", "))), "models")),
                List.of("chat_id"), null, null, null);

        assertThat(render(query)).contains("array_to_string(array_slice(group_uniq_array(\"deployment\"), 1, 6), ', ')");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void sliceAliasAsGroupKeyFailsClosedRatherThanRendering() {
        // array_slice returns an array, so it joins the computed-array set: grouping by its alias would
        // render SQL the front end then refuses, which is the asymmetry this renderer exists to prevent.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FnExpr("array_slice", false, List.of(
                                new FieldExpr("execution_path"),
                                new ValueExpr(ValueType.INTEGER, "1"),
                                new ValueExpr(ValueType.INTEGER, "3"))), "head"),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("head"), null, null, null);

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("array_slice")
                .hasMessageContaining("can only be");
    }

    @Test
    void arrayPredicateValueListRendersAsArrayConstructor() {
        // array_has_any carries its value list as the DSL's ArrayExpr, which is inexpressible in SQL
        // everywhere else — here it becomes ARRAY[...] and reads back into the same shape.
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                comparison(ComparisonOp.EQ,
                        new FnExpr("array_has_any", false, List.of(
                                new FieldExpr("execution_path"),
                                new ArrayExpr(List.of(string("a"), string("b"))))),
                        new ValueExpr(ValueType.BOOLEAN, "true")),
                QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)),
                null, null, null, null);

        assertThat(render(query)).contains("ARRAY[");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void arrayFieldOutsideAnAccessorStillFailsClosed() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(new FieldExpr("execution_path"), null)),
                List.of("execution_path"), null, null, null);

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("can only be projected");
    }

    @Test
    void computedArrayNestedInAnAccessorRenders() {
        // The positional carve-out: an array-returning call is a legal operand in argument 0 only.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("array_length", false, List.of(
                                new FnExpr("split_string", false, List.of(
                                        new FieldExpr("deployment"), string(","))))),
                        "n")),
                null, null, null, null);

        assertThat(render(query)).contains("array_length(split_string(");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void computedArrayOutsideAnAccessorIsRefused() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("lower", false, List.of(
                                new FnExpr("split_string", false, List.of(
                                        new FieldExpr("deployment"), string(","))))),
                        "x")),
                null, null, null, null);

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("returns an array");
    }

    @Test
    void mapAccessorsRenderAndRoundTrip() {
        // The map carve-out's own round trip: a map field is projection-only, so this is the one shape
        // where the renderer must emit a call over one and the front end must read it back into the same
        // query. The dotted key travels through both directions as a single literal, never split.
        final StructuredQuery query = new StructuredQuery(
                MAP_MODEL.entity(),
                comparison(ComparisonOp.EQ,
                        new FnExpr("map_contains", false, List.of(
                                new FieldExpr("point_attributes"), string("user.email"))),
                        new ValueExpr(ValueType.BOOLEAN, "true")),
                QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FnExpr("map_value", false, List.of(
                                new FieldExpr("point_attributes"), string("user.email"))), "who"),
                        new OutputColumn(new FnExpr("sum", false, List.of(new FieldExpr("point_value"))), "cost")),
                List.of("who"), null, null, null);

        assertThat(renderWith(MAP_MODEL, query))
                .contains("map_value(\"point_attributes\", 'user.email')")
                .contains("map_contains(\"point_attributes\", 'user.email')");
        assertThat(roundTripWith(MAP_MODEL, query)).isEqualTo(query);
    }

    @Test
    void mapKeysNestedInAnArrayAccessorRendersAndRoundTrips() {
        // The composition the positional carve-out exists for, across both surfaces: an array-valued call
        // as an array accessor's argument 0, over a map field that is projection-only everywhere else.
        final StructuredQuery query = new StructuredQuery(
                MAP_MODEL.entity(), null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("array_length", false, List.of(
                                new FnExpr("map_keys", false, List.of(new FieldExpr("point_attributes"))))),
                        "n")),
                null, null, null, null);

        assertThat(renderWith(MAP_MODEL, query)).contains("array_length(map_keys(\"point_attributes\"))");
        assertThat(roundTripWith(MAP_MODEL, query)).isEqualTo(query);
    }

    @Test
    void groupingOrSortingByTheAliasOfAnArrayCallIsRefused() {
        // Caught live against a running instance, not by review: /v1/queries/translate emitted
        // GROUP BY "parts" for this, and /v1/queries/translate-sql then refused its own output —
        // the round-trip asymmetry this renderer exists to prevent. The column-type guard could not see
        // it, since a computed array has no declared type (and dial_usage_log's array column is not the
        // one being grouped by).
        for (final StructuredQuery query : List.of(
                aggregateBySplitAlias(List.of("parts"), null),
                aggregateBySplitAlias(null, List.of(new SortItem("parts", SortDir.ASC, null))))) {
            assertThatThrownBy(() -> render(query))
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("split_string")
                    .hasMessageContaining("returns an array");
        }
    }

    @Test
    void distinctOverAnArrayCallStillRenders() {
        // The deliberate non-symmetry: the SQL front end accepts SELECT DISTINCT over an array-valued
        // call, so the renderer must keep emitting it. Guarding distinct alongside the keys above would
        // break a shape both surfaces agree on.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, true,
                List.of(new OutputColumn(
                        new FnExpr("split_string", false, List.of(new FieldExpr("deployment"), string(","))),
                        "parts")),
                null, null, null, null);

        assertThat(render(query)).contains("SELECT DISTINCT split_string(");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    private static StructuredQuery aggregateBySplitAlias(List<String> groupBy, List<SortItem> sort) {
        return new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(
                                new FnExpr("split_string", false,
                                        List.of(new FieldExpr("deployment"), string(","))),
                                "parts"),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                groupBy, null, sort, null);
    }

    @Test
    void mapFieldOutsideAnAccessorStillFailsClosed() {
        final StructuredQuery query = new StructuredQuery(
                MAP_MODEL.entity(), null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(new FieldExpr("point_attributes"), null)),
                List.of("point_attributes"), null, null, null);

        assertThatThrownBy(() -> renderWith(MAP_MODEL, query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("point_attributes");
    }

    // ------------------------------------------------------------- rendering

    @Test
    void rendersRowModeQueryExactly() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                comparison(ComparisonOp.EQ, new FieldExpr("event_kind"), string("chat")),
                QueryMode.ROW,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("project_id"), null),
                        new OutputColumn(new FieldExpr("total_tokens"), "tokens")),
                null,
                null,
                List.of(new SortItem("total_tokens", SortDir.DESC, NullsOrder.LAST)),
                new OffsetPage(50L, 25, false));

        assertThat(render(query)).isEqualTo(
                "SELECT \"project_id\", \"total_tokens\" AS \"tokens\" FROM \"dial_usage_log\" FINAL"
                        + " WHERE \"event_kind\" = 'chat'"
                        + " ORDER BY \"total_tokens\" DESC NULLS LAST LIMIT 25 OFFSET 50");
    }

    @Test
    void rendersEmptySelectAsStarWhenStarExpandsToTheBareProjection() {
        // No enrichments and every source column SQL-visible: SQL '*' expands to exactly the
        // execute path's bare projection, so the compact form is kept.
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("kind", new QueryFieldBinding(
                "kind", DSL.field(DSL.name("plain", "kind"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING));
        final QueryEntityModel plain = new QueryEntityModel(
                "plain", DSL.table(DSL.name("plain")), bindings, List.of(),
                List.of(new QuerySchemaFieldDto("kind", QueryFieldType.STRING, null, null, "kind", null, null, null, false, false)));
        final StructuredQuery query = new StructuredQuery(
                "plain", null, QueryMode.ROW, false, null, null, null, null, null);
        assertThat(renderer.render(query, plain)).isEqualTo("SELECT * FROM \"plain\"");
    }

    @Test
    void rendersEmptySelectOnEnrichedEntityAsStar() {
        // Both wildcards now mean the same thing — the source's non-heavy columns — so '*' is faithful
        // even for an enriched entity: the star no longer fans out into enrichment columns.
        final StructuredQuery query = new StructuredQuery(
                ENRICHED_MODEL.entity(), null, QueryMode.ROW, false, null, null, null, null, null);
        assertThat(renderer.render(query, ENRICHED_MODEL)).isEqualTo("SELECT * FROM \"events\" FINAL");
    }

    @Test
    void rendersEmptySelectOnEntityWithObjectSourceColumns() {
        // dial_usage_log's bare projection includes an ARRAY column (execution_path). Every declared
        // type is SQL-visible, so none of them blocks the bare projection: it renders as the compact
        // '*', and the star expansion translates back to a projection carrying them — but not any
        // payload column, which the enrichment rule leaves out whether or not it is heavy. The
        // claim/header pair is the clean demonstration of that: neither is heavy, both are absent.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false, null, null, null, null, null);

        assertThat(render(query)).isEqualTo("SELECT * FROM \"dial_usage_log\" FINAL");
        assertThat(roundTrip(query).select())
                .extracting(OutputColumn::expr)
                .contains(new FieldExpr("execution_path"), new FieldExpr("request_body_bytes"))
                .doesNotContain(
                        new FieldExpr("dial_usage_log_payload.jwt_claims"),
                        new FieldExpr("dial_usage_log_payload.request_tags"),
                        new FieldExpr("dial_usage_log_payload.request_body"),
                        new FieldExpr("dial_usage_log_payload.response_body"));
    }

    @Test
    void rendersExplicitObjectSelectAndRoundTrips() {
        // Object fields are projection-only but fully renderable: the rendered identifier must be
        // accepted by the SQL front end and translate back to the identical projection.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(
                        new OutputColumn(new FieldExpr("event_kind"), null),
                        new OutputColumn(new FieldExpr("dial_usage_log_payload.jwt_claims"), null)),
                null, null, null, null);

        final String sql = render(query);
        assertThat(sql).isEqualTo("SELECT \"event_kind\", \"dial_usage_log_payload.jwt_claims\" FROM \"dial_usage_log\" FINAL");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void rendersJsonExtractOverObjectFieldAndRoundTrips() {
        // The one position where an object field is renderable: the JSON argument of json_extract_*.
        // It must survive the round trip, or the DSL and the SQL front end disagree about the subset.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("json_extract_string", false,
                                List.of(new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "sub"))),
                        "sub")),
                null, null, null, null);

        assertThat(render(query)).isEqualTo(
                "SELECT json_extract_string(\"dial_usage_log_payload.jwt_claims\", 'sub') AS \"sub\" FROM \"dial_usage_log\" FINAL");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    /**
     * The carve-out matches the function name case-insensitively, exactly as both surfaces match every
     * other function name (the execute path lowercases before consulting the closed catalog, and this
     * renderer lowercases before emitting the call). Were it matched on the raw spelling, an upper-case
     * {@code JSON_EXTRACT_STRING} over an {@code object} field would execute but fail to translate.
     */
    @Test
    void rendersMixedCaseJsonExtractOverObjectFieldAndRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("JSON_Extract_String", false,
                                List.of(new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "sub"))),
                        "sub")),
                null, null, null, null);

        assertThat(render(query)).isEqualTo(
                "SELECT json_extract_string(\"dial_usage_log_payload.jwt_claims\", 'sub') AS \"sub\" FROM \"dial_usage_log\" FINAL");
        // The round trip normalizes the spelling to the catalog's, which is the same query to execute.
        assertThat(roundTrip(query)).isEqualTo(new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("json_extract_string", false,
                                List.of(new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "sub"))),
                        "sub")),
                null, null, null, null));
    }

    @Test
    void rendersFilterOnExtractedObjectPathAndRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                comparison(
                        ComparisonOp.EQ,
                        new FnExpr("json_extract_string", false,
                                List.of(new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "sub"))),
                        new ValueExpr(ValueType.STRING, "user-1")),
                QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)),
                null, null, null, null);

        assertThat(render(query)).contains("json_extract_string(\"dial_usage_log_payload.jwt_claims\", 'sub') = 'user-1'");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void rendersNestedPathExtractOverObjectField() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("json_extract_long", false,
                                List.of(
                                        new FieldExpr("dial_usage_log_payload.jwt_claims"),
                                        new ValueExpr(ValueType.STRING, "usage"),
                                        new ValueExpr(ValueType.STRING, "total_tokens"))),
                        "tokens")),
                null, null, null, null);

        assertThat(render(query)).contains("json_extract_long(\"dial_usage_log_payload.jwt_claims\", 'usage', 'total_tokens')");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void rejectsBareObjectFieldEvenWhenTheSameFieldIsAlsoExtracted() {
        // The carve-out is positional, not per-field: extracting from dial_usage_log_payload.jwt_claims does not license
        // using the bare field as a sort key in the same document.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("json_extract_string", false,
                                List.of(new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "sub"))),
                        "sub")),
                null, null, List.of(new SortItem("dial_usage_log_payload.jwt_claims", SortDir.ASC, null)), null);

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void objectFieldDocumentComparisonsRoundTrip() {
        // The round trip is the whole reason the renderer emits a bare identifier: every operator the
        // carve-out admits has to come back as the identical DSL document, or /v1/queries/translate would
        // hand out SQL that /v1/queries/translate-sql refuses.
        final FieldExpr claims = new FieldExpr("dial_usage_log_payload.jwt_claims");
        final List<ComparisonNode> filters = List.of(
                comparison(ComparisonOp.CO, claims, string("admin")),
                comparison(ComparisonOp.ICO, claims, string("admin")),
                comparison(ComparisonOp.EQ, claims, string("{\"sub\":\"u1\"}")),
                comparison(ComparisonOp.NE, claims, string("{\"sub\":\"u1\"}")));
        for (final ComparisonNode filter : filters) {
            final StructuredQuery query = rowQuery(filter);
            assertThat(roundTrip(query)).as("round trip of '%s'", filter.op().code()).isEqualTo(query);
        }
    }

    @Test
    void negatedContainsOnObjectFieldRoundTripsThroughNot() {
        // `nc`/`inc` render as NOT LIKE / NOT ILIKE, which Calcite normalizes to NOT(LIKE) before this
        // translator sees it — so they come back as a NOT over the positive operator rather than as
        // themselves. That normalization is Calcite's and predates this change (it is why the existing
        // contains-family test asserts rendering only), and it is semantics-preserving; asserted here so
        // the object-field path is known to behave exactly as the character-field path does.
        for (final ComparisonOp negated : List.of(ComparisonOp.NC, ComparisonOp.INC)) {
            final ComparisonOp positive = negated == ComparisonOp.NC ? ComparisonOp.CO : ComparisonOp.ICO;
            final StructuredQuery query = rowQuery(
                    comparison(negated, new FieldExpr("dial_usage_log_payload.jwt_claims"), string("admin")));
            assertThat(roundTrip(query).filter())
                    .as("round trip of '%s'", negated.code())
                    .isEqualTo(new LogicalNode(LogicalOp.NOT, List.of(
                            comparison(positive, new FieldExpr("dial_usage_log_payload.jwt_claims"), string("admin")))));
        }
    }

    @Test
    void negatedContainsOnCharacterFieldNormalizesIdentically() {
        // The control for the test above: the same normalization on an ordinary character field, proving
        // it is Calcite's behavior and not something the object carve-out introduced.
        final StructuredQuery query = rowQuery(
                comparison(ComparisonOp.NC, new FieldExpr("deployment"), string("gpt")));
        assertThat(roundTrip(query).filter())
                .isEqualTo(new LogicalNode(LogicalOp.NOT, List.of(
                        comparison(ComparisonOp.CO, new FieldExpr("deployment"), string("gpt")))));
    }

    @Test
    void objectFieldMembershipRoundTrips() {
        final StructuredQuery query = rowQuery(new ComparisonNode(ComparisonOp.IN, List.of(
                new FieldExpr("dial_usage_log_payload.jwt_claims"),
                new ArrayExpr(List.of(string("{\"a\":1}"), string("{\"b\":2}"))))));
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void objectFieldContainsWithMetacharacterIsRejectedForThePattern() {
        // The contains metacharacter rule is the character field's, and it applies here unchanged — the
        // rejection must name the pattern, not the field's type.
        final StructuredQuery query = rowQuery(
                comparison(ComparisonOp.CO, new FieldExpr("dial_usage_log_payload.jwt_claims"), string("50%")));
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("co")
                .hasMessageNotContaining("can only be projected");
    }

    @Test
    void rendersObjectFieldContainsAsBareIdentifier() {
        // The external dialect carries the plain identifier — the toJSONString the execute path applies
        // is an execution detail. Rendering the wrapper here would emit SQL the front end refuses.
        final String sql = render(rowQuery(
                comparison(ComparisonOp.ICO, new FieldExpr("dial_usage_log_payload.jwt_claims"), string("admin"))));
        assertThat(sql).contains("\"dial_usage_log_payload.jwt_claims\" ILIKE '%admin%'").doesNotContain("toJSONString");
    }

    @Test
    void rendersObjectFieldEqualityAsBareIdentifier() {
        final String sql = render(rowQuery(
                comparison(ComparisonOp.EQ, new FieldExpr("dial_usage_log_payload.jwt_claims"), string("{\"sub\":\"u1\"}"))));
        assertThat(sql).contains("\"dial_usage_log_payload.jwt_claims\" = ").doesNotContain("toJSONString").doesNotContain("CAST");
    }

    @Test
    void rendersObjectFieldNotContains() {
        assertThat(render(rowQuery(
                comparison(ComparisonOp.NC, new FieldExpr("dial_usage_log_payload.jwt_claims"), string("admin")))))
                .contains("\"dial_usage_log_payload.jwt_claims\" NOT LIKE '%admin%'");
    }

    @Test
    void rejectsObjectFieldComparedToScalarField() {
        // The carve-out's shape is `<object field> <operator> <literal>`. Comparing an object field to
        // an ordinary column would render `"dial_usage_log_payload.request_tags" = "event_kind"`, which the front end refuses
        // on translate-back. Unreachable through the endpoint (the builder pass rejects it first), but
        // this guard's stated job is defending a render that skipped that pass.
        final StructuredQuery query = rowQuery(comparison(
                ComparisonOp.EQ, new FieldExpr("dial_usage_log_payload.jwt_claims"), new FieldExpr("event_kind")));
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void rejectsObjectFieldDocumentComparisonInHaving() {
        // The carve-out is a WHERE-clause rule. In HAVING the bare field is neither grouped nor
        // aggregated, so the rendered statement would fail the front end's validator ("is not being
        // grouped") and fail execution with NOT_AN_AGGREGATE — SQL that neither executes nor
        // translates back, which is the asymmetry this guard exists to prevent.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FieldExpr("deployment"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("deployment"),
                comparison(ComparisonOp.ICO, new FieldExpr("dial_usage_log_payload.jwt_claims"), string("admin")),
                null, null);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected")
                .hasMessageContaining("'having'");
    }

    @Test
    void rejectsObjectFieldOrderingComparison() {
        final StructuredQuery query = rowQuery(
                comparison(ComparisonOp.GT, new FieldExpr("dial_usage_log_payload.jwt_claims"), string("{\"a\":1}")));
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void rejectsObjectFieldAsRightComparisonOperand() {
        // The carve-out is the shape `<object field> <operator> <literal>` and nothing else. An object
        // field on the *right* is refused by the SQL front end outright, so rendering it would emit SQL
        // that cannot be translated back — the renderer must fail closed on its own, without a build pass.
        final StructuredQuery query = rowQuery(
                comparison(ComparisonOp.EQ, new FieldExpr("event_kind"), new FieldExpr("dial_usage_log_payload.jwt_claims")));
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void rejectsObjectFieldComparedToAnotherObjectField() {
        // Both sides restricted: the left operand's type must not admit the right one into the carve-out.
        final StructuredQuery query = rowQuery(
                comparison(ComparisonOp.EQ, new FieldExpr("dial_usage_log_payload.jwt_claims"), new FieldExpr("dial_usage_log_payload.jwt_claims")));
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void rejectsObjectFieldInFilter() {
        // An eq-null filter is a null test, not a document comparison: a JSON column is never null, so
        // it would render as a constant IS NULL that the SQL subset refuses on translate-back. Both
        // guards fail closed on it independently — the DSL-surface operand check during build, and the
        // renderer's own guard, which is what keeps the round-trip guarantee for an unbuilt render.
        final StructuredQuery query = rowQuery(
                comparison(ComparisonOp.EQ, new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.NULL, null)));
        assertThatThrownBy(() -> builder.build(ctx, MODEL, query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void rejectsObjectFieldInHaving() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("event_kind"),
                comparison(ComparisonOp.EQ, new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.NULL, null)),
                null, null);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("'having'");
    }

    @Test
    void rejectsObjectFieldSortKey() {
        // Directly by field name…
        final StructuredQuery byName = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)),
                null, null, List.of(new SortItem("dial_usage_log_payload.jwt_claims", SortDir.ASC, null)), null);
        builder.build(ctx, MODEL, byName);
        assertThatThrownBy(() -> render(byName))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("'sort'");
        // …and via the alias of a projected object field (translate-back would sort the object itself).
        final StructuredQuery byAlias = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("dial_usage_log_payload.jwt_claims"), "c")),
                null, null, List.of(new SortItem("c", SortDir.ASC, null)), null);
        builder.build(ctx, MODEL, byAlias);
        assertThatThrownBy(() -> render(byAlias))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("'sort'");
    }

    @Test
    void rejectsObjectFieldGroupKey() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FieldExpr("dial_usage_log_payload.jwt_claims"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("dial_usage_log_payload.jwt_claims"), null, null, null);
        builder.build(ctx, MODEL, query);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("'group_by'");
    }

    @Test
    void rejectsObjectFieldFunctionArgument() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(new FnExpr("count", false, List.of(new FieldExpr("dial_usage_log_payload.jwt_claims"))), "n")),
                null, null, null, null);
        builder.build(ctx, MODEL, query);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("a function call over");
    }

    @Test
    void rejectsDistinctOverObjectProjection() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, true,
                List.of(new OutputColumn(new FieldExpr("dial_usage_log_payload.jwt_claims"), null)),
                null, null, null, null);
        builder.build(ctx, MODEL, query);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.jwt_claims'")
                .hasMessageContaining("'distinct'");
    }

    @Test
    void rendersExplicitArraySelectAndRoundTrips() {
        // Array fields are projection-only but fully renderable: the rendered identifier must be
        // accepted by the SQL front end and translate back to the identical projection.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(
                        new OutputColumn(new FieldExpr("event_kind"), null),
                        new OutputColumn(new FieldExpr("execution_path"), null)),
                null, null, null, null);

        final String sql = render(query);
        assertThat(sql).isEqualTo("SELECT \"event_kind\", \"execution_path\" FROM \"dial_usage_log\" FINAL");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void rendersEmptySelectWithArraySourceColumnAsStar() {
        // An array source column no longer blocks the bare projection, and needs no special rendering:
        // the whole projection renders as '*', which expands back to the source's non-heavy columns
        // (the entity has an enrichment, whose column the star leaves out).
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("id", new QueryFieldBinding(
                "id", DSL.field(DSL.name("trails", "id"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING));
        bindings.put("path", new QueryFieldBinding(
                "path", DSL.field(DSL.name("trails", "path"), JooqTypes.of(QueryFieldType.ARRAY)),
                QueryFieldType.ARRAY, QueryFieldType.STRING));
        bindings.put("meta.note", new QueryFieldBinding(
                "meta.note", DSL.field(DSL.name("meta", "note"), JooqTypes.of(QueryFieldType.STRING)),
                QueryFieldType.STRING));
        final EnrichmentJoin join = new EnrichmentJoin(
                DSL.table(DSL.name("meta")),
                DSL.field(DSL.name("trails", "id")).eq(DSL.field(DSL.name("meta", "id"))), null, null, null, null,
                Set.of("meta.note"), null);
        final QueryEntityModel trails = new QueryEntityModel(
                "trails", DSL.table(DSL.name("trails")), bindings, List.of(join),
                List.of(
                        new QuerySchemaFieldDto("id", QueryFieldType.STRING, null, null, "id", null, null, null, false, false),
                        new QuerySchemaFieldDto("path", QueryFieldType.ARRAY, QueryFieldType.STRING, null, "path", null, null, null, false, false),
                        new QuerySchemaFieldDto("meta.note", QueryFieldType.STRING, null, null, "note", null, null, null, false, false)));

        final StructuredQuery query = new StructuredQuery(
                "trails", null, QueryMode.ROW, false, null, null, null, null, null);
        assertThat(renderer.render(query, trails)).isEqualTo("SELECT * FROM \"trails\" FINAL");
    }

    @Test
    void rendersEmptySelectWithObjectSourceColumnAsStar() {
        // A bare projection on an enriched entity: '*' covers it, because a star expands to the
        // source's non-heavy columns only — the object source column needs no special rendering.
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("id", new QueryFieldBinding(
                "id", DSL.field(DSL.name("audit", "id"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING));
        bindings.put("claims", new QueryFieldBinding(
                "claims", DSL.field(DSL.name("audit", "claims"), JooqTypes.of(QueryFieldType.OBJECT)),
                QueryFieldType.OBJECT));
        bindings.put("meta.note", new QueryFieldBinding(
                "meta.note", DSL.field(DSL.name("meta", "note"), JooqTypes.of(QueryFieldType.STRING)),
                QueryFieldType.STRING));
        final EnrichmentJoin join = new EnrichmentJoin(
                DSL.table(DSL.name("meta")),
                DSL.field(DSL.name("audit", "id")).eq(DSL.field(DSL.name("meta", "id"))), null, null, null, null,
                Set.of("meta.note"), null);
        final QueryEntityModel audit = new QueryEntityModel(
                "audit", DSL.table(DSL.name("audit")), bindings, List.of(join),
                List.of(
                        new QuerySchemaFieldDto("id", QueryFieldType.STRING, null, null, "id", null, null, null, false, false),
                        new QuerySchemaFieldDto("claims", QueryFieldType.OBJECT, null, null, "claims", null, null, null, false, false),
                        new QuerySchemaFieldDto("meta.note", QueryFieldType.STRING, null, null, "note", null, null, null, false, false)));

        final StructuredQuery query = new StructuredQuery(
                "audit", null, QueryMode.ROW, false, null, null, null, null, null);
        assertThat(renderer.render(query, audit)).isEqualTo("SELECT * FROM \"audit\" FINAL");
    }

    @Test
    void rendersDistinctRowQuery() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, true,
                List.of(new OutputColumn(new FieldExpr("deployment"), null)), null, null, null, null);
        assertThat(render(query)).isEqualTo("SELECT DISTINCT \"deployment\" FROM \"dial_usage_log\" FINAL");
    }

    @Test
    void rendersAggregateQueryExactly() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                comparison(ComparisonOp.GT, new FieldExpr("total_price"), new ValueExpr(ValueType.DECIMAL, "0.5")),
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("deployment"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("deployment"),
                comparison(ComparisonOp.GT, new FnExpr("count", false, List.of()), new ValueExpr(ValueType.LONG, "10")),
                List.of(new SortItem("n", SortDir.DESC, null)),
                new OffsetPage(0L, 50, false));

        assertThat(render(query)).isEqualTo(
                "SELECT \"deployment\", count(*) AS \"n\" FROM \"dial_usage_log\" FINAL"
                        + " WHERE \"total_price\" > 0.5"
                        + " GROUP BY \"deployment\""
                        + " HAVING count(*) > 10"
                        + " ORDER BY \"n\" DESC LIMIT 50");
    }

    @Test
    void rendersLogicalTreeParenthesized() {
        final FilterNode filter = new LogicalNode(LogicalOp.OR, List.of(
                new LogicalNode(LogicalOp.AND, List.of(
                        comparison(ComparisonOp.GE, new FieldExpr("total_tokens"), new ValueExpr(ValueType.LONG, "100")),
                        comparison(ComparisonOp.LE, new FieldExpr("total_tokens"), new ValueExpr(ValueType.LONG, "200")))),
                new LogicalNode(LogicalOp.NOT, List.of(
                        comparison(ComparisonOp.NE, new FieldExpr("event_kind"), string("chat"))))));

        assertThat(render(rowQuery(filter))).contains(
                "WHERE ((\"total_tokens\" >= 100 AND \"total_tokens\" <= 200)"
                        + " OR (NOT \"event_kind\" <> 'chat'))");
    }

    @Test
    void rendersInList() {
        final FilterNode filter = comparison(
                ComparisonOp.IN, new FieldExpr("event_kind"), new ArrayExpr(List.of(string("chat"), string("embedding"))));
        assertThat(render(rowQuery(filter))).contains("WHERE \"event_kind\" IN ('chat', 'embedding')");
    }

    @Test
    void rendersContainsFamilyWithExactSemantics() {
        assertThat(render(rowQuery(comparison(ComparisonOp.CO, new FieldExpr("deployment"), string("gpt")))))
                .contains("\"deployment\" LIKE '%gpt%'");
        assertThat(render(rowQuery(comparison(ComparisonOp.NC, new FieldExpr("deployment"), string("gpt")))))
                .contains("\"deployment\" NOT LIKE '%gpt%'");
        assertThat(render(rowQuery(comparison(ComparisonOp.ICO, new FieldExpr("deployment"), string("gpt")))))
                .contains("\"deployment\" ILIKE '%gpt%'");
        assertThat(render(rowQuery(comparison(ComparisonOp.INC, new FieldExpr("deployment"), string("gpt")))))
                .contains("\"deployment\" NOT ILIKE '%gpt%'");
    }

    @Test
    void rendersNullComparisonsAsIsNull() {
        final ValueExpr nullValue = new ValueExpr(ValueType.NULL, null);
        assertThat(render(rowQuery(comparison(ComparisonOp.EQ, new FieldExpr("chat_id"), nullValue))))
                .contains("WHERE \"chat_id\" IS NULL");
        assertThat(render(rowQuery(comparison(ComparisonOp.NE, new FieldExpr("chat_id"), nullValue))))
                .contains("WHERE \"chat_id\" IS NOT NULL");
    }

    @Test
    void rendersLiteralEncodings() {
        final long epochMillis = Instant.parse("2026-07-01T12:30:45Z").toEpochMilli();
        final FilterNode filter = new LogicalNode(LogicalOp.AND, List.of(
                comparison(ComparisonOp.GE, new FieldExpr("request_time"), new ValueExpr(ValueType.TIMESTAMP, Long.toString(epochMillis))),
                comparison(ComparisonOp.EQ, new FieldExpr("success"), new ValueExpr(ValueType.BOOLEAN, "true")),
                comparison(ComparisonOp.EQ, new FieldExpr("event_id"), new ValueExpr(ValueType.UUID, "00000000-0000-4000-8000-000000000002")),
                comparison(ComparisonOp.EQ, new FieldExpr("deployment"), string("it's"))));

        final String sql = render(rowQuery(filter));

        assertThat(sql)
                .contains("\"request_time\" >= TIMESTAMP '2026-07-01 12:30:45'")
                .contains("\"success\" = TRUE")
                .contains("\"event_id\" = '00000000-0000-4000-8000-000000000002'")
                .contains("\"deployment\" = 'it''s'");
    }

    @Test
    void rendersTimestampWithMillisWhenPresent() {
        final FilterNode filter = comparison(
                ComparisonOp.GE, new FieldExpr("request_time"), new ValueExpr(ValueType.TIMESTAMP, "1751372445123"));
        assertThat(render(rowQuery(filter))).contains("TIMESTAMP '2025-07-01 12:20:45.123'");
    }

    @Test
    void rendersFunctions() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FnExpr("lower", false, List.of(new FieldExpr("deployment"))), "d"),
                        new OutputColumn(new FnExpr("count", true, List.of(new FieldExpr("project_id"))), "projects"),
                        new OutputColumn(new FnExpr("percentile_cont", false, List.of(
                                new ValueExpr(ValueType.DECIMAL, "0.95"), new FieldExpr("total_tokens"))), "p95"),
                        new OutputColumn(new FnExpr("date_bin", false, List.of(
                                new ValueExpr(ValueType.INTEGER, "5"), string("minute"), new FieldExpr("request_time"))), "bucket")),
                List.of("d", "bucket"),
                null,
                null,
                null);

        assertThat(render(query))
                .contains("lower(\"deployment\") AS \"d\"")
                .contains("count(DISTINCT \"project_id\") AS \"projects\"")
                .contains("percentile_cont(0.95) WITHIN GROUP (ORDER BY \"total_tokens\") AS \"p95\"")
                .contains("date_bin(5, 'minute', \"request_time\") AS \"bucket\"");
    }

    @Test
    void rendersDistinctQuantifierOnlyWhereExecutionHonorsIt() {
        // ExprTranslator honors the distinct flag only on count/sum/avg; min/max (and the scalars)
        // silently ignore it, so the renderer drops the quantifier there — rendering it would be
        // semantically inert yet outside the accepted SQL subset.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FnExpr("max", true, List.of(new FieldExpr("total_tokens"))), "top"),
                        new OutputColumn(new FnExpr("sum", true, List.of(new FieldExpr("total_tokens"))), "s"),
                        new OutputColumn(new FnExpr("avg", true, List.of(new FieldExpr("total_tokens"))), "a")),
                null, null, null, null);

        final String sql = render(query);

        assertThat(sql)
                .contains("max(\"total_tokens\") AS \"top\"")
                .contains("sum(DISTINCT \"total_tokens\") AS \"s\"")
                .contains("avg(DISTINCT \"total_tokens\") AS \"a\"");
        // And the rendered statement stays within the SQL subset the front end accepts.
        assertThat(frontEnd.translate(sql).query().mode()).isEqualTo(QueryMode.AGGREGATE);
    }

    @Test
    void quotesIdentifiersWithDots() {
        final StructuredQuery query = new StructuredQuery(
                "events", null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("users.region"), null)), null, null, null, null);
        assertThat(renderer.render(query, ENRICHED_MODEL)).isEqualTo("SELECT \"users.region\" FROM \"events\" FINAL");
    }

    @Test
    void groupUniqArrayRendersAndSurvivesTheRoundTrip() {
        // The catalog spelling is emitted verbatim, so the SQL front end has to accept it — the
        // sql-query-translation invariant that every DSL catalog name is an accepted SQL spelling.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(
                                new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment"))),
                                "deployments")),
                List.of("chat_id"), null, null, null);

        final String sql = render(query);

        assertThat(sql).contains("group_uniq_array(\"deployment\") AS \"deployments\"");
        assertThat(frontEnd.translate(sql).query().select())
                .containsExactly(
                        new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(
                                new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment"))),
                                "deployments"));
    }

    @Test
    void argMaxRendersAsAnOrdinaryCallAndSurvivesTheRoundTrip() {
        // Unlike the percentiles, arg_max needs no special rendered shape — it is an ordinary
        // two-argument call in both directions, which is why query-translation-endpoint needed no spec
        // delta for it. The round trip is what proves the Calcite operator registration accepts the
        // exact spelling this renderer emits.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(
                                new FnExpr("arg_max", false,
                                        List.of(new FieldExpr("deployment"), new FieldExpr("request_time"))),
                                "latest")),
                List.of("chat_id"), null, null, null);

        final String sql = render(query);

        assertThat(sql).contains("arg_max(\"deployment\", \"request_time\") AS \"latest\"");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void groupUniqArrayNestedInAnotherFunctionIsRejected() {
        // The SQL front end refuses LENGTH(<array>), so rendering this would emit SQL that does not
        // translate back — the round-trip guarantee this renderer exists to hold.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(new FnExpr("length", false, List.of(
                                new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment"))))), "n")),
                List.of("chat_id"), null, null, null);

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("can only be projected");
    }

    @Test
    void functionNamesMatchCaseInsensitivelyLikeExecution() {
        // The execute path lowercases function names before matching the closed catalog
        // (ExprTranslator), so uppercase spellings must render — lowercase — not be rejected.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FnExpr("COUNT", false, List.of()), "n"),
                        new OutputColumn(new FnExpr("PERCENTILE_CONT", false, List.of(
                                new ValueExpr(ValueType.DECIMAL, "0.95"), new FieldExpr("total_tokens"))), "p95"),
                        new OutputColumn(new FnExpr("Max", false, List.of(new FieldExpr("total_tokens"))), "top")),
                null, null, null, null);

        final String sql = render(query);

        assertThat(sql)
                .contains("count(*) AS \"n\"")
                .contains("percentile_cont(0.95) WITHIN GROUP (ORDER BY \"total_tokens\") AS \"p95\"")
                .contains("max(\"total_tokens\") AS \"top\"");
        // And the rendered statement stays within the SQL subset the front end accepts.
        assertThat(frontEnd.translate(sql).query().mode()).isEqualTo(QueryMode.AGGREGATE);
    }

    @Test
    void rendersPaddedLiteralsFromTheParsedValueLikeExecution() {
        // ValueExprToObjectMapper trims before parsing on the execute path; rendering must apply the
        // same normalization — a padded boolean must not silently invert, and padded date/timestamp
        // text must not leak into the SQL literal.
        final FilterNode filter = new LogicalNode(LogicalOp.AND, List.of(
                comparison(ComparisonOp.EQ, new FieldExpr("success"), new ValueExpr(ValueType.BOOLEAN, " true ")),
                comparison(ComparisonOp.GE, new FieldExpr("request_time"), new ValueExpr(ValueType.TIMESTAMP, " 1751328000000 ")),
                comparison(ComparisonOp.GT, new FieldExpr("total_tokens"), new ValueExpr(ValueType.LONG, " 100 "))));

        assertThat(render(rowQuery(filter)))
                .contains("\"success\" = TRUE")
                .contains("\"request_time\" >= TIMESTAMP '2025-07-01 00:00:00'")
                .contains("\"total_tokens\" > 100");
        assertThat(render(rowQuery(comparison(
                ComparisonOp.EQ, new FieldExpr("event_kind"), new ValueExpr(ValueType.DATE, " 2024-01-01 ")))))
                .contains("DATE '2024-01-01'");
    }

    @Test
    void rejectsMalformedLiteralsWithTheExecutePathMessage() {
        assertThatThrownBy(() -> render(rowQuery(comparison(
                ComparisonOp.EQ, new FieldExpr("success"), new ValueExpr(ValueType.BOOLEAN, "yes")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("invalid boolean literal");
        assertThatThrownBy(() -> render(rowQuery(comparison(
                ComparisonOp.GE, new FieldExpr("request_time"), new ValueExpr(ValueType.TIMESTAMP, "not-a-number")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("invalid long/timestamp literal");
    }

    // ------------------------------------------------------- inexpressible DSL

    @Test
    void rejectsContainsValueWithMetacharacters() {
        assertThatThrownBy(() -> render(
                rowQuery(comparison(ComparisonOp.CO, new FieldExpr("deployment"), string("50%")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'co'")
                .hasMessageContaining("metacharacter");
        assertThatThrownBy(() -> render(
                rowQuery(comparison(ComparisonOp.INC, new FieldExpr("deployment"), string("a_b")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'inc'");
    }

    @Test
    void rejectsNullLiteralOutsideEqNe() {
        assertThatThrownBy(() -> render(rowQuery(
                comparison(ComparisonOp.LT, new FieldExpr("total_tokens"), new ValueExpr(ValueType.NULL, null)))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("null literal");
    }

    @Test
    void rejectsHavingOnRowModeQuery() {
        // Execution silently ignores a row-mode having; rendering it would produce SQL the front end
        // rejects ("not being grouped"), so translation fails closed instead.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)),
                null,
                comparison(ComparisonOp.GT, new FieldExpr("total_tokens"), new ValueExpr(ValueType.LONG, "5")),
                null, null);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'having'")
                .hasMessageContaining("row-mode");
    }

    @Test
    void rejectsMalformedFilterTreesInsteadOfThrowingUnchecked() {
        // Belt-and-braces: the builder validates these on every governed path, but the renderer must
        // never turn a malformed tree into an unchecked exception (500) or unvalidated SQL text.
        assertThatThrownBy(() -> render(rowQuery(
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("event_kind"))))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("exactly two arguments");
        assertThatThrownBy(() -> render(rowQuery(new LogicalNode(LogicalOp.NOT, List.of()))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'not'");
        assertThatThrownBy(() -> render(rowQuery(new LogicalNode(LogicalOp.AND, List.of()))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'and'/'or'");
    }

    @Test
    void rejectsIncludeTotal() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)), null, null, null,
                new OffsetPage(0L, 10, true));
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("include_total");
    }

    @Test
    void rejectsArrayFieldInFilter() {
        // Array fields admit no comparison on either surface. Both guards fail closed on this
        // independently: the DSL-surface operand check during build, and the renderer's own guard,
        // which is what keeps the round-trip guarantee for an unbuilt render.
        final StructuredQuery query = rowQuery(
                comparison(ComparisonOp.EQ, new FieldExpr("execution_path"), new ValueExpr(ValueType.NULL, null)));
        assertThatThrownBy(() -> builder.build(ctx, MODEL, query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("can only be projected");
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void rejectsArrayFieldInHaving() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FieldExpr("deployment"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("deployment"),
                comparison(ComparisonOp.NE, new FieldExpr("execution_path"), new ValueExpr(ValueType.NULL, null)),
                null, null);
        assertThatThrownBy(() -> builder.build(ctx, MODEL, query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("can only be projected");
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("'having'");
    }

    @Test
    void rejectsArrayFieldSortKey() {
        // Directly by field name…
        final StructuredQuery byName = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)),
                null, null,
                List.of(new SortItem("execution_path", SortDir.ASC, null)), null);
        builder.build(ctx, MODEL, byName);
        assertThatThrownBy(() -> render(byName))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("'sort'");
        // …and via the alias of a projected array field (translate-back would sort the array itself).
        final StructuredQuery byAlias = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("execution_path"), "p")),
                null, null,
                List.of(new SortItem("p", SortDir.ASC, null)), null);
        builder.build(ctx, MODEL, byAlias);
        assertThatThrownBy(() -> render(byAlias))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("'sort'");
    }

    @Test
    void rejectsArrayFieldGroupKey() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FieldExpr("execution_path"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("execution_path"), null, null, null);
        builder.build(ctx, MODEL, query);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("'group_by'");
    }

    @Test
    void rejectsArrayFieldFunctionArgument() {
        // count(execution_path) is execution-acceptable (ExprTranslator has no arg type check), but
        // the SQL front end rejects aggregate/function calls over an array field on translate-back.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(new FnExpr("count", false, List.of(new FieldExpr("execution_path"))), "n")),
                null, null, null, null);
        builder.build(ctx, MODEL, query);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("function call");
    }

    @Test
    void rejectsDistinctOverArrayProjection() {
        // SELECT DISTINCT over an array column lowers to a full-width group-by on translate-back,
        // which the SQL front end rejects — projection-only, so the renderer fails closed.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, true,
                List.of(new OutputColumn(new FieldExpr("execution_path"), null)), null, null, null, null);
        builder.build(ctx, MODEL, query);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("execution_path")
                .hasMessageContaining("'distinct'");
    }

    @Test
    void rejectsAggregateModeDistinct() {
        // Execution accepts aggregate+distinct (deduplicating aggregate rows), but SELECT DISTINCT
        // combined with GROUP BY is rejected by the SQL front end as nested aggregation — the
        // combination is inexpressible, so translate fails closed on a document execute accepts.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, true,
                List.of(new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("deployment"), null, null, null);
        builder.build(ctx, DialUsageLogTestModel.model(), query);
        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'distinct'")
                .hasMessageContaining("aggregate");
    }

    // ------------------------------------------------------------- round-trip

    /** Validates like the translate endpoint (builder pass), renders, and re-translates the SQL. */
    // ------------------------------------------- derivation expression functions

    @Test
    void conditionalRendersWithCatalogNamesAndRoundTrips() {
        // The renderer emits catalog names verbatim, so this is also the assertion that the DSL wire name
        // and the SQL name have not drifted apart for any of the four functions involved.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FnExpr("if", false, List.of(
                        new FnExpr("not_empty", false, List.of(new FieldExpr("chat_id"))),
                        new ValueExpr(ValueType.STRING, "chat"),
                        new FnExpr("if", false, List.of(
                                new FnExpr("starts_with", false, List.of(
                                        new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt"))),
                                new ValueExpr(ValueType.STRING, "gpt"),
                                new ValueExpr(ValueType.STRING, "other"))))), "kind")),
                null, null, null, null);

        final String sql = render(query);

        assertThat(sql)
                .contains("if(not_empty(")
                .contains("starts_with(")
                .contains("AS \"kind\"");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void predicateProducersRenderAndRoundTrip() {
        for (final String name : List.of("starts_with", "contains", "equals")) {
            final StructuredQuery query = new StructuredQuery(
                    ENTITY, null, QueryMode.ROW, false,
                    List.of(new OutputColumn(new FnExpr(name, false, List.of(
                            new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt"))), "hit")),
                    null, null, null, null);

            assertThat(render(query)).as("render of %s", name).contains(name + "(");
            assertThat(roundTrip(query)).as("round trip of %s", name).isEqualTo(query);
        }
    }

    @Test
    void caseInsensitiveExtractionOverAnObjectFieldRendersAndRoundTrips() {
        // The object field is only ever the call's first argument — it never appears as an operand in its
        // own right, which is what keeps the projection-only rule intact while the extraction renders.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FnExpr("json_extract_string_ci", false, List.of(
                        new FieldExpr("dial_usage_log_payload.request_tags"), new ValueExpr(ValueType.STRING, "x-session-id"))), "sid")),
                null, null, null, null);

        assertThat(render(query)).contains("json_extract_string_ci(\"dial_usage_log_payload.request_tags\", 'x-session-id')");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void arrayExtractionRendersInProjectionAndRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FnExpr("json_extract_array", false, List.of(
                        new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "roles"))), "roles")),
                null, null, null, null);

        assertThat(render(query)).contains("json_extract_array(\"dial_usage_log_payload.jwt_claims\", 'roles')");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void arrayExtractionAsAnOperandFailsToRender() {
        // Rendering it would emit SQL the front end refuses on translate-back — the round-trip asymmetry
        // this renderer exists to prevent, so it is refused here instead.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FnExpr("length", false, List.of(
                        new FnExpr("json_extract_array", false, List.of(
                                new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "roles"))))), "n")),
                null, null, null, null);

        assertThatThrownBy(() -> render(query))
                .hasMessageContaining("json_extract_array")
                .hasMessageContaining("can only be projected");
    }

    @Test
    void arrayExtractionSortedByItsAliasIsRefusedLikeTheArrayValuedAggregate() {
        // This used to assert the opposite — that the sort rendered — as a "probe" recording that the
        // projection-only guard is keyed on declared field types and so cannot see a COMPUTED array. That
        // was the gap, not the contract: sql-query-translation says an array-returning call is "usable
        // only as a plain projection item (optionally aliased)", and the rendered ORDER BY was refused by
        // the SQL front end on the way back. Verified live against a running instance before changing it.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FnExpr("json_extract_array", false, List.of(
                        new FieldExpr("dial_usage_log_payload.jwt_claims"), new ValueExpr(ValueType.STRING, "roles"))), "roles")),
                null, null, List.of(new SortItem("roles", SortDir.ASC, null)), null);

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("json_extract_array")
                .hasMessageContaining("returns an array");
    }

    // ------------------------------------------------------------- case and window

    @Test
    void caseRendersAsSearchedCaseAndRoundTrips() {
        final CaseExpr size = new CaseExpr(List.of(
                new CaseBranch(comparison(ComparisonOp.GT, new FieldExpr("total_tokens"), new ValueExpr(ValueType.LONG, "1000")), string("large"))),
                string("small"));
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false, List.of(new OutputColumn(size, "size")), null, null, null, null);

        assertThat(render(query)).contains("CASE WHEN \"total_tokens\" > 1000 THEN 'large' ELSE 'small' END AS \"size\"");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void caseWithoutElseAndWithTheNullBranchRoundTrips() {
        final CaseExpr noElse = new CaseExpr(List.of(new CaseBranch(
                comparison(ComparisonOp.EQ, new FieldExpr("success"), new ValueExpr(ValueType.BOOLEAN, "true")),
                new FieldExpr("total_tokens"))), null);
        final CaseExpr nullThen = new CaseExpr(List.of(new CaseBranch(
                comparison(ComparisonOp.EQ, new FieldExpr("chat_id"), string("")),
                new ValueExpr(ValueType.NULL, null))), new FieldExpr("chat_id"));
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(noElse, "ok_tokens"), new OutputColumn(nullThen, "chat")), null, null, null, null);

        assertThat(render(query))
                .contains("THEN \"total_tokens\" END AS \"ok_tokens\"")
                .contains("THEN NULL ELSE \"chat_id\" END AS \"chat\"");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void windowRendersOverClauseAndRoundTrips() {
        final WindowExpr rn = new WindowExpr(new FnExpr("row_number", false, List.of()),
                List.of(new FieldExpr("project_id")),
                List.of(new WindowOrder(new FieldExpr("request_time"), SortDir.DESC, null)), null);
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("project_id"), null), new OutputColumn(rn, "rn")), null, null, null, null);

        assertThat(render(query)).contains("row_number() OVER (PARTITION BY \"project_id\" ORDER BY \"request_time\" DESC) AS \"rn\"");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void windowFrameRendersAndRoundTrips() {
        final WindowExpr running = new WindowExpr(new FnExpr("sum", false, List.of(new FieldExpr("total_tokens"))), List.of(),
                List.of(new WindowOrder(new FieldExpr("request_time"), SortDir.ASC, null)),
                new WindowFrame(FrameUnit.ROWS, new FrameBound(FrameBoundKind.UNBOUNDED_PRECEDING, null),
                        new FrameBound(FrameBoundKind.CURRENT_ROW, null)));
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false, List.of(new OutputColumn(running, "running")), null, null, null, null);

        assertThat(render(query)).endsWith("ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS \"running\" FROM \"dial_usage_log\" FINAL");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void windowOverAnAggregateRoundTripsInAggregateMode() {
        final FnExpr total = new FnExpr("sum", false, List.of(new FieldExpr("total_tokens")));
        final WindowExpr rank = new WindowExpr(new FnExpr("rank", false, List.of()), List.of(),
                List.of(new WindowOrder(total, SortDir.DESC, null)), null);
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(new FieldExpr("project_id"), null), new OutputColumn(total, "t"), new OutputColumn(rank, "r")),
                List.of("project_id"), null, null, null);

        assertThat(render(query)).contains("rank() OVER (ORDER BY sum(\"total_tokens\") DESC) AS \"r\"");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void windowInTheFilterIsRejectedBeforeRendering() {
        final WindowExpr rn = new WindowExpr(new FnExpr("row_number", false, List.of()), null, null, null);
        final StructuredQuery query = rowQuery(comparison(ComparisonOp.LT, rn, new ValueExpr(ValueType.INTEGER, "10")));
        assertThatThrownBy(() -> builder.build(ctx, MODEL, query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'select' entries only");
    }

    @Test
    void higherOrderCallRoundTrips() {
        // Rendered in ClickHouse's shape and read back to the same document: the lambda first, the
        // parameter keeping the caller's own name rather than a canonical one.
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("arrayExists", false, List.of(
                                new LambdaExpr(List.of("role"), new FnExpr("starts_with", false, List.of(
                                        new LambdaParamExpr("role"),
                                        new ValueExpr(ValueType.STRING, "coder-")))),
                                new FieldExpr("execution_path"))),
                        "coder")),
                null, null, null, null);

        // The parameter renders unquoted on this surface too: it is a binding, not a column reference,
        // and the front end reads it back as one.
        assertThat(render(query)).contains("arrayExists(role -> starts_with(role, 'coder-')");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void capturedColumnRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(
                        new FnExpr("arrayExists", false, List.of(
                                new LambdaExpr(List.of("x"), new FnExpr("starts_with", false, List.of(
                                        new LambdaParamExpr("x"), new FieldExpr("deployment")))),
                                new FieldExpr("execution_path"))),
                        "matches")),
                null, null, null, null);

        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void flatteningAggregateRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(
                        new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(new FnExpr("groupUniqArrayArray", false,
                                List.of(new FieldExpr("execution_path"))), "steps")),
                List.of("chat_id"), null, null, null);

        // The canonical camelCase survives the rendering: lowercasing it would emit a name the catalog
        // does not advertise, even though this surface would read it back case-insensitively.
        assertThat(render(query)).contains("groupUniqArrayArray(");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void regexPredicateRoundTrips() {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                new ComparisonNode(ComparisonOp.EQ, List.of(
                        new FnExpr("match", false, List.of(
                                new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "^gpt-4"))),
                        new ValueExpr(ValueType.BOOLEAN, "true"))),
                QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("chat_id"), null)),
                null, null, null, null);

        assertThat(roundTrip(query)).isEqualTo(query);
    }

    private StructuredQuery roundTrip(StructuredQuery query) {
        return roundTripWith(DialUsageLogTestModel.model(), query);
    }

    /**
     * {@link #roundTrip} against a model other than the default fixture.
     *
     * <p>An absent {@code final} does not survive the trip as absent — SQL always says whether a reference is
     * {@code FINAL}, so it comes back explicit, as what the absent value resolved to. That resolution is
     * asserted here and the value normalised back to absent, so the callers compare everything else.
     */
    private StructuredQuery roundTripWith(QueryEntityModel model, StructuredQuery query) {
        builder.build(ctx, model, query);
        final StructuredQuery back = frontEnd.translate(renderWith(model, query)).query();
        if (query.finalRead() != null) {
            return back;
        }
        assertThat(back.finalRead()).as("an absent final comes back as what it resolved to")
                .isEqualTo(FinalDecision.readsFinal(model, null, false));
        return back.withFinalRead(null);
    }

    private String renderWith(QueryEntityModel model, StructuredQuery query) {
        return renderer.render(query, model);
    }

    @Test
    void bareSelectOnEnrichedEntityRoundTripsToTheExecutionProjection() {
        // A bare select executes as the source-owned columns only; the rendered SQL must translate
        // back to exactly that projection — no enrichment columns, hence no enrichment joins.
        final StructuredQuery query = new StructuredQuery(
                ENRICHED_MODEL.entity(), null, QueryMode.ROW, false, null, null, null, null, null);
        builder.build(ctx, ENRICHED_MODEL, query);

        final String sql = renderer.render(query, ENRICHED_MODEL);
        final StructuredQuery translated = frontEnd.translate(sql).query();

        assertThat(translated.entity()).isEqualTo("events");
        assertThat(translated.select()).containsExactly(
                new OutputColumn(new FieldExpr("user_id"), null),
                new OutputColumn(new FieldExpr("amount"), null));
    }

    @Test
    void rowQueryRoundTripsThroughTheSqlFrontEnd() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                new LogicalNode(LogicalOp.AND, List.of(
                        comparison(ComparisonOp.EQ, new FieldExpr("event_kind"), string("chat")),
                        comparison(ComparisonOp.CO, new FieldExpr("deployment"), string("gpt")))),
                QueryMode.ROW,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("project_id"), null),
                        new OutputColumn(new FieldExpr("total_tokens"), null)),
                null,
                null,
                List.of(new SortItem("total_tokens", SortDir.DESC, NullsOrder.LAST)),
                new OffsetPage(20L, 10, false));

        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void unspecifiedNullsOrderRoundTripsToAnExplicitOne() {
        // Calcite normalizes an unspecified NULLS ordering to an explicit one (the nulls-greatest
        // convention ClickHouse shares), so the DSL round-trips to an explicit but equivalent sort.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("total_tokens"), null)),
                null, null,
                List.of(new SortItem("total_tokens", SortDir.DESC, null)),
                null);

        final StructuredQuery translated = roundTrip(query);

        assertThat(translated.sort()).hasSize(1);
        assertThat(translated.sort().getFirst().field()).isEqualTo("total_tokens");
        assertThat(translated.sort().getFirst().dir()).isEqualTo(SortDir.DESC);
    }

    @Test
    void containsOperatorsRoundTripWithExactCaseSemantics() {
        for (final ComparisonOp op : List.of(ComparisonOp.CO, ComparisonOp.ICO)) {
            final StructuredQuery query = new StructuredQuery(
                    ENTITY,
                    comparison(op, new FieldExpr("deployment"), string("gpt")),
                    QueryMode.ROW,
                    false,
                    List.of(new OutputColumn(new FieldExpr("event_kind"), null)),
                    null, null, null, null);
            assertThat(roundTrip(query)).as("operator %s", op).isEqualTo(query);
        }
    }

    @Test
    void aggregateQueryRoundTripsThroughTheSqlFrontEnd() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                comparison(ComparisonOp.GT, new FieldExpr("total_price"), new ValueExpr(ValueType.DECIMAL, "0.5")),
                QueryMode.AGGREGATE,
                false,
                List.of(
                        // A group key needs no alias — its derived name is the field name, which is
                        // what the front end reports back, so this form round-trips identically.
                        new OutputColumn(new FieldExpr("deployment"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("deployment"),
                // A literal compared to a function has no field type to retype against, so its DSL
                // value_type is the plain SQL literal type: integer.
                comparison(ComparisonOp.GT, new FnExpr("count", false, List.of()), new ValueExpr(ValueType.INTEGER, "10")),
                List.of(new SortItem("n", SortDir.DESC, NullsOrder.LAST)),
                new OffsetPage(0L, 50, false));

        final StructuredQuery translated = roundTrip(query);

        assertThat(translated.entity()).isEqualTo(ENTITY);
        assertThat(translated.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translated.groupBy()).containsExactly("deployment");
        assertThat(translated.select()).isEqualTo(query.select());
        // Calcite retypes the decimal literal to the field's declared scale (0.5 → 0.500000000):
        // numerically identical, so compare the value as a number and the rest structurally.
        final ComparisonNode filter = (ComparisonNode) translated.filter();
        assertThat(filter.op()).isEqualTo(ComparisonOp.GT);
        assertThat(filter.args().get(0)).isEqualTo(new FieldExpr("total_price"));
        final ValueExpr threshold = (ValueExpr) filter.args().get(1);
        assertThat(threshold.valueType()).isEqualTo(ValueType.DECIMAL);
        assertThat(new BigDecimal(threshold.value())).isEqualByComparingTo("0.5");
        assertThat(translated.having()).isEqualTo(query.having());
        assertThat(translated.sort()).isEqualTo(query.sort());
        assertThat(translated.page()).isEqualTo(query.page());
    }

    @Test
    void everyScalarCatalogFunctionRoundTripsThroughTheSqlFrontEnd() {
        // The renderer spells every catalog function under its lowercase DSL name, so each scalar
        // must be a spelling the SQL front end accepts — a rendered statement that translate-sql
        // rejects breaks the round-trip guarantee (length was once such a gap: the front end only
        // knew char_length/character_length).
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(
                        new OutputColumn(new FnExpr("lower", false, List.of(new FieldExpr("deployment"))), "lo"),
                        new OutputColumn(new FnExpr("upper", false, List.of(new FieldExpr("deployment"))), "up"),
                        new OutputColumn(new FnExpr("length", false, List.of(new FieldExpr("deployment"))), "len"),
                        new OutputColumn(new FnExpr("trim", false, List.of(new FieldExpr("deployment"))), "tr"),
                        new OutputColumn(new FnExpr("abs", false, List.of(new FieldExpr("total_tokens"))), "ab"),
                        new OutputColumn(new FnExpr("date_bin", false, List.of(
                                new ValueExpr(ValueType.INTEGER, "5"), string("minute"), new FieldExpr("request_time"))), "bin"),
                        new OutputColumn(new FnExpr("width_bucket", false, List.of(
                                new FieldExpr("total_tokens"),
                                new ValueExpr(ValueType.INTEGER, "0"),
                                new ValueExpr(ValueType.INTEGER, "1000"),
                                new ValueExpr(ValueType.INTEGER, "10"))), "bkt")),
                null, null, null, null);

        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void percentileAggregatesRoundTripThroughTheSqlFrontEnd() {
        // Rendered as fn(fraction) WITHIN GROUP (ORDER BY column) — the only percentile shape the
        // SQL front end parses — and translated back to the identical (fraction, column) DSL call.
        for (final String fn : List.of("percentile_cont", "percentile_disc")) {
            final StructuredQuery query = new StructuredQuery(
                    ENTITY, null, QueryMode.AGGREGATE, false,
                    List.of(new OutputColumn(new FnExpr(fn, false, List.of(
                            new ValueExpr(ValueType.DECIMAL, "0.95"), new FieldExpr("total_tokens"))), "p95")),
                    null, null, null, null);
            assertThat(roundTrip(query)).as("function %s", fn).isEqualTo(query);
        }
    }

    @Test
    void distinctQueryRoundTripsAsDistinct() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, true,
                List.of(new OutputColumn(new FieldExpr("deployment"), null)), null, null, null, null);
        final StructuredQuery translated = roundTrip(query);
        assertThat(translated.distinct()).isTrue();
        assertThat(translated.select()).isEqualTo(query.select());
    }

    @Test
    void scalarWithDistinctFlagRendersWithoutDistinctAndRoundTrips() {
        // Execution accepts a scalar call with distinct=true but ignores the flag (ExprTranslator),
        // so the translate endpoint's validation pass accepts the document too. The rendered SQL
        // must not carry the quantifier — lower(DISTINCT x) is outside the accepted subset (the
        // front end rejects it) — and must round-trip to the execution-equivalent call.
        final StructuredQuery query = new StructuredQuery(
                ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FnExpr("lower", true, List.of(new FieldExpr("deployment"))), "lo")),
                null, null, null, null);
        builder.build(ctx, DialUsageLogTestModel.model(), query);

        final String sql = render(query);
        assertThat(sql).isEqualTo("SELECT lower(\"deployment\") AS \"lo\" FROM \"dial_usage_log\" FINAL");

        final StructuredQuery translated = frontEnd.translate(sql).query();
        assertThat(translated.select()).containsExactly(
                new OutputColumn(new FnExpr("lower", false, List.of(new FieldExpr("deployment"))), "lo"));
    }

    @Test
    void nullCheckAndInListRoundTrip() {
        // AND keeps the IN subtree intact; inside an OR, Calcite flattens the IN into sibling
        // equalities (semantically equivalent, but not the identical tree).
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                new LogicalNode(LogicalOp.AND, List.of(
                        comparison(ComparisonOp.EQ, new FieldExpr("chat_id"), new ValueExpr(ValueType.NULL, null)),
                        comparison(ComparisonOp.IN, new FieldExpr("event_kind"),
                                new ArrayExpr(List.of(string("chat"), string("embedding")))))),
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)),
                null, null, null, null);

        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void timestampFilterRoundTrips() {
        final long epochMillis = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli();
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                comparison(ComparisonOp.GE, new FieldExpr("request_time"),
                        new ValueExpr(ValueType.TIMESTAMP, Long.toString(epochMillis))),
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("event_kind"), null)),
                null, null, null, null);

        assertThat(roundTrip(query)).isEqualTo(query);
    }

    // ------------------------------------------------ optional aliases / derived output names

    @Test
    void unaliasedAggregateRendersItsDerivedAlias() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("deployment"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), null)),
                List.of("deployment"),
                null,
                null,
                null);

        final String sql = render(query);

        // The plain field needs no alias; the aggregate declares the name execution returns.
        assertThat(sql).contains("\"deployment\", count(*) AS \"count\"");
        // ... and the statement round-trips to the same document, aliases and all.
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void unaliasedScalarProjectionRendersItsDerivedAlias() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                null,
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FnExpr("lower", false, List.of(new FieldExpr("deployment"))), null)),
                null, null, null, null);

        assertThat(render(query)).contains("lower(\"deployment\") AS \"lower\"");
        assertThat(roundTrip(query)).isEqualTo(query);
    }

    @Test
    void collidingUnaliasedEntriesRenderSuffixedAliases() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
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

        final String sql = render(query);

        assertThat(sql).contains("AS \"count\"").contains("AS \"count_1\"");
        // Re-translating reports the same two names, so the round-trip preserves the column identities.
        assertThat(frontEnd.translate(sql).columns()).extracting(ResultColumn::name).containsExactly("count", "count_1");
    }

    @Test
    void derivedNameIsUsableAsSortKey() {
        final StructuredQuery query = new StructuredQuery(
                ENTITY,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("deployment"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), null)),
                List.of("deployment"),
                null,
                // The null ordering is stated explicitly (DESC's own default) so the round-trip compares
                // equal — Calcite always reports a resolved null ordering.
                List.of(new SortItem("count", SortDir.DESC, NullsOrder.FIRST)),
                null);

        final String sql = render(query);

        assertThat(sql).contains("ORDER BY \"count\" DESC");
        assertThat(roundTrip(query)).isEqualTo(query);
    }
}
