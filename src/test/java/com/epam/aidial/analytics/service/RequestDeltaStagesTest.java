package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.sync.delta.RequestBodyMode;
import com.epam.aidial.analytics.sync.mapping.DeriverRegistry;
import com.epam.aidial.analytics.sync.mapping.MappingConfig;
import com.epam.aidial.analytics.sync.mapping.MappingEngine;
import com.epam.aidial.analytics.sync.mapping.MappingFileLoader;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import com.epam.aidial.analytics.sync.mapping.TransformRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which targets get the request-delta stage: a payload mapping on a table that has the request-delta columns, the table
 * checked on the first flush.
 */
class RequestDeltaStagesTest {

    private static final String[] TABLE_COLUMNS = {"request_path", "request_parent", "request_depth", "request_ancestors",
        "request_tools_hash", "request_inherited", "request_cache_markers"};

    private final TransformRegistry transforms = new TransformRegistry();
    private final DeriverRegistry derivers = new DeriverRegistry(JsonMapper.builder().build());
    private final MappingConfig payload = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers)
            .load("classpath:mapping/dial-usage-log-payload.yaml");
    private final MappingConfig usageLogMapping = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers)
            .load("classpath:mapping/dial-usage-log.yaml");

    @Test
    void payloadTableWithTheDeltaColumnsIsRewritten() {
        assertThat(flush(stages(true, TABLE_COLUMNS))).containsKey("request_inherited");
    }

    /** A custom target mapping request_body onto a table without the columns would have its bodies emptied. */
    @Test
    void tableWithoutTheDeltaColumnsIsLeftAlone() {
        assertThat(flush(stages(true, "request_body"))).doesNotContainKey("request_inherited").containsKey("request_body");
    }

    @Test
    void withoutSchemaValidationTheMappingIsTrusted() {
        assertThat(flush(stages(false))).containsKey("request_inherited");
    }

    @Test
    void mappingWithoutRequestBodyGetsNoStage() {
        assertThat(stages(true, TABLE_COLUMNS).byTable()).containsOnlyKeys("dial_usage_log_payload");
        SyncTarget usageLog = new SyncTarget("dial_usage_log", usageLogMapping, new MappingEngine(usageLogMapping, transforms, derivers));
        assertThat(new RequestDeltaStages(properties(true), List.of(usageLog), new RecordingWriter(), new SimpleMeterRegistry()).byTable())
                .isEmpty();
    }

    private static Map<String, Object> flush(RequestDeltaStages stages) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_time", "2026-01-15 10:00:00.000");
        row.put("project_id", "p");
        row.put("user_hash", "u");
        row.put("request_body", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        stages.byTable().get("dial_usage_log_payload").prepare(new ArrayList<>(List.of(row))).run();
        return row;
    }

    private RequestDeltaStages stages(boolean validateSchema, String... tableColumns) {
        RecordingWriter writer = new RecordingWriter() {
            @Override
            public Map<String, String> describeTable(String table) {
                Map<String, String> columns = new LinkedHashMap<>();
                for (String column : tableColumns) {
                    columns.put(column, "String");
                }
                return columns;
            }
        };
        SyncTarget target = new SyncTarget("dial_usage_log_payload", payload, new MappingEngine(payload, transforms, derivers));
        return new RequestDeltaStages(properties(validateSchema), List.of(target), writer, new SimpleMeterRegistry());
    }

    private static SyncProperties properties(boolean validateSchema) {
        return new SyncProperties("-", 0, 1000, 268_435_456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, "logs", "yyyy/MM/dd",
                "'date='yyyy-MM-dd", validateSchema, List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")),
                null, null, RequestBodyMode.DELTA);
    }
}
