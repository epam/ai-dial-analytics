package com.epam.aidial.analytics.observability.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring bean type, or a single method of one, for on-demand execution tracing.
 *
 * <p>On a type, every public method not declared by {@link Object} is traced; on a method, that method is. A trace
 * line is written through the target class's own logger and only while that logger is at TRACE, so an operator can
 * turn tracing on for one class at runtime. Lines carry the class, the method and the duration — never arguments,
 * return values, stack traces or response bodies.
 *
 * <p>Tracing sees only calls made through the bean's proxy: a private, final or self-invoked step produces no line.
 * The type is proxied by subclassing, so annotating a final class or a record fails startup.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface LogExecution {
}
