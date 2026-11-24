package com.epam.aidial.analytics.service.query;

import org.jooq.Condition;
import org.jooq.Context;
import org.jooq.Field;
import org.jooq.impl.CustomField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.util.List;

/**
 * The mask condition of a masked field (see {@code openspec/specs/table-access-control/spec.md}, "Column visibility by
 * caller role"): true on the rows whose stored value the caller may read, false everywhere else, never {@code NULL}.
 * One instance per allowing-role set per query model, so the fields that share allowing roles share one condition.
 *
 * <p>Its text is rendered once per SELECT scope as {@code (ifNull(<or>, 0) AS __adas_mcond_<i>)} and every other
 * reference in the scope is the bare alias ({@link MaskRendering}); a policy can be 16 KiB, and inlining it at every
 * reference would multiply that by the references a statement makes. Wrapped in {@code ifNull}, a policy whose caller
 * value is unknown, or whose comparison is unknown on a row, admits that row no more than the row filter does, and the
 * row's flag ({@code NOT mask}) is {@code 1} rather than a {@code NULL} an aggregate would skip.
 */
public final class ColumnMask {

    private final Field<Boolean> definition;
    private final boolean aliased;

    private ColumnMask(Field<Boolean> definition, boolean aliased) {
        this.definition = definition;
        this.aliased = aliased;
    }

    /** The mask that is true where {@code anyOf} — the disjunction of the allowing roles' row policies — is true. */
    public static ColumnMask of(Condition anyOf) {
        return new ColumnMask(DSL.field("ifNull({0}, 0)", Boolean.class, anyOf), true);
    }

    /** The mask true where every one of {@code masks} is: a computed field's, present where every input it returns is. */
    public static ColumnMask allOf(List<ColumnMask> masks) {
        if (masks.size() == 1) {
            return masks.getFirst();
        }
        return new ColumnMask(DSL.field("({0})", Boolean.class,
                DSL.and(masks.stream().map(mask -> DSL.condition(mask.reference())).toList())), false);
    }

    /** A reference to this mask, as the statement renders it in the scope it lands in. */
    public Field<Boolean> reference() {
        return aliased ? new Reference(this) : definition;
    }

    /** One reference; whether it defines the scope's alias or reads it is decided as it renders. */
    private static final class Reference extends CustomField<Boolean> {

        private final ColumnMask mask;

        private Reference(ColumnMask mask) {
            super(DSL.name("mask"), SQLDataType.BOOLEAN);
            this.mask = mask;
        }

        @Override
        public void accept(Context<?> ctx) {
            MaskRendering.reference(mask, mask.definition, ctx);
        }
    }
}
