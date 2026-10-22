package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.sync.inline.BodySide;
import com.epam.aidial.analytics.sync.inline.InlineFilesSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the external column-mapping YAML file (path from {@code sync.mappingFile}) into an immutable
 * {@link MappingConfig}. Resolves {@code classpath:}/{@code file:} locations via the Spring
 * {@link ResourceLoader}. Fails fast with a clear message on an unreadable or structurally invalid
 * file, an unknown {@code $}-prefixed source token, an unknown extraction kind, an unknown transform
 * name, an unknown deriver name, a derived column missing its {@code sources}, or a misplaced or malformed
 * {@code inline-files} attribute.
 *
 * <p>An optional top-level {@code match:} block declares the target's emit predicate (see
 * {@link RecordMatcher}); when absent the target emits a row for every record. Each condition names a
 * dotted {@code path}, an {@code op} ({@code ends-with}/{@code equals}/{@code contains}), a
 * {@code value}, and an optional {@code ignore-case} flag (default {@code false}) that folds case on
 * both sides. All conditions are ANDed. An unknown operator is a fatal load-time error.
 *
 * <p>Expected shape — a column is either mapped (single {@code source}) or derived ({@code derive} over
 * a {@code sources} list):
 * <pre>
 * match:                              # optional; omit to emit for every record
 *   - path: request.uri
 *     op: ends-with
 *     value: /rate
 * columns:
 *   - column: response_upstream_uri
 *     source: response.upstream_uri
 *     kind: string
 *     default: "unknown"              # optional fallback when the source is absent/blank
 *     nullable: true                  # optional; absent/blank source with no default -> JSON null
 *   - column: request_body
 *     source: request.body
 *     kind: string
 *     inline-files: { body: request, uri: request.uri }   # optional; a body governed by sync.inline-files-mode
 *   - column: event_id
 *     derive: hash-uuid               # named deriver from the closed registry
 *     sources: [trace.trace_id, trace.core_span_id]
 *     args: { path: id }              # optional static arguments
 *     kind: string
 * </pre>
 */
@RequiredArgsConstructor
public class MappingFileLoader {

    private static final String INLINE_FILES = "inline-files";

    private final ResourceLoader resourceLoader;
    private final TransformRegistry transforms;
    private final DeriverRegistry derivers;

