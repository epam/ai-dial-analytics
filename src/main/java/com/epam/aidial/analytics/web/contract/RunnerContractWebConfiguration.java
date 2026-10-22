package com.epam.aidial.analytics.web.contract;

import com.epam.aidial.analytics.service.enrichment.support.RunnerContactMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the two halves of the runner contract. They are deliberately different components and are
 * registered differently; each class's own note says why.
 *
 * <ul>
 *   <li>{@link RunnerContractHeaderFilter} sets the echo on every response under the prefix, registered as
 *       a servlet filter at highest precedence so it runs ahead of the security filter chain — a 401, a
 *       URL-level 403 and an unmapped-path 404 must carry the echo too, and none of those reaches an
 *       interceptor.
 *   <li>{@link RunnerContractInterceptor} requires the header on the four runner-only data-plane handlers,
 *       registered as a {@code HandlerInterceptor} because it needs the resolved handler method to read
 *       {@link RunnerContractExempt}, which a filter does not have.
 * </ul>
 *
 * <p><b>The two mappings are in different pattern languages and are not interchangeable</b>, and they are
 * deliberately of different <em>scope</em> too. The filter's {@code /v1/pipelines/*} is a servlet
 * path-prefix mapping, which also covers the bare {@code /v1/pipelines} — the feed, which must echo. The
 * interceptor's {@code /v1/pipelines/*&#47;**} is a Spring path pattern covering only the pipeline-scoped
 * prefix, because registration on the collection must <em>not</em> require the header.
 *
 * <p>Both spellings fail silently if swapped: {@code /**} as a servlet pattern is a literal that matches
 * nothing, leaving no echo anywhere and every caller concluding this build predates the contract; and a
 * Spring {@code /v1/pipelines/**} matches zero trailing segments too, so it would refuse
 * {@code POST /v1/pipelines}.
 */
@Configuration(proxyBeanMethods = false)
public class RunnerContractWebConfiguration implements WebMvcConfigurer {

    private final RunnerContractInterceptor interceptor;

    public RunnerContractWebConfiguration(ObjectProvider<RunnerContactMetrics> metrics) {
        this.interceptor = new RunnerContractInterceptor(metrics);
    }

    @Bean
    public FilterRegistrationBean<RunnerContractHeaderFilter> runnerContractHeaderFilter() {
        final FilterRegistrationBean<RunnerContractHeaderFilter> registration =
                new FilterRegistrationBean<>(new RunnerContractHeaderFilter());
        registration.addUrlPatterns(RunnerContractHeaderFilter.URL_PATTERN);
        // Ahead of the security filter chain (Boot registers that at -100), and ahead of everything else
        // too: the filter sets one response header and always continues, so there is nothing it could do
        // too early.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(RunnerContractInterceptor.PATH_PATTERN);
    }
}
