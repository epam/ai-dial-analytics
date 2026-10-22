package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.HigherOrderFunctions;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The DSL type of an {@link Expr}, in the catalog's own {@link QueryFieldType} vocabulary — a bound column's
 * declared type, a literal's declared type, or a catalog function's declared return type.
 *
 * <p><b>Why this exists rather than reading the jOOQ {@code Field}'s Java type.</b> A translated field's
 * Java type is what {@code JooqTypes} maps the DSL type onto, and that mapping is lossy in both directions:
 * {@code long} and {@code timestamp} both become {@code BIGINT}/{@code Long}, and {@code object},
 * {@code array} and {@code map} all become {@code OTHER}/{@code Object}, while a timestamp <em>literal</em>
 * renders through {@code fromUnixTimestamp64Milli} and carries {@code Object} rather than the type its
 * column counterpart carries. Any rule that has to decide whether two expressions are of the same type —
 * {@code if}'s branch unification is the one that does — gets three wrong answers out of that: it unifies a
 * timestamp with a long, unifies an object with an array, and refuses a timestamp column against a timestamp
 * literal. Asking the DSL for the DSL's own type instead makes all three come out right.
 *
 * <p><b>Total over everything that can reach it.</b> {@code Expr} has five kinds; {@code ParamExpr} and
 * {@code ArrayExpr} are rejected by {@link ExprTranslator#toField} before any caller here runs, a
 * {@code FieldExpr} has already been resolved against the bindings by the same method, and an
 * {@code FnExpr}'s name has already been matched against the closed catalog. So the only unresolvable input
 * is the {@code null} literal, which has no type by construction and which callers report on their own
 * terms. {@link #of} returns {@code null} for exactly that case.
 */
final class ExprFieldType {

    /**
     * For each catalog function declaring {@code same_as_argument}, the argument index its result type
     * follows. {@code if} names its {@code then} branch — the two branches have already been unified by the
     * time an outer expression asks, so either would do and the first one a reader sees is the better
     * answer for a message.
     */
    private static final Map<String, Integer> TYPE_CARRYING_ARG = Map.of(
            "arg_max", 0,
            "if", 1,
            "max", 0,
            "min", 0,
            "percentile_disc", 1);

    /** The types that are one numeric family for widening purposes, mirroring {@code EnrichmentTypeMapping}. */
    private static final Set<QueryFieldType> NUMERIC =
            Set.of(QueryFieldType.INTEGER, QueryFieldType.LONG, QueryFieldType.DECIMAL);

    private ExprFieldType() {}

    /**
     * The DSL type of {@code expr}, or {@code null} for a null literal (the one expression with no type).
     *
     * @param bindings the resolved field bindings the expression was translated against
     */
    static QueryFieldType of(Expr expr, Map<String, QueryFieldBinding> bindings) {
        return switch (expr) {
            case FieldExpr field -> {
                final QueryFieldBinding binding = bindings.get(field.name());
                yield binding == null ? null : binding.type();
            }
            case ValueExpr value -> ofLiteral(value.valueType());
            case FnExpr fn -> ofFunction(fn, bindings);
            // Both are rejected by ExprTranslator#toField before any caller here runs; enumerated rather
            // than defaulted so a new Expr kind fails to compile here instead of silently reading untyped.
            case ParamExpr ignored -> null;
            case ArrayExpr ignored -> null;
            // A lambda has no type of its own — what a higher-order call asks about is its BODY, in the
            // scope that call built. Typed null so a lambda reaching any other operand check is refused
            // with that check's own message.
            case LambdaExpr ignored -> null;
            case LambdaParamExpr param -> LambdaScope.typeOf(param, bindings);
            case CaseExpr caseExpr -> ofCase(caseExpr, bindings);
            case WindowExpr window -> ofWindow(window, bindings);
            // A scalar subquery is typed by its one column, carried by the plan the SQL front end built.
            case ScalarSubqueryExpr scalar -> scalar.subquery() == null || scalar.subquery().columns().isEmpty()
                    ? null : scalar.subquery().columns().getFirst().type();
        };
    }

    /** Whether {@code type} is one of the mutually-widening numeric types. */
    static boolean isNumeric(QueryFieldType type) {
        return NUMERIC.contains(type);
    }

    /**
     * Whether a value of {@code type} is a single scalar — false for the three non-scalar families, which
     * have no literal and no scalar rendering in the DSL and so cannot be an ordinary operand.
     *
     * <p>Being non-scalar is not the same as being incomparable. {@code DocumentComparisons} reads this
     * classification to find the operands it then rules on, and admits an {@code object} field as a
     * <em>document</em> comparison operand — a comparison over the column's canonical JSON text rather
     * than over a scalar value. {@code array} and {@code map} remain operands of nothing.
     */
    static boolean isScalar(QueryFieldType type) {
        return type != QueryFieldType.OBJECT && type != QueryFieldType.ARRAY && type != QueryFieldType.MAP;
    }

    /**
     * The literal types map onto the field types one for one, by name, except {@code null} — which is a
     * value of every type and therefore of none.
     */
    private static QueryFieldType ofLiteral(ValueType type) {
        return type == ValueType.NULL ? null : QueryFieldType.fromCode(type.code());
    }

    /**
     * A call's type is its catalog descriptor's declared {@code returns}, with two families that do not name
     * a single type: {@code numeric} stands for the widening numeric family and is reported as its widest
     * member, since every use of it here is a widening comparison; {@code same_as_argument} follows the
     * argument {@link #TYPE_CARRYING_ARG} names.
     */
    private static QueryFieldType ofFunction(FnExpr fn, Map<String, QueryFieldBinding> bindings) {
        final QueryFunctionDto descriptor = QueryFunctionCatalog.describe(fn.name());
        if (descriptor == null) {
            return null;
        }
        return switch (descriptor.returns()) {
            case STRING -> QueryFieldType.STRING;
            case INTEGER -> QueryFieldType.INTEGER;
            case LONG -> QueryFieldType.LONG;
            case NUMERIC -> QueryFieldType.DECIMAL;
            case BOOLEAN -> QueryFieldType.BOOLEAN;
            case TIMESTAMP -> QueryFieldType.TIMESTAMP;
            case ARRAY -> QueryFieldType.ARRAY;
            case SAME_AS_ARGUMENT -> ofCarryingArg(descriptor.name(), fn, bindings);
            case ELEMENT_OF_ARGUMENT -> ofElementOfFirstArg(fn, bindings);
        };
    }

    /**
     * The element type of a call's first argument — the declared return type of {@code array_join}, the one
     * function that consumes an array and answers with one of its elements.
     *
     * <p>Two operand shapes can reach it, because those are the two {@code ExprTranslator} admits at that
     * position. A bound {@code array} column carries its element type in the binding, published by discovery
     * as {@code element_type}. An array-returning catalog call carries no declared type of its own, so its
     * element type is derived: {@code map_keys}, {@code split_string} and {@code json_extract_array} are
     * fixed at {@code string} — they read or split text whatever their operand holds — while
     * {@code group_uniq_array} collects its own operand's values and {@code array_slice} narrows its own
     * operand, so both carry that operand's element type (the latter by recursing).
     * {@code RelToStructuredQueryTranslator} derives the same five on the SQL surface; the two must agree or
     * a query types differently depending on which endpoint it arrived through.
     *
     * <p>Anything else yields {@code null}, the untyped answer this class already gives a null literal.
     * {@code ExprTranslator} rejects such an operand before a caller can observe it, so this is a fallback,
     * not a path.
     */
    private static QueryFieldType ofElementOfFirstArg(FnExpr fn, Map<String, QueryFieldBinding> bindings) {
        if (fn.args() == null || fn.args().isEmpty()) {
            return null;
        }
        return ofElement(fn.args().getFirst(), bindings);
    }

    /**
     * The element type of an {@code array}-typed expression — {@link #ofElementOfFirstArg} asked directly
     * about the operand rather than about the call that consumes it, which is what {@code array_to_string}
     * needs to render each element as {@code to_string} renders a scalar of that type.
     *
     * @param operand an expression whose {@link #of} is {@code array}
     */
    static QueryFieldType ofElement(Expr operand, Map<String, QueryFieldBinding> bindings) {
        if (operand instanceof FieldExpr(String name)) {
            final QueryFieldBinding binding = bindings.get(name);
            return binding == null ? null : binding.elementType();
        }
        if (operand instanceof FnExpr call && ArrayValuedFunctions.isArrayValued(call.name())) {
            // group_uniq_array is one of the two array-returning calls whose element type is NOT fixed: it
            // collects its own operand's values, so an array_join over it yields that operand's type. The
            // three text-producing ones extract or split text and are string whatever they read. Answering
            // `string` for all of them typed array_join(group_uniq_array(total_tokens)) as text, and that
            // type is what the aggregate-mode alias binding carries — so a `having` or `sort` over the alias
            // was then validated as text too.
            if (ArrayValuedFunctions.GROUP_UNIQ_ARRAY.equalsIgnoreCase(call.name())) {
                return ofCarryingOperand(call, bindings);
            }
            // array_slice is the other: it narrows an array rather than producing one, so its element type
            // is whatever its own operand's is — resolved by asking this same question one level down, which
            // is what types array_join(array_slice(group_uniq_array(total_tokens), 1, 3)) as numeric.
            if (ArrayValuedFunctions.ARRAY_SLICE.equalsIgnoreCase(call.name())) {
                return ofElementOfFirstArg(call, bindings);
            }
            // The two flattening aggregates collect the ELEMENTS of their operand, so an element of their
            // result is an element of that operand — one question, asked one level down.
            if (ArrayValuedFunctions.isFlatteningAggregate(call.name())) {
                return ofElementOfFirstArg(call, bindings);
            }
            // arrayFilter narrows its operand and so keeps its element type; arrayMap replaces each element
            // with its body, so an element of the result is whatever the body produces — asked in the scope
            // that call's own translation built, which is why the lambda is consulted rather than the array.
            if (HigherOrderFunctions.ARRAY_FILTER.equalsIgnoreCase(call.name())) {
                return ofArrayOperandElement(call, bindings);
            }
            if (HigherOrderFunctions.ARRAY_MAP.equalsIgnoreCase(call.name())) {
                return ofMappedElement(call, bindings);
            }
            return QueryFieldType.STRING;
        }
        return null;
    }

    /**
     * The element type of a higher-order call's array operand — argument {@code 1}, since ClickHouse puts
     * the array second.
     */
    private static QueryFieldType ofArrayOperandElement(FnExpr call, Map<String, QueryFieldBinding> bindings) {
        final List<Expr> args = call.args() == null ? List.of() : call.args();
        return args.size() <= HigherOrderFunctions.ARRAY_ARGUMENT
                ? null : ofElement(args.get(HigherOrderFunctions.ARRAY_ARGUMENT), bindings);
    }

    /**
     * The type of an {@code arrayMap} result's element: the type of the lambda's body, resolved in the
     * scope that binds the parameter to the operand's element type — the same scope
     * {@code ExprTranslator} translates the body in, so the two answer alike.
     */
    private static QueryFieldType ofMappedElement(FnExpr call, Map<String, QueryFieldBinding> bindings) {
        final List<Expr> args = call.args() == null ? List.of() : call.args();
        if (args.size() <= HigherOrderFunctions.ARRAY_ARGUMENT
                || !(args.get(HigherOrderFunctions.LAMBDA_ARGUMENT) instanceof LambdaExpr(List<String> params, Expr body))
                || params == null || params.size() != 1 || body == null) {
            return null;
        }
        final QueryFieldType element = ofArrayOperandElement(call, bindings);
        return of(body, LambdaScope.extend(bindings, params.getFirst(), element));
    }

    /** The type of a single-operand call's own argument, or {@code null} when it has none. */
    private static QueryFieldType ofCarryingOperand(FnExpr call, Map<String, QueryFieldBinding> bindings) {
        return call.args() == null || call.args().isEmpty() ? null : of(call.args().getFirst(), bindings);
    }

    /**
     * A {@code case}'s type is its branches' unified type: equal types stay, numerics widen to the widest,
     * an enum against a string reads as the enum. A pair that does not unify yields the first branch's type
     * here and is refused by {@code ExprTranslator} with the mismatch named; a null-literal branch is skipped
     * for the same reason. An absent {@code else} does not contribute (it is null).
     */
    private static QueryFieldType ofCase(CaseExpr caseExpr, Map<String, QueryFieldBinding> bindings) {
        QueryFieldType type = null;
        final List<Expr> values = new ArrayList<>();
        if (caseExpr.when() != null) {
            for (final CaseBranch branch : caseExpr.when()) {
                if (branch != null) {
                    values.add(branch.then());
                }
            }
        }
        values.add(caseExpr.otherwise());
        for (final Expr value : values) {
            final QueryFieldType candidate = value == null ? null : of(value, bindings);
            if (candidate == null) {
                continue;
            }
            if (type == null || type == candidate) {
                type = candidate;
            } else if (isNumeric(type) && isNumeric(candidate)) {
                type = widest(type, candidate);
            } else if (type == QueryFieldType.STRING && candidate == QueryFieldType.ENUM) {
                type = QueryFieldType.ENUM;
            }
        }
        return type;
    }

    /** The ranking functions are 64-bit counters; an aggregate under a window has the aggregate's type. */
    private static QueryFieldType ofWindow(WindowExpr window, Map<String, QueryFieldBinding> bindings) {
        if (window.fn() == null || window.fn().name() == null) {
            return null;
        }
        if (ExprTranslator.RANKING_FUNCTIONS.contains(window.fn().name().toLowerCase(java.util.Locale.ROOT))) {
            return QueryFieldType.LONG;
        }
        return ofFunction(window.fn(), bindings);
    }

    private static QueryFieldType widest(QueryFieldType left, QueryFieldType right) {
        for (final QueryFieldType candidate : new QueryFieldType[] {QueryFieldType.DECIMAL, QueryFieldType.LONG}) {
            if (left == candidate || right == candidate) {
                return candidate;
            }
        }
        return QueryFieldType.INTEGER;
    }

    private static QueryFieldType ofCarryingArg(
            String canonicalName, FnExpr fn, Map<String, QueryFieldBinding> bindings) {
        final Integer index = TYPE_CARRYING_ARG.get(canonicalName);
        if (index == null || fn.args() == null || fn.args().size() <= index) {
            return null;
        }
        return of(fn.args().get(index), bindings);
    }
}
