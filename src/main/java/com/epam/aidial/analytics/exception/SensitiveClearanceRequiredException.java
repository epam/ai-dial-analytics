package com.epam.aidial.analytics.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * A schema operation touches sensitivity — declares or changes a {@code sensitive} flag, or changes a TTL
 * that is on a sensitive column — and the caller does not hold the {@code TABLE_SENSITIVE} clearance on
 * the table.
 *
 * <p>An {@link AccessDeniedException}, so it is the same {@code 403 forbidden} as every other privilege
 * denial; its own handler exists only to carry this message. The generic handler replaces the message with
 * a fixed sentence, which is right for an endpoint gate but leaves this denial unexplained — the caller
 * holds {@code TABLE_MODIFY} and needs to be told which privilege is missing. The message names only
 * columns the caller can already see, so it discloses nothing.
 */
public class SensitiveClearanceRequiredException extends AccessDeniedException {

    public SensitiveClearanceRequiredException(String message) {
        super(message);
    }
}
