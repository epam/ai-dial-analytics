package com.epam.aidial.analytics.service.query.sql;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.SqlBasicVisitor;

/**
 * Tells whether a statement built as {@code SELECT * FROM t WHERE (<text>)} is still that: one {@code SELECT}
 * whose only clause is a {@code WHERE} holding no query. Decided on the parsed tree, before validation, so a
 * text that names another table is refused the same way whether or not that table exists.
 */
public final class SqlConditionStatement {

    private SqlConditionStatement() {
    }

    /** {@code false} when the statement parses and carries a clause or a nested query; a text that does not parse is left to translation to report. */
    public static boolean isConditionOnly(String statement) {
        final SqlNode parsed;
        try {
            parsed = SqlParser.create(statement, SqlQueryFrontEnd.PARSER_CONFIG).parseQuery();
        } catch (SqlParseException e) {
            return true;
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
                    if (SqlKind.QUERY.contains(call.getKind())) {
                        throw new NestedQuery();
                    }
                    return super.visit(call);
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
