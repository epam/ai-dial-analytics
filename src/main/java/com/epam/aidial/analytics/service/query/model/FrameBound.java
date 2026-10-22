package com.epam.aidial.analytics.service.query.model;

/**
 * One bound of a {@link WindowFrame}: {@code offset} is required exactly for
 * {@link FrameBoundKind#PRECEDING} and {@link FrameBoundKind#FOLLOWING} and must be absent otherwise;
 * enforced during translation.
 */
public record FrameBound(FrameBoundKind kind, Long offset) {}
