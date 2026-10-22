package com.epam.aidial.analytics.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test wiring that replaces the production JWKS-backed {@link NimbusJwtDecoderResolver} with one that
 * verifies tokens against the shared HMAC secret from {@link JwtTestTokens}. Imported explicitly by
 * auth slice tests; not component-scanned.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    public NimbusJwtDecoderResolver testNimbusJwtDecoderResolver() {
        return config -> NimbusJwtDecoder.withSecretKey(JwtTestTokens.hmacSecretKey()).build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
