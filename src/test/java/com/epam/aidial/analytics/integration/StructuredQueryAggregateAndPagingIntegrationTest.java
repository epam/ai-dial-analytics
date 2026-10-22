package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of aggregation, grouping, HAVING, sort variants, query-level distinct, and
 * pagination for {@code POST /v1/queries/execute} beyond the core in {@link StructuredQueryExecutionIntegrationTest}.
 * Each test owns its data via {@link #cleanSlate()} and asserts the values real ClickHouse computes.
 */
class StructuredQueryAggregateAndPagingIntegrationTest extends AbstractClickHouseIntegrationTest {

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    private void execute(String body, ResultMatcher... matchers) throws Exception {
        final ResultActions actions =
                mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON).content(body));
        for (final ResultMatcher matcher : matchers) {
            actions.andExpect(matcher);
        }
    }

    // --- aggregates ---------------------------------------------------------------------------------

    @Test
    void avgMinMaxAggregates() throws Exception {
        // chat: 100 & 300 -> avg 200, min 100, max 300; embedding: 50 -> avg 50, min 50, max 50.
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 300)
                .row("embedding", "p1", 50)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": ["event_kind"],
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "fn", "name": "avg", "args": [ { "type": "field", "name": "total_tokens" } ] }, "as": "avg_tokens" },
                    { "expr": { "type": "fn", "name": "min", "args": [ { "type": "field", "name": "total_tokens" } ] }, "as": "min_tokens" },
                    { "expr": { "type": "fn", "name": "max", "args": [ { "type": "field", "name": "total_tokens" } ] }, "as": "max_tokens" }
                  ],
                  "sort": [ { "field": "event_kind", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].event_kind").value("chat"),
                jsonPath("$.rows[0].avg_tokens").value(200.0),
                jsonPath("$.rows[0].min_tokens").value(100),
                jsonPath("$.rows[0].max_tokens").value(300),
                jsonPath("$.rows[1].event_kind").value("embedding"),
                jsonPath("$.rows[1].avg_tokens").value(50.0));
    }

    @Test
    void countDistinct() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .row("chat", "p2", 300)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "select": [
                    { "expr": { "type": "fn", "name": "count", "distinct": true, "args": [ { "type": "field", "name": "project_id" } ] }, "as": "distinct_projects" }
                  ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].distinct_projects").value(2));
    }

    @Test
    void sumDistinct() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "select": [
                    { "expr": { "type": "fn", "name": "sum", "distinct": true, "args": [ { "type": "field", "name": "total_tokens" } ] }, "as": "distinct_sum" }
                  ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].distinct_sum").value(300));
    }

    // --- grouping -----------------------------------------------------------------------------------

    @Test
    void multiKeyGroupBy() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .row("chat", "p2", 300)
                .row("embedding", "p1", 50)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": ["event_kind", "project_id"],
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "field", "name": "project_id" } },
                    { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "c" }
                  ],
                  "sort": [ { "field": "event_kind", "dir": "asc" }, { "field": "project_id", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(3)),
                jsonPath("$.rows[0].event_kind").value("chat"),
                jsonPath("$.rows[0].project_id").value("p1"),
                jsonPath("$.rows[0].c").value(2),
                jsonPath("$.rows[1].project_id").value("p2"),
                jsonPath("$.rows[1].c").value(1),
                jsonPath("$.rows[2].event_kind").value("embedding"));
    }

    @Test
    void groupByAliasedExpression() throws Exception {
        // group by length(project_id): 'p1'/'p2' -> 2, 'proj' -> 4.
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p2", 200)
                .row("chat", "proj", 300)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": ["plen"],
                  "select": [
                    { "expr": { "type": "fn", "name": "length", "args": [ { "type": "field", "name": "project_id" } ] }, "as": "plen" },
                    { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "c" }
                  ],
                  "sort": [ { "field": "plen", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].plen").value(2),
                jsonPath("$.rows[0].c").value(2),
                jsonPath("$.rows[1].plen").value(4),
                jsonPath("$.rows[1].c").value(1));
    }

    @Test
    void havingFiltersGroups() throws Exception {
        // chat sums to 400, embedding to 50; HAVING token_sum >= 250 keeps chat only.
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 300)
                .row("embedding", "p1", 50)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": ["event_kind"],
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "fn", "name": "sum", "args": [ { "type": "field", "name": "total_tokens" } ] }, "as": "token_sum" }
                  ],
                  "having": { "op": "ge", "args": [ { "type": "field", "name": "token_sum" }, { "type": "value", "value_type": "long", "value": "250" } ] }
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].event_kind").value("chat"),
                jsonPath("$.rows[0].token_sum").value(400));
    }

    // --- sort ---------------------------------------------------------------------------------------

    @Test
    void descendingSort() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 300)
                .row("chat", "p1", 200)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "total_tokens" } } ],
                  "sort": [ { "field": "total_tokens", "dir": "desc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(3)),
                jsonPath("$.rows[0].total_tokens").value(300),
                jsonPath("$.rows[1].total_tokens").value(200),
                jsonPath("$.rows[2].total_tokens").value(100));
    }

    @Test
    void multiKeySort() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 300)
                .row("embedding", "p1", 50)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "field", "name": "total_tokens" } }
                  ],
                  "sort": [ { "field": "event_kind", "dir": "asc" }, { "field": "total_tokens", "dir": "desc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(3)),
                jsonPath("$.rows[0].event_kind").value("chat"),
                jsonPath("$.rows[0].total_tokens").value(300),
                jsonPath("$.rows[1].total_tokens").value(100),
                jsonPath("$.rows[2].event_kind").value("embedding"));
    }

    @Test
    void sortByAggregateAlias() throws Exception {
        // token_sum: completion 500, chat 400, embedding 50 -> desc order.
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 300)
                .row("embedding", "p1", 50)
                .row("completion", "p2", 500)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": ["event_kind"],
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "fn", "name": "sum", "args": [ { "type": "field", "name": "total_tokens" } ] }, "as": "token_sum" }
                  ],
                  "sort": [ { "field": "token_sum", "dir": "desc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(3)),
                jsonPath("$.rows[0].event_kind").value("completion"),
                jsonPath("$.rows[1].event_kind").value("chat"),
                jsonPath("$.rows[2].event_kind").value("embedding"));
    }

    // --- paging & distinct --------------------------------------------------------------------------

    @Test
    void offsetSkipsRows() throws Exception {
        // ascending 50,100,200,300,500; offset 2 limit 2 -> 200,300.
        fixture()
                .row("embedding", "p1", 50)
                .row("chat", "p1", 100)
                .row("chat", "p2", 200)
                .row("chat", "p1", 300)
                .row("completion", "p2", 500)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "total_tokens" } } ],
                  "sort": [ { "field": "total_tokens", "dir": "asc" } ],
                  "page": { "type": "offset", "offset": 2, "limit": 2, "include_total": true }
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].total_tokens").value(200),
                jsonPath("$.rows[1].total_tokens").value(300),
                jsonPath("$.totalCount").value(5));
    }

    @Test
    void includeTotalFalseOmitsTotalCount() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .row("chat", "p1", 300)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "total_tokens" } } ],
                  "sort": [ { "field": "total_tokens", "dir": "asc" } ],
                  "page": { "type": "offset", "offset": 0, "limit": 2, "include_total": false }
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                // include_total=false -> the service returns a null totalCount, which the mapper omits.
                jsonPath("$.totalCount").doesNotExist());
    }

    @Test
    void queryLevelDistinct() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .row("chat", "p2", 300)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "distinct": true,
                  "select": [ { "expr": { "type": "field", "name": "project_id" } } ],
                  "sort": [ { "field": "project_id", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].project_id").value("p1"),
                jsonPath("$.rows[1].project_id").value("p2"));
    }

    @Test
    void queryLevelDistinctOverAnExpressionProjection() throws Exception {
        // The flag applies to the projected OUTPUT, not only to a bare column — the case the spec
        // requirement added by this change states and that nothing previously covered.
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .row("embedding", "p2", 300)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "distinct": true,
                  "select": [ { "expr": { "type": "fn", "name": "upper", "args": [
                      { "type": "field", "name": "event_kind" } ] }, "as": "k" } ],
                  "sort": [ { "field": "k", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].k").value("CHAT"),
                jsonPath("$.rows[1].k").value("EMBEDDING"));
    }

    @Test
    void queryLevelDistinctAppliesBeforeThePageWindow() throws Exception {
        // Deduplication before paging is what makes a page carry the requested number of DISTINCT rows;
        // applying it after would return a short page.
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .row("embedding", "p2", 300)
                .row("rate", "p3", 400)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "distinct": true,
                  "select": [ { "expr": { "type": "field", "name": "event_kind" } } ],
                  "sort": [ { "field": "event_kind", "dir": "asc" } ],
                  "page": { "type": "offset", "limit": 2, "offset": 0 }
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].event_kind").value("chat"),
                jsonPath("$.rows[1].event_kind").value("embedding"));
    }

    @Test
    void queryLevelDistinctOverContainerColumnExecutesButDoesNotTranslate() throws Exception {
        // The projection-only rule for array/map/object belongs to the SQL SUBSET, not to the DSL: the
        // DSL can project such a column, and deduplicating whole rows over a projected one is well
        // defined, so execution runs it. Only the rendered-SQL surface has no way to express it.
        fixture().row("chat", "p1", 100).insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "distinct": true,
                  "select": [ { "expr": { "type": "field", "name": "execution_path" } } ]
                }
                """;

        execute(body, status().isOk());

        mockMvc.perform(post("/v1/queries/translate")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    // --- optional aliases / derived output names -----------------------------------------------------

    @Test
    void aggregateWithoutAliasesKeysRowsByDerivedNames() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 300)
                .row("embedding", "p1", 50)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": ["event_kind"],
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "fn", "name": "count", "args": [] } },
                    { "expr": { "type": "fn", "name": "sum", "args": [ { "type": "field", "name": "total_tokens" } ] } }
                  ],
                  "sort": [ { "field": "count", "dir": "desc" } ]
                }
                """;

        // No `as` anywhere: the keys are the PostgreSQL-shaped defaults, and `sort` addresses one of them.
        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].event_kind").value("chat"),
                jsonPath("$.rows[0].count").value(2),
                jsonPath("$.rows[0].sum").value(400),
                jsonPath("$.rows[1].event_kind").value("embedding"),
                jsonPath("$.rows[1].count").value(1),
                jsonPath("$.rows[1].sum").value(50));
    }

    @Test
    void havingFiltersOnDerivedName() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 300)
                .row("embedding", "p1", 50)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": ["event_kind"],
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "fn", "name": "count", "args": [] } }
                  ],
                  "having": {
                    "op": "gt",
                    "args": [
                      { "type": "field", "name": "count" },
                      { "type": "value", "value_type": "integer", "value": "1" }
                    ]
                  }
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].event_kind").value("chat"),
                jsonPath("$.rows[0].count").value(2));
    }

    @Test
    void collidingDerivedNamesAreSuffixed() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p2", 300)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "select": [
                    { "expr": { "type": "fn", "name": "count", "args": [] } },
                    { "expr": { "type": "fn", "name": "count", "distinct": true, "args": [ { "type": "field", "name": "project_id" } ] } }
                  ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].count").value(2),
                jsonPath("$.rows[0].count_1").value(2));
    }
}
