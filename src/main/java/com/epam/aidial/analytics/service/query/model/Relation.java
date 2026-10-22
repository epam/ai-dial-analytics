package com.epam.aidial.analytics.service.query.model;

import java.util.List;

/**
 * The source a {@link CompositeQuery} block reads from — <b>internal</b> to the SQL surface, never on the
 * wire. A relation is a queryable entity ({@link EntityRelation}), a nested block rendered as a derived
 * table ({@link DerivedRelation}), a join of two relations ({@link JoinRelation}), or a set operation over
 * two or more blocks ({@link SetOpRelation}). Each exposes the columns a block over it may reference,
 * positionally, under the names the block's expressions use.
 */
public sealed interface Relation permits EntityRelation, DerivedRelation, JoinRelation, SetOpRelation {

    /** The columns a block over this relation sees, in order, under the names its expressions reference. */
    List<RelationColumn> columns();
}
