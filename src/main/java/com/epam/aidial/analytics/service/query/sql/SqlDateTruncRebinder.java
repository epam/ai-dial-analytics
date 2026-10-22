package com.epam.aidial.analytics.service.query.sql;

import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.util.Util;

/**
 * Re-binds PostgreSQL-shaped {@code date_trunc(unit, timestamp)} calls onto the DSL's operator,
 * <strong>before</strong> the statement is validated.
 *
 * <p><b>Why a rewrite and not an operator-table entry.</b> {@code DATE_TRUNC} is not resolved through the
 * operator table at all: the Babel grammar has a dedicated {@code DateTruncFunctionCall()} production that
 * hard-binds every occurrence to {@code SqlLibraryOperators.DATE_TRUNC} at <em>parse</em> time. Chaining a
 * DSL-signature operator ahead of the standard table — which is what makes {@code date_bin} and the
 * {@code json_extract_*} family work, since those parse as unresolved functions — therefore cannot win
 * here, and registering one would look correct while changing nothing.
 *
 * <p>The grammar production is more permissive than Calcite's operator, and that is what makes this cheap:
 * its second argument is {@code TimeUnit() | Expression}, so {@code date_trunc('month', request_time)}
 * <em>parses</em> without complaint and yields the operand list in the order the caller wrote it. Only the
 * operand type-checker on {@code SqlLibraryOperators.DATE_TRUNC} — which is BigQuery-shaped
 * {@code DATE_TRUNC(date, unit)}, the operands the other way round — then rejects it, and it does so
 * through {@code handleUnresolvedFunction}, whose fallback message ("Invalid number of arguments … Was
 * expecting 2 arguments") names an arity that is not the problem. So the tree is already right; only the
 * operator bound to it is wrong.
 *
 * <p><b>The swap alone is not sufficient</b>: {@link SqlQueryOperators#TABLE} must also carry the DSL
 * signature, because the validator re-resolves a function call by name against the operator table when
 * deriving its type. With the swap and without the entry, validation fails with "No match found for
 * function signature". The two halves are separate mechanisms — parse-time binding and validation-time
 * lookup — and this operator is the only one in the front end that needs both.
 *
 * <p>The swap is deliberately narrow: it fires only when the first operand is a <b>character literal</b>,
 * which is the DSL's shape and cannot be Calcite's (whose first operand is the date). A statement written
 * in Calcite's own order is left exactly as parsed, so it keeps whatever behaviour that operator has rather
 * than being silently reinterpreted with its arguments transposed — the one failure mode worth ruling out
 * here, since both orders are individually plausible and the wrong one returns a plausible timestamp.
 */
final class SqlDateTruncRebinder {

    private SqlDateTruncRebinder() {}

    /**
     * Rewrites every PostgreSQL-shaped {@code date_trunc} call in {@code statement} in place.
     *
     * <p>Walks the whole tree rather than the select list alone: the call is legal anywhere an expression
     * is, and a {@code GROUP BY} occurrence must be rewritten identically to its {@code SELECT} twin or the
     * two stop matching and the group key fails to resolve.
     */
    static void rebind(SqlNode statement) {
        if (statement == null) {
            return;
        }
        statement.accept(new SqlBasicVisitor<Void>() {
            @Override
            public Void visit(SqlCall call) {
                if (call instanceof SqlBasicCall basic && isPostgresShapedDateTrunc(basic)) {
                    basic.setOperator(SqlQueryOperators.DATE_TRUNC);
                }
                return super.visit(call);
            }
        });
    }

    private static boolean isPostgresShapedDateTrunc(SqlBasicCall call) {
        if (!"DATE_TRUNC".equalsIgnoreCase(call.getOperator().getName()) || call.operandCount() != 2) {
            return false;
        }
        final SqlNode unit = call.operand(0);
        return unit instanceof SqlLiteral literal
                && SqlTypeName.CHAR_TYPES.contains(Util.first(literal.getTypeName(), SqlTypeName.ANY));
    }
}
