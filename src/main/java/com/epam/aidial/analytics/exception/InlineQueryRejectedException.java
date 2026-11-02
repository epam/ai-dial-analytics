package com.epam.aidial.analytics.exception;

import lombok.Getter;

/**
 * A dashboard widget's inline body was rejected by the translation the query surface performs. Carries
 * the widget's id and the original rejection, so the response keeps the status and machine code the query
 * surface gives that rejection and only gains a {@code widget <id>: } prefix on its message.
 *
 * <p>A wrapper rather than a rethrow of the same type with a new message: some rejection types build their
 * own message, and rethrowing a supertype instead would change the machine code.
 */
@Getter
public class InlineQueryRejectedException extends RuntimeException {

    private final String widgetId;
    private final RuntimeException rejection;

    public InlineQueryRejectedException(String widgetId, RuntimeException rejection) {
        super("widget " + widgetId + ": " + rejection.getMessage(), rejection);
        this.widgetId = widgetId;
        this.rejection = rejection;
    }
}
