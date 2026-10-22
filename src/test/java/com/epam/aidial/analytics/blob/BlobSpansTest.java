package com.epam.aidial.analytics.blob;

import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.observability.ClientSpanTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link BlobSpans} attaches a peer identity ({@code peer.service} + {@code server.address})
 * to blob {@code CLIENT} spans so the blob backend renders as a service-graph node, alongside the
 * {@code blob.*} detail attributes, and that the endpoint host is parsed correctly.
 */
class BlobSpansTest {

    private static final AttributeKey<String> BLOB_OPERATION = AttributeKey.stringKey("blob.operation");
    private static final AttributeKey<String> BLOB_PROVIDER = AttributeKey.stringKey("blob.provider");
    private static final AttributeKey<String> BLOB_CONTAINER = AttributeKey.stringKey("blob.container");
    private static final AttributeKey<String> BLOB_PREFIX = AttributeKey.stringKey("blob.prefix");

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
    }

    @AfterEach
    void tearDown() {
        sdk.close();
    }

    private BlobSpans blobSpans(String provider, String endpoint) {
        ClientSpanTracer tracer = new ClientSpanTracer(sdk);
        BlobProperties properties = new BlobProperties(provider, null, null, "logs", endpoint, Map.of());
        return new BlobSpans(tracer, properties);
    }

    @Test
    void listSpanCarriesPeerIdentityAndServerAddressFromEndpoint() {
        BlobSpans spans = blobSpans("azureblob", "https://acct.blob.core.windows.net");

        String result = spans.list("logs/2026/07/23", () -> "ok");

        assertThat(result).isEqualTo("ok");
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("blob list");
        assertThat(span.getAttributes().get(ClientSpanTracer.PEER_SERVICE)).isEqualTo("azureblob");
        assertThat(span.getAttributes().get(ClientSpanTracer.SERVER_ADDRESS)).isEqualTo("acct.blob.core.windows.net");
        assertThat(span.getAttributes().get(BLOB_OPERATION)).isEqualTo("list");
        assertThat(span.getAttributes().get(BLOB_PROVIDER)).isEqualTo("azureblob");
        assertThat(span.getAttributes().get(BLOB_CONTAINER)).isEqualTo("logs");
        assertThat(span.getAttributes().get(BLOB_PREFIX)).isEqualTo("logs/2026/07/23");
    }

    @Test
    void spanOmitsServerAddressWhenNoEndpointConfigured() {
        BlobSpans spans = blobSpans("filesystem", null);

        spans.get("logs/2026/07/23/file.json", () -> "ok");

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("blob get");
        assertThat(span.getAttributes().get(ClientSpanTracer.PEER_SERVICE)).isEqualTo("filesystem");
        assertThat(span.getAttributes().get(ClientSpanTracer.SERVER_ADDRESS)).isNull();
    }
}
