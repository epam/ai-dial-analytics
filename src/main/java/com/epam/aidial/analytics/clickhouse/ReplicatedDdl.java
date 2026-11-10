package com.epam.aidial.analytics.clickhouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a {@code CREATE TABLE} statement into its replicated form: the {@code MergeTree}-family engine becomes its
 * {@code Replicated} counterpart with no Keeper arguments ({@code ReplacingMergeTree(v)} →
 * {@code ReplicatedReplacingMergeTree(v)}), and the two settings that switch insert deduplication off are merged into
 * the statement's own {@code SETTINGS}. Columns, keys, partitioning and {@code TTL} are left exactly as written.
 *
 * <p>Two callers: the migration pre-create hook, which applies it to a pending migration's
 * {@code CREATE TABLE IF NOT EXISTS} so applied migration files never change ({@code data-sync} spec), and the
 * startup conversion, which applies it to a plain table's {@code system.tables.create_table_query}
 * ({@code clickhouse-replication} spec).
 */
public final class ReplicatedDdl {

    /** {@code replicated_deduplication_window = 0, replicated_deduplication_window_for_async_inserts = 0}. */
    public static final String NO_INSERT_DEDUPLICATION =
            "replicated_deduplication_window = 0, replicated_deduplication_window_for_async_inserts = 0";

    private static final Pattern CREATE = Pattern.compile(
            "^\\s*CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?((?:`[^`]*`|\"[^\"]*\"|[^\\s(`\"])+)"
                    + "(\\s+UUID\\s+'[^']*')?", Pattern.CASE_INSENSITIVE);
    // Keywords match in any case; engine names are case-sensitive in ClickHouse, so the name group is not.
    private static final Pattern ENGINE = Pattern.compile("(?i:\\bENGINE)\\s*=\\s*(\\w+)");
    // The table's SETTINGS clause is a list of `name = value`; a column or key named `settings` is not followed by one.
    private static final Pattern SETTINGS = Pattern.compile("\\bSETTINGS\\b(?=\\s+\\w+\\s*=)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("\\sCOMMENT\\s+'", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXISTING_DEDUP = Pattern.compile(
            "replicated_deduplication_window(?:_for_async_inserts)?\\s*=");
    private static final Pattern NO_DEDUP = Pattern.compile("\\breplicated_deduplication_window = 0\\b");
    private static final Pattern NO_ASYNC_DEDUP =
            Pattern.compile("\\breplicated_deduplication_window_for_async_inserts = 0\\b");

    private ReplicatedDdl() {
    }

    /** Whether {@code engine} ({@code system.tables.engine}) is a {@code MergeTree}-family engine. */
    public static boolean isMergeTreeFamily(String engine) {
        return engine != null && engine.endsWith("MergeTree");
    }

    /** Whether {@code engine} is a replicated {@code MergeTree}-family engine. */
    public static boolean isReplicated(String engine) {
        return isMergeTreeFamily(engine) && engine.startsWith("Replicated");
    }

    /** Whether {@code engineFull} ({@code system.tables.engine_full}) sets both deduplication settings to {@code 0}. */
    public static boolean disablesDeduplication(String engineFull) {
        return engineFull != null && NO_DEDUP.matcher(engineFull).find() && NO_ASYNC_DEDUP.matcher(engineFull).find();
    }

