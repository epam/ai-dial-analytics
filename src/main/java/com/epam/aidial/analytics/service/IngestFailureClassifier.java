package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.exception.CauseChain;

import java.io.EOFException;
import java.util.zip.ZipException;

/**
 * Classifies a blob-level ingest failure into a {@link IngestFailureReason}. Deterministic only where repeating the
 * attempt cannot succeed: an invalid gzip stream ({@link ZipException}: a bad header, CRC or deflate block) and a
 * mapping fault ({@link RecordMappingException}). An early end of the gzip stream ({@link EOFException}) is not a
 * {@code ZipException} and stays transient, because a read cut short looks the same as a truncated blob.
 */
final class IngestFailureClassifier {

    private IngestFailureClassifier() {
    }

    /** The reason a blob-level failure is recorded under. */
    static IngestFailureReason classifyBlob(Throwable error) {
        if (CauseChain.anyMatch(error, cause -> cause instanceof RecordMappingException)) {
            return IngestFailureReason.MAPPING_ERROR;
        }
        if (CauseChain.anyMatch(error, cause -> cause instanceof ZipException)) {
            return IngestFailureReason.CORRUPT_GZIP;
        }
        return IngestFailureReason.READ_ERROR;
    }

    /**
     * A short description of the failure for the ledger: each exception in the chain by type, with its message
     * except under a mapping fault, whose causes may quote the record being mapped.
     */
    static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        boolean quoteMessages = true;
        int depth = 0;
        for (Throwable cause = error; cause != null && depth < 8; cause = cause.getCause(), depth++) {
            if (!text.isEmpty()) {
                text.append(" <- ");
            }
            text.append(cause.getClass().getSimpleName());
            if (quoteMessages && cause.getMessage() != null) {
                text.append(": ").append(cause.getMessage());
            }
            if (cause instanceof RecordMappingException) {
                quoteMessages = false;
            }
        }
        return text.toString();
    }
}
