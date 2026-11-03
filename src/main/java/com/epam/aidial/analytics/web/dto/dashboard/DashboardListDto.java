package com.epam.aidial.analytics.web.dto.dashboard;

import com.epam.aidial.analytics.service.dashboard.DashboardCreatePermissions;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The dashboard list: every dashboard the caller may see, most recently updated first. */
public record DashboardListDto(
        List<DashboardListItemDto> dashboards,
        @Schema(description = "What the calling caller may create") DashboardCreatePermissions permissions) {}
