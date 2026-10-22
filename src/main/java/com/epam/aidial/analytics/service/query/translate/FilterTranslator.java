package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.DocumentComparisons;
import com.epam.aidial.analytics.service.query.ObjectPathUsage;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Select;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates the recursive filter tree into a jOOQ {@link Condition}. Logical nodes map to
 * {@code AND}/{@code OR}/{@code NOT}; comparison nodes are treated as binary {@code left op right}
 * where the left operand is any translatable expression and the right is a literal, field, or — for
 * {@code in} — an array of literals. A {@code null} tree means "no filter" ({@code TRUE}).
 *
 * <p>Used for both {@code filter} (against base-field bindings) and {@code having} (against bindings
 * augmented with aggregate aliases) — the binding map supplied by the caller decides which names are
 * resolvable.
 */
@Component
public class FilterTranslator {

    /**
     * ClickHouse's canonical serializer for a native {@code JSON} value — the rendering every document
     * comparison is defined over, applied to the column and to the caller's literal alike.
     */
    private static final String CANONICAL_JSON = "toJSONString";

    private final ExprTranslator exprTranslator;
    private final Map<FilterNode, Condition> pushed;

    @Autowired
    public FilterTranslator(ExprTranslator exprTranslator) {
        this(exprTranslator, Map.of());
    }

    private FilterTranslator(ExprTranslator exprTranslator, Map<FilterNode, Condition> pushed) {
        this.exprTranslator = exprTranslator;
        this.pushed = pushed;
    }

    /**
     * A translator whose subquery predicates and scalar subqueries render through {@code subqueries}. Only
     * the composite SQL renderer asks for one; the shared bean refuses every subquery.
     */
    public FilterTranslator withSubqueryRenderer(SubqueryRenderer subqueries) {
        return new FilterTranslator(exprTranslator.withSubqueryRenderer(subqueries), pushed);
    }

    /**
     * A translator for one statement whose operands render through {@code expressions} and which renders each
     * node of {@code conjuncts} — matched by value — as the given condition instead of translating it: the read
     * path's per-statement substitution of the calls and conjuncts it computes inside an enrichment's derived
     * table. A substituted conjunct was translated, and so validated, when the derived table was built.
     */
    FilterTranslator withPushed(ExprTranslator expressions, Map<? extends FilterNode, ? extends Condition> conjuncts) {
        return new FilterTranslator(expressions, Map.copyOf(conjuncts));
    }

    public Condition toCondition(FilterNode node, Map<String, QueryFieldBinding> bindings) {
        return toCondition(node, bindings, WindowPosition.FORBIDDEN);
    }


    private Condition toCondition(FilterNode node, Map<String, QueryFieldBinding> bindings, WindowPosition windows) {
        if (node == null) {
            return DSL.trueCondition();
        }
        final Condition substituted = pushed.isEmpty() ? null : pushed.get(node);
        if (substituted != null) {
            return substituted;
        }
        return switch (node) {
            case LogicalNode logical -> toLogical(logical, bindings, windows);
            case ComparisonNode comparison -> toComparison(comparison, bindings, windows);
            case SubqueryNode subquery -> toSubquery(subquery, bindings);
        };
    }

    /**
     * Records how {@code node} addresses each {@code object} column, for a caller that keys its grain joins
     * and can prune their projection. Delegates to the expression translator, which owns the rule.
     *
     * @see ExprTranslator#collectObjectPaths(FilterNode, Map, ObjectPathUsage.Builder)
     */
    public void collectObjectPaths(
            FilterNode node, Map<String, QueryFieldBinding> bindings, ObjectPathUsage.Builder into) {
        exprTranslator.collectObjectPaths(node, bindings, into);
    }

