package com.epam.aidial.analytics.exception;

/**
 * Raised when a write reaches an enrichment whose automatic relayout is holding its writers for the short window
 * between the final catch-up copy and the catalog update. Transient by construction: the hold is released within
 * minutes, and a caller that retries from where it was — the runner does, from its committed cursor — loses
 * nothing. Mapped by {@code DefaultExceptionHandler} to {@code 503 table_relayout_in_progress}.
 */
public class TableRelayoutInProgressException extends RuntimeException {

    public TableRelayoutInProgressException(String message) {
        super(message);
    }
}
