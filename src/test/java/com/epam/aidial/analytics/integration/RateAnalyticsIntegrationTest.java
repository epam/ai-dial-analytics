package com.epam.aidial.analytics.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the {@code rate_analytics} SOURCE table against a real ClickHouse + PostgreSQL.
 * Verifies the ClickHouse V2 migration provisioned the table (a row query executes against it), the
 * catalog exposes exactly its 8 analytics columns plus the {@code event_id} identity column, the
 * physical-but-hidden columns ({@code trace_id}, {@code core_span_id}, {@code _ingested_at}) are
 * invisible to the query API, that real rate rows — a signed {@code Int8} {@code rate} and an
 * AES-encrypted {@code comment} — store and read back correctly through the structured query path,
 * and that the {@code event_id} added by ClickHouse migration V5 identifies rows the way the
 * incremental scan requires (unique per distinct row, stable across a re-ingest).
 */
class RateAnalyticsIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";

    @Test
    void exposesTheAnalyticsColumnsAndTheIdentityColumn() throws Exception {
        mockMvc.perform(get("/v1/queries/entities/schema/rate_analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("rate_analytics"))
                .andExpect(jsonPath("$.fields", hasSize(10)))
                .andExpect(jsonPath("$.fields[*].name", containsInAnyOrder(
                        "event_id", "response_id", "rate", "rate_raw", "comment", "deployment",
                        "user_hash", "chat_id", "project_id", "request_time")));
    }

    @Test
    void rowQueryExecutesAgainstTheProvisionedTable() throws Exception {
        // The table exists (V2 ClickHouse migration) and its columns resolve (V2 catalog seed), so a row
        // query returns 200 with a rows array — empty here since no rate rows were seeded.
        String body = """
                {
                  "entity": "rate_analytics",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "field", "name": "response_id" } },
                    { "expr": { "type": "field", "name": "rate" } },
                    { "expr": { "type": "field", "name": "comment" } }
                  ]
                }
                """;
        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").exists());
    }

    @Test
    void hiddenPhysicalColumnIsNotSelectable() throws Exception {
        // trace_id is a physical sort-key column but is NOT registered in the catalog, so referencing it
        // is rejected as an unknown field rather than leaking through the query API.
        String body = """
                {
                  "entity": "rate_analytics",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "trace_id" } } ]
                }
                """;
        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void storesAndReadsBackRealRateRows() throws Exception {
        // Seed two real rate events straight into the provisioned table: a positive rating with a
        // comment, and a negative rating with no comment. This exercises what only surfaces against real
        // ClickHouse — the signed Int8 `rate` and the AES_256_GCM_SIV `comment` codec — then reads them
        // back through the production query path (catalog -> executor -> ClickHouse).
        execute("TRUNCATE TABLE rate_analytics");
        execute("INSERT INTO rate_analytics "
                + "(request_time, project_id, trace_id, core_span_id, deployment, user_hash, chat_id, "
                + "response_id, rate, comment) VALUES "
                + "('2026-01-01 00:00:00.000', 'p1', 't1', 's1', 'gpt-4o', 'u1', 'c1', 'resp_1', 1, 'great answer'),"
                + "('2026-01-01 00:00:01.000', 'p1', 't2', 's2', 'gpt-4o', 'u1', 'c1', 'resp_2', -1, NULL)");

        String body = """
                {
                  "entity": "rate_analytics",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "field", "name": "response_id" } },
                    { "expr": { "type": "field", "name": "rate" } },
                    { "expr": { "type": "field", "name": "comment" } }
                  ],
                  "sort": [ { "field": "rate", "dir": "desc" } ]
                }
                """;

        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                // rate desc -> the +1 row first, its encrypted comment decrypted on read.
                .andExpect(jsonPath("$.rows[0].response_id").value("resp_1"))
                .andExpect(jsonPath("$.rows[0].rate").value(1))
                .andExpect(jsonPath("$.rows[0].comment").value("great answer"))
                // the -1 row with a null comment round-trips as JSON null.
                .andExpect(jsonPath("$.rows[1].response_id").value("resp_2"))
                .andExpect(jsonPath("$.rows[1].rate").value(-1))
                .andExpect(jsonPath("$.rows[1].comment").value(nullValue()));
    }

    @Test
    void rawRateSeparatesBooleanFalseFromNumericZeroAndReadsNullForHistory() throws Exception {
        // The whole point of rate_raw, against real ClickHouse: a thumbs-down submitted as boolean
        // `false` and a rating cleared to numeric 0 both store rate = 0, and only the raw text tells
        // them apart. The third row supplies no rate_raw at all, standing in for every row ingested
        // before the column existed — it must read null rather than an inferred value, because that
        // inference is exactly the ambiguity the column removes.
        //
        // Also the only place the physical LowCardinality(Nullable(String)) declaration is exercised:
        // SchemaValidator reconciles the mapping against it at context startup, so a wrapper it could
        // not see through would fail every test in this class rather than just this one.
        execute("TRUNCATE TABLE rate_analytics");
        execute("INSERT INTO rate_analytics "
                + "(request_time, project_id, trace_id, core_span_id, deployment, user_hash, chat_id, "
                + "response_id, rate, rate_raw) VALUES "
                + "('2026-01-01 00:00:00.000', 'p1', 't1', 's1', 'gpt-4o', 'u1', 'c1', 'resp_1', 0, 'false'),"
                + "('2026-01-01 00:00:01.000', 'p1', 't2', 's2', 'gpt-4o', 'u1', 'c1', 'resp_2', 0, '0')");
        execute("INSERT INTO rate_analytics "
                + "(request_time, project_id, trace_id, core_span_id, deployment, user_hash, chat_id, "
                + "response_id, rate) VALUES "
                + "('2026-01-01 00:00:02.000', 'p1', 't3', 's3', 'gpt-4o', 'u1', 'c1', 'resp_3', 0)");

        String body = """
                {
                  "entity": "rate_analytics",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "field", "name": "response_id" } },
                    { "expr": { "type": "field", "name": "rate" } },
                    { "expr": { "type": "field", "name": "rate_raw" } }
                  ],
                  "sort": [ { "field": "response_id", "dir": "asc" } ]
                }
                """;

        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(3)))
                // All three are indistinguishable through `rate` alone...
                .andExpect(jsonPath("$.rows[0].rate").value(0))
                .andExpect(jsonPath("$.rows[1].rate").value(0))
                .andExpect(jsonPath("$.rows[2].rate").value(0))
                // ...and separated by rate_raw, which also says where it cannot answer.
                .andExpect(jsonPath("$.rows[0].rate_raw").value("false"))
                .andExpect(jsonPath("$.rows[1].rate_raw").value("0"))
                .andExpect(jsonPath("$.rows[2].rate_raw").value(nullValue()));
    }

    @Test
    void exactNegativeCountExcludesClearedAndUnattributedRatings() {
        // The published counting expression, run for real: rate_raw = 'false' OR (rate_raw IS NOT NULL
        // AND rate < 0). The numeric half reads `rate`, not rate_raw, because rate_raw is text and has
        // no ordering — matching it against '-1' would miss the -2 row seeded below.
        execute("TRUNCATE TABLE rate_analytics");
        execute("INSERT INTO rate_analytics "
                + "(request_time, project_id, trace_id, core_span_id, deployment, user_hash, chat_id, "
                + "response_id, rate, rate_raw) VALUES "
                + "('2026-01-01 00:00:00.000', 'p1', 't1', 's1', 'gpt-4o', 'u1', 'c1', 'resp_1', 0, 'false'),"
                + "('2026-01-01 00:00:01.000', 'p1', 't2', 's2', 'gpt-4o', 'u1', 'c1', 'resp_2', -1, '-1'),"
                + "('2026-01-01 00:00:02.000', 'p1', 't3', 's3', 'gpt-4o', 'u1', 'c1', 'resp_3', -2, '-2'),"
                + "('2026-01-01 00:00:03.000', 'p1', 't4', 's4', 'gpt-4o', 'u1', 'c1', 'resp_4', 0, '0'),"
                + "('2026-01-01 00:00:04.000', 'p1', 't5', 's5', 'gpt-4o', 'u1', 'c1', 'resp_5', 1, 'true')");
        // A pre-column row: negative, but unattributable, so it is excluded rather than guessed at.
        execute("INSERT INTO rate_analytics "
                + "(request_time, project_id, trace_id, core_span_id, deployment, user_hash, chat_id, "
                + "response_id, rate) VALUES "
                + "('2026-01-01 00:00:05.000', 'p1', 't6', 's6', 'gpt-4o', 'u1', 'c1', 'resp_6', -1)");

        assertThat(queryScalar("SELECT count(*) AS n FROM rate_analytics FINAL "
                + "WHERE rate_raw = 'false' OR (rate_raw IS NOT NULL AND rate < 0)", "n"))
                .isEqualTo("3");
        // And the unattributed population is nameable, which is what makes the count publishable.
        assertThat(queryScalar("SELECT count(*) AS n FROM rate_analytics FINAL WHERE rate_raw IS NULL", "n"))
                .isEqualTo("1");
    }

    @Test
    void eventIdIdentifiesEveryDistinctRowAndSurvivesReIngest() throws Exception {
        // No row here supplies event_id — exactly like the ingest path, which never writes that column
        // and lets the V5 DEFAULT expression derive it. Two of the rows share (trace_id, core_span_id)
        // and two more have neither, the cases a trace/span-only hash would collapse onto one identity
        // and make the keyset scan skip rows.
        execute("TRUNCATE TABLE rate_analytics");
        execute("INSERT INTO rate_analytics "
                + "(request_time, project_id, trace_id, core_span_id, deployment, user_hash, chat_id, "
                + "response_id, rate, comment) VALUES "
                + "('2026-01-01 00:00:00.000', 'p1', 't1', 's1', 'gpt-4o', 'u1', 'c1', 'resp_1', 1, NULL),"
                + "('2026-01-01 00:00:05.000', 'p1', 't1', 's1', 'gpt-4o', 'u1', 'c1', 'resp_1', -1, NULL),"
                + "('2026-01-01 00:00:06.000', 'p1', '',   '',   'gpt-4o', 'u1', 'c1', 'resp_2', 1, NULL),"
                + "('2026-01-01 00:00:07.000', 'p1', '',   '',   'gpt-4o', 'u1', 'c1', 'resp_3', 1, NULL)");

        List<String> ids = eventIdsByRequestTime();
        assertThat(ids).hasSize(4).doesNotHaveDuplicates();

        // Re-ingesting an identical log line collapses on the sort key (reads apply final=1); because
        // event_id is derived from that same key, the surviving row keeps the identity any enrichment
        // was already keyed on, instead of orphaning it behind a new one.
        execute("INSERT INTO rate_analytics "
                + "(request_time, project_id, trace_id, core_span_id, deployment, user_hash, chat_id, "
                + "response_id, rate, comment) VALUES "
                + "('2026-01-01 00:00:06.000', 'p1', '', '', 'gpt-4o', 'u1', 'c1', 'resp_2', 1, NULL)");

        assertThat(eventIdsByRequestTime()).isEqualTo(ids);
    }

    /** Every row's {@code event_id}, in {@code request_time} order, read through the query path. */
    private List<String> eventIdsByRequestTime() throws Exception {
        String body = """
                {
                  "entity": "rate_analytics",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "event_id" } } ],
                  "sort": [ { "field": "request_time", "dir": "asc" } ]
                }
                """;
        String content = mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(content, "$.rows[*].event_id");
    }
}