    /**
     * The condition of a {@code case} branch, which is a filter tree standing inside an <em>expression</em>
     * rather than in {@code filter}/{@code having}. A {@code case} is only ever rendered from a select
     * entry — {@code StructuredQueryBuilder} refuses a window anywhere in {@code filter}/{@code having}
     * before either tree is translated, and every other filter position refuses a {@code case} carrying one
     * through the per-comparison check below — so a window here is still a window in {@code select}, and is
     * admitted: {@code CASE WHEN row_number() OVER (…) > 5 THEN … END} is a select-list expression the
     * engine computes after the block's result exists, exactly like a bare window column.
     */
    public Condition toCaseCondition(FilterNode node, Map<String, QueryFieldBinding> bindings) {
        return toCondition(node, bindings, WindowPosition.ALLOWED);
    }

    /** Whether the filter tree being translated may carry a window expression as a comparison operand. */
    private enum WindowPosition { FORBIDDEN, ALLOWED }

    /**
     * {@code IN}/{@code NOT IN} over one operand or a tuple, and {@code EXISTS}/{@code NOT EXISTS}. The
     * subquery is rendered by the composite renderer this translator was given; the shared bean's
     * renderer refuses, so the DSL surface cannot reach here.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Condition toSubquery(SubqueryNode node, Map<String, QueryFieldBinding> bindings) {
        if (node.subquery() == null) {
            throw new QueryValidationException("a subquery predicate requires a query");
        }
        final Select select = exprTranslator.subqueryRenderer().render(node.subquery());
        final List<Expr> operands = node.operands() == null ? List.of() : node.operands();
        return switch (node.kind()) {
            case EXISTS -> DSL.exists(select);
            case NOT_EXISTS -> DSL.notExists(select);
            case IN, NOT_IN -> {
                if (operands.isEmpty()) {
                    throw new QueryValidationException("an IN subquery predicate requires at least one operand");
                }
                final Condition in;
                if (operands.size() == 1) {
                    final Field left = exprTranslator.toField(operands.getFirst(), bindings);
                    in = node.kind() == SubqueryNode.SubqueryKind.IN ? left.in(select) : left.notIn(select);
                } else {
                    final List<Field<?>> fields = new ArrayList<>(operands.size());
                    for (final Expr operand : operands) {
                        fields.add(exprTranslator.toField(operand, bindings));
                    }
                    in = node.kind() == SubqueryNode.SubqueryKind.IN
                            ? DSL.row(fields).in(select) : DSL.row(fields).notIn(select);
                }
                yield in;
            }
        };
    }

    private Condition toLogical(LogicalNode node, Map<String, QueryFieldBinding> bindings, WindowPosition windows) {
        final List<FilterNode> args = node.args() == null ? List.of() : node.args();
        return switch (node.op()) {
            case AND -> DSL.and(translateAll(args, bindings, windows));
            case OR -> DSL.or(translateAll(args, bindings, windows));
            case NOT -> {
                if (args.size() != 1) {
                    throw new QueryValidationException("'not' expects exactly one child node");
                }
                yield DSL.not(toCondition(args.getFirst(), bindings, windows));
            }
        };
    }

    private List<Condition> translateAll(
            List<FilterNode> nodes, Map<String, QueryFieldBinding> bindings, WindowPosition windows) {
        if (nodes.isEmpty()) {
            throw new QueryValidationException("'and'/'or' require at least one child node");
        }
        final List<Condition> conditions = new ArrayList<>(nodes.size());
        for (final FilterNode child : nodes) {
            conditions.add(toCondition(child, bindings, windows));
        }
        return conditions;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Condition toComparison(
            ComparisonNode node, Map<String, QueryFieldBinding> bindings, WindowPosition windows) {
        final List<Expr> args = node.args() == null ? List.of() : node.args();
        if (args.size() != 2) {
            throw new QueryValidationException("comparison '" + node.op().code() + "' expects exactly two arguments");
        }
        // An array-valued aggregate has no comparison in the DSL, and a `having` clause reaches an aggregate
        // output through its alias rather than by restating the call — so nothing expressible is lost.
        for (final Expr arg : args) {
            ArrayValuedFunctions.rejectAsOperand(arg, "an operand of comparison '" + node.op().code() + "'");
            // A window is computed over the block's result, after every predicate has run: SQL forbids it
            // in WHERE and HAVING, and so does the DSL, rather than let ClickHouse phrase the refusal. A
            // `case` branch condition is exempt: that tree stands inside a select-list expression, not in a
            // predicate, so the window is still computed where SQL allows one.
            if (windows == WindowPosition.FORBIDDEN) {
                WindowExpressions.rejectNested(arg, "a filter or having comparison");
            }
        }
        final Expr leftExpr = args.get(0);
        final Expr right = args.get(1);
        final ComparisonOp op = node.op();

        // The operand-type gate, before anything is rendered. Without it a non-scalar operand reached
        // ClickHouse and came back as a relayed type error (Code 43 / Code 117) — or, for `eq` on an
        // object field against a well-formed JSON literal, as a silently empty result, since native
        // equality on a JSON column matches nothing. `/v1/queries/translate` runs this same pass before
        // rendering (QueryTranslationService), so the execute and translate surfaces cannot disagree.
        final QueryFieldType document = documentOperandType(leftExpr, op, right, bindings);
        rejectProjectionOnlyOperand(right, bindings, "the right operand of comparison '" + op.code() + "' over");

        final Field left = exprTranslator.toField(leftExpr, bindings);
        if (document != null) {
            return documentComparison(left, op, right, ((FieldExpr) leftExpr).name());
        }

        if (op == ComparisonOp.IN) {
            return left.in(inValues(right, bindings));
        }
        if (isNullLiteral(right)) {
            return switch (op) {
                case EQ -> left.isNull();
                case NE -> left.isNotNull();
                default -> throw new QueryValidationException(
                        "null literal is only valid with 'eq'/'ne', not '" + op.code() + "'");
            };
        }
        // The contains family is the filter grammar's spelling of the `contains` function: one LIKE over one
        // text operand. It therefore takes the same text check ExprTranslator applies to that function —
        // string or uuid, never enum — so the two spellings of the operation accept the same operands, and an
        // `enum` (or any other non-text) left operand is refused here rather than sent to ClickHouse as a LIKE
        // the server has no overload for.
        if (op == ComparisonOp.CO || op == ComparisonOp.NC || op == ComparisonOp.ICO || op == ComparisonOp.INC) {
            ExprTranslator.requireTextOperand(
                    "comparison '" + op.code() + "'", args.getFirst(), bindings, "left operand");
        }
        return switch (op) {
            // No explicit ESCAPE clause: ClickHouse's LIKE/ILIKE reject `… ESCAPE 'c'` syntax but treat
            // backslash as the escape character by default — which is exactly what containsPattern() emits.
            case CO -> ((Field<String>) left).like(containsPattern(right));
            case NC -> ((Field<String>) left).notLike(containsPattern(right));
            case ICO -> ((Field<String>) left).likeIgnoreCase(containsPattern(right));
            case INC -> ((Field<String>) left).notLikeIgnoreCase(containsPattern(right));
            case EQ -> left.eq(exprTranslator.toField(right, bindings));
            case NE -> left.ne(exprTranslator.toField(right, bindings));
            case LT -> left.lt(exprTranslator.toField(right, bindings));
            case GT -> left.gt(exprTranslator.toField(right, bindings));
            case LE -> left.le(exprTranslator.toField(right, bindings));
            case GE -> left.ge(exprTranslator.toField(right, bindings));
            case IN -> throw new IllegalStateException("'in' handled above");
        };
    }

    private List<Object> inValues(Expr right, Map<String, QueryFieldBinding> bindings) {
        if (!(right instanceof ArrayExpr(List<Expr> arrayItems))) {
            throw new QueryValidationException("'in' requires an array as its right operand");
        }
        final List<Expr> items = arrayItems == null ? List.of() : arrayItems;
        if (items.isEmpty()) {
            throw new QueryValidationException("'in' requires a non-empty array");
        }
        final List<Object> values = new ArrayList<>(items.size());
        for (final Expr item : items) {
            if (!(item instanceof ValueExpr value)) {
                throw new QueryValidationException("'in' array items must be literal values");
            }
            values.add(exprTranslator.toField(value, bindings));
        }
        return values;
    }

    // ------------------------------------------------- projection-only operands and document comparisons

    /**
     * The declared type of a left operand that is a projection-only field {@code op} admits as a
     * <b>document comparison</b>, or {@code null} when the operand is an ordinary scalar expression.
     *
     * <p>Rejects the operand outright when its type admits no such comparison ({@code array} and
     * {@code map}: neither has a canonical text rendering the SQL subset can name, so neither would
     * round-trip), when the operator is one an {@code object} field does not admit (the ordering
     * comparisons — lexicographic order over a serialization is not an order over documents), or when the
     * right operand is a null literal. That last one is not a document comparison at all: the DSL spells
     * {@code IS NULL} as {@code eq} against null, and a ClickHouse {@code JSON} column is never null, so
     * accepting it would hand back a predicate that is constant false for every row.
     */
    private static QueryFieldType documentOperandType(
            Expr left, ComparisonOp op, Expr right, Map<String, QueryFieldBinding> bindings) {
        final QueryFieldType type = projectionOnlyType(left, bindings);
        if (type == null) {
            return null;
        }
        final String field = ((FieldExpr) left).name();
        if (isNullLiteral(right)) {
            throw new QueryValidationException(DocumentComparisons.rejectionMessage(field, type,
                    "a null test on", "is not supported — such a column is never null"));
        }
        if (!DocumentComparisons.accepts(type, op)) {
            throw new QueryValidationException(DocumentComparisons.rejectionMessage(field, type,
                    "comparison '" + op.code() + "' over", "is not supported"));
        }
        return type;
    }

