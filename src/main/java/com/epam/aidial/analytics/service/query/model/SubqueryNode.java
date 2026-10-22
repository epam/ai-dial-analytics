package com.epam.aidial.analytics.service.query.model;

import java.util.List;

/**
 * A subquery predicate — <b>internal</b> to the SQL surface, never on the wire. {@code operands} are the
 * left-hand expressions of {@code IN}/{@code NOT IN} (one, or a tuple matching the subquery's column
 * count) and empty for {@code EXISTS}/{@code NOT EXISTS}. The subquery is uncorrelated by construction:
 * the SQL front end rejects any reference from inside it to an enclosing block.
 *
 * <p>Carries no wire {@code op}: {@link FilterNodeDeserializer} routes every non-logical node to
 * {@link ComparisonNode}, whose operator enum has no code for a subquery, so no request can construct one.
 */
public record SubqueryNode(SubqueryKind kind, List<Expr> operands, CompositeQuery subquery) implements FilterNode {

    /** The four predicate spellings. */
    public enum SubqueryKind {
        IN,
        NOT_IN,
        EXISTS,
        NOT_EXISTS
    }
}
