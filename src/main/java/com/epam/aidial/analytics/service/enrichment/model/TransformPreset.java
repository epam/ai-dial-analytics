package com.epam.aidial.analytics.service.enrichment.model;

/**
 * How an {@code llm} transform calls its DIAL deployment: through the deployment's chat-completions endpoint, or
 * through one of an application's custom routes ({@code /v1/deployments/{id}/route/{route}}).
 */
public enum TransformPreset {
    CHAT_COMPLETION,
    DIAL_APP_ROUTE
}
