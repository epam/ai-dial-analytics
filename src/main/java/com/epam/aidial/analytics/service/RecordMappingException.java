package com.epam.aidial.analytics.service;

/**
 * A record of a blob could not be mapped: its match predicate, a transform or a deriver raised an error. Raised by
 * {@link BlobIngestor} so that a mapping fault — deterministic for the same blob and configuration — is told apart
 * from a fault reading the blob. The message names the target and the cause's type only, never the record.
 */
public class RecordMappingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RecordMappingException(String table, RuntimeException cause) {
        super("Mapping a record for target '" + table + "' failed: " + cause.getClass().getSimpleName(), cause);
    }
}
