package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvResultWriterTest {

    private final CsvResultWriter writer = new CsvResultWriter(new JsonMapperConfiguration().jsonMapper());

    private String write(List<String> columns, List<Map<String, Object>> rows) {
        return new String(writer.write(columns, rows), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> row(Object... keyValues) {
        final Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void emptyResultIsExactlyTheHeaderRecord() {
        assertThat(write(List.of("request_id", "model"), List.of())).isEqualTo("request_id,model\r\n");
    }

    @Test
    void everyRecordEndsWithCrlfAndFollowsColumnOrder() {
        final String csv = write(List.of("b", "a"), List.of(row("a", "1", "b", "2"), row("a", "3", "b", "4")));

        assertThat(csv).isEqualTo("b,a\r\n2,1\r\n4,3\r\n");
    }

    @Test
    void fieldsWithDelimiterQuoteOrLineBreakAreQuotedAndQuotesDoubled() {
        final String csv = write(List.of("text", "cr", "plain"),
                List.of(row("text", "say \"hi\", then\nbye", "cr", "a\rb", "plain", "ok")));

        assertThat(csv).isEqualTo("text,cr,plain\r\n\"say \"\"hi\"\", then\nbye\",\"a\rb\",ok\r\n");
    }

    @Test
    void headerNamesAreQuotedByTheSameRules() {
        assertThat(write(List.of("a,b", "c"), List.of())).isEqualTo("\"a,b\",c\r\n");
    }

    @Test
    void nullIsAnEmptyFieldAndEmptyStringIsQuoted() {
        final String csv = write(List.of("n", "e", "last"), List.of(row("n", null, "e", "", "last", null)));

        assertThat(csv).isEqualTo("n,e,last\r\n,\"\",\r\n");
    }

    @Test
    void missingKeyRendersLikeNull() {
        assertThat(write(List.of("a", "b"), List.of(row("a", "x")))).isEqualTo("a,b\r\nx,\r\n");
    }

    @Test
    void numbersAndBooleansRenderAsTheirJsonLiterals() {
        final String csv = write(List.of("i", "d", "big", "t"),
                List.of(row("i", 42L, "d", 3.5d, "big", new BigDecimal("12.50"), "t", true)));

        assertThat(csv).isEqualTo("i,d,big,t\r\n42,3.5,12.50,true\r\n");
    }

    @Test
    void structuredCellsRenderAsCompactJson() {
        final Map<String, Object> object = new LinkedHashMap<>();
        object.put("roles", Arrays.asList("admin", null));
        object.put("n", 1);
        final String csv = write(List.of("arr", "map", "obj", "empty"), List.of(row(
                "arr", Arrays.asList("a", null),
                "map", Map.of("http.method", "GET"),
                "obj", object,
                "empty", Map.of())));

        assertThat(csv).isEqualTo("arr,map,obj,empty\r\n"
                + "\"[\"\"a\"\",null]\",\"{\"\"http.method\"\":\"\"GET\"\"}\","
                + "\"{\"\"roles\"\":[\"\"admin\"\",null],\"\"n\"\":1}\",{}\r\n");
    }

    @Test
    void formulaLikeStringsAreWrittenAsIs() {
        assertThat(write(List.of("f", "g"), List.of(row("f", "=SUM(A1:A2)", "g", "@cmd"))))
                .isEqualTo("f,g\r\n=SUM(A1:A2),@cmd\r\n");
    }

    @Test
    void outputIsUtf8WithoutByteOrderMark() {
        final byte[] bytes = writer.write(List.of("name"), List.of(row("name", "Олексій")));

        assertThat(bytes[0]).isEqualTo((byte) 'n');
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("name\r\nОлексій\r\n");
    }
}
