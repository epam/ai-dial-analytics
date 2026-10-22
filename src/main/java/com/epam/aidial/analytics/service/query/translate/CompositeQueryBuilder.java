package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.EnrichmentJoins;
import com.epam.aidial.analytics.service.query.JooqTypes;
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
import java.util.HashSet;
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
        return new Rendering(dsl, models, finalReads).render(root, LimitPolicy.GOVERNED, maxLimit);
    }

    /** One rendering pass: owns the alias counter and the translators bound to it as subquery renderer. */
    private final class Rendering implements SubqueryRenderer {
        private final DSLContext dsl;
        private final Function<String, QueryEntityModel> models;
        private final Consumer<QueryEntityModel> finalReads;
        private final StructuredQueryBuilder builder;
        private int aliases;

        Rendering(DSLContext dsl, Function<String, QueryEntityModel> models, Consumer<QueryEntityModel> finalReads) {
            this.dsl = dsl;
            this.models = models;
            this.finalReads = finalReads;
            this.builder = queryBuilder.withSubqueryRenderer(this);
        }

        /**
         * {@code leaf} built over the entity reference {@code entity} — its {@code final} taken from the
         * reference's {@code FINAL} mark — and reported to {@link #finalReads} when it reads final.
         */
        private SelectQuery<Record> buildLeaf(EntityRelation entity, QueryEntityModel model, StructuredQuery leaf,
                int maxLimit, LimitPolicy policy, List<Condition> keyConjuncts) {
            final StructuredQuery marked = leaf.withFinalRead(entity.finalRead());
            if (builder.readsFinal(model, marked)) {
                finalReads.accept(model);
            }
            return builder.build(dsl, model, marked, maxLimit, policy, keyConjuncts);
        }

        @Override
        public Select<?> render(CompositeQuery subquery) {
            return render(subquery, LimitPolicy.NESTED, 0);
        }

        SelectQuery<Record> render(CompositeQuery query, LimitPolicy policy, int maxLimit) {
            if (query.source() instanceof EntityRelation entity) {
                final QueryEntityModel model = models.apply(entity.entity());
                return buildLeaf(entity, model, withEntity(query.block(), model.entity()), maxLimit, policy, List.of());
            }
            StructuredQuery block = query.block();
            final QueryEntityModel model = switch (query.source()) {
                case EntityRelation entity -> throw new IllegalStateException("entity relation handled above: " + entity.entity());
                case DerivedRelation derived -> {
                    final String alias = nextAlias();
                    yield syntheticModel(alias, render(derived.query(), LimitPolicy.NESTED, 0).asTable(alias),
                            derived.columns(), alias);
                }
                case SetOpRelation setOp -> {
                    final String alias = nextAlias();
                    yield syntheticModel(alias, setOpTable(setOp).as(alias), setOp.columns(), alias);
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
            return builder.build(dsl, model, withEntity(block, model.entity()), maxLimit, policy);
        }

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
        private Table<?> setOpTable(SetOpRelation setOp) {
            final List<CompositeQuery> branches = setOp.branches();
            if (branches.size() < 2) {
                throw new IllegalStateException(
                        "a set operation needs at least two branches, got " + branches.size());
            }
            final String separator = " " + setOp.kind().keyword() + " " + setOp.qualifier() + " ";
            final StringBuilder template = new StringBuilder("(");
            final List<QueryPart> parts = new ArrayList<>(branches.size());
            for (int i = 0; i < branches.size(); i++) {
                if (i > 0) {
                    template.append(separator);
                }
                template.append("({").append(i).append("})");
                parts.add(render(branches.get(i), LimitPolicy.NESTED, 0));
            }
            return DSL.table(template.append(')').toString(), parts.toArray(QueryPart[]::new));
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
                bindings.put(column.column().name(), binding(column.column(), field));
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
            into.add(DSL.condition("{0} IN ({1})", reducedBinding.field(),
                    DSL.select(restrictedBinding.field()).from(restricted.table()).where(where)));
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
                    final Table<?> table =
                            buildLeaf(entity, model, leaf, 0, LimitPolicy.NESTED, keyConjuncts).asTable(alias);
                    yield new Operand(table, exposedFields(alias, columns));
                }
                case DerivedRelation derived -> {
                    final String alias = nextAlias();
                    final Table<?> table = render(derived.query(), LimitPolicy.NESTED, 0).asTable(alias);
                    yield new Operand(table, exposedFields(alias, derived.columns()));
                }
                case SetOpRelation setOp -> {
                    // The fold seals a set-operation join operand into a derived block, so this arm is not
                    // reached today; rendering it as an aliased derived table is what a set operation means
                    // in operand position, so it is implemented rather than left to throw.
                    final String alias = nextAlias();
                    yield new Operand(setOpTable(setOp).as(alias), exposedFields(alias, setOp.columns()));
                }
                case JoinRelation nested -> {
                    // A nested join renders flat — its operands' alias-qualified fields stay visible — which is
                    // what makes a left-deep chain read `t0 JOIN t1 ON … JOIN t2 ON …`. The SQL front end wraps a
                    // right-side join into a derived block before it gets here.
                    final QueryEntityModel joined = joinModel(nested, needed, OperandPushdown.none(null));
                    final Map<String, Field<?>> fields = new LinkedHashMap<>();
                    joined.bindings().forEach((name, binding) -> fields.put(name, binding.field()));
                    yield new Operand(joined.table(), fields);
                }
            };
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

        private static QueryEntityModel syntheticModel(
                String entity, Table<?> table, List<RelationColumn> columns, String alias) {
            final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
            for (final RelationColumn column : columns) {
                bindings.put(column.name(), binding(column,
                        DSL.field(DSL.name(alias, column.name()), JooqTypes.of(column.type()))));
            }
            return new QueryEntityModel(entity, table, bindings, List.of(), List.of());
        }

        private static QueryFieldBinding binding(RelationColumn column, Field<?> field) {
            return new QueryFieldBinding(column.name(), field, column.type(), column.elementType(), false);
        }

        private String nextAlias() {
            return "t" + aliases++;
        }

        private record Operand(Table<?> table, Map<String, Field<?>> fields) {}
    }
}
