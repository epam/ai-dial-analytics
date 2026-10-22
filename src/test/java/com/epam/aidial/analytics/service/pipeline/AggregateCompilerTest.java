package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.TruncUnit;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link AggregateCompiler} — the rendered statement shape. The assertions are about
 * the property the whole design rests on: the write aggregates over <em>every</em> row of each key the
 * window touched (a full recompute selected by a dirty-key tuple {@code IN}), never over the window
 * alone. A statement that aggregated only the window would still run and still look plausible, so the
 * shape is pinned here rather than left to an integration test to notice.
 */
class AggregateCompilerTest {

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final AggregateCompiler compiler =
            new AggregateCompiler(new ExprTranslator(new ValueExprToObjectMapper()), ctx);
    private final QueryEntityModel model = DialUsageLogTestModel.model();

    /** The input's cursor columns, as the catalog resolves them for a preview's newest-first order. */
    private static final CursorColumns CURSOR = new CursorColumns("_ingested_at", "event_id", "dial_usage_log", null);

    /**
     * The window predicates a probe would hand over; their exact form is the scan path's concern.
     * Identifiers render double-quoted here because that is what the shared ClickHouse render context
     * emits (ClickHouse accepts both quoting styles; the Class-1 write path relies on the same one).
     */
    private final List<Condition> window = List.of(DSL.condition("\"_ingested_at\" > 0"));

    /** No membership predicate: the shape every declaration below has unless it says otherwise. */
    private static final AggregateCompiler.CompiledFilter NO_FILTER = AggregateCompiler.CompiledFilter.NONE;

    /** A predicate together with the entity fields it reads — what decides which statements join for it. */
    private static AggregateCompiler.CompiledFilter filter(String sql, String... columns) {
        return new AggregateCompiler.CompiledFilter(DSL.condition(sql), Set.of(columns));
    }

    private static Map<String, AggregateCompiler.CompiledFilter> scoped(
            String measure, String sql, String... columns) {
        return Map.of(measure, filter(sql, columns));
    }

    private static GroupKey column(String name) {
        final GroupKey key = new GroupKey();
        key.setColumn(name);
        return key;
    }

    private static GroupKey bucket(String column, TruncUnit unit, String as) {
        final GroupKey key = new GroupKey();
        final GroupKey.Trunc trunc = new GroupKey.Trunc();
        trunc.setColumn(column);
        trunc.setUnit(unit);
        key.setTrunc(trunc);
        key.setAs(as);
        return key;
    }

    private static Measure measure(String name, String fn, String column) {
        final Measure m = new Measure();
        m.setName(name);
        m.setFn(fn);
        m.setColumn(column);
        return m;
    }

    /** Sets the quantifier on an already-built measure, so the shape of the call reads as the addition it is. */
    private static Measure distinct(Measure measure) {
        measure.setDistinct(true);
        return measure;
    }

    private static AggregateSpec spec(List<GroupKey> groupBy, List<Measure> measures) {
        final AggregateSpec spec = new AggregateSpec();
        spec.setGroupBy(groupBy);
        spec.setMeasures(measures);
        return spec;
    }

