package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.configuration.OtelSinkProperties;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.otel.otlp.OtlpFlattener;
import com.epam.aidial.analytics.service.table.TableDataService;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Data-plane half of the OTLP sink capability: turns one OTLP/HTTP JSON export into landing-table rows
 * and writes them.
 *
 * <p>The write goes through {@link TableDataService#writeAll}, the documented row-write choke point and the
 * only caller of the ClickHouse row writer — so an OTLP export inherits the {@code ACTIVE} check, the
 * unknown-column check, the value checks and the writer credential, and cannot drift from what
 * {@code POST /v1/tables/{name}/rows} accepts.
 *
 * <p><b>Rows are written in chunks</b> of {@code tables.limits.max-write-batch-rows} rather than as one
 * batch. A normally-configured collector batches far more data points than that cap, so letting the cap
 * reject the payload would make the endpoint useless; the whole payload is instead bounded up front by
 * {@code otel-sink.max-rows-per-request}, which is what stops one export from being expanded into
 * unbounded heap.
 *
 * <p>Every row is validated before the first chunk is inserted, so a rejected value writes nothing. The
 * consequence that remains, stated rather than hidden: a chunked write is <b>not atomic</b>. A ClickHouse
 * failure part-way through leaves the earlier chunks written, and the exporter — which is told the export
 * failed — retries the whole payload. Duplicate-on-retry is the normal failure mode of every OTLP
 * backend, because OTLP is an at-least-once protocol with no request identity to deduplicate on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtelIngestService {

    private final OtelSinkService sinks;
    private final OtelSinkAccess sinkAccess;
    private final OtlpFlattener flattener;
    private final TableDataService dataService;
    private final TableDefinitionRepository definitions;
    private final OtelSinkProperties properties;

    /**
     * Flattens {@code payload} into {@code signal}'s landing table for {@code sinkName} and writes it.
     *
     * @return the number of rows written (0 for an export carrying no records)
     * @throws com.epam.aidial.analytics.exception.OtelSinkNotFoundException if the sink or signal is unknown (404)
     * @throws TableConflictException   if the backing table is missing from the catalog or not ACTIVE (409)
     * @throws TableValidationException if the payload flattens to more rows than the per-request cap (422)
     */
    public int ingest(String sinkName, OtelSignal signal, JsonNode payload) {
        // Before anything is looked up: the endpoint declares TABLE_WRITE deferred because the sink name in
        // the path is not the table name, so the per-table decision belongs here, after the derivation.
        sinkAccess.requireIngest(sinkName, signal);
        sinks.requireEnabled();
        final String table = sinks.tableFor(sinkName, signal);
        requireWritable(sinkName, signal, table);

        final List<Map<String, Object>> rows = flattener.flatten(signal, payload, Instant.now());
        if (rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > properties.maxRowsPerRequest()) {
            throw new TableValidationException("this export flattens to " + rows.size()
                    + " rows, which exceeds the per-request maximum of " + properties.maxRowsPerRequest()
                    + "; configure a smaller batch on the exporter");
        }
        // writeAll, not write per chunk: every row is validated before the first chunk is inserted, so a
        // rejected value fails the export whole instead of after earlier chunks already landed.
        dataService.writeAll(table, rows);
        log.debug("Ingested {} {} rows into {}", rows.size(), signal.code(), table);
        return rows.size();
    }

    /**
     * Refuses an export whose landing table is gone or not yet materialized. The sink's tables are
     * ordinary catalog tables, so an admin can delete one through {@code /v1/tables}; answering 409 names
     * that state instead of letting the write fail as an opaque unknown-table error.
     */
    private void requireWritable(String sinkName, OtelSignal signal, String table) {
        final TableStatus status = definitions.findById(table)
                .map(TableDefinitionEntity::getStatus)
                .orElse(null);
        if (status == null) {
            throw new TableConflictException("the table backing the '" + signal.code() + "' signal of sink '"
                    + sinkName + "' ('" + table + "') is no longer in the catalog; recreate the sink");
        }
        if (status != TableStatus.ACTIVE) {
            throw new TableConflictException("the table backing the '" + signal.code() + "' signal of sink '"
                    + sinkName + "' ('" + table + "') is " + status + ", not ACTIVE; it has no usable"
                    + " ClickHouse table");
        }
    }
}
