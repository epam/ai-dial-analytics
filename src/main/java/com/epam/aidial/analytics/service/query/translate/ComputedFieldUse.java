package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ComputedFields;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.OutputColumnNaming;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The projection-only rule for computed fields (see {@code openspec/specs/structured-query/spec.md}, "Computed fields
 * are projection-only and completed after the statement runs"): a computed field's value exists only after the
 * statement has run, so a block may name one as a bare row-mode {@code select} entry and nowhere else. Checked before
 * anything is translated, so a refused query never reaches ClickHouse and every surface that builds through
 * {@link StructuredQueryBuilder} — execute, CSV, saved queries, SQL after translation, Flight, translate — refuses it
 * with the same message.
 */
final class ComputedFieldUse {

    /** The prefix of the output names ADAS reserves for its own helper columns. */
    private static final String RESERVED_PREFIX = "__adas_";

    private ComputedFieldUse() {
    }

    /**
     * Refuses every use of a computed field of {@code model} in {@code query} but a bare row-mode projection.
     *
     * @throws QueryValidationException naming the field, for a computed field in a filter, having, group-by or sort
     *     key, inside any expression, in an aggregate-mode or distinct query, or behind a sorted output name; and for a
     *     reserved output name in a query that projects one
     */
    static void requirePlainProjection(QueryEntityModel model, StructuredQuery query) {
        final Set<String> computed = computedNames(model);
        if (computed.isEmpty()) {
            return;
        }
        final Set<String> clauses = new LinkedHashSet<>(StructuredQueryBuilder.referencedFields(query.filter()));
        clauses.addAll(StructuredQueryBuilder.referencedFields(query.having()));
        if (query.groupBy() != null) {
            clauses.addAll(query.groupBy());
        }
        if (query.sort() != null) {
            query.sort().stream().map(SortItem::field).forEach(clauses::add);
        }
        refuseAny(clauses, computed);
        final List<OutputColumn> select = query.select() == null ? List.of() : query.select();
        if (query.mode() == QueryMode.AGGREGATE || query.distinct()) {
            refuseAny(StructuredQueryBuilder.referencedFields(query), computed);
        }
        final List<String> names = OutputColumnNaming.resolve(select);
        final Set<String> computedOutputs = new HashSet<>();
        for (int i = 0; i < select.size(); i++) {
            final OutputColumn column = select.get(i);
            if (column.expr() instanceof FieldExpr(String field) && computed.contains(field)) {
                computedOutputs.add(names.get(i));
                continue;
            }
            // Anything but the bare field: an argument of a function, an aggregate, a case branch, a window, a lambda.
            refuseAny(StructuredQueryBuilder.referencedFields(single(column)), computed);
        }
        if (computedOutputs.isEmpty()) {
            return;
        }
        if (query.sort() != null) {
            for (final SortItem item : query.sort()) {
                if (computedOutputs.contains(item.field())) {
                    throw new QueryValidationException(ComputedFields.projectionOnlyMessage(fieldOf(select, names, item.field()))
                            + "; sort key '" + item.field() + "' names its output");
                }
            }
        }
        for (final String name : names) {
            if (name != null && name.startsWith(RESERVED_PREFIX)) {
                throw new QueryValidationException("output name '" + name + "' is reserved: names starting with '"
                        + RESERVED_PREFIX + "' are not allowed in a query that selects a computed field");
            }
        }
    }

    /**
     * Refuses a computed field of {@code model} anywhere in {@code block}, a block of a composite statement: an
     * enclosing block would consume the field's stand-in, which the completion step never sees.
     */
    static void refuseInComposite(QueryEntityModel model, StructuredQuery block) {
        final Set<String> computed = computedNames(model);
        if (computed.isEmpty()) {
            return;
        }
        for (final String name : StructuredQueryBuilder.referencedFields(block)) {
            if (computed.contains(name)) {
                throw new QueryValidationException(ComputedFields.projectionOnlyMessage(name)
                        + " by a single-entity statement; it cannot be read in a composite statement (a join, a derived"
                        + " table, a common table expression, a set operation or a subquery)");
            }
        }
    }

    private static Set<String> computedNames(QueryEntityModel model) {
        final Set<String> names = new HashSet<>();
        model.bindings().forEach((name, binding) -> {
            if (binding.isComputed()) {
                names.add(name);
            }
        });
        return names;
    }

    private static void refuseAny(Set<String> referenced, Set<String> computed) {
        for (final String name : referenced) {
            if (name != null && computed.contains(name)) {
                throw new QueryValidationException(ComputedFields.projectionOnlyMessage(name));
            }
        }
    }

    private static String fieldOf(List<OutputColumn> select, List<String> names, String output) {
        for (int i = 0; i < select.size(); i++) {
            if (output.equals(names.get(i)) && select.get(i).expr() instanceof FieldExpr(String field)) {
                return field;
            }
        }
        return output;
    }

    /** A row-mode block selecting {@code column} alone, so its references can be collected with the shared walk. */
    private static StructuredQuery single(OutputColumn column) {
        return new StructuredQuery("_", null, QueryMode.ROW, false, List.of(column), null, null, null, null);
    }
}
