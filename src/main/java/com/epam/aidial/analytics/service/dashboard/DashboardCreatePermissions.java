package com.epam.aidial.analytics.service.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What the calling caller may create: a {@code private} dashboard, a {@code public} one. Computed per request. */
public record DashboardCreatePermissions(
        @JsonProperty("create_private") boolean createPrivate,
        @JsonProperty("create_public") boolean createPublic) {}
