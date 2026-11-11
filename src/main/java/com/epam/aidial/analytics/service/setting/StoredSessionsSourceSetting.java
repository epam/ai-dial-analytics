package com.epam.aidial.analytics.service.setting;

import java.time.Instant;

/** A stored sessions-source setting with the server-stamped time and author of its last write. */
public record StoredSessionsSourceSetting(SessionsSourceSetting setting, Instant updatedAt, String updatedBy) {
}
