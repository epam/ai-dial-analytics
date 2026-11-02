package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.sync.inline.InlineFileRewrite;
import com.epam.aidial.analytics.sync.inline.InlineFilesPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Config-driven engine, walks
 * the declared {@link MappingConfig} columns and turns a parsed log record into an ordered
 * {@code Map<String, Object>} keyed by ClickHouse column name, ready for {@code JSONEachRow}
 * serialization.
 *
 * <p>Allowlist by construction: only declared columns are read and written.
 *
 * <p>For a <em>mapped</em> column the engine resolves the configured source (a dotted JSON path, or
 * {@code $sourceFile}), coerces present values to the declared {@link ColumnKind}, and applies the
 * configured transform (if any). For a <em>derived</em> column it resolves each {@code sources} path
 * to a node and computes the value via the named {@link DeriverRegistry} deriver. In both cases, when
 * the value is absent or blank the engine falls back, in order: to the configured default; else to
 * {@code null} if the column is {@code nullable}; else to the kind's zero value (so a non-nullable
 * column without a producer value still inserts safely).
 *
 * <p>A mapped column declaring {@code inline-files} is a body whose inline files the {@link InlineFilesPolicy}
 * ({@code sync.inline-files-drop-truncated} and {@code sync.inline-files-max-size}) governs. When the policy
 * {@link InlineFilesPolicy#rewrites() rewrites}, its coerced value is rewritten by an {@link InlineFileRewrite} before
 * the fallback applies; the rewrite replaces only the value written to that column, so every other column — derived
 * ones reading the same source path included — still sees the record as the producer sent it. Under
 * {@link InlineFilesPolicy#keep()} the attribute is inert and no body is parsed.
 *
 * <p>Not a Spring bean: one instance is compiled per {@link SyncTarget} (see {@code MappingConfiguration}),
 * so a run can drive several mappings — one per target table — from the shared registries.
 */
public class MappingEngine {

    private final List<CompiledColumn> columns;

    /**
     * Compiles the declared mapping once at startup: each column's transform/deriver is resolved to its
     * {@link TransformRegistry}/{@link DeriverRegistry} reference and each dotted source path is
     * pre-split, so {@link #toRow} performs no registry lookups or path re-splitting per row. The names
     * were already validated by {@link MappingFileLoader}; resolving here would surface any divergence
     * between the loader's and the engine's registries as a fail-fast startup error rather than a
     * per-row NPE.
     */
    public MappingEngine(MappingConfig config, TransformRegistry transforms, DeriverRegistry derivers) {
        this(config, transforms, derivers, InlineFilesPolicy.keep(), "", new SimpleMeterRegistry());
    }

    /**
     * Compiles the mapping for a target, with its inline-file handling.
     *
     * @param policy   the inline-files settings; when it {@link InlineFilesPolicy#rewrites() rewrites}, each {@code inline-files} column gets a rewrite
     * @param table    the target table, tagging the coercion-failure and inline-file counters
     * @param registry where those counters are registered
     */
    public MappingEngine(MappingConfig config, TransformRegistry transforms, DeriverRegistry derivers,
                         InlineFilesPolicy policy, String table, MeterRegistry registry) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(registry, "registry");
        List<CompiledColumn> compiled = new ArrayList<>(config.columns().size());
        for (ColumnMapping column : config.columns()) {
            InlineFileRewrite rewrite = policy.rewrites() && column.inlineFiles() != null
                    ? new InlineFileRewrite(column.inlineFiles().body(), policy, table, column.column(), registry)
                    : null;
            // Only a kind with a failure mode gets a counter, so the string, object and source-file columns do not each
            // register a series that can never move.
            CoercionFailures failures = !column.isSourceFile() && canFail(column.kind())
                    ? new CoercionFailures(table, column.column(), column.kind(), registry)
                    : null;
            compiled.add(CompiledColumn.of(column, transforms, derivers, rewrite, failures));
        }
        this.columns = List.copyOf(compiled);
    }

    /**
     * Maps a single parsed log record to a row.
     *
     * @param record     the parsed record
     * @param sourceFile originating blob name (provenance + dedup key; the {@code $sourceFile} source)
     * @return an ordered map of column name to value, in declared column order
     */
    public Map<String, Object> toRow(JsonNode record, String sourceFile) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (CompiledColumn column : columns) {
            row.put(column.mapping().column(), resolve(column, record, sourceFile));
        }
        return row;
    }

    private static Object resolve(CompiledColumn compiled, JsonNode record, String sourceFile) {
        ColumnMapping column = compiled.mapping();
        if (compiled.sourceFile()) {
            return applyFallback(column, sourceFile);
        }
        if (compiled.deriver() != null) {
            List<JsonNode> sources = new ArrayList<>(compiled.sourcePaths().size());
            for (JsonPaths.CompiledPath path : compiled.sourcePaths()) {
                sources.add(JsonPaths.walk(record, path));
            }
            Object derived = compiled.deriver().derive(sources, column.args());
            if (derived == Coercion.FAILED) {
                report(compiled, "DERIVED", sourceFile);
                derived = null;
            }
            return applyFallback(column, derived);
        }
        JsonNode node = JsonPaths.walk(record, compiled.sourcePath());
        Object value = (node.isMissingNode() || node.isNull()) ? null : coerce(column.kind(), node);
        if (value == Coercion.FAILED) {
            // A value the kind cannot represent is treated as absent: counted, and given the fallback rather than an
            // invented zero. The transform sees the null an absent value would give it.
            report(compiled, node.getNodeType().name(), sourceFile);
            value = null;
        }
        if (compiled.inlineFiles() != null && value instanceof String body) {
            JsonNode uri = JsonPaths.walk(record, compiled.uriPath());
            value = compiled.inlineFiles().apply(body, uri.isString() ? uri.asString() : null);
        }
        if (compiled.transform() != null) {
            value = compiled.transform().apply(value);
        }
        return applyFallback(column, value);
    }

    /** Whether a present value can fail to be read as {@code kind}: every kind but {@code string} and {@code object}. */
    private static boolean canFail(ColumnKind kind) {
        return kind != ColumnKind.STRING && kind != ColumnKind.OBJECT;
    }

    /**
     * Counts and logs a coercion failure. A column of a kind with no failure mode has no reporter; a deriver reporting a
     * failure for one (a mapping pairing a numeric deriver with the {@code string} kind) still takes the fallback.
     */
    private static void report(CompiledColumn compiled, String nodeType, String sourceFile) {
        if (compiled.failures() != null) {
            compiled.failures().report(nodeType, sourceFile);
        }
    }

    /**
     * A {@link ColumnMapping} with its transform/deriver references and source path(s) resolved once at
     * load time, so the per-row hot path does no registry lookups or path splitting.
     *
     * @param mapping     the declared column (carries name, kind, default, nullable, args)
     * @param sourceFile  whether the column's source is the {@code $sourceFile} synthetic token
     * @param sourcePath  the pre-compiled mapped source path; {@code null} for a derived or source-file column
     * @param transform   the resolved transform for a mapped column; {@code null} when none
     * @param deriver     the resolved deriver for a derived column; {@code null} for a mapped column
     * @param sourcePaths the pre-compiled deriver source paths; empty for a mapped or source-file column
     * @param inlineFiles the inline-file rewrite of a body column under a policy that rewrites; {@code null} otherwise
     * @param uriPath     the pre-compiled request-URI path the rewrite reads; {@code null} when there is no rewrite
     * @param failures    counts and logs the column's coercion failures; {@code null} for a kind that cannot fail
     */
    private record CompiledColumn(
            ColumnMapping mapping,
            boolean sourceFile,
            JsonPaths.CompiledPath sourcePath,
            Transform transform,
            Deriver deriver,
            List<JsonPaths.CompiledPath> sourcePaths,
            InlineFileRewrite inlineFiles,
            JsonPaths.CompiledPath uriPath,
            CoercionFailures failures) {

        static CompiledColumn of(ColumnMapping column, TransformRegistry transforms, DeriverRegistry derivers, InlineFileRewrite rewrite,
                CoercionFailures failures) {
            if (column.isSourceFile()) {
                return new CompiledColumn(column, true, null, null, null, List.of(), null, null, failures);
            }
            if (column.isDerived()) {
                Deriver deriver = requireResolved(derivers.get(column.derive()), "deriver", column.derive());
                List<JsonPaths.CompiledPath> paths = new ArrayList<>(column.sources().size());
                for (String path : column.sources()) {
                    paths.add(JsonPaths.compile(path));
                }
                return new CompiledColumn(column, false, null, null, deriver, List.copyOf(paths), null, null, failures);
            }
            Transform transform = column.transform() == null
                    ? null
                    : requireResolved(transforms.get(column.transform()), "transform", column.transform());
            JsonPaths.CompiledPath uriPath = rewrite == null ? null : JsonPaths.compile(column.inlineFiles().uri());
            return new CompiledColumn(
                    column, false, JsonPaths.compile(column.source()), transform, null, List.of(), rewrite, uriPath, failures);
        }

        private static <T> T requireResolved(T resolved, String kind, String name) {
            if (resolved == null) {
                throw new IllegalStateException("No " + kind + " registered under name '" + name
                        + "'; the mapping loader and engine registries have diverged");
            }
            return resolved;
        }
    }

    /**
     * Reads {@code node} as the declared kind, strictly ({@link Coercion}): a value the kind cannot represent is
     * {@link Coercion#FAILED} rather than an invented {@code 0} or {@code false}, and blank text is absent. A
     * <em>structured</em> node read as {@code STRING} is
     * serialized back to its JSON text rather than coerced: Jackson yields no string for a container node,
     * so the alternative is the empty string, which the blank fallback then stores as NULL — a producer
     * field that arrived as an object would be recorded as though it had never been sent. That matters for
     * a verbatim payload column such as {@code assembled_response}, whose value is legal in either shape
     * (the derivers reading it accept both) and which the mapping is required to store as reported
     * whatever its shape.
     */
    private static Object coerce(ColumnKind kind, JsonNode node) {
        return switch (kind) {
            case STRING -> Coercion.asText(node);
            case LONG -> Coercion.toLong(node);
            case DOUBLE -> Coercion.toDecimal(node);
            case BOOL -> Coercion.toBool(node);
            case ARRAY_STRING -> Coercion.toStringArray(node);
            case OBJECT -> node;
        };
    }

    private static Object applyFallback(ColumnMapping column, Object value) {
        if (!isBlank(value)) {
            return value;
        }
        if (column.defaultValue() != null) {
            return column.defaultValue();
        }
        if (column.nullable()) {
            return null;
        }
        return zero(column.kind());
    }

    private static Object zero(ColumnKind kind) {
        return switch (kind) {
            case STRING -> "";
            case LONG -> 0L;
            case DOUBLE -> BigDecimal.ZERO;
            case BOOL -> false;
            case ARRAY_STRING -> List.of();
            case OBJECT -> JsonNodeFactory.instance.objectNode();
        };
    }

    private static boolean isBlank(Object value) {
        return switch (value) {
            case null -> true;
            case String string -> string.isBlank();
            case Collection<?> collection -> collection.isEmpty();
            default -> false;
        };
    }
}
