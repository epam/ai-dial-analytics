package com.epam.aidial.analytics.sync.mapping;

import java.util.Set;

/**
 * One data-sync ingest target: a ClickHouse table paired with the {@link MappingConfig} that shapes its
 * rows and the {@link MappingEngine} compiled from that mapping. During a run each parsed record is
 * offered to every target; a target emits a row (via its {@link MappingEngine}) only when its mapping's
 * {@link RecordMatcher} matches the record, so a single record MAY produce rows in more than one target
 * table. The default configuration has a single target ({@code dial_usage_log}, match-all), preserving
 * the original single-table behavior.
 *
 * <p>Under {@code sync.sensitive-columns} other than {@code *} the mapping is the configured one without the columns the
 * catalog flags {@code sensitive} on the table that no entry names (see {@link SensitiveColumnExclusion}): they are never
 * extracted, and the stored row holds their ClickHouse defaults.
 *
 * @param table           the ClickHouse table this target inserts into
 * @param mapping         the compiled column mapping and emit predicate for this target, after sensitive-column exclusion
 * @param engine          the mapping engine that turns a record into this target's row
 * @param excludedColumns the sensitive physical columns left out of this target's rows, mapped or not; a stage that writes
 *                        columns beyond the mapping must not write these
 */
public record SyncTarget(String table, MappingConfig mapping, MappingEngine engine, Set<String> excludedColumns) {

    public SyncTarget {
        excludedColumns = excludedColumns == null ? Set.of() : Set.copyOf(excludedColumns);
    }

    /** A target that leaves no sensitive column out. */
    public SyncTarget(String table, MappingConfig mapping, MappingEngine engine) {
        this(table, mapping, engine, Set.of());
    }

    /** The emit predicate for this target (match-all when the mapping declares no {@code match:} block). */
    public RecordMatcher matcher() {
        return mapping.matcher();
    }
}
