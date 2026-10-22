package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Makes a set operation mean to ADAS what it means to ClickHouse, before validation sees it.
 *
 * <p>Two readings are ClickHouse's rather than the standard's, and Calcite's parse loses both:
 *
 * <ul>
 *   <li><b>The unqualified operators.</b> {@code UNION} and {@code UNION DISTINCT} parse to the same
 *       operator ({@code all = false} either way), so what the caller wrote is unrecoverable from the tree.
 *       ClickHouse reads a bare {@code INTERSECT}/{@code EXCEPT} as {@code ALL} — its
 *       {@code intersect_default_mode} and {@code except_default_mode} both default to {@code ALL}, where
 *       the standard and Calcite read {@code DISTINCT} — and refuses a bare {@code UNION} outright,
 *       because {@code union_default_mode} defaults to empty. This pass reads the operator's tokens out of
 *       the source text and rewrites the call to Calcite's own explicit operator, so everything downstream
 *       renders the keyword and no server-side default mode can reinterpret the statement.
 *   <li><b>A trailing {@code ORDER BY}/{@code LIMIT}/{@code OFFSET}.</b> ClickHouse binds it to the last
 *       branch, not to the combined result: {@code SELECT 5 UNION ALL <three rows> ORDER BY x LIMIT 2}
 *       returns the 5 untouched followed by two sorted rows of the second branch. Calcite parses the
 *       clause as a {@link SqlOrderBy} over the whole operation — standard scope — so this pass
 *       re-associates it into the last branch. To ClickHouse a union is a <em>list</em> of {@code SELECT}
 *       queries and the clause belongs to the final one, which is not the last <em>operand</em> of the
 *       parse tree: Calcite reads {@code A UNION ALL B INTERSECT ALL C} as
 *       {@code A UNION ALL (B INTERSECT ALL C)}, so this pass walks the right spine down to {@code C}.
 *       ClickHouse also rejects the clause when a parenthesis closes before it — whether around the last
 *       branch or around the whole operation — the clause being grammatically part of an unparenthesized
 *       final {@code SELECT}, and so does this pass.
 * </ul>
 *
 * <p>{@code MINUS} is rejected: ClickHouse has no such keyword and answers a syntax error, so accepting
 * Oracle's synonym for {@code EXCEPT} would succeed where the caller's own query fails.
 *
 * <p>Every rule holds at every level — the statement body, each CTE body, each derived table and each
 * subquery — so a trailing {@code ORDER BY} inside a derived table binds to that derived table's last
 * branch.
 */
final class SqlSetOperations {

    private static final String ALL = "ALL";
    private static final String DISTINCT = "DISTINCT";

    private final SqlSourceRegion source;

    private SqlSetOperations(SqlSourceRegion source) {
        this.source = source;
    }

    /**
     * Rewrites {@code parsed} in place and returns the statement to carry on with — the same node, unless a
     * trailing clause was re-associated into the last branch, in which case the {@link SqlOrderBy} wrapper
     * is gone and the set operation itself is the statement.
     *
     * @throws SqlTranslationException for a bare {@code UNION}, a {@code MINUS}, or a trailing clause that
     *     follows a parenthesis
     */
    static SqlNode rewrite(SqlNode parsed, String sql) {
        return new SqlSetOperations(new SqlSourceRegion(sql)).query(parsed);
    }

    /** Whether a call is one of the three set operators. */
    static boolean isSetOp(SqlNode node) {
        if (!(node instanceof SqlBasicCall call)) {
            return false;
        }
        final SqlKind kind = call.getKind();
        return kind == SqlKind.UNION || kind == SqlKind.INTERSECT || kind == SqlKind.EXCEPT;
    }

    /**
     * One query position. Returns the node to put back in the caller's slot: the same node in every case
     * but a trailing clause over a set operation, which is the one rewrite that changes the shape.
     */
    private SqlNode query(SqlNode node) {
        return switch (node) {
            case SqlOrderBy order -> order(order);
            case SqlWith with -> {
                ctes(with);
                with.body = query(with.body);
                yield with;
            }
            case SqlSelect select -> {
                select(select);
                yield select;
            }
            case SqlBasicCall call when isSetOp(call) -> {
                setOp(call);
                yield call;
            }
            case null, default -> node;
        };
    }

