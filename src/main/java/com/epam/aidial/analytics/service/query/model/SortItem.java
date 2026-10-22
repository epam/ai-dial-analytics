package com.epam.aidial.analytics.service.query.model;

/**
 * One ordering key: a projectable field / {@code group_by} key (row mode) or an aggregate
 * alias (aggregate mode), with a direction and optional null ordering. {@code nulls} is
 * client-controlled: when {@code null} the database default applies ({@code ASC} → NULLS
 * LAST, {@code DESC} → NULLS FIRST).
 */
public record SortItem(String field, SortDir dir, NullsOrder nulls) {

    // A client sort that is not a total order is not made deterministic by this record: appending a stable
    // unique tiebreaker (such as the primary key) is a translation-stage concern, not part of the request shape.
}
