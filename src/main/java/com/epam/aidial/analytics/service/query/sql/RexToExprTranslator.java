package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.DocumentComparisons;
import com.epam.aidial.analytics.service.query.HigherOrderFunctions;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.FrameBound;
import com.epam.aidial.analytics.service.query.model.FrameBoundKind;
import com.epam.aidial.analytics.service.query.model.FrameUnit;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowFrame;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexFieldCollation;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.rex.RexWindow;
import org.apache.calcite.rex.RexWindowBound;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlTrimFunction;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.TimestampString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Translates Calcite {@link RexNode} trees into DSL {@link Expr}/{@link FilterNode} trees against a
 * fixed input row: {@code inputs.get(i)} is the already-translated DSL expression for input ordinal
 * {@code i}. Fails closed: any operator, literal family, or shape outside the DSL surface raises
 * {@link SqlTranslationException} naming the construct.
 *
 * <p>Cast tolerance: Calcite's validator injects casts the user never wrote, so
 * identity/widening casts within one type family are unwrapped (literals are retyped to the target
 * family); every other {@code CAST} is rejected — including one to a character type, which is either the
 * validator's own coercion of a non-text operand (rejected, as the type rules promise) or, when the caller
 * wrote it, already a {@link SqlQueryOperators#TO_STRING_CAST} marker call by the time the plan exists
 * ({@link SqlTextRebinder}), read here by {@link #toStringCast}.
 * {@code LIKE}/{@code ILIKE} map only to the DSL's
 * contains operators ({@code co}/{@code nc} case-sensitive, {@code ico}/{@code inc}
 * case-insensitive) for exact {@code '%text%'} patterns.
 */
final class RexToExprTranslator {

    /** The refusal for a subquery that reaches into its enclosing block. */
    static final String CORRELATION_MESSAGE = "correlated subqueries (a subquery referencing a column of the enclosing"
            + " statement) are not supported; rewrite the statement as a JOIN";

    /** The window-only ranking functions, lowered to their zero-argument DSL names. */
    private static final Map<String, String> RANKING_FUNCTIONS = Map.of(
            "ROW_NUMBER", "row_number",
            "RANK", "rank",
            "DENSE_RANK", "dense_rank");

    /**
     * One accepted interval qualifier: the DSL unit it lowers to, and how many of the literal's own base
     * units make up one of them.
     *
     * <p>A Calcite interval literal does not carry the number the statement wrote — it carries a magnitude
     * in the qualifier family's base unit: <b>milliseconds</b> for a day-time qualifier and <b>months</b>
     * for a year-month one. {@code INTERVAL 2 HOUR} is therefore {@code 7200000} typed {@code INTERVAL_HOUR}
     * and {@code INTERVAL 1 YEAR} is {@code 12} typed {@code INTERVAL_YEAR}. Dividing by this factor
     * recovers the written amount, and requiring an exact quotient is what rejects a fractional interval.
     */
    private record IntervalUnit(String dslUnit, BigDecimal baseUnitsPerUnit) {
        private static IntervalUnit millis(String dslUnit, long perUnit) {
            return new IntervalUnit(dslUnit, BigDecimal.valueOf(perUnit));
        }
    }

    /**
     * The interval qualifiers accepted as a datetime operand, keyed by the literal's own {@link SqlTypeName}.
     *
     * <p><b>Enumerated, not filtered.</b> Calcite has a dozen-odd interval type names, most of them compound
     * ({@code INTERVAL_YEAR_MONTH}, {@code INTERVAL_DAY_SECOND}, …), and a compound qualifier names two
     * offsets at once — which no single catalog call carries, and keeping one field would answer a different
     * question than was asked. Listing the six that map cleanly means every other qualifier, including one a
     * future Calcite adds, fails closed rather than being read approximately.
     *
     * <p>{@code WEEK} and {@code QUARTER} are absent because they never arrive: Calcite lowers them to
     * {@code INTERVAL_DAY} and {@code INTERVAL_MONTH} during validation, so {@code INTERVAL 1 WEEK} reaches
     * here as 604800000 ms of {@code INTERVAL_DAY} and normalizes to {@code 7 day} — an exact identity for
     * an offset, which is why the DSL needs neither unit.
     */
    private static final Map<SqlTypeName, IntervalUnit> INTERVAL_UNITS = Map.of(
            SqlTypeName.INTERVAL_SECOND, IntervalUnit.millis("second", 1_000L),
            SqlTypeName.INTERVAL_MINUTE, IntervalUnit.millis("minute", 60_000L),
            SqlTypeName.INTERVAL_HOUR, IntervalUnit.millis("hour", 3_600_000L),
            SqlTypeName.INTERVAL_DAY, IntervalUnit.millis("day", 86_400_000L),
            SqlTypeName.INTERVAL_MONTH, IntervalUnit.millis("month", 1L),
            SqlTypeName.INTERVAL_YEAR, IntervalUnit.millis("year", 12L));

    private final List<Expr> inputs;
    private final Map<String, QueryFieldType> fieldTypes;
    /** Folds a subquery's relational tree into its own plan; refuses where no subquery may appear. */
    private final Function<RelNode, CompositeQuery> subqueries;

    RexToExprTranslator(List<Expr> inputs, Map<String, QueryFieldType> fieldTypes) {
        this(inputs, fieldTypes, rel -> {
            throw new SqlTranslationException("a subquery is not supported in this position");
        });
    }

    RexToExprTranslator(List<Expr> inputs, Map<String, QueryFieldType> fieldTypes,
                        Function<RelNode, CompositeQuery> subqueries) {
        this.inputs = inputs;
        this.fieldTypes = fieldTypes;
        this.subqueries = subqueries;
    }

    // ---------------------------------------------------------------- filters

    /** Translates a boolean condition (WHERE/HAVING) into a DSL filter tree. */
    FilterNode toFilter(RexNode node) {
        if (node instanceof RexInputRef ref) {
            // A bare boolean column used as a predicate: WHERE success.
            return requireBoolean(ref, "a bare column predicate");
        }
        if (node instanceof RexFieldAccess access && access.getReferenceExpr() instanceof RexCorrelVariable) {
            throw new SqlTranslationException(CORRELATION_MESSAGE);
        }
        if (!(node instanceof RexCall call)) {
            throw unsupported(node);
        }
        if (call instanceof RexSubQuery subQuery) {
            return subqueryPredicate(subQuery, false);
        }
        return switch (call.getKind()) {
            case AND -> new LogicalNode(LogicalOp.AND, filters(call.getOperands()));
            case OR -> orFilter(call);
            case NOT -> {
                // NOT IN / NOT EXISTS arrive as NOT over the subquery predicate; carried as the negated kind so
                // the rendering is the idiomatic one rather than NOT (x IN (…)).
                if (call.getOperands().getFirst() instanceof RexSubQuery negated
                        && (negated.getKind() == SqlKind.IN || negated.getKind() == SqlKind.EXISTS)) {
                    yield subqueryPredicate(negated, true);
                }
                yield new LogicalNode(LogicalOp.NOT, List.of(toFilter(call.getOperands().getFirst())));
            }
            case EQUALS -> comparison(ComparisonOp.EQ, call);
            case NOT_EQUALS -> comparison(ComparisonOp.NE, call);
            case LESS_THAN -> comparison(ComparisonOp.LT, call);
            case GREATER_THAN -> comparison(ComparisonOp.GT, call);
            case LESS_THAN_OR_EQUAL -> comparison(ComparisonOp.LE, call);
            case GREATER_THAN_OR_EQUAL -> comparison(ComparisonOp.GE, call);
            case IS_NULL -> nullComparison(ComparisonOp.EQ, call);
            case IS_NOT_NULL -> nullComparison(ComparisonOp.NE, call);
            case IS_TRUE -> booleanComparison(call, true);
            case IS_FALSE -> booleanComparison(call, false);
            case LIKE -> likeFilter(call);
            case SEARCH -> SargExpander.expand(call, this);
            // A boolean-returning catalog call used directly as a predicate: WHERE array_has(roles, 'x').
            // The DSL has no standalone boolean node in a filter tree, so it becomes the comparison the
            // grammar does have — `<call> = TRUE` — which FilterTranslator renders back to the bare
            // predicate. Previously only a bare boolean COLUMN was accepted here, so every boolean scalar
            // in the catalog was unusable in WHERE; the array predicates need it, and admitting them
            // without the rest would be an arbitrary line through one operand type.
            default -> {
                if (call.getType().getSqlTypeName() == SqlTypeName.BOOLEAN) {
                    yield comparisonOf(ComparisonOp.EQ, toExpr(call),
                            new ValueExpr(ValueType.BOOLEAN, "true"));
                }
                throw unsupported(call);
            }
        };
    }

    private List<FilterNode> filters(List<RexNode> operands) {
        final List<FilterNode> nodes = new ArrayList<>(operands.size());
        for (final RexNode operand : operands) {
            nodes.add(toFilter(operand));
        }
        return nodes;
    }

    /**
     * OR chains of equality tests on one field fold back into the DSL {@code in} operator (Calcite may
     * expand small IN lists into ORs before this translator sees them); anything else stays an OR node.
     */
    private FilterNode orFilter(RexCall call) {
        final List<FilterNode> children = filters(call.getOperands());
        FieldExpr field = null;
        final List<Expr> values = new ArrayList<>(children.size());
        for (final FilterNode child : children) {
            if (!(child instanceof ComparisonNode(ComparisonOp op, List<Expr> args))
                    || op != ComparisonOp.EQ
                    || !(args.get(0) instanceof FieldExpr left)
                    || !(args.get(1) instanceof ValueExpr value)
                    || value.valueType() == ValueType.NULL) {
                return new LogicalNode(LogicalOp.OR, children);
            }
            if (field == null) {
                field = left;
            } else if (!field.equals(left)) {
                return new LogicalNode(LogicalOp.OR, children);
            }
            values.add(value);
        }
        return new ComparisonNode(ComparisonOp.IN, List.of(field, new ArrayExpr(values)));
    }

    private ComparisonNode comparison(ComparisonOp op, RexCall call) {
        return comparisonOf(op, toExpr(call.getOperands().get(0)), toExpr(call.getOperands().get(1)));
    }

    /** Builds a comparison, retyping a literal operand to its field counterpart's declared type. */
    ComparisonNode comparisonOf(ComparisonOp op, Expr left, Expr right) {
        rejectComparisonOperands(op, left, right);
        requireJsonLiteral(left, op, right);
        if (left instanceof FieldExpr(String name) && right instanceof ValueExpr value) {
            right = retype(value, fieldTypes.get(name));
        } else if (right instanceof FieldExpr(String name) && left instanceof ValueExpr value) {
            left = retype(value, fieldTypes.get(name));
        }
        return new ComparisonNode(op, List.of(left, right));
    }

    /**
     * The projection-only gate for a comparison's two operands. The document carve-out is a property of
     * the comparison's <b>shape</b>, not of one operand on its own: the DSL surface admits exactly
     * {@code <object field> <op> <character literal>} — {@code FilterTranslator} reads the carve-out off
     * the <em>left</em> operand only and refuses any projection-only field on the right — so admitting an
     * {@code object} field in either position independently would let {@code translate-sql} hand back a
     * document {@code /v1/queries/execute} then refuses ({@code 'x' = jwt_claims},
     * {@code jwt_claims = event_kind} and {@code request_tags = jwt_claims} all would).
     *
     * <p>So the right operand keeps the blanket rejection, worded exactly as the DSL surface words it —
     * a caller must not be able to tell which surface refused the query — and the left one is admitted
     * only when the right operand is the character literal the carve-out is defined against. A left
     * {@code object} field whose counterpart is not a literal names that reason rather than claiming no
     * comparison over the type exists, which for {@code object} is no longer true.
     */
    private void rejectComparisonOperands(ComparisonOp op, Expr left, Expr right) {
        rejectLeftComparisonOperand(left, op, isCharacterLiteral(right));
        rejectProjectionOnlyOperand(right, "the right operand of comparison '" + op.code() + "' over");
    }

    private void rejectLeftComparisonOperand(Expr left, ComparisonOp op, boolean literalRight) {
        if (literalRight) {
            rejectProjectionOnlyOperand(left, op, "a comparison on");
            return;
        }
        if (left instanceof FieldExpr(String name)
                && SqlProjectionOnlyTypes.isComparableAs(fieldTypes.get(name), op)) {
            SqlProjectionOnlyTypes.reject(name, fieldTypes.get(name),
                    "a comparison against a non-literal operand on");
        }
        rejectProjectionOnlyOperand(left, "a comparison on");
    }

    /** A character literal — the only right operand the document carve-out is defined against. */
    private static boolean isCharacterLiteral(Expr expr) {
        return expr instanceof ValueExpr value && value.valueType() == ValueType.STRING;
    }

    /**
     * A document comparison's right operand must be a JSON document. Checked here as well as on the DSL
     * surface because {@code POST /v1/queries/translate-sql} translates <b>without</b> a builder pass — so
     * without this it would hand back a DSL document that {@code /v1/queries/execute} then refuses, which
     * is exactly the round-trip asymmetry the two endpoints exist to rule out.
     */
    private void requireJsonLiteral(Expr left, ComparisonOp op, Expr right) {
        if (!(left instanceof FieldExpr(String name))
                || !DocumentComparisons.requiresJsonLiteral(op)
                || !DocumentComparisons.accepts(fieldTypes.get(name), op)) {
            return;
        }
        if (right instanceof ValueExpr value && !DocumentComparisons.isJsonDocument(value.value())) {
            throw new SqlTranslationException(
                    DocumentComparisons.jsonLiteralMessage(name, op, value.value()));
        }
    }

    private ComparisonNode nullComparison(ComparisonOp op, RexCall call) {
        final Expr operand = toExpr(call.getOperands().getFirst());
        rejectProjectionOnlyOperand(operand, "IS [NOT] NULL on");
        return new ComparisonNode(op, List.of(operand, new ValueExpr(ValueType.NULL, null)));
    }

    private ComparisonNode booleanComparison(RexCall call, boolean value) {
        return comparisonOf(
                ComparisonOp.EQ,
                toExpr(call.getOperands().getFirst()),
                new ValueExpr(ValueType.BOOLEAN, Boolean.toString(value)));
    }

    private ComparisonNode requireBoolean(RexNode node, String context) {
        if (node.getType().getSqlTypeName() != SqlTypeName.BOOLEAN) {
            throw unsupported(node);
        }
        return comparisonOf(ComparisonOp.EQ, toExpr(node), new ValueExpr(ValueType.BOOLEAN, "true"));
    }

    /**
     * {@code LIKE}/{@code ILIKE} map to the DSL contains operators only for the exact pattern
     * {@code '%text%'} with no further metacharacters, each with its standard semantics preserved:
     * case-sensitive {@code LIKE}/{@code NOT LIKE} → {@code co}/{@code nc}, case-insensitive
     * {@code ILIKE}/{@code NOT ILIKE} → {@code ico}/{@code inc}.
     */
    private FilterNode likeFilter(RexCall call) {
        if (call.getOperands().size() != 2) {
            throw new SqlTranslationException("LIKE with an ESCAPE clause is not supported");
        }
        final Expr operand = toExpr(call.getOperands().get(0));
        // CO stands for the whole contains family here: the carve-out admits all four together, so which
        // member the pattern turns out to be (below) cannot change whether the operand is legal.
        rejectProjectionOnlyOperand(operand, ComparisonOp.CO, "a contains (LIKE) pattern on");
        if (!(call.getOperands().get(1) instanceof RexLiteral literal)
                || literal.getTypeName().getFamily() != org.apache.calcite.sql.type.SqlTypeFamily.CHARACTER) {
            throw new SqlTranslationException("LIKE requires a string literal pattern");
        }
        final String pattern = literal.getValueAs(String.class);
        if (pattern == null || pattern.length() < 2 || !pattern.startsWith("%") || !pattern.endsWith("%")) {
            throw new SqlTranslationException(
                    "only contains patterns ('%text%') are supported for LIKE; got '" + pattern + "'");
        }
        final String text = pattern.substring(1, pattern.length() - 1);
        if (text.contains("%") || text.contains("_")) {
            throw new SqlTranslationException(
                    "LIKE patterns with '%' or '_' inside the text are not supported; got '" + pattern + "'");
        }
        final String operatorName = call.getOperator().getName().toUpperCase(Locale.ROOT);
        final boolean negated = operatorName.startsWith("NOT");
        final boolean caseInsensitive = operatorName.endsWith("ILIKE");
        final ComparisonOp op = caseInsensitive
                ? (negated ? ComparisonOp.INC : ComparisonOp.ICO)
                : (negated ? ComparisonOp.NC : ComparisonOp.CO);
        return new ComparisonNode(op, List.of(operand, new ValueExpr(ValueType.STRING, text)));
    }

    // ------------------------------------------------------------ expressions

    /** Translates a value expression (projection entry, comparison operand, function argument). */
    Expr toExpr(RexNode node) {
        return switch (node) {
            case RexInputRef ref -> inputs.get(ref.getIndex());
            case RexLiteral literal -> literalExpr(literal);
            case RexFieldAccess access when access.getReferenceExpr() instanceof RexCorrelVariable ->
                    throw new SqlTranslationException(CORRELATION_MESSAGE);
            case RexOver over -> windowExpr(over);
            case RexSubQuery subQuery -> scalarSubquery(subQuery);
            case RexCall call -> callExpr(call);
            default -> throw unsupported(node);
        };
    }

    private Expr callExpr(RexCall call) {
        if (call.getKind() == SqlKind.CAST || call.getKind() == SqlKind.SAFE_CAST) {
            return castExpr(call);
        }
        if (call.getKind() == SqlKind.CASE) {
            return caseExpr(call);
        }
        final String name = call.getOperator().getName().toUpperCase(Locale.ROOT);
        return switch (name) {
            case "LOWER" -> fn("lower", call, 1);
            case "UPPER" -> fn("upper", call, 1);
            case "CHAR_LENGTH", "CHARACTER_LENGTH", "LENGTH" -> fn("length", call, 1);
            case "ABS" -> fn("abs", call, 1);
            case "TRIM" -> trimExpr(call);
            case "DATE_BIN" -> fn("date_bin", call, 3);
            case "DATE_TRUNC" -> fn("date_trunc", call, 2);
            case "WIDTH_BUCKET" -> fn("width_bucket", call, 4);
            case "JSON_EXTRACT_STRING" -> jsonExtract("json_extract_string", call);
            case "JSON_EXTRACT_LONG" -> jsonExtract("json_extract_long", call);
            case "JSON_EXTRACT_DOUBLE" -> jsonExtract("json_extract_double", call);
            case "JSON_EXTRACT_BOOL" -> jsonExtract("json_extract_bool", call);
            case "JSON_EXTRACT_STRING_CI" -> jsonExtract("json_extract_string_ci", call);
            case "JSON_EXTRACT_ARRAY" -> jsonExtract(ArrayValuedFunctions.JSON_EXTRACT_ARRAY, call);
            case "IF" -> fn("if", call, 3);
            case "NOT_EMPTY" -> fn("not_empty", call, 1);
            case "STARTS_WITH" -> fn("starts_with", call, 2);
            case "CONTAINS" -> fn("contains", call, 2);
            case "EQUALS" -> fn("equals", call, 2);
            // Arithmetic reaches the DSL only through the operators Calcite parses (named "+", "-", "*",
            // "/", "%") plus the standard MOD, each lowered onto its catalog name. The catalog names
            // themselves are not registered as SQL functions — MINUS is a reserved set operator in the
            // Babel grammar, so minus(a, b) cannot parse whatever the operator table says, and one rule
            // beats four-of-five. No operator node enters the DSL either way.
            // Datetime arithmetic shares these two operator names with numeric arithmetic: Calcite's
            // DATETIME_PLUS and MINUS_DATE are both spelled "+"/"-", and it normalizes the datetime to
            // operand 0 whichever order the statement wrote. So an interval operand is looked for FIRST and
            // lowered onto date_add/date_sub; only then does the numeric reading apply.
            case "+" -> {
                final Expr offset = dateOffset(call, false);
                yield offset != null ? offset : fn("plus", call, 2);
            }
            // Unary minus carries Calcite's name for binary minus, so the arity decides which this is.
            // Matching on the name alone would mis-lower `-a` into a two-argument call with one operand.
            case "-" -> {
                if (call.getOperands().size() == 1) {
                    yield negation(call);
                }
                final Expr offset = dateOffset(call, true);
                yield offset != null ? offset : fn("minus", call, 2);
            }
            case "NOW", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP" -> clock(call);
            case "DATE_ADD" -> canonicalOffsetUnit(literalArguments(fn("date_add", call, 3), 0, 1));
            case "DATE_SUB" -> canonicalOffsetUnit(literalArguments(fn("date_sub", call, 3), 0, 1));
            case "*" -> fn("multiply", call, 2);
            case "/" -> {
                final Expr avg = undoAvgExpansion(call);
                yield avg != null ? avg : fn("div", call, 2);
            }
            // Two inbound spellings of one operation: the % operator and Calcite's standard MOD function.
            // The renderer emits only %, keeping the round-trip single-valued.
            case "%", "MOD" -> fn("modulo", call, 2);
            case "ROUND" -> rounding(call);
            // FLOOR/CEIL also carry a datetime form, FLOOR(<ts> TO <unit>), which arrives as a
            // two-operand call. That is date_trunc's job and has no equivalent under this name, so it is
            // refused rather than silently lowered with the unit discarded.
            case "FLOOR" -> unitlessRounding("floor", call);
            case "CEIL", "CEILING" -> unitlessRounding("ceil", call);
            case "MAP_VALUE" -> accessorFn("map_value", call, 2);
            case "MAP_CONTAINS" -> accessorFn("map_contains", call, 2);
            case "MAP_KEYS" -> accessorFn(ArrayValuedFunctions.MAP_KEYS, call, 1);
            case "ARRAY_HAS" -> accessorFn("array_has", call, 2);
            case "ARRAY_HAS_ANY" -> accessorFn("array_has_any", call, 2);
            case "ARRAY_HAS_ALL" -> accessorFn("array_has_all", call, 2);
            case "ARRAY_LENGTH" -> accessorFn("array_length", call, 1);
            // accessorFn, not fn: argument 0 is an array by definition, so it takes the container carve-out
            // every other array-operand function takes there. The arity is what makes the other dialect's
            // two-operand ARRAY_JOIN (Spark's array-to-string join) fail closed instead of being read as an
            // expansion with a stray delimiter.
            case "ARRAY_JOIN" -> accessorFn(ArrayValuedFunctions.ARRAY_JOIN, call, 1);
            case "ARRAY_TO_STRING" ->
                    literalArguments(accessorFn(ArrayValuedFunctions.ARRAY_TO_STRING, call, 2), 1);
            case "ARRAY_SLICE" ->
                    literalArguments(accessorFn(ArrayValuedFunctions.ARRAY_SLICE, call, 3), 1, 2);
            case "SPLIT_STRING" -> fn(ArrayValuedFunctions.SPLIT_STRING, call, 2);
            // The || operator arrives here as CONCAT too: SqlTextRebinder swaps it before validation, so
            // this surface has one inbound spelling to read and the DSL one function to render.
            case "CONCAT" -> renderingFn("concat", call, 2, Integer.MAX_VALUE);
            case "TO_STRING" -> renderingFn("to_string", call, 1, 1);
            case "TO_STRING_CAST" -> toStringCast(call);
            case "MATCH" -> fn("match", call, 2);
            case "__LAMBDAPARAM" -> lambdaParam(call);
            // The ClickHouse-named families are matched case-insensitively rather than by a case label:
            // a label must be a compile-time constant and these names are camelCase, so an upper-cased
            // label would not be the name the catalog advertises.
            default -> {
                if (HigherOrderFunctions.isHigherOrder(name)) {
                    yield higherOrderFn(HigherOrderFunctions.canonical(name), call);
                }
                if (ArrayValuedFunctions.isFlatteningAggregate(name)) {
                    yield accessorFn(ArrayValuedFunctions.canonicalFlattening(name), call, 1);
                }
                throw unsupported(call);
            }
        };
    }

    /**
     * {@code json_extract_*(json, key[, key…])} — the first operand is any character expression or an
     * {@code object} field, and every subsequent operand must be a <em>string literal</em> naming one
     * level of the path. Keeping the path literal is what makes the accessed path statically known, so
     * a rule's referenced-column analysis and its governance are complete; a computed path would hide
     * it — and it is rejected for being computed, never for the JSON argument's type.
     */
    private FnExpr jsonExtract(String dslName, RexCall call) {
        final List<RexNode> operands = call.getOperands();
        if (operands.size() < 2) {
            throw new SqlTranslationException("function '" + dslName
                    + "' expects a JSON expression plus at least one path key");
        }
        // The case-insensitive extractor is the family's one fixed-arity member: matching a key without
        // knowing its case is defined for a single top-level key, so a third operand is an arity error
        // rather than a deeper path.
        if ("json_extract_string_ci".equals(dslName) && operands.size() != 2) {
            throw new SqlTranslationException("function '" + dslName + "' expects exactly two arguments"
                    + " (json, key); case-insensitive matching is defined for a single top-level key");
        }
        final List<Expr> args = new ArrayList<>(operands.size());
        final Expr json = toExpr(operands.getFirst());
        rejectUnextractableJsonOperand(json, "a function call over");
        args.add(json);
        for (final RexNode operand : operands.subList(1, operands.size())) {
            final Expr key = toExpr(operand);
            if (!(key instanceof ValueExpr value) || value.valueType() != ValueType.STRING) {
                throw new SqlTranslationException("function '" + dslName
                        + "' requires string literal path keys after the JSON expression");
            }
            args.add(key);
        }
        return new FnExpr(dslName, false, args);
    }

    private FnExpr fn(String dslName, RexCall call, int arity) {
        if (call.getOperands().size() != arity) {
            throw new SqlTranslationException(
                    "function '" + dslName + "' expects " + arity + " argument(s)");
        }
        final List<Expr> args = new ArrayList<>(arity);
        for (final RexNode operand : call.getOperands()) {
            final Expr arg = toExpr(operand);
            rejectProjectionOnlyOperand(arg, "a function call over");
            rejectSubqueryArgument(arg, dslName);
            args.add(arg);
        }
        return new FnExpr(dslName, false, args);
    }

    /**
     * A call on one of the two functions that <b>render</b> their operands as text — {@code concat}
     * (two or more) and {@code to_string} (exactly one). Two things separate it from {@link #fn}.
     *
     * <p>The arity is a <b>range</b>: {@code concat} is the catalog's one variadic scalar whose extra
     * operands are ordinary expressions rather than the literal path keys {@link #jsonExtract} reads.
     *
     * <p>And the array-valued rejection is applied <b>here</b> rather than left to the DSL translator,
     * which is what {@link #fn}'s callers do. Those functions declare a typed operand family
     * ({@code LENGTH} takes {@code CHARACTER}, the arithmetic operators {@code NUMERIC}), so Calcite
     * already refuses an {@code ARRAY}-typed nested call during validation. These two declare {@code ANY}
     * on purpose — rendering any scalar is their job — so nothing upstream fails closed, and without this
     * guard {@code POST /v1/queries/translate-sql} would answer with a document that only fails when
     * executed.
     */
    private FnExpr renderingFn(String dslName, RexCall call, int minArity, int maxArity) {
        final int arity = call.getOperands().size();
        if (arity < minArity || arity > maxArity) {
            throw new SqlTranslationException(minArity == maxArity
                    ? "function '" + dslName + "' expects " + minArity + " argument(s)"
                    : "function '" + dslName + "' expects at least " + minArity + " argument(s)");
        }
        final List<Expr> args = new ArrayList<>(arity);
        for (int i = 0; i < arity; i++) {
            final Expr arg = toExpr(call.getOperands().get(i));
            rejectProjectionOnlyOperand(arg, "a function call over");
            ArrayValuedFunctions.rejectAsOperand(arg, dslName, i);
            rejectSubqueryArgument(arg, dslName);
            args.add(arg);
        }
        return new FnExpr(dslName, false, args);
    }

    /**
     * A caller-written {@code CAST(<expr> AS <character type>)}, which reaches the plan as a
     * {@link SqlQueryOperators#TO_STRING_CAST} call — the marker {@link SqlTextRebinder} lowers it onto
     * before validation, so that a cast the caller spelled stays distinguishable from one the validator
     * injects (which {@link #castExpr} still rejects).
     *
     * <p>Here, where the operand finally has a type, the marker resolves two ways. Over a <b>non-text</b>
     * operand it is the rendering request it looks like, and becomes {@code to_string} with that function's
     * whole treatment — the null-preserving semantics, the projection-only and array-valued refusals. Over
     * an operand that is <b>already text</b> it is the identity cast it has always been, and translates to
     * the operand alone: unwrapping is not an optimization but the pre-existing translation of that
     * statement, and wrapping it instead would rewrite accepted statements — {@code project_id = 'p1'} into
     * {@code toString(project_id) = 'p1'} over a {@code LowCardinality(String)} sort-key column, a group key
     * on the low-cardinality column into one on {@code String} — for no gain, since {@code toString} over a
     * ClickHouse {@code String} answers the same values.
     *
     * <p>The identity branch delegates to {@link #castExpr} rather than reimplementing it: the marker's
     * derived type is {@code VARCHAR} and its operand is character, which is exactly the same-family
     * widening that method already unwraps — including the literal retyping it does on the way.
     */
    private Expr toStringCast(RexCall call) {
        if (call.getOperands().size() == 1
                && character(call.getOperands().getFirst().getType().getSqlTypeName())) {
            return castExpr(call);
        }
        return renderingFn("to_string", call, 1, 1);
    }

    /**
     * Requires the given argument positions (0-based) of an already-translated call to be <b>literals</b> —
     * {@code array_to_string}'s delimiter and {@code array_slice}'s two bounds, which the DSL declares as
     * literals so the values can be checked once, before the statement runs, instead of per row inside
     * ClickHouse.
     *
     * <p>Only literal-<em>ness</em> is checked here. What the literal may <em>be</em> (a non-zero offset, a
     * positive length, a string delimiter) is the DSL translator's rule and stays defined in exactly one
     * place, which is also where {@code split_string}'s non-empty delimiter lives.
     */
    private static FnExpr literalArguments(FnExpr fn, int... positions) {
        for (final int position : positions) {
            if (!(fn.args().get(position) instanceof ValueExpr)) {
                throw new SqlTranslationException("function '" + fn.name() + "' requires a literal as argument "
                        + (position + 1) + ", not a computed expression");
            }
        }
        return fn;
    }

    /**
     * Rewrites a {@code date_add}/{@code date_sub} unit literal to the DSL's own lower-case spelling, so
     * the function spelling and the {@code ± INTERVAL n <UNIT>} operator spelling land on <b>one</b>
     * document.
     *
     * <p>The two spellings are asymmetric without this. SQL keywords are conventionally written upper
     * case and the renderer emits the interval keyword upper case, so {@code date_add('HOUR', 2, now())}
     * would carry {@code 'HOUR'} while {@code now() + INTERVAL 2 HOUR} carries the {@code 'hour'}
     * {@link IntervalUnit#dslUnit()} — two documents for one meaning, and a DSL → SQL → DSL cycle that
     * never settles because the renderer normalizes the case and the reader did not.
     *
     * <p>Only the <em>case</em> is canonicalised; whether the unit is one this surface has at all stays
     * the DSL translator's single rule, which reads it case-insensitively and reports the allowlist. And
     * only these two functions need the rewrite: {@code date_trunc}/{@code date_bin} have their unit
     * emitted back as the string literal it was read as, so both of their directions already agree.
     */
    private static FnExpr canonicalOffsetUnit(FnExpr fn) {
        if (!(fn.args().getFirst() instanceof ValueExpr(ValueType unitType, String unit))
                || unitType != ValueType.STRING || unit == null) {
            return fn;
        }
        final List<Expr> args = new ArrayList<>(fn.args());
        args.set(0, new ValueExpr(ValueType.STRING, unit.trim().toLowerCase(Locale.ROOT)));
        return new FnExpr(fn.name(), fn.distinct(), List.copyOf(args));
    }

    /** A scalar subquery is a comparison operand or a select item, never a function argument. */
    private static void rejectSubqueryArgument(Expr arg, String dslName) {
        if (arg instanceof ScalarSubqueryExpr) {
            throw new SqlTranslationException("a scalar subquery cannot be an argument of '" + dslName
                    + "'; project it or compare against it instead");
        }
    }

    /**
     * A call on a function whose argument 0 is a container — a map or array accessor. Identical to
     * {@link #fn} except that the projection-only rejection is <b>positional</b>: argument 0 may be a
     * {@code map} or {@code array} field (whichever that function accepts, per
     * {@link SqlProjectionOnlyTypes#isAllowedFunctionArgument}), every other argument may not, and an
     * array-valued <em>call</em> is legal in argument 0 of an array-operand function so that
     * {@code ARRAY_LENGTH(MAP_KEYS(m))} translates.
     *
     * <p>A SQL {@code ARRAY[...]} constructor in a later argument becomes the DSL's array-of-literals
     * shape, which is what {@code array_has_any}/{@code array_has_all} take.
     */
    private FnExpr accessorFn(String dslName, RexCall call, int arity) {
        if (call.getOperands().size() != arity) {
            throw new SqlTranslationException(
                    "function '" + dslName + "' expects " + arity + " argument(s)");
        }
        final List<Expr> args = new ArrayList<>(arity);
        for (int i = 0; i < call.getOperands().size(); i++) {
            final RexNode operand = call.getOperands().get(i);
            if (operand instanceof RexCall nested
                    && nested.getKind() == SqlKind.ARRAY_VALUE_CONSTRUCTOR) {
                args.add(arrayLiteral(dslName, nested));
                continue;
            }
            final Expr arg = toExpr(operand);
            if (i == 0) {
                rejectDisallowedContainerOperand(arg, dslName);
            } else {
                rejectProjectionOnlyOperand(arg, "a function call over");
                ArrayValuedFunctions.rejectAsOperand(arg, "an argument of '" + dslName + "'");
            }
            args.add(arg);
        }
        return new FnExpr(dslName, false, args);
    }

    /**
     * The internal marker {@code __lambdaParam(<array>, '<name>')} as the DSL's parameter reference. Every
     * marker in a statement becomes one of these; which <em>lambda</em> each belongs to is decided by
     * {@link #higherOrderFn}, from where the reference sits rather than from the marker itself.
     */
    private Expr lambdaParam(RexCall call) {
        if (call.getOperands().size() != 2
                || !(call.getOperands().get(1) instanceof RexLiteral literal)
                || literal.getValueAs(String.class) == null) {
            throw unsupported(call);
        }
        return new LambdaParamExpr(literal.getValueAs(String.class));
    }

    /**
     * One of the five higher-order array calls, rebuilt into {@code fn(<lambda>, <array>)} — the shape the
     * caller wrote and the shape the renderer emits, both of which the inlining rewrite
     * ({@link SqlLambdaRewriter}) flattened away before validation.
     *
     * <p><b>How the parameter is recovered.</b> The body has already been translated, so every marker in
     * it is a {@link LambdaParamExpr}; the ones belonging to a <em>nested</em> lambda were consumed when
     * that call was rebuilt, since this runs innermost-first as the recursion does. What is left unbound
     * is therefore this lambda's, and its name is the caller's own spelling, carried through validation on
     * the marker. A body that never mentions the parameter leaves nothing to read, and an unused parameter
     * has no observable effect, so it takes the conventional name.
     *
     * <p>The container carve-out applies at argument <b>1</b> here, not 0: ClickHouse puts the array
     * second, and {@link SqlProjectionOnlyTypes} is asked about that position rather than the first.
     */
    private Expr higherOrderFn(String dslName, RexCall call) {
        // Three operands, not the caller's two: SqlLambdaRewriter inlined the body and appended the
        // parameter's name, which is the only place that knows which markers this lambda binds.
        if (call.getOperands().size() != 3) {
            throw new SqlTranslationException("function '" + dslName + "' expects 2 argument(s)");
        }
        final Expr body = toExpr(call.getOperands().get(HigherOrderFunctions.LAMBDA_ARGUMENT));
        rejectSubqueryArgument(body, dslName);
        final Expr array = toExpr(call.getOperands().get(HigherOrderFunctions.ARRAY_ARGUMENT));
        if (array instanceof FieldExpr(String arrayName)
                && !SqlProjectionOnlyTypes.isAllowedFunctionArgument(
                        dslName, HigherOrderFunctions.ARRAY_ARGUMENT, fieldTypes.get(arrayName))) {
            SqlProjectionOnlyTypes.reject(arrayName, fieldTypes.get(arrayName), "a function call over");
        }
        if (!(call.getOperands().get(2) instanceof RexLiteral name)
                || name.getValueAs(String.class) == null) {
            throw unsupported(call);
        }
        return new FnExpr(dslName, false,
                List.of(new LambdaExpr(List.of(name.getValueAs(String.class)), body), array));
    }

    /** A SQL {@code ARRAY['a', 'b']} constructor as the DSL's {@link ArrayExpr} of literals. */
    private Expr arrayLiteral(String dslName, RexCall call) {
        final List<Expr> items = new ArrayList<>(call.getOperands().size());
        for (final RexNode operand : call.getOperands()) {
            final Expr item = toExpr(operand);
            if (!(item instanceof ValueExpr)) {
                throw new SqlTranslationException(
                        "function '" + dslName + "' value list items must be literals");
            }
            items.add(item);
        }
        if (items.isEmpty()) {
            throw new SqlTranslationException("function '" + dslName + "' requires a non-empty value list");
        }
        return new ArrayExpr(items);
    }

    /**
     * Rejects argument 0 of an accessor when it is a projection-only field the accessor does not accept —
     * a {@code map} field given to an array accessor, an {@code object} field given to either, and so on.
     * The carve-out itself is {@link SqlProjectionOnlyTypes}', so both translation directions agree on it.
     */
    private void rejectDisallowedContainerOperand(Expr expr, String dslName) {
        if (expr instanceof FieldExpr(String name)
                && !SqlProjectionOnlyTypes.isAllowedFunctionArgument(dslName, 0, fieldTypes.get(name))) {
            SqlProjectionOnlyTypes.reject(name, fieldTypes.get(name), "a function call over");
        }
    }

    /** Unary minus, lowered to the catalog's {@code minus} against a zero literal. */
    private Expr negation(RexCall call) {
        final Expr operand = toExpr(call.getOperands().getFirst());
        rejectProjectionOnlyOperand(operand, "a function call over");
        return new FnExpr("minus", false, List.of(new ValueExpr(ValueType.INTEGER, "0"), operand));
    }

    /** {@code ROUND(value)} or {@code ROUND(value, digits)} — both catalog arities. */
    private Expr rounding(RexCall call) {
        final int arity = call.getOperands().size();
        if (arity != 1 && arity != 2) {
            throw new SqlTranslationException("function 'round' expects one or two argument(s)");
        }
        return fn("round", call, arity);
    }

    /**
     * The clock, under any of its three inbound spellings — {@code now()}, {@code CURRENT_TIMESTAMP},
     * {@code LOCALTIMESTAMP} — lowered to the one zero-argument DSL function. Three spellings because three
     * populations write them (engine/PostgreSQL, standard SQL and most BI clients, and a client meaning
     * "no zone"), and on a surface whose one zone is UTC they name the same instant; producing one document
     * rather than three is what keeps them from behaving merely alike.
     *
     * <p>{@code CURRENT_DATE} is deliberately absent and stays an unsupported construct: it is a
     * truncation of the clock rather than a spelling of it, and lowering it to {@code now()} would answer a
     * date-typed request with a timestamp. {@code date_trunc('day', now())} says it exactly.
     *
     * <p>The parenthesised spellings parse to a call with no operands, so a non-empty operand list can only
     * come from {@code now(x)} — which the operator table's niladic checker already refuses, making this
     * the belt to its braces.
     */
    private Expr clock(RexCall call) {
        if (!call.getOperands().isEmpty()) {
            throw new SqlTranslationException("function 'now' takes no arguments"
                    + " (there is no zone parameter: the value is UTC)");
        }
        return new FnExpr("now", false, List.of());
    }

    /**
     * A datetime {@code ±} interval-literal call lowered onto {@code date_add} / {@code date_sub}, or
     * {@code null} when this is not datetime arithmetic at all and the caller should read the operator
     * numerically.
     *
     * <p><b>The offset must reduce to a constant.</b> The DSL declares an offset's unit and amount as
     * literals so they can be checked once before the statement runs, so a per-row-varying offset has no
     * representation to lower onto — {@code INTERVAL <column> HOUR} is refused, naming the reason.
     *
     * <p><b>The unit comes from the interval operand's own type, not from the written keyword.</b> Calcite
     * has already lowered {@code WEEK} to {@code INTERVAL_DAY} and {@code QUARTER} to
     * {@code INTERVAL_MONTH} by the time a {@code Rex} tree exists, so those two normalize to their exact
     * equivalents (7 days, 3 months) and a client that wrote {@code INTERVAL 1 WEEK} reads back
     * {@code date_sub('day', 7, …)}. The six accepted type names are <b>enumerated</b> rather than filtered
     * for compound-ness, so a multi-field qualifier ({@code INTERVAL '1-2' YEAR TO MONTH}) and any
     * qualifier a future Calcite adds both fail closed.
     *
     * <p><b>Sign folds into the function name.</b> A negative magnitude flips the operator, so
     * {@code + INTERVAL -2 HOUR} and {@code - INTERVAL 2 HOUR} produce one document — which they must, since
     * the DSL refuses a negative amount and the renderer emits one statement per document.
     */
    private Expr dateOffset(RexCall call, boolean subtract) {
        final List<RexNode> operands = call.getOperands();
        if (operands.size() != 2 || !INTERVAL_UNITS.containsKey(operands.get(1).getType().getSqlTypeName())) {
            rejectQualifiedDatetimeDifference(call, operands);
            // An interval operand that is not one of the six accepted qualifiers is refused HERE rather
            // than left to fall through to the numeric reading, which would report it as an unusable
            // literal family. What it actually is, is a multi-field qualifier — two offsets in one operand —
            // and saying so is the difference between a caller splitting the call in two and a caller
            // filing a bug.
            if (operands.size() == 2 && SqlTypeUtil.isInterval(operands.get(1).getType())) {
                throw new SqlTranslationException("a multi-field INTERVAL qualifier ("
                        + operands.get(1).getType() + ") names two offsets at once and is not expressible in the"
                        + " structured query DSL; write one offset per call");
            }
            return null;
        }
        final IntervalUnit unit = INTERVAL_UNITS.get(operands.get(1).getType().getSqlTypeName());
        final BigDecimal magnitude = intervalMagnitude(operands.get(1));
        if (magnitude == null) {
            throw new SqlTranslationException("an INTERVAL offset must be a constant amount;"
                    + " a computed one is not expressible in the structured query DSL, which declares an"
                    + " offset's unit and amount as literals");
        }
        final BigDecimal[] amountAndRemainder = magnitude.abs().divideAndRemainder(unit.baseUnitsPerUnit());
        if (amountAndRemainder[1].signum() != 0 || amountAndRemainder[0].signum() == 0) {
            throw new SqlTranslationException("an INTERVAL offset must be a whole, non-zero number of "
                    + unit.dslUnit() + "s");
        }
        // Range-checked here rather than left to the literal parse downstream. Both consumers of the
        // document require a Java int (ExprTranslator.dateOffsetAmount, the renderer's offsetAmount), so
        // without this an oversized offset translates successfully and fails later with a message about a
        // malformed integer literal — and translate-sql would have handed back a document execute-sql
        // cannot run, which is the asymmetry the two endpoints exist to rule out.
        if (amountAndRemainder[0].compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new SqlTranslationException("an INTERVAL offset of " + amountAndRemainder[0].toBigIntegerExact()
                    + " " + unit.dslUnit() + "s is too large; the offset amount must fit a 32-bit integer");
        }
        final boolean negative = magnitude.signum() < 0;
        final String dslName = subtract != negative ? "date_sub" : "date_add";
        final Expr operand = toExpr(operands.getFirst());
        rejectProjectionOnlyOperand(operand, "a function call over");
        rejectSubqueryArgument(operand, dslName);
        return new FnExpr(dslName, false, List.of(
                new ValueExpr(ValueType.STRING, unit.dslUnit()),
                new ValueExpr(ValueType.INTEGER, amountAndRemainder[0].toBigIntegerExact().toString()),
                operand));
    }

    /**
     * The magnitude of an interval operand in its qualifier's own base unit, or {@code null} when the
     * operand does not reduce to a constant.
     *
     * <p><b>Two Rex shapes reach here, because the two interval spellings parse differently</b> — measured,
     * not assumed. The standard {@code INTERVAL '2' HOUR} arrives as a plain interval {@link RexLiteral}
     * holding 7200000. The engine-native {@code INTERVAL 2 HOUR} — the spelling a ClickHouse-fluent caller
     * writes, and the one this change exists to accept — arrives instead as {@code *(2, INTERVAL 1 HOUR)}:
     * Babel parses the bare count as a multiplier and nothing folds it before the {@code Rex} tree is
     * built. Handling only the literal would accept the standard spelling and refuse the native one, which
     * is exactly backwards.
     *
     * <p>Multiplication is therefore resolved rather than pattern-matched at one depth, which also admits
     * {@code 2 * INTERVAL 1 HOUR} written by hand and the interval {@code TIMESTAMPADD} lowers to. A
     * non-constant factor — {@code INTERVAL <column> HOUR} — resolves to {@code null} and is refused by the
     * caller, which is the bound that matters.
     */
    private static BigDecimal intervalMagnitude(RexNode node) {
        if (node instanceof RexLiteral literal) {
            return SqlTypeUtil.isInterval(literal.getType()) ? literal.getValueAs(BigDecimal.class) : null;
        }
        if (node instanceof RexCall call && "*".equals(call.getOperator().getName())
                && call.getOperands().size() == 2) {
            // Either order: the count may be written before or after the unit interval.
            final BigDecimal leftInterval = intervalMagnitude(call.getOperands().get(0));
            if (leftInterval != null) {
                final BigDecimal factor = constantNumeric(call.getOperands().get(1));
                return factor == null ? null : leftInterval.multiply(factor);
            }
            final BigDecimal rightInterval = intervalMagnitude(call.getOperands().get(1));
            if (rightInterval != null) {
                final BigDecimal factor = constantNumeric(call.getOperands().getFirst());
                return factor == null ? null : rightInterval.multiply(factor);
            }
        }
        return null;
    }

    /**
     * A numeric operand's constant value, or {@code null} when it is not constant. Unary minus is unwrapped
     * because {@code INTERVAL -2 HOUR} parses its count as {@code -(2)} rather than as a negative literal.
     */
    private static BigDecimal constantNumeric(RexNode node) {
        if (node instanceof RexLiteral literal) {
            return SqlTypeUtil.isNumeric(literal.getType()) ? literal.getValueAs(BigDecimal.class) : null;
        }
        if (node instanceof RexCall call && "-".equals(call.getOperator().getName())
                && call.getOperands().size() == 1) {
            final BigDecimal inner = constantNumeric(call.getOperands().getFirst());
            return inner == null ? null : inner.negate();
        }
        return null;
    }

    /**
     * Refuses the standard datetime-difference form {@code (ts1 - ts2) <unit>}, which Calcite hands over as
     * a two-operand {@code -} whose operands are both datetimes and whose own type is an interval.
     *
     * <p>Without this it falls through to the numeric reading and lowers to {@code minus(ts1, ts2)}, which
     * renders as a plain ClickHouse subtraction — a difference in <b>seconds</b>. A caller asking for hours
     * would get a number ~3600× too large with nothing to flag it, so the qualifier is honoured by
     * rejecting the form rather than by discarding it. This is the same call {@code unitlessRounding} makes
     * about {@code FLOOR(<ts> TO <unit>)}: a datetime form under a name that has no equivalent here is
     * refused, not silently reinterpreted. A bare {@code ts1 - ts2} never reaches this method — Calcite's
     * own type checking rejects it first — so this covers only the qualified spelling.
     */
    private static void rejectQualifiedDatetimeDifference(RexCall call, List<RexNode> operands) {
        if (operands.size() == 2
                && SqlTypeUtil.isInterval(call.getType())
                && SqlTypeUtil.isDatetime(operands.get(0).getType())
                && SqlTypeUtil.isDatetime(operands.get(1).getType())) {
            throw new SqlTranslationException("the difference of two timestamps is not expressible in the"
                    + " structured query DSL; the interval unit would be discarded, leaving a difference in"
                    + " seconds rather than in the unit asked for");
        }
    }

    /**
     * {@code FLOOR}/{@code CEIL} in their numeric form only. The datetime form
     * {@code FLOOR(<timestamp> TO <unit>)} arrives as a second operand carrying a time-unit flag; it is
     * {@code date_trunc}'s job, and lowering it here would discard the unit and silently answer a
     * different question.
     */
    private Expr unitlessRounding(String dslName, RexCall call) {
        if (call.getOperands().size() != 1) {
            throw new SqlTranslationException("only the numeric form of '" + dslName
                    + "' is supported; use date_trunc(unit, timestamp) to truncate a timestamp");
        }
        return fn(dslName, call, 1);
    }

    /** Only plain {@code TRIM(x)} (both ends, spaces) maps to the DSL {@code trim}. */
    private Expr trimExpr(RexCall call) {
        final List<RexNode> operands = call.getOperands();
        if (operands.size() == 3
                && operands.get(0) instanceof RexLiteral flag
                && flag.getValueAs(SqlTrimFunction.Flag.class) == SqlTrimFunction.Flag.BOTH
                && operands.get(1) instanceof RexLiteral pad
                && " ".equals(pad.getValueAs(String.class))) {
            final Expr arg = toExpr(operands.get(2));
            rejectProjectionOnlyOperand(arg, "a function call over");
            return new FnExpr("trim", false, List.of(arg));
        }
        throw new SqlTranslationException("only plain TRIM(x) is supported (no LEADING/TRAILING or custom characters)");
    }

    /**
     * The refusal for a cast this translator does not lower. Shared so
     * {@link RelToStructuredQueryTranslator} can recognise it exactly — it re-words the refusal for the
     * one position where the cast is the validator's own and the caller wrote no cast at all: a set
     * operation's branch coercion.
     */
    static String unsupportedCast(SqlTypeName from, SqlTypeName to) {
        return "CAST from " + from + " to " + to + " is not supported by the query translator";
    }

    /**
     * Unwraps validator-injected identity/widening casts within one type family; rejects everything
     * else. A cast literal is retyped to the target family so downstream literal parsing matches.
     */
    private Expr castExpr(RexCall call) {
        final RexNode operand = call.getOperands().getFirst();
        final SqlTypeName from = operand.getType().getSqlTypeName();
        final SqlTypeName to = call.getType().getSqlTypeName();
        // A bare string literal compared against a timestamp column: Calcite injects CAST(CHAR ->
        // TIMESTAMP), which used to land in the rejection below. Grafana, Power BI and most generated SQL
        // emit that spelling for time bounds, so refusing it failed every such query at translation.
        // Scoped to a LITERAL operand: CAST(<column> AS TIMESTAMP) is a general cast capability with its
        // own null-and-error semantics and stays unsupported.
        if (character(from) && to == SqlTypeName.TIMESTAMP) {
            final Expr expr = toExpr(operand);
            if (expr instanceof ValueExpr value && value.valueType() == ValueType.STRING) {
                return isoTimestampLiteral(value);
            }
            throw new SqlTranslationException("CAST from " + from + " to " + to
                    + " is supported only for an ISO-8601 string literal, not a computed expression");
        }
        // A cast to a character type is NOT handled here, deliberately: a caller-written one has already
        // become a TO_STRING_CAST marker call before validation (SqlTextRebinder, then toStringCast), and
        // the only casts-to-character that reach this point as casts are the ones the validator itself
        // injects to coerce a non-text operand into a text-family position. Lowering those would silently
        // widen every text function to accept any type — notEmpty(toString(total_tokens)) instead of the
        // 400 naming the operand's type — so they fall through to the rejection below, which is what the
        // type rules promise. (The marker's own identity case does come back through here, by delegation
        // from toStringCast: a character operand and a VARCHAR result is same-family widening.)
        if (!sameFamilyWidening(from, to)) {
            throw new SqlTranslationException(unsupportedCast(from, to));
        }
        final Expr expr = toExpr(operand);
        if (expr instanceof ValueExpr value) {
            return retype(value, targetType(to));
        }
        return expr;
    }

    /**
     * An ISO-8601 string literal as a DSL {@code timestamp} value, carrying epoch-millis as every
     * timestamp literal on this surface does.
     *
     * <p>Three spellings are accepted, which is what BI clients emit: a bare date (midnight UTC), a
     * date-and-time with a space separator, and the {@code T}/{@code Z} form. Anything else is a clean
     * {@code invalid_argument} naming the formats rather than a silently different instant or a raw
     * ClickHouse failure — a date string read as the wrong instant is worse than a rejected query.
     */
    private static Expr isoTimestampLiteral(ValueExpr value) {
        final String text = value.value() == null ? "" : value.value().trim();
        final Long millis = parseIsoInstant(text);
        if (millis == null) {
            throw new SqlTranslationException("cannot read '" + text + "' as a timestamp;"
                    + " expected an ISO-8601 date ('2026-08-20'), date-time ('2026-08-20 00:00:00'),"
                    + " or offset date-time ('2026-08-20T00:00:00Z')");
        }
        return new ValueExpr(ValueType.TIMESTAMP, Long.toString(millis));
    }

    /** Epoch-millis for the three accepted ISO-8601 spellings, or {@code null} when none matches. */
    private static Long parseIsoInstant(String text) {
        try {
            return java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            // Not a bare date; try the two date-time spellings below.
        }
        try {
            return java.time.LocalDateTime.parse(text.replace(' ', 'T'))
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            // Not a zoneless date-time; try the offset form below.
        }
        try {
            return java.time.OffsetDateTime.parse(text.replace(' ', 'T')).toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean sameFamilyWidening(SqlTypeName from, SqlTypeName to) {
        if (from == to) {
            return true;
        }
        return (exactNumeric(from) && exactNumeric(to))
                || (character(from) && character(to))
                // A date compared to a timestamp column widens to midnight UTC — lossless.
                || (from == SqlTypeName.DATE && to == SqlTypeName.TIMESTAMP);
    }

    private static boolean exactNumeric(SqlTypeName type) {
        return type == SqlTypeName.TINYINT || type == SqlTypeName.SMALLINT || type == SqlTypeName.INTEGER
                || type == SqlTypeName.BIGINT || type == SqlTypeName.DECIMAL;
    }

    private static boolean character(SqlTypeName type) {
        return type == SqlTypeName.CHAR || type == SqlTypeName.VARCHAR;
    }

    private static QueryFieldType targetType(SqlTypeName to) {
        return switch (to) {
            case TINYINT, SMALLINT, INTEGER -> QueryFieldType.INTEGER;
            case BIGINT -> QueryFieldType.LONG;
            case DECIMAL -> QueryFieldType.DECIMAL;
            case CHAR, VARCHAR -> QueryFieldType.STRING;
            case DATE -> QueryFieldType.DATE;
            case TIMESTAMP -> QueryFieldType.TIMESTAMP;
            default -> null;
        };
    }

    // ---------------------------------------------------------------- literals

    /** Converts a {@link RexLiteral} to a typed DSL literal. */
    ValueExpr literalExpr(RexLiteral literal) {
        if (literal.isNull()) {
            return new ValueExpr(ValueType.NULL, null);
        }
        final SqlTypeName type = literal.getTypeName();
        return switch (type) {
            case CHAR, VARCHAR -> new ValueExpr(ValueType.STRING, literal.getValueAs(String.class));
            case BOOLEAN -> new ValueExpr(ValueType.BOOLEAN, Boolean.toString(literal.getValueAs(Boolean.class)));
            case TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, DOUBLE, FLOAT, REAL ->
                    numericExpr(literal.getValueAs(BigDecimal.class));
            case DATE -> new ValueExpr(ValueType.DATE, literal.getValueAs(DateString.class).toString());
            case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE -> new ValueExpr(
                    ValueType.TIMESTAMP,
                    Long.toString(literal.getValueAs(TimestampString.class).getMillisSinceEpoch()));
            // An interval literal is not a value on this surface: it has no DSL value_type, and the only
            // thing that consumes one is the datetime ± interval form, which never routes its interval
            // operand through here. Reaching this arm therefore means an interval stood somewhere a value
            // was expected — projected, compared, or multiplied — and naming the construct is what keeps
            // that from reading as an internal literal-family failure.
            default -> throw new SqlTranslationException(SqlTypeUtil.isInterval(literal.getType())
                    ? "an INTERVAL is only expressible as an offset of a timestamp"
                            + " (<timestamp> + INTERVAL n unit, or date_add/date_sub), not as a value of its own"
                    : "unsupported literal type " + type);
        };
    }

    /** Converts a Sarg endpoint / range value to a typed DSL literal using the searched operand type. */
    ValueExpr sargValueExpr(Comparable<?> value, SqlTypeName type) {
        return switch (value) {
            case NlsString nls -> new ValueExpr(ValueType.STRING, nls.getValue());
            case Boolean bool -> new ValueExpr(ValueType.BOOLEAN, bool.toString());
            case BigDecimal decimal -> numericExpr(decimal);
            case DateString date -> new ValueExpr(ValueType.DATE, date.toString());
            case TimestampString timestamp -> new ValueExpr(
                    ValueType.TIMESTAMP, Long.toString(timestamp.getMillisSinceEpoch()));
            default -> throw new SqlTranslationException(
                    "unsupported literal value in range/IN predicate: " + value.getClass().getSimpleName());
        };
    }

    private static ValueExpr numericExpr(BigDecimal value) {
        if (value.scale() <= 0 || value.stripTrailingZeros().scale() <= 0) {
            final BigDecimal integral = value.setScale(0, RoundingMode.UNNECESSARY);
            try {
                return new ValueExpr(ValueType.INTEGER, Integer.toString(integral.intValueExact()));
            } catch (ArithmeticException tooWide) {
                try {
                    return new ValueExpr(ValueType.LONG, Long.toString(integral.longValueExact()));
                } catch (ArithmeticException stillTooWide) {
                    return new ValueExpr(ValueType.DECIMAL, value.toPlainString());
                }
            }
        }
        return new ValueExpr(ValueType.DECIMAL, value.toPlainString());
    }

    /**
     * Retypes a literal to the declared type of the field it is compared against (or a cast target),
     * so DSL literal parsing sees the type the schema declares — e.g. a SQL string literal compared to
     * a {@code uuid} field becomes a uuid literal, an integer literal against a {@code long} field
     * becomes a long. Incompatible pairs are left as-is; the DSL layer rejects them.
     */
    private static ValueExpr retype(ValueExpr value, QueryFieldType fieldType) {
        if (fieldType == null || value.valueType() == ValueType.NULL) {
            return value;
        }
        final ValueType from = value.valueType();
        return switch (fieldType) {
            case UUID -> from == ValueType.STRING ? new ValueExpr(ValueType.UUID, value.value()) : value;
            case LONG -> from == ValueType.INTEGER ? new ValueExpr(ValueType.LONG, value.value()) : value;
            case DECIMAL -> from == ValueType.INTEGER || from == ValueType.LONG
                    ? new ValueExpr(ValueType.DECIMAL, value.value())
                    : value;
            case TIMESTAMP -> from == ValueType.DATE
                    ? new ValueExpr(ValueType.TIMESTAMP, Long.toString(java.time.LocalDate.parse(value.value())
                            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()))
                    : value;
            default -> value;
        };
    }

    /**
     * Array and map fields are projection-only in every operand position, and an object field in every
     * one this overload is used for, so such a field used as a predicate operand or function argument
     * fails closed with an error naming the field and the restriction (never a misleading
     * "unknown column").
     */
    void rejectProjectionOnlyOperand(Expr expr, String use) {
        if (expr instanceof FieldExpr(String name)) {
            SqlProjectionOnlyTypes.reject(name, fieldTypes.get(name), use);
        }
    }

    /**
     * The operator-aware form, for the <b>left</b> operand of a comparison whose right operand is a
     * character literal (a {@code LIKE} pattern, or the literal side of a comparison — see
     * {@link #rejectComparisonOperands}). An {@code object} field is a legal left operand of the contains
     * and equality comparisons — their meaning is defined over the document's canonical JSON text, which
     * both surfaces can name — so those must not be rejected here or a statement the DSL renders would be
     * refused on the way back. Every other operator, and every {@code array}/{@code map} field, still
     * fails closed. {@code IS [NOT] NULL} deliberately does not come through here: it keeps the blanket
     * overload, because a {@code JSON} column is never null and the test would be constant.
     */
    void rejectProjectionOnlyOperand(Expr expr, ComparisonOp op, String use) {
        if (expr instanceof FieldExpr(String name)
                && !SqlProjectionOnlyTypes.isComparableAs(fieldTypes.get(name), op)) {
            SqlProjectionOnlyTypes.reject(name, fieldTypes.get(name), use);
        }
    }

    /**
     * The JSON argument of a {@code json_extract_*} call — the one position where an {@code object}
     * field is legal outside a projection item (see {@link SqlProjectionOnlyTypes}). An {@code array}
     * field still fails closed here with the ordinary projection-only wording.
     */
    private void rejectUnextractableJsonOperand(Expr expr, String use) {
        if (expr instanceof FieldExpr(String name)
                && !SqlProjectionOnlyTypes.isExtractableFrom(fieldTypes.get(name))) {
            SqlProjectionOnlyTypes.reject(name, fieldTypes.get(name), use);
        }
    }

    // ------------------------------------------------------------ case / window / subquery

    /**
     * {@code CASE WHEN c1 THEN v1 [WHEN c2 THEN v2 …] [ELSE e] END} — Calcite has already rewritten the
     * simple form into the searched one, so the operands arrive as condition/value pairs with the else last
     * (a trailing null literal when the statement wrote no {@code ELSE}, which becomes an absent
     * {@code else}). Each condition is a predicate of the filter grammar. A {@code THEN} that is a null
     * literal is carried as one: {@code NULLIF(a, b)} lowers to {@code CASE WHEN a = b THEN NULL ELSE a END},
     * and {@code COALESCE(a, b)} to {@code CASE WHEN a IS NOT NULL THEN a ELSE b END}, so both standard
     * functions translate through this branch without a catalog entry of their own.
     */
    private Expr caseExpr(RexCall call) {
        final Expr windowedSum = undoNullWhenCountZero(call);
        if (windowedSum != null) {
            return windowedSum;
        }
        final List<RexNode> operands = call.getOperands();
        if (operands.size() < 3 || operands.size() % 2 == 0) {
            throw new SqlTranslationException("CASE requires at least one WHEN … THEN branch");
        }
        final List<CaseBranch> branches = new ArrayList<>(operands.size() / 2);
        for (int i = 0; i + 1 < operands.size(); i += 2) {
            final FilterNode when = toFilter(operands.get(i));
            final Expr then = toExpr(operands.get(i + 1));
            rejectProjectionOnlyOperand(then, "a CASE branch value over");
            branches.add(new CaseBranch(when, then));
        }
        final RexNode last = operands.getLast();
        Expr otherwise = null;
        if (!(last instanceof RexLiteral literal && literal.isNull())) {
            otherwise = toExpr(last);
            rejectProjectionOnlyOperand(otherwise, "a CASE branch value over");
        }
        return new CaseExpr(List.copyOf(branches), otherwise);
    }

    /**
     * Undoes Calcite's own rewrite of a nullable windowed {@code SUM}: {@code SqlToRelConverter} lowers
     * {@code SUM(x) OVER w} into {@code CASE WHEN COUNT(x) OVER w > 0 THEN SUM(x) OVER w ELSE NULL END} so
     * that an empty frame yields null. ClickHouse's {@code sum} already yields null over an empty frame of a
     * nullable operand, so the wrapper is recognised — one branch, a {@code COUNT OVER} strictly above zero,
     * the same operands and window under the {@code THEN}, a null {@code ELSE} — and translated as the plain
     * window function the caller wrote. Returns {@code null} for any other CASE.
     */
    private Expr undoNullWhenCountZero(RexCall call) {
        final List<RexNode> operands = call.getOperands();
        if (operands.size() != 3
                || !(operands.get(0) instanceof RexCall condition)
                || condition.getKind() != SqlKind.GREATER_THAN
                || !(condition.getOperands().get(0) instanceof RexOver count)
                || !"COUNT".equalsIgnoreCase(count.getAggOperator().getName())
                || !(condition.getOperands().get(1) instanceof RexLiteral zero)
                || zero.getValueAs(Long.class) == null || zero.getValueAs(Long.class) != 0L
                || !(operands.get(2) instanceof RexLiteral elseValue) || !elseValue.isNull()) {
            return null;
        }
        RexNode then = operands.get(1);
        while (then instanceof RexCall cast && cast.getKind() == SqlKind.CAST) {
            then = cast.getOperands().getFirst();
        }
        if (!(then instanceof RexOver sum) || !"SUM".equalsIgnoreCase(sum.getAggOperator().getName())
                || !sum.getOperands().equals(count.getOperands()) || !sum.getWindow().equals(count.getWindow())) {
            return null;
        }
        return windowExpr(sum);
    }

    /**
     * Undoes Calcite's expansion of a windowed {@code AVG}: the standard convertlet lowers
     * {@code AVG(x) OVER w} into {@code CAST(SUM(x) OVER w) / COUNT(x) OVER w} (the {@code SUM} itself
     * possibly wrapped as {@link #undoNullWhenCountZero} describes). Recognised by shape — a division whose
     * operands are a {@code SUM} and a {@code COUNT} over the same operands and window — and translated back
     * to the {@code avg} the caller wrote; ClickHouse's own {@code avg} handles the empty frame. Returns
     * {@code null} for any other division.
     */
    private Expr undoAvgExpansion(RexCall division) {
        if (division.getOperands().size() != 2) {
            return null;
        }
        final RexOver sum = windowedAggregate(division.getOperands().get(0), "SUM");
        final RexOver count = windowedAggregate(division.getOperands().get(1), "COUNT");
        if (sum == null || count == null
                || !sum.getOperands().equals(count.getOperands()) || !sum.getWindow().equals(count.getWindow())) {
            return null;
        }
        final WindowExpr sumWindow = (WindowExpr) windowExpr(sum);
        return new WindowExpr(new FnExpr("avg", false, sumWindow.fn().args()),
                sumWindow.partitionBy(), sumWindow.orderBy(), sumWindow.frame());
    }

    /** The windowed {@code aggregate} under any casts and any null-when-count-zero wrapper, else {@code null}. */
    private static RexOver windowedAggregate(RexNode node, String aggregate) {
        RexNode current = node;
        while (true) {
            if (current instanceof RexCall cast && cast.getKind() == SqlKind.CAST) {
                current = cast.getOperands().getFirst();
            } else if (current instanceof RexCall wrapper && wrapper.getKind() == SqlKind.CASE
                    && wrapper.getOperands().size() == 3) {
                current = wrapper.getOperands().get(1);
            } else {
                break;
            }
        }
        return current instanceof RexOver over && aggregate.equalsIgnoreCase(over.getAggOperator().getName())
                ? over : null;
    }

    /**
     * {@code <fn>(…) OVER (PARTITION BY … ORDER BY … [frame])}. The function is one of the ranking
     * functions or one of the aggregates a window may apply; the default frame — {@code RANGE BETWEEN
     * UNBOUNDED PRECEDING AND CURRENT ROW} under an {@code ORDER BY}, the whole partition without one, and
     * whatever Calcite attaches to a function that cannot be framed — is carried as an absent frame so the
     * DSL document and the rendered SQL stay minimal and round-trip.
     */
    private Expr windowExpr(RexOver over) {
        final String operator = over.getAggOperator().getName().toUpperCase(Locale.ROOT);
        if (over.isDistinct()) {
            throw new SqlTranslationException(operator + " does not accept DISTINCT inside a window");
        }
        if (over.ignoreNulls()) {
            throw new SqlTranslationException("IGNORE NULLS is not supported in a window function");
        }
        final FnExpr fn;
        final String ranking = RANKING_FUNCTIONS.get(operator);
        if (ranking != null) {
            if (!over.getOperands().isEmpty()) {
                throw new SqlTranslationException(operator + " takes no arguments");
            }
            fn = new FnExpr(ranking, false, List.of());
        } else {
            fn = switch (operator) {
                case "COUNT" -> {
                    if (over.getOperands().size() > 1) {
                        throw new SqlTranslationException("COUNT accepts at most one argument");
                    }
                    yield new FnExpr("count", false, windowArgs(over));
                }
                case "SUM", "AVG", "MIN", "MAX" -> {
                    if (over.getOperands().size() != 1) {
                        throw new SqlTranslationException(operator + " expects exactly one argument");
                    }
                    yield new FnExpr(operator.toLowerCase(Locale.ROOT), false, windowArgs(over));
                }
                default -> throw new SqlTranslationException("window function '" + operator + "' is not supported;"
                        + " supported: row_number, rank, dense_rank, count, sum, avg, min, max");
            };
        }
        final RexWindow window = over.getWindow();
        final List<Expr> partitionBy = new ArrayList<>(window.partitionKeys.size());
        for (final RexNode key : window.partitionKeys) {
            final Expr expr = toExpr(key);
            rejectProjectionOnlyOperand(expr, "a window PARTITION BY over");
            partitionBy.add(expr);
        }
        final List<WindowOrder> orderBy = new ArrayList<>(window.orderKeys.size());
        for (final RexFieldCollation collation : window.orderKeys) {
            final Expr expr = toExpr(collation.left);
            rejectProjectionOnlyOperand(expr, "a window ORDER BY over");
            final SortDir dir = collation.getDirection() == org.apache.calcite.rel.RelFieldCollation.Direction.DESCENDING
                    ? SortDir.DESC : SortDir.ASC;
            // A window collation always names a null direction; the DSL carries one only where the caller
            // did — the engine default (ASC → NULLS LAST, DESC → NULLS FIRST) stays implicit so a rendered
            // window reads back to the same expression.
            final com.epam.aidial.analytics.service.query.model.NullsOrder nulls = switch (collation.getNullDirection()) {
                case FIRST -> dir == SortDir.DESC ? null : com.epam.aidial.analytics.service.query.model.NullsOrder.FIRST;
                case LAST -> dir == SortDir.ASC ? null : com.epam.aidial.analytics.service.query.model.NullsOrder.LAST;
                default -> null;
            };
            orderBy.add(new WindowOrder(expr, dir, nulls));
        }
        final WindowFrame frame = over.getAggOperator().allowsFraming()
                ? frame(window, !orderBy.isEmpty()) : null;
        return new WindowExpr(fn, List.copyOf(partitionBy), List.copyOf(orderBy), frame);
    }

    private List<Expr> windowArgs(RexOver over) {
        final List<Expr> args = new ArrayList<>(over.getOperands().size());
        for (final RexNode operand : over.getOperands()) {
            final Expr arg = toExpr(operand);
            rejectProjectionOnlyOperand(arg, "a window function over");
            args.add(arg);
        }
        return args;
    }

    /** The frame as written, or {@code null} for the standard default the engine applies on its own. */
    private static WindowFrame frame(RexWindow window, boolean ordered) {
        final RexWindowBound lower = window.getLowerBound();
        final RexWindowBound upper = window.getUpperBound();
        if (lower == null || upper == null) {
            return null;
        }
        final boolean defaultLower = lower.isUnboundedPreceding();
        final boolean defaultUpper = ordered ? upper.isCurrentRow() : upper.isUnboundedFollowing();
        if (!window.isRows() && defaultLower && defaultUpper) {
            return null;
        }
        return new WindowFrame(window.isRows() ? FrameUnit.ROWS : FrameUnit.RANGE, bound(lower), bound(upper));
    }

    private static FrameBound bound(RexWindowBound bound) {
        if (bound.isUnboundedPreceding()) {
            return new FrameBound(FrameBoundKind.UNBOUNDED_PRECEDING, null);
        }
        if (bound.isUnboundedFollowing()) {
            return new FrameBound(FrameBoundKind.UNBOUNDED_FOLLOWING, null);
        }
        if (bound.isCurrentRow()) {
            return new FrameBound(FrameBoundKind.CURRENT_ROW, null);
        }
        final RexNode offset = bound.getOffset();
        if (!(offset instanceof RexLiteral literal) || literal.getValueAs(Long.class) == null) {
            throw new SqlTranslationException("a window frame offset must be an integer literal");
        }
        return new FrameBound(bound.isPreceding() ? FrameBoundKind.PRECEDING : FrameBoundKind.FOLLOWING,
                literal.getValueAs(Long.class));
    }

    /** {@code [NOT] IN (<subquery>)} and {@code [NOT] EXISTS (<subquery>)}; every other quantified form is refused. */
    private FilterNode subqueryPredicate(RexSubQuery subQuery, boolean negated) {
        return switch (subQuery.getKind()) {
            case IN -> {
                final List<Expr> operands = new ArrayList<>(subQuery.getOperands().size());
                for (final RexNode operand : subQuery.getOperands()) {
                    final Expr expr = toExpr(operand);
                    rejectProjectionOnlyOperand(expr, ComparisonOp.IN, "an IN predicate on");
                    operands.add(expr);
                }
                yield new SubqueryNode(negated ? SubqueryNode.SubqueryKind.NOT_IN : SubqueryNode.SubqueryKind.IN,
                        List.copyOf(operands), subqueries.apply(subQuery.rel));
            }
            case EXISTS -> new SubqueryNode(
                    negated ? SubqueryNode.SubqueryKind.NOT_EXISTS : SubqueryNode.SubqueryKind.EXISTS,
                    List.of(), existenceOnly(subqueries.apply(subQuery.rel)));
            case SCALAR_QUERY -> requireBoolean(subQuery, "a scalar subquery predicate");
            default -> throw new SqlTranslationException("SQL construct '" + subQuery.getKind()
                    + "' (quantified comparison) is not supported");
        };
    }

    /**
     * An {@code EXISTS} subquery reads no column, and Calcite says so by dropping the subquery's projection:
     * the plan it hands over is the filtered scan with the entity's <em>full</em> row type, heavy and
     * enrichment columns included. Rendering that verbatim would have the existence test read every column
     * of the entity, so a row-mode block is re-projected to a single literal. An aggregate block is left as
     * it is — its select list is what makes it a one-row result — and so is the page, since a stated
     * {@code OFFSET} changes which rows exist.
     */
    private static CompositeQuery existenceOnly(CompositeQuery plan) {
        final StructuredQuery block = plan.block();
        if (block.mode() != QueryMode.ROW) {
            return plan;
        }
        final StructuredQuery reprojected = new StructuredQuery(
                block.entity(), block.filter(), block.mode(), false,
                List.of(new OutputColumn(new ValueExpr(ValueType.INTEGER, "1"), "one")),
                null, block.having(), null, block.page(), block.finalRead());
        return new CompositeQuery(plan.source(), reprojected,
                List.of(new RelationColumn("one", QueryFieldType.INTEGER, ResultColumnKind.INT32)));
    }

    /** A scalar subquery in expression position: exactly one column, folded into its own plan. */
    private Expr scalarSubquery(RexSubQuery subQuery) {
        if (subQuery.getKind() != SqlKind.SCALAR_QUERY) {
            throw new SqlTranslationException("SQL construct '" + subQuery.getKind()
                    + "' is only supported as a predicate");
        }
        final CompositeQuery plan = subqueries.apply(subQuery.rel);
        if (plan.columns().size() != 1) {
            throw new SqlTranslationException("a scalar subquery must produce exactly one column");
        }
        return new ScalarSubqueryExpr(plan);
    }

    private static SqlTranslationException unsupported(RexNode node) {
        final String construct = node instanceof RexCall call ? call.getOperator().getName() : node.toString();
        return new SqlTranslationException(
                "SQL construct '" + construct + "' cannot be expressed in the structured query DSL");
    }
}
