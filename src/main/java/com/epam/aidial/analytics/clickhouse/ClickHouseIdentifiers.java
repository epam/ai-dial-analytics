package com.epam.aidial.analytics.clickhouse;

/**
 * Quoting for identifiers the service did not validate: table and database names read back from {@code system.tables}
 * or configured by an operator. Unlike {@code Identifiers.quote}, which accepts only the catalog's own lower-case
 * names and rejects anything else as a bad request, this escapes any name so it can be rendered verbatim.
 */
public final class ClickHouseIdentifiers {

    private ClickHouseIdentifiers() {
    }

    /** {@code identifier} back-quoted, with backslashes and back-quotes escaped. */
    public static String quote(String identifier) {
        return "`" + identifier.replace("\\", "\\\\").replace("`", "\\`") + "`";
    }
}
