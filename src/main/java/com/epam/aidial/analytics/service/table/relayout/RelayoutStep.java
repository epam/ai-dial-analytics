package com.epam.aidial.analytics.service.table.relayout;

/**
 * The completed step of an automatic relayout, in order. A restart resumes at the step after the one recorded.
 */
public enum RelayoutStep {
    /** Started: the aligned copy table is about to be created and filled. */
    STARTED,
    /** The history is copied, slice by slice of the grain keys, up to {@code copy_mark}. */
    COPIED,
    /** The rows written during that copy are copied, up to {@code catch_up_mark}. */
    CAUGHT_UP,
    /** The writers are held and the last rows copied: the tables are about to be exchanged. */
    HELD,
    /** The tables are exchanged: the aligned one serves the name, the old one is under {@code copy_table}. */
    SWAPPED,
    /** The catalog says aligned and the hold is released; the old table is about to be checked and dropped. */
    RELEASED,
    /** Finished: the table is aligned and the old one dropped (or kept, when the counts disagreed). */
    DONE,
    /** Skipped: the database engine cannot exchange tables, or the table cannot be aligned. Re-judged on each start. */
    UNSUPPORTED,
    /**
     * Failed before the swap: the old table is in service, the hold released, and the error recorded. The next start
     * relays the table out again from the beginning.
     */
    FAILED;

    /** Whether the relayout has started and not finished — what {@code layout: migrating} reports. */
    public boolean inProgress() {
        return this != DONE && this != UNSUPPORTED && this != FAILED;
    }
}
