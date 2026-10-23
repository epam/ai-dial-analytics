package com.epam.aidial.analytics.sync.delta;

/**
 * How ingest stores the request body of a payload row ({@code sync.request-body-mode}). Bound leniently (case and
 * {@code -}/{@code _} ignored), so {@code whole} and {@code delta} in the environment select these constants; any
 * other value fails binding and therefore startup.
 */
public enum RequestBodyMode {

    /** Store every request body whole in {@code request_body}; the request-delta columns keep their defaults. The default. */
    WHOLE,

    /**
     * Store a chat request as its envelope, the history elements its parent did not have, and a link to that parent,
     * with {@code request_body} written NULL (see {@link RequestDeltaEncoder}). Any other body is stored whole.
     */
    DELTA
}