    /** Rejects a projection-only field in a position that admits no comparison over one at all. */
    private static void rejectProjectionOnlyOperand(
            Expr expr, Map<String, QueryFieldBinding> bindings, String use) {
        final QueryFieldType type = projectionOnlyType(expr, bindings);
        if (type != null) {
            throw new QueryValidationException(DocumentComparisons.rejectionMessage(
                    ((FieldExpr) expr).name(), type, use, "is not supported"));
        }
    }

    /**
     * The declared type of {@code expr} when it is a bare field of a non-scalar type, else {@code null}.
     * An unknown field name yields {@code null} so it falls through to {@code toField}'s ordinary
     * "unknown field" error rather than a type-specific one.
     */
    private static QueryFieldType projectionOnlyType(Expr expr, Map<String, QueryFieldBinding> bindings) {
        if (!(expr instanceof FieldExpr(String name))) {
            return null;
        }
        final QueryFieldBinding binding = bindings.get(name);
        return binding != null && !ExprFieldType.isScalar(binding.type()) ? binding.type() : null;
    }

    /**
     * Renders a comparison over an {@code object} field's <b>canonical JSON text</b>. The contains family
     * matches a substring of it; {@code eq}/{@code ne}/{@code in} canonicalize the right-hand literal by
     * the same function and compare documents, so the match does not depend on the key order, whitespace
     * or number format the caller wrote.
     */
    private Condition documentComparison(Field<?> left, ComparisonOp op, Expr right, String field) {
        final Field<String> canonical = canonicalDocument(left);
        return switch (op) {
            case CO -> canonical.like(containsPattern(right));
            case NC -> canonical.notLike(containsPattern(right));
            case ICO -> canonical.likeIgnoreCase(containsPattern(right));
            case INC -> canonical.notLikeIgnoreCase(containsPattern(right));
            case EQ -> canonical.eq(canonicalLiteral(jsonDocument(right, field, op)));
            case NE -> canonical.ne(canonicalLiteral(jsonDocument(right, field, op)));
            case IN -> canonical.in(canonicalLiterals(right, field, op));
            // DocumentComparisons.accepts admits exactly the seven above; a new member reaching here
            // means the set and this switch drifted, which is a bug rather than a caller error.
            default -> throw new IllegalStateException(
                    "operator '" + op.code() + "' is not a document comparison");
        };
    }

