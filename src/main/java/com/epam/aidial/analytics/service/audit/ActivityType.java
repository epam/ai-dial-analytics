package com.epam.aidial.analytics.service.audit;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What an activity says happened to a resource.
 *
 * <p>The constants are spelled exactly as their wire values, which deliberately departs from ADAS's
 * uppercase-constant convention. The DIAL admin frontend that consumes this feed compares these values
 * with strict {@code ===} against PascalCase and normalizes nothing — it renders {@code activityType}
 * raw into its grid — so uppercase constants would display literally as "CREATE" and fail every branch.
 * Naming the constants as the wire values also keeps {@code @Enumerated(EnumType.STRING)} storage, JSON
 * serialization, and the feed's filter values in agreement without a translation layer between them.
 * ADAS's checkstyle registers no {@code ConstantName}/{@code EnumConstantName} module, so the build
 * does not object.
 */
public enum ActivityType {

    Create,
    Update,
    Delete;

    /**
     * Pins the wire spelling against ADAS's global {@code WRITE_ENUMS_TO_LOWERCASE}, which would otherwise
     * emit {@code "create"} and defeat the whole point of naming these constants as their wire values.
     * That setting is right for the rest of the API — lowercase is ADAS's own vocabulary — and this
     * capability is the one place that has to speak a foreign one.
     */
    @JsonValue
    public String wireValue() {
        return name();
    }
}
