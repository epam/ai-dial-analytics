package com.epam.aidial.analytics.exception;

/**
 * A pipeline-scoped write-back row named the grain key and provenance columns but none of the pipeline's
 * declared output columns, so it could carry no derived label. Mapped to HTTP 422 with the stable code
 * {@code write_output_column_required}.
 *
 * <p><b>Why this is not a no-op.</b> The target is a {@code ReplacingMergeTree} read at its grain, so such
 * a row does not fail and does not do nothing — it <em>replaces</em> whatever label that grain already
 * holds with nothing, after the model call that produced the old one was made and charged. The existing
 * subset rule cannot catch it: the row names only permitted columns.
 *
 * <p><b>Why its own code rather than {@link WriteColumnNotAllowedException}'s.</b> Both are 422s about a
 * write's column set, which is the argument for one code — but a machine code exists so a client can act
 * on it without reading the prose, and the two remedies are opposite: there, remove the named column; here,
 * add one the declaration names. Publishing a code whose stated remedy is wrong half the time is worse than
 * publishing a second code.
 *
 * <p>Version-independent on purpose. ADAS holds the target and the mapping, so ADAS is where "this write
 * can write no label" is decidable no matter which client version sent it — a remote invariant is not a
 * safe guard for a local, irreversible loss.
 */
public class WriteOutputColumnRequiredException extends RuntimeException {
    public WriteOutputColumnRequiredException(String message) {
        super(message);
    }
}
