package com.epam.aidial.analytics.service.pipeline.preview;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Caps every value a preview returns at {@value #CAP_BYTES} bytes, and measures each column's widest value
 * before it does.
 *
 * <p>A body column runs to tens of kilobytes per row, so five rows of two of them make a response a client
 * cannot render. The cap shortens what is <b>returned</b>, never what is read: it runs last, after every check
 * that needs the full values, and {@code max_bytes} is measured from the value before it is cut. A constant
 * rather than a configured knob, like the enrich sample's row cap: raising it would rebuild the response it
 * exists to avoid.
 *
 * <p>A {@code string} is cut at a code point boundary and ends in {@code …}, the ellipsis counted in the cap.
 * An {@code object}, {@code array} or {@code map} is measured as its compact serialization through the
 * application {@link JsonMapper}, as the response writes it, and an over-cap one is replaced by that
 * serialization, cut the same way — the cell then holds text, and the column's {@code type} still names the
 * value's real type. Bytes rather than characters, because bytes are what the response weighs; they are counted before
 * the response's own JSON string escaping, so a cut cell of quotes or control characters weighs more on the wire.
 */
@Component
@RequiredArgsConstructor
public class PreviewValueCapper {

    /** The largest a returned cell may be, in UTF-8 bytes, the ellipsis included. */
    public static final int CAP_BYTES = 2048;

    private static final String ELLIPSIS = "…";

    private static final int ELLIPSIS_BYTES = ELLIPSIS.getBytes(StandardCharsets.UTF_8).length;

    /** The types whose width varies with the value; {@code uuid} and {@code enum} are fixed or small. */
    private static final Set<QueryFieldType> MEASURED =
            EnumSet.of(QueryFieldType.STRING, QueryFieldType.OBJECT, QueryFieldType.ARRAY, QueryFieldType.MAP);

    private final JsonMapper jsonMapper;

    /** The capped sample: its columns with {@code max_bytes}, its rows, and a note per capped column. */
    public record Capped(List<PreviewColumn> columns, List<Map<String, Object>> rows, List<PreviewNote> notes) {}

    public Capped cap(List<PreviewColumn> columns, List<Map<String, Object>> rows) {
        final List<Map<String, Object>> capped = new ArrayList<>(rows.size());
        rows.forEach(row -> capped.add(new LinkedHashMap<>(row)));
        final List<PreviewColumn> measured = new ArrayList<>(columns.size());
        final List<PreviewNote> notes = new ArrayList<>();
        for (final PreviewColumn column : columns) {
            if (!MEASURED.contains(column.type())) {
                measured.add(column);
                continue;
            }
            Long widest = null;
            int cut = 0;
            for (final Map<String, Object> row : capped) {
                final Object value = row.get(column.name());
                if (value == null) {
                    continue;
                }
                final String text = value instanceof String string ? string : jsonMapper.writeValueAsString(value);
                final int size = text.getBytes(StandardCharsets.UTF_8).length;
                widest = widest == null ? size : Math.max(widest, size);
                if (size > CAP_BYTES) {
                    row.put(column.name(), truncate(text));
                    cut++;
                }
            }
            measured.add(column.withMaxBytes(widest));
            if (cut > 0) {
                notes.add(PreviewNote.about(PreviewNoteCode.VALUES_TRUNCATED, column.name(), cut + " sampled value(s) of "
                        + "column '" + column.name() + "' exceeded the " + CAP_BYTES + "-byte value cap and "
                        + "are shortened in `rows`; every check and `max_bytes` (" + widest + ") read the full "
                        + "values"));
            }
        }
        return new Capped(measured, capped, notes);
    }

    /**
     * {@code text}'s leading code points that fit in {@value #CAP_BYTES} bytes with the ellipsis, followed by it.
     * Walked by code point, so neither a UTF-8 sequence nor a surrogate pair is split.
     */
    static String truncate(String text) {
        final int budget = CAP_BYTES - ELLIPSIS_BYTES;
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            final int codePoint = text.codePointAt(end);
            final int width = utf8Width(codePoint);
            if (bytes + width > budget) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }
}
