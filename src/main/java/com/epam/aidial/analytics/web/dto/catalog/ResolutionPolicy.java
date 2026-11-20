package com.epam.aidial.analytics.web.dto.catalog;

/**
 * What an import does with a bundle object that already exists on the target. Deployment manager's
 * values; import never mutates an existing object's definition, columns or pipeline, so {@link #OVERWRITE} is
 * refused. An existing access entry is not a bundle object: an import with {@code include_access} writes the bundle's
 * entries under either policy.
 */
public enum ResolutionPolicy {
    FAIL_IF_EXISTS,
    SKIP_IF_EXISTS,
    OVERWRITE
}