    /** The CTE bodies of a {@code WITH}, each a query position of its own. */
    private void ctes(SqlWith with) {
        for (final SqlNode item : with.withList) {
            if (item instanceof SqlWithItem withItem) {
                withItem.query = query(withItem.query);
            }
        }
    }

    /**
     * An {@code ORDER BY}/{@code LIMIT}/{@code OFFSET} wrapper. Over a set operation the clause is
     * ClickHouse's last-branch clause and moves there, which drops the wrapper; over anything else the
     * wrapped query is rewritten where it stands.
     */
    private SqlNode order(SqlOrderBy order) {
        // Calcite wraps a trailing clause around the whole query it sorts, WITH included: `WITH c AS (…)
        // SELECT … UNION ALL SELECT … ORDER BY x` parses as SqlOrderBy(SqlWith(<set operation>)), the
        // shape SqlQueryFrontEnd.screen peels too. ClickHouse reads that clause as the final SELECT's all
        // the same — measured: `WITH c AS (SELECT arrayJoin([1,2,3]) AS x) SELECT 9 AS x UNION ALL SELECT
        // x FROM c ORDER BY x LIMIT 1` returns 9 and 1 there — so the WITH is peeled to reach the
        // operation the clause really trails, and stays the statement with the clause inside its body.
        if (order.query instanceof SqlWith with) {
            ctes(with);
            if (isSetOp(with.body)) {
                with.body = reassociate((SqlBasicCall) with.body, order);
                return with;
            }
            with.body = query(with.body);
            return order;
        }
        if (!isSetOp(order.query)) {
            // Every other query shape rewrites in place, so the wrapper's final `query` field is never
            // reassigned — which it could not be anyway.
            query(order.query);
            return order;
        }
        return reassociate((SqlBasicCall) order.query, order);
    }

    /**
     * Moves a trailing clause into the final {@code SELECT} of {@code operation} and returns the operation,
     * to stand where the wrapper stood.
     *
     * <p>The final {@code SELECT} is found by walking the right spine, not by taking the last operand: a
     * mixed-precedence chain nests, so the last operand of {@code A UNION ALL B INTERSECT ALL C} is the
     * {@code INTERSECT}, while the query ClickHouse binds the clause to is {@code C}. Measured on
     * ClickHouse 26.3.24 — {@code SELECT 9 AS x UNION ALL SELECT arrayJoin([2,3]) AS x INTERSECT ALL
     * SELECT arrayJoin([1,2,3]) AS x ORDER BY x LIMIT 1} returns the single row 9, which is what binding
     * the clause to {@code C} (and nothing else) yields.
     *
     * <p>The wrapper this builds makes the clause a set-operation <em>input's</em> sort, which Calcite's
     * {@code SqlToRelConverter} discards when it carries no {@code LIMIT}/{@code OFFSET} — the standard's
     * reading that an inner sort is unobservable. {@link SqlQueryFrontEnd} therefore plans with
     * {@code removeSortInSubQuery} off; without it {@code a UNION ALL b ORDER BY x} would execute with no
     * ordering at all, and the default row limit over the combined result would then choose the rows.
     *
     * @throws SqlTranslationException if a parenthesis closes before the clause, which ClickHouse refuses
     */
    private SqlNode reassociate(SqlBasicCall operation, SqlOrderBy order) {
        setOp(operation);
        SqlBasicCall owner = operation;
        while (isSetOp(owner.operand(owner.operandCount() - 1))) {
            owner = owner.operand(owner.operandCount() - 1);
        }
        final int lastIndex = owner.operandCount() - 1;
        final SqlNode last = owner.operand(lastIndex);
        if (parenthesized(last)) {
            throw new SqlTranslationException(
                    "an ORDER BY, LIMIT or OFFSET after a set operation belongs to its last branch and"
                            + " cannot follow a parenthesis; to order or limit the combined result,"
                            + " wrap the whole set operation instead:"
                            + " SELECT ... FROM (<set operation>) ORDER BY ...");
        }
        owner.setOperand(lastIndex, new SqlOrderBy(
                order.getParserPosition(), last, order.orderList, order.offset, order.fetch));
        return operation;
    }

