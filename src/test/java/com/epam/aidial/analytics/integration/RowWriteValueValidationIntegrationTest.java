package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the row-write value checks for {@code date}, {@code array}, {@code enum} and
 * {@code map} columns against a real ClickHouse, which on its own stores each refused form as something else
 * with no error: a bad date clamped or rolled over, a bare number read as a day count, a null array element
 * or map value as the type default, an enum number as the element at that internal id. Pins both halves: each
 * such value is refused before the insert, and the accepted forms store what was sent — including a
 * timestamp array element, rendered to its UTC instant.
 *
 * <p>Requires a running Docker daemon.
 */
class RowWriteValueValidationIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String TABLE = "value_events";
    private static final String ROWS = "/v1/tables/" + TABLE + "/rows";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    private static final String COLUMNS = """
            [{"source_name":"event_id","name":"event_id","type":"string"},
             {"source_name":"day","name":"day","type":"date","nullable":true},
             {"source_name":"stamps","name":"stamps","type":"array","element_type":"timestamp"},
             {"source_name":"counts","name":"counts","type":"array","element_type":"integer"},
             {"source_name":"level","name":"level","type":"enum","enum_values":["a","b"],"nullable":true},
             {"source_name":"attrs","name":"attrs","type":"map"},
             {"source_name":"n","name":"n","type":"integer","nullable":true}]""";
    /** The non-nullable array and map columns every row has to carry. */
    private static final String REQUIRED = "\"stamps\":[],\"counts\":[],\"attrs\":{}";

    @BeforeEach
    void cleanSlate() throws Exception {
        truncate();
        IntegrationTables.source(mockMvc, TABLE, COLUMNS, "[\"event_id\"]");
    }

    @AfterEach
    void dropUserTables() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + TABLE));
    }

    private void write(String rowsJson, int expectedStatus) throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON).content("{\"rows\":" + rowsJson + "}"))
                .andExpect(status().is(expectedStatus));
    }

    /** Writes one row carrying {@code fields} plus whichever required columns it does not set itself. */
    private void writeOne(String fields, int expectedStatus) throws Exception {
        String row = "\"event_id\":\"e\"";
        for (final String required : REQUIRED.split(",")) {
            if (!fields.contains(required.substring(0, required.indexOf(':')))) {
                row += "," + required;
            }
        }
        write("[{" + row + "," + fields + "}]", expectedStatus);
    }

    private void assertRowCount(int expected) throws Exception {
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT count(*) AS c FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(expected));
    }

    @Test
    void validDateRoundTrips() throws Exception {
        write("[{\"event_id\":\"e\",\"day\":\"2026-08-11\"," + REQUIRED + "},"
                + "{\"event_id\":\"lo\",\"day\":\"1970-01-01\"," + REQUIRED + "},"
                + "{\"event_id\":\"hi\",\"day\":\"2149-06-06\"," + REQUIRED + "}]", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT day FROM " + TABLE + " WHERE event_id = 'e'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].day").value("2026-08-11"));
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT day FROM " + TABLE + " WHERE event_id = 'hi'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].day").value("2149-06-06"));
    }

    /** Each is a {@code 200} with a different day in the table when it reaches ClickHouse unchecked. */
    @Test
    void invalidDatesAreRejectedAndNothingIsInserted() throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"good\",\"day\":\"2026-08-11\"," + REQUIRED + "},"
                                + "{\"event_id\":\"bad\",\"day\":\"2026-02-30\"," + REQUIRED + "}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("row 2"),
                        containsString("'day'"),
                        containsString("2026-02-30"),
                        containsString("does not exist on the calendar"))));
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"day\":\"2200-01-01\"," + REQUIRED + "}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("out of range for date")));
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"day\":20000," + REQUIRED + "}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("not a number")));

        assertRowCount(0);
    }

    /** The element is stored as the instant it denotes, at UTC — the same rendering a scalar timestamp gets. */
    @Test
    void timestampArrayElementIsStoredAsItsUtcInstant() throws Exception {
        writeOne("\"stamps\":[\"2026-08-11T13:00:00+03:00\",\"2026-08-11T05:30:00-04:30\"]", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT stamps FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].stamps[0]").value("2026-08-11T10:00:00Z"))
                .andExpect(jsonPath("$.rows[0].stamps[1]").value("2026-08-11T10:00:00Z"));
    }

    @Test
    void zonelessTimestampArrayElementIsRejected() throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"stamps\":[\"2026-08-11T10:00:00Z\",\"2026-08-11T10:00:00\"],"
                                + "\"counts\":[],\"attrs\":{}}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("'stamps[1]'"),
                        containsString("has no time zone"))));
        assertRowCount(0);
    }

    /** Elements are never {@code Nullable}: ClickHouse would store {@code 0} / the epoch in a null's place. */
    @Test
    void nullArrayElementIsRejected() throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"stamps\":[],\"counts\":[1,null],\"attrs\":{}}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("'counts[1]'"),
                        containsString("must not be null"))));
        writeOne("\"stamps\":[null]", 422);
        assertRowCount(0);
    }

    @Test
    void enumAcceptsOnlyDeclaredNamesAsStrings() throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"level\":\"c\"," + REQUIRED + "}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("'level'"),
                        containsString("'c' is not one of the declared values"),
                        containsString("('a', 'b')"))));
        // 1 is a valid internal id ('a'), which ClickHouse would store without complaint.
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"level\":1," + REQUIRED + "}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("as a string, not a number")));
        assertRowCount(0);

        writeOne("\"level\":\"b\"", 200);
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT level FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].level").value("b"));
    }

    /** ClickHouse would store {@code ''} for the null with no error. */
    @Test
    void nullMapValueIsRejected() throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"stamps\":[],\"counts\":[],\"attrs\":{\"k\":null}}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("'attrs'"),
                        containsString("key 'k' must not be null"))));
        assertRowCount(0);

        writeOne("\"attrs\":{\"k\":\"v\"}", 200);
        assertRowCount(1);
    }

    /** ClickHouse coerces {@code true} to {@code 1} and {@code ""} to {@code 0} in an integer column. */
    @Test
    void integerColumnRejectsBooleanAndEmptyString() throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"n\":true," + REQUIRED + "}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("'n'"),
                        containsString("not a boolean"))));
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"n\":\"\"," + REQUIRED + "}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("'n'"),
                        containsString("is not a number"))));
        assertRowCount(0);
    }
}
