package com.epam.aidial.analytics.exception;

/** An audit activity addressed by id does not exist. Mapped to HTTP 404. */
public class ActivityNotFoundException extends RuntimeException {

    public ActivityNotFoundException(String message) {
        super(message);
    }
}
