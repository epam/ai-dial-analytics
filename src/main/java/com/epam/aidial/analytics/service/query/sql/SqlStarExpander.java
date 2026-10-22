package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.WildcardProjection;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlTableRef;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlBasicVisitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Expands a star select item ({@code SELECT *}, {@code SELECT t.*}) into the entity's wildcard field
 * list — its non-heavy source columns, see {@link WildcardProjection} — by rewriting the parsed
 * statement <strong>before</strong> it is validated.
 *
 * <p>Expanding in the AST rather than filtering the validated plan has two consequences that matter:
 *
 * <ul>
 *   <li>an expanded star is indistinguishable from a caller-written column list, so validation, the
 *       relational plan, the produced DSL {@code select} and the reported {@code ResultColumn} names all
 *       behave exactly as for the equivalent explicit statement — each column keeps its own name;
 *   <li>the excluded columns stay fully addressable: they remain in the table's row type
 *       ({@link CalciteQueryCatalog}), so naming a heavy or an enrichment column explicitly resolves and
 *       projects it as before. Nothing is hidden, only left out of the star.
 * </ul>
 *
 * <p>The {@code FROM} may name one entity, a join of several operands, a derived table or a CTE. A star over
 * an entity operand expands to that entity's wildcard fields — qualified by the operand's alias when there
 * is more than one operand, so validation never sees an ambiguity the caller did not write. A derived table
 * or CTE body — and a subquery in WHERE, HAVING or the select list — is <em>recursed into</em>, so the exclusion is applied where the base table is referenced; a
 * star over such an operand is left to the validator, whose expansion is then exactly what the inner
 * select produced. A derived table joined without an alias is given a generated one first, so a bare star
 * over it can be expanded as {@code <alias>.*} rather than abandoned to the validator's full-row-type
 * expansion. An unresolvable table is left untouched for validation to report. A function-star such
 * as {@code count(*)} is never touched: it is an operand of a call, not a top-level select item.
 *
 * <p>Runs before {@link SqlSelectAliasInjector}, so the derived-name uniqueness pass sees the real
 * select list.
 */
final class SqlStarExpander {
    private SqlStarExpander() {}

    /** One {@code FROM} operand: how a qualified star names it, and the entity it is (null for a derived table or CTE). */
    private record Operand(String alias, String entity) {}

    /**
     * Rewrites the select list of {@code parsed} in place, expanding every expandable star, in the statement
     * body and in every CTE body and derived table it contains. Ignores any node shape it does not know (an
     * unsupported statement shape, rejected downstream with its own message).
     *
     * @throws SqlTranslationException if a star's expansion would be empty (every column of the entity
     *     visible to the caller is marked heavy)
     */
    static void expandStars(SqlNode parsed, CalciteQueryCatalog.Snapshot snapshot) {
        // The ORDER BY wrapper is peeled first, before any other shape is tested. Calcite wraps a top-level
        // ORDER BY around the whole query it sorts — WITH included — so `WITH c AS (…) SELECT * FROM c
        // ORDER BY x` parses as SqlOrderBy(SqlWith(…)), not SqlWith(SqlOrderBy(…)). Testing for SqlWith
        // first would miss that shape entirely and leave its stars to the validator, which expands against
        // the table's FULL row type: heavy and dotted enrichment columns included. It is also the shape BI
        // tools generate, since they append their own ORDER BY.
        if (parsed instanceof SqlOrderBy order) {
            expandStars(order.query, snapshot);
            return;
        }
        if (parsed instanceof SqlWith with) {
            for (final SqlNode item : with.withList) {
                if (item instanceof SqlWithItem withItem) {
                    expandStars(withItem.query, snapshot);
                }
            }
            expandStars(with.body, snapshot);
            return;
        }
        // Each branch of a set operation is a query block whose star expands at its own entity. Without
        // this arm the method would fall out at the SqlSelect guard below and leave a branch's star to the
        // validator, which expands the table's FULL row type — heavy and dotted enrichment columns
        // included.
        if (SqlSetOperations.isSetOp(parsed)) {
            for (final SqlNode branch : ((SqlCall) parsed).getOperandList()) {
                expandStars(branch, snapshot);
            }
            return;
        }
        if (!(parsed instanceof SqlSelect select) || select.getSelectList() == null) {
            return;
        }
        // A subquery in WHERE, HAVING or the select list is a query block like any other: its own star is
        // expanded where its entity is named, so an EXISTS (SELECT * FROM big) never reads heavy columns.
        expandNestedQueries(select.getWhere(), snapshot);
        expandNestedQueries(select.getHaving(), snapshot);
        for (final SqlNode item : select.getSelectList()) {
            if (!(item instanceof SqlIdentifier)) {
                expandNestedQueries(item, snapshot);
            }
        }
        final List<Operand> operands = new ArrayList<>();
        collectOperands(select.getFrom(), operands, snapshot, new AliasSource(select.getFrom()));
        if (operands.isEmpty()) {
            return;
        }
        final SqlNodeList items = select.getSelectList();
        final List<SqlNode> expanded = new ArrayList<>(items.size());
        boolean rewritten = false;
        for (final SqlNode item : items) {
            final String qualifier = starQualifier(item);
            final List<SqlNode> replacement = qualifier == null ? null : expansion(qualifier, item, operands, snapshot);
            if (replacement == null) {
                expanded.add(item);
            } else {
                expanded.addAll(replacement);
                rewritten = true;
            }
        }
        if (rewritten) {
            select.setSelectList(new SqlNodeList(expanded, items.getParserPosition()));
        }
    }

