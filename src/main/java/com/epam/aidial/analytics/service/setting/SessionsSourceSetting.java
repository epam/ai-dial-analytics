package com.epam.aidial.analytics.service.setting;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The body of the sessions-source setting: the base entity and its role bindings. Also the stored JSON shape. */
public record SessionsSourceSetting(
        @JsonProperty("base_table") String baseTable,
        @JsonProperty("roles") SessionsSourceRoles roles) {
}
