package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ColumnMark;
import com.epam.aidial.analytics.service.query.EnrichmentJoins;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.OutputFlag;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.WildcardProjection;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.DerivedRelation;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.JoinRelation;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.Relation;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder.LimitPolicy;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.QueryPart;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.SelectQuery;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Renders a composite SQL plan — a tree of {@link CompositeQuery} blocks over entities, derived tables and
 * joins — into one jOOQ {@link SelectQuery}, with every block rendered by the ordinary
 * {@link StructuredQueryBuilder}.
 *
 * <p>The root block over a single entity renders exactly as a single-entity query does. Every other block
 * renders against a <b>synthetic</b> {@link QueryEntityModel}: its table is the derived table or join the
 * block reads from, and its bindings map the block's column names to alias-qualified fields, so
 * {@code ExprTranslator}/{@code FilterTranslator} render the block's expressions with their usual type
 * rules and the function catalog's single rendering. A join operand that is an entity is itself wrapped
 * as a derived table projecting the columns the plan references, which is what keeps
 * enrichment joins, source-column names and sensitive-column masking inside the existing leaf rendering.
 *
 * <p>Nested blocks render under {@link LimitPolicy#NESTED}; only the root takes the caller's row
 * governance. Aliases {@code t0, t1, …} are allocated per rendering.
 *
 * <p>A join block's {@code WHERE} conjuncts that read one entity operand alone are evaluated inside that operand's
 * block, and an equi-join operand is reduced to the keys its restricted partner can produce (see
 * {@link OperandPushdown} for which side admits which). Both bound an operand that otherwise reads its whole source
 * and hashes every enrichment it joins; neither changes the statement's rows.
 */
@Component
public class CompositeQueryBuilder {

    private final StructuredQueryBuilder queryBuilder;
    private final boolean operandPushdown;

    @Autowired
    public CompositeQueryBuilder(StructuredQueryBuilder queryBuilder) {
        this(queryBuilder, true);
    }

    private CompositeQueryBuilder(StructuredQueryBuilder queryBuilder, boolean operandPushdown) {
        this.queryBuilder = queryBuilder;
        this.operandPushdown = operandPushdown;
    }

    /**
     * This builder with operand pushdown and semi-join reduction switched off: a join block's {@code WHERE} stays
     * above the join and every entity operand renders unrestricted. The reference both rewrites are proved against:
     * their tests run both and compare what ClickHouse returns.
     */
    public CompositeQueryBuilder withoutOperandPushdown() {
        return new CompositeQueryBuilder(queryBuilder, false);
    }

    /**
     * Renders {@code root} under the caller's row ceiling. {@code models} resolves each entity of the plan to
     * its catalog model and fails (as the DSL path does) for one that is not queryable.
     */
    public SelectQuery<Record> build(
            DSLContext dsl, CompositeQuery root, Function<String, QueryEntityModel> models, int maxLimit) {
        return build(dsl, root, models, maxLimit, model -> { });
    }

    /**
     * As {@link #build(DSLContext, CompositeQuery, Function, int)}, reporting to {@code finalReads} the model of
     * every entity reference the statement reads {@code FINAL} — once per reference, as it is rendered. The
     * caller needs exactly that set to decide the statement's partition-local switch, which applies only to
     * tables read under {@code FINAL}.
     */
    public SelectQuery<Record> build(
            DSLContext dsl,
            CompositeQuery root,
            Function<String, QueryEntityModel> models,
            int maxLimit,
            Consumer<QueryEntityModel> finalReads) {
        final Rendering rendering = new Rendering(dsl, models, finalReads, root);
        return rendering.render(root, LimitPolicy.GOVERNED, maxLimit, rendering.marked ? MaskMarking.Mode.ROOT : MaskMarking.Mode.NONE);
    }

    /**
     * One rendering pass: owns the alias counter and the translators bound to it as subquery renderer.
     *
     * <p>A plan that reads a masked field carries marks (see {@code openspec/specs/structured-query/spec.md}, "Masked
     * inputs are reported per output cell"): its root block projects the statement's flags, and every block nested in it
     * — a derived table, a CTE, a set-operation branch, a join operand, a scalar subquery — projects a mark flag and a
     * bare-absent flag per output ({@link com.epam.aidial.analytics.service.query.OutputFlag}), which the block reading
     * it binds to the operand's columns ({@link ColumnMark}). A plan that reads none renders as it always has.
     */
    private final class Rendering implements SubqueryRenderer {
        private final DSLContext dsl;
        private final Function<String, QueryEntityModel> models;
        private final Consumer<QueryEntityModel> finalReads;
        private final StructuredQueryBuilder builder;
        private final boolean marked;
        private final Set<String> declared;
        private final Map<CompositeQuery, Field<?>> scalarFlags = new IdentityHashMap<>();
        private int aliases;

        Rendering(DSLContext dsl, Function<String, QueryEntityModel> models, Consumer<QueryEntityModel> finalReads,
                CompositeQuery root) {
            this.dsl = dsl;
            // Resolved once per entity: deciding whether the plan carries marks reads every entity it names first.
            final Map<String, QueryEntityModel> resolved = new HashMap<>();
            this.models = entity -> resolved.computeIfAbsent(entity, models);
            this.finalReads = finalReads;
            this.builder = queryBuilder.withSubqueryRenderer(this);
            this.marked = MaskedPlanRules.readsMaskedField(root, entity -> maskedFields(this.models.apply(entity)));
            // A name the caller declares could capture an operand alias's flag columns ("t0"."__adas_mf_0") and lose a
            // mark, so in a plan that carries marks the aliases skip every one.
            this.declared = marked ? MaskedPlanRules.declaredNames(root) : Set.of();
        }

        private static Set<String> maskedFields(QueryEntityModel model) {
            final Set<String> masked = new HashSet<>();
            model.bindings().forEach((name, binding) -> {
                if (binding.isMasked()) {
                    masked.add(name);
                }
            });
            return masked;
        }

        /** The mode of a block nested in the plan. */
        private MaskMarking.Mode nested() {
            return marked ? MaskMarking.Mode.NESTED : MaskMarking.Mode.NONE;
        }

        /**
         * {@code leaf} built over the entity reference {@code entity} — its {@code final} taken from the
         * reference's {@code FINAL} mark — and reported to {@link #finalReads} when it reads final.
         */
        private SelectQuery<Record> buildLeaf(EntityRelation entity, QueryEntityModel model, StructuredQuery leaf,
                int maxLimit, LimitPolicy policy, List<Condition> keyConjuncts, MaskMarking.Mode marks) {
            // Every entity read of a composite plan is a leaf built here, so this one check covers joins, derived
            // tables, set operations and subqueries alike.
            ComputedFieldUse.refuseInComposite(model, leaf);
            final StructuredQuery finalMarked = leaf.withFinalRead(entity.finalRead());
            if (builder.readsFinal(model, finalMarked)) {
                finalReads.accept(model);
            }
            return builder.build(dsl, model, finalMarked, maxLimit, policy, keyConjuncts, marks);
        }

        /**
         * A scalar subquery with its own mark, in a plan that reads a masked field: {@code tuple(<value>, <its output's mark
         * flag>)}, read back as the value and the flag ({@code ifNull(…, 0)}: a subquery finding no row is a null value with
         * no absent input).
         */
        @Override
        public ScalarSubquery renderScalar(CompositeQuery subquery) {
            if (!marked) {
                return null;
            }
            // An array or map output reads its empty value where its cell is absent (marked) or the subquery finds no row, as
            // the spec states for a scalar subquery over such a field; the plain form refuses the empty subquery.
            final SelectQuery<Record> select = render(subquery, LimitPolicy.NESTED, 0, MaskMarking.Mode.NESTED);
            final Flags flags = Flags.of(select);
            final Field<?> output = select.getSelect().getFirst();
            final String alias = nextAlias();
            final Field<Object> tuple = DSL.field(DSL.select(DSL.field("tuple({0}, {1})", Object.class,
                    DSL.field(DSL.name(alias, output.getName())), DSL.field(DSL.name(alias, flags.mark().get(0)))))
                    .from(select.asTable(alias)));
            final Field<?> flag = DSL.field("ifNull(tupleElement({0}, 2), 0)", Integer.class, tuple);
            scalarFlags.put(subquery, flag);
            return new ScalarSubquery(DSL.field("tupleElement({0}, 1)", output.getDataType(), tuple), flag);
        }

        @Override
        public Field<?> scalarFlag(CompositeQuery subquery) {
            return scalarFlags.get(subquery);
        }

        /** A subquery a predicate embeds ({@code in}, {@code exists}): its set or truth marks nothing. */
        @Override
        public Select<?> render(CompositeQuery subquery) {
            return render(subquery, LimitPolicy.NESTED, 0, MaskMarking.Mode.NONE);
        }

        SelectQuery<Record> render(CompositeQuery query, LimitPolicy policy, int maxLimit, MaskMarking.Mode marks) {
            if (query.source() instanceof EntityRelation entity) {
                final QueryEntityModel model = models.apply(entity.entity());
                return buildLeaf(entity, model, withEntity(query.block(), model.entity()), maxLimit, policy, List.of(), marks);
            }
            StructuredQuery block = query.block();
            final QueryEntityModel model = switch (query.source()) {
                case EntityRelation entity -> throw new IllegalStateException("entity relation handled above: " + entity.entity());
                case DerivedRelation derived -> {
                    final String alias = nextAlias();
                    final SelectQuery<Record> inner = render(derived.query(), LimitPolicy.NESTED, 0, nested());
                    yield syntheticModel(alias, inner.asTable(alias), derived.columns(), alias, Flags.of(inner));
                }
                case SetOpRelation setOp -> {
                    final String alias = nextAlias();
                    final SetOpTable table = setOpTable(setOp);
                    yield syntheticModel(alias, table.table().as(alias), setOp.columns(), alias, table.flags());
                }
                case JoinRelation join -> {
                    final OperandPushdown.Plan plan = operandPushdown
                            ? OperandPushdown.plan(join, block.filter())
                            : OperandPushdown.none(block.filter());
                    // What moved into an operand is no longer read above the join, so the operands need not
                    // project the columns only it named.
                    block = block.withFilter(plan.remaining());
                    yield joinModel(join, StructuredQueryBuilder.referencedFields(block), plan);
                }
            };
            final StructuredQuery resolved = withEntity(block, model.entity());
            ComputedFieldUse.requirePlainProjection(model, resolved);
            return builder.build(dsl, model, resolved, maxLimit, policy, List.of(), marks);
        }

        /**
         * A nested block's flag column names, by output position, and the positions whose flag is the constant {@code 0};
         * empty when it projects none.
         */
        private record Flags(Map<Integer, String> mark, Map<Integer, String> bare, Set<Integer> zeroMark, Set<Integer> zeroBare) {

            static final Flags NONE = new Flags(Map.of(), Map.of(), Set.of(), Set.of());

            static Flags of(SelectQuery<?> select) {
                return of(List.of(select));
            }

            /** The flags of set-operation branches: the first branch's names, a flag zero only where it is in every one. */
            static Flags of(List<? extends SelectQuery<?>> branches) {
                final Map<Integer, String> mark = new HashMap<>();
                final Map<Integer, String> bare = new HashMap<>();
                final Set<Integer> zeroMark = new HashSet<>();
                final Set<Integer> zeroBare = new HashSet<>();
                final Set<Integer> liveMark = new HashSet<>();
                final Set<Integer> liveBare = new HashSet<>();
                for (int b = 0; b < branches.size(); b++) {
                    for (final Field<?> field : branches.get(b).getSelect()) {
                        final OutputFlag flag = OutputFlag.of(field);
                        if (flag == null) {
                            continue;
                        }
                        if (b == 0) {
                            (flag.bare() ? bare : mark).put(flag.position(), field.getName());
                        }
                        if (!flag.zero()) {
                            (flag.bare() ? liveBare : liveMark).add(flag.position());
                        }
                    }
                }
                mark.keySet().stream().filter(position -> !liveMark.contains(position)).forEach(zeroMark::add);
                bare.keySet().stream().filter(position -> !liveBare.contains(position)).forEach(zeroBare::add);
                return new Flags(Map.copyOf(mark), Map.copyOf(bare), Set.copyOf(zeroMark), Set.copyOf(zeroBare));
            }

            /**
             * The marks of output {@code position} of the operand aliased {@code alias}, or {@code null} when the operand's
             * output reads nothing that can be absent.
             */
            ColumnMark at(String alias, int position, boolean outerJoin) {
                final String flag = mark.get(position);
                if (flag == null || zeroMark.contains(position)) {
                    return null;
                }
                final String absent = bare.get(position);
                return new ColumnMark(ref(alias, flag, outerJoin),
                        absent == null || zeroBare.contains(position) ? null : ref(alias, absent, outerJoin));
            }

            /**
             * A flag column of the operand aliased {@code alias}; across an outer join wrapped {@code ifNull(…, 0)}: an
             * unmatched operand's cells are null for want of a match, which marks nothing.
             */
            private static Field<?> ref(String alias, String name, boolean outerJoin) {
                final Field<Integer> column = DSL.field(DSL.name(alias, name), Integer.class);
                return outerJoin ? DSL.field("ifNull({0}, 0)", Integer.class, column) : column;
            }
        }

        /** A set operation as a table, and its flag columns by output position. */
        private record SetOpTable(Table<?> table, Flags flags) {}

        /** The block with its entity set to the model it renders against (a leaf's own name, else the alias). */
        private static StructuredQuery withEntity(StructuredQuery block, String entity) {
            return entity.equals(block.entity()) ? block : block.withEntity(entity);
        }

        /**
         * A set operation as a table: every branch rendered by the ordinary nested-block path,
         * parenthesized, and joined by the operator with its qualifier spelled out.
         *
         * <p>The keyword is written literally rather than through jOOQ's {@code union()}/{@code intersect()}
         * API because jOOQ chooses the spelling per dialect: a bare {@code UNION} is refused outright by
         * ClickHouse's empty {@code union_default_mode}, and {@code intersectAll}/{@code exceptAll} are
         * emulated where jOOQ believes the dialect lacks them. Parenthesising each branch is required, not
         * cosmetic — a branch carrying its own {@code ORDER BY}/{@code LIMIT} only parses inside
         * parentheses.
         */
        private SetOpTable setOpTable(SetOpRelation setOp) {
            final List<CompositeQuery> branches = setOp.branches();
            if (branches.size() < 2) {
                throw new IllegalStateException(
                        "a set operation needs at least two branches, got " + branches.size());
            }
            // In a plan that reads a masked field every union branch projects two flags per output, so the branches align.
            // An intersect or except compares whole rows, flags included, and MaskedPlanRules refuses one over a marked
            // output, so its branches project none.
            final boolean union = marked && setOp.kind() == SetOpKind.UNION;
            final List<SelectQuery<Record>> rendered = new ArrayList<>(branches.size());
            for (final CompositeQuery branch : branches) {
                rendered.add(render(branch, LimitPolicy.NESTED, 0, union ? nested() : MaskMarking.Mode.NONE));
            }
            final Flags flags = union ? Flags.of(rendered) : Flags.NONE;
            // A union none of whose positions can be marked keeps the plain form.
            if (union && !setOp.all() && flags.zeroMark().size() < flags.mark().size()) {
                return distinctUnion(rendered);
            }
            return new SetOpTable(setOpTemplate(rendered, " " + setOp.kind().keyword() + " " + setOp.qualifier() + " "), flags);
        }

        private static Table<?> setOpTemplate(List<? extends QueryPart> branches, String separator) {
            final StringBuilder template = new StringBuilder("(");
            for (int i = 0; i < branches.size(); i++) {
                if (i > 0) {
                    template.append(separator);
                }
                template.append("({").append(i).append("})");
            }
            return DSL.table(template.append(')').toString(), branches.toArray(QueryPart[]::new));
        }

        /**
         * A {@code UNION DISTINCT} whose rows carry marks: the {@code UNION ALL} of its branches grouped by the outputs and
         * each output's bare-absent flag, with {@code max} of each mark flag, so the marks of the rows that collapse
         * combine instead of splitting the row. Each branch also projects its ordinal and {@code rowNumberInAllBlocks()}
         * over the branch as rendered, and the grouped rows are ordered by {@code min((ordinal, row))}: each group's first
         * occurrence, the rows ClickHouse's own {@code UNION DISTINCT} returns for the same text.
         */
        private SetOpTable distinctUnion(List<SelectQuery<Record>> branches) {
            final SelectQuery<Record> first = branches.getFirst();
            final Flags flags = Flags.of(branches);
            final List<String> outputs = new ArrayList<>();
            final Set<String> names = new HashSet<>();
            for (final Field<?> field : first.getSelect()) {
                names.add(field.getName());
                if (OutputFlag.of(field) == null) {
                    outputs.add(field.getName());
                }
            }
            final String ordinal = unique("__adas_branch", names);
            final String row = unique("__adas_row", names);
            final List<QueryPart> numbered = new ArrayList<>(branches.size());
            for (int i = 0; i < branches.size(); i++) {
                final String alias = nextAlias();
                numbered.add(DSL.select(DSL.table(DSL.name(alias)).asterisk(), DSL.inline(i).as(ordinal),
                        DSL.field("rowNumberInAllBlocks()", Long.class).as(row)).from(branches.get(i).asTable(alias)));
            }
            final String all = nextAlias();
            final SelectQuery<Record> grouped = dsl.selectQuery();
            grouped.addFrom(setOpTemplate(numbered, " UNION ALL ").as(all));
            final List<Field<?>> keys = new ArrayList<>();
            for (final String output : outputs) {
                final Field<?> column = DSL.field(DSL.name(all, output));
                grouped.addSelect(column.as(output));
                keys.add(column);
            }
            for (int i = 0; i < outputs.size(); i++) {
                final Field<?> mark = DSL.field(DSL.name(all, flags.mark().get(i)), Integer.class);
                final Field<?> bare = DSL.field(DSL.name(all, flags.bare().get(i)), Integer.class);
                grouped.addSelect(new OutputFlag(DSL.field("max({0})", Integer.class, mark), i, false, flags.zeroMark().contains(i))
                        .as(flags.mark().get(i)));
                grouped.addSelect(new OutputFlag(bare, i, true, flags.zeroBare().contains(i)).as(flags.bare().get(i)));
                keys.add(bare);
            }
            grouped.addGroupBy(keys);
            grouped.addOrderBy(DSL.field("min(({0}, {1}))", Object.class, DSL.field(DSL.name(all, ordinal)),
                    DSL.field(DSL.name(all, row))).asc());
            return new SetOpTable(DSL.table(grouped), flags);
        }

        private static String unique(String name, Set<String> taken) {
            String candidate = name;
            while (taken.contains(candidate)) {
                candidate = candidate + "_";
            }
            return candidate;
        }

        /**
         * A join as a model: the operands rendered as aliased derived tables and joined left-deep, the exposed
         * columns bound to the side and inner column they come from. {@code referenced} is the set of the
         * join's column names the enclosing block uses; together with the {@code ON} condition's names it
         * decides which columns each operand must expose, so a leaf entity projects only what is read — never
         * a heavy column nobody asked for (column pruning).
         */
        private QueryEntityModel joinModel(JoinRelation join, Set<String> referenced, OperandPushdown.Plan plan) {
            final Set<String> needed = new HashSet<>(referenced);
            needed.addAll(StructuredQueryBuilder.referencedFields(join.on()));
            final Set<String> leftNeeded = new LinkedHashSet<>();
            final Set<String> rightNeeded = new LinkedHashSet<>();
            for (final JoinRelation.JoinColumn column : join.joinColumns()) {
                if (needed.contains(column.column().name())) {
                    (column.fromLeft() ? leftNeeded : rightNeeded).add(column.sourceName());
                }
            }
            final QueryEntityModel leftModel =
                    join.left() instanceof EntityRelation entity ? models.apply(entity.entity()) : null;
            final QueryEntityModel rightModel =
                    join.right() instanceof EntityRelation entity ? models.apply(entity.entity()) : null;
            final List<Condition> leftKeys = new ArrayList<>(1);
            final List<Condition> rightKeys = new ArrayList<>(1);
            if (plan.reducesLeft(join.kind())) {
                addReduction(leftKeys, leftModel, plan.leftKey(), rightModel, plan.rightKey(), plan.right());
            }
            if (plan.reducesRight(join.kind())) {
                addReduction(rightKeys, rightModel, plan.rightKey(), leftModel, plan.leftKey(), plan.left());
            }
            final Operand left = operand(join.left(), leftNeeded, leftModel, plan.left(), leftKeys);
            final Operand right = operand(join.right(), rightNeeded, rightModel, plan.right(), rightKeys);
            final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
            for (final JoinRelation.JoinColumn column : join.joinColumns()) {
                if (!needed.contains(column.column().name())) {
                    continue;
                }
                final Operand side = column.fromLeft() ? left : right;
                final Field<?> field = side.fields.get(column.sourceName());
                if (field == null) {
                    throw new IllegalStateException("join column '" + column.column().name()
                            + "' refers to an unknown operand column '" + column.sourceName() + "'");
                }
                final QueryFieldBinding binding = binding(column.column(), field);
                final ColumnMark mark = side.marks.get(column.sourceName());
                bindings.put(column.column().name(), mark == null ? binding : binding.marked(mark));
            }
            final Table<?> joined;
            if (join.kind() == com.epam.aidial.analytics.service.query.model.JoinKind.CROSS) {
                joined = left.table.crossJoin(right.table);
            } else {
                final Condition on = builder.filterTranslator().toCondition(join.on(), bindings);
                joined = switch (join.kind()) {
                    case INNER -> left.table.join(right.table).on(on);
                    case LEFT -> left.table.leftOuterJoin(right.table).on(on);
                    case RIGHT -> left.table.rightOuterJoin(right.table).on(on);
                    case FULL -> left.table.fullOuterJoin(right.table).on(on);
                    case CROSS -> throw new IllegalStateException("cross join handled above");
                };
            }
            return new QueryEntityModel("join", joined, bindings, List.of(), List.of());
        }

        /**
         * Adds to {@code into} the reduction of an operand ({@code reduced}) to the values of {@code reducedKey} that
         * its partner can produce: {@code reducedKey IN (SELECT restrictedKey FROM <restricted source> WHERE <its
         * moved source-only conjuncts>)}. Nothing is added when the partner carries no moved source-only conjunct, or
         * when either key is not a source column: a reduction on an enrichment column could key nothing.
         *
         * <p>Built from the restricted side's own moved conjuncts only, never from a reduction of it (one hop), and
         * read without the {@code FINAL} modifier, as the grain-join key subquery is: under the same predicates the
         * raw key set is a superset of the deduplicated one, so no key the partner produces is lost.
         */
        private void addReduction(List<Condition> into, QueryEntityModel reduced, String reducedKey,
                QueryEntityModel restricted, String restrictedKey, List<FilterNode> restrictedConjuncts) {
            if (reduced == null || restricted == null || restrictedConjuncts.isEmpty()) {
                return;
            }
            final List<FilterNode> sourceOnly =
                    StructuredQueryBuilder.sourceOnlyConjuncts(restricted, OperandPushdown.and(restrictedConjuncts));
            final QueryFieldBinding reducedBinding = reduced.bindings().get(reducedKey);
            final QueryFieldBinding restrictedBinding = restricted.bindings().get(restrictedKey);
            if (sourceOnly.isEmpty() || reducedBinding == null || restrictedBinding == null
                    || !EnrichmentJoins.referencesSourceOnly(reduced, Set.of(reducedKey))
                    || !EnrichmentJoins.referencesSourceOnly(restricted, Set.of(restrictedKey))) {
                return;
            }
            final List<Condition> where = new ArrayList<>(sourceOnly.size());
            for (final FilterNode conjunct : sourceOnly) {
                where.add(builder.filterTranslator().toCondition(conjunct, restricted.bindings()));
            }
            into.add(DSL.condition("{0} IN ({1})", reducedBinding.physicalField(),
                    DSL.select(restrictedBinding.physicalField()).from(restricted.table()).where(where)));
        }

        /**
         * A join operand: an aliased table plus its exposed columns, keyed by the operand's own column names.
         * {@code needed} is the subset of those names the join reads; an entity leaf projects exactly them, evaluates
         * the {@code moved} conjuncts (already in its own column names) as its filter, and hands {@code keyConjuncts}
         * (its reduction) to the builder, which ANDs them into its {@code WHERE} and keys its grain joins by them.
         * {@code model} is the entity's model, {@code null} for any other relation, which takes neither.
         */
        private Operand operand(Relation relation, Set<String> needed, QueryEntityModel model, List<FilterNode> moved,
                List<Condition> keyConjuncts) {
            return switch (relation) {
                case EntityRelation entity -> {
                    // The leaf projects exactly the referenced flat fields, through the ordinary builder — which
                    // is what carries the enrichment joins, the source column names and the masking with it.
                    final String alias = nextAlias();
                    final List<RelationColumn> columns = new ArrayList<>();
                    for (final RelationColumn column : entity.columns()) {
                        if (needed.contains(column.name())) {
                            columns.add(column);
                        }
                    }
                    if (columns.isEmpty()) {
                        // Nothing of this side is read (SELECT count(*) FROM a CROSS JOIN b): the derived table
                        // still needs one column, so take the cheapest — the first wildcard field, never a heavy one.
                        columns.add(anchorColumn(entity, model));
                    }
                    final List<OutputColumn> select = new ArrayList<>(columns.size());
                    for (final RelationColumn column : columns) {
                        select.add(new OutputColumn(new FieldExpr(column.name()), column.name()));
                    }
                    final StructuredQuery leaf = new StructuredQuery(
                            entity.entity(), OperandPushdown.and(moved), QueryMode.ROW, false, select, null, null, null, null);
                    final SelectQuery<Record> built = buildLeaf(entity, model, leaf, 0, LimitPolicy.NESTED, keyConjuncts, nested());
                    yield new Operand(built.asTable(alias), exposedFields(alias, columns), marks(alias, columns, Flags.of(built)));
                }
                case DerivedRelation derived -> {
                    final String alias = nextAlias();
                    final SelectQuery<Record> built = render(derived.query(), LimitPolicy.NESTED, 0, nested());
                    yield new Operand(built.asTable(alias), exposedFields(alias, derived.columns()),
                            marks(alias, derived.columns(), Flags.of(built)));
                }
                case SetOpRelation setOp -> {
                    // The fold seals a set-operation join operand into a derived block, so this arm is not
                    // reached today; rendering it as an aliased derived table is what a set operation means
                    // in operand position, so it is implemented rather than left to throw.
                    final String alias = nextAlias();
                    final SetOpTable table = setOpTable(setOp);
                    yield new Operand(table.table().as(alias), exposedFields(alias, setOp.columns()),
                            marks(alias, setOp.columns(), table.flags()));
                }
                case JoinRelation nested -> {
                    // A nested join renders flat — its operands' alias-qualified fields stay visible — which is
                    // what makes a left-deep chain read `t0 JOIN t1 ON … JOIN t2 ON …`. The SQL front end wraps a
                    // right-side join into a derived block before it gets here.
                    final QueryEntityModel joined = joinModel(nested, needed, OperandPushdown.none(null));
                    final Map<String, Field<?>> fields = new LinkedHashMap<>();
                    final Map<String, ColumnMark> marks = new HashMap<>();
                    joined.bindings().forEach((name, binding) -> {
                        fields.put(name, binding.physicalField());
                        if (binding.mark() != null) {
                            marks.put(name, binding.mark());
                        }
                    });
                    yield new Operand(joined.table(), fields, marks);
                }
            };
        }

        /** By column name, the marks a join operand aliased {@code alias} carries for {@code columns}, across the join. */
        private static Map<String, ColumnMark> marks(String alias, List<RelationColumn> columns, Flags flags) {
            final Map<String, ColumnMark> marks = new HashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                final ColumnMark mark = flags.at(alias, i, true);
                if (mark != null) {
                    marks.put(columns.get(i).name(), mark);
                }
            }
            return marks;
        }

        /** The column an otherwise unread join operand projects: its first wildcard field, else its first column. */
        private static RelationColumn anchorColumn(EntityRelation entity, QueryEntityModel model) {
            final List<String> wildcard = WildcardProjection.sourceFields(model);
            for (final RelationColumn column : entity.columns()) {
                if (!wildcard.isEmpty() && wildcard.getFirst().equals(column.name())) {
                    return column;
                }
            }
            if (entity.columns().isEmpty()) {
                throw new QueryValidationException("entity '" + entity.entity() + "' exposes no column to join on");
            }
            return entity.columns().getFirst();
        }

        private static Map<String, Field<?>> exposedFields(String alias, List<RelationColumn> columns) {
            final Map<String, Field<?>> fields = new LinkedHashMap<>();
            for (final RelationColumn column : columns) {
                fields.put(column.name(), DSL.field(DSL.name(alias, column.name()), JooqTypes.of(column.type())));
            }
            return fields;
        }

        /** A block over a derived table or a set operation, each column bound with the marks the operand carries for it. */
        private static QueryEntityModel syntheticModel(
                String entity, Table<?> table, List<RelationColumn> columns, String alias, Flags flags) {
            final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                final RelationColumn column = columns.get(i);
                final QueryFieldBinding binding = binding(column,
                        DSL.field(DSL.name(alias, column.name()), JooqTypes.of(column.type())));
                final ColumnMark mark = flags.at(alias, i, false);
                bindings.put(column.name(), mark == null ? binding : binding.marked(mark));
            }
            return new QueryEntityModel(entity, table, bindings, List.of(), List.of());
        }

        private static QueryFieldBinding binding(RelationColumn column, Field<?> field) {
            return new QueryFieldBinding(column.name(), field, column.type(), column.elementType(), false);
        }

        private String nextAlias() {
            String alias;
            do {
                alias = "t" + aliases++;
            } while (declared.contains(alias));
            return alias;
        }

        private record Operand(Table<?> table, Map<String, Field<?>> fields, Map<String, ColumnMark> marks) {}
    }
}
