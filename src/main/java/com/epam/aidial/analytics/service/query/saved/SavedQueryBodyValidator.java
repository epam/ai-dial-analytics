package com.epam.aidial.analytics.service.query.saved;

import com.epam.aidial.analytics.exception.SavedQueryValidationException;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTimeMode;
import com.epam.aidial.analytics.web.dto.query.saved.SavedQueryRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Envelope validation for a saved query: everything checkable without consulting the catalog.
 *
 * <p>Every rule here answers {@code 422 validation_error}. Catalog-dependent checks — does the entity
 * exist, does this field resolve, does the body translate — belong to {@code SavedQueryBodyResolver}.
 *
 * <p>Bean validation is deliberately not used: a {@code MethodArgumentNotValidException} maps to
 * {@code 400 bad_request}, and the envelope rules are specified as {@code 422}.
 */
@Component
@RequiredArgsConstructor
public class SavedQueryBodyValidator {

    /** Bounded shape for a relative period token. ADAS does not interpret its meaning, only its form. */
    private static final Pattern PERIOD = Pattern.compile("^[a-z0-9_]{1,32}$");

    /** The whole accepted vocabulary for {@code time}, quoted verbatim so a rejection is actionable. */
    private static final String TIME_SHAPES =
            "'time' must be null, a relative intent {\"mode\":\"relative\",\"period\":\"<token>\"}, or an "
                    + "absolute intent {\"mode\":\"absolute\",\"from\":<instant>,\"to\":<instant>}";

    private final JsonMapper jsonMapper;

    @Value("${saved-queries.max-body-bytes:65536}")
    private int maxBodyBytes;

    /** Runs every envelope rule, in the order that produces the most specific message first. */
    public void validate(SavedQueryRequestDto request) {
        if (request == null) {
            throw new SavedQueryValidationException("a saved query body is required");
        }
        rejectServerAssignedFields(request);
        validateName(request.name());
        validateExactlyOneBody(request);
        rejectParameters(request.query());
        final SavedQueryTime time = timeIntent(request);
        validateTime(time);
        validateSize(request, time);
    }

    /**
     * Server-assigned fields are rejected rather than silently ignored, so a client that sends one finds
     * out immediately instead of believing it took effect. {@code params} is refused for a different
     * reason: this capability has no parameter model at all (see {@link #rejectParameters}).
     */
    private static void rejectServerAssignedFields(SavedQueryRequestDto request) {
        final List<String> supplied = new ArrayList<>();
        if (request.id() != null) {
            supplied.add("id");
        }
        if (request.ownerId() != null) {
            supplied.add("owner_id");
        }
        if (request.ownerEmail() != null) {
            supplied.add("owner_email");
        }
        if (request.source() != null) {
            supplied.add("source");
        }
        if (request.generation() != null) {
            supplied.add("generation");
        }
        if (request.createdAt() != null) {
            supplied.add("created_at");
        }
        if (request.updatedAt() != null) {
            supplied.add("updated_at");
        }
        if (!supplied.isEmpty()) {
            throw new SavedQueryValidationException(
                    "these fields are assigned by the server and must not be supplied: "
                            + String.join(", ", supplied));
        }
        if (request.params() != null) {
            throw new SavedQueryValidationException(
                    "'params' is not supported: a saved query is a complete query, not a parameterized "
                            + "template");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new SavedQueryValidationException("'name' is required and must not be blank");
        }
    }

    private static void validateExactlyOneBody(SavedQueryRequestDto request) {
        final boolean hasQuery = request.query() != null;
        final boolean hasSql = sqlBody(request) != null;
        if (hasQuery == hasSql) {
            throw new SavedQueryValidationException(
                    "exactly one of 'query' or 'sql' must be set, not " + (hasQuery ? "both" : "neither"));
        }
    }