    /**
     * Loads and validates the mapping file at the given location.
     *
     * @param location a Spring resource location ({@code classpath:...} or {@code file:...})
     * @return the parsed, validated mapping
     * @throws IllegalStateException if the file cannot be read or is structurally invalid
     */
    public MappingConfig load(String location) {
        Map<String, Object> root = read(location);
        Object rawColumns = root == null ? null : root.get("columns");
        if (!(rawColumns instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalStateException(
                    "Mapping file '" + location + "' must declare a non-empty 'columns' list");
        }

        List<ColumnMapping> columns = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof Map<?, ?> entry)) {
                throw new IllegalStateException(
                        "Mapping file '" + location + "' column #" + (i + 1) + " is not a mapping object");
            }
            columns.add(parseColumn(location, i, entry));
        }
        return new MappingConfig(columns, parseMatch(location, root.get("match")));
    }

    /**
     * Parses the optional top-level {@code match:} block into a {@link RecordMatcher}. An absent block
     * yields a match-all matcher (the target emits for every record); a present block must be a list of
     * {@code {path, op, value}} conditions with operators from the closed {@link RecordMatcher.Op} set.
     */
    private RecordMatcher parseMatch(String location, Object raw) {
        if (raw == null) {
            return RecordMatcher.matchAll();
        }
        if (!(raw instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalStateException(
                    "Mapping file '" + location + "' declares a 'match' that is not a non-empty list");
        }
        List<RecordMatcher.Condition> conditions = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String where = "Mapping file '" + location + "' match condition #" + (i + 1);
            if (!(entries.get(i) instanceof Map<?, ?> entry)) {
                throw new IllegalStateException(where + " is not a mapping object");
            }
            String path = requireString(entry.get("path"), where + " is missing 'path'");
            String opToken = requireString(entry.get("op"), where + " is missing 'op'");
            RecordMatcher.Op op = RecordMatcher.Op.fromToken(opToken);
            if (op == null) {
                throw new IllegalStateException(where + " uses unknown operator '" + opToken
                        + "'; known operators: " + knownOps());
            }
            String value = requireString(entry.get("value"), where + " is missing 'value'");
            boolean ignoreCase = Boolean.TRUE.equals(entry.get("ignore-case"));
            conditions.add(new RecordMatcher.Condition(JsonPaths.compile(path), op, value, ignoreCase));
        }
        return new RecordMatcher(conditions);
    }

    private static List<String> knownOps() {
        List<String> tokens = new ArrayList<>();
        for (RecordMatcher.Op op : RecordMatcher.Op.values()) {
            tokens.add(op.token());
        }
        return tokens;
    }

    private ColumnMapping parseColumn(String location, int index, Map<?, ?> entry) {
        String where = "Mapping file '" + location + "' column #" + (index + 1);

        String column = requireString(entry.get("column"), where + " is missing 'column'");
        String kindToken = requireString(entry.get("kind"), where + " ('" + column + "') is missing 'kind'");
        ColumnKind kind = parseKind(where, column, kindToken);
        boolean nullable = parseNullable(where, column, entry.get("nullable"));

        String derive = optionalString(entry.get("derive"));
        if (derive != null) {
            if (entry.get(INLINE_FILES) != null) {
                throw new IllegalStateException(where + " ('" + column + "') is derived and declares '" + INLINE_FILES
                        + "'; only a mapped string column can be an inline-files body");
            }
            return parseDerivedColumn(where, column, kind, nullable, derive, entry);
        }
        return parseMappedColumn(where, column, kind, nullable, entry);
    }

    private ColumnMapping parseMappedColumn(
            String where, String column, ColumnKind kind, boolean nullable, Map<?, ?> entry) {
        String source = requireString(entry.get("source"), where + " ('" + column + "') is missing 'source'");
        if (source.startsWith("$") && !ColumnMapping.SOURCE_FILE_TOKEN.equals(source)) {
            throw new IllegalStateException(where + " ('" + column + "') uses unknown synthetic source token '"
                    + source + "'; only '" + ColumnMapping.SOURCE_FILE_TOKEN + "' is allowed");
        }
        String transform = optionalString(entry.get("transform"));
        if (transform != null && !transforms.contains(transform)) {
            throw new IllegalStateException(where + " ('" + column + "') references unknown transform '"
                    + transform + "'; known transforms: " + transforms.names());
        }
        InlineFilesSpec inlineFiles = parseInlineFiles(where, column, kind, source, transform, entry.get(INLINE_FILES));
        return new ColumnMapping(column, source, kind, transform, entry.get("default"), nullable, null, List.of(), Map.of(), inlineFiles);
    }

    /**
     * Parses the optional {@code inline-files} attribute: {@code body} ({@code request}/{@code response}) and
     * {@code uri} (the dotted source path of the request URI). It belongs only on a mapped {@code string} column that
     * reads a real source path and declares no transform — the inline-file rewrite replaces the resolved value, so a
     * transform after it would have nothing well-defined to act on.
     */
    private static InlineFilesSpec parseInlineFiles(
            String where, String column, ColumnKind kind, String source, String transform, Object raw) {
        if (raw == null) {
            return null;
        }
        String prefix = where + " ('" + column + "') ";
        if (!(raw instanceof Map<?, ?> attribute)) {
            throw new IllegalStateException(prefix + "has a non-mapping '" + INLINE_FILES + "'");
        }
        if (kind != ColumnKind.STRING) {
            throw new IllegalStateException(prefix + "declares '" + INLINE_FILES + "' on a non-string column");
        }
        if (ColumnMapping.SOURCE_FILE_TOKEN.equals(source)) {
            throw new IllegalStateException(prefix + "declares '" + INLINE_FILES + "' on a " + ColumnMapping.SOURCE_FILE_TOKEN + " column");
        }
        if (transform != null) {
            throw new IllegalStateException(prefix + "declares both '" + INLINE_FILES + "' and a transform");
        }
        String bodyToken = optionalString(attribute.get("body"));
        BodySide body = BodySide.fromToken(bodyToken);
        if (body == null) {
            throw new IllegalStateException(prefix + "has '" + INLINE_FILES + ".body' " + (bodyToken == null ? "missing" : "'" + bodyToken + "'")
                    + "; expected request or response");
        }
        String uri = optionalString(attribute.get("uri"));
        if (uri == null || uri.startsWith("$")) {
            throw new IllegalStateException(prefix + "has '" + INLINE_FILES + ".uri' " + (uri == null ? "missing" : "'" + uri + "'")
                    + "; expected the dotted source path of the request URI");
        }
        return new InlineFilesSpec(body, uri);
    }

    private ColumnMapping parseDerivedColumn(
            String where, String column, ColumnKind kind, boolean nullable, String derive, Map<?, ?> entry) {
        if (entry.get("source") != null) {
            throw new IllegalStateException(where + " ('" + column + "') declares both 'source' and 'derive'; "
                    + "a column is either mapped or derived");
        }
        if (!derivers.contains(derive)) {
            throw new IllegalStateException(where + " ('" + column + "') references unknown deriver '"
                    + derive + "'; known derivers: " + derivers.names());
        }
        List<String> sources = parseSources(where, column, entry.get("sources"));
        Map<String, Object> args = parseArgs(where, column, entry.get("args"));
        return new ColumnMapping(column, null, kind, null, entry.get("default"), nullable, derive, sources, args, null);
    }

    /**
     * Parses the optional {@code nullable} flag, rejecting anything other than a real boolean. Without
     * this, {@code Boolean.parseBoolean(String.valueOf(...))} would silently coerce a typo ({@code "yes"},
     * a YAML {@code null}, a mistyped value) to {@code false} and mask the misconfiguration.
     */
    private static boolean parseNullable(String where, String column, Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        String token = String.valueOf(raw);
        if ("true".equals(token)) {
            return true;
        }
        if ("false".equals(token)) {
            return false;
        }
        throw new IllegalStateException(where + " ('" + column + "') has invalid 'nullable' value '"
                + token + "'; expected true or false");
    }

    private static ColumnKind parseKind(String where, String column, String kindToken) {
        try {
            return ColumnKind.fromToken(kindToken);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(where + " ('" + column + "'): " + ex.getMessage(), ex);
        }
    }

    private static List<String> parseSources(String where, String column, Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException(where + " ('" + column + "') is derived but declares no non-empty "
                    + "'sources' list");
        }
        List<String> sources = new ArrayList<>(list.size());
        for (Object item : list) {
            String source = optionalString(item);
            if (source == null) {
                throw new IllegalStateException(where + " ('" + column + "') has a blank entry in 'sources'");
            }
            sources.add(source);
        }
        return sources;
    }

    private static Map<String, Object> parseArgs(String where, String column, Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(where + " ('" + column + "') has a non-mapping 'args'");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (Map.Entry<?, ?> arg : map.entrySet()) {
            args.put(String.valueOf(arg.getKey()), arg.getValue());
        }
        return args;
    }

    private Map<String, Object> read(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Mapping file not found: '" + location + "'");
        }
        try (InputStream in = resource.getInputStream()) {
            // SafeConstructor disables arbitrary !!-tag deserialization; the mapping is operator-trusted
            // but this is cost-free hardening.
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            return yaml.load(in);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read mapping file '" + location + "'", ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Mapping file '" + location + "' is not valid YAML: " + ex.getMessage(), ex);
        }
    }

    private static String requireString(Object value, String message) {
        String text = optionalString(value);
        if (text == null) {
            throw new IllegalStateException(message);
        }
        return text;
    }

    private static String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
