package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonValue;

/** What import does, or would do, with one bundle access entry. Upper case on the wire, as {@link ImportAction}. */
public enum AccessImportAction {
    /** The target table has no entry for the role. */
    CREATE,
    /** The target table's entry for the role differs and is replaced. */
    REPLACE,
    /** The target table's entry for the role is equal. */
    SKIP,
    /** The entry is refused. */
    FAIL;

    @JsonValue
    public String code() {
        return name();
    }
}
