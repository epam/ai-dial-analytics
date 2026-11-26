package com.epam.aidial.analytics.service.query;

import org.jooq.Context;
import org.jooq.Field;
import org.jooq.impl.CustomField;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.jooq.impl.SQLDataType;

/**
 * An internal column of a nested block of a composite plan — a derived table, a CTE, a set-operation branch, a join
 * operand, a scalar subquery — carrying one of its outputs' marks to the block that reads it: the output's mark flag, or
 * its bare-absent flag ({@link ColumnMark}). Every such block projects both for every output, a constant {@code 0} where
 * nothing applies, after its outputs and in their order, so set-operation branches align by position.
 */
public final class OutputFlag extends CustomField<Integer> {

    private final Field<?> expression;
    private final int position;
    private final boolean bare;
    private final boolean zero;

    /**
     * @param expression what the column computes
     * @param position   the index of the output it marks
     * @param bare       whether it is the output's bare-absent flag rather than its mark flag
     * @param zero       whether it is the constant {@code 0}: the output reads nothing that can be absent
     */
    public OutputFlag(Field<?> expression, int position, boolean bare, boolean zero) {
        super(DSL.name("output_flag"), SQLDataType.INTEGER);
        this.expression = expression;
        this.position = position;
        this.bare = bare;
        this.zero = zero;
    }

    /** Whether it is the constant {@code 0}, which the block reading it need not read. */
    public boolean zero() {
        return zero;
    }

    /** The flag projected at {@code projected}, alias unwrapped, or {@code null} when it projects anything else. */
    public static OutputFlag of(Field<?> projected) {
        final Field<?> field = projected instanceof QOM.FieldAlias<?> alias ? alias.$field() : projected;
        return field instanceof OutputFlag flag ? flag : null;
    }

    /** The index of the output it marks. */
    public int position() {
        return position;
    }

    /** Whether it is the output's bare-absent flag rather than its mark flag. */
    public boolean bare() {
        return bare;
    }

    @Override
    public void accept(Context<?> ctx) {
        ctx.visit(expression);
    }
}
