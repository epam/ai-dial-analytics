package com.epam.aidial.analytics.web.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Namespacing for the caller's expanded {@link Privilege}s when they are carried as Spring
 * {@link GrantedAuthority}s. Prefixed so they cannot collide with the caller's raw provider role names,
 * which travel alongside under {@link ProviderRoleAuthority#PREFIX}.
 */
public final class PrivilegeAuthority {

    /** Authority prefix marking a granted application privilege. */
    public static final String PREFIX = "PRIVILEGE_";

    private PrivilegeAuthority() {
    }

    /** Wraps a privilege as a prefixed authority. */
    public static GrantedAuthority of(Privilege privilege) {
        return new SimpleGrantedAuthority(PREFIX + privilege.name());
    }

    /** The authority name a check for {@code privilege} looks for. */
    public static String name(Privilege privilege) {
        return PREFIX + privilege.name();
    }

    /** Whether an authority name is a prefixed privilege. */
    public static boolean isPrivilege(String authority) {
        return authority != null && authority.startsWith(PREFIX);
    }

    /** The privilege an authority names, or {@code null} when it names none. */
    public static Privilege parse(String authority) {
        if (!isPrivilege(authority)) {
            return null;
        }
        final String name = authority.substring(PREFIX.length());
        for (final Privilege privilege : Privilege.values()) {
            if (privilege.name().equals(name)) {
                return privilege;
            }
        }
        return null;
    }
}
