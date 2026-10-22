package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Carries ClickHouse's {@code FINAL} table modifier through a parser that has no such modifier.
 *
 * <p>Calcite's Babel grammar reads {@code FROM t FINAL} as the table {@code t} aliased {@code FINAL}, and
 * refuses {@code FROM t AS a FINAL} outright. What it does have is a table hint, {@code t /*+ final *&#47;},
 * which the validator keeps and {@code SqlToRelConverter} puts on the resulting {@code TableScan} — exactly
 * where the translator binds a reference to its entity (see {@link #HINT}). This pass therefore rewrites each
 * {@code FINAL} that follows a table reference into that hint, placed straight after the table name and ahead
 * of any alias, where the grammar accepts it:
 *
 * <pre>
 *   FROM dial_usage_log FINAL            →  FROM dial_usage_log /*+ final *&#47;
 *   FROM dial_usage_log AS l FINAL       →  FROM dial_usage_log /*+ final *&#47; AS l
 *   JOIN rate_analytics r FINAL ON …     →  JOIN rate_analytics /*+ final *&#47; r ON …
 * </pre>
 *
 * <p><b>Where it is a modifier.</b> Exactly where ClickHouse accepts it (measured on 26.3 and 26.8): after a table
 * reference in a {@code FROM} list or a {@code JOIN}, bare, quoted or qualified, and after the reference's
 * alias ({@code t FINAL}, {@code t AS a FINAL}, {@code t a FINAL}) — never between the reference and its
 * alias, which ClickHouse refuses ({@code t FINAL AS a}) and which this pass therefore leaves for the parser
 * to refuse too. The scan is token-aware: string literals (including the C-style {@code e'…'} form), quoted
 * identifiers and comments are skipped, so {@code 'FINAL'}, {@code "final"} and {@code -- FINAL} keep their
 * meaning. Anywhere else the word is left alone, and a {@code FINAL} after a {@code WHERE} still fails to
 * parse exactly as it did.
 *
 * <p><b>What it refuses.</b> {@code FINAL} after a derived table — {@code (SELECT …) FINAL} or
 * {@code (SELECT …) AS d FINAL} — is refused here, because it names no table: ClickHouse refuses it too
 * ({@code UNSUPPORTED_METHOD}). The same refusal for a reference to a CTE name or a catalog table needs the
 * parsed tree and is made by {@link SqlFinalReferences}.
 *
 * <p>The rewrite changes the statement's length, so a parse error reported after a rewritten reference points
 * a few columns further right than the caller's text; the diagnosis itself is unchanged.
 */
final class SqlFinalModifier {

    /** The hint name the rewrite emits and the translator reads off a {@code TableScan}. */
    static final String HINT = "final";

    /** The refusal for {@code FINAL} on something that is not an entity. */
    static final String NOT_AN_ENTITY =
            "FINAL applies to an entity, not to a derived table, a CTE or a catalog table; put it on the entity inside the body";

    private static final String HINT_TEXT = " /*+ " + HINT + " */";

    /** Words that close a statement after a column or alias named {@code format}: {@code ORDER BY format DESC}. */
    private static final Set<String> NOT_A_FORMAT_NAME = Set.of("ASC", "DESC", "FINAL", "FIRST", "LAST");

    /** Words that end a {@code FROM} list at their depth. */
    private static final Set<String> CLAUSE_ENDS = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "WINDOW", "QUALIFY",
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "SELECT", "ON", "USING", "VALUES");

    /** Words that cannot be a table name or an alias where they stand. */
    private static final Set<String> NOT_A_NAME = Set.of(
            "FROM", "JOIN", "AS", "ON", "USING", "LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS", "NATURAL",
            "LATERAL", "FINAL", "WHERE", "SELECT");

    /** Words that may follow a complete table reference; any other word after {@code FINAL} is an alias. */
    private static final Set<String> FOLLOWS_A_REFERENCE = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "WINDOW", "QUALIFY",
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL",
            "ON", "USING");

    private SqlFinalModifier() {
    }

    /** {@code sql} with every {@code FINAL} modifier rewritten into the {@link #HINT} table hint. */
    static String rewrite(String sql) {
        if (sql == null || !sql.toUpperCase(Locale.ROOT).contains("FINAL")) {
            return sql;
        }
        final List<Token> tokens = tokenize(sql);
        final List<int[]> removals = new ArrayList<>();
        final List<Integer> insertions = new ArrayList<>();
        // Per depth: [0] a table reference may stand here; [1] inside a FROM clause (a join condition pauses
        // [0] but not [1], so a comma after `JOIN b ON …` continues the FROM list).
        final Deque<boolean[]> frames = new ArrayDeque<>();
        frames.push(new boolean[] {false, false});
        for (int i = 0; i < tokens.size(); i++) {
            final Token token = tokens.get(i);
            switch (token.kind) {
                case OPEN -> frames.push(new boolean[] {false, false});
                case CLOSE -> {
                    if (frames.size() > 1) {
                        frames.pop();
                    }
                }
                case COMMA -> {
                    final boolean[] frame = frames.peek();
                    if (frame[1]) {
                        frame[0] = true;
                    }
                }
                case WORD -> {
                    final String word = token.upper(sql);
                    final boolean[] frame = frames.peek();
                    if (word.equals("FROM") || word.equals("JOIN")) {
                        frame[0] = true;
                        frame[1] = true;
                    } else if (word.equals("ON") || word.equals("USING")) {
                        frame[0] = false;
                    } else if (CLAUSE_ENDS.contains(word)) {
                        frame[0] = false;
                        frame[1] = false;
                    } else if (word.equals("FINAL") && frame[0] && !aliasFollows(sql, tokens, i)) {
                        final int end = referenceEnd(sql, tokens, i);
                        if (end >= 0) {
                            removals.add(new int[] {spaceBefore(sql, token.start), token.end});
                            insertions.add(tokens.get(end).end);
                        }
                    }
                }
                default -> { }
            }
        }
        if (removals.isEmpty()) {
            return sql;
        }
        final StringBuilder out = new StringBuilder(sql.length() + insertions.size() * HINT_TEXT.length());
        int at = 0;
        for (int k = 0; k < removals.size(); k++) {
            final int insert = insertions.get(k);
            final int[] removal = removals.get(k);
            out.append(sql, at, insert).append(HINT_TEXT).append(sql, insert, removal[0]);
            at = removal[1];
        }
        return out.append(sql, at, sql.length()).toString();
    }

    /**
     * Whether {@code word} stands as a bare word token in {@code sql} — outside string literals, quoted
     * identifiers and comments — compared case-insensitively. Shares this class's lexer so that what counts
     * as code here is exactly what counts as code to {@link #rewrite}.
     */
    static boolean containsWord(String sql, String word) {
        if (sql == null) {
            return false;
        }
        for (final Token token : tokenize(sql)) {
            if (token.kind == Kind.WORD && token.end - token.start == word.length()
                    && sql.regionMatches(true, token.start, word, 0, word.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code sql} carries a ClickHouse {@code FORMAT} clause: {@code FORMAT JSON} anywhere (which Calcite
     * misreads as JSON value syntax), or a trailing {@code FORMAT <name>} closing the statement, before any
     * {@code ;}. A column or alias merely named {@code format} is neither. Shares this class's lexer, so literals,
     * quoted identifiers and comments never count.
     */
    static boolean hasFormatClause(String sql) {
        if (sql == null) {
            return false;
        }
        final List<Token> tokens = tokenize(sql);
        int last = tokens.size() - 1;
        while (last >= 0 && tokens.get(last).kind == Kind.OTHER && sql.charAt(tokens.get(last).start) == ';') {
            last--;
        }
        for (int i = 0; i < last; i++) {
            final Token token = tokens.get(i);
            final Token next = tokens.get(i + 1);
            if (token.kind == Kind.WORD && "FORMAT".equals(token.upper(sql))
                    && (next.kind == Kind.WORD && "JSON".equals(next.upper(sql))
                            || i + 1 == last && (next.kind == Kind.WORD && !NOT_A_FORMAT_NAME.contains(next.upper(sql))
                                    || next.kind == Kind.QUOTED))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The index of the last token of the table name the {@code FINAL} at {@code finalAt} modifies, or
     * {@code -1} when the tokens before it are not a table reference in {@code FROM}/{@code JOIN} position.
     *
     * @throws SqlTranslationException when they are a derived table
     */
    private static int referenceEnd(String sql, List<Token> tokens, int finalAt) {
        int at = finalAt - 1;
        if (at < 0) {
            return -1;
        }
        // An alias: `<ref> AS a FINAL` or `<ref> a FINAL`.
        if (isName(sql, tokens.get(at)) && at >= 1) {
            final Token before = tokens.get(at - 1);
            if (before.kind == Kind.WORD && before.upper(sql).equals("AS")) {
                at -= 2;
            } else if (isName(sql, before) || before.kind == Kind.CLOSE) {
                at -= 1;
            }
        }
        if (at < 0) {
            return -1;
        }
        if (tokens.get(at).kind == Kind.CLOSE) {
            if (opensReference(sql, tokens, matchingOpen(tokens, at) - 1)) {
                throw new SqlTranslationException(NOT_AN_ENTITY);
            }
            return -1;
        }
        // The table name: `name` or `a.b.name`.
        final int end = at;
        if (!isName(sql, tokens.get(at))) {
            return -1;
        }
        while (at >= 2 && tokens.get(at - 1).kind == Kind.DOT && isName(sql, tokens.get(at - 2))) {
            at -= 2;
        }
        return opensReference(sql, tokens, at - 1) ? end : -1;
    }

    /**
     * Whether an alias follows the {@code FINAL} at {@code finalAt} — {@code t FINAL AS a} or {@code t FINAL a},
     * the placements ClickHouse refuses. Such a {@code FINAL} is left in place for the parser to refuse too.
     */
    private static boolean aliasFollows(String sql, List<Token> tokens, int finalAt) {
        if (finalAt + 1 >= tokens.size()) {
            return false;
        }
        final Token next = tokens.get(finalAt + 1);
        return next.kind == Kind.QUOTED || next.kind == Kind.WORD && !FOLLOWS_A_REFERENCE.contains(next.upper(sql));
    }

    /** Whether the token at {@code at} is what a table reference follows: {@code FROM}, {@code JOIN} or a comma. */
    private static boolean opensReference(String sql, List<Token> tokens, int at) {
        if (at < 0) {
            return false;
        }
        final Token token = tokens.get(at);
        if (token.kind == Kind.COMMA) {
            return true;
        }
        if (token.kind != Kind.WORD) {
            return false;
        }
        final String word = token.upper(sql);
        return word.equals("FROM") || word.equals("JOIN");
    }

    private static int matchingOpen(List<Token> tokens, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            if (tokens.get(i).kind == Kind.CLOSE) {
                depth++;
            } else if (tokens.get(i).kind == Kind.OPEN && --depth == 0) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isName(String sql, Token token) {
        return token.kind == Kind.QUOTED || token.kind == Kind.WORD && !NOT_A_NAME.contains(token.upper(sql));
    }

    /** Where the whitespace run ending at {@code at} starts, so a removed word takes its leading gap with it. */
    private static int spaceBefore(String sql, int at) {
        int from = at;
        while (from > 0 && Character.isWhitespace(sql.charAt(from - 1))) {
            from--;
        }
        return from;
    }

    // ------------------------------------------------------------------ lexer

    private enum Kind { WORD, QUOTED, OPEN, CLOSE, COMMA, DOT, OTHER }

    private record Token(Kind kind, int start, int end) {
        String upper(String sql) {
            return sql.substring(start, end).toUpperCase(Locale.ROOT);
        }
    }

    /**
     * The statement's tokens, with string literals, comments and whitespace dropped. Only the distinctions
     * {@link #rewrite} needs are drawn: a word, a double-quoted identifier, parentheses, commas and dots; any
     * other character is {@code OTHER}.
     */
    private static List<Token> tokenize(String sql) {
        final List<Token> tokens = new ArrayList<>();
        final int n = sql.length();
        int i = 0;
        while (i < n) {
            final char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                final int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
            } else if (c == '\'') {
                i = skipQuoted(sql, i, '\'', false);
            } else if ((c == 'e' || c == 'E') && i + 1 < n && sql.charAt(i + 1) == '\''
                    && (i == 0 || !isWordChar(sql.charAt(i - 1)))) {
                i = skipQuoted(sql, i + 1, '\'', true);
            } else if (c == '"') {
                final int end = skipQuoted(sql, i, '"', false);
                tokens.add(new Token(Kind.QUOTED, i, end));
                i = end;
            } else if (isWordChar(c)) {
                final int start = i;
                while (i < n && isWordChar(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.WORD, start, i));
            } else {
                final Kind kind = switch (c) {
                    case '(' -> Kind.OPEN;
                    case ')' -> Kind.CLOSE;
                    case ',' -> Kind.COMMA;
                    case '.' -> Kind.DOT;
                    default -> Kind.OTHER;
                };
                tokens.add(new Token(kind, i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    /** The index just past the literal opened by {@code quote} at {@code open}; a doubled quote escapes itself. */
    private static int skipQuoted(String sql, int open, char quote, boolean backslashEscapes) {
        int i = open + 1;
        while (i < sql.length()) {
            final char c = sql.charAt(i);
            if (backslashEscapes && c == '\\') {
                i += 2;
            } else if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return sql.length();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
