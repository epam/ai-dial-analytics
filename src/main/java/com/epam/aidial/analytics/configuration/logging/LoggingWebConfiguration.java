package com.epam.aidial.analytics.configuration.logging;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the request logging components.
 *
 * <ul>
 *   <li>{@link CorrelationIdInterceptor} at highest precedence, so the {@code traceparent} response header
 *       is set before any other MVC processing.
 *   <li>{@link AccessLogFilter} as a servlet filter on every path, ordered at {@link #ACCESS_LOG_ORDER}:
 *       after {@code RunnerContractHeaderFilter} (highest precedence) and the HTTP observation filter
 *       (highest precedence + 1, so the access line carries the request's trace id), and ahead of the
 *       security filter chain (-100), so a {@code 401} or {@code 403} the chain writes is still logged.
 *   <li>{@link CallerLogInterceptor} on every handler, to hand the authenticated caller to the filter.
 * </ul>
 */
@Configuration
public class LoggingWebConfiguration implements WebMvcConfigurer {

    /** The access-log filter's order; see the class note. */
    public static final int ACCESS_LOG_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    public CorrelationIdInterceptor correlationIdInterceptor() {
        return new CorrelationIdInterceptor();
    }

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilter() {
        final FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(new AccessLogFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(ACCESS_LOG_ORDER);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(correlationIdInterceptor()).order(Ordered.HIGHEST_PRECEDENCE);
        // Ahead of the contract interceptor, so a contract refusal's access line still names its caller.
        registry.addInterceptor(new CallerLogInterceptor()).order(Ordered.HIGHEST_PRECEDENCE + 1);
    }
}
