package com.epam.aidial.analytics.configuration;

import com.epam.aidial.analytics.web.security.apikey.CoreApiKeyIntrospector;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * OpenAPI document for the Swagger UI. Declares the two ways a caller may authenticate in
 * {@code oidc} mode so the "Authorize" dialog can carry credentials on try-it-out requests:
 * an {@code Api-Key} header (DIAL API key introspected against Core) and a Bearer JWT/opaque token
 * in the {@code Authorization} header. Both are declared as global, optional requirements — the UI
 * offers each independently, matching the runtime where either header authenticates a request and
 * the Bearer header takes precedence when both are present. The schemes are documentation only; they
 * do not enforce anything, so declaring them unconditionally is safe even in {@code none} mode.
 */
@Configuration
public class OpenApiConfiguration {

    private static final String API_KEY_SCHEME = "ApiKeyAuth";
    private static final String BEARER_SCHEME = "BearerAuth";

    @Bean
    public OpenAPI adasOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Analytics Data Access Service API")
                        .description("Gateway between analytics consumers and the underlying data stores.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(CoreApiKeyIntrospector.API_KEY_HEADER)
                                .description("DIAL API key sent in the " + CoreApiKeyIntrospector.API_KEY_HEADER
                                        + " header (active in oidc mode when security.api-key.enabled=true)."))
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name(HttpHeaders.AUTHORIZATION)
                                .description("OIDC bearer token (JWT or opaque) sent in the Authorization header.")))
                // Two separate requirements express OR: a request may authenticate with either scheme.
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
