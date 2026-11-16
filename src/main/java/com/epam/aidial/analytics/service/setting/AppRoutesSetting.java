package com.epam.aidial.analytics.service.setting;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The body of the app-routes setting, in the order written. Also the stored JSON shape. */
public record AppRoutesSetting(@JsonProperty("routes") List<AppRoute> routes) {

    /** Whether {@code (deployment, route)} is listed, compared exactly and case-sensitively. */
    public boolean allows(String deployment, String route) {
        return routes != null && routes.contains(new AppRoute(deployment, route));
    }
}
