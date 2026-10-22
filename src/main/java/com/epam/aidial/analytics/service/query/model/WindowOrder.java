package com.epam.aidial.analytics.service.query.model;

/**
 * One {@code ORDER BY} key of a {@link WindowExpr}: an expression (not a name, unlike {@link SortItem} —
 * a window orders by a value of the current row, which may be computed), a direction and an optional
 * null ordering. A {@code null} direction means ascending.
 */
public record WindowOrder(Expr expr, SortDir dir, NullsOrder nulls) {}
