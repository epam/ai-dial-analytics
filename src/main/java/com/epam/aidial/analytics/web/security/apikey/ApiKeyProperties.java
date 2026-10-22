package com.epam.aidial.analytics.web.security.apikey;

import com.epam.aidial.analytics.web.security.ConditionalOnSecurityMode;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RolesMappingParser;
import com.epam.aidial.analytics.web.security.SecurityMode;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Typed binding for API-key authentication ({@code security.api-key.*}), active only when
 * {@code security.api-key.enabled=true}. Validation delegates to DIAL Core {@code /v1/user/info}
 * (see {@link CoreApiKeyIntrospector}); Core role names are mapped onto {@link Privilege}s via two
 * separate mappings — {@code roles-mapping} for project-key callers and the OIDC
 * {@code security.defaults.roles-mapping} for JWT-rooted per-request keys, which carry the same role
 * names a JWT caller would.
 *
 * <p>Neither mapping needs to yield a privilege. A deployment whose API keys are authorized entirely by
 * per-table grants is legitimate — that is the BI-tool persona read grants exist for — so a
 * mapping that expands to nothing is no longer a startup failure. An <em>unknown</em> mapping value
 * still is.
 *
 * <p>Property descriptions live in {@code docs/configuration.md}; env-driven via {@code application.yml}.
 */
@Getter
@Setter
@Slf4j
@Component
@ConfigurationProperties(prefix = "security.api-key")
@ConditionalOnSecurityMode(SecurityMode.OIDC)
@ConditionalOnProperty(name = "security.api-key.enabled", havingValue = "true")
public class ApiKeyProperties {

    private final JsonMapper jsonMapper;
    private final String defaultRolesMappingJson;

    private boolean enabled;
    private String coreUrl;
    private int cacheTtlSeconds;
    private int cacheMaxSize;
    private int requestTimeoutMs;
    private String rolesMapping;
    private boolean startupProbe;

    private Map<String, Set<Privilege>> parsedRolesMapping = Collections.emptyMap();
    private Map<String, Set<Privilege>> parsedDefaultRolesMapping = Collections.emptyMap();

    public ApiKeyProperties(JsonMapper jsonMapper,
                            @Value("${security.defaults.roles-mapping:}") String defaultRolesMappingJson) {
        this.jsonMapper = jsonMapper;
        this.defaultRolesMappingJson = defaultRolesMappingJson;
    }

    @PostConstruct
    public void validate() {
        if (!enabled) {
            log.debug("API-key authentication is disabled");
            return;
        }

        if (StringUtils.isBlank(coreUrl)) {
            throw new IllegalStateException(
                    "security.api-key.enabled=true requires security.api-key.core-url to be set");
        }

        parsedRolesMapping = RolesMappingParser.parse(jsonMapper, rolesMapping, "security.api-key.roles-mapping");
        parsedDefaultRolesMapping =
                RolesMappingParser.parse(jsonMapper, defaultRolesMappingJson, "security.defaults.roles-mapping");

        log.info("API-key authentication is enabled. Core URL: {}, cache TTL: {}s, "
                        + "project-key mapped roles: {}, default mapped roles (JWT-root path): {}",
                coreUrl, cacheTtlSeconds, parsedRolesMapping.keySet(), parsedDefaultRolesMapping.keySet());
    }
}
