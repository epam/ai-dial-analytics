package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.HigherOrderFunctions;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.DerivedRelation;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.JoinKind;
import com.epam.aidial.analytics.service.query.model.JoinRelation;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.NullsOrder;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.OutputColumnNaming;
import com.epam.aidial.analytics.service.query.model.PageSpec;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.Relation;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Intersect;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps the validated relational plan of a supported SQL statement onto query blocks. One block is the
 * linear stack {@code Source → [Filter] → [Project] → [Aggregate] → [Filter(having)] → [Project] → [Sort]
 * → [Project(trim)]}, folded bottom-up while carrying the DSL expression for every intermediate field —
 * exactly the shape a {@link StructuredQuery} expresses. Where the plan does not fit one block, the block
 * so far is <b>sealed</b> into a derived table and a fresh block starts over it: a filter,
 * aggregation or second sort above a {@code Sort}, an aggregate over an aggregate or a {@code DISTINCT},
 * a projection that drops a computed sort key, a group key or a deduplication key — or that computes a
 * window or a row expansion above a deduplication — a {@code DISTINCT} over a block that computes a
 * window, and every join operand that carries clauses. A {@code Join} becomes a block over a join
 * relation whose operands are entities or sealed blocks; a {@code RexSubQuery} is folded into its own plan.
 *
 * <p>A statement that is one block over one entity with no subquery yields a {@link SqlTranslation} —
 * byte-identical to what it yielded before composite statements existed. Anything else yields a
 * {@link SqlCompositeTranslation}. Set operations, correlated subqueries and every other node kind fail
 * closed with a construct-naming error.
 */
final class RelToStructuredQueryTranslator {
    /**
     * The refusal {@code translate-sql} reports for a composite statement. It names the nesting the
     * translator performs as well as the nesting a client writes: every reason this class seals a block has
     * to appear in it, or a statement is told about a derived table it never wrote. Five reasons are
     * reachable without a client writing any nesting, and the message covers them in two clauses — a key
     * the projection drops, which is a computed {@code ORDER BY} expression
     * ({@link #dropsComputedSortKey}), a {@code GROUP BY} key ({@link #dropsComputedGroupKey}) or a
     * {@code DISTINCT} key ({@link #dropsDistinctKey}); and a window or row expansion that cannot share a
     * block with a {@code DISTINCT} — either of them above one ({@link #computesAheadOfDeduplication}), or
     * a window below one, as {@link #aggregateCtx}'s windowed-block seal handles. Three of those four
     * combinations seal and the fourth does not: an expansion <em>below</em> a {@code DISTINCT} shares the
     * block happily, because {@code SELECT DISTINCT array_join(x)} is exactly what that statement says.
     *
     * <p>Three wordings are deliberate.
     * "Computed" qualifies only the sort key, because a group or deduplication key is nested whether it is
     * computed or a bare column. The second clause is stated as a computation rather than as an unprojected
     * key, because those statements project every key they group by. And it says "separate ... from" rather
     * than "over", because the two live on opposite sides of the {@code DISTINCT} in the two cases and only
     * the separation is common to both: {@code SELECT project_id, row_number() OVER () AS rn ... GROUP BY
     * project_id} computes above the deduplication, {@code SELECT DISTINCT row_number() OVER () AS rn ...}
     * below it.
     */
    static final String NO_DSL_EQUIVALENT =
            "statements with set operations, joins, derived tables or subqueries — including a statement nested by this"
                    + " surface to carry an ORDER BY, GROUP BY or DISTINCT key its SELECT list does not project,"
                    + " or to separate a window function or a row expansion from a DISTINCT — execute but"
                    + " have no structured-query equivalent";

    /**
     * The refusal for a sort key that resolves neither against the projection nor against a source column.
     * Superseded for the shape {@link #dropsComputedSortKey} names — a computed key the projection drops is
     * sealed and re-expressed over the sealed block — so this remains only for a key that is genuinely
     * unresolvable.
     */
    private static final String ORDER_BY_UNRESOLVED =
            "an ORDER BY expression must be a column or appear in the SELECT list";

    /** Calcite's synthetic name for an output it had to name itself (see {@link #outputNames}). */
    private static final Pattern SYNTHESIZED_NAME = Pattern.compile("EXPR\\$\\d+");
    /**
     * The synthetic name a lambda parameter takes while an {@code arrayMap} body's kind is derived. Not a
     * legal column name, so it cannot collide with one the statement uses.
     */
    private static final String LAMBDA_PARAM_KIND_NAME = "__lambda_param";

    /** The two catalog functions whose result is an array rather than a scalar. */
    private static final String GROUP_UNIQ_ARRAY = ArrayValuedFunctions.GROUP_UNIQ_ARRAY;
    private static final String JSON_EXTRACT_ARRAY = ArrayValuedFunctions.JSON_EXTRACT_ARRAY;
    /** The DSL (wire) name of the two-operand latest-row aggregate; the SQL spelling is the same. */
    private static final String ARG_MAX = "arg_max";
    /** The window-only ranking functions, typed as 64-bit counters whatever their window. */
    private static final Set<String> RANKING_FUNCTIONS = Set.of("row_number", "rank", "dense_rank");
    /**
     * The numeric result kinds an {@code if} widens across, <b>widest first</b>. Mirrors
     * {@code ExprTranslator.NUMERIC_WIDTH_ORDER} in this surface's own kind vocabulary, which splits the
     * DSL's single {@code decimal} into an exact and a floating form. {@code FLOAT64} leads because that is
     * the direction both Calcite's least-restrictive rule and ClickHouse take a decimal/float pair — the
     * kind named here has to be the kind the values actually arrive as.
     */
    private static final ResultColumnKind[] NUMERIC_WIDTH_ORDER = {
        ResultColumnKind.FLOAT64, ResultColumnKind.DECIMAL, ResultColumnKind.INT64, ResultColumnKind.INT32};

    private final CalciteQueryCatalog.Snapshot snapshot;
    /** Set once the plan needs more than one block over one entity, or any subquery. */
    private boolean composite;