    /**
     * The canonical JSON text of a stored document. ClickHouse's {@code toJSONString} over a native
     * {@code JSON} column orders keys lexicographically at every level, strips insignificant whitespace
     * and normalizes numbers, while keeping the string/number distinction.
     */
    private static Field<String> canonicalDocument(Field<?> document) {
        return DSL.function(CANONICAL_JSON, String.class, document);
    }

    /**
     * The canonical JSON text of a caller's literal, produced by the <b>same</b> ClickHouse function that
     * canonicalizes the column. Canonicalizing in Java instead would mean reproducing ClickHouse's number
     * normalization, key collation and escaping exactly, and any divergence would surface as a filter that
     * silently matches nothing — the defect this rendering exists to remove. The literal is bound, never
     * inlined; {@link #jsonDocument} has already proved it parses, so the cast cannot raise.
     */
    private static Field<String> canonicalLiteral(String json) {
        return DSL.function(CANONICAL_JSON, String.class,
                DSL.field("CAST({0} AS JSON)", Object.class, DSL.val(json)));
    }

    /** The JSON document a document comparison's right operand must be, or a 400 naming why it is not. */
    private static String jsonDocument(Expr right, String field, ComparisonOp op) {
        // A literal of the wrong value_type goes through the same "not a JSON document" sentence the SQL
        // front end produces for it, naming the value; only a non-literal operand — which has no text to
        // canonicalize — takes the second wording. Both come from DocumentComparisons so the two surfaces
        // cannot be told apart by the message.
        if (!(right instanceof ValueExpr(ValueType valueType, String value))) {
            throw new QueryValidationException(DocumentComparisons.nonLiteralOperandMessage(field, op));
        }
        if (valueType != ValueType.STRING || !DocumentComparisons.isJsonDocument(value)) {
            throw new QueryValidationException(DocumentComparisons.jsonLiteralMessage(field, op, value));
        }
        return value;
    }

