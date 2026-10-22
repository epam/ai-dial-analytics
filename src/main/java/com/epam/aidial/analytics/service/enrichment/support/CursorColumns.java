package com.epam.aidial.analytics.service.enrichment.support;

/**
 * A source's unique total order for the governed incremental scan: {@code version} is the monotonic,
 * assigned-at-ingest column the scan orders and safety-lags by, and {@code identity} is the tiebreaker
 * that makes the tuple unique so keyset paging can neither skip nor repeat a row. Resolved from the
 * source's catalog scan metadata by {@link ScanSourceMetadata}.
 *
 * <p>{@code source} and {@code partitionColumn} are what {@link CursorPredicates} needs to restrict a
 * cursor-bounded read to the partitions its window can reach; {@code partitionColumn} is null for a source
 * that declares no {@code partition_column}, or on which that bound would not be exact (see
 * {@link ScanSourceMetadata}), and such a source gets no partition bound at all.
 */
public record CursorColumns(String version, String identity, String source, String partitionColumn) {

    /** An unpartitioned source's cursor: the reads it bounds carry no partition predicate. */
    public CursorColumns(String version, String identity) {
        this(version, identity, null, null);
    }

    /** Whether a lower cursor bound on this source can also be expressed as a partition bound. */
    public boolean partitioned() {
        return source != null && partitionColumn != null;
    }
}
