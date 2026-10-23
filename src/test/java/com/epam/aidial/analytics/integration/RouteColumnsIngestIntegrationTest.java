package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.configuration.PollProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.service.BlobIngestor;
import com.epam.aidial.analytics.service.ClickHouseIngestLedger;
import com.epam.aidial.analytics.service.IngestFlushOrder;
import com.epam.aidial.analytics.service.IngestPathsTestAccess;
import com.epam.aidial.analytics.sync.LogParser;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The producer's route fields end to end: a gzip blob read by the poller, mapped by the shipped mapping and
 * written to the real {@code dial_usage_log}, whose {@code route_name}/{@code route_type} columns come from
 * {@code V15}. A global route, an application route and a request no route served land as reported, and
 * the last reads NULL in both columns rather than an empty string.
 */
class RouteColumnsIngestIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String BUCKET = "logs";
    private static final String PATH_PREFIX = "route-it";

    @Autowired
    private ClickHouseIngestLedger ledger;
    @Autowired
    private UsageLogWriter usageLogWriter;
    @Autowired
    private List<SyncTarget> targets;
    @Autowired
    private IngestFlushOrder flushOrder;

    @Test
    void reportedRouteLandsInBothColumnsAndAbsenceReadsNull(@TempDir Path baseDir) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String suffix = Long.toString(System.nanoTime());
        String key = PATH_PREFIX + "/" + DateTimeFormatter.ofPattern("yyyy/MM/dd").format(today) + "/routes-"
                + suffix + ".log.gz";
        String project = "route-it-" + suffix;
        writeBlob(baseDir.resolve(BUCKET).resolve(key), String.join("\n",
                record(today, project, "t-global", "/v1/vector-store",
                        "\"route_name\":\"vector-store\",\"route_type\":\"global\","),
                record(today, project, "t-app", "/v1/deployments/my-app/route/search",
                        "\"deployment\":\"my-app\",\"route_name\":\"index-search\",\"route_type\":\"application\","),
                record(today, project, "t-none", "/openai/deployments/gpt-4/chat/completions",
                        "\"deployment\":\"gpt-4\",")) + "\n");

        BlobClient blobClient = new BlobClient(new BlobProperties("filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString())), TestBlobSpans.noop());
        SyncProperties syncProps = new SyncProperties("-", 0, 1000, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX,
                "yyyy/MM/dd", "'date='yyyy-MM-dd", false, List.of(), null);
        BlobIngestor ingestor = new BlobIngestor(blobClient,
                new LogParser(JsonMapper.builder().build(), SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, flushOrder);
        PollProperties pollProps = new PollProperties(true, "-", Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ZERO,
                Duration.ofMinutes(10), "day");

        assertThat(IngestPathsTestAccess.pollOnce(blobClient, ingestor, usageLogWriter, syncProps, pollProps, ledger)).isTrue();

        assertThat(route(project, "t-global")).isEqualTo("|vector-store|global");
        assertThat(route(project, "t-app")).isEqualTo("my-app|index-search|application");
        // No route served the chat completion: the producer omits both fields, and both columns are NULL.
        assertThat(route(project, "t-none")).isEqualTo("gpt-4|<null>|<null>");
    }

    /** {@code deployment|route_name|route_type} of one ingested row, with NULL spelled out so it cannot pass as ''. */
    private String route(String project, String traceId) {
        return queryScalar("SELECT concat(deployment, '|', ifNull(route_name, '<null>'), '|', ifNull(route_type, '<null>')) AS r"
                + " FROM dial_usage_log FINAL WHERE project_id = '" + project + "' AND trace_id = '" + traceId + "'", "r");
    }

    private static void writeBlob(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String record(LocalDate day, String project, String traceId, String uri, String routeFields) {
        return "{\"project\":{\"id\":\"" + project + "\"}," + routeFields
                + "\"request\":{\"method\":\"POST\",\"uri\":\"" + uri + "\",\"time\":\"" + day + "T00:00:01\"},"
                + "\"response\":{\"status\":200},\"trace\":{\"trace_id\":\"" + traceId + "\",\"core_span_id\":\"s1\"}}";
    }
}