    /** Expands the stars of every query block nested inside an expression (a predicate or a select item). */
    private static void expandNestedQueries(SqlNode expression, CalciteQueryCatalog.Snapshot snapshot) {
        if (expression == null) {
            return;
        }
        expression.accept(new SqlBasicVisitor<Void>() {
            @Override
            public Void visit(SqlCall call) {
                if (call instanceof SqlSelect || call instanceof SqlOrderBy || call instanceof SqlWith) {
                    expandStars(call, snapshot);
                    return null; // expandStars recursed into the block itself
                }
                return super.visit(call);
            }
        });
    }

    /**
     * The items a star stands for, or {@code null} to leave it to the validator. A bare star over several
     * operands expands operand by operand: an entity to its qualified wildcard fields, anything else to
     * {@code alias.*} — every derived table has one by the time this runs, see
     * {@link #named(SqlJoin, int, SqlNode, AliasSource)}. A qualified star expands only when it names an
     * entity operand.
     *
     * @throws SqlTranslationException if an operand of a multi-operand {@code FROM} cannot be named at all
     */
    private static List<SqlNode> expansion(
            String qualifier, SqlNode star, List<Operand> operands, CalciteQueryCatalog.Snapshot snapshot) {
        if (!qualifier.isEmpty()) {
            for (final Operand operand : operands) {
                if (operand.entity() != null && qualifier.equalsIgnoreCase(operand.alias())) {
                    return fieldIdentifiers(operand.entity(), qualifier, star, snapshot);
                }
            }
            return null;
        }
        if (operands.size() == 1) {
            final Operand only = operands.getFirst();
            return only.entity() == null ? null : fieldIdentifiers(only.entity(), null, star, snapshot);
        }
        final List<SqlNode> items = new ArrayList<>();
        for (final Operand operand : operands) {
            if (operand.alias() == null) {
                // Every derived table reaches here named — collectOperands generates an alias for one the
                // caller left unaliased — so this is a FROM item that is neither an entity, a CTE reference
                // nor a query block. Refusing is the safe answer: falling through to the validator would
                // expand the star against each table's FULL row type, heavy and enrichment columns included,
                // which is exactly what this pass exists to prevent.
                throw new SqlTranslationException("'*' over this FROM is not supported: one of its items is"
                        + " neither a table nor a parenthesised SELECT, so its columns cannot be expanded;"
                        + " list the columns explicitly");
            }
            if (operand.entity() != null) {
                items.addAll(fieldIdentifiers(operand.entity(), operand.alias(), star, snapshot));
            } else {
                items.add(SqlIdentifier.star(List.of(operand.alias(), ""), star.getParserPosition(),
                        List.of(star.getParserPosition(), star.getParserPosition())));
            }
        }
        return items;
    }

