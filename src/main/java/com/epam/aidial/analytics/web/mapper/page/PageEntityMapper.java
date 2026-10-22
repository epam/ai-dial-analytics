package com.epam.aidial.analytics.web.mapper.page;

import com.epam.aidial.analytics.exception.InvalidFilterColumnException;
import com.epam.aidial.analytics.exception.InvalidFilterValueException;
import com.epam.aidial.analytics.web.dto.page.FilterDto;
import com.epam.aidial.analytics.web.dto.page.PageRequestDto;
import com.epam.aidial.analytics.web.dto.page.SortDirectionDto;
import com.epam.aidial.analytics.web.dto.page.SortDto;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link PageRequestDto} into the Spring Data {@link Specification} and {@link PageRequest} that
 * answer it, against an allow-listed set of columns.
 *
 * <p>Generic over the entity so a second paged read surface reuses it by passing its own allow-list.
 * Every filterable attribute is a scalar on the root entity by construction — the wire contract carries
 * flat column names, so navigating an association for one special case would be a hidden asymmetry.
 *
 * @param <T> the entity being read
 */
public class PageEntityMapper<T> {

    /** Escape character for {@code LIKE}, so a user-supplied wildcard matches literally. */
    private static final char LIKE_ESCAPE = '\\';

    private final Map<String, FilterableColumn> columns;
    private final Sort defaultSort;
    private final List<String> tieBreakers;

    /**
     * Builds a mapper over an allow-listed column set.
     *
     * <p>All three arguments are given in <strong>wire</strong> names and resolved here, so a name this
     * mapper does not serve fails at construction rather than at query time. The two vocabularies
     * coincide for most columns, which is exactly what makes the one place they diverge a trap worth
     * closing: a caller passing an attribute name that is spelled differently on the wire would build
     * fine and then throw on every request that used the default ordering.
     *
     * @param columns     wire column name to the attribute it reads; anything outside this map is a 400
     * @param defaultSort applied when a request carries no sorts, because offset paging over an
     *                    unordered result set silently duplicates and skips rows between pages
     * @param tieBreakers wire columns appended to every ordering, in order, until it is a total order
     */
    public PageEntityMapper(Map<String, FilterableColumn> columns, Sort defaultSort, List<String> tieBreakers) {
        this.columns = Map.copyOf(columns);
        this.tieBreakers = tieBreakers.stream().map(name -> column(name).attribute()).toList();
        final List<Sort.Order> defaults = new ArrayList<>();
        defaultSort.forEach(order -> defaults.add(order.isDescending()
                ? Sort.Order.desc(column(order.getProperty()).attribute())
                : Sort.Order.asc(column(order.getProperty()).attribute())));
        // Completed exactly as a request's own ordering is, rather than by a second rule that happens to
        // agree today: same direction, same skip-if-already-present. Otherwise a caller who sends the
        // default ordering explicitly would get different rows on a page than one who omits it — the two
        // things this class calls the same ordering.
        this.defaultSort = Sort.by(withTieBreakers(defaults));
    }

    /** Appends the tie-breakers not already present, following the direction of the last term. */
    private List<Sort.Order> withTieBreakers(List<Sort.Order> orders) {
        final boolean descending = orders.isEmpty() || orders.getLast().isDescending();
        for (final String attribute : tieBreakers) {
            if (orders.stream().noneMatch(order -> order.getProperty().equals(attribute))) {
                orders.add(descending ? Sort.Order.desc(attribute) : Sort.Order.asc(attribute));
            }
        }
        return orders;
    }

    /**
     * The page and ordering to read, with this mapper's default ordering when none was requested.
     *
     * <p>Tie-breakers are always appended, because "sorted" and "totally ordered" are not the same thing
     * and only the second makes offset paging safe. Sorting by a timestamp is the case that bites: every
     * activity of one revision copies that revision's timestamp, so a schema define over thirty columns
     * writes thirty-one rows sharing a single millisecond. The database may return tied rows in any
     * order it likes, and it need not pick the same order twice — so page 2 can repeat a row from page 1
     * and skip another entirely. Appending unique terms removes the ties, which is what the caller was
     * promised by there being a default sort at all.
     */
    public PageRequest toPageRequest(PageRequestDto request) {
        final List<Sort.Order> orders = new ArrayList<>();
        for (final SortDto sort : request.sortsOrEmpty()) {
            final FilterableColumn column = column(sort.column());
            orders.add(sort.directionOrDefault() == SortDirectionDto.DESC
                    ? Sort.Order.desc(column.attribute())
                    : Sort.Order.asc(column.attribute()));
        }
        if (orders.isEmpty()) {
            return PageRequest.of(request.pageNumberOrDefault(), request.pageSizeOrDefault(), defaultSort);
        }
        // Each tie-breaker follows the direction of the least significant term the caller gave. A
        // tie-breaker only ever orders rows the caller's own terms tied on, so a fixed DESC would quietly
        // reverse them inside each tie — asking for oldest-first and getting each revision's activities
        // newest-first is a contradiction the caller never expressed.
        return PageRequest.of(
                request.pageNumberOrDefault(), request.pageSizeOrDefault(), Sort.by(withTieBreakers(orders)));
    }

