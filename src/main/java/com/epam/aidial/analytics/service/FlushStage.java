package com.epam.aidial.analytics.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A step a {@link RowBatchSink} runs on each batch right before inserting it, for a rewrite that needs the whole batch
 * rather than one record — the request-delta encoding of the payload table ({@link RequestDeltaStages}). The mapping
 * stays per-record and stateless; anything that must see the batch, or remember what was written, lives here.
 */
@FunctionalInterface
interface FlushStage {

    /** The stage that changes nothing. */
    FlushStage NONE = rows -> Written.NONE;

    /**
     * Rewrites {@code rows} in place before they are inserted.
     *
     * @param rows the batch about to be inserted
     * @return what to run once the batch is stored; never run when its flush failed
     */
    Written prepare(List<Map<String, Object>> rows);

    /** Told once a prepared batch is stored, and which of its rows were not. */
    @FunctionalInterface
    interface Written {

        /** Does nothing. */
        Written NONE = notStored -> { };

        /**
         * Called once the batch is stored. A batch split by blob stores some rows and not others (see
         * {@link RowBatchSink}); a row of {@code notStored} must not be taken as stored.
         *
         * @param notStored the rows of the prepared batch that were not stored, compared by identity; empty when all were
         */
        void written(Set<Map<String, Object>> notStored);

        /**
         * Called before a prepared batch is inserted in parts rather than whole (see {@link RowBatchSink}): rewrites the
         * rows again, where needed, so that whether one part is stored never changes what a row of another part means.
         * Rows of one part land together, as a whole batch does.
         *
         * @param partOf the part each row is inserted with, compared with {@link Object#equals}
         */
        default void isolate(Function<Map<String, Object>, ?> partOf) {
        }
    }
}
