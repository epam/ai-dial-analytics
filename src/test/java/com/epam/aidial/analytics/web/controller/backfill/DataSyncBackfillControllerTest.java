package com.epam.aidial.analytics.web.controller.backfill;

import com.epam.aidial.analytics.exception.BackfillInProgressException;
import com.epam.aidial.analytics.exception.BackfillNotFoundException;
import com.epam.aidial.analytics.exception.BackfillRangeException;
import com.epam.aidial.analytics.service.BackfillRun;
import com.epam.aidial.analytics.service.DataBackfillService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DataSyncBackfillController.class)
@AutoConfigureMockMvc(addFilters = false)
class DataSyncBackfillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataBackfillService service;

    private static BackfillRun.Snapshot snapshot(BackfillRun.State state) {
        return snapshot(state, List.of());
    }

    private static BackfillRun.Snapshot snapshot(BackfillRun.State state, List<LocalDate> failedDates) {
        return snapshot("run-1", state, failedDates);
    }

    private static BackfillRun.Snapshot snapshot(String runId, BackfillRun.State state, List<LocalDate> failedDates) {
        return new BackfillRun.Snapshot(
                runId, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-03"), 3,
                state, state == BackfillRun.State.RUNNING ? LocalDate.parse("2026-06-02") : null,
                2, failedDates.size(), failedDates, 4, 20, 1, 0,
                Instant.parse("2026-06-25T10:00:00Z"),
                state == BackfillRun.State.RUNNING ? null : Instant.parse("2026-06-25T10:05:00Z"));
    }

    @Test
    void triggerReturns202WithRunId() throws Exception {
        BackfillRun run = mock(BackfillRun.class);
        BDDMockito.given(run.snapshot()).willReturn(snapshot(BackfillRun.State.RUNNING));
        BDDMockito.given(service.start("2026-06-01", "2026-06-03", false)).willReturn(run);

        mockMvc.perform(post("/v1/data-sync/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-06-01\",\"to\":\"2026-06-03\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.from").value("2026-06-01"))
                .andExpect(jsonPath("$.to").value("2026-06-03"))
                .andExpect(jsonPath("$.days").value(3));
    }

    /** {@code includeIngested} binds leniently: a JSON boolean or its string spelling; absent and null mean false. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        ",\"includeIngested\":true | true",
        ",\"includeIngested\":\"true\" | true",
        ",\"includeIngested\":false | false",
        ",\"includeIngested\":null | false",
        "'' | false"})
    void includeIngestedSelectsWhetherRecordedBlobsAreReingested(String member, boolean expected) throws Exception {
        BackfillRun run = mock(BackfillRun.class);
        BDDMockito.given(run.snapshot()).willReturn(snapshot(BackfillRun.State.RUNNING));
        BDDMockito.given(service.start("2026-06-01", "2026-06-03", expected)).willReturn(run);

        mockMvc.perform(post("/v1/data-sync/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-06-01\",\"to\":\"2026-06-03\"" + member + "}"))
                .andExpect(status().isAccepted());

        verify(service).start("2026-06-01", "2026-06-03", expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"\"maybe\"", "{\"yes\":true}"})
    void unreadableIncludeIngestedIsBadRequest(String value) throws Exception {
        mockMvc.perform(post("/v1/data-sync/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-06-01\",\"to\":\"2026-06-03\",\"includeIngested\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));

        verifyNoInteractions(service);
    }

    @Test
    void statusReturnsRunView() throws Exception {
        BDDMockito.given(service.status("run-1")).willReturn(snapshot(BackfillRun.State.COMPLETED));

        mockMvc.perform(get("/v1/data-sync/backfill/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.daysTotal").value(3))
                .andExpect(jsonPath("$.daysCompleted").value(2))
                .andExpect(jsonPath("$.rowsInserted").value(20))
                .andExpect(jsonPath("$.finishedAt").value("2026-06-25T10:05:00Z"))
                // No day failed, so the member is absent, not an empty list or a null.
                .andExpect(jsonPath("$", not(hasKey("failedDates"))));
    }

    @Test
    void statusSerializesFailedDates() throws Exception {
        BDDMockito.given(service.status("run-1")).willReturn(snapshot(BackfillRun.State.COMPLETED,
                List.of(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-03"))));

        mockMvc.perform(get("/v1/data-sync/backfill/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysFailed").value(2))
                .andExpect(jsonPath("$.failedDates.length()").value(2))
                .andExpect(jsonPath("$.failedDates[0]").value("2026-06-01"))
                .andExpect(jsonPath("$.failedDates[1]").value("2026-06-03"));
    }

    @Test
    void listReturnsRunsInServiceOrder() throws Exception {
        BDDMockito.given(service.list(null)).willReturn(List.of(
                snapshot("run-2", BackfillRun.State.RUNNING, List.of()),
                snapshot("run-1", BackfillRun.State.COMPLETED, List.of(LocalDate.parse("2026-06-02")))));

        mockMvc.perform(get("/v1/data-sync/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.length()").value(2))
                .andExpect(jsonPath("$.runs[0].runId").value("run-2"))
                .andExpect(jsonPath("$.runs[0].state").value("running"))
                .andExpect(jsonPath("$.runs[0].currentDate").value("2026-06-02"))
                .andExpect(jsonPath("$.runs[0]", not(hasKey("failedDates"))))
                .andExpect(jsonPath("$.runs[1].runId").value("run-1"))
                .andExpect(jsonPath("$.runs[1].state").value("completed"))
                .andExpect(jsonPath("$.runs[1].daysTotal").value(3))
                .andExpect(jsonPath("$.runs[1].daysCompleted").value(2))
                .andExpect(jsonPath("$.runs[1].rowsInserted").value(20))
                .andExpect(jsonPath("$.runs[1].finishedAt").value("2026-06-25T10:05:00Z"))
                .andExpect(jsonPath("$.runs[1].failedDates[0]").value("2026-06-02"));
    }

    @Test
    void emptyListIsAnEmptyRunsArray() throws Exception {
        BDDMockito.given(service.list(null)).willReturn(List.of());

        mockMvc.perform(get("/v1/data-sync/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").isArray())
                .andExpect(jsonPath("$.runs.length()").value(0));
    }

    @ParameterizedTest
    @CsvSource({"running, RUNNING", "Running, RUNNING", "RUNNING, RUNNING", "completed, COMPLETED", "failed, FAILED"})
    void stateFilterBindsByWireSpellingCaseInsensitively(String param, BackfillRun.State expected) throws Exception {
        BDDMockito.given(service.list(expected)).willReturn(List.of());

        mockMvc.perform(get("/v1/data-sync/backfill").param("state", param)).andExpect(status().isOk());

        verify(service).list(expected);
    }

    @Test
    void unknownStateIsBadRequestNamingTheAcceptedValues() throws Exception {
        mockMvc.perform(get("/v1/data-sync/backfill").param("state", "paused"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("state must be one of [running, completed, failed]"));

        verifyNoInteractions(service);
    }

    /** An empty value reaches the binder rather than reading as an absent filter. */
    @Test
    void emptyStateIsBadRequestNotAnUnfilteredList() throws Exception {
        mockMvc.perform(get("/v1/data-sync/backfill").param("state", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("state must be one of [running, completed, failed]"));

        verifyNoInteractions(service);
    }

    @Test
    void invalidRangeMapsToBadRequest() throws Exception {
        BDDMockito.given(service.start("2026-06-10", "2026-06-01", false))
                .willThrow(new BackfillRangeException("from must not be after to"));

        mockMvc.perform(post("/v1/data-sync/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-06-10\",\"to\":\"2026-06-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void inProgressMapsToConflict() throws Exception {
        BDDMockito.given(service.start("2026-06-01", "2026-06-03", false))
                .willThrow(new BackfillInProgressException("A backfill run is already in progress"));

        mockMvc.perform(post("/v1/data-sync/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-06-01\",\"to\":\"2026-06-03\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("backfill_conflict"))
                .andExpect(jsonPath("$.message").value("A backfill run is already in progress"));
    }

    @Test
    void unknownRunMapsToNotFound() throws Exception {
        BDDMockito.given(service.status("nope"))
                .willThrow(new BackfillNotFoundException("Unknown backfill run: nope"));

        mockMvc.perform(get("/v1/data-sync/backfill/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}
