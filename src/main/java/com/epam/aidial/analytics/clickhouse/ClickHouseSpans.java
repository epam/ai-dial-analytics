package com.epam.aidial.analytics.clickhouse;

import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.observability.ClientSpanTracer;
import com.epam.aidial.analytics.observability.ClientSpanTracer.ThrowingRunnable;
import com.epam.aidial.analytics.observability.ClientSpanTracer.ThrowingSupplier;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Names and attributes the ClickHouse {@code CLIENT} spans in one place so the ClickHouse components
 * can wrap a client call with a single readable line. Delegates to {@link ClientSpanTracer};
 * host/port/database come from the shared {@link ClickHouseProperties}.
 */
@Component
@RequiredArgsConstructor
public class ClickHouseSpans {

    /**
     * The statement's ClickHouse query id, when the caller set one (every pipeline statement does; see
     * {@link StatementTag}). It joins the span to {@code system.query_log}; the statement text itself is never
     * an attribute.
     */
    public static final AttributeKey<String> DB_QUERY_ID = AttributeKey.stringKey("db.query.id");

    private final ClientSpanTracer tracer;
    private final ClickHouseProperties properties;

    /** Traces a read query as a {@code clickhouse query} span, carrying {@code queryId} when it is non-null. */
    public <T, E extends Exception> T query(String queryId, ThrowingSupplier<T, E> body) throws E {
        return tracer.clientSpan("clickhouse query", withQueryId(attributes("query"), queryId), body);
    }

    /** Traces a {@code DESCRIBE TABLE} introspection as a {@code clickhouse query} span. */
    public <T, E extends Exception> T describe(ThrowingSupplier<T, E> body) throws E {
        return tracer.clientSpan("clickhouse query", attributes("describe"), body);
    }

    /**
     * Traces a batch insert into {@code table} as a {@code clickhouse insert} span. The table is a
     * {@code db.collection.name} attribute rather than part of the span name, so span names stay
     * low-cardinality even when callers insert into user-defined catalog/enrichment tables.
     */
    public <E extends Exception> void insert(String table, ThrowingRunnable<E> body) throws E {
        insertSelect(table, null, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Traces a server-side {@code INSERT … SELECT} into {@code table} as a {@code clickhouse insert}
     * span, returning the statement's result (the written-row count). The value-returning sibling of
     * {@link #insert(String, ThrowingRunnable)}, which delegates here — both forms of writing to a table
     * must carry the same span name and attributes. {@code queryId} is recorded when it is non-null.
     */
    public <T, E extends Exception> T insertSelect(String table, String queryId, ThrowingSupplier<T, E> body) throws E {
        Attributes attributes = attributes("insert").toBuilder()
                .put(ClientSpanTracer.DB_COLLECTION, table)
                .build();
        return tracer.clientSpan("clickhouse insert", withQueryId(attributes, queryId), body);
    }

    /** Traces a DDL statement as a {@code clickhouse ddl} span. */
    public <E extends Exception> void ddl(ThrowingRunnable<E> body) throws E {
        tracer.clientSpan("clickhouse ddl", attributes("ddl"), body);
    }

    private static Attributes withQueryId(Attributes attributes, String queryId) {
        return queryId == null ? attributes : attributes.toBuilder().put(DB_QUERY_ID, queryId).build();
    }

    private Attributes attributes(String operation) {
        return ClientSpanTracer.clickHouseAttributes(
                operation, properties.host(), properties.port(), properties.database());
    }
}
