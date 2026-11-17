package com.epam.aidial.analytics.web.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a JSON array of strings and nothing else: a number, a {@code null} or a nested array as an item is a binding
 * failure ({@code 400}). Jackson's default {@code List<String>} binding coerces scalars, keeps {@code null}s and
 * flattens {@code [["a"]]} to {@code ["a"]}, leaking the closing token to the enclosing object.
 */
public class StrictStringListDeserializer extends ValueDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            throw ctxt.wrongTokenException(p, List.class, JsonToken.START_ARRAY, "expected an array of strings");
        }
        final List<String> values = new ArrayList<>();
        for (JsonToken token = p.nextToken(); token != JsonToken.END_ARRAY; token = p.nextToken()) {
            if (token != JsonToken.VALUE_STRING) {
                throw ctxt.wrongTokenException(p, String.class, JsonToken.VALUE_STRING, "every item must be a string");
            }
            values.add(p.getString());
        }
        return values;
    }
}
