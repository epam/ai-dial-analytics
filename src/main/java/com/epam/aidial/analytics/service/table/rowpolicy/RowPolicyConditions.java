package com.epam.aidial.analytics.service.table.rowpolicy;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.query.CallerValues;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.sql.SqlConditionStatement;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.SqlTranslation;
import com.epam.aidial.analytics.service.security.SystemIdentity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns a row policy written as a SQL condition — the text that would follow {@code WHERE} — into the filter
 * tree a policy is stored as. The text is an input format only: it is translated by the SQL front end the
 * ad-hoc SQL surface uses, and what comes out is validated, stored and enforced like any other policy filter,
 * so no operator-written SQL is ever stored or sent to the data store.
 *
 * <p>The condition is resolved under the system identity, because a policy is validated against every column
 * the table stores, whatever the writing caller may see.
 *
 * <p>A caller value is written {@code caller.user_hash()} or {@code caller.project()}, the spelling a query uses,
 * which the SQL front end reads itself; or, as before, {@code caller.user_hash} or {@code caller.project} without
 * the parentheses. In SQL the bare spelling is a column of a relation that does not exist, so each one outside a
 * quoted section is replaced, before parsing, by a string literal holding a marker generated for this one call,
 * and a string value equal to a marker becomes the {@code param} afterwards. The operator cannot write the marker,
 * so a literal of the condition never turns into a caller value. A policy condition is a single {@code WHERE},
 * which the planner does not simplify, so the marker literal is safe here where it would not be in a query.
 */
@Component
@RequiredArgsConstructor
public class RowPolicyConditions {

    private static final List<String> CALLER_VALUES = List.of(CallerValues.USER_HASH, CallerValues.PROJECT);

    private static final String SINGLE_CONDITION =
            "'where' must be a single condition over the table's own columns, with no subquery and no other clause";

    private final TableDefinitionRepository definitions;
    private final SqlQueryFrontEnd sql;
    private final SystemIdentity systemIdentity;
    private final JsonMapper jsonMapper;

    /**
     * The filter {@code condition} means over {@code table}.
     *
     * @throws TableNotFoundException   if the table does not exist (404)
     * @throws TableValidationException if the condition cannot be a policy filter (422)
     */
    public FilterNode toFilter(String table, String condition) {
        definitions.findById(table).orElseThrow(() -> new TableNotFoundException("table '" + table + "' not found"));
        if (StringUtils.isBlank(condition)) {
            throw new TableValidationException("'where' must not be blank");
        }
        final String marker = UUID.randomUUID() + ":";
        // The parentheses keep the text one condition: a trailing clause inside them does not parse. The
        // closing one is on its own line, so a line comment ending the text cannot swallow it.
        final String statement = "SELECT * FROM \"" + table + "\" WHERE (" + markCallerValues(condition, marker) + "\n)";
        // Refused on the parsed text, before any name is resolved: the answer must not tell whether a table or
        // column the writer cannot see exists.
        if (!SqlConditionStatement.isConditionOnly(statement)) {
            throw new TableValidationException(SINGLE_CONDITION);
        }
        final AtomicReference<SqlTranslation> translated = new AtomicReference<>();
        try {
            systemIdentity.runAsSystem(() -> translated.set(sql.translate(statement, Privilege.TABLE_GET, true)));
        } catch (SqlTranslationException | QueryValidationException ex) {
            throw new TableValidationException("'where' " + ex.getMessage().replace(marker, ""));
        } catch (AssertionError ex) {
            // Calcite asserts on some texts that parse and mean nothing, such as a clause inside the parentheses.
            throw new TableValidationException("'where' is not a condition the SQL surface can read");
        }
        final StructuredQuery query = translated.get().query();
        // Balanced parentheses can still close the condition and open a clause; a policy is a condition only.
        if (query.filter() == null || query.having() != null || query.distinct() || query.page() != null
                || !isEmpty(query.groupBy()) || !isEmpty(query.sort())) {
            throw new TableValidationException(SINGLE_CONDITION);
        }
        final FilterNode filter = unmark(query.filter(), marker);
        if (jsonMapper.writeValueAsString(filter).contains(marker)) {
            throw new TableValidationException("'where' may use a caller value (" + String.join(", ", CALLER_VALUES)
                    + ") only as one side of '=' or '<>' against a string column");
        }
        return filter;
    }

    /** {@code condition} with each caller value outside a quoted section or a comment replaced by its marker literal. */
    static String markCallerValues(String condition, String marker) {
        final StringBuilder out = new StringBuilder(condition.length() + 64);
        char quote = 0;
        for (int i = 0; i < condition.length(); i++) {
            final char c = condition.charAt(i);
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (condition.startsWith("--", i) || condition.startsWith("//", i) || condition.startsWith("/*", i)) {
                // A comment is copied as it is: a quote inside one opens nothing.
                int end;
                if (condition.charAt(i + 1) == '*') {
                    final int close = condition.indexOf("*/", i + 2);
                    end = close < 0 ? condition.length() : close + 2;
                } else {
                    end = i;
                    while (end < condition.length() && condition.charAt(end) != '\n' && condition.charAt(end) != '\r') {
                        end++;
                    }
                }
                out.append(condition, i, end);
                i = end - 1;
                continue;
            } else {
                final String name = callerValueAt(condition, i);
                if (name != null) {
                    out.append('\'').append(marker).append(name).append('\'');
                    i += name.length() - 1;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The caller value spelled at {@code at} as a whole name, not as part of a longer one and not as the call
     * {@code caller.user_hash()} — which the SQL front end reads itself — or {@code null}.
     */
    private static String callerValueAt(String text, int at) {
        if (at > 0 && isNamePart(text.charAt(at - 1))) {
            return null;
        }
        for (final String name : CALLER_VALUES) {
            final int end = at + name.length();
            if (text.startsWith(name, at) && (end == text.length() || !isNamePart(text.charAt(end)))
                    && !text.substring(end).stripLeading().startsWith("(")) {
                return name;
            }
        }
        return null;
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /**
     * {@code node} with each marker literal that is a direct operand of a comparison turned into its {@code param}. An
     * {@code in} list holding a marker — {@code IN ('a', caller.user_hash)}, folded by the translator — becomes the
     * disjunction of equalities it abbreviates, which is what the call spelling {@code IN ('a', caller.user_hash())}
     * stores, so both spellings store the same filter.
     */
    private static FilterNode unmark(FilterNode node, String marker) {
        return switch (node) {
            case LogicalNode logical when logical.args() != null -> new LogicalNode(
                    logical.op(), logical.args().stream().map(arg -> unmark(arg, marker)).toList());
            case ComparisonNode(ComparisonOp op, List<Expr> args) when op == ComparisonOp.IN && args != null
                    && args.size() == 2 && args.get(1) instanceof ArrayExpr(List<Expr> items) && items != null
                    && items.stream().anyMatch(item -> unmark(item, marker) != item) -> new LogicalNode(LogicalOp.OR,
                    items.stream().<FilterNode>map(item -> new ComparisonNode(ComparisonOp.EQ,
                            List.of(args.getFirst(), unmark(item, marker)))).toList());
            case ComparisonNode comparison when comparison.args() != null -> new ComparisonNode(
                    comparison.op(), comparison.args().stream().map(arg -> unmark(arg, marker)).toList());
            default -> node;
        };
    }

    private static Expr unmark(Expr expr, String marker) {
        return expr instanceof ValueExpr value && value.value() != null && value.value().startsWith(marker)
                ? new ParamExpr(value.value().substring(marker.length())) : expr;
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }
}
