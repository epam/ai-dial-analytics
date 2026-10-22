package com.epam.aidial.analytics.web.security;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a bean only in the named {@link SecurityMode}. Replaces the mixture of
 * {@code @ConditionalOnProperty(name = "security.mode", havingValue = "oidc")} (case-insensitive) and
 * {@code @ConditionalOnExpression("'${security.mode:none}' == 'oidc' && ...")} (case-sensitive) that let
 * one surface enforce authentication while another stayed open.
 *
 * <p>Combine with an ordinary {@code @ConditionalOnProperty} for beans that also need a feature flag —
 * conditions are ANDed, so {@code @ConditionalOnSecurityMode(OIDC)} plus
 * {@code @ConditionalOnProperty(name = "flight.enabled", havingValue = "true")} is the Flight guard.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(SecurityModeCondition.class)
public @interface ConditionalOnSecurityMode {

    /** The mode in which the annotated bean exists. */
    SecurityMode value();
}
