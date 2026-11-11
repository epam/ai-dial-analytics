package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.configuration.MappingConfiguration;
import com.epam.aidial.analytics.configuration.PollProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.service.BlobIngestor;
import com.epam.aidial.analytics.service.ClickHouseIngestLedger;
import com.epam.aidial.analytics.service.IngestFlushOrder;
import com.epam.aidial.analytics.service.IngestPathsTestAccess;
import com.epam.aidial.analytics.service.RequestDeltaStages;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.sync.LogParser;
import com.epam.aidial.analytics.sync.delta.RequestBodyMode;
import com.epam.aidial.analytics.sync.mapping.DeriverRegistry;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import com.epam.aidial.analytics.sync.mapping.TransformRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sync.sensitive-columns-enabled} end to end: the shipped mappings, the seeded catalog's {@code sensitive} flags and
 * the real ClickHouse tables. A chat completion and a {@code /rate} record are ingested from a gzip blob; under the default
 * the sensitive columns carry what was captured, under {@code false} the rows are still written, keys and the grain join
 * intact, with every sensitive column at its ClickHouse default, request-delta columns included under {@code delta}.
 *
 * <p>The switch-off targets are built here by {@link MappingConfiguration} against the context's own catalog
 * repositories, so the default and the switch-off ingest share one context. The bean the real startup builds under
 * {@code false} is checked by {@link SensitiveColumnsDisabledStartupIntegrationTest}.
 */
class SensitiveColumnIngestIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String BUCKET = "logs";
    private static final String PATH_PREFIX = "sensitive-it";
    private static final String NULL = "<null>";

    @Autowired
    private ClickHouseIngestLedger ledger;
    @Autowired
    private UsageLogWriter usageLogWriter;
    @Autowired
    private List<SyncTarget> defaultTargets;
    @Autowired
    private IngestFlushOrder flushOrder;
    @Autowired
    private SyncProperties contextSyncProperties;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private TransformRegistry transforms;
    @Autowired
    private DeriverRegistry derivers;
    @Autowired
    private ObjectProvider<ColumnMappingRepository> columnMappings;
    @Autowired
    private ObjectProvider<TableDefinitionRepository> tableDefinitions;

    @TempDir
    private Path baseDir;

    /** The day the blob is dated and its rows are asserted on, taken once so a run crossing UTC midnight still agrees. */
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @Test
    void theDefaultWritesEverySensitiveColumn() throws Exception {
        String project = ingest(defaultTargets, RequestBodyMode.WHOLE);

        assertThat(payload(project, "request_body")).contains("\"content\":\"hello\"");
        assertThat(payload(project, "response_body")).contains("\"content\":\"hi there\"");
        assertThat(payload(project, "assembled_response")).contains("hi there");
        assertThat(payload(project, "toString(jwt_claims)")).contains("\"sub\":\"someone\"");
        assertThat(payload(project, "toString(request_tags)")).contains("\"x-conversation-id\":\"c-1\"");
        assertThat(rate(project, "ifNull(comment, '" + NULL + "')")).isEqualTo("too vague");
    }

    @Test
    void disabledLeavesEverySensitiveColumnAtItsDefault() throws Exception {
        String project = ingest(disabledTargets(), RequestBodyMode.WHOLE);

        assertSensitiveColumnsAtDefaults(project);
        assertKeysAndJoinIntact(project);
    }

    @Test
    void disabledUnderDeltaGetsNoRequestDeltaProcessing() throws Exception {
        String project = ingest(disabledTargets(), RequestBodyMode.DELTA);

        assertSensitiveColumnsAtDefaults(project);
        assertKeysAndJoinIntact(project);
        assertThat(payload(project, "toString(request_hash)")).isEqualTo("00000000-0000-0000-0000-000000000000");
        assertThat(payload(project, "toString(request_parent_hash)")).isEqualTo("00000000-0000-0000-0000-000000000000");
        assertThat(payload(project, "length(request_ancestor_hashes)")).isEqualTo("0");
    }

    private void assertSensitiveColumnsAtDefaults(String project) {
        assertThat(payload(project, "ifNull(request_body, '" + NULL + "')")).isEqualTo(NULL);
        assertThat(payload(project, "ifNull(response_body, '" + NULL + "')")).isEqualTo(NULL);
        assertThat(payload(project, "ifNull(assembled_response, '" + NULL + "')")).isEqualTo(NULL);
        assertThat(payload(project, "ifNull(request_cache_control, '" + NULL + "')")).isEqualTo(NULL);
        assertThat(payload(project, "toString(jwt_claims)")).isEqualTo("{}");
        assertThat(payload(project, "toString(request_tags)")).isEqualTo("{}");
        assertThat(rate(project, "ifNull(comment, '" + NULL + "')")).isEqualTo(NULL);
    }

    private void assertKeysAndJoinIntact(String project) {
        assertThat(payload(project, "concat(trace_id, '|', core_span_id, '|', user_hash, '|', toString(event_id))"))
                .isEqualTo(project + "-chat|s1|user-1|" + usageLog(project, "toString(event_id)", project + "-chat"));
        assertThat(payload(project, "toDate(request_time)")).isEqualTo(today.toString());
        assertThat(usageLog(project, "concat(deployment, '|', user_hash)", project + "-chat")).isEqualTo("gpt-4|user-1");
        assertThat(queryScalar("SELECT count() AS c FROM dial_usage_log AS u FINAL INNER JOIN dial_usage_log_payload AS p FINAL"
                + " ON u.event_id = p.event_id WHERE u.project_id = '" + project + "'", "c")).isEqualTo("2");
        assertThat(rate(project, "concat(toString(rate), '|', rate_raw, '|', toString(event_id != toUUID('"
                + "00000000-0000-0000-0000-000000000000')))")).isEqualTo("0|false|1");
    }

    private List<SyncTarget> disabledTargets() {
        return new MappingConfiguration().syncTargets(properties(RequestBodyMode.WHOLE, false), context, transforms, derivers,
                new SimpleMeterRegistry(), columnMappings, tableDefinitions);
    }

    /** Writes the two records under a fresh project id, polls them in through {@code targets}, and returns the project id. */
    private String ingest(List<SyncTarget> targets, RequestBodyMode mode) throws Exception {
        String project = "sensitive-it-" + System.nanoTime();
        String key = PATH_PREFIX + "/" + DateTimeFormatter.ofPattern("yyyy/MM/dd").format(today) + "/records-" + project + ".log.gz";
        writeBlob(baseDir.resolve(BUCKET).resolve(key), chatRecord(today, project) + "\n" + rateRecord(today, project) + "\n");

        BlobClient blobClient = new BlobClient(new BlobProperties("filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString())), TestBlobSpans.noop());
        SyncProperties syncProps = properties(mode, true);
        BlobIngestor ingestor = new BlobIngestor(blobClient, new LogParser(JsonMapper.builder().build(), SyncProperties.DEFAULT_MAX_LINE_BYTES),
                targets, flushOrder, new RequestDeltaStages(syncProps, targets, usageLogWriter, new SimpleMeterRegistry()));
        PollProperties pollProps = new PollProperties(true, "-", Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ZERO,
                Duration.ofMinutes(10), "day");

        assertThat(IngestPathsTestAccess.pollOnce(blobClient, ingestor, usageLogWriter, syncProps, pollProps, ledger)).isTrue();
        return project;
    }

    private SyncProperties properties(RequestBodyMode mode, boolean sensitiveColumnsEnabled) {
        return new SyncProperties("-", 0, 1000, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX, "yyyy/MM/dd",
                "'date='yyyy-MM-dd", false, contextSyncProperties.targets(), null, null, mode, sensitiveColumnsEnabled);
    }

    /** The chat completion's payload row; the payload target is match-all, so the {@code /rate} record has one too. */
    private String payload(String project, String expression) {
        return queryScalar("SELECT " + expression + " AS v FROM dial_usage_log_payload FINAL WHERE project_id = '" + project
                + "' AND trace_id = '" + project + "-chat'", "v");
    }

    private String rate(String project, String expression) {
        return queryScalar("SELECT " + expression + " AS v FROM rate_analytics FINAL WHERE project_id = '" + project + "'", "v");
    }

    private String usageLog(String project, String expression, String traceId) {
        return queryScalar("SELECT " + expression + " AS v FROM dial_usage_log FINAL WHERE project_id = '" + project
                + "' AND trace_id = '" + traceId + "'", "v");
    }

    private static void writeBlob(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String chatRecord(LocalDate day, String project) {
        return "{\"project\":{\"id\":\"" + project + "\"},\"deployment\":\"gpt-4\",\"user\":{\"id\":\"user-1\"},"
                + "\"claims\":{\"sub\":\"someone\"},\"headers\":{\"X-Conversation-Id\":\"c-1\"},"
                + "\"request\":{\"method\":\"POST\",\"uri\":\"/openai/deployments/gpt-4/chat/completions\",\"time\":\"" + day + "T00:00:01\","
                + "\"body\":\"{\\\"messages\\\":[{\\\"role\\\":\\\"user\\\",\\\"content\\\":\\\"hello\\\"}]}\"},"
                + "\"response\":{\"status\":200,\"body\":\"{\\\"choices\\\":[{\\\"message\\\":{\\\"role\\\":\\\"assistant\\\","
                + "\\\"content\\\":\\\"hi there\\\"}}]}\"},"
                + "\"assembled_response\":\"{\\\"choices\\\":[{\\\"message\\\":{\\\"content\\\":\\\"hi there\\\"}}]}\","
                + "\"trace\":{\"trace_id\":\"" + project + "-chat\",\"core_span_id\":\"s1\"}}";
    }

    private static String rateRecord(LocalDate day, String project) {
        return "{\"project\":{\"id\":\"" + project + "\"},\"deployment\":\"gpt-4\",\"user\":{\"id\":\"user-1\"},"
                + "\"request\":{\"method\":\"POST\",\"uri\":\"/v1/gpt-4/rate\",\"time\":\"" + day + "T00:00:02\","
                + "\"body\":\"{\\\"responseId\\\":\\\"r-1\\\",\\\"rate\\\":false,\\\"comment\\\":\\\"too vague\\\"}\"},"
                + "\"response\":{\"status\":200},\"trace\":{\"trace_id\":\"" + project + "-rate\",\"core_span_id\":\"s2\"}}";
    }
}
