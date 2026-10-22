package com.epam.aidial.analytics.service.query.model;

import java.util.List;

/**
 * A queryable entity as a relation. {@code columns} are the entity's flat fields the enclosing plan
 * actually references (after pruning), each under its catalog name — so a leaf rendered as a derived
 * table projects exactly what is read and never a heavy column nobody asked for. {@code finalRead} is
 * whether the SQL reference carried ClickHouse's {@code FINAL} modifier; a SQL reference is always one or
 * the other, never "unstated".
 */
public record EntityRelation(String entity, List<RelationColumn> columns, boolean finalRead) implements Relation {

    /** A reference without {@code FINAL}. */
    public EntityRelation(String entity, List<RelationColumn> columns) {
        this(entity, columns, false);
    }
}
