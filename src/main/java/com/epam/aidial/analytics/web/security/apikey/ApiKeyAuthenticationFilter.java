package com.epam.aidial.analytics.web.security.apikey;

import com.epam.aidial.analytics.web.handler.ErrorView;
import com.epam.aidial.analytics.web.security.CallerAuthorities;
import com.epam.aidial.analytics.web.security.ConditionalOnSecurityMode;
import com.epam.aidial.analytics.web.security.ErrorViewWriter;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.epam.aidial.analytics.web.security.UserSecurityDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authenticates a DIAL {@code Api-Key} presented in the {@code Api-Key} header by introspecting it
 * against DIAL Core (see {@link CoreApiKeyIntrospector}), caching the result, and populating the
 * {@link SecurityContext} with an {@link ApiKeyAuthenticationToken}. Layered before the Bearer filter
 * in {@code oidc} mode: if an {@code Authorization} header is present the JWT/opaque path wins and this
 * filter defers; a blank or absent {@code Api-Key} also passes through unauthenticated.
 *
 * <p>A rejected/malformed key produces {@code 401 unauthorized}; an unreachable Core produces
 * {@code 503 service_unavailable}; both as the standard {@link ErrorView} JSON. A key that
 * authenticates but maps to no privilege is still authenticated, carrying only its raw provider roles
 * (prefixed); the per-endpoint privilege checks then let it reach only what a per-table grant naming one
 * of those roles authorizes, and deny everything else.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnSecurityMode(SecurityMode.OIDC)
@ConditionalOnProperty(name = "security.api-key.enabled", havingValue = "true")
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final CoreApiKeyIntrospector introspector;
    private final ApiKeyCache cache;
    private final ApiKeyAuthorityResolver authorityResolver;
    private final ErrorViewWriter errorViewWriter;
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (StringUtils.isNotBlank(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(CoreApiKeyIntrospector.API_KEY_HEADER);
        if (StringUtils.isBlank(apiKey)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Authentication authentication = cache.getOrAuthenticate(apiKey, () -> authenticate(apiKey));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            chain.doFilter(request, response);
        } catch (BadCredentialsException ex) {
            SecurityContextHolder.clearContext();
            writeError(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid API key");
        } catch (AuthenticationServiceException ex) {
            SecurityContextHolder.clearContext();
            writeError(request, response, HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable",
                    "API key validation is temporarily unavailable");
        }
    }

    private Authentication authenticate(String apiKey) {
        IntrospectionResult result = introspector.introspect(apiKey);
        Set<Privilege> privileges = authorityResolver.resolve(result.rawRoles(), result.fromProjectKey());
        // One shared builder for all four entry points: expanded privileges plus the raw provider roles
        // (prefixed) for per-table grant matching.
        List<GrantedAuthority> authorities = CallerAuthorities.build(privileges, result.rawRoles());
        String project = result.fromProjectKey() ? result.principal() : null;
        Map<String, Object> claims = new HashMap<>(result.claims());
        ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(
                result.principal(), authorities, result.fromProjectKey(), project, claims);
        token.setDetails(new UserSecurityDetails(result.email()));
        if (privileges.isEmpty()) {
            log.debug("Api-key principal '{}' (fromProjectKey={}) maps to no privilege; authenticated with "
                    + "raw provider roles {}", result.principal(), result.fromProjectKey(), result.rawRoles());
        }
        return token;
    }

    private void writeError(HttpServletRequest request,
                            HttpServletResponse response,
                            HttpStatus status,
                            String error,
                            String message) throws IOException {
        ErrorView errorView = new ErrorView(request, status, error, message);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        errorViewWriter.write(response, errorView);
    }
}
