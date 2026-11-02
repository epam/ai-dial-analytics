package com.epam.aidial.analytics.service.dashboard;

import com.epam.aidial.analytics.exception.DashboardValidationException;
import com.epam.aidial.analytics.service.dashboard.model.DashboardWidget;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Converts a dashboard's JSON payloads — its widgets and its time intent — between their typed form and
 * the text stored in the {@code jsonb} columns, with the <strong>application's</strong> mapper, for the
 * reason {@code SavedQueryJson} gives: an inline structured body depends on Jackson 3 wiring that
 * Hibernate's Jackson 2 mapper cannot see.
 */
@Component
public class DashboardJson {

    private final JsonMapper jsonMapper;
    private final JavaType widgetList;

    public DashboardJson(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.widgetList = jsonMapper.getTypeFactory().constructCollectionType(List.class, DashboardWidget.class);
    }

    /** Serializes a payload for its {@code jsonb} column; {@code null} in, {@code null} out. */
    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new DashboardValidationException("the dashboard could not be serialized");
        }
    }

    /** The UTF-8 size of a payload as it would be stored. */
    public int serializedSize(Object value) {
        final String text = write(value);
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The stored widget array. A failure means a stored row cannot be read — it was written by this same
     * mapper — so it surfaces as a server error rather than a client-facing code.
     */
    public List<DashboardWidget> readWidgets(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, widgetList);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored dashboard widgets are not readable", e);
        }
    }

    /** The stored time intent, or null. */
    public SavedQueryTime readTime(String json) {
        if (json == null) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, SavedQueryTime.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored dashboard time intent is not readable", e);
        }
    }
}
