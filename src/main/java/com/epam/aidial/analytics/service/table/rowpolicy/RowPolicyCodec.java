package com.epam.aidial.analytics.service.table.rowpolicy;

import com.epam.aidial.analytics.service.table.TableAccess;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Converts a table's {@code row_policies} JSON text to and from the role → {@link RowPolicy} map, using
 * the <b>application's</b> {@link JsonMapper} — the one the query endpoints parse a filter with — so a
 * stored policy is parsed exactly as the filter a client posts to {@code /v1/queries/execute} would be
 * (see {@code SavedQueryJson} for why Hibernate's own mapper cannot do this).
 */
@Component
@RequiredArgsConstructor
public class RowPolicyCodec {

    private static final TypeReference<LinkedHashMap<String, RowPolicy>> POLICIES = new TypeReference<>() {};

    private final JsonMapper jsonMapper;

    /**
     * The stored policies keyed by role, in role order; empty for a blank or empty document.
     *
     * <p>A failure means a stored row cannot be read — it was written by this same mapper, so this is data
     * corruption or a breaking DSL change, not caller error, and it surfaces as a server error.
     */
    public Map<String, RowPolicy> read(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            final Map<String, RowPolicy> parsed = jsonMapper.readValue(json, POLICIES);
            return parsed == null ? Map.of() : new TreeMap<>(parsed);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored row policies are not readable", e);
        }
    }

    /**
     * A table's stored grant lists as read by the row-policy projection, which carries them as JSON text; an
     * empty {@link TableAccess} for a blank document. Unknown members are tolerated, as on every persisted read.
     */
    public TableAccess readAccess(String json) {
        if (StringUtils.isBlank(json)) {
            return new TableAccess();
        }
        try {
            final TableAccess access = jsonMapper.readValue(json, TableAccess.class);
            return access == null ? new TableAccess() : access;
        } catch (JacksonException e) {
            throw new IllegalStateException("stored table access is not readable", e);
        }
    }

    /** Serializes {@code policies} for the {@code row_policies} column, roles in sorted order. */
    public String write(Map<String, RowPolicy> policies) {
        try {
            return jsonMapper.writeValueAsString(new TreeMap<>(policies));
        } catch (JacksonException e) {
            throw new IllegalStateException("row policies could not be serialized", e);
        }
    }
}
