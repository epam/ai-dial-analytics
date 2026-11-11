package com.epam.aidial.analytics.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /v1/settings/sessions-source} in {@code none} security mode: storage, whole-document replace, catalog
 * validation, the strict request body, the audit trail, and that a stored value is not revalidated on read.
 * Authorization is covered in {@link SessionsSourceSettingAuthIntegrationTest}.
 *
 * <p>Requires a running Docker daemon: the column-drop case materializes a real source table.
 */
class SessionsSourceSettingIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String URL = "/v1/settings/sessions-source";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final AtomicInteger NAMES = new AtomicInteger();

    /** dial_usage_log fields: project_id string, request_time timestamp, total_price decimal, total_tokens long. */
    private static final String VALID = """
            {"base_table":"dial_usage_log","roles":{"session_id":"project_id","time":"request_time",
             "project":"project_id","title":"dial_usage_log_payload.request_body","cost":"total_price","turns":"total_tokens"}}""";

    @Autowired
    private JdbcTemplate jdbc;

    private String table;

    @BeforeEach
    void ownTheSetting() {
        jdbc.update("DELETE FROM instance_setting");
        table = "sessions_setting_" + NAMES.incrementAndGet();
    }

    @AfterEach
    void cleanUp() throws Exception {
        jdbc.update("DELETE FROM instance_setting");
        mockMvc.perform(delete("/v1/tables/" + table));
    }

    @Test
    void absentBeforeTheFirstWrite() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("setting_not_found"));
    }

    @Test
    void validSettingRoundTrips() throws Exception {
        final String written = mockMvc.perform(put(URL).contentType(JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base_table").value("dial_usage_log"))
                .andExpect(jsonPath("$.roles.session_id").value("project_id"))
                .andExpect(jsonPath("$.roles.title").value("dial_usage_log_payload.request_body"))
                .andExpect(jsonPath("$.roles.cost").value("total_price"))
                .andExpect(jsonPath("$.updated_at").isNotEmpty())
                .andExpect(jsonPath("$.updated_by").isEmpty())
                .andReturn().getResponse().getContentAsString();

        final String read = mockMvc.perform(get(URL)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(read).isEqualTo(written);
    }

    @Test
    void optionalRolesReadBackAsNullAndReplaceCarriesNothingOver() throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content(VALID)).andExpect(status().isOk());
        mockMvc.perform(put(URL).contentType(JSON)
                        .content("{\"base_table\":\"dial_usage_log\",\"roles\":{\"session_id\":\"trace_id\",\"time\":\"request_time\"}}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.session_id").value("trace_id"))
                .andExpect(jsonPath("$.roles.project").isEmpty())
                .andExpect(jsonPath("$.roles.title").isEmpty())
                .andExpect(jsonPath("$.roles.cost").isEmpty())
                .andExpect(jsonPath("$.roles.turns").isEmpty())
                .andExpect(jsonPath("$.roles.project").hasJsonPath());
    }

    @Test
    void refusedWriteLeavesTheStoredValueUnchanged() throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content(VALID)).andExpect(status().isOk());

        mockMvc.perform(put(URL).contentType(JSON)
                        .content("{\"base_table\":\"no_such_table\",\"roles\":{\"session_id\":\"a\",\"time\":\"b\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString("base_table")));

        mockMvc.perform(get(URL)).andExpect(jsonPath("$.base_table").value("dial_usage_log"));
    }

    @Test
    void eachValidationRuleIsReported() throws Exception {
        expectInvalid("{\"base_table\":\"dial_usage_log_payload\",\"roles\":{\"session_id\":\"trace_id\",\"time\":\"request_time\"}}",
                "base_table");
        expectInvalid("{\"base_table\":\"dial_usage_log\",\"roles\":{\"time\":\"request_time\"}}", "roles.session_id is required");
        expectInvalid("{\"base_table\":\"dial_usage_log\",\"roles\":{\"session_id\":null,\"time\":\"request_time\"}}",
                "roles.session_id is required");
        expectInvalid("{\"base_table\":\"dial_usage_log\"}", "roles is required");
        expectInvalid(roles("\"project\":\"no_such_field\""), "roles.project names unknown field");
        expectInvalid("{\"base_table\":\"dial_usage_log\",\"roles\":{\"session_id\":\"trace_id\",\"time\":\"project_id\"}}",
                "roles.time names field 'project_id' of type string; accepted: timestamp");
        expectInvalid(roles("\"project\":\"execution_path\""), "roles.project names array field");
        expectInvalid(roles("\"title\":\"dial_usage_log_payload.request_body_full\""), "roles.title names projection-only field");
        expectInvalid(roles("\"session_id\":\"event_id\""), "roles.session_id names field 'event_id' of type uuid");
        expectInvalid(roles("\"turns\":\"total_price\""), "roles.turns names field 'total_price' of type decimal; accepted: integer, long");
    }

    @Test
    void responseOnlyAndUnknownMembersAreRejected() throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content(VALID.substring(0, VALID.length() - 1) + ",\"updated_by\":\"someone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("updated_by")));
        mockMvc.perform(put(URL).contentType(JSON)
                        .content("{\"base_table\":\"dial_usage_log\",\"roles\":{\"session_id\":\"trace_id\",\"time\":\"request_time\",\"model\":\"x\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("model")));
        mockMvc.perform(get(URL)).andExpect(status().isNotFound());
    }

    @Test
    void writesAreAuditedAndRefusalsAreNot() throws Exception {
        final String filter = "{\"filters\":[{\"column\":\"resourceType\",\"operator\":\"eq\",\"value\":\"Setting\"},"
                + "{\"column\":\"resourceId\",\"operator\":\"eq\",\"value\":\"sessions-source\"}]}";
        final long before = feedTotal(filter);

        mockMvc.perform(put(URL).contentType(JSON).content(VALID)).andExpect(status().isOk());
        mockMvc.perform(put(URL).contentType(JSON).content("{\"base_table\":\"nope\",\"roles\":{\"session_id\":\"a\",\"time\":\"b\"}}"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(get(URL)).andExpect(status().isOk());
        mockMvc.perform(put(URL).contentType(JSON).content(VALID)).andExpect(status().isOk());

        mockMvc.perform(post("/v1/activities").contentType(JSON).content(filter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(before + 2))
                .andExpect(jsonPath("$.data[0].activityType").value("Update"))
                .andExpect(jsonPath("$.data[0].parentActivityId").doesNotExist())
                // The row is cleared outside the persistence context before each test, so this test's first
                // write is an insert, recorded as a Create whatever earlier tests left in the trail.
                .andExpect(jsonPath("$.data[1].activityType").value("Create"));
    }

    /**
     * Two first writes race to insert the row: the loser fails on the primary key and is retried as an update, so both
     * succeed and a read returns one complete body. Repeated because one round need not hit the race.
     */
    @Test
    void concurrentFirstWritesBothSucceedAndLeaveOneCompleteValue() throws Exception {
        final String other = "{\"base_table\":\"dial_usage_log\",\"roles\":{\"session_id\":\"trace_id\",\"time\":\"request_time\"}}";
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                jdbc.update("DELETE FROM instance_setting");
                final CyclicBarrier start = new CyclicBarrier(2);
                final List<Future<Integer>> writes = new ArrayList<>();
                for (final String body : List.of(VALID, other)) {
                    writes.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return mockMvc.perform(put(URL).contentType(JSON).content(body)).andReturn().getResponse().getStatus();
                    }));
                }
                for (final Future<Integer> write : writes) {
                    assertThat(write.get(30, TimeUnit.SECONDS)).isEqualTo(200);
                }

                final String read = mockMvc.perform(get(URL)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                final String sessionId = JsonPath.read(read, "$.roles.session_id");
                final Object project = JsonPath.read(read, "$.roles.project");
                if ("trace_id".equals(sessionId)) {
                    assertThat(project).as("the second body, with nothing of the first").isNull();
                } else {
                    assertThat(sessionId).isEqualTo("project_id");
                    assertThat(project).as("the first body, whole").isEqualTo("project_id");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void droppedColumnLeavesTheSettingAsWritten() throws Exception {
        IntegrationTables.source(mockMvc, table,
                "[{\"source_name\":\"sid\",\"name\":\"sid\",\"type\":\"string\"},"
                        + "{\"source_name\":\"ts\",\"name\":\"ts\",\"type\":\"timestamp\"},"
                        + "{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\"}]",
                "[\"sid\"]");
        final String body = "{\"base_table\":\"" + table + "\",\"roles\":{\"session_id\":\"sid\",\"time\":\"ts\",\"title\":\"title\"}}";
        mockMvc.perform(put(URL).contentType(JSON).content(body)).andExpect(status().isOk());

        mockMvc.perform(patch("/v1/tables/" + table + "/schema").contentType(JSON).content("{\"drop\":[\"title\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.roles.title").value("title"));
        mockMvc.perform(put(URL).contentType(JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("roles.title")))
                .andExpect(jsonPath("$.message").value(not(containsString("roles.session_id"))));
    }

    private long feedTotal(String filter) throws Exception {
        final Number total = JsonPath.read(mockMvc.perform(post("/v1/activities").contentType(JSON).content(filter))
                .andReturn().getResponse().getContentAsString(), "$.total");
        return total.longValue();
    }

    private void expectInvalid(String body, String message) throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString(message)));
    }

    /** A body on dial_usage_log with valid required roles, plus {@code extra} role members (overriding when repeated). */
    private static String roles(String extra) {
        final String required = extra.contains("\"session_id\"") ? "\"time\":\"request_time\"" : "\"session_id\":\"trace_id\",\"time\":\"request_time\"";
        return "{\"base_table\":\"dial_usage_log\",\"roles\":{" + required + "," + extra + "}}";
    }
}
