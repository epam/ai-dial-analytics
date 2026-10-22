package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One resolved output on an enrich pipeline's compiled projection: which model response field lands in
 * which physical column, and the transform applied on the way.
 *
 * <p><b>This is the fold of two lists into one.</b> Before, a consumer received {@code output_bindings}
 * ({@code column} → {@code var}) and the retired evaluator's {@code output_vars} ({@code name} → {@code jsonata})
 * and had to join them by name itself — which the Enrichment Runner did, per row, into a throwaway
 * {@code HashMap}, for a list whose {@code jsonata} is the identity on nearly every output. The join has
 * exactly one correct answer and one place that knows it, so it is done here, once, at registration's
 * resolution rather than on every row.
 *
 * <p><b>{@link #name} and {@link #column} are two facts, not one, and must not be collapsed.</b>
 * {@code name} is the property the model's JSON response carries — the target column's <em>exposed</em>
 * name, which is what the composed schema keys on and what an output declaration must match.
 * {@code column} is the physical {@code source_name} the value is written under; {@code EnrichSpecValidator}
 * rewrites a binding to the physical spelling before storage. They are equal on all four live targets and
 * equal on nothing by construction — the first target that exposes a column under a different name than it
 * stores it would silently write to the wrong place, or nowhere, if a reader assumed one from the other.
 *
 * @param name the model response property, i.e. the target column's exposed name
 * @param column the physical enrichment column the value is written to
 * @param jsonata transform over the model response, absent when the value is read straight out of
 *     {@code response[name]} — which is the common case
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineOutputDto(
        @Schema(description = "Model response property supplying the value; the target column's exposed "
                + "name, and the property the composed response schema declares", example = "risk_level")
        String name,

        @Schema(description = "Physical enrichment column written to (the column's source_name, which "
                + "may differ from `name`)", example = "risk_level")
        String column,

        @Schema(description = "Transform over the model response, applied before the value reaches the "
                + "column. Absent when the value is taken directly from the response property named by "
                + "`name`.")
        String jsonata) {
}
