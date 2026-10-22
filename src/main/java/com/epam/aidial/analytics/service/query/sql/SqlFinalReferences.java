package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlHint;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlTableRef;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.util.SqlBasicVisitor;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses a {@code FINAL} (the {@link SqlFinalModifier#HINT} hint once rewritten) on a table reference that
 * names no entity: a CTE name, or a catalog table under {@code INFORMATION_SCHEMA}. Both parse as ordinary
 * table references, so {@link SqlFinalModifier} cannot tell them from an entity by the text around them; the
 * tree can. ClickHouse refuses {@code FINAL} on a CTE name as it does on any subquery, and inlining the CTE
 * would otherwise drop the hint without a trace — reading the entity inside it raw while the caller asked for
 * final.
 *
 * <p>A CTE name is recognised by name wherever it is declared in the statement. That over-approximates scope
 * only for a statement that both declares a CTE and names an entity of the same name outside it, which the
 * CTE would shadow anyway.
 */
final class SqlFinalReferences {

    private static final String INFORMATION_SCHEMA = "INFORMATION_SCHEMA";

    private SqlFinalReferences() {
    }

    /**
     * @throws SqlTranslationException when {@code parsed} carries {@code FINAL} on a CTE name or a catalog table
     */
    static void check(SqlNode parsed) {
        final Set<String> cteNames = new HashSet<>();
        parsed.accept(new SqlBasicVisitor<Void>() {
            @Override
            public Void visit(SqlCall call) {
                if (call instanceof SqlWithItem item) {
                    cteNames.add(item.name.getSimple().toUpperCase(Locale.ROOT));
                }
                return super.visit(call);
            }
        });
        parsed.accept(new SqlBasicVisitor<Void>() {
            @Override
            public Void visit(SqlCall call) {
                if (call instanceof SqlTableRef ref && isFinal(ref) && ref.operand(0) instanceof SqlIdentifier table
                        && namesNoEntity(table, cteNames)) {
                    throw new SqlTranslationException(SqlFinalModifier.NOT_AN_ENTITY);
                }
                return super.visit(call);
            }
        });
    }

    private static boolean isFinal(SqlTableRef ref) {
        if (!(ref.operand(1) instanceof SqlNodeList hints)) {
            return false;
        }
        for (final SqlNode hint : hints) {
            if (hint instanceof SqlHint sqlHint && sqlHint.getName().equalsIgnoreCase(SqlFinalModifier.HINT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesNoEntity(SqlIdentifier table, Set<String> cteNames) {
        if (table.names.size() > 1 && table.names.getFirst().equalsIgnoreCase(INFORMATION_SCHEMA)) {
            return true;
        }
        return table.isSimple() && cteNames.contains(table.getSimple().toUpperCase(Locale.ROOT));
    }
}
