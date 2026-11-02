package com.epam.aidial.analytics.service.dashboard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Whether a rise in a single-value widget's figure is good news. A rendering hint only. */
public enum ChangeDirection {
    HIGHER_IS_BETTER("higher_is_better"),
    LOWER_IS_BETTER("lower_is_better");

    private final String token;

    ChangeDirection(String token) {
        this.token = token;
    }

    @JsonValue
    public String token() {
        return token;
    }

    @JsonCreator
    public static ChangeDirection of(String value) {
        return TokenEnums.parse(values(), ChangeDirection::token, value, "change_direction");
    }
}
