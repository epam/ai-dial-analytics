package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the row-write timestamp contract against a real ClickHouse: {@code timestamp}
 * columns accept ISO-8601 with an explicit zone and nothing else, and a value the read path returns is
 * writable back unchanged — the round-trip asymmetry the change removes.
 *
 * <p>The forms ClickHouse's own default parser used to accept (space-separated, zone-less, date-only, a
 * bare epoch number) are now rejected at the API boundary with a diagnosable {@code 422}, so this suite
 * pins both halves: what the endpoint takes, and that each rejected form reports <em>why</em> rather than
 * surfacing as the old opaque ClickHouse {@code 400}.
 *
 * <p>Requires a running Docker daemon.
 */
class RowWriteTimestampIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String TABLE = "ts_events";
    private static final String ROWS = "/v1/tables/" + TABLE + "/rows";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    // noted_on/label/score are declared nullable: this suite is about recorded_at's timestamp contract,
    // and most cases below write only event_id + recorded_at, which the non-nullable check must not block.
    private static final String COLUMNS = """
            [{"source_name":"event_id","name":"event_id","type":"string"},
             {"source_name":"recorded_at","name":"recorded_at","type":"timestamp"},
             {"source_name":"noted_on","name":"noted_on","type":"date","nullable":true},
             {"source_name":"label","name":"label","type":"string","nullable":true},
             {"source_name":"score","name":"score","type":"decimal","nullable":true}]""";

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    @AfterEach
    void dropUserTables() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + TABLE));
    }

    private void createTable() throws Exception {
        IntegrationTables.source(mockMvc, TABLE, COLUMNS, "[\"recorded_at\",\"event_id\"]");
    }

    private void write(String rowsJson, int expectedStatus) throws Exception {
        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON).content("{\"rows\":" + rowsJson + "}"))
                .andExpect(status().is(expectedStatus));
    }

    /** The reason this change exists: the exact string the read path hands back must insert. */
    @Test
    void isoTimestampWithZoneDesignatorIsAcceptedAndReadsBackIdentically() throws Exception {
        createTable();

        write("""
                [{"event_id":"e1","recorded_at":"2026-08-11T10:00:00Z","noted_on":"2026-08-11",
                  "label":"first","score":"94.25"}]""", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT event_id, recorded_at FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recorded_at").value("2026-08-11T10:00:00Z"));

        // Write the value the read path just returned, verbatim. Under the old behaviour this was a 400.
        write("[{\"event_id\":\"e2\",\"recorded_at\":\"2026-08-11T10:00:00Z\"}]", 200);
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT count(*) AS c FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(2));
    }

    /** An explicit offset denotes an instant, so it must store the same instant as its UTC spelling. */
    @Test
    void explicitOffsetStoresTheSameInstantAsItsUtcEquivalent() throws Exception {
        createTable();

        write("[{\"event_id\":\"offset\",\"recorded_at\":\"2026-08-11T13:00:00+03:00\"}]", 200);
        write("[{\"event_id\":\"utc\",\"recorded_at\":\"2026-08-11T10:00:00Z\"}]", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT event_id, recorded_at FROM " + TABLE + " ORDER BY event_id\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recorded_at").value("2026-08-11T10:00:00Z"))
                .andExpect(jsonPath("$.rows[1].recorded_at").value("2026-08-11T10:00:00Z"));
    }

    @Test
    void omittedSecondsAndSubMillisecondPrecisionAreAccepted() throws Exception {
        createTable();

        write("""
                [{"event_id":"a","recorded_at":"2026-08-11T10:00Z"},
                 {"event_id":"b","recorded_at":"2026-08-11T11:00:00.123456Z"}]""", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT recorded_at FROM " + TABLE + " ORDER BY recorded_at\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recorded_at").value("2026-08-11T10:00:00Z"))
                .andExpect(jsonPath("$.rows[1].recorded_at").value("2026-08-11T11:00:00.123Z"));
    }

    /**
     * The space-separated form was the only accepted spelling before this change, so its rejection is the
     * one a migrating caller will hit — the message has to name the separator, not just say "invalid".
     */
    @Test
    void spaceSeparatedFormIsRejectedWithDiagnosableMessage() throws Exception {
        createTable();

        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"e1\",\"recorded_at\":\"2026-08-11 10:00:00.000\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("recorded_at"),
                        containsString("space separator"),
                        containsString("2026-08-11T10:00:00Z"))));
    }

    @Test
    void zonelessDateOnlyAndNumericValuesAreRejected() throws Exception {
        createTable();

        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"e1\",\"recorded_at\":\"2026-08-11T10:00:00\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(
                        containsString("has no time zone")));

        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"e1\",\"recorded_at\":\"2026-08-11\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(
                        containsString("is a date, not a timestamp")));

        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"e1\",\"recorded_at\":1786514400000}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(
                        containsString("not a number")));
    }

    /**
     * A day that does not exist must not be quietly moved to the month's end: the resolver clamps by default,
     * which would store {@code 2026-02-28} for a caller who sent {@code 2026-02-29} — a wrong instant with a
     * {@code 200}. A real leap day, by contrast, is a real instant and inserts.
     */
    @Test
    void nonExistentCalendarDayIsRejectedRatherThanClampedToTheMonthEnd() throws Exception {
        createTable();

        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"e1\",\"recorded_at\":\"2026-02-29T10:00:00Z\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("recorded_at"),
                        containsString("does not exist on the calendar"),
                        containsString("2026-02-29"))));

        write("[{\"event_id\":\"leap\",\"recorded_at\":\"2024-02-29T10:00:00Z\"}]", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT event_id, recorded_at FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].recorded_at").value("2024-02-29T10:00:00Z"));
    }

    /** All-or-nothing, exactly as an unknown-column rejection already is. */
    @Test
    void oneInvalidTimestampInBatchInsertsNothing() throws Exception {
        createTable();

        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rows":[{"event_id":"good1","recorded_at":"2026-08-11T10:00:00Z"},
                                         {"event_id":"good2","recorded_at":"2026-08-11T11:00:00Z"},
                                         {"event_id":"bad","recorded_at":"2026-08-11 12:00:00.000"}]}"""))
                .andExpect(status().isUnprocessableContent())
                // The 1-based index locates the offending row; a batch may carry 10 000 of them.
                .andExpect(jsonPath("$.message").value(containsString("row 3")));

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT count(*) AS c FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(0));
    }

    /**
     * Normalization is scoped to {@code timestamp} columns. A {@code date} needs none ({@code YYYY-MM-DD} is
     * already both spellings), a {@code decimal} keeps its string digits rather than going through a float,
     * and a {@code string} column stores a timestamp-shaped value verbatim — including the space-separated
     * spelling, which is only rejected where the column is actually a timestamp.
     */
    @Test
    void onlyTimestampColumnsAreNormalized() throws Exception {
        createTable();

        write("""
                [{"event_id":"e1","recorded_at":"2026-08-11T10:00:00Z","noted_on":"2026-08-11",
                  "label":"2026-08-11 10:00:00.000","score":"94.25"}]""", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT noted_on, label, score FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].noted_on").value("2026-08-11"))
                .andExpect(jsonPath("$.rows[0].label").value("2026-08-11 10:00:00.000"))
                .andExpect(jsonPath("$.rows[0].score").value(94.25));
    }

    /**
     * The column-set check runs first, so a row naming a non-physical column still reports that rather than
     * its (also invalid) timestamp — the failure a caller can actually act on comes first.
     */
    @Test
    void unknownColumnIsReportedBeforeAnInvalidTimestamp() throws Exception {
        createTable();

        mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rows":[{"nope":"x","recorded_at":"2026-08-11 10:00:00.000"}]}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(
                        containsString("unknown column 'nope'")));
    }

    /**
     * An enrichment's grain key is physical but absent from the enrichment's own column mapping, so its
     * declared type has to come from the source. A {@code timestamp} grain key is therefore validated like
     * any other timestamp — without the source lookup it would skip normalization and keep failing with the
     * opaque ClickHouse error, inside the very endpoint this change fixes.
     */
    @Test
    void timestampGrainKeyIsValidatedFromTheSourceDeclaredType() throws Exception {
        final String source = "ts_buckets";
        final String enrichment = "ts_bucket_labels";
        try {
            IntegrationTables.source(mockMvc, source, """
                    [{"source_name":"bucket_at","name":"bucket_at","type":"timestamp"},
                     {"source_name":"hits","name":"hits","type":"long"}]""", "[\"bucket_at\"]");
            IntegrationTables.enrichment(mockMvc, enrichment, source, "bucket_at",
                    "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");

            mockMvc.perform(post("/v1/tables/" + enrichment + "/rows").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rows\":[{\"bucket_at\":\"2026-08-11T10:00:00Z\",\"label\":\"hot\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.inserted").value(1));

            mockMvc.perform(post("/v1/tables/" + enrichment + "/rows").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rows\":[{\"bucket_at\":\"2026-08-11 10:00:00.000\",\"label\":\"hot\"}]}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("validation_error"))
                    .andExpect(jsonPath("$.message").value(containsString("bucket_at")))
                    .andExpect(jsonPath("$.message").value(containsString("space separator")));
        } finally {
            // The enrichment goes first: deleting a source that still has one is a 409.
            mockMvc.perform(delete("/v1/tables/" + enrichment));
            mockMvc.perform(delete("/v1/tables/" + source));
        }
    }

    /** A nullable timestamp column still stores null. */
    @Test
    void nullTimestampIsAccepted() throws Exception {
        IntegrationTables.source(mockMvc, TABLE, """
                [{"source_name":"event_id","name":"event_id","type":"string"},
                 {"source_name":"recorded_at","name":"recorded_at","type":"timestamp"},
                 {"source_name":"seen_at","name":"seen_at","type":"timestamp","nullable":true}]""",
                "[\"recorded_at\",\"event_id\"]");

        write("[{\"event_id\":\"e1\",\"recorded_at\":\"2026-08-11T10:00:00Z\",\"seen_at\":null}]", 200);

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT event_id, recorded_at, seen_at FROM " + TABLE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].recorded_at").value("2026-08-11T10:00:00Z"))
                .andExpect(jsonPath("$.rows[0].seen_at").value(nullValue()));
    }
}