    @Test
    void rendersDimensionPlusTimeBucketRollup() {
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.DAY, "day")),
                List.of(measure("call_count", "count", null), measure("cost", "sum", "total_price")));

        final AggregateCompiler.Compiled compiled =
                compiler.compileRefresh(spec, model, "usage_daily", NO_FILTER, Map.of(), window);

        assertThat(compiled.targetColumns())
                .containsExactly("deployment", "day", "call_count", "cost", "_updated_at");
        assertThat(compiled.sql())
                .startsWith("INSERT INTO \"usage_daily\" (\"deployment\", \"day\", \"call_count\", \"cost\", \"_updated_at\")")
                .contains("toStartOfInterval(\"dial_usage_log\".\"request_time\", INTERVAL 1 DAY, 'UTC')")
                .contains("count(*)")
                .contains("sum(\"dial_usage_log\".\"total_price\")")
                .contains("now64(3)")
                .contains("group by")
                .doesNotContain("limit");
    }

    @Test
    void restrictsToDirtyKeysWithTupleInOverWindow() {
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.DAY, "day")),
                List.of(measure("call_count", "count", null)));

        final String sql = compiler.compileRefresh(spec, model, "usage_daily", NO_FILTER, Map.of(), window).sql();

        // The outer aggregate is bounded by WHICH KEYS changed, not by the window itself.
        assertThat(sql).contains("(\"dial_usage_log\".\"deployment\", "
                + "toStartOfInterval(\"dial_usage_log\".\"request_time\", INTERVAL 1 DAY, 'UTC')) IN (select distinct");
        // Every window-predicate occurrence sits inside a sub-select — three of them, none at the top level:
        // the dirty-key sub-select, plus the two scalar sub-queries measuring the dirty buckets' span. If one
        // ever appeared in the outer WHERE, the recompute would see part of a key and write a partial total.
        assertThat(sql.split("\"_ingested_at\" > 0", -1)).hasSize(4);
        assertThat(outerWhere(sql)).doesNotContain("\"_ingested_at\"");
    }

    @Test
    void boundsTheRecomputeToTheDirtyBucketsSpan() {
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.DAY, "day")),
                List.of(measure("call_count", "count", null)));

        final String sql = compiler.compileRefresh(spec, model, "usage_daily", NO_FILTER, Map.of(), window).sql();

        // Without this the outer half reads the whole table every refresh: ClickHouse cannot prune on the
        // tuple IN above, but it can on a scalar-subquery bound over the raw bucketed column. The upper bound
        // steps one unit past the last dirty bucket — in SQL, so the step stays in the engine's calendar.
        assertThat(sql)
                .contains("\"dial_usage_log\".\"request_time\" >= (select min(toStartOfInterval("
                        + "\"dial_usage_log\".\"request_time\", INTERVAL 1 DAY, 'UTC'))")
                .contains("\"dial_usage_log\".\"request_time\" < (select max(toStartOfInterval("
                        + "\"dial_usage_log\".\"request_time\", INTERVAL 1 DAY, 'UTC'))")
                .contains("+ INTERVAL 1 DAY");
        // The bound is on the outer half — that is the half it exists to prune.
        assertThat(outerWhere(sql)).contains("\"dial_usage_log\".\"request_time\" >= (…)");
    }

    @Test
    void monthBucketCompilesToCalendarTruncationAndStepsOneMonth() {
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.MONTH, "month")),
                List.of(measure("call_count", "count", null)));

        final String sql = compiler.compileRefresh(spec, model, "usage_monthly", NO_FILTER, Map.of(), window).sql();

        // A month is not a fixed width, so both the bucket and the step past the last dirty bucket have to
        // be calendar arithmetic — and it has to happen in the engine, in the zone the bucketing used.
        // Marshalling the step through Java would mean reproducing that here, and a mismatch silently drops
        // rows from the recompute.
        assertThat(sql)
                .contains("toStartOfInterval(\"dial_usage_log\".\"request_time\", INTERVAL 1 MONTH, 'UTC')")
                .contains("+ INTERVAL 1 MONTH");
    }

    @Test
    void fixedWidthBucketsRenderExactlyWhatTheyDidBeforeCompilingThroughDateTrunc() {
        // Buckets now lower to date_trunc rather than date_bin(1, …). On the fixed-width units that is a
        // refactor with no output: same toStartOfInterval call, same amount of 1, same pinned zone. Asserted
        // because a rendering change here would silently re-key every materialized rollup.
        for (final TruncUnit unit : List.of(TruncUnit.HOUR, TruncUnit.DAY, TruncUnit.WEEK)) {
            final AggregateSpec spec = spec(
                    List.of(column("deployment"), bucket("request_time", unit, "bucket")),
                    List.of(measure("call_count", "count", null)));

            assertThat(compiler.compileRefresh(spec, model, "usage_rollup", NO_FILTER, Map.of(), window).sql())
                    .as("bucket unit %s", unit.code())
                    .contains("toStartOfInterval(\"dial_usage_log\".\"request_time\", INTERVAL 1 "
                            + unit.intervalKeyword() + ", 'UTC')");
        }
    }

    /**
     * The statement's own {@code WHERE}, with the body of every sub-select replaced by {@code (…)} —
     * ordinary grouping parentheses are kept. Lets an assertion distinguish "restricts the recompute
     * itself" from "appears somewhere inside a sub-query", which is exactly the distinction the recompute
     * half's correctness turns on.
     */
    private static String outerWhere(String sql) {
        final String where = sql.substring(sql.indexOf("where"));
        final StringBuilder outer = new StringBuilder();
        int i = 0;
        while (i < where.length()) {
            if (where.startsWith("(select", i)) {
                int depth = 0;
                final int start = i;
                while (i < where.length()) {
                    if (where.charAt(i) == '(') {
                        depth++;
                    } else if (where.charAt(i) == ')' && --depth == 0) {
                        i++;
                        break;
                    }
                    i++;
                }
                outer.append(where, start, start + 1).append("…)");
            } else {
                outer.append(where.charAt(i++));
            }
        }
        return outer.toString();
    }

    @Test
    void appliesTheMembershipFilterToBothHalves() {
        final AggregateSpec spec = spec(
                List.of(column("deployment")), List.of(measure("call_count", "count", null)));
        final AggregateCompiler.CompiledFilter filter = filter("\"success\" = true", "success");

        final String sql = compiler.compileRefresh(spec, model, "usage_daily", filter, Map.of(), window).sql();

        // Both the recompute and the dirty-key probe must see the same membership predicate, or a row
        // excluded from the measure could still mark its key dirty (and vice versa).
        assertThat(sql.split("\"success\" = true", -1)).hasSize(3);
    }

    @Test
    void rendersSingleNonTimeKeyRollup() {
        // The conv_rollup spine shape: one row per chat_id, no time bucket at all.
        final AggregateSpec spec = spec(
                List.of(column("chat_id")),
                List.of(measure("turn_count", "count", null),
                        measure("total_tokens", "sum", "total_tokens"),
                        measure("first_ts", "min", "request_time")));

        final AggregateCompiler.Compiled compiled =
                compiler.compileRefresh(spec, model, "conv_rollup", NO_FILTER, Map.of(), window);

        assertThat(compiled.targetColumns())
                .containsExactly("chat_id", "turn_count", "total_tokens", "first_ts", "_updated_at");
        assertThat(compiled.sql())
                .contains("min(\"dial_usage_log\".\"request_time\")")
                .contains("(\"dial_usage_log\".\"chat_id\") IN (select distinct");
    }

    @Test
    void compilesArrayMeasureAsSortedUniqueSet() {
        // The conversations use case: the set of deployments that handled any hop of a conversation, as one
        // array column. Sorted, because the recompute-and-collapse write is only idempotent when a re-run
        // over unchanged rows produces the identical value — and groupUniqArray's own order is not stable.
        final AggregateSpec spec = spec(
                List.of(column("chat_id")),
                List.of(measure("turn_count", "count", null),
                        measure("deployments", "group_uniq_array", "deployment")));

        final AggregateCompiler.Compiled compiled =
                compiler.compileRefresh(spec, model, "conv_rollup", NO_FILTER, Map.of(), window);

        assertThat(compiled.targetColumns())
                .containsExactly("chat_id", "turn_count", "deployments", "_updated_at");
        assertThat(compiled.sql())
                .contains("arraySort(groupUniqArray(\"dial_usage_log\".\"deployment\"))")
                .contains("group by \"dial_usage_log\".\"chat_id\"");
    }

    @Test
    void scopedMeasureWrapsOnlyItsOwnOperandInCase() {
        // The conversations use case that motivated per-measure predicates: the whole-chain cost must sum
        // total_price over root hops only, while every other measure keeps reading the whole group.
        final AggregateSpec spec = spec(
                List.of(column("chat_id")),
                List.of(measure("total_tokens", "sum", "total_tokens"),
                        measure("chain_price", "sum", "total_price")));
        final Map<String, AggregateCompiler.CompiledFilter> measureFilters =
                scoped("chain_price", "\"parent_deployment\" is null", "parent_deployment");

        final String sql = compiler.compileRefresh(spec, model, "conv_rollup", NO_FILTER, measureFilters, window).sql();

        // The scoped measure gets the CASE; the unscoped one beside it must be untouched, which is the
        // whole point of putting the predicate on the measure instead of on the pipeline.
        assertThat(sql)
                .containsIgnoringCase("sum(case when (\"parent_deployment\" is null) "
                        + "then \"dial_usage_log\".\"total_price\" end)")
                .contains("sum(\"dial_usage_log\".\"total_tokens\")");
        // And it stays out of both WHERE clauses: narrowing the recompute half would change the other
        // measures, and narrowing the dirty-key half would stop keys whose matching rows fall outside the
        // window from ever being recomputed.
        assertThat(sql.split("\"parent_deployment\" is null", -1))
                .as("the measure predicate appears exactly once, inside the CASE")
                .hasSize(2);
    }

    @Test
    void scopedCountStarCountsMatchingRowsViaLiteral() {
        // count() has no column to wrap, so the CASE yields a literal on matching rows and NULL elsewhere;
        // count(x) counts non-null values, which makes this the count of the matching rows.
        final AggregateSpec spec = spec(
                List.of(column("chat_id")), List.of(measure("turn_count", "count", null)));
        final Map<String, AggregateCompiler.CompiledFilter> measureFilters =
                scoped("turn_count", "\"parent_deployment\" is null", "parent_deployment");

        final String sql = compiler.compileRefresh(spec, model, "conv_rollup", NO_FILTER, measureFilters, window).sql();

        assertThat(sql).containsIgnoringCase("count(case when (\"parent_deployment\" is null) then 1 end)");
    }

    @Test
    void distinctMeasureDeduplicatesItsColumn() {
        // The conversations use case that motivated the quantifier: one turn is one trace_id and a trace can
        // span many hops, so a turn count over a hop-grain input has to count distinct values, not rows.
        final AggregateSpec spec = spec(
                List.of(column("chat_id")), List.of(distinct(measure("turn_count", "count", "trace_id"))));

        final String sql = compiler.compileRefresh(spec, model, "conv_rollup", NO_FILTER, Map.of(), window).sql();

        assertThat(sql).containsIgnoringCase("count(distinct \"dial_usage_log\".\"trace_id\")");
    }

    @Test
    void distinctMeasureCombinesWithItsOwnPredicate() {
        // The pair success_count needs. count(DISTINCT x) counts distinct non-null values, so the CASE's
        // implicit NULL drops the non-matching rows and what is left is the distinct traces that succeeded —
        // correct with no special case for the combination.
        final AggregateSpec spec = spec(
                List.of(column("chat_id")), List.of(distinct(measure("success_count", "count", "trace_id"))));
        final Map<String, AggregateCompiler.CompiledFilter> measureFilters =
                scoped("success_count", "\"success\" = true", "success");

        final String sql = compiler.compileRefresh(spec, model, "conv_rollup", NO_FILTER, measureFilters, window).sql();

        assertThat(sql).containsIgnoringCase("count(distinct case when (\"success\" = true) "
                + "then \"dial_usage_log\".\"trace_id\" end)");
    }

    @Test
    void distinctRendersForEverySupportingFunction() {
        final AggregateSpec spec = spec(
                List.of(column("chat_id")),
                List.of(distinct(measure("tokens", "sum", "total_tokens")),
                        distinct(measure("mean_tokens", "avg", "total_tokens"))));

        final String sql = compiler.compileRefresh(spec, model, "conv_rollup", NO_FILTER, Map.of(), window).sql();

        assertThat(sql)
                .containsIgnoringCase("sum(distinct \"dial_usage_log\".\"total_tokens\")")
                .containsIgnoringCase("avg(distinct \"dial_usage_log\".\"total_tokens\")");
    }

    @Test
    void omittedDistinctRendersExactlyAsBefore() {
        // The compatibility claim of the quantifier: it is additive, so a declaration that predates it must
        // render byte-identically. A default that leaked into the rendering would silently change every
        // stored pipeline's output.
        final AggregateSpec spec = spec(
                List.of(column("chat_id")),
                List.of(measure("turn_count", "count", "trace_id"),
                        measure("tokens", "sum", "total_tokens")));

        final String sql = compiler.compileRefresh(spec, model, "conv_rollup", NO_FILTER, Map.of(), window).sql();

        // Scoped to the aggregates on purpose: the statement legitimately contains one other DISTINCT, the
        // `select distinct` of the dirty-key restriction, which has nothing to do with a measure.
        assertThat(sql)
                .contains("count(\"dial_usage_log\".\"trace_id\")")
                .contains("sum(\"dial_usage_log\".\"total_tokens\")")
                .doesNotContainIgnoringCase("count(distinct")
                .doesNotContainIgnoringCase("sum(distinct");
    }

    @Test
    void previewAppliesMeasurePredicatesToo() {
        // Preview is the gate an operator reads before enabling, so it has to show the scoped numbers. A
        // preview that ignored the predicate would show totals the enabled pipeline never writes.
        final AggregateSpec spec = spec(
                List.of(column("chat_id")), List.of(measure("chain_price", "sum", "total_price")));
        final Map<String, AggregateCompiler.CompiledFilter> measureFilters =
                scoped("chain_price", "\"parent_deployment\" is null", "parent_deployment");

        final String sql = compiler.compilePreview(spec, model, CURSOR, NO_FILTER, measureFilters, 20).getSQL();

        assertThat(sql).containsIgnoringCase("case when (\"parent_deployment\" is null)");
    }

    // --- the grain join ----------------------------------------------------------------------------

    /** The same entity, with one ACTIVE enrichment exposing {@code usage_client_identity.client_session_id}. */
    private final QueryEntityModel enriched = DialUsageLogTestModel.modelWithEnrichment();

    private static final String SESSION = DialUsageLogTestModel.ENRICHMENT_COLUMN;
    private static final String JOIN = "left outer join \"usage_client_identity\"";

    /** How many statements of the compiled refresh carry the grain join. */
    private static int joins(String sql) {
        return sql.toLowerCase().split(JOIN, -1).length - 1;
    }

    @Test
    void groupKeyOverEnrichmentColumnJoinsRecomputeAndDirtyKeySelect() {
        final AggregateSpec spec = spec(
                List.of(column(SESSION)), List.of(measure("turn_count", "count", null)));

        final String sql = compiler.compileRefresh(spec, enriched, "sessions", NO_FILTER, Map.of(), window).sql();

        // Both halves: the recompute reads the key and the dirty-key select projects it, and each composes
        // its own FROM. A join in only one of them renders SQL that names an unjoined table.
        assertThat(joins(sql)).isEqualTo(2);
        assertThat(sql)
                .contains("\"usage_client_identity\".\"client_session_id\"")
                .containsIgnoringCase("on \"dial_usage_log\".\"event_id\" = \"usage_client_identity\".\"event_id\"");
    }

    @Test
    void declarationTouchingNoEnrichmentJoinsNothing() {
        final AggregateSpec spec = spec(
                List.of(column("chat_id")), List.of(measure("turn_count", "count", null)));

        final String sql = compiler.compileRefresh(spec, enriched, "conv_rollup", NO_FILTER, Map.of(), window).sql();

        assertThat(joins(sql)).isZero();
    }

    @Test
    void filterOverEnrichmentColumnJoinsEveryStatementIncludingBothBucketBounds() {
        // The filter is the one reference every statement carries, and the bucket bounds are the case that
        // fails at execution rather than at compile: they are independent scalar sub-queries, so an
        // unjoined one is only visible in the rendered SQL.
        final AggregateSpec spec = spec(
                List.of(bucket("request_time", TruncUnit.DAY, "day")),
                List.of(measure("call_count", "count", null)));
        final AggregateCompiler.CompiledFilter filter =
                filter("\"usage_client_identity\".\"client_session_id\" != \'\'", SESSION);

        final String sql = compiler.compileRefresh(spec, enriched, "usage_daily", filter, Map.of(), window).sql();

        // Recompute + dirty keys + the two bucket bounds.
        assertThat(joins(sql)).isEqualTo(4);
    }

    @Test
    void enrichmentNamedOnlyByMeasureStaysOutOfKeyAndBoundStatements() {
        // Deriving the referenced set per statement rather than once per compile: a measure reaches the
        // recompute only, so the dirty-key select and both bucket bounds must not pay for a full enrichment
        // read they have no reference to.
        final AggregateSpec spec = spec(
                List.of(bucket("request_time", TruncUnit.DAY, "day")),
                List.of(measure("sessions", "count", SESSION)));

        final String sql = compiler.compileRefresh(spec, enriched, "usage_daily", NO_FILTER, Map.of(), window).sql();

        assertThat(joins(sql)).isEqualTo(1);
    }

    @Test
    void measurePredicateOverEnrichmentColumnJoinsTheRecomputeOnly() {
        final AggregateSpec spec = spec(
                List.of(column("chat_id")), List.of(measure("chain_price", "sum", "total_price")));

        final String sql = compiler.compileRefresh(spec, enriched, "conv_rollup", NO_FILTER,
                scoped("chain_price", "\"usage_client_identity\".\"client_session_id\" != \'\'", SESSION),
                window).sql();

        assertThat(joins(sql)).isEqualTo(1);
    }

    @Test
    void previewJoinsWhatRefreshWouldJoin() {
        // Preview is the gate an operator reads before enabling; a sample compiled without the join would
        // either fail or report the wrong numbers for the statement it is standing in for.
        final AggregateSpec spec = spec(
                List.of(column(SESSION)), List.of(measure("turn_count", "count", null)));

        final String sql = compiler.compilePreview(spec, enriched, CURSOR, NO_FILTER, Map.of(), 20).getSQL();

        assertThat(sql.toLowerCase()).contains(JOIN);
    }

    @Test
    void unreferencedEnrichmentChangesNothingAboutTheRenderedStatement() {
        // The compatibility claim of this change: a pipeline that reads no enrichment must compile the
        // statement it compiled before, byte for byte, even though its model now carries one.
        final AggregateSpec spec = spec(
                List.of(column("deployment"), bucket("request_time", TruncUnit.DAY, "day")),
                List.of(measure("call_count", "count", null), measure("cost", "sum", "total_price")));
        final AggregateCompiler.CompiledFilter filter = filter("\"success\" = true", "success");

        assertThat(compiler.compileRefresh(spec, enriched, "usage_daily", filter, Map.of(), window).sql())
                .isEqualTo(compiler.compileRefresh(spec, model, "usage_daily", filter, Map.of(), window).sql());
    }

    @Test
    void previewIsBoundedAndUnwindowed() {
        final AggregateSpec spec = spec(
                List.of(column("deployment")), List.of(measure("call_count", "count", null)));

        final String sql = compiler.compilePreview(spec, model, CURSOR, NO_FILTER, Map.of(), 20).getSQL();

        // Preview answers "what does this declaration produce", so it is not window-restricted; it is
        // bounded only by the sample limit, and it never writes.
        assertThat(sql).doesNotContain("insert").doesNotContain("select distinct");
        assertThat(sql.toLowerCase()).contains("limit");
    }

    @Test
    void previewOrdersGroupsByTheirLatestInputRowNewestFirst() {
        final AggregateSpec spec = spec(
                List.of(column("deployment")), List.of(measure("call_count", "count", null)));

        final String sql = compiler.compilePreview(spec, model, CURSOR, NO_FILTER, Map.of(), 20).getSQL();

        // The latest row of each group first, then the key as the tiebreak; the ordering value is a helper
        // column the service strips, projected under its internal alias.
        assertThat(sql).contains("max(toUnixTimestamp64Milli(\"dial_usage_log\".\"_ingested_at\")) \""
                + AggregateCompiler.LATEST_VERSION + "\"");
        assertThat(sql).contains("argMax(toString(\"dial_usage_log\".\"event_id\"), "
                + "(toUnixTimestamp64Milli(\"dial_usage_log\".\"_ingested_at\"), toString(\"dial_usage_log\".\"event_id\"))) \""
                + AggregateCompiler.LATEST_IDENTITY + "\"");
        assertThat(sql).containsPattern(
                "order by max\\(toUnixTimestamp64Milli\\(\"dial_usage_log\"\\.\"_ingested_at\"\\)\\) desc, "
                        + "\"dial_usage_log\"\\.\"deployment\" asc");
    }

    @Test
    void previewCountsEveryGroupInTheSameStatement() {
        final AggregateSpec spec = spec(
                List.of(column("deployment")), List.of(measure("call_count", "count", null)));

        final String sql = compiler.compilePreview(spec, model, CURSOR, NO_FILTER, Map.of(), 20).getSQL();

        // A window count over the grouped result, evaluated before the LIMIT: the number of rows the target holds.
        assertThat(sql).contains("count(*) OVER () \"" + AggregateCompiler.TOTAL_GROUPS + "\"");
        assertThat(sql.toLowerCase()).endsWith("limit ?");
    }

    @Test
    void previewQualifiesTheVersionColumnAgainstJoinedEnrichmentCarryingTheSameName() {
        // An upsert_by_key input versioned by _updated_at, joined to a user-table enrichment that has its own
        // _updated_at: an unqualified version column would be ambiguous in the joined statement.
        final AggregateSpec spec = spec(
                List.of(column(SESSION)), List.of(measure("turn_count", "count", null)));
        final CursorColumns upsert = new CursorColumns("_updated_at", "event_id", "dial_usage_log", null);

        final String sql = compiler.compilePreview(spec, enriched, upsert, NO_FILTER, Map.of(), 20).getSQL();

        assertThat(sql.toLowerCase()).contains(JOIN);
        assertThat(sql).contains("max(toUnixTimestamp64Milli(\"dial_usage_log\".\"_updated_at\"))")
                .doesNotContain("toUnixTimestamp64Milli(\"_updated_at\")");
    }

    // --- the dirty-key restriction ------------------------------------------------------------------

    /** The entity with its enrichment joined the way the catalog builds it: keyed, so restrictable. */
    private final QueryEntityModel keyedEnrichment = DialUsageLogTestModel.modelWithKeyedEnrichment();

    private static final String CLIENT_TYPE = DialUsageLogTestModel.ENRICHMENT_TYPE_COLUMN;
    private static final String CLIENT_TS = DialUsageLogTestModel.ENRICHMENT_TIME_COLUMN;
    private static final String CLIENT_TAGS = DialUsageLogTestModel.ENRICHMENT_ARRAY_COLUMN;

    /** Where a restricted enrichment's derived table begins its WHERE. */
    private static final String RESTRICTED = "from \"usage_client_identity\" where ((";

    /** The keying shape: the enrichment's grain key in a source key set. */
    private static final String KEYING = "\"usage_client_identity\".\"event_id\" IN (select";

    /**
     * How many dirty-key {@code IN}s the statement carries: the outer one always, plus one per restricted
     * enrichment. The restriction is that same condition, so counting it is counting restrictions.
     */
    private static int dirtyKeyIns(String sql) {
        return sql.split("IN \\(select distinct", -1).length - 1;
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

    private static GroupKey alias(GroupKey key, String as) {
        key.setAs(as);
        return key;
    }

    private String refresh(AggregateSpec spec, AggregateCompiler.CompiledFilter filter) {
        return compiler.compileRefresh(spec, keyedEnrichment, "sessions", filter, Map.of(), window).sql();
    }

    @Test
    void enrichmentGrainRestrictsItsEnrichmentByTheSameDirtyKeyCondition() {
        final AggregateSpec spec = spec(List.of(alias(column(SESSION), "session_id")),
                List.of(measure("turn_count", "count", null), measure("types", "count", CLIENT_TYPE)));

        final String sql = refresh(spec, NO_FILTER);

        assertThat(dirtyKeyIns(sql)).isEqualTo(2);
        // The derived table's WHERE and the outer WHERE carry the same dirty-key condition, text for text.
        final String condition = parenthesised(sql, sql.indexOf(RESTRICTED) + RESTRICTED.length() - 1);
        assertThat(sql.split(Pattern.quote(condition), -1)).hasSize(3);
        // Every column the recompute reads from the enrichment is projected by the restricted table.
        final String projection = sql.substring(sql.indexOf("left outer join (select"), sql.indexOf(RESTRICTED));
        assertThat(projection).contains("\"usage_client_identity\".\"event_id\"")
                .contains("\"usage_client_identity\".\"client_session_id\"")
                .contains("\"usage_client_identity\".\"client_type\"");
    }

    @Test
    void timeBucketOverEnrichmentColumnRestrictsByTheBucket() {
        final AggregateSpec spec = spec(List.of(bucket(CLIENT_TS, TruncUnit.DAY, "day")),
                List.of(measure("turn_count", "count", null)));

        final String sql = refresh(spec, NO_FILTER);

        assertThat(dirtyKeyIns(sql)).isEqualTo(2);
        assertThat(sql).contains(RESTRICTED
                + "toStartOfInterval(\"usage_client_identity\".\"client_ts\", INTERVAL 1 DAY, 'UTC')) IN (select distinct");
    }

    @Test
    void sourceOnlyFilterDoesNotKeyTheRestrictedEnrichment() {
        final AggregateSpec spec = spec(List.of(alias(column(SESSION), "session_id")),
                List.of(measure("turn_count", "count", null)));

        final String sql = refresh(spec, filter("\"success\" = true", "success"));

        assertThat(dirtyKeyIns(sql)).isEqualTo(2);
        // Two keyed joins remain, both inside the dirty-key sub-selects, keyed on the window. The recompute's
        // own join carries the restriction alone, not the filter's unbounded key set beside it.
        assertThat(sql.split(KEYING.replace("(", "\\("), -1)).hasSize(3);
    }

    @Test
    void mixedGrainIsNotRestricted() {
        final AggregateSpec spec = spec(List.of(column("deployment"), alias(column(CLIENT_TYPE), "client_type")),
                List.of(measure("turn_count", "count", null)));

        final String sql = refresh(spec, NO_FILTER);

        assertThat(dirtyKeyIns(sql)).isEqualTo(1);
        assertThat(sql).doesNotContain(RESTRICTED).containsIgnoringCase(JOIN + " on");
    }

    @Test
    void sourceGrainWithEnrichmentFilterIsNotRestrictedAndStaysUnkeyed() {
        final AggregateSpec spec = spec(List.of(column("chat_id")), List.of(measure("turn_count", "count", null)));

        final String sql = refresh(spec, filter("length(\"usage_client_identity\".\"client_session_id\") > 0", SESSION));

        assertThat(dirtyKeyIns(sql)).isEqualTo(1);
        // The filter names an enrichment column, so it cannot key the recompute's join: the join is bare.
        assertThat(sql).doesNotContain(RESTRICTED).containsIgnoringCase(JOIN + " on");
    }

    @Test
    void arrayEnrichmentKeyIsNotRestricted() {
        // An unmatched array reads [], not NULL; if [] were a dirty key, rows of other keys would count under it.
        final AggregateSpec spec = spec(List.of(alias(column(CLIENT_TAGS), "tags")),
                List.of(measure("turn_count", "count", null)));

        final String sql = refresh(spec, NO_FILTER);

        assertThat(dirtyKeyIns(sql)).isEqualTo(1);
        assertThat(sql).doesNotContain(RESTRICTED);
    }

    @Test
    void secondEnrichmentReadOnlyByMeasureIsJoinedAsBefore() {
        final AggregateSpec spec = spec(List.of(alias(column(SESSION), "session_id")),
                List.of(measure("answers", "count", DialUsageLogTestModel.payload("assembled_response"))));

        final String sql = refresh(spec, NO_FILTER);

        assertThat(dirtyKeyIns(sql)).isEqualTo(2);
        assertThat(sql.toLowerCase()).contains("left outer join \"dial_usage_log_payload\" on");
    }

    @Test
    void previewOfAnEnrichmentGrainCarriesNoRestriction() {
        final AggregateSpec spec = spec(List.of(alias(column(SESSION), "session_id")),
                List.of(measure("turn_count", "count", null)));

        final String sql = compiler.compilePreview(spec, keyedEnrichment, CURSOR, NO_FILTER, Map.of(), 20).getSQL();

        assertThat(sql).doesNotContain("select distinct").containsIgnoringCase(JOIN + " on");
    }
}
