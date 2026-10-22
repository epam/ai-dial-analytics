package com.epam.aidial.analytics.web.security.apikey;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.web.security.ErrorViewWriter;
import com.epam.aidial.analytics.web.security.ProviderRoleAuthority;
import com.epam.aidial.analytics.web.security.UserSecurityDetails;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ApiKeyAuthenticationFilter} in isolation (real cache, authority resolver and
 * error writer; mocked Core introspector). Verifies Bearer-header precedence, blank-key pass-through,
 * successful authentication and authority mapping, raw-role retention when no application role maps,
 * the {@code 401}/{@code 503} error bodies, and that a repeated valid key is served from the cache
 * without re-introspecting.
 */
class ApiKeyAuthenticationFilterTest {

    private final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();

    private CoreApiKeyIntrospector introspector;
    private ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        introspector = mock(CoreApiKeyIntrospector.class);

        ApiKeyProperties properties = new ApiKeyProperties(jsonMapper, "");
        properties.setEnabled(true);
        properties.setCoreUrl("https://core.test");
        properties.setCacheTtlSeconds(60);
        properties.setCacheMaxSize(100);
        properties.setRolesMapping("{\"admin\":[\"FULL_ADMIN\"],\"viewer\":[\"READ_ONLY_ADMIN\"]}");
        properties.validate();

        filter = new ApiKeyAuthenticationFilter(
                introspector,
                new ApiKeyCache(properties),
                new ApiKeyAuthorityResolver(properties),
                new ErrorViewWriter(jsonMapper));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/tables");
        request.setServletPath("/v1/tables");
        return request;
    }

    @Test
    void authorizationHeaderTakesPrecedence() throws ServletException, IOException {
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer some-jwt");
        request.addHeader("Api-Key", "k-1");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(introspector, never()).introspect(anyString());
    }

    @Test
    void blankApiKeyPassesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = request();
        request.addHeader("Api-Key", "  ");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(introspector, never()).introspect(anyString());
    }

    @Test
    void validProjectKeyIsAuthenticatedWithMappedAuthority() throws ServletException, IOException {
        given(introspector.introspect("admin-key"))
                .willReturn(new IntrospectionResult("acme", null, List.of("admin"), true));

        MockHttpServletRequest request = request();
        request.addHeader("Api-Key", "admin-key");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isInstanceOf(ApiKeyAuthenticationToken.class);
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("acme");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        java.util.stream.Stream.concat(
                                com.epam.aidial.analytics.web.security.RoleBundle.FULL_ADMIN.privileges().stream()
                                        .map(com.epam.aidial.analytics.web.security.PrivilegeAuthority::name),
                                java.util.stream.Stream.of(ProviderRoleAuthority.PREFIX + "admin"))
                                .toArray(String[]::new));
    }

    @Test
    void jwtRootedKeyCarriesEmailDetails() throws ServletException, IOException {
        given(introspector.introspect("jwt-key"))
                .willReturn(new IntrospectionResult("u1", "u1@test", List.of("admin"), false));

        MockHttpServletRequest request = request();
        request.addHeader("Api-Key", "jwt-key");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // JWT-rooted keys map through the default roles-mapping; here it is empty, so no application
        // role is granted — but the token is still authenticated, carrying the raw provider role
        // (prefixed) so per-table access can match on it.
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(ProviderRoleAuthority.PREFIX + "admin");
        assertThat(authentication.getDetails()).isEqualTo(new UserSecurityDetails("u1@test"));
    }

    @Test
    void unmappedRoleIsAuthenticatedWithRawProviderRoleOnly() throws ServletException, IOException {
        given(introspector.introspect("weird-key"))
                .willReturn(new IntrospectionResult("acme", null, List.of("stranger"), true));

        MockHttpServletRequest request = request();
        request.addHeader("Api-Key", "weird-key");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // No application role maps, but the token is authenticated and carries the raw provider role
        // so it can still be authorized per-table; the URL rules deny everything else.
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(ProviderRoleAuthority.PREFIX + "stranger");
    }

    @Test
    void invalidKeyReturns401ErrorView() throws ServletException, IOException {
        given(introspector.introspect("bad-key")).willThrow(new BadCredentialsException("Invalid API key"));

        MockHttpServletRequest request = request();
        request.addHeader("Api-Key", "bad-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"error\":\"unauthorized\"");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void coreUnavailableReturns503ErrorView() throws ServletException, IOException {
        given(introspector.introspect("k-1"))
                .willThrow(new AuthenticationServiceException("Failed to validate API key with DIAL Core"));

        MockHttpServletRequest request = request();
        request.addHeader("Api-Key", "k-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"error\":\"service_unavailable\"");
    }

    @Test
    void repeatedValidKeyIsServedFromCache() throws ServletException, IOException {
        given(introspector.introspect("admin-key"))
                .willReturn(new IntrospectionResult("acme", null, List.of("admin"), true));

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = request();
            request.addHeader("Api-Key", "admin-key");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        verify(introspector, times(1)).introspect("admin-key");
    }
}
