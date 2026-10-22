package com.epam.aidial.analytics.service.query.model;

import java.util.List;

/** A nested block used as a derived table; its columns are the block's outputs. */
public record DerivedRelation(CompositeQuery query) implements Relation {

    @Override
    public List<RelationColumn> columns() {
        return query.columns();
    }
}
