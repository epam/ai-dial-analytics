package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.service.table.TableStatus;

import java.time.Instant;
import java.util.List;

/**
 * The read aggregate for one sink: its identity plus, per enabled signal, the catalog table backing it
 * and that table's current status.
 *
 * @param name        the sink name
 * @param description the sink's description, or null
 * @param createdAt   when the sink was provisioned
 * @param endpoint    the sink's OTLP base path, what an exporter is configured with
 * @param signals     one entry per enabled signal, ordered by signal
 */
public record OtelSinkView(
        String name,
        String description,
        Instant createdAt,
        String endpoint,
        List<SignalView> signals) {

    /**
     * One enabled signal and the state of the table behind it.
     *
     * <p>{@code status} is {@code null} when the backing table is no longer in the catalog. A sink's
     * tables are ordinary, non-{@code system} catalog tables, so a {@code TABLE_DELETE} holder can
     * delete one
     * through {@code /v1/tables}; reporting the absence is how that state is made legible instead of
     * surfacing later as a confusing ingest failure.
     *
     * @param signal the signal this entry lands
     * @param table  the catalog table backing it
     * @param status that table's status, or null when it is no longer in the catalog
     */
    public record SignalView(OtelSignal signal, String table, TableStatus status) {}
}
