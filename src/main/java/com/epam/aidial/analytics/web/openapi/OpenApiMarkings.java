package com.epam.aidial.analytics.web.openapi;

import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Writes the {@link ApiPreview} and {@link ApiInternal} markers into the published operation as the boolean
 * extensions {@code x-preview} and {@code x-internal}. A marker on the method or on its controller counts.
 */
@Component
public class OpenApiMarkings implements GlobalOperationCustomizer {

    public static final String PREVIEW = "x-preview";
    public static final String INTERNAL = "x-internal";

    @Override
    public Operation customize(Operation operation, HandlerMethod handler) {
        if (marked(handler.getMethod(), handler.getBeanType(), ApiPreview.class)) {
            operation.addExtension(PREVIEW, true);
        }
        if (marked(handler.getMethod(), handler.getBeanType(), ApiInternal.class)) {
            operation.addExtension(INTERNAL, true);
        }
        return operation;
    }

    /** Whether the handler method or its controller carries the marker. */
    public static boolean marked(Method handler, Class<?> controller, Class<? extends Annotation> marker) {
        return AnnotatedElementUtils.hasAnnotation(handler, marker) || AnnotatedElementUtils.hasAnnotation(controller, marker);
    }
}
