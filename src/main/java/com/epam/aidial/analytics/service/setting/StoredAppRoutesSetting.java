package com.epam.aidial.analytics.service.setting;

import java.time.Instant;

/** A stored app-routes setting with the server-stamped time and author of its last write. */
public record StoredAppRoutesSetting(AppRoutesSetting setting, Instant updatedAt, String updatedBy) {
}
