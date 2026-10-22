package com.epam.aidial.analytics.service.pipeline;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Kind of a pipeline: the transform it applies and where that transform runs.
 * {@code AGGREGATE} is an in-DB rollup — a scheduled {@code INSERT … SELECT} whose target is a
 * {@code source} written {@code upsert_by_key}.
 *
 * <p>{@code ENRICH} is the other implemented kind: a transform bound to a target enrichment, driven by
 * the Enrichment Runner over the pipeline-scoped data plane except for its Class-1 ({@code sql}) case,
 * which ADAS materializes in-database itself. It reached this object by the convergence
 * {@code V20__aggregate_pipeline.sql} anticipated, replacing the separate {@code rule} registry.
 *
 * <p>The design's closed set is {@code external | aggregate | enrich}; {@code EXTERNAL} stays declared
 * and unimplemented.
 *
 * <p><b>An unimplemented member is declared, not omitted</b> — the same choice
 * {@link com.epam.aidial.analytics.service.pipeline.model.FreshnessMode#INCREMENTAL} makes. Leaving them
 * out would make {@code kind: "enrich"} a Jackson binding failure, answered with a generic 400 that names
 * neither the offending member nor what is accepted; declaring it lets
 * {@code PipelineService.requireSupportedKind} refuse the request with the 422 the registry's error
 * contract promises, listing the kinds that do work. Only an implemented kind is ever persisted, which is
 * what the registry's {@code kind} CHECK constraint enforces at the other end.
 *
 * <p>Stored by {@link Enum#name()} (uppercase) in PostgreSQL; the wire form is the lowercase
 * {@link #code()}.
 */
public enum PipelineKind {
    AGGREGATE("aggregate", true),
    /** A transform bound to a target enrichment; run by the Enrichment Runner, or in-DB when Class-1. */
    ENRICH("enrich", true),
    /**
     * An out-of-process producer registering what it writes; not implemented, rejected with 422.
     *
     * <p>Declared <b>last deliberately</b>: {@code @JsonValue} makes the published OpenAPI enumerate in
     * declaration order, so this is the order a reader of the spec, a rendered filter form, or a generated
     * client's enum sees. Keeping the implemented kinds together and the unimplemented one at the end is
     * the honest presentation; it had been offered <em>between</em> them, which reads as equally available.
     *
     * <p><b>What this does not fix:</b> a caller that blindly takes the first member still narrows the
     * listing, because {@code aggregate} is first and always was. That is not addressable by ordering —
     * {@code kind} deliberately publishes no default, since an absent {@code kind} means every kind and a
     * default would silently drop rows rather than refuse. A caller that wants everything must omit the
     * parameter, and no ordering can express that.
     */
    EXTERNAL("external", false);

    private final String code;
    /**
     * -- GETTER --
     * Whether this capability can actually run the kind; a declared-but-unimplemented one is a 422.
     */
    @Getter
    private final boolean implemented;

    PipelineKind(String code, boolean implemented) {
        this.code = code;
        this.implemented = implemented;
    }

    /** The wire codes of the kinds that can actually be registered, for an error message to name. */
    public static String implementedCodes() {
        return Arrays.stream(values())
                .filter(PipelineKind::isImplemented)
                .map(PipelineKind::code)
                .collect(Collectors.joining(", "));
    }

    @JsonValue
    public String code() {
        return code;
    }
}
