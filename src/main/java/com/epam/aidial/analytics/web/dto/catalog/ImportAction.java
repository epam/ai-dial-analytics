package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What import does, or would do, with one bundle object. Spelled in deployment manager's upper case on the wire,
 * unlike ADAS's own lower-case enums, so one admin UI reads both services' import reports alike.
 */
public enum ImportAction {
    /** The object is new and is created. */
    CREATE,
    /** The object exists and is kept unchanged, or is a required system table the target has. */
    SKIP,
    /** The object exists under FAIL_IF_EXISTS, or fails validation. */
    FAIL;

    @JsonValue
    public String code() {
        return name();
    }
}
