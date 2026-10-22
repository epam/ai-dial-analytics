package com.epam.aidial.analytics.service.query.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one rejection message into a {@link SqlIssue} with a stable {@code code} and, where the
 * classification implies one, a concrete remedy.
 *
 * <p><b>Why classify the message rather than tag the throw sites.</b> The rejections this must cover are
 * raised from some two hundred places across the front end, the Rel translation and the builder, and a
 * third of them are Calcite's own — worded by the library, not by ADAS. Threading a code through all of
 * them would be a large change to the execute path for the benefit of a read-only endpoint, and would
 * still not reach the Calcite messages. Classifying here keeps the execute path untouched.
 *
 * <p><b>The cost of that choice is drift, and it is bounded deliberately.</b> An unrecognised message
 * classifies as {@link #FALLBACK_CODE} with its text intact and no remedy, so a reworded diagnosis
 * degrades to "valid: false plus the original message" — exactly what the caller would have read from
 * {@code execute-sql} — and never to a wrong code or misleading advice. Every pattern here is pinned by
 * {@code SqlIssueClassifierTest} against the real message, so a Calcite upgrade that rewords one fails
 * the build rather than silently falling back.
 */
final class SqlIssueClassifier {

    /** The code for a diagnosis no pattern matches: honest, positionless, and advice-free. */
    static final String FALLBACK_CODE = "invalid_sql";

    /**
     * The marker {@link SqlReservedWords#aliasHint} appends. Matching the hint rather than re-deriving it
     * keeps one source for the detection: the hint is produced structurally from Calcite's own
     * "an identifier was legal here" predicate, and re-testing that here could disagree with it.
     */
    private static final String RESERVED_WORD_MARKER = "is a reserved word and cannot be an unquoted identifier";

    /** {@code at line L, column C} — Calcite's position wording, and the hint's. */
    private static final Pattern POSITION = Pattern.compile("at line (\\d+), column (\\d+)");

    /**
     * {@link SqlNestedTrailingClause}'s sentence, with the block's position captured. Matched on its own
     * pattern rather than through {@link #POSITION}: Calcite's half of the message does not always carry a
     * position of its own ({@code Expected query or join} carries none), so counting positions could read
     * the block's as Calcite's. Matching the hint rather than re-deriving the detection is the same choice
     * {@link #RESERVED_WORD_MARKER} makes, and for the same reason.
     */
    private static final Pattern NESTED_BLOCK = Pattern.compile(
            "The parenthesis at line (\\d+), column (\\d+) opens a block");

    /** The fix for that shape — two spellings, both stated in the message itself. */
    private static final String NESTED_BLOCK_REMEDY =
            "parenthesise the block's last query too, or leave its first one unparenthesised";

    /**
     * The classification table, first match wins. Ordered most specific first: several Calcite messages
     * share a prefix, and the arity message would otherwise be caught by the broader function pattern.
     */
    private static final List<Rule> RULES = List.of(
            // ---- statement shape, from SqlQueryFrontEnd.screen
            new Rule("not_a_select", Pattern.compile("^only SELECT statements are supported"),
                    "submit a single read-only SELECT"),
            new Rule("multiple_statements", Pattern.compile("^exactly one SQL statement is required"),
                    "submit one statement, with no trailing semicolon-separated second"),
            new Rule("empty_statement", Pattern.compile("^SQL statement must not be empty"), null),

            // ---- catalog resolution
            new Rule("unknown_entity", Pattern.compile("Object '([^']+)' not found"),
                    "list the queryable entities with GET /v1/queries/entities"),
            new Rule("unknown_column", Pattern.compile("Column '([^']+)' not found in "),
                    "list the entity's fields with GET /v1/queries/entities/schema/{entity}"),
            // A column the SELECT list names and nothing resolves reads as a self-reference, because the
            // unnamed-item alias injector has already given the item that same name. The message is
            // Calcite's and describes the injected alias, not the caller's mistake — so it is classified
            // as what it actually is.
            new Rule("unknown_column", Pattern.compile("The definition of column '([^']+)' depends on itself"),
                    "list the entity's fields with GET /v1/queries/entities/schema/{entity}"),
            new Rule("ambiguous_column", Pattern.compile("Column '([^']+)' is ambiguous"),
                    "qualify the column with its table alias"),
            new Rule("unknown_field", Pattern.compile("^unknown field '([^']+)'"),
                    "list the entity's fields with GET /v1/queries/entities/schema/{entity}"),

            // ---- the function catalog
            new Rule("function_arity", Pattern.compile("Invalid number of arguments to function '([^']+)'"),
                    "check the signature in GET /v1/queries/sql-functions — count takes one argument, so write count(*)"),
            new Rule("unknown_function", Pattern.compile("No match found for function signature (\\w+)"),
                    "only the spellings in GET /v1/queries/sql-functions are available"),
            new Rule("unsupported_function", Pattern.compile("^unsupported function '([^']+)' in query expression"),
                    "only the spellings in GET /v1/queries/sql-functions are available"),

            // ---- governance, from StructuredQueryBuilder
            new Rule("limit_above_maximum", Pattern.compile("^page limit (\\d+) exceeds the maximum of (\\d+) rows"),
                    "lower the LIMIT to the maximum and page with OFFSET"),
            new Rule("negative_limit", Pattern.compile("^page limit must not be negative"), null),

            // ---- the type restrictions
            new Rule("projection_only_field", Pattern.compile("field '([^']+)' can only be projected"),
                    "project the field and filter or group on a scalar column instead"),
            // Before the cast rule: a branch pair this surface will not unify is a type problem of its
            // own, and the caller wrote no CAST to drop. Its own message carries the remedy.
            new Rule("branch_type_mismatch",
                    Pattern.compile("^the branches of a set operation give column"), null),
            new Rule("unsupported_cast", Pattern.compile("^CAST from (\\S+) to (\\S+) is not supported"),
                    "drop the CAST — compare against a literal of the column's own type, e.g. TIMESTAMP '…'"),
            new Rule("not_grouped", Pattern.compile("Expression '([^']+)' is not being grouped"),
                    "add the expression to GROUP BY, or wrap it in an aggregate"),

            // ---- unsupported constructs. LogicalValues is the IN-list case and is matched first: Calcite
            // rewrites an IN list longer than its sub-query threshold into a VALUES relation, so the
            // statement is refused for a construct the caller never wrote.
            new Rule("in_list_too_long", Pattern.compile("SQL construct 'LogicalValues' is not supported"),
                    "an IN list longer than 20 literals becomes a sub-query; split it into batches of 20 or fewer"),
            // Set operations execute, so the rule that classified their blanket refusal is gone with the
            // message it matched. What can still refuse one is a spelling the database does not share: a
            // bare UNION, whose default mode the database has none of, and MINUS, which it has no keyword
            // for. Both carry the remedy in the message, so neither needs one here.
            new Rule("unsupported_construct", Pattern.compile("^UNION requires ALL or DISTINCT"), null),
            new Rule("unsupported_construct", Pattern.compile("^MINUS is not supported"), null),
            new Rule("unsupported_construct",
                    Pattern.compile("^an ORDER BY, LIMIT or OFFSET after a set operation"), null),
            new Rule("unsupported_construct", Pattern.compile("^recursive common table expressions"), null),
            new Rule("unsupported_construct", Pattern.compile("SQL construct '([^']+)' (is not supported"
                    + "|cannot be expressed in the structured query DSL)"), null),
            new Rule("unsupported_construct", Pattern.compile("is not supported by the query translator"), null),

            // ---- parse failures. Last, because a parse message can quote a construct name in passing.
            new Rule("parse_error", Pattern.compile("^SQL parse error: "), null));

    private SqlIssueClassifier() {}

    /** Classifies {@code message}, never returning null — an unmatched diagnosis becomes the fallback. */
    static SqlIssue classify(String message) {
        final String text = message == null ? "" : message;
        // Before everything else, including the reserved-word case it stands in for: the parse stopped on
        // the trailing clause, which is the one part of the statement that is correct, so the position
        // worth publishing is the block's rather than Calcite's. The code stays `parse_error` — the
        // statement did fail to parse — and the remedy carries what the caller can do about it.
        final Matcher nested = NESTED_BLOCK.matcher(text);
        if (nested.find()) {
            return new SqlIssue("parse_error", text, Integer.parseInt(nested.group(1)),
                    Integer.parseInt(nested.group(2)), NESTED_BLOCK_REMEDY);
        }
        final List<Position> positions = positions(text);
        if (isReservedWordAlias(text, positions)) {
            // The hint names the real offender; Calcite's own position, reported first, is the token it
            // stopped on — three characters short of it in the alias case this exists to correct.
            return at("reserved_word_alias", text, positions.get(1),
                    "rename the identifier, or double-quote it");
        }
        final Position first = positions.isEmpty() ? null : positions.getFirst();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return at(rule.code(), text, first, rule.remedy());
            }
        }
        return at(FALLBACK_CODE, text, first, null);
    }

    /**
     * Whether the failure is a reserved word standing where an identifier belonged — the unquoted-alias
     * case — as opposed to a reserved word Calcite merely stopped on.
     *
     * <p>The two are told apart by position, not by a keyword list. {@link SqlReservedWords#aliasHint}
     * fires on any parse failure where an identifier would have been legal, so it also fires on a
     * truncated statement ({@code … dial_usage_log WHERE}), where the word it names is the very keyword
     * the parser stopped on. In the alias case the offender is a <em>different</em> word than the one
     * Calcite reports — that mis-anchoring is the whole reason the hint exists — so two distinct positions
     * mean the hint located something Calcite's diagnosis did not, and one position means it only echoed
     * it. Classifying the echo as an alias problem would advise a caller to rename their {@code WHERE}.
     */
    private static boolean isReservedWordAlias(String message, List<Position> positions) {
        return positions.size() > 1
                && message.contains(RESERVED_WORD_MARKER)
                && !positions.get(0).equals(positions.get(1));
    }

    /** Every {@code at line L, column C} in the message, in order: Calcite's first, then the hint's. */
    private static List<Position> positions(String message) {
        final Matcher matcher = POSITION.matcher(message);
        final List<Position> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(new Position(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
        }
        return found;
    }

    private static SqlIssue at(String code, String message, Position position, String remedy) {
        return position == null
                ? SqlIssue.of(code, message, remedy)
                : new SqlIssue(code, message, position.line(), position.column(), remedy);
    }

    /** A 1-based position in the submitted statement. */
    private record Position(int line, int column) {}

    /** One classification: the code and remedy to publish when {@code pattern} is found in the message. */
    private record Rule(String code, Pattern pattern, String remedy) {}
}
