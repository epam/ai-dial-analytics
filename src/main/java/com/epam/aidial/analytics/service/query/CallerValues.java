package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Condition;
import org.jooq.impl.DSL;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The two caller values, {@code caller.user_hash} and {@code caller.project}: a {@code param} of either name stands
 * for the identity of the caller reading, in a row policy and in a query alike (see the table-access-control and
 * structured-query specs).
 *
 * <p>A comparison with a caller value is bound before it is translated: a known value becomes a string literal, an
 * unknown one replaces the whole comparison with {@link #UNKNOWN}, which is {@code NULL} for every row — no row
 * matches it, none matches its negation, and an {@code OR} still matches through another branch. That is sound only
 * while the value is a direct operand of {@code eq}/{@code ne}: a function around it could turn the unknown into a
 * constant, and rows written for callers without the identity store exactly that constant.
 *
 * <p><b>Scope.</b> A query's comparison is bound by the filter translator, which takes the values from the scope the
 * current thread runs under: {@link #resolving} on execution, {@link #validating} on a pass that builds a query only
 * to validate it, and none at all — so every {@code param} stays refused — everywhere else, pipelines included. The
 * scope is a static thread-local rather than a bean because the translators are built by hand in many places; it is
 * opened and closed inside one call on one thread and holds only the function that call supplied.
 */
@Slf4j
public final class CallerValues {

    /** The caller's user hash, comparable with a {@code user_hash} column. */
    public static final String USER_HASH = "caller.user_hash";

    /** The project of the caller's project API key. */
    public static final String PROJECT = "caller.project";

    /** The closed set of caller-value names, matched case-sensitively. */
    public static final Set<String> NAMES = Set.of(USER_HASH, PROJECT);

    /** A longer value is unknown, which bounds what a read can add to a predicate or a statement. */
    public static final int MAX_BYTES = 512;

    /** The largest a caller value can render: every byte one that literal escaping doubles. */
    public static final String WORST_CASE_VALUE = "'".repeat(MAX_BYTES);

    /** {@code NULL} as a filter-typed operand: no row matches it, and no row matches its negation. */
    public static final Condition UNKNOWN = DSL.condition("CAST(NULL, 'Nullable(UInt8)')");

    private static final ThreadLocal<UnaryOperator<String>> SCOPE = new ThreadLocal<>();

    private CallerValues() {
    }

    /** Whether {@code expr} is a caller value. */
    public static boolean isCallerValue(Expr expr) {
        return expr instanceof ParamExpr param && NAMES.contains(param.name());
    }

    /** Whether one of {@code comparison}'s direct operands is a caller value. */
    public static boolean usesCallerValue(ComparisonNode comparison) {
        return comparison.args() != null && comparison.args().stream().anyMatch(CallerValues::isCallerValue);
    }

    /**
     * The executing caller's values, read from {@code claims}: {@code null} for a name the caller has no value for. An
     * unknown value makes a comparison match nothing, which from the caller's side looks like an empty table, so it is
     * logged and put on the span as {@code adas.<surface>.unknown.<name>} — by name, never by content.
     */
    public static UnaryOperator<String> of(SecurityClaimsExtractor claims, String surface) {
        return name -> {
            final String value = known(switch (name) {
                case USER_HASH -> claims.getUserHash();
                case PROJECT -> claims.getProject();
                default -> null;
            });
            if (value == null) {
                log.debug("A {} references {}, which this caller does not have; the comparison matches no row", surface, name);
                // One attribute per name: a single shared key would keep only the last unknown value.
                Span.current().setAttribute("adas." + surface + ".unknown." + name, true);
            }
            return value;
        };
    }

    /** {@code value} when it may be compared with, or {@code null}: absent, blank and over-long are unknown. */
    public static String known(String value) {
        return StringUtils.isBlank(value) || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES ? null : value;
    }

    /**
     * {@code comparison} with each caller value replaced by its string literal, or {@code null} when one of them is
     * unknown — the caller then renders {@link #UNKNOWN} in place of the whole comparison.
     *
     * @param values the value for a caller-value name, or {@code null} when the caller has none
     */
    public static ComparisonNode bind(ComparisonNode comparison, UnaryOperator<String> values) {
        final List<Expr> bound = new ArrayList<>(comparison.args().size());
        for (final Expr arg : comparison.args()) {
            if (isCallerValue(arg)) {
                final String known = known(values.apply(((ParamExpr) arg).name()));
                if (known == null) {
                    return null;
                }
                bound.add(new ValueExpr(ValueType.STRING, known));
            } else {
                bound.add(arg);
            }
        }
        return new ComparisonNode(comparison.op(), bound);
    }

    /**
     * The operand {@code comparison}, which uses a caller value, compares it with when it is an {@code eq} or {@code ne}
     * between exactly one caller value and something else, or {@code null}. Whether that operand is a {@code string}
     * field (or, in {@code having}, a {@code string} aggregate) is the caller's to check against its own bindings.
     */
    public static Expr comparedOperand(ComparisonNode comparison) {
        final List<Expr> args = comparison.args();
        final boolean equality = comparison.op() == ComparisonOp.EQ || comparison.op() == ComparisonOp.NE;
        if (!equality || args.size() != 2 || isCallerValue(args.get(0)) == isCallerValue(args.get(1))) {
            return null;
        }
        return isCallerValue(args.get(0)) ? args.get(1) : args.get(0);
    }

    /** The refusal of a caller value anywhere but a direct operand of an equality against a string field. */
    public static String placementMessage(String name) {
        return "caller value '" + name + "' is not supported by the query translator in this position: a caller value"
                + " may only be a direct operand of 'eq' ('=') or 'ne' ('<>') against a string field, in a filter, a"
                + " having or a join condition";
    }

    /** Runs {@code work} with comparisons bound to {@code values}: the execution path. */
    public static <T> T resolving(UnaryOperator<String> values, Supplier<T> work) {
        final UnaryOperator<String> outer = SCOPE.get();
        SCOPE.set(values);
        try {
            return work.get();
        } finally {
            if (outer == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(outer);
            }
        }
    }

    /**
     * Runs {@code work} with every caller value standing at its worst-case value: a pass that builds a query only to
     * validate it, reads no identity, and discards what it built.
     */
    public static <T> T validating(Supplier<T> work) {
        return resolving(ignored -> WORST_CASE_VALUE, work);
    }

    /** As {@link #validating(Supplier)} for work with no result. */
    public static void validating(Runnable work) {
        validating(() -> {
            work.run();
            return null;
        });
    }

    /** The values the current thread binds comparisons to, or {@code null} when caller values are refused here. */
    public static UnaryOperator<String> current() {
        return SCOPE.get();
    }
}
