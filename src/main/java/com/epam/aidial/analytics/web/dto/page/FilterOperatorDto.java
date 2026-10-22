package com.epam.aidial.analytics.web.dto.page;

/**
 * Comparison applied by one {@link FilterDto}.
 *
 * <p>These are the sibling DIAL control planes' operator names, adopted verbatim so the admin frontend
 * can point at ADAS without a second client. {@code isnull}/{@code isnotnull} are deliberately absent:
 * nothing consumes them.
 */
public enum FilterOperatorDto {

    /** Equals. */
    eq,
    /** Not equals. */
    ne,
    /** Contains, case-insensitive; wildcard characters in the value are matched literally. */
    co,
    /** Does not contain; same matching rules as {@link #co}. */
    nc,
    /** Less than. */
    lt,
    /** Greater than. */
    gt,
    /** Less than or equal. */
    le,
    /** Greater than or equal. */
    ge,
    /** Set membership over a comma-separated value. */
    in
}
