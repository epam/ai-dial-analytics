package com.epam.aidial.analytics.service.query;

import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Renders ClickHouse's {@code FINAL} table modifier, which jOOQ has no native spelling for.
 *
 * <p>Only ever applied to an <b>unaliased</b> table. ClickHouse accepts the modifier after a table's alias
 * ({@code t AS a FINAL}) and refuses it before one ({@code t FINAL AS a}, measured on 26.3 and 26.8), while a jOOQ
 * plain-SQL table aliased with {@code .as()} renders the alias after the template — {@code "t" FINAL "a"},
 * a syntax error. Every table this is applied to renders bare: the entity's source in {@code FROM}, an
 * enrichment in its grain {@code LEFT JOIN} (qualified by its own name), and the enrichment inside a keyed
 * join's derived table, whose alias belongs to the derived table rather than to the modified reference.
 */
public final class FinalTables {

    private FinalTables() {
    }

    /** {@code table} followed by {@code FINAL}. */
    public static Table<?> of(Table<?> table) {
        return DSL.table("{0} FINAL", table);
    }

    /** {@code table} modified by {@code FINAL} when {@code finalRead}, else {@code table} itself. */
    public static Table<?> of(Table<?> table, boolean finalRead) {
        return finalRead ? of(table) : table;
    }
}