    /** The {@code in} list's items, each a canonicalized JSON document. */
    private static List<Field<String>> canonicalLiterals(Expr right, String field, ComparisonOp op) {
        if (!(right instanceof ArrayExpr(List<Expr> arrayItems))) {
            throw new QueryValidationException("'in' requires an array as its right operand");
        }
        final List<Expr> items = arrayItems == null ? List.of() : arrayItems;
        if (items.isEmpty()) {
            throw new QueryValidationException("'in' requires a non-empty array");
        }
        final List<Field<String>> values = new ArrayList<>(items.size());
        for (final Expr item : items) {
            values.add(canonicalLiteral(jsonDocument(item, field, op)));
        }
        return values;
    }

    private static boolean isNullLiteral(Expr expr) {
        return expr instanceof ValueExpr value && value.valueType() == ValueType.NULL;
    }

    /**
     * Builds the {@code %…%} LIKE pattern for a contains operand ({@code co}/{@code nc}/{@code ico}/
     * {@code inc}), escaping the LIKE metacharacters ({@code %}, {@code _}, and the escape character
     * itself) so the operand matches literally rather than as a wildcard pattern.
     */
    private static String containsPattern(Expr right) {
        if (right instanceof ValueExpr(ValueType valueType, String value) && valueType == ValueType.STRING && value != null) {
            return "%" + escapeLike(value) + "%";
        }
        throw new QueryValidationException("'co'/'nc'/'ico'/'inc' require a string literal right operand");
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
