package com.epam.aidial.analytics.web.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security wiring for {@code none} mode (the default): every request is permitted and no
 * authentication is performed. This explicit permit-all chain overrides Spring Security's
 * locked-down default chain, keeping local development and the existing test suite open. Method
 * security is not enabled here, so {@link RequiresPrivilege} is inert and the privilege advisor is
 * never registered.
 *
 * <p>The mode condition goes through {@link ConditionalOnSecurityMode} rather than a raw
 * {@code @ConditionalOnProperty} — this was the last bean deciding the mode for itself, and while
 * {@code @ConditionalOnProperty} happens to match case-insensitively too, leaving one instance of the
 * shape behind is how the nine-way divergence that {@link SecurityMode} exists to end got started.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnSecurityMode(SecurityMode.NONE)
public class NoneSecurityConfiguration {

    @Bean
    public SecurityFilterChain permitAllSecurityFilterChain(HttpSecurity http) {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
