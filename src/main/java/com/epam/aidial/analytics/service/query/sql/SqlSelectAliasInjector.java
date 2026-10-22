package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.model.OutputColumnNaming;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Names the unnamed items of a parsed {@code SELECT} list by rewriting them into {@code AS} aliases,
 * <strong>before</strong> the statement is validated.
 *
 * <p>Calcite names an unnamed target-list item {@code EXPR$<n>} during validation, which leaks planner
 * internals into result column names and — more importantly — is not a name the statement can
 * reference: {@code SELECT count(*) FROM t ORDER BY count} is valid PostgreSQL (a bare {@code ORDER BY}
 * name resolves against output columns) but would fail validation against an output still called
 * {@code EXPR$0}. Injecting the name as a real alias first makes the derived name behave exactly like a
 * caller-written one, including Calcite's per-clause ambiguity rules (which, like PostgreSQL's, prefer
 * an input column in {@code GROUP BY}).
 *
 * <p>Base names mirror PostgreSQL's {@code FigureColname} over its own parse tree:
 *
 * <ul>
 *   <li>a function-syntax call → its lowercase operator name, so {@code count(*)} → {@code count} and
 *       {@code CHAR_LENGTH(x)} → {@code char_length} (the spelling the caller wrote, even where the DSL
 *       maps that call onto another catalog function);
 *   <li>{@code WITHIN GROUP} → the name of the aggregate it wraps, so
 *       {@code percentile_cont(0.5) WITHIN GROUP (ORDER BY x)} → {@code percentile_cont};
 *   <li>{@code CASE} → {@code case}; a windowed call ({@code sum(x) OVER (…)}) → the name of the
 *       function under {@code OVER};
 *   <li>anything else (literal, operator expression, {@code CAST}) → {@link OutputColumnNaming#UNNAMED}.
 * </ul>
 *
 * <p>Because the last rule reads the operator the <em>caller</em> wrote, this pass runs <strong>before</strong>
 * {@link SqlTextRebinder}, which swaps {@code ||} and a cast-to-character onto function calls: naming after
 * the rewritten tree would call an unaliased {@code CAST(project_id AS VARCHAR)} {@code to_string_cast} and
 * a {@code ||} expression {@code concat}, where PostgreSQL (and this service, before either rewrite existed)
 * says {@code ?column?}.
 *
 * <p>Items that already carry a name are left untouched: a bare identifier (Calcite names it after its
 * last component, as PostgreSQL does), an {@code AS} call, and a star — whose columns cannot be known
 * before validation, which is why {@link RelToStructuredQueryTranslator} keeps a safety net over the
 * validated names. Uniqueness is delegated to {@link OutputColumnNaming#assign} so the {@code _<n>}
 * suffixing matches the DSL surface exactly and Calcite is never left with a duplicate to rename.
 */
final class SqlSelectAliasInjector {

    private SqlSelectAliasInjector() {}

    /**
     * Rewrites the select list of {@code parsed} in place, aliasing every unnamed item. Accepts every
     * parse shape the statement whose outputs are named can arrive in — a bare {@code SqlSelect}, a
     * {@code SqlWith}, and a {@code SqlOrderBy} wrapping either — and ignores any other node (an
     * unsupported statement shape, rejected downstream with its own message).
     */
    static void injectAliases(SqlNode parsed) {
        final SqlSelect select = selectOf(parsed);
        if (select == null || select.getSelectList() == null) {
            return;
        }
        final SqlNodeList items = select.getSelectList();
        final List<String> explicit = new ArrayList<>(items.size());
        final List<String> bases = new ArrayList<>(items.size());
        for (final SqlNode item : items) {
            final boolean named = alreadyNamed(item);
            explicit.add(named ? nameOf(item) : null);
            // An already-named item is never rewritten, so it must not consume a derived name: a star
            // (named, but by columns only validation knows) would otherwise reserve `?column?` and push
            // a genuinely unnamed sibling to `?column?_1`.
            bases.add(named ? null : baseName(item));
        }
        final List<String> names = OutputColumnNaming.assign(explicit, bases);

        boolean rewritten = false;
        final List<SqlNode> aliased = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            final SqlNode item = items.get(i);
            if (alreadyNamed(item)) {
                aliased.add(item);
                continue;
            }
            aliased.add(SqlStdOperatorTable.AS.createCall(
                    SqlParserPos.ZERO, item, new SqlIdentifier(names.get(i), SqlParserPos.ZERO)));
            rewritten = true;
        }
        if (rewritten) {
            select.setSelectList(new SqlNodeList(aliased, items.getParserPosition()));
        }
    }

    /** The {@code SqlSelect} to rewrite, unwrapping an {@code ORDER BY} and a {@code WITH}; {@code null} if there is none. */
    private static SqlSelect selectOf(SqlNode parsed) {
        return switch (parsed) {
            case SqlSelect select -> select;
            // The ORDER BY case recurses rather than matching a SqlSelect directly, because Calcite wraps a
            // top-level ORDER BY around the whole query it sorts, WITH included: `WITH c AS (…) SELECT …
            // ORDER BY x` is SqlOrderBy(SqlWith(…)). Matching only SqlOrderBy(SqlSelect) would skip alias
            // injection for that shape and leave its unnamed items to Calcite's EXPR$<n>.
            case SqlOrderBy order -> selectOf(order.query);
            // A WITH names derived tables; the statement whose outputs are named is its body.
            case SqlWith with -> selectOf(with.body);
            // A set operation takes its output names from its FIRST branch — ClickHouse's rule as well as
            // the standard's — so that is the select list whose unnamed items must be aliased, or the
            // statement's columns would come back as Calcite's EXPR$<n>.
            case SqlCall call when SqlSetOperations.isSetOp(call) -> selectOf(call.getOperandList().getFirst());
            case null, default -> null;
        };
    }

    /**
     * Whether the item carries a name already: an {@code AS} alias, or a plain identifier (including a
     * star, which validation expands into the underlying column names).
     */
    private static boolean alreadyNamed(SqlNode item) {
        return item.getKind() == SqlKind.AS || item instanceof SqlIdentifier;
    }

    /**
     * The name an already-named item occupies, so a derived name never collides with it. An {@code AS}
     * alias is its second operand; an identifier is named after its last component (a quoted flat
     * enrichment name such as {@code "deployment_ref.display_name"} is one component, dots included).
     * A star occupies no single name.
     */
    private static String nameOf(SqlNode item) {
        if (item.getKind() == SqlKind.AS) {
            final List<SqlNode> operands = ((SqlCall) item).getOperandList();
            return operands.size() > 1 && operands.get(1) instanceof SqlIdentifier alias ? alias.getSimple() : null;
        }
        if (item instanceof SqlIdentifier identifier) {
            return identifier.isStar() ? null : identifier.names.get(identifier.names.size() - 1);
        }
        return null;
    }

    /** The PostgreSQL-shaped base name for an unnamed select item. */
    private static String baseName(SqlNode item) {
        // PostgreSQL names a CASE target "case" and a windowed call after the function under OVER.
        if (item.getKind() == SqlKind.CASE) {
            return "case";
        }
        if (item instanceof SqlBasicCall call) {
            final SqlOperator operator = call.getOperator();
            if ((operator.getKind() == SqlKind.WITHIN_GROUP || operator.getKind() == SqlKind.OVER)
                    && !call.getOperandList().isEmpty()) {
                return baseName(call.getOperandList().getFirst());
            }
            if (isFunctionSyntax(operator)) {
                return operator.getName().toLowerCase(Locale.ROOT);
            }
        }
        return OutputColumnNaming.UNNAMED;
    }

    /** Function-call syntax — PostgreSQL names a {@code FuncCall} after the function, an operator not. */
    private static boolean isFunctionSyntax(SqlOperator operator) {
        final SqlSyntax syntax = operator.getSyntax();
        return syntax == SqlSyntax.FUNCTION || syntax == SqlSyntax.FUNCTION_ID || syntax == SqlSyntax.FUNCTION_STAR;
    }
}
