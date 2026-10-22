package com.epam.aidial.analytics.web.flight;

import com.epam.aidial.analytics.web.security.ConditionalOnSecurityMode;
import com.epam.aidial.analytics.web.security.SecurityMode;
import lombok.extern.slf4j.Slf4j;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.RequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Carries the authenticated caller's {@link Authentication} from Flight's per-call authentication into
 * the producer, so the query pipeline can resolve the caller's roles and filter sensitive columns the
 * same way the HTTP path does. The {@link FlightHeaderAuthenticator} gate authenticates and authorizes
 * every RPC but exposes only the peer-identity string to the producer; this middleware re-derives the
 * full {@link Authentication} (roles included) and makes it retrievable via
 * {@link org.apache.arrow.flight.FlightProducer.CallContext#getMiddleware(Key)}.
 *
 * <p>Best-effort by design: authentication failures are swallowed and surface as a {@code null}
 * principal — the header authenticator is the real gate and rejects unauthorized calls before the
 * producer runs, so a {@code null} here only ever means "treat as no roles". Only wired in {@code oidc}
 * mode with Flight enabled; in {@code none} mode no middleware is registered and the producer sees a
 * {@code null} principal (which, with security disabled, means all columns are visible).
 */
@Slf4j
public final class FlightPrincipalMiddleware implements FlightServerMiddleware {

    /** Key used to register and retrieve this middleware on the Flight server / call context. */
    public static final Key<FlightPrincipalMiddleware> KEY = Key.of("adas-principal");

    private final Authentication authentication;

    private FlightPrincipalMiddleware(Authentication authentication) {
        this.authentication = authentication;
    }

    /** The authenticated caller, or {@code null} when unauthenticated / security disabled. */
    public Authentication authentication() {
        return authentication;
    }

    @Override
    public void onBeforeSendingHeaders(CallHeaders outgoingHeaders) {
        // No response headers to add.
    }

    @Override
    public void onCallCompleted(CallStatus status) {
        // Nothing to release.
    }

    @Override
    public void onCallErrored(Throwable err) {
        // Nothing to release.
    }

    /** Creates one {@link FlightPrincipalMiddleware} per call, capturing the caller's authentication. */
    @Component
    @ConditionalOnSecurityMode(SecurityMode.OIDC)
    @ConditionalOnProperty(name = "flight.enabled", havingValue = "true")
    public static class Factory implements FlightServerMiddleware.Factory<FlightPrincipalMiddleware> {

        private final FlightHeaderAuthenticator authenticator;

        public Factory(FlightHeaderAuthenticator authenticator) {
            this.authenticator = authenticator;
        }

        @Override
        public FlightPrincipalMiddleware onCallStarted(CallInfo info, CallHeaders incomingHeaders, RequestContext context) {
            Authentication authentication = null;
            try {
                authentication = authenticator.resolveAuthentication(incomingHeaders);
            } catch (RuntimeException e) {
                // Best-effort: the header authenticator gate rejects unauthorized calls before the
                // producer runs, so recording no principal here is safe (treated as no roles).
                log.debug("Flight principal capture failed (call will be gated by the authenticator): {}", e.getMessage());
            }
            return new FlightPrincipalMiddleware(authentication);
        }
    }
}
