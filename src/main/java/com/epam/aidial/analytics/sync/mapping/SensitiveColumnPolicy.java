package com.epam.aidial.analytics.sync.mapping;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The parsed {@code sync.sensitive-columns} setting: which of the columns the catalog flags {@code sensitive} ingest writes.
 * {@code *} writes all of them, {@code none} (or an empty value) none, and a list of entries only the named ones. A bare
 * entry {@code column} names that column on every ingest target table, a qualified {@code table.column} on that table
 * only; both parts are matched exactly, case-sensitively, against the target's table and {@code column_mapping.source_name}.
 *
 * <p>Parsing checks only the shape of the value: blank and repeated entries are dropped, and a keyword mixed with other
 * entries or an entry with an empty part is refused. Whether an entry names a column at all, and whether that column is
 * sensitive, needs the catalog and is checked by {@link SensitiveColumnExclusion#resolve}.
 *
 * @param all     whether every sensitive column is written ({@code *}); the catalog is then not read
 * @param entries the column entries, in configured order; empty with {@code all} false means no sensitive column is written
 */
public record SensitiveColumnPolicy(boolean all, List<Entry> entries) {

    /** The setting's property name, as error and log messages name it. */
    public static final String SETTING = "sync.sensitive-columns";

    /** The boolean setting {@code sync.sensitive-columns} replaces; set to anything, it fails startup. */
    public static final String REMOVED_SETTING = "sync.sensitive-columns-enabled";

    public static final String ALL_KEYWORD = "*";
    public static final String NONE_KEYWORD = "none";

    public static final SensitiveColumnPolicy ALL = new SensitiveColumnPolicy(true, List.of());
    public static final SensitiveColumnPolicy NONE = new SensitiveColumnPolicy(false, List.of());

    public SensitiveColumnPolicy {
        entries = List.copyOf(entries);
    }

    /**
     * One column entry.
     *
     * @param table  the table a qualified entry is limited to, or {@code null} for a bare entry
     * @param column the physical column name
     */
    public record Entry(String table, String column) {

        /** Whether this entry names {@code column} on {@code table}. */
        boolean names(String table, String column) {
            return this.column.equals(column) && (this.table == null || this.table.equals(table));
        }

        @Override
        public String toString() {
            return table == null ? column : table + "." + column;
        }
    }

    /**
     * Parses the configured entries; {@code null} (unset) is {@link #ALL}, an empty list or only blank entries {@link #NONE}.
     *
     * @throws IllegalArgumentException when a keyword is mixed with other entries or an entry has an empty part
     */
    public static SensitiveColumnPolicy parse(List<String> configured) {
        if (configured == null) {
            return ALL;
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String raw : configured) {
            String entry = raw == null ? "" : raw.strip();
            if (!entry.isEmpty()) {
                distinct.add(entry);
            }
        }
        for (String keyword : List.of(ALL_KEYWORD, NONE_KEYWORD)) {
            if (distinct.contains(keyword) && distinct.size() > 1) {
                throw new IllegalArgumentException(SETTING + " cannot combine '" + keyword + "' with other entries: " + distinct);
            }
        }
        if (distinct.contains(ALL_KEYWORD)) {
            return ALL;
        }
        if (distinct.isEmpty() || distinct.contains(NONE_KEYWORD)) {
            return NONE;
        }
        return new SensitiveColumnPolicy(false, distinct.stream().map(SensitiveColumnPolicy::entry).toList());
    }

    /**
     * Fails when the removed boolean {@code sync.sensitive-columns-enabled} ({@code SYNC_SENSITIVE_COLUMNS_ENABLED}) is
     * set, whatever its value. Ignoring it would turn a deployment's {@code false} into the default {@code *} and write
     * every sensitive column again.
     *
     * @throws IllegalStateException when the removed setting is set
     */
    public static void rejectRemovedSetting(Environment environment) {
        if (Binder.get(environment).bind(REMOVED_SETTING, Bindable.of(String.class)).isBound()) {
            throw new IllegalStateException(REMOVED_SETTING + " (SYNC_SENSITIVE_COLUMNS_ENABLED) is no longer supported; use "
                    + SETTING + " (SYNC_SENSITIVE_COLUMNS): unset or '*' for true, 'none' for false, or a comma-separated list of "
                    + "the sensitive columns ingest writes");
        }
    }

    /** Whether ingest writes the sensitive column {@code column} of {@code table}. */
    public boolean writes(String table, String column) {
        return all || entries.stream().anyMatch(e -> e.names(table, column));
    }

    private static Entry entry(String text) {
        int dot = text.indexOf('.');
        if (dot < 0) {
            return new Entry(null, text);
        }
        String table = text.substring(0, dot).strip();
        String column = text.substring(dot + 1).strip();
        if (table.isEmpty() || column.isEmpty() || column.indexOf('.') >= 0) {
            throw new IllegalArgumentException(SETTING + " entry '" + text + "' is neither a column nor table.column");
        }
        return new Entry(table, column);
    }
}
