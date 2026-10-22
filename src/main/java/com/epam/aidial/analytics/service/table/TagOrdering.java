package com.epam.aidial.analytics.service.table;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The single source of truth for how a per-table tag ordering (see {@code table_tag_order} /
 * {@link TableDefinitionEntity#getTagOrder()}) sorts a table's columns or flattened query fields.
 * Both read surfaces — {@code GET /v1/tables/{name}} and {@code GET /v1/queries/entities/schema/{name}}
 * — sort through this one comparator so they can never drift.
 *
 * <p>The total order groups items into three tiers:
 * <ol>
 *   <li>items whose tag appears in the ordering, by the tag's index in the list;</li>
 *   <li>items whose tag is not listed, grouped by tag ordered alphabetically;</li>
 *   <li>untagged items (null tag), last.</li>
 * </ol>
 * Within any group, items are ordered alphabetically by name. The sort is presentation-only — callers
 * apply it only when an ordering is set and never mutate the underlying column order.
 */
public final class TagOrdering {

    private TagOrdering() {
    }

    /**
     * Builds a comparator implementing the tag-group ordering for a non-empty {@code tagOrder}. The
     * accessors extract each item's tag (nullable) and name (non-null, unique per surface, so the
     * order is total and stable).
     */
    public static <T> Comparator<T> comparator(
            List<String> tagOrder, Function<T, String> tagOf, Function<T, String> nameOf) {
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < tagOrder.size(); i++) {
            index.putIfAbsent(tagOrder.get(i), i);
        }
        return Comparator
                .<T>comparingInt(item -> tier(tagOf.apply(item), index))
                .thenComparingInt(item -> {
                    final Integer i = index.get(tagOf.apply(item));
                    return i == null ? 0 : i;
                })
                .thenComparing(item -> unlistedTag(tagOf.apply(item), index))
                .thenComparing(item -> nameOrEmpty(nameOf.apply(item)));
    }

    /** Tier: listed tag -> 0, unlisted (non-null) tag -> 1, untagged (null) -> 2. */
    private static int tier(String tag, Map<String, Integer> index) {
        if (tag == null) {
            return 2;
        }
        return index.containsKey(tag) ? 0 : 1;
    }

    /** The alphabetical key for the unlisted tier; inert ("") for listed and untagged items. */
    private static String unlistedTag(String tag, Map<String, Integer> index) {
        return tag != null && !index.containsKey(tag) ? tag : "";
    }

    private static String nameOrEmpty(String name) {
        return name == null ? "" : name;
    }
}
