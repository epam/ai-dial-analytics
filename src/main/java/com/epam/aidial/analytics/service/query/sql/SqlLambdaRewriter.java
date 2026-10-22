package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.HigherOrderFunctions;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLambda;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlShuttle;

import java.util.regex.Pattern;

/**
 * Inlines every lambda in a parsed statement, <strong>before</strong> it is validated:
 * {@code arrayExists(x -> f(x, col), arr)} becomes
 * {@code arrayExists(f(__lambdaParam(arr, 'x'), col), arr)}.
 *
 * <p><b>Why inline rather than let Calcite validate the lambda.</b> Calcite parses {@code x -> body} and
 * validates it in a {@code SqlLambdaScope} that resolves an identifier <em>only</em> against the lambda's
 * own parameters. ClickHouse resolves it against the enclosing row too, so
 * {@code arrayExists(x -> starts_with(x, deployment), roles)} — legal there — fails validation here with
 * "Param 'deployment' not found in lambda expression". Inlining leaves the body an ordinary expression in
 * the enclosing scope: a captured column resolves like any other column, while the parameter still carries
 * the array's element type, because that is what {@link SqlQueryOperators#LAMBDA_PARAM} is typed as.
 *
 * <p>The rewrite runs on the <em>parsed</em> tree for the reason {@link SqlTextRebinder} does: only there
 * is what the caller wrote still distinguishable from what the validator injects. {@code SqlShuttle}'s
 * post-order walk rewrites an inner lambda before the outer one that contains it, so an inner body is
 * already inlined when the outer parameter is substituted into it — which is what makes
 * {@code arrayExists(x -> arrayExists(y -> equals(y, x), inner), outer)} come out with each marker over
 * its own array.
 *
 * <p><b>What it refuses, and why here.</b> Three things are wrong with a lambda in ways the validator
 * would otherwise report as something else, or not at all:
 *
 * <ul>
 *   <li>an <b>aggregate or window call in a body</b>. Once inlined, the body sits in the enclosing scope,
 *       where {@code max(deployment)} is an ordinary aggregate — Calcite then reports the array operand as
 *       "not being grouped", which describes neither the mistake nor its fix. A body is evaluated per
 *       element of one row's array, so the call is refused here, naming it;
 *   <li>a lambda <b>outside</b> a higher-order call's first argument, and a higher-order call whose first
 *       argument is not a lambda. Nothing else can apply one, and an un-inlined {@code SqlLambda} reaching
 *       the validator would fail with Calcite's own wording about a form this surface never offered;
 *   <li>a caller-written {@code __lambdaParam(…)}. The marker is in the operator table, so the validator
 *       would resolve it like any catalog function; it is rejected before the walk introduces one of its
 *       own, so an occurrence seen here can only be the caller's — the same reservation
 *       {@link SqlTextRebinder} makes for its own marker.
 * </ul>
 *
 * <p>The parameter name is validated against the same pattern the DSL surface applies, since it is
 * rendered into the ClickHouse statement as a bare identifier.
 */
final class SqlLambdaRewriter {

