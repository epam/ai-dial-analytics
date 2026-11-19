package com.epam.aidial.analytics.web.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publishes the handler's operation with {@code x-preview: true}: part of the client API, but it may still change.
 * This is the extension DIAL Core uses for endpoints under development. On a controller it marks every handler.
 * {@link OpenApiMarkings} turns it into the extension, and {@code OpenApiAnnotationGuardTest} fails the build when the
 * marked set differs from the {@code /v1/queries} surface. See {@code openspec/specs/openapi-document/spec.md}.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiPreview {
}
