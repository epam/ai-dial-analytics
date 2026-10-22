package com.epam.aidial.analytics.web.security.apikey;

import com.epam.aidial.analytics.web.security.ConditionalOnSecurityMode;
import com.epam.aidial.analytics.web.security.SecurityMode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates a DIAL {@code Api-Key} by introspecting it against DIAL Core's {@code /v1/user/info}
 * endpoint and turning the response into an {@link IntrospectionResult}. The body is read as a raw
 * string and parsed with the application {@link JsonMapper} because Core labels its JSON response as
 * {@code application/octet-stream}, which content-negotiated deserialization would reject.
 *
 * <p>A key Core rejects (any 4xx/5xx) or a malformed response becomes a {@link BadCredentialsException}
 * (rendered as 401); a connection failure or timeout becomes an {@link AuthenticationServiceException}
 * (rendered as 503).
 */
@Slf4j
@Component
@ConditionalOnSecurityMode(SecurityMode.OIDC)
@ConditionalOnProperty(name = "security.api-key.enabled", havingValue = "true")
public class CoreApiKeyIntrospector {

    public static final String API_KEY_HEADER = "Api-Key";
    private static final String USER_INFO_PATH = "/v1/user/info";
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() { };

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String userInfoUrl;
    private final String defaultPrincipalClaim;
    private final String defaultEmailClaim;
    private final boolean startupProbe;

    @Autowired
    public CoreApiKeyIntrospector(ApiKeyProperties properties,
                                  JsonMapper jsonMapper,
                                  @Value("${security.defaults.principal-claim:oid}") String defaultPrincipalClaim,
                                  @Value("${security.defaults.email-claim:unique_name}") String defaultEmailClaim) {
        this(buildRestClient(properties.getRequestTimeoutMs()), jsonMapper, properties.getCoreUrl(),
                defaultPrincipalClaim, defaultEmailClaim, properties.isStartupProbe());
    }

    CoreApiKeyIntrospector(RestClient restClient,
                           JsonMapper jsonMapper,
                           String coreUrl,
                           String defaultPrincipalClaim,
                           String defaultEmailClaim,
                           boolean startupProbe) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
        this.userInfoUrl = Strings.CS.removeEnd(coreUrl, "/") + USER_INFO_PATH;
        this.defaultPrincipalClaim = defaultPrincipalClaim;
        this.defaultEmailClaim = defaultEmailClaim;
        this.startupProbe = startupProbe;
    }

    private static RestClient buildRestClient(int requestTimeoutMs) {
        Duration timeout = Duration.ofMillis(requestTimeoutMs);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    @PostConstruct
    public void probeCore() {
        if (!startupProbe) {
            return;
        }
        try {
            fetchUserInfo("adas-startup-probe");
            log.info("DIAL Core /v1/user/info reachable at {}", userInfoUrl);
        } catch (BadCredentialsException ex) {
            // Core reached and answered (rejected the probe key) — that is what we want to confirm.
            log.info("DIAL Core /v1/user/info reachable at {} (rejected probe key)", userInfoUrl);
        } catch (AuthenticationServiceException ex) {
            throw new IllegalStateException(
                    "DIAL Core /v1/user/info is unreachable at " + userInfoUrl
                            + ". Disable the startup probe via API_KEY_STARTUP_PROBE=false to skip this check.",
                    ex);
        }
    }

    public IntrospectionResult introspect(String apiKey) {
        Map<String, Object> response = fetchUserInfo(apiKey);
        List<String> rawRoles = extractRoles(response.get("roles"));

        if (response.get("project") instanceof String project && StringUtils.isNotBlank(project)) {
            return new IntrospectionResult(project, null, rawRoles, true);
        }

        if (response.get("userClaims") instanceof Map<?, ?> userClaimsRaw && !userClaimsRaw.isEmpty()) {
            Map<String, List<String>> userClaims = normalizeUserClaims(userClaimsRaw);
            String principal = firstNonBlank(userClaims.get(defaultPrincipalClaim));
            if (StringUtils.isBlank(principal)) {
                log.warn("Core /v1/user/info userClaims response is missing the configured principal claim '{}'",
                        defaultPrincipalClaim);
                throw new BadCredentialsException("Malformed Core user-info response");
            }
            String email = firstNonBlank(userClaims.get(defaultEmailClaim));
            return new IntrospectionResult(principal, email, rawRoles, false, userClaims);
        }

        log.warn("Core /v1/user/info response contains neither 'project' nor 'userClaims'");
        throw new BadCredentialsException("Malformed Core user-info response");
    }

    private Map<String, Object> fetchUserInfo(String apiKey) {
        String body;
        try {
            body = restClient.get()
                    .uri(userInfoUrl)
                    .header(API_KEY_HEADER, apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            log.debug("Core /v1/user/info rejected API key with status {}", ex.getStatusCode());
            throw new BadCredentialsException("Invalid API key");
        } catch (ResourceAccessException ex) {
            log.warn("Failed to reach Core /v1/user/info at {}", userInfoUrl, ex);
            throw new AuthenticationServiceException("Failed to validate API key with DIAL Core", ex);
        }
        if (StringUtils.isBlank(body)) {
            log.debug("Core /v1/user/info returned an empty body");
            throw new BadCredentialsException("Invalid API key");
        }
        try {
            return jsonMapper.readValue(body, STRING_OBJECT_MAP);
        } catch (JacksonException ex) {
            log.debug("Core /v1/user/info returned an unparseable body");
            throw new BadCredentialsException("Malformed Core user-info response");
        }
    }

    private List<String> extractRoles(Object rolesClaim) {
        if (rolesClaim instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    private static Map<String, List<String>> normalizeUserClaims(Map<?, ?> raw) {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                List<String> strings = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
                result.put(name, strings);
            } else if (value instanceof String s) {
                result.put(name, List.of(s));
            }
        }
        return result;
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }
}
