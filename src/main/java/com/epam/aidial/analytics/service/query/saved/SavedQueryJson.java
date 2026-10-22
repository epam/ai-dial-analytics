package com.epam.aidial.analytics.service.query.saved;

import com.epam.aidial.analytics.exception.SavedQueryValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts a saved query's JSON payloads between their typed form and the text stored in the
 * {@code jsonb} columns, using the <strong>application's</strong> {@link JsonMapper}.
 *
 * <p>This exists because Hibernate's JSON format mapper is Jackson 2 ({@code com.fasterxml.jackson})
 * while this codebase's databind is Jackson 3 ({@code tools.jackson}). The structured-query DSL depends
 * on Jackson 3 wiring that Hibernate's mapper cannot see — {@code FilterNode} is routed by
 * {@code FilterNodeDeserializer} because its {@code op} key is both discriminator and operator data, and
 * that routing is declared with a Jackson 3 {@code @JsonDeserialize}. Letting Hibernate map the DSL
 * directly fails on read.
 *
 * <p>The alternative — a parallel Jackson 2 deserializer for {@code FilterNode} — would mean two
 * implementations of one security-relevant parse, which could drift. Round-tripping through the
 * application mapper instead means a stored body is parsed by exactly the mapper the query endpoints
 * use, so what is validated, what is stored, and what a client can post back to {@code /v1/queries/execute}
 * are the same thing by construction.
 */
@Component
@RequiredArgsConstructor
public class SavedQueryJson {

    private final JsonMapper jsonMapper;

    /** Serializes a payload for its {@code jsonb} column; {@code null} in, {@code null} out. */
    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new SavedQueryValidationException("the saved query body could not be serialized");
        }
    }

    /**
     * Parses a stored payload back into its typed form; {@code null} in, {@code null} out.
     *
     * <p>A failure here means a stored row cannot be read — the row was written by this same mapper, so
     * this is data corruption or a breaking DSL change, not caller error. It surfaces as a server error
     * rather than being mapped to a client-facing code.
     */
    public <T> T read(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored saved-query payload is not readable as " + type.getSimpleName(), e);
        }
    }
}
