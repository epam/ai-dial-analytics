package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Per-sink authorization for the OTLP ingest endpoints. The endpoints declare
 * {@link Privilege#TABLE_WRITE} as deferred, and this is where it is decided: the sink name in the path is
 * not the table name, so the check has to happen after the {@code otel_<sink>_<signal>} derivation.
 *
 * <p>It answers the question by delegating to the table the export would write —
 * {@link TableAccessEvaluator#canWrite} on {@code otel_<sink>_<signal>} — so exporting through a sink is
 * governed by exactly the same {@code write} grant as {@code POST /v1/tables/{name}/rows}. That is what
 * lets an exporter be a machine identity entitled to one sink's tables and nothing else, without a second
 * permission model to keep in step. {@code TABLE_WRITE} confers no read, so such an exporter cannot
 * enumerate the catalog or read back what it wrote.
 *
 * <p>The name derivation lives here rather than at the endpoint so the {@code otel_<sink>_<signal>}
 * convention stays in {@link OtelTableNames} alone.
 *
 * <p>Unlike an endpoint gate, this runs on every request — so it must decide {@code none} mode itself
 * rather than relying on the advisor being absent. {@link TableAccessEvaluator} does not, so the caller
 * that reaches here passes through {@link SecurityModeResolver} first; see
 * {@link #requireIngest(String, OtelSignal)}.
 */
@Component("otelSinkAccess")
@RequiredArgsConstructor
@Slf4j
public class OtelSinkAccess {

    private final TableAccessEvaluator tableAccess;
    private final SecurityModeResolver securityMode;

    /**
     * Whether the caller may export {@code signalCode} telemetry through sink {@code sinkName}.
     *
     * <p>An unparseable sink name or signal is answered {@code false} rather than by throwing: this runs
     * before the controller, so a malformed path must fail closed here and be reported as the 404 the
     * ingest service produces only for callers who got past the gate.
     */
    private boolean canIngest(String sinkName, OtelSignal signal) {
        if (signal == null || sinkName == null) {
            return false;
        }
        try {
            return tableAccess.canWrite(OtelTableNames.tableName(sinkName, signal));
        } catch (RuntimeException invalidName) {
            log.debug("Refusing otel ingest for sink '{}': {}", sinkName, invalidName.getMessage());
            return false;
        }
    }

    /**
     * Asserts the caller may export {@code signal} through {@code sinkName}, before anything is looked up.
     *
     * <p>Refused with {@code 403 forbidden} rather than by reporting the sink absent: the missing privilege
     * is a write, and the non-disclosure rule covers read privileges only. An unparseable sink name is
     * refused here too rather than allowed through to the 404 the ingest service produces, so a malformed
     * path fails closed for a caller that was never entitled to the sink.
     */
    public void requireIngest(String sinkName, OtelSignal signal) {
        if (!securityMode.isEnforced() || canIngest(sinkName, signal)) {
            return;
        }
        throw new AccessDeniedException("caller lacks write access to sink '" + sinkName + "'");
    }
}
