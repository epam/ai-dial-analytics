package com.epam.aidial.analytics.clickhouse.replication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The tables a pipeline run reads, in the order its home must synchronize them: the input sources its cursor or
 * dirty keys range over, then the enrichments it joins, then its own target when it reads it. The order is what makes
 * a source row visible at the source sync have its enrichment rows waited for by the later enrichment sync, because
 * ingest commits each payload part before the source part it belongs to.
 */
public record SyncPlan(List<String> tables) {

    public SyncPlan {
        tables = List.copyOf(tables);
    }

    /** {@code sources}, then {@code enrichments}, then {@code target} when not null; each table once, first wins. */
    public static SyncPlan of(Collection<String> sources, Collection<String> enrichments, String target) {
        final Set<String> ordered = new LinkedHashSet<>();
        add(ordered, sources);
        add(ordered, enrichments);
        if (target != null && !target.isBlank()) {
            ordered.add(target);
        }
        return new SyncPlan(new ArrayList<>(ordered));
    }

    private static void add(Set<String> into, Collection<String> tables) {
        if (tables != null) {
            tables.stream().filter(table -> table != null && !table.isBlank()).forEach(into::add);
        }
    }
}
