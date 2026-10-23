package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.sync.inline.InlineFilesMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link MappingEngine} against the shipped default {@code dial_usage_log} mapping (the
 * events column structure): dotted-path resolution, each extraction kind, {@code $sourceFile}
 * injection, allowlist semantics (undeclared fields excluded), the configured-default fallback,
 * null-on-absent for nullable columns, exact-decimal cost passthrough, numeric {@code response_status},
 * and the calculated (derived) columns.
 *
 * <p>Also exercises the {@code dial_usage_log_payload} mapping, which took over the three heavy/PII
 * body columns — including the merged {@code assembled_response}, stored verbatim and never validated
 * on shape. The claim and header documents stayed on the parent. The two mappings run over the same
 * record and are asserted against each other where they must agree: the grain key, and the fact that
 * each column belongs to exactly one of them.
 */
class MappingEngineTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final MappingEngine engine =
            new MappingEngine(loadDefaultMapping(), new TransformRegistry(), new DeriverRegistry(jsonMapper));

    private static final String SAMPLE = """
            {"chat":{"id":"c1"},"project":{"id":"statgpt"},
            "user":{"id":"u1","title":"Alice"},
            "token_usage":{"completion_tokens":3,"prompt_tokens":5,"total_tokens":8,
            "price":0.0012,"deployment_price":0.0034,
            "prompt_tokens_details":{"cached_tokens":42,"cache_write_tokens":9}},
            "deployment":"gpt-4","parent_deployment":"parentdep",
            "operation_duration_ms":1234,
            "execution_path":["a","b"],"undeclared_field":"NOPE",
            "assembled_response":"{\\"id\\":\\"chatcmpl-abc\\",\\"usage\\":{\\"cache_creation_input_tokens\\":7,\\"completion_tokens_details\\":{\\"reasoning_tokens\\":11}}}",
            "trace":{"trace_id":"t1","core_span_id":"s1","core_parent_span_id":"ps1"},
            "claims":{"sub":"user-1","roles":["admin"]},
            "headers":{"content-type":"application/json","x-request-id":"r1"},
            "request":{"protocol":"HTTP/1.1","method":"POST","uri":"/openai/deployments/gpt-4/chat/completions",
            "time":"2026-06-18T00:00:14.794","body":"{\\"messages\\":[{\\"role\\":\\"user\\"},{\\"role\\":\\"assistant\\"}]}"},
            "response":{"status":"200","upstream_uri":"https://up","body":"RAWRESP"}}
            """.replace("\n", "");

    private static final String MCP_SAMPLE = """
            {"project":{"id":"p"},"user":{"id":"u"},
            "trace":{"trace_id":"t2","core_span_id":"s2"},
            "request":{"method":"POST","uri":"/v1/deployments/srv/mcp",
            "time":"2026-06-18T00:00:14.794","body":"{\\"method\\":\\"tools/call\\",\\"params\\":{\\"name\\":\\"search\\"}}"},
            "response":{"status":"500"}}
            """.replace("\n", "");

    /**
     * A streamed Anthropic-native call where {@code response.body} holds the verbatim SSE transcript, as it
     * does for any stream, and {@code assembled_response} holds one too rather than the merged JSON message
     * the column normally carries. Not a legacy-only shape: a small share of chat entry hops still fail to
     * yield a {@code response_id}, on recent days as much as older ones. It is
     * also the shape that made the body-derived {@code cache_creation_tokens} store 0 for every streamed
     * request.
     */
    private static final String STREAMED_SAMPLE = """
            {"project":{"id":"p"},"user":{"id":"u"},"deployment":"anthropic.claude-opus-4-8",
            "token_usage":{"prompt_tokens":75941,"completion_tokens":83,"total_tokens":76024,
            "prompt_tokens_details":{"cached_tokens":74632,"cache_write_tokens":1215}},
            "trace":{"trace_id":"t3","core_span_id":"s3"},
            "assembled_response":"event: message_start\\ndata: {\\"type\\":\\"message_start\\",\\"message\\":\
            {\\"id\\":\\"msg_1\\",\\"usage\\":{\\"cache_creation_input_tokens\\":1215}}}\\n\\n",
            "request":{"method":"POST","uri":"/anthropic/v1/messages?beta=true","time":"2026-08-05T09:00:00.000",
            "body":"{\\"stream\\":true,\\"messages\\":[{\\"role\\":\\"user\\"}]}"},
            "response":{"status":"200","body":"event: message_start\\ndata: {\\"type\\":\\"message_start\\"}\\n\\n"}}
            """.replace("\n", "");

    /**
     * A record whose {@code assembled_response} is a JSON <em>object</em> rather than a JSON string. Both
     * shapes reach ingest — {@code DeriverRegistry} reads structured nodes as-is and textual ones parsed —
     * so the stored column has to survive either.
     */
    private static final String STRUCTURED_SAMPLE = """
            {"project":{"id":"p"},"user":{"id":"u"},"deployment":"gpt-4",
            "request":{"time":"2026-01-01T00:00:00.000Z","uri":"/openai/deployments/gpt-4/chat/completions"},
            "trace":{"trace_id":"t","core_span_id":"s"},
            "assembled_response":{"id":"chatcmpl-obj","choices":[{"message":{"content":"hi"}}]}}
            """.replace("\n", "");

    private static MappingConfig loadDefaultMapping() {
        return loadMapping("classpath:mapping/dial-usage-log.yaml");
    }

    private static MappingConfig loadMapping(String location) {
        return new MappingFileLoader(
                new DefaultResourceLoader(), new TransformRegistry(), new DeriverRegistry(JsonMapper.builder().build()))
                .load(location);
    }

    private final MappingEngine payloadEngine = new MappingEngine(
            loadMapping("classpath:mapping/dial-usage-log-payload.yaml"),
            new TransformRegistry(), new DeriverRegistry(jsonMapper));

    private JsonNode read(String json) {
        return jsonMapper.readTree(json);
    }

    /**
     * The payload row must carry exactly the five verbatim-content columns plus the four the table
     * needs to key, partition, expire and survive the dedup-key drop rule — and nothing else.
     * {@code _updated_at} is the table's own default and stays unmapped.
     */
    @Test
    void payloadMappingProducesTheNineDeclaredColumns() {
        Map<String, Object> row = payloadEngine.toRow(read(SAMPLE), "blob-1.log");

        assertThat(row).containsOnlyKeys(
                "event_id", "request_time", "trace_id", "core_span_id",
                "jwt_claims", "request_tags",
                "request_body", "response_body", "assembled_response");
        assertThat(row).doesNotContainKey("_updated_at");
    }

    /**
     * The two rows one record produces are joined on {@code event_id} by the catalog's grain join, so
     * the key columns must agree exactly. A divergence here would not fail an insert — it would quietly
     * produce a usage-log row whose payload columns read as null.
     */
    @Test
    void payloadRowKeysMatchTheUsageLogRowForTheSameRecord() {
        JsonNode record = read(SAMPLE);
        Map<String, Object> usageLogRow = engine.toRow(record, "blob-1.log");
        Map<String, Object> payloadRow = payloadEngine.toRow(record, "blob-1.log");

        assertThat(payloadRow.get("event_id")).isEqualTo(usageLogRow.get("event_id"));
        assertThat(payloadRow.get("request_time")).isEqualTo(usageLogRow.get("request_time"));
        assertThat(payloadRow.get("trace_id")).isEqualTo(usageLogRow.get("trace_id"));
        assertThat(payloadRow.get("core_span_id")).isEqualTo(usageLogRow.get("core_span_id"));
        // Every verbatim-content column is written by exactly one of the two mappings, never both.
        for (String column : List.of("request_body", "response_body", "assembled_response",
                "jwt_claims", "request_tags")) {
            assertThat(payloadRow).as("payload." + column).containsKey(column);
            assertThat(usageLogRow).as("dial." + column).doesNotContainKey(column);
        }
    }

    /**
     * The dedup-key drop rule is decided on the row a target produced, so the payload row has to carry
     * the two id columns for {@code BlobIngestor} to judge it at all. Without them every payload row
     * would be dropped.
     */
    @Test
    void payloadRowCarriesDedupKeyColumns() {
        Map<String, Object> row = payloadEngine.toRow(read(SAMPLE), "blob-1.log");

        assertThat(row.get("trace_id")).isEqualTo("t1");
        assertThat(row.get("core_span_id")).isEqualTo("s1");
        assertThat(row.get("request_time")).isNotNull();
    }

    /** An absent body is null, not "" — "the producer sent nothing" stays distinguishable. */
    @Test
    void payloadMappingHandlesAbsentContent() {
        Map<String, Object> row = payloadEngine.toRow(read(MCP_SAMPLE), "blob-1.log");

        assertThat(row.get("response_body")).isNull();
        assertThat(row.get("assembled_response")).isNull();
    }

    private static final String RATE_SAMPLE = """
            {"project":{"id":"statgpt"},"user":{"id":"u1"},"chat":{"id":"c1"},
            "deployment":"gpt-4o","trace":{"trace_id":"t9","core_span_id":"s9"},
            "request":{"method":"POST","uri":"/v1/gpt-4o/rate","time":"2026-07-01T11:13:38.335",
            "body":"{\\"rate\\":true,\\"responseId\\":\\"resp_abc\\",\\"comment\\":\\"nice\\"}"},
            "response":{"status":"200"}}
            """.replace("\n", "");

    @Test
    void rateMappingExtractsBodyFieldsAndNormalizesRate() {
        TransformRegistry transforms = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingConfig rateMapping = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers)
                .load("classpath:mapping/rate-analytics.yaml");
        MappingEngine rateEngine = new MappingEngine(rateMapping, transforms, derivers);

        Map<String, Object> row = rateEngine.toRow(jsonMapper.readTree(RATE_SAMPLE), "logs/2026/07/01/f.log.gz");

        // response_id comes from the request body's responseId (not from any response).
        assertThat(row).containsEntry("response_id", "resp_abc");
        // rate is normalized: boolean true -> 1 (stored as a long for the Int8 column)...
        assertThat(row).containsEntry("rate", 1L);
        // ...and rate_raw keeps the submitted form alongside it, so the normalization stays reversible.
        assertThat(row).containsEntry("rate_raw", "true");
        assertThat(row).containsEntry("comment", "nice");
        // Context dimensions copied straight from the record.
        assertThat(row).containsEntry("deployment", "gpt-4o");
        assertThat(row).containsEntry("user_hash", "u1");
        assertThat(row).containsEntry("chat_id", "c1");
        assertThat(row).containsEntry("project_id", "statgpt");
        assertThat(row).containsEntry("request_time", "2026-07-01 11:13:38.335");
        // Hidden physical columns (sort key) are populated for correct storage/dedup.
        assertThat(row).containsEntry("trace_id", "t9").containsEntry("core_span_id", "s9");
        // The mapping only declares these 11 columns (event_id/_source_file are not part of rate_analytics).
        assertThat(row.keySet()).containsExactlyInAnyOrder(
                "request_time", "project_id", "trace_id", "core_span_id", "deployment",
                "user_hash", "chat_id", "response_id", "rate", "rate_raw", "comment");
    }

    private static final String RATE_SAMPLE_NO_RATE = """
            {"project":{"id":"statgpt"},"user":{"id":"u1"},"chat":{"id":"c1"},
            "deployment":"gpt-4o","trace":{"trace_id":"t9","core_span_id":"s9"},
            "request":{"method":"POST","uri":"/v1/gpt-4o/rate","time":"2026-07-01T11:13:38.335",
            "body":"{\\"responseId\\":\\"resp_abc\\"}"},
            "response":{"status":"200"}}
            """.replace("\n", "");

    @Test
    void rateMappingLeavesAbsentRateAndCommentNull() {
        TransformRegistry transforms = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingConfig rateMapping = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers)
                .load("classpath:mapping/rate-analytics.yaml");
        MappingEngine rateEngine = new MappingEngine(rateMapping, transforms, derivers);

        Map<String, Object> row = rateEngine.toRow(jsonMapper.readTree(RATE_SAMPLE_NO_RATE), "f.log.gz");

        assertThat(row).containsEntry("response_id", "resp_abc");
        assertThat(row.get("rate")).isNull();      // nullable, absent -> JSON null
        assertThat(row.get("rate_raw")).isNull();  // nullable, absent -> JSON null
        assertThat(row.get("comment")).isNull();   // nullable, absent -> JSON null
    }

    private static final String RATE_SAMPLE_BOOLEAN_FALSE = """
            {"project":{"id":"statgpt"},"user":{"id":"u1"},"chat":{"id":"c1"},
            "deployment":"gpt-4o","trace":{"trace_id":"t9","core_span_id":"s9"},
            "request":{"method":"POST","uri":"/v1/gpt-4o/rate","time":"2026-07-01T11:13:38.335",
            "body":"{\\"rate\\":false,\\"responseId\\":\\"resp_abc\\"}"},
            "response":{"status":"200"}}
            """.replace("\n", "");

    private static final String RATE_SAMPLE_NUMERIC_ZERO = """
            {"project":{"id":"statgpt"},"user":{"id":"u1"},"chat":{"id":"c1"},
            "deployment":"gpt-4o","trace":{"trace_id":"t9","core_span_id":"s9"},
            "request":{"method":"POST","uri":"/v1/gpt-4o/rate","time":"2026-07-01T11:13:38.335",
            "body":"{\\"rate\\":0,\\"responseId\\":\\"resp_abc\\"}"},
            "response":{"status":"200"}}
            """.replace("\n", "");

    /**
     * The reason {@code rate_raw} exists, asserted through the real mapping file rather than the deriver:
     * both submissions store {@code rate = 0}, and only the raw text says which was a thumbs-down and
     * which was a cleared rating.
     */
    @Test
    void rateMappingSeparatesBooleanFalseFromNumericZero() {
        MappingEngine rateEngine = rateEngine();

        Map<String, Object> booleanFalse =
                rateEngine.toRow(jsonMapper.readTree(RATE_SAMPLE_BOOLEAN_FALSE), "f.log.gz");
        Map<String, Object> numericZero =
                rateEngine.toRow(jsonMapper.readTree(RATE_SAMPLE_NUMERIC_ZERO), "f.log.gz");

        assertThat(booleanFalse).containsEntry("rate", 0L).containsEntry("rate_raw", "false");
        assertThat(numericZero).containsEntry("rate", 0L).containsEntry("rate_raw", "0");
    }

    private static final String RATE_SAMPLE_OBJECT_RATE = """
            {"project":{"id":"statgpt"},"user":{"id":"u1"},"chat":{"id":"c1"},
            "deployment":"gpt-4o","trace":{"trace_id":"t9","core_span_id":"s9"},
            "request":{"method":"POST","uri":"/v1/gpt-4o/rate","time":"2026-07-01T11:13:38.335",
            "body":"{\\"rate\\":{\\"score\\":1},\\"responseId\\":\\"resp_abc\\"}"},
            "response":{"status":"200"}}
            """.replace("\n", "");

    /**
     * A non-scalar {@code rate} reaches the column as null rather than as a serialized document: the
     * extractor yields the blank default and the engine's blank fallback nulls it, since the column is
     * nullable. Covered end to end because the two halves live in different classes.
     */
    @Test
    void rateMappingStoresNullForNonScalarRate() {
        Map<String, Object> row = rateEngine().toRow(jsonMapper.readTree(RATE_SAMPLE_OBJECT_RATE), "f.log.gz");

        assertThat(row.get("rate_raw")).isNull();
        assertThat(row.get("rate")).isNull();
        assertThat(row).containsEntry("response_id", "resp_abc");
    }

    private MappingEngine rateEngine() {
        TransformRegistry transforms = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingConfig rateMapping = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers)
                .load("classpath:mapping/rate-analytics.yaml");
        return new MappingEngine(rateMapping, transforms, derivers);
    }

    @Test
    void resolvesDottedPathsAndEachKind() {
        JsonNode node = jsonMapper.readTree(SAMPLE);

        Map<String, Object> row = engine.toRow(node, "logs/2026/06/18/file.log.gz");

        assertThat(row).containsEntry("project_id", "statgpt")
                .containsEntry("user_hash", "u1")
                .containsEntry("chat_id", "c1")
                .containsEntry("deployment", "gpt-4")
                .containsEntry("request_method", "POST")
                .containsEntry("request_uri", "/openai/deployments/gpt-4/chat/completions")
                .containsEntry("response_upstream_uri", "https://up")
                .containsEntry("trace_id", "t1")
                .containsEntry("core_span_id", "s1");
        // long kind -> numeric
        assertThat(row).containsEntry("prompt_tokens", 5L)
                .containsEntry("completion_tokens", 3L)
                .containsEntry("total_tokens", 8L);
        // array<string> kind -> list
        assertThat(row.get("execution_path")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void extractsDirectOptionalFields() {
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(SAMPLE), "f");

        assertThat(row).containsEntry("parent_deployment", "parentdep")
                .containsEntry("cached_prompt_tokens", 42L)
                .containsEntry("total_price", new BigDecimal("0.0012"))
                .containsEntry("deployment_price", new BigDecimal("0.0034"))
                .containsEntry("core_parent_span_id", "ps1");
        // The cache-write count comes from token_usage, NOT from the response payload: the sample's
        // assembled_response also carries a (differing) cache_creation_input_tokens of 7, and the
        // producer's own accounting is what must win — it is the only source populated for every
        // transport and upstream dialect.
        assertThat(row).containsEntry("cache_creation_tokens", 9L);
    }

    @Test
    void carriesTheProducersReportedOperationDuration() {
        // Copied as reported, never recomputed: the producer measures accept -> full response body (for a
        // background job, accept -> log emission), an interval nothing at ingest time can reconstruct.
        assertThat(engine.toRow(jsonMapper.readTree(SAMPLE), "f").get("operation_duration_ms"))
                .isEqualTo(1234L);

        // The producer clamps its measurement at zero, so a reported 0 is a real sub-millisecond
        // operation and is stored as one.
        String zeroDuration = SAMPLE.replace("\"operation_duration_ms\":1234", "\"operation_duration_ms\":0");
        assertThat(engine.toRow(jsonMapper.readTree(zeroDuration), "f").get("operation_duration_ms"))
                .isEqualTo(0L);

        // A record from a producer predating the field takes the non-nullable fallback: also 0, which is
        // why this column cannot distinguish "not reported" from a measured zero. Deliberate — the
        // distinction would identify a producer version, not a property of the request.
        String noDuration = SAMPLE.replace("\"operation_duration_ms\":1234,", "");
        assertThat(engine.toRow(jsonMapper.readTree(noDuration), "f").get("operation_duration_ms"))
                .isEqualTo(0L);
    }

    @Test
    void carriesTheReportedRouteAndNullWhenNoRouteServedTheRequest() {
        // SAMPLE is a chat completion, which no route serves: the producer omits both fields, and NULL is
        // the "not a route request" signal rather than an empty string.
        Map<String, Object> noRoute = engine.toRow(jsonMapper.readTree(SAMPLE), "f");
        assertThat(noRoute).containsEntry("route_name", null).containsEntry("route_type", null);

        String globalRoute = SAMPLE.replace("\"deployment\":\"gpt-4\",",
                "\"route_name\":\"vector-store\",\"route_type\":\"global\",");
        assertThat(engine.toRow(jsonMapper.readTree(globalRoute), "f"))
                .containsEntry("route_name", "vector-store")
                .containsEntry("route_type", "global");

        String applicationRoute = SAMPLE.replace("\"deployment\":\"gpt-4\",",
                "\"deployment\":\"my-app\",\"route_name\":\"index-search\",\"route_type\":\"application\",");
        assertThat(engine.toRow(jsonMapper.readTree(applicationRoute), "f"))
                .containsEntry("deployment", "my-app")
                .containsEntry("route_name", "index-search")
                .containsEntry("route_type", "application");

        // The two fields are independent: one reported, the other absent, stores the value and NULL.
        String nameOnly = SAMPLE.replace("\"deployment\":\"gpt-4\",", "\"route_name\":\"vector-store\",");
        assertThat(engine.toRow(jsonMapper.readTree(nameOnly), "f"))
                .containsEntry("route_name", "vector-store")
                .containsEntry("route_type", null);

        // The producer owns the route-kind domain, so a kind this service has never seen is stored as
        // reported rather than rejected.
        String unknownKind = SAMPLE.replace("\"deployment\":\"gpt-4\",",
                "\"route_name\":\"vector-store\",\"route_type\":\"interceptor\",");
        assertThat(engine.toRow(jsonMapper.readTree(unknownKind), "f")).containsEntry("route_type", "interceptor");

        // The route columns never feed event_kind, which stays derived from the request URI alone.
        assertThat(engine.toRow(jsonMapper.readTree(globalRoute), "f").get("event_kind"))
                .isEqualTo(noRoute.get("event_kind"));
    }

    @Test
    void parsesResponseStatusAsNumber() {
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(SAMPLE), "f");

        assertThat(row.get("response_status")).isEqualTo(200L);
    }

    @Test
    void preservesDecimalPrecisionForCosts() {
        String highPrecision = SAMPLE.replace("\"price\":0.0012", "\"price\":0.000000000123");
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(highPrecision), "f");

        assertThat(row.get("total_price")).isEqualTo(new BigDecimal("0.000000000123"));
    }

    @Test
    void normalizesRequestTimeToClickHouseLiteral() {
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(SAMPLE), "f");

        assertThat(row.get("request_time")).isEqualTo("2026-06-18 00:00:14.794");
    }

    @Test
    void injectsSourceFileToken() {
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(SAMPLE), "logs/2026/06/18/file.log.gz");

        assertThat(row.get("_source_file")).isEqualTo("logs/2026/06/18/file.log.gz");
    }

    @Test
    void capturesEveryPayloadColumnAndExcludesUndeclared() {
        Map<String, Object> payloadRow = payloadEngine.toRow(read(SAMPLE), "f");
        Map<String, Object> row = engine.toRow(read(SAMPLE), "f");

        // Every payload field is a declared column of the PAYLOAD mapping, so they are captured
        // there — and only there: dial-usage-log.yaml no longer declares them.
        assertThat(payloadRow).containsEntry("response_body", "RAWRESP");
        assertThat(payloadRow.get("request_body")).asString().contains("messages");
        // Stored verbatim: exactly the producer's string, not a re-serialization of a parse of it.
        assertThat(payloadRow).containsEntry("assembled_response",
                "{\"id\":\"chatcmpl-abc\",\"usage\":{\"cache_creation_input_tokens\":7,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":11}}}");
        assertThat(payloadRow).containsKeys("jwt_claims", "request_tags");
        assertThat(row).doesNotContainKeys("request_body", "response_body", "assembled_response",
                "jwt_claims", "request_tags");
        // Allowlist still holds: fields not declared in the mapping are never written.
        assertThat(row).doesNotContainKey("undeclared_field");
        assertThat(row.values()).doesNotContain("NOPE");
        // Dropped source columns are gone entirely.
        assertThat(row).doesNotContainKeys("api_type", "user_title", "request_protocol");
    }

    @Test
    void storesNullWhenTheProducerSentNoAssembledResponse() {
        // Nullable is load-bearing: "the producer sent nothing" has to stay distinguishable from "the
        // producer sent an empty response", which the non-nullable fallback (an empty string) would erase.
        // MCP_SAMPLE carries no assembled_response at all — the absent case, not an empty one.
        Map<String, Object> payloadRow = payloadEngine.toRow(read(MCP_SAMPLE), "f");

        assertThat(payloadRow).containsKey("assembled_response");
        assertThat(payloadRow.get("assembled_response")).isNull();
        // Both rows are ingested normally; nothing else is disturbed.
        assertThat(engine.toRow(read(MCP_SAMPLE), "f"))
                .containsEntry("project_id", "p").containsEntry("response_status", 500L);
    }

    @Test
    void storesValueThatIsNotJsonUnchanged() {
        // The expected shape is a merged JSON message, but ingest validates nothing: deciding what arrived
        // would mean parsing a payload this pipeline never reads, and a row emptied on that basis would
        // report "no response" for a request that had one. So the non-JSON payload some records carry (see
        // STREAMED_SAMPLE) is stored as-is rather than rejected — which is what keeps both a live ingest and
        // a backfill from failing per row on that minority.
        Map<String, Object> payloadRow = payloadEngine.toRow(read(STREAMED_SAMPLE), "f");

        assertThat(payloadRow.get("assembled_response")).asString()
                .startsWith("event: message_start")
                .contains("\"type\":\"message_start\"");
        // Not a failed row: every other column is populated as usual, on both targets.
        assertThat(engine.toRow(read(STREAMED_SAMPLE), "f"))
                .containsEntry("deployment", "anthropic.claude-opus-4-8")
                .containsEntry("cache_creation_tokens", 1215L);
    }

    @Test
    void storesStructuredValueAsItsJsonText() {
        // The producer may send the merged message as a JSON object rather than as a JSON string — the
        // derivers reading it accept either shape — and the column must store it as reported whatever its
        // shape. Coercing a container node to text yields nothing in Jackson, so without the container case
        // this would fall through the blank fallback and record NULL: a response that arrived reported as
        // one that never did, and silently, since the derived response_id would still be right.
        Map<String, Object> payloadRow = payloadEngine.toRow(read(STRUCTURED_SAMPLE), "f");

        assertThat(payloadRow.get("assembled_response")).asString()
                .isEqualTo("{\"id\":\"chatcmpl-obj\",\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        // Read as a document by the deriver in the same pass, exactly as the string form is — and the
        // deriver lives on the OTHER target, reading the record rather than the stored column.
        assertThat(engine.toRow(read(STRUCTURED_SAMPLE), "f"))
                .containsEntry("response_id", "chatcmpl-obj");
    }

    @Test
    void derivingFromTheAssembledResponseIsUnaffectedByAlsoStoringIt() {
        // response_id reads assembled_response as its first deriver source. Storing the same field as a
        // column of its own is independent of that — and so is storing it on a different table: a
        // deriver reads the parsed record, never a stored column.
        Map<String, Object> row = engine.toRow(read(SAMPLE), "f");

        assertThat(row).containsEntry("response_id", "chatcmpl-abc");
        assertThat(row).containsEntry("reasoning_tokens", 11L);
        assertThat(payloadEngine.toRow(read(SAMPLE), "f").get("assembled_response"))
                .asString().contains("chatcmpl-abc");
    }

    @Test
    void mappingWithoutTheColumnWritesNoAssembledResponse(@TempDir Path dir) throws Exception {
        // Removing the entry is the off switch, and it does not cost the derived column: assembled_response
        // stays available as a deriver source whether or not it is persisted.
        Path file = write(dir, """
                columns:
                  - column: response_id
                    derive: json-extract-string
                    sources: [assembled_response, response.body]
                    args: { path: id }
                    kind: string
                """);
        TransformRegistry transforms = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingConfig mapping = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers)
                .load("file:" + file);

        Map<String, Object> row = new MappingEngine(mapping, transforms, derivers)
                .toRow(jsonMapper.readTree(SAMPLE), "f");

        assertThat(row).doesNotContainKey("assembled_response");
        assertThat(row).containsEntry("response_id", "chatcmpl-abc");
    }

    private static Path write(Path dir, String yaml) throws Exception {
        Path file = dir.resolve("mapping.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void writesOnlyDeclaredColumnsInOrder() {
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(SAMPLE), "f");

        assertThat(row.keySet()).containsExactly(
                "event_id", "event_kind", "request_time", "_source_file", "project_id", "chat_id", "trace_id",
                "core_span_id", "core_parent_span_id", "execution_path", "parent_deployment", "deployment",
                "response_upstream_uri", "user_hash", "response_id", "prompt_tokens", "completion_tokens",
                "total_tokens", "cached_prompt_tokens", "cache_creation_tokens", "reasoning_tokens",
                "number_request_messages", "total_price", "deployment_price", "operation_duration_ms",
                "response_status", "success", "request_method", "request_uri", "mcp_method", "mcp_tool_call_name",
                "request_body_bytes", "response_body_bytes", "route_name", "route_type");
    }

    @Test
    void objectKindCarriesClaimsAndHeadersVerbatim() {
        Map<String, Object> row = payloadEngine.toRow(read(SAMPLE), "f");

        JsonNode claims = (JsonNode) row.get("jwt_claims");
        JsonNode headers = (JsonNode) row.get("request_tags");
        assertThat(claims.path("sub").asString()).isEqualTo("user-1");
        assertThat(claims.path("roles").path(0).asString()).isEqualTo("admin");
        assertThat(headers.path("content-type").asString()).isEqualTo("application/json");
        assertThat(headers.path("x-request-id").asString()).isEqualTo("r1");
    }

    /**
     * {@code request_tags} declares the {@code lowercase-json-keys} transform, so a header map arrives with
     * its top-level names folded and its values byte-for-byte.
     *
     * <p><b>Asserted here rather than only on the registry.</b> The transform receives whatever
     * {@code MappingEngine.coerce} produces for an {@code object} column — a Jackson 3
     * ({@code tools.jackson}) node. Both Jackson generations are on this classpath and their
     * {@code ObjectNode} types share no hierarchy, so a registry written against Jackson 2 compiles, passes
     * a registry unit test built from the same Jackson 2 fixtures, and returns every document unchanged
     * here. That is exactly how the transform first shipped; this is the level the mismatch is visible at.
     */
    @Test
    void objectKindLowerCasesHeaderKeysAndLeavesValuesAlone() {
        String record = SAMPLE.replace(
                "\"headers\":{\"content-type\":\"application/json\",\"x-request-id\":\"r1\"}",
                "\"headers\":{\"X-Claude-Code-Session-Id\":\"AbC-123\","
                        + "\"User-Agent\":\"Claude-CLI/2.1\",\"Content-Type\":\"application/json\"}");

        Map<String, Object> row = payloadEngine.toRow(read(record), "f");

        JsonNode headers = (JsonNode) row.get("request_tags");
        assertThat(headers.propertyNames())
                .containsExactly("x-claude-code-session-id", "user-agent", "content-type");
        // Only the key is folded — a session id and a user agent are both case-sensitive.
        assertThat(headers.path("x-claude-code-session-id").asString()).isEqualTo("AbC-123");
        assertThat(headers.path("user-agent").asString()).isEqualTo("Claude-CLI/2.1");
    }

    /** jwt_claims declares no transform: claim names are producer-stable and stay as sent. */
    @Test
    void objectKindLeavesClaimKeysAsSent() {
        String record = SAMPLE.replace(
                "\"claims\":{\"sub\":\"user-1\",\"roles\":[\"admin\"]}",
                "\"claims\":{\"Sub\":\"user-1\",\"roles\":[\"admin\"]}");

        Map<String, Object> row = payloadEngine.toRow(read(record), "f");

        assertThat(((JsonNode) row.get("jwt_claims")).propertyNames()).containsExactly("Sub", "roles");
    }

    @Test
    void objectKindFallsBackToEmptyObjectWhenAbsent() {
        Map<String, Object> row = payloadEngine.toRow(read("{}"), "f");

        JsonNode claims = (JsonNode) row.get("jwt_claims");
        JsonNode headers = (JsonNode) row.get("request_tags");
        assertThat(claims.isObject()).isTrue();
        assertThat(claims.isEmpty()).isTrue();
        assertThat(headers.isObject()).isTrue();
        assertThat(headers.isEmpty()).isTrue();
    }

    @Test
    void computesCalculatedColumns() {
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(SAMPLE), "f");

        assertThat(row.get("event_kind")).isEqualTo("llm_call");
        assertThat(row.get("response_id")).isEqualTo("chatcmpl-abc");
        assertThat(row.get("reasoning_tokens")).isEqualTo(11L);
        assertThat(row.get("number_request_messages")).isEqualTo(2L);
        assertThat(row.get("success")).isEqualTo(true);
        assertThat(row.get("response_body_bytes")).isEqualTo(7L); // "RAWRESP"
        // The size columns stay on dial_usage_log while the body they measure lives on the payload
        // table — which is exactly why they stay: they outlive the payload they describe.
        String requestBody = (String) payloadEngine.toRow(read(SAMPLE), "f").get("request_body");
        assertThat(row.get("request_body_bytes"))
                .isEqualTo((long) requestBody.getBytes(StandardCharsets.UTF_8).length);
        // Not an MCP request -> the JSON-RPC fields are empty.
        assertThat(row.get("mcp_method")).isEqualTo("");
        assertThat(row.get("mcp_tool_call_name")).isEqualTo("");
    }

    @Test
    void streamedResponseStillCarriesTheProducersCacheWriteCount() {
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(STREAMED_SAMPLE), "f");

        // An SSE transcript parses as no JSON document at all, so the body-derived columns take their
        // absent fallback — response_id is empty even though the transcript plainly contains the id.
        assertThat(row.get("response_id")).isEqualTo("");
        // The cache-write count is mapped from token_usage, so it survives the shape that defeats
        // derivation. Storing 0 here is the bug this guards: it reads as "no cache write happened".
        assertThat(row.get("cache_creation_tokens")).isEqualTo(1215L);
    }

    @Test
    void eventIdIsDeterministicAndKeyed() {
        Map<String, Object> first = engine.toRow(jsonMapper.readTree(SAMPLE), "f");
        Map<String, Object> second = engine.toRow(jsonMapper.readTree(SAMPLE), "other-file");

        // Stable across re-ingests (independent of the source file) and a parseable UUID.
        assertThat(first.get("event_id")).isEqualTo(second.get("event_id"));
        UUID parsed = UUID.fromString((String) first.get("event_id"));
        assertThat(parsed.version()).isEqualTo(5);

        // A different (trace_id, core_span_id) yields a different id.
        Map<String, Object> mcp = engine.toRow(jsonMapper.readTree(MCP_SAMPLE), "f");
        assertThat(mcp.get("event_id")).isNotEqualTo(first.get("event_id"));
    }

    @Test
    void classifiesMcpAndParsesJsonRpcBody() {
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(MCP_SAMPLE), "f");

        assertThat(row.get("event_kind")).isEqualTo("mcp");
        assertThat(row.get("mcp_method")).isEqualTo("tools/call");
        assertThat(row.get("mcp_tool_call_name")).isEqualTo("search");
        assertThat(row.get("success")).isEqualTo(false); // status 500
    }

    @Test
    void absentNullableColumnsBecomeNullWhileNonNullableFallBackToZero() {
        Map<String, Object> row = engine.toRow(jsonMapper.readTree("{}"), "f");

        // Non-nullable columns without a producer value fall back to the kind's zero value.
        assertThat(row.get("event_kind")).isEqualTo("");
        assertThat(row.get("execution_path")).isEqualTo(List.of());
        assertThat(row.get("response_status")).isEqualTo(0L);
        assertThat(row.get("success")).isEqualTo(false);
        assertThat(row.get("number_request_messages")).isEqualTo(0L);
        assertThat(row.get("request_body_bytes")).isEqualTo(0L);
        assertThat(row.get("operation_duration_ms")).isEqualTo(0L);
        // A derived UUID is always produced, even for an empty record.
        assertThat(row.get("event_id")).isNotNull();
        UUID.fromString((String) row.get("event_id"));
        // Nullable optional columns become null so absent is distinguishable from a genuine zero.
        assertThat(row).containsKey("prompt_tokens");
        assertThat(row.get("prompt_tokens")).isNull();
        assertThat(row.get("cached_prompt_tokens")).isNull();
        assertThat(row.get("cache_creation_tokens")).isNull();
        assertThat(row.get("total_price")).isNull();
        assertThat(row.get("request_body")).isNull();
        // request_time has no default: an absent request.time falls back to the STRING zero value so the
        // ingestor drops the record (a defaulted epoch timestamp would split it into its own partition).
        assertThat(row.get("request_time")).isEqualTo("");
        assertThat(row.get("_source_file")).isEqualTo("f");
    }

    @Test
    void genuineZeroIsPreservedNotNulled() {
        String zeroTokens = SAMPLE.replace("\"prompt_tokens\":5", "\"prompt_tokens\":0");
        Map<String, Object> row = engine.toRow(jsonMapper.readTree(zeroTokens), "f");

        // A present 0 is a real value, distinct from an absent (null) field.
        assertThat(row.get("prompt_tokens")).isEqualTo(0L);
    }

    /** The data-sync spec's inline-file requirement as the shipped payload mapping applies it. */
    @Nested
    class InlineFiles {

        private static final String IMAGE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        private static final String REQUEST_BODY =
                "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"what is this?\"},"
                        + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," + IMAGE + "\"}}]}]}";
        private static final String RESPONSE_BODY =
                "{\"choices\":[{\"message\":{\"content\":\"a pixel\",\"audio\":{\"data\":\"" + IMAGE + "\"}}}]}";

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

        private MappingEngine payload(InlineFilesMode mode) {
            return new MappingEngine(loadMapping("classpath:mapping/dial-usage-log-payload.yaml"),
                    new TransformRegistry(), new DeriverRegistry(jsonMapper), mode, "dial_usage_log_payload", registry);
        }

        private JsonNode record(String uri, String requestBody, String responseBody, String assembled) {
            ObjectNode record = jsonMapper.createObjectNode();
            record.putObject("trace").put("trace_id", "t1").put("core_span_id", "s1");
            ObjectNode request = record.putObject("request").put("time", "2026-10-13T10:00:00.000").put("uri", uri);
            if (requestBody != null) {
                request.put("body", requestBody);
            }
            if (responseBody != null) {
                record.putObject("response").put("status", "200").put("body", responseBody);
            }
            if (assembled != null) {
                record.put("assembled_response", assembled);
            }
            return record;
        }

        private double count(String name, String column) {
            Counter counter = registry.find(name).tags("table", "dial_usage_log_payload", "column", column).counter();
            return counter == null ? 0 : counter.count();
        }

        @Test
        void keepStoresTheBodiesByteForByteAndRegistersNoCounter() {
            JsonNode record = record("/openai/deployments/gpt-5/chat/completions", REQUEST_BODY, RESPONSE_BODY, RESPONSE_BODY);
            Map<String, Object> row = payload(InlineFilesMode.KEEP).toRow(record, "f");

            assertThat(row.get("request_body")).isEqualTo(REQUEST_BODY);
            assertThat(row.get("response_body")).isEqualTo(RESPONSE_BODY);
            assertThat(row.get("assembled_response")).isEqualTo(RESPONSE_BODY);
            assertThat(registry.getMeters()).isEmpty();
        }

        @Test
        void dropRemovesTheFilesFromAllThreeBodiesAndCountsThem() {
            JsonNode record = record("/openai/deployments/gpt-5/chat/completions?api-version=2024-10-21",
                    REQUEST_BODY, RESPONSE_BODY, RESPONSE_BODY);
            Map<String, Object> row = payload(InlineFilesMode.DROP).toRow(record, "f");

            assertThat(row.get("request_body")).isEqualTo(REQUEST_BODY.replace(IMAGE, ""));
            assertThat((String) row.get("request_body")).contains("\"url\":\"data:image/png;base64,\"", "what is this?");
            assertThat(row.get("response_body")).isEqualTo(RESPONSE_BODY.replace(IMAGE, ""));
            assertThat(row.get("assembled_response")).isEqualTo(RESPONSE_BODY.replace(IMAGE, ""));
            assertThat(count("ingest.inline.files.removed", "request_body")).isEqualTo(1);
            assertThat(count("ingest.inline.bytes.removed", "request_body")).isEqualTo(IMAGE.length());
            assertThat(count("ingest.inline.files.removed", "assembled_response")).isEqualTo(1);
            assertThat(count("ingest.inline.bodies.unscanned", "response_body")).isZero();
        }

        @Test
        void theRequestBodyIsScannedAsRequestAndTheResponseAsResponse() {
            // A response-only location in the request body, and a request-only location in the response body: neither is a file there.
            JsonNode record = record("/openai/deployments/gpt-5/chat/completions", RESPONSE_BODY, REQUEST_BODY, null);
            Map<String, Object> row = payload(InlineFilesMode.DROP).toRow(record, "f");

            assertThat(row.get("request_body")).isEqualTo(RESPONSE_BODY);
            assertThat(row.get("response_body")).isEqualTo(REQUEST_BODY);
        }

        @Test
        void routeIsNeverScanned() {
            String anthropic = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":"
                    + "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"" + IMAGE + "\"}}]}]}";
            Map<String, Object> routed = payload(InlineFilesMode.DROP)
                    .toRow(record("/claude_code_router/v1/messages?beta=true", anthropic, null, null), "f");
            Map<String, Object> direct = payload(InlineFilesMode.DROP)
                    .toRow(record("/anthropic/v1/messages?beta=true", anthropic, null, null), "f");

            assertThat(routed.get("request_body")).isEqualTo(anthropic);
            assertThat(direct.get("request_body")).isEqualTo(anthropic.replace(IMAGE, ""));
        }

        @Test
        void absentBodiesStayNullUnderDrop() {
            Map<String, Object> row = payload(InlineFilesMode.DROP)
                    .toRow(record("/openai/deployments/gpt-5/chat/completions", REQUEST_BODY, null, null), "f");

            assertThat(row.get("response_body")).isNull();
            assertThat(row.get("assembled_response")).isNull();
        }

        @Test
        void bodySizesOnTheUsageLogStayTheOriginalOnes() {
            JsonNode record = record("/openai/deployments/gpt-5/chat/completions", REQUEST_BODY, RESPONSE_BODY, null);
            payload(InlineFilesMode.DROP).toRow(record, "f");
            Map<String, Object> usageLog = engine.toRow(record, "f");

            // The rewrite replaces only the value written to the payload column; the record is untouched.
            assertThat(usageLog.get("request_body_bytes")).isEqualTo((long) REQUEST_BODY.getBytes(StandardCharsets.UTF_8).length);
            assertThat(usageLog.get("response_body_bytes")).isEqualTo((long) RESPONSE_BODY.getBytes(StandardCharsets.UTF_8).length);
            assertThat(record.at("/request/body").asString()).isEqualTo(REQUEST_BODY);
        }

        @Test
        void dropTruncatedOnlyCutsBackTheHalfFileOfBodyCoreCut() {
            String head = REQUEST_BODY.substring(0, REQUEST_BODY.length() - 4) + ",{\"type\":\"image_url\",\"image_url\":{\"url\":\"";
            String cut = head + "data:image/png;base64," + "A".repeat(4 * 1024 * 1024 - head.length() - 22) + ">>";
            MappingEngine dropTruncated = payload(InlineFilesMode.DROP_TRUNCATED);

            Map<String, Object> truncated = dropTruncated.toRow(record("/openai/deployments/gpt-5/chat/completions", cut, RESPONSE_BODY, null), "f");
            Map<String, Object> whole = dropTruncated.toRow(record("/openai/deployments/gpt-5/chat/completions", REQUEST_BODY, RESPONSE_BODY, null), "f");

            // The complete image before the cut is kept; only the half file the cut landed in goes.
            assertThat(truncated.get("request_body")).isEqualTo(head.substring(0, head.length() - 1) + ">>");
            assertThat((String) truncated.get("request_body")).contains(IMAGE);
            // A body Core did not cut is stored as sent, files and all.
            assertThat(whole.get("request_body")).isEqualTo(REQUEST_BODY);
            assertThat(whole.get("response_body")).isEqualTo(RESPONSE_BODY);
            assertThat(count("ingest.inline.bodies.cut", "request_body")).isEqualTo(1);
            assertThat(count("ingest.inline.files.removed", "request_body")).isZero();
            assertThat(count("ingest.inline.files.removed", "response_body")).isZero();
        }

        @Test
        void anUnscannableResponseIsCountedAndStoredUnchanged() {
            Map<String, Object> row = payload(InlineFilesMode.DROP)
                    .toRow(record("/openai/deployments/gpt-5/chat/completions", REQUEST_BODY, "upstream timeout", null), "f");

            assertThat(row.get("response_body")).isEqualTo("upstream timeout");
            assertThat(count("ingest.inline.bodies.unscanned", "response_body")).isEqualTo(1);
        }
    }
}
