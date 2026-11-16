package com.epam.aidial.analytics.clickhouse.replication;

import java.time.Duration;
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
 *
 * <p>{@code safetyLag}, when set, is the safety lag of the run's window. The table syncs wait only for writes already
 * committed when they start, so the window is sound only while the time from the first table sync to the probe stays
 * well inside the safety lag; the router re-synchronizes when a round took longer than half of it, and refuses the run
 * when it cannot get under that bound. {@code null}, or a zero lag (the window guard switched off), skips the check.
 */
public record SyncPlan(List<String> tables, Duration safetyLag) {

    public SyncPlan {
        tables = List.copyOf(tables);
    }

    public SyncPlan(List<String> tables) {
        this(tables, null);
    }

    /** This plan, for a run whose window applies {@code lag}. */
    public SyncPlan withSafetyLag(Duration lag) {
        return new SyncPlan(tables, lag);
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
