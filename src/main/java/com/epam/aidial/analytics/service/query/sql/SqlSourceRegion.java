package com.epam.aidial.analytics.service.query.sql;

import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the original statement text between two parsed nodes.
 *
 * <p>Needed because Calcite's AST is lossy in the two places ClickHouse and standard SQL disagree about a
 * set operation: {@code UNION} and {@code UNION DISTINCT} parse to the <em>same</em> operator, and nothing
 * records whether a branch was parenthesized. Both facts are still in the text, bounded by the operands'
 * own {@link SqlParserPos}, so this class maps a position to an offset and reads the tokens in between.
 *
 * <p>The region between two operands can hold only whitespace, comments, parentheses and the operator's
 * own keywords — never a string literal — which is what makes reading it safe. Comments are blanked out
 * anyway, so a bare {@code UNION} followed by a block comment mentioning {@code ALL} is not read as
 * {@code UNION ALL}.
 */
final class SqlSourceRegion {

    /** The keywords that can open a trailing clause, in no particular order — the earliest match wins. */
    private static final String[] CLAUSE_KEYWORDS = {"ORDER", "LIMIT", "OFFSET", "FETCH"};

    private final String sql;
    /** Offset of the first character of each 1-based line. */
    private final int[] lineOffsets;

    SqlSourceRegion(String sql) {
        this.sql = sql;
        final List<Integer> offsets = new ArrayList<>();
        offsets.add(0); // unused slot 0, so line N is at index N
        offsets.add(0);
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        this.lineOffsets = offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The text strictly between where {@code left} ends and where {@code right} begins, with comments
     * replaced by a space. Empty when the positions are unusable (a synthesized node carries
     * {@link SqlParserPos#ZERO}), which callers treat as "cannot tell" rather than as a reading.
     */
    String between(SqlParserPos left, SqlParserPos right) {
        final int from = offsetOf(left.getEndLineNum(), left.getEndColumnNum() + 1);
        final int to = offsetOf(right.getLineNum(), right.getColumnNum());
        if (from < 0 || to < 0 || to <= from || to > sql.length()) {
            return "";
        }
        return stripComments(sql.substring(from, to));
    }

    /**
     * The text after {@code node} ends, to the end of the statement, with comments replaced by a space.
     * Empty when the position is unusable, which callers treat as "cannot tell".
     */
    String after(SqlParserPos node) {
        final int from = offsetOf(node.getEndLineNum(), node.getEndColumnNum() + 1);
        if (from < 0 || from > sql.length()) {
            return "";
        }
        return stripComments(sql.substring(from));
    }

    /**
     * The offset just past where {@code node} ends, in the text exactly as given — nothing stripped. Unlike
     * {@link #after}, which blanks comments without knowing about string literals, this reads no text at
     * all, so it is safe to cut a statement at. {@code -1} when the position is unusable.
     */
    int offsetAfter(SqlParserPos node) {
        final int from = offsetOf(node.getEndLineNum(), node.getEndColumnNum() + 1);
        return from < 0 || from > sql.length() ? -1 : from;
    }

    /**
     * Whether the node's own source text opens with {@code (} — which is how a parenthesized query block
     * is told from a bare one, the parse tree recording no parentheses of its own. Calcite's position for
     * a parenthesized query spans the parentheses, so the first character of the span is the answer.
     */
    boolean opensWithParen(SqlParserPos node) {
        final int from = offsetOf(node.getLineNum(), node.getColumnNum());
        return from >= 0 && from < sql.length() && sql.charAt(from) == '(';
    }

    /**
     * The index of the first {@code ORDER}/{@code LIMIT}/{@code OFFSET}/{@code FETCH} word in a region, or
     * {@code -1}. Matched on word boundaries, so a longer identifier ending in one of them does not count.
     */
    static int firstClauseKeyword(String region) {
        // Matched in place with regionMatches rather than against an uppercased copy: toUpperCase is not
        // length-preserving for every character ('ß' becomes "SS"), so an index found in the copy can sit
        // past its true position in the original — and callers use the index to substring the original.
        for (int at = 0; at < region.length(); at++) {
            for (final String keyword : CLAUSE_KEYWORDS) {
                final int end = at + keyword.length();
                if (end <= region.length() && region.regionMatches(true, at, keyword, 0, keyword.length())
                        && isWord(region, at, end)) {
                    return at;
                }
            }
        }
        return -1;
    }

    /** Whether the half-open range is bounded by non-identifier characters on both sides. */
    private static boolean isWord(String region, int from, int to) {
        final boolean beforeOk = from == 0 || !isIdentifierChar(region.charAt(from - 1));
        final boolean afterOk = to >= region.length() || !isIdentifierChar(region.charAt(to));
        return beforeOk && afterOk;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * The uppercased word tokens of a region, in order, with every other character treated as a separator.
     * Punctuation is dropped, so {@code ") UNION ALL ("} reads as {@code [UNION, ALL]}.
     */
    static List<String> words(String region) {
        final List<String> words = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        for (int i = 0; i < region.length(); i++) {
            final char c = region.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                current.append(c);
            } else if (!current.isEmpty()) {
                words.add(current.toString().toUpperCase(Locale.ROOT));
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            words.add(current.toString().toUpperCase(Locale.ROOT));
        }
        return words;
    }

    /**
     * Comments blanked out, so neither a keyword nor a closing parenthesis inside one is read as code.
     * Replaced by a space rather than removed, so nothing accidentally joins across the gap.
     */
    private static String stripComments(String region) {
        final StringBuilder out = new StringBuilder(region.length());
        int i = 0;
        while (i < region.length()) {
            if (region.startsWith("--", i)) {
                final int end = region.indexOf('\n', i);
                i = end < 0 ? region.length() : end;
                out.append(' ');
            } else if (region.startsWith("/*", i)) {
                final int end = region.indexOf("*/", i + 2);
                i = end < 0 ? region.length() : end + 2;
                out.append(' ');
            } else {
                out.append(region.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** The offset of a 1-based line/column, or {@code -1} when the position is not in the text. */
    private int offsetOf(int line, int column) {
        if (line < 1 || line >= lineOffsets.length || column < 1) {
            return -1;
        }
        final int offset = lineOffsets[line] + column - 1;
        return offset > sql.length() ? -1 : offset;
    }
}
