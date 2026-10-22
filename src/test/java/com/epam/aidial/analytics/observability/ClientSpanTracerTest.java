package com.epam.aidial.analytics.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link ClientSpanTracer} produces a {@link SpanKind#CLIENT CLIENT}-kind span with the
 * supplied name/attributes on success, and marks the span {@link StatusCode#ERROR} with the recorded
 * exception (rethrown unchanged) on failure — the behavior the tracing review requires for outbound
 * ClickHouse/blob I/O.
 */
class ClientSpanTracerTest {

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk sdk;
    private ClientSpanTracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
        tracer = new ClientSpanTracer(sdk);
    }

    @AfterEach
    void tearDown() {
        sdk.close();
    }

    @Test
    void recordsClientSpanOnSuccess() {
        Attributes attributes = ClientSpanTracer.clickHouseAttributes("query", "ch-host", 8123, "analytics");

        String result = tracer.clientSpan("clickhouse query", attributes, () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("clickhouse query");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(span.getAttributes().get(ClientSpanTracer.DB_SYSTEM)).isEqualTo("clickhouse");
        assertThat(span.getAttributes().get(ClientSpanTracer.DB_OPERATION)).isEqualTo("query");
        assertThat(span.getAttributes().get(ClientSpanTracer.DB_NAMESPACE)).isEqualTo("analytics");
        assertThat(span.getAttributes().get(ClientSpanTracer.SERVER_ADDRESS)).isEqualTo("ch-host");
        assertThat(span.getAttributes().get(ClientSpanTracer.SERVER_PORT)).isEqualTo(8123L);
    }

    @Test
    void blobAttributesCarryPeerServiceAndServerAddress() {
        Attributes withEndpoint = ClientSpanTracer.blobAttributes("azureblob", "acct.blob.core.windows.net");
        assertThat(withEndpoint.get(ClientSpanTracer.PEER_SERVICE)).isEqualTo("azureblob");
        assertThat(withEndpoint.get(ClientSpanTracer.SERVER_ADDRESS)).isEqualTo("acct.blob.core.windows.net");

        Attributes withoutHost = ClientSpanTracer.blobAttributes("filesystem", null);
        assertThat(withoutHost.get(ClientSpanTracer.PEER_SERVICE)).isEqualTo("filesystem");
        assertThat(withoutHost.get(ClientSpanTracer.SERVER_ADDRESS)).isNull();
    }

    @Test
    void recordsErrorAndRethrowsUnchecked() {
        RuntimeException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> tracer.clientSpan("clickhouse query", Attributes.empty(), () -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).isNotEmpty();
    }

    @Test
    void propagatesCheckedException() {
        IOException failure = new IOException("blob unavailable");

        assertThatThrownBy(() -> tracer.clientSpan("blob get", Attributes.empty(), () -> {
            throw failure;
        })).isSameAs(failure);

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void internalSpanRecordsInternalKind() {
        tracer.internalSpan("data-sync poll", Attributes.empty(), () -> "done");

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("data-sync poll");
        assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
    }

    @Test
    void voidClientSpanRunsBodyAndRecordsError() {
        AtomicBoolean ran = new AtomicBoolean(false);
        tracer.clientSpan("clickhouse ddl", Attributes.empty(), () -> ran.set(true));
        assertThat(ran).isTrue();

        RuntimeException boom = new IllegalStateException("boom");
        assertThatThrownBy(() -> tracer.clientSpan("clickhouse insert", Attributes.empty(), () -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        assertThat(exporter.getFinishedSpanItems().get(1).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }
}
