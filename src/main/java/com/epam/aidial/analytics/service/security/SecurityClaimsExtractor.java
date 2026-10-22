package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.PrivilegeAuthority;
import com.epam.aidial.analytics.web.security.ProviderRoleAuthority;
import com.epam.aidial.analytics.web.security.UserSecurityDetails;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the authenticated caller's identity from the security context for use by services (e.g.
 * per-table access evaluation). Returns {@code null}/empty when there is no authentication — which
 * is always the case in {@code none} mode.
 */
@Service
public class SecurityClaimsExtractor {

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    /**
     * Whether the security context holds a <em>real</em> identity — one that names somebody.
     *
     * <p>Spring Security's anonymous filter installs an {@link org.springframework.security.authentication.AnonymousAuthenticationToken}
     * on every unauthenticated request, whose principal name is the literal {@code "anonymousUser"}. That
     * is a placeholder, not a caller: recording it anywhere as an identity would be inventing one. This
     * distinguishes it from a genuine authentication, including the system principal that ADAS's own
     * background work runs under (a real token, so it answers {@code true} here).
     */
    public boolean isAuthenticated() {
        final Authentication authentication = currentAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !TRUST_RESOLVER.isAnonymous(authentication);
    }

    /** The authenticated principal name (the configured principal claim), or {@code null}. */
    public String getAuthor() {
        Authentication authentication = currentAuthentication();
        return authentication != null ? authentication.getName() : null;
    }

    /** The caller's email if present on the authentication details, otherwise {@code null}. */
    public String getEmail() {
        Authentication authentication = currentAuthentication();
        if (authentication != null && authentication.getDetails() instanceof UserSecurityDetails(String email)) {
            return email;
        }
        return null;
    }

    /**
     * The {@link Privilege}s granted to the caller, or an empty set when unauthenticated. This is the
     * global half of authorization: per-table grants are matched against {@link #getProviderRoles()}
     * instead, and effective access is the union of the two.
     */
    public Set<Privilege> getPrivileges() {
        final Set<Privilege> privileges = EnumSet.noneOf(Privilege.class);
        authorities().map(PrivilegeAuthority::parse).filter(Objects::nonNull).forEach(privileges::add);
        return privileges;
    }

    /** Whether the caller holds {@code privilege} globally, over every table. */
    public boolean hasPrivilege(Privilege privilege) {
        return authorities().anyMatch(PrivilegeAuthority.name(privilege)::equals);
    }

    /**
     * The caller's raw provider role names (as extracted from the token/introspection, before application
     * role mapping), or an empty set when unauthenticated. Used for per-table access evaluation.
     */
    public Set<String> getProviderRoles() {
        return authorities()
                .filter(ProviderRoleAuthority::isProviderRole)
                .map(ProviderRoleAuthority::strip)
                .collect(Collectors.toSet());
    }

    private java.util.stream.Stream<String> authorities() {
        Authentication authentication = currentAuthentication();
        if (authentication == null) {
            return java.util.stream.Stream.empty();
        }
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority);
    }

    private static Authentication currentAuthentication() {
        SecurityContext context = SecurityContextHolder.getContext();
        return context != null ? context.getAuthentication() : null;
    }
}
