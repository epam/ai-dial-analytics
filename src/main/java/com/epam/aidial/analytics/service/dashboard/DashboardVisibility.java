package com.epam.aidial.analytics.service.dashboard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Who may see a dashboard. {@code private} behaves like a {@code personal} saved query — its owner only —
 * and {@code public} like a {@code common} one. Stored as the constant name; exchanged as the token.
 */
public enum DashboardVisibility {
    PRIVATE("private"),
    PUBLIC("public");

    private final String token;

    DashboardVisibility(String token) {
        this.token = token;
    }

    @JsonValue
    public String token() {
        return token;
    }

    @JsonCreator
    public static DashboardVisibility of(String value) {
        return TokenEnums.parse(values(), DashboardVisibility::token, value, "visibility");
    }
}
