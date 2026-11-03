package com.epam.aidial.analytics.service.dashboard;

/**
 * What the calling caller may do to one dashboard: {@code write} — replace and delete it at its current
 * visibility; {@code publish} — change that visibility. Computed per request, never stored or audited.
 */
public record DashboardPermissions(boolean write, boolean publish) {}
