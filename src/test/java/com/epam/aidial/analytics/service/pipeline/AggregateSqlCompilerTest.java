package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.pipeline.model.TruncUnit;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.sql.CalciteQueryCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlInsertPlan;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import com.epam.aidial.analytics.service.table.PartitionGranularity;
import com.epam.aidial.analytics.web.security.Privilege;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link AggregateSqlCompiler} — the rendered statement of an SQL declaration, modelled on
 * {@link AggregateCompilerTest}. The statements are planned by the real SQL front end over the
 * {@link DialUsageLogTestModel} fixture with its keyed enrichment, so the block is exactly what the gate hands over.
 * The properties pinned are the form path's: the write recomputes every dirty key in full (a tuple {@code IN} over a
 * windowed sub-select, never the window itself), a bucket bounds the recompute, an enrichment grain restricts its
 * join to the dirty keys — exactly, even for an expression key whose absence value is itself dirty — and the
 * statement carries no {@code LIMIT} and no {@code FINAL} keyword.
 */
class AggregateSqlCompilerTest {

    private static final String SESSION = DialUsageLogTestModel.ENRICHMENT_COLUMN;

    /** A coalesced enrichment key, the sessions-rollup shape the gate requires for a nullable enrichment column. */
    private static final String SESSION_KEY = "coalesce(\"" + SESSION + "\", '')";

    /** The coalesce as the planner hands it over and the translator renders it. */
    private static final String RENDERED_KEY = "case when \"usage_client_identity\".\"client_session_id\" is not null"
            + " then \"usage_client_identity\".\"client_session_id\" else '' end";

    /** The same key with its enrichment column NULL — what an input row reads once its enrichment row is dropped. */
    private static final String ABSENCE_IMAGE = "case when NULL is not null then NULL else '' end";

    /** Where the restricted enrichment's derived table begins its WHERE. */
    private static final String RESTRICTED = "from \"usage_client_identity\" where ((";

    private static final CursorColumns CURSOR = new CursorColumns("_ingested_at", "event_id", "dial_usage_log", null);

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
    private final AggregateSqlCompiler compiler = new AggregateSqlCompiler(expr, new FilterTranslator(expr), ctx);
    private final QueryEntityModel model = DialUsageLogTestModel.modelWithKeyedEnrichment();
    private final List<Condition> window = List.of(DSL.condition("\"_ingested_at\" > 0"));

    private SqlInsertPlan plan(String sql) {
        return plan(sql, model);
    }

