package com.epam.aidial.analytics.sync.mapping;

/**
 * A named, code-defined value transform applied to a coerced source value before it is inserted.
 * The set of transforms is closed (see {@link TransformRegistry}); there is no user-supplied
 * expression language.
 */
@FunctionalInterface
public interface Transform {

    /**
     * Transforms a coerced source value.
     *
     * @param value the value produced by the column's extraction kind (never the raw record)
     * @return the transformed value
     */
    Object apply(Object value);
}
