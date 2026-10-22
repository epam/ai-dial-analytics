package com.epam.aidial.analytics.exception;

/**
 * A modifying table-management operation targeted a {@code system} table — one owned/managed by ADAS
 * itself (materialized out of band and, for the seeded sources, fed by the data-sync ingest job). Such
 * tables are protected from modification via the API: update, schema change, delete, and row write are
 * all refused regardless of caller role. Mapped to HTTP 409 with the {@code table_is_system} code.
 */
public class SystemTableException extends RuntimeException {

    public SystemTableException(String message) {
        super(message);
    }
}
