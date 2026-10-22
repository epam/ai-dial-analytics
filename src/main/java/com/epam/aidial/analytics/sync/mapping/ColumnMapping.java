package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.sync.inline.InlineFilesSpec;

import java.util.List;
import java.util.Map;

/**
 * One declared target column in the data-sync mapping. Drives how a single ClickHouse column is
 * populated from a parsed log record.
 *
 * <p>A column is either <em>mapped</em> (a single {@code source} path coerced to a {@link ColumnKind},
 * optionally post-processed by a named transform) or <em>derived</em> (a named {@code derive} function
 * over an ordered list of {@code sources} paths plus static {@code args}). Exactly one of
 * {@code source} / {@code derive} is set.
 *
 * @param column       the ClickHouse column name (the {@code JSONEachRow} insert key)
 * @param source       a dotted source path within the record, or the {@code $sourceFile} synthetic token; {@code null} for a derived column
 * @param kind         the extraction kind used to coerce the resolved/derived value
 * @param transform    optional named transform from the closed registry; {@code null} when none (always {@code null} for a derived column)
 * @param defaultValue optional fallback used when the resolved/derived value is absent or blank; {@code null} when none
 * @param nullable     whether an absent/blank value with no default yields a JSON {@code null} (true) or the
 *                     kind's zero value (false); the table column must be {@code Nullable(...)} iff this is true
 * @param derive       optional named deriver from the closed registry; {@code null} for a mapped column
 * @param sources      the ordered source paths feeding the deriver; empty for a mapped column
 * @param args         the deriver's static arguments; empty when none
 * @param inlineFiles  the {@code inline-files} attribute of a mapped {@code string} body column, which puts its inline files
 *                     under {@code sync.inline-files-mode}; {@code null} for every other column
 */
public record ColumnMapping(
        String column,
        String source,
        ColumnKind kind,
        String transform,
        Object defaultValue,
        boolean nullable,
        String derive,
        List<String> sources,
        Map<String, Object> args,
        InlineFilesSpec inlineFiles) {

    /** The synthetic source token that resolves to the originating blob name. */
    public static final String SOURCE_FILE_TOKEN = "$sourceFile";

    public ColumnMapping {
        sources = sources == null ? List.of() : List.copyOf(sources);
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    /** Convenience constructor for a mapped (non-derived) column. */
    public ColumnMapping(String column, String source, ColumnKind kind, String transform,
                         Object defaultValue, boolean nullable) {
        this(column, source, kind, transform, defaultValue, nullable, null, List.of(), Map.of(), null);
    }

    /** Whether this column's source is the {@code $sourceFile} synthetic token. */
    public boolean isSourceFile() {
        return SOURCE_FILE_TOKEN.equals(source);
    }

    /** Whether this column is computed by a named deriver rather than mapped from a single source. */
    public boolean isDerived() {
        return derive != null;
    }
}
