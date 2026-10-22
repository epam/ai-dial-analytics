package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How one statement addresses each {@code object}-typed column it references: either through a known set of
 * literal JSON paths, or as the whole document.
 *
 * <p>This is what lets a keyed grain join project one subcolumn per addressed path instead of the whole
 * native {@code JSON} column. The distinction is not a size optimization: ClickHouse stores a {@code JSON}
 * column as <b>one stream per path per part</b>, so reading the column whole opens paths x parts read
 * buffers regardless of how few bytes the values hold. A column such as
 * {@code dial_usage_log_payload.request_tags} can hold hundreds of paths across hundreds of parts, and
 * reading it whole can exhaust a 2 GiB ceiling on the buffers alone.
 *
 * <p><b>The empty path set means the whole column</b>, deliberately, rather than a separate flag or a null:
 * "no prunable path" and "read it whole" are the same statement about the same column, and a representation
 * that could hold both independently would admit a state — pruned <em>and</em> whole — that has no meaning.
 * A column this map does not mention is also read whole, which is what makes threading the map optional: a
 * caller that computes none gets {@link #none()} and renders exactly what it rendered before.
 *
 * <p><b>Whole wins, and per column.</b> One unprunable reference to a column ({@code json_extract_string_ci},
 * or the column used as a value in its own right) makes that column whole no matter how many literal-path
 * reads accompany it — the unprunable form needs the serialized document and cannot be served from
 * subcolumns. It does <em>not</em> make any other column whole: a statement mixing a
 * literal-path read of one {@code object} column with a case-insensitive read of another prunes the first.
 * Widening the fallback to the whole statement would be simpler and would silently lose the pruning in the
 * mixed case, which is the common one as declarations grow.
 */
public final class ObjectPathUsage {

    private static final ObjectPathUsage NONE = new ObjectPathUsage(Map.of());

    /** Exposed column name to the literal paths it is addressed by; an empty set means the whole column. */
    private final Map<String, Set<List<String>>> byColumn;

    private ObjectPathUsage(Map<String, Set<List<String>>> byColumn) {
        this.byColumn = byColumn;
    }

    /**
     * The usage of a statement that computed none — every column reads whole, which is what every caller
     * rendered before this map existed.
     */
    public static ObjectPathUsage none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The literal paths {@code column} is addressed by, or an <b>empty set</b> if it is read whole (either
     * because some reference needs the whole document, or because this statement never mentioned it).
     */
    public Set<List<String>> prunedPaths(String column) {
        return byColumn.getOrDefault(column, Set.of());
    }

    /** Whether {@code column} can be projected as subcolumns rather than whole. */
    public boolean isPruned(String column) {
        return !prunedPaths(column).isEmpty();
    }

    /** Accumulates references as the expression walk finds them; whole always wins over paths. */
    public static final class Builder {

        private final Map<String, Set<List<String>>> byColumn = new LinkedHashMap<>();

        /**
         * Records that {@code column} is addressed at {@code path}. Ignored once the column is whole — the
         * walk order must not decide the outcome, so the unprunable reference wins whenever it is seen.
         */
        public Builder addPath(String column, List<String> path) {
            // An empty path addresses the column itself, which is the whole document by another spelling.
            if (path.isEmpty()) {
                return markWhole(column);
            }
            if (isWhole(column)) {
                return this;
            }
            byColumn.computeIfAbsent(column, key -> new LinkedHashSet<>()).add(List.copyOf(path));
            return this;
        }

        /** Records that {@code column} needs the whole document, discarding any paths already collected. */
        public Builder markWhole(String column) {
            byColumn.put(column, Set.of());
            return this;
        }

        /**
         * Records that each of {@code names} that is an {@code object} column needs the whole document.
         *
         * <p>For the names a statement <b>projects</b> rather than reads through an expression. A projected
         * {@code object} column is returned to the caller as a document, so no path within it is the thing
         * being asked for — and a projection the usage did not account for is precisely the way a pruned
         * join comes to omit a column the statement still selects.
         */
        public Builder markWholeObjects(
                Collection<String> names, Map<String, QueryFieldBinding> bindings) {
            for (final String name : names) {
                final QueryFieldBinding binding = bindings.get(name);
                if (binding != null && binding.type() == QueryFieldType.OBJECT) {
                    markWhole(name);
                }
            }
            return this;
        }

        private boolean isWhole(String column) {
            final Set<List<String>> paths = byColumn.get(column);
            return paths != null && paths.isEmpty();
        }

        public ObjectPathUsage build() {
            final Map<String, Set<List<String>>> copy = new LinkedHashMap<>();
            for (final Map.Entry<String, Set<List<String>>> entry : byColumn.entrySet()) {
                copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return copy.isEmpty() ? NONE : new ObjectPathUsage(Collections.unmodifiableMap(copy));
        }
    }

    /**
     * The derived-table alias a pruned path is projected under: the column's own name and the path joined
     * by dots, as <b>one</b> identifier ({@code request_tags.baggage}).
     *
     * <p>A single identifier rather than nested name segments because of what the outer statement renders.
     * Unpruned, a path read addresses {@code "enrichment"."request_tags"."baggage"} — three parts, the last
     * two being JSON subcolumn access into a column of the derived table. Once the derived table projects
     * that subcolumn, {@code "request_tags"} is no longer one of its columns and the three-part reference
     * has nothing to resolve against. Aliasing to one name keeps the reference two-part and unambiguous,
     * needs no mangling, and stays readable in a logged statement; jOOQ quotes it whole, so the dot is
     * inert rather than a name separator.
     */
    public static String alias(String columnName, List<String> path) {
        final List<String> parts = new ArrayList<>(path.size() + 1);
        parts.add(columnName);
        parts.addAll(path);
        return String.join(".", parts);
    }

    /**
     * Native JSON subcolumn access: {@code field}'s qualified name with {@code path} appended as further
     * identifier segments ({@code "enrichment"."request_tags"."baggage"}).
     *
     * <p>Both sides of the pruning read this: the derived table projects it from the enrichment's real
     * column, and an unpruned statement addresses it directly. Shared so the two spell the same access the
     * same way — the derived table projecting one form while the outer statement reads another is the
     * failure this whole seam exists to make unrepresentable.
     */
    public static Field<?> subcolumn(Field<?> field, List<String> path) {
        final String[] column = field.getQualifiedName().getName();
        final String[] parts = Arrays.copyOf(column, column.length + path.size());
        for (int i = 0; i < path.size(); i++) {
            parts[column.length + i] = path.get(i);
        }
        return DSL.field(DSL.name(parts));
    }
}
