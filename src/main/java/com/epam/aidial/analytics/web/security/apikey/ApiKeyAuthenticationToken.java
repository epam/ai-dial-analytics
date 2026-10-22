package com.epam.aidial.analytics.web.security.apikey;

import lombok.Getter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Map;

/**
 * Authentication produced by {@link ApiKeyAuthenticationFilter} for a validated DIAL {@code Api-Key}.
 * The principal is the project name (project-key callers) or the user id resolved from the configured
 * principal claim (JWT-rooted per-request keys). Credentials are never retained.
 *
 * <p>Beyond the principal and authorities, the token retains the identity needed for per-table grant
 * evaluation: {@code projectKey} / {@code project} for a project-key caller, and the raw {@code claims}
 * for a JWT-rooted caller (empty for a project key).
 */
@Getter
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String principal;
    private final boolean projectKey;
    private final String project;
    private final transient Map<String, Object> claims;

    public ApiKeyAuthenticationToken(String principal, Collection<? extends GrantedAuthority> authorities,
                                     boolean projectKey, String project, Map<String, Object> claims) {
        super(authorities);
        this.principal = principal;
        this.projectKey = projectKey;
        this.project = project;
        this.claims = claims == null ? Map.of() : Map.copyOf(claims);
        setAuthenticated(true);
    }

    /** Convenience for callers that carry no grant-evaluation identity (e.g. the Flight header path). */
    public ApiKeyAuthenticationToken(String principal, Collection<? extends GrantedAuthority> authorities) {
        this(principal, authorities, false, null, Map.of());
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal;
    }
}
