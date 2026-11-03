package com.epam.aidial.analytics.web.dto.dashboard;

import com.epam.aidial.analytics.service.dashboard.DashboardPermissions;
import com.epam.aidial.analytics.service.dashboard.DashboardVisibility;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** One entry of the dashboard list: enough to show and open it, and no widgets. */
public record DashboardListItemDto(
        String id,
        String name,
        String description,
        DashboardVisibility visibility,
        @JsonProperty("updated_at") Instant updatedAt,
        DashboardPermissions permissions) {}
