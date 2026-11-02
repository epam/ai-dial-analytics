package com.epam.aidial.analytics.web.dto.dashboard;

import com.epam.aidial.analytics.service.dashboard.DashboardCompare;
import com.epam.aidial.analytics.service.dashboard.DashboardPermissions;
import com.epam.aidial.analytics.service.dashboard.DashboardVisibility;
import com.epam.aidial.analytics.service.dashboard.model.DashboardWidget;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A dashboard in full, as returned by the single read and by create and replace. Inline bodies are
 * returned exactly as stored, unredacted, for every caller who may see the dashboard; running a widget
 * stays subject to the reader's own grants on the query surface. A member with no value is omitted.
 */
public record DashboardDto(
        @Schema(description = "Server-assigned opaque identifier", example = "dash_3f1ac9d24e7b4f0e8c1d5a6b7c8d9e0f")
        String id,

        String name,

        String description,

        @Schema(description = "private or public", example = "private")
        DashboardVisibility visibility,

        @Schema(description = "Authoring identity's principal, and the only field ownership is decided by. "
                + "Absent when security is disabled.")
        @JsonProperty("owner_id") String ownerId,

        @Schema(description = "Author's email at creation, for display only; often absent")
        @JsonProperty("owner_email") String ownerEmail,

        @Schema(description = "The dashboard's time intent, in the saved-query time model")
        SavedQueryTime time,

        @Schema(description = "Period comparison, returned as sent; absent means off")
        DashboardCompare compare,

        List<DashboardWidget> widgets,

        @Schema(description = "Concurrency token: send it back unchanged on a replace", example = "3")
        long generation,

        @JsonProperty("created_at") Instant createdAt,

        @JsonProperty("updated_at") Instant updatedAt,

        @Schema(description = "What the calling caller may do to this dashboard")
        DashboardPermissions permissions) {}
