package com.epam.aidial.analytics.web.security.apikey;

import com.epam.aidial.analytics.web.security.ConditionalOnSecurityMode;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.epam.aidial.analytics.web.security.UserRolesResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Maps the raw role names Core returns for an API key onto {@link Privilege}s, choosing the
 * mapping by key shape: project-key responses ({@code project} present) use
 * {@code security.api-key.roles-mapping}; JWT-rooted per-request keys ({@code userClaims} present)
 * use the OIDC {@code security.defaults.roles-mapping}, because those role names are the same ones
 * the user would carry when calling ADAS directly with a JWT.
 */
@Slf4j
@Component
@ConditionalOnSecurityMode(SecurityMode.OIDC)
@ConditionalOnProperty(name = "security.api-key.enabled", havingValue = "true")
public class ApiKeyAuthorityResolver {

    private final UserRolesResolver projectKeyResolver;
    private final UserRolesResolver userClaimsResolver;

    public ApiKeyAuthorityResolver(ApiKeyProperties properties) {
        this.projectKeyResolver = new UserRolesResolver(properties.getParsedRolesMapping());
        this.userClaimsResolver = new UserRolesResolver(properties.getParsedDefaultRolesMapping());
    }

    /** The privileges {@code rawRoles} confer under the mapping the key shape selects. */
    public Set<Privilege> resolve(List<String> rawRoles, boolean fromProjectKey) {
        UserRolesResolver delegate = fromProjectKey ? projectKeyResolver : userClaimsResolver;
        List<GrantedAuthority> granted = rawRoles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                .toList();
        return delegate.resolve(granted);
    }
}
