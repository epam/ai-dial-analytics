package com.epam.aidial.analytics.service.query.model;

/**
 * A set operator combining query blocks — <b>internal</b> to the SQL surface, never on the wire.
 *
 * <p>The {@code ALL}/{@code DISTINCT} qualifier is not part of the kind: it is carried separately by
 * {@link SetOpRelation#all()}, because every kind admits both and the rendered SQL must always spell one
 * of them out. {@code MINUS} is deliberately absent — ClickHouse has no such keyword, so the SQL front
 * end refuses that spelling rather than mapping it here.
 */
public enum SetOpKind {

    UNION("UNION"),
    INTERSECT("INTERSECT"),
    EXCEPT("EXCEPT");

    private final String keyword;

    SetOpKind(String keyword) {
        this.keyword = keyword;
    }

    /** The SQL keyword, rendered followed by {@code ALL} or {@code DISTINCT}. */
    public String keyword() {
        return keyword;
    }
}
