package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One value of a transform's {@code outputs} map, in either of the two accepted wire forms.
 *
 * <ul>
 *   <li>a bare <b>string</b> — the common case, 23 of the 25 live outputs. For an {@code llm} transform it
 *       is the prose the model is told about the field; for a {@code sql} one it is the expression.
 *       Which one it means is selected by the transform's {@code type}, not guessed here, so the raw text
 *       is parked in {@link #text} and dispatched by the mapper.
 *   <li>an <b>object</b> {@code {prose, values?, jsonata?}} — {@code llm} only, for the two outputs that
 *       need more than prose.
 * </ul>
 *
 * <p>The two forms are told apart by {@link OutputSpecDeserializer}, which dispatches on the token type and
 * hands the object branch to {@link OutputSpecBody}'s ordinary bean deserializer. That delegation is the
 * point: a hand-written deserializer reading only the members it recognises would let a typo'd
 * {@code "prosee"} through silently, in the object branch, on the one surface
 * {@code StrictRequestJsonConfiguration} exists to make strict — so an unknown member inside the object is
 * a 400 naming it, exactly as at the top level.
 *
 * @param text the bare-string form's raw text; null when the object form was used
 * @param body the object form; null when the bare-string form was used
 */
@Schema(description = "What the transform produces for one target column: for an llm transform the prose "
        + "the model is told about the field, as a bare string or an object {prose?, values?, jsonata?} - "
        + "prose optional, defaulting to the column's own description; for a sql transform the scalar "
        + "expression as a bare string",
        anyOf = {String.class, OutputSpecBody.class})
public record OutputSpec(String text, OutputSpecBody body) {

    /**
     * The wire form to serialize: whichever of the two was carried, so a read echoes exactly the shape
     * that was authored and a read-modify-write {@code PATCH} sends it back unchanged.
     *
     * <p>This is not the {@code vars} rule ("always echo the object form") relaxed. There the two forms
     * are two spellings of one fact, so picking one removes a guess; here the form <em>is</em> the type
     * dispatch — a {@code sql} output must be a bare expression string on the way in, so echoing it as an
     * object would produce a body the next {@code PATCH} refuses.
     */
    @JsonValue
    public Object wireValue() {
        return body == null ? text : body;
    }

    /** The prose form's text, whichever form carried it: the bare string, or the object's {@code prose}. */
    public String proseText() {
        return body == null ? text : body.prose();
    }

    /** The declared domain, or null. Only the object form can carry one. */
    public List<String> values() {
        return body == null ? null : body.values();
    }

    /** The response transform, or null. Only the object form can carry one. */
    public String jsonata() {
        return body == null ? null : body.jsonata();
    }

    /**
     * True when the bare-string form was used — the only form a {@code sql} transform may use.
     *
     * <p>{@code hidden} because springdoc would otherwise publish {@code bareString} as a member of this
     * schema beside the {@code anyOf} that is the real wire shape. {@link VarSpec}, the same
     * string-or-object pattern, publishes only its two components; this brings the pair back into line.
     * Not {@code @JsonIgnore}d: an ignored property is silently accepted on the way in, where an unknown
     * one is the 400 naming it that the strict request mapper exists for.
     */
    @Schema(hidden = true)
    public boolean isBareString() {
        return body == null;
    }
}