    /**
     * The SQL body as it must be stored: a blank string is <em>absent</em>, the same reading
     * {@link #validateExactlyOneBody} applies. One definition for both, because the two disagreeing is a
     * {@code 500}: validation would accept {@code {"query":…, "sql":""}} as a single body while persistence
     * wrote an empty string alongside the structured one, which the table's exactly-one-body CHECK rejects.
     */
    public static String sqlBody(SavedQueryRequestDto request) {
        return request.sql() == null || request.sql().isBlank() ? null : request.sql();
    }

    /**
     * A runtime parameter expression is refused here, with this capability's own message, rather than
     * being left to the translator. The translator would also reject it — it has no server-side parameter
     * registry — but as a {@code 400} carrying an internal message about the translator. Which errors this
     * API produces is part of its contract; the translator's wording is an implementation detail.
     */
    private static void rejectParameters(StructuredQuery query) {
        if (query == null) {
            return;
        }
        if (nodeHasParam(query.filter()) || nodeHasParam(query.having()) || selectHasParam(query.select())) {
            throw new SavedQueryValidationException(
                    "runtime parameter expressions are not supported in a saved query: a parameterized body "
                            + "cannot be executed, so it would be stored as a query that could never run");
        }
    }

    private static boolean selectHasParam(List<OutputColumn> select) {
        return select != null && select.stream()
                .anyMatch(column -> column != null && exprHasParam(column.expr()));
    }

    private static boolean nodeHasParam(FilterNode node) {
        return switch (node) {
            case null -> false;
            case LogicalNode logical -> logical.args() != null
                    && logical.args().stream().anyMatch(SavedQueryBodyValidator::nodeHasParam);
            case ComparisonNode comparison -> comparison.args() != null
                    && comparison.args().stream().anyMatch(SavedQueryBodyValidator::exprHasParam);
            // Internal to the SQL surface; a saved query body is a wire document and cannot carry one.
            case SubqueryNode ignored -> false;
        };
    }

    private static boolean exprHasParam(Expr expr) {
        return switch (expr) {
            case null -> false;
            case ParamExpr ignored -> true;
            case FnExpr fn -> fn.args() != null && fn.args().stream().anyMatch(SavedQueryBodyValidator::exprHasParam);
            case ArrayExpr array -> array.items() != null
                    && array.items().stream().anyMatch(SavedQueryBodyValidator::exprHasParam);
            case FieldExpr ignored -> false;
            case ValueExpr ignored -> false;
            case CaseExpr caseExpr -> (caseExpr.when() != null && caseExpr.when().stream().anyMatch(
                    branch -> nodeHasParam(branch.when()) || exprHasParam(branch.then())))
                    || exprHasParam(caseExpr.otherwise());
            case WindowExpr window -> exprHasParam(window.fn())
                    || (window.partitionBy() != null && window.partitionBy().stream().anyMatch(SavedQueryBodyValidator::exprHasParam))
                    || (window.orderBy() != null && window.orderBy().stream().anyMatch(order -> exprHasParam(order.expr())));
            case LambdaExpr lambda -> exprHasParam(lambda.body());
            // A parameter reference stands for an array element, never for a bound query parameter.
            case LambdaParamExpr ignored -> false;
            case ScalarSubqueryExpr ignored -> false;
        };
    }

