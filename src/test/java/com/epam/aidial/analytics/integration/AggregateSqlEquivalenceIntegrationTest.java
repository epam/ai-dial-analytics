package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A form-declared aggregate pipeline and its {@code INSERT … SELECT} twin, over the same input and into two targets
 * of identical schema, must write identical rows after every refresh. The pair covers what the two front ends
 * render differently: an enrichment-column key (form: the column plus a filter; SQL: a {@code coalesce} plus the
 * same WHERE), a day bucket the SQL writes with no time zone, and conditional measures (form {@code where}; SQL
 * {@code CASE WHEN} inside the aggregate).
 *
 * <p>Requires a running Docker daemon.
 */
class AggregateSqlEquivalenceIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PIPELINES = "/v1/pipelines";
    private static final String SOURCE = "twin_events";
    private static final String ENRICHMENT = "twin_owner";
    private static final String OWNER = ENRICHMENT + ".owner";
    private static final String FORM_TARGET = "twin_form";
    private static final String SQL_TARGET = "twin_sql";
    private static final String FORM_PIPELINE = "twin_form_rollup";
    private static final String SQL_PIPELINE = "twin_sql_rollup";
    private static final String TRIGGER = "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}";

    private static final String TARGET_COLUMNS = """
            [{"source_name":"owner","name":"owner","type":"string"},
             {"source_name":"day","name":"day","type":"timestamp"},
             {"source_name":"events","name":"events","type":"long"},
             {"source_name":"total","name":"total","type":"long","nullable":true},
             {"source_name":"big_total","name":"big_total","type":"long","nullable":true},
             {"source_name":"big_events","name":"big_events","type":"long"}]""";

    private static final String FORM = """
            {"name":"%s","kind":"aggregate","target":"%s","inputs":["%s"],
             "filter":"length(\\"%s\\") > 0",
             "group_by":[{"column":"%s","as":"owner"},{"trunc":{"column":"at","unit":"day"},"as":"day"}],
             "measures":[{"name":"events","fn":"count"},
                         {"name":"total","fn":"sum","column":"score"},
                         {"name":"big_total","fn":"sum","column":"score","where":"score >= 10"},
                         {"name":"big_events","fn":"count","where":"score >= 10"}],
             %s}""".formatted(FORM_PIPELINE, FORM_TARGET, SOURCE, OWNER, OWNER, TRIGGER);

    private static final String SQL = "INSERT INTO " + SQL_TARGET + " (owner, day, events, total, big_total, big_events)"
            + " SELECT coalesce(\"" + OWNER + "\", '') AS owner, date_trunc('day', at), count(*), sum(score),"
            + " sum(CASE WHEN score >= 10 THEN score END), count(CASE WHEN score >= 10 THEN 1 END)"
            + " FROM " + SOURCE + " WHERE length(\"" + OWNER + "\") > 0 GROUP BY 1, 2";

    @BeforeEach
    void createTables() throws Exception {
        dropAll();
        IntegrationTables.source(mockMvc, SOURCE, """
                [{"source_name":"event_id","name":"event_id","type":"string"},
                 {"source_name":"score","name":"score","type":"long"},
                 {"source_name":"at","name":"at","type":"timestamp"}]""",
                "[\"event_id\"]", "event_id", "at");
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"owner\",\"name\":\"owner\",\"type\":\"string\",\"nullable\":true}]");
        IntegrationTables.upsertSource(mockMvc, FORM_TARGET, TARGET_COLUMNS, "[\"owner\",\"day\"]");
        IntegrationTables.upsertSource(mockMvc, SQL_TARGET, TARGET_COLUMNS, "[\"owner\",\"day\"]");
    }

    @AfterEach
    void dropAll() throws Exception {
        mockMvc.perform(delete(PIPELINES + "/" + FORM_PIPELINE));
        mockMvc.perform(delete(PIPELINES + "/" + SQL_PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + FORM_TARGET));
        mockMvc.perform(delete("/v1/tables/" + SQL_TARGET));
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
        mockMvc.perform(delete("/v1/tables/" + SOURCE));
    }

    private void seed(String eventId, String day, long score, int minute, String owner) {
        execute("INSERT INTO " + SOURCE + " (event_id, score, at) VALUES ('" + eventId + "', " + score
                + ", '" + day + " 23:" + String.format("%02d", minute) + ":00.000')");
        if (owner != null) {
            execute("INSERT INTO " + ENRICHMENT + " (event_id, owner) VALUES ('" + eventId + "', '" + owner + "')");
        }
    }

    private void createAndArm() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(FORM)).andExpect(status().isCreated());
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + SQL_PIPELINE
                        + "\",\"kind\":\"aggregate\",\"target\":\"" + SQL_TARGET + "\",\"sql\":\"" + SQL.replace("\"", "\\\"")
                        + "\"," + TRIGGER + "}"))
                .andExpect(status().isCreated());
        for (String name : new String[] {FORM_PIPELINE, SQL_PIPELINE}) {
            mockMvc.perform(patch(PIPELINES + "/" + name).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isOk());
        }
    }

    /** Refreshes both pipelines and returns {form rows_written, sql rows_written}. */
    private String refreshBoth() throws Exception {
        final StringBuilder written = new StringBuilder();
        for (String name : new String[] {FORM_PIPELINE, SQL_PIPELINE}) {
            final String body = mockMvc.perform(post(PIPELINES + "/" + name + "/refresh"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            written.append(body.replaceAll(".*\"rows_written\":(\\d+).*", "$1")).append(';');
        }
        return written.toString();
    }

    private String rows(String target) {
        return queryScalar("SELECT toString(groupArray((owner, d, events, total, big_total, big_events))) AS r FROM ("
                + "SELECT owner, formatDateTime(day, '%F %T', 'UTC') AS d, events, total, big_total, big_events FROM "
                + target + " FINAL ORDER BY owner, d)", "r");
    }

    @Test
    void formPipelineAndItsSqlTwinWriteIdenticalRows() throws Exception {
        createAndArm();
        // 23:xx UTC: a bucket rendered in any zone east of UTC would put these on the next day.
        seed("e1", "2026-01-01", 10, 1, "ada");
        seed("e2", "2026-01-01", 3, 2, "ada");
        seed("e3", "2026-01-02", 4, 3, "grace");
        seed("e4", "2026-01-02", 50, 4, null);
        assertThat(refreshBoth()).isEqualTo("2;2;");
        assertThat(rows(SQL_TARGET)).isEqualTo(rows(FORM_TARGET))
                .isEqualTo("[('ada','2026-01-01 00:00:00',2,13,10,1),('grace','2026-01-02 00:00:00',1,4,NULL,0)]");

        // A late row into a written bucket, a new bucket, a new owner, and an unenriched row.
        seed("e5", "2026-01-02", 20, 5, "grace");
        seed("e6", "2026-01-03", 1, 6, "ada");
        seed("e7", "2026-01-03", 8, 7, "linus");
        seed("e8", "2026-01-01", 70, 8, null);
        assertThat(refreshBoth()).isEqualTo("3;3;");
        final String afterAppend = rows(FORM_TARGET);
        assertThat(rows(SQL_TARGET)).isEqualTo(afterAppend).contains("('grace','2026-01-02 00:00:00',2,24,20,1)");

        assertThat(refreshBoth()).isEqualTo("0;0;");
        assertThat(rows(SQL_TARGET)).isEqualTo(rows(FORM_TARGET)).isEqualTo(afterAppend);
    }
}
