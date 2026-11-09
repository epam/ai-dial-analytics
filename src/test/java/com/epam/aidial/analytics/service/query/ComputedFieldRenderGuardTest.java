package com.epam.aidial.analytics.service.query;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ComputedField#render}: a stand-in renders only as the top-level select entries a completer replaces. */
class ComputedFieldRenderGuardTest {

    private static final DSLContext DSL_CONTEXT = DSL.using(SQLDialect.CLICKHOUSE);
    private static final Table<?> PAYLOAD = DSL.table(DSL.name(ComputedFields.TABLE));
    private static final Field<Object> EVENT = DSL.field(DSL.name(ComputedFields.TABLE, "event_id"));

    private static Field<Object> full() {
        return ComputedFields.REQUEST_BODY_FULL.standIn(ComputedFields.TABLE);
    }

    private static Field<Object> status() {
        return ComputedFields.REQUEST_BODY_REBUILD.standIn(ComputedFields.TABLE);
    }

    private static String render(Select<?> select, int projected) {
        return ComputedField.render(() -> select.getSQL(ParamType.INLINED), projected);
    }

    @Test
    void projectedStandInsRenderOnceEach() {
        final Select<?> select = DSL_CONTEXT.select(full().as("body"), status().as("status")).from(PAYLOAD);

        assertThat(render(select, 2)).contains("tuple(");
    }

    @Test
    void standInInAnyOtherClauseIsRefused() {
        assertThatThrownBy(() -> render(DSL_CONTEXT.select(full().as("body")).from(PAYLOAD).groupBy(full()), 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("renders 2 time(s)");
        assertThatThrownBy(() -> render(DSL_CONTEXT.select(EVENT).from(PAYLOAD).where(full().isNotNull()), 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("renders 1 time(s)");
        assertThatThrownBy(() -> render(DSL_CONTEXT.select(DSL.rowNumber().over(DSL.partitionBy(status()).orderBy(EVENT)).as("rn"))
                .from(PAYLOAD), 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void standInInsideDerivedTableIsRefused() {
        // The completer only sees the outer select list, so an enclosing block would read the raw tuple.
        final Table<?> inner = DSL_CONTEXT.select(full().as("body")).from(PAYLOAD).asTable("d");
        final Select<?> select = DSL_CONTEXT.select(DSL.field(DSL.name("d", "body"))).from(inner);

        assertThatThrownBy(() -> render(select, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void statementWithoutStandInsIsUnchanged() {
        final Select<?> select = DSL_CONTEXT.select(EVENT).from(PAYLOAD);

        assertThat(render(select, 0)).isEqualTo(select.getSQL(ParamType.INLINED));
    }

    @Test
    void renderingOutsideTheGuardCountsNothing() {
        // A plain getSQL, such as a debug line or the translate endpoints, renders the stand-in freely.
        final Select<?> grouped = DSL_CONTEXT.select(EVENT).from(PAYLOAD).groupBy(full());

        assertThat(grouped.getSQL(ParamType.INLINED)).contains("group by tuple(");
        assertThat(render(DSL_CONTEXT.select(full().as("body")).from(PAYLOAD), 1)).contains("tuple(");
    }
}
