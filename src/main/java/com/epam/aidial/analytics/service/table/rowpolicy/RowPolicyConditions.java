package com.epam.aidial.analytics.service.table.rowpolicy;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.query.RowPolicyPredicates;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.SqlTranslation;
import com.epam.aidial.analytics.service.security.SystemIdentity;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
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
 * <p>A caller value is written {@code caller.user_hash} or {@code caller.project}. In SQL that spelling is a
 * column of a relation that does not exist, so each one outside a quoted section is replaced, before parsing,
 * by a string literal holding a marker generated for this one call, and a string value equal to a marker
 * becomes the {@code param} afterwards. The operator cannot write the marker, so a literal of the condition
 * never turns into a caller value.
 */
@Component
@RequiredArgsConstructor
public class RowPolicyConditions {

    private static final List<String> CALLER_VALUES =
            List.of(RowPolicyPredicates.CALLER_USER_HASH, RowPolicyPredicates.CALLER_PROJECT);

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
        final TableDefinitionEntity definition = definitions.findById(table)
                .orElseThrow(() -> new TableNotFoundException("table '" + table + "' not found"));
        if (definition.getType() == TableType.ENRICHMENT) {
            throw new TableValidationException("'where' is not available on table '" + table + "': an enrichment "
                    + "is not queryable on its own, so its row policies are written as 'filter'");
        }
        if (StringUtils.isBlank(condition)) {
            throw new TableValidationException("'where' must not be blank");
        }
        final String marker = UUID.randomUUID() + ":";
        // The parentheses keep the text one condition: a trailing clause inside them does not parse.
        final String statement = "SELECT * FROM \"" + table + "\" WHERE (" + markCallerValues(condition, marker) + ")";
        final AtomicReference<SqlTranslation> translated = new AtomicReference<>();
        try {
            systemIdentity.runAsSystem(() -> translated.set(sql.translate(statement, Privilege.TABLE_GET)));
        } catch (SqlTranslationException | QueryValidationException ex) {
            throw new TableValidationException("'where' " + ex.getMessage().replace(marker, ""));
        } catch (AssertionError ex) {
            // Calcite asserts on some texts that parse and mean nothing, such as a clause inside the parentheses.
            throw new TableValidationException("'where' is not a condition the SQL surface can read");
        }
        final StructuredQuery query = translated.get().query();
        // Balanced parentheses can still close the condition and open a clause; a policy is a condition only.
        if (query.filter() == null || query.having() != null || query.distinct() || translated.get().explicitLimit()
                || !isEmpty(query.groupBy()) || !isEmpty(query.sort())) {
            throw new TableValidationException("'where' must be a single condition, with no other clause");
        }
        final FilterNode filter = unmark(query.filter(), marker);
        if (jsonMapper.writeValueAsString(filter).contains(marker)) {
            throw new TableValidationException("'where' may use a caller value (" + String.join(", ", CALLER_VALUES)
                    + ") only as one side of '=' or '<>' against a string column");
        }
        return filter;
    }

    /** {@code condition} with each caller value outside a quoted section replaced by its marker literal. */
    static String markCallerValues(String condition, String marker) {
        final StringBuilder out = new StringBuilder(condition.length() + 64);
        char quote = 0;
        for (int i = 0; i < condition.length(); i++) {
            final char c = condition.charAt(i);
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
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

    /** The caller value spelled at {@code at} as a whole name, not as part of a longer one, or {@code null}. */
    private static String callerValueAt(String text, int at) {
        if (at > 0 && isNamePart(text.charAt(at - 1))) {
            return null;
        }
        for (final String name : CALLER_VALUES) {
            final int end = at + name.length();
            if (text.startsWith(name, at) && (end == text.length() || !isNamePart(text.charAt(end)))) {
                return name;
            }
        }
        return null;
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /** {@code node} with each marker literal that is a direct operand of a comparison turned into its {@code param}. */
    private static FilterNode unmark(FilterNode node, String marker) {
        return switch (node) {
            case LogicalNode logical when logical.args() != null -> new LogicalNode(
                    logical.op(), logical.args().stream().map(arg -> unmark(arg, marker)).toList());
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
