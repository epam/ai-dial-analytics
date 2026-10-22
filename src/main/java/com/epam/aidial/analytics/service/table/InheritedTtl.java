package com.epam.aidial.analytics.service.table;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The row-expiry TTL an aligned enrichment inherits from its source's physical table, and whether that table
 * drops expired parts whole ({@code ttl_only_drop_parts}).
 *
 * <p>Read from the source's {@code engine_full} rather than the catalog, because the case that matters has no
 * catalog TTL: {@code dial_usage_log}'s one year is migration-managed and deliberately not mirrored into its
 * definition. The server renders the clause in one canonical spelling —
 * {@code TTL toDateTime(request_time) + toIntervalYear(1)} — which is all this parses. A TTL in any other shape
 * (several rules, a {@code DELETE WHERE}, an expression over another column) is not inherited: copying a clause
 * the enrichment may not even have the columns for would make its {@code CREATE TABLE} fail.
 *
 * @param ttl              the inherited single-rule TTL over the alignment column
 * @param ttlOnlyDropParts whether the source drops expired parts whole, which the enrichment then does too
 */
public record InheritedTtl(TableDdlRenderer.TtlDdl ttl, boolean ttlOnlyDropParts) {

    private static final Pattern TTL = Pattern.compile(
            " TTL (toDateTime\\()?`?(\\w+)`?\\)? \\+ toInterval(Hour|Day|Week|Month|Year)\\((\\d+)\\)(?= SETTINGS |$)");
    private static final Pattern DROP_PARTS = Pattern.compile("\\bttl_only_drop_parts = 1\\b");

    /**
     * The TTL {@code engineFull} declares over {@code column}, or empty when it declares none, declares one in a
     * shape this does not parse, or declares one over another column.
     */
    public static Optional<InheritedTtl> parse(String engineFull, String column) {
        if (engineFull == null) {
            return Optional.empty();
        }
        final Matcher ttl = TTL.matcher(engineFull);
        if (!ttl.find() || !ttl.group(2).equals(column)) {
            return Optional.empty();
        }
        final TtlUnit unit = TtlUnit.valueOf(ttl.group(3).toUpperCase(Locale.ROOT));
        final TableDdlRenderer.TtlDdl ddl = new TableDdlRenderer.TtlDdl(
                column, Integer.parseInt(ttl.group(4)), unit, ttl.group(1) != null);
        return Optional.of(new InheritedTtl(ddl, DROP_PARTS.matcher(engineFull).find()));
    }
}
