package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.Identifiers;

/**
 * The single derivation of a sink's landing-table names: {@code otel_<sink>_<signal>}. Every caller —
 * provisioning, deletion, the ingest lookup and the ingest authorization check — goes through
 * here, so the convention lives in one place instead of being spelled out in a SpEL string and three
 * services.
 *
 * <p>The sink-name bound is the reason this class validates rather than merely concatenates: a catalog
 * identifier is capped at 64 characters, and the longest suffix is {@code _metrics} (8) on top of the
 * {@code otel_} prefix (5), so a sink name longer than 51 would produce a table name the catalog
 * rejects — <em>after</em> the shorter signals had already been created. Refusing up front keeps the
 * failure at the point of naming.
 */
public final class OtelTableNames {

    /** 64 (the catalog identifier limit) minus {@code otel_} (5) minus {@code _metrics} (8). */
    public static final int MAX_SINK_NAME_LENGTH = 51;

    private static final String PREFIX = "otel_";

    private OtelTableNames() {
    }

    /**
     * Validates a sink name against the catalog identifier grammar and the derived-name bound, returning
     * it on success.
     *
     * @throws TableValidationException if the name is not snake_case, is reserved, or is too long
     */
    public static String requireValidSinkName(String name) {
        Identifiers.requireUserIdentifier(name);
        if (name.length() > MAX_SINK_NAME_LENGTH) {
            throw new TableValidationException("sink name '" + name + "' is too long: at most "
                    + MAX_SINK_NAME_LENGTH + " characters, so that the derived table name '"
                    + PREFIX + name + "_metrics' stays within the 64-character catalog identifier limit");
        }
        return name;
    }

    /** The catalog table backing {@code signal} on {@code sinkName}. */
    public static String tableName(String sinkName, OtelSignal signal) {
        return PREFIX + sinkName + "_" + signal.code();
    }

    /** The sink's OTLP base path — what an OTLP/HTTP exporter is configured with. */
    public static String endpoint(String sinkName) {
        return "/v1/otel/sink/" + sinkName;
    }
}
