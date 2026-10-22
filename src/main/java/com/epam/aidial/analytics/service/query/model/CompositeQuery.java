package com.epam.aidial.analytics.service.query.model;

import java.util.List;

/**
 * One query block of a composite SQL statement — <b>internal</b> to the SQL surface, never on the wire.
 * {@code block} carries the block's clauses in the {@link StructuredQuery} shape (its {@code entity} is
 * the leaf entity when {@code source} is an {@link EntityRelation}, else {@code null}); {@code source} is
 * what the block reads from; {@code columns} describe the block's outputs positionally, aligned with
 * {@code block.select()}, so an enclosing block or a result schema can type them without re-deriving.
 *
 * <p>Row governance (default and maximum limit) applies only to the plan's <em>root</em> block; a nested
 * block's {@code page} is rendered exactly as stated and an absent one renders no {@code LIMIT}.
 */
public record CompositeQuery(Relation source, StructuredQuery block, List<RelationColumn> columns) {

    /** The same block with a different page — the Flight transport's clamp-and-probe needs exactly this. */
    public CompositeQuery withPage(PageSpec page) {
        return new CompositeQuery(source, block.withPage(page), columns);
    }
}
