package com.epam.aidial.analytics.web.contract;

import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Echoes {@link RunnerContract#VERSION} on <b>every</b> response under {@code /v1/pipelines} — a read, a
 * write, a refusal and an error alike, of any method and any status.
 *
 * <p><b>Why a filter and not the interceptor that requires the header.</b> To a client, an <em>absent</em>
 * echo is the definition of "this ADAS predates the contract" — the one signal an older build cannot forge,
 * since an unknown request header is silently ignored by any Spring service. That makes it a signal only if
 * every response this service can produce under the prefix carries it, and a {@link
 * org.springframework.web.servlet.HandlerInterceptor} never sees the ones that reach no handler: a 401 and a
 * URL-level 403 are written inside the security filter chain by {@code RestAuthenticationEntryPoint} /
 * {@code RestAccessDeniedHandler}, and a path under the prefix that resolves to no handler answers 404 with
 * no handler at all. Setting the echo only where a handler runs would make a wrong or expired API key —
 * the most ordinary operational error there is — indistinguishable from a version skew, which is precisely
 * the distinction this header exists to make. {@link RunnerContractInterceptor} owns the request-side
 * requirement and sets no header; this filter owns the echo and refuses nothing.
 *
 * <p>Ordered at highest precedence, so it runs ahead of the security filter chain. It is safe there because
 * it does exactly one thing and always continues the chain — there is no request state it could act on
 * before authentication has decided anything.
 *
 * <p><b>The mapping is a servlet URL pattern, not a Spring path pattern.</b> {@code /v1/pipelines/*} is a
 * path-prefix mapping, which also covers the bare {@code /v1/pipelines} — the feed's own path, and the
 * first call a runner makes after a deploy. {@code /v1/pipelines/**} would be a <em>literal</em> there and
 * match nothing, failing silently: no echo anywhere, and every caller concluding this build predates the
 * contract. See {@link RunnerContractWebConfiguration}, which owns the registration, and note that the
 * interceptor's mapping <em>is</em> a Spring pattern and does use {@code /**}.
 */
public class RunnerContractHeaderFilter extends OncePerRequestFilter {

    /** The servlet path-prefix mapping this filter is registered under; see the class note. */
    public static final String URL_PATTERN = "/v1/pipelines/*";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Set before the chain runs, not after: the response may already be committed by the time control
        // comes back (the security chain writes its own body, and so does an error forward), and a header
        // set on a committed response is dropped without complaint. Setting it up front also means an error
        // forward inherits it — a forward does not reset the response's headers.
        response.setHeader(RunnerContract.HEADER, RunnerContract.VERSION_VALUE);
        chain.doFilter(request, response);
    }

    /**
     * Does not opt out of an error dispatch, which {@code OncePerRequestFilter} does by default.
     *
     * <p>Belt and braces rather than the load-bearing path: this service renders its errors from
     * {@code DefaultExceptionHandler} on the initial dispatch, and a container that does forward to an
     * error page matches filter URL patterns against <em>that</em> path, so a filter mapped under
     * {@code /v1/pipelines} would not be reached on the forward anyway. What actually carries the echo
     * through an error is that the header is set before the chain runs and a forward does not reset the
     * response's headers.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
