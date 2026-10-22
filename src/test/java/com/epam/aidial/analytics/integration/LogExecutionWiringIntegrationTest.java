package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.clickhouse.ClickHouseInsertSelectExecutor;
import com.epam.aidial.analytics.observability.trace.LogExecution;
import com.epam.aidial.analytics.observability.trace.LogExecutionConfiguration;
import com.epam.aidial.analytics.service.enrichment.EnrichWriteService;
import com.epam.aidial.analytics.service.enrichment.GroupFetchService;
import com.epam.aidial.analytics.service.enrichment.MaterializeService;
import com.epam.aidial.analytics.service.enrichment.ScanService;
import com.epam.aidial.analytics.service.pipeline.AggregateRefreshService;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.web.controller.pipeline.PipelineController;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.aop.support.AopUtils.isAopProxy;

/**
 * Every {@link LogExecution}-annotated component is proxied with the trace advisor in the real application context,
 * and the method-security advisor still applies alongside it. Runs in {@code oidc} mode, the only mode in which
 * {@code privilegeAuthorizationAdvisor} exists, and shares {@link AbstractOidcAuthIntegrationTest}'s cached context.
 */
class LogExecutionWiringIntegrationTest extends AbstractOidcAuthIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void annotatedComponentsAreAopProxiesCarryingTheTraceAdvisor() {
        Advisor traceAdvisor = context.getBean(LogExecutionConfiguration.ADVISOR_BEAN_NAME, Advisor.class);
        List<Class<?>> annotated = List.of(
                MaterializeService.class, ScanService.class, GroupFetchService.class, EnrichWriteService.class,
                AggregateRefreshService.class, PipelineService.class, ClickHouseInsertSelectExecutor.class,
                StructuredQueryExecutor.class);
        assertThat(annotated).allSatisfy(type -> {
            assertThat(type).hasAnnotation(LogExecution.class);
            Object bean = context.getBean(type);
            assertThat(isAopProxy(bean)).as(type.getSimpleName()).isTrue();
            assertThat(((Advised) bean).getAdvisors()).as(type.getSimpleName()).contains(traceAdvisor);
        });
    }

    @Test
    void theMethodSecurityAdvisorStillAppliesAlongsideIt() {
        Advisor privilegeAdvisor = context.getBean("privilegeAuthorizationAdvisor", Advisor.class);
        Object controller = context.getBean(PipelineController.class);
        assertThat(isAopProxy(controller)).isTrue();
        assertThat(((Advised) controller).getAdvisors()).contains(privilegeAdvisor);
    }
}
