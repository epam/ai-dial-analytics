package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.DerivedRelation;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.FrameBoundKind;
import com.epam.aidial.analytics.service.query.model.FrameUnit;
import com.epam.aidial.analytics.service.query.model.JoinKind;
import com.epam.aidial.analytics.service.query.model.JoinRelation;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The composite half of the SQL front end — joins, CTEs, derived tables and subqueries — plus the two new
 * expression kinds, CASE and windows, against the scenarios of the sql-query-translation delta. Offline,
 * over {@link DialUsageLogTestModel} and a second entity {@code events} carrying an enrichment column.
 */
class SqlCompositeTranslationTest {

    private final SqlQueryFrontEnd frontEnd = newFrontEnd();

    private static SqlQueryFrontEnd newFrontEnd() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY, "events", "spans"));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        when(catalog.findModel(eq("events"), any())).thenReturn(Optional.of(eventsModel()));
        when(catalog.findModel(eq("spans"), any())).thenReturn(Optional.of(spansModel()));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    /**
     * Source {@code spans(user_id, attributes map, status enum)} — the two declared types whose runtime
     * kind is shared with another type ({@code map} with {@code object}, {@code enum} with {@code string}),
     * so a column of either only keeps its own validation rules across a join or a derived block if the
     * declared type travels with the column rather than being recovered from the kind.
     */
    private static QueryEntityModel spansModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        final List<QuerySchemaFieldDto> schema = new java.util.ArrayList<>();
        for (final var column : List.of(
                Map.entry("user_id", QueryFieldType.STRING),
                Map.entry("attributes", QueryFieldType.MAP),
                Map.entry("status", QueryFieldType.ENUM))) {
            bindings.put(column.getKey(), new QueryFieldBinding(column.getKey(),
                    DSL.field(DSL.name("spans", column.getKey()), JooqTypes.of(column.getValue())), column.getValue()));
            schema.add(new QuerySchemaFieldDto(
                    column.getKey(), column.getValue(), null, null, column.getKey(), null, null, null, false, false));
        }
        return new QueryEntityModel("spans", DSL.table(DSL.name("spans")), bindings, List.of(), schema);
    }

    /** Source {@code events(user_id, amount, rate)} with enrichment {@code users} exposing {@code users.region}. */
    private static QueryEntityModel eventsModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        final List<QuerySchemaFieldDto> schema = new java.util.ArrayList<>();
        for (final var column : List.of(
                Map.entry("user_id", QueryFieldType.STRING),
                Map.entry("amount", QueryFieldType.LONG),
                Map.entry("rate", QueryFieldType.DECIMAL))) {
            bindings.put(column.getKey(), new QueryFieldBinding(column.getKey(),
                    DSL.field(DSL.name("events", column.getKey()), JooqTypes.of(column.getValue())), column.getValue()));
            schema.add(new QuerySchemaFieldDto(
                    column.getKey(), column.getValue(), null, null, column.getKey(), null, null, null, false, false));
        }
        bindings.put("users.region", new QueryFieldBinding(
                "users.region", DSL.field(DSL.name("users", "region"), JooqTypes.of(QueryFieldType.STRING)),
                QueryFieldType.STRING));
        schema.add(new QuerySchemaFieldDto(
                "users.region", QueryFieldType.STRING, null, null, "region", null, null, null, false, false));
        final EnrichmentJoin join = new EnrichmentJoin(
                DSL.table(DSL.name("users")),
                DSL.field(DSL.name("events", "user_id")).eq(DSL.field(DSL.name("users", "user_id"))), null, null, null, null,
                Set.of("users.region"), null);
        return new QueryEntityModel("events", DSL.table(DSL.name("events")), bindings, List.of(join), schema);
    }

    private SqlCompositeTranslation composite(String sql) {
        final SqlStatementPlan plan = frontEnd.plan(sql);
        assertThat(plan).as(sql).isInstanceOf(SqlCompositeTranslation.class);
        return (SqlCompositeTranslation) plan;
    }

    // ------------------------------------------------------------------ joins

    @Test
    void innerJoinBecomesTheBlockOverTheJoinRelation() {
        final SqlCompositeTranslation plan = composite(
                "SELECT a.event_kind, b.amount FROM dial_usage_log a JOIN events b ON a.user_hash = b.user_id"
                        + " WHERE a.event_kind = 'chat'");
        final CompositeQuery root = plan.query();
        assertThat(root.source()).isInstanceOf(JoinRelation.class);
        final JoinRelation join = (JoinRelation) root.source();
        assertThat(join.kind()).isEqualTo(JoinKind.INNER);
        assertThat(join.left()).isInstanceOf(EntityRelation.class);
        assertThat(join.right()).isInstanceOf(EntityRelation.class);
        assertThat(join.on()).isEqualTo(new ComparisonNode(ComparisonOp.EQ,
                List.of(new FieldExpr("user_hash"), new FieldExpr("user_id"))));
        assertThat(root.block().filter()).isNotNull();
        assertThat(root.block().entity()).isNull();
        assertThat(plan.columns()).containsExactly(
                new ResultColumn("event_kind", ResultColumnKind.STRING),
                new ResultColumn("amount", ResultColumnKind.INT64));
        assertThat(plan.explicitLimit()).isFalse();
    }

    @Test
    void outerJoinKindsAreCarried() {
        assertThat(((JoinRelation) composite("SELECT a.event_id, b.rate FROM dial_usage_log a"
                + " LEFT JOIN events b ON a.user_hash = b.user_id").query().source()).kind()).isEqualTo(JoinKind.LEFT);
        assertThat(((JoinRelation) composite("SELECT a.event_id, b.rate FROM dial_usage_log a"
                + " RIGHT JOIN events b ON a.user_hash = b.user_id").query().source()).kind()).isEqualTo(JoinKind.RIGHT);
        assertThat(((JoinRelation) composite("SELECT a.event_id, b.rate FROM dial_usage_log a"
                + " FULL JOIN events b ON a.user_hash = b.user_id").query().source()).kind()).isEqualTo(JoinKind.FULL);
    }

    @Test
    void crossJoinHasNoCondition() {
        final JoinRelation join = (JoinRelation) composite(
                "SELECT count(*) FROM dial_usage_log a CROSS JOIN events b").query().source();
        assertThat(join.kind()).isEqualTo(JoinKind.CROSS);
        assertThat(join.on()).isNull();
    }

    @Test
    void joinWithoutAnEqualityIsRejected() {
        assertThatThrownBy(() -> frontEnd.plan(
                "SELECT a.event_id FROM dial_usage_log a JOIN events b ON a.total_tokens > b.amount"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("equality");
    }

    // ------------------------------------------- declared types across an operand boundary

    @Test
    void joinOperandKeepsItsColumnsDeclaredTypes() {
        // The runtime kind shares OBJECT between object and map and STRING between string, uuid and enum,
        // so a column typed back from the kind would cross the join as the wrong DSL type.
        final JoinRelation join = (JoinRelation) composite(
                "SELECT b.attributes, b.status FROM dial_usage_log a JOIN spans b ON a.user_hash = b.user_id")
                .query().source();
        assertThat(join.columns())
                .filteredOn(column -> Set.of("attributes", "status").contains(column.name()))
                .extracting(RelationColumn::name, RelationColumn::type)
                .containsExactly(
                        tuple("attributes", QueryFieldType.MAP),
                        tuple("status", QueryFieldType.ENUM));
    }

    @Test
    void mapAccessorsWorkOverJoinedOperandsMapColumn() {
        // Every operand is validated exactly as a single-entity statement is: reading one attribute of a
        // joined entity's map is the canonical query over an OTEL-shaped table, and it must not be refused
        // (with a message that misnames the column an object, at that) just because it crossed a join.
        final SqlCompositeTranslation plan = composite(
                "SELECT map_value(b.attributes, 'user.email') AS email"
                        + " FROM dial_usage_log a JOIN spans b ON a.user_hash = b.user_id");
        assertThat(plan.query().block().select()).singleElement()
                .extracting(OutputColumn::expr)
                .isEqualTo(new FnExpr("map_value", false,
                        List.of(new FieldExpr("attributes"), new ValueExpr(ValueType.STRING, "user.email"))));
        assertThat(plan.columns()).containsExactly(new ResultColumn("email", ResultColumnKind.STRING));
    }

    @Test
    void mapAccessorsWorkOverDerivedBlocksMapColumn() {
        // The outer filter over a limited block is what seals the inner one into a real derived table
        // rather than letting Calcite flatten the two into a single-entity statement.
        final SqlCompositeTranslation plan = composite(
                "SELECT map_value(t.attributes, 'user.email') AS email"
                        + " FROM (SELECT user_id, attributes FROM spans ORDER BY user_id LIMIT 5) t"
                        + " WHERE t.user_id <> ''");
        assertThat(plan.query().source()).isInstanceOf(DerivedRelation.class);
        assertThat(plan.columns()).containsExactly(new ResultColumn("email", ResultColumnKind.STRING));
    }

    @Test
    void jsonExtractOverJoinedMapColumnIsRejectedAsMap() {
        // The mirror of the case above: a map is not a JSON document on either side of a join, and the
        // rejection has to name the type the column actually has.
        assertThatThrownBy(() -> frontEnd.plan(
                "SELECT json_extract_string(b.attributes, 'k') FROM dial_usage_log a"
                        + " JOIN spans b ON a.user_hash = b.user_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("map field 'attributes'");
    }

    @Test
    void joinWithAnEqualityAndAnInequalityIsAccepted() {
        final JoinRelation join = (JoinRelation) composite("SELECT a.event_id FROM dial_usage_log a"
                + " JOIN events b ON a.user_hash = b.user_id AND a.total_tokens > b.amount").query().source();
        assertThat(join.on()).isInstanceOf(com.epam.aidial.analytics.service.query.model.LogicalNode.class);
    }

    @Test
    void usingJoinIsTheEqualityConjunction() {
        // Both entities carry no shared column name except through aliasing, so join events to itself.
        final JoinRelation join = (JoinRelation) composite(
                "SELECT a.amount FROM events a JOIN events b USING (user_id)").query().source();
        assertThat(join.on()).isEqualTo(new ComparisonNode(ComparisonOp.EQ,
                List.of(new FieldExpr("user_id"), new FieldExpr("user_id0"))));
    }

    @Test
    void selfJoinUnderAliasesScansTheEntityTwice() {
        final JoinRelation join = (JoinRelation) composite("SELECT p.event_id, c.event_id AS child FROM dial_usage_log p"
                + " JOIN dial_usage_log c ON c.core_parent_span_id = p.core_span_id").query().source();
        assertThat(((EntityRelation) join.left()).entity()).isEqualTo(DialUsageLogTestModel.ENTITY);
        assertThat(((EntityRelation) join.right()).entity()).isEqualTo(DialUsageLogTestModel.ENTITY);
    }

    @Test
    void enrichmentColumnResolvesOnTheJoinOperand() {
        final SqlCompositeTranslation plan = composite("SELECT b.\"users.region\", a.event_id FROM dial_usage_log a"
                + " JOIN events b ON a.user_hash = b.user_id");
        assertThat(plan.columns()).extracting(ResultColumn::name).containsExactly("users.region", "event_id");
    }

    @Test
    void starOverTheJoinExpandsEachOperandsWildcardFields() {
        final SqlCompositeTranslation plan = composite(
                "SELECT * FROM dial_usage_log a JOIN events b ON a.user_hash = b.user_id");
        final List<String> names = plan.columns().stream().map(ResultColumn::name).toList();
        // Heavy and enrichment columns stay out of the star on both sides.
        assertThat(names).doesNotContain(
                "dial_usage_log_payload.request_body", "dial_usage_log_payload.response_body",
                "dial_usage_log_payload.assembled_response", "users.region");
        assertThat(names).startsWith("event_id", "event_kind");
        assertThat(names).endsWith("user_id", "amount", "rate");
    }

    /**
     * The shape whose only difference from the test above is a missing alias. An unaliased derived table
     * cannot be named by a qualified star, so the expansion used to be abandoned and the star handed to
     * Calcite — which expands it against the table's full row type, heavy columns included.
     */
    @Test
    void starOverAnUnaliasedDerivedTableStillOmitsHeavyColumns() {
        for (final String sql : List.of(
                "SELECT * FROM dial_usage_log a JOIN (SELECT user_id FROM events) ON a.user_hash = user_id",
                "SELECT * FROM dial_usage_log a, (SELECT user_id FROM events) WHERE a.user_hash = user_id")) {
            final List<String> names = composite(sql).columns().stream().map(ResultColumn::name).toList();
            assertThat(names).as(sql)
                    .doesNotContain("dial_usage_log_payload.request_body", "dial_usage_log_payload.response_body", "dial_usage_log_payload.assembled_response", "users.region");
            assertThat(names).as(sql).startsWith("event_id", "event_kind").endsWith("user_id");
        }
    }

    @Test
    void qualifiedStarOverOneOperand() {
        final SqlCompositeTranslation plan = composite(
                "SELECT b.* FROM dial_usage_log a JOIN events b ON a.user_hash = b.user_id");
        assertThat(plan.columns()).extracting(ResultColumn::name).containsExactly("user_id", "amount", "rate");
    }

    @Test
    void ambiguousUnqualifiedColumnIsTheValidationFailure() {
        assertThatThrownBy(() -> frontEnd.plan(
                "SELECT user_id FROM events a JOIN events b ON a.user_id = b.user_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("validation");
    }

    @Test
    void threeWayJoinIsLeftDeep() {
        final JoinRelation outer = (JoinRelation) composite("SELECT a.event_id FROM dial_usage_log a"
                + " JOIN events b ON a.user_hash = b.user_id JOIN events c ON b.user_id = c.user_id").query().source();
        assertThat(outer.left()).isInstanceOf(JoinRelation.class);
        assertThat(outer.right()).isInstanceOf(EntityRelation.class);
    }

    @Test
    void joinOperandWithClausesIsSealedIntoTheDerivedTable() {
        final JoinRelation join = (JoinRelation) composite("SELECT a.event_id, t.n FROM dial_usage_log a"
                + " JOIN (SELECT user_id, count(*) AS n FROM events GROUP BY user_id) t ON a.user_hash = t.user_id")
                .query().source();
        assertThat(join.right()).isInstanceOf(DerivedRelation.class);
        assertThat(((DerivedRelation) join.right()).query().block().mode()).isEqualTo(QueryMode.AGGREGATE);
    }

    // ------------------------------------------------------------------ CTEs and derived tables

    @Test
    void cteWithAnAggregateBecomesTheDerivedTable() {
        final SqlCompositeTranslation plan = composite("WITH chats AS (SELECT project_id, total_tokens FROM dial_usage_log"
                + " WHERE event_kind = 'chat' LIMIT 500) SELECT project_id, sum(total_tokens) AS t FROM chats GROUP BY project_id");
        assertThat(plan.query().source()).isInstanceOf(DerivedRelation.class);
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().page()).isEqualTo(new OffsetPage(0, 500, false));
        assertThat(inner.block().filter()).isNotNull();
        assertThat(plan.query().block().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(plan.query().block().groupBy()).containsExactly("project_id");
        assertThat(plan.columns()).containsExactly(
                new ResultColumn("project_id", ResultColumnKind.STRING), new ResultColumn("t", ResultColumnKind.INT64));
    }

    @Test
    void cteJoinedToAnEntity() {
        final JoinRelation join = (JoinRelation) composite("WITH top AS (SELECT user_id FROM events ORDER BY rate DESC LIMIT 10)"
                + " SELECT l.event_id FROM dial_usage_log l JOIN top ON l.user_hash = top.user_id").query().source();
        assertThat(join.right()).isInstanceOf(DerivedRelation.class);
        assertThat(((DerivedRelation) join.right()).query().block().page()).isEqualTo(new OffsetPage(0, 10, false));
    }

    @Test
    void filterAboveTheLimitedBlockSealsIt() {
        final SqlCompositeTranslation plan = composite("SELECT project_id FROM (SELECT project_id, total_tokens"
                + " FROM dial_usage_log ORDER BY total_tokens DESC LIMIT 5) t WHERE total_tokens > 100");
        assertThat(plan.query().source()).isInstanceOf(DerivedRelation.class);
        assertThat(((DerivedRelation) plan.query().source()).query().block().page()).isEqualTo(new OffsetPage(0, 5, false));
        assertThat(plan.query().block().filter()).isNotNull();
        assertThat(plan.explicitLimit()).isFalse();
    }

    @Test
    void aggregateOverAnAggregateSealsTheInnerBlock() {
        final SqlCompositeTranslation plan = composite(
                "SELECT avg(n) FROM (SELECT project_id, count(*) AS n FROM dial_usage_log GROUP BY project_id) t");
        assertThat(plan.query().block().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(((DerivedRelation) plan.query().source()).query().block().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(plan.columns()).containsExactly(new ResultColumn("avg", ResultColumnKind.FLOAT64));
    }

    @Test
    void innerLimitAboveTheSharedMaximumIsCarriedAsWritten() {
        final SqlCompositeTranslation plan = composite(
                "SELECT count(*) FROM (SELECT event_id FROM dial_usage_log LIMIT 5000) t");
        assertThat(((DerivedRelation) plan.query().source()).query().block().page()).isEqualTo(new OffsetPage(0, 5000, false));
        assertThat(plan.query().block().page()).isNull();
    }

    @Test
    void twoOrderByLevelsSealTheInnerOne() {
        final SqlCompositeTranslation plan = composite("SELECT project_id FROM (SELECT project_id, total_tokens"
                + " FROM dial_usage_log ORDER BY total_tokens DESC LIMIT 5) t ORDER BY project_id LIMIT 3");
        assertThat(plan.query().block().page()).isEqualTo(new OffsetPage(0, 3, false));
        assertThat(plan.explicitLimit()).isTrue();
    }

    @Test
    void distinctOverTheGroupedBlockSealsIt() {
        final SqlCompositeTranslation plan = composite(
                "SELECT DISTINCT n FROM (SELECT project_id, count(*) AS n FROM dial_usage_log GROUP BY project_id) t");
        assertThat(plan.query().block().distinct()).isTrue();
        assertThat(plan.query().source()).isInstanceOf(DerivedRelation.class);
    }

    // ------------------------------------- a projection that drops its own computed sort key

    /**
     * The top-N-by-aggregate idiom, with no derived table in the statement at all: the projection above the
     * {@code ORDER BY} does not carry {@code count(*)}, so the ordered, bounded computation becomes the
     * inner block. Refused outright before this change, with a message about the ORDER BY expression.
     */
    @Test
    void topRowsByAnAggregateTheProjectionDoesNotCarrySealsTheOrderedBlock() {
        final SqlCompositeTranslation plan = composite("SELECT project_id FROM dial_usage_log"
                + " GROUP BY project_id ORDER BY count(*) DESC LIMIT 5");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(inner.block().groupBy()).containsExactly("project_id");
        assertThat(inner.block().sort()).hasSize(1);
        assertThat(inner.block().sort().getFirst().dir()).isEqualTo(SortDir.DESC);
        assertThat(inner.block().page()).isEqualTo(new OffsetPage(0, 5, false));
        // The bound the caller wrote governs the rows returned, not only the inner computation: without it
        // the outer block would take the 100-row default and answer a 5-row question with 100 rows.
        assertThat(plan.query().block().page()).isEqualTo(new OffsetPage(0, 5, false));
        assertThat(plan.explicitLimit()).isTrue();
        assertThat(plan.columns()).containsExactly(new ResultColumn("project_id", ResultColumnKind.STRING));
        // And the ordering is re-expressed on the OUTER block, over the count column the sealed block
        // exposes. Row order out of a subquery is not contractual — ClickHouse's default-on
        // query_plan_remove_redundant_sorting strips an inner ORDER BY whose enclosing block has none — so
        // without this the statement's whole point, "in descending order of that count", is not in the SQL.
        assertThat(inner.columns()).extracting(RelationColumn::name).containsExactly("project_id", "count");
        assertThat(plan.query().block().sort()).hasSize(1);
        assertThat(plan.query().block().sort().getFirst().field()).isEqualTo("count");
        assertThat(plan.query().block().sort().getFirst().dir()).isEqualTo(SortDir.DESC);
        // The direction and the null ordering are the statement's own, carried unchanged.
        assertThat(plan.query().block().sort().getFirst().nulls())
                .isEqualTo(inner.block().sort().getFirst().nulls());
    }

    @Test
    void theSameIdiomInsideAnInSubqueryTranslates() {
        final SqlCompositeTranslation plan = composite("SELECT project_id, sum(total_tokens) AS spend"
                + " FROM dial_usage_log WHERE project_id IN (SELECT project_id FROM dial_usage_log"
                + " GROUP BY project_id ORDER BY sum(total_tokens) DESC LIMIT 8) GROUP BY project_id");
        assertThat(plan.query().block().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(plan.query().block().filter()).isNotNull();
        assertThat(plan.columns()).containsExactly(
                new ResultColumn("project_id", ResultColumnKind.STRING),
                new ResultColumn("spend", ResultColumnKind.INT64));
    }

    @Test
    void derivedTableWhoseOuterProjectionDropsTheSortAliasTranslates() {
        final SqlCompositeTranslation plan = composite("SELECT d FROM (SELECT project_id AS d, count(*) AS n"
                + " FROM dial_usage_log GROUP BY project_id ORDER BY n DESC LIMIT 5) t");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().sort()).hasSize(1);
        assertThat(inner.block().page()).isEqualTo(new OffsetPage(0, 5, false));
        assertThat(plan.columns()).containsExactly(new ResultColumn("d", ResultColumnKind.STRING));
        // The alias the outer projection dropped is still a column of the sealed block, so the outer
        // ORDER BY names it — the ordering the client wrote is not left inside the derived table.
        assertThat(plan.query().block().sort()).hasSize(1);
        assertThat(plan.query().block().sort().getFirst().field()).isEqualTo("n");
        assertThat(plan.query().block().sort().getFirst().dir()).isEqualTo(SortDir.DESC);
    }

    /** The same failure in row mode, with no aggregate involved: a computed key the projection drops. */
    @Test
    void computedRowModeSortKeyTheProjectionDoesNotCarrySealsTheOrderedBlock() {
        final SqlCompositeTranslation plan = composite(
                "SELECT event_id FROM dial_usage_log ORDER BY length(event_kind) LIMIT 5");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().mode()).isEqualTo(QueryMode.ROW);
        assertThat(inner.block().sort()).hasSize(1);
        assertThat(inner.block().page()).isEqualTo(new OffsetPage(0, 5, false));
        assertThat(plan.query().block().page()).isEqualTo(new OffsetPage(0, 5, false));
        // The sealed block projects the computed key alongside the projection's own column, which is what
        // lets the outer block name it: "returns five rows ordered by that length" is then in the SQL.
        assertThat(inner.columns()).extracting(RelationColumn::name).containsExactly("event_id", "length");
        assertThat(plan.query().block().sort()).hasSize(1);
        assertThat(plan.query().block().sort().getFirst().field()).isEqualTo("length");
    }

    /**
     * The no-{@code LIMIT} form is the sharp case for the outer ordering: the sealed block carries an
     * {@code ORDER BY} with no bound attached, which is exactly what ClickHouse's
     * {@code query_plan_remove_redundant_sorting} removes when the enclosing block does not order.
     */
    @Test
    void theHavingFormOfTheSameShapeTranslates() {
        final SqlCompositeTranslation plan = composite("SELECT project_id FROM dial_usage_log GROUP BY project_id"
                + " HAVING count(*) > 1 ORDER BY count(*) DESC");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().having()).isNotNull();
        assertThat(inner.block().sort()).hasSize(1);
        // No LIMIT was written, so none is carried out and the outer block takes the ordinary default.
        assertThat(plan.query().block().page()).isNull();
        assertThat(plan.explicitLimit()).isFalse();
        assertThat(plan.query().block().sort()).hasSize(1);
        assertThat(plan.query().block().sort().getFirst().field()).isEqualTo("count");
        assertThat(plan.query().block().sort().getFirst().dir()).isEqualTo(SortDir.DESC);
    }

    /** Several keys, and a mixed direction, all travel out in the statement's own order. */
    @Test
    void everySortKeyIsReExpressedOnTheOuterBlock() {
        final SqlCompositeTranslation plan = composite("SELECT event_id FROM dial_usage_log"
                + " ORDER BY length(event_kind) DESC, project_id ASC LIMIT 5");
        assertThat(plan.query().block().sort()).hasSize(2);
        assertThat(plan.query().block().sort().getFirst().dir()).isEqualTo(SortDir.DESC);
        assertThat(plan.query().block().sort().get(1).dir()).isEqualTo(SortDir.ASC);
        // Both keys name columns of the sealed block, the second one the plain column it always was.
        assertThat(plan.query().block().sort().get(1).field()).isEqualTo("project_id");
        assertThat(((DerivedRelation) plan.query().source()).query().columns())
                .extracting(RelationColumn::name)
                .contains(plan.query().block().sort().getFirst().field(), "project_id");
    }

    /**
     * The offset is applied <b>once</b>, inside, where the ordering it skips into is. Carrying it outward as
     * well would skip a second time and return rows 20-25 of the ordering for a statement asking for 10-15.
     */
    @Test
    void theOffsetStaysInsideTheSealedBlock() {
        final SqlCompositeTranslation plan = composite("SELECT event_id FROM dial_usage_log"
                + " ORDER BY length(event_kind) OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY");
        assertThat(((DerivedRelation) plan.query().source()).query().block().page())
                .isEqualTo(new OffsetPage(10, 5, false));
        assertThat(plan.query().block().page()).isEqualTo(new OffsetPage(0, 5, false));
        // The ordering still travels — only the offset stays behind.
        assertThat(plan.query().block().sort()).hasSize(1);
    }

    /**
     * The other side of naming a sealed column: if the outer projection aliases a <b>different</b>
     * expression to the name the sort key would use, the DSL would resolve the sort against that alias and
     * order by the wrong expression, so the statement fails closed instead.
     */
    @Test
    void anOutputShadowingTheSortColumnIsRefused() {
        assertThatThrownBy(() -> frontEnd.plan("SELECT length(event_kind) AS request_time FROM dial_usage_log"
                + " ORDER BY dial_usage_log.request_time DESC LIMIT 5"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("names a different expression than the ORDER BY column 'request_time'");
    }

    @Test
    void nestedSortKeyHasNoStructuredQueryDocument() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT project_id FROM dial_usage_log GROUP BY project_id ORDER BY count(*) DESC LIMIT 5"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT);
    }

    /**
     * The regression guard for the fix's narrowness: a sort key that is a plain <b>column</b> resolves
     * against the source's columns, so it stays a single block and keeps its DSL document. Sealing it would
     * make an ordinary statement composite for nothing.
     */
    @Test
    void plainColumnSortKeyTheProjectionDropsStaysOneBlock() {
        assertThat(frontEnd.plan("SELECT event_id FROM dial_usage_log ORDER BY request_time DESC LIMIT 5"))
                .isInstanceOf(SqlTranslation.class);
        assertThat(frontEnd.plan("SELECT count(*) FROM dial_usage_log GROUP BY project_id ORDER BY project_id"))
                .isInstanceOf(SqlTranslation.class);
    }

    /** And a projection that keeps its computed sort key resolves through the select list, as before. */
    @Test
    void projectionCarryingItsComputedSortKeyStaysOneBlock() {
        assertThat(frontEnd.plan("SELECT project_id AS d, count(*) AS n FROM dial_usage_log"
                + " GROUP BY project_id ORDER BY n DESC LIMIT 5")).isInstanceOf(SqlTranslation.class);
    }

    @Test
    void theCarriedLimitIsStillGovernedByTheSharedMaximum() {
        // 500 is carried out and honoured; 1500 is refused exactly as it is for a statement with no computed
        // sort key, rather than being clamped or silently moved inside where governance cannot see it.
        assertThat(composite("SELECT project_id FROM dial_usage_log GROUP BY project_id"
                + " ORDER BY count(*) DESC LIMIT 500").query().block().page())
                .isEqualTo(new OffsetPage(0, 500, false));
        final SqlStatementPlan plan = frontEnd.plan("SELECT project_id FROM dial_usage_log GROUP BY project_id"
                + " ORDER BY count(*) DESC LIMIT 1500");
        assertThat(((SqlCompositeTranslation) plan).query().block().page())
                .isEqualTo(new OffsetPage(0, 1500, false));
        // This class translates and does not render, so the page the plan carries is all it can assert.
        // Governance runs at render time, and the refusal that page produces is asserted where the
        // builder harness lives: CompositeQueryRenderTest#carriedLimitAboveTheMaximumIsRefused.
    }

    // ------------------------------------ a projection that drops its own computed group key

    /**
     * The shape the {@code array_join} tagging idiom lands on: the inner block groups by a computed value and
     * the enclosing statement averages the per-group aggregate without projecting the key, so Calcite trims
     * the key out of the inner projection. Refused outright before this change — with
     * {@code a computed GROUP BY expression must also appear in the SELECT list}, which the client could not
     * act on, because the key <em>was</em> in the inner SELECT list and the trim took it out.
     */
    @Test
    void aggregateOverGroupedBlockDroppingTheComputedKeyTranslates() {
        final SqlCompositeTranslation plan = composite("SELECT avg(c) AS avg_cost FROM (SELECT"
                + " ARRAY_JOIN(SPLIT_STRING(event_kind, ',')) AS tag, sum(total_price) AS c"
                + " FROM dial_usage_log GROUP BY tag) t");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().mode()).isEqualTo(QueryMode.AGGREGATE);
        // The key stays in the sealed block's own select list, which is the only place the DSL can name it.
        assertThat(inner.block().groupBy()).containsExactly("tag");
        assertThat(inner.columns()).extracting(RelationColumn::name).containsExactly("tag", "c");
        assertThat(plan.query().block().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(plan.columns()).containsExactly(new ResultColumn("avg_cost", ResultColumnKind.FLOAT64));
    }

    /** Nothing about it is {@code array_join}'s: any computed key the projection drops seals the same way. */
    @Test
    void anOrdinaryComputedGroupKeyTheProjectionDropsSealsTheGroupedBlock() {
        final SqlCompositeTranslation plan = composite("SELECT avg(c) AS a FROM (SELECT lower(event_kind) AS k,"
                + " sum(total_price) AS c FROM dial_usage_log GROUP BY k) t");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().groupBy()).containsExactly("k");
        assertThat(inner.columns()).extracting(RelationColumn::name).containsExactly("k", "c");
    }

    /**
     * With no derived table in the statement at all: {@code GROUP BY <expression>} whose value the statement
     * never projects. The key has no alias to be named by, so the sealed block names it after the expression
     * the same way an unnamed select item is named.
     */
    @Test
    void groupByAnExpressionTheStatementNeverProjectsSealsTheGroupedBlock() {
        final SqlCompositeTranslation plan =
                composite("SELECT count(*) AS n FROM dial_usage_log GROUP BY lower(event_kind)");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().groupBy()).containsExactly("lower");
        assertThat(inner.columns()).extracting(RelationColumn::name).containsExactly("lower", "n");
        assertThat(plan.columns()).containsExactly(new ResultColumn("n", ResultColumnKind.INT64));
    }

    /**
     * The grouped block's own {@code ORDER BY} and {@code LIMIT} travel with the seal, exactly as they do on
     * the dropped-sort-key path: sealing through the plain path instead would leave the bound inside the
     * derived table and let governance apply its default to the projection.
     */
    @Test
    void theOrderedBoundedFormCarriesItsRowBoundOut() {
        final SqlCompositeTranslation plan = composite("SELECT c FROM (SELECT lower(event_kind) AS k,"
                + " sum(total_price) AS c FROM dial_usage_log GROUP BY k ORDER BY c DESC LIMIT 5) t");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().groupBy()).containsExactly("k");
        assertThat(inner.block().page()).isEqualTo(new OffsetPage(0, 5, false));
        assertThat(plan.query().block().page()).isEqualTo(new OffsetPage(0, 5, false));
        assertThat(plan.query().block().sort()).hasSize(1);
        assertThat(plan.query().block().sort().getFirst().field()).isEqualTo("c");
        assertThat(plan.explicitLimit()).isTrue();
    }

    /**
     * A key that is a bare column keeps using the field-name branch, which names the source column: the
     * inner block of {@code SELECT avg(n) FROM (… GROUP BY project_id)} needs no extra level, and a
     * projection that keeps its computed key stays one block entirely.
     */
    @Test
    void plainColumnKeyAndKeptComputedKeyGainNoLevel() {
        final CompositeQuery inner = ((DerivedRelation) composite("SELECT avg(n) FROM (SELECT project_id,"
                + " count(*) AS n FROM dial_usage_log GROUP BY project_id) t").query().source()).query();
        assertThat(inner.block().groupBy()).containsExactly("project_id");
        assertThat(inner.columns()).extracting(RelationColumn::name).containsExactly("n");
        assertThat(frontEnd.plan("SELECT lower(event_kind) AS k, count(*) AS n FROM dial_usage_log GROUP BY k"))
                .isInstanceOf(SqlTranslation.class);
    }

    /**
     * A {@code GROUP BY} whose projection carries no aggregate at all. Calcite lowers it to the same
     * keys-only {@code Aggregate} as {@code SELECT DISTINCT}, so before the deduplication key was nested it
     * became {@code SELECT DISTINCT 1} — one row for a statement that has one row per distinct key. Wrong
     * rows rather than an error, which is why it is pinned on the plan and on the rendered SQL both.
     */
    @Test
    void groupedStatementWithNoAggregateInItsProjectionSealsTheDeduplication() {
        final SqlCompositeTranslation plan =
                composite("SELECT 1 AS one FROM dial_usage_log GROUP BY lower(event_kind)");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().distinct()).isTrue();
        assertThat(inner.columns()).extracting(RelationColumn::name).containsExactly("lower");
        assertThat(plan.query().block().distinct()).isFalse();
        assertThat(plan.columns()).containsExactly(new ResultColumn("one", ResultColumnKind.INT32));
    }

    /** The client-written half of the same shape: a DISTINCT over a pair, projected down to one column. */
    @Test
    void distinctKeyTheEnclosingProjectionDropsSealsTheDeduplication() {
        final SqlCompositeTranslation plan = composite(
                "SELECT project_id FROM (SELECT DISTINCT project_id, event_kind FROM dial_usage_log) x");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().distinct()).isTrue();
        assertThat(inner.columns()).extracting(RelationColumn::name).containsExactly("project_id", "event_kind");
        // Deduplicated by the pair and then projected — not re-deduplicated by project_id alone.
        assertThat(plan.query().block().distinct()).isFalse();
    }

    /** And the ordinary SELECT DISTINCT, whose projection IS its key list, gains no level. */
    @Test
    void ordinarySelectDistinctStaysOneBlock() {
        assertThat(frontEnd.plan("SELECT DISTINCT project_id, event_kind FROM dial_usage_log"))
                .isInstanceOf(SqlTranslation.class);
        assertThat(frontEnd.plan("SELECT DISTINCT lower(event_kind) AS k FROM dial_usage_log"))
                .isInstanceOf(SqlTranslation.class);
    }

    /**
     * Keeping every deduplication key is not enough to make a projection safe over one. A window function
     * is evaluated in the select stage that feeds {@code DISTINCT}, so folded into one block it numbers
     * every source row and the deduplication then finds every triple already unique: one row per source
     * row, where the statement asks for one per distinct pair. Wrong rows, not an error.
     */
    @Test
    void windowAboveDeduplicationSealsIt() {
        final SqlCompositeTranslation plan = composite("SELECT project_id, event_kind,"
                + " row_number() OVER () AS rn FROM (SELECT DISTINCT project_id, event_kind"
                + " FROM dial_usage_log) x");
        final CompositeQuery inner = ((DerivedRelation) plan.query().source()).query();
        assertThat(inner.block().distinct()).isTrue();
        assertThat(inner.columns()).extracting(RelationColumn::name).containsExactly("project_id", "event_kind");
        // The window is on the OUTER block, over the deduplicated rows.
        assertThat(plan.query().block().distinct()).isFalse();
        assertThat(plan.columns()).extracting(ResultColumn::name).containsExactly("project_id", "event_kind", "rn");
    }

    /** The row-expanding half of the same rule: deduplicate the pairs first, expand afterwards. */
    @Test
    void expansionAboveDeduplicationSealsIt() {
        final SqlCompositeTranslation plan = composite("SELECT project_id,"
                + " ARRAY_JOIN(SPLIT_STRING(event_kind, ',')) AS part FROM (SELECT DISTINCT project_id,"
                + " event_kind FROM dial_usage_log) x");
        assertThat(((DerivedRelation) plan.query().source()).query().block().distinct()).isTrue();
        assertThat(plan.query().block().distinct()).isFalse();
    }

    /**
     * A key the deduplication is itself over is projectable without a further level, which is what the skip
     * for a passed-through output buys. {@code ARRAY_JOIN} shows it plainly, staying a single block. A
     * window already forces one level of its own wherever it feeds an aggregate ({@code aggregateCtx}
     * seals a windowed block, since a window is computed after aggregation) — the point here is that
     * projecting the key does not add a SECOND one.
     */
    @Test
    void deduplicationOverItsOwnWindowOrExpansionIsNotNestedTwice() {
        assertThat(frontEnd.plan("SELECT DISTINCT ARRAY_JOIN(execution_path) AS step FROM dial_usage_log"))
                .isInstanceOf(SqlTranslation.class);
        final SqlCompositeTranslation plan =
                composite("SELECT DISTINCT row_number() OVER () AS rn FROM dial_usage_log");
        assertThat(plan.query().block().distinct()).isTrue();
        assertThat(((DerivedRelation) plan.query().source()).query().source())
                .isInstanceOf(EntityRelation.class);
    }

    /**
     * Every reason this translator seals has to be in the refusal, or a statement that wrote no nesting is
     * told about a derived table it never wrote. These four project every key they group by and have no
     * join, derived table, subquery or ORDER BY, so every key-shaped clause of the message is false of
     * them and only the window/expansion clause can carry them. The last two are the mirror-image case —
     * a DISTINCT over a window, sealed by aggregateCtx rather than by projectCtx — which is why that
     * clause names the separation and not a direction.
     */
    @Test
    void refusalNamesTheSeparationWhenThatIsTheSealReason() {
        for (final String sql : List.of(
                "SELECT project_id, row_number() OVER () AS rn FROM dial_usage_log GROUP BY project_id",
                "SELECT project_id, ARRAY_JOIN(SPLIT_STRING(project_id, ',')) AS part"
                        + " FROM dial_usage_log GROUP BY project_id",
                "SELECT DISTINCT row_number() OVER () AS rn FROM dial_usage_log",
                "SELECT DISTINCT lower(event_kind) AS k, row_number() OVER () AS rn FROM dial_usage_log")) {
            assertThat(composite(sql)).isNotNull();
            assertThatThrownBy(() -> frontEnd.translate(sql))
                    .as(sql)
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessage(RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT)
                    .hasMessageContaining("separate a window function or a row expansion from a DISTINCT");
        }
    }

    /**
     * The refusal names the two key kinds WITHOUT the word "computed", because a deduplication key is
     * nested whether it is computed or a bare column. This statement has no join, no derived table, no
     * subquery, no ORDER BY and a bare-column key: every clause of the enumeration has to fit it, or the
     * caller is told about something they never wrote.
     */
    @Test
    void refusalFitsPlainColumnDeduplicationKey() {
        assertThat(composite("SELECT 1 AS one FROM dial_usage_log GROUP BY project_id")).isNotNull();
        assertThatThrownBy(() -> frontEnd.translate("SELECT 1 AS one FROM dial_usage_log GROUP BY project_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT)
                .hasMessageContaining("ORDER BY, GROUP BY or DISTINCT key")
                .hasMessageNotContaining("computed");
    }

    @Test
    void derivedColumnKindsSurviveTheDerivedTable() {
        // avg is FLOAT64, which the DSL type vocabulary would flatten to decimal: the exact kind must survive.
        // The LIMIT forces the seal: without it the outer filter would simply fold into a HAVING, one block.
        final SqlCompositeTranslation plan = composite("SELECT m FROM (SELECT project_id, avg(total_tokens) AS m"
                + " FROM dial_usage_log GROUP BY project_id LIMIT 50) t WHERE m > 1");
        assertThat(plan.query().source()).isInstanceOf(DerivedRelation.class);
        assertThat(plan.columns()).containsExactly(new ResultColumn("m", ResultColumnKind.FLOAT64));
    }

    // ------------------------------------------------------------------ subqueries

    @Test
    void inSubqueryIsThePredicateOverTheEntityBlock() {
        final SqlCompositeTranslation plan = composite("SELECT event_id FROM dial_usage_log"
                + " WHERE user_hash IN (SELECT user_id FROM events WHERE rate > 4)");
        assertThat(plan.query().source()).isInstanceOf(EntityRelation.class);
        assertThat(plan.query().block().entity()).isEqualTo(DialUsageLogTestModel.ENTITY);
        assertThat(plan.query().block().filter()).isInstanceOf(SubqueryNode.class);
        final SubqueryNode node = (SubqueryNode) plan.query().block().filter();
        assertThat(node.kind()).isEqualTo(SubqueryNode.SubqueryKind.IN);
        assertThat(node.operands()).containsExactly(new FieldExpr("user_hash"));
        assertThat(node.subquery().block().entity()).isEqualTo("events");
        assertThat(node.subquery().block().filter()).isNotNull();
    }

    @Test
    void notInAndNotExistsCarryTheNegatedKinds() {
        assertThat(((SubqueryNode) composite("SELECT event_id FROM dial_usage_log"
                + " WHERE user_hash NOT IN (SELECT user_id FROM events)").query().block().filter()).kind())
                .isEqualTo(SubqueryNode.SubqueryKind.NOT_IN);
        assertThat(((SubqueryNode) composite("SELECT count(*) FROM dial_usage_log"
                + " WHERE NOT EXISTS (SELECT 1 FROM events WHERE rate > 4)").query().block().filter()).kind())
                .isEqualTo(SubqueryNode.SubqueryKind.NOT_EXISTS);
        assertThat(((SubqueryNode) composite("SELECT count(*) FROM dial_usage_log"
                + " WHERE EXISTS (SELECT 1 FROM events WHERE rate > 4)").query().block().filter()).kind())
                .isEqualTo(SubqueryNode.SubqueryKind.EXISTS);
    }

    @Test
    void scalarSubqueryAsTheComparisonOperand() {
        final SqlCompositeTranslation plan = composite("SELECT event_id, total_tokens FROM dial_usage_log"
                + " WHERE total_tokens > (SELECT avg(total_tokens) FROM dial_usage_log)");
        assertThat(plan.query().block().filter()).isInstanceOf(ComparisonNode.class);
        final ComparisonNode comparison = (ComparisonNode) plan.query().block().filter();
        assertThat(comparison.op()).isEqualTo(ComparisonOp.GT);
        assertThat(comparison.args().get(1)).isInstanceOf(ScalarSubqueryExpr.class);
    }

    @Test
    void scalarSubqueryProjectedIsTypedByItsColumn() {
        final SqlCompositeTranslation plan = composite(
                "SELECT project_id, (SELECT count(*) FROM events) AS rates FROM dial_usage_log");
        assertThat(plan.columns()).containsExactly(
                new ResultColumn("project_id", ResultColumnKind.STRING), new ResultColumn("rates", ResultColumnKind.INT64));
    }

    @Test
    void correlatedSubqueryIsRejected() {
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_id FROM dial_usage_log l"
                + " WHERE EXISTS (SELECT 1 FROM events e WHERE e.user_id = l.user_hash)"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("correlated");
    }

    @Test
    void scalarSubqueryAsTheFunctionArgumentIsRejected() {
        assertThatThrownBy(() -> frontEnd.plan("SELECT abs((SELECT count(*) FROM events)) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("argument");
    }

    @Test
    void unionInsideTheSubqueryIsAccepted() {
        final SqlCompositeTranslation plan = composite("SELECT event_id FROM dial_usage_log WHERE user_hash IN"
                + " (SELECT user_id FROM events UNION ALL SELECT user_id FROM events)");
        assertThat(plan.query().source()).isInstanceOf(EntityRelation.class);
        // The set operation lives inside the IN predicate's own plan, not in the outer block's source.
        assertThat(plan.query().block().filter()).isNotNull();
    }

    @Test
    void starInsideTheSubqueryExpandsAtTheEntity() {
        // A star inside a subquery is expanded where the entity is named, so the subquery never projects heavy
        // or enrichment columns the caller did not ask for. An EXISTS subquery is re-projected to one literal —
        // Calcite drops its projection altogether and would otherwise hand over the full row type.
        final SqlCompositeTranslation exists = composite(
                "SELECT count(*) FROM dial_usage_log WHERE EXISTS (SELECT * FROM events WHERE rate > 4)");
        final SubqueryNode node = (SubqueryNode) exists.query().block().filter();
        assertThat(node.subquery().columns()).extracting(RelationColumn::name).containsExactly("one");
        assertThat(node.subquery().block().filter()).isNotNull();
        final SqlCompositeTranslation in = composite(
                "SELECT count(*) FROM dial_usage_log WHERE user_hash IN (SELECT user_id FROM (SELECT * FROM events) e)");
        assertThat(((SubqueryNode) in.query().block().filter()).subquery().columns())
                .extracting(RelationColumn::name).containsExactly("user_id");
        final SqlCompositeTranslation scalar = composite(
                "SELECT (SELECT count(*) FROM (SELECT * FROM dial_usage_log LIMIT 10) t) AS n FROM events");
        final ScalarSubqueryExpr sub = (ScalarSubqueryExpr) scalar.query().block().select().getFirst().expr();
        final CompositeQuery inner = ((DerivedRelation) sub.subquery().source()).query();
        assertThat(inner.columns()).extracting(RelationColumn::name).doesNotContain("dial_usage_log_payload.request_body", "dial_usage_log_payload.assembled_response");
    }

    @Test
    void compositeStatementsHaveNoDslDocument() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT a.event_id FROM dial_usage_log a JOIN events b ON a.user_hash = b.user_id"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT);
    }

    // ------------------------------------------------------------------ CASE

    @Test
    void searchedCaseWithSeveralBranches() {
        final SqlTranslation translation = frontEnd.translate("SELECT CASE WHEN total_tokens > 1000 THEN 'large'"
                + " WHEN total_tokens > 100 THEN 'medium' ELSE 'small' END AS size, count(*) FROM dial_usage_log GROUP BY size");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(translation.query().groupBy()).containsExactly("size");
        final CaseExpr caseExpr = (CaseExpr) translation.query().select().getFirst().expr();
        assertThat(caseExpr.when()).hasSize(2);
        assertThat(caseExpr.when().getFirst().when()).isEqualTo(new ComparisonNode(ComparisonOp.GT,
                List.of(new FieldExpr("total_tokens"), new ValueExpr(ValueType.LONG, "1000"))));
        assertThat(caseExpr.otherwise()).isEqualTo(new ValueExpr(ValueType.STRING, "small"));
        assertThat(translation.columns().getFirst()).isEqualTo(new ResultColumn("size", ResultColumnKind.STRING));
    }

    @Test
    void raggedCharacterLiteralsKeepTheirOwnLength() {
        // Calcite's default type system unifies CHAR(3) and CHAR(6) as CHAR(6) and pads the shorter literal; the
        // front end's type system unifies them as VARCHAR instead, so no literal ever grows a trailing blank.
        final SqlTranslation in = frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE deployment IN ('gpt', 'claude')");
        assertThat(in.query().filter()).isEqualTo(new ComparisonNode(ComparisonOp.IN, List.of(new FieldExpr("deployment"),
                new com.epam.aidial.analytics.service.query.model.ArrayExpr(List.of(
                        new ValueExpr(ValueType.STRING, "gpt"), new ValueExpr(ValueType.STRING, "claude"))))));
        final SqlTranslation caseTranslation = frontEnd.translate(
                "SELECT CASE WHEN success THEN 'ok' ELSE 'failed' END AS outcome FROM dial_usage_log");
        final CaseExpr caseExpr = (CaseExpr) caseTranslation.query().select().getFirst().expr();
        assertThat(caseExpr.when().getFirst().then()).isEqualTo(new ValueExpr(ValueType.STRING, "ok"));
    }

    @Test
    void simpleCaseIsTheSearchedCase() {
        final SqlTranslation simple = frontEnd.translate(
                "SELECT CASE event_kind WHEN 'chat' THEN 1 ELSE 0 END AS is_chat FROM dial_usage_log");
        final SqlTranslation searched = frontEnd.translate(
                "SELECT CASE WHEN event_kind = 'chat' THEN 1 ELSE 0 END AS is_chat FROM dial_usage_log");
        assertThat(simple.query()).isEqualTo(searched.query());
    }

    @Test
    void caseWithoutElseHasNoElse() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT CASE WHEN success THEN total_tokens END AS ok_tokens FROM dial_usage_log");
        final CaseExpr caseExpr = (CaseExpr) translation.query().select().getFirst().expr();
        assertThat(caseExpr.otherwise()).isNull();
        assertThat(translation.columns()).containsExactly(new ResultColumn("ok_tokens", ResultColumnKind.INT64));
    }

    @Test
    void caseWidensMixedNumericBranches() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT CASE WHEN success THEN total_tokens ELSE total_price END AS v FROM dial_usage_log");
        assertThat(translation.columns()).containsExactly(new ResultColumn("v", ResultColumnKind.DECIMAL));
    }

    @Test
    void caseWithMismatchedBranchesIsRejected() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT CASE WHEN success THEN 'yes' ELSE 1 END FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void caseIsAcceptedAsTheFilterOperand() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE CASE WHEN success THEN 'a' ELSE 'b' END = 'a'");
        assertThat(((ComparisonNode) translation.query().filter()).args().getFirst()).isInstanceOf(CaseExpr.class);
    }

    // ------------------------------------------------------------------ windows

    @Test
    void rowNumberWithPartitionAndOrder() {
        final SqlTranslation translation = frontEnd.translate("SELECT project_id, request_time,"
                + " row_number() OVER (PARTITION BY project_id ORDER BY request_time) AS rn FROM dial_usage_log");
        final WindowExpr window = (WindowExpr) translation.query().select().get(2).expr();
        assertThat(window.fn()).isEqualTo(new FnExpr("row_number", false, List.of()));
        assertThat(window.partitionBy()).containsExactly(new FieldExpr("project_id"));
        assertThat(window.orderBy()).singleElement().satisfies(order -> {
            assertThat(order.expr()).isEqualTo(new FieldExpr("request_time"));
            assertThat(order.dir()).isEqualTo(SortDir.ASC);
        });
        assertThat(window.frame()).isNull();
        assertThat(translation.columns().get(2)).isEqualTo(new ResultColumn("rn", ResultColumnKind.INT64));
    }

    @Test
    void runningSumCarriesItsFrame() {
        final SqlTranslation translation = frontEnd.translate("SELECT request_time, sum(total_tokens) OVER"
                + " (ORDER BY request_time ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running FROM dial_usage_log");
        final WindowExpr window = (WindowExpr) translation.query().select().get(1).expr();
        assertThat(window.fn()).isEqualTo(new FnExpr("sum", false, List.of(new FieldExpr("total_tokens"))));
        assertThat(window.frame()).isNotNull();
        assertThat(window.frame().unit()).isEqualTo(FrameUnit.ROWS);
        assertThat(window.frame().start().kind()).isEqualTo(FrameBoundKind.UNBOUNDED_PRECEDING);
        assertThat(window.frame().end().kind()).isEqualTo(FrameBoundKind.CURRENT_ROW);
        assertThat(translation.columns().get(1)).isEqualTo(new ResultColumn("running", ResultColumnKind.INT64));
    }

    @Test
    void offsetFrameBoundsCarryTheirOffsets() {
        final SqlTranslation translation = frontEnd.translate("SELECT avg(total_tokens) OVER"
                + " (ORDER BY request_time ROWS BETWEEN 2 PRECEDING AND 1 FOLLOWING) AS smoothed FROM dial_usage_log");
        final WindowExpr window = (WindowExpr) translation.query().select().getFirst().expr();
        assertThat(window.frame().start().kind()).isEqualTo(FrameBoundKind.PRECEDING);
        assertThat(window.frame().start().offset()).isEqualTo(2L);
        assertThat(window.frame().end().kind()).isEqualTo(FrameBoundKind.FOLLOWING);
        assertThat(window.frame().end().offset()).isEqualTo(1L);
    }

    @Test
    void defaultFrameIsAbsent() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT sum(total_tokens) OVER (ORDER BY request_time) AS running FROM dial_usage_log");
        assertThat(((WindowExpr) translation.query().select().getFirst().expr()).frame()).isNull();
        final SqlTranslation unordered = frontEnd.translate(
                "SELECT sum(total_tokens) OVER (PARTITION BY project_id) AS total FROM dial_usage_log");
        assertThat(((WindowExpr) unordered.query().select().getFirst().expr()).frame()).isNull();
    }

    @Test
    void rankOverAnAggregateInAnAggregateQuery() {
        final SqlTranslation translation = frontEnd.translate("SELECT project_id, sum(total_tokens) AS t,"
                + " rank() OVER (ORDER BY sum(total_tokens) DESC) AS r FROM dial_usage_log GROUP BY project_id");
        assertThat(translation.query().mode()).isEqualTo(QueryMode.AGGREGATE);
        final WindowExpr window = (WindowExpr) translation.query().select().get(2).expr();
        assertThat(window.orderBy()).singleElement().satisfies(order -> {
            assertThat(order.expr()).isEqualTo(new FnExpr("sum", false, List.of(new FieldExpr("total_tokens"))));
            assertThat(order.dir()).isEqualTo(SortDir.DESC);
        });
    }

    @Test
    void windowInWhereIsRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_id FROM dial_usage_log WHERE row_number() OVER (ORDER BY request_time) < 10"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void unsupportedWindowFunctionIsRejectedByName() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT lag(total_tokens) OVER (ORDER BY request_time) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("LAG");
    }

    @Test
    void distinctInsideTheWindowIsRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT count(DISTINCT project_id) OVER () FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("DISTINCT");
    }

    @Test
    void windowOverTheDerivedTableFilteredOnItsAlias() {
        // The accepted spelling of "the first 10 per partition": a derived block computes the window, the outer
        // block filters on its alias.
        final SqlCompositeTranslation plan = composite("SELECT project_id, rn FROM (SELECT project_id,"
                + " row_number() OVER (PARTITION BY project_id ORDER BY request_time) AS rn FROM dial_usage_log) t"
                + " WHERE rn <= 10");
        assertThat(plan.query().source()).isInstanceOf(DerivedRelation.class);
        assertThat(plan.columns()).containsExactly(
                new ResultColumn("project_id", ResultColumnKind.STRING), new ResultColumn("rn", ResultColumnKind.INT64));
    }
}
