package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.Field;

import java.util.List;
import java.util.Set;

/**
 * Binds a flat API field name (as published by the schema endpoint, e.g. {@code request_time},
 * {@code success}) to the jOOQ {@link Field} that backs it (qualified by its owning source or
 * enrichment table) and its declared {@link QueryFieldType}. For {@code array} fields,
 * {@code elementType} carries the scalar type of the array's elements ({@code null} for every
 * other type). {@code heavy} mirrors the catalog column's heavy flag: the field is omitted from
 * wildcard projections (see {@code WildcardProjection}) but resolves normally when named. Produced
 * from the catalog by {@code CatalogQueryService} and consumed by the
 * model → jOOQ translation layer so that field resolution stays single-sourced with the schema
 * the client discovers.
 *
 * <p>{@code prunedPaths} is the one member that is a property of <b>one statement</b> rather than of the
 * catalog: for an {@code object} column whose grain join this statement emits as a derived table
 * projecting subcolumns, it is the set of literal JSON paths that table projects, and it is empty
 * everywhere else. It rides on the binding deliberately. The derived table's projection and the
 * expression rendering that reads it must agree on both <em>whether</em> a column was pruned and
 * <em>which</em> paths survived — disagree and the statement names a column that does not exist — and the
 * bindings map is the one thing already threaded to both. A separate map passed alongside would be two
 * facts that can drift; this is one fact read twice. {@link QueryEntityModel#withPrunedPaths} is what
 * stamps it, and only {@link EnrichmentJoins} calls that, because only it knows whether the join it
 * emitted was keyed.
 */
public record QueryFieldBinding(
        String name,
        Field<?> field,
        QueryFieldType type,
        QueryFieldType elementType,
        boolean heavy,
        Set<List<String>> prunedPaths) {

    public QueryFieldBinding {
        prunedPaths = prunedPaths == null ? Set.of() : Set.copyOf(prunedPaths);
    }

    /** Convenience constructor for a field this statement reads whole — every field the catalog produces. */
    public QueryFieldBinding(
            String name, Field<?> field, QueryFieldType type, QueryFieldType elementType, boolean heavy) {
        this(name, field, type, elementType, heavy, Set.of());
    }

    /** Convenience constructor for a non-heavy field of any type. */
    public QueryFieldBinding(String name, Field<?> field, QueryFieldType type, QueryFieldType elementType) {
        this(name, field, type, elementType, false, Set.of());
    }

    /** Convenience constructor for scalar (non-array), non-heavy fields. */
    public QueryFieldBinding(String name, Field<?> field, QueryFieldType type) {
        this(name, field, type, null, false, Set.of());
    }

    /** This binding as the statement renders it once its grain join projects {@code paths} as subcolumns. */
    public QueryFieldBinding prunedTo(Set<List<String>> paths) {
        return new QueryFieldBinding(name, field, type, elementType, heavy, paths);
    }
}
