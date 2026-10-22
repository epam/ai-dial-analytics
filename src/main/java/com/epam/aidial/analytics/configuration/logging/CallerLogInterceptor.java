package com.epam.aidial.analytics.configuration.logging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the authenticated caller for the log context: the principal name (the configured principal
 * claim, or the API key's principal) goes into MDC {@value AccessLogFilter#CALLER} for the rest of the
 * request, and into the request attribute {@link AccessLogFilter#CALLER_ATTRIBUTE}, which
 * {@link AccessLogFilter} reads for its access line.
 *
 * <p>An interceptor rather than the filter itself because only here is the security context still set: the
 * security chain clears it on its way out, before control returns to the filter. The anonymous placeholder
 * ({@code anonymousUser}) is not a caller and stays {@code -}, as {@code SecurityClaimsExtractor} treats it.
 */
public class CallerLogInterceptor implements HandlerInterceptor {

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !TRUST_RESOLVER.isAnonymous(authentication)
                && authentication.getName() != null) {
            final String caller = authentication.getName();
            request.setAttribute(AccessLogFilter.CALLER_ATTRIBUTE, caller);
            MDC.put(AccessLogFilter.CALLER, caller);
        }
        return true;
    }
}
