package com.epam.aidial.analytics.web.dto.enrichment;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads one {@code outputs} value in either accepted form: a bare string, or an object.
 *
 * <p><b>The object branch delegates, and that is the whole design.</b> A hand-written deserializer that
 * read only the members it knows would let a typo'd {@code "prosee"} through — silently, and on the one
 * surface {@code StrictRequestJsonConfiguration} exists to make strict, which is exactly the class of bug
 * that motivated strictness (a {@code member_select} carrying {@code order} instead of {@code order_by}
 * stored as no ordering at all). Handing the object to {@link OutputSpecBody}'s ordinary bean deserializer
 * keeps {@code FAIL_ON_UNKNOWN_PROPERTIES} in force inside it, so an unrecognized member is a 400 naming
 * it just as at the top level.
 *
 * <p>Wired at the use site — the transform records' {@code outputs} map, via {@code contentUsing} — rather than
 * on {@link OutputSpec} itself, so reading {@link OutputSpecBody} here cannot re-enter this deserializer.
 * That is the same wiring rule {@code FilterNodeDeserializer} follows for the same reason.
 *
 * <p>Anything that is neither a string nor an object is reported as the type error it is, rather than
 * coerced: a number or an array in this position is a caller mistake with no sensible reading.
 */
public class OutputSpecDeserializer extends ValueDeserializer<OutputSpec> {

    @Override
    public OutputSpec deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return new OutputSpec(p.getString(), null);
        }
        if (token == JsonToken.START_OBJECT) {
            JsonNode node = ctxt.readTree(p);
            return new OutputSpec(null, ctxt.readTreeAsValue(node, OutputSpecBody.class));
        }
        throw ctxt.wrongTokenException(p, OutputSpec.class, JsonToken.VALUE_STRING,
                "a transform output is either prose (a string) or an object {prose, values?, jsonata?}");
    }
}
