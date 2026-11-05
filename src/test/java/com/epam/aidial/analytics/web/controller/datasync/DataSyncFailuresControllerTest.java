package com.epam.aidial.analytics.web.controller.datasync;

import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.service.IngestFailureEntry;
import com.epam.aidial.analytics.service.IngestFailureFilter;
import com.epam.aidial.analytics.service.IngestFailureKind;
import com.epam.aidial.analytics.service.IngestFailureReason;
import com.epam.aidial.analytics.service.IngestFailureService;
import com.epam.aidial.analytics.service.IngestFailureState;
import com.epam.aidial.analytics.service.OpenIngestFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DataSyncFailuresController.class)
@AutoConfigureMockMvc(addFilters = false)
class DataSyncFailuresControllerTest {

    private static final Instant T0 = Instant.parse("2026-05-26T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestFailureService service;

    @BeforeEach
    void sweepRuns() {
        when(service.retrySweepStatus()).thenReturn(new IngestFailureService.RetrySweepStatus(true, T0.plusSeconds(900), 20));
    }

    @Test
    void listsEntriesWithSnakeCaseMembers() throws Exception {
        OpenIngestFailure blob = new OpenIngestFailure(IngestFailureKind.BLOB, "logs/a.log.gz", LocalDate.parse("2026-05-26"), T0, Instant.EPOCH,
                2, T0, T0.plusSeconds(60), "transient", "read_error", "IOException: reset", "poll");
        OpenIngestFailure day = new OpenIngestFailure(IngestFailureKind.DAY, "2026-05-25", LocalDate.parse("2026-05-25"), Instant.EPOCH,
                Instant.EPOCH, 1, T0, T0, "transient", "listing_failed", "down", "reconcile");
        when(service.list(any(), anyInt())).thenReturn(new IngestFailureService.Page(List.of(
                new IngestFailureEntry(blob, IngestFailureState.PENDING, T0.plusSeconds(1860)),
                new IngestFailureEntry(day, IngestFailureState.PARKED, null)), true, 2, Map.of(IngestFailureState.PENDING, 1, IngestFailureState.PARKED, 1)));

        mockMvc.perform(get("/v1/data-sync/failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.failures[0].kind").value("blob"))
                .andExpect(jsonPath("$.failures[0].subject").value("logs/a.log.gz"))
                .andExpect(jsonPath("$.failures[0].blob_last_modified").value(T0.toString()))
                .andExpect(jsonPath("$.failures[0].state").value("pending"))
                .andExpect(jsonPath("$.failures[0].failure_class").value("transient"))
                .andExpect(jsonPath("$.failures[0].attempts").value(2))
                .andExpect(jsonPath("$.failures[0].first_failed_at").value(T0.toString()))
                .andExpect(jsonPath("$.failures[0].last_failed_at").value(T0.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.failures[0].next_attempt_at").value(T0.plusSeconds(1860).toString()))
                .andExpect(jsonPath("$.failures[1].kind").value("day"))
                .andExpect(jsonPath("$.failures[1].blob_last_modified").doesNotExist())
                .andExpect(jsonPath("$.failures[1].next_attempt_at").doesNotExist())
                .andExpect(jsonPath("$.failures[0].backfill_run_ids").doesNotExist())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.by_state.pending").value(1))
                .andExpect(jsonPath("$.by_state.parked").value(1))
                .andExpect(jsonPath("$.retry_sweep.enabled").value(true))
                .andExpect(jsonPath("$.retry_sweep.next_sweep_at").value(T0.plusSeconds(900).toString()))
                .andExpect(jsonPath("$.retry_sweep.max_per_sweep").value(20));
        verify(service).list(IngestFailureFilter.ALL, 100);
    }

    @Test
    void filtersAreParsedCaseInsensitively() throws Exception {
        when(service.list(any(), anyInt())).thenReturn(IngestFailureService.Page.of(List.of()));

        mockMvc.perform(get("/v1/data-sync/failures").param("state", "PARKED").param("kind", "blob").param("reason", "mapping_error")
                        .param("from", "2026-05-01").param("to", "2026-05-31").param("limit", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failures").isEmpty())
                .andExpect(jsonPath("$.truncated").value(false));

        verify(service).list(new IngestFailureFilter(IngestFailureKind.BLOB, IngestFailureState.PARKED, IngestFailureReason.MAPPING_ERROR,
                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31"), null), 1000);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "state|resolved|state must be one of [pending, parked]",
        "state|''|state must be one of",
        "kind|file|kind must be one of [blob, day, records]",
        "reason|oops|reason must be one of",
        "from|yesterday|from must be an ISO date",
        "limit|0|limit must be an integer from 1 to 1000",
        "limit|1001|limit must be an integer from 1 to 1000",
        "limit|many|limit must be an integer from 1 to 1000",
        "backfill_run_id|' '|backfill_run_id must not be blank",
    })
    void invalidFilterIsBadRequestNamingTheParameter(String name, String value, String message) throws Exception {
        mockMvc.perform(get("/v1/data-sync/failures").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString(message)));
        verifyNoInteractions(service);
    }

    @Test
    void invertedRangeIsBadRequest() throws Exception {
        mockMvc.perform(get("/v1/data-sync/failures").param("from", "2026-05-02").param("to", "2026-05-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from must not be after to"));
    }

    @Test
    void anUnavailableClickHouseIs503() throws Exception {
        when(service.list(any(), anyInt())).thenThrow(new ClickHouseUnavailableException("down", new RuntimeException()));

        mockMvc.perform(get("/v1/data-sync/failures"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("clickhouse_unavailable"));
    }

    @Test
    void retryRequeuesByFilter() throws Exception {
        when(service.requeue(any())).thenReturn(3);

        mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"parked\",\"reason\":\"mapping_error\",\"subjects\":[\"a\",\"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(3));

        ArgumentCaptor<IngestFailureFilter> filter = ArgumentCaptor.forClass(IngestFailureFilter.class);
        verify(service).requeue(filter.capture());
        assertThat(filter.getValue().state()).isEqualTo(IngestFailureState.PARKED);
        assertThat(filter.getValue().reason()).isEqualTo(IngestFailureReason.MAPPING_ERROR);
        assertThat(filter.getValue().subjects()).isEqualTo(Set.of("a", "b"));
    }

    @Test
    void listFiltersByBackfillRunAndReportsItsRuns() throws Exception {
        OpenIngestFailure day = new OpenIngestFailure(IngestFailureKind.DAY, "2026-05-25", LocalDate.parse("2026-05-25"), Instant.EPOCH,
                Instant.EPOCH, 1, T0, T0, "transient", "listing_failed", "down", "backfill", "skip_recorded",
                List.of("00000000-0000-4000-8000-000000000001"));
        when(service.list(any(), anyInt())).thenReturn(IngestFailureService.Page.of(List.of(new IngestFailureEntry(day, IngestFailureState.PARKED, null))));

        mockMvc.perform(get("/v1/data-sync/failures").param("backfill_run_id", "00000000-0000-4000-8000-000000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failures[0].backfill_run_ids[0]").value("00000000-0000-4000-8000-000000000001"));

        verify(service).list(new IngestFailureFilter(null, null, null, null, null, null, "00000000-0000-4000-8000-000000000001", null), 100);
    }

    @Test
    void disabledSweepIsReportedWithoutNextRun() throws Exception {
        when(service.retrySweepStatus()).thenReturn(new IngestFailureService.RetrySweepStatus(false, null, 20));
        when(service.requeue(any())).thenReturn(1);

        mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"parked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(1))
                .andExpect(jsonPath("$.retry_sweep.enabled").value(false))
                .andExpect(jsonPath("$.retry_sweep.next_sweep_at").doesNotExist());
    }

    @Test
    void retryTargetsNameEachSubjectWithItsKind() throws Exception {
        when(service.requeue(any())).thenReturn(1);

        mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targets\":[{\"kind\":\"records\",\"subject\":\"logs/a.log.gz\"},{\"kind\":\"DAY\",\"subject\":\"2026-05-25\"}]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<IngestFailureFilter> filter = ArgumentCaptor.forClass(IngestFailureFilter.class);
        verify(service).requeue(filter.capture());
        assertThat(filter.getValue().targets()).containsExactlyInAnyOrder(
                new IngestFailureFilter.Target(IngestFailureKind.RECORDS, "logs/a.log.gz"),
                new IngestFailureFilter.Target(IngestFailureKind.DAY, "2026-05-25"));
        assertThat(filter.getValue().kind()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"targets\":[{\"kind\":\"file\",\"subject\":\"a\"}]}|targets.kind must be one of [blob, day, records]",
        "{\"targets\":[{\"kind\":\"blob\"}]}|each of targets must name a kind and a non-blank subject",
        "{\"targets\":[{\"kind\":\"blob\",\"subject\":\" \"}]}|each of targets must name a kind and a non-blank subject",
        "{\"targets\":[null]}|each of targets must name a kind and a non-blank subject",
        "{\"targets\":[{\"kind\":\"blob\",\"subject\":\"a\",\"state\":\"parked\"}]}|'state'",
        "{\"backfill_run_id\":\"\"}|backfill_run_id must not be blank",
    })
    void anInvalidTargetOrRunIsRefused(String body, String message) throws Exception {
        mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(message)));
        verify(service, never()).requeue(any());
    }

    @Test
    void anEmptyRetryBodyIsRefused() throws Exception {
        mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        verifyNoInteractions(service);
    }

    @Test
    void anUnknownRetryMemberIsRefused() throws Exception {
        mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"parked\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("'status'")));
        verifyNoInteractions(service);
    }

    @Test
    void tooManySubjectsAreRefused() throws Exception {
        String subjects = String.join(",", Collections.nCopies(1001, "\"x\""));
        mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjects\":[" + subjects + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("subjects must hold at most 1000 entries"));
        verifyNoInteractions(service);
    }

    @Test
    void nullOrBlankSubjectIsRefused() throws Exception {
        for (String body : List.of("{\"subjects\":[null]}", "{\"subjects\":[\"a\",\" \"]}")) {
            mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("subjects must not hold a null or blank entry"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void anInvalidRetryFilterIsRefused() throws Exception {
        mockMvc.perform(post("/v1/data-sync/failures/retry").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"file\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("kind must be one of")));
        verifyNoInteractions(service);
    }
}
