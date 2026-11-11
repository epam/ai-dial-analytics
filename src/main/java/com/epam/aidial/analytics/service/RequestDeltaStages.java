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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The request-delta {@link FlushStage}s of the configured targets under {@code sync.request-body-mode=whole-with-path}
 * or {@code delta}. Under {@code whole} there are none, and no body is parsed.
 *
 * <p>A target gets a stage when its mapping maps {@code request_body}, {@code project_id} and {@code user_hash} and its
 * table has the request-delta columns: that is what a payload mapping and table declare (ClickHouse {@code V16}),
 * whatever the operator named the table. The table is checked once, on the stage's first flush: by then the startup
 * migrations have run, which they have not while this bean is built. One encoder per such table, built once at
 * startup, so its path cache outlives a run and the poller, the scheduled sync and a backfill all see each other's
 * writes. Under {@code whole-with-path} the stage only stamps chat requests with their path, so a row
 * stored whole can still be a parent (see {@link RequestDeltaEncoder}). Under {@code delta} with no target qualifying,
 * startup logs a warning: the setting would otherwise do nothing, silently. A target whose {@code request_body} is excluded by
 * {@code sync.sensitive-columns-enabled=false} has no body to rewrite and gets no stage, so its chain and cache-marker
 * columns keep their defaults; when that leaves no stage at all, the warning names the switch rather than the mappings.
 */
@Component
@Slf4j
public class RequestDeltaStages {

    /** Written paths remembered per payload table; a few hundred bytes each. */
    static final int PATH_CACHE_ENTRIES = 200_000;

    private static final Set<String> REQUIRED_COLUMNS = Set.of("request_body", "project_id", "user_hash");

    /** The columns the stage writes beyond the mapping: what a payload table gains in ClickHouse V16. */
    private static final Set<String> REQUIRED_TABLE_COLUMNS = Set.of(
            "request_hash", "request_parent_hash", "request_ancestor_hashes", "request_cache_control");

    private final Map<String, FlushStage> stages;

    @Autowired
    public RequestDeltaStages(SyncProperties properties, List<SyncTarget> targets, UsageLogWriter writer, MeterRegistry registry) {
        Map<String, FlushStage> byTable = new LinkedHashMap<>();
        RequestBodyMode mode = properties.requestBodyMode();
        if (mode != RequestBodyMode.WHOLE) {
            for (SyncTarget target : targets) {
                Set<String> columns = target.mapping().columns().stream().map(ColumnMapping::column).collect(Collectors.toSet());
                if (columns.containsAll(REQUIRED_COLUMNS)) {
                    RequestDeltaEncoder encoder = new RequestDeltaEncoder(
                            mode, new ClickHouseParentLookup(writer, target.table()), PATH_CACHE_ENTRIES, registry, target.table());
                    byTable.put(target.table(), new CheckedStage(encoder, writer, target.table()));
                }
            }
            if (byTable.isEmpty() && !properties.sensitiveColumnsEnabled()) {
                log.warn("sync.request-body-mode={} has no effect: with sync.sensitive-columns-enabled=false excluding the "
                        + "sensitive columns, no ingest target's mapping maps all of {}", mode.name().toLowerCase(Locale.ROOT).replace('_', '-'), REQUIRED_COLUMNS);
            } else if (byTable.isEmpty() && mode == RequestBodyMode.DELTA) {
                log.warn("sync.request-body-mode=delta has no effect: no ingest target's mapping maps all of {}", REQUIRED_COLUMNS);
            }
        }
        this.stages = Map.copyOf(byTable);
    }

    /**
     * A stage that first makes sure its table physically has the request-delta columns. The mapping alone is not
     * enough: a custom target mapping {@code request_body} onto a table without them, or the payload table before
     * {@code V16} was applied, would have its bodies cut while the insert drops the chain columns, leaving rows that can
     * never be rebuilt. The table is described once, on the first flush, whatever {@code sync.validate-schema} says; a
     * table without the columns gets no rewriting from then on. A failed describe fails that flush like any other
     * ClickHouse error, before any body is cut, and is retried on the next one.
     */
    private static final class CheckedStage implements FlushStage {

        private final RequestDeltaEncoder encoder;
        private final UsageLogWriter writer;
        private final String table;
        private volatile Boolean hasColumns;

        CheckedStage(RequestDeltaEncoder encoder, UsageLogWriter writer, String table) {
            this.encoder = encoder;
            this.writer = writer;
            this.table = table;
        }

        @Override
        public Written prepare(List<Map<String, Object>> rows) {
            if (!hasColumns()) {
                return Written.NONE;
            }
            RequestDeltaEncoder.Encoding encoding = encoder.encode(rows);
            return new Written() {
                @Override
                public void written(Set<Map<String, Object>> notStored) {
                    encoding.written(notStored);
                }

                @Override
                public void isolate(Function<Map<String, Object>, ?> partOf) {
                    encoding.isolate(partOf);
                }
            };
        }

        private boolean hasColumns() {
            Boolean known = hasColumns;
            if (known == null) {
                known = writer.describeTable(table).keySet().containsAll(REQUIRED_TABLE_COLUMNS);
                if (!known) {
                    log.warn("Target {} maps a request body but lacks the request-delta columns {}; its bodies are stored as mapped",
                            table, REQUIRED_TABLE_COLUMNS);
                }
                hasColumns = known;
            }
            return known;
        }
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