    private static SqlInsertPlan plan(String sql, QueryEntityModel planned) {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(planned));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog)).planInsert(sql, Privilege.TABLE_READ);
    }

    private static AggregateSqlValidator.Analysis analysis(SqlInsertPlan plan, List<Integer> keys,
            AggregateSqlValidator.Bucket bucket) {
        return new AggregateSqlValidator.Analysis(plan, keys, bucket);
    }

    private String refresh(AggregateSqlValidator.Analysis analysis, String target) {
        return compiler.compileRefresh(analysis, model, target, compiler.where(analysis, model), window).sql();
    }

    /** A day-bucketed usage rollup with a WHERE: the shape every bucket assertion below reads. */
    private AggregateSqlValidator.Analysis dailyUsage() {
        return analysis(plan("INSERT INTO usage_daily (deployment, day, cost) SELECT deployment,"
                        + " date_trunc('day', request_time), sum(total_price) FROM dial_usage_log WHERE chat_id <> ''"
                        + " GROUP BY 1, 2"),
                List.of(0, 1), new AggregateSqlValidator.Bucket(1, TruncUnit.DAY));
    }

    /** The sessions-rollup shape: one coalesced enrichment key, optionally with the WHERE that drops unmatched rows. */
    private AggregateSqlValidator.Analysis sessions(String where) {
        return analysis(plan("INSERT INTO sessions (session_id, turns, last_seen) SELECT " + SESSION_KEY + ","
                        + " count(*), max(request_time) FROM dial_usage_log" + where + " GROUP BY 1"),
                List.of(0), null);
    }

    /**
     * The outer WHERE of a rendered statement, with every parenthesised sub-select collapsed to {@code (…)} — so a
     * predicate found here is applied by the recompute itself, not somewhere inside a sub-query or a derived table.
     */
    private static String outerWhere(String sql) {
        final StringBuilder outer = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            if (sql.startsWith("(select", i)) {
                int depth = 0;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '(') {
                        depth++;
                    } else if (sql.charAt(i) == ')' && --depth == 0) {
                        i++;
                        break;
                    }
                    i++;
                }
                outer.append("(…)");
            } else {
                outer.append(sql.charAt(i++));
            }
        }
        final String flat = outer.toString();
        return flat.substring(flat.lastIndexOf(" where "));
    }

    /** The balanced parenthesised group opening at {@code start}. */
    private static String parenthesised(String sql, int start) {
        int depth = 0;
        for (int i = start; i < sql.length(); i++) {
            if (sql.charAt(i) == '(') {
                depth++;
            } else if (sql.charAt(i) == ')' && --depth == 0) {
                return sql.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("unbalanced: " + sql.substring(start));
    }

    @Test
    void prefixesTheInsertInColumnListOrderAndStampsUpdatedAt() {
        final AggregateCompiler.Compiled compiled = compiler.compileRefresh(dailyUsage(), model, "usage_daily",
                compiler.where(dailyUsage(), model), window);

        assertThat(compiled.targetColumns()).containsExactly("deployment", "day", "cost", "_updated_at");
        assertThat(compiled.sql())
                .startsWith("INSERT INTO \"usage_daily\" (\"deployment\", \"day\", \"cost\", \"_updated_at\") select ")
                .contains("sum(\"dial_usage_log\".\"total_price\") \"cost\", now64(3) from \"dial_usage_log\"");
    }

    @Test
    void carriesTheDirtyKeyRestrictionBesideTheBlocksOwnWhere() {
        final String sql = refresh(dailyUsage(), "usage_daily");

        final String outer = outerWhere(sql);
        assertThat(outer).contains("\"dial_usage_log\".\"chat_id\" <> ''").contains(") IN (…)");
        // The window never reaches the recompute itself, only its sub-selects: the dirty-key select and the two
        // bucket-bound scalar sub-queries.
        assertThat(outer).doesNotContain("_ingested_at");
        assertThat(sql.split("\"_ingested_at\" > 0", -1)).hasSize(4);
        // The dirty-key sub-select applies the block's WHERE and the window.
        final String dirty = sql.substring(sql.indexOf("IN (select distinct"));
        assertThat(dirty).contains("where (\"dial_usage_log\".\"chat_id\" <> '' and (\"_ingested_at\" > 0))");
    }

    @Test
    void boundsBucketedRecomputeToTheDirtyBucketsSpanInUtc() {
        final String sql = refresh(dailyUsage(), "usage_daily");

        assertThat(sql)
                .contains("\"dial_usage_log\".\"request_time\" >= (select min(toStartOfInterval("
                        + "\"dial_usage_log\".\"request_time\", INTERVAL 1 DAY, 'UTC'))")
                .contains("\"dial_usage_log\".\"request_time\" < (select max(toStartOfInterval("
                        + "\"dial_usage_log\".\"request_time\", INTERVAL 1 DAY, 'UTC'))")
                .contains("+ INTERVAL 1 DAY");
        assertThat(outerWhere(sql)).contains("\"dial_usage_log\".\"request_time\" >= (…)");
    }

    @Test
    void pinsZoneLessBucketToUtc() {
        final String sql = refresh(dailyUsage(), "usage_daily");

        // date_trunc('day', request_time) was written with no zone; every rendering of it carries 'UTC'.
        assertThat(sql.split("toStartOfInterval\\(", -1).length - 1)
                .isEqualTo(sql.split(Pattern.quote("INTERVAL 1 DAY, 'UTC')"), -1).length - 1)
                .isPositive();
    }

    @Test
    void rendersNoLimitAndNoFinalKeyword() {
        final String sql = refresh(dailyUsage(), "usage_daily").toLowerCase();

        assertThat(sql).doesNotContain(" limit ").doesNotContain(" final");
        assertThat(refresh(sessions(""), "sessions").toLowerCase()).doesNotContain(" limit ").doesNotContain(" final");
    }

    @Test
    void groupsAnExpressionKeyWhoseTargetNameIsAnEntityFieldByItsPositionalAlias() {
        // The bucket lands in a target column named request_time, an input column too: grouping by that name would
        // group by the raw timestamp, so the item is aliased by position and grouped by the alias.
        final AggregateSqlValidator.Analysis analysis = analysis(plan("INSERT INTO usage_daily (deployment, request_time,"
                        + " cost) SELECT deployment, date_trunc('day', request_time), sum(total_price) FROM dial_usage_log"
                        + " GROUP BY 1, 2"),
                List.of(0, 1), new AggregateSqlValidator.Bucket(1, TruncUnit.DAY));

        final String sql = refresh(analysis, "usage_daily");

        assertThat(sql).contains("'UTC') \"__c1\"").endsWith("group by \"dial_usage_log\".\"deployment\", \"__c1\"");
        assertThat(compiler.sampleAliases(analysis, model)).isEqualTo(Map.of("__c1", "request_time"));
    }

    @Test
    void anExpressionKeyOverAnEnrichmentRestrictsItsGrainJoinByTheDirtyKeys() {
        final String sql = refresh(sessions(" WHERE length(\"" + SESSION + "\") > 0"), "sessions");

        // The enrichment is a derived table whose WHERE is the dirty-key condition over the key expression.
        assertThat(sql).contains("left outer join (select \"usage_client_identity\".\"event_id\"")
                .contains(RESTRICTED + "(" + RENDERED_KEY + ") IN (select distinct " + RENDERED_KEY);
        final String restriction = parenthesised(sql, sql.indexOf(RESTRICTED) + RESTRICTED.length() - 2);
        // The same dirty-key condition is the outer one, text for text: what the derived table admits is exactly
        // what the recompute aggregates.
        final String dirtyKeys = parenthesised(restriction, 1);
        assertThat(outerWhere(sql)).contains(") IN (…)");
        assertThat(sql.split(Pattern.quote(dirtyKeys), -1).length - 1).isEqualTo(2);
    }

    @Test
    void dirtyAbsenceValueAdmitsEveryEnrichmentRowThatTick() {
        // No WHERE drops unmatched rows here, so a row whose enrichment row the restriction removed would read
        // coalesce(NULL, '') = '' and land in the '' group if '' is dirty. The restriction therefore also admits
        // every enrichment row whenever the key's absence image is among the dirty keys.
        final String sql = refresh(sessions(""), "sessions");

        final String restriction = parenthesised(sql, sql.indexOf(RESTRICTED) + RESTRICTED.length() - 2);
        assertThat(restriction)
                .startsWith("(((" + RENDERED_KEY + ") IN (select distinct")
                .contains(") or ((" + ABSENCE_IMAGE + ") IN (select distinct " + RENDERED_KEY);
        // Both disjuncts test the same dirty set: the image is compared with the keys the window touched.
        final int or = restriction.indexOf(") or ((");
        final String keys = restriction.substring(restriction.indexOf("IN (select distinct"), or);
        final String image = restriction.substring(restriction.indexOf("IN (select distinct", or));
        assertThat(image).startsWith(keys.substring(0, keys.length() - 1));
        // The outer recompute keeps the plain dirty-key condition: only the join is widened.
        assertThat(outerWhere(sql)).doesNotContain("NULL");
    }

    @Test
    void sourceGrainIsNotRestricted() {
        final String sql = refresh(analysis(plan("INSERT INTO chats (chat_id, turns) SELECT chat_id, count(*) FROM"
                + " dial_usage_log WHERE length(\"" + SESSION + "\") > 0 GROUP BY 1"), List.of(0), null), "chats");

        assertThat(sql).doesNotContain(RESTRICTED).doesNotContain("NULL is not null");
    }

    @Test
    void theProbeFilterIsTheBlocksWhereWithTheEnrichmentItReads() {
        final AggregateSqlValidator.Analysis analysis = sessions(" WHERE length(\"" + SESSION + "\") > 0");

        final AggregateCompiler.CompiledFilter where = compiler.where(analysis, model);

        assertThat(where.columns()).containsExactly(SESSION);
        assertThat(ctx.renderInlined(where.condition())).contains("length(\"usage_client_identity\".\"client_session_id\")");
        assertThat(compiler.where(analysis(plan("INSERT INTO chats (chat_id, turns) SELECT chat_id, count(*) FROM"
                + " dial_usage_log GROUP BY 1"), List.of(0), null), model)).isSameAs(AggregateCompiler.CompiledFilter.NONE);
    }

    @Test
    void previewSamplesTheBlockNewestFirstWithItsHelpersAndLimit() {
        final String sql = compiler.compilePreview(dailyUsage(), model, CURSOR, 5).getSQL(ParamType.INLINED);

        assertThat(sql).startsWith("select ").doesNotContain("INSERT").doesNotContain("select distinct")
                .contains("\"" + AggregateCompiler.LATEST_VERSION + "\"")
                .contains("\"" + AggregateCompiler.LATEST_IDENTITY + "\"")
                .contains("count(*) OVER () \"" + AggregateCompiler.TOTAL_GROUPS + "\"")
                .contains("order by max(toUnixTimestamp64Milli(\"dial_usage_log\".\"_ingested_at\")) desc, "
                        + "\"dial_usage_log\".\"deployment\" asc")
                .endsWith("limit 5");
        assertThat(sql.toLowerCase()).doesNotContain(" final");
    }

    /**
     * {@link #model} with its keyed enrichment partition-aligned on {@code request_time}, as the catalog builds the
     * identity and payload enrichments: the shape the query surface's window transfer applies to.
     */
    private QueryEntityModel alignedModel() {
        final String enrichment = DialUsageLogTestModel.ENRICHMENT;
        final List<EnrichmentJoin> joins = model.enrichments().stream()
                .map(join -> !join.table().getName().equals(enrichment) ? join : EnrichmentJoin.keyed(join.table(),
                        join.sourceKey(), join.enrichmentKey(),
                        DSL.field(DSL.name(DialUsageLogTestModel.ENTITY, "request_time")),
                        DSL.field(DSL.name(enrichment, "request_time")), join.exposedNames(), PartitionGranularity.DAY))
                .toList();
        return new QueryEntityModel(model.entity(), model.table(), model.bindings(), joins, model.baseSchema(),
                model.tagOrder(), model.partitionLocal(), model.collapsing(), model.finalCapable());
    }

    @Test
    void windowOnlyWhereKeepsBothKeySetsOnAnAlignedJoinInTheRefreshAndThePreview() {
        final QueryEntityModel aligned = alignedModel();
        final AggregateSqlValidator.Analysis analysis = analysis(plan("INSERT INTO usage_daily (deployment, day, last_session)"
                        + " SELECT deployment, date_trunc('day', request_time), max(" + SESSION_KEY + ") FROM dial_usage_log"
                        + " WHERE request_time >= TIMESTAMP '2026-01-01 00:00:00' GROUP BY 1, 2", aligned),
                List.of(0, 1), new AggregateSqlValidator.Bucket(1, TruncUnit.DAY));
        final String grainSet = "\"usage_client_identity\".\"event_id\" IN (select";
        final String partitionSet = "\"usage_client_identity\".\"request_time\" IN (select";

        final String refresh = compiler.compileRefresh(analysis, aligned, "usage_daily", compiler.where(analysis, aligned),
                window).sql();
        final String preview = compiler.compilePreview(analysis, aligned, CURSOR, 5).getSQL(ParamType.INLINED);

        assertThat(refresh).contains(grainSet).contains(partitionSet).doesNotContain("\"usage_client_identity\".\"request_time\" >=");
        assertThat(preview).contains(grainSet).contains(partitionSet).doesNotContain("\"usage_client_identity\".\"request_time\" >=");
    }
}
