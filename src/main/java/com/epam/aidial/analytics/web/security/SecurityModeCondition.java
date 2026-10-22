package com.epam.aidial.analytics.web.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * Backs {@link ConditionalOnSecurityMode} by resolving the mode through {@link SecurityMode#resolve}, so
 * bean registration and every runtime gate agree on one interpretation of {@code security.mode}. An
 * invalid value throws here, which aborts the context refresh — the mode is not something a partially
 * started service may guess at.
 */
public class SecurityModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        final Map<String, Object> attributes =
                metadata.getAnnotationAttributes(ConditionalOnSecurityMode.class.getName());
        if (attributes == null) {
            return false;
        }
        final SecurityMode required = (SecurityMode) attributes.get("value");
        return SecurityMode.resolve(context.getEnvironment()) == required;
    }
}
