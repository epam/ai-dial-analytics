package com.epam.aidial.analytics.exception;

import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;

/**
 * A runner-only data-plane call did not declare the contract version this build serves. Mapped to HTTP 409
 * with the stable code its {@link Reason} names.
 *
 * <p>409 rather than 422: the request is well formed and the declaration it acts on is valid — what
 * conflicts is the pair of build versions. (426 Upgrade Required describes it more precisely but is unused
 * in this service and in the runner's HTTP client, and 412/428 read as being about resource
 * preconditions.) The runner treats only 5xx and 429 as retryable, so this is permanent to it, which is
 * correct: retrying a version skew cannot succeed.
 */
public class RunnerContractException extends RuntimeException {

    /**
     * Which of the two skews this is. They are separate codes because "a caller that predates the contract"
     * and "a caller on the wrong side of a bump" are different operational situations, and the distinction
     * is free. The {@link #tag()} is also the refusal counter's tag value, so the code a client reads and
     * the series an operator alerts on cannot drift apart.
     */
    public enum Reason {

        /** No header at all — the caller was built before the contract existed. */
        REQUIRED("runner_contract_required", "required"),

        /** A header naming some other version — the caller is on the wrong side of a bump. */
        MISMATCH("runner_contract_mismatch", "mismatch");

        private final String code;
        private final String tag;

        Reason(String code, String tag) {
            this.code = code;
            this.tag = tag;
        }

        /** The stable machine code in the {@code ErrorView}. */
        public String code() {
            return code;
        }

        /** The refusal counter's {@code reason} tag value. */
        public String tag() {
            return tag;
        }
    }

    /** How much of a declared version is worth repeating; a real one is a single digit. */
    private static final int MAX_ECHOED_LENGTH = 32;

    private final Reason reason;

    private RunnerContractException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** No {@code Adas-Contract-Version} was sent. */
    public static RunnerContractException required() {
        return new RunnerContractException(Reason.REQUIRED, "this endpoint is part of the Enrichment "
                + "Runner data plane and requires the '" + RunnerContract.HEADER + "' header; this service "
                + "serves contract version " + RunnerContract.VERSION + ". A caller that does not send it "
                + "predates the contract and must be upgraded to the release shipped alongside this one");
    }

    /**
     * A version was sent and it is not ours. The message names <b>both</b> versions so the reader learns
     * which side is behind without correlating two deployments.
     *
     * <p>The declared value is echoed, which is the one place this refusal repeats caller input back — kept
     * because a mismatch the reader cannot attribute to a version is the failure this whole contract exists
     * to remove, and bounded to {@link #MAX_ECHOED_LENGTH} characters so the size of what is echoed is ours
     * rather than the caller's.
     */
    public static RunnerContractException mismatch(String declared) {
        return new RunnerContractException(Reason.MISMATCH, "contract version mismatch: this service serves "
                + RunnerContract.VERSION + " and the caller declared '" + abbreviate(declared) + "'. Only an "
                + "exact match is served — the two builds must be deployed together");
    }

    private static String abbreviate(String declared) {
        if (declared == null || declared.length() <= MAX_ECHOED_LENGTH) {
            return declared;
        }
        return declared.substring(0, MAX_ECHOED_LENGTH) + "…";
    }
}
