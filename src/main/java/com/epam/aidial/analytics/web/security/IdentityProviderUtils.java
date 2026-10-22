package com.epam.aidial.analytics.web.security;

import com.epam.aidial.analytics.web.security.IdentityProvidersProperties.ProviderConfig;
import com.epam.aidial.analytics.web.security.SecurityProperties.Defaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves per-provider authentication settings from {@link IdentityProvidersProperties}, applying the
 * service-wide {@link SecurityProperties} defaults where a provider omits its own values. Created only
 * in {@code oidc} mode by {@link OidcSecurityConfiguration}; the constructor fails fast when no usable
 * provider is configured.
 */
@Slf4j
public class IdentityProviderUtils {

    private static final String DEFAULTS_PROPERTY = "security.defaults.roles-mapping";
    private static final String V1_ISSUER_FORMAT = "https://%s/%s/";
    private static final String V2_ISSUER_FORMAT = "https://%s/%s/v2.0";

    private final Map<String, Set<Privilege>> defaultRolesMapping;
    private final String defaultEmailClaim;
    private final String defaultPrincipalClaim;
    private final boolean requireEmail;
    private final JsonMapper jsonMapper;

    public IdentityProviderUtils(JsonMapper jsonMapper, SecurityProperties securityProperties, IdentityProvidersProperties providersProperties) {
        this.jsonMapper = jsonMapper;
        this.requireEmail = securityProperties.requireEmail();

        Defaults defaults = securityProperties.defaults();
        this.defaultEmailClaim = defaults != null ? defaults.emailClaim() : null;
        this.defaultPrincipalClaim = defaults != null ? defaults.principalClaim() : null;
        this.defaultRolesMapping =
                RolesMappingParser.parse(jsonMapper, defaults != null ? defaults.rolesMapping() : null, DEFAULTS_PROPERTY);

        boolean anyUsable = providersProperties.providers() != null && providersProperties.providers().values().stream()
                .anyMatch(provider -> provider.hasJwkSetUri() || provider.hasUserInfoEndpoint());
        if (!anyUsable) {
            throw new IllegalStateException(
                    "Security mode is 'oidc' but no usable identity providers are configured "
                            + "(each provider needs a jwk-set-uri or a user-info-endpoint)");
        }
    }

    /**
     * Expands the accepted issuer values for a provider. A provider with a fully-qualified issuer URL
     * accepts exactly that issuer; a provider configured with a tenant id plus {@code aliases} accepts
     * the Azure v1 and v2 issuer URL forms for each alias.
     */
    public Set<String> getAcceptedIssuers(ProviderConfig config) {
        Set<String> acceptedIssuers = new HashSet<>();
        String issuer = config.issuer();
        if (isValidUrlWithProtocol(issuer)) {
            acceptedIssuers.add(issuer);
        } else if (config.aliases() != null && !config.aliases().isEmpty()) {
            for (String alias : config.aliases()) {
                acceptedIssuers.add(String.format(V1_ISSUER_FORMAT, alias, issuer));
                acceptedIssuers.add(String.format(V2_ISSUER_FORMAT, alias, issuer));
            }
        }
        return acceptedIssuers;
    }

    /** Merges the service-wide default roles mapping with the provider's own (provider entries win). */
    public Map<String, Set<Privilege>> getRolesMapping(ProviderConfig config) {
        Map<String, Set<Privilege>> providerMapping =
                RolesMappingParser.parse(jsonMapper, config.rolesMapping(), "providers.<name>.roles-mapping");
        if (providerMapping.isEmpty()) {
            return defaultRolesMapping;
        }
        Map<String, Set<Privilege>> merged = new HashMap<>(defaultRolesMapping);
        merged.putAll(providerMapping);
        return merged;
    }

    /** The claim names to read the caller's email from: the provider's, else the default if any. */
    public Set<String> getEmailClaims(ProviderConfig config) {
        Set<String> result = new LinkedHashSet<>();
        if (config.emailClaims() != null && !config.emailClaims().isEmpty()) {
            result.addAll(config.emailClaims());
        } else if (StringUtils.isNotBlank(defaultEmailClaim)) {
            result.add(defaultEmailClaim);
        }
        return result;
    }

    /** The dotted claim paths to read provider role names from. */
    public List<String> getRoleClaims(ProviderConfig config) {
        return config.roleClaims() != null ? config.roleClaims() : List.of();
    }

    /** The claim used as the authenticated principal name: the provider's, else the default. */
    public String getPrincipalClaim(ProviderConfig config) {
        String principalClaim = config.principalClaim();
        return StringUtils.isNotBlank(principalClaim) ? principalClaim : defaultPrincipalClaim;
    }

    public boolean isEmailRequired() {
        return requireEmail;
    }

    private static boolean isValidUrlWithProtocol(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        try {
            return new URI(value).getScheme() != null;
        } catch (URISyntaxException e) {
            log.debug("Invalid issuer url format: {}", value, e);
            return false;
        }
    }
}
