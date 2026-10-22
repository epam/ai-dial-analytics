package com.epam.aidial.analytics.web.dto.catalog;

/**
 * What an import does with a bundle object that already exists on the target. Deployment manager's
 * values; import never mutates an existing object, so {@link #OVERWRITE} is refused.
 */
public enum ResolutionPolicy {
    FAIL_IF_EXISTS,
    SKIP_IF_EXISTS,
    OVERWRITE
}
