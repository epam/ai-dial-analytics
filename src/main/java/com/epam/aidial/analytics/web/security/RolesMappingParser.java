package com.epam.aidial.analytics.web.security;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a {@code roles-mapping} property — {@code security.defaults.roles-mapping},
 * {@code providers.<name>.roles-mapping} or {@code security.api-key.roles-mapping} — into the privileges
 * each provider role confers.
 *
 * <p>A mapping value may name a {@link RoleBundle} or a {@link Privilege}, mixed in one list. Every token
 * is resolved here and the result is closed under {@link PrivilegeImplications}, so a bundle is never
 * observable below this point and implication has one source of truth.
 *
 * <p>A token that names neither <b>fails startup</b>, with an error naming the offending value and the
 * property that carried it. Silently dropping it is what let a deployment believe a role was mapped when
 * it was not — the same failure mode as a mis-cased {@code security.mode}.
 */
public final class RolesMappingParser {

    private static final TypeReference<Map<String, List<String>>> RAW_TYPE = new TypeReference<>() {
    };

    private RolesMappingParser() {
    }

    /**
     * Parses {@code json} into role name → conferred privileges. A blank value yields an empty mapping.
     *
     * @param propertyName the property the JSON came from, named in any failure
     */
    public static Map<String, Set<Privilege>> parse(JsonMapper jsonMapper, String json, String propertyName) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        final Map<String, List<String>> raw;
        try {
            raw = jsonMapper.readValue(json, RAW_TYPE);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Invalid " + propertyName + " JSON: " + ex.getMessage(), ex);
        }
        final Map<String, Set<Privilege>> result = new LinkedHashMap<>();
        raw.forEach((role, tokens) -> result.put(role, resolve(tokens, role, propertyName)));
        return Map.copyOf(result);
    }

    /** Resolves one role's mapping values to the closed privilege set they confer. */
    private static Set<Privilege> resolve(List<String> tokens, String role, String propertyName) {
        final Set<Privilege> declared = EnumSet.noneOf(Privilege.class);
        if (tokens == null) {
            return Set.of();
        }
        for (final String token : tokens) {
            declared.addAll(resolveToken(token, role, propertyName));
        }
        return Set.copyOf(PrivilegeImplications.closure(declared));
    }

    /** One mapping value: a bundle name, a privilege name, or a startup failure. */
    private static Set<Privilege> resolveToken(String token, String role, String propertyName) {
        final String name = token == null ? "" : token.trim();
        final RoleBundle bundle = RoleBundle.byName(name);
        if (bundle != null) {
            return bundle.declaredPrivileges();
        }
        for (final Privilege privilege : Privilege.values()) {
            if (privilege.name().equals(name)) {
                return EnumSet.of(privilege);
            }
        }
        throw new IllegalStateException(
                "Invalid " + propertyName + ": role '" + role + "' maps to '" + token
                        + "', which names neither a bundle nor a privilege. Bundles: "
                        + Arrays.toString(RoleBundle.values()) + "; privileges: "
                        + Arrays.toString(Privilege.values()));
    }
}
