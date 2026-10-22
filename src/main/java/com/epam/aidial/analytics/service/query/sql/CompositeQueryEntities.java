package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.DerivedRelation;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.JoinRelation;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Every leaf entity a {@link CompositeQuery} plan reads from — <b>internal</b> to the SQL surface,
 * used only to derive a saved query's {@code source}. Walks the relation tree
 * ({@link EntityRelation}/{@link JoinRelation}/{@link DerivedRelation}) and, separately, every
 * {@link ScalarSubqueryExpr} or {@link SubqueryNode} nested in a block's {@code select}, {@code filter}
 * or {@code having} — a subquery against a different entity reads that entity just as a join does, so
 * it counts toward the same set.
 */
final class CompositeQueryEntities {

    private CompositeQueryEntities() {
    }

    /** Every entity {@code query} reads from, directly or through a derived table, join, or subquery. */
    static Set<String> leafEntities(CompositeQuery query) {
        return new HashSet<>(QueryReferences.of(query).entityCounts().keySet());
    }
}
