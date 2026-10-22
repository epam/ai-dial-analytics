package com.epam.aidial.analytics.sync.inline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The inline-file rewrite of one body column of one target: classifies the record's API from its request URI, applies
 * the policy to the column's value, and counts what was done. One instance per declaring column, built only when the
 * policy {@link InlineFilesPolicy#rewrites() rewrites}, so with the repair off and no size limit no counter is
 * registered and nothing is parsed. A body the policy cannot change (no longer than the limit, and not one DIAL Core
 * cut while the repair is on) is returned before it is even classified.
 *
 * <p>The counters are attributed to the table and column rather than to the ingest path, like
 * {@code clickhouse.inserts}: every path (reconciliation run, poller, backfill) writes through the same mapping.
 */
public final class InlineFileRewrite {

    static final String FILES_REMOVED = "ingest.inline.files.removed";
    static final String BYTES_REMOVED = "ingest.inline.bytes.removed";
    static final String BODIES_CUT = "ingest.inline.bodies.cut";
    static final String BODIES_UNSCANNED = "ingest.inline.bodies.unscanned";

    private final BodySide side;
    private final InlineFilesPolicy policy;
    private final Counter filesRemoved;
    private final Counter bytesRemoved;
    private final Counter bodiesCut;
    private final Counter bodiesUnscanned;

    /**
     * Registers the four counters for a column.
     *
     * @param side     which side of the call the column holds
     * @param policy   a policy that {@link InlineFilesPolicy#rewrites() rewrites}
     * @param table    the target table, a counter tag
     * @param column   the column, a counter tag
     * @param registry where the counters are registered
     */
    public InlineFileRewrite(BodySide side, InlineFilesPolicy policy, String table, String column, MeterRegistry registry) {
        if (!policy.rewrites()) {
            throw new IllegalArgumentException("a policy that changes nothing needs no rewrite");
        }
        this.side = side;
        this.policy = policy;
        this.filesRemoved = registry.counter(FILES_REMOVED, "table", table, "column", column);
        this.bytesRemoved = registry.counter(BYTES_REMOVED, "table", table, "column", column);
        this.bodiesCut = registry.counter(BODIES_CUT, "table", table, "column", column);
        this.bodiesUnscanned = registry.counter(BODIES_UNSCANNED, "table", table, "column", column);
    }

    /**
     * Rewrites a body value.
     *
     * @param value the column's resolved value; may be {@code null}
     * @param uri   the record's request URI; may be {@code null}
     * @return the value to store: unchanged unless the URI names a file-carrying API and a file was found
     */
    public String apply(String value, String uri) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (!InlineFileStripper.mayChange(value, policy)) {
            return value;
        }
        Api api = ApiClassifier.classify(uri);
        if (api == null) {
            return value;
        }
        InlineFileStripper.Result result = InlineFileStripper.strip(value, api, side, policy);
        if (result.filesRemoved() > 0) {
            filesRemoved.increment(result.filesRemoved());
        }
        if (result.bytesRemoved() > 0) {
            bytesRemoved.increment(result.bytesRemoved());
        }
        if (result.cutBack()) {
            bodiesCut.increment();
        }
        if (result.unscannable() > 0) {
            bodiesUnscanned.increment(result.unscannable());
        }
        return result.body();
    }
}
