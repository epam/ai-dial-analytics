package com.epam.aidial.analytics.web.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a JSON string and nothing else: a number, a boolean, an array or an object is a binding failure ({@code 400}),
 * where Jackson's default {@code String} binding would coerce a scalar to its text.
 */
public class StrictStringDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            throw ctxt.wrongTokenException(p, String.class, JsonToken.VALUE_STRING, "expected a string");
        }
        return p.getString();
    }
}
