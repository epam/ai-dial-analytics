package com.epam.aidial.analytics.web.controller.otel;

import com.epam.aidial.analytics.exception.OtelDisabledException;
import com.epam.aidial.analytics.exception.OtelSinkNotFoundException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.otel.OtelSignal;
import com.epam.aidial.analytics.service.otel.OtelSinkService;
import com.epam.aidial.analytics.service.otel.OtelSinkView;
import com.epam.aidial.analytics.service.table.TableStatus;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer contract for the sink control plane: response shapes and the error-code mapping. */
@WebMvcTest(OtelSinkController.class)
@AutoConfigureMockMvc(addFilters = false)
class OtelSinkControllerTest {

    private static final String SINKS = "/v1/otel/sinks";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtelSinkService sinkService;

    private static OtelSinkView view() {
        return new OtelSinkView("app", "chat telemetry", Instant.parse("2026-08-19T10:00:00Z"),
                "/v1/otel/sink/app",
                List.of(new OtelSinkView.SignalView(OtelSignal.LOGS, "otel_app_logs", TableStatus.ACTIVE),
                        new OtelSinkView.SignalView(OtelSignal.TRACES, "otel_app_traces", null)));
    }

    @Test
    void createReturns201WithTheSinkEndpointAndSignalTables() throws Exception {
        BDDMockito.given(sinkService.create(eq("app"), any(), any(), any())).willReturn(view());

        mockMvc.perform(post(SINKS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"app\",\"signals\":[\"logs\",\"traces\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("app"))
                .andExpect(jsonPath("$.endpoint").value("/v1/otel/sink/app"))
                .andExpect(jsonPath("$.created_at").value("2026-08-19T10:00:00Z"))
                .andExpect(jsonPath("$.signals[0].signal").value("logs"))
                .andExpect(jsonPath("$.signals[0].table").value("otel_app_logs"))
                .andExpect(jsonPath("$.signals[0].status").value("active"))
                // A signal whose backing table was deleted through /v1/tables reports a null status
                // rather than disappearing from the sink.
                .andExpect(jsonPath("$.signals[1].status").doesNotExist());
    }

    @Test
    void listIsObjectNotBareArray() throws Exception {
        BDDMockito.given(sinkService.list()).willReturn(List.of(view()));

        mockMvc.perform(get(SINKS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sinks[0].name").value("app"));
    }

    @Test
    void unknownSinkIs404NotFound() throws Exception {
        BDDMockito.given(sinkService.get("nope"))
                .willThrow(new OtelSinkNotFoundException("otel sink 'nope' not found"));

        mockMvc.perform(get(SINKS + "/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void duplicateSinkIs409Conflict() throws Exception {
        BDDMockito.given(sinkService.create(anyString(), any(), any(), any()))
                .willThrow(new TableConflictException("otel sink 'app' already exists"));

        mockMvc.perform(post(SINKS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"app\",\"signals\":[\"logs\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    void invalidSignalSetIs422ValidationError() throws Exception {
        BDDMockito.given(sinkService.create(anyString(), any(), any(), any()))
                .willThrow(new TableValidationException("signals contains a duplicate entry"));

        mockMvc.perform(post(SINKS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"app\",\"signals\":[\"logs\",\"logs\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void emptyOrUnknownSignalListReachesTheServiceRatherThanFailingAtBinding() throws Exception {
        // The body binds signals as raw text and carries no bean-validation constraints, so an empty list
        // and an unrecognised code both reach the service and report 422 validation_error — the same
        // status the duplicate case reports — instead of a 400 bad_request from the binder.
        BDDMockito.given(sinkService.create(anyString(), any(), any(), any()))
                .willThrow(new TableValidationException("at least one signal is required"));

        for (final String signals : new String[] {"[]", "[\"profiles\"]"}) {
            mockMvc.perform(post(SINKS).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"app\",\"signals\":" + signals + "}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("validation_error"));
        }
        BDDMockito.then(sinkService).should().create("app", null, List.of(), null);
        BDDMockito.then(sinkService).should().create("app", null, List.of("profiles"), null);
    }

    @Test
    void blankNameReachesTheServiceRatherThanFailingAtBinding() throws Exception {
        BDDMockito.given(sinkService.create(anyString(), any(), any(), any()))
                .willThrow(new TableValidationException("invalid identifier ''"));

        mockMvc.perform(post(SINKS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"signals\":[\"logs\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void disabledSurfaceIs503OtelDisabled() throws Exception {
        BDDMockito.given(sinkService.list())
                .willThrow(new OtelDisabledException("the OTLP sink surface is disabled (otel-sink.enabled=false)"));

        mockMvc.perform(get(SINKS))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("otel_disabled"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete(SINKS + "/app")).andExpect(status().isNoContent());
    }
}