    /**
     * The time intent as it must be stored: the raw request node bound to {@link SavedQueryTime}, or
     * {@code null}. Binding it here rather than in the DTO is what keeps a malformed intent a {@code 422}
     * — declared as {@code SavedQueryTime}, an unknown {@code mode} token, an unparseable instant, or a
     * {@code time} that is not an object at all fails inside Jackson, and a binding failure is a
     * {@code HttpMessageNotReadableException} the handler answers with {@code 400 bad_request}. This is the
     * same reason {@code SavedQueryController.parseScope} parses the {@code scope} parameter by hand.
     *
     * <p>Also the value persistence must write, for the same reason {@link #sqlBody} is: the stored intent
     * is the union this validator accepted, not the caller's node, so unrecognised keys inside {@code time}
     * cannot ride along into the metadata database.
     */
    public SavedQueryTime timeIntent(SavedQueryRequestDto request) {
        final JsonNode time = request.time();
        if (time == null || time.isNull()) {
            return null;
        }
        if (!time.isObject()) {
            throw new SavedQueryValidationException(TIME_SHAPES);
        }
        final JsonNode mode = time.get("mode");
        if (mode != null && !mode.isNull() && (!mode.isString() || modeOf(mode.stringValue()) == null)) {
            throw new SavedQueryValidationException(
                    "unknown 'time.mode' " + mode + "; expected 'relative' or 'absolute'");
        }
        try {
            return jsonMapper.treeToValue(time, SavedQueryTime.class);
        } catch (JacksonException e) {
            // A well-formed mode with an ill-formed member: a non-ISO instant, a wrongly typed period.
            throw new SavedQueryValidationException(TIME_SHAPES);
        }
    }

    /** The mode token as the mapper reads it (case-insensitive enums are enabled), or {@code null}. */
    private static SavedQueryTimeMode modeOf(String token) {
        try {
            return SavedQueryTimeMode.valueOf(token.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The time intent is a strictly validated union, not a free-form object: an unvalidated bag in the
     * metadata database is a place to smuggle arbitrary text past every other guard in this capability.
     */
    private static void validateTime(SavedQueryTime time) {
        if (time == null) {
            return;
        }
        if (time.mode() == null) {
            throw new SavedQueryValidationException("'time.mode' is required when 'time' is present");
        }
        if (time.mode() == SavedQueryTimeMode.RELATIVE) {
            validateRelative(time);
        } else {
            validateAbsolute(time);
        }
    }

    private static void validateRelative(SavedQueryTime time) {
        if (time.from() != null || time.to() != null) {
            throw new SavedQueryValidationException(
                    "a relative 'time' must not carry 'from'/'to': storing instants for a relative period "
                            + "would freeze the query to the day it was authored");
        }
        if (time.period() == null || !PERIOD.matcher(time.period()).matches()) {
            throw new SavedQueryValidationException(
                    "'time.period' must match " + PERIOD.pattern() + " for a relative time intent");
        }
    }

    private static void validateAbsolute(SavedQueryTime time) {
        if (time.period() != null) {
            throw new SavedQueryValidationException("an absolute 'time' must not carry 'period'");
        }
        if (time.from() == null || time.to() == null) {
            throw new SavedQueryValidationException(
                    "'time.from' and 'time.to' are both required for an absolute time intent");
        }
        if (time.from().isAfter(time.to())) {
            throw new SavedQueryValidationException("'time.from' must not be after 'time.to'");
        }
    }

    /**
     * Caps the stored body: a JSONB column with no bound is an unbounded write primitive available to any
     * authenticated caller.
     *
     * <p>{@code name}, {@code description}, and {@code tag} are <strong>not</strong> counted, and are not
     * otherwise bounded either — they are plain {@code TEXT}. That is a deliberate gap, not a covered
     * case: this cap is scoped to the query payload, the part that is structurally unbounded because a
     * caller composes it. A later change that wants the free-text fields bounded must add a limit rather
     * than assume this one already reaches them.
     */
    private void validateSize(SavedQueryRequestDto request, SavedQueryTime time) {
        final int size = serializedSize(request.query())
                + (request.sql() == null ? 0 : request.sql().getBytes(StandardCharsets.UTF_8).length)
                + serializedSize(time)
                + serializedSize(request.chart());
        if (size > maxBodyBytes) {
            throw new SavedQueryValidationException(
                    "the saved query body is " + size + " bytes, over the " + maxBodyBytes + "-byte maximum");
        }
    }

    private int serializedSize(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return jsonMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        } catch (JacksonException e) {
            throw new SavedQueryValidationException("the saved query body could not be serialized");
        }
    }
}
