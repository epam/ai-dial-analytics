package com.epam.aidial.analytics.service.otel.otlp;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * The resource-level and scope-level facts copied onto every row derived beneath them, resolved once per
 * resource/scope pair instead of per record.
 *
 * <p>{@code serviceName} is lifted out of the resource attributes because it is the one attribute every
 * telemetry reader filters and groups by — leading each landing table's ordering key — and leaving it
 * only inside the map would make that filter a map lookup, which the DSL cannot express.
 *
 * @param serviceName        the resource's {@code service.name} attribute, or {@code ""}
 * @param resourceAttributes the resource's attributes
 * @param scopeName          the instrumentation scope's name, or {@code ""}
 * @param scopeVersion       the instrumentation scope's version, or {@code ""}
 * @param scopeAttributes    the instrumentation scope's attributes
 */
public record OtlpScope(
        String serviceName,
        Map<String, String> resourceAttributes,
        String scopeName,
        String scopeVersion,
        Map<String, String> scopeAttributes) {

    /** The OTLP semantic-convention attribute naming the producing service. */
    public static final String SERVICE_NAME_ATTRIBUTE = "service.name";

    /**
     * Resolves the pair from a {@code resource} node and a {@code scopeX} entry's
     * {@code scope} member (either may be absent, which yields empty values rather than a failure).
     */
    public static OtlpScope of(JsonNode resource, JsonNode scope) {
        final Map<String, String> resourceAttributes = OtlpValues.attributes(resource);
        return new OtlpScope(
                resourceAttributes.getOrDefault(SERVICE_NAME_ATTRIBUTE, ""),
                resourceAttributes,
                OtlpValues.text(scope, "name"),
                OtlpValues.text(scope, "version"),
                OtlpValues.attributes(scope));
    }
}
