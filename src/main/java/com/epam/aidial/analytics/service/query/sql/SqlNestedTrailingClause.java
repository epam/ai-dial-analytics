package com.epam.aidial.analytics.service.query.sql;

import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.Locale;
import java.util.Set;

/**
 * Names the one grammar gap a caller can hit by combining two things this surface documents, and says
 * what to write instead.
 *
 * <p><b>The gap.</b> A parenthesized query block — a derived table, a CTE body, a subquery operand —
 * cannot both begin with a {@code (} of its own and carry a trailing {@code ORDER BY}/{@code LIMIT}/
 * {@code OFFSET}/{@code FETCH}. So
 * {@code SELECT count(*) FROM ((SELECT a FROM t) UNION ALL SELECT a FROM u LIMIT 2)} does not parse,
 * while the same statement with the first branch bare, with the last branch parenthesized, or with no
 * trailing clause at all, all do. It is not about set operations: a plain
 * {@code FROM ((SELECT a FROM t) ORDER BY a)} and a parenthesized join with a trailing {@code LIMIT}
 * fail the same way, and a block whose content begins with {@code WITH} instead of {@code (} does not.
 *
 * <p><b>Why.</b> Calcite's grammar offers two readings for the text inside a parenthesis
 * ({@code ExprOrJoinOrOrderedQuery}, in the generated {@code SqlBabelParserImpl}): an ordered query,
 * which accepts a trailing clause, and a join-or-set-operation list, which does not. The choice is made
 * on lookahead, and only the first reading can start at a {@code (} — so a block whose content opens
 * with a parenthesis is committed to the reading that has nowhere to put the clause. Nothing in ADAS
 * runs yet: the statement dies in the generated parser, before {@link SqlSetOperations} sees a tree.
 *
 * <p><b>Why this is worth a sentence of its own.</b> Left alone, the failure arrives as
 * {@code Incorrect syntax near the keyword 'LIMIT'} plus
 * {@link SqlReservedWords}' inference that {@code LIMIT} is a reserved word wanting quotes — advice that
 * is true in general and useless here, because quoting it produces a different statement. Both halves
 * point at the clause, which is the one part of the text that is fine. The block is what is wrong, and
 * it is two nested parentheses back.
 *
 * <p><b>Detection is structural, and then verified.</b> Three facts about the text, none of them an
 * inference from it: the parse stopped on a word that opens a trailing clause; the innermost parenthesis
 * still open at that point is immediately followed by another that itself opens a query; and deleting the
 * clause makes what is left parse ({@link #clauseIsWhatBreaksIt}). The text is read with a scanner that
 * tracks quoting and comments, so a {@code (} inside a string literal or a comment neither opens a block
 * nor appears to start one.
 *
 * <p>The third fact is what keeps this from taking a failure that belongs to {@link SqlReservedWords}.
 * {@code FROM ((SELECT a FROM t) UNION ALL SELECT limit FROM u)} stops the parse on {@code limit}, inside
 * a block of exactly this shape, and there the reserved-word sentence is the right answer — so a claim
 * resting on the first two facts alone would replace a correct message with a wrong one. Verifying rather
 * than guessing also means a mis-scan costs nothing: the probe it builds does not parse, and nothing is
 * claimed. Where any fact is missing this returns null and the caller falls back to
 * {@link SqlReservedWords}, leaving an unrecognised failure exactly as it was.
 */
final class SqlNestedTrailingClause {

    /** The words that open a trailing clause. {@code ORDER} is the only one needing a second word. */
    private static final Set<String> CLAUSE_OPENERS = Set.of("ORDER", "LIMIT", "OFFSET", "FETCH");

    /** The words a query block can begin with, which is what tells a block from a value list. */
    private static final String[] QUERY_OPENERS = {"SELECT", "WITH", "VALUES", "TABLE"};

    private SqlNestedTrailingClause() {}

