package com.epam.aidial.analytics.service.pipeline;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a stored SQL fragment for {@code table.column} references, without running the SQL front end.
 *
 * <p>The point is the cost, and it is the reason both kinds' guards use this rather than the parser they
 * validate with. The catalog's dependency questions — "which declarations read this enrichment?", "which
 * read this column?" — are asked over <b>every stored declaration</b>, from inside a table delete or a
 * schema patch. Re-translating each one would cost a front-end parse per declaration per question, and it
 * would also make one drifted declaration fail an operation about an unrelated table: a fragment that no
 * longer parses would throw instead of contributing no reference.
 *
 * <p>Precision is bought back by what the answer is used for. An enrichment column has exactly one
 * spelling in a declaration's namespace — the qualified flat name — so scanning for that shape finds
 * exactly the enrichment references, and a token that resolves to nothing is dropped by the caller's
 * entity lookup. A false positive therefore costs a spurious 409 on a table nobody is reading, and a
 * false negative is impossible for the shape the guards exist for.
 *
 * <p>The declaration being <em>registered</em> is always resolved properly, by the front end. This is for
 * reading the ones already stored.
 */
public final class QualifiedColumnTokens {

    /** A {@code table.column} reference, either half optionally double-quoted. */
    private static final Pattern QUALIFIED_NAME =
            Pattern.compile("\"?([a-z][a-z0-9_]*)\"?\\.\"?([a-z][a-z0-9_]*)\"?", Pattern.CASE_INSENSITIVE);

    /**
     * Any identifier, quoted or bare — the over-approximation {@link #identifiers} scans for. A leading
     * underscore is allowed, unlike in {@link #QUALIFIED_NAME}: the seeded system columns are spelled
     * {@code _ingested_at} and {@code _source_file}, and a pattern starting at a letter returned them
     * without it, so they never matched their own column names.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*", Pattern.CASE_INSENSITIVE);

    /**
     * The {@code INSERT INTO <target> (<columns>)} head of an {@code aggregate} SQL declaration, anchored at the start
     * and allowing leading comments. The column list is matched up to its first {@code )}, so a quoted target column
     * containing one leaves the remainder in the body — an over-approximation, never a miss.
     */
    private static final Pattern INSERT_PREFIX = Pattern.compile(
            "^\\s*(?:(?:--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)\\s*)*INSERT\\s+INTO\\s+"
                    + "(?:\"[^\"]*\"|[a-z_][a-z0-9_]*)(?:\\s*\\.\\s*(?:\"[^\"]*\"|[a-z_][a-z0-9_]*))?\\s*\\([^)]*\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private QualifiedColumnTokens() {
    }

