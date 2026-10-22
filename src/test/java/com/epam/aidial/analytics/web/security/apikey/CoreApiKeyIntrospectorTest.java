package com.epam.aidial.analytics.web.security.apikey;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for DIAL Core API-key introspection: confirms the key is forwarded in the {@code Api-Key}
 * header, the two Core response shapes (project-key vs JWT-rooted {@code userClaims}) are parsed, a
 * body labelled {@code application/octet-stream} is still read, and Core rejection vs unreachability
 * map to {@link BadCredentialsException} / {@link AuthenticationServiceException}. Uses
 * {@link MockRestServiceServer} so no live Core or Docker is needed.
 */
class CoreApiKeyIntrospectorTest {

    private static final String CORE_URL = "https://core.test";
    private static final String USER_INFO = CORE_URL + "/v1/user/info";

    private final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();

    private CoreApiKeyIntrospector introspector(RestClient restClient) {
        return new CoreApiKeyIntrospector(restClient, jsonMapper, CORE_URL, "oid", "unique_name", false);
    }

    @Test
    void parsesProjectKeyResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USER_INFO))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Api-Key", "k-1"))
                .andRespond(withSuccess("{\"project\":\"acme\",\"roles\":[\"admin\"]}", APPLICATION_JSON));

        IntrospectionResult result = introspector(builder.build()).introspect("k-1");

        assertThat(result.fromProjectKey()).isTrue();
        assertThat(result.principal()).isEqualTo("acme");
        assertThat(result.email()).isNull();
        assertThat(result.rawRoles()).containsExactly("admin");
        server.verify();
    }

    @Test
    void parsesProjectKeyResponseLabelledOctetStream() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USER_INFO))
                .andRespond(withSuccess("{\"project\":\"acme\",\"roles\":[\"admin\"]}", APPLICATION_OCTET_STREAM));

        IntrospectionResult result = introspector(builder.build()).introspect("k-1");

        assertThat(result.fromProjectKey()).isTrue();
        assertThat(result.principal()).isEqualTo("acme");
        server.verify();
    }

    @Test
    void parsesUserClaimsResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USER_INFO))
                .andRespond(withSuccess(
                        "{\"userClaims\":{\"oid\":\"u1\",\"unique_name\":\"u1@test\"},\"roles\":[\"admin\"]}",
                        APPLICATION_JSON));

        IntrospectionResult result = introspector(builder.build()).introspect("k-1");

        assertThat(result.fromProjectKey()).isFalse();
        assertThat(result.principal()).isEqualTo("u1");
        assertThat(result.email()).isEqualTo("u1@test");
        assertThat(result.rawRoles()).containsExactly("admin");
        server.verify();
    }

    @Test
    void missingPrincipalClaimIsRejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USER_INFO))
                .andRespond(withSuccess("{\"userClaims\":{\"foo\":\"bar\"}}", APPLICATION_JSON));

        CoreApiKeyIntrospector introspector = introspector(builder.build());
        assertThatThrownBy(() -> introspector.introspect("k-1"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void responseWithNeitherProjectNorUserClaimsIsRejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USER_INFO))
                .andRespond(withSuccess("{\"roles\":[\"admin\"]}", APPLICATION_JSON));

        CoreApiKeyIntrospector introspector = introspector(builder.build());
        assertThatThrownBy(() -> introspector.introspect("k-1"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void coreRejectionMapsToBadCredentials() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USER_INFO)).andRespond(withStatus(UNAUTHORIZED));

        CoreApiKeyIntrospector introspector = introspector(builder.build());
        assertThatThrownBy(() -> introspector.introspect("bad"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void coreConnectionFailureMapsToServiceException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USER_INFO)).andRespond(request -> {
            throw new IOException("core down");
        });

        CoreApiKeyIntrospector introspector = introspector(builder.build());
        assertThatThrownBy(() -> introspector.introspect("k-1"))
                .isInstanceOf(AuthenticationServiceException.class);
    }
}
