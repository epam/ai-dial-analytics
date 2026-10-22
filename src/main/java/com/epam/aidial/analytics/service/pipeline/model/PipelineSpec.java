package com.epam.aidial.analytics.service.pipeline.model;

/**
 * The transform declaration of a pipeline — the half of a registration the {@code kind} selects.
 *
 * <p>The registry object is kind-independent (name, target, inputs, filter, trigger, enabled, and the
 * runtime state ADAS owns); everything that differs between kinds lives behind this interface. That is
 * The framing read literally: "the kind selects both the transform spec and where it runs."
 *
 * <p>Sealed rather than open because the set of kinds is closed and enumerated by
 * {@link com.epam.aidial.analytics.service.pipeline.PipelineKind}: a new implementation is a new kind,
 * and the compiler should say so at every dispatch site rather than let one fall through a default.
 *
 * <p>This is the <b>service-layer</b> type. It is deliberately not what the entity holds: the persisted
 * column is untyped JSON, read through {@code PipelineSpecCodec}, because Hibernate binds a JSON column
 * through the declared field type and so cannot dispatch on a sibling column. See that class for why
 * that indirection is preferred over an in-JSON discriminator.
 */
public sealed interface PipelineSpec permits AggregateSpec, EnrichSpec {
}
