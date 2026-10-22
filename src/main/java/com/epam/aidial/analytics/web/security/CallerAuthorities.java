package com.epam.aidial.analytics.web.security;

import org.springframework.security.core.GrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The one place a caller's granted authorities are assembled, for all four authentication entry points:
 * Bearer JWT, opaque-token introspection, DIAL API key over HTTP, and DIAL API key over the Flight
 * surface. Every caller carries the same two kinds of authority — its expanded {@link Privilege}s under
 * {@link PrivilegeAuthority#PREFIX}, and its <em>raw</em> provider role names under
 * {@link ProviderRoleAuthority#PREFIX}.
 *
 * <p>This exists because the Flight API-key path used to omit the raw provider roles while the three HTTP
 * paths added them, and both share one {@code ApiKeyCache} entry keyed by the credential — so whichever
 * surface introspected a key first decided whether per-table grants could match it at all. A BI tool
 * reaching ADAS over Flight is exactly the caller per-table read grants exist for, so that divergence made
 * them unreliable for their motivating case.
 */
public final class CallerAuthorities {

    private CallerAuthorities() {
    }

    /**
     * The authorities for a caller holding {@code privileges} and carrying {@code rawProviderRoles}.
     * Either may be empty: a valid credential that maps to no privilege is still authenticated, and may
     * still be authorized for a specific table by a grant naming one of its provider roles.
     */
    public static List<GrantedAuthority> build(Set<Privilege> privileges, Collection<String> rawProviderRoles) {
        final List<GrantedAuthority> authorities = new ArrayList<>(privileges.size() + rawProviderRoles.size());
        for (final Privilege privilege : privileges) {
            authorities.add(PrivilegeAuthority.of(privilege));
        }
        for (final String rawRole : rawProviderRoles) {
            authorities.add(ProviderRoleAuthority.of(rawRole));
        }
        return authorities;
    }

    /** The same, for an entry point that already holds its raw roles as authorities. */
    public static List<GrantedAuthority> buildFromAuthorities(
            Set<Privilege> privileges, Collection<? extends GrantedAuthority> rawProviderAuthorities) {
        return build(privileges, rawProviderAuthorities.stream().map(GrantedAuthority::getAuthority).toList());
    }
}
