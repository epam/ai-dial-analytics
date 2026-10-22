package com.epam.aidial.analytics.service.table;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Kind of a catalog table — the table's <b>shape</b>, of which there are two.
 * {@code SOURCE} is a standalone table keyed by its own key, queryable directly and usable as a join
 * parent; {@code ENRICHMENT} is a child bound to a source, adding columns through a single-key grain
 * join. How rows get <em>produced</em> is not a kind — an aggregation is a <em>pipeline</em> whose
 * output is an ordinary {@code SOURCE} (written {@link TableWriteMode#UPSERT_BY_KEY}), which is why
 * there is no third {@code AGGREGATION} kind: a rollup is queryable and can parent an enrichment with
 * no special case anywhere.
 *
 * <p>Stored by {@link Enum#name()} (uppercase) in PostgreSQL; the wire form is the lowercase
 * {@link #code()}.
 */
public enum TableType {
    SOURCE("source"),
    ENRICHMENT("enrichment");

    private final String code;

    TableType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
