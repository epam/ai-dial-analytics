package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One value of an enrich pipeline's {@code transform.inputs} map: what the transform receives under that
 * name.
 *
 * <ul>
 *   <li>a bare <b>string</b> — a column of the read source's entity, which is the whole of live usage
 *       (all 8 live bindings name a column and none carries a transform);
 *   <li>an <b>object</b> {@code {column}} or {@code {jsonata}} — exactly one of them, for the row-shaping
 *       case a bare column name cannot express.
 * </ul>
 *
 * <p>{@code transform.inputs} replaces the pipeline's {@code input_bindings} <em>and</em> the retired
 * evaluator's {@code input_vars} together: the map's keys are the variable names, so a name can no longer
 * be declared in one object and bound in another with nothing comparing the two. The evaluator's copy was
 * read by nothing in any case — the inputs map's own keys drive template substitution. It sits inside the
 * {@code transform} block rather than at the top level because the pipeline's top-level {@code inputs} is
 * the table it reads; the two are different JSON types, so a member sent at the wrong level fails to bind
 * rather than binding to the wrong thing.
 *
 * <p><b>What a var is depends on the trigger's grain</b>, which is the single most misread fact in this
 * area. At row grain it is a top-level {@code {{placeholder}}} in the request template. At group grain it
 * is a field of each member object rendered inside {@code {{members}}} and appears nowhere in the template
 * as a placeholder — which is why the template rule is trigger-aware and why both live group pipelines
 * mention none of their four vars in their templates.
 *
 * @param text the bare-string form's column name; null when the object form was used
 * @param body the object form; null when the bare-string form was used
 */
@Schema(description = "What the transform receives under this name: a column of the read source's entity "
        + "(a bare string), or an object {column} / {jsonata}",
        anyOf = {String.class, VarSpecBody.class})
public record VarSpec(String text, VarSpecBody body) {

    /**
     * The form this value was written in, so a serialized request (the catalog bundle carries a pipeline's
     * transform in request shape) reads back as the same value. Reading is the use site's
     * {@link VarSpecDeserializer}, as before.
     */
    @JsonValue
    public Object wireValue() {
        return body == null ? text : body;
    }

    /** The bound column, whichever form carried it, or null when a transform was declared instead. */
    public String column() {
        return body == null ? text : body.column();
    }

    /** The JSONata transform over the scanned row, or null when a column was bound directly. */
    public String jsonata() {
        return body == null ? null : body.jsonata();
    }
}
