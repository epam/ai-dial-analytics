package com.epam.aidial.analytics.exception;

/**
 * A caller confined by a row policy on a table asked a pipeline to read that table on its behalf — to arm,
 * preview or refresh it, or to scan, assemble or materialize through its data plane. Pipeline reads run
 * under pipeline settings that carry no row-policy filter, so serving the request would hand the caller rows
 * its policy withholds. Mapped to HTTP 403 with the stable code {@code row_policy_confined}; the message
 * names the table, which the caller's own pipeline declaration already names.
 */
public class RowPolicyConfinedException extends RuntimeException {
    public RowPolicyConfinedException(String message) {
        super(message);
    }
}
