package com.epam.aidial.analytics.clickhouse;

import java.util.Map;

/**
 * ClickHouse refused an insert because of the rows it carried: a value it could not parse, convert or fit, or a
 * constraint it violated. Raised only for the closed set of {@linkplain #DATA_CLASS_CODES data-class codes}, recognised
 * by the numeric server code, never by message text; every other insert failure keeps its own treatment. The ingest
 * write path splits such a batch by blob (see {@code openspec/specs/data-sync/spec.md}, "A batch ClickHouse refuses for
 * its data is split by blob").
 */
public class ClickHouseDataRejectedException extends RuntimeException {

    /**
     * The data-class server error codes and their names. A code outside this set — a transport fault, an unavailable
     * server, a memory limit, too many parts, disk exhaustion — is not the rows' fault, and splitting the batch would
     * only re-send it to a server that cannot take it.
     */
    public static final Map<Integer, String> DATA_CLASS_CODES = Map.ofEntries(
            Map.entry(6, "CANNOT_PARSE_TEXT"),
            Map.entry(25, "CANNOT_PARSE_ESCAPE_SEQUENCE"),
            Map.entry(26, "CANNOT_PARSE_QUOTED_STRING"),
            Map.entry(27, "CANNOT_PARSE_INPUT_ASSERTION_FAILED"),
            Map.entry(38, "CANNOT_PARSE_DATE"),
            Map.entry(41, "CANNOT_PARSE_DATETIME"),
            Map.entry(53, "TYPE_MISMATCH"),
            Map.entry(69, "ARGUMENT_OUT_OF_BOUND"),
            Map.entry(70, "CANNOT_CONVERT_TYPE"),
            Map.entry(72, "CANNOT_PARSE_NUMBER"),
            Map.entry(117, "INCORRECT_DATA"),
            Map.entry(128, "TOO_LARGE_ARRAY_SIZE"),
            Map.entry(130, "CANNOT_READ_ARRAY_FROM_TEXT"),
            Map.entry(131, "TOO_LARGE_STRING_SIZE"),
            Map.entry(321, "VALUE_IS_OUT_OF_RANGE_OF_DATA_TYPE"),
            Map.entry(349, "CANNOT_INSERT_NULL_IN_ORDINARY_COLUMN"),
            Map.entry(376, "CANNOT_PARSE_UUID"),
            Map.entry(407, "DECIMAL_OVERFLOW"),
            Map.entry(441, "CANNOT_PARSE_DOMAIN_VALUE_FROM_STRING"),
            Map.entry(467, "CANNOT_PARSE_BOOL"),
            Map.entry(469, "VIOLATED_CONSTRAINT"),
            Map.entry(563, "CANNOT_READ_MAP_FROM_TEXT"),
            Map.entry(675, "CANNOT_PARSE_IPV4"),
            Map.entry(676, "CANNOT_PARSE_IPV6"),
            Map.entry(691, "UNKNOWN_ELEMENT_OF_ENUM"));

    private final String table;
    private final int code;

    public ClickHouseDataRejectedException(String table, int code, Throwable cause) {
        super("ClickHouse refused the rows inserted into " + table + ": " + code + " " + DATA_CLASS_CODES.get(code), cause);
        this.table = table;
        this.code = code;
    }

    /** The table the refused insert targeted. */
    public String table() {
        return table;
    }

    /** The server error code. */
    public int code() {
        return code;
    }

    /** The server error code's name. */
    public String codeName() {
        return DATA_CLASS_CODES.get(code);
    }
}
