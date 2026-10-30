package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The closed registry of {@link ComputedField computed fields}: {@code request_body_full} and
 * {@code request_body_rebuild} of {@code dial_usage_log_payload} (see {@code openspec/specs/table-management/spec.md},
 * "Seeded payload enrichment on dial_usage_log").
 *
 * <p>Service-defined rather than catalog rows. Every component that reads {@code column_mapping} — export, relayout,
 * the DDL renderer, row policies, grants — treats a row as a stored column, so a computed one would fail there late
 * and far from the feature. Instead, the two places that build a flat field set from catalog rows,
 * {@link CatalogQueryService} and {@code EntityFieldResolver}, append these after the enrichment's catalog columns, as
 * transient {@link ColumnMappingEntity} descriptors that are never persisted, and nothing else sees them.
 */
public final class ComputedFields {

    /** The one table that carries computed fields. */
    public static final String TABLE = "dial_usage_log_payload";

    /** The catalog column whose presence, and whose {@code sensitive} flag, the computed fields follow. */
    public static final String ANCHOR = "request_body";

    /**
     * The other catalog column whose values the rebuilt body returns (its cache markers), so whose {@code sensitive}
     * flag the computed fields also carry.
     */
    public static final String MARKERS = "request_cache_control";

    /** The stored body rebuilt to the request it was. */
    public static final ComputedField REQUEST_BODY_FULL = new ComputedField("request_body_full", ComputedField.Kind.BODY,
            QueryFieldType.STRING, null, "Request body (rebuilt)",
            "The request body as the request carried it, rebuilt from its delta chain; projection-only.");

    /** What {@link #REQUEST_BODY_FULL} holds. */
    public static final ComputedField REQUEST_BODY_REBUILD = new ComputedField("request_body_rebuild", ComputedField.Kind.STATUS,
            QueryFieldType.ENUM, List.of("stored", "rebuilt", "partial"), "Request body rebuild status",
            "What request_body_full holds: stored (the body was stored whole), rebuilt, or partial (the stored delta,"
                    + " because the chain could not be completed); projection-only.");

    private static final Map<String, List<ComputedField>> BY_TABLE = Map.of(TABLE, List.of(REQUEST_BODY_FULL, REQUEST_BODY_REBUILD));

    private ComputedFields() {
    }

    /**
     * The computed fields of {@code table} as transient catalog descriptors, given its catalog {@code columns}: empty
     * unless the table carries computed fields <em>and</em> its {@link #ANCHOR} row is present, each sensitive when that
     * row or the {@link #MARKERS} row is: the rebuilt body returns the values of both. Never persisted.
     */
    public static List<Descriptor> of(String table, List<ColumnMappingEntity> columns) {
        final List<ComputedField> fields = BY_TABLE.get(table);
        if (fields == null) {
            return List.of();
        }
        final ColumnMappingEntity anchor = columns.stream().filter(column -> ANCHOR.equals(column.getSourceName())).findFirst().orElse(null);
        if (anchor == null) {
            return List.of();
        }
        final boolean sensitive = anchor.isSensitive()
                || columns.stream().anyMatch(column -> MARKERS.equals(column.getSourceName()) && column.isSensitive());
        final List<Descriptor> descriptors = new ArrayList<>(fields.size());
        int ordinal = columns.stream().mapToInt(ColumnMappingEntity::getOrdinal).max().orElse(0);
        for (final ComputedField field : fields) {
            final ColumnMappingEntity column = new ColumnMappingEntity();
            column.setTableName(table);
            column.setName(field.name());
            column.setSourceName(field.name());
            column.setType(field.type());
            column.setEnumValues(field.enumValues());
            column.setNullable(true);
            column.setSensitive(sensitive);
            column.setHeavy(true);
            column.setDisplayName(field.displayName());
            column.setDescription(field.description());
            column.setOrdinal(++ordinal);
            descriptors.add(new Descriptor(field, column));
        }
        return descriptors;
    }

    /**
     * {@code columns} followed by {@code table}'s computed-field descriptors ({@link #of}): the field set a sensitive
     * check over {@code table} must judge, since a computed field is projected by name like a catalog column.
     */
    public static List<ColumnMappingEntity> withComputed(String table, List<ColumnMappingEntity> columns) {
        final List<Descriptor> computed = of(table, columns);
        if (computed.isEmpty()) {
            return columns;
        }
        final List<ColumnMappingEntity> all = new ArrayList<>(columns);
        computed.forEach(descriptor -> all.add(descriptor.column()));
        return all;
    }

    /** Whether {@code flatName} — a qualified {@code "<table>.<field>"} name — names a computed field. */
    public static boolean isComputed(String flatName) {
        if (flatName == null) {
            return false;
        }
        final int dot = flatName.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        final List<ComputedField> fields = BY_TABLE.get(flatName.substring(0, dot));
        final String name = flatName.substring(dot + 1);
        return fields != null && fields.stream().anyMatch(field -> field.name().equals(name));
    }

    /** The one refusal every query surface gives a computed field used anywhere but as a plain projection. */
    public static String projectionOnlyMessage(String flatName) {
        return "field '" + flatName + "' is projection-only: it is computed after the query runs and can only be selected";
    }

    /** The refusal a pipeline declaration gets for a computed field anywhere but a transform input. */
    public static String declarationMessage(String what, String flatName) {
        return what + " references '" + flatName + "', which is computed after a read's statement runs: it can only be"
                + " selected by a query or bound as a transform input";
    }

    /**
     * One computed field as the catalog-reading code sees it.
     *
     * @param field  the registry entry
     * @param column its transient catalog descriptor
     */
    public record Descriptor(ComputedField field, ColumnMappingEntity column) {}
}
