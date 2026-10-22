package com.epam.aidial.analytics.service.pipeline.preview;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the preview's value cap: every returned cell is at most 2048 bytes, cut on a code point
 * boundary, while {@code max_bytes} and the note describe the full values.
 */
class PreviewValueCapperTest {

    private final PreviewValueCapper capper = new PreviewValueCapper(new JsonMapperConfiguration().jsonMapper());

    private static PreviewColumn input(String name, QueryFieldType type) {
        return new PreviewColumn(name, PreviewColumnRole.INPUT, type, null);
    }

    private static Map<String, Object> row(String column, Object value) {
        final Map<String, Object> row = new HashMap<>();
        row.put(column, value);
        return row;
    }

    private static int bytes(Object value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void cutsAnOverCapStringOnMultiByteBoundary() {
        // 'é' is two bytes, so 2045 bytes of budget land in the middle of one: the cut must stop before it.
        final String body = "é".repeat(1500);

        final PreviewValueCapper.Capped capped = capper.cap(List.of(input("body", QueryFieldType.STRING)),
                List.of(row("body", body)));

        final String cell = (String) capped.rows().getFirst().get("body");
        assertThat(bytes(cell)).isLessThanOrEqualTo(PreviewValueCapper.CAP_BYTES);
        assertThat(cell).endsWith("…").startsWith("éé");
        assertThat(cell.substring(0, cell.length() - 1)).matches("é+");
        assertThat(capped.columns().getFirst().maxBytes()).isEqualTo(3000L);
    }

    @Test
    void neverSplitsSurrogatePairThatStraddlesTheCut() {
        // 2044 ASCII bytes leave one byte of budget, too little for the 4-byte character that follows.
        final String body = "a".repeat(2044) + "😀".repeat(10);

        final String cell = (String) capper.cap(List.of(input("body", QueryFieldType.STRING)),
                List.of(row("body", body))).rows().getFirst().get("body");

        assertThat(cell).isEqualTo("a".repeat(2044) + "…");
        assertThat(bytes(cell)).isLessThanOrEqualTo(PreviewValueCapper.CAP_BYTES);
        assertThat(Character.isHighSurrogate(cell.charAt(cell.length() - 2))).isFalse();
    }

    @Test
    void leavesExactlyTheCapUnchangedAndCutsOneByteOver() {
        final PreviewValueCapper.Capped atCap = capper.cap(List.of(input("body", QueryFieldType.STRING)),
                List.of(row("body", "a".repeat(PreviewValueCapper.CAP_BYTES))));
        final PreviewValueCapper.Capped overCap = capper.cap(List.of(input("body", QueryFieldType.STRING)),
                List.of(row("body", "a".repeat(PreviewValueCapper.CAP_BYTES + 1))));

        assertThat(atCap.rows().getFirst().get("body")).isEqualTo("a".repeat(PreviewValueCapper.CAP_BYTES));
        assertThat(atCap.notes()).isEmpty();
        final String cell = (String) overCap.rows().getFirst().get("body");
        assertThat(cell).isEqualTo("a".repeat(PreviewValueCapper.CAP_BYTES - 3) + "…");
        assertThat(bytes(cell)).isEqualTo(PreviewValueCapper.CAP_BYTES);
    }

    @Test
    void cutsMapColumnAsJsonText() {
        final Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("k", "v".repeat(3000));

        final PreviewValueCapper.Capped capped = capper.cap(List.of(input("tags", QueryFieldType.MAP)),
                List.of(row("tags", tags)));

        final String cell = (String) capped.rows().getFirst().get("tags");
        assertThat(cell).startsWith("{\"k\":\"vvv").endsWith("…");
        assertThat(bytes(cell)).isLessThanOrEqualTo(PreviewValueCapper.CAP_BYTES);
        assertThat(capped.notes()).singleElement().extracting(PreviewNote::code).isEqualTo(PreviewNoteCode.VALUES_TRUNCATED);
    }

    @Test
    void replacesAnOverCapObjectAndArrayWithTheirCutJson() {
        final Map<String, Object> object = new LinkedHashMap<>();
        object.put("messages", "x".repeat(3000));
        final List<Object> array = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            array.add("item-" + i);
        }
        final Map<String, Object> row = new HashMap<>();
        row.put("request", object);
        row.put("tags", array);

        final PreviewValueCapper.Capped capped = capper.cap(
                List.of(input("request", QueryFieldType.OBJECT), input("tags", QueryFieldType.ARRAY)), List.of(row));

        final Object request = capped.rows().getFirst().get("request");
        final Object tags = capped.rows().getFirst().get("tags");
        assertThat(request).isInstanceOf(String.class).asString().startsWith("{\"messages\":\"xxx").endsWith("…");
        assertThat(tags).isInstanceOf(String.class).asString().startsWith("[\"item-0\",\"item-1\"").endsWith("…");
        assertThat(bytes(request)).isLessThanOrEqualTo(PreviewValueCapper.CAP_BYTES);
        assertThat(bytes(tags)).isLessThanOrEqualTo(PreviewValueCapper.CAP_BYTES);
        // The column's type still names the value's real type.
        assertThat(capped.columns()).extracting(PreviewColumn::type)
                .containsExactly(QueryFieldType.OBJECT, QueryFieldType.ARRAY);
    }

    @Test
    void leavesAnUnderCapValueUnchangedAndMeasuresItAsJson() {
        final Map<String, Object> object = Map.of("a", 1);

        final PreviewValueCapper.Capped capped = capper.cap(
                List.of(input("small", QueryFieldType.OBJECT), input("text", QueryFieldType.STRING)),
                List.of(Map.of("small", object, "text", "hello")));

        assertThat(capped.rows().getFirst()).containsEntry("small", object).containsEntry("text", "hello");
        assertThat(capped.columns()).extracting(PreviewColumn::maxBytes).containsExactly(7L, 5L);
        assertThat(capped.notes()).isEmpty();
    }

    @Test
    void measuresTheWidestValueAndLeavesAnAllNullColumnUnmeasured() {
        final List<Map<String, Object>> rows = List.of(
                row("body", "x".repeat(5000)), row("body", "short"), row("body", null));

        final PreviewValueCapper.Capped capped = capper.cap(
                List.of(input("body", QueryFieldType.STRING), input("empty", QueryFieldType.STRING),
                        new PreviewColumn("id", PreviewColumnRole.GRAIN_KEY, QueryFieldType.UUID, null)),
                rows);

        assertThat(capped.columns()).extracting(PreviewColumn::maxBytes).containsExactly(5000L, null, null);
        assertThat(capped.rows().get(1)).containsEntry("body", "short");
    }

    @Test
    void reportsOneNotePerCappedColumnWithItsCount() {
        final List<Map<String, Object>> rows = List.of(
                Map.of("a", "x".repeat(3000), "b", "y".repeat(3000)),
                Map.of("a", "x".repeat(3000), "b", "ok"));

        final List<PreviewNote> notes = capper.cap(
                List.of(input("a", QueryFieldType.STRING), input("b", QueryFieldType.STRING)), rows).notes();

        assertThat(notes).extracting(PreviewNote::code)
                .containsExactly(PreviewNoteCode.VALUES_TRUNCATED, PreviewNoteCode.VALUES_TRUNCATED);
        assertThat(notes).extracting(PreviewNote::subject).containsExactly("a", "b");
        assertThat(notes.get(0).message()).startsWith("2 sampled value(s) of column 'a'").contains("2048");
        assertThat(notes.get(1).message()).startsWith("1 sampled value(s) of column 'b'");
    }
}
