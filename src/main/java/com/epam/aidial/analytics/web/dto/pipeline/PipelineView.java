package com.epam.aidial.analytics.web.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Which projection of one stored pipeline a read returns.
 *
 * <p>Two consumers with opposite needs are served by one endpoint. The Enrichment Runner needs the
 * resolved, executable form; a human, MCP or the UI wants what was written. Serving one verbose response
 * to both is why a {@code curl} of the registry returns a wall of JSON.
 */
public enum PipelineView {

    /**
     * The authored declaration — what the caller wrote, with derived and resolved values omitted. The
     * <b>default</b>, because every reader that is not the runner wants this one: a human with a
     * {@code curl}, MCP, and the UI.
     *
     * <p>Declared <b>first deliberately</b>: {@code @JsonValue} makes the published OpenAPI enumerate in
     * declaration order, and a spec-driven consumer fills an optional enum from its first member. So this
     * order is not cosmetic — it is where the default reaches a generated client, a rendered filter form,
     * or a {@code <select>} with no empty option. With {@link #COMPILED} first, every such caller sent
     * {@code view=compiled} without ever naming it.
     */
    SOURCE,

    /**
     * The resolved, executable form: the authored declaration plus five derived members — the
     * {@code response_schema} the model is actually held to (the declaration's stored one where it has
     * one, otherwise the schema composed from the target's columns and {@code transform.outputs}), the
     * derived {@code outputs} mapping, the {@code grain_key}, the {@code version_column} and the resolved
     * read source under {@code inputs}.
     *
     * <p>This is what the Enrichment Runner reads, and it asks for it <b>explicitly</b> — it is not the
     * default. It carries the same field names as {@link #SOURCE}; the two differ in what is resolved,
     * never in what anything is called. The one member {@code SOURCE} serves and this projection does not
     * is the authored {@code transform.response_schema}, which is the same fact as the derived top-level
     * one wherever a declaration stores a schema — served on both, it would ship a multi-kilobyte object
     * twice in every entry of a feed the runner polls, and it is the authored surface that needs it, to
     * keep a read-modify-write round trip from dropping a stored schema.
     */
    COMPILED;

    /**
     * The lowercase wire code, which is what {@code ?view=} accepts.
     *
     * <p>{@code @JsonValue} is what makes the published OpenAPI parameter enumerate {@code source} /
     * {@code compiled} rather than the Java constant names, the same way {@code PipelineKind} does. The
     * binder accepts either spelling (it compares case-insensitively), so this is a documentation fix
     * rather than a behaviour one — but the uppercase spelling appeared in no other document, and a
     * generated client built from the spec would have sent a value nothing else names.
     */
    @JsonValue
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
