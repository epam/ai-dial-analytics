package com.epam.aidial.analytics.service.dashboard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The dashboard-wide period comparison. The tokens are hyphenated, unlike the service's snake_case ones,
 * because they are the wire form the dashboard client already exchanges; the global
 * {@code WRITE_ENUMS_TO_LOWERCASE} would otherwise write {@code previous_period}.
 */
public enum DashboardCompare {
    OFF("off"),
    PREVIOUS_PERIOD("previous-period"),
    PREVIOUS_MONTH("previous-month"),
    PREVIOUS_YEAR("previous-year");

    private final String token;

    DashboardCompare(String token) {
        this.token = token;
    }

    @JsonValue
    public String token() {
        return token;
    }

    @JsonCreator
    public static DashboardCompare of(String value) {
        return TokenEnums.parse(values(), DashboardCompare::token, value, "compare");
    }
}
