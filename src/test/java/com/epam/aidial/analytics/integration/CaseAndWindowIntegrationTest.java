package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DSL's {@code case} and {@code window} expressions over {@code POST /v1/queries/execute} against a live
 * ClickHouse, plus the position rules and the internal node kinds that must never deserialize.
 */
class CaseAndWindowIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    private ResultActions executeDsl(String body) throws Exception {
        return mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void caseExpressionExecutes() throws Exception {
        fixture().row("chat", "p1", 5000).row("chat", "p1", 50).insert();

        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"case",
                     "when":[{"when":{"op":"gt","args":[{"type":"field","name":"total_tokens"},{"type":"value","value_type":"long","value":"1000"}]},
                              "then":{"type":"value","value_type":"string","value":"large"}}],
                     "else":{"type":"value","value_type":"string","value":"small"}},"as":"size"}],
                 "sort":[{"field":"size","dir":"asc"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].size").value("large"))
                .andExpect(jsonPath("$.rows[1].size").value("small"));
    }

    @Test
    void caseWithoutElseYieldsNullAndGroupsByItsAlias() throws Exception {
        fixture().row("chat", "p1", 100).row("embedding", "p1", 50).insert();

        executeDsl("""
                {"entity":"dial_usage_log","mode":"aggregate",
                 "select":[{"expr":{"type":"case",
                     "when":[{"when":{"op":"eq","args":[{"type":"field","name":"event_kind"},{"type":"value","value_type":"string","value":"chat"}]},
                              "then":{"type":"value","value_type":"string","value":"chat"}}]},"as":"kind"},
                           {"expr":{"type":"fn","name":"count","args":[]},"as":"n"}],
                 "group_by":["kind"],"sort":[{"field":"n","dir":"asc"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[*].kind").value(org.hamcrest.Matchers.hasItem(nullValue())));
    }

    @Test
    void windowExpressionsExecute() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(100).traceId("t1").requestTime("2026-01-01 00:00:01.000"))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(300).traceId("t2").requestTime("2026-01-01 00:00:02.000"))
                .add(r -> r.eventKind("chat").projectId("p2").totalTokens(50).traceId("t3").requestTime("2026-01-01 00:00:03.000"))
                .insert();

        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"field","name":"trace_id"}},
                           {"expr":{"type":"window","fn":{"type":"fn","name":"row_number","args":[]},
                                    "partition_by":[{"type":"field","name":"project_id"}],
                                    "order_by":[{"expr":{"type":"field","name":"request_time"},"dir":"asc"}]},"as":"rn"},
                           {"expr":{"type":"window","fn":{"type":"fn","name":"sum","args":[{"type":"field","name":"total_tokens"}]},
                                    "order_by":[{"expr":{"type":"field","name":"request_time"},"dir":"asc"}],
                                    "frame":{"unit":"rows","start":{"kind":"unbounded_preceding"},"end":{"kind":"current_row"}}},"as":"running"}],
                 "sort":[{"field":"trace_id","dir":"asc"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].rn").value(1))
                .andExpect(jsonPath("$.rows[1].rn").value(2))
                .andExpect(jsonPath("$.rows[2].rn").value(1))
                .andExpect(jsonPath("$.rows[2].running").value(450));
    }

    @Test
    void windowInTheFilterIsRejected() throws Exception {
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "filter":{"op":"lt","args":[{"type":"window","fn":{"type":"fn","name":"row_number","args":[]}},
                                             {"type":"value","value_type":"integer","value":"10"}]},
                 "select":[{"expr":{"type":"field","name":"trace_id"}}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("'select' entries only")));
    }

    @Test
    void rankingFunctionOutsideTheWindowIsRejected() throws Exception {
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"fn","name":"row_number","args":[]},"as":"rn"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("row_number")))
                .andExpect(jsonPath("$.message").value(containsString("OVER")));
    }

    @Test
    void internalExpressionKindsDoNotDeserialize() throws Exception {
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"subquery"},"as":"x"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "filter":{"op":"in_subquery","args":[]},
                 "select":[{"expr":{"type":"field","name":"trace_id"}}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }
}
