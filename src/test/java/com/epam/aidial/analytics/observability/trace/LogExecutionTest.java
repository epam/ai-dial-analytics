package com.epam.aidial.analytics.observability.trace;

import com.epam.aidial.analytics.observability.EventCapture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.client.RestClientResponseException;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogExecutionTest {

    private static final String BODY_MARKER = "BODY-MARKER-7f3a";

    private static final List<String> TRACED_LOGGERS = List.of(
            TracedExecutor.class.getName(), MethodTraced.class.getName(), GuardedTraced.class.getName());

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AnnotationAwareAspectJAutoProxyCreator.class, LogExecutionTest::aspectjAutoProxyCreator)
            .withUserConfiguration(LogExecutionConfiguration.class)
            .withBean(TracedExecutor.class)
            .withBean(MethodTraced.class);

    private EventCapture appender;

    @BeforeEach
    void attach() {
        appender = EventCapture.attach();
    }

    @AfterEach
    void detach() {
        appender.detach();
        setLevel(Level.INFO);
        MDC.clear();
    }

    @Test
    void atInfoNothingIsLoggedAndTheMethodRunsOnce() {
        setLevel(Level.INFO);
        runner.run(context -> {
            TracedExecutor executor = context.getBean(TracedExecutor.class);
            executor.run();
            assertThat(target(executor).invocations.get()).isEqualTo(1);
            assertThat(traceLines()).isEmpty();
        });
    }

    @Test
    void atTraceEnterAndExitCarryTheDurationAndTheMdc() {
        setLevel(Level.TRACE);
        runner.run(context -> {
            TracedExecutor executor = context.getBean(TracedExecutor.class);
            MDC.put("pipeline_name", "p1");
            executor.run();
            assertThat(target(executor).invocations.get()).isEqualTo(1);
            List<LogEvent> lines = traceLines();
            assertThat(lines).extracting(e -> e.getMessage().getFormattedMessage())
                    .hasSize(2)
                    .satisfies(m -> assertThat(m.getFirst()).isEqualTo("enter TracedExecutor.run()"))
                    .satisfies(m -> assertThat(m.get(1)).matches("exit TracedExecutor\\.run\\(\\) in \\d+ ms"));
            assertThat(lines).allSatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.TRACE);
                assertThat(e.getLoggerName()).isEqualTo(TracedExecutor.class.getName());
                assertThat(e.getContextData().<String>getValue("pipeline_name")).isEqualTo("p1");
            });
        });
    }

    @Test
    void failingCallIsTracedWithoutItsMessageOrBodyAndTheOriginalExceptionPropagates() {
        setLevel(Level.TRACE);
        runner.run(context -> {
            TracedExecutor executor = context.getBean(TracedExecutor.class);
            assertThatThrownBy(executor::fail).isSameAs(target(executor).failure);
            List<LogEvent> lines = traceLines();
            assertThat(lines).hasSize(2);
            assertThat(lines.get(1).getMessage().getFormattedMessage())
                    .matches("exception TracedExecutor\\.fail\\(\\) in \\d+ ms: RestClientResponseException");
            assertThat(lines).allSatisfy(e -> {
                assertThat(e.getThrown()).isNull();
                assertThat(e.getMessage().getFormattedMessage()).doesNotContain(BODY_MARKER);
            });
        });
    }

    @Test
    void objectMethodsAndNonPublicMethodsAreNotTraced() {
        setLevel(Level.TRACE);
        runner.run(context -> {
            TracedExecutor executor = context.getBean(TracedExecutor.class);
            assertThat(executor.toString()).isEqualTo("traced");
            executor.hashCode();
            executor.equals(executor);
            executor.getClass();
            executor.packagePrivate();
            assertThat(traceLines()).isEmpty();
        });
    }

    @Test
    void methodLevelAnnotationTracesOnlyThatMethod() {
        setLevel(Level.TRACE);
        runner.run(context -> {
            MethodTraced bean = context.getBean(MethodTraced.class);
            bean.plain();
            assertThat(traceLines()).isEmpty();
            bean.traced();
            assertThat(traceLines()).extracting(e -> e.getMessage().getFormattedMessage())
                    .first().isEqualTo("enter MethodTraced.traced()");
        });
    }

    @Test
    void annotatedBeanIsAnAopProxy() {
        runner.run(context -> {
            assertThat(AopUtils.isAopProxy(context.getBean(TracedExecutor.class))).isTrue();
            assertThat(AopUtils.isAopProxy(context.getBean(MethodTraced.class))).isTrue();
        });
    }

    @Test
    void theInfrastructureAdvisorAppliesUnderTheInfrastructureAutoProxyCreator() {
        new ApplicationContextRunner()
                .withBean(InfrastructureAdvisorAutoProxyCreator.class, () -> {
                    InfrastructureAdvisorAutoProxyCreator creator = new InfrastructureAdvisorAutoProxyCreator();
                    creator.setProxyTargetClass(true);
                    return creator;
                })
                .withUserConfiguration(LogExecutionConfiguration.class)
                .withBean(TracedExecutor.class)
                .run(context -> assertThat(AopUtils.isAopProxy(context.getBean(TracedExecutor.class))).isTrue());
    }

    @Test
    void theSwitchOffRemovesTheAdvisor() {
        runner.withPropertyValues("observability.execution-trace.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(Advisor.class);
            assertThat(AopUtils.isAopProxy(context.getBean(TracedExecutor.class))).isFalse();
        });
        runner.run(context -> assertThat(context).hasBean(LogExecutionConfiguration.ADVISOR_BEAN_NAME));
    }

    /** The shape of ADAS's {@code privilegeAuthorizationAdvisor}: a static infrastructure method-security advisor. */
    @Test
    void coexistsWithMethodSecurityAdvisorOnTheSameBean() {
        AtomicInteger authorized = new AtomicInteger();
        setLevel(Level.TRACE);
        runner.withBean("privilegeAuthorizationAdvisor", Advisor.class, () -> {
            AuthorizationManagerBeforeMethodInterceptor interceptor = new AuthorizationManagerBeforeMethodInterceptor(
                    AnnotationMatchingPointcut.forMethodAnnotation(Guarded.class), (authentication, invocation) -> {
                        authorized.incrementAndGet();
                        return new AuthorizationDecision(true);
                    });
            interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder());
            return interceptor;
        }, definition -> definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE)).withBean(GuardedTraced.class).run(context -> {
            GuardedTraced bean = context.getBean(GuardedTraced.class);
            assertThat(((Advised) bean).getAdvisors()).hasSize(2);
            bean.guarded();
            assertThat(authorized).hasValue(1);
            assertThat(AopTestUtils.<GuardedTraced>getTargetObject(bean).invocations).hasValue(1);
            assertThat(traceLines()).extracting(e -> e.getMessage().getFormattedMessage())
                    .hasSize(2)
                    .first().isEqualTo("enter GuardedTraced.guarded()");
        });
    }

    private static AnnotationAwareAspectJAutoProxyCreator aspectjAutoProxyCreator() {
        AnnotationAwareAspectJAutoProxyCreator creator = new AnnotationAwareAspectJAutoProxyCreator();
        creator.setProxyTargetClass(true);
        return creator;
    }

    /** The proxy's own fields are never initialised, so state is read from the target. */
    private static TracedExecutor target(TracedExecutor proxy) {
        return AopTestUtils.getTargetObject(proxy);
    }

    private List<LogEvent> traceLines() {
        return appender.events().stream()
                .filter(e -> TRACED_LOGGERS.contains(e.getLoggerName()))
                .toList();
    }

    private static void setLevel(Level level) {
        TRACED_LOGGERS.forEach(name -> Configurator.setLevel(name, level));
    }

    @LogExecution
    public static class TracedExecutor {

        final AtomicInteger invocations = new AtomicInteger();

        final RestClientResponseException failure = new RestClientResponseException("422 " + BODY_MARKER, HttpStatusCode.valueOf(422),
                "Unprocessable " + BODY_MARKER, HttpHeaders.EMPTY,
                ("{\"error\":\"PIPELINE_INVALID\",\"message\":\"" + BODY_MARKER + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        public void run() {
            invocations.incrementAndGet();
        }

        public void fail() {
            throw failure;
        }

        void packagePrivate() {
            // Not public: never traced.
        }

        @Override
        public String toString() {
            return "traced";
        }
    }

    public static class MethodTraced {

        public void plain() {
            // Not annotated: never traced.
        }

        @LogExecution
        public void traced() {
            // Annotated: traced at TRACE.
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Guarded {
    }

    @LogExecution
    public static class GuardedTraced {

        final AtomicInteger invocations = new AtomicInteger();

        @Guarded
        public void guarded() {
            invocations.incrementAndGet();
        }
    }
}
