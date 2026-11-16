package com.epam.aidial.analytics.service.setting;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One allowed pair of the app-routes setting: a DIAL application and one of its routes. Compared exactly. */
public record AppRoute(
        @JsonProperty("deployment") String deployment,
        @JsonProperty("route") String route) {
}