    /** The conjunction of a request's filters; an empty list matches everything. */
    public Specification<T> toSpecification(List<FilterDto> filters) {
        return (root, query, builder) -> {
            final List<Predicate> predicates = new ArrayList<>();
            for (final FilterDto filter : filters) {
                predicates.add(toPredicate(filter, root, builder));
            }
            return predicates.isEmpty() ? builder.conjunction() : builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Predicate toPredicate(FilterDto filter, Root<T> root, CriteriaBuilder builder) {
        final FilterableColumn column = column(filter.column());
        if (filter.operator() == null) {
            throw new InvalidFilterValueException("filter on column '" + filter.column() + "' requires an operator");
        }
        return switch (filter.operator()) {
            case eq -> builder.equal(root.get(column.attribute()), column.coerce(filter.column(), filter.value()));
            case ne -> negated(builder, root, column,
                    builder.notEqual(root.get(column.attribute()), column.coerce(filter.column(), filter.value())));
            case co -> contains(filter, root, builder, column);
            case nc -> negated(builder, root, column, builder.not(contains(filter, root, builder, column)));
            case in -> in(filter, root, column);
            case lt, gt, le, ge -> compare(filter, root, builder, column);
        };
    }

    /**
     * Widens a negating comparison to include rows where the column is NULL.
     *
     * <p>SQL's three-valued logic makes {@code NULL <> 'x'} neither true nor false, so a plain
     * {@code ne} silently drops every row with no value — which reads as a filter doing something other
     * than what it says. The concrete case here is {@code parentActivityId}: filtering it {@code ne}
     * some id would omit every top-level activity, i.e. exactly the rows most obviously "not equal" to
     * it, and with {@code isnull} absent from the operator set there would be no other way to reach
     * them. "Not equal to x" is taken to mean what a reader thinks it means.
     */
    private Predicate negated(
            CriteriaBuilder builder, Root<T> root, FilterableColumn column, Predicate negation) {
        return builder.or(negation, builder.isNull(root.get(column.attribute())));
    }

    /**
     * Case-insensitive containment with the value's own wildcards neutralised, so a {@code %} a caller
     * typed matches a literal percent sign instead of everything.
     */
    private Predicate contains(FilterDto filter, Root<T> root, CriteriaBuilder builder, FilterableColumn column) {
        if (!column.isTextual()) {
            throw new InvalidFilterValueException(
                    "operator '" + filter.operator() + "' is not applicable to column '" + filter.column() + "'");
        }
        final String value = (String) column.coerce(filter.column(), filter.value());
        if (value.isBlank()) {
            // A blank pattern would build LIKE '%%', so `co` would return the entire feed and `nc`
            // nothing — a filter that reads as a restriction and acts as its opposite. `in` already
            // refuses a value with no usable members; this is the same refusal.
            throw new InvalidFilterValueException(
                    "filter on column '" + filter.column() + "' requires a non-blank value");
        }
        // Locale.ROOT, not the JVM default: the database lowercases with its own collation, and a JVM
        // running under a Turkish locale would fold "I" to a dotless "ı" that no database-lowercased "i"
        // can match — a filter that silently returns nothing, on a host setting nobody connects to it.
        return builder.like(
                builder.lower(root.get(column.attribute())),
                "%" + escapeLikeWildcards(value.toLowerCase(Locale.ROOT)) + "%",
                LIKE_ESCAPE);
    }

    private Predicate in(FilterDto filter, Root<T> root, FilterableColumn column) {
        final String raw = filter.value();
        if (raw == null) {
            throw new InvalidFilterValueException("filter on column '" + filter.column() + "' requires a value");
        }
        final List<Object> values = Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(part -> (Object) column.coerce(filter.column(), part))
                .toList();
        if (values.isEmpty()) {
            throw new InvalidFilterValueException(
                    "filter on column '" + filter.column() + "' requires at least one value");
        }
        return root.get(column.attribute()).in(values);
    }

    @SuppressWarnings("unchecked")
    private Predicate compare(FilterDto filter, Root<T> root, CriteriaBuilder builder, FilterableColumn column) {
        if (!column.isOrdered()) {
            throw new InvalidFilterValueException(
                    "operator '" + filter.operator() + "' is not applicable to column '" + filter.column() + "'");
        }
        final Expression<Comparable<Object>> path = root.get(column.attribute());
        final Comparable<Object> value = (Comparable<Object>) column.coerce(filter.column(), filter.value());
        return switch (filter.operator()) {
            case lt -> builder.lessThan(path, value);
            case gt -> builder.greaterThan(path, value);
            case le -> builder.lessThanOrEqualTo(path, value);
            default -> builder.greaterThanOrEqualTo(path, value);
        };
    }

    private FilterableColumn column(String name) {
        final FilterableColumn column = name == null ? null : columns.get(name);
        if (column == null) {
            throw new InvalidFilterColumnException("'" + name + "' is not a filterable or sortable column; "
                    + "supported columns are " + columns.keySet().stream().sorted().toList());
        }
        return column;
    }

    /** Escapes the LIKE metacharacters, and the escape character itself first so it is not doubled twice. */
    private static String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
