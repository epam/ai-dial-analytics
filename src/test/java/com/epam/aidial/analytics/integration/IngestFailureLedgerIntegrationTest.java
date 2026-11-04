package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.clickhouse.ClickHouseDataRejectedException;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.service.ClickHouseIngestFailureLedger;
import com.epam.aidial.analytics.service.ClickHouseIngestLedger;
import com.epam.aidial.analytics.service.IngestFailureEvent;
import com.epam.aidial.analytics.service.IngestFailureKind;
import com.epam.aidial.analytics.service.IngestFailureLedger;
import com.epam.aidial.analytics.service.IngestFailureReason;
import com.epam.aidial.analytics.service.IngestFailureService;
import com.epam.aidial.analytics.service.IngestFailureState;
import com.epam.aidial.analytics.service.IngestLedger;
import com.epam.aidial.analytics.service.OpenIngestFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The failure ledger against a real ClickHouse: the {@code V17} table, the per-subject aggregation and the latest-event
 * read as {@link ClickHouseIngestFailureLedger} issues them, the ingest ledger's keyed read that resolves failed blobs,
 * the two {@code /v1/data-sync/failures} endpoints end to end, and that the table is unreachable through the query surface.
 */
class IngestFailureLedgerIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String TABLE = ClickHouseIngestFailureLedger.TABLE;
    private static final Instant T0 = Instant.now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private ClickHouseIngestFailureLedger failureLedger;
    @Autowired
    private ClickHouseIngestLedger ingestLedger;
    @Autowired
    private IngestFailureService failures;
    @Autowired
    private UsageLogWriter usageLogWriter;

    @BeforeEach
    void cleanSlate() {
        execute("TRUNCATE TABLE " + TABLE);
        execute("TRUNCATE TABLE " + ClickHouseIngestLedger.TABLE);
    }

    @Test
    void theTableMatchesTheDeclaredColumns() {
        assertThat(usageLogWriter.describeTable(TABLE)).containsAllEntriesOf(ClickHouseIngestFailureLedger.COLUMNS);
    }

    @Test
    void openAggregatesEachSubjectFromItsEvents() {
        Instant blobModified = T0.minusSeconds(60);
        failureLedger.append(List.of(
                failed("logs/a.log.gz", blobModified, IngestFailureReason.READ_ERROR, "poll", T0),
                failed("logs/a.log.gz", blobModified, IngestFailureReason.READ_ERROR, "reconcile", T0.plusSeconds(60)),
                requeued("logs/a.log.gz", blobModified, T0.plusSeconds(120)),
                failed("logs/a.log.gz", blobModified, IngestFailureReason.CORRUPT_GZIP, "retry", T0.plusSeconds(180))));

        assertThat(failureLedger.open()).singleElement().satisfies(open -> {
            assertThat(open.kind()).isEqualTo(IngestFailureKind.BLOB);
            assertThat(open.subject()).isEqualTo("logs/a.log.gz");
            assertThat(open.blobLastModified()).isEqualTo(blobModified);
            assertThat(open.day()).isEqualTo(blobModified.atZone(ZoneOffset.UTC).toLocalDate());
            assertThat(open.resetAt()).isEqualTo(T0.plusSeconds(120));
            assertThat(open.attempts()).isEqualTo(1);
            assertThat(open.firstFailedAt()).isEqualTo(T0);
            assertThat(open.lastFailedAt()).isEqualTo(T0.plusSeconds(180));
            assertThat(open.failureClass()).isEqualTo("deterministic");
            assertThat(open.reason()).isEqualTo("corrupt_gzip");
            assertThat(open.path()).isEqualTo("retry");
            assertThat(open.message()).isEqualTo("boom");
        });
    }

    @Test
    void subjectWithoutResetHasNoResetTime() {
        failureLedger.append(List.of(failed("logs/b.log.gz", T0, IngestFailureReason.READ_ERROR, "poll", T0)));

        assertThat(failureLedger.open()).singleElement().satisfies(open -> {
            assertThat(open.resetAt()).isEqualTo(Instant.EPOCH);
            assertThat(open.attempts()).isEqualTo(1);
        });
    }

    @Test
    void resolvedSubjectIsNotOpen() {
        LocalDate day = LocalDate.parse("2026-05-26");
        failureLedger.append(List.of(
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.FAILED, "reconcile",
                        IngestFailureReason.LISTING_FAILED, "down", T0),
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.RESOLVED, "retry",
                        null, "", T0.plusSeconds(60))));

        assertThat(failureLedger.open()).isEmpty();
        assertThat(failureLedger.latest(IngestFailureKind.DAY, day.toString())).contains(IngestFailureEvent.Type.RESOLVED);
        assertThat(failureLedger.latest(IngestFailureKind.DAY, "2026-05-27")).isEmpty();
    }

    /** A subject names the backfill runs that failed it since its latest resolution, each once, sorted. */
    @Test
    void openNamesTheBackfillRunsSinceTheLatestResolution() {
        LocalDate day = LocalDate.parse("2026-05-26");
        String runA = "00000000-0000-4000-8000-00000000000a";
        String runB = "00000000-0000-4000-8000-00000000000b";
        String runC = "00000000-0000-4000-8000-00000000000c";
        failureLedger.append(List.of(
                dayFailed(day, runA, T0),
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.RESOLVED, "retry",
                        null, "", T0.plusSeconds(60)),
                dayFailed(day, runC, T0.plusSeconds(120)),
                dayFailed(day, runB, T0.plusSeconds(180)),
                dayFailed(day, runC, T0.plusSeconds(240)),
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.FAILED, "reconcile",
                        IngestFailureReason.LISTING_FAILED, "down", T0.plusSeconds(300)),
                failed("logs/a.log.gz", T0, IngestFailureReason.READ_ERROR, "poll", T0)));

        assertThat(failureLedger.open()).extracting(OpenIngestFailure::subject, OpenIngestFailure::backfillRunIds).containsExactlyInAnyOrder(
                tuple(day.toString(), List.of(runB, runC)),
                tuple("logs/a.log.gz", List.of()));
    }

    private static IngestFailureEvent dayFailed(LocalDate day, String runId, Instant at) {
        return new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.FAILED, "backfill",
                IngestFailureReason.LISTING_FAILED, "down", at, IngestLedger.Mode.SKIP_RECORDED.wire(), runId);
    }

    /** A failed day keeps the ledger mode its run took, so its retry runs the same way. */
    @Test
    void failedDayKeepsItsLedgerMode() {
        LocalDate day = LocalDate.parse("2026-05-26");
        failureLedger.append(List.of(
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.FAILED, "backfill",
                        IngestFailureReason.WRITE_FAILED, "down", T0, IngestLedger.Mode.INGEST_ALL.wire()),
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.REQUEUED, "operator",
                        null, "", T0.plusSeconds(60))));

        assertThat(failureLedger.open()).singleElement()
                .satisfies(open -> assertThat(open.ledgerMode()).isEqualTo(IngestLedger.Mode.INGEST_ALL));
        assertThat(failureLedger.latestOf(IngestFailureKind.DAY, day.toString()))
                .contains(new IngestFailureLedger.Latest(IngestFailureEvent.Type.REQUEUED, "ingest_all"));
    }

    /**
     * A later failure of a skipping run does not hide an earlier {@code ingest_all} one; a resolution ends it, so a failure
     * after it takes its own mode again.
     */
    @Test
    void ingestAllModeHoldsUntilTheDayIsResolved() {
        LocalDate day = LocalDate.parse("2026-05-26");
        failureLedger.append(List.of(
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.FAILED, "backfill",
                        IngestFailureReason.WRITE_FAILED, "down", T0, IngestLedger.Mode.INGEST_ALL.wire()),
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.FAILED, "reconcile",
                        IngestFailureReason.WRITE_FAILED, "down", T0.plusSeconds(60), IngestLedger.Mode.SKIP_RECORDED.wire())));

        assertThat(failureLedger.open()).singleElement()
                .satisfies(open -> assertThat(open.ledgerMode()).isEqualTo(IngestLedger.Mode.INGEST_ALL));
        assertThat(failureLedger.latestOf(IngestFailureKind.DAY, day.toString()))
                .contains(new IngestFailureLedger.Latest(IngestFailureEvent.Type.FAILED, "ingest_all"));

        failureLedger.append(List.of(
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.RESOLVED, "retry",
                        null, "", T0.plusSeconds(120)),
                new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, IngestFailureEvent.Type.FAILED, "reconcile",
                        IngestFailureReason.WRITE_FAILED, "down", T0.plusSeconds(180), IngestLedger.Mode.SKIP_RECORDED.wire())));

        assertThat(failureLedger.open()).singleElement()
                .satisfies(open -> assertThat(open.ledgerMode()).isEqualTo(IngestLedger.Mode.SKIP_RECORDED));
    }

    @Test
    void theIngestLedgerKeyedReadResolvesFailedBlobs() {
        ingestLedger.record(List.of(new BlobListing("logs/c.log.gz", T0), new BlobListing("logs/d.log.gz", T0)));

        Map<String, IngestLedger.Recorded> recorded = ingestLedger.recordedAmong(List.of("logs/c.log.gz", "logs/x.log.gz"));

        assertThat(recorded).containsOnlyKeys("logs/c.log.gz");
        assertThat(recorded.get("logs/c.log.gz").lastModified()).isEqualTo(T0);
        assertThat(recorded.get("logs/c.log.gz").ingestedAt()).isAfter(T0);
        assertThat(ingestLedger.recordedAmong(List.of())).isEmpty();

        String odd = "logs/it's a \\ key.log.gz";
        ingestLedger.record(List.of(new BlobListing(odd, T0)));
        assertThat(ingestLedger.recordedAmong(List.of(odd, "logs/c.log.gz"))).containsOnlyKeys(odd, "logs/c.log.gz");
    }

    /** More keys than one read binds are read in several, so the parameter never outgrows a request. */
    @Test
    void theKeyedReadSpansMoreKeysThanOneReadBinds() {
        List<BlobListing> listings = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            String key = "many/" + "k".repeat(200) + "-" + i + ".log.gz";
            listings.add(new BlobListing(key, T0));
            keys.add(key);
        }
        ingestLedger.record(listings);

        assertThat(ingestLedger.recordedAmong(keys)).hasSize(2500);
    }

    @Test
    void outstandingExcludesBlobsTheIngestLedgerRecordedSinceTheyFailed() {
        // Failed two days ago, so the server's ingested_at stamp falls after the failure whatever the clock skew.
        failureLedger.append(List.of(
                failed("logs/e.log.gz", T0, IngestFailureReason.CORRUPT_GZIP, "poll", T0),
                failed("logs/f.log.gz", T0, IngestFailureReason.CORRUPT_GZIP, "poll", T0)));
        ingestLedger.record(List.of(new BlobListing("logs/e.log.gz", T0)));

        IngestFailureService.Snapshot snapshot = failures.snapshot();

        assertThat(snapshot.outstanding()).singleElement().satisfies(entry -> {
            assertThat(entry.failure().subject()).isEqualTo("logs/f.log.gz");
            assertThat(entry.state()).isEqualTo(IngestFailureState.PARKED);
        });
        assertThat(snapshot.resolvedByLedger()).extracting(open -> open.subject()).containsExactly("logs/e.log.gz");

        // Compaction writes the resolution, so the blob leaves the open set for good.
        assertThat(failures.resolveIngested(snapshot)).isEqualTo(1);
        assertThat(failureLedger.open()).extracting(open -> open.subject()).containsExactly("logs/f.log.gz");
    }

    /**
     * Skipped records are recorded in the hand-over that records their blob, so the ingest ledger always holds a later
     * entry for it: a records subject must not resolve through it, while a blob subject on the same key still does.
     */
    @Test
    void recordsSubjectIsNotResolvedByTheIngestLedgerButItsBlobSubjectIs() {
        failureLedger.append(List.of(
                failed("logs/r.log.gz", T0, IngestFailureReason.CORRUPT_GZIP, "poll", T0),
                new IngestFailureEvent(IngestFailureKind.RECORDS, "logs/r.log.gz", T0.atZone(ZoneOffset.UTC).toLocalDate(), T0,
                        IngestFailureEvent.Type.FAILED, "poll", IngestFailureReason.SKIPPED_RECORDS, "malformed_line: 1 (line 3)", T0)));
        ingestLedger.record(List.of(new BlobListing("logs/r.log.gz", T0)));

        IngestFailureService.Snapshot snapshot = failures.snapshot();

        assertThat(snapshot.outstanding()).singleElement().satisfies(entry -> {
            assertThat(entry.failure().kind()).isEqualTo(IngestFailureKind.RECORDS);
            assertThat(entry.failure().reason()).isEqualTo("skipped_records");
            assertThat(entry.failure().failureClass()).isEqualTo("deterministic");
            assertThat(entry.state()).isEqualTo(IngestFailureState.PARKED);
        });
        assertThat(snapshot.resolvedByLedger()).extracting(open -> open.kind()).containsExactly(IngestFailureKind.BLOB);
    }

    /** Every data-class code the write path splits on is the code the supported server gives that name. */
    @Test
    void dataClassCodesMatchTheServersNames() {
        ClickHouseDataRejectedException.DATA_CLASS_CODES.forEach((code, name) ->
                assertThat(queryScalar("SELECT errorCodeToName(" + code + ") AS n", "n")).as("code %d", code).isEqualTo(name));
    }

    /** A value the table cannot parse is refused with a data-class code, which is what lets the write path split by blob. */
    @Test
    void anUnparseableValueIsRefusedAsDataClass() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("project_id", "p");
        bad.put("trace_id", "refused-" + UUID.randomUUID());
        bad.put("core_span_id", "s");
        bad.put("request_time", "not a time");

        assertThatThrownBy(() -> usageLogWriter.insert("dial_usage_log", List.of(bad)))
                .isInstanceOfSatisfying(ClickHouseDataRejectedException.class,
                        refused -> assertThat(ClickHouseDataRejectedException.DATA_CLASS_CODES).containsKey(refused.code()));
    }

    /** An entry written before the failure — an earlier ingest of the same version — does not resolve it. */
    @Test
    void earlierIngestOfTheSameVersionDoesNotResolveLaterFailure() {
        ingestLedger.record(List.of(new BlobListing("logs/i.log.gz", T0)));
        Instant afterTheIngest = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS);
        failureLedger.append(List.of(failed("logs/i.log.gz", T0, IngestFailureReason.MAPPING_ERROR, "backfill", afterTheIngest)));

        assertThat(failures.outstanding()).singleElement()
                .satisfies(entry -> assertThat(entry.failure().subject()).isEqualTo("logs/i.log.gz"));
    }

    @Test
    void theEndpointsListAndRequeue() throws Exception {
        failureLedger.append(List.of(
                failed("logs/g.log.gz", T0, IngestFailureReason.MAPPING_ERROR, "reconcile", T0),
                failed("logs/h.log.gz", T0, IngestFailureReason.READ_ERROR, "poll", T0.plusSeconds(1))));

        mockMvc.perform(get("/v1/data-sync/failures").param("state", "parked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].subject").value("logs/g.log.gz"))
                .andExpect(jsonPath("$.failures[0].state").value("parked"))
                .andExpect(jsonPath("$.failures[0].reason").value("mapping_error"))
                .andExpect(jsonPath("$.failures[0].failure_class").value("deterministic"))
                .andExpect(jsonPath("$.failures[0].attempts").value(1))
                .andExpect(jsonPath("$.failures[0].blob_last_modified").value(T0.toString()))
                .andExpect(jsonPath("$.failures[0].next_attempt_at").doesNotExist());

        mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"parked\",\"reason\":\"mapping_error\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(1));

        mockMvc.perform(get("/v1/data-sync/failures").param("kind", "blob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failures.length()").value(2))
                .andExpect(jsonPath("$.failures[?(@.subject == 'logs/g.log.gz')].state").value(hasItem("pending")))
                .andExpect(jsonPath("$.failures[?(@.subject == 'logs/g.log.gz')].attempts").value(hasItem(0)));
    }

    @Test
    void theFailureLedgerIsNotListedOrQueryable() throws Exception {
        mockMvc.perform(get("/v1/queries/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..name", not(hasItem(TABLE))));
        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT subject FROM " + TABLE + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    private static IngestFailureEvent failed(String key, Instant lastModified, IngestFailureReason reason, String path, Instant at) {
        return new IngestFailureEvent(IngestFailureKind.BLOB, key, lastModified.atZone(ZoneOffset.UTC).toLocalDate(), lastModified,
                IngestFailureEvent.Type.FAILED, path, reason, "boom", at);
    }

    private static IngestFailureEvent requeued(String key, Instant lastModified, Instant at) {
        return new IngestFailureEvent(IngestFailureKind.BLOB, key, lastModified.atZone(ZoneOffset.UTC).toLocalDate(), lastModified,
                IngestFailureEvent.Type.REQUEUED, IngestFailureEvent.OPERATOR_PATH, null, "", at);
    }
}
