package com.epam.aidial.analytics.web.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps the raw provider-role authorities extracted from a token onto the {@link Privilege}s the
 * configured {@code roles-mapping} confers. The mapping is already expanded and closed under implication
 * by {@link RolesMappingParser}, so this is a union of lookups and nothing more.
 *
 * <p>A provider role with no mapping entry contributes nothing, and a caller that maps to no privilege at
 * all is still <b>authenticated</b>: it holds no global privilege but may be authorized for a specific
 * table by a grant naming one of its raw provider roles. That is a legitimate deployment shape — a
 * grant-only machine identity — so it is not logged as a problem. Previously it produced a per-request
 * WARN carrying the whole mapping.
 */
@RequiredArgsConstructor
public class UserRolesResolver {

    private final Map<String, Set<Privilege>> rolesMapping;

    /** The privileges {@code providerAuthorities} confer, as a union over the mapping. */
    public Set<Privilege> resolve(Collection<? extends GrantedAuthority> providerAuthorities) {
        final Set<Privilege> privileges = EnumSet.noneOf(Privilege.class);
        for (final GrantedAuthority authority : providerAuthorities) {
            final Set<Privilege> mapped = rolesMapping.get(authority.getAuthority());
            if (mapped != null) {
                privileges.addAll(mapped);
            }
        }
        return privileges;
    }
}
