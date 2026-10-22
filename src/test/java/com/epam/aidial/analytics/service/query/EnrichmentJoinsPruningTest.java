package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a keyed grain join projects for an {@code object} column, which is the difference between a
 * single-path read costing one stream per part and costing one stream per JSON path per part.
 *
 * <p>The assertions are on rendered SQL rather than on the usage map, because the defect this guards
 * against is not a wrong map — it is a map the projection and the outer reference read differently, which
 * only shows up as a statement naming a column nothing projects.
 */
class EnrichmentJoinsPruningTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String PAYLOAD = "dial_usage_log_payload";
    private static final String TAGS = PAYLOAD + ".request_tags";
    private static final String CLAIMS = PAYLOAD + ".jwt_claims";

    private final DSLContext render = DSL.using(SQLDialect.DEFAULT);

    @Test
    void keyedSinglePathReadProjectsTheAliasedSubcolumnAndNotTheColumn() {
        String sql = renderKeyed(usage(builder -> builder.addPath(TAGS, List.of("baggage"))), Set.of(TAGS));

        assertThat(sql).contains("\"request_tags\".\"baggage\"");
        assertThat(sql).contains("\"request_tags.baggage\"");
        // The whole column must not be projected beside the subcolumn: projecting both would open every
        // path's stream anyway and make the pruning cosmetic.
        assertThat(projectionOfDerivedTable(sql)).doesNotContain("\"dial_usage_log_payload\".\"request_tags\",");
    }

    @Test
    void caseInsensitiveReadStillProjectsTheWholeColumn() {
        // json_extract_string_ci has no statically known key, so there is no subcolumn to address and the
        // serialized document is genuinely needed.
        String sql = renderKeyed(usage(builder -> builder.markWhole(TAGS)), Set.of(TAGS));

        assertThat(sql).contains("\"dial_usage_log_payload\".\"request_tags\"");
        assertThat(sql).doesNotContain("\"request_tags.");
    }

    @Test
    void statementMixingBothPrunesOnlyThePrunableColumn() {
        String sql = renderKeyed(
                usage(builder -> builder.addPath(TAGS, List.of("baggage")).markWhole(CLAIMS)),
                Set.of(TAGS, CLAIMS));

        assertThat(sql).contains("\"request_tags.baggage\"");
        assertThat(sql).contains("\"dial_usage_log_payload\".\"jwt_claims\"");
        assertThat(sql).doesNotContain("\"jwt_claims.");
    }

    @Test
    void twoPathsOnOneColumnBothProject() {
        String sql = renderKeyed(
                usage(builder -> builder
                        .addPath(TAGS, List.of("baggage"))
                        .addPath(TAGS, List.of("user-agent"))),
                Set.of(TAGS));

        assertThat(sql).contains("\"request_tags.baggage\"");
        assertThat(sql).contains("\"request_tags.user-agent\"");
    }

    @Test
    void anUnkeyedJoinIsNeverPruned() {
        SelectQuery<Record> select = render.selectQuery();
        QueryEntityModel model = model();
        select.addFrom(model.table());
        // No source predicates: the join is emitted as the bare enrichment table, which has no derived
        // table and therefore no alias for a pruned path to resolve against.
        QueryEntityModel effective = EnrichmentJoins.addReferenced(
                select, model, Set.of(TAGS), List.of(), usage(b -> b.addPath(TAGS, List.of("baggage"))));

        assertThat(effective.bindings().get(TAGS).prunedPaths()).isEmpty();
        assertThat(select.getSQL(ParamType.INLINED)).doesNotContain("\"request_tags.baggage\"");
    }

    @Test
    void theReturnedModelCarriesThePrunedPathsAndTheOriginalDoesNot() {
        SelectQuery<Record> select = render.selectQuery();
        QueryEntityModel model = model();
        select.addFrom(model.table());
        QueryEntityModel effective = EnrichmentJoins.addReferenced(
                select, model, Set.of(TAGS), List.of(sourcePredicate()),
                usage(b -> b.addPath(TAGS, List.of("baggage"))));

        assertThat(effective.bindings().get(TAGS).prunedPaths()).containsExactly(List.of("baggage"));
        // The caller's own model is untouched, so a caller that renders against it gets the unpruned
        // statement it would have got before — wrong for a pruned join, which is why addReferenced hands
        // back the model to use rather than mutating this one.
        assertThat(model.bindings().get(TAGS).prunedPaths()).isEmpty();
    }

    @Test
    void anEmptyUsageRendersExactlyWhatItRenderedBefore() {
        SelectQuery<Record> select = render.selectQuery();
        QueryEntityModel model = model();
        select.addFrom(model.table());
        QueryEntityModel effective = EnrichmentJoins.addReferenced(
                select, model, Set.of(TAGS), List.of(sourcePredicate()), ObjectPathUsage.none());

        assertThat(effective).isSameAs(model);
        assertThat(select.getSQL(ParamType.INLINED)).contains("\"dial_usage_log_payload\".\"request_tags\"");
    }

    /**
     * The coupling that actually breaks things: the derived table's alias and the outer statement's
     * reference are produced by different classes, and if they disagree the statement names a column
     * nothing projects. Rendered together here, from the collector's own verdict, so nothing in the chain
     * is asserted from a hand-written expectation of what the other side does.
     */
    @Test
    void prunedColumnIsAddressedThroughTheAliasTheJoinProjected() {
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        Expr expr = new FnExpr("json_extract_string", false,
                List.of(new FieldExpr(TAGS), new ValueExpr(ValueType.STRING, "baggage")));
        QueryEntityModel model = model();

        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        translator.collectObjectPaths(expr, model.bindings(), usage);

        SelectQuery<Record> select = render.selectQuery();
        select.addFrom(model.table());
        QueryEntityModel effective = EnrichmentJoins.addReferenced(
                select, model, Set.of(TAGS), List.of(sourcePredicate()), usage.build());
        select.addSelect(translator.toField(expr, effective.bindings()));

        String sql = select.getSQL(ParamType.INLINED);
        // jOOQ's DEFAULT dialect renders a derived-column alias without the AS keyword.
        assertThat(sql).contains("\"request_tags\".\"baggage\" \"request_tags.baggage\"");
        assertThat(sql).contains("\"dial_usage_log_payload\".\"request_tags.baggage\"");
    }

    /**
     * The output that blocked a client-identity enrichment. {@code json_extract_array} was
     * classified with {@code json_extract_string_ci}, so this one reference forced {@code jwt_claims}
     * whole and cost the pipeline's five single-path reads their pruning too — which is why the case
     * asserts the five alongside it rather than the array alone.
     */
    @Test
    void arrayExtractionPrunesAndNoLongerCostsItsColumnTheOtherReads() {
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        QueryEntityModel model = model();
        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        Expr roles = new FnExpr(JsonExtractFunctions.ARRAY, false,
                List.of(new FieldExpr(CLAIMS), new ValueExpr(ValueType.STRING, "roles")));
        translator.collectObjectPaths(roles, model.bindings(), usage);
        translator.collectObjectPaths(new FnExpr("json_extract_string", false,
                List.of(new FieldExpr(CLAIMS), new ValueExpr(ValueType.STRING, "sub"))), model.bindings(), usage);

        ObjectPathUsage built = usage.build();
        assertThat(built.isPruned(CLAIMS)).isTrue();
        assertThat(built.prunedPaths(CLAIMS)).containsExactlyInAnyOrder(List.of("roles"), List.of("sub"));

        SelectQuery<Record> select = render.selectQuery();
        select.addFrom(model.table());
        QueryEntityModel effective = EnrichmentJoins.addReferenced(
                select, model, Set.of(CLAIMS), List.of(sourcePredicate()), built);
        select.addSelect(translator.toField(roles, effective.bindings()));

        String sql = select.getSQL(ParamType.INLINED);
        // Projected as a subcolumn under the dotted alias, and addressed through that alias.
        assertThat(sql).contains("\"jwt_claims\".\"roles\" \"jwt_claims.roles\"");
        assertThat(sql).contains("toJSONString(\"dial_usage_log_payload\".\"jwt_claims.roles\")");
        // The whole column is what this change exists to stop projecting.
        assertThat(projectionOfDerivedTable(sql)).doesNotContain("\"dial_usage_log_payload\".\"jwt_claims\",");
    }

    /**
     * The variadic half: {@code json_extract_array} takes more than one key where
     * {@code json_extract_string_ci} takes exactly one, so a deeper path has to survive the alias.
     */
    @Test
    void arrayExtractionPrunesAmultiSegmentPath() {
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        QueryEntityModel model = model();
        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        Expr expr = new FnExpr(JsonExtractFunctions.ARRAY, false, List.of(new FieldExpr(CLAIMS),
                new ValueExpr(ValueType.STRING, "realm"), new ValueExpr(ValueType.STRING, "roles")));
        translator.collectObjectPaths(expr, model.bindings(), usage);

        SelectQuery<Record> select = render.selectQuery();
        select.addFrom(model.table());
        QueryEntityModel effective = EnrichmentJoins.addReferenced(
                select, model, Set.of(CLAIMS), List.of(sourcePredicate()), usage.build());
        select.addSelect(translator.toField(expr, effective.bindings()));

        String sql = select.getSQL(ParamType.INLINED);
        assertThat(sql).contains("\"jwt_claims\".\"realm\".\"roles\" \"jwt_claims.realm.roles\"");
        assertThat(sql).contains("toJSONString(\"dial_usage_log_payload\".\"jwt_claims.realm.roles\")");
    }

    @Test
    void anUnprunableReadAddressesTheWholeColumnOnBothSides() {
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        Expr expr = new FnExpr(JsonExtractFunctions.STRING_CI, false,
                List.of(new FieldExpr(TAGS), new ValueExpr(ValueType.STRING, "baggage")));
        QueryEntityModel model = model();

        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        translator.collectObjectPaths(expr, model.bindings(), usage);
        assertThat(usage.build().isPruned(TAGS)).isFalse();

        SelectQuery<Record> select = render.selectQuery();
        select.addFrom(model.table());
        QueryEntityModel effective = EnrichmentJoins.addReferenced(
                select, model, Set.of(TAGS), List.of(sourcePredicate()), usage.build());
        select.addSelect(translator.toField(expr, effective.bindings()));

        assertThat(select.getSQL(ParamType.INLINED)).doesNotContain("\"request_tags.baggage\"");
    }

    @Test
    void columnUsedAsValueInItsOwnRightIsNotPruned() {
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        QueryEntityModel model = model();
        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        // A bare reference returns the document, so no path within it is what was asked for.
        translator.collectObjectPaths(new FieldExpr(TAGS), model.bindings(), usage);

        assertThat(usage.build().isPruned(TAGS)).isFalse();
    }

    @Test
    void oneUnprunableReadCostsItsOwnColumnThePruningAndNoOther() {
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        QueryEntityModel model = model();
        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        // Same column, both shapes — whole must win regardless of which is walked first.
        translator.collectObjectPaths(new FnExpr("json_extract_string", false,
                List.of(new FieldExpr(TAGS), new ValueExpr(ValueType.STRING, "baggage"))), model.bindings(), usage);
        translator.collectObjectPaths(new FnExpr(JsonExtractFunctions.STRING_CI, false,
                List.of(new FieldExpr(TAGS), new ValueExpr(ValueType.STRING, "user-agent"))), model.bindings(), usage);
        translator.collectObjectPaths(new FnExpr("json_extract_string", false,
                List.of(new FieldExpr(CLAIMS), new ValueExpr(ValueType.STRING, "user_id"))), model.bindings(), usage);

        ObjectPathUsage built = usage.build();
        assertThat(built.isPruned(TAGS)).isFalse();
        assertThat(built.prunedPaths(CLAIMS)).containsExactly(List.of("user_id"));
    }

    private String renderKeyed(ObjectPathUsage usage, Set<String> referenced) {
        SelectQuery<Record> select = render.selectQuery();
        QueryEntityModel model = model();
        select.addFrom(model.table());
        EnrichmentJoins.addReferenced(select, model, referenced, List.of(sourcePredicate()), usage);
        return select.getSQL(ParamType.INLINED);
    }

    /** The derived table's select list, which is where a pruned projection has to be visible. */
    private static String projectionOfDerivedTable(String sql) {
        int from = sql.indexOf("from \"dial_usage_log_payload\"");
        return from < 0 ? sql : sql.substring(0, from);
    }

    private static ObjectPathUsage usage(java.util.function.Consumer<ObjectPathUsage.Builder> build) {
        ObjectPathUsage.Builder builder = ObjectPathUsage.builder();
        build.accept(builder);
        return builder.build();
    }

    private static Condition sourcePredicate() {
        return DSL.field(DSL.name(SOURCE, "request_time")).gt(DSL.inline("2026-09-15"));
    }

    /** A source with one <b>keyable</b>, partition-aligned payload enrichment exposing two object columns. */
    private static QueryEntityModel model() {
        Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("event_id", new QueryFieldBinding(
                "event_id", DSL.field(DSL.name(SOURCE, "event_id")), QueryFieldType.STRING));
        bindings.put("request_time", new QueryFieldBinding(
                "request_time", DSL.field(DSL.name(SOURCE, "request_time")), QueryFieldType.TIMESTAMP));
        bindings.put(TAGS, new QueryFieldBinding(
                TAGS, DSL.field(DSL.name(PAYLOAD, "request_tags")), QueryFieldType.OBJECT));
        bindings.put(CLAIMS, new QueryFieldBinding(
                CLAIMS, DSL.field(DSL.name(PAYLOAD, "jwt_claims")), QueryFieldType.OBJECT));

        EnrichmentJoin join = EnrichmentJoin.keyed(
                DSL.table(DSL.name(PAYLOAD)),
                DSL.field(DSL.name(SOURCE, "event_id")),
                DSL.field(DSL.name(PAYLOAD, "event_id")),
                DSL.field(DSL.name(SOURCE, "request_time")),
                DSL.field(DSL.name(PAYLOAD, "request_time")),
                Set.of(TAGS, CLAIMS), null);

        List<QuerySchemaFieldDto> schema = List.of();
        return new QueryEntityModel(
                SOURCE, DSL.table(DSL.name(SOURCE)), bindings, List.of(join), schema);
    }

    @Test
    void multiSegmentPathsProjectAndResolveTogether() {
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        Expr expr = new FnExpr("json_extract_string", false, List.of(
                new FieldExpr(CLAIMS), new ValueExpr(ValueType.STRING, "realm"),
                new ValueExpr(ValueType.STRING, "roles")));
        QueryEntityModel model = model();

        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        translator.collectObjectPaths(expr, model.bindings(), usage);

        SelectQuery<Record> select = render.selectQuery();
        select.addFrom(model.table());
        QueryEntityModel effective = EnrichmentJoins.addReferenced(
                select, model, Set.of(CLAIMS), List.of(sourcePredicate()), usage.build());
        select.addSelect(translator.toField(expr, effective.bindings()));

        String sql = select.getSQL(ParamType.INLINED);
        assertThat(sql).contains("\"jwt_claims\".\"realm\".\"roles\" \"jwt_claims.realm.roles\"");
        assertThat(sql).contains("\"dial_usage_log_payload\".\"jwt_claims.realm.roles\"");
    }

    @Test
    void keyContainingDotFallsBackToTheWholeColumn() {
        // The alias joins column and path with dots, so the single key "a.b" and the path ["a","b"] would
        // collide on one derived-table name and one read would silently return the other's value. Refused
        // into the whole-column rendering, which is correct for both.
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        QueryEntityModel model = model();
        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        translator.collectObjectPaths(new FnExpr("json_extract_string", false,
                List.of(new FieldExpr(TAGS), new ValueExpr(ValueType.STRING, "a.b"))), model.bindings(), usage);

        assertThat(usage.build().isPruned(TAGS)).isFalse();
    }

    @Test
    void theDottedKeyAndTheTwoSegmentPathCannotCollide() {
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        QueryEntityModel model = model();
        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        translator.collectObjectPaths(new FnExpr("json_extract_string", false,
                List.of(new FieldExpr(TAGS), new ValueExpr(ValueType.STRING, "a.b"))), model.bindings(), usage);
        translator.collectObjectPaths(new FnExpr("json_extract_long", false,
                List.of(new FieldExpr(TAGS), new ValueExpr(ValueType.STRING, "a"),
                        new ValueExpr(ValueType.STRING, "b"))), model.bindings(), usage);

        // The dotted key made the whole column whole, so neither is projected as a subcolumn and the two
        // aliases that would have collided are never emitted.
        assertThat(usage.build().isPruned(TAGS)).isFalse();

        SelectQuery<Record> select = render.selectQuery();
        select.addFrom(model.table());
        EnrichmentJoins.addReferenced(
                select, model, Set.of(TAGS), List.of(sourcePredicate()), usage.build());
        assertThat(select.getSQL(ParamType.INLINED)).doesNotContain("\"request_tags.a.b\"");
    }

    @Test
    void anUppercaseFunctionNameLosesPruningRatherThanGainingIt() {
        // The renderer lower-cases before dispatch; the collector compares raw. The asymmetry must fail
        // toward the whole column, never toward a pruned projection the renderer will not address.
        ExprTranslator translator = new ExprTranslator(new ValueExprToObjectMapper());
        QueryEntityModel model = model();
        ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        translator.collectObjectPaths(new FnExpr("JSON_EXTRACT_STRING", false,
                List.of(new FieldExpr(TAGS), new ValueExpr(ValueType.STRING, "baggage"))), model.bindings(), usage);

        assertThat(usage.build().isPruned(TAGS)).isFalse();
    }
}
