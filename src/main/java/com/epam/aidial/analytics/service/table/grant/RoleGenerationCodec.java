package com.epam.aidial.analytics.service.table.grant;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Converts a table's {@code access_role_generations} JSON text — for each role, the access generation at which its entry
 * last changed, a removed entry included — to and from a map. A role absent from it has not changed since the column
 * was added, so its mark is {@code 0}.
 */
@Component
@RequiredArgsConstructor
public class RoleGenerationCodec {

    private static final TypeReference<TreeMap<String, Long>> MARKS = new TypeReference<>() {};

    private final JsonMapper jsonMapper;

    /** The generation at which {@code role}'s entry last changed, or {@code 0} when it never has. */
    public long markOf(String json, String role) {
        return read(json).getOrDefault(role, 0L);
    }

    /** {@code json} with every role of {@code roles} marked as changed at {@code generation}. */
    public String mark(String json, Collection<String> roles, long generation) {
        final Map<String, Long> marks = read(json);
        roles.forEach(role -> marks.put(role, generation));
        try {
            return jsonMapper.writeValueAsString(marks);
        } catch (JacksonException e) {
            throw new IllegalStateException("access role generations could not be serialized", e);
        }
    }

    private Map<String, Long> read(String json) {
        if (StringUtils.isBlank(json)) {
            return new TreeMap<>();
        }
        try {
            final TreeMap<String, Long> parsed = jsonMapper.readValue(json, MARKS);
            return parsed == null ? new TreeMap<>() : parsed;
        } catch (JacksonException e) {
            throw new IllegalStateException("stored access role generations are not readable", e);
        }
    }
}
