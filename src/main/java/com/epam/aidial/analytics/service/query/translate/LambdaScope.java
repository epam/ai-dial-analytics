package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The scope a lambda body is translated in: the statement's own field bindings <b>extended</b> with the
 * lambda's parameter, rather than replaced by it. Extending is what makes a captured column resolve, which
 * is ClickHouse's own scoping ({@code arrayExists(x -> starts_with(x, deployment), roles)} reads
 * {@code deployment} from the enclosing row).
 *
 * <p>A parameter is bound under <b>two</b> keys, and the pair is what makes the two surfaces agree:
 *
 * <ul>
 *   <li>a <b>prefixed key</b> no column name can collide with, which is what {@link LambdaParamExpr}
 *       resolves against — so a {@code lambda_param} names a parameter and never a column, whatever the
 *       column list happens to hold;
 *   <li>the <b>bare name</b>, which is what {@code FieldExpr} resolves against — so a parameter
 *       <b>shadows</b> a column of the same name, which is ClickHouse's own rule and the rule the spec
 *       states.
 * </ul>
 *
 * <p><b>The bare binding is not a convenience.</b> Without it the two surfaces mean different things by
 * the same statement: {@code SqlLambdaRewriter.substitute} replaces every single-part identifier equal to
 * the parameter, so the SQL surface shadows, and {@code StructuredQueryToSqlRenderer} renders a
 * {@code FieldExpr} as that same single-part identifier — so a DSL document that resolved a captured
 * {@code deployment} to the column would render SQL in which it is the parameter. One binding closes that
 * gap; a caller who means the enclosing row's column names the parameter something else.
 *
 * <p>Nesting works by the same mechanism — an inner lambda extends the map its outer one produced, so an
 * inner body sees both parameters, innermost winning where the names collide.
 */
final class LambdaScope {

    /**
     * The key prefix for a parameter binding. A colon cannot occur in a catalog column name (a name is an
     * identifier, or two joined by a dot for an enrichment column), so the two namespaces cannot collide.
     */
    private static final String PARAM_PREFIX = "lambda_param:";

    /**
     * The accepted parameter spelling. Narrow on purpose: the name is the one caller-supplied token this
     * service renders into a ClickHouse statement as a <b>bare identifier</b>, since ClickHouse does not
     * accept a quoted one in a lambda's parameter position. Everything outside this pattern is refused
     * before any SQL is built.
     */
    private static final Pattern PARAM_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,63}");

    private LambdaScope() {}

    /**
     * The single parameter of {@code lambda}, validated. The DSL grammar carries a list because
     * ClickHouse's multi-parameter forms are spelled that way, but every function in this catalog takes one
     * array and so binds one name; a list of any other length is refused rather than silently truncated.
     */
    static String parameterOf(String fn, LambdaExpr lambda) {
        final List<String> params = lambda.params() == null ? List.of() : lambda.params();
        if (params.size() != 1) {
            throw new QueryValidationException("function '" + fn + "' takes a lambda of exactly one"
                    + " parameter, got " + params.size());
        }
        final String param = params.getFirst();
        if (param == null || !PARAM_NAME.matcher(param).matches()) {
            throw new QueryValidationException("lambda parameter '" + param + "' is not a valid name;"
                    + " it must match " + PARAM_NAME.pattern());
        }
        return param;
    }

    /**
     * {@code bindings} extended with {@code param} bound to the array's element type, under both the
     * prefixed key and the bare name. The bound jOOQ field is the bare parameter name — validated by
     * {@link #parameterOf} before it can reach here — because that is how ClickHouse spells a lambda
     * parameter in the body it renders.
     *
     * <p>The bare entry <b>replaces</b> whatever column was bound under that name, which is the shadowing
     * rule; a parameter naming no column simply adds one. Both entries carry the same binding, so a
     * parameter reached as a {@code lambda_param} and the same parameter reached as a {@code field} render
     * and type identically.
     *
     * @param elementType the element type of the array the lambda is applied to; {@code null} when the
     *     operand's element type is unknown, in which case the parameter is typed {@code string} — the
     *     element type every array-returning scalar in the catalog produces
     */
    static Map<String, QueryFieldBinding> extend(
            Map<String, QueryFieldBinding> bindings, String param, QueryFieldType elementType) {
        final Map<String, QueryFieldBinding> scoped = new LinkedHashMap<>(bindings);
        final Field<?> field = DSL.field(param);
        final QueryFieldBinding bound = new QueryFieldBinding(
                param, field, elementType == null ? QueryFieldType.STRING : elementType);
        scoped.put(PARAM_PREFIX + param, bound);
        scoped.put(param, bound);
        return scoped;
    }

    /**
     * The jOOQ field a parameter reference renders to, or a rejection naming the parameters that are in
     * scope — which is the useful message, since the mistake is almost always a name that belongs to
     * another lambda or to a column (and a column is spelled {@code field}, not {@code lambda_param}).
     */
    static Field<?> resolve(LambdaParamExpr param, Map<String, QueryFieldBinding> bindings) {
        final QueryFieldBinding binding = bindings.get(PARAM_PREFIX + param.name());
        if (binding == null) {
            throw new QueryValidationException("unknown lambda parameter '" + param.name() + "'; "
                    + inScope(bindings));
        }
        return binding.field();
    }

    /** The DSL type of a parameter reference, or {@code null} when it is bound by no enclosing lambda. */
    static QueryFieldType typeOf(LambdaParamExpr param, Map<String, QueryFieldBinding> bindings) {
        final QueryFieldBinding binding = bindings.get(PARAM_PREFIX + param.name());
        return binding == null ? null : binding.type();
    }

    /** The parameters bound by the enclosing lambdas, for a rejection message. */
    private static String inScope(Map<String, QueryFieldBinding> bindings) {
        final String names = bindings.keySet().stream()
                .filter(key -> key.startsWith(PARAM_PREFIX))
                .map(key -> "'" + key.substring(PARAM_PREFIX.length()) + "'")
                .collect(Collectors.joining(", "));
        return names.isEmpty()
                ? "no lambda parameter is in scope here"
                : "the parameters in scope are " + names;
    }
}
