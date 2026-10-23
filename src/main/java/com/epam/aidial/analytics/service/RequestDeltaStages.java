package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.sync.delta.ClickHouseParentLookup;
import com.epam.aidial.analytics.sync.delta.RequestBodyMode;
import com.epam.aidial.analytics.sync.delta.RequestDeltaEncoder;
import com.epam.aidial.analytics.sync.mapping.ColumnMapping;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The request-delta {@link FlushStage}s of the configured targets, under either {@code sync.request-body-mode}.
 *
 * <p>A target gets a stage when its mapping maps both {@code request_body} and {@code request_scope}: that is
 * what a payload mapping declares (ClickHouse {@code V15}), whatever the operator named the table. One encoder per such
 * table, built once at startup, so its path cache outlives a run and the poller, the scheduled sync and a backfill
 * all see each other's writes. Under {@code whole} the stage only stamps chat requests with their path, so a row
 * stored whole can still be a parent (see {@link RequestDeltaEncoder}). Under {@code delta} with no target qualifying,
 * startup logs a warning: the setting would otherwise do nothing, silently.
 */
@Component
@Slf4j
public class RequestDeltaStages {

    /** Written paths remembered per payload table; a few hundred bytes each. */
    static final int PATH_CACHE_ENTRIES = 200_000;

    private static final Set<String> REQUIRED_COLUMNS = Set.of("request_body", "request_scope");

    private final Map<String, FlushStage> stages;

    @Autowired
    public RequestDeltaStages(SyncProperties properties, List<SyncTarget> targets, UsageLogWriter writer, MeterRegistry registry) {
        Map<String, FlushStage> byTable = new LinkedHashMap<>();
        RequestBodyMode mode = properties.requestBodyMode();
        for (SyncTarget target : targets) {
            Set<String> columns = target.mapping().columns().stream().map(ColumnMapping::column).collect(Collectors.toSet());
            if (columns.containsAll(REQUIRED_COLUMNS)) {
                RequestDeltaEncoder encoder = new RequestDeltaEncoder(
                        mode, new ClickHouseParentLookup(writer, target.table()), PATH_CACHE_ENTRIES, registry, target.table());
                byTable.put(target.table(), rows -> encoder.encode(rows)::written);
            }
        }
        if (mode == RequestBodyMode.DELTA && byTable.isEmpty()) {
            log.warn("sync.request-body-mode=delta has no effect: no ingest target's mapping maps both {}", REQUIRED_COLUMNS);
        }
        this.stages = Map.copyOf(byTable);
    }

    private RequestDeltaStages(Map<String, FlushStage> stages) {
        this.stages = stages;
    }

    /** No stages: every body is stored whole. */
    static RequestDeltaStages none() {
        return new RequestDeltaStages(Map.of());
    }

    /** Target table → its stage, for the tables that have one. */
    Map<String, FlushStage> byTable() {
        return stages;
    }
}
