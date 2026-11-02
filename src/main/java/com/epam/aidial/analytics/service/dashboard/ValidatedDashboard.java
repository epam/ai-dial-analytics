package com.epam.aidial.analytics.service.dashboard;

import com.epam.aidial.analytics.service.dashboard.model.DashboardWidget;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * A dashboard body that passed every envelope rule, in the form it is stored: the name trimmed, blank
 * references and blank SQL bodies dropped, each inline query's {@code result_view} filled in, and the time
 * intent bound to the saved-query model.
 */
public record ValidatedDashboard(
        String name,
        String description,
        DashboardVisibility visibility,
        SavedQueryTime time,
        DashboardCompare compare,
        JsonNode settings,
        List<DashboardWidget> widgets) {}
