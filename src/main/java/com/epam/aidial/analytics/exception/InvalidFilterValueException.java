package com.epam.aidial.analytics.exception;

/**
 * A paged read carried a value that cannot be interpreted for its column — a non-numeric value for a
 * numeric attribute, an unknown enum constant, or an absent value. Mapped to HTTP 400.
 *
 * <p>Raised while coercing the value, <em>before</em> the query is built. Letting a bad value reach
 * Hibernate instead produces a {@code CoercionException}, which is not an {@code IllegalArgumentException}
 * and so would fall through to the catch-all handler as a 500.
 */
public class InvalidFilterValueException extends RuntimeException {

    public InvalidFilterValueException(String message) {
        super(message);
    }
}
