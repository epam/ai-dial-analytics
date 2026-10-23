package com.epam.aidial.analytics.service;

import java.util.List;
import java.util.Map;

/**
 * A step a {@link RowBatchSink} runs on each batch right before inserting it, for a rewrite that needs the whole batch
 * rather than one record — the request-delta encoding of the payload table ({@link RequestDeltaStages}). The mapping
 * stays per-record and stateless; anything that must see the batch, or remember what was written, lives here.
 */
@FunctionalInterface
interface FlushStage {

    /** The stage that changes nothing. */
    FlushStage NONE = rows -> () -> { };

    /**
     * Rewrites {@code rows} in place before they are inserted.
     *
     * @param rows the batch about to be inserted
     * @return what to run once the insert carrying the batch has succeeded; never run after a failed insert
     */
    Runnable prepare(List<Map<String, Object>> rows);
}
