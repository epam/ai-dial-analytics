package com.epam.aidial.analytics.web.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one bean every runtime authorization gate asks for the security mode: the HTTP filter chain, method
 * security, the Flight authenticator, API-key authentication, the per-table grant evaluator, the
 * sensitive-column filter, saved-query ownership and rule authorization. Registered in every mode, so a
 * component may depend on it unconditionally.
 *
 * <p>Construction parses the property, which means an invalid {@code security.mode} fails startup even if
 * no {@link ConditionalOnSecurityMode} bean happens to be evaluated first.
 */
@Slf4j
@Component
public class SecurityModeResolver {

    private final SecurityMode mode;

    public SecurityModeResolver(@Value("${" + SecurityMode.PROPERTY + ":}") String rawMode) {
        this.mode = SecurityMode.parse(rawMode);
        log.info("Security mode: {}", mode.code());
    }

    /** The resolved mode. */
    public SecurityMode mode() {
        return mode;
    }

    /**
     * Whether authentication and authorization are enforced. A {@code false} here is what every
     * {@code none}-mode fail-open branch keys off, so those branches cannot disagree about the mode.
     */
    public boolean isEnforced() {
        return mode.isEnforced();
    }
}
