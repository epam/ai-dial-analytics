package com.epam.aidial.analytics.web.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The union of every configured {@code roles-mapping} — {@code security.defaults}, each
 * {@code providers.<name>}, and {@code security.api-key} — as one lookup from a raw provider role name to
 * the privileges it confers globally.
 *
 * <p>This exists for <b>advice, not enforcement</b>. Authorization always reads the caller's own expanded
 * privileges off its authentication, which is per-request and per-provider. Grant management, by
 * contrast, is handed a bare role <em>name</em> and has to answer a question about it in the abstract: does
 * granting {@code analysts} a {@code read} on this table change anything, or does {@code analysts} already
 * hold {@code TABLE_READ} over every table? A union is the right approximation for that — if any
 * configured provider maps the name to the privilege, some caller carrying it holds the privilege
 * globally, which is exactly when the grant is redundant.
 *
 * <p>Registered in every mode; empty when nothing is configured, which makes every grant report as
 * effective. Parsing failures are the same startup failures {@link RolesMappingParser} raises elsewhere,
 * so an unknown mapping value is caught here too rather than silently making the advice wrong.
 *
 * <p><b>Nothing is parsed in {@code none} mode</b>, and that is a compatibility guard rather than an
 * optimisation. The other three parse sites ({@code IdentityProviderUtils}, {@code ApiKeyProperties} and
 * the resolver map) exist only in {@code oidc} mode, so before this bean existed a {@code none}-mode
 * deployment carrying a stale or misspelled {@code roles-mapping} started and ignored it. Parsing here
 * unconditionally would turn that into a refusal to boot over a property with no effect in that mode —
 * a widening from "{@code oidc} only" to every mode that no capability declares. Grants are inert in
 * {@code none} mode in any case, so the advice this bean provides has nothing to be right about.
 *
 * <p>The visible consequence is that {@code PUT /v1/tables/{name}/access} reports no
 * {@code already_global} ineffective grant in {@code none} mode. An operator staging grants on a
 * {@code none}-mode instance before switching to {@code oidc} loses that advice — accepted, because the
 * alternative is refusing to boot over a property the mode ignores.
 */
@Slf4j
@Component
public class ProviderRolePrivileges {

    private final Map<String, Set<Privilege>> byRole;

    public ProviderRolePrivileges(
            JsonMapper jsonMapper,
            IdentityProvidersProperties providers,
            @Value("${security.defaults.roles-mapping:}") String defaultsMapping,
            @Value("${security.api-key.roles-mapping:}") String apiKeyMapping,
            SecurityModeResolver securityMode) {
        if (!securityMode.isEnforced()) {
            this.byRole = Map.of();
            return;
        }
        final Map<String, Set<Privilege>> merged = new HashMap<>();
        absorb(merged, RolesMappingParser.parse(jsonMapper, defaultsMapping, "security.defaults.roles-mapping"));
        absorb(merged, RolesMappingParser.parse(jsonMapper, apiKeyMapping, "security.api-key.roles-mapping"));
        if (providers.providers() != null) {
            providers.providers().forEach((name, config) -> absorb(merged,
                    RolesMappingParser.parse(jsonMapper, config.rolesMapping(), "providers." + name + ".roles-mapping")));
        }
        this.byRole = Map.copyOf(merged);
    }

    /** The privileges {@code providerRole} confers globally under some configured mapping. */
    public Set<Privilege> privilegesOf(String providerRole) {
        return byRole.getOrDefault(providerRole, Set.of());
    }

    /** Whether {@code providerRole} already holds {@code privilege} over every table. */
    public boolean holdsGlobally(String providerRole, Privilege privilege) {
        return privilegesOf(providerRole).contains(privilege);
    }

    private static void absorb(Map<String, Set<Privilege>> into, Map<String, Set<Privilege>> mapping) {
        mapping.forEach((role, privileges) -> into.merge(role, privileges, (a, b) -> {
            // noneOf + addAll, not EnumSet.copyOf: copyOf throws IllegalArgumentException for an empty
            // non-EnumSet collection, which is exactly what a mapping entry with a null token list yields.
            // So the same role present with a null list in two properties would abort startup with an error
            // naming neither of them.
            final Set<Privilege> union = EnumSet.noneOf(Privilege.class);
            union.addAll(a);
            union.addAll(b);
            return union;
        }));
    }
}
