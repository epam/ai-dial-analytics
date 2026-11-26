package com.epam.aidial.analytics.service.query;

import org.jooq.Field;

/**
 * The marks an operand column of a composite plan carries into the block that reads it — a derived table's, a CTE's, a
 * set operation's or a join operand's output (see {@code openspec/specs/structured-query/spec.md}, "Masked inputs are
 * reported per output cell"). The value is already masked in the operand; only the marks travel.
 *
 * @param flag nonzero where the operand cell was computed with an absent input (marked)
 * @param bare nonzero where the operand cell is a bare masked composite field that is absent, so an output reading it
 *     bare is nulled rather than returning the empty value it reads inside expressions; {@code null} when it never is
 */
public record ColumnMark(Field<?> flag, Field<?> bare) {}
