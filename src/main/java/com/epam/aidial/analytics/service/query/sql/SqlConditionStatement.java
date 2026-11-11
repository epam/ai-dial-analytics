package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.SqlBasicVisitor;

/**
 * Tells whether a statement built as {@code SELECT * FROM t WHERE (<text>)} is still that: one {@code SELECT}
 * whose only clause is a {@code WHERE} holding no query and no column of another table. Decided on the parsed tree, before validation, so a
 * text that names another table is refused the same way whether or not that table exists.
 */
public final class SqlConditionStatement {

    private SqlConditionStatement() {
    }

    /** {@code false} when the statement parses and carries a clause or a nested query; a text that does not parse is left to translation to report. */
    public static boolean isConditionOnly(String statement) {
        final SqlNode parsed;
        try {
            // The text the front end parses: its FINAL rewrite makes some statements parse that otherwise do not.
            parsed = SqlParser.create(SqlFinalModifier.rewrite(statement), SqlQueryFrontEnd.PARSER_CONFIG).parseQuery();
        } catch (SqlParseException e) {
            return true;
        } catch (SqlTranslationException e) {
            return false;
        }
        if (!(parsed instanceof SqlSelect select) || select.getWhere() == null || select.isDistinct()
                || select.getGroup() != null || select.getHaving() != null || select.getQualify() != null
                || !select.getWindowList().isEmpty() || select.getOrderList() != null
                || select.getOffset() != null || select.getFetch() != null) {
            return false;
        }
        try {
            select.getWhere().accept(new SqlBasicVisitor<Void>() {
                @Override
                public Void visit(SqlCall call) {
                    // A sequence value names a table too: the validator resolves it against the whole catalog.
                    if (SqlKind.QUERY.contains(call.getKind()) || call.getKind() == SqlKind.NEXT_VALUE
                            || call.getKind() == SqlKind.CURRENT_VALUE) {
                        throw new NestedQuery();
                    }
                    return super.visit(call);
                }

                @Override
                public Void visit(SqlIdentifier id) {
                    // A table's own column has no dot in its name; a dotted one is a column of an enrichment.
                    if (id.names.stream().anyMatch(name -> name.contains("."))) {
                        throw new NestedQuery();
                    }
                    return null;
                }
            });
        } catch (NestedQuery e) {
            return false;
        }
        return true;
    }

    private static final class NestedQuery extends RuntimeException {
        NestedQuery() {
            super(null, null, false, false);
        }
    }
}
