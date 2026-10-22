package com.epam.aidial.analytics.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for the opaque-token userinfo introspection path: confirms the bearer token is forwarded
 * to the configured endpoint, roles are extracted from the response claims, the provider name is
 * recorded, and a non-2xx userinfo response becomes an {@link OAuth2IntrospectionException}. Uses
 * {@link MockRestServiceServer} so no live endpoint or Docker is needed.
 */
class DefaultOpaqueTokenIntrospectorTest {

    private static final String USERINFO = "https://idp.test/userinfo";

    private DefaultOpaqueTokenIntrospector introspector(RestClient restClient) {
        return introspector(restClient, List.of("roles"));
    }

    private DefaultOpaqueTokenIntrospector introspector(RestClient restClient, List<String> roleClaims) {
        MultiPathGrantedAuthoritiesConverter<ClaimAccessor> authoritiesConverter =
                new MultiPathGrantedAuthoritiesConverter<>();
        authoritiesConverter.setAuthoritiesPaths(roleClaims);
        authoritiesConverter.setAuthorityPrefix("");
        return new DefaultOpaqueTokenIntrospector(restClient, "google", USERINFO, authoritiesConverter, "sub",
                roleClaims, Set.of("email"));
    }

    @Test
    void introspectsUserInfoAndExtractsRolesAndProvider() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USERINFO))
                .andExpect(method(GET))
                .andExpect(header(AUTHORIZATION, "Bearer tok-1"))
                .andRespond(withSuccess("{\"sub\":\"u1\",\"email\":\"u1@test\",\"roles\":[\"viewer\"]}", APPLICATION_JSON));

        OAuth2AuthenticatedPrincipal principal = introspector(builder.build()).introspect("tok-1");

        assertThat(principal.getName()).isEqualTo("u1");
        assertThat((String) principal.getAttribute(DefaultOpaqueTokenIntrospector.IDP_CLAIM)).isEqualTo("google");
        assertThat((String) principal.getAttribute("email")).isEqualTo("u1@test");
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("viewer");
        server.verify();
    }

    @Test
    void customConverterDerivesRolesFromGoogleWorkspaceGroups() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USERINFO))
                .andRespond(withSuccess("{\"sub\":\"u1\",\"email\":\"u1@test\"}", APPLICATION_JSON));
        server.expect(requestTo(containsString("searchDirectGroups")))
                .andExpect(method(GET))
                .andExpect(header(AUTHORIZATION, "Bearer tok-1"))
                .andRespond(withSuccess("""
                        {"memberships":[
                          {"groupKey":{"id":"analytics-admins@test"}},
                          {"groupKey":{"id":"analytics-viewers@test"}},
                          {"noGroupKey":true}
                        ]}""", APPLICATION_JSON));

        OAuth2AuthenticatedPrincipal principal =
                introspector(builder.build(), List.of(OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS))
                        .introspect("tok-1");

        assertThat(principal.getName()).isEqualTo("u1");
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("analytics-admins@test", "analytics-viewers@test");
        server.verify();
    }

    @Test
    void customConverterWithoutEmailClaimIsRejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USERINFO))
                .andRespond(withSuccess("{\"sub\":\"u1\"}", APPLICATION_JSON));

        DefaultOpaqueTokenIntrospector introspector =
                introspector(builder.build(), List.of(OpaqueTokenCustomGrantedAuthoritiesConverters.GOOGLE_WORKSPACE_GROUPS));

        assertThatThrownBy(() -> introspector.introspect("tok-1"))
                .isInstanceOf(OAuth2IntrospectionException.class)
                .hasMessageContaining("Email claim");
        server.verify();
    }

    @Test
    void unknownCustomConverterIsRejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USERINFO))
                .andRespond(withSuccess("{\"sub\":\"u1\",\"email\":\"u1@test\"}", APPLICATION_JSON));

        DefaultOpaqueTokenIntrospector introspector = introspector(builder.build(), List.of("fn:noSuchConverter"));

        assertThatThrownBy(() -> introspector.introspect("tok-1"))
                .isInstanceOf(OAuth2IntrospectionException.class)
                .hasMessageContaining("fn:noSuchConverter");
        server.verify();
    }

    @Test
    void nonSuccessUserInfoIsRejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USERINFO)).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        DefaultOpaqueTokenIntrospector introspector = introspector(builder.build());
        assertThatThrownBy(() -> introspector.introspect("bad-token"))
                .isInstanceOf(OAuth2IntrospectionException.class);
        server.verify();
    }
}
