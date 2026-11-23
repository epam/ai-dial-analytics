package com.epam.aidial.analytics.service.table.grant;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Converts a table's {@code column_policies} JSON text to and from the role → allowlist map. Output is deterministic
 * (roles sorted, each list sorted and de-duplicated), but the column is JSONB, which Postgres re-serialises, so whether
 * a role's policy changed is decided on decoded values, never on the stored text.
 */
@Component
@RequiredArgsConstructor
public class ColumnPolicyCodec {

    private static final TypeReference<LinkedHashMap<String, List<String>>> POLICIES = new TypeReference<>() {};

    private final JsonMapper jsonMapper;

    /** The stored policies keyed by role, in role order, each list sorted; empty for a blank or empty document. */
    public Map<String, List<String>> read(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            final Map<String, List<String>> parsed = jsonMapper.readValue(json, POLICIES);
            final Map<String, List<String>> result = new TreeMap<>();
            if (parsed != null) {
                parsed.forEach((role, columns) -> result.put(role, canonical(columns)));
            }
            return result;
        } catch (JacksonException e) {
            throw new IllegalStateException("stored column policies are not readable", e);
        }
    }

    /** Serializes {@code policies} for the {@code column_policies} column, roles and names sorted. */
    public String write(Map<String, List<String>> policies) {
        final Map<String, List<String>> canonical = new TreeMap<>();
        policies.forEach((role, columns) -> canonical.put(role, canonical(columns)));
        try {
            return jsonMapper.writeValueAsString(canonical);
        } catch (JacksonException e) {
            throw new IllegalStateException("column policies could not be serialized", e);
        }
    }

    /** {@code columns} sorted and de-duplicated; empty for {@code null}. */
    public static List<String> canonical(List<String> columns) {
        return columns == null ? List.of() : List.copyOf(new TreeSet<>(columns));
    }
}
