package com.epam.aidial.analytics.web.dto.enrichment;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads one {@code transform.inputs} value in either accepted form: a bare column name, or an object.
 *
 * <p>The object branch delegates to {@link VarSpecBody}'s ordinary bean deserializer for the same reason
 * {@code OutputSpecDeserializer} does: a hand-written reader that took only the members it knows would let
 * a misspelling through silently, on the one surface strict request parsing exists to protect. Wired at
 * the use site rather than on {@link VarSpec}, so reading {@link VarSpecBody} here cannot re-enter this
 * deserializer.
 */
public class VarSpecDeserializer extends ValueDeserializer<VarSpec> {

    @Override
    public VarSpec deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_STRING) {
            return new VarSpec(p.getString(), null);
        }
        if (token == JsonToken.START_OBJECT) {
            JsonNode node = ctxt.readTree(p);
            return new VarSpec(null, ctxt.readTreeAsValue(node, VarSpecBody.class));
        }
        throw ctxt.wrongTokenException(p, VarSpec.class, JsonToken.VALUE_STRING,
                "a transform input is either a column name (a string) or an object {column} / {jsonata}");
    }
}
