package com.epam.aidial.analytics.service.enrichment.model;

/**
 * How a pipeline is triggered, and which grain it fires at.
 *
 * <ul>
 *   <li>{@link #SCHEDULE} — row grain: one source row in, one label out, fired on the pipeline's cron.
 *   <li>{@link #GROUP} — group grain: one label per <em>group key</em>, for judgements whose unit is a
 *       set of rows sharing a key rather than a single row.
 * </ul>
 *
 * <p>{@code on_ingest} is retired: it was a second row-grain kind that differed from {@code SCHEDULE} only in
 * being driven by a fixed delay instead of a cron. {@code push} remains unsupported.
 */
public enum TriggerKind {
    SCHEDULE,
    GROUP
}
