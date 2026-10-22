package com.epam.aidial.analytics.service.query.saved;

/**
 * How the client last rendered a saved query's result. A presentation preference only — it has no
 * effect on what the query returns.
 *
 * <p>Serialized lowercase on the wire ({@code table} / {@code chart}).
 */
public enum SavedQueryResultView {
    TABLE,
    CHART
}
