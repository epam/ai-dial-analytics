package com.epam.aidial.analytics.service.query.saved;

/**
 * A saved query's sharing scope. {@code PERSONAL} is readable only by its owner and writable by any
 * caller holding an application role (on its own rows); {@code COMMON} is readable by any caller
 * holding {@code SAVED_QUERY_READ} and writable only by a {@code SAVED_QUERY_PUBLISH} holder.
 *
 * <p>Serialized lowercase on the wire ({@code personal} / {@code common}) by the application's
 * {@code JsonMapper} configuration.
 */
public enum SavedQueryScope {
    PERSONAL,
    COMMON
}