    /**
     * Flattens a {@code FROM} into its operands, recursing into joins and — for the exclusion to apply where
     * the entity is named — into every derived table and CTE body on the way.
     */
    private static void collectOperands(
            SqlNode from, List<Operand> operands, CalciteQueryCatalog.Snapshot snapshot, AliasSource aliases) {
        if (from == null) {
            return;
        }
        if (from instanceof SqlJoin join) {
            collectOperands(named(join, 0, join.getLeft(), aliases), operands, snapshot, aliases);
            collectOperands(named(join, 3, join.getRight(), aliases), operands, snapshot, aliases);
            return;
        }
        final SqlNode node = unwrapAlias(from);
        if (node instanceof SqlIdentifier table) {
            // A queryable entity, or a CTE / unknown table (entity null): the latter two are left to validation.
            operands.add(new Operand(aliasOf(from, table), resolveEntity(table, snapshot)));
            return;
        }
        // A derived table carries its own select list: expand there, where the entity is named.
        expandStars(node, snapshot);
        operands.add(new Operand(aliasOf(from, null), null));
    }

    /**
     * The join operand as the expansion needs to see it: an unaliased derived table is given a generated
     * alias, in the parsed statement itself, and the aliased node is returned.
     *
     * <p><b>Without this a bare star over the shape leaks every heavy column.</b> A star over several
     * operands can only be expanded operand by operand, and the only way to name a derived table's columns
     * is {@code <alias>.*} — so an operand with no alias used to abandon the whole expansion and hand the
     * star to the validator, which expands it against each table's <em>full</em> row type. That is the exact
     * opposite of the intent for a statement the subset accepts ({@code FROM entity a JOIN (SELECT …) ON …},
     * and its comma form). Naming the derived table changes nothing a caller can observe: an alias on a
     * derived table does not restrict how its columns are referenced, and the generated name is checked
     * against every name already used in this {@code FROM} so it cannot shadow one the caller wrote.
     *
     * <p>Only a query block is named. Anything else unaliased ({@code UNNEST}, a table function) is left as
     * it is and refused by {@link #expansion}, since wrapping a shape this subset does not accept would only
     * change which layer reports it.
     */
    private static SqlNode named(SqlJoin join, int index, SqlNode operand, AliasSource aliases) {
        if (operand == null || !SqlKind.QUERY.contains(operand.getKind())) {
            return operand;
        }
        final SqlParserPos pos = operand.getParserPosition();
        final SqlNode aliased = SqlStdOperatorTable.AS.createCall(
                pos, operand, new SqlIdentifier(aliases.next(), pos));
        join.setOperand(index, aliased);
        return aliased;
    }

    /**
     * Generates aliases for the unaliased derived tables of one {@code FROM}, avoiding every name that
     * {@code FROM} already uses — the aliases the caller wrote and the bare table names a qualified star
     * may equally name.
     */
    private static final class AliasSource {
        private final Set<String> taken = new HashSet<>();
        private int counter;

        AliasSource(SqlNode from) {
            collect(from);
        }

        private void collect(SqlNode node) {
            if (node == null) {
                return;
            }
            if (node instanceof SqlJoin join) {
                collect(join.getLeft());
                collect(join.getRight());
                return;
            }
            final SqlNode inner = unwrapAlias(node);
            final String name = aliasOf(node, inner instanceof SqlIdentifier table ? table : null);
            if (name != null) {
                taken.add(name.toLowerCase(Locale.ROOT));
            }
        }

        /** The next unused generated alias. Double-underscored, so a collision needs a deliberate name. */
        String next() {
            while (true) {
                final String candidate = "__t" + counter++;
                if (taken.add(candidate)) {
                    return candidate;
                }
            }
        }
    }

