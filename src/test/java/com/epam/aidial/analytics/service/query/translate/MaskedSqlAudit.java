package com.epam.aidial.analytics.service.query.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a masked column's stored value read outside its own mask in rendered SQL (see
 * {@code openspec/specs/structured-query/spec.md}, "A masked field reads as absent at every reference"). Every reference
 * to the column must sit in one of the forms that read it behind the mask of that column:
 *
 * <ul>
 *   <li>the masked scalar {@code if(m, if(m, c, defaultValueOfArgumentType(assumeNotNull(c))), NULL)}, and the same form
 *       around a pushed call read back from an enrichment's derived table, whose last branch is the call's absent value;</li>
 *   <li>the then-branch of an {@code if} on the mask, {@code if(m, c, …)}: a masked composite, an object path inside a
 *       masked {@code object}, a pushed call read back from an enrichment's derived table;</li>
 *   <li>a conjunct in the pruning form, {@code … and m and <comparison on c>}, the mask a conjunct of its own.</li>
 * </ul>
 *
 * <p>Each mask condition renders once per scope as {@code (ifNull(<condition>, 0) AS "__adas_mcond_<i>")} and is referenced by
 * its alias elsewhere. Only the definitions of the conditions the caller names are folded to their alias, and each column is
 * matched against the aliases of its own condition only, so a read behind another column's mask is found too. A reader that
 * reads stored values on purpose (an enrichment's derived table, say) is removed by the caller before the audit.
 */
final class MaskedSqlAudit {

    private static final String NONE = "(?!)";

    private MaskedSqlAudit() {
    }

    /** A masked column: {@code column}, a pattern matching its physical name, read behind {@code condition}'s mask. */
    record Masked(String column, String condition) {

        /** A column by its literal physical name. */
        static Masked of(String physical, String condition) {
            return new Masked(Pattern.quote(physical), condition);
        }
    }

    /** Each read of a column of {@code masked} outside its own mask, with the text around it; empty when there is none. */
    static List<String> unguarded(String sql, List<Masked> masked) {
        final Map<String, Set<String>> aliases = new LinkedHashMap<>();
        String text = sql;
        for (final Masked column : masked) {
            if (aliases.containsKey(column.condition())) {
                continue;
            }
            final Set<String> found = new LinkedHashSet<>();
            final Matcher definition = Pattern.compile("\\(ifNull\\(" + Pattern.quote(column.condition()) + ", 0\\) AS (\"__adas_mcond_\\d+\")\\)")
                    .matcher(text);
            final StringBuilder folded = new StringBuilder();
            while (definition.find()) {
                found.add(definition.group(1));
                definition.appendReplacement(folded, Matcher.quoteReplacement(definition.group(1)));
            }
            definition.appendTail(folded);
            text = folded.toString();
            aliases.put(column.condition(), found);
        }
        for (final Masked column : masked) {
            final Set<String> own = aliases.get(column.condition());
            final String mask = own.isEmpty() ? NONE : "(" + String.join("|", own.stream().map(Pattern::quote).toList()) + ")";
            final String c = "(?:" + column.column() + ")";
            // The scalar form, and a pushed call's read-back, whose absent branch is the call over an absent value.
            text = text.replaceAll("if\\(" + mask + ", if\\(\\1, " + c + ", defaultValueOfArgumentType\\(assumeNotNull\\(" + c + "\\)\\)\\)",
                    "<masked>");
            text = text.replaceAll("if\\(" + mask + ", " + c + "(?:\\.\"[^\"]*\")*(?=[,)])", "<masked>");
            text = text.replaceAll("(?<=\\(| and )" + mask + " and " + c, "<guarded>");
        }
        final List<String> leaks = new ArrayList<>();
        for (final Masked column : masked) {
            final Matcher read = Pattern.compile(column.column()).matcher(text);
            while (read.find()) {
                leaks.add("…" + text.substring(Math.max(0, read.start() - 80), Math.min(text.length(), read.end() + 40)) + "…");
            }
        }
        return leaks;
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
}
