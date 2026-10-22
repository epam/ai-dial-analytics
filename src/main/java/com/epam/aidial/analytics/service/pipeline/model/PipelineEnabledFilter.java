package com.epam.aidial.analytics.service.pipeline.model;

/**
 * Which pipelines a registry listing selects by {@code enabled}. {@link #ANY} is the default a caller
 * naming no filter gets — the listing reports the registry as it actually stands, so a pipeline an
 * operator switched off is visible rather than silently absent. A consumer that runs pipelines (the
 * Enrichment Runner) asks for {@link #ENABLED} explicitly.
 *
 * <p>Named {@code PipelineEnabledFilter} rather than {@code PipelineFilter} because
 * {@code service.pipeline.PipelineFilters} already exists and means something else entirely — the
 * bounded SQL membership-predicate helper.
 */
public enum PipelineEnabledFilter {

    ENABLED(Boolean.TRUE),
    DISABLED(Boolean.FALSE),
    /** No filter at all; the repository predicate is disabled by a null rather than widened to both. */
    ANY(null);

    private final Boolean value;

    PipelineEnabledFilter(Boolean value) {
        this.value = value;
    }

    /** The {@code enabled} value this filter selects, or null to not filter on it. */
    public Boolean value() {
        return value;
    }
}