    /**
     * The operand of an {@code AS} call, or the node itself — a derived table may or may not be aliased — with
     * a hinted table reference ({@code t /*+ final *&#47;}, see {@link SqlFinalModifier}) seen through to its
     * table, so a {@code FINAL} entity expands exactly as the bare one does.
     */
    private static SqlNode unwrapAlias(SqlNode node) {
        SqlNode inner = node;
        if (inner != null && inner.getKind() == SqlKind.AS) {
            final List<SqlNode> operands = ((SqlCall) inner).getOperandList();
            inner = operands.isEmpty() ? null : operands.getFirst();
        }
        return inner instanceof SqlTableRef ref ? ref.operand(0) : inner;
    }

    /**
     * The name a qualified star may use: the {@code AS} alias when present, else the table's own name;
     * {@code null} for an unaliased derived table, which no qualifier can name.
     */
    private static String aliasOf(SqlNode from, SqlIdentifier table) {
        if (from.getKind() == SqlKind.AS) {
            final List<SqlNode> operands = ((SqlCall) from).getOperandList();
            if (operands.size() > 1 && operands.get(1) instanceof SqlIdentifier alias) {
                return alias.getSimple();
            }
        }
        return table == null ? null : table.names.get(table.names.size() - 1);
    }

    /**
     * The queryable entity {@code table} names, matched case-insensitively (the parser preserves the
     * caller's casing and the validator matches case-insensitively, so this must too); {@code null} when
     * it is not a queryable entity.
     */
    private static String resolveEntity(SqlIdentifier table, CalciteQueryCatalog.Snapshot snapshot) {
        final String name = table.names.get(table.names.size() - 1);
        for (final String entity : snapshot.entityWildcardFields().keySet()) {
            if (entity.equalsIgnoreCase(name)) {
                return entity;
            }
        }
        return null;
    }

    /**
     * The qualifier of a star select item — {@code ""} for a bare {@code *}, the table or alias name for
     * {@code t.*} and for the schema-qualified {@code adas.t.*} — or {@code null} when the item is not a
     * star this surface expands.
     *
     * <p>The three-part form has to be handled here rather than left to the validator. Every entity also
     * resolves under the data schema, and a client that discovered it through {@code INFORMATION_SCHEMA}
     * qualifies what it generates with the {@code TABLE_SCHEMA} it read — so {@code adas.t.*} is a shape
     * this surface actively steers clients into. Leaving it unexpanded would hand it to the validator,
     * which expands a star against the table's <em>full</em> row type: heavy columns and dotted
     * enrichment columns included, defeating the wildcard rule for a statement that differs from the
     * bare star only in a qualifier.
     *
     * <p>A deeper name, or a three-part name whose leading part is not the data schema, is left alone so
     * the validator reports the bad qualifier instead of this method silently accepting it.
     */
    private static String starQualifier(SqlNode item) {
        if (!(item instanceof SqlIdentifier identifier) || !identifier.isStar()) {
            return null;
        }
        final List<String> names = identifier.names;
        return switch (names.size()) {
            case 1 -> "";
            case 2 -> names.get(0);
            // adas.t.* — the table part is the qualifier the caller's expansion matches against the alias.
            case 3 -> InformationSchema.DATA_SCHEMA.equalsIgnoreCase(names.get(0)) ? names.get(1) : null;
            default -> null;
        };
    }

    /** The star's replacement items: one identifier per wildcard field, carrying the star's position. */
    private static List<SqlNode> fieldIdentifiers(
            String entity, String qualifier, SqlNode star, CalciteQueryCatalog.Snapshot snapshot) {
        final List<String> fields = snapshot.wildcardFields(entity);
        if (fields == null || fields.isEmpty()) {
            throw new SqlTranslationException(WildcardProjection.emptyExpansionMessage(entity));
        }
        final SqlParserPos pos = star.getParserPosition();
        final List<SqlNode> identifiers = new ArrayList<>(fields.size());
        for (final String field : fields) {
            // Keep the caller's qualifier so a qualified star stays unambiguous; wildcard fields are the
            // source's own columns, whose names never contain a dot, so a two-part name is safe here.
            identifiers.add(qualifier == null
                    ? new SqlIdentifier(field, pos)
                    : new SqlIdentifier(List.of(qualifier, field), pos));
        }
        return identifiers;
    }
}
