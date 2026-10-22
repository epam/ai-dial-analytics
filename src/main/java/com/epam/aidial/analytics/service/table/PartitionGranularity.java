package com.epam.aidial.analytics.service.table;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Time granularity of a source's optional {@code PARTITION BY}. Maps to a ClickHouse partition
 * expression over the partition column (rendered by {@link TableDdlRenderer}):
 * {@code DAY → toYYYYMMDD(col)}, {@code MONTH → toYYYYMM(col)}, {@code YEAR → toYYYY(col)}. Stored by
 * {@link Enum#name()} (uppercase) in PostgreSQL; the wire form is the lowercase {@link #code()}.
 */
public enum PartitionGranularity {
    DAY("day"),
    MONTH("month"),
    YEAR("year");

    private final String code;

    PartitionGranularity(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
