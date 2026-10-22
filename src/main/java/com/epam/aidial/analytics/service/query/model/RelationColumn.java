package com.epam.aidial.analytics.service.query.model;

import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

/**
 * One column a {@link Relation} exposes: its name as an enclosing block references it, the DSL
 * {@link QueryFieldType} it is validated as (plus the element type for an array), and its runtime
 * {@link ResultColumnKind} (plus the element kind for an array).
 *
 * <p><b>Both are carried because each is lossy in the direction of the other.</b> The kind cannot be
 * derived from the type — {@code FLOAT64} and {@code DECIMAL} are both {@code decimal} in the DSL
 * vocabulary, and the Arrow schema of an outer block must name the kind the values actually arrive as.
 * The type cannot be derived from the kind either: {@code object} and {@code map} share the
 * {@link ResultColumnKind#OBJECT} kind, and {@code enum} and {@code uuid} share {@code STRING}. Deriving
 * the type back from the kind would collapse a {@code map} column into an {@code object} the moment it
 * crossed a join or a derived block — refusing the map accessors ({@code map_value}, {@code map_contains},
 * {@code map_keys}) over an operand's map column, which a single-entity statement accepts — and would
 * silently widen an {@code enum} into a {@code string}. Every operand is validated exactly as a
 * single-entity statement is, so the declared type travels with the column.
 */
public record RelationColumn(
        String name, QueryFieldType type, QueryFieldType elementType,
        ResultColumnKind kind, ResultColumnKind elementKind) {

    /**
     * Fills in a type the caller could not state from the kind it maps to. A computed expression has no
     * declared type — only the kind its evaluation yields — so it is typed by that kind, which is exactly
     * what it is; a pass-through column carries its source's declared type instead and must state it.
     */
    public RelationColumn {
        if (type == null) {
            type = typeOf(kind);
        }
        if (elementType == null && elementKind != null) {
            elementType = typeOf(elementKind);
        }
    }

    /** A scalar column of a known declared type. */
    public RelationColumn(String name, QueryFieldType type, ResultColumnKind kind) {
        this(name, type, null, kind, null);
    }

    private static QueryFieldType typeOf(ResultColumnKind kind) {
        return switch (kind) {
            case STRING -> QueryFieldType.STRING;
            case INT32 -> QueryFieldType.INTEGER;
            case INT64 -> QueryFieldType.LONG;
            case FLOAT64, DECIMAL -> QueryFieldType.DECIMAL;
            case BOOL -> QueryFieldType.BOOLEAN;
            case DATE -> QueryFieldType.DATE;
            case TIMESTAMP -> QueryFieldType.TIMESTAMP;
            case ARRAY -> QueryFieldType.ARRAY;
            case OBJECT -> QueryFieldType.OBJECT;
        };
    }
}