    /** The parameter spelling both surfaces accept; the name reaches ClickHouse as a bare identifier. */
    private static final Pattern PARAM_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,63}");

    private SqlLambdaRewriter() {}

    /** Rewrites every higher-order call in {@code statement}, returning the rewritten tree. */
    static SqlNode rewrite(SqlNode statement) {
        if (statement == null) {
            return null;
        }
        final SqlNode rewritten = statement.accept(new SqlShuttle() {
            @Override
            public SqlNode visit(SqlCall call) {
                rejectSpelledMarker(call);
                final SqlNode visited = super.visit(call);
                if (visited instanceof SqlBasicCall basic
                        && HigherOrderFunctions.isHigherOrder(basic.getOperator().getName())) {
                    return inline(basic);
                }
                return visited;
            }
        });
        rejectRemainingLambdas(rewritten);
        return rewritten;
    }

    /**
     * The call with its lambda replaced by the inlined body, and the parameter's name appended as a third
     * operand.
     *
     * <p><b>The name rides on the call, not only on the markers.</b> Reconstruction runs innermost-first,
     * so when the inner call of
     * {@code arrayExists(x -> arrayExists(y -> equals(y, x), roles), roles)} is rebuilt, its body holds
     * both parameters' markers and neither has been consumed yet — leaving nothing in the body to say
     * which one the inner lambda binds. The binding call is the one place that knows, so it carries it.
     */
    private static SqlNode inline(SqlBasicCall call) {
        final String fn = HigherOrderFunctions.canonical(call.getOperator().getName());
        if (call.operandCount() != 2) {
            throw new SqlTranslationException("'" + fn + "' expects exactly two arguments, a lambda and an"
                    + " array, as in " + fn + "(x -> ..., array)");
        }
        if (!(call.operand(HigherOrderFunctions.LAMBDA_ARGUMENT) instanceof SqlLambda lambda)) {
            throw new SqlTranslationException("'" + fn + "' requires a lambda as its first argument, as in "
                    + fn + "(x -> ..., array)");
        }
        final SqlNode array = call.operand(HigherOrderFunctions.ARRAY_ARGUMENT);
        final String param = parameterOf(fn, lambda);
        final SqlNode body = lambda.getExpression();
        if (body == null) {
            throw new SqlTranslationException("'" + fn + "' has a lambda with no body");
        }
        rejectAggregate(fn, body);
        return call.getOperator().createCall(
                call.getParserPosition(),
                substitute(body, param, array),
                array,
                SqlLiteral.createCharString(param, call.getParserPosition()));
    }

    /** The lambda's single parameter name, validated. */
    private static String parameterOf(String fn, SqlLambda lambda) {
        final SqlNodeList params = lambda.getParameters();
        if (params == null || params.size() != 1) {
            throw new SqlTranslationException("'" + fn + "' takes a lambda of exactly one parameter, got "
                    + (params == null ? 0 : params.size()));
        }
        final String param = params.getFirst().toString();
        if (!PARAM_NAME.matcher(param).matches()) {
            throw new SqlTranslationException("lambda parameter '" + param + "' is not a valid name; it"
                    + " must match " + PARAM_NAME.pattern());
        }
        return param;
    }

    /** {@code body} with every occurrence of {@code param} replaced by the marker over {@code array}. */
    private static SqlNode substitute(SqlNode body, String param, SqlNode array) {
        return body.accept(new SqlShuttle() {
            @Override
            public SqlNode visit(SqlIdentifier id) {
                if (id.names.size() != 1 || !id.names.getFirst().equals(param)) {
                    return id;
                }
                final SqlParserPos pos = id.getParserPosition();
                return SqlQueryOperators.LAMBDA_PARAM.createCall(
                        pos, array.clone(pos), SqlLiteral.createCharString(param, pos));
            }
        });
    }

    /**
     * Refuses an aggregate or window call inside a lambda body. Keyed on the operator's own kind — any
     * {@code SqlAggFunction}, any {@code OVER} — rather than on a list of names, so an aggregate added to
     * the catalog later is covered here without this class being edited.
     */
    private static void rejectAggregate(String fn, SqlNode body) {
        body.accept(new SqlShuttle() {
            @Override
            public SqlNode visit(SqlCall call) {
                if (isAggregateName(call.getOperator().getName())) {
                    throw new SqlTranslationException("'" + call.getOperator().getName() + "' is an"
                            + " aggregate and cannot appear inside the lambda of '" + fn + "': the body is"
                            + " evaluated per element of one row's array. An aggregate may produce the"
                            + " array itself");
                }
                if (call.getKind() == SqlKind.OVER) {
                    throw new SqlTranslationException("a window function cannot appear inside the lambda"
                            + " of '" + fn + "': the body is evaluated per element of one row's array");
                }
                return super.visit(call);
            }
        });
    }

    /**
     * Refuses a lambda anywhere the walk above did not consume one — every position but a higher-order
     * call's first argument. Checked as a second pass rather than per parent node, because a lambda can sit
     * in a {@code SqlNodeList} (a select item, a {@code GROUP BY} key) that is nobody's direct operand, and
     * an un-inlined one reaching the validator would fail with Calcite's wording about a lambda form this
     * surface does not offer.
     */
    private static void rejectRemainingLambdas(SqlNode rewritten) {
        rewritten.accept(new SqlShuttle() {
            @Override
            public SqlNode visit(SqlCall call) {
                if (call instanceof SqlLambda) {
                    throw new SqlTranslationException("a lambda is only valid as the first argument of a"
                            + " higher-order array function (" + HigherOrderFunctions.names() + ")");
                }
                return super.visit(call);
            }
        });
    }

    /**
     * Whether {@code name} is an aggregate of the shared function catalog. Matched by <b>name</b> rather
     * than by the operator's class: this runs before validation, where every function call still carries
     * an unresolved operator and no {@code SqlAggFunction} exists yet. Reading the catalog rather than a
     * hand-kept list means an aggregate added later is covered here without this class being edited.
     */
    private static boolean isAggregateName(String name) {
        final QueryFunctionDto descriptor = QueryFunctionCatalog.describe(name);
        return descriptor != null
                && (descriptor.group() == QueryFunctionGroup.AGGREGATE
                || descriptor.group() == QueryFunctionGroup.ORDERED_SET_AGGREGATE);
    }

    /** Refuses a caller-written call against the internal marker, which the operator table resolves. */
    private static void rejectSpelledMarker(SqlCall call) {
        if (SqlQueryOperators.LAMBDA_PARAM.getName().equalsIgnoreCase(call.getOperator().getName())) {
            throw new SqlTranslationException("unsupported function '" + call.getOperator().getName()
                    + "' in query expression");
        }
    }
}
