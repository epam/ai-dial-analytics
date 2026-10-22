package com.epam.aidial.analytics.sync.mapping;

import java.util.List;

/**
 * The immutable, ordered set of columns that define the data-sync target table's structure, loaded
 * from the external mapping file at startup, plus the optional emit predicate declared by the file's
 * {@code match:} block. Column order is preserved so {@code JSONEachRow} output is stable.
 *
 * @param columns the ordered list of declared columns
 * @param matcher the compiled emit predicate; {@link RecordMatcher#matchAll()} when no {@code match:} block is declared
 */
public record MappingConfig(List<ColumnMapping> columns, RecordMatcher matcher) {

    public MappingConfig {
        columns = List.copyOf(columns);
        matcher = matcher == null ? RecordMatcher.matchAll() : matcher;
    }

    /** Convenience constructor for a mapping that emits unconditionally (no {@code match:} predicate). */
    public MappingConfig(List<ColumnMapping> columns) {
        this(columns, RecordMatcher.matchAll());
    }
}
