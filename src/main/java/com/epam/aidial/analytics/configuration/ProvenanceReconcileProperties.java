package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the startup provenance reconciler
 * ({@code enrichment.provenance-reconcile.*}). Values are env-driven via {@code application.yml}.
 *
 * @param enabled whether the reconciler adds the {@code pipeline_generation} provenance column to the
 *                enrichments an {@code ENRICH} pipeline targets. Default {@code true}, and deliberately
 *                so: provenance columns are injected at table creation only, so every enrichment that
 *                existed before the column was added lacks it and the write silently drops what the
 *                target does not carry — a disabled reconciler is therefore a silently degrading stamp
 *                rather than a neutral setting. It exists for an operator who needs the startup DDL
 *                suppressed, not as a posture to choose.
 */
@Validated
@ConfigurationProperties(prefix = "enrichment.provenance-reconcile")
public record ProvenanceReconcileProperties(boolean enabled) {
}
