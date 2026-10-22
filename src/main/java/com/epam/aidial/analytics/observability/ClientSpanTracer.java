package com.epam.aidial.analytics.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Wraps a unit of work in an OpenTelemetry span so traces show the service's dependencies (ClickHouse,
 * blob store, …). Outbound I/O is traced as {@link SpanKind#CLIENT CLIENT}-kind spans; background job
 * cycles are traced as {@link SpanKind#INTERNAL INTERNAL}-kind parent spans (see {@link #internalSpan})
 * so the client spans they trigger nest under one trace instead of each becoming a disconnected root.
 * Clients such as the ClickHouse {@code client-v2} and jclouds run over their own HTTP stacks, which
 * Spring's HTTP-client auto-instrumentation never sees, so their spans must be created by hand here.
 *
 * <p>The span is created from {@link Span#current()}'s context, so a client span nests correctly under
 * the active parent (the inbound request span produced by the Micrometer→OpenTelemetry bridge, or an
 * {@link #internalSpan} on the scheduled sync/backfill threads). When tracing is disabled (the default)
 * {@code management.tracing.enabled} is off, so the SDK hands out a no-op tracer whose spans are
 * non-recording — {@link #clientSpan}/{@link #internalSpan} then allocate no {@code SdkSpan} and this is
 * a genuine no-op.
 */
@Component
public class ClientSpanTracer {

    /** Instrumentation scope name shared by every manually-created span. */
    private static final String INSTRUMENTATION_SCOPE = "com.epam.aidial.analytics";

    // Semantic-convention attribute keys captured on spans, kept as plain constants so the service does
    // not need to depend on the (still-experimental) opentelemetry-semconv artifact. Current stable keys
    // are used so these line up with the opentelemetry-jdbc auto-instrumented spans in the same trace.
    public static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system.name");
    public static final AttributeKey<String> DB_OPERATION = AttributeKey.stringKey("db.operation.name");
    public static final AttributeKey<String> DB_NAMESPACE = AttributeKey.stringKey("db.namespace");
    public static final AttributeKey<String> DB_COLLECTION = AttributeKey.stringKey("db.collection.name");
    public static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
    public static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");
    public static final AttributeKey<String> PEER_SERVICE = AttributeKey.stringKey("peer.service");

    private final Tracer tracer;

    public ClientSpanTracer(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * Runs {@code body} inside a new {@link SpanKind#CLIENT CLIENT} span named {@code name} and
     * carrying {@code attributes}. Any exception (checked {@code E} or unchecked) is recorded on the
     * span, marks it {@link StatusCode#ERROR}, and is rethrown unchanged; the span is always ended.
     *
     * @param name       low-cardinality span name (e.g. {@code "clickhouse query"})
     * @param attributes attributes to attach to the span
     * @param body       the outbound call to trace
     * @param <T>        the call's return type
     * @param <E>        the checked exception the call may throw
     * @return whatever {@code body} returns
     * @throws E if {@code body} throws it
     */
    public <T, E extends Exception> T clientSpan(String name, Attributes attributes, ThrowingSupplier<T, E> body) throws E {
        return span(SpanKind.CLIENT, name, attributes, body);
    }

    /** Void {@link #clientSpan(String, Attributes, ThrowingSupplier)} for calls that return nothing. */
    public <E extends Exception> void clientSpan(String name, Attributes attributes, ThrowingRunnable<E> body) throws E {
        span(SpanKind.CLIENT, name, attributes, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Runs {@code body} inside a new {@link SpanKind#INTERNAL INTERNAL} span. Used to give a
     * self-initiated background cycle (a scheduled poll/sync or an operator-triggered backfill day) a
     * single parent span, so the {@link #clientSpan CLIENT} spans it triggers form one coherent trace
     * rather than a scatter of disconnected root spans. Error handling matches {@link #clientSpan}.
     */
    public <T, E extends Exception> T internalSpan(String name, Attributes attributes, ThrowingSupplier<T, E> body) throws E {
        return span(SpanKind.INTERNAL, name, attributes, body);
    }

    private <T, E extends Exception> T span(SpanKind kind, String name, Attributes attributes, ThrowingSupplier<T, E> body) throws E {
        Span span = tracer.spanBuilder(name)
                .setSpanKind(kind)
                .setAllAttributes(attributes)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (Exception ex) {
            // Precise rethrow: the try body can only surface E or an unchecked exception, so this
            // compiles against the method's `throws E` without an unsafe cast.
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            span.recordException(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Builds the standard attribute set for a ClickHouse client span: {@code db.system.name=clickhouse},
     * the operation, the target database (when set), and the server address/port. Raw SQL is
     * deliberately not captured — the ClickHouse path inlines parameters, so statements could carry
     * user-identifying values.
     *
     * @param operation low-cardinality operation name (e.g. {@code "query"}, {@code "insert"})
     * @param host      ClickHouse host
     * @param port      ClickHouse HTTP port
     * @param database  target database, or {@code null}/blank when none is configured
     */
    public static Attributes clickHouseAttributes(String operation, String host, int port, String database) {
        AttributesBuilder builder = Attributes.builder()
                .put(DB_SYSTEM, "clickhouse")
                .put(DB_OPERATION, operation)
                .put(SERVER_ADDRESS, host)
                .put(SERVER_PORT, (long) port);
        if (StringUtils.isNotBlank(database)) {
            builder.put(DB_NAMESPACE, database.trim());
        }
        return builder.build();
    }

    /**
     * Builds the peer-identity attribute set for a blob-store client span.
     * Tempo synthesizes a virtual node for a {@code CLIENT} span from well-known peer attributes; the
     * {@code blob.*} keys alone are custom and invisible to it, so a peer identity is required.
     * {@code peer.service} always identifies the provider and, being first in Tempo's default
     * {@code peer_attributes} list, labels the node; {@code server.address} additionally carries the
     * endpoint host when one is configured (blank for the provider default / the {@code filesystem}
     * backend in tests, where only {@code peer.service} remains). {@code server.address} labels the
     * node instead only if an operator promotes it ahead of {@code peer.service} in {@code peer_attributes}.
     *
     * @param provider jclouds provider id used as the {@code peer.service} label (e.g. {@code azureblob})
     * @param host     endpoint host (e.g. {@code <account>.blob.core.windows.net}), or {@code null}/blank
     */
    public static Attributes blobAttributes(String provider, String host) {
        AttributesBuilder builder = Attributes.builder().put(PEER_SERVICE, provider);
        if (StringUtils.isNotBlank(host)) {
            builder.put(SERVER_ADDRESS, host);
        }
        return builder.build();
    }

    /**
     * A supplier whose computation may throw a checked exception. Kept generic in the exception type
     * so a single {@link #clientSpan} signature fits both runtime-exception and {@code IOException}
     * call sites.
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }

    /** A {@link Runnable} whose computation may throw a checked exception; the void {@link ThrowingSupplier}. */
    @FunctionalInterface
    public interface ThrowingRunnable<E extends Exception> {
        void run() throws E;
    }
}