    /** A select's own nested query positions: its {@code FROM}, its predicates and its select list. */
    private void select(SqlSelect select) {
        select.setFrom(from(select.getFrom()));
        expression(select.getWhere());
        expression(select.getHaving());
        expression(select.getSelectList());
        expression(select.getGroup());
        expression(select.getOrderList());
    }

    /**
     * A {@code FROM} item. A derived table is a query position of its own; a join's sides and its
     * {@code ON} predicate are walked; an {@code AS} wrapper keeps its alias and has its query operand
     * replaced in place.
     */
    private SqlNode from(SqlNode node) {
        return switch (node) {
            case SqlJoin join -> {
                join.setLeft(from(join.getLeft()));
                join.setRight(from(join.getRight()));
                // An ON predicate is a query position like WHERE — `ON a.k = b.k AND a.k IN (<set
                // operation>)` carries one — and a qualifier left unresolved there would execute
                // Calcite's standard-SQL reading of an operator ClickHouse reads differently.
                expression(join.getCondition());
                yield join;
            }
            case SqlBasicCall call when call.getKind() == SqlKind.AS -> {
                call.setOperand(0, from(call.operand(0)));
                yield call;
            }
            case SqlSelect select -> query(select);
            case SqlWith with -> query(with);
            case SqlOrderBy order -> query(order);
            case SqlBasicCall call when isSetOp(call) -> query(call);
            case null, default -> node;
        };
    }

    /**
     * Query positions inside an expression — a subquery predicate, an {@code IN} operand, a scalar select.
     * Walked with the parent slot in hand rather than through a visitor, because the one rewrite that
     * changes shape has to be written back into that slot.
     *
     * <p>Every call shape is walked, not only {@link SqlBasicCall}: {@code CASE} is a {@link SqlCall} that
     * is not one, so an arm for the basic call alone would leave a set operation nested in a {@code WHEN}
     * or {@code THEN} with Calcite's parse of it. A slot is written back only when the walk actually
     * replaced the node, so a call whose operand list is fixed is never asked to accept one.
     */
    private void expression(SqlNode expression) {
        switch (expression) {
            case SqlNodeList list -> {
                for (int i = 0; i < list.size(); i++) {
                    final int index = i;
                    replace(list.get(i), rewritten -> list.set(index, rewritten));
                }
            }
            case SqlSelect select -> select(select);
            case SqlCall call -> {
                for (int i = 0; i < call.operandCount(); i++) {
                    final int index = i;
                    // Not call.operand(i): a searched CASE carries a null value operand, which that
                    // accessor refuses.
                    replace(call.getOperandList().get(i), rewritten -> call.setOperand(index, rewritten));
                }
            }
            case null, default -> { }
        }
    }

    /** Walks one expression operand and hands the replacement to {@code slot} only if there is one. */
    private void replace(SqlNode operand, Consumer<SqlNode> slot) {
        if (operand == null) {
            return;
        }
        final SqlNode rewritten = child(operand);
        if (rewritten != operand) {
            slot.accept(rewritten);
        }
    }

    /** One operand of an expression: a query position is rewritten and replaced, anything else is walked. */
    private SqlNode child(SqlNode node) {
        if (node instanceof SqlSelect || node instanceof SqlWith || node instanceof SqlOrderBy
                || isSetOp(node)) {
            return query(node);
        }
        expression(node);
        return node;
    }

    /** A set operation: resolve its qualifier, then walk its branches as query positions. */
    private void setOp(SqlBasicCall call) {
        resolveQualifier(call);
        for (int i = 0; i < call.operandCount(); i++) {
            call.setOperand(i, query(call.operand(i)));
        }
    }

