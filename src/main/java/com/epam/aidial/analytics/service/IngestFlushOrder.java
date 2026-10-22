package com.epam.aidial.analytics.service;

import java.util.Collection;
import java.util.Map;

/**
 * Which ingest target tables are enrichments of which others, for {@link RunSink} to flush enrichments
 * before their source. See the flush-order note on {@link RunSink} for why the order is load-bearing.
 *
 * <p>An interface rather than the catalog repository itself, because the ingest path otherwise knows
 * nothing of the catalog — it validates its mappings against the physical tables — and the unit tests of
 * {@link BlobIngestor} build it with plain mapping files and no database. {@link #NONE} is the answer for
 * those, and for a deployment with no data-sync-written enrichment.
 */
@FunctionalInterface
public interface IngestFlushOrder {

    /** Every target flushes independently — the pre-payload behaviour. */
    IngestFlushOrder NONE = tables -> Map.of();

    /**
     * @param tables the run's target tables
     * @return enrichment table → the source table it enriches, for every pair where both are in
     *     {@code tables}; a table absent from the keys is independent
     */
    Map<String, String> sourceOf(Collection<String> tables);
}
