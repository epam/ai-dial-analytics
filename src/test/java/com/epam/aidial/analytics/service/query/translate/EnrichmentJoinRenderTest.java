package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Render-only coverage of source+enrichment flattening: a query that references an enrichment column
 * emits a single {@code LEFT JOIN} on the shared grain key, while a query confined to the source emits
 * no join. Built offline on the connection-less CLICKHOUSE render context with a hand-built
 * {@link QueryEntityModel} (source {@code events} + enrichment {@code users}).
 */
class EnrichmentJoinRenderTest {

    /** The tail of every key sub-query keyed by {@code amount > 10 and user_id = 'u-1'} alone. */
    private static final String SOURCE_CONJUNCTS =
            "from \"events\" where (\"events\".\"amount\" > 10 and \"events\".\"user_id\" = 'u-1'))";

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    /**
     * Unshaped: this class pins the keying every statement shares. The single-entity read path's shaping on top of
     * it — an allow-listed enrichment conjunct also copied into its derived table, a final-read count joining only
     * what its filter reads — is {@link ReadShapingRenderTest}'s.
     */
    private final StructuredQueryBuilder builder = newBuilder().withoutReadShaping();

    private static StructuredQueryBuilder newBuilder() {
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
    }

    private QueryEntityModel model() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "user_id", "events", "user_id", QueryFieldType.STRING);
        bind(bindings, "amount", "events", "amount", QueryFieldType.LONG);
        // Enrichment column is published under its table-qualified flat name "users.region"; the backing
        // jOOQ field stays the two-part physical reference users.region.
        bind(bindings, "users.region", "users", "region", QueryFieldType.STRING);

        final Field<Object> sourceKey = DSL.field(DSL.name("events", "user_id"));
        final Field<Object> grainKey = DSL.field(DSL.name("users", "user_id"));
        // The keyed form, as CatalogQueryService builds it: the join knows both key fields, so a
        // source-only filter can restrict the enrichment side to the keys it selects.
        final EnrichmentJoin join =
                EnrichmentJoin.keyed(DSL.table(DSL.name("users")), sourceKey, grainKey, null, null, Set.of("users.region"), null);

        return new QueryEntityModel(
                "events",
                DSL.table(DSL.name("events")),
                bindings,
                List.of(join),
                List.of(new QuerySchemaFieldDto("users.region", QueryFieldType.STRING, null, null, "region", null, null, null, false, false)));
    }

    private static void bind(
            Map<String, QueryFieldBinding> bindings, String flatName, String table, String col, QueryFieldType type) {
        bindings.put(flatName, new QueryFieldBinding(flatName, DSL.field(DSL.name(table, col), JooqTypes.of(type)), type));
    }

    private String render(StructuredQuery query) {
        return builder.build(ctx, model(), query).getSQL(ParamType.INLINED);
    }

    private static StructuredQuery rowSelecting(String... fields) {
        return new StructuredQuery(
                "events", null, QueryMode.ROW, false,
                List.of(java.util.Arrays.stream(fields).map(f -> new OutputColumn(new FieldExpr(f), null)).toArray(OutputColumn[]::new)),
                null, null, null, null);
    }

    /** {@link #model()} with an {@code upsert_by_key} source, as the shipped sources are. */
    private QueryEntityModel collapsingModel() {
        final QueryEntityModel m = model();
        return new QueryEntityModel(m.entity(), m.table(), m.bindings(), m.enrichments(), m.baseSchema(), m.tagOrder(),
                m.partitionLocal(), true);
    }

    @Test
    void finalReadRendersFinalOnTheSourceAndTheKeyedEnrichmentButNotOnTheKeySubquery() {
        final FilterNode filter = new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("user_id"), new ValueExpr(ValueType.STRING, "u-1")));
        final StructuredQuery query = new StructuredQuery("events", filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("users.region"), null)), null, null, null, null);

        final String sql = builder.build(ctx, collapsingModel(), query).getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("from \"events\" final left outer join")
                .containsIgnoringCase("from \"users\" final where")
                // The key sub-query reads the source raw: the raw key set is a superset of the final one.
                .containsIgnoringCase("(select \"events\".\"user_id\" from \"events\" where");
    }

    @Test
    void rawReadRendersNoFinalAnywhere() {
        final String sql = builder.build(ctx, collapsingModel(), rowSelecting("amount", "users.region").withFinalRead(false))
                .getSQL(ParamType.INLINED);

        assertThat(sql).doesNotContainIgnoringCase("final");
    }

    @Test
    void anAppendSourceReadFinalTakesFinalOnItsEnrichmentsOnly() {
        // model() has an append source: ClickHouse refuses FINAL on its plain MergeTree, but the entity still reads
        // final through the enrichment, so the grain join stays 0..1.
        final String sql = render(rowSelecting("amount", "users.region"));

        assertThat(sql)
                .containsIgnoringCase("from \"events\" left outer join \"users\" final on");
    }

    @Test
    void joinsEnrichmentWhenItsColumnIsSelected() {
        final String sql = render(rowSelecting("amount", "users.region"));
        assertThat(sql)
                .containsIgnoringCase("left outer join")
                .containsIgnoringCase("users")
                .containsIgnoringCase("region");
    }

    @Test
    void selectingEnrichmentColumnRendersAsSingleDottedAlias() {
        // The enrichment column renders as the two-part physical reference "users"."region", aliased to
        // its qualified flat name as ONE quoted identifier "users.region" (a single token with a literal
        // dot), NOT split into "users"."region". Guards the EXPLICIT_DEFAULT_QUOTED render-config
        // assumption: were quoting disabled, the dotted alias would render unquoted and ClickHouse would
        // misparse it as a qualified name.
        final String sql = render(rowSelecting("amount", "users.region"));
        assertThat(sql)
                .contains("\"users\".\"region\"")
                .contains("\"users.region\"");
    }

    @Test
    void joinsEnrichmentWhenItsColumnIsFiltered() {
        final FilterNode filter = new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("users.region"), new ValueExpr(ValueType.STRING, "emea")));
        final StructuredQuery query =
                new StructuredQuery("events", filter, QueryMode.ROW, false, List.of(new OutputColumn(new FieldExpr("amount"), null)),
                        null, null, null, null);
        final String sql = render(query);
        assertThat(sql).containsIgnoringCase("left outer join").containsIgnoringCase("users");
        // A filter that names the enrichment's own column cannot key the join — the key sub-query would need
        // the join itself — so the enrichment is joined whole, as a bare table rather than a derived one.
        assertThat(sql).containsIgnoringCase("left outer join \"users\" final on");
        assertThat(sql).doesNotContainIgnoringCase(" in (select");
    }

    /**
     * A filter confined to the source keys the join: the enrichment is emitted as a derived table restricted
     * to {@code users.user_id IN (SELECT events.user_id FROM events WHERE <filter>)}, projecting the grain key
     * and only the enrichment columns the query names, and aliased back to the enrichment's own name so every
     * other reference in the statement resolves unchanged. Without this, ClickHouse's hash join would build
     * over every row of the enrichment to serve however few the filter selects.
     */
    @Test
    void sourceOnlyFilterKeysTheJoinToTheSelectedGrainKeys() {
        final FilterNode filter = new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("user_id"), new ValueExpr(ValueType.STRING, "u-1")));
        final StructuredQuery query = new StructuredQuery("events", filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("amount"), null), new OutputColumn(new FieldExpr("users.region"), null)),
                null, null, null, null);

        final String sql = render(query);

        assertThat(sql)
                .containsIgnoringCase("left outer join (select \"users\".\"user_id\", \"users\".\"region\" from \"users\"")
                .containsIgnoringCase("where (\"users\".\"user_id\" in (select \"events\".\"user_id\" from \"events\" where \"events\".\"user_id\" = 'u-1'))")
                .containsIgnoringCase(") \"users\" on (\"events\".\"user_id\" = \"users\".\"user_id\")")
                // The statement's own WHERE is still applied to the source; the key sub-query is in addition.
                .containsIgnoringCase("where \"events\".\"user_id\" = 'u-1' limit");
    }

    /** The count statement for {@code include_total} is keyed by the same rule as the row statement. */
    @Test
    void countStatementIsKeyedByTheSameFilter() {
        final FilterNode filter = new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("user_id"), new ValueExpr(ValueType.STRING, "u-1")));
        final StructuredQuery query = new StructuredQuery("events", filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("users.region"), null)), null, null, null, null);

        final String sql = builder.buildCount(ctx, model(), query).getSQL(ParamType.INLINED);

        // The referenced set is the query's, select included, so the derived table carries the projected column
        // even though count(*) never reads it; ClickHouse prunes an unused sub-query column, and the key
        // restriction is what the assertion is about.
        assertThat(sql)
                .containsIgnoringCase("select count(*)")
                .containsIgnoringCase("left outer join (select \"users\".\"user_id\", \"users\".\"region\" from \"users\" final where (\"users\".\"user_id\" in (select");
    }

    /**
     * A model whose enrichment declares the same partition column as its source: {@code events} and
     * {@code users} both partitioned on {@code day}, which is the shape {@code dial_usage_log} and
     * {@code dial_usage_log_payload} have on {@code request_time}.
     */
    private QueryEntityModel partitionAlignedModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "user_id", "events", "user_id", QueryFieldType.STRING);
        bind(bindings, "day", "events", "day", QueryFieldType.DATE);
        bind(bindings, "users.region", "users", "region", QueryFieldType.STRING);

        final EnrichmentJoin join = EnrichmentJoin.keyed(
                DSL.table(DSL.name("users")),
                DSL.field(DSL.name("events", "user_id")),
                DSL.field(DSL.name("users", "user_id")),
                DSL.field(DSL.name("events", "day")),
                DSL.field(DSL.name("users", "day")),
                Set.of("users.region"), null);

        return new QueryEntityModel("events", DSL.table(DSL.name("events")), bindings, List.of(join), List.of());
    }

    private StructuredQuery pointLookupSelectingRegion() {
        final FilterNode filter = new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("user_id"), new ValueExpr(ValueType.STRING, "u-1")));
        return new StructuredQuery("events", filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("users.region"), null)), null, null, null, null);
    }

    /**
     * The point of the whole mechanism: a partition-aligned join gains a second restriction, on the
     * enrichment's partition column, drawn from the same source predicates as the grain-key one. Without it
     * an enrichment keyed on an identifier uncorrelated with its partitioning is read across every part to
     * return one row — the grain-key set cannot exclude a part, because the row really might be in any of
     * them.
     */
    @Test
    void alignedPartitionColumnIsBoundedByTheSameSourcePredicates() {
        final String sql = builder.build(ctx, partitionAlignedModel(), pointLookupSelectingRegion())
                .getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("\"users\".\"user_id\" in (select \"events\".\"user_id\" from \"events\" where \"events\".\"user_id\" = 'u-1')")
                .containsIgnoringCase("\"users\".\"day\" in (select \"events\".\"day\" from \"events\" where \"events\".\"user_id\" = 'u-1')");
    }

    /**
     * The partition restriction goes inside the derived table, not onto the join condition. Both would prune
     * the scan, but only the inner form narrows what the derived table projects — and projecting a heavy
     * column across every part is the cost being avoided.
     */
    @Test
    void partitionRestrictionIsInsideTheDerivedTableNotTheJoinCondition() {
        final String sql = builder.build(ctx, partitionAlignedModel(), pointLookupSelectingRegion())
                .getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase(") \"users\" on ((\"events\".\"user_id\" = \"users\".\"user_id\") "
                        + "and (\"events\".\"day\" = \"users\".\"day\"))")
                .doesNotContainIgnoringCase("\"users\".\"day\" from \"events\") limit");
        // The projection is the grain key, the partition column the tuple join compares, and the referenced
        // column — nothing the statement does not need to join or to read.
        assertThat(sql).containsIgnoringCase(
                "left outer join (select \"users\".\"user_id\", \"users\".\"day\", \"users\".\"region\" from \"users\"");
    }

    /**
     * An unaligned enrichment — the default, and every enrichment the API can create — keeps exactly the
     * rendering it had: keyed on the grain key alone. The alignment is a catalog claim about two columns
     * holding the same value, and a model that does not carry it must not have one invented for it.
     */
    @Test
    void unalignedJoinCarriesNoPartitionRestriction() {
        final String sql = builder.build(ctx, model(), pointLookupSelectingRegion()).getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("\"users\".\"user_id\" in (select \"events\".\"user_id\" from \"events\"")
                .doesNotContainIgnoringCase("\"day\"");
    }

    /**
     * No source predicates, no restriction of either kind: alignment does not by itself justify keying, since
     * there is no value set to bound the enrichment by. Guards against the partition bound being emitted from
     * an empty predicate list, which would restrict the enrichment to nothing.
     */
    @Test
    void alignedJoinWithoutSourcePredicatesStaysUnkeyed() {
        final String sql = builder.build(ctx, partitionAlignedModel(), rowSelecting("users.region"))
                .getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("left outer join \"users\" final on")
                .doesNotContainIgnoringCase(" in (select");
    }

    /** No filter offers no key set: the join stays a bare {@code LEFT JOIN} over the enrichment table. */
    @Test
    void unfilteredQueryJoinsTheEnrichmentUnkeyed() {
        final String sql = render(rowSelecting("amount", "users.region"));
        assertThat(sql)
                .containsIgnoringCase("left outer join \"users\" final on")
                .doesNotContainIgnoringCase(" in (select");
    }

    /**
     * A join built from an opaque condition, with no grain-key fields, is never keyed — whatever the filter.
     * Hand-built test models and any caller that cannot name the keys keep the previous rendering exactly.
     */
    @Test
    void joinWithoutKeyFieldsIsNeverKeyed() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "user_id", "events", "user_id", QueryFieldType.STRING);
        bind(bindings, "users.region", "users", "region", QueryFieldType.STRING);
        final Field<Object> sourceKey = DSL.field(DSL.name("events", "user_id"));
        final Field<Object> grainKey = DSL.field(DSL.name("users", "user_id"));
        final QueryEntityModel opaque = new QueryEntityModel("events", DSL.table(DSL.name("events")), bindings,
                List.of(new EnrichmentJoin(DSL.table(DSL.name("users")), sourceKey.eq(grainKey), null, null, null, null, Set.of("users.region"), null)),
                List.of());
        final FilterNode filter = new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("user_id"), new ValueExpr(ValueType.STRING, "u-1")));
        final StructuredQuery query = new StructuredQuery("events", filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("users.region"), null)), null, null, null, null);

        final String sql = builder.build(ctx, opaque, query).getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("left outer join \"users\" final on")
                .doesNotContainIgnoringCase(" in (select");
    }

    /**
     * {@code events} with two enrichments: {@code users}, partition-aligned on {@code day} (the
     * {@code dial_usage_log_payload} shape), and {@code devices}, keyed on the grain key alone (the
     * {@code usage_client_identity} shape).
     */
    private QueryEntityModel twoEnrichmentModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "user_id", "events", "user_id", QueryFieldType.STRING);
        bind(bindings, "amount", "events", "amount", QueryFieldType.LONG);
        bind(bindings, "day", "events", "day", QueryFieldType.DATE);
        bind(bindings, "users.region", "users", "region", QueryFieldType.STRING);
        bind(bindings, "devices.os", "devices", "os", QueryFieldType.STRING);
        final EnrichmentJoin users = EnrichmentJoin.keyed(
                DSL.table(DSL.name("users")),
                DSL.field(DSL.name("events", "user_id")),
                DSL.field(DSL.name("users", "user_id")),
                DSL.field(DSL.name("events", "day")),
                DSL.field(DSL.name("users", "day")),
                Set.of("users.region"), null);
        final EnrichmentJoin devices = EnrichmentJoin.keyed(
                DSL.table(DSL.name("devices")),
                DSL.field(DSL.name("events", "user_id")),
                DSL.field(DSL.name("devices", "user_id")), null, null,
                Set.of("devices.os"), null);
        return new QueryEntityModel("events", DSL.table(DSL.name("events")), bindings, List.of(users, devices), List.of());
    }

    private static FilterNode eq(String field, String value) {
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr(field), new ValueExpr(ValueType.STRING, value)));
    }

    private static FilterNode amountAbove(long value) {
        return new ComparisonNode(
                ComparisonOp.GT, List.of(new FieldExpr("amount"), new ValueExpr(ValueType.LONG, String.valueOf(value))));
    }

    private static FilterNode and(FilterNode... args) {
        return new LogicalNode(LogicalOp.AND, List.of(args));
    }

    private static StructuredQuery filteredSelecting(FilterNode filter, String... fields) {
        return new StructuredQuery("events", filter, QueryMode.ROW, false,
                List.of(java.util.Arrays.stream(fields).map(f -> new OutputColumn(new FieldExpr(f), null)).toArray(OutputColumn[]::new)),
                null, null, null, null);
    }

    /**
     * RC1 of the 2026-10-14 enrichment-join cost sweep: a filter mixing source and enrichment conjuncts used to
     * un-key every join, so the time window never reached the enrichment side and ClickHouse hashed the whole
     * enrichment table. Its source-only conjuncts now key the join, the grain key and the aligned partition
     * alike, and the enrichment conjunct stays out of both sub-queries.
     */
    @Test
    void mixedAndIsKeyedByItsSourceOnlyConjuncts() {
        final StructuredQuery query = filteredSelecting(
                and(amountAbove(10), eq("user_id", "u-1"), eq("users.region", "emea")), "amount", "users.region");

        final String sql = builder.build(ctx, twoEnrichmentModel(), query).getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("\"users\".\"user_id\" in (select \"events\".\"user_id\" " + SOURCE_CONJUNCTS)
                .containsIgnoringCase("\"users\".\"day\" in (select \"events\".\"day\" " + SOURCE_CONJUNCTS);
    }

    /** The enrichment conjunct is applied once, in the statement's own WHERE, with the whole filter. */
    @Test
    void enrichmentConjunctAppearsOnlyInTheOuterWhere() {
        final StructuredQuery query = filteredSelecting(
                and(amountAbove(10), eq("user_id", "u-1"), eq("users.region", "emea")), "amount", "users.region");

        final String sql = builder.build(ctx, twoEnrichmentModel(), query).getSQL(ParamType.INLINED);

        assertThat(sql.split("\"users\"\\.\"region\" = 'emea'", -1)).hasSize(2);
        assertThat(sql).containsIgnoringCase(
                "where (\"events\".\"amount\" > 10 and \"events\".\"user_id\" = 'u-1' and \"users\".\"region\" = 'emea') limit");
    }

    /** {@code include_total}'s count statement takes the same keying as the row statement. */
    @Test
    void countStatementIsKeyedByTheSourceOnlyConjunctsOfTheMixedFilter() {
        final StructuredQuery query = filteredSelecting(
                and(amountAbove(10), eq("user_id", "u-1"), eq("users.region", "emea")), "users.region");

        final String sql = builder.buildCount(ctx, twoEnrichmentModel(), query).getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("select count(*)")
                .containsIgnoringCase("\"users\".\"user_id\" in (select \"events\".\"user_id\" " + SOURCE_CONJUNCTS)
                .containsIgnoringCase("\"users\".\"day\" in (select \"events\".\"day\" " + SOURCE_CONJUNCTS);
        assertThat(sql.split("\"users\"\\.\"region\" = 'emea'", -1)).hasSize(2);
    }

    /**
     * The switch was statement-wide: a filter on one enrichment (e.g. identity or baggage) un-keyed the join of
     * another (the payload carrying the body). Now the filter on {@code devices} keys the {@code users} join, and
     * the {@code devices} join too.
     */
    @Test
    void filterOnOneEnrichmentKeysAnotherEnrichmentsJoin() {
        final StructuredQuery query = filteredSelecting(
                and(amountAbove(10), eq("user_id", "u-1"), eq("devices.os", "linux")), "users.region");

        final String sql = builder.build(ctx, twoEnrichmentModel(), query).getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("left outer join (select \"users\".\"user_id\", \"users\".\"day\", \"users\".\"region\" from \"users\" final where")
                .containsIgnoringCase("\"users\".\"user_id\" in (select \"events\".\"user_id\" " + SOURCE_CONJUNCTS)
                .containsIgnoringCase("left outer join (select \"devices\".\"user_id\", \"devices\".\"os\" from \"devices\" final where")
                .containsIgnoringCase("\"devices\".\"user_id\" in (select \"events\".\"user_id\" " + SOURCE_CONJUNCTS);
    }

    /** Nested {@code and}s flatten: a source conjunct two levels down still keys the join. */
    @Test
    void nestedAndIsFlattenedToItsSourceOnlyConjuncts() {
        final StructuredQuery query = filteredSelecting(
                and(and(amountAbove(10), eq("users.region", "emea")), eq("user_id", "u-1")), "users.region");

        final String sql = builder.build(ctx, twoEnrichmentModel(), query).getSQL(ParamType.INLINED);

        assertThat(sql).containsIgnoringCase("\"users\".\"user_id\" in (select \"events\".\"user_id\" " + SOURCE_CONJUNCTS);
    }

    /** An {@code or} mixing source and enrichment columns has no source-only conjunct: the join stays unkeyed. */
    @Test
    void mixedOrIsLeftUnkeyed() {
        final StructuredQuery query = filteredSelecting(
                new LogicalNode(LogicalOp.OR, List.of(eq("user_id", "u-1"), eq("users.region", "emea"))), "amount");

        final String sql = builder.build(ctx, twoEnrichmentModel(), query).getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("left outer join \"users\" final on")
                .doesNotContainIgnoringCase(" in (select");
    }

    /** An {@code or} beside a source conjunct is one opaque conjunct: dropped from the keys, not split. */
    @Test
    void mixedOrBesideSourceConjunctKeysOnTheSourceConjunctOnly() {
        final StructuredQuery query = filteredSelecting(
                and(amountAbove(10), new LogicalNode(LogicalOp.OR, List.of(eq("user_id", "u-1"), eq("users.region", "emea")))),
                "amount");

        final String sql = builder.build(ctx, twoEnrichmentModel(), query).getSQL(ParamType.INLINED);

        assertThat(sql).containsIgnoringCase(
                "\"users\".\"user_id\" in (select \"events\".\"user_id\" from \"events\" where \"events\".\"amount\" > 10)");
    }

    /** An {@code and} of enrichment conjuncts only offers no key set either. */
    @Test
    void enrichmentOnlyAndIsLeftUnkeyed() {
        final StructuredQuery query = filteredSelecting(
                and(eq("users.region", "emea"), eq("devices.os", "linux")), "amount");

        final String sql = builder.build(ctx, twoEnrichmentModel(), query).getSQL(ParamType.INLINED);

        assertThat(sql)
                .containsIgnoringCase("left outer join \"users\" final on")
                .containsIgnoringCase("left outer join \"devices\" final on")
                .doesNotContainIgnoringCase(" in (select");
    }

    @Test
    void noJoinWhenOnlySourceColumnsAreReferenced() {
        final String sql = render(rowSelecting("amount"));
        assertThat(sql)
                .doesNotContainIgnoringCase("join")
                .doesNotContainIgnoringCase("users");
    }

    @Test
    void omittedProjectionExcludesEnrichmentColumnsAndTheirJoins() {
        // `*` projects the source's own columns only; enrichment columns are opt-in, so no join is
        // emitted and neither the enrichment table nor its column appears in the rendered SQL.
        final StructuredQuery selectAll =
                new StructuredQuery("events", null, QueryMode.ROW, false, null, null, null, null, null);
        final String sql = render(selectAll);
        assertThat(sql)
                .containsIgnoringCase("user_id")
                .doesNotContainIgnoringCase("join")
                .doesNotContainIgnoringCase("users")
                .doesNotContainIgnoringCase("region");
    }
}
