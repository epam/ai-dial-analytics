package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the precondition the {@code ReplacingMergeTree(_updated_at)} version contract rests on: one row
 * write is one server-side {@code INSERT}, so each carries its own evaluation of the version column's
 * {@code DEFAULT now64(3)} and the engine has an ordering to collapse duplicates by. From ClickHouse 26.2
 * the server defaults {@code async_insert} to {@code 1}, which coalesces concurrent inserts into one flush
 * that evaluates {@code now64(3)} once; the writes then tie, and a tie leaves the engine free to keep any
 * of them. Both row-inserting clients therefore pin {@code async_insert = 0}
 * ({@code ClickHouseWriterClient.ASYNC_INSERT}), and both are covered here — the catalog write path
 * (HTTP, genuinely concurrent at runtime) and the ingest writer.
 *
 * <p>This is the case the rest of the suite structurally cannot reach: every other test that exercises
 * deduplication supplies the version column explicitly (see {@code FinalDedupIntegrationTest}), so it
 * proves the collapse but never the stamping — and the stamp is a server-side default no client supplies.
 *
 * <p>The pin is asserted by asking the server what it did — {@code system.asynchronous_insert_log} holds
 * one row per insert the buffer accepted, and {@code SYSTEM FLUSH LOGS} both flushes and creates it —
 * rather than by measuring stamp distinctness, which is the more direct statement of the contract but not
 * a test: these writes complete inside one millisecond locally, so they share a stamp on the synchronous
 * path too. Removing either pin turns its assertion from {@code 0} to {@code WRITES}, and nothing else in
 * the suite changes colour.
 *
 * <p>Requires a running Docker daemon.
 */
class RowWriteVersionMonotonicityIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String TABLE = "version_probe";
    private static final String ROWS = "/v1/tables/" + TABLE + "/rows";
    private static final String COLUMNS = """
            [{"source_name":"widget_id","name":"widget_id","type":"string"},
             {"source_name":"label","name":"label","type":"string"}]""";

    /** Separate writes, so each is its own {@code INSERT} rather than one batch of several rows. */
    private static final int WRITES = 5;

    /**
     * The ingest writer, exercised directly on the probe table. Which table it writes is irrelevant to
     * what is asserted — the subject is the client's own {@code async_insert} setting — and using the
     * probe table keeps the test off {@code dial_usage_log}'s column contract.
     */
    @Autowired
    private UsageLogWriter usageLogWriter;

    @BeforeEach
    void cleanSlate() throws Exception {
        truncate();
        IntegrationTables.upsertSource(mockMvc, TABLE, COLUMNS, "[\"widget_id\"]");
    }

    @AfterEach
    void dropUserTables() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + TABLE));
    }

    @Test
    void catalogRowWritesAreNotRoutedThroughTheServerAsyncInsertBuffer() throws Exception {
        for (int i = 1; i <= WRITES; i++) {
            mockMvc.perform(post(ROWS).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"label-" + i + "\"}]}"))
                    .andExpect(status().isOk());
        }

        assertThat(bufferedInserts()).isEqualTo("0");
    }

    @Test
    void usageLogWritesAreNotRoutedThroughTheServerAsyncInsertBuffer() {
        for (int i = 1; i <= WRITES; i++) {
            usageLogWriter.insert(TABLE, List.of(Map.of("widget_id", "w1", "label", "label-" + i)));
        }

        assertThat(bufferedInserts()).isEqualTo("0");
    }

    /** How many inserts into the probe table the server's async-insert buffer accepted. */
    private String bufferedInserts() {
        execute("SYSTEM FLUSH LOGS");
        return queryScalar("SELECT count() AS buffered FROM system.asynchronous_insert_log"
                + " WHERE table = '" + TABLE + "'", "buffered");
    }
}