    /**
     * A sentence naming the block behind this failure and the two spellings that work, or null when the
     * failure is not of this shape.
     */
    static String hint(String sql, SqlParseException e) {
        if (sql == null || e == null) {
            return null;
        }
        final SqlParserPos pos = e.getPos();
        if (pos == null) {
            return null;
        }
        final int stopped = offsetOf(sql, pos.getLineNum(), pos.getColumnNum());
        final String clause = clauseOpenerAt(sql, stopped);
        if (clause == null) {
            return null;
        }
        final int block = innermostOpenParen(sql, stopped);
        if (block < 0) {
            return null;
        }
        final int inner = firstNonBlank(sql, block + 1);
        if (inner < 0 || sql.charAt(inner) != '(' || !opensQuery(sql, inner + 1)
                || !clauseIsWhatBreaksIt(sql, stopped, block)) {
            return null;
        }
        return "The parenthesis at line " + lineOf(sql, block) + ", column " + columnOf(sql, block)
                + " opens a block that starts with another '(' and carries a trailing " + clause
                + ", which this parser cannot read together — parenthesise the block's last query too,"
                + " as in ((A) UNION ALL (B ORDER BY x LIMIT 2)), or leave its first one unparenthesised.";
    }

    /**
     * The trailing-clause keyword the parse stopped on, or null. {@code ORDER} counts only when {@code BY}
     * follows it, so a reserved-word misuse such as {@code SELECT order FROM t} — where the failure lands
     * on the same word but {@link SqlReservedWords} has the better answer — is not claimed here.
     */
    private static String clauseOpenerAt(String sql, int from) {
        if (from < 0 || from >= sql.length()) {
            return null;
        }
        final int start = skipToWord(sql, from);
        if (start < 0) {
            return null;
        }
        int end = start;
        while (end < sql.length() && isIdentifierChar(sql.charAt(end))) {
            end++;
        }
        final String word = sql.substring(start, end).toUpperCase(Locale.ROOT);
        if (!CLAUSE_OPENERS.contains(word)) {
            return null;
        }
        if (!"ORDER".equals(word)) {
            return word;
        }
        final int next = skipToWord(sql, end);
        return next >= 0 && sql.regionMatches(true, next, "BY", 0, 2)
                && (next + 2 >= sql.length() || !isIdentifierChar(sql.charAt(next + 2)))
                ? "ORDER BY" : null;
    }

    /** The first offset at or after {@code from} that begins a bare word, or -1. */
    private static int skipToWord(String sql, int from) {
        int i = from;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i < sql.length() && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_') ? i : -1;
    }

