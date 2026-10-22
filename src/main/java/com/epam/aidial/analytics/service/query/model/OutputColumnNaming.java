package com.epam.aidial.analytics.service.query.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the output column names of a {@code select} list. {@code as} is optional on every entry
 * ({@link OutputColumn}); when it is absent the name is derived the way PostgreSQL derives an unnamed
 * target-list name (its {@code FigureColname}), restricted to the expressions this DSL can express:
 *
 * <ul>
 *   <li>{@link FieldExpr} → the field name <em>verbatim</em>. An enrichment column's qualified flat
 *       name ({@code deployment_ref.display_name}) is kept whole: the dot is part of the exposed name,
 *       not a table qualifier to strip.
 *   <li>{@link FnExpr} → the lowercase function name ({@code count}, {@code sum}, {@code date_bin}),
 *       whether the function is scalar, aggregate, or ordered-set, and regardless of {@code distinct}.
 *   <li>{@link ValueExpr} / {@link ParamExpr} → {@value #UNNAMED}, PostgreSQL's own placeholder for a
 *       name it cannot derive.
 *   <li>{@link ArrayExpr} → {@code array}. Unreachable from a projection today (an array literal is
 *       only valid as an {@code in} right operand), mapped for totality.
 *   <li>{@link CaseExpr} → {@code case}; {@link WindowExpr} → its function's name, as PostgreSQL names
 *       {@code sum(x) OVER (…)} simply {@code sum}.
 * </ul>
 *
 * <p>Unlike PostgreSQL, output names must be <strong>unique</strong>: rows are name-keyed maps
 * ({@code QueryResultPage}) and a result schema is a flat field list, so two columns cannot share a
 * name. Uniqueness is established deterministically from the list order in two passes — explicit
 * aliases reserve their names first, then derived names take the first free {@code base}, {@code
 * base_1}, {@code base_2}, … So an explicit alias always appears under exactly the name the caller
 * asked for, and derived names move out of its way regardless of position.
 *
 * <p>Every surface resolves through this one class — execution ({@code StructuredQueryBuilder}), the
 * DSL→SQL renderer ({@code StructuredQueryToSqlRenderer}), and the SQL front end (which also reuses
 * {@link #assign} for the aliases it injects before validation) — so response keys, rendered SQL
 * aliases, and Arrow schema names cannot drift apart.
 */
public final class OutputColumnNaming {

    /** PostgreSQL's placeholder name for an output column whose name cannot be derived. */
    public static final String UNNAMED = "?column?";

    private OutputColumnNaming() {}

    /**
     * The final output names of {@code select}, positionally aligned with it: an entry's explicit
     * {@code as} (de-duplicated against earlier explicit aliases) or its derived name.
     *
     * @param select the select list; {@code null} or empty yields an empty list
     */
    public static List<String> resolve(List<OutputColumn> select) {
        if (select == null || select.isEmpty()) {
            return List.of();
        }
        final List<String> explicit = new ArrayList<>(select.size());
        final List<String> bases = new ArrayList<>(select.size());
        for (final OutputColumn column : select) {
            explicit.add(alias(column));
            bases.add(baseName(column.expr()));
        }
        return assign(explicit, bases);
    }

    /**
     * Builds the select list for {@code exprs} that resolves to exactly {@code names} while carrying
     * {@code as} only where it is load-bearing. The SQL front end uses this so a translated DSL
     * document keeps an alias only where it says something the DSL's own naming would not, yet always
     * executes to the names reported as its result columns.
     *
     * <p>Redundancy is decided against the list actually produced, never against a hypothetical
     * alias-free one: an entry that keeps its alias reserves that name in pass 1 of {@link #assign} and
     * shifts what the remaining derived entries get, so an alias is dropped only when re-resolving the
     * candidate list still yields {@code names} unchanged. (Without that check an explicit alias of the
     * shape {@code <base>_<n>} can look redundant next to a repeated base and be dropped, after which
     * the entry resolves to a different name than the one reported.)
     *
     * @param exprs the select expressions
     * @param names the resolved output name per expression, positionally aligned with {@code exprs}
     */
    public static List<OutputColumn> withMinimalAliases(List<Expr> exprs, List<String> names) {
        if (exprs.size() != names.size()) {
            throw new IllegalArgumentException(
                    "expression list of " + exprs.size() + " is not aligned with " + names.size() + " names");
        }
        final List<OutputColumn> select = new ArrayList<>(exprs.size());
        for (int i = 0; i < exprs.size(); i++) {
            select.add(new OutputColumn(exprs.get(i), names.get(i)));
        }
        for (int i = 0; i < select.size(); i++) {
            final OutputColumn aliased = select.get(i);
            select.set(i, new OutputColumn(aliased.expr(), null));
            if (!names.equals(resolve(select))) {
                select.set(i, aliased);
            }
        }
        return List.copyOf(select);
    }

    /**
     * Assigns unique output names from explicit names and derived base names, both positionally
     * aligned with the select list.
     *
     * <p>Pass 1 reserves the explicit names in list order, a repeat of an already-reserved name taking
     * the first free {@code <name>_<n>}. Pass 2 assigns the derived names in list order, each taking
     * the first free candidate from {@code base}, {@code base_1}, {@code base_2}, …
     *
     * <p>An entry with no explicit name and a {@code null} base occupies no name at all and stays
     * {@code null} in the result: the SQL front end uses that for a star item, which is already named
     * (by the columns it expands to, unknowable before validation) and so must reserve nothing — were it
     * given a base, it would consume a name no output ever carries and needlessly suffix a sibling.
     *
     * @param explicit per-entry caller-supplied name, {@code null} where the entry has none (or the
     *     whole list {@code null} to derive every name)
     * @param bases per-entry derived base name, consulted where {@code explicit} has no name;
     *     {@code null} for an entry that must not be named
     */
    public static List<String> assign(List<String> explicit, List<String> bases) {
        final int size = bases.size();
        final List<String> names = new ArrayList<>(java.util.Collections.nCopies(size, null));
        final Set<String> taken = new HashSet<>(size * 2);
        if (explicit != null) {
            for (int i = 0; i < size; i++) {
                final String name = explicit.get(i);
                if (name != null) {
                    names.set(i, firstFree(name, taken));
                }
            }
        }
        for (int i = 0; i < size; i++) {
            if (names.get(i) == null && bases.get(i) != null) {
                names.set(i, firstFree(bases.get(i), taken));
            }
        }
        return java.util.Collections.unmodifiableList(names);
    }

    /** The name PostgreSQL would give {@code expr} in a target list with no {@code AS}. */
    public static String baseName(Expr expr) {
        return switch (expr) {
            case FieldExpr(String name) -> name;
            case FnExpr fn -> fn.name() == null ? UNNAMED : fn.name().toLowerCase(Locale.ROOT);
            case ArrayExpr ignored -> "array";
            // PostgreSQL names a CASE target "case" and a windowed call after its function.
            case CaseExpr ignored -> "case";
            case WindowExpr window -> window.fn() == null ? UNNAMED : baseName(window.fn());
            // A scalar subquery takes its single column's name, as PostgreSQL does.
            case ScalarSubqueryExpr sub -> sub.subquery() == null || sub.subquery().columns().isEmpty()
                    ? UNNAMED : sub.subquery().columns().getFirst().name();
            case null, default -> UNNAMED;
        };
    }

    /** The caller-supplied alias, treating blank as absent. */
    private static String alias(OutputColumn column) {
        final String as = column.as();
        return as == null || as.isBlank() ? null : as;
    }

    /** {@code base} itself when free, else {@code base_1}, {@code base_2}, … Marks the result taken. */
    private static String firstFree(String base, Set<String> taken) {
        if (taken.add(base)) {
            return base;
        }
        for (int n = 1; ; n++) {
            final String candidate = base + "_" + n;
            if (taken.add(candidate)) {
                return candidate;
            }
        }
    }
}
