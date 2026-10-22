package com.epam.aidial.analytics.web.controller.otel;

import com.epam.aidial.analytics.exception.OtelDisabledException;
import com.epam.aidial.analytics.exception.OtelSinkNotFoundException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.otel.OtelIngestService;
import com.epam.aidial.analytics.service.otel.OtelSignal;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer contract for the OTLP data plane: the OTLP-shaped success body, and the error codes an
 * exporter has to be able to act on.
 */
@WebMvcTest(OtelIngestController.class)
@AutoConfigureMockMvc(addFilters = false)
class OtelIngestControllerTest {

    private static final String LOGS = "/v1/otel/sink/app/v1/logs";
    private static final String EMPTY_EXPORT = "{\"resourceLogs\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtelIngestService ingestService;

    @Test
    void successfulExportReturnsAnEmptyPartialSuccess() throws Exception {
        BDDMockito.given(ingestService.ingest(eq("app"), eq(OtelSignal.LOGS), any())).willReturn(2);

        mockMvc.perform(post(LOGS).contentType(MediaType.APPLICATION_JSON).content(EMPTY_EXPORT))
                .andExpect(status().isOk())
                // An empty partial_success is OTLP's encoding of "everything was accepted"; nothing else
                // may appear in the body, because protojson rejects unknown fields.
                .andExpect(content().json("{\"partial_success\":{}}", JsonCompareMode.STRICT));
    }

    @Test
    void tracesAndMetricsRouteToTheirOwnSignals() throws Exception {
        mockMvc.perform(post("/v1/otel/sink/app/v1/traces")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resourceSpans\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/otel/sink/app/v1/metrics")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resourceMetrics\":[]}"))
                .andExpect(status().isOk());

        BDDMockito.then(ingestService).should().ingest(eq("app"), eq(OtelSignal.TRACES), any());
        BDDMockito.then(ingestService).should().ingest(eq("app"), eq(OtelSignal.METRICS), any());
    }

    @Test
    void protobufContentTypeIs415UnsupportedMediaType() throws Exception {
        mockMvc.perform(post(LOGS).contentType("application/x-protobuf").content("binary"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_media_type"));
    }

    @Test
    void malformedJsonIs400BadRequest() throws Exception {
        mockMvc.perform(post(LOGS).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void unknownSinkOrSignalIs404NotFound() throws Exception {
        BDDMockito.given(ingestService.ingest(any(), any(), any()))
                .willThrow(new OtelSinkNotFoundException("otel sink 'app' does not land the 'logs' signal"));

        mockMvc.perform(post(LOGS).contentType(MediaType.APPLICATION_JSON).content(EMPTY_EXPORT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void missingBackingTableIs409Conflict() throws Exception {
        BDDMockito.given(ingestService.ingest(any(), any(), any()))
                .willThrow(new TableConflictException("otel_app_logs is no longer in the catalog"));

        mockMvc.perform(post(LOGS).contentType(MediaType.APPLICATION_JSON).content(EMPTY_EXPORT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    void oversizedPayloadIs422ValidationError() throws Exception {
        BDDMockito.given(ingestService.ingest(any(), any(), any()))
                .willThrow(new TableValidationException("this export flattens to 60000 rows"));

        mockMvc.perform(post(LOGS).contentType(MediaType.APPLICATION_JSON).content(EMPTY_EXPORT))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void disabledSurfaceIs503OtelDisabled() throws Exception {
        BDDMockito.given(ingestService.ingest(any(), any(), any()))
                .willThrow(new OtelDisabledException("the OTLP sink surface is disabled"));

        mockMvc.perform(post(LOGS).contentType(MediaType.APPLICATION_JSON).content(EMPTY_EXPORT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("otel_disabled"));
    }
}