    /**
     * Every identifier a SQL fragment mentions, lowercased — the answer to "which <b>source</b> columns
     * might this read", where {@link #of} answers it only for enrichments.
     *
     * <p>A source column has no qualified spelling: a stored expression reads it bare, as
     * {@code length(deployment)}, so the qualified scan cannot see it at all. A guard built on the qualified
     * scan alone therefore let a column an expression reads be renamed out from under it. This keeps the
     * class's bargain rather than breaking it — keywords, function names and string contents come back
     * too, and they are harmless for the same reason as before: the caller intersects with a table's real
     * column names, so a false positive costs a spurious refusal on a column nobody reads, and a false
     * negative is impossible because every column reference is an identifier.
     */
    public static Set<String> identifiers(String sql) {
        if (StringUtils.isBlank(sql)) {
            return Set.of();
        }
        final Set<String> tokens = new LinkedHashSet<>();
        final Matcher matcher = IDENTIFIER.matcher(sql);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    /** The {@code table.column} tokens a SQL fragment mentions, quoted or bare; empty for a blank one. */
    public static Set<String> of(String sql) {
        if (StringUtils.isBlank(sql)) {
            return Set.of();
        }
        final Set<String> tokens = new LinkedHashSet<>();
        final Matcher matcher = QUALIFIED_NAME.matcher(sql);
        while (matcher.find()) {
            tokens.add(matcher.group(1) + "." + matcher.group(2));
        }
        return tokens;
    }

    /**
     * The contents of every double-quoted identifier in a SQL fragment, as written and in order; empty for a blank one.
     *
     * <p>A small lexer rather than a pattern: a {@code "} inside a string literal ({@code 'a"b'}) or a comment
     * ({@code /* 5" screens *}{@code /}) would otherwise pair with the next identifier's opening quote and shift every
     * pair after it, so a reference such as {@code e."enr.col"} would be read inside-out and missed. Literals (with
     * {@code ''} escapes), {@code --} line comments and block comments are skipped; a {@code ""} inside an identifier
     * is an escaped quote. Unterminated text after an opening delimiter contributes nothing.
     */
    public static List<String> quoted(String sql) {
        if (StringUtils.isBlank(sql)) {
            return List.of();
        }
        final List<String> contents = new ArrayList<>();
        final int length = sql.length();
        int i = 0;
        while (i < length) {
            final char c = sql.charAt(i);
            if (c == '\'') {
                i = endOfDelimited(sql, i, '\'', null);
            } else if (c == '-' && sql.startsWith("--", i)) {
                final int newline = sql.indexOf('\n', i);
                i = newline < 0 ? length : newline + 1;
            } else if (c == '/' && sql.startsWith("/*", i)) {
                final int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? length : close + 2;
            } else if (c == '"') {
                final StringBuilder content = new StringBuilder();
                final int end = endOfDelimited(sql, i, '"', content);
                if (end <= length && !content.isEmpty()) {
                    contents.add(content.toString());
                }
                i = end;
            } else {
                i++;
            }
        }
        return contents;
    }

    /**
     * The index just past the closing {@code quote} of the delimited token opening at {@code start}, a doubled quote
     * being an escaped one; {@code sql.length() + 1} when it never closes. Its contents are appended to {@code into}
     * when given.
     */
    private static int endOfDelimited(String sql, int start, char quote, StringBuilder into) {
        int i = start + 1;
        while (i < sql.length()) {
            final char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    if (into != null) {
                        into.append(quote);
                    }
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            if (into != null) {
                into.append(c);
            }
            i++;
        }
        return sql.length() + 1;
    }

    /**
     * Every name an {@code aggregate} SQL declaration may read, by token scan and <b>never by parsing</b>: the union
     * of its {@link #of qualified tokens}, the contents of its double-quoted identifiers and its
     * {@link #identifiers bare identifiers}, all lowercased.
     *
     * <p>The quoted scan is what finds a reference spelled {@code alias."<enrichment>.<column>"}, which the qualified
     * pattern reads as {@code alias.<enrichment>}. All three run over the statement with its
     * {@code INSERT INTO <target> (<columns>)} head removed, so a target column name does not pin an input column of
     * the same name; when that head is not recognised the whole text is scanned, so the answer only ever
     * over-approximates. A name inside a string literal or a comment comes back from the bare and qualified scans too
     * — a spurious refusal, where a miss would let a column be dropped under a stored declaration.
     */
    public static Set<String> ofStatement(String sql) {
        if (StringUtils.isBlank(sql)) {
            return Set.of();
        }
        final String body = withoutInsertPrefix(sql);
        final Set<String> tokens = new LinkedHashSet<>();
        for (final String qualified : of(body)) {
            tokens.add(qualified.toLowerCase(Locale.ROOT));
        }
        for (final String content : quoted(body)) {
            tokens.add(content.toLowerCase(Locale.ROOT));
        }
        tokens.addAll(identifiers(body));
        return tokens;
    }

    /** {@code sql} without its leading {@code INSERT INTO <target> (<columns>)}, or unchanged when that is not recognised. */
    public static String withoutInsertPrefix(String sql) {
        final Matcher matcher = INSERT_PREFIX.matcher(sql);
        return matcher.find() ? sql.substring(matcher.end()) : sql;
    }
}
