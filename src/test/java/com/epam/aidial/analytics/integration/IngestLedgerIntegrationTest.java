package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.configuration.PollProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.service.BlobIngestor;
import com.epam.aidial.analytics.service.ClickHouseIngestLedger;
import com.epam.aidial.analytics.service.DataSyncService;
import com.epam.aidial.analytics.service.IngestFlushOrder;
import com.epam.aidial.analytics.service.IngestPathsTestAccess;
import com.epam.aidial.analytics.sync.LogParser;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ingest ledger against a real ClickHouse: the {@code V13} table, the prefix read and the record write
 * as {@link ClickHouseIngestLedger} issues them, and the point of the whole change end to end — the poller
 * and then the reconciliation run over the same day leave every row in the target table once, before any
 * merge. Also pins that the ledger is unreachable through the query and table surfaces.
 */
class IngestLedgerIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String LEDGER = ClickHouseIngestLedger.TABLE;
    private static final String BUCKET = "logs";
    private static final String PATH_PREFIX = "ledger-it";

    @Autowired
    private ClickHouseIngestLedger ledger;
    @Autowired
    private UsageLogWriter usageLogWriter;
    @Autowired
    private List<SyncTarget> targets;
    @Autowired
    private IngestFlushOrder flushOrder;

    @BeforeEach
    void cleanSlate() {
        execute("TRUNCATE TABLE " + LEDGER);
    }

    @Test
    void recordedRoundTripsExactlyToTheMillisecond() {
        Instant lastModified = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS).plusMillis(7);

        ledger.record(List.of(new BlobListing("rt/2026/a.log.gz", lastModified)));

        assertThat(ledger.recorded("rt/")).containsExactly(Map.entry("rt/2026/a.log.gz", lastModified));
    }

    /** No FINAL and no merge: argMax over ingested_at picks the newer entry, even across monthly partitions. */
    @Test
    void newerEntryWinsBeforeAnyMergeAndAcrossPartitions() {
        execute("SYSTEM STOP MERGES " + LEDGER);
        try {
            Instant older = Instant.now().minus(Duration.ofDays(40)).truncatedTo(ChronoUnit.MILLIS);
            Instant newer = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            ledger.record(List.of(new BlobListing("sup/x.log.gz", older)));
            ledger.record(List.of(new BlobListing("sup/x.log.gz", newer)));

            assertThat(queryScalar("SELECT count() AS c FROM " + LEDGER + " WHERE blob_key = 'sup/x.log.gz'", "c")).isEqualTo("2");
            assertThat(ledger.recorded("sup/")).containsExactly(Map.entry("sup/x.log.gz", newer));
        } finally {
            execute("SYSTEM START MERGES " + LEDGER);
        }
    }

    @Test
    void prefixReadReturnsOnlyKeysUnderThatPrefix() {
        Instant lastModified = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ledger.record(List.of(
                new BlobListing("iso/2026/06/1/a.log.gz", lastModified),
                new BlobListing("iso/2026/06/10/b.log.gz", lastModified)));

        assertThat(ledger.recorded("iso/2026/06/1/")).containsOnlyKeys("iso/2026/06/1/a.log.gz");
    }

    /** The statement size does not depend on the keys: a day of long keys reads back in one constant-size query. */
    @Test
    void manyLongKeysReadBackUnderOnePrefix() {
        Instant lastModified = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String padding = "k".repeat(250);
        List<BlobListing> listings = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            listings.add(new BlobListing("big/" + padding + "-" + i + ".log.gz", lastModified));
        }

        ledger.record(listings);

        assertThat(ledger.recorded("big/")).hasSize(5000);
    }

    @Test
    void listingWithoutLastModifiedIsNotRecorded() {
        ledger.record(List.of(new BlobListing("epoch/a.log.gz", Instant.EPOCH)));

        assertThat(ledger.recorded("epoch/")).isEmpty();
    }

    /**
     * The duplicate this change removes: the poller ingests a blob, then the reconciliation run lists the same
     * day. Merges are stopped, so the count is what was written, not what the engine collapsed.
     */
    @Test
    void pollerThenReconciliationWritesEachRowOnce(@TempDir Path baseDir) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String key = PATH_PREFIX + "/" + DateTimeFormatter.ofPattern("yyyy/MM/dd").format(today) + "/e2e-"
                + System.nanoTime() + ".log.gz";
        writeBlob(baseDir.resolve(BUCKET).resolve(key), today);

        BlobClient blobClient = new BlobClient(new BlobProperties("filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString())), TestBlobSpans.noop());
        SyncProperties syncProps = new SyncProperties("-", 0, 1000, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX, "yyyy/MM/dd",
                "'date='yyyy-MM-dd", false, List.of(), null, null, null);
        BlobIngestor ingestor = new BlobIngestor(blobClient, new LogParser(JsonMapper.builder().build(), SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, flushOrder);
        PollProperties pollProps = new PollProperties(true, "-", Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ZERO,
                Duration.ofMinutes(10), "day");

        execute("SYSTEM STOP MERGES dial_usage_log");
        try {
            assertThat(IngestPathsTestAccess.pollOnce(blobClient, ingestor, usageLogWriter, syncProps, pollProps, ledger)).isTrue();
            DataSyncService.RunSummary summary =
                    IngestPathsTestAccess.reconcile(blobClient, ingestor, usageLogWriter, syncProps, ledger, today);

            assertThat(summary.success()).isTrue();
            assertThat(summary.blobsSkipped()).isEqualTo(1);
            assertThat(summary.filesProcessed()).isZero();
            assertThat(queryScalar("SELECT count() AS c FROM dial_usage_log WHERE _source_file = '" + key
                    + "' SETTINGS final = 0", "c")).isEqualTo("2");
        } finally {
            execute("SYSTEM START MERGES dial_usage_log");
        }
    }

    @Test
    void ledgerIsNotListedOrQueryable() throws Exception {
        mockMvc.perform(get("/v1/queries/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..name", not(hasItem(LEDGER))));
        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT blob_key FROM " + LEDGER + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void tablesApiCannotCreateOrAddressTheLedger() throws Exception {
        execute("INSERT INTO " + LEDGER + " (blob_key, blob_last_modified) VALUES ('survivor/a.log.gz', now64(3))");

        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + LEDGER + "\",\"type\":\"source\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reserved for service-internal tables")));
        mockMvc.perform(get("/v1/tables/" + LEDGER)).andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/tables/" + LEDGER))
                .andExpect(status().isNotFound());

        assertThat(queryScalar("SELECT count() AS c FROM " + LEDGER + " WHERE blob_key = 'survivor/a.log.gz'", "c")).isEqualTo("1");
    }

    /** Two keyed records dated today (the payload table's 90-day TTL would drop an older part at once). */
    private static void writeBlob(Path file, LocalDate day) throws Exception {
        Files.createDirectories(file.getParent());
        String suffix = Long.toString(System.nanoTime());
        String content = record(day, "t1-" + suffix) + "\n" + record(day, "t2-" + suffix) + "\n";
        try (OutputStream out = Files.newOutputStream(file);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String record(LocalDate day, String traceId) {
        return "{\"project\":{\"id\":\"ledger-it\"},\"request\":{\"uri\":\"/chat/completions\",\"time\":\"" + day
                + "T00:00:01\"},\"response\":{\"status\":200},\"trace\":{\"trace_id\":\"" + traceId
                + "\",\"core_span_id\":\"s1\"}}";
    }
}
