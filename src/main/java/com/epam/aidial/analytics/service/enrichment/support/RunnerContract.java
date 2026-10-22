package com.epam.aidial.analytics.service.enrichment.support;

/**
 * The version of the ADAS ↔ Enrichment Runner contract this build speaks, and the header that carries it.
 *
 * <p><b>Only equality is defined.</b> The number is opaque: it is not a semver, not a range, and carries
 * no ordering a reader may act on. A caller declaring anything other than {@link #VERSION} is refused
 * rather than served on a best-effort basis, because the two services deliberately delete a vocabulary
 * instead of deprecating one — there is no "compatible range" to express when the rule is that both halves
 * ship together. Nothing here should grow a partial-ordering policy later; a comparison other than
 * {@code ==} is a design change, not a relaxation.
 *
 * <p><b>A property of the build, not configuration.</b> There is no environment variable for it, for the
 * same reason the runner hard-codes {@code view=compiled}: the only wrong setting disables every pipeline
 * at once, and silently. A skew is then a deploy fact, visible in the image tags, rather than a value
 * somebody can set to a number that happens to match.
 *
 * <p><b>When it must be raised.</b> On any breaking change to the compiled projection
 * ({@code GET /v1/pipelines?kind=enrich&view=compiled}, and the single read of an {@code enrich} pipeline)
 * or to the request or response shape of any of the four runner-only data-plane endpoints —
 * {@code POST /v1/pipelines/{name}/scan}, {@code /group-rows}, {@code /materialize} and {@code /rows}.
 * Renaming {@code next_cursor} is exactly as breaking to the runner as dropping {@code grain_key}, so both
 * halves count. {@code RunnerContractShapeTest} pins those shapes against a golden list and fails the
 * build naming this constant when either moves, so the bump is not left to be remembered.
 */
public final class RunnerContract {

    /**
     * The contract version this build serves, on the wire — and the <b>one place it is written down</b>, so
     * that raising it is a one-character edit rather than two that could disagree. A literal rather than a
     * rendering of {@link #VERSION} because it is used in an annotation, which needs a compile-time
     * constant.
     */
    public static final String VERSION_VALUE = "4";

    /**
     * The same version as a number, for comparisons and messages. Compared for equality and nothing else;
     * see the class note for when it is raised.
     */
    public static final int VERSION = Integer.parseInt(VERSION_VALUE);

    /**
     * The header that carries it, on the response for every {@code /v1/pipelines} call and on the request
     * for the four runner-only data-plane endpoints.
     *
     * <p>Its <b>absence</b> from a response is the signal that matters: an unknown request header is
     * silently ignored by any Spring service, so a client cannot detect an older ADAS by sending
     * something, while an unknown response header is the one signal an older build cannot forge.
     */
    public static final String HEADER = "Adas-Contract-Version";

    private RunnerContract() {
    }
}
