package com.epam.aidial.analytics.service.query.sql;

import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.parser.SqlParserUtil;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;

import java.io.StringReader;
import java.util.Locale;

/**
 * Identifies the reserved word behind a parse failure, so the message can name it and say how to fix it.
 *
 * <p>The case this exists for is a reserved word used where an identifier belonged — overwhelmingly, as an
 * unquoted alias. Calcite reports it accurately but incompletely: see
 * {@code SqlQueryFrontEnd#parseError} for why its diagnosis lands on the <em>preceding</em> token.
 *
 * <p><b>Detection is structural, not textual.</b> The two conditions are Calcite's own — the failure
 * stopped on a keyword, and an identifier would have been legal there — and the second is answered by
 * {@link SqlParserUtil#allowsIdentifier}, the very predicate the generated parser uses when deciding to
 * reformat the message in the first place. Matching on the rendered text instead would bind this to a
 * string Calcite is free to reword, which is the fragility {@code trimExpectations} already argues against
 * by anchoring on a marker rather than a line count.
 *
 * <p><b>The vocabulary comes from the grammar, not from a list.</b> {@code Metadata#isReservedWord} is the
 * Babel parser's own token set, so it cannot drift on a Calcite upgrade the way a hand-kept list would —
 * and it needs to be exact, because which words are reserved is genuinely unguessable ({@code rows} is;
 * {@code first}, {@code last}, {@code turns}, {@code tokens}, {@code spend} and {@code conversations} are
 * not). {@code isReservedWord} rather than {@code isKeyword}: the latter also covers non-reserved keywords,
 * which <em>are</em> legal as identifiers, so it would produce a hint for a word that parses fine.
 */
final class SqlReservedWords {

    /**
     * The Babel grammar's reserved words. Built once and lazily: constructing the metadata runs a parser to
     * collect the token set, and the generated parser caches the result in a static of its own, so the cost
     * is paid at most once per process — and only on the first parse failure.
     */
    private static final class Vocabulary {
        private static final SqlAbstractParserImpl.Metadata METADATA =
                SqlBabelParserImpl.FACTORY.getParser(new StringReader("")).getMetadata();
    }

    private SqlReservedWords() {}

    /**
     * A sentence naming the reserved word this failure is really about, or null when the failure is not of
     * that shape — in which case the caller reports Calcite's diagnosis unchanged, leaving an unrecognised
     * failure un-improved rather than mangled.
     */
    static String aliasHint(String sql, SqlParseException e) {
        if (sql == null || e == null || !identifierWasLegal(e)) {
            return null;
        }
        final SqlParserPos pos = e.getPos();
        if (pos == null) {
            return null;
        }
        final Word stopped = wordAt(sql, offsetOf(sql, pos.getLineNum(), pos.getColumnNum()));
        if (stopped == null) {
            return null;
        }
        // `AS` is itself reserved, so testing the stopped word first would name `AS` — precisely the wrong
        // token this exists to correct. When the parse stops there the alias production was not entered,
        // and the offender is the word after it. Every other shape (`count(*) rows`, `SELECT rows FROM t`,
        // `FROM t rows`) stops on the offender itself.
        final Word candidate = "AS".equalsIgnoreCase(stopped.text())
                ? wordAt(sql, stopped.endOffset())
                : stopped;
        return candidate != null && isReserved(candidate.text()) ? describe(candidate) : null;
    }

    /**
     * Whether the grammar would have accepted an identifier where the parse stopped. Both inputs survive
     * onto the exception, and a failure that carries neither (a lexical error, say) is simply not this
     * shape.
     */
    private static boolean identifierWasLegal(SqlParseException e) {
        final String[] tokenImages = e.getTokenImages();
        final int[][] expected = e.getExpectedTokenSequences();
        return tokenImages != null && expected != null && SqlParserUtil.allowsIdentifier(tokenImages, expected);
    }

    private static boolean isReserved(String word) {
        return Vocabulary.METADATA.isReservedWord(word.toUpperCase(Locale.ROOT));
    }

    /**
     * Worded to stay true even where the guess is off-target. The gate below fires on "a reserved word sits
     * where an identifier was legal", which is the alias case but also, say, {@code WHERE x = ORDER BY y}.
     * Stating what the word <em>is</em> — rather than asserting the caller meant it as an alias — keeps the
     * sentence correct in both, and the parser's own diagnosis still precedes it.
     */
    private static String describe(Word word) {
        return "'" + word.text() + "' at line " + word.line() + ", column " + word.column()
                + " is a reserved word and cannot be an unquoted identifier — rename it, or quote it as \""
                + word.text() + "\".";
    }

    /** A bare word read out of the statement text, with the 1-based position a caller can point at. */
    private record Word(String text, int line, int column, int endOffset) {}

    /**
     * The next bare word at or after {@code from}, skipping whitespace. Deliberately simple: it is only
     * ever asked about a position the parser already reported, and a candidate is discarded unless it turns
     * out to be reserved — so a miss produces silence, never a wrong hint.
     */
    private static Word wordAt(String sql, int from) {
        if (from < 0) {
            return null;
        }
        int i = from;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (i >= sql.length() || !(Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            return null;
        }
        final int start = i;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
                || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
            i++;
        }
        return new Word(sql.substring(start, i), lineOf(sql, start), columnOf(sql, start), i);
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
        final int lineStart = sql.lastIndexOf('\n', offset - 1);
        return offset - lineStart;
    }
}
