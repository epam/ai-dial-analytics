package com.epam.aidial.analytics.service;

import com.clickhouse.client.api.ServerException;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.ZipException;

import static org.assertj.core.api.Assertions.assertThat;

class IngestFailureClassifierTest {

    @Test
    void anInvalidGzipStreamIsDeterministic() {
        IngestFailureReason reason = IngestFailureClassifier.classifyBlob(new UncheckedIOException(new ZipException("Not in GZIP format")));

        assertThat(reason).isEqualTo(IngestFailureReason.CORRUPT_GZIP);
        assertThat(reason.deterministic()).isTrue();
    }

    @Test
    void mappingFaultIsDeterministic() {
        IngestFailureReason reason = IngestFailureClassifier.classifyBlob(
                new RecordMappingException("dial_usage_log", new IllegalArgumentException("bad")));

        assertThat(reason).isEqualTo(IngestFailureReason.MAPPING_ERROR);
        assertThat(reason.failureClass()).isEqualTo("deterministic");
    }

    @Test
    void anEarlyEndOfTheGzipStreamIsTransient() {
        IngestFailureReason reason = IngestFailureClassifier.classifyBlob(new EOFException("Unexpected end of ZLIB input stream"));

        assertThat(reason).isEqualTo(IngestFailureReason.READ_ERROR);
        assertThat(reason.failureClass()).isEqualTo("transient");
    }

    @Test
    void anyOtherBlobFailureIsTransient() {
        assertThat(IngestFailureClassifier.classifyBlob(new IOException("Blob not found: x"))).isEqualTo(IngestFailureReason.READ_ERROR);
        assertThat(IngestFailureClassifier.classifyBlob(new IllegalStateException("store hiccup"))).isEqualTo(IngestFailureReason.READ_ERROR);
    }

    @Test
    void dayReasonsAreTransient() {
        assertThat(IngestFailureReason.LISTING_FAILED.deterministic()).isFalse();
        assertThat(IngestFailureReason.PREPARE_FAILED.deterministic()).isFalse();
        assertThat(IngestFailureReason.WRITE_FAILED.deterministic()).isFalse();
        assertThat(IngestFailureReason.STORAGE_FULL.deterministic()).isFalse();
    }

    @Test
    void theDescriptionNeverQuotesWhatMappingFaultCarries() {
        String description = IngestFailureClassifier.describe(
                new RecordMappingException("dial_usage_log", new IllegalArgumentException("value 'secret prompt' is not a number")));

        assertThat(description).contains("RecordMappingException", "IllegalArgumentException").doesNotContain("secret prompt");
    }

    @Test
    void theDescriptionQuotesReadFaults() {
        assertThat(IngestFailureClassifier.describe(new ZipException("Not in GZIP format"))).isEqualTo("ZipException: Not in GZIP format");
    }

    @Test
    void theDescriptionNeverQuotesClickHouseInsertErrorMessages() {
        ServerException server = new ServerException(376, "Cannot parse uuid 'secret prompt': while executing 'insert'", 500, "q");
        String description = IngestFailureClassifier.describe(
                new RowBatchSink.FlushException(new IllegalStateException("ClickHouse insert failed", server)));

        assertThat(description)
                .isEqualTo("FlushException: Failed to insert a row batch <- IllegalStateException: ClickHouse insert failed <- ServerException: code 376")
                .doesNotContain("secret prompt");
    }
}
