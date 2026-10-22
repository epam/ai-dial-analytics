package com.epam.aidial.analytics.configuration.logging;

import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.observability.Elapsed;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gives every servlet request its log context and logs one access line when it completes.
 *
 * <p><b>The context.</b> For the whole request the MDC carries {@value #REQUEST_ID} (a fresh UUID — an
 * inbound header is not used, because it would let a caller choose the value), {@value #CALLER} ({@code -}
 * until {@link CallerLogInterceptor} resolves the principal) and, under {@code /v1/pipelines/{name}},
 * {@value #PIPELINE_NAME} — the {@code {name}} segment, truncated, because it is caller-chosen. The key
 * spelling {@code pipeline_name} is shared with the Enrichment Runner. The previous values are restored in
 * {@code finally}, so a pooled thread never carries one request's context into the next — and so is
 * {@value StatementTag#MDC_KEY}, which each pipeline statement sets during the request (see
 * {@link StatementTag}).
 *
 * <p><b>The line.</b> {@code http {method} {path} pipeline={} status={} ms={} caller={} contract={}}: at
 * INFO under {@code /v1/pipelines} (the collection and everything below it), at DEBUG elsewhere, and not at
 * all for {@code /actuator}. The path is the request URI without its query string, truncated like the
 * pipeline name, since it carries that name and is just as caller-chosen; neither a query string nor a body
 * is ever logged. {@code contract} is the request's declared {@code Adas-Contract-Version}.
 *
 * <p><b>Why a filter, ordered ahead of the security chain.</b> A {@code 401} or {@code 403} is written inside
 * the security filter chain and reaches no handler, so only a filter around that chain sees it. The same
 * reason means the caller cannot be read here: the chain clears the security context on its way out, so
 * {@link CallerLogInterceptor} copies the principal name into a request attribute while it is still set,
 * and this filter reads that attribute in its {@code finally}.
 */
@Slf4j
public class AccessLogFilter extends OncePerRequestFilter {

    /** MDC key for the pipeline's {@code {name}} path segment; spelled as the Enrichment Runner spells it. */
    public static final String PIPELINE_NAME = "pipeline_name";
    /** MDC key for the per-request id. */
    public static final String REQUEST_ID = "request_id";
    /** MDC key for the authenticated principal's name. */
    public static final String CALLER = "caller";
    /** Request attribute {@link CallerLogInterceptor} writes the caller into. */
    public static final String CALLER_ATTRIBUTE = AccessLogFilter.class.getName() + ".caller";

    static final String NONE = "-";
    static final int MAX_PIPELINE_NAME_LENGTH = 128;
    static final int MAX_CONTRACT_LENGTH = 32;
    static final int MAX_PATH_LENGTH = 512;

    private static final String PIPELINES = "/v1/pipelines";
    private static final String PIPELINES_PREFIX = PIPELINES + "/";
    private static final String ACTUATOR = "/actuator";
    /** Every key the filter sets or a statement sets during the request; restored when the request ends. */
    private static final List<String> CONTEXT_KEYS = List.of(PIPELINE_NAME, REQUEST_ID, CALLER, StatementTag.MDC_KEY);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        final String path = path(request);
        return path.equals(ACTUATOR) || path.startsWith(ACTUATOR + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        final String path = path(request);
        final String pipeline = pipelineName(path);
        final Map<String, String> previous = snapshot();
        final long started = System.nanoTime();
        boolean failed = false;
        try {
            MDC.put(REQUEST_ID, UUID.randomUUID().toString());
            MDC.put(CALLER, NONE);
            if (pipeline != null) {
                MDC.put(PIPELINE_NAME, pipeline);
            } else {
                MDC.remove(PIPELINE_NAME);
            }
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            failed = true;
            throw e;
        } finally {
            try {
                logAccess(request, response, path, pipeline, failed, started);
            } finally {
                restore(previous);
            }
        }
    }

    private static void logAccess(HttpServletRequest request, HttpServletResponse response, String path,
                                  String pipeline, boolean failed, long started) {
        final boolean pipelineScoped = isPipelinePath(path);
        if (pipelineScoped ? !log.isInfoEnabled() : !log.isDebugEnabled()) {
            return;
        }
        // An exception escaping the chain has not been rendered yet; the container answers it with a 500.
        final int status = failed && !response.isCommitted() ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
        final long millis = Elapsed.millisSince(started);
        final Object caller = request.getAttribute(CALLER_ATTRIBUTE);
        final String contract = StringUtils.truncate(StringUtils.defaultIfBlank(request.getHeader(RunnerContract.HEADER), NONE),
                MAX_CONTRACT_LENGTH);
        final String format = "http {} {} pipeline={} status={} ms={} caller={} contract={}";
        final Object[] args = {request.getMethod(), StringUtils.truncate(path, MAX_PATH_LENGTH), pipeline != null ? pipeline : NONE, status, millis,
                caller != null ? caller : NONE, contract};
        if (pipelineScoped) {
            log.info(format, args);
        } else {
            log.debug(format, args);
        }
    }

    /** The request URI without the context path; a URI never carries the query string. */
    private static String path(HttpServletRequest request) {
        final String uri = request.getRequestURI();
        final String context = request.getContextPath();
        return StringUtils.isNotEmpty(context) && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private static boolean isPipelinePath(String path) {
        return path.equals(PIPELINES) || path.startsWith(PIPELINES_PREFIX);
    }

    /** The {@code {name}} segment of {@code /v1/pipelines/{name}/...}, truncated, or {@code null}. */
    static String pipelineName(String path) {
        if (!path.startsWith(PIPELINES_PREFIX)) {
            return null;
        }
        final String rest = path.substring(PIPELINES_PREFIX.length());
        final int slash = rest.indexOf('/');
        final String name = slash < 0 ? rest : rest.substring(0, slash);
        return name.isEmpty() ? null : StringUtils.truncate(name, MAX_PIPELINE_NAME_LENGTH);
    }

    private static Map<String, String> snapshot() {
        final Map<String, String> previous = new LinkedHashMap<>();
        for (String key : CONTEXT_KEYS) {
            previous.put(key, MDC.get(key));
        }
        return previous;
    }

    private static void restore(Map<String, String> previous) {
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }
}
