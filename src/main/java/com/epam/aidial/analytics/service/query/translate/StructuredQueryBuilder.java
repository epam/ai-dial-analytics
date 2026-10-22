package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryFinalProperties;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.EnrichmentJoins;
import com.epam.aidial.analytics.service.query.FinalDecision;
import com.epam.aidial.analytics.service.query.FinalTables;
import com.epam.aidial.analytics.service.query.ObjectPathUsage;
import com.epam.aidial.analytics.service.query.PartitionPreselection;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.WildcardProjection;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.CursorPage;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.NullsOrder;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.OutputColumnNaming;
import com.epam.aidial.analytics.service.query.model.PageSpec;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.SelectQuery;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles a complete jOOQ {@link SelectQuery} from a {@link StructuredQuery} for a resolved
 * {@link QueryEntityModel}. Built on jOOQ's mutable model API ({@link DSLContext#selectQuery()}),
 * which suits dynamic construction far better than the fluent step API. Supports both query modes:
 *
 * <ul>
 *   <li><strong>row</strong> — projection from {@code select} (or, when empty, the wildcard expansion:
 *       the source's non-heavy columns, see {@code WildcardProjection}), {@code filter}, {@code sort},
 *       {@code page};
 *   <li><strong>aggregate</strong> — {@code group_by} keys plus aggregate-function {@code select}
 *       entries, with {@code having} and {@code sort} resolved against group keys and the resolved
 *       select output names.
 * </ul>
 *
 * <p>Output names come from {@link OutputColumnNaming}: an entry's {@code as} when present, else the
 * PostgreSQL-shaped default for its expression. {@code as} is never required, in either mode. Every
 * entry of a non-empty {@code select} is rendered with an explicit alias carrying that name, so the
 * column names ClickHouse returns are exactly the ones the response contract promises.
 *
 * <p>A source's enrichments are LEFT JOINed on demand: only the enrichments whose exposed columns the
 * query actually references (across {@code select}/{@code filter}/{@code sort}/{@code group_by}) are
 * joined. Joins are single-key with 0..1 cardinality, so they never change the source's row
 * multiplicity. The {@link DSLContext} is the connection-less CLICKHOUSE render context — this builder
 * never executes, only models the query for later rendering. Pagination is offset-only; cursor paging
 * is rejected with a clear message, and a limit outside {@code [0, 1000]} is rejected rather than
 * silently clamped ({@code 0} or an omitted page applies the default limit).
 *
 * <p>Whether the entity is read {@code FINAL} is decided here by {@link FinalDecision} — from the query's
 * {@code final}, the source's write discipline and the {@code query.force-final} migration flag — and
 * rendered as the {@code FINAL} modifier on the source (when its engine collapses versions) and on every
 * enrichment the query joins. This builder
 * serves the user-facing surfaces only; the pipeline reads assemble their own statements and deduplicate
 * through the {@code final = 1} setting instead.
 *
 * <p>On the single-entity read path ({@link #build(DSLContext, QueryEntityModel, StructuredQuery, int)} and
 * {@link #buildCount}) the statement is additionally <em>shaped</em> so a heavy enrichment column costs what the
 * statement returns or keeps rather than what its window holds — late materialization of projection-only heavy
 * columns, and pushdown of allow-listed enrichment conjuncts and heavy-column calls into the derived tables. The
 * rules are {@link ReadShaping}'s; every one of them returns exactly the unshaped statement's result, which
 * {@link #withoutReadShaping()} renders for comparison.
 */
@Component
public class StructuredQueryBuilder {

    /** Row limit applied when a request omits one; public so surfaces can name it in their messages. */
    public static final int DEFAULT_LIMIT = 100;

    /** Hard ceiling on a requested row limit; a request above it is rejected, never clamped. */
    public static final int MAX_LIMIT = 1000;

    /**
     * How a block's {@code page} is rendered. {@link #GOVERNED} is the shared row governance every
     * caller-facing statement runs under: an absent limit becomes {@link #DEFAULT_LIMIT} and one above the
     * ceiling is refused. {@link #NESTED} is for a block inside a composite SQL statement — a derived table,
     * a join operand, a subquery — whose {@code LIMIT} bounds an intermediate result rather than the rows
     * returned: it renders exactly what was stated, uncapped, and renders nothing when nothing was stated.
     */
    public enum LimitPolicy {
        GOVERNED,
        NESTED
    }

    /**
     * Extra conditions a caller adds to a block's own {@code WHERE} — and, for an enrichment the conditions can
     * restrict by its own columns, to that enrichment's grain join. The aggregate pipeline's SQL compiler passes one:
     * its dirty-key restriction must reach the grain join, or the join is built from the whole enrichment on every
     * refresh (see {@link EnrichmentJoins}'s note on restrictions). The read path passes its partition
     * pre-selections as {@code preselected}.
     *
     * @param conditions   conjuncts appended after the block's own filter
     * @param restrictions a condition over one enrichment's columns alone, by that enrichment's table name, which
     *     then becomes its derived table's only {@code WHERE}; each must select a superset of the enrichment rows the
     *     block reads
     * @param preselected  source-only partition pre-selections ({@link PartitionPreselection}), appended to the
     *     {@code WHERE} like {@code conditions} and also keying every join without an aligned partition pair
     */
    public record LeafRestriction(List<Condition> conditions, Map<String, Condition> restrictions, List<Condition> preselected) {

        /** Nothing added: the block renders exactly as the overloads without one render it. */
        public static final LeafRestriction NONE = new LeafRestriction(List.of(), Map.of());

        public LeafRestriction {
            conditions = List.copyOf(conditions);
            restrictions = Map.copyOf(restrictions);
            preselected = List.copyOf(preselected);
        }

        /** Conditions and restrictions with no pre-selection. */
        public LeafRestriction(List<Condition> conditions, Map<String, Condition> restrictions) {
            this(conditions, restrictions, List.of());
        }
    }

    private final ExprTranslator exprTranslator;
    private final FilterTranslator filterTranslator;
    private final boolean forceFinal;
    private final boolean readShaping;
    private final boolean pathPruning;
    private final boolean windowTransfer;

    @Autowired
    public StructuredQueryBuilder(
            ExprTranslator exprTranslator, FilterTranslator filterTranslator, QueryFinalProperties finalProperties) {
        this(exprTranslator, filterTranslator, finalProperties.forceFinal());
    }

    /** A builder that honours each query's own {@code final} ({@code query.force-final} off). */
    public StructuredQueryBuilder(ExprTranslator exprTranslator, FilterTranslator filterTranslator) {
        this(exprTranslator, filterTranslator, false);
    }

    public StructuredQueryBuilder(ExprTranslator exprTranslator, FilterTranslator filterTranslator, boolean forceFinal) {
        this(exprTranslator, filterTranslator, forceFinal, true, true, true);
    }

    private StructuredQueryBuilder(ExprTranslator exprTranslator, FilterTranslator filterTranslator, boolean forceFinal,
            boolean readShaping, boolean pathPruning, boolean windowTransfer) {
        this.exprTranslator = exprTranslator;
        this.filterTranslator = filterTranslator;
        this.forceFinal = forceFinal;
        this.readShaping = readShaping;
        this.pathPruning = pathPruning;
        this.windowTransfer = windowTransfer;
    }

    /**
     * This builder with read shaping switched off: {@link #build(DSLContext, QueryEntityModel, StructuredQuery, int)}
     * and {@link #buildCount} then render every block exactly as the unshaped overloads do. The reference the
     * shaping is proved against: its tests run both and compare what ClickHouse returns.
     */
    public StructuredQueryBuilder withoutReadShaping() {
        return new StructuredQueryBuilder(exprTranslator, filterTranslator, forceFinal, false, pathPruning, windowTransfer);
    }

    /**
     * This builder with object-path pruning switched off: every statement declares no {@code object}-path usage, so
     * its grain joins project whole columns as they did before the query surface declared any. The reference the
     * pruning is proved against: its tests run both and compare what ClickHouse returns.
     */
    public StructuredQueryBuilder withoutPathPruning() {
        return new StructuredQueryBuilder(exprTranslator, filterTranslator, forceFinal, readShaping, false, windowTransfer);
    }

    /**
     * This builder with the window transfer switched off: every keyed aligned join takes its two key sets, as it did
     * before an aligned join could be bounded by the source's partition-column bounds instead. The reference the
     * transfer is proved against: its tests run both and compare what ClickHouse returns.
     */
    public StructuredQueryBuilder withoutWindowTransfer() {
        return new StructuredQueryBuilder(exprTranslator, filterTranslator, forceFinal, readShaping, pathPruning, false);
    }

    /**
     * A builder whose translators render subqueries through {@code subqueries}. Only the composite SQL
     * renderer asks for one; the shared bean refuses every subquery.
     */
    public StructuredQueryBuilder withSubqueryRenderer(SubqueryRenderer subqueries) {
        return new StructuredQueryBuilder(
                exprTranslator.withSubqueryRenderer(subqueries), filterTranslator.withSubqueryRenderer(subqueries),
                forceFinal, readShaping, pathPruning, windowTransfer);
    }

    /**
     * Whether {@code query} reads {@code model} {@code FINAL} when this builder renders it — the decision the
     * executor needs to know whether the statement's partition-local switch applies.
     *
     * @throws com.epam.aidial.analytics.exception.IllegalFinalException when the query asks for
     *     {@code FINAL} on a source that does not collapse versions
     */
    public boolean readsFinal(QueryEntityModel model, StructuredQuery query) {
        return FinalDecision.readsFinal(model, query.finalRead(), forceFinal);
    }

    /** The filter translator this builder renders predicates with — the composite renderer needs it for join conditions. */
    FilterTranslator filterTranslator() {
        return filterTranslator;
    }

    /**
     * Builds the select for {@code query} against {@code model} using its bindings and enrichments,
     * under the shared row governance (default {@value #DEFAULT_LIMIT}, maximum {@value #MAX_LIMIT}).
     */
    public SelectQuery<Record> build(DSLContext dsl, QueryEntityModel model, StructuredQuery query) {
        return build(dsl, model, query, MAX_LIMIT);
    }

    /**
     * As {@link #build(DSLContext, QueryEntityModel, StructuredQuery)} but with {@code maxLimit} as the
     * caller's own row ceiling instead of the shared {@value #MAX_LIMIT}. Only a transport that governs
     * its own ceiling passes this — the Arrow Flight SQL endpoint, whose {@code flight.max-rows} replaces
     * the shared maximum because it streams Arrow batches under a bounded per-request allocator rather
     * than materializing a JSON document. Every other caller uses the three-argument form and is
     * therefore unaffected.
     *
     * <p>This overload (and the three-argument form that delegates to it) is the single-entity read path, so it is
     * where the statement is <em>shaped</em> (see {@link ReadShaping}): a projection-only heavy enrichment column
     * is fetched for the returned page only, an allow-listed enrichment-only conjunct also narrows its
     * enrichment's derived table, and an allow-listed call over a heavy text column is computed inside it. The
     * other overloads serve composite blocks and the pipeline compiler and render unshaped.
     */
    public SelectQuery<Record> build(
            DSLContext dsl, QueryEntityModel model, StructuredQuery query, int maxLimit) {
        if (!readShaping) {
            return build(dsl, model, query, maxLimit, LimitPolicy.GOVERNED);
        }
        try {
            return shaped(dsl, model, query, maxLimit);
        } catch (QueryValidationException ex) {
            // Shaping translates parts of the query ahead of the statement; whatever it trips over, the unshaped
            // build trips over too, and reporting that build's refusal keeps every message what it always was.
            return build(dsl, model, query, maxLimit, LimitPolicy.GOVERNED);
        }
    }

    /**
     * As {@link #build(DSLContext, QueryEntityModel, StructuredQuery, int)} under an explicit
     * {@link LimitPolicy}. {@link LimitPolicy#NESTED} is reached only from the composite SQL renderer, for
     * a block that is not the statement's root.
     */
    public SelectQuery<Record> build(
            DSLContext dsl, QueryEntityModel model, StructuredQuery query, int maxLimit, LimitPolicy policy) {
        return build(dsl, model, query, maxLimit, policy, List.of());
    }

    /**
     * As {@link #build(DSLContext, QueryEntityModel, StructuredQuery, int, LimitPolicy)}, with {@code keyConjuncts}
     * ANDed into the block's {@code WHERE} and also handed to its grain joins as key predicates. Each must reference
     * the block's source columns alone. Only the composite renderer passes them: a join operand reduced to the keys
     * its partner can produce (see {@link CompositeQueryBuilder}).
     */
    SelectQuery<Record> build(DSLContext dsl, QueryEntityModel model, StructuredQuery query, int maxLimit,
            LimitPolicy policy, List<Condition> keyConjuncts) {
        return render(dsl, model, query, maxLimit, policy, LeafRestriction.NONE, Map.of(), false, true, keyConjuncts);
    }

    /**
     * As {@link #build(DSLContext, QueryEntityModel, StructuredQuery, int, LimitPolicy)}, with {@code extra} ANDed
     * into the block's {@code WHERE} after its own filter and its restrictions handed to the grain joins.
     *
     * <p>The aggregate pipeline's overload, so it declares no object-path usage and its grain joins project whole
     * {@code object} columns: its preview appends select items of its own after this returns, which no usage
     * computed here could account for. Nor does it transfer a window-only key onto an aligned join: the refresh and
     * its preview keep both key sets, as the {@code structured-query} spec requires (nothing was measured for them).
     */
    public SelectQuery<Record> build(DSLContext dsl, QueryEntityModel model, StructuredQuery query, int maxLimit,
            LimitPolicy policy, LeafRestriction extra) {
        final StructuredQueryBuilder renderer = windowTransfer ? withoutWindowTransfer() : this;
        return renderer.render(dsl, model, query, maxLimit, policy, extra, Map.of(), false, false, List.of());
    }

    /**
     * The shaped single-entity statement (see {@link ReadShaping} and the {@code structured-query} spec's
     * late-materialization, conjunct-pushdown and call-pushdown requirements).
     */
    private SelectQuery<Record> shaped(DSLContext dsl, QueryEntityModel model, StructuredQuery query, int maxLimit) {
        final long[] page = ReadShaping.governedPage(query.page(), DEFAULT_LIMIT, maxLimit);
        final ReadShaping.LatePlan late = page == null ? null : ReadShaping.latePlan(model, query, page[0], (int) page[1]);
        final Set<String> lateTables = late == null ? Set.of() : late.tables();

        final List<Expr> exprs = new ArrayList<>();
        if (query.select() != null) {
            query.select().forEach(column -> exprs.add(column.expr()));
        }
        final Set<String> rawNames = new HashSet<>();
        if (query.sort() != null) {
            query.sort().forEach(item -> rawNames.add(item.field()));
        }
        if (query.groupBy() != null) {
            rawNames.addAll(query.groupBy());
        }
        final Pushdown pushdown = pushdown(model, query.filter(), exprs, query.having(), rawNames, lateTables);
        final StructuredQueryBuilder statement = pushdown.builder(this);
        if (late == null) {
            return statement.render(dsl, model, query, maxLimit, LimitPolicy.GOVERNED, pushdown.restriction(),
                    pushdown.shaping(), false);
        }

        // The page block: the same block without its heavy entries, cut at offset + limit rows, projecting the
        // page's keys. Its light joins are shaped as the outer statement's are; the late ones are joined as today.
        final StructuredQuery pageQuery = new StructuredQuery(query.entity(), query.filter(), QueryMode.ROW, false,
                late.pageEntries(), null, null, query.sort(), new OffsetPage(0, late.pageRows(), false), query.finalRead());
        final SelectQuery<Record> pageBlock = statement.render(dsl, model, pageQuery, late.pageRows(), LimitPolicy.NESTED,
                pushdown.restriction(), pushdown.shaping(), true);
        final EnrichmentJoin keyJoin = late.keyJoin();
        pageBlock.addSelect(keyJoin.sourceKey().as(ReadShaping.PAGE_KEY));
        final List<Field<?>> keyColumns = new ArrayList<>(2);
        keyColumns.add(DSL.field(DSL.name(ReadShaping.PAGE_KEY)));
        if (keyJoin.partitionAligned()) {
            pageBlock.addSelect(keyJoin.sourcePartition().as(ReadShaping.PAGE_PARTITION));
            keyColumns.add(DSL.field(DSL.name(ReadShaping.PAGE_PARTITION)));
        }
        final Select<?> pageKeys = DSL.select(keyColumns).from(pageBlock.asTable(ReadShaping.PAGE_ALIAS));

        // The outer statement: the block as written, kept to the page's rows, its heavy enrichments joined for the
        // page's keys. Both references to pageKeys stand in this one scope, so ClickHouse evaluates it once.
        final Map<String, EnrichmentJoins.Shaping> outer = new HashMap<>(pushdown.shaping());
        for (final String table : lateTables) {
            outer.put(table, new EnrichmentJoins.Shaping(List.of(), List.of(), Set.of(), pageKeys));
        }
        final Condition onPage = keyJoin.partitionAligned()
                ? DSL.condition("({0}, {1}) IN ({2})", keyJoin.sourceKey(), keyJoin.sourcePartition(), pageKeys)
                        .or(keyJoin.sourceKey().isNull()).or(keyJoin.sourcePartition().isNull())
                : DSL.condition("{0} IN ({1})", keyJoin.sourceKey(), pageKeys).or(keyJoin.sourceKey().isNull());
        // The partition pre-selections restrict the page block only. The outer statement is already confined to the
        // page's keys, which ClickHouse carries into every aligned join as well, so a copy here would only evaluate
        // each pre-selection a second time — its own scope — and prune nothing more.
        return statement.render(dsl, model, query, maxLimit, LimitPolicy.GOVERNED,
                new LeafRestriction(List.of(onPage), Map.of()), outer, false);
    }

    /**
     * The conjunct and call pushdown for one statement: the per-enrichment shaping its joins take, and the
     * substitutions its expressions and filter conjuncts render through.
     */
    private record Pushdown(Map<String, EnrichmentJoins.Shaping> shaping, Map<Expr, Field<?>> calls,
            Map<FilterNode, Condition> conjuncts, List<Condition> preselected) {

        /** The partition pre-selections, as the restriction the statement's {@code WHERE} and grain joins take. */
        LeafRestriction restriction() {
            return preselected.isEmpty() ? LeafRestriction.NONE : new LeafRestriction(List.of(), Map.of(), preselected);
        }

        /** The builder the statement renders with: {@code base} itself, or one substituting what was pushed. */
        StructuredQueryBuilder builder(StructuredQueryBuilder base) {
            if (calls.isEmpty() && conjuncts.isEmpty()) {
                return base;
            }
            final ExprTranslator expressions = base.exprTranslator.withPushedCalls(calls);
            return new StructuredQueryBuilder(expressions, base.filterTranslator.withPushed(expressions, conjuncts),
                    base.forceFinal, false, base.pathPruning, base.windowTransfer);
        }
    }

    /**
     * Plans and translates the conjunct pushdown over {@code filter} and the call pushdown over {@code exprs},
     * {@code filter} and {@code having}, leaving the {@code excluded} enrichments alone. What is pushed is translated
     * here, with this builder's own translators, because it renders inside the derived table over the raw columns.
     *
     * <p>A heavy field the statement reads only through pushed conjuncts and pushed calls is not projected by its
     * derived table at all: each pushed conjunct over it is computed there as a column, beside its {@code WHERE}
     * copy, and the statement's own conjunct reads that column. On a matched row the column holds the conjunct's
     * value, which the copy has made true; on an unmatched row it reads its type default ({@code 0}) or NULL, both
     * rejected — exactly what the conjunct gives an unmatched row, since every pushable conjunct is false there. So
     * the build holds a flag per matching row instead of the body it was decided on.
     */
    private Pushdown pushdown(QueryEntityModel model, FilterNode filter, List<Expr> exprs, FilterNode having,
            Set<String> rawNames, Set<String> excluded) {
        final Map<String, QueryFieldBinding> bindings = model.bindings();
        final List<ReadShaping.PushedConjunct> conjuncts = ReadShaping.pushableConjuncts(model, filter, excluded);
        // The call plan reads everything except the pushed conjuncts themselves: a field they name is still read
        // raw only if something else reads it.
        final List<FilterNode> remaining = new ArrayList<>(conjuncts(filter));
        conjuncts.forEach(conjunct -> remaining.removeIf(node -> node == conjunct.node()));
        remaining.add(having);
        final ReadShaping.CallPlan plan = ReadShaping.planCalls(model, exprs, remaining, rawNames, excluded, 0);

        final Map<String, List<Condition>> where = new HashMap<>();
        final Map<String, List<Field<?>>> computed = new HashMap<>();
        final Map<String, Set<String>> omitted = new HashMap<>();
        final Map<Expr, Field<?>> calls = new HashMap<>();
        for (final ReadShaping.PushedCall call : plan.calls()) {
            final Field<?> translated = exprTranslator.toField(call.call(), bindings);
            computed.computeIfAbsent(call.table(), ignored -> new ArrayList<>()).add(translated.as(call.alias()));
            omitted.computeIfAbsent(call.table(), ignored -> new HashSet<>()).add(call.field());
            calls.put(call.call(), DSL.field(DSL.name(call.table(), call.alias()), translated.getDataType()));
        }
        final Map<FilterNode, Condition> substituted = new HashMap<>();
        final List<Condition> preselected = new ArrayList<>();
        int next = plan.calls().size();
        for (final ReadShaping.PushedConjunct conjunct : conjuncts) {
            final Condition condition = filterTranslator.toCondition(conjunct.node(), bindings);
            where.computeIfAbsent(conjunct.table(), ignored -> new ArrayList<>()).add(condition);
            // A lookup on an aligned enrichment also names the partitions its rows live in (PartitionPreselection),
            // bounded by whatever the filter already bounds the source's partition column to.
            if (ReadShaping.lookup(model, conjunct.node())) {
                final EnrichmentJoin owner = model.ownerOf(conjunct.field());
                PartitionPreselection.of(owner, condition, ReadShaping.partitionBounds(filterTranslator, model, owner, filter))
                        .ifPresent(preselected::add);
            }
            if (ReadShaping.heavy(model, conjunct.field()) && !plan.raw().contains(conjunct.field())) {
                final String alias = ReadShaping.CALL_PREFIX + next++;
                computed.computeIfAbsent(conjunct.table(), ignored -> new ArrayList<>()).add(DSL.field(condition).as(alias));
                omitted.computeIfAbsent(conjunct.table(), ignored -> new HashSet<>()).add(conjunct.field());
                substituted.put(conjunct.node(), DSL.condition(DSL.field(DSL.name(conjunct.table(), alias), Boolean.class)));
            }
        }
        final Map<String, EnrichmentJoins.Shaping> shaping = new HashMap<>();
        final Set<String> tables = new HashSet<>(where.keySet());
        tables.addAll(computed.keySet());
        for (final String table : tables) {
            shaping.put(table, new EnrichmentJoins.Shaping(where.getOrDefault(table, List.of()),
                    computed.getOrDefault(table, List.of()), omitted.getOrDefault(table, Set.of()), null));
        }
        return new Pushdown(shaping, calls, substituted, preselected);
    }

    /** The block's statement as the single-entity read path renders it: declaring its object paths, no key conjuncts. */
    private SelectQuery<Record> render(DSLContext dsl, QueryEntityModel model, StructuredQuery query, int maxLimit,
            LimitPolicy policy, LeafRestriction extra, Map<String, EnrichmentJoins.Shaping> shaping, boolean pageBlock) {
        return render(dsl, model, query, maxLimit, policy, extra, shaping, pageBlock, true, List.of());
    }

    /**
     * The block's statement. With {@code declarePaths} the block declares which {@code object} paths it addresses,
     * so a grain join emitted as a derived table projects those subcolumns instead of the whole column, and every
     * clause is rendered against the model {@link EnrichmentJoins#addReferenced} returns — the only model whose
     * bindings know a pruned path lives under an alias of that derived table. The key predicates are translated
     * against {@code model} itself: they render inside the key subquery, over the source, and name no enrichment
     * column for pruning to change.
     */
    private SelectQuery<Record> render(DSLContext dsl, QueryEntityModel model, StructuredQuery query, int maxLimit,
            LimitPolicy policy, LeafRestriction extra, Map<String, EnrichmentJoins.Shaping> shaping, boolean pageBlock,
            boolean declarePaths, List<Condition> keyConjuncts) {
        final boolean finalRead = readsFinal(model, query);
        final SelectQuery<Record> select = dsl.selectQuery();
        // The source takes FINAL only when its own engine collapses; ClickHouse refuses it on a plain MergeTree,
        // whose entity may still read final through its enrichments.
        select.addFrom(FinalTables.of(model.table(), finalRead && model.collapsing()));
        // A window is computed over the block's result, after every predicate has run: SQL forbids it in
        // WHERE and HAVING, and so does the DSL, rather than let ClickHouse phrase the refusal.
        WindowExpressions.rejectInFilter(query.filter(), "'filter'");
        WindowExpressions.rejectInFilter(query.having(), "'having'");
        rejectIncludeTotalWithExpansion(query);
        final Condition filter = filterTranslator.toCondition(query.filter(), model.bindings());
        final List<Condition> keys = new ArrayList<>(keyPredicates(model, query, filter));
        keys.addAll(keyConjuncts);
        final QueryEntityModel effective = EnrichmentJoins.addReferenced(select, model, referencedFields(query), keys,
                extra.preselected(), extra.restrictions(), shaping, transferredBounds(model, query.filter(), keyConjuncts),
                declarePaths && pathPruning ? objectPaths(model, query) : ObjectPathUsage.none(), finalRead);
        select.addConditions(effective == model ? filter : filterTranslator.toCondition(query.filter(), effective.bindings()));
        select.addConditions(keyConjuncts);
        select.addConditions(extra.conditions());
        select.addConditions(extra.preselected());

        final QueryMode mode = query.mode();
        // Names that are select outputs (aliases / projected fields), so sort can reference the output
        // column instead of re-translating its expression.
        final Set<String> selectAliases = new HashSet<>();
        final Map<String, QueryFieldBinding> sortBindings = mode == QueryMode.AGGREGATE
                ? buildAggregate(select, effective.bindings(), query, selectAliases)
                : buildRow(select, effective, query, selectAliases, pageBlock);

        if (query.distinct()) {
            select.setDistinct(true);
        }
        select.addOrderBy(sortFields(query.sort(), sortBindings, selectAliases));
        applyPage(select, query.page(), maxLimit, policy);
        return select;
    }

    /**
     * Builds the {@code SELECT count(*)} query matching {@code query}'s filter, for offset
     * {@code include_total} (row mode only; ignores paging/projection). Referenced enrichments are
     * joined; a single-key 0..1 LEFT JOIN does not change the source row count.
     */
    public SelectQuery<Record> buildCount(DSLContext dsl, QueryEntityModel model, StructuredQuery query) {
        if (!readShaping) {
            return count(dsl, model, query, Map.of(), List.of(), false);
        }
        try {
            // Its filter is the only place a count reads an enrichment column, so it takes the conjunct and call
            // pushdown over the filter, and never late materialization: it projects nothing to fetch late.
            final Pushdown pushdown = pushdown(model, query.filter(), List.of(), null, Set.of(), Set.of());
            return pushdown.builder(this).count(dsl, model, query, pushdown.shaping(), pushdown.preselected(), true);
        } catch (QueryValidationException ex) {
            return count(dsl, model, query, Map.of(), List.of(), false);
        }
    }

    /**
     * The count statement. With {@code filterJoinsOnly}, a count read final joins only the enrichments its filter
     * references: read final, every grain join is 0..1, so one the filter does not read cannot change the count,
     * and joining it would only build its referenced columns — a page's heavy body, say — over the whole window.
     * Read raw, an enrichment key with unmerged versions fans its source row out in the page, so the count keeps
     * every join the page has and counts the same rows.
     */
    private SelectQuery<Record> count(DSLContext dsl, QueryEntityModel model, StructuredQuery query,
            Map<String, EnrichmentJoins.Shaping> shaping, List<Condition> preselected, boolean filterJoinsOnly) {
        final SelectQuery<Record> count = dsl.selectQuery();
        final boolean finalRead = readsFinal(model, query);
        count.addSelect(DSL.count());
        count.addFrom(FinalTables.of(model.table(), finalRead && model.collapsing()));
        final Condition filter = filterTranslator.toCondition(query.filter(), model.bindings());
        // count(*) projects no columns, so only enrichments a clause actually references are joined.
        final Set<String> joined = filterJoinsOnly && finalRead ? referencedFields(query.filter()) : referencedFields(query);
        // The filter is the only clause the count renders, so it alone declares the paths the joins project.
        final ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        if (pathPruning) {
            filterTranslator.collectObjectPaths(query.filter(), model.bindings(), usage);
        }
        final QueryEntityModel effective = EnrichmentJoins.addReferenced(count, model, joined,
                keyPredicates(model, query, filter), preselected, Map.of(), shaping,
                transferredBounds(model, query.filter(), List.of()), usage.build(), finalRead);
        count.addConditions(effective == model ? filter : filterTranslator.toCondition(query.filter(), effective.bindings()));
        count.addConditions(preselected);
        return count;
    }

    /**
     * The {@code object} paths {@code query} addresses across every clause this builder renders against the joined
     * enrichments: the select expressions, the filter and the having through the conservative expression walk, and
     * the sort and group-by keys, which name a column and so need it whole when it is an {@code object}. A pushed
     * conjunct or call is covered by the same walk over the clause it came from: its derived-table copy reads the
     * real column, and the statement's own copy reads whatever the walk declared for it.
     */
    private ObjectPathUsage objectPaths(QueryEntityModel model, StructuredQuery query) {
        final Map<String, QueryFieldBinding> bindings = model.bindings();
        final ObjectPathUsage.Builder usage = ObjectPathUsage.builder();
        if (query.select() != null) {
            query.select().forEach(column -> exprTranslator.collectObjectPaths(column.expr(), bindings, usage));
        }
        filterTranslator.collectObjectPaths(query.filter(), bindings, usage);
        filterTranslator.collectObjectPaths(query.having(), bindings, usage);
        final Set<String> named = new HashSet<>();
        if (query.sort() != null) {
            query.sort().forEach(item -> named.add(item.field()));
        }
        if (query.groupBy() != null) {
            named.addAll(query.groupBy());
        }
        return usage.markWholeObjects(named, bindings).build();
    }

    /**
     * The predicates that may key the block's grain joins: the source-only top-level conjuncts of its
     * {@code filter} (see {@link #sourceOnlyConjuncts}). A conjunct that reaches into an enrichment cannot
     * travel into the key subquery — it would need that very join there — so it is left out, and only it:
     * the source conjuncts beside it still key every join. Any subset of the outer {@code WHERE}'s conjuncts
     * is a sound key, whatever the enrichment conjuncts are ({@code IS NULL} and {@code coalesce} included):
     * every row the outer {@code WHERE} keeps passes the subset, so its enrichment row is still in the derived
     * table and its {@code LEFT JOIN} result is unchanged. A filter that is source-only throughout keeps its
     * whole translated condition, rendering exactly as before; a block with no filter, or none of whose
     * conjuncts is source-only, hands back nothing and the joins are emitted unkeyed. See
     * {@link EnrichmentJoins} for why keying matters: without it a lookup naming one enrichment column
     * hashes the whole enrichment table.
     */
    private List<Condition> keyPredicates(QueryEntityModel model, StructuredQuery query, Condition filter) {
        if (query.filter() == null || filter == null) {
            return List.of();
        }
        if (EnrichmentJoins.referencesSourceOnly(model, referencedFields(query.filter()))) {
            return List.of(filter);
        }
        final List<Condition> keys = new ArrayList<>();
        for (final FilterNode conjunct : sourceOnlyConjuncts(model, query.filter())) {
            keys.add(filterTranslator.toCondition(conjunct, model.bindings()));
        }
        return keys;
    }

    /**
     * By enrichment table name, the bounds that restrict each keyed aligned join of the block in place of its two key
     * sets: {@code filter}'s bounds on that join's source partition column, rendered on its alignment column (see
     * {@link #partitionBounds}), when they are <em>every</em> source-only conjunct of {@code filter} and no key conjunct
     * was handed in. Then the bounds select every enrichment row an admitted source row can match, and the key sets
     * would only restate them at the cost of building ~2M-value sets over a month's window. Any other source conjunct
     * keeps the sets: a lookup on another column gets its one-granule pruning from the grain-key set, which a range
     * cannot give. Empty when the transfer is switched off.
     */
    private Map<String, List<Condition>> transferredBounds(QueryEntityModel model, FilterNode filter,
            List<Condition> keyConjuncts) {
        if (!windowTransfer || filter == null || !keyConjuncts.isEmpty()) {
            return Map.of();
        }
        final int sourceOnly = sourceOnlyConjuncts(model, filter).size();
        if (sourceOnly == 0) {
            return Map.of();
        }
        final Map<String, List<Condition>> transferred = new HashMap<>();
        for (final EnrichmentJoin join : model.enrichments()) {
            if (!join.keyable() || !join.partitionAligned()) {
                continue;
            }
            // partitionBounds keeps a subset of the source-only conjuncts, so equal counts mean it kept them all.
            final List<Condition> bounds = ReadShaping.partitionBounds(filterTranslator, model, join, filter);
            if (bounds.size() == sourceOnly) {
                transferred.put(join.table().getName(), bounds);
            }
        }
        return transferred;
    }

    /**
     * {@code filter}'s bounds on {@code join}'s source partition column, rendered on the enrichment's alignment column
     * for a partition pre-selection over {@code join} to narrow its own read by (see {@link PartitionPreselection});
     * empty when nothing bounds it. Shared with the pipeline paths so a pre-selection is bounded the same way there.
     */
    public static List<Condition> partitionBounds(FilterTranslator translator, QueryEntityModel model, EnrichmentJoin join,
            FilterNode filter) {
        return ReadShaping.partitionBounds(translator, model, join, filter);
    }

    /**
     * The top-level conjuncts of {@code filter} that reference source columns only, in filter order. A
     * top-level {@code and} is flattened, recursing into nested {@code and}s only; an {@code or}, a
     * {@code not}, a comparison and a subquery predicate are each one conjunct, kept or dropped whole. Every
     * node returned is a subtree of {@code filter} that the filter's own {@code WHERE} requires to hold.
     */
    public static List<FilterNode> sourceOnlyConjuncts(QueryEntityModel model, FilterNode filter) {
        final List<FilterNode> conjuncts = new ArrayList<>();
        collectConjuncts(filter, conjuncts);
        final List<FilterNode> kept = new ArrayList<>(conjuncts.size());
        for (final FilterNode conjunct : conjuncts) {
            if (EnrichmentJoins.referencesSourceOnly(model, referencedFields(conjunct))) {
                kept.add(conjunct);
            }
        }
        return kept;
    }

    /**
     * The top-level conjuncts of {@code filter}, in filter order: a top-level {@code and} flattened, recursing into
     * nested {@code and}s only, every other node one conjunct. Empty for no filter.
     */
    static List<FilterNode> conjuncts(FilterNode filter) {
        final List<FilterNode> conjuncts = new ArrayList<>();
        collectConjuncts(filter, conjuncts);
        return conjuncts;
    }

    private static void collectConjuncts(FilterNode node, List<FilterNode> conjuncts) {
        if (node == null) {
            return;
        }
        if (node instanceof LogicalNode(LogicalOp op, List<FilterNode> args) && op == LogicalOp.AND) {
            if (args != null) {
                for (final FilterNode child : args) {
                    collectConjuncts(child, conjuncts);
                }
            }
            return;
        }
        conjuncts.add(node);
    }

    private Map<String, QueryFieldBinding> buildRow(
            SelectQuery<Record> select,
            QueryEntityModel model,
            StructuredQuery query,
            Set<String> selectAliases,
            boolean pageBlock) {
        final Map<String, QueryFieldBinding> bindings = model.bindings();
        final List<OutputColumn> projection = query.select();
        if (pageBlock && (projection == null || projection.isEmpty())) {
            // A page block keeps only the entries its sort names, possibly none; its caller adds the page keys, and
            // a wildcard expansion here would project columns nothing reads.
            return bindings;
        }
        if (projection == null || projection.isEmpty()) {
            // Omitted projection -> the wildcard expansion (the model has no jOOQ field metadata to expand
            // into a literal *): the source's own non-heavy columns. Enrichment columns are opt-in (a query
            // must name one to pull its enrichment in) and heavy columns are omitted by cost; both are
            // decided in one place, WildcardProjection, shared with the SQL surfaces.
            final List<String> wildcard = WildcardProjection.sourceFields(model);
            if (wildcard.isEmpty()) {
                throw new QueryValidationException(WildcardProjection.emptyExpansionMessage(model.entity()));
            }
            final List<Field<?>> all = new ArrayList<>(wildcard.size());
            for (final String name : wildcard) {
                all.add(bindings.get(name).field());
            }
            select.addSelect(all);
        } else {
            // Every entry is aliased with its resolved output name — including a field whose alias
            // equals its own name — so the rendered SQL, and therefore the column names ClickHouse
            // echoes back, always match the names the response contract promises.
            final List<String> names = OutputColumnNaming.resolve(projection);
            final List<Field<?>> fields = new ArrayList<>(projection.size());
            for (int i = 0; i < projection.size(); i++) {
                rejectWindowInsideCall(projection.get(i).expr());
                final Field<?> field = exprTranslator.toField(projection.get(i).expr(), bindings);
                final String name = names.get(i);
                fields.add(field.as(name));
                selectAliases.add(name);
            }
            select.addSelect(fields);
        }
        return bindings;
    }

    private Map<String, QueryFieldBinding> buildAggregate(
            SelectQuery<Record> select,
            Map<String, QueryFieldBinding> bindings,
            StructuredQuery query,
            Set<String> selectAliases) {
        final List<String> groupBy = query.groupBy() == null ? List.of() : query.groupBy();
        final List<OutputColumn> selectEntries = query.select() == null ? List.of() : query.select();
        if (selectEntries.isEmpty()) {
            throw new QueryValidationException(
                    "aggregate mode requires at least one select entry (group-key projection or aggregate call)");
        }

        // having may reference aggregate outputs as well as group keys, so expose both via aliasBindings;
        // group_by and sort may also reference a select output (e.g. a width_bucket expression named
        // "bucket"), so track output names for group-key and sort resolution.
        final Map<String, QueryFieldBinding> aliasBindings = new HashMap<>(bindings);
        final List<String> names = OutputColumnNaming.resolve(selectEntries);
        // Explicit aliases that shadow an entity column while projecting something else: a group_by key
        // naming one is ambiguous and is rejected below rather than silently resolved either way.
        final Set<String> shadowingAliases = new HashSet<>();
        final List<Field<?>> selectFields = new ArrayList<>();
        // A window in aggregate mode is computed over the grouped rows, so its operands must be group keys
        // or aggregates — checked against the group_by names and the aggregate outputs' aliases.
        final Set<String> groupKeySet = new HashSet<>(groupBy);
        final Set<String> aggregateAliases = new HashSet<>();
        for (int i = 0; i < selectEntries.size(); i++) {
            if (selectEntries.get(i).expr() instanceof FnExpr fn && WindowExpressions.isAggregate(fn.name())) {
                aggregateAliases.add(names.get(i));
            }
        }
        for (int i = 0; i < selectEntries.size(); i++) {
            final OutputColumn col = selectEntries.get(i);
            final String name = names.get(i);
            if (shadowsEntityColumn(col, name, bindings)) {
                shadowingAliases.add(name);
            }
            rejectWindowInsideCall(col.expr());
            // Every window the entry carries, not only one standing at its top level: a window nested in a
            // scalar call or in a `case` branch is computed over the same grouped rows and takes the same
            // operand rule.
            WindowExpressions.visit(col.expr(), node -> {
                if (node instanceof WindowExpr window) {
                    WindowExpressions.requireGroupedOperands(window, groupKeySet, aggregateAliases);
                }
            });
            if (col.expr() instanceof FieldExpr(String fieldName) && groupBy.contains(fieldName)) {
                final Field<?> field = exprTranslator.resolveField(fieldName, bindings);
                selectFields.add(field.as(name));
                selectAliases.add(name);
            } else {
                final Field<?> expr = exprTranslator.toField(col.expr(), bindings);
                selectFields.add(expr.as(name));
                // The alias carries the type its expression actually has, read by the same ExprFieldType
                // every other type rule uses. A fixed type here would be load-bearing in the wrong
                // direction: `having` is translated against these bindings, so every rule that reads an
                // operand's type — the text check the contains family applies, the numeric check
                // arithmetic applies, `equals`'s unification — would judge an alias by a type it never
                // had, refusing a contains filter over `max(model)` as a number. A null-literal
                // projection stays untyped, which is ExprFieldType's one null answer and what it is.
                final QueryFieldBinding binding =
                        new QueryFieldBinding(name, expr, ExprFieldType.of(col.expr(), bindings));
                if (col.as() == null || col.as().isBlank()) {
                    // A derived name must not shadow an entity column of the same name in `having`:
                    // PostgreSQL resolves having against input columns, so the column keeps the name.
                    aliasBindings.putIfAbsent(name, binding);
                } else {
                    aliasBindings.put(name, binding);
                }
                selectAliases.add(name);
            }
        }

        final List<Field<?>> groupFields = new ArrayList<>();
        for (final String key : groupBy) {
            groupFields.add(resolveGroupKey(key, bindings, selectAliases, shadowingAliases));
        }

        select.addSelect(selectFields);
        // A global aggregate has no GROUP BY at all. Passing an empty list to jOOQ would render the
        // "group by nothing" form GROUP BY (SELECT 1), which returns zero rows (instead of one) when
        // the filter matches nothing — standard SQL wants the single global-aggregate row.
        if (!groupFields.isEmpty()) {
            select.addGroupBy(groupFields);
        }
        if (query.having() != null) {
            select.addHaving(filterTranslator.toCondition(query.having(), aliasBindings));
        }
        return aliasBindings;
    }

    /**
     * True when {@code column} carries an <em>explicit</em> {@code as} alias that is also the name of an
     * entity column, while projecting something other than that very column. Grouping by such a name is
     * ambiguous — the caller deliberately named an output after a column that {@code GROUP BY} resolves
     * to instead — so {@link #resolveGroupKey} rejects it. An alias that merely repeats the name of the
     * field it projects ({@code {field: model, as: "model"}}) is not shadowing: both readings group by
     * the same column.
     */
    private boolean shadowsEntityColumn(OutputColumn column, String name, Map<String, QueryFieldBinding> bindings) {
        if (column.as() == null || column.as().isBlank() || exprTranslator.resolveFieldOrNull(name, bindings) == null) {
            return false;
        }
        return !(column.expr() instanceof FieldExpr(String field) && field.equals(name));
    }

    /**
     * Resolves a group-by key to the field to group by. A bound field wins: PostgreSQL resolves an
     * ambiguous {@code GROUP BY} name as an <em>input</em> column, so an entity column keeps the name
     * even when a select output (e.g. a derived aggregate name) shares it. A key that is not a bound
     * field but names a select output is grouped by its output-column reference
     * ({@code GROUP BY "bucket"}) rather than by re-translating the expression.
     *
     * <p>The one shape where those two readings differ <em>and</em> the caller cannot have meant the
     * column by accident — an explicit alias naming an entity column over a different expression
     * ({@code date_bin(…) as "request_time"} grouped by {@code request_time}) — fails closed: the DSL
     * document cannot say which of the two the caller means, the SQL surface rejects both statements
     * that map onto it, and the DSL→SQL rendering of the key would name the column
     * regardless. A different alias makes the intent expressible either way.
     */
    private Field<?> resolveGroupKey(
            String key,
            Map<String, QueryFieldBinding> bindings,
            Set<String> selectAliases,
            Set<String> shadowingAliases) {
        final Field<?> bound = exprTranslator.resolveFieldOrNull(key, bindings);
        if (bound != null) {
            if (shadowingAliases.contains(key)) {
                throw new QueryValidationException("group-by key '" + key + "' is ambiguous: it names a column of the"
                        + " entity and is also the explicit alias of a select entry projecting a different expression."
                        + " 'group_by' resolves such a name to the column (as in PostgreSQL); alias the select entry to"
                        + " a name that is not a column of the entity to group by its output instead");
            }
            return bound;
        }
        if (selectAliases.contains(key)) {
            return DSL.field(DSL.name(key));
        }
        throw new QueryValidationException("unknown group-by field '" + key + "'");
    }

    private List<SortField<?>> sortFields(
            List<SortItem> sort, Map<String, QueryFieldBinding> bindings, Set<String> selectAliases) {
        if (sort == null || sort.isEmpty()) {
            return List.of();
        }
        final List<SortField<?>> fields = new ArrayList<>(sort.size());
        for (final SortItem item : sort) {
            // A sort key naming a select output is ordered by its output-column reference rather than
            // by re-translating the expression.
            final Field<?> field = selectAliases.contains(item.field())
                    ? DSL.field(DSL.name(item.field()))
                    : exprTranslator.resolveField(item.field(), bindings);
            SortField<?> sortField = item.dir() == SortDir.DESC ? field.desc() : field.asc();
            // Null ordering is client-controlled; when unspecified we emit no NULLS clause
            // and let the database default apply (ASC → NULLS LAST, DESC → NULLS FIRST).
            if (item.nulls() == NullsOrder.FIRST) {
                sortField = sortField.nullsFirst();
            } else if (item.nulls() == NullsOrder.LAST) {
                sortField = sortField.nullsLast();
            }
            fields.add(sortField);
        }
        return fields;
    }

    private void applyPage(SelectQuery<Record> select, PageSpec page, int maxLimit, LimitPolicy policy) {
        switch (page) {
            case null -> {
                if (policy == LimitPolicy.GOVERNED) {
                    select.addLimit(DEFAULT_LIMIT);
                }
            }
            case OffsetPage offset -> {
                if (offset.offset() < 0) {
                    throw new QueryValidationException("page offset must not be negative");
                }
                if (policy == LimitPolicy.NESTED) {
                    // An inner block's bound is whatever it stated: no default, no ceiling. A stated OFFSET
                    // with no LIMIT is rendered as a bare OFFSET, which ClickHouse accepts.
                    if (offset.limit() < 0) {
                        throw new QueryValidationException("page limit must not be negative");
                    }
                    if (offset.limit() > 0 && offset.offset() > 0) {
                        select.addLimit(offset.offset(), offset.limit());
                    } else if (offset.limit() > 0) {
                        select.addLimit(offset.limit());
                    } else if (offset.offset() > 0) {
                        select.addOffset(offset.offset());
                    }
                    return;
                }
                select.addLimit(offset.offset(), validateLimit(offset.limit(), maxLimit));
            }
            case CursorPage ignored -> throw new QueryValidationException(
                    "cursor pagination is not supported by the query translator; use offset paging");
        }
    }

    /**
     * Refuses {@code include_total} on a <em>row-mode</em> query whose projection expands an array.
     *
     * <p>{@link #buildCount} counts <em>source</em> rows under the query's filter — it projects nothing, so
     * an {@code array_join} in the projection cannot reach it, and the number it produces is the count
     * before expansion. Reporting that as the total of an expanded page would answer a different question
     * than the one the page answers, and would do so plausibly: it is a real number, just not this result's.
     *
     * <p>Aggregate mode is exempt because there is no wrong number to ship there: the executor returns a
     * null total for an aggregate query without ever building a count, so {@code include_total} is already
     * inert. Refusing it would reject the very shape this function exists for — {@code array_join(...)}
     * aliased and named in {@code group_by} — for nothing but a flag many clients set uniformly, and would
     * do so with a message describing a count that is never taken.
     *
     * <p>This is the only guard {@code array_join} carries, and it is ours rather than the engine's —
     * ClickHouse has no {@code include_total} to narrow. Refusing rather than correcting: counting over the
     * expanded relation means a second full scan of it, for an opt-in nobody has asked for on this shape.
     * The refusal is additive to undo; a wrong total silently shipped is not.
     */
    private static void rejectIncludeTotalWithExpansion(StructuredQuery query) {
        if (query.mode() == QueryMode.AGGREGATE
                || !(query.page() instanceof OffsetPage offset)
                || !offset.includeTotal()
                || query.select() == null) {
            return;
        }
        for (final OutputColumn column : query.select()) {
            WindowExpressions.visit(column.expr(), node -> {
                if (node instanceof FnExpr fn && ArrayValuedFunctions.ARRAY_JOIN.equalsIgnoreCase(fn.name())) {
                    throw new QueryValidationException("'include_total' cannot be combined with '"
                            + ArrayValuedFunctions.ARRAY_JOIN + "': the total is counted over the source rows"
                            + " matching the filter, before the array is expanded, so it would not describe the"
                            + " rows this query returns. Drop 'include_total' to run the expansion");
                }
            });
        }
    }

    /**
     * A window may be a select entry, or sit inside a scalar call or a {@code case} in one — but never inside
     * an aggregate's arguments (it is computed after aggregation) or inside another window.
     */
    private static void rejectWindowInsideCall(Expr expr) {
        WindowExpressions.visit(expr, node -> {
            if (node instanceof FnExpr fn && WindowExpressions.isAggregate(fn.name()) && fn.args() != null) {
                for (final Expr arg : fn.args()) {
                    WindowExpressions.rejectNested(arg, "an aggregate function's arguments");
                }
            }
            if (node instanceof WindowExpr window) {
                WindowExpressions.rejectNested(window.fn(), "a window function's arguments");
                if (window.partitionBy() != null) {
                    window.partitionBy().forEach(key -> WindowExpressions.rejectNested(key, "PARTITION BY"));
                }
                if (window.orderBy() != null) {
                    for (final WindowOrder order : window.orderBy()) {
                        if (order != null) {
                            WindowExpressions.rejectNested(order.expr(), "a window ORDER BY");
                        }
                    }
                }
            }
        });
    }

    private static int validateLimit(int requested, int maxLimit) {
        if (requested < 0) {
            throw new QueryValidationException("page limit must not be negative");
        }
        if (requested == 0) {
            // 0 doubles as the "no explicit limit" sentinel (an omitted JSON limit and the SQL
            // translator's OFFSET-without-LIMIT both arrive here as 0).
            return DEFAULT_LIMIT;
        }
        if (requested > maxLimit) {
            throw new QueryValidationException(
                    "page limit " + requested + " exceeds the maximum of " + maxLimit + " rows");
        }
        return requested;
    }

    /** Every field name a filter tree references (a subquery predicate's operands included, its body not). */
    public static Set<String> referencedFields(FilterNode node) {
        final Set<String> names = new HashSet<>();
        collectFilter(node, names);
        return names;
    }

    /** Collects every field name a query references across filter, having, select, sort, and group_by. */
    public static Set<String> referencedFields(StructuredQuery query) {
        final Set<String> names = new HashSet<>();
        collectFilter(query.filter(), names);
        collectFilter(query.having(), names);
        if (query.select() != null) {
            for (final OutputColumn col : query.select()) {
                collectExpr(col.expr(), names);
            }
        }
        if (query.sort() != null) {
            for (final SortItem item : query.sort()) {
                if (item.field() != null) {
                    names.add(item.field());
                }
            }
        }
        if (query.groupBy() != null) {
            names.addAll(query.groupBy());
        }
        return names;
    }

    private static void collectFilter(FilterNode node, Set<String> names) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    for (final FilterNode child : logical.args()) {
                        collectFilter(child, names);
                    }
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    for (final Expr arg : comparison.args()) {
                        collectExpr(arg, names);
                    }
                }
            }
            case SubqueryNode subquery -> {
                // Uncorrelated by construction: only the operands reference this block's fields.
                if (subquery.operands() != null) {
                    for (final Expr arg : subquery.operands()) {
                        collectExpr(arg, names);
                    }
                }
            }
        }
    }

    private static void collectExpr(Expr expr, Set<String> names) {
        switch (expr) {
            case null -> { }
            case FieldExpr field -> names.add(field.name());
            case FnExpr fn -> {
                if (fn.args() != null) {
                    for (final Expr arg : fn.args()) {
                        collectExpr(arg, names);
                    }
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    for (final Expr item : array.items()) {
                        collectExpr(item, names);
                    }
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (branch != null) {
                            collectFilter(branch.when(), names);
                            collectExpr(branch.then(), names);
                        }
                    }
                }
                collectExpr(caseExpr.otherwise(), names);
            }
            case WindowExpr window -> {
                collectExpr(window.fn(), names);
                if (window.partitionBy() != null) {
                    for (final Expr key : window.partitionBy()) {
                        collectExpr(key, names);
                    }
                }
                if (window.orderBy() != null) {
                    for (final WindowOrder order : window.orderBy()) {
                        if (order != null) {
                            collectExpr(order.expr(), names);
                        }
                    }
                }
            }
            // A column captured inside a lambda body is an ordinary column read: it must reach this set, or
            // the enrichment it belongs to is never joined (EnrichmentJoins.addReferenced) while the body
            // still renders the qualified reference, and a filter reaching into an enrichment is misread as
            // source-only and pushed into the grain-join key subquery. The parameter itself is a
            // LambdaParamExpr, which names no column and stays on the default branch below — and a `field`
            // naming the parameter resolves to the parameter too (LambdaScope's shadowing rule), so the
            // body's names are collected apart and the parameters removed before they join the set.
            // Removing after the walk, rather than filtering during it, is what makes nesting right: an
            // inner lambda has already dropped its own parameter by the time the outer one drops its.
            case LambdaExpr lambda -> {
                final Set<String> captured = new HashSet<>();
                collectExpr(lambda.body(), captured);
                if (lambda.params() != null) {
                    lambda.params().forEach(captured::remove);
                }
                names.addAll(captured);
            }
            default -> { } // value / param expressions reference no fields; a scalar subquery is uncorrelated
        }
    }
}
