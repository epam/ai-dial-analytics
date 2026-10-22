package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.web.dto.query.QueryFunctionReturnType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The catalog functions that take a <b>lambda</b>: ClickHouse's higher-order array family, under
 * ClickHouse's own names and in ClickHouse's own operand order — the lambda first, the array second.
 * Everything that has to recognize one by name resolves it here: the DSL translator, the DSL→SQL renderer,
 * the SQL→DSL translator, the operator table, and the pre-validation rewrite that carries a lambda
 * parameter through Calcite.
 *
 * <p><b>The array is at argument 1, not 0.</b> Every other array-consuming function in the catalog takes
 * its array first, which is why {@code ArrayValuedFunctions.acceptsArrayOperand} was once a plain
 * {@code index == 0} test. ClickHouse puts the array second here, so that rule is now a position per
 * function and this class supplies the position for these five.
 *
 * <p>Names are held in their <b>canonical camelCase</b> — these functions are new, so their ClickHouse
 * spelling is their DSL spelling, with no alias to reconcile — and matched case-insensitively, as every
 * other function-name match on both surfaces is.
 */
public final class HigherOrderFunctions {

    /** {@code arrayExists(x -> boolean, array)} — true when any element satisfies the body. */
    public static final String ARRAY_EXISTS = "arrayExists";

    /** {@code arrayAll(x -> boolean, array)} — true when every element does, vacuously so for an empty array. */
    public static final String ARRAY_ALL = "arrayAll";

    /** {@code arrayCount(x -> boolean, array)} — how many elements satisfy the body. */
    public static final String ARRAY_COUNT = "arrayCount";

    /** {@code arrayFilter(x -> boolean, array)} — the elements that satisfy the body, as an array. */
    public static final String ARRAY_FILTER = "arrayFilter";

    /** {@code arrayMap(x -> expression, array)} — the body applied to each element, as an array. */
    public static final String ARRAY_MAP = "arrayMap";

    /** The argument that holds the lambda — the first, as in ClickHouse. */
    public static final int LAMBDA_ARGUMENT = 0;

    /** The argument that holds the array — the second, as in ClickHouse. */
    public static final int ARRAY_ARGUMENT = 1;

    /** Return type per canonical name; a {@link LinkedHashMap} so {@link #names} reads in a stable order. */
    private static final Map<String, QueryFunctionReturnType> RETURNS = new LinkedHashMap<>();

    static {
        RETURNS.put(ARRAY_ALL, QueryFunctionReturnType.BOOLEAN);
        RETURNS.put(ARRAY_COUNT, QueryFunctionReturnType.INTEGER);
        RETURNS.put(ARRAY_EXISTS, QueryFunctionReturnType.BOOLEAN);
        RETURNS.put(ARRAY_FILTER, QueryFunctionReturnType.ARRAY);
        RETURNS.put(ARRAY_MAP, QueryFunctionReturnType.ARRAY);
    }

    private HigherOrderFunctions() {}

    /** Whether {@code name} is one of the five, matched case-insensitively. */
    public static boolean isHigherOrder(String name) {
        return canonical(name) != null;
    }

    /**
     * The canonical camelCase spelling of {@code name}, or {@code null} when it is not one of the five.
     * Callers that have already matched (the translator branches) use it to report and render the name the
     * catalog advertises rather than whatever case the caller wrote.
     */
    public static String canonical(String name) {
        if (name == null) {
            return null;
        }
        for (final String candidate : RETURNS.keySet()) {
            if (candidate.equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether the lambda's body must evaluate to a boolean — true for all but {@code arrayMap}, whose body
     * produces the mapped element and may be of any scalar type.
     */
    public static boolean requiresBooleanBody(String name) {
        return isHigherOrder(name) && !ARRAY_MAP.equalsIgnoreCase(name);
    }

    /** Whether the call itself answers with a boolean — {@code arrayExists} and {@code arrayAll}. */
    public static boolean returnsBoolean(String name) {
        return RETURNS.get(canonical(name)) == QueryFunctionReturnType.BOOLEAN;
    }

    /** The catalog return type of {@code name}, or {@code null} when it is not one of the five. */
    public static QueryFunctionReturnType returnType(String name) {
        return RETURNS.get(canonical(name));
    }

    /** The jOOQ field type a rendered call carries — the coarse Java type, as every other branch uses. */
    public static Class<?> resultClass(String name) {
        return switch (RETURNS.get(canonical(name))) {
            case BOOLEAN -> Boolean.class;
            case INTEGER -> Integer.class;
            default -> Object.class;
        };
    }

    /** The five names, comma-separated, for a message that has to list them. */
    public static String names() {
        return String.join(", ", RETURNS.keySet());
    }

    /**
     * Whether argument {@code index} of {@code name} is the array operand — the position rule
     * {@code ArrayValuedFunctions} and {@code SqlProjectionOnlyTypes} both delegate here for, so a declared
     * {@code array} column and a computed array are admitted in the same place.
     */
    public static boolean acceptsArrayAt(String name, int index) {
        return index == ARRAY_ARGUMENT && isHigherOrder(name);
    }
}
