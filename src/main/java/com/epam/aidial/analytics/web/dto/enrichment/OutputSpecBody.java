package com.epam.aidial.analytics.web.dto.enrichment;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The object form of an {@code outputs} value: what the model is told about the field, plus <em>at most
 * one</em> of a declared domain or a response transform. {@code llm} transforms only — a {@code sql}
 * output is a bare expression string, so this shape has nothing it could mean there.
 *
 * <p><b>{@code prose} is optional</b>, and carries no {@code @NotBlank}. An absent, null, blank or
 * wholly-empty value all mean "default it", and composition then takes the field's description from the
 * target column — the one fact an author used to have to copy by hand, beside the {@code type}, the
 * {@code enum} domain and the enum order composition already reads off the column. An annotation here
 * never enforced anything in any case: the {@code outputs} map carries no {@code @Valid}, so bean
 * validation does not cascade into its values, and the check that did the work lived in the service.
 *
 * <p><b>There is deliberately no refusal of an object declaring none of the three.</b> An absent value,
 * {@code null}, {@code {}} and a blank {@code prose} are <em>one</em> spelling of "default the prose from
 * the target column's description", not four, and a refusal could only reject one of them: {@code {}} and
 * {@code {"prose": null}} bind to exactly the value {@code null} binds to, so nothing downstream can tell
 * them apart to refuse. All four forms are accepted and all four compose the same schema property.
 *
 * <p>A plain record with no custom deserializer, deliberately: {@link OutputSpecDeserializer} delegates
 * the object branch here so that the strict request mapper's {@code FAIL_ON_UNKNOWN_PROPERTIES} still
 * applies inside it and a misspelled member is a 400 naming it.
 *
 * @param prose   what the model is told about this field; becomes the composed schema's
 *                {@code description}. Optional — omitted, it defaults to the target column's own
 *                {@code description} at composition time.
 * @param values  a closed domain for a field whose target column cannot carry one; refused when that column
 *                already declares {@code enum_values}, and refused together with {@code jsonata}
 * @param jsonata a transform applied to the model's field before it reaches the column; refused when it is
 *                the identity expression, because an absent {@code jsonata} already means that
 */
public record OutputSpecBody(
        @Schema(description = "What the model is told about this field. Optional: omitted, the target "
                + "column's own description is used in the composed schema.",
                example = "Title of the session, at most 8 words, English. No filenames, paths or identifiers.")
        String prose,

        @Schema(description = "Closed domain for a field whose target column cannot carry one (a string "
                + "column). Refused when the column declares enum_values.")
        List<String> values,

        @Schema(description = "Transform applied to the model's field before it reaches the column. Omit for "
                + "a direct lookup by name; the identity expression is refused.",
                example = "(coding_task_type = \"none\") ? null : coding_task_type")
        String jsonata) {
}