    /**
     * Reads the written qualifier out of the source region between the two operands and rewrites the call
     * to the explicit operator it means. Fails closed: a region whose keyword cannot be read is an error,
     * never a silent default.
     */
    private void resolveQualifier(SqlBasicCall call) {
        final List<String> words = SqlSourceRegion.words(source.between(
                call.operand(0).getParserPosition(), call.operand(1).getParserPosition()));
        final String keyword = words.isEmpty() ? null : words.getFirst();
        // Fail closed on anything but this call's own keyword. An empty region (a synthesized node with
        // no usable position) and a region that reads as something else are the same situation: the text
        // could not be matched to the parse, and the bare and DISTINCT spellings are indistinguishable
        // without it. Erroring is the only safe answer — falling through would promote a written
        // INTERSECT DISTINCT to ALL, the silent divergence this whole pass exists to prevent. `MINUS`
        // parses as kind EXCEPT, so it is the one keyword that legitimately differs from the kind's name.
        if (keyword == null || !("MINUS".equals(keyword) || keyword.equals(call.getKind().name()))) {
            throw new SqlTranslationException("cannot tell whether the "
                    + call.getOperator().getName() + " is ALL or DISTINCT; spell the keyword out");
        }
        if ("MINUS".equals(keyword)) {
            throw new SqlTranslationException("MINUS is not supported; use EXCEPT ALL or EXCEPT DISTINCT,"
                    + " which is what the database understands");
        }
        final String qualifier = words.size() > 1 ? words.get(1) : null;
        if (ALL.equals(qualifier) || DISTINCT.equals(qualifier)) {
            return; // Calcite parsed the explicit operator already; leave it exactly as written.
        }
        final SqlOperator promoted = switch (call.getKind()) {
            case UNION -> throw new SqlTranslationException("UNION requires ALL or DISTINCT: the database"
                    + " refuses a bare UNION, because its union_default_mode setting has no default mode");
            case INTERSECT -> SqlStdOperatorTable.INTERSECT_ALL;
            case EXCEPT -> SqlStdOperatorTable.EXCEPT_ALL;
            default -> throw new SqlTranslationException(
                    "SQL construct '" + call.getOperator().getName() + "' is not supported");
        };
        // A bare INTERSECT/EXCEPT means ALL to the database — the opposite of the DISTINCT Calcite parsed —
        // so the call is rewritten to say ALL before anything downstream reads it.
        call.setOperator(promoted);
    }

    /**
     * Whether the trailing clause follows a parenthesis rather than a bare final {@code SELECT}, which is
     * what makes it a syntax error for the database — whether the parenthesis closes the final branch
     * ({@code a UNION ALL (b) ORDER BY x}) or an enclosing block, the whole operation included
     * ({@code (a UNION ALL b) ORDER BY x}). Both were measured against ClickHouse 26.3 and both are
     * refused there, as is {@code a UNION ALL (b INTERSECT ALL c) ORDER BY x}.
     *
     * <p>Two readings, because one span cannot answer both. The branch's <em>own</em> parentheses are read
     * off the first character of its span, which Calcite includes them in. An enclosing one is read
     * forward, from the end of the branch to the clause keyword: what encloses the operation cannot be
     * read off the operation's span, which starts at its <em>first</em> branch — so
     * {@code (a) UNION ALL b ORDER BY x}, which ClickHouse executes with the clause on the bare {@code b},
     * opens with a parenthesis exactly as {@code (a UNION ALL b) ORDER BY x} does. Stopping the forward
     * scan at the clause keyword is also what keeps a nested operation readable: inside a derived table
     * the text after the final branch is {@code " ORDER BY 1) t"}, whose parenthesis closes the derived
     * table and comes after the clause, not before it.
     */
    private boolean parenthesized(SqlNode last) {
        if (source.opensWithParen(last.getParserPosition())) {
            return true;
        }
        // A branch carrying an ORDER BY of its own was necessarily parenthesized — an unparenthesized one
        // would have taken this very clause — so its own span is the answer whatever the text says.
        if (last instanceof SqlOrderBy) {
            return true;
        }
        final String region = source.after(last.getParserPosition());
        final int clause = SqlSourceRegion.firstClauseKeyword(region);
        // A branch ending in `WHERE f(z)` is not parenthesized: its span ends at that call's own `)`, so
        // the scan starts past it and sees only the text between the branch and the clause.
        return (clause < 0 ? region : region.substring(0, clause)).indexOf(')') >= 0;
    }
}
