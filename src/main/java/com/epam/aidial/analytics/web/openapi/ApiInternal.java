package com.epam.aidial.analytics.web.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publishes the handler's operation with {@code x-internal: true}: called by a machine producer (an OpenTelemetry
 * exporter, the Enrichment Runner), not by API clients, so client generators and renderers may skip it. The operation
 * stays in the document and the Swagger UI. On a controller it marks every handler. {@link OpenApiMarkings} turns it
 * into the extension, and {@code OpenApiAnnotationGuardTest} fails the build when a runner data-plane or OTLP ingest
 * handler is unmarked or any other handler is marked. See {@code openspec/specs/openapi-document/spec.md}.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiInternal {
}
