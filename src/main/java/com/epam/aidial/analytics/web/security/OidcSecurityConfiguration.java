package com.epam.aidial.analytics.web.security;

import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.web.security.IdentityProvidersProperties.ProviderConfig;
import com.epam.aidial.analytics.web.security.apikey.ApiKeyAuthenticationFilter;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.Pointcuts;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Security wiring for {@code oidc} mode: an OAuth2 resource server that authenticates Bearer tokens
 * from several configured providers, and authorizes <b>per endpoint by privilege</b>. Both token shapes
 * are supported — a token that parses as a JWS is verified locally against the matching issuer's JWKS;
 * an opaque token is introspected against each opaque provider's userinfo endpoint. The choice is made
 * per request by an {@link AuthenticationManagerResolver}.
 *
 * <p><b>The URL layer no longer authorizes.</b> It requires authentication on {@code /v1/**} and nothing
 * more; every endpoint names the privilege it needs with {@link RequiresPrivilege}, enforced by
 * {@link PrivilegeAuthorizationManager} through the advisor registered here, and per table where that
 * privilege is table-scoped. The blanket {@code hasAnyAuthority(FULL_ADMIN, READ_ONLY_ADMIN)} rule it
 * replaces was the only gate on roughly twenty-five read endpoints, so no read could be narrowed below
 * "any application role" however grants were set.
 *
 * <p>That inversion makes a <b>missing</b> annotation an open endpoint rather than an admin-only one,
 * which is why two things hold it closed: {@code anyRequest().denyAll()} for a path matching no rule, and
 * {@code EndpointAuthorizationCoverageTest}, which fails the build unless every request mapping under
 * {@code /v1} declares exactly one privilege requirement. Review alone is not enough for fifty-odd
 * endpoints across fifteen controllers where the failure is silent.
 *
 * <p>The OpenAPI document and Swagger UI need an authenticated caller unless
 * {@link SecurityProperties#disableSwaggerAuthorization()} is set, which makes them public alongside the
 * operational paths.
 *
 * <p>Authentication and authorization failures are rendered as the standard {@code ErrorView} JSON by
 * {@link RestAuthenticationEntryPoint} (401) and {@link RestAccessDeniedHandler} (403). Where a 403 would
 * itself disclose that a table exists, the privilege manager answers 404 instead.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnSecurityMode(SecurityMode.OIDC)
public class OidcSecurityConfiguration {

    /** Always public: probes, build info and the metrics scrape. */
    static final String[] OPERATIONAL_PATHS = {
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
    };

    /** The OpenAPI document and the Swagger UI rendered from it. */
    static final String[] API_DOCS_PATHS = {
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/v3/api-docs/**",
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler,
                                                   ObjectProvider<ApiKeyAuthenticationFilter> apiKeyFilter,
                                                   SecurityProperties securityProperties) {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(publicPaths(securityProperties.disableSwaggerAuthorization())).permitAll()
                        // Authentication only. Authorization is per endpoint, by privilege, and per table
                        // where the privilege is table-scoped — so a caller that maps to no privilege at all
                        // is still authenticated and can be authorized for one table by a matching grant.
                        .requestMatchers("/v1/**").authenticated()
                        // Reached only when the rule above did not already permit them. The document maps
                        // every functional endpoint, which an anonymous caller has no use for.
                        .requestMatchers(API_DOCS_PATHS).authenticated()
                        // Fail closed for anything that matched no rule above: with the URL layer no longer
                        // authorizing, an unmatched path must be unreachable rather than merely unrestricted.
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .authenticationManagerResolver(authenticationManagerResolver));
        // API-key auth (when enabled) runs before the Bearer filter so a JWT/opaque Authorization
        // header still takes precedence; the filter defers when that header is present.
        apiKeyFilter.ifAvailable(filter -> http.addFilterBefore(filter, BearerTokenAuthenticationFilter.class));
        return http.build();
    }

    /** The credential-free paths: the operational ones, plus the API description when it is served openly. */
    static String[] publicPaths(boolean apiDocsPublic) {
        return apiDocsPublic
                ? Stream.concat(Arrays.stream(OPERATIONAL_PATHS), Arrays.stream(API_DOCS_PATHS)).toArray(String[]::new)
                : OPERATIONAL_PATHS.clone();
    }

    /**
     * Registers {@link PrivilegeAuthorizationManager} as a method-security advisor over
     * {@link RequiresPrivilege} and {@link AuthenticatedOnly}. Declared here rather than as a plain
     * {@code @Component} so it exists only in {@code oidc} mode, which is what keeps the annotations inert
     * in {@code none} mode exactly as method security was.
     *
     * <p>{@code static} and {@code ROLE_INFRASTRUCTURE} so the infrastructure auto-proxy creator that
     * {@link EnableMethodSecurity} installs picks it up without instantiating this configuration early.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor privilegeAuthorizationAdvisor(ObjectProvider<TableAccessEvaluator> tableAccess) {
        // Method annotations only, matching where the manager and the coverage test look for them. A
        // class-annotation pointcut would proxy a controller whose class-level declaration the manager then
        // ignores — a gate that looks enforcing and is not — so both annotations target METHOD alone.
        final Pointcut pointcut = Pointcuts.union(
                AnnotationMatchingPointcut.forMethodAnnotation(RequiresPrivilege.class),
                AnnotationMatchingPointcut.forMethodAnnotation(AuthenticatedOnly.class));
        final AuthorizationManagerBeforeMethodInterceptor interceptor =
                new AuthorizationManagerBeforeMethodInterceptor(
                        pointcut, new PrivilegeAuthorizationManager(tableAccess.getObject()));
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder());
        return interceptor;
    }

    /**
     * Suppresses Spring Boot's auto-registration of {@link ApiKeyAuthenticationFilter} as a top-level
     * servlet filter: it is wired into the Spring Security chain via {@code addFilterBefore()} above,
     * so registering it again would run it twice, outside the chain.
     */
    @Bean
    @ConditionalOnBean(ApiKeyAuthenticationFilter.class)
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(ApiKeyAuthenticationFilter filter) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ErrorViewWriter errorViewWriter(JsonMapper jsonMapper) {
        return new ErrorViewWriter(jsonMapper);
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ErrorViewWriter errorViewWriter) {
        return new RestAuthenticationEntryPoint(errorViewWriter);
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(ErrorViewWriter errorViewWriter) {
        return new RestAccessDeniedHandler(errorViewWriter);
    }

    @Bean
    public IdentityProviderUtils identityProviderUtils(JsonMapper jsonMapper, SecurityProperties securityProperties,
                                                       IdentityProvidersProperties providersProperties) {
        return new IdentityProviderUtils(jsonMapper, securityProperties, providersProperties);
    }

    @Bean
    public NimbusJwtDecoderResolver nimbusJwtDecoderResolver() {
        return config -> NimbusJwtDecoder.withJwkSetUri(config.jwkSetUri()).build();
    }

    @Bean
    public IssuerToDecoderMapFactory issuerToDecoderMapFactory(IdentityProviderUtils identityProviderUtils,
                                                               NimbusJwtDecoderResolver nimbusJwtDecoderResolver) {
        return new IssuerToDecoderMapFactory(identityProviderUtils, nimbusJwtDecoderResolver);
    }

    @Bean
    public JwtDecoder jwtDecoder(IssuerToDecoderMapFactory issuerToDecoderMapFactory, IdentityProvidersProperties properties) {
        Map<String, JwtDecoder> issuerToDecoder = new HashMap<>();
        for (ProviderConfig config : jwtProviders(properties).values()) {
            issuerToDecoder.putAll(issuerToDecoderMapFactory.createIssuerToDecoderMap(config));
        }
        return new MultiIssuerJwtDecoder(issuerToDecoder);
    }

    @Bean
    public JwtAuthenticationConverterFactory jwtAuthenticationConverterFactory(IdentityProviderUtils identityProviderUtils,
                                                                               IdentityProvidersProperties properties) {
        return new JwtAuthenticationConverterFactory(jwtProviders(properties), identityProviderUtils);
    }

    @Bean
    public JwtAuthenticationProvider jwtAuthenticationProvider(JwtDecoder jwtDecoder,
                                                               JwtAuthenticationConverterFactory converterFactory) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(jwtDecoder);
        provider.setJwtAuthenticationConverter(jwt -> {
            String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;
            JwtAuthenticationConverter converter = issuer != null ? converterFactory.getConverter(issuer) : null;
            if (converter == null) {
                throw new InvalidBearerTokenException("Unrecognized issuer: " + issuer);
            }
            return converter.convert(jwt);
        });
        return provider;
    }

    @Bean
    public OpaqueTokenIntrospector opaqueTokenIntrospector(IdentityProviderUtils identityProviderUtils,
                                                           IdentityProvidersProperties properties) {
        RestClient restClient = RestClient.create();
        List<OpaqueTokenIntrospector> introspectors = new ArrayList<>();
        opaqueProviders(properties).forEach((name, config) -> {
            List<String> roleClaims = identityProviderUtils.getRoleClaims(config).stream().map(String::trim).toList();
            checkCustomAuthoritiesConverter(name, roleClaims);
            MultiPathGrantedAuthoritiesConverter<ClaimAccessor> authoritiesConverter =
                    new MultiPathGrantedAuthoritiesConverter<>();
            authoritiesConverter.setAuthoritiesPaths(roleClaims);
            authoritiesConverter.setAuthorityPrefix("");
            introspectors.add(new DefaultOpaqueTokenIntrospector(
                    restClient, name, config.userInfoEndpoint(), authoritiesConverter,
                    identityProviderUtils.getPrincipalClaim(config), roleClaims,
                    identityProviderUtils.getEmailClaims(config)));
        });
        return new MultiIssuerOpaqueTokenIntrospector(introspectors);
    }

    @Bean
    public OpaqueAuthenticationConverterFactory opaqueAuthenticationConverterFactory(IdentityProviderUtils identityProviderUtils,
                                                                                     IdentityProvidersProperties properties) {
        return new OpaqueAuthenticationConverterFactory(opaqueProviders(properties), identityProviderUtils);
    }

    @Bean
    public OpaqueTokenAuthenticationProvider opaqueTokenAuthenticationProvider(OpaqueTokenIntrospector opaqueTokenIntrospector,
                                                                               OpaqueAuthenticationConverterFactory converterFactory) {
        OpaqueTokenAuthenticationProvider provider = new OpaqueTokenAuthenticationProvider(opaqueTokenIntrospector);
        provider.setAuthenticationConverter((token, principal) -> {
            String providerName = principal.getAttribute(DefaultOpaqueTokenIntrospector.IDP_CLAIM);
            OpaqueAuthenticationConverter converter = providerName != null ? converterFactory.getConverter(providerName) : null;
            if (converter == null) {
                throw new InvalidBearerTokenException("No converter for opaque provider: " + providerName);
            }
            return converter.convert(token, principal);
        });
        return provider;
    }

    @Bean
    public AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver(JwtAuthenticationProvider jwtAuthenticationProvider,
                                                                                           OpaqueTokenAuthenticationProvider opaqueTokenAuthenticationProvider) {
        AuthenticationManager jwtManager = new ProviderManager(jwtAuthenticationProvider);
        AuthenticationManager opaqueManager = new ProviderManager(opaqueTokenAuthenticationProvider);
        BearerTokenResolver tokenResolver = new DefaultBearerTokenResolver();

        return request -> {
            String token = tokenResolver.resolve(request);
            if (token != null) {
                try {
                    SignedJWT.parse(token);
                } catch (ParseException e) {
                    return opaqueManager;
                }
            }
            return jwtManager;
        };
    }

    /**
     * A provider whose {@code role-claims} select a custom converter ({@code fn:...}) must name one that
     * exists — otherwise every token it introspects would be rejected at request time. Fail at startup
     * instead, where the misconfiguration is visible.
     */
    private static void checkCustomAuthoritiesConverter(String providerName, List<String> roleClaims) {
        if (OpaqueTokenCustomGrantedAuthoritiesConverters.isCustomConverterSelection(roleClaims)
                && OpaqueTokenCustomGrantedAuthoritiesConverters.converterFor(roleClaims.getFirst()) == null) {
            throw new IllegalStateException("Provider '" + providerName + "' selects unknown custom role converter '"
                    + roleClaims.getFirst() + "'; known converters: "
                    + OpaqueTokenCustomGrantedAuthoritiesConverters.CONVERTERS.keySet());
        }
    }

    private static Map<String, ProviderConfig> jwtProviders(IdentityProvidersProperties properties) {
        Map<String, ProviderConfig> result = new HashMap<>();
        if (properties.providers() != null) {
            properties.providers().forEach((name, config) -> {
                if (config.hasJwkSetUri() && StringUtils.isNotBlank(config.issuer())) {
                    result.put(name, config);
                }
            });
        }
        return result;
    }

    private static Map<String, ProviderConfig> opaqueProviders(IdentityProvidersProperties properties) {
        Map<String, ProviderConfig> result = new HashMap<>();
        if (properties.providers() != null) {
            properties.providers().forEach((name, config) -> {
                if (config.hasUserInfoEndpoint()) {
                    result.put(name, config);
                }
            });
        }
        return result;
    }
}