    /**
     * The statements of a migration script, split on {@code ;} and with their comments removed. Quoted literals and
     * identifiers ({@code '…'}, {@code "…"}, {@code `…`}) are kept intact, so a {@code ;}, {@code --} or {@code /*}
     * inside one neither splits the statement nor starts a comment. Empty statements are dropped.
     */
    public static List<String> statements(String script) {
        final List<String> statements = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            final char c = script.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < script.length()) {
                    current.append(script.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
            } else if (c == '-' && script.startsWith("--", i)) {
                final int end = script.indexOf('\n', i);
                i = end < 0 ? script.length() : end - 1;
                current.append(' ');
            } else if (c == '/' && script.startsWith("/*", i)) {
                final int end = script.indexOf("*/", i + 2);
                i = end < 0 ? script.length() : end + 1;
                current.append(' ');
            } else if (c == ';') {
                addStatement(statements, current);
            } else {
                current.append(c);
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        final String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    /** The table a {@code CREATE TABLE} names, as written (possibly database-qualified or quoted), if it is one. */
    public static Optional<String> createdTable(String statement) {
        final Matcher create = CREATE.matcher(statement);
        return create.find() ? Optional.of(create.group(2)) : Optional.empty();
    }

    /** Whether {@code statement} is a {@code CREATE TABLE IF NOT EXISTS} with a {@code MergeTree}-family engine. */
    public static boolean isCreateIfNotExists(String statement) {
        final Matcher create = CREATE.matcher(statement);
        final Matcher engine = ENGINE.matcher(statement);
        return create.find() && create.group(1) != null && findTopLevel(engine, statement)
                && isMergeTreeFamily(engine.group(1));
    }

    /**
     * {@code statement}, a {@code CREATE TABLE} with a {@code MergeTree}-family engine, in its replicated form.
     *
     * @throws IllegalArgumentException if the statement is not a {@code CREATE TABLE} or declares no such engine
     */
    public static String toReplicated(String statement) {
        if (createdTable(statement).isEmpty()) {
            throw new IllegalArgumentException("not a CREATE TABLE statement");
        }
        final Matcher engine = ENGINE.matcher(statement);
        if (!findTopLevel(engine, statement) || !isMergeTreeFamily(engine.group(1))) {
            throw new IllegalArgumentException("the statement declares no MergeTree-family engine");
        }
        final String name = engine.group(1);
        final String replicated = name.startsWith("Replicated") ? name : "Replicated" + name;
        // A replicated engine as create_table_query renders it carries explicit Keeper arguments; dropping them lets
        // the Replicated database supply its defaults. A plain engine's arguments (the version column) are kept.
        String rest = statement.substring(engine.end());
        if (name.startsWith("Replicated") && rest.startsWith("(")) {
            rest = stripKeeperArguments(rest);
        }
        String result = statement.substring(0, engine.start()) + "ENGINE = " + replicated + rest;
        return withNoDeduplication(trimTerminator(result));
    }

    /**
     * {@code createTableQuery} ({@code system.tables.create_table_query}) creating {@code twin} instead of its own
     * table, in the same database, with any {@code UUID} clause removed so the twin gets a fresh one.
     */
    public static String renamed(String createTableQuery, String database, String twin) {
        final Matcher create = CREATE.matcher(createTableQuery);
        if (!create.find()) {
            throw new IllegalArgumentException("not a CREATE TABLE statement");
        }
        return createTableQuery.substring(0, create.start(2)) + ClickHouseIdentifiers.quote(database) + "." + ClickHouseIdentifiers.quote(twin)
                + createTableQuery.substring(create.end());
    }

    private static String withNoDeduplication(String statement) {
        final Matcher engine = ENGINE.matcher(statement);
        findTopLevel(engine, statement);
        final String head = statement.substring(0, engine.end());
        final String tail = statement.substring(engine.end());
        final boolean[] topLevel = topLevel(tail);
        // SETTINGS is the last clause of a CREATE TABLE but a trailing COMMENT, so its last top-level match is the one.
        final Matcher settingsMatcher = SETTINGS.matcher(tail);
        int settingsStart = -1;
        int settingsEnd = -1;
        while (settingsMatcher.find()) {
            if (topLevel[settingsMatcher.start()]) {
                settingsStart = settingsMatcher.start();
                settingsEnd = settingsMatcher.end();
            }
        }
        final Matcher comment = COMMENT.matcher(tail);
        final int commentStart = findTopLevel(comment, topLevel, Math.max(settingsStart, 0)) ? comment.start() : -1;
        if (settingsStart >= 0) {
            final String before = tail.substring(0, settingsEnd);
            // The settings list runs up to a trailing table COMMENT, if any; it is rebuilt entry by entry so a
            // deduplication setting written anywhere in it is replaced without leaving a dangling comma.
            final int listEnd = commentStart >= 0 ? commentStart : tail.length();
            final List<String> kept = new ArrayList<>();
            for (final String entry : topLevelEntries(tail.substring(settingsEnd, listEnd))) {
                if (!EXISTING_DEDUP.matcher(entry).lookingAt()) {
                    kept.add(entry);
                }
            }
            return head + before + " " + NO_INSERT_DEDUPLICATION + (kept.isEmpty() ? "" : ", " + String.join(", ", kept))
                    + tail.substring(listEnd);
        }
        if (commentStart >= 0) {
            return head + tail.substring(0, commentStart) + " SETTINGS " + NO_INSERT_DEDUPLICATION
                    + tail.substring(commentStart);
        }
        return head + tail + "\nSETTINGS " + NO_INSERT_DEDUPLICATION;
    }

    /** Advances {@code matcher} to its first match over {@code text} at the statement's top level; see {@link #topLevel}. */
    private static boolean findTopLevel(Matcher matcher, String text) {
        return findTopLevel(matcher, topLevel(text), 0);
    }

    private static boolean findTopLevel(Matcher matcher, boolean[] topLevel, int from) {
        if (!matcher.find(from)) {
            return false;
        }
        do {
            if (topLevel[matcher.start()]) {
                return true;
            }
        } while (matcher.find());
        return false;
    }

    /**
     * For each position of {@code text}, a comment-free statement, whether it is at the top level: outside every
     * quoted literal or identifier and outside every parenthesis. The table's own clauses ({@code ENGINE},
     * {@code SETTINGS}, its {@code COMMENT}) are; a column definition, a key tuple and a function argument are not.
     */
    private static boolean[] topLevel(String text) {
        final boolean[] topLevel = new boolean[text.length() + 1];
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < text.length()) {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else {
                topLevel[i] = depth == 0;
            }
        }
        return topLevel;
    }

    /** The comma-separated entries of a {@code SETTINGS} list, trimmed; commas inside quotes or parentheses do not split. */
    private static List<String> topLevelEntries(String list) {
        final List<String> entries = new ArrayList<>();
        boolean quoted = false;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            final char c = list.charAt(i);
            if (c == '\'' && (i == 0 || list.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                entries.add(list.substring(start, i).trim());
                start = i + 1;
            }
        }
        entries.add(list.substring(start).trim());
        entries.removeIf(String::isEmpty);
        return entries;
    }

    /** {@code rest}, which starts with a replicated engine's argument list, without that list. */
    private static String stripKeeperArguments(String rest) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < rest.length(); i++) {
            final char c = rest.charAt(i);
            if (c == '\'' && (i == 0 || rest.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
                if (depth == 0) {
                    final String args = rest.substring(1, i);
                    // ReplicatedReplacingMergeTree('path', 'replica', version): keep what follows the two Keeper args.
                    final String kept = dropFirstTwoArguments(args);
                    return (kept.isEmpty() ? "" : "(" + kept + ")") + rest.substring(i + 1);
                }
            }
        }
        return rest;
    }

    private static String dropFirstTwoArguments(String args) {
        int dropped = 0;
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < args.length(); i++) {
            final char c = args.charAt(i);
            if (c == '\'' && (i == 0 || args.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                dropped++;
                if (dropped == 2) {
                    return args.substring(i + 1).trim();
                }
            }
        }
        // Fewer than three arguments: only Keeper arguments, or none at all.
        return args.trim().startsWith("'") ? "" : args.trim();
    }

    private static String trimTerminator(String statement) {
        String trimmed = statement.stripTrailing();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return trimmed;
    }
}
