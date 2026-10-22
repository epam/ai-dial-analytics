package com.epam.aidial.analytics.web.controller.query;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Renders a query result as RFC 4180 CSV for the {@code *-csv} execution endpoints: UTF-8 without a
 * byte-order mark, {@code ,} between fields, {@code CRLF} after every record, and a header record that
 * is written even when there are no rows.
 *
 * <p>Each cell is the value the JSON response would carry for it, obtained by serializing the cell with
 * the application's primary {@link JsonMapper} — the one the JSON body goes through — rather than by a
 * parallel type switch, so a value type with its own serializer (enum values, temporals, the unwrapped
 * ClickHouse arrays) renders here exactly as it does there. A value that serializes as a JSON string
 * is written as that string's characters; anything else (number, boolean, array, object) as its compact
 * JSON text. The value is deliberately serialized directly rather than through a {@code JsonNode} tree:
 * the tree normalizes decimals ({@code 12.50} → {@code 12.5}) where the JSON body does not.
 *
 * <p>A null is an empty unquoted field and the empty string is {@code ""}, so the two stay
 * distinguishable. Nothing is rewritten to defend spreadsheet applications against formula-like text.
 */
@Component
@RequiredArgsConstructor
public class CsvResultWriter {

    private static final String RECORD_END = "\r\n";

    private final JsonMapper jsonMapper;

    /** Writes the header record for {@code columns}, then one record per row, reading each row by column name. */
    public byte[] write(List<String> columns, List<Map<String, Object>> rows) {
        final StringBuilder out = new StringBuilder();
        appendRecord(out, columns);
        for (final Map<String, Object> row : rows) {
            appendRecord(out, columns.stream().map(column -> cellText(row.get(column))).toList());
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRecord(StringBuilder out, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            appendField(out, fields.get(i));
        }
        out.append(RECORD_END);
    }

    /** {@code null} (an SQL null) is the empty unquoted field; everything else is quoted only when it must be. */
    private static void appendField(StringBuilder out, String field) {
        if (field == null) {
            return;
        }
        if (field.isEmpty() || needsQuoting(field)) {
            out.append('"').append(field.replace("\"", "\"\"")).append('"');
        } else {
            out.append(field);
        }
    }

    private static boolean needsQuoting(String field) {
        for (int i = 0; i < field.length(); i++) {
            final char c = field.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }

    private String cellText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        final String json = jsonMapper.writeValueAsString(value);
        if ("null".equals(json)) {
            return null;
        }
        return json.startsWith("\"") ? jsonMapper.readValue(json, String.class) : json;
    }
}
