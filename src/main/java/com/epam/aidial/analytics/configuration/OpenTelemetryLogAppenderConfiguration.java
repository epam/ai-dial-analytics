package com.epam.aidial.analytics.configuration;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;

/**
 * Routes log4j2 events captured by the {@code <OpenTelemetry>} appender (log4j2.xml) into the
 * OpenTelemetry SDK configured by spring-boot-starter-opentelemetry. The official starter does not
 * install the appender automatically, so this must run once the Spring context has wired the SDK;
 * events emitted before that are buffered by the appender ({@code numLogsCapturedBeforeOtelInstall}).
 */
@Configuration
public class OpenTelemetryLogAppenderConfiguration {

    public OpenTelemetryLogAppenderConfiguration(ObjectProvider<OpenTelemetry> openTelemetry) {
        openTelemetry.ifAvailable(OpenTelemetryAppender::install);
    }
}
