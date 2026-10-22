package com.epam.aidial.analytics.service.query.model;

import java.util.List;

/**
 * A set operation over two or more query blocks, used as a relation — <b>internal</b> to the SQL surface,
 * never on the wire. Like {@link SubqueryNode} and {@link ScalarSubqueryExpr} it carries no Jackson type
 * id, so no request body can construct one; only the SQL front end builds it.
 *
 * <p>{@code branches} are full blocks rather than bare relations because a branch carries its own
 * {@code WHERE}, {@code GROUP BY}, {@code DISTINCT}, {@code ORDER BY} and {@code LIMIT} — including a
 * trailing clause the caller wrote after the last branch, which ClickHouse binds to that branch and the
 * front end re-associates into it. {@code columns} are the operation's output row: matched positionally
 * across branches, named after the <em>first</em> branch, and typed by unifying the branches' own columns
 * (falling back to the validated row type where they disagree).
 *
 * <p>{@code all} is the resolved qualifier, never the caller's absence of one: the front end has already
 * refused a bare {@code UNION} (ClickHouse's empty {@code union_default_mode} refuses it too) and promoted
 * a bare {@code INTERSECT}/{@code EXCEPT} to {@code ALL} (ClickHouse's default modes for those two), so
 * rendering always spells the keyword out and a reconfigured server cannot reinterpret the statement.
 *
 * <p>Branch outputs are never column-pruned: every branch must keep the same arity in the same order, and
 * a {@code DISTINCT} branch's row count depends on what it projects.
 */
public record SetOpRelation(
        SetOpKind kind, boolean all, List<CompositeQuery> branches, List<RelationColumn> columns)
        implements Relation {

    /** The rendered qualifier — always one or the other, never absent. */
    public String qualifier() {
        return all ? "ALL" : "DISTINCT";
    }
}
