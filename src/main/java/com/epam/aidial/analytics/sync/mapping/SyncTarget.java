package com.epam.aidial.analytics.sync.mapping;

/**
 * One data-sync ingest target: a ClickHouse table paired with the {@link MappingConfig} that shapes its
 * rows and the {@link MappingEngine} compiled from that mapping. During a run each parsed record is
 * offered to every target; a target emits a row (via its {@link MappingEngine}) only when its mapping's
 * {@link RecordMatcher} matches the record, so a single record MAY produce rows in more than one target
 * table. The default configuration has a single target ({@code dial_usage_log}, match-all), preserving
 * the original single-table behavior.
 *
 * @param table  the ClickHouse table this target inserts into
 * @param mapping the compiled column mapping and emit predicate for this target
 * @param engine  the mapping engine that turns a record into this target's row
 */
public record SyncTarget(String table, MappingConfig mapping, MappingEngine engine) {

    /** The emit predicate for this target (match-all when the mapping declares no {@code match:} block). */
    public RecordMatcher matcher() {
        return mapping.matcher();
    }
}
