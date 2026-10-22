package com.epam.aidial.analytics.exception;

/**
 * A paged read named a column outside the allow-list of filterable/sortable attributes. Mapped to HTTP
 * 400.
 *
 * <p>Its own type rather than {@code IllegalArgumentException}, which nothing maps: an unhandled
 * exception here would surface as a 500 carrying a raw Hibernate message.
 */
public class InvalidFilterColumnException extends RuntimeException {

    public InvalidFilterColumnException(String message) {
        super(message);
    }
}
