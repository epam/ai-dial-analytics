package com.epam.aidial.analytics.web.handler;

import com.clickhouse.client.api.ServerException;
import com.epam.aidial.analytics.configuration.logging.AccessLogFilter;
import com.epam.aidial.analytics.exception.ActivityNotFoundException;
import com.epam.aidial.analytics.exception.BackfillDisabledException;
import com.epam.aidial.analytics.exception.BackfillInProgressException;
import com.epam.aidial.analytics.exception.BackfillNotFoundException;
import com.epam.aidial.analytics.exception.BackfillRangeException;
import com.epam.aidial.analytics.exception.CatalogBundleMalformedException;
import com.epam.aidial.analytics.exception.CatalogBundleVersionUnsupportedException;
import com.epam.aidial.analytics.exception.CatalogExportInvalidException;
import com.epam.aidial.analytics.exception.CatalogImportConflictException;
import com.epam.aidial.analytics.exception.CatalogImportForbiddenException;
import com.epam.aidial.analytics.exception.CatalogImportInvalidException;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.exception.ColumnInRowPolicyException;
import com.epam.aidial.analytics.exception.DeclarationUnresolvableException;
import com.epam.aidial.analytics.exception.IllegalFinalException;
import com.epam.aidial.analytics.exception.InvalidCursorException;
import com.epam.aidial.analytics.exception.InvalidFilterColumnException;
import com.epam.aidial.analytics.exception.InvalidFilterValueException;
import com.epam.aidial.analytics.exception.InvalidRequestParameterException;
import com.epam.aidial.analytics.exception.OtelDisabledException;
import com.epam.aidial.analytics.exception.OtelSinkNotFoundException;
import com.epam.aidial.analytics.exception.PipelineAccessDeniedException;
import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import com.epam.aidial.analytics.exception.PipelinePreviewBudgetExceededException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.QueryBudgetExceededException;
import com.epam.aidial.analytics.exception.QueryEntityNotFoundException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.ResolutionPolicyUnsupportedException;
import com.epam.aidial.analytics.exception.RevisionNotFoundException;
import com.epam.aidial.analytics.exception.RowPolicyConfinedException;
import com.epam.aidial.analytics.exception.RunnerContractException;
import com.epam.aidial.analytics.exception.SavedQueryNotFoundException;
import com.epam.aidial.analytics.exception.SavedQueryValidationException;
import com.epam.aidial.analytics.exception.SensitiveClearanceRequiredException;
import com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException;
import com.epam.aidial.analytics.exception.SensitiveGrantWidensException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableRelayoutInProgressException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.exception.UnattributableSavedQueryException;
import com.epam.aidial.analytics.exception.WriteColumnNotAllowedException;
import com.epam.aidial.analytics.exception.WriteOutputColumnRequiredException;
import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Translates uncaught exceptions into the stable {@link ErrorView} JSON contract. Concrete
 * endpoints add their own handlers (e.g. ClickHouse availability -> 503) as features land.
 *
 * <p><b>Refusal logging.</b> A refusal of the caller's request (ClickHouse rejecting a query or being down,
 * PostgreSQL, the preview, query and pipeline read budgets, the runner contract) is logged at WARN
 * <em>without</em> a stack, at most once per (handler, {@code error} code, path pattern) per minute, and
 * each line after the first gives the number suppressed since the previous one — see
 * {@link RefusalLogLimiter}. The stack is at DEBUG on the {@link SqlLog SQL logger}, not this class's: a driver
 * message anywhere in the cause chain can quote the statement, literals included. None of it changes a response:
 * the {@link ErrorView} bodies, status codes and codes are exactly what they were.
 */
@RestControllerAdvice
@Slf4j
public class DefaultExceptionHandler {

    /** Hop ceiling when walking a cause chain: above any real wrapping depth, low enough to end a cycle. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** Written for the caller or the path pattern when there is none. */
    private static final String NONE = "-";

    /**
     * Looked up rather than injected: web slices build this advice without the service layer, and a refusal
     * line must never depend on the caller being resolvable.
     */
    private final ObjectProvider<SecurityClaimsExtractor> claims;
    private final RefusalLogLimiter refusalLog;

    @Autowired
    public DefaultExceptionHandler(ObjectProvider<SecurityClaimsExtractor> claims) {
        this(claims, new RefusalLogLimiter(System::nanoTime));
    }

