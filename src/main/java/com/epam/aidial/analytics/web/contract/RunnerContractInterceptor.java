package com.epam.aidial.analytics.web.contract;

import com.epam.aidial.analytics.exception.RunnerContractException;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContactMetrics;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Requires a matching {@link RunnerContract#HEADER} on every {@code POST} handler under
 * {@code /v1/pipelines/{name}} that is not {@link RunnerContractExempt} — that is, on
 * {@code scan}, {@code group-rows}, {@code materialize} and {@code rows}, and not on the two operator
 * surfaces.
 *
 * <p><b>Deny-by-default over one closed class.</b> {@code PipelineDataController} is mapped at
 * {@code /v1/pipelines/{name}} and holds exactly six {@code POST} mappings, so "every POST under the prefix
 * except the two exempt ones" is a statement about one class rather than a path list that has to be kept in
 * step. The one list that <em>does</em> have to be kept in step is {@code OidcSecurityConfiguration}'s,
 * which enumerates the same four paths as {@code authenticated()} — an opt-in enumeration of exactly what
 * this interceptor is deny-by-default about. They stay separate lists deliberately: one is an authorization
 * rule and the other a versioning rule, and collapsing them would make a security decision follow from a
 * build constant. Each names the other; change one and check the other.
 *
 * <p><b>Where it runs.</b> In {@code preHandle}, so the refusal is decided before the pipeline lookup, the
 * per-call grant check and any ClickHouse read — a skewed caller cannot consume a read budget or reach the
 * database. And <em>after</em> the security filter chain, so an unauthenticated caller still gets 401: "who
 * is calling" is settled before "which build is calling". The exception it throws is resolved by
 * {@code DefaultExceptionHandler} like any other, so the refusal renders as an ordinary {@code ErrorView} —
 * carrying the contract echo, because {@link RunnerContractHeaderFilter} has already set it.
 *
 * <p>This interceptor sets no response header. The filter owns that, for the reasons its own note gives.
 *
 * <p><b>The refusal is decided from the request alone</b> — not from the registry, the metrics stack or
 * anything the other service does. {@link RunnerContactMetrics} is looked up through an
 * {@link ObjectProvider} and only counted into, so the guard holds identically in a web-layer slice that
 * boots no meter registry; a refusal never depends on being countable.
 *
 * <p><b>The mapping is a Spring path pattern</b>, unlike the filter's servlet URL pattern, and the leading
 * {@code *} is load-bearing: {@code /v1/pipelines/**} would also match the bare {@code /v1/pipelines},
 * because a Spring {@code /**} matches zero segments as well as many — and would then refuse
 * {@code POST /v1/pipelines}, pipeline <em>registration</em>, which the requirement says explicitly must
 * not need the header. {@code /v1/pipelines/*&#47;**} is the pipeline-scoped prefix: at least one segment
 * for the name, then whatever follows. Everything it matches beyond the six mappings on
 * {@code PipelineDataController} is either not a {@code POST} or not a handler at all, and is passed
 * through above.
 */
@RequiredArgsConstructor
public class RunnerContractInterceptor implements HandlerInterceptor {

    /**
     * The Spring path pattern this interceptor is registered under: the <b>pipeline-scoped</b> prefix, not
     * the collection. See the class note — dropping the {@code *} would refuse pipeline registration.
     */
    public static final String PATH_PATTERN = "/v1/pipelines/*/**";

    private final ObjectProvider<RunnerContactMetrics> metrics;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        if (method.getMethodAnnotation(RunnerContractExempt.class) != null) {
            return true;
        }
        final String declared = StringUtils.trimToNull(request.getHeader(RunnerContract.HEADER));
        if (declared == null) {
            throw refuse(RunnerContractException.required());
        }
        if (!RunnerContract.VERSION_VALUE.equals(declared)) {
            throw refuse(RunnerContractException.mismatch(declared));
        }
        return true;
    }

    private RunnerContractException refuse(RunnerContractException refusal) {
        metrics.ifAvailable(sink -> sink.recordRefusal(refusal.reason()));
        return refusal;
    }
}
