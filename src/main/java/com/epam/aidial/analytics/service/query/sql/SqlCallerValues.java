package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.CallerValues;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlBasicVisitor;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL spelling of a caller value: {@code caller.user_hash()} and {@code caller.project()} (see the
 * sql-query-translation spec, "Caller values in SQL").
 *
 * <p>Calcite cannot resolve the qualified name from an operator table, and a string literal standing in for the value
 * would be folded by the planner in {@code HAVING} and {@code ON}. So a user statement has each call rewritten, before
 * parsing, to an operator of the same length whose name swaps the dot for an underscore — {@code caller_user_hash()} —
 * which {@link SqlQueryOperators} registers as non-deterministic, dynamic and nullable, so nothing folds it. The
 * rewrite skips literals, quoted identifiers and comments, needs the name whole (not after a name character or a dot,
 * with no whitespace or comment inside it) and needs {@code (} to follow, after whitespace only. Equal length keeps
 * every position the parser reports.
 *
 * <p>The internal names are markers. Every statement the front end parses has each call of one checked against the
 * positions where the caller wrote {@code caller.user_hash()} — read off the statement as first parsed, before the
 * rewrite — and any other is refused as an unknown function, whatever its spelling (any letter case, quoted,
 * Unicode-escaped) and whether or not the statement also holds a legitimate call. A statement nothing was rewritten in,
 * which is every pipeline declaration, admits none.
 */
final class SqlCallerValues {

    /** The operator {@code caller.user_hash()} is rewritten to. */
    static final String USER_HASH_OPERATOR = "CALLER_USER_HASH";

    /** The operator {@code caller.project()} is rewritten to. */
    static final String PROJECT_OPERATOR = "CALLER_PROJECT";

    private static final Map<String, String> CALLER_VALUE_OF = Map.of(
            USER_HASH_OPERATOR, CallerValues.USER_HASH,
            PROJECT_OPERATOR, CallerValues.PROJECT);

    /** How Calcite names the operator of a rewrite-produced call in an arity error, the one message that names it. */
    private static final Pattern MARKER_NAME = Pattern.compile(
            "(?<=function ')(?i)(" + USER_HASH_OPERATOR + "|" + PROJECT_OPERATOR + ")(?=')");

    private SqlCallerValues() {
    }

    /**
     * {@code sql} with every caller-value call rewritten; the first caller value it names, or {@code null} when none
     * was; and where the caller wrote each call, as parser positions.
     */
    record Rewrite(String sql, String firstCallerValue, Set<List<Integer>> written) {

        /** A statement read without caller values: nothing rewritten, no call admitted. */
        static Rewrite none(String sql) {
            return new Rewrite(sql, null, Set.of());
        }

        boolean applied() {
            return firstCallerValue != null;
        }

        /** Refuses every call of an internal operator in {@code parsed} that the rewrite did not produce. */
        void rejectForgedCalls(SqlNode parsed) {
            forEachCall(parsed, id -> {
                if (id.isSimple() && isMarker(id.getSimple()) && !written.contains(position(id.getParserPosition()))) {
                    throw unknownFunction(id.getSimple());
                }
            });
        }

        /** {@code message} with the internal operator an arity error names restored to the caller-value spelling. */
        String restoreNames(String message) {
            if (!applied() || message == null) {
                return message;
            }
            final Matcher matcher = MARKER_NAME.matcher(message);
            final StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out,
                        Matcher.quoteReplacement(CALLER_VALUE_OF.get(matcher.group(1).toUpperCase(Locale.ROOT))));
            }
            return matcher.appendTail(out).toString();
        }
    }

    /**
     * Rewrites each caller-value call in {@code sql} to its internal operator; {@code screened} is the same text as the
     * parser first read it, from which the positions of the caller's own calls are taken.
     */
    static Rewrite rewrite(String sql, SqlNode screened) {
        final List<SqlFinalModifier.Token> tokens = SqlFinalModifier.tokenize(sql);
        final StringBuilder out = new StringBuilder(sql);
        String first = null;
        for (int i = 0; i + 3 < tokens.size(); i++) {
            final String name = callerValueAt(sql, tokens, i);
            if (name != null) {
                out.setCharAt(tokens.get(i + 1).start(), '_');
                first = first == null ? name : first;
            }
        }
        if (first == null) {
            return Rewrite.none(sql);
        }
        final Set<List<Integer>> written = new HashSet<>();
        forEachCall(screened, id -> {
            if (id.names.size() == 2 && "caller".equalsIgnoreCase(id.names.getFirst())
                    && CallerValues.NAMES.contains("caller." + id.names.getLast().toLowerCase(Locale.ROOT))) {
                written.add(position(id.getParserPosition()));
            }
        });
        return new Rewrite(out.toString(), first, Set.copyOf(written));
    }

    /**
     * The caller value whose call starts at token {@code i} — {@code caller}, {@code .}, the name, written adjacent,
     * then {@code (} after whitespace only — or {@code null}.
     */
    private static String callerValueAt(String sql, List<SqlFinalModifier.Token> tokens, int i) {
        final SqlFinalModifier.Token caller = tokens.get(i);
        final SqlFinalModifier.Token dot = tokens.get(i + 1);
        final SqlFinalModifier.Token name = tokens.get(i + 2);
        final SqlFinalModifier.Token open = tokens.get(i + 3);
        if (caller.kind() != SqlFinalModifier.Kind.WORD || dot.kind() != SqlFinalModifier.Kind.DOT
                || name.kind() != SqlFinalModifier.Kind.WORD || open.kind() != SqlFinalModifier.Kind.OPEN
                || dot.start() != caller.end() || name.start() != dot.end()
                || i > 0 && tokens.get(i - 1).kind() == SqlFinalModifier.Kind.DOT
                || !sql.substring(name.end(), open.start()).isBlank()
                || !"caller".equalsIgnoreCase(sql.substring(caller.start(), caller.end()))) {
            return null;
        }
        final String value = "caller." + sql.substring(name.start(), name.end()).toLowerCase(Locale.ROOT);
        return CallerValues.NAMES.contains(value) ? value : null;
    }

    /** Hands {@code action} the identifier of every function call in {@code node}. */
    private static void forEachCall(SqlNode node, Consumer<SqlIdentifier> action) {
        node.accept(new SqlBasicVisitor<Void>() {
            @Override
            public Void visit(SqlCall call) {
                if (call.getOperator() instanceof SqlFunction function && function.getSqlIdentifier() != null) {
                    action.accept(function.getSqlIdentifier());
                }
                return super.visit(call);
            }
        });
    }

    private static List<Integer> position(SqlParserPos pos) {
        return List.of(pos.getLineNum(), pos.getColumnNum());
    }

    /** The caller value {@code operator} carries, or {@code null} when it is no caller-value operator. */
    static String callerValueOf(SqlOperator operator) {
        return operator == SqlQueryOperators.CALLER_USER_HASH || operator == SqlQueryOperators.CALLER_PROJECT
                ? CALLER_VALUE_OF.get(operator.getName()) : null;
    }

    private static boolean isMarker(String name) {
        return USER_HASH_OPERATOR.equalsIgnoreCase(name) || PROJECT_OPERATOR.equalsIgnoreCase(name);
    }

    /** Calcite's own wording for a function it does not know, so the refusal classifies as {@code unknown_function}. */
    private static SqlTranslationException unknownFunction(String name) {
        return new SqlTranslationException("SQL validation error: No match found for function signature " + name + "()");
    }
}