    private RelToStructuredQueryTranslator(CalciteQueryCatalog.Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** Translates a validated {@link RelRoot} into a DSL query or a composite plan, plus result column descriptors. */
    static SqlExecutableStatement translate(RelRoot root, CalciteQueryCatalog.Snapshot snapshot) {
        return new RelToStructuredQueryTranslator(snapshot).translateRoot(root);
    }

    private SqlExecutableStatement translateRoot(RelRoot root) {
        final Ctx ctx = fold(root.project(true));
        final List<String> validated = root.validatedRowType.getFieldNames();
        if (validated.size() != ctx.outputs.size()) {
            throw new IllegalStateException(
                    "translated output arity " + ctx.outputs.size()
                            + " does not match validated arity " + validated.size());
        }
        final CompositeQuery block = assemble(ctx, outputNames(validated, ctx.outputs));
        final List<ResultColumn> columns = new ArrayList<>(block.columns().size());
        for (final RelationColumn column : block.columns()) {
            columns.add(new ResultColumn(column.name(), column.kind(), column.elementKind()));
        }
        // ctx.fetch is the parsed LIMIT, so "the statement stated a bound" is recorded from the parse
        // rather than recovered later from the page — where an omitted LIMIT and OFFSET-without-LIMIT
        // are indistinguishable from a limit the client actually asked for.
        final boolean explicitLimit = ctx.fetch != null;
        if (!composite && block.source() instanceof EntityRelation) {
            return new SqlTranslation(block.block(), List.copyOf(columns), explicitLimit);
        }
        return new SqlCompositeTranslation(block, List.copyOf(columns), explicitLimit);
    }

    /**
     * The final output names: the validated names, which are the aliases
     * {@link SqlSelectAliasInjector} injected before validation, with a safety net for any name the
     * validator still synthesized. {@code EXPR$<n>} is reachable when the item could not be aliased
     * ahead of validation — a star expansion is the practical case — so such a name falls back to the
     * DSL's own base name for that expression. The whole list is then uniquified exactly as the DSL
     * surface uniquifies, so a substituted name can never duplicate a neighbour.
     */
    private static List<String> outputNames(List<String> validated, List<Expr> outputs) {
        final List<String> explicit = new ArrayList<>(validated.size());
        final List<String> bases = new ArrayList<>(validated.size());
        for (int i = 0; i < validated.size(); i++) {
            final String name = validated.get(i);
            final boolean synthesized = SYNTHESIZED_NAME.matcher(name).matches() || name.startsWith("$f");
            explicit.add(synthesized ? null : name);
            bases.add(synthesized ? OutputColumnNaming.baseName(outputs.get(i)) : name);
        }
        return OutputColumnNaming.assign(explicit, bases);
    }

    // ------------------------------------------------------------------ walker

    /**
     * The types of the columns a block's source exposes. For an entity they are the catalog's declared
     * types; for a derived table or join they come from the inner blocks' result columns and carry the
     * exact runtime kind too, because the DSL type is lossy ({@code FLOAT64} and {@code DECIMAL} are both
     * {@code decimal}) and the Arrow schema must name what actually arrives.
     */
    private record Types(
            Map<String, QueryFieldType> fieldTypes,
            Map<String, QueryFieldType> elementTypes,
            Map<String, ResultColumnKind> kinds,
            Map<String, ResultColumnKind> elementKinds) {

        static Types ofColumns(List<RelationColumn> columns) {
            final Map<String, QueryFieldType> types = new LinkedHashMap<>();
            final Map<String, QueryFieldType> elementTypes = new LinkedHashMap<>();
            final Map<String, ResultColumnKind> kinds = new LinkedHashMap<>();
            final Map<String, ResultColumnKind> elementKinds = new LinkedHashMap<>();
            for (final RelationColumn column : columns) {
                types.put(column.name(), column.type());
                if (column.elementType() != null) {
                    elementTypes.put(column.name(), column.elementType());
                }
                kinds.put(column.name(), column.kind());
                if (column.elementKind() != null) {
                    elementKinds.put(column.name(), column.elementKind());
                }
            }
            return new Types(types, elementTypes, kinds, elementKinds);
        }

        /**
         * This scope plus one synthetic name of {@code kind} — how a lambda parameter is typed while the
         * kind of an {@code arrayMap} body is derived. Nothing else can see the name: the copy is local to
         * that derivation, and the body it types has had its parameter references rewritten to it.
         */
        Types withKind(String name, ResultColumnKind kind) {
            final Map<String, ResultColumnKind> scoped = new LinkedHashMap<>(kinds);
            scoped.put(name, kind);
            return new Types(fieldTypes, elementTypes, scoped, elementKinds);
        }
    }

    /** Mutable fold state: the DSL expression per current field ordinal plus collected clauses. */
    private final class Ctx {
        Relation source;
        /** The entity name when {@link #source} is an {@link EntityRelation}; {@code null} otherwise. */
        String entity;
        /** Whether the entity reference carried {@code FINAL}; meaningful only when {@link #entity} is set. */
        boolean finalRead;
        Types types;
        List<Expr> outputs;
        FilterNode filter;
        FilterNode having;
        boolean aggregated;
        boolean distinct;
        List<Expr> groupKeys = List.of();
        List<SortEntry> sortEntries = List.of();
        boolean sorted;
        Long offset;
        Integer fetch;

        RexToExprTranslator rex() {
            return new RexToExprTranslator(outputs, types.fieldTypes(),
                    RelToStructuredQueryTranslator.this::foldSubquery);
        }

        /** Whether any current output computes a window expression. */
        boolean hasWindow() {
            return outputs.stream().anyMatch(RelToStructuredQueryTranslator::containsWindow);
        }

        /** A block with no clause at all, projecting its source's columns unchanged and in order. */
        boolean bare() {
            if (filter != null || having != null || aggregated || distinct || sorted) {
                return false;
            }
            final List<RelationColumn> columns = source.columns();
            if (outputs.size() != columns.size()) {
                return false;
            }
            for (int i = 0; i < outputs.size(); i++) {
                if (!(outputs.get(i) instanceof FieldExpr(String name)) || !name.equals(columns.get(i).name())) {
                    return false;
                }
            }
            return true;
        }
    }

    private record SortEntry(Expr expr, SortDir dir, NullsOrder nulls) {}

    private Ctx fold(RelNode rel) {
        return switch (rel) {
            case TableScan scan -> scanCtx(scan);
            case Filter filter -> filterCtx(filter);
            case Project project -> projectCtx(project);
            case Aggregate aggregate -> aggregateCtx(aggregate);
            case Sort sort -> sortCtx(sort);
            case Join join -> joinCtx(join);
            case SetOp setOp -> setOpCtx(setOp);
            case Correlate ignored -> throw new SqlTranslationException(RexToExprTranslator.CORRELATION_MESSAGE);
            default -> throw new SqlTranslationException(
                    "SQL construct '" + rel.getRelTypeName() + "' is not supported");
        };
    }

    private Ctx scanCtx(TableScan scan) {
        final List<String> qualified = scan.getTable().getQualifiedName();
        final String entity = qualified.getLast();
        final Map<String, QueryFieldType> fieldTypes = snapshot.fieldTypes(entity);
        if (fieldTypes == null) {
            throw new SqlTranslationException("unknown queryable entity '" + entity + "'");
        }
        final Map<String, QueryFieldType> elementTypes = snapshot.elementTypes(entity);
        final Ctx ctx = new Ctx();
        ctx.entity = entity;
        ctx.finalRead = scan.getHints().stream().anyMatch(hint -> hint.hintName.equalsIgnoreCase(SqlFinalModifier.HINT));
        ctx.types = new Types(fieldTypes, elementTypes == null ? Map.of() : elementTypes, Map.of(), Map.of());
        final List<RelationColumn> columns = new ArrayList<>();
        final List<Expr> fields = new ArrayList<>();
        for (final String name : scan.getRowType().getFieldNames()) {
            final QueryFieldType declared = fieldTypes.get(name);
            final QueryFieldType declaredElement = ctx.types.elementTypes().get(name);
            final ResultColumn column = ResultColumns.column(name, declared, declaredElement);
            // The catalog's declared type travels with the column, not just the kind it maps to: an
            // enclosing block over this scan (a join operand, a sealed derived block) must validate the
            // column exactly as a single-entity statement does, and the kind alone cannot tell a map from
            // an object or an enum from a string.
            columns.add(new RelationColumn(name, declared, declaredElement, column.kind(), column.elementKind()));
            fields.add(new FieldExpr(name));
        }
        ctx.source = new EntityRelation(entity, List.copyOf(columns), ctx.finalRead);
        ctx.outputs = fields;
        return ctx;
    }

    private Ctx filterCtx(Filter filter) {
        Ctx ctx = fold(filter.getInput());
        if (ctx.sorted || ctx.hasWindow()) {
            // A filter above ORDER BY/LIMIT filters the limited rows, and a filter above a window filters the
            // windowed rows (SQL forbids a window in WHERE): either way the block so far becomes a derived
            // table and the filter applies to it, which is exactly the statement's meaning.
            ctx = seal(ctx, filter.getInput());
        }
        final FilterNode node = ctx.rex().toFilter(filter.getCondition());
        if (ctx.aggregated) {
            ctx.having = and(ctx.having, node);
        } else {
            ctx.filter = and(ctx.filter, node);
        }
        return ctx;
    }

    private static FilterNode and(FilterNode existing, FilterNode added) {
        return existing == null ? added : new LogicalNode(LogicalOp.AND, List.of(existing, added));
    }

    private Ctx projectCtx(Project project) {
        Ctx ctx = fold(project.getInput());
        if (ctx.hasWindow() && project.getProjects().stream().anyMatch(RelToStructuredQueryTranslator::containsOver)) {
            // A window over a block that already computes a window (SELECT sum(rn) OVER () FROM (… row_number()
            // OVER () AS rn …)) cannot be one block — windows do not nest — so the inner one is sealed first.
            ctx = seal(ctx, project.getInput());
        }
        List<Expr> outputs = projections(project, ctx);
        if (dropsComputedSortKey(ctx, outputs)) {
            ctx = sealCarryingRowBound(ctx, project.getInput());
            // Re-translated against the sealed block, whose columns are its outputs in order — so the
            // projection's input ordinals index them one-for-one and the second pass reads the same fields.
            outputs = projections(project, ctx);
        } else if (dropsComputedGroupKey(ctx, outputs)
                || dropsDistinctKey(ctx, outputs)
                || computesAheadOfDeduplication(ctx, outputs)) {
            // A group key dropped, a deduplication key dropped, or a computation the engine evaluates ahead
            // of the deduplication: one seal serves all three. Through the row-bound path when the block
            // carries one, so an ordered, limited block does not leave its ORDER BY and LIMIT behind.
            ctx = ctx.sorted ? sealCarryingRowBound(ctx, project.getInput()) : seal(ctx, project.getInput());
            outputs = projections(project, ctx);
        }
        ctx.outputs = outputs;
        return ctx;
    }

    private List<Expr> projections(Project project, Ctx ctx) {
        final RexToExprTranslator rex = ctx.rex();
        final List<Expr> outputs = new ArrayList<>(project.getProjects().size());
        for (final RexNode node : project.getProjects()) {
            outputs.add(rex.toExpr(node));
        }
        return outputs;
    }

    /**
     * Whether a projection above {@code ORDER BY} drops a <b>computed</b> sort key — the shape a block
     * cannot carry, since the DSL's {@code sort} names an output or a source column and has no
     * sort-by-expression.
     *
     * <p>Both halves of the condition are load-bearing. A projection that <em>keeps</em> its sort key
     * ({@code SELECT d, n FROM (… ORDER BY n …)}) resolves through the select list and stays one block, so
     * nothing that translates today gains a derived level. A key that is a bare column keeps using
     * {@link #sortFieldName}'s fallback, which names the source column — that is how
     * {@code SELECT event_id … ORDER BY request_time LIMIT 5} stays a single-block translation, and sealing
     * it would make an ordinary statement composite and cost it its {@code /translate-sql} document for
     * nothing.
     */
    private static boolean dropsComputedSortKey(Ctx ctx, List<Expr> outputs) {
        if (!ctx.sorted) {
            return false;
        }
        for (final SortEntry entry : ctx.sortEntries) {
            if (!(entry.expr() instanceof FieldExpr) && !outputs.contains(entry.expr())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a projection above {@code GROUP BY} drops a <b>computed</b> group key — the same shape a block
     * cannot carry as {@link #dropsComputedSortKey}, and for the same reason: the DSL's {@code group_by} names
     * a select output or a source column and has no group-by-expression, so a key the projection does not
     * carry has no name left to be given.
     *
     * <p>Calcite trims such a key whenever the enclosing statement does not need its value —
     * {@code SELECT avg(c) FROM (SELECT lower(event_kind) AS k, sum(total_price) AS c … GROUP BY k) t} keeps
     * the grouping but projects only the aggregate, and {@code SELECT count(*) … GROUP BY lower(event_kind)}
     * never projects it at all. Both once failed with {@link #computedGroupKeyName}'s "must also appear in
     * the SELECT list", which the client could not act on: the key <em>was</em> written in the inner
     * {@code SELECT} list and the trim removed it. Sealing keeps the key in the sealed block's own select
     * list, where it names the grouping, and the projection applies over the derived table.
     *
     * <p>Both halves of the condition mirror the sort-key rule. A projection that <em>keeps</em> its group
     * key stays one block, so nothing that translates today gains a derived level; a key that is a bare
     * column keeps using {@link #groupByNames}'s field-name branch, which names the source column with no
     * nesting at all.
     */
    private static boolean dropsComputedGroupKey(Ctx ctx, List<Expr> outputs) {
        if (!ctx.aggregated) {
            return false;
        }
        for (final Expr key : ctx.groupKeys) {
            if (!(key instanceof FieldExpr) && !outputs.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a projection above a {@code DISTINCT} block drops one of the keys that block deduplicates by.
     *
     * <p>{@link #aggregateCtx} lowers an {@code Aggregate} with no aggregate calls to {@code DISTINCT} over
     * its outputs, which is what {@code SELECT DISTINCT} is — but it is also what {@code GROUP BY} with no
     * aggregate in the select list lowers to, and there the two are not the same statement.
     * {@code SELECT 1 AS one FROM dial_usage_log GROUP BY lower(event_kind)} yields one row per distinct
     * lowered kind; read as a {@code DISTINCT} over the projection it collapses to the single row
     * {@code SELECT DISTINCT 1}. The same loss applies to a key the client wrote {@code DISTINCT} over and
     * an enclosing block projects away ({@code SELECT a FROM (SELECT DISTINCT a, b FROM t) x} deduplicates
     * by the pair, not by {@code a}).
     *
     * <p>Sealing is the fix for both, and the same fix as for the two computed-key paths: the deduplicating
     * block keeps its own keys and the projection applies over it. A projection that keeps every key is
     * left alone here — the ordinary {@code SELECT DISTINCT} — but "keeps every key" is not by itself
     * enough to make a projection safe over a deduplication: see {@link #computesAheadOfDeduplication}.
     * The one shape that pays a level it does not need is a dropped key <b>functionally determined</b> by a
     * kept one ({@code SELECT a FROM (SELECT DISTINCT a, lower(a) AS l FROM t) x}, where deduplicating by
     * the pair is deduplicating by {@code a}); it stays correct, it only loses its single-block document.
     */
    private static boolean dropsDistinctKey(Ctx ctx, List<Expr> outputs) {
        return ctx.distinct && !outputs.containsAll(ctx.outputs);
    }

    /**
     * Whether a projection above a {@code DISTINCT} block computes something the engine evaluates
     * <b>before</b> that deduplication — a window function or the row-expanding {@code array_join}.
     *
     * <p>Neither is a scalar of its operands in the way the rest of the catalog is — one multiplies rows
     * and one depends on their position — and both are evaluated in the select stage that feeds
     * {@code DISTINCT} rather than over its result. Folded into
     * one block, {@code SELECT a, b, row_number() OVER () AS rn FROM (SELECT DISTINCT a, b FROM t) x}
     * becomes {@code SELECT DISTINCT a, b, row_number() OVER () FROM t}, which numbers every
     * <em>source</em> row and then deduplicates triples that the numbering already made unique — one row
     * per source row, where the statement asks for one per distinct pair. {@code array_join} loses rows
     * the other way: deduplicating after the expansion collapses repeated elements within a single array
     * that the statement's own ordering of the two stages keeps.
     *
     * <p>Sealing puts the deduplication back underneath, which is where the statement wrote it. An output
     * that is a key <em>passed through</em> is skipped, so a block that deduplicates over its own window
     * or expansion (the key itself being one) is not nested a second time for projecting it.
     */
    private static boolean computesAheadOfDeduplication(Ctx ctx, List<Expr> outputs) {
        if (!ctx.distinct) {
            return false;
        }
        for (final Expr output : outputs) {
            if (ctx.outputs.contains(output)) {
                continue;
            }
            if (containsWindow(output) || containsArrayExpansion(output)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link #seal} for the dropped-sort-key path, carrying the sealed block's own row bound
     * <b>and its ordering</b> onto the fresh block.
     *
     * <p><b>Why the collation is re-expressed.</b> Row order out of a ClickHouse subquery is not
     * contractual — {@code query_plan_remove_redundant_sorting} is on by default and strips an inner
     * {@code ORDER BY} whose enclosing block has none — so sealing alone would leave the statement's
     * {@code ORDER BY} inside a derived table and the rows arriving in whatever order the engine found
     * cheapest, for a statement whose whole point is the ordering. Every sort key reaching here was read
     * out of the sealed block's own outputs by {@link #sortCtx}, so each one is exposed as a column of the
     * sealed relation and can be named against it exactly as the carried {@code fetch} is re-applied. The
     * inner {@code ORDER BY} stays where it is because it is what the inner {@code LIMIT} selects with.
     *
     * <p><b>Why the limit is re-applied.</b> Only the outermost block is governed; a nested one is rendered
     * exactly as written. Sealing alone would therefore move the caller's {@code LIMIT 500} inside and leave
     * the outer block unbounded, so governance would apply its 100-row default and silently return 100 rows
     * for a statement that asked for 500 — a wrong answer, which is worse than the refusal this whole path
     * replaces. Re-applying the same bound over a block that already yields at most that many rows cannot
     * change the result, and it is what keeps {@code explicitLimit} true: no default clamp, no misleading
     * "default limit applied" note, and a limit above the shared maximum still refused exactly as it is for
     * the same statement without a computed sort key.
     *
     * <p><b>Why the offset is not.</b> An {@code OFFSET} skips into an ordering, and that ordering is inside
     * the sealed block; applying it again over the projection would skip a second time and return the wrong
     * rows.
     *
     * <p>{@code sorted} is set because it is the honest flag for "this block carries a row bound": it makes
     * any filter, aggregate or further sort above seal in turn, so nothing is ever applied <em>before</em> a
     * bound the statement wrote after it.
     */
    private Ctx sealCarryingRowBound(Ctx ctx, RelNode producer) {
        final Integer fetch = ctx.fetch;
        final List<String> names = outputNames(producer.getRowType().getFieldNames(), ctx.outputs);
        final List<SortEntry> collation = new ArrayList<>(ctx.sortEntries.size());
        for (final SortEntry entry : ctx.sortEntries) {
            final int ordinal = ctx.outputs.indexOf(entry.expr());
            if (ordinal < 0) {
                // Unreachable: sortCtx reads every key out of ctx.outputs by ordinal. Refused rather than
                // dropped, so a future shape that does reach it cannot lose the ordering silently.
                throw new SqlTranslationException(ORDER_BY_UNRESOLVED);
            }
            collation.add(new SortEntry(new FieldExpr(names.get(ordinal)), entry.dir(), entry.nulls()));
        }
        final Ctx fresh = seal(ctx, names);
        fresh.fetch = fetch;
        fresh.sortEntries = List.copyOf(collation);
        fresh.sorted = true;
        return fresh;
    }

    private Ctx aggregateCtx(Aggregate aggregate) {
        Ctx ctx = fold(aggregate.getInput());
        if (ctx.sorted || ctx.aggregated || ctx.distinct || ctx.hasWindow()) {
            // Aggregation above a limited, grouped, distinct or windowed block aggregates that block's rows:
            // seal it (a window is computed after aggregation, so it cannot feed the same block's aggregate).
            // The windowed case is reachable with no nesting written — SELECT DISTINCT row_number() OVER ()
            // lowers to a keys-only Aggregate over a windowed block — so it is one of the reasons
            // NO_DSL_EQUIVALENT has to name, which it does as separating a window from a DISTINCT.
            ctx = seal(ctx, aggregate.getInput());
        }
        if (aggregate.getGroupSets().size() > 1) {
            throw new SqlTranslationException("ROLLUP/CUBE/GROUPING SETS are not supported");
        }
        final List<Integer> groupOrdinals = aggregate.getGroupSet().asList();
        if (aggregate.getAggCallList().isEmpty() && groupOrdinals.size() == ctx.outputs.size()) {
            // SELECT DISTINCT lowers to a group-by over every projected column with no aggregate calls.
            for (final Expr output : ctx.outputs) {
                rejectProjectionOnlyField(output, ctx, "SELECT DISTINCT over");
            }
            ctx.distinct = true;
            return ctx;
        }
        final List<Expr> groupKeys = new ArrayList<>(groupOrdinals.size());
        final List<Expr> outputs = new ArrayList<>();
        for (final int ordinal : groupOrdinals) {
            final Expr key = ctx.outputs.get(ordinal);
            rejectArrayKeyOrOperand(key, ctx, "GROUP BY on");
            groupKeys.add(key);
            outputs.add(key);
        }
        for (final AggregateCall call : aggregate.getAggCallList()) {
            // Calcite re-projects a select item that IS a group key as ANY_VALUE(<group key>); unwrap it
            // back to the key expression rather than treating it as a real aggregate.
            if ("ANY_VALUE".equalsIgnoreCase(call.getAggregation().getName())
                    && call.getArgList().size() == 1
                    && aggregate.getGroupSet().get(call.getArgList().getFirst())) {
                outputs.add(ctx.outputs.get(call.getArgList().getFirst()));
                continue;
            }
            final List<Integer> callArgs = call.getArgList();
            for (int i = 0; i < callArgs.size(); i++) {
                // The array-consuming aggregates take an array by definition — groupUniqArrayArray and
                // groupArrayArray flatten one, and arg_max carries one as its VALUE while its ordering key
                // stays scalar. The position rule is ArrayValuedFunctions', so the declared-column and
                // computed-array answers cannot drift; every other aggregate keeps the blanket rejection.
                if (ArrayValuedFunctions.acceptsArrayOperand(aggregateName(call), i)) {
                    continue;
                }
                rejectFlattenableOperand(call, ctx.outputs.get(callArgs.get(i)), ctx);
                rejectArrayKeyOrOperand(ctx.outputs.get(callArgs.get(i)), ctx, "an aggregate function over");
            }
            outputs.add(aggExpr(call, ctx.outputs));
        }
        ctx.aggregated = true;
        ctx.groupKeys = groupKeys;
        ctx.outputs = outputs;
        return ctx;
    }

    /** The aggregate's name as the carve-out matches it — case-insensitively, as every name match is. */
    private static String aggregateName(AggregateCall call) {
        return call.getAggregation().getName();
    }

    /**
     * Refuses {@code group_uniq_array} over an array operand <b>naming the function that flattens
     * instead</b>. The generic rejection that follows would say an array field "can only be projected",
     * which is true of every other aggregate and unhelpful here: the author's question — the set of
     * elements across the group — has an answer on this surface, under a different name. Only the message
     * differs; the call stays refused, because a set of arrays is a type the DSL cannot carry.
     */
    private static void rejectFlattenableOperand(AggregateCall call, Expr operand, Ctx ctx) {
        if (!ArrayValuedFunctions.GROUP_UNIQ_ARRAY.equalsIgnoreCase(aggregateName(call))) {
            return;
        }
        if (operand instanceof FieldExpr(String field)
                && ctx.types.fieldTypes().get(field) == QueryFieldType.ARRAY) {
            throw new SqlTranslationException("array field '" + field + "' cannot be collected by '"
                    + ArrayValuedFunctions.GROUP_UNIQ_ARRAY + "', whose result would be an array of arrays;"
                    + " use '" + ArrayValuedFunctions.GROUP_UNIQ_ARRAY_ARRAY + "' to flatten the group's"
                    + " arrays into one");
        }
    }

    /** Maps one {@link AggregateCall} onto the DSL aggregate function catalog. */
    private static FnExpr aggExpr(AggregateCall call, List<Expr> inputs) {
        if (call.filterArg >= 0) {
            throw new SqlTranslationException("FILTER clauses on aggregate functions are not supported");
        }
        final String name = call.getAggregation().getName().toUpperCase(Locale.ROOT);
        final List<Integer> args = call.getArgList();
        return switch (name) {
            case "COUNT" -> {
                if (args.size() > 1) {
                    throw new SqlTranslationException("COUNT accepts at most one argument");
                }
                yield new FnExpr(
                        "count", call.isDistinct(), args.isEmpty() ? List.of() : List.of(inputs.get(args.getFirst())));
            }
            case "SUM", "AVG", "MIN", "MAX" -> {
                if (args.size() != 1) {
                    throw new SqlTranslationException(name + " expects exactly one argument");
                }
                yield new FnExpr(name.toLowerCase(Locale.ROOT), call.isDistinct(), List.of(inputs.get(args.getFirst())));
            }
            case "ARG_MAX" -> {
                if (args.size() != 2) {
                    throw new SqlTranslationException(name + " expects exactly two arguments (value, order)");
                }
                // Rejected here rather than on the operator, because SqlBasicAggFunction.withDistinct is
                // not public API and the quantifier therefore parses. Deduplicating the values would
                // detach a value from the order that selects it, so this is meaningless rather than a
                // no-op — unlike MIN/MAX, where the DSL ignores it.
                if (call.isDistinct()) {
                    throw new SqlTranslationException(name + " does not accept DISTINCT; deduplicating the "
                            + "values would detach a value from the order that selects it");
                }
                yield new FnExpr(ARG_MAX, false, List.of(inputs.get(args.get(0)), inputs.get(args.get(1))));
            }
            case "PERCENTILE_CONT", "PERCENTILE_DISC" -> percentileExpr(name, call, inputs);
            case "GROUPUNIQARRAYARRAY", "GROUPARRAYARRAY" -> {
                final String canonical = ArrayValuedFunctions.canonicalFlattening(name);
                if (args.size() != 1) {
                    throw new SqlTranslationException(canonical + " expects exactly one argument");
                }
                if (call.isDistinct()) {
                    throw new SqlTranslationException(canonical + " does not accept DISTINCT; "
                            + (ArrayValuedFunctions.GROUP_ARRAY_ARRAY.equals(canonical)
                            ? "deduplicating the flattened elements would make it "
                            + ArrayValuedFunctions.GROUP_UNIQ_ARRAY_ARRAY
                            : "it already returns the distinct elements of the group"));
                }
                yield new FnExpr(canonical, false, List.of(inputs.get(args.getFirst())));
            }
            case "GROUP_UNIQ_ARRAY" -> {
                if (args.size() != 1) {
                    throw new SqlTranslationException(name + " expects exactly one argument");
                }
                // Not carried as a distinct aggregate: the function's result is already a set, so the
                // quantifier would be a no-op that the DSL→SQL renderer then drops, making the round-trip
                // asymmetric for no gain. Rejected rather than silently ignored.
                if (call.isDistinct()) {
                    throw new SqlTranslationException(
                            name + " does not accept DISTINCT; it already returns the distinct values of the group");
                }
                yield new FnExpr(GROUP_UNIQ_ARRAY, false, List.of(inputs.get(args.getFirst())));
            }
            default -> throw new SqlTranslationException(
                    "aggregate function '" + call.getAggregation().getName() + "' is not in the query function catalog");
        };
    }

    /** {@code PERCENTILE_x(fraction) WITHIN GROUP (ORDER BY col)} → DSL {@code percentile_x(fraction, col)}. */
    private static FnExpr percentileExpr(String name, AggregateCall call, List<Expr> inputs) {
        if (call.getArgList().size() != 1 || call.getCollation().getFieldCollations().size() != 1) {
            throw new SqlTranslationException(name + " requires one fraction and WITHIN GROUP (ORDER BY <one column>)");
        }
        final Expr fraction = inputs.get(call.getArgList().getFirst());
        if (!(fraction instanceof ValueExpr)) {
            throw new SqlTranslationException(name + " requires a numeric literal fraction");
        }
        final RelFieldCollation collation = call.getCollation().getFieldCollations().getFirst();
        if (collation.getDirection() != RelFieldCollation.Direction.ASCENDING) {
            throw new SqlTranslationException(name + " supports only ascending WITHIN GROUP ordering");
        }
        return new FnExpr(
                name.toLowerCase(Locale.ROOT), false, List.of(fraction, inputs.get(collation.getFieldIndex())));
    }

    private Ctx sortCtx(Sort sort) {
        Ctx ctx = fold(sort.getInput());
        if (ctx.sorted) {
            // A second ORDER BY/LIMIT level orders the already-limited rows: seal the inner level.
            ctx = seal(ctx, sort.getInput());
        }
        final List<SortEntry> entries = new ArrayList<>();
        for (final RelFieldCollation collation : sort.getCollation().getFieldCollations()) {
            final Expr key = ctx.outputs.get(collation.getFieldIndex());
            rejectArrayKeyOrOperand(key, ctx, "ORDER BY on");
            entries.add(new SortEntry(key, direction(collation), nulls(collation.nullDirection)));
        }
        ctx.sortEntries = entries;
        ctx.offset = longLiteral(sort.offset, "OFFSET");
        ctx.fetch = intLiteral(sort.fetch, "LIMIT");
        if (ctx.fetch != null && ctx.fetch == 0) {
            throw new SqlTranslationException("LIMIT 0 is not supported");
        }
        ctx.sorted = true;
        return ctx;
    }

    // ------------------------------------------------------------ set operations

    /**
     * A set operation: every input folded and sealed into a branch block, the operation itself becoming the
     * source of a fresh block. That wrapper is what carries the statement's row governance — a
     * {@code LIMIT} rendered on a branch would bound that branch alone, which is exactly why ClickHouse's
     * own trailing {@code LIMIT} is a branch limit and why the surface needs a block of its own above the
     * operation.
     *
     * <p>{@code all} is read straight off Calcite because {@code SqlSetOperations} has already resolved
     * what the caller wrote: a bare {@code INTERSECT}/{@code EXCEPT} arrives here as its {@code ALL}
     * operator, and a bare {@code UNION} never arrives at all.
     */
    private Ctx setOpCtx(SetOp setOp) {
        final SetOpKind kind = switch (setOp) {
            case Union ignored -> SetOpKind.UNION;
            case Intersect ignored -> SetOpKind.INTERSECT;
            case Minus ignored -> SetOpKind.EXCEPT;
            default -> throw new SqlTranslationException(
                    "SQL construct '" + setOp.getRelTypeName() + "' is not supported");
        };
        final List<CompositeQuery> branches = new ArrayList<>(setOp.getInputs().size());
        for (final RelNode input : setOp.getInputs()) {
            final Ctx branch = foldBranch(setOp, input, branches.size());
            branches.add(assemble(branch, outputNames(input.getRowType().getFieldNames(), branch.outputs)));
        }
        final List<RelationColumn> columns = setOpColumns(setOp, branches);
        composite = true;
        final Ctx ctx = new Ctx();
        ctx.source = new SetOpRelation(kind, setOp.all, List.copyOf(branches), columns);
        ctx.types = Types.ofColumns(columns);
        ctx.outputs = identity(columns);
        return ctx;
    }

    /**
     * One branch, folded — with the validator's own branch coercion reported as what it is rather than as
     * a cast the caller never wrote. See {@link #branchCoercionMismatch}.
     */
    private Ctx foldBranch(SetOp setOp, RelNode input, int ordinal) {
        try {
            return fold(input);
        } catch (SqlTranslationException e) {
            throw branchCoercionMismatch(setOp, input, ordinal, e);
        }
    }

    /**
     * Re-words the one refusal a set operation reaches through the validator's own rewriting. Where
     * corresponding branch columns are a text type and a non-text one, Calcite unifies them by inserting a
     * {@code CAST} to text into the other branch's projection, and {@link RexToExprTranslator} refuses that
     * cast deliberately — lowering an injected text coercion would widen every text function to accept any
     * type. The refusal then names a {@code CAST} that does not appear in the statement, and the
     * translation endpoint's classifier attaches "drop the CAST" to it, so it is re-raised here as the
     * branch-type mismatch this surface promises for a pair it will not unify.
     *
     * <p>The injected cast is identifiable rather than guessed at: a caller-written cast <em>to</em> text
     * never reaches the Rex translation as a cast at all — it has become a {@code TO_STRING_CAST} marker
     * call before validation — so a cast to a character type in a branch's projection is the validator's.
     * The failure is matched against that exact cast's own refusal, so any other rejection from inside the
     * branch (an unsupported function, an unknown column) passes through untouched.
     */
    private static SqlTranslationException branchCoercionMismatch(
            SetOp setOp, RelNode input, int ordinal, SqlTranslationException failure) {
        if (!(input instanceof Project project)) {
            return failure;
        }
        final List<String> names = setOp.getRowType().getFieldNames();
        final List<RexNode> projects = project.getProjects();
        for (int i = 0; i < projects.size() && i < names.size(); i++) {
            if (!(projects.get(i) instanceof RexCall call) || call.getKind() != SqlKind.CAST) {
                continue;
            }
            final SqlTypeName to = call.getType().getSqlTypeName();
            final SqlTypeName from = call.getOperands().getFirst().getType().getSqlTypeName();
            if (!SqlTypeName.CHAR_TYPES.contains(to) || SqlTypeName.CHAR_TYPES.contains(from)
                    || !RexToExprTranslator.unsupportedCast(from, to).equals(failure.getMessage())) {
                continue;
            }
            // Read in branch order: this branch's own type, and the type the other branches gave.
            final String own = typeName(from);
            final String other = typeName(to);
            return branchTypeMismatch(
                    columnLabel(names, i), ordinal == 0 ? own + " vs " + other : other + " vs " + own);
        }
        return failure;
    }

    /**
     * The operation's output columns: named after the first branch's <em>rendered</em> columns, because
     * those are the names the rendered operation exposes and the block above it references. They are
     * Calcite's first-branch names (which is also what ClickHouse names the result) with one difference
     * that matters: where the first branch's item is an unnamed expression, Calcite's own row type carries
     * a synthesized {@code EXPR$n} while the branch renders the DSL's base name, so taking the name from
     * the row type would reference a column the branch does not expose — visible as soon as the operation
     * nests, where no alias injection covers for it. The columns are typed by the branches' own columns
     * where they agree, so the exact runtime kind survives — an {@code enum} stays distinguishable from a
     * {@code string}, an array's element kind is kept, {@code FLOAT64} does not collapse into
     * {@code DECIMAL} — and by Calcite's least-restrictive unification where they do not.
     */
    private static List<RelationColumn> setOpColumns(SetOp setOp, List<CompositeQuery> branches) {
        final List<RelationColumn> head = branches.getFirst().columns();
        final List<String> names = setOp.getRowType().getFieldNames();
        final List<RelDataTypeField> fields = setOp.getRowType().getFieldList();
        final List<RelationColumn> columns = new ArrayList<>(head.size());
        for (int i = 0; i < head.size(); i++) {
            final RelationColumn first = head.get(i);
            boolean agree = true;
            for (final CompositeQuery branch : branches) {
                final RelationColumn column = branch.columns().get(i);
                if (column.type() != first.type() || column.elementType() != first.elementType()
                        || column.kind() != first.kind() || column.elementKind() != first.elementKind()) {
                    agree = false;
                    break;
                }
            }
            if (agree) {
                columns.add(first);
                continue;
            }
            final SqlTypeName unified = fields.get(i).getType().getSqlTypeName();
            final ResultColumnKind kind = ResultColumns.sqlKind(unified);
            if (kind == null) {
                throw branchTypeMismatch(columnLabel(names, i), branchTypes(branches, i));
            }
            columns.add(new RelationColumn(
                    first.name(), ResultColumns.fieldType(kind), null, kind, null));
        }
        return columns;
    }

    /** The one wording for a branch pair this surface will not unify, whichever check found it. */
    private static SqlTranslationException branchTypeMismatch(String column, String types) {
        return new SqlTranslationException("the branches of a set operation give " + column
                + " incompatible types (" + types + "); project the same type in every branch");
    }

    /**
     * How the mismatch message names a column: by its name, or by its position where the name is one
     * Calcite synthesized — a planner internal the caller never wrote and cannot act on.
     */
    private static String columnLabel(List<String> names, int ordinal) {
        final String name = ordinal < names.size() ? names.get(ordinal) : null;
        return name == null || SYNTHESIZED_NAME.matcher(name).matches() || name.startsWith("$f")
                ? "column " + (ordinal + 1)
                : "column '" + name + "'";
    }

    /** One SQL type in the surface's own kind vocabulary, falling back to the SQL name where it has none. */
    private static String typeName(SqlTypeName type) {
        final ResultColumnKind kind = ResultColumns.sqlKind(type);
        return (kind == null ? type.getName() : kind.name()).toLowerCase(Locale.ROOT);
    }

    /** The branches' own declared types for one column ordinal, for the mismatch message. */
    private static String branchTypes(List<CompositeQuery> branches, int ordinal) {
        final List<String> types = new ArrayList<>(branches.size());
        for (final CompositeQuery branch : branches) {
            types.add(branch.columns().get(ordinal).kind().name().toLowerCase(Locale.ROOT));
        }
        return String.join(" vs ", types);
    }

    // ------------------------------------------------------------------ joins and sealing

    private Ctx joinCtx(Join join) {
        final Ctx leftCtx = fold(join.getLeft());
        final Ctx rightCtx = fold(join.getRight());
        final Relation left = asOperand(leftCtx, join.getLeft(), false);
        final Relation right = asOperand(rightCtx, join.getRight(), true);
        JoinKind kind = switch (join.getJoinType()) {
            case INNER -> JoinKind.INNER;
            case LEFT -> JoinKind.LEFT;
            case RIGHT -> JoinKind.RIGHT;
            case FULL -> JoinKind.FULL;
            default -> throw new SqlTranslationException(
                    "SQL construct '" + join.getJoinType() + " JOIN' is not supported");
        };
        final List<String> names = join.getRowType().getFieldNames();
        final int leftCount = left.columns().size();
        final List<JoinRelation.JoinColumn> joinColumns = new ArrayList<>(names.size());
        final Set<String> leftNames = new HashSet<>();
        for (int i = 0; i < names.size(); i++) {
            final boolean fromLeft = i < leftCount;
            final RelationColumn origin = fromLeft ? left.columns().get(i) : right.columns().get(i - leftCount);
            joinColumns.add(new JoinRelation.JoinColumn(
                    new RelationColumn(names.get(i), origin.type(), origin.elementType(),
                            origin.kind(), origin.elementKind()),
                    fromLeft, origin.name()));
            if (fromLeft) {
                leftNames.add(names.get(i));
            }
        }
        final List<RelationColumn> columns = joinColumns.stream().map(JoinRelation.JoinColumn::column).toList();
        final Ctx ctx = new Ctx();
        ctx.types = Types.ofColumns(columns);
        ctx.outputs = identity(columns);
        final FilterNode on;
        if (join.getCondition().isAlwaysTrue()) {
            if (kind != JoinKind.INNER) {
                throw new SqlTranslationException("an outer join requires an ON condition with an equality between"
                        + " a column of each side");
            }
            kind = JoinKind.CROSS;
            on = null;
        } else {
            on = ctx.rex().toFilter(join.getCondition());
            requireEquiConjunct(on, leftNames);
        }
        composite = true;
        ctx.source = new JoinRelation(left, right, kind, on, List.copyOf(joinColumns));
        return ctx;
    }

    /**
     * A join operand: a bare entity scan stays an {@link EntityRelation}, a bare join on the <em>left</em>
     * stays a flat {@link JoinRelation} (rendered as a left-deep chain), and everything else — a block with
     * clauses, a join on the right, or a set operation on either side — is sealed into a derived table.
     */
    private Relation asOperand(Ctx ctx, RelNode producer, boolean right) {
        // A set operation is sealed into a derived table even on the left: unlike a join chain it does not
        // render flat, so it needs its own alias and column bindings, which is what a derived block gives
        // it.
        if (ctx.bare() && (ctx.source instanceof EntityRelation
                || (!right && ctx.source instanceof JoinRelation))) {
            return ctx.source;
        }
        return seal(ctx, producer).source;
    }

    /**
     * Seals the block folded so far into a derived table and starts a fresh block over it, whose columns
     * are the sealed block's outputs under the producer's validated names.
     */
    private Ctx seal(Ctx ctx, RelNode producer) {
        return seal(ctx, outputNames(producer.getRowType().getFieldNames(), ctx.outputs));
    }

    /** {@link #seal} with the sealed block's column names already resolved by the caller. */
    private Ctx seal(Ctx ctx, List<String> names) {
        composite = true;
        final CompositeQuery block = assemble(ctx, names);
        final Ctx fresh = new Ctx();
        fresh.source = new DerivedRelation(block);
        fresh.types = Types.ofColumns(block.columns());
        fresh.outputs = identity(block.columns());
        return fresh;
    }

    /** A subquery's plan: its relational tree folded and assembled as a nested block. */
    private CompositeQuery foldSubquery(RelNode rel) {
        composite = true;
        final Ctx ctx = fold(rel);
        return assemble(ctx, outputNames(rel.getRowType().getFieldNames(), ctx.outputs));
    }

    private static List<Expr> identity(List<RelationColumn> columns) {
        final List<Expr> outputs = new ArrayList<>(columns.size());
        for (final RelationColumn column : columns) {
            outputs.add(new FieldExpr(column.name()));
        }
        return outputs;
    }

    /**
     * ClickHouse's hash join needs at least one equality between the two sides; an inequality-only
     * condition fails inside the server with a message about its own internals, so it is refused here
     * with the rule spelled out.
     */
    private static void requireEquiConjunct(FilterNode on, Set<String> leftNames) {
        for (final FilterNode conjunct : conjuncts(on)) {
            if (conjunct instanceof ComparisonNode(ComparisonOp op, List<Expr> args)
                    && op == ComparisonOp.EQ && args != null && args.size() == 2) {
                final Set<String> first = fieldNames(args.get(0));
                final Set<String> second = fieldNames(args.get(1));
                if (!first.isEmpty() && !second.isEmpty() && sidesDiffer(first, second, leftNames)) {
                    return;
                }
            }
        }
        throw new SqlTranslationException("a join condition must contain an equality between a column of each side"
                + " (the engine cannot join on inequalities alone); use CROSS JOIN with a WHERE clause"
                + " for an unconditional pairing");
    }

    private static boolean sidesDiffer(Set<String> first, Set<String> second, Set<String> leftNames) {
        final boolean firstLeft = leftNames.containsAll(first);
        final boolean firstRight = first.stream().noneMatch(leftNames::contains);
        final boolean secondLeft = leftNames.containsAll(second);
        final boolean secondRight = second.stream().noneMatch(leftNames::contains);
        return (firstLeft && secondRight) || (firstRight && secondLeft);
    }

    private static List<FilterNode> conjuncts(FilterNode node) {
        final List<FilterNode> out = new ArrayList<>();
        collectConjuncts(node, out);
        return out;
    }

    private static void collectConjuncts(FilterNode node, List<FilterNode> out) {
        if (node instanceof LogicalNode(LogicalOp op, List<FilterNode> args) && op == LogicalOp.AND && args != null) {
            for (final FilterNode arg : args) {
                collectConjuncts(arg, out);
            }
        } else if (node != null) {
            out.add(node);
        }
    }

    /** Every field name an expression references (a scalar subquery references none of the block's). */
    private static Set<String> fieldNames(Expr expr) {
        final Set<String> names = new HashSet<>();
        collectFieldNames(expr, names);
        return names;
    }

    private static void collectFieldNames(Expr expr, Set<String> names) {
        switch (expr) {
            case null -> { }
            case FieldExpr field -> names.add(field.name());
            case FnExpr fn -> {
                if (fn.args() != null) {
                    fn.args().forEach(arg -> collectFieldNames(arg, names));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> collectFieldNames(item, names));
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        collectFilterFieldNames(branch.when(), names);
                        collectFieldNames(branch.then(), names);
                    }
                }
                collectFieldNames(caseExpr.otherwise(), names);
            }
            case WindowExpr window -> {
                collectFieldNames(window.fn(), names);
                if (window.partitionBy() != null) {
                    window.partitionBy().forEach(key -> collectFieldNames(key, names));
                }
                if (window.orderBy() != null) {
                    for (final WindowOrder order : window.orderBy()) {
                        collectFieldNames(order.expr(), names);
                    }
                }
            }
            // A lambda body may capture a column of the enclosing row, so its columns are columns this
            // statement reads. Its PARAMETER is not one — that is why a parameter reference has a node
            // kind of its own, and why this walk needs no scope tracking to tell the two apart.
            case LambdaExpr lambda -> collectFieldNames(lambda.body(), names);
            default -> { }
        }
    }

    private static void collectFilterFieldNames(FilterNode node, Set<String> names) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(child -> collectFilterFieldNames(child, names));
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(arg -> collectFieldNames(arg, names));
                }
            }
            case SubqueryNode subquery -> {
                if (subquery.operands() != null) {
                    subquery.operands().forEach(arg -> collectFieldNames(arg, names));
                }
            }
        }
    }

    /** Whether a DSL expression is or contains a window expression. */
    private static boolean containsWindow(Expr expr) {
        return switch (expr) {
            case null -> false;
            case WindowExpr ignored -> true;
            case FnExpr fn -> fn.args() != null && fn.args().stream().anyMatch(RelToStructuredQueryTranslator::containsWindow);
            case CaseExpr caseExpr -> (caseExpr.when() != null && caseExpr.when().stream().anyMatch(branch ->
                    containsWindow(branch.then()) || filterContainsWindow(branch.when())))
                    || containsWindow(caseExpr.otherwise());
            case ArrayExpr array -> array.items() != null
                    && array.items().stream().anyMatch(RelToStructuredQueryTranslator::containsWindow);
            case LambdaExpr lambda -> containsWindow(lambda.body());
            default -> false;
        };
    }

    /**
     * Whether a DSL expression is or contains the row-expanding {@code array_join}. Mirrors
     * {@link #containsWindow}; the {@code CASE} condition is walked too, since {@code array_join} carries
     * no positional restriction on this surface and expands wherever it is written.
     */
    private static boolean containsArrayExpansion(Expr expr) {
        return switch (expr) {
            case null -> false;
            case FnExpr fn -> ArrayValuedFunctions.ARRAY_JOIN.equals(fn.name())
                    || (fn.args() != null
                    && fn.args().stream().anyMatch(RelToStructuredQueryTranslator::containsArrayExpansion));
            case CaseExpr caseExpr -> (caseExpr.when() != null && caseExpr.when().stream().anyMatch(branch ->
                    containsArrayExpansion(branch.then()) || filterContainsArrayExpansion(branch.when())))
                    || containsArrayExpansion(caseExpr.otherwise());
            case ArrayExpr array -> array.items() != null
                    && array.items().stream().anyMatch(RelToStructuredQueryTranslator::containsArrayExpansion);
            case LambdaExpr lambda -> containsArrayExpansion(lambda.body());
            default -> false;
        };
    }

    private static boolean filterContainsArrayExpansion(FilterNode node) {
        return switch (node) {
            case null -> false;
            case LogicalNode logical -> logical.args() != null
                    && logical.args().stream()
                    .anyMatch(RelToStructuredQueryTranslator::filterContainsArrayExpansion);
            case ComparisonNode comparison -> comparison.args() != null
                    && comparison.args().stream()
                    .anyMatch(RelToStructuredQueryTranslator::containsArrayExpansion);
            case SubqueryNode subquery -> subquery.operands() != null
                    && subquery.operands().stream()
                    .anyMatch(RelToStructuredQueryTranslator::containsArrayExpansion);
        };
    }

    private static boolean filterContainsWindow(FilterNode node) {
        return switch (node) {
            case null -> false;
            case LogicalNode logical -> logical.args() != null
                    && logical.args().stream().anyMatch(RelToStructuredQueryTranslator::filterContainsWindow);
            case ComparisonNode comparison -> comparison.args() != null
                    && comparison.args().stream().anyMatch(RelToStructuredQueryTranslator::containsWindow);
            case SubqueryNode subquery -> subquery.operands() != null
                    && subquery.operands().stream().anyMatch(RelToStructuredQueryTranslator::containsWindow);
        };
    }

    /** Whether a Calcite expression contains a windowed call. */
    private static boolean containsOver(RexNode node) {
        if (node instanceof org.apache.calcite.rex.RexOver) {
            return true;
        }
        return node instanceof org.apache.calcite.rex.RexCall call
                && call.getOperands().stream().anyMatch(RelToStructuredQueryTranslator::containsOver);
    }

    private static SortDir direction(RelFieldCollation collation) {
        return switch (collation.getDirection()) {
            case ASCENDING, STRICTLY_ASCENDING -> SortDir.ASC;
            case DESCENDING, STRICTLY_DESCENDING -> SortDir.DESC;
            default -> throw new SqlTranslationException("unsupported sort direction " + collation.getDirection());
        };
    }

    private static NullsOrder nulls(RelFieldCollation.NullDirection direction) {
        return switch (direction) {
            case FIRST -> NullsOrder.FIRST;
            case LAST -> NullsOrder.LAST;
            case UNSPECIFIED -> null;
        };
    }

    private static Long longLiteral(RexNode node, String clause) {
        if (node == null) {
            return null;
        }
        if (node instanceof RexLiteral literal && literal.getValueAs(Long.class) != null) {
            return literal.getValueAs(Long.class);
        }
        throw new SqlTranslationException(clause + " must be a literal (parameters are not supported)");
    }

    private static Integer intLiteral(RexNode node, String clause) {
        final Long value = longLiteral(node, clause);
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE; // rejected downstream as exceeding the max page size anyway
        }
        return value.intValue();
    }

    // ------------------------------------------------------------- assembly

    /** The block folded in {@code ctx} as one query block with its result columns under {@code names}. */
    private CompositeQuery assemble(Ctx ctx, List<String> names) {
        final QueryMode mode = ctx.aggregated ? QueryMode.AGGREGATE : QueryMode.ROW;
        // The alias is carried only where it says something the DSL's own naming would not, and the
        // produced select is guaranteed to resolve back to exactly these names — so the DSL document
        // executes to the same column names as the ResultColumn descriptors report.
        final List<OutputColumn> select = OutputColumnNaming.withMinimalAliases(ctx.outputs, names);
        final List<RelationColumn> columns = new ArrayList<>(ctx.outputs.size());
        for (int i = 0; i < ctx.outputs.size(); i++) {
            final Expr output = ctx.outputs.get(i);
            final ResultColumn column = resultColumn(names.get(i), output, ctx);
            columns.add(new RelationColumn(column.name(), declaredType(output, ctx), declaredElementType(output, ctx),
                    column.kind(), column.elementKind()));
        }
        final StructuredQuery query = new StructuredQuery(
                ctx.entity,
                ctx.filter,
                mode,
                ctx.distinct,
                select,
                ctx.aggregated ? groupByNames(ctx, names, select) : null,
                ctx.having,
                sortItems(ctx, names),
                page(ctx),
                // A SQL entity reference is final or raw, never unstated: an absent `final` would read a
                // collapsing source final and turn a raw SQL read into a final one.
                ctx.entity == null ? null : ctx.finalRead);
        return new CompositeQuery(ctx.source, query, List.copyOf(columns));
    }

    /**
     * Resolves each group key to a DSL {@code group_by} name: a field name or a select alias.
     * Global aggregates yield {@code null} (no group_by section), matching how REST clients express
     * them.
     */
    private static List<String> groupByNames(Ctx ctx, List<String> names, List<OutputColumn> select) {
        if (ctx.groupKeys.isEmpty()) {
            return null;
        }
        final List<String> groupBy = new ArrayList<>(ctx.groupKeys.size());
        for (final Expr key : ctx.groupKeys) {
            if (key instanceof FieldExpr(String field)) {
                rejectAliasShadowingGroupColumn(field, key, names, select);
                groupBy.add(field);
                continue;
            }
            groupBy.add(computedGroupKeyName(ctx, names, key));
        }
        return groupBy;
    }

    /**
     * Rejects {@code GROUP BY <column>} when a select item is explicitly aliased after that column while
     * projecting something else ({@code SELECT date_bin(1,'hour',request_time) AS request_time … GROUP BY
     * request_time}). The DSL expresses the key as the bare name {@code request_time}, which is then both
     * a column and the alias of a different expression — a shape the DSL rejects as ambiguous rather than
     * picking a reading, so the statement fails closed here with the SQL-level reason instead of
     * producing a document that only fails on execution.
     */
    private static void rejectAliasShadowingGroupColumn(
            String field, Expr key, List<String> names, List<OutputColumn> select) {
        for (int i = 0; i < select.size(); i++) {
            if (select.get(i).as() != null && names.get(i).equals(field) && !key.equals(select.get(i).expr())) {
                throw new SqlTranslationException("select alias '" + field + "' names a different expression than the"
                        + " GROUP BY column '" + field + "', so the structured query DSL cannot tell the two apart."
                        + " Alias the expression to a name that is not a column of the entity.");
            }
        }
    }

    /**
     * Names a computed {@code GROUP BY} key by the select output that carries the same expression —
     * the only way the DSL can express "group by this computed value".
     *
     * <p>The DSL resolves a {@code group_by} name against the source's columns first (input columns
     * win in {@code GROUP BY}, mirroring PostgreSQL), so an output name that is <em>also</em> a column
     * of the source would silently regroup the statement by that unrelated column. Any other output
     * carrying the same expression under a non-shadowed name is used instead; when every candidate is
     * shadowed the translation fails closed rather than executing different semantics than the
     * submitted SQL.
     */
    private static String computedGroupKeyName(Ctx ctx, List<String> names, Expr key) {
        String shadowed = null;
        for (int i = 0; i < ctx.outputs.size(); i++) {
            if (!key.equals(ctx.outputs.get(i))) {
                continue;
            }
            final String name = names.get(i);
            if (!ctx.types.fieldTypes().containsKey(name)) {
                return name;
            }
            shadowed = name;
        }
        if (shadowed != null) {
            throw new SqlTranslationException("a computed GROUP BY expression is named '" + shadowed
                    + "', which is also a column of '" + sourceName(ctx) + "': the structured query DSL would group"
                    + " by the column instead of the expression. Alias the expression to a name that is not a column"
                    + " of the source.");
        }
        // Unreachable: ctx.outputs loses a group key only in projectCtx, which seals the block in that case
        // (see dropsComputedGroupKey) and so never assembles one. Kept as the fail-closed floor, so a future
        // shape that does drop a key cannot be silently regrouped by whatever the projection left behind.
        throw new SqlTranslationException("a computed GROUP BY expression must also appear in the SELECT list");
    }

    private static String sourceName(Ctx ctx) {
        return ctx.entity != null ? ctx.entity : "the derived table";
    }

    /** Resolves sort keys: plain fields sort by name; computed keys must be select outputs (by alias). */
    private static List<SortItem> sortItems(Ctx ctx, List<String> names) {
        if (ctx.sortEntries.isEmpty()) {
            return null;
        }
        final List<SortItem> items = new ArrayList<>(ctx.sortEntries.size());
        for (final SortEntry entry : ctx.sortEntries) {
            items.add(new SortItem(sortFieldName(entry.expr(), ctx, names), entry.dir(), entry.nulls()));
        }
        return items;
    }

    /**
     * A sort key's DSL name: the output that carries the expression, else the source column it names.
     *
     * <p>The fallback is guarded against an output <b>shadowing</b> the column, for the same reason
     * {@link #rejectAliasShadowingGroupColumn} guards {@code group_by}: the DSL resolves a {@code sort} name
     * against the select outputs first, so a block projecting {@code length(x) AS request_time} while
     * sorting on the column {@code request_time} would order by the alias's expression instead. That is a
     * different statement, so it fails closed rather than executing the wrong ordering — and the guard
     * covers the sealed-collation path of {@link #sealCarryingRowBound}, whose keys are named against the
     * sealed relation's columns.
     */
    private static String sortFieldName(Expr expr, Ctx ctx, List<String> names) {
        final int i = ctx.outputs.indexOf(expr);
        if (i >= 0) {
            return names.get(i);
        }
        if (expr instanceof FieldExpr(String field)) {
            if (names.contains(field)) {
                throw new SqlTranslationException("select alias '" + field + "' names a different expression than"
                        + " the ORDER BY column '" + field + "', so the structured query DSL cannot tell the two"
                        + " apart. Alias the expression to a name that is not a column of the source.");
            }
            return field;
        }
        throw new SqlTranslationException(ORDER_BY_UNRESOLVED);
    }

    private static PageSpec page(Ctx ctx) {
        if (ctx.offset == null && ctx.fetch == null) {
            return null; // downstream governance applies the default limit
        }
        return new OffsetPage(ctx.offset == null ? 0 : ctx.offset, ctx.fetch == null ? 0 : ctx.fetch, false);
    }

    // ---------------------------------------------------------- result kinds

    /**
     * Array and object fields are projection-only: the DSL has no array or object literal and no
     * comparison over either, so any use outside a plain projection item fails closed, naming the
     * field and the restriction (never a misleading "unknown column").
     */
    private static void rejectProjectionOnlyField(Expr expr, Ctx ctx, String use) {
        if (expr instanceof FieldExpr(String name)) {
            SqlProjectionOnlyTypes.reject(name, ctx.types.fieldTypes().get(name), use);
        }
    }

    /**
     * {@link #rejectProjectionOnlyField} plus the same rule for a <b>computed</b> array — an
     * array-returning call ({@code map_keys}, {@code split_string}, {@code json_extract_array},
     * {@code group_uniq_array}) used as a key or an operand.
     *
     * <p>{@code SqlProjectionOnlyTypes} cannot state this one: that classifier is keyed on a declared
     * column type, and a computed result has none. Without it, {@code GROUP BY} on the <em>alias</em> of a
     * {@code map_keys} projection was accepted and ClickHouse grouped by whole arrays — a
     * plausible-looking result answering a question nobody asked.
     *
     * <p><b>Deliberately not used for {@code SELECT DISTINCT}</b>, which is why this is a second method
     * rather than a line added to the first. A distinct projection is not operand use: the restriction is on
     * using an array <em>as an operand</em>, and {@code SELECT DISTINCT json_extract_array(…)} is accepted
     * (unlike distinct over a declared {@code array} column, which the column-type rule catches). The two
     * read alike and behave differently, so the split is explicit.
     */
    private static void rejectArrayKeyOrOperand(Expr expr, Ctx ctx, String use) {
        rejectProjectionOnlyField(expr, ctx, use);
        // The name set is ArrayValuedFunctions', but the exception is this surface's: its own
        // rejectAsOperand raises QueryValidationException, which is the DSL path's type. Every failure
        // reaching a caller of this translator must be a SqlTranslationException, so the check is
        // spelled out here rather than delegated.
        if (expr instanceof FnExpr fn && ArrayValuedFunctions.isArrayValued(fn.name())) {
            throw new SqlTranslationException("'" + fn.name() + "' returns an array and can only be"
                    + " projected; it cannot be " + use + " a computed array");
        }
    }

    /** Result descriptor per select item: array fields carry their element kind. */
    private static ResultColumn resultColumn(String name, Expr expr, Ctx ctx) {
        if (expr instanceof FieldExpr(String field) && ctx.types.fieldTypes().get(field) == QueryFieldType.ARRAY) {
            final ResultColumnKind elementKind = ctx.types.elementKinds().get(field);
            if (elementKind != null) {
                return new ResultColumn(name, ResultColumnKind.ARRAY, elementKind);
            }
            return ResultColumns.column(name, QueryFieldType.ARRAY, ctx.types.elementTypes().get(field));
        }
        // A scalar subquery's column is whatever its block reported, element kind included.
        if (expr instanceof ScalarSubqueryExpr scalar) {
            final RelationColumn column = scalarColumn(scalar);
            return new ResultColumn(name, column.kind(), column.elementKind());
        }
        // group_uniq_array is one of the two catalog functions whose array result carries its operand's
        // element kind rather than a fixed one, and no declared field type restates it, so it is derived
        // here — the array results ResultColumns cannot map, since that mapping is keyed on a declared
        // column type.
        if (expr instanceof FnExpr fn && GROUP_UNIQ_ARRAY.equals(fn.name())) {
            return new ResultColumn(name, ResultColumnKind.ARRAY, kind(fn.args().getFirst(), ctx.types));
        }
        // array_slice is the other: it narrows an array instead of producing one, so its element kind is its
        // own operand's — asked by recursing, which is what types a slice of a numeric group_uniq_array as a
        // numeric list and hands the Arrow writer the right element writer.
        if (expr instanceof FnExpr fn && ArrayValuedFunctions.ARRAY_SLICE.equals(fn.name())) {
            return new ResultColumn(
                    name, ResultColumnKind.ARRAY, elementKind(fn.args().getFirst(), ctx.types));
        }
        // json_extract_array is the other array-returning function, typed here for the same reason and with
        // a FIXED element kind rather than an operand-derived one: it extracts text values whatever the
        // member's JSON types are, so nothing about the operand can inform it.
        if (expr instanceof FnExpr fn && JSON_EXTRACT_ARRAY.equals(fn.name())) {
            return new ResultColumn(name, ResultColumnKind.ARRAY, ResultColumnKind.STRING);
        }
        // map_keys and split_string are the other two array-returning scalars, both with a FIXED text
        // element kind for the same reason json_extract_array's is fixed: a map's keys and a split's parts
        // are text whatever the operand carries, so nothing about the operand can inform it.
        if (expr instanceof FnExpr fn
                && (ArrayValuedFunctions.MAP_KEYS.equals(fn.name())
                        || ArrayValuedFunctions.SPLIT_STRING.equals(fn.name()))) {
            return new ResultColumn(name, ResultColumnKind.ARRAY, ResultColumnKind.STRING);
        }
        // The four array-returning shapes this change adds, each typed by where its elements come from:
        // a flattening aggregate and arrayFilter keep their operand's element kind, arg_max over an array
        // keeps the value operand's, and arrayMap's elements are whatever its body produces.
        if (expr instanceof FnExpr fn && ArrayValuedFunctions.isFlatteningAggregate(fn.name())
                && fn.args() != null && fn.args().size() == 1) {
            return new ResultColumn(name, ResultColumnKind.ARRAY, elementKind(fn.args().getFirst(), ctx.types));
        }
        if (expr instanceof FnExpr fn && HigherOrderFunctions.ARRAY_FILTER.equals(fn.name())
                && fn.args() != null && fn.args().size() == 2) {
            return new ResultColumn(name, ResultColumnKind.ARRAY,
                    elementKind(fn.args().get(HigherOrderFunctions.ARRAY_ARGUMENT), ctx.types));
        }
        if (expr instanceof FnExpr fn && HigherOrderFunctions.ARRAY_MAP.equals(fn.name())
                && fn.args() != null && fn.args().size() == 2) {
            return new ResultColumn(name, ResultColumnKind.ARRAY, mappedElementKind(fn, ctx.types));
        }
        if (expr instanceof FnExpr fn && ARG_MAX.equals(fn.name())
                && fn.args() != null && fn.args().size() == 2
                && isArrayValued(fn.args().getFirst(), ctx.types)) {
            return new ResultColumn(name, ResultColumnKind.ARRAY, elementKind(fn.args().getFirst(), ctx.types));
        }
        return new ResultColumn(name, kind(expr, ctx.types));
    }

    /** Whether {@code expr} evaluates to an array — a declared array column, or an array-returning call. */
    private static boolean isArrayValued(Expr expr, Types types) {
        if (expr instanceof FieldExpr(String field)) {
            return types.fieldTypes().get(field) == QueryFieldType.ARRAY;
        }
        return expr instanceof FnExpr fn && ArrayValuedFunctions.isArrayValued(fn.name());
    }

    /**
     * The element kind of an {@code arrayMap} result: the kind of its lambda's <b>body</b>, derived with
     * the parameter bound to the operand's element kind.
     *
     * <p>Done by rewriting the body's parameter references to a synthetic name that {@link Types#withKind}
     * carries, rather than by teaching {@link #kind} about lambda scopes: the kind walk is used from a
     * dozen places that have no lambda in sight, and a scope parameter on all of them would be carried
     * empty everywhere but here. The synthetic name is not a legal column name, so it cannot collide with
     * one the statement uses.
     */
    private static ResultColumnKind mappedElementKind(FnExpr call, Types types) {
        final ResultColumnKind paramKind =
                elementKind(call.args().get(HigherOrderFunctions.ARRAY_ARGUMENT), types);
        if (!(call.args().get(HigherOrderFunctions.LAMBDA_ARGUMENT) instanceof LambdaExpr lambda)
                || lambda.body() == null) {
            return paramKind;
        }
        return kind(bindParam(lambda.body()), types.withKind(LAMBDA_PARAM_KIND_NAME, paramKind));
    }

    /**
     * {@code body} with every parameter reference rewritten to the synthetic kind-carrying name.
     *
     * <p>Walks the conditional too, not only calls: {@code arrayMap(x -> CASE WHEN … THEN x END, arr)}
     * is a legal body, and leaving its parameter unbound would reach {@link #kind}'s default branch —
     * "expression cannot be projected as a result column" — for a statement that is perfectly fine.
     */
    private static Expr bindParam(Expr body) {
        return switch (body) {
            case LambdaParamExpr ignored -> new FieldExpr(LAMBDA_PARAM_KIND_NAME);
            case FnExpr fn -> new FnExpr(fn.name(), fn.distinct(),
                    fn.args() == null ? List.of() : fn.args().stream()
                            .map(RelToStructuredQueryTranslator::bindParam).toList());
            case CaseExpr caseExpr -> new CaseExpr(
                    caseExpr.when() == null ? List.of() : caseExpr.when().stream()
                            .map(branch -> new CaseBranch(branch.when(), bindParam(branch.then()))).toList(),
                    bindParam(caseExpr.otherwise()));
            case null, default -> body;
        };
    }

    /**
     * The DSL type an enclosing block judges this output by, or {@code null} to let
     * {@link RelationColumn} derive one from the runtime kind.
     *
     * <p>A <b>pass-through</b> column keeps the type its source declared, which is what makes a
     * {@code map} still a map and an {@code enum} still an enum one block out: the runtime kind cannot
     * carry that (it shares {@code OBJECT} between {@code object} and {@code map}, and {@code STRING}
     * between {@code string}, {@code uuid} and {@code enum}), so a derived block or a join operand would
     * otherwise refuse the map accessors and drop the enum's string affinity that a single-entity
     * statement over the same column gets. A <b>computed</b> expression has no declared type at all —
     * only the kind its evaluation yields — and is typed by that.
     */
    private static QueryFieldType declaredType(Expr expr, Ctx ctx) {
        return switch (expr) {
            case FieldExpr(String field) -> ctx.types.fieldTypes().get(field);
            case ScalarSubqueryExpr scalar -> scalarColumn(scalar).type();
            default -> null;
        };
    }

    /** {@link #declaredType}'s element half: the declared element type of a pass-through array column. */
    private static QueryFieldType declaredElementType(Expr expr, Ctx ctx) {
        return switch (expr) {
            case FieldExpr(String field) -> ctx.types.elementTypes().get(field);
            case ScalarSubqueryExpr scalar -> scalarColumn(scalar).elementType();
            default -> null;
        };
    }

    private static RelationColumn scalarColumn(ScalarSubqueryExpr scalar) {
        if (scalar.subquery() == null || scalar.subquery().columns().size() != 1) {
            throw new SqlTranslationException("a scalar subquery must produce exactly one column");
        }
        return scalar.subquery().columns().getFirst();
    }

    /** Runtime value family per select expression (ClickHouse semantics). */
    private static ResultColumnKind kind(Expr expr, Types types) {
        return switch (expr) {
            case FieldExpr(String name) -> {
                // A derived or joined source carries the exact kind its inner block reported; an entity
                // column maps through the shared declared-type rule.
                final ResultColumnKind known = types.kinds().get(name);
                yield known != null ? known : fieldKind(types.fieldTypes().get(name));
            }
            case ValueExpr value -> valueKind(value.valueType());
            case FnExpr fn -> fnKind(fn, types);
            case CaseExpr caseExpr -> caseKind(caseExpr, types);
            case WindowExpr window -> windowKind(window, types);
            case ScalarSubqueryExpr scalar -> scalarColumn(scalar).kind();
            default -> throw new SqlTranslationException("expression cannot be projected as a result column");
        };
    }

    /**
     * {@code array_join}'s column is one <em>element</em> of its operand, so its kind is the operand's
     * element kind — the exact inverse of the array-returning entries, which wrap an operand's kind into an
     * array. Getting this wrong is not cosmetic: the kind drives the Arrow writer, so reporting the array
     * here would hand a list writer a scalar value.
     *
     * <p>A declared {@code array} column carries the kind (or the declared element type it maps from); the
     * three array-returning scalars carry no declared type at all and take the same fixed {@code STRING}
     * element their own results take, for the same reason those are fixed. An array column whose catalog row
     * predates element types has neither, and falls back to {@code STRING} rather than failing the query —
     * the same untyped answer the DSL surface's {@code ExprFieldType} gives, so the two surfaces agree on a
     * column they can both only guess at.
     */
    private static ResultColumnKind arrayJoinKind(FnExpr fn, Types types) {
        return elementKind(fn.args() == null || fn.args().isEmpty() ? null : fn.args().getFirst(), types);
    }

    /**
     * The element kind of an array-valued expression — {@link #arrayJoinKind}'s whole rule, named separately
     * because {@code array_slice} has to ask it of <em>its</em> own operand (a slice narrows an array rather
     * than producing one, so its elements are the operand's).
     */
    private static ResultColumnKind elementKind(Expr operand, Types types) {
        if (operand instanceof FieldExpr(String name)) {
            final ResultColumnKind declared = types.elementKinds().get(name);
            if (declared != null) {
                return declared;
            }
            final QueryFieldType elementType = types.elementTypes().get(name);
            if (elementType != null) {
                return ResultColumns.scalarKind(elementType);
            }
        }
        // group_uniq_array collects its own operand's values, so an expansion of it yields that operand's
        // kind — the one array-returning call whose element is not fixed. The DSL surface's ExprFieldType
        // makes the same distinction; answering STRING for all four would type
        // ARRAY_JOIN(GROUP_UNIQ_ARRAY(total_tokens)) as text and hand the Arrow writer the wrong writer.
        if (operand instanceof FnExpr call
                && ArrayValuedFunctions.GROUP_UNIQ_ARRAY.equalsIgnoreCase(call.name())
                && call.args() != null && !call.args().isEmpty()) {
            return kind(call.args().getFirst(), types);
        }
        // A slice keeps its own operand's element kind, so it is answered by asking the same question one
        // level down: array_join(array_slice(group_uniq_array(total_tokens), 1, 3)) is a numeric column,
        // not text.
        if (operand instanceof FnExpr call
                && ArrayValuedFunctions.ARRAY_SLICE.equalsIgnoreCase(call.name())
                && call.args() != null && !call.args().isEmpty()) {
            return elementKind(call.args().getFirst(), types);
        }
        // The three array-returning shapes this change adds, each mirroring the arm ExprFieldType.ofElement
        // already has, so the two surfaces type the same statement alike. A flattening aggregate collects
        // the ELEMENTS of its operand, so an element of its result is an element of that operand.
        if (operand instanceof FnExpr call
                && ArrayValuedFunctions.isFlatteningAggregate(call.name())
                && call.args() != null && !call.args().isEmpty()) {
            return elementKind(call.args().getFirst(), types);
        }
        // arrayFilter narrows its operand and so keeps its element kind — asked of argument 1, since
        // ClickHouse's higher-order family puts the array second.
        if (operand instanceof FnExpr call
                && HigherOrderFunctions.ARRAY_FILTER.equalsIgnoreCase(call.name())
                && call.args() != null && call.args().size() > HigherOrderFunctions.ARRAY_ARGUMENT) {
            return elementKind(call.args().get(HigherOrderFunctions.ARRAY_ARGUMENT), types);
        }
        // arrayMap replaces each element with its body, so an element of its result is whatever the body
        // produces — the same derivation the projected call itself is typed by.
        if (operand instanceof FnExpr call
                && HigherOrderFunctions.ARRAY_MAP.equalsIgnoreCase(call.name())
                && call.args() != null && call.args().size() > HigherOrderFunctions.ARRAY_ARGUMENT) {
            return mappedElementKind(call, types);
        }
        return ResultColumnKind.STRING;
    }

    /** Delegates to the shared declared-type mapping, which the Flight catalog renders through too. */
    private static ResultColumnKind fieldKind(QueryFieldType type) {
        return ResultColumns.scalarKind(type);
    }

    private static ResultColumnKind valueKind(ValueType type) {
        return switch (type) {
            case STRING, UUID, NULL -> ResultColumnKind.STRING;
            case INTEGER -> ResultColumnKind.INT32;
            case LONG -> ResultColumnKind.INT64;
            case DECIMAL -> ResultColumnKind.DECIMAL;
            case BOOLEAN -> ResultColumnKind.BOOL;
            case DATE -> ResultColumnKind.DATE;
            case TIMESTAMP -> ResultColumnKind.TIMESTAMP;
        };
    }

    private static ResultColumnKind fnKind(FnExpr fn, Types types) {
        return switch (fn.name()) {
            // ClickHouse runtime semantics, not the catalog's advertised INTEGER: its length returns UInt64,
            // and array_length renders onto the same function.
            case "count", "length", "width_bucket", "array_length" -> ResultColumnKind.INT64;
            // div renders as ClickHouse divide, which always returns Float64 — that is what keeps an
            // integer-over-integer ratio fractional, so the column must be advertised as such.
            case "avg", "percentile_cont", "percentile_disc", "div" -> ResultColumnKind.FLOAT64;
            case "lower", "upper", "trim", "json_extract_string", "map_value",
                 "concat", "to_string", ArrayValuedFunctions.ARRAY_TO_STRING -> ResultColumnKind.STRING;
            case "json_extract_long" -> ResultColumnKind.INT64;
            case "json_extract_double" -> ResultColumnKind.FLOAT64;
            case "json_extract_bool" -> ResultColumnKind.BOOL;
            // The clock and the two offsets are all timestamp-valued whatever they are applied to, the same
            // way the two truncations are — the offset functions' own operand is a timestamp by declaration,
            // so there is nothing to read it from.
            case "date_bin", "date_trunc", "now", "date_add", "date_sub" -> ResultColumnKind.TIMESTAMP;
            case "abs", "min", "max", "floor", "ceil" ->
                    kind(fn.args().getLast(), types);
            // Argument 0, not the last-argument form above: round carries (value[, digits]), so the last
            // argument of the two-argument form is the precision literal. Reading it would type
            // ROUND(total_price, 2) as an integer column and the Arrow writer would then truncate 1.53 to 1.
            case "round" -> kind(fn.args().getFirst(), types);
            // Argument 0, deliberately, and NOT the last-argument form the line above uses: arg_max
            // carries (value, order), so the last argument is what the rows are ranked by, not what is
            // returned. Reading it would type this column as the order's type — a timestamp, in the
            // motivating case — while still returning plausible rows.
            case ARG_MAX -> kind(fn.args().getFirst(), types);
            case "sum" -> switch (kind(fn.args().getFirst(), types)) {
                case INT32, INT64 -> ResultColumnKind.INT64;
                case DECIMAL -> ResultColumnKind.DECIMAL;
                case FLOAT64 -> ResultColumnKind.FLOAT64;
                default -> throw new SqlTranslationException("SUM requires a numeric argument");
            };
            // An array result carries an element kind, which a bare ResultColumnKind cannot: a projection of
            // it is typed by resultColumn() before reaching here, so arriving here means it was nested inside
            // another expression, which the subset does not express.
            case "json_extract_string_ci" -> ResultColumnKind.STRING;
            case "not_empty", "starts_with", "contains", "equals", "match",
                 "map_contains", "array_has", "array_has_any", "array_has_all" -> ResultColumnKind.BOOL;
            // The higher-order predicates answer about an array without returning one, so their columns
            // are ordinary scalars — which is what makes them groupable and sortable.
            case HigherOrderFunctions.ARRAY_EXISTS, HigherOrderFunctions.ARRAY_ALL -> ResultColumnKind.BOOL;
            case HigherOrderFunctions.ARRAY_COUNT -> ResultColumnKind.INT32;
            case ArrayValuedFunctions.ARRAY_JOIN -> arrayJoinKind(fn, types);
            // plus/minus/multiply/modulo preserve their operands' type, widened when the two differ — the
            // same three-type widening if applies to its branches, so both read alike. modulo is here
            // rather than beside div deliberately: an integer remainder stays an integer.
            case "plus", "minus", "multiply" -> arithmeticKind(fn, types, true);
            case "modulo" -> arithmeticKind(fn, types, false);
            // The branches, never the condition: operand 0 is boolean by construction and typing the column
            // from it would call every conditional a boolean.
            case "if" -> conditionalKind(fn, types);
            case GROUP_UNIQ_ARRAY, JSON_EXTRACT_ARRAY,
                 ArrayValuedFunctions.MAP_KEYS, ArrayValuedFunctions.SPLIT_STRING,
                 ArrayValuedFunctions.ARRAY_SLICE,
                 ArrayValuedFunctions.GROUP_UNIQ_ARRAY_ARRAY, ArrayValuedFunctions.GROUP_ARRAY_ARRAY,
                 HigherOrderFunctions.ARRAY_FILTER, HigherOrderFunctions.ARRAY_MAP ->
                    throw new SqlTranslationException(
                    "'" + fn.name() + "' returns an array and can only be projected; it cannot be an operand "
                            + "of another expression");
            default -> throw new SqlTranslationException(
                    "function '" + fn.name() + "' is not in the query function catalog");
        };
    }

    /**
     * A binary arithmetic call's result kind: its operands' kind, <b>widened</b> when the two differ. Shares
     * {@link #conditionalKind}'s widening for the same reason — a result schema naming the narrower operand
     * would misdescribe the values arriving — and is separate only because the operand indices differ.
     *
     * <p>{@code promoteIntegers} carries ClickHouse's own overflow-avoiding promotion for
     * {@code plus}/{@code minus}/{@code multiply}: those three return a type one rank wider than their
     * operands, so {@code Int32 + Int32} arrives as {@code Int64}. Naming INT32 there would have the Arrow
     * writer narrow the value with {@code intValue()} — a silently wrong number, exactly the failure
     * {@code sum}'s own INT32 → INT64 rule above exists to avoid. {@code modulo} is the exception and passes
     * {@code false}: a remainder cannot exceed its divisor, so ClickHouse keeps the narrower type.
     */
    private static ResultColumnKind arithmeticKind(FnExpr fn, Types types, boolean promoteIntegers) {
        if (fn.args() == null || fn.args().size() != 2) {
            throw new SqlTranslationException("function '" + fn.name() + "' expects exactly two arguments");
        }
        final ResultColumnKind kind = widest(kind(fn.args().get(0), types), kind(fn.args().get(1), types));
        return promoteIntegers && kind == ResultColumnKind.INT32 ? ResultColumnKind.INT64 : kind;
    }

    /** The wider of two operand kinds under {@link #NUMERIC_WIDTH_ORDER}; {@code left} when neither ranks. */
    private static ResultColumnKind widest(ResultColumnKind left, ResultColumnKind right) {
        if (left == right) {
            return left;
        }
        for (final ResultColumnKind candidate : NUMERIC_WIDTH_ORDER) {
            if (left == candidate || right == candidate) {
                return candidate;
            }
        }
        return left;
    }

    /**
     * An {@code if}'s result kind: its two branches' kind, <b>widened</b> when they are different numerics.
     *
     * <p>The branches are already known to unify — Calcite's least-restrictive return type refuses the call
     * otherwise, during validation and before this runs — so the only remaining question is which kind to
     * name. Naming the {@code then} branch's would understate a mixed-numeric pair: Calcite derives (and
     * ClickHouse returns) the <em>widest</em>, so {@code if(c, int_col, decimal_col)} would be advertised as
     * an integer column while the values arriving are decimals. A result schema that misdescribes its own
     * column is worse than one that is coarse, and the widening rule is the same three-type one the DSL
     * surface applies, so both surfaces answer alike.
     */
    private static ResultColumnKind conditionalKind(FnExpr fn, Types types) {
        if (fn.args() == null || fn.args().size() != 3) {
            throw new SqlTranslationException("function 'if' expects exactly three arguments"
                    + " (condition, then, else)");
        }
        return widest(kind(fn.args().get(1), types), kind(fn.args().get(2), types));
    }

    /** A {@code case}'s kind: the widest of its branch values, the absent {@code else} contributing nothing. */
    private static ResultColumnKind caseKind(CaseExpr caseExpr, Types types) {
        ResultColumnKind kind = null;
        final List<Expr> values = new ArrayList<>();
        if (caseExpr.when() != null) {
            for (final CaseBranch branch : caseExpr.when()) {
                values.add(branch.then());
            }
        }
        if (caseExpr.otherwise() != null) {
            values.add(caseExpr.otherwise());
        }
        for (final Expr value : values) {
            if (value instanceof ValueExpr literal && literal.valueType() == ValueType.NULL) {
                continue; // a null branch takes the others' type
            }
            final ResultColumnKind candidate = kind(value, types);
            kind = kind == null ? candidate : widest(kind, candidate);
        }
        if (kind == null) {
            throw new SqlTranslationException("CASE needs at least one branch that is not a null literal");
        }
        return kind;
    }

    /** A window's kind: a 64-bit counter for the ranking functions, the aggregate's own kind otherwise. */
    private static ResultColumnKind windowKind(WindowExpr window, Types types) {
        if (window.fn() == null || window.fn().name() == null) {
            throw new SqlTranslationException("a window expression requires a function");
        }
        if (RANKING_FUNCTIONS.contains(window.fn().name().toLowerCase(Locale.ROOT))) {
            return ResultColumnKind.INT64;
        }
        return fnKind(window.fn(), types);
    }
}
