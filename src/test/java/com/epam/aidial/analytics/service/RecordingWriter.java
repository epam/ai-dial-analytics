package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.clickhouse.TestClickHouseSpans;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link UsageLogWriter} test double: records every inserted row (no deduplication — that is
 * the ClickHouse {@code ReplacingMergeTree} engine's job at runtime). Shared by the scheduled
 * data-sync and near-real-time polling tests. {@link #rows} aggregates rows across all target tables
 * (the single-target tests only insert into {@code dial_usage_log}); {@link #rowsFor(String)} returns
 * the rows inserted into one table, for multi-target assertions.
 */
class RecordingWriter extends UsageLogWriter {

    final List<Map<String, Object>> rows = new ArrayList<>();
    final Map<String, List<Map<String, Object>>> rowsByTable = new LinkedHashMap<>();
    int insertCalls;
    /** The 1-based insert call that fails (stands in for ClickHouse refusing a write), or 0 for none. */
    int failOnInsertCall;

    RecordingWriter() {
        super(null, null, null, TestClickHouseSpans.noop(), new SimpleMeterRegistry());
    }

    @Override
    public void insert(String table, List<Map<String, Object>> batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (insertCalls + 1 == failOnInsertCall) {
            throw new IllegalStateException("ClickHouse insert failed");
        }
        insertCalls++;
        rows.addAll(batch);
        rowsByTable.computeIfAbsent(table, key -> new ArrayList<>()).addAll(batch);
    }

    /** Rows inserted into the given target table (empty when none). */
    List<Map<String, Object>> rowsFor(String table) {
        return rowsByTable.getOrDefault(table, List.of());
    }
}