    DefaultExceptionHandler(ObjectProvider<SecurityClaimsExtractor> claims, RefusalLogLimiter refusalLog) {
        this.claims = claims;
        this.refusalLog = refusalLog;
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoResourceFoundException.class)
    public ErrorView handleNotFound(HttpServletRequest req, NoResourceFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", "Resource not found");
    }

    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ErrorView handleMethodNotAllowed(HttpServletRequest req, HttpRequestMethodNotSupportedException ex) {
        return new ErrorView(req, HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        ConstraintViolationException.class,
        MissingRequestValueException.class,
    })
    public ErrorView handleBadRequest(HttpServletRequest req, Exception ex) {
        // Never echo ex.getMessage(): Jackson/binding messages leak type names, field paths, and input
        // fragments. Return a stable generic message; the detail is for the server log only.
        // MethodArgumentTypeMismatchException belongs here rather than in the catch-all below: a query
        // param or path variable that will not convert to its declared type (?enabled=maybe) is the
        // caller's error, and 500 would invite a retry that cannot succeed.
        log.debug("[{}] Request: {} - rejected as bad request", req.getMethod(), req.getServletPath(), ex);
        final String unknown = unknownProperty(ex);
        if (unknown != null) {
            return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request",
                "The request body carries an unrecognized field '" + unknown + "'");
        }
        final String authored = authoredParameterMessage(ex);
        if (authored != null) {
            return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request", authored);
        }
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request",
            "The request could not be processed; check the request body, parameters, and content type");
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidRequestParameterException.class)
    public ErrorView handleInvalidRequestParameter(HttpServletRequest req, InvalidRequestParameterException ex) {
        // The type reaches here two ways and both echo the message, for the reason
        // authoredParameterMessage() below sets out: only hand-written code throws it, and that code names
        // the accepted set rather than repeating what the caller sent. Wrapped in a binding failure when an
        // @InitBinder editor refuses a value; bare when a handler refuses a combination of parameters that
        // each bound fine on their own.
        log.debug("[{}] Request: {} - rejected as an invalid request parameter",
                req.getMethod(), req.getServletPath(), ex);
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    /**
     * The message of an {@link InvalidRequestParameterException} in this failure's cause chain, or null.
     *
     * <p>The <b>second</b> carve-out in the never-echo rule, and it holds for the same reason as the first:
     * what is echoed did not come from the caller's input. A field name did not, because it is matched
     * against our own type; this message did not, because only hand-written code throws that type — an
     * {@code @InitBinder} editor, or a handler refusing a combination of parameters that each bound fine on
     * their own — and that code names the accepted set rather than repeating what was sent. Every other
     * binding failure — Spring's converters, Jackson's — still gets the generic sentence, which is the
     * whole point of keeping the marker type narrow. The bare throw is handled by
     * {@link #handleInvalidRequestParameter}; this method covers the wrapped one.
     *
     * <p>Read from the chain rather than the exception at hand: Spring wraps a {@code PropertyEditor}'s
     * refusal in {@code MethodArgumentTypeMismatchException}, so the marker is never the top-level type.
     */
    private static String authoredParameterMessage(Exception ex) {
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof InvalidRequestParameterException) {
                return cause.getMessage();
            }
            final Throwable next = cause.getCause();
            cause = next == cause ? null : next;
        }
        return null;
    }

    /**
     * The name of the unrecognized field a strict request read rejected, or null when the failure was
     * anything else. See {@code StrictRequestJsonConfiguration} for why requests are read strictly.
     *
     * <p>This is the one part of a Jackson message that <b>is</b> safe to echo, and it is the part worth
     * echoing: the field name came from the caller's own body, not from our types, and a rejection that
     * does not say which field was wrong replaces a silent misread with a silent refusal. Nothing else is
     * taken from the exception — not the value, not the target type, not the known-property list, not the
     * JSON path — so the general rule above stands for every other binding failure.
     */
    private static String unknownProperty(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnrecognizedPropertyException unrecognized) {
                return unrecognized.getPropertyName();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(QueryEntityNotFoundException.class)
    public ErrorView handleQueryEntityNotFound(HttpServletRequest req, QueryEntityNotFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalFinalException.class)
    public ErrorView handleIllegalFinal(HttpServletRequest req, IllegalFinalException ex) {
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "illegal_final", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(QueryValidationException.class)
    public ErrorView handleQueryValidation(HttpServletRequest req, QueryValidationException ex) {
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(SqlTranslationException.class)
    public ErrorView handleSqlTranslation(HttpServletRequest req, SqlTranslationException ex) {
        // Translation messages are built by our SQL front end and name only the offending construct
        // or catalog entities/columns the caller can already discover — safe to echo.
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(ClickHouseQueryException.class)
    public ErrorView handleClickHouseQuery(HttpServletRequest req, ClickHouseQueryException ex) {
        // The server's error code only: a driver message can carry the statement, literals included.
        logRefusal(req, "clickhouse_query", "bad_request", ex, "ClickHouse rejected query ({})", clickHouseFailure(ex));
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request",
            "The query could not be executed against ClickHouse; check field/function/type compatibility");
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(ClickHouseUnavailableException.class)
    public ErrorView handleClickHouseUnavailable(HttpServletRequest req, ClickHouseUnavailableException ex) {
        logRefusal(req, "clickhouse_unavailable", "clickhouse_unavailable", ex, "ClickHouse unavailable ({})",
                clickHouseFailure(ex));
        return new ErrorView(req, HttpStatus.SERVICE_UNAVAILABLE, "clickhouse_unavailable",
            "ClickHouse is currently unavailable");
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(TableNotFoundException.class)
    public ErrorView handleTableNotFound(HttpServletRequest req, TableNotFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(TableConflictException.class)
    public ErrorView handleTableConflict(HttpServletRequest req, TableConflictException ex) {
        return new ErrorView(req, HttpStatus.CONFLICT, "conflict", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(SystemTableException.class)
    public ErrorView handleSystemTable(HttpServletRequest req, SystemTableException ex) {
        return new ErrorView(req, HttpStatus.CONFLICT, "table_is_system", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(TableValidationException.class)
    public ErrorView handleTableValidation(HttpServletRequest req, TableValidationException ex) {
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", ex.getMessage());
    }

    /**
     * Its own code rather than {@code validation_error}: the refusal names a pipeline the operator was
     * probably not thinking about when it edited a grant list, so a client has to be able to tell this
     * apart from a malformed body.
     */
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(SensitiveGrantWidensException.class)
    public ErrorView handleSensitiveGrantWidens(HttpServletRequest req, SensitiveGrantWidensException ex) {
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "sensitive_grant_widens", ex.getMessage());
    }

    /**
     * A conflict with stored state rather than a fault in the request: the operator's edit is well-formed,
     * and a pipeline it does not name cannot be read.
     */
    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(DeclarationUnresolvableException.class)
    public ErrorView handleDeclarationUnresolvable(
            HttpServletRequest req, DeclarationUnresolvableException ex) {
        log.warn("[{}] Request: {} - a stored pipeline declaration could not be resolved",
                req.getMethod(), req.getServletPath(), ex);
        return new ErrorView(
                req, HttpStatus.CONFLICT, "pipeline_declaration_unresolvable", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(DataAccessException.class)
    public ErrorView handlePostgresUnavailable(HttpServletRequest req, DataAccessException ex) {
        // The exception's type only: a DataAccessException message usually carries the SQL.
        logRefusal(req, "postgres", "postgres_unavailable", ex, "PostgreSQL data access error ({})",
                ex.getClass().getSimpleName());
        return new ErrorView(req, HttpStatus.SERVICE_UNAVAILABLE, "postgres_unavailable",
            "The catalog database is currently unavailable");
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(BackfillRangeException.class)
    public ErrorView handleBackfillRange(HttpServletRequest req, BackfillRangeException ex) {
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(BackfillInProgressException.class)
    public ErrorView handleBackfillInProgress(HttpServletRequest req, BackfillInProgressException ex) {
        // Deliberately NOT the generic conflict code TableConflictException uses: the remedy here (wait for
        // the run holding the slot) is none of that one's, and a client acting on the code alone would apply
        // the wrong one. The string matches the enrichment runner's code for its backfill conflict.
        return new ErrorView(req, HttpStatus.CONFLICT, "backfill_conflict", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(BackfillNotFoundException.class)
    public ErrorView handleBackfillNotFound(HttpServletRequest req, BackfillNotFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    /**
     * Its own code: the condition is transient and the remedy is to retry the same write shortly, which neither
     * {@code clickhouse_unavailable} nor {@code postgres_unavailable} says.
     */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(TableRelayoutInProgressException.class)
    public ErrorView handleTableRelayoutInProgress(HttpServletRequest req, TableRelayoutInProgressException ex) {
        return new ErrorView(req, HttpStatus.SERVICE_UNAVAILABLE, "table_relayout_in_progress", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(BackfillDisabledException.class)
    public ErrorView handleBackfillDisabled(HttpServletRequest req, BackfillDisabledException ex) {
        return new ErrorView(req, HttpStatus.SERVICE_UNAVAILABLE, "backfill_disabled", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(PipelineNotFoundException.class)
    public ErrorView handlePipelineNotFound(HttpServletRequest req, PipelineNotFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "unknown_pipeline", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(PipelineValidationException.class)
    public ErrorView handlePipelineValidation(HttpServletRequest req, PipelineValidationException ex) {
        // Messages name the offending construct plus catalog tables/columns the caller can already
        // discover through /v1/tables — safe to echo, and the whole point is that the caller can fix it.
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "pipeline_validation_failed", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(PipelinePreviewBudgetExceededException.class)
    public ErrorView handlePipelinePreviewBudget(
            HttpServletRequest req, PipelinePreviewBudgetExceededException ex) {
        // 422, not 503: the refusal is deterministic, so a client backing off and retrying — the correct
        // response to a 503 — would loop. The message names which budget was exceeded and nothing from the
        // driver, so an operator can tell "this declaration is too expensive to preview" from "ClickHouse
        // is down" without a log dive.
        // Keyed per pipeline, as the read budget's refusal is, so one pipeline previewed repeatedly over its
        // budget cannot hide another's first refusal.
        logRefusal(req, "pipeline_preview_budget", "pipeline_preview_budget_exceeded", pathPattern(req) + " "
                + Objects.toString(MDC.get(AccessLogFilter.PIPELINE_NAME), NONE), ex,
                "refused by the pipeline preview budget: {}", ex.getMessage());
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT,
                "pipeline_preview_budget_exceeded", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(QueryBudgetExceededException.class)
    public ErrorView handleQueryBudget(HttpServletRequest req, QueryBudgetExceededException ex) {
        // 422, not 503: the refusal is deterministic — the identical query is refused identically until the
        // query or the budget changes — so a client backing off and retrying, the correct response to a 503,
        // would loop. The message names the ceiling, its configured value and what to narrow, and echoes
        // nothing from the driver, so a caller can tell "this query is too expensive" from "ClickHouse is
        // down" without a log dive. Mirrors pipeline_preview_budget_exceeded above.
        // Logged because the refusal used to arrive here as a ClickHouseQueryException and be logged as one:
        // an operator deciding whether query.budget.* is set too tight needs the refusals to be visible on
        // the server, not only in the callers' responses.
        logRefusal(req, "query_budget", "query_budget_exceeded", ex, "refused by the query budget: {}",
                ex.getMessage());
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "query_budget_exceeded", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(PipelineReadBudgetExceededException.class)
    public ErrorView handlePipelineReadBudget(
            HttpServletRequest req, PipelineReadBudgetExceededException ex) {
        // 422, not 503, for the reason the two budgets above give: the refusal is deterministic, so the
        // runner must dead-letter the item rather than back off and retry it forever. A separate code from
        // query_budget_exceeded because the caller and the remedy differ — the runner cannot "narrow the
        // time range", and an operator reading this code knows to look at the declaration's heavy bindings
        // or at the enrichment's partition alignment, not at a caller's query.
        // Keyed per pipeline as well: the pattern is shared by every pipeline, and one pipeline refused on every
        // call must not hide another's first refusal. The name is bounded, because this refusal comes only
        // after the name resolved as a registered pipeline.
        logRefusal(req, "pipeline_read_budget", "pipeline_read_budget_exceeded", pathPattern(req) + " "
                + Objects.toString(MDC.get(AccessLogFilter.PIPELINE_NAME), NONE), ex,
                "refused by the pipeline read budget: {}", ex.getMessage());
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT,
                "pipeline_read_budget_exceeded", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(PipelineConflictException.class)
    public ErrorView handlePipelineConflict(HttpServletRequest req, PipelineConflictException ex) {
        // INFO, not WARN: a conflict with stored state (a disabled pipeline, a name in use) is the caller's
        // to resolve, but an operator wondering why the runner wrote nothing needs to see it.
        log.info("[{}] Request: {} - pipeline_conflict: {}", req.getMethod(), req.getServletPath(), ex.getMessage());
        return new ErrorView(req, HttpStatus.CONFLICT, "pipeline_conflict", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(RunnerContractException.class)
    public ErrorView handleRunnerContract(HttpServletRequest req, RunnerContractException ex) {
        // One handler, two codes: the reason carries the code so a client acting on it can tell "built
        // before the contract existed" from "on the wrong side of a bump" — different operational
        // situations with the same remedy shape but different urgency. Logged at WARN because a refusal
        // here means a deploy went out of order and the Enrichment Runner is doing no work: the condition
        // must be visible on the server, not only in the response nobody is reading. The message names both
        // versions; the caller says which runner deployment is the skewed one.
        final String code = ex.reason().code();
        logRefusal(req, "runner_contract", code, ex, "refused by the runner contract ({}) for caller '{}': {}",
                code, caller(), ex.getMessage());
        return new ErrorView(req, HttpStatus.CONFLICT, ex.reason().code(), ex.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(SensitiveColumnNotEntitledException.class)
    public ErrorView handleSensitiveColumnNotEntitled(
            HttpServletRequest req, SensitiveColumnNotEntitledException ex) {
        return new ErrorView(req, HttpStatus.FORBIDDEN, "sensitive_column_not_entitled", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidCursorException.class)
    public ErrorView handleInvalidCursor(HttpServletRequest req, InvalidCursorException ex) {
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "invalid_cursor", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(WriteColumnNotAllowedException.class)
    public ErrorView handleWriteColumnNotAllowed(HttpServletRequest req, WriteColumnNotAllowedException ex) {
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "write_column_not_allowed", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(WriteOutputColumnRequiredException.class)
    public ErrorView handleWriteOutputColumnRequired(
            HttpServletRequest req, WriteOutputColumnRequiredException ex) {
        // Deliberately NOT folded into write_column_not_allowed above, though both are 422s about a
        // write's column set: the two remedies are opposite (drop a column there, add one here), and a
        // client acting on the code alone would apply the wrong one half the time.
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT,
                "write_output_column_required", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(PipelineAccessDeniedException.class)
    public ErrorView handlePipelineAccessDenied(HttpServletRequest req, PipelineAccessDeniedException ex) {
        return new ErrorView(req, HttpStatus.FORBIDDEN, "pipeline_access_denied", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(RowPolicyConfinedException.class)
    public ErrorView handleRowPolicyConfined(HttpServletRequest req, RowPolicyConfinedException ex) {
        return new ErrorView(req, HttpStatus.FORBIDDEN, "row_policy_confined", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ColumnInRowPolicyException.class)
    public ErrorView handleColumnInRowPolicy(HttpServletRequest req, ColumnInRowPolicyException ex) {
        return new ErrorView(req, HttpStatus.CONFLICT, "column_in_row_policy", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(SavedQueryNotFoundException.class)
    public ErrorView handleSavedQueryNotFound(HttpServletRequest req, SavedQueryNotFoundException ex) {
        // Deliberately identical for "does not exist" and "not visible to you": a distinct 403 would
        // confirm that another caller has a personal saved query under this id.
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(SavedQueryValidationException.class)
    public ErrorView handleSavedQueryValidation(HttpServletRequest req, SavedQueryValidationException ex) {
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "validation_error", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(UnattributableSavedQueryException.class)
    public ErrorView handleUnattributableSavedQuery(
            HttpServletRequest req, UnattributableSavedQueryException ex) {
        // 5xx, not 4xx: the token authenticated, so the service already accepted it. Refusing to act on
        // it afterwards is our own inconsistency — a principal claim that yields nothing is deployment
        // misconfiguration the caller cannot fix.
        return new ErrorView(req, HttpStatus.INTERNAL_SERVER_ERROR, "principal_unavailable", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ActivityNotFoundException.class)
    public ErrorView handleActivityNotFound(HttpServletRequest req, ActivityNotFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "activity_not_found", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(RevisionNotFoundException.class)
    public ErrorView handleRevisionNotFound(HttpServletRequest req, RevisionNotFoundException ex) {
        // One code for "no such revision" and "the resource did not exist then": both mean there is no
        // point-in-time state to return, which is the only thing the caller can act on.
        return new ErrorView(req, HttpStatus.NOT_FOUND, "revision_not_found", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidFilterColumnException.class)
    public ErrorView handleInvalidFilterColumn(HttpServletRequest req, InvalidFilterColumnException ex) {
        // The message is composed by the thrower and names the offending column plus the allow-list; it
        // is never an underlying exception's text, per this handler's rule.
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "invalid_filter_column", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidFilterValueException.class)
    public ErrorView handleInvalidFilterValue(HttpServletRequest req, InvalidFilterValueException ex) {
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "invalid_filter_value", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ErrorView handleUnsupportedMediaType(HttpServletRequest req, HttpMediaTypeNotSupportedException ex) {
        // Without this mapping a wrong Content-Type fell through to the catch-all and rendered as
        // 500 internal_error on every endpoint — telling a caller their request broke the server rather
        // than that they sent the wrong encoding. The OTLP endpoints rely on it (an exporter configured
        // for application/x-protobuf must be told so), and every other endpoint benefits.
        // The converters' type list is long and generic for a JSON body; only the multipart endpoints name a
        // different one, through their mapping's consumes.
        final String accepted = ex.getSupportedMediaTypes().contains(MediaType.MULTIPART_FORM_DATA)
                ? MediaType.MULTIPART_FORM_DATA_VALUE : MediaType.APPLICATION_JSON_VALUE;
        return new ErrorView(req, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
                "Unsupported Content-Type; this endpoint accepts " + accepted);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(OtelSinkNotFoundException.class)
    public ErrorView handleOtelSinkNotFound(HttpServletRequest req, OtelSinkNotFoundException ex) {
        return new ErrorView(req, HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(OtelDisabledException.class)
    public ErrorView handleOtelDisabled(HttpServletRequest req, OtelDisabledException ex) {
        return new ErrorView(req, HttpStatus.SERVICE_UNAVAILABLE, "otel_disabled", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(CatalogExportInvalidException.class)
    public ErrorView handleCatalogExportInvalid(HttpServletRequest req, CatalogExportInvalidException ex) {
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "catalog_export_invalid", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(CatalogImportInvalidException.class)
    public ErrorView handleCatalogImportInvalid(HttpServletRequest req, CatalogImportInvalidException ex) {
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "catalog_import_invalid", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(CatalogImportConflictException.class)
    public ErrorView handleCatalogImportConflict(HttpServletRequest req, CatalogImportConflictException ex) {
        return new ErrorView(req, HttpStatus.CONFLICT, "catalog_import_conflict", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(CatalogBundleVersionUnsupportedException.class)
    public ErrorView handleCatalogBundleVersionUnsupported(HttpServletRequest req,
            CatalogBundleVersionUnsupportedException ex) {
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "catalog_bundle_version_unsupported", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(ResolutionPolicyUnsupportedException.class)
    public ErrorView handleResolutionPolicyUnsupported(HttpServletRequest req, ResolutionPolicyUnsupportedException ex) {
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, "resolution_policy_unsupported", ex.getMessage());
    }

    /** Written for the caller: it names the offending field, never an input fragment. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(CatalogBundleMalformedException.class)
    public ErrorView handleCatalogBundleMalformed(HttpServletRequest req, CatalogBundleMalformedException ex) {
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(CatalogImportForbiddenException.class)
    public ErrorView handleCatalogImportForbidden(HttpServletRequest req, CatalogImportForbiddenException ex) {
        return new ErrorView(req, HttpStatus.FORBIDDEN, "forbidden", ex.getMessage());
    }

    /**
     * A missing multipart part, or a request that is not multipart at all where one is required. The caller's
     * error, so a 400 rather than the catch-all's 500.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ErrorView handleMultipartBadRequest(HttpServletRequest req, Exception ex) {
        log.debug("[{}] Request: {} - rejected as a bad multipart request", req.getMethod(), req.getServletPath(), ex);
        if (ex instanceof MissingServletRequestPartException missing) {
            return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request",
                    "The multipart request is missing its '" + missing.getRequestPartName() + "' part");
        }
        return new ErrorView(req, HttpStatus.BAD_REQUEST, "bad_request",
                "The request must be multipart/form-data with a 'file' part");
    }

    /** An upload over the configured multipart limits; wins over the {@link MultipartException} handler as the closer match. */
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ErrorView handlePayloadTooLarge(HttpServletRequest req, MaxUploadSizeExceededException ex) {
        return new ErrorView(req, HttpStatus.CONTENT_TOO_LARGE, "payload_too_large",
                "The upload is larger than this ADAS accepts" + (ex.getMaxUploadSize() > 0
                        ? " (" + ex.getMaxUploadSize() + " bytes)" : ""));
    }

    /** The clearance denial keeps its message: it names the missing privilege, and only visible columns. */
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(SensitiveClearanceRequiredException.class)
    public ErrorView handleSensitiveClearanceRequired(HttpServletRequest req, SensitiveClearanceRequiredException ex) {
        return new ErrorView(req, HttpStatus.FORBIDDEN, "forbidden", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(AccessDeniedException.class)
    public ErrorView handleAccessDenied(HttpServletRequest req, AccessDeniedException ex) {
        // Endpoint privilege denials (@RequiresPrivilege) are thrown during controller
        // invocation, past the security filter chain, so RestAccessDeniedHandler never sees them; they
        // surface here. URL-level denials are handled in the filter chain. Both render the same shape.
        return new ErrorView(req, HttpStatus.FORBIDDEN, "forbidden",
            "You do not have permission to perform this operation");
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public ErrorView handleGeneralError(HttpServletRequest req, Exception ex) {
        log.error("[{}] Request: {} raised exception", req.getMethod(), req.getServletPath(), ex);
        return new ErrorView(req, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal server error");
    }

    /**
     * Logs one refusal at WARN without a stack, if {@link RefusalLogLimiter} admits it, and its stack at DEBUG on
     * the SQL logger. {@code format} continues after the method and path prefix; a line after the first appends
     * how many of the same key were suppressed since the previous one.
     */
    private void logRefusal(HttpServletRequest req, String handler, String code, Throwable ex,
            String format, Object... args) {
        logRefusal(req, handler, code, pathPattern(req), ex, format, args);
    }

    /** As above, rate-limited under {@code scope} rather than the bare path pattern. */
    private void logRefusal(HttpServletRequest req, String handler, String code, String scope, Throwable ex,
            String format, Object... args) {
        final long suppressed = refusalLog.admit(handler, code, scope);
        if (suppressed != RefusalLogLimiter.SUPPRESSED) {
            final List<Object> all = new ArrayList<>(List.of(req.getMethod(), req.getServletPath()));
            all.addAll(Arrays.asList(args));
            String line = "[{}] Request: {} - " + format;
            if (suppressed > 0) {
                line += " ({} more suppressed since the last line)";
                all.add(suppressed);
            }
            log.warn(line, all.toArray());
        }
        SqlLog.LOG.debug("[{}] Request: {} - {} refusal stack", req.getMethod(), req.getServletPath(), code, ex);
    }

    /**
     * What a ClickHouse failure was, safe to log: the server's error code when the server answered, otherwise the
     * type of the innermost cause (a refused connection, a timeout). Never a message.
     */
    private static String clickHouseFailure(Throwable ex) {
        Throwable cause = ex;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof ServerException server) {
                return "ClickHouse code " + server.getCode();
            }
            final Throwable next = cause.getCause();
            if (next == null || next == cause) {
                break;
            }
            cause = next;
        }
        return cause.getClass().getSimpleName();
    }

    /** The matched handler mapping, bounded by the build, never the raw caller-chosen path. */
    private static String pathPattern(HttpServletRequest req) {
        final Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? NONE : pattern.toString();
    }

    /**
     * The authenticated principal, as {@link SecurityClaimsExtractor} names it everywhere else; the access
     * log's MDC {@code caller} when no principal is in scope; {@code -} when neither is.
     */
    private String caller() {
        final SecurityClaimsExtractor extractor = claims.getIfAvailable();
        if (extractor != null && extractor.isAuthenticated()) {
            final String author = extractor.getAuthor();
            if (author != null && !author.isBlank()) {
                return author;
            }
        }
        final String mdc = MDC.get(AccessLogFilter.CALLER);
        return mdc == null || mdc.isBlank() ? NONE : mdc;
    }
}
