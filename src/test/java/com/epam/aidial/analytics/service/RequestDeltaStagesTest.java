package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.sync.delta.RequestBodyMode;
import com.epam.aidial.analytics.sync.mapping.DeriverRegistry;
import com.epam.aidial.analytics.sync.mapping.MappingConfig;
import com.epam.aidial.analytics.sync.mapping.MappingEngine;
import com.epam.aidial.analytics.sync.mapping.MappingFileLoader;
import com.epam.aidial.analytics.sync.mapping.SensitiveColumnExclusion;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import com.epam.aidial.analytics.sync.mapping.TransformRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which targets get the request-delta stage: a payload mapping on a table that has the request-delta columns, the table
 * checked on the first flush.
 */
class RequestDeltaStagesTest {

    private static final String[] TABLE_COLUMNS = {"request_hash", "request_parent_hash", "request_ancestor_hashes",
        "request_cache_control"};

    private final TransformRegistry transforms = new TransformRegistry();
    private final DeriverRegistry derivers = new DeriverRegistry(JsonMapper.builder().build());
    private final MappingConfig payload = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers)
            .load("classpath:mapping/dial-usage-log-payload.yaml");
    private final MappingConfig usageLogMapping = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers)
            .load("classpath:mapping/dial-usage-log.yaml");

    @Test
    void payloadTableWithTheDeltaColumnsIsRewritten() {
        assertThat(flush(stages(true, TABLE_COLUMNS))).containsKey("request_parent_hash");
    }

    /** A custom target mapping request_body onto a table without the columns would have its bodies emptied. */
    @Test
    void tableWithoutTheDeltaColumnsIsLeftAlone() {
        assertThat(flush(stages(true, "request_body"))).doesNotContainKey("request_parent_hash").containsKey("request_body");
    }

    /** Schema validation off is no reason to trust the mapping: on a table without the columns, a cut body is lost. */
    @Test
    void withoutSchemaValidationTheTableIsStillChecked() {
        assertThat(flush(stages(false, "request_body"))).doesNotContainKey("request_parent_hash").containsKey("request_body");
        assertThat(flush(stages(false, TABLE_COLUMNS))).containsKey("request_parent_hash");
    }

    @Test
    void wholeGetsNoStage() {
        SyncTarget target = new SyncTarget("dial_usage_log_payload", payload, new MappingEngine(payload, transforms, derivers));
        assertThat(new RequestDeltaStages(properties(true, RequestBodyMode.WHOLE), List.of(target), new RecordingWriter(),
                new SimpleMeterRegistry()).byTable()).isEmpty();
    }

    @Test
    void wholeWithPathStampsThePathOnly() {
        SyncTarget target = new SyncTarget("dial_usage_log_payload", payload, new MappingEngine(payload, transforms, derivers));
        RequestDeltaStages stages = new RequestDeltaStages(properties(true, RequestBodyMode.WHOLE_WITH_PATH), List.of(target),
                writerWith(TABLE_COLUMNS), new SimpleMeterRegistry());

        assertThat(flush(stages)).containsKey("request_hash").doesNotContainKey("request_parent_hash");
    }

    @Test
    void mappingWithoutRequestBodyGetsNoStage() {
        assertThat(stages(true, TABLE_COLUMNS).byTable()).containsOnlyKeys("dial_usage_log_payload");
        SyncTarget usageLog = new SyncTarget("dial_usage_log", usageLogMapping, new MappingEngine(usageLogMapping, transforms, derivers));
        assertThat(new RequestDeltaStages(properties(true), List.of(usageLog), new RecordingWriter(), new SimpleMeterRegistry()).byTable())
                .isEmpty();
    }

    /** A payload target whose request body was excluded as sensitive has nothing to cut; startup says the mode does nothing to it. */
    @Test
    void excludedRequestBodyGetsNoStageAndWarns() {
        MappingConfig withoutBody = SensitiveColumnExclusion.without(payload, Set.of("request_body"));
        SyncTarget target = new SyncTarget("dial_usage_log_payload", withoutBody, new MappingEngine(withoutBody, transforms, derivers),
                Set.of("request_body"));
        try (LogCapture logs = LogCapture.of(RequestDeltaStages.class)) {
            assertThat(new RequestDeltaStages(properties(true, RequestBodyMode.DELTA), List.of(target), writerWith(TABLE_COLUMNS),
                    new SimpleMeterRegistry()).byTable()).isEmpty();
            assertThat(logs.messagesAt(Level.WARN))
                    .anySatisfy(message -> assertThat(message).contains("dial_usage_log_payload").contains("request body is excluded"))
                    .anySatisfy(message -> assertThat(message).contains("no ingest target's mapping maps all of"));
        }
    }

    private static Map<String, Object> flush(RequestDeltaStages stages) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_time", "2026-01-15 10:00:00.000");
        row.put("project_id", "p");
        row.put("user_hash", "u");
        row.put("request_body", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        stages.byTable().get("dial_usage_log_payload").prepare(new ArrayList<>(List.of(row))).written(Set.of());
        return row;
    }

    private RequestDeltaStages stages(boolean validateSchema, String... tableColumns) {
        SyncTarget target = new SyncTarget("dial_usage_log_payload", payload, new MappingEngine(payload, transforms, derivers));
        return new RequestDeltaStages(properties(validateSchema, RequestBodyMode.DELTA), List.of(target), writerWith(tableColumns),
                new SimpleMeterRegistry());
    }

    private static RecordingWriter writerWith(String... tableColumns) {
        return new RecordingWriter() {
            @Override
            public Map<String, String> describeTable(String table) {
                Map<String, String> columns = new LinkedHashMap<>();
                for (String column : tableColumns) {
                    columns.put(column, "String");
                }
                return columns;
            }
        };
    }

    private static SyncProperties properties(boolean validateSchema) {
        return properties(validateSchema, RequestBodyMode.DELTA);
    }

    private static SyncProperties properties(boolean validateSchema, RequestBodyMode mode) {
        return new SyncProperties("-", 0, 1000, 268_435_456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, "logs", "yyyy/MM/dd",
                "'date='yyyy-MM-dd", validateSchema, List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")),
                null, null, mode, null);
    }
}
