package com.epam.aidial.analytics.service.query.translate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a masked column's stored value read outside its mask in rendered SQL (see
 * {@code openspec/specs/structured-query/spec.md}, "A masked field reads as absent at every reference"). Every reference
 * to the column's physical name must sit in one of the forms that read it behind its mask:
 *
 * <ul>
 *   <li>the masked scalar {@code if(m, if(m, c, defaultValueOfArgumentType(assumeNotNull(c))), NULL)};</li>
 *   <li>the masked composite {@code if(m, c, <empty value>)};</li>
 *   <li>a path inside a masked {@code object}, {@code if(m, c.<path>, NULL)};</li>
 *   <li>a conjunct in the pruning form, {@code m and <comparison on c>}.</li>
 * </ul>
 *
 * <p>Each mask condition renders once per scope as {@code (ifNull(<or>, 0) AS "__adas_mcond_<i>")} and is referenced by its
 * alias elsewhere, so the definitions are folded to their alias first. A reader that reads stored values on purpose (an
 * enrichment's derived table, say) is removed by the caller before the audit.
 */
final class MaskedSqlAudit {

    private static final String MASK = "\"__adas_mcond_\\d+\"";
    private static final Pattern DEFINITION = Pattern.compile("\\(ifNull\\((?:(?!AS \"__adas_mcond_).)*?, 0\\) AS (" + MASK + ")\\)",
            Pattern.DOTALL);

    private MaskedSqlAudit() {
    }

    /** {@code sql} without each derived table aliased {@code alias}, a reader of stored values the caller allows. */
    static String withoutDerivedTables(String sql, String alias) {
        final Pattern end = Pattern.compile("\\) \"" + Pattern.quote(alias) + "\"(?!\\.)");
        String text = sql;
        for (Matcher matcher = end.matcher(text); matcher.find(); matcher = end.matcher(text)) {
            int open = matcher.start();
            for (int depth = 0; open >= 0; open--) {
                final char c = text.charAt(open);
                if (c == ')') {
                    depth++;
                } else if (c == '(' && --depth == 0) {
                    break;
                }
            }
            text = text.substring(0, open) + "<derived>" + text.substring(matcher.start() + 1);
        }
        return text;
    }

    /** Each occurrence of a name of {@code physical} outside its mask, with the text around it; empty when there is none. */
    static List<String> unguarded(String sql, List<String> physical) {
        String text = DEFINITION.matcher(sql).replaceAll("$1");
        for (final String name : physical) {
            final String column = Pattern.quote(name);
            text = text.replaceAll("if\\(" + MASK + ", if\\(" + MASK + ", " + column + ", defaultValueOfArgumentType\\(assumeNotNull\\("
                    + column + "\\)\\)\\), NULL\\)", "<masked>");
            text = text.replaceAll("if\\(" + MASK + ", " + column + ", (\\[\\]|map\\(\\)|CAST\\('\\{\\}', 'JSON'\\))\\)", "<masked>");
            text = text.replaceAll("if\\(" + MASK + ", " + column + "(\\.\"[^\"]*\")+, NULL\\)", "<masked>");
            text = text.replaceAll(MASK + " and " + column, "<guarded>");
        }
        final List<String> found = new ArrayList<>();
        for (final String name : physical) {
            final Matcher matcher = Pattern.compile(Pattern.quote(name)).matcher(text);
            while (matcher.find()) {
                found.add("…" + text.substring(Math.max(0, matcher.start() - 80), Math.min(text.length(), matcher.end() + 40)) + "…");
            }
        }
        return found;
    }
}
