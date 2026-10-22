package com.epam.aidial.analytics.web.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Namespacing for the caller's <em>raw</em> provider role names (e.g. {@code analytics-writer}) when they
 * are carried as Spring {@link GrantedAuthority}s alongside the caller's expanded privileges
 * ({@link PrivilegeAuthority}). The two prefixes keep the two vocabularies apart: a privilege check looks
 * only for {@link PrivilegeAuthority#PREFIX}, while per-table grant evaluation reads the raw roles back
 * via {@code SecurityClaimsExtractor.getProviderRoles()} and matches them against a table's grant lists.
 */
public final class ProviderRoleAuthority {

    /** Authority prefix marking a raw provider role name. */
    public static final String PREFIX = "PROVIDER_ROLE_";

    private ProviderRoleAuthority() {
    }

    /** Wraps a raw provider role name as a prefixed authority. */
    public static GrantedAuthority of(String rawRole) {
        return new SimpleGrantedAuthority(PREFIX + rawRole);
    }

    /** Whether an authority name is a prefixed provider role. */
    public static boolean isProviderRole(String authority) {
        return authority != null && authority.startsWith(PREFIX);
    }

    /** Strips the prefix, yielding the raw provider role name. */
    public static String strip(String authority) {
        return authority.substring(PREFIX.length());
    }
}
