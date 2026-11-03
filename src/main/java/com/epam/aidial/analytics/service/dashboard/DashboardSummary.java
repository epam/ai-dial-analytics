package com.epam.aidial.analytics.service.dashboard;

import java.time.Instant;

/**
 * A dashboard as the list shows it, read by a constructor projection so a listing never loads the
 * {@code widgets} document. {@code ownerId} is not returned in a list item; it is read only so the
 * item's {@code publish} flag can tell an ownerless {@code public} dashboard apart.
 */
public record DashboardSummary(
        String id,
        String name,
        String description,
        DashboardVisibility visibility,
        String ownerId,
        Instant updatedAt) {}
