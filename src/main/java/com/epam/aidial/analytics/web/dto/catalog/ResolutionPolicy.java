package com.epam.aidial.analytics.web.dto.catalog;

/**
 * What an import does with a bundle object that already exists on the target. Deployment manager's values.
 * {@link #OVERWRITE} updates the object where the existing mutation services can do it safely — it adds columns, updates
 * table and column metadata and re-declares pipelines, keeps environment-specific fields, and never drops, renames or
 * retypes anything — and refuses the object otherwise. An existing access entry is not a bundle object: an import with
 * {@code include_access} writes the bundle's entries under every policy.
 */
public enum ResolutionPolicy {
    /** An existing object refuses the import (409). */
    FAIL_IF_EXISTS,
    /** An existing object is kept unchanged; its entry reports what {@link #OVERWRITE} would change. */
    SKIP_IF_EXISTS,
    /** An existing object is updated towards the bundle where that is safe, and refused where it is not. */
    OVERWRITE
}
