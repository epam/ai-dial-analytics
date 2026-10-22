package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the row-write numeric range check against a real ClickHouse, which on its own
 * stores an out-of-range integer wrapped ({@code 3000000000} in an {@code Int32} reads back as
 * {@code -1294967296}) rather than refusing it, and refuses an out-of-range decimal only with an error that
 * names neither row nor column. Pins both halves: each wrapping form is refused before the
 * insert, and boundary values still store exactly.
 *
 * <p>Requires a running Docker daemon.
 */
class RowWriteNumericRangeIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String TABLE = "int_events";
    private static final String ROWS = "/v1/tables/" + TABLE + "/rows";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    private static final String COLUMNS = """
            [{"source_name":"event_id","name":"event_id","type":"string"},
             {"source_name":"small","name":"small","type":"integer","nullable":true},
             {"source_name":"big","name":"big","type":"long","nullable":true},
             {"source_name":"smalls","name":"smalls","type":"array","element_type":"integer"},
             {"source_name":"price","name":"price","type":"decimal","nullable":true}]""";

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

    @Test
    void boundaryValuesStoreExactly() throws Exception {
        write("""
                [{"event_id":"e1","small":2147483647,"big":3000000000,"smalls":[-2147483648, 7]}]""", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT small, big FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].small").value(2147483647))
                .andExpect(jsonPath("$.rows[0].big").value(3000000000L));
    }

    /** The reported case, plus every other spelling ClickHouse wraps the same way. */
    @Test
    void outOfRangeValuesAreRejectedAndNothingIsInserted() throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rows":[{"event_id":"good","small":1,"smalls":[]},
                                         {"event_id":"bad","small":3000000000,"smalls":[]}]}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("row 2"),
                        containsString("'small'"),
                        containsString("3000000000"),
                        containsString("out of range for integer (Int32)"))));

        write("[{\"event_id\":\"q\",\"small\":\"3000000000\",\"smalls\":[]}]", 422);
        write("[{\"event_id\":\"a\",\"smalls\":[1, 3000000000]}]", 422);
        write("[{\"event_id\":\"l\",\"big\":99999999999999999999,\"smalls\":[]}]", 422);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT count(*) AS c FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(0));
    }

    /** ClickHouse already refused a fraction, but with an opaque parse error; now it names row and column. */
    @Test
    void fractionalValueIsRejectedAsNotWhole() throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"f\",\"small\":1.5,\"smalls\":[]}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("'small'"),
                        containsString("not a whole number"))));
    }

    @Test
    void decimalAtItsIntegerDigitLimitStoresAndOneDigitMoreIsRejected() throws Exception {
        write("[{\"event_id\":\"d\",\"price\":\"99999999999999999999999999.5\",\"smalls\":[]}]", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT price FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                // Asserted on the raw body: the read emits the full digits, which a double-backed jsonPath would round.
                .andExpect(content().string(containsString("\"price\":99999999999999999999999999.5")));

        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"x\",\"price\":\"100000000000000000000000000\",\"smalls\":[]}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("'price'"),
                        containsString("out of range for decimal"))));
        write("[{\"event_id\":\"e\",\"price\":1e30,\"smalls\":[]}]", 422);
    }
}