    /**
     * The offset of the innermost parenthesis still unclosed at {@code before}, or -1 when none is. The
     * scan skips string literals, quoted identifiers and comments, so their contents cannot open or close
     * a block; an unterminated one swallows the rest of the text, which simply yields no finding.
     */
    private static int innermostOpenParen(String sql, int before) {
        final int[] open = new int[Math.min(before, sql.length()) + 1];
        int depth = 0;
        int i = 0;
        final int end = Math.min(before, sql.length());
        while (i < end) {
            final char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = closingQuote(sql, i, c);
            } else if (sql.startsWith("--", i)) {
                final int newline = sql.indexOf('\n', i);
                i = newline < 0 ? sql.length() : newline + 1;
            } else if (sql.startsWith("/*", i)) {
                final int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? sql.length() : close + 2;
            } else {
                if (c == '(') {
                    open[depth++] = i;
                } else if (c == ')' && depth > 0) {
                    depth--;
                }
                i++;
            }
        }
        return depth > 0 ? open[depth - 1] : -1;
    }

    /**
     * The offset just past the literal or quoted identifier opening at {@code at}. A doubled quote is the
     * escape in the ordinary spellings; Babel also lexes a C-style literal ({@code e'…\'…'}), where a
     * backslash escapes the next character instead, so the {@code e} prefix selects which rule applies.
     */
    private static int closingQuote(String sql, int at, char quote) {
        final boolean cStyle = quote == '\'' && isCstylePrefix(sql, at);
        int i = at + 1;
        while (i < sql.length()) {
            if (cStyle && sql.charAt(i) == '\\') {
                i += 2;
            } else if (sql.charAt(i) != quote) {
                i++;
            } else if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                i += 2;
            } else {
                return i + 1;
            }
        }
        return sql.length();
    }

    /**
     * Whether the quote at {@code at} is preceded by the {@code e} of a C-style literal — the letter
     * standing on its own, so the {@code e} ending an identifier ({@code table e'x'} is a literal,
     * {@code alias'x'} is not a thing) does not make one.
     */
    private static boolean isCstylePrefix(String sql, int at) {
        if (at == 0 || (sql.charAt(at - 1) != 'e' && sql.charAt(at - 1) != 'E')) {
            return false;
        }
        return at - 1 == 0 || !isIdentifierChar(sql.charAt(at - 2));
    }

    /**
     * The offset of the first character at or after {@code from} that is neither whitespace nor part of a
     * comment, or -1 when the text ends first.
     */
    private static int firstNonBlank(String sql, int from) {
        int i = Math.max(from, 0);
        while (i < sql.length()) {
            final char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (sql.startsWith("--", i)) {
                final int newline = sql.indexOf('\n', i);
                i = newline < 0 ? sql.length() : newline + 1;
            } else if (sql.startsWith("/*", i)) {
                final int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? sql.length() : close + 2;
            } else {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether what follows is a query rather than a value — the difference between the block this exists
     * for and a parenthesized value list, which the character scan alone cannot tell apart. Without this,
     * {@code WHERE a IN ((1), (2) LIMIT 3)} satisfies both structural facts and would be told to
     * reparenthesize a list that contains no queries at all.
     */
    private static boolean opensQuery(String sql, int from) {
        final int at = firstNonBlank(sql, from);
        if (at < 0) {
            return false;
        }
        if (sql.charAt(at) == '(') {
            return true; // A deeper nesting; the branch itself is parenthesized again.
        }
        for (final String keyword : QUERY_OPENERS) {
            final int end = at + keyword.length();
            if (sql.regionMatches(true, at, keyword, 0, keyword.length())
                    && (end >= sql.length() || !isIdentifierChar(sql.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the trailing clause is what the block cannot read — confirmed, not inferred, by deleting the
     * clause and parsing what is left.
     *
     * <p>The two structural facts say the text <em>looks like</em> this shape; they cannot say the clause is
     * the reason the parse failed. A reserved clause keyword written as an identifier
     * ({@code FROM ((SELECT a FROM t) UNION ALL SELECT limit FROM u)}) stops the parse on the same word,
     * inside the same kind of block, and there {@link SqlReservedWords} has the right answer — so claiming
     * the shape would replace a correct sentence with a wrong one. Deleting from the stop position to the
     * block's own closing parenthesis separates the two: for the real shape what remains is the block
     * without its trailing clause, which parses; for the identifier it leaves a truncated {@code SELECT},
     * which does not.
     *
     * <p>One extra parse, of a statement that has already failed to parse, in exchange for a claim that is
     * verified rather than guessed. It also makes a mis-scan harmless: if the scanner mistook the block,
     * the probe it builds does not parse and nothing is claimed.
     */
    private static boolean clauseIsWhatBreaksIt(String sql, int stopped, int block) {
        final int close = matchingParen(sql, block);
        if (close < 0 || close < stopped) {
            return false;
        }
        final String probe = sql.substring(0, stopped) + sql.substring(close);
        try {
            SqlParser.create(probe, SqlQueryFrontEnd.PARSER_CONFIG).parseStmtList();
            return true;
        } catch (SqlParseException ignored) {
            return false;
        }
    }

    /**
     * The offset of the {@code )} closing the parenthesis at {@code open}, or -1 when the text ends first.
     * Quoting and comments are skipped, exactly as in {@link #innermostOpenParen}.
     */
    private static int matchingParen(String sql, int open) {
        int depth = 0;
        int i = open;
        while (i < sql.length()) {
            final char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = closingQuote(sql, i, c);
            } else if (sql.startsWith("--", i)) {
                final int newline = sql.indexOf('\n', i);
                i = newline < 0 ? sql.length() : newline + 1;
            } else if (sql.startsWith("/*", i)) {
                final int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? sql.length() : close + 2;
            } else {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    return i;
                }
                i++;
            }
        }
        return -1;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** The 0-based offset of a 1-based (line, column), or -1 when the statement is shorter than that. */
    private static int offsetOf(String sql, int line, int column) {
        int offset = 0;
        for (int remaining = line - 1; remaining > 0; remaining--) {
            offset = sql.indexOf('\n', offset);
            if (offset < 0) {
                return -1;
            }
            offset++;
        }
        final int target = offset + column - 1;
        return target <= sql.length() ? target : -1;
    }

    private static int lineOf(String sql, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (sql.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int columnOf(String sql, int offset) {
        return offset - sql.lastIndexOf('\n', offset - 1);
    }
}
