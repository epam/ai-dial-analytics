package com.epam.aidial.analytics.blob;

import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.observability.ClientSpanTracer;
import com.epam.aidial.analytics.observability.ClientSpanTracer.ThrowingSupplier;
import io.opentelemetry.api.common.Attributes;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Names and attributes the blob-store {@code CLIENT} spans in one place so {@link BlobClient} can wrap
 * a jclouds call with a single readable line rather than repeating the span name and attribute wiring.
 * Delegates to {@link ClientSpanTracer}; provider/container/endpoint come from the shared
 * {@link BlobProperties}. Each span carries a peer identity ({@code peer.service} + {@code server.address}
 * via {@link ClientSpanTracer#blobAttributes}) so the blob backend shows up as a node in Tempo's service
 * graph, plus the {@code blob.*} detail keys. Blob keys/prefixes are date-partitioned log paths, not
 * user data, so they are safe to record.
 */
@Component
@RequiredArgsConstructor
public class BlobSpans {

    private final ClientSpanTracer tracer;
    private final BlobProperties properties;

    /** Traces a prefix listing as a {@code blob list} span. */
    public <T, E extends Exception> T list(String prefix, ThrowingSupplier<T, E> body) throws E {
        return tracer.clientSpan("blob list", attributes("list", "blob.prefix", prefix), body);
    }

    /** Traces a single-blob fetch as a {@code blob get} span. */
    public <T, E extends Exception> T get(String key, ThrowingSupplier<T, E> body) throws E {
        return tracer.clientSpan("blob get", attributes("get", "blob.key", key), body);
    }

    private Attributes attributes(String operation, String targetKey, String targetValue) {
        return ClientSpanTracer.blobAttributes(properties.provider(), endpointHost()).toBuilder()
                .put("blob.operation", operation)
                .put("blob.provider", properties.provider())
                .put("blob.container", properties.bucket())
                .put(targetKey, targetValue)
                .build();
    }

    /**
     * Extracts the host from the configured blob endpoint so it can be reported as {@code server.address}.
     * Returns {@code null} when no endpoint is set (provider default / {@code filesystem}) or the value has
     * no parseable host — in which case only {@code peer.service} identifies the node.
     */
    private String endpointHost() {
        String endpoint = properties.endpoint();
        if (StringUtils.isBlank(endpoint)) {
            return null;
        }
        try {
            String host = URI.create(endpoint.trim()).getHost();
            return StringUtils.isNotBlank(host) ? host : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
