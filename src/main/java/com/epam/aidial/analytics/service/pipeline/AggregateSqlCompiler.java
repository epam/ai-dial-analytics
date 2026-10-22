package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.CursorPredicates;
import com.epam.aidial.analytics.service.enrichment.validation.ExprColumnCollector;
import com.epam.aidial.analytics.service.pipeline.AggregateCompiler.CompiledFilter;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.PartitionPreselection;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder.LeafRestriction;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder.LimitPolicy;
import com.epam.aidial.analytics.service.table.Identifiers;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compiles an aggregate pipeline's <b>SQL declaration</b> — the single block {@link AggregateSqlValidator} accepted —
 * into the statements a refresh and a preview run. It is {@link AggregateCompiler}'s twin over a parsed statement
 * instead of a form, and shares its dirty-key, bucket-bound and grain-restriction helpers rather than copying them,
 * so the two modes cannot drift on the one contract that matters: every dirty group key is recomputed in full.
 *
 * <p><b>The block is rendered by {@link StructuredQueryBuilder}</b>, the renderer the query surface uses, so an
 * expression means in a rollup exactly what it means in a query. Three things differ from a user query:
 * <ul>
 *   <li>{@link LimitPolicy#NESTED}: the block states no page, so no {@code LIMIT} is added — a write must not be
 *       truncated to a first page of groups;</li>
 *   <li>{@code final} is pinned off and the builder is constructed without {@code query.force-final}, so no
 *       per-table {@code FINAL} keyword is rendered; versions are collapsed by the statement-level {@code final = 1}
 *       the pipeline executors pin, as on the form path;</li>
 *   <li>the dirty-key restriction is passed <em>into</em> the build as a {@link LeafRestriction}, not ANDed after it,
 *       so that when the whole grain reads one enrichment the restriction also reaches that enrichment's grain join
 *       and the hash join is built from the dirty keys' rows rather than from the whole enrichment.</li>
 * </ul>
 *
 * <p><b>The absence image.</b> On the form path an enrichment group key is a plain nullable column, so an input row
 * whose enrichment row the restriction drops reads NULL, which never satisfies the dirty-key {@code IN}. An SQL key
 * may be an <em>expression</em> over the enrichment, typically {@code coalesce("<enrichment>.<column>", '')}, and such
 * a row then reads the expression's value with its enrichment columns NULL — {@code ''} here. If that value is itself
 * a dirty key, restricting the join would move every other key's rows into the {@code ''} group. The image is
 * computed by rendering the key expressions against bindings in which every enrichment column is the literal
 * {@code NULL} (the engine constant-folds the result), and the derived table's restriction becomes
 * {@code keys IN dirty OR image IN dirty}: on a tick whose dirty set holds the image the second disjunct is true for
 * every row and the join runs unrestricted, and on every other tick it is false and the pushdown holds. The choice is
 * made per tick inside the statement, because the dirty set is a sub-select the service never sees.
 *
 * <p><b>Buckets are UTC.</b> A recognised bucket call ({@code date_trunc(unit, t)}, {@code date_bin(1, unit, t)}) is
 * rendered by the shared translator as {@code toStartOfInterval(t, INTERVAL 1 <unit>, 'UTC')}; the catalog's bucket
 * functions take no zone argument, so every SQL declaration's bucket is pinned to UTC exactly as a form bucket is.
 */
@Component
public class AggregateSqlCompiler {

    private static final String UPDATED_AT = "_updated_at";

    /** Prefix of the alias a select item gets when its target column's name would collide with an entity field. */
    static final String POSITION_ALIAS = "__c";

    private final ExprTranslator exprTranslator;
    private final FilterTranslator filterTranslator;
    private final DSLContext renderContext;
    /** Built without {@code query.force-final}: a pipeline read never renders a per-table FINAL keyword. */
    private final StructuredQueryBuilder builder;

    public AggregateSqlCompiler(ExprTranslator exprTranslator, FilterTranslator filterTranslator, DSLContext renderContext) {
        this.exprTranslator = exprTranslator;
        this.filterTranslator = filterTranslator;
        this.renderContext = renderContext;
        this.builder = new StructuredQueryBuilder(exprTranslator, filterTranslator, false);
    }

    /**
     * The block's {@code WHERE}, translated, with the fields it reads — what the probe applies as the pipeline's
     * filter, exactly as a form declaration's {@code filter} is applied, so the probe joins the enrichments it names.
     * {@link CompiledFilter#NONE} when the block has no {@code WHERE}.
     */
    public CompiledFilter where(AggregateSqlValidator.Analysis analysis, QueryEntityModel model) {
        final StructuredQuery query = analysis.plan().translation().query();
        if (query.filter() == null) {
            return CompiledFilter.NONE;
        }
        return new CompiledFilter(filterTranslator.toCondition(query.filter(), model.bindings()),
                Set.copyOf(StructuredQueryBuilder.referencedFields(query.filter())));
    }

    /** Every entity field the block reads — the widest statement's set, which the enrichment clamp covers. */
    public static Set<String> referencedFields(AggregateSqlValidator.Analysis analysis) {
        return StructuredQueryBuilder.referencedFields(analysis.plan().translation().query());
    }

    /**
     * The full recompute for one window, prefixed {@code INSERT INTO "<target>" (<columns>, "_updated_at")}.
     *
     * @param where            the block's {@code WHERE} as {@link #where} compiled it
     * @param windowPredicates the probed window's predicates, applied to the dirty-key sub-select and the bucket
     *     bound's sub-queries only — never to the recompute itself, which must see every row of a dirty key
     */
    public AggregateCompiler.Compiled compileRefresh(AggregateSqlValidator.Analysis analysis, QueryEntityModel model,
            String target, CompiledFilter where, List<Condition> windowPredicates) {
        final Map<String, QueryFieldBinding> bindings = model.bindings();
        final List<Expr> keys = keyExprs(analysis);
        final List<Field<?>> groupFields = translate(keys, bindings);
        final List<Set<String>> keyReads = keys.stream().map(AggregateSqlCompiler::reads).toList();

        final Set<String> grouped = new LinkedHashSet<>(where.columns());
        keyReads.forEach(grouped::addAll);
        final SelectQuery<Record> dirty =
                AggregateCompiler.dirtyKeySelect(renderContext, model, groupFields, grouped, where, windowPredicates);
        final Condition dirtyKeys = AggregateCompiler.tupleIn(groupFields, dirty);

        final List<Condition> conditions = new ArrayList<>(bucketBound(analysis, model, where, windowPredicates));
        conditions.add(dirtyKeys);
        final EnrichmentJoin restricted = AggregateCompiler.groupEnrichment(model, keyReads);
        Map<String, Condition> restrictions = Map.of();
        final List<Condition> preselected = new ArrayList<>(1);
        if (restricted != null) {
            final Condition absent = AggregateCompiler.tupleIn(absenceImage(keys, model), dirty);
            restrictions = Map.of(restricted.table().getName(), dirtyKeys.or(absent));
            // The partition pre-selection, as the form path applies it, with the same escape the restriction has: an
            // unmatched input row reads the absence image, and when that tuple is itself dirty such rows are
            // recomputed although no enrichment row puts their partition in the set — so the pre-selection yields to
            // it, and the statement reads every partition exactly when it has to. The escape is a constant the
            // engine folds, so the restriction still reaches the aligned joins through their tuple join condition.
            PartitionPreselection.of(restricted, dirtyKeys, List.of()).ifPresent(source -> preselected.add(source.or(absent)));
        }

        final SelectQuery<Record> select = builder.build(renderContext, model, block(analysis, bindings),
                StructuredQueryBuilder.MAX_LIMIT, LimitPolicy.NESTED, new LeafRestriction(conditions, restrictions, preselected));
        select.addSelect(DSL.field("now64(3)"));

        final List<String> targetColumns = new ArrayList<>(analysis.plan().columns());
        targetColumns.add(UPDATED_AT);
        final String columnList = targetColumns.stream()
                .map(column -> renderContext.render(DSL.name(column)))
                .collect(Collectors.joining(", "));
        final String sql = "INSERT INTO " + renderContext.render(DSL.name(Identifiers.require(target)))
                + " (" + columnList + ") " + select.getSQL(ParamType.INLINED);
        return new AggregateCompiler.Compiled(sql, targetColumns);
    }

    /**
     * The preview's sample: the block over the whole input, unwindowed, with the form preview's helper columns
     * ({@link AggregateCompiler#PREVIEW_HELPER_COLUMNS}) and order — newest group first, keys ascending — and at most
     * {@code limit} groups. Its select items are aliased by target column wherever that name is free
     * ({@link #sampleAliases} maps the rest back).
     */
    public SelectQuery<Record> compilePreview(AggregateSqlValidator.Analysis analysis, QueryEntityModel model,
            CursorColumns cursorColumns, int limit) {
        final Map<String, QueryFieldBinding> bindings = model.bindings();
        final List<Field<?>> groupFields = translate(keyExprs(analysis), bindings);
        final SelectQuery<Record> select = builder.build(renderContext, model, block(analysis, bindings),
                StructuredQueryBuilder.MAX_LIMIT, LimitPolicy.NESTED, LeafRestriction.NONE);
        final Field<Long> version = CursorPredicates.qualifiedVersionExpr(cursorColumns);
        final Field<String> identity = CursorPredicates.qualifiedIdentityExpr(cursorColumns);
        final Field<Long> latest = DSL.max(version);
        select.addSelect(latest.as(AggregateCompiler.LATEST_VERSION));
        select.addSelect(DSL.field("argMax({1}, ({0}, {1}))", String.class, version, identity)
                .as(AggregateCompiler.LATEST_IDENTITY));
        select.addSelect(DSL.field("count(*) OVER ()", Long.class).as(AggregateCompiler.TOTAL_GROUPS));
        select.addOrderBy(latest.desc());
        groupFields.forEach(field -> select.addOrderBy(field.asc()));
        select.addLimit(limit);
        return select;
    }

    /**
     * The sample's column aliases that are not their target column's name, mapped to that name — what a preview
     * renames before it returns rows keyed by target column. Empty in the ordinary case.
     */
    public Map<String, String> sampleAliases(AggregateSqlValidator.Analysis analysis, QueryEntityModel model) {
        final List<String> aliases = aliases(analysis, model.bindings());
        final Map<String, String> renamed = new LinkedHashMap<>();
        for (int i = 0; i < aliases.size(); i++) {
            final String column = analysis.plan().columns().get(i);
            if (!aliases.get(i).equals(column)) {
                renamed.put(aliases.get(i), column);
            }
        }
        return renamed;
    }

    // --- the block ----------------------------------------------------------------------------------

    /**
     * The planned block as the builder renders it here: {@code final} off, each select item aliased, and each
     * {@code GROUP BY} entry naming the field a plain key projects or the alias of an expression key's item.
     *
     * <p>An item is aliased by its target column unless that name is an entity field it does not itself project:
     * there the builder would resolve a {@code GROUP BY} of the name to the entity column (PostgreSQL's rule) or refuse
     * it as ambiguous, so the item takes the positional alias {@code __c<i>} instead. The INSERT maps by position, so
     * aliases never affect what is written.
     */
    private StructuredQuery block(AggregateSqlValidator.Analysis analysis, Map<String, QueryFieldBinding> bindings) {
        final StructuredQuery query = analysis.plan().translation().query();
        final List<String> aliases = aliases(analysis, bindings);
        final List<OutputColumn> select = new ArrayList<>(query.select().size());
        for (int i = 0; i < query.select().size(); i++) {
            select.add(new OutputColumn(query.select().get(i).expr(), aliases.get(i)));
        }
        final List<String> groupBy = new ArrayList<>(analysis.keyPositions().size());
        for (final int position : analysis.keyPositions()) {
            groupBy.add(query.select().get(position).expr() instanceof FieldExpr(String name) ? name : aliases.get(position));
        }
        return new StructuredQuery(query.entity(), query.filter(), query.mode(), false, select, groupBy,
                null, List.of(), null, false);
    }

    private static List<String> aliases(AggregateSqlValidator.Analysis analysis, Map<String, QueryFieldBinding> bindings) {
        final List<OutputColumn> select = analysis.plan().translation().query().select();
        final List<String> aliases = new ArrayList<>(select.size());
        for (int i = 0; i < select.size(); i++) {
            final String column = analysis.plan().columns().get(i);
            final boolean projectsItself = select.get(i).expr() instanceof FieldExpr(String name) && name.equals(column);
            aliases.add(bindings.containsKey(column) && !projectsItself ? POSITION_ALIAS + i : column);
        }
        return aliases;
    }

    private static List<Expr> keyExprs(AggregateSqlValidator.Analysis analysis) {
        final List<OutputColumn> select = analysis.plan().translation().query().select();
        return analysis.keyPositions().stream().map(position -> select.get(position).expr()).toList();
    }

    private List<Field<?>> translate(List<Expr> exprs, Map<String, QueryFieldBinding> bindings) {
        final List<Field<?>> fields = new ArrayList<>(exprs.size());
        for (final Expr expr : exprs) {
            fields.add(exprTranslator.toField(expr, bindings));
        }
        return fields;
    }

    private static Set<String> reads(Expr expr) {
        final Set<String> reads = new LinkedHashSet<>();
        ExprColumnCollector.collect(expr, reads);
        return reads;
    }

    /**
     * The key tuple an input row reads when its enrichment row is absent: each key rendered against bindings in which
     * every enrichment column is the literal {@code NULL} (and the source's own columns are themselves). Under the
     * {@code groupEnrichment} rule every key reads that one enrichment's columns only, so the result is a constant
     * tuple the engine folds — {@code ''} for {@code coalesce("<enrichment>.<column>", '')}. An enrichment column can
     * stand for NULL here because the gate admits a key over an enrichment column only when that column can represent
     * absence, which is what makes an unmatched LEFT JOIN row read NULL rather than a type default.
     */
    private List<Field<?>> absenceImage(List<Expr> keys, QueryEntityModel model) {
        final Set<String> enrichmentFields = new LinkedHashSet<>();
        model.enrichments().forEach(join -> enrichmentFields.addAll(join.exposedNames()));
        final Map<String, QueryFieldBinding> nulls = new LinkedHashMap<>(model.bindings());
        for (final String name : enrichmentFields) {
            final QueryFieldBinding binding = nulls.get(name);
            if (binding != null) {
                nulls.put(name, new QueryFieldBinding(binding.name(), DSL.field("NULL", binding.field().getDataType()),
                        binding.type(), binding.elementType(), binding.heavy()));
            }
        }
        return translate(keys, nulls);
    }

    /**
     * The pruning bound on the bucketed column, from {@code AggregateCompiler.bucketBound}. The bucketed operand is
     * the bucket call's timestamp argument — the last one of {@code date_trunc(unit, t)} and {@code date_bin(1, unit, t)}.
     */
    private List<Condition> bucketBound(AggregateSqlValidator.Analysis analysis, QueryEntityModel model,
            CompiledFilter where, List<Condition> windowPredicates) {
        final AggregateSqlValidator.Bucket bucket = analysis.bucket();
        if (bucket == null) {
            return List.of();
        }
        final FnExpr call = (FnExpr) analysis.plan().translation().query().select().get(bucket.position()).expr();
        final Expr operand = call.args().getLast();
        final Set<String> referenced = new LinkedHashSet<>(where.columns());
        referenced.addAll(reads(operand));
        return AggregateCompiler.bucketBound(renderContext, model, exprTranslator.toField(operand, model.bindings()),
                exprTranslator.toField(call, model.bindings()), bucket.unit().intervalKeyword(), referenced, where,
                windowPredicates);
    }
}
