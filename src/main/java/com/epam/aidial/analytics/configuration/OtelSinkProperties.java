package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the OTLP sink surface ({@code otel-sink.*}), env-overridable via
 * {@code application.yml}.
 *
 * <p>The prefix is {@code otel-sink} rather than the more obvious {@code otel} on purpose: the
 * OpenTelemetry SDK and agent own the {@code OTEL_*} environment namespace, and Spring's relaxed binding
 * would map a deployment's {@code OTEL_ENABLED} — set for the agent, meaning something else entirely —
 * straight onto this surface's kill switch.
 *
 * <p>{@code enabled} is the kill switch for the whole {@code /v1/otel/**} surface — control plane and
 * ingest alike answer 503 {@code otel_disabled} when it is off. It defaults to <b>true even when the
 * property is absent</b>, matching {@code clickhouse.migrations.enabled}: a {@code Boolean} rather than a
 * {@code boolean} so "absent" is distinguishable from an explicit {@code false} and can be defaulted
 * deliberately rather than to Java's {@code false}.
 *
 * <p>{@code maxRowsPerRequest} bounds how many rows one OTLP payload may flatten into, so a single
 * export cannot be expanded into unbounded heap. It is deliberately much larger than
 * {@code tables.limits.max-write-batch-rows}, which is the size of the chunks those rows are then
 * written in: a normally-configured collector batches far more than 10 000 data points, and rejecting
 * such a payload would make the endpoint useless.
 *
 * <p>{@code defaultRetentionDays} is the row-expiry TTL given to a sink's landing tables when the create
 * request does not ask for one; {@code 0} means "no TTL".
 *
 * @param enabled              whether the OTLP surface is served (default true, including when absent)
 * @param maxRowsPerRequest    maximum rows one OTLP export may flatten into (default 50000)
 * @param defaultRetentionDays default landing-table retention in days, 0 for none (default 30)
 */
@ConfigurationProperties(prefix = "otel-sink")
public record OtelSinkProperties(Boolean enabled, int maxRowsPerRequest, int defaultRetentionDays) {

    /** Whether the OTLP surface is served; absent means enabled. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
