package com.epam.aidial.analytics.service.query.model;

import java.util.List;

/**
 * A join of two relations. {@code on} is a filter tree over the join's own column names ({@code null}
 * for a cross join); {@code columns} lists every exposed column with the side and source column it comes
 * from, so a renderer can qualify each reference by the operand's alias. Names are unique across both
 * sides — the SQL front end takes them from Calcite's validated join row type, which already
 * de-duplicates.
 */
public record JoinRelation(
        Relation left,
        Relation right,
        JoinKind kind,
        FilterNode on,
        List<JoinColumn> joinColumns) implements Relation {

    @Override
    public List<RelationColumn> columns() {
        return joinColumns.stream().map(JoinColumn::column).toList();
    }

    /** One exposed column of a join: the column as the block sees it, and where it comes from. */
    public record JoinColumn(RelationColumn column, boolean fromLeft, String sourceName) {}
}
