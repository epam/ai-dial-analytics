package com.epam.aidial.analytics.sync.delta;

/**
 * How ingest stores the request body of a payload row ({@code sync.request-body-mode}). Bound leniently (case and
 * {@code -}/{@code _} ignored), so {@code whole} and {@code delta} in the environment select these constants; any
 * other value fails binding and therefore startup.
 */
public enum RequestBodyMode {

    /** Store every request body whole in {@code request_body}, a chat request stamped with its path. The default. */
    WHOLE,

    /**
     * Store a chat request in {@code request_body} cut to the history elements its parent did not have, without the
     * tools it shares with its parent, with a link to that parent (see {@link RequestDeltaEncoder}). Any other body is
     * stored whole.
     */
    DELTA
}
