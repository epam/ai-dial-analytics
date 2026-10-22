package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The map and array accessors, and the guarded arithmetic, against a <b>real ClickHouse</b>.
 *
 * <p><b>Why this class exists rather than more unit tests.</b> The preceding change measured a rendering
 * that returned an empty result for <em>every row</em> and raised nothing:
 * {@code JSONExtractKeysAndValues} over a native {@code JSON} column on ClickHouse 26.3. A silent-empty
 * cannot be caught by review or by a test that only asserts "the query runs" — the broken predecessor
 * passed exactly that. Every accessor here reads a container column through a rendering nothing previously
 * exercised, so each is asserted on a <b>non-empty</b> result over real data, and the absent-key and
 * empty-container cases are asserted separately so a rendering that collapses the two is caught.
 *
 * <p>The map columns come from an OTLP metrics sink rather than a hand-built table: producer-defined
 * attribute maps with dotted keys are the motivating shape, and the dotted key is the specific trap —
 * {@code map_value(m, 'user.email')} read as a JSON <em>path</em> would return the empty string for every
 * row, and {@code map_contains} read the same way returns {@code false} for every row, which looks like a
 * legitimate empty result rather than a defect.
 *
 * <p>Requires a running Docker daemon.
 */
class ContainerAccessorIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SINK = "acc";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    private static final String METRICS = "otel_" + SINK + "_metrics";

    @AfterEach
    void removeSink() throws Exception {
        mockMvc.perform(delete("/v1/otel/sinks/" + SINK));
    }

    /**
     * A metrics sink carrying three data points: one with a full attribute set including a dotted key, one
     * whose attribute map lacks the key under test, and one carrying the key with an <b>empty value</b> —
     * the row that separates {@code map_contains} from {@code map_value}.
     */
    private void seed() throws Exception {
        mockMvc.perform(post("/v1/otel/sinks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + SINK + "\",\"signals\":[\"metrics\"]}"))
                .andExpect(status().isCreated());
        final String nanos = Long.toString(
                (java.time.Instant.now().toEpochMilli() - 1000) * 1_000_000L);
        mockMvc.perform(post("/v1/otel/sink/" + SINK + "/v1/metrics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceMetrics":[{
                                  "resource":{"attributes":[
                                    {"key":"service.name","value":{"stringValue":"claude-code"}}]},
                                  "scopeMetrics":[{"metrics":[
                                    {"name":"cost","sum":{"dataPoints":[
                                      {"timeUnixNano":"%s","asDouble":1.5,"attributes":[
                                        {"key":"user.email","value":{"stringValue":"a@example.com"}},
                                        {"key":"model","value":{"stringValue":"opus"}}]},
                                      {"timeUnixNano":"%s","asDouble":2.5,"attributes":[
                                        {"key":"model","value":{"stringValue":"sonnet"}}]},
                                      {"timeUnixNano":"%s","asDouble":4.0,"attributes":[
                                        {"key":"user.email","value":{"stringValue":""}},
                                        {"key":"model","value":{"stringValue":"haiku"}}]}]}}]}]}]}"""
                                .formatted(nanos, nanos, nanos)))
                .andExpect(status().isOk());
    }

    private ResultActions sql(String statement) throws Exception {
        return mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"" + statement.replace("\"", "\\\"") + "\"}"));
    }

    // --- map accessors ------------------------------------------------------------------------------

    @Test
    void mapValueReadsRealValuesAndGroupsByThem() throws Exception {
        seed();

        // Non-empty is the whole point: a rendering that silently returns '' for every row would pass an
        // assertion that only checked the query succeeds.
        sql("SELECT MAP_VALUE(point_attributes, 'model') AS m, sum(point_value) AS cost "
                + "FROM " + METRICS + " GROUP BY m ORDER BY m")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].m").value("haiku"))
                .andExpect(jsonPath("$.rows[0].cost").value(4.0))
                .andExpect(jsonPath("$.rows[1].m").value("opus"))
                .andExpect(jsonPath("$.rows[2].m").value("sonnet"));
    }

    @Test
    void mapValueMatchesDottedKeyLiterallyNotAsPath() throws Exception {
        seed();

        // The trap: read as a JSON path, 'user.email' would descend into a 'user' member that does not
        // exist and yield '' for every row — with nothing raised anywhere to notice.
        sql("SELECT MAP_VALUE(point_attributes, 'user.email') AS who FROM " + METRICS
                + " WHERE MAP_VALUE(point_attributes, 'model') = 'opus'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].who").value("a@example.com"));
    }

    @Test
    void mapValueYieldsTheEmptyStringForAnAbsentKey() throws Exception {
        seed();

        sql("SELECT MAP_VALUE(point_attributes, 'user.email') AS who FROM " + METRICS
                + " WHERE MAP_VALUE(point_attributes, 'model') = 'sonnet'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].who").value(""));
    }

    @Test
    void mapContainsSeparatesAnAbsentKeyFromAnEmptyValue() throws Exception {
        seed();

        // This is the reason map_contains earns its place. map_value reads '' for both the sonnet row
        // (no such key) and the haiku row (key present, value empty); only the presence test tells them
        // apart, and a rendering that read the dotted key as a path would answer false for all three.
        sql("SELECT MAP_VALUE(point_attributes, 'model') AS m, "
                + "MAP_CONTAINS(point_attributes, 'user.email') AS present, "
                + "MAP_VALUE(point_attributes, 'user.email') AS who "
                + "FROM " + METRICS + " ORDER BY m")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].m").value("haiku"))
                .andExpect(jsonPath("$.rows[0].present").value(true))
                .andExpect(jsonPath("$.rows[0].who").value(""))
                .andExpect(jsonPath("$.rows[1].m").value("opus"))
                .andExpect(jsonPath("$.rows[1].present").value(true))
                .andExpect(jsonPath("$.rows[2].m").value("sonnet"))
                .andExpect(jsonPath("$.rows[2].present").value(false))
                .andExpect(jsonPath("$.rows[2].who").value(""));
    }

    @Test
    void mapKeysListsTheRealKeysAndArrayLengthCountsThem() throws Exception {
        seed();

        sql("SELECT MAP_KEYS(point_attributes) AS keys, "
                + "ARRAY_LENGTH(MAP_KEYS(point_attributes)) AS n FROM " + METRICS
                + " WHERE MAP_VALUE(point_attributes, 'model') = 'sonnet'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].keys").isArray())
                .andExpect(jsonPath("$.rows[0].keys[0]").value("model"))
                .andExpect(jsonPath("$.rows[0].n").value(1));
    }

    @Test
    void arrayHasOverComputedArrayFilters() throws Exception {
        seed();

        // The nesting the positional carve-out exists for: an array-valued call in an array accessor's
        // first argument. Legal there and nowhere else.
        sql("SELECT count(*) AS n FROM " + METRICS
                + " WHERE ARRAY_HAS(MAP_KEYS(point_attributes), 'user.email')")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].n").value(2));
    }

    @Test
    void mapKeysOutsideProjectionIsRefused() throws Exception {
        seed();

        sql("SELECT MAP_KEYS(point_attributes) AS k, count(*) FROM " + METRICS + " GROUP BY k")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void booleanScalarsReadBackAsBooleansNotAsOneAndZero() throws Exception {
        truncate();
        fixture().rowWithDeployment("gpt-4o").insert();

        // ClickHouse yields UInt8 for every predicate, which reads back over REST as 1/0 unless the
        // rendering wraps it — the reason json_extract_bool carries toBool. Asserted across the whole
        // boolean family, not only the accessors added here: one catalog return type must have one
        // spelling, or a client reads `true` from one function and `1` from another in the same row.
        // Both truth values are asserted, since a rendering that answered 1/0 would fail `false` too.
        sql("SELECT ARRAY_HAS(execution_path, 'x') AS a, CONTAINS(deployment, 'z') AS c, "
                + "STARTS_WITH(deployment, 'gpt') AS s, NOT_EMPTY(deployment) AS e, "
                + "EQUALS(deployment, 'gpt-4o') AS q "
                + "FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].a").value(false))
                .andExpect(jsonPath("$.rows[0].c").value(false))
                .andExpect(jsonPath("$.rows[0].s").value(true))
                .andExpect(jsonPath("$.rows[0].e").value(true))
                .andExpect(jsonPath("$.rows[0].q").value(true));
    }

    // --- guarded arithmetic -------------------------------------------------------------------------

    @Test
    void divisionByZeroDenominatorYieldsNullNotNan() throws Exception {
        truncate();
        // sonnet: 10/0 -> null. opus: 10/4 -> 2.5. A bare ClickHouse `/` would give inf for the first,
        // which is a wrong VALUE in the envelope rather than a failure — the reason the guard is asserted
        // against real ClickHouse and not against the rendered string.
        fixture()
                .row("chat", "p1", 10)
                .row("chat", "p1", 10)
                .insert();

        sql("SELECT sum(total_tokens) / sum(completion_tokens) AS ratio FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].ratio").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void integerDivisionKeepsItsFractionalPart() throws Exception {
        truncate();
        fixture().row("chat", "p1", 5).insert();

        // Both operands are integers; intDiv would answer 2. The contract says the ratio is not truncated.
        sql("SELECT total_tokens / 2 AS half FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].half").value(2.5));
    }

    @Test
    void remainderByZeroYieldsNullRatherThanRaising() throws Exception {
        truncate();
        fixture().row("chat", "p1", 7).insert();

        // modulo's failure mode differs from div's: ClickHouse RAISES here rather than returning a wrong
        // value, so a guard dropped from modulo alone would fail this and pass every div assertion.
        sql("SELECT total_tokens % completion_tokens AS r FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].r").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void remainderKeepsIntegerTypeAndTheLeftOperandsSign() throws Exception {
        truncate();
        fixture().row("chat", "p1", 7).insert();

        sql("SELECT total_tokens % 3 AS r, -total_tokens % 3 AS neg FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].r").value(1))
                .andExpect(jsonPath("$.rows[0].neg").value(-1));
    }

    @Test
    void splitStringTrimsItsElementsAndNestsInAnAccessor() throws Exception {
        truncate();
        // A comma-delimited value is the conversation_insights.topics shape: a multi-value column that is
        // not typed as an array, so the split is what makes its parts reachable.
        fixture().rowWithDeployment("a, b ,c").insert();

        sql("SELECT SPLIT_STRING(deployment, ',') AS parts, "
                + "ARRAY_LENGTH(SPLIT_STRING(deployment, ',')) AS n FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].parts").isArray())
                .andExpect(jsonPath("$.rows[0].parts[0]").value("a"))
                // The trim is the assertion that matters: without it this is " b " and a GROUP BY on the
                // parts buckets the same topic twice.
                .andExpect(jsonPath("$.rows[0].parts[1]").value("b"))
                .andExpect(jsonPath("$.rows[0].parts[2]").value("c"))
                .andExpect(jsonPath("$.rows[0].n").value(3));
    }

    @Test
    void emptyTextSplitsToEmptyArrayNotOneElementBlank() throws Exception {
        truncate();
        fixture().rowWithDeployment("").insert();

        sql("SELECT ARRAY_LENGTH(SPLIT_STRING(deployment, ',')) AS n FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].n").value(0));
    }

    @Test
    void isoStringTimeBoundExecutesEndToEnd() throws Exception {
        truncate();
        fixture().row("chat", "p1", 42).insert();

        // The papercut every BI client hits: a bare string time bound. Asserted end-to-end because the
        // coercion has to survive translation AND the ClickHouse comparison, not just parse.
        sql("SELECT count(*) AS n FROM dial_usage_log WHERE request_time >= '2020-01-01'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].n").value(1));
    }
}
