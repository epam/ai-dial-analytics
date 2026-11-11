package com.epam.aidial.analytics.service.setting;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The session roles of the sessions-source setting, each a flat field name of the base table's entity
 * schema, or {@code null} when the role is not set. This is also the stored JSON shape.
 */
public record SessionsSourceRoles(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("time") String time,
        @JsonProperty("project") String project,
        @JsonProperty("title") String title,
        @JsonProperty("cost") String cost,
        @JsonProperty("turns") String turns) {
}
