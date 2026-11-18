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
 * The producer's spending-limits check end to end: a gzip blob read by the poller, mapped by the shipped mapping
 * and written to the real {@code dial_usage_log}, whose spending-limit columns come from {@code V18}. An assigned
 * budget, a policy that applies by itself on a token without a project, an exhausted shadow check, limits off and
 * a request budgets did not apply to land as reported, and the last reads NULL in all five columns.
 */
class BudgetColumnsIngestIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String BUCKET = "logs";
    private static final String PATH_PREFIX = "budget-it";

    @Autowired
    private ClickHouseIngestLedger ledger;
    @Autowired
    private UsageLogWriter usageLogWriter;
    @Autowired
    private List<SyncTarget> targets;
    @Autowired
    private IngestFlushOrder flushOrder;

    @Test
    void reportedSpendingLimitsLandInTheFiveColumnsAndAbsenceReadsNull(@TempDir Path baseDir) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String suffix = Long.toString(System.nanoTime());
        String key = PATH_PREFIX + "/" + DateTimeFormatter.ofPattern("yyyy/MM/dd").format(today) + "/budgets-"
                + suffix + ".log.gz";
        String deployment = "budget-it-" + suffix;
        String project = "project-a";
        writeBlob(baseDir.resolve(BUCKET).resolve(key), String.join("\n",
                record(today, project, deployment, "t-assigned", "\"spending_limits\":{\"mode\":\"enforce\","
                        + "\"cost_object\":\"project-a\",\"outcome\":\"paid\",\"policy\":\"engineering-pro\",\"assignment\":\"project-a/a1\"},"),
                record(today, "", deployment, "t-self", "\"spending_limits\":{\"mode\":\"shadow\","
                        + "\"cost_object\":\"ORG\",\"outcome\":\"paid\",\"policy\":\"ORG/personal\"},"),
                record(today, project, deployment, "t-exhausted", "\"spending_limits\":{\"mode\":\"shadow\","
                        + "\"cost_object\":\"project-a\",\"outcome\":\"exhausted\"},"),
                record(today, project, deployment, "t-off", "\"spending_limits\":{\"mode\":\"off\"},"),
                record(today, project, deployment, "t-none", "")) + "\n");

        BlobClient blobClient = new BlobClient(new BlobProperties("filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString())), TestBlobSpans.noop());
        SyncProperties syncProps = new SyncProperties("-", 0, 1000, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX,
                "yyyy/MM/dd", "'date='yyyy-MM-dd", false, List.of(), null, null, null, null);
        BlobIngestor ingestor = new BlobIngestor(blobClient,
                new LogParser(JsonMapper.builder().build(), SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, flushOrder);
        PollProperties pollProps = new PollProperties(true, "-", Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ZERO,
                Duration.ofMinutes(10), "day");

        assertThat(IngestPathsTestAccess.pollOnce(blobClient, ingestor, usageLogWriter, syncProps, pollProps, ledger)).isTrue();

        assertThat(budget(deployment, "t-assigned")).isEqualTo("project-a|enforce|project-a|paid|engineering-pro|project-a/a1");
        // A token without a project: project_id is empty while the producer charged its default cost object.
        assertThat(budget(deployment, "t-self")).isEqualTo("|shadow|ORG|paid|ORG/personal|<null>");
        assertThat(budget(deployment, "t-exhausted")).isEqualTo("project-a|shadow|project-a|exhausted|<null>|<null>");
        assertThat(budget(deployment, "t-off")).isEqualTo("project-a|off|<null>|<null>|<null>|<null>");
        // Budgets did not apply: the producer omits the object, and all five columns are NULL.
        assertThat(budget(deployment, "t-none")).isEqualTo("project-a|<null>|<null>|<null>|<null>|<null>");
    }

    /** {@code project_id} and the five spending-limit columns of one row, {@code |}-joined, NULL spelled out. */
    private String budget(String deployment, String traceId) {
        return queryScalar("SELECT concat(project_id, '|', ifNull(spending_limits_mode, '<null>'), '|', ifNull(budget_cost_object, '<null>'), '|',"
                + " ifNull(budget_outcome, '<null>'), '|', ifNull(budget_policy, '<null>'), '|', ifNull(budget_assignment, '<null>')) AS b"
                + " FROM dial_usage_log FINAL WHERE deployment = '" + deployment + "' AND trace_id = '" + traceId + "'", "b");
    }

    private static void writeBlob(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String record(LocalDate day, String project, String deployment, String traceId, String budgetField) {
        return "{\"project\":{\"id\":\"" + project + "\"}," + budgetField + "\"deployment\":\"" + deployment + "\","
                + "\"request\":{\"method\":\"POST\",\"uri\":\"/openai/deployments/" + deployment + "/chat/completions\","
                + "\"time\":\"" + day + "T00:00:01\"},"
                + "\"response\":{\"status\":200},\"trace\":{\"trace_id\":\"" + traceId + "\",\"core_span_id\":\"s1\"}}";
    }
}
