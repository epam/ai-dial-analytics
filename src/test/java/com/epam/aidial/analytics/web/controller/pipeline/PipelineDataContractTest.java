package com.epam.aidial.analytics.web.controller.pipeline;

import com.epam.aidial.analytics.exception.PipelineAccessDeniedException;
import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.exception.RunnerContractException;
import com.epam.aidial.analytics.service.enrichment.EnrichPreviewService;
import com.epam.aidial.analytics.service.enrichment.EnrichWriteService;
import com.epam.aidial.analytics.service.enrichment.GroupFetchService;
import com.epam.aidial.analytics.service.enrichment.MaterializeService;
import com.epam.aidial.analytics.service.enrichment.ScanService;
import com.epam.aidial.analytics.service.enrichment.model.GroupPage;
import com.epam.aidial.analytics.service.enrichment.model.MaterializePage;
import com.epam.aidial.analytics.service.enrichment.model.ScanPage;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContactMetrics;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import com.epam.aidial.analytics.service.pipeline.AggregateRefreshService;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.model.RefreshOutcome;
import com.epam.aidial.analytics.service.pipeline.preview.PipelinePreview;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumnRole;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNote;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNoteCode;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The runner contract on the data plane: which of this controller's six {@code POST}s require the
 * {@code Adas-Contract-Version} header, what a missing or wrong one answers, and that the refusal lands
 * before anything is looked up.
 *
 * <p>A web slice rather than a full boot because the whole guard is a web-layer decision — it is taken from
 * the request and the resolved handler method alone, with no pipeline, no grant check and no ClickHouse
 * involved. That is also the property under test: every service below is a mock, and a refused call must
 * reach none of them.
 *
 * <p>Filters are off here, so this class exercises only the request-side requirement; the response echo the
 * filter sets is asserted in {@code RunnerContractHeaderIntegrationTest}, which needs the real chain.
 */
@WebMvcTest(PipelineDataController.class)
@AutoConfigureMockMvc(addFilters = false)
class PipelineDataContractTest {

    /** The four runner-only surfaces, which require the header. */
    private static final String SCAN = "/v1/pipelines/tox-live/scan";
    private static final String GROUP_ROWS = "/v1/pipelines/tox-live/group-rows";
    private static final String MATERIALIZE = "/v1/pipelines/tox-live/materialize";
    private static final String ROWS = "/v1/pipelines/tox-live/rows";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PipelineService pipelines;
    @MockitoBean
    private PipelineMapper mapper;
    @MockitoBean
    private AggregateRefreshService refreshService;
    @MockitoBean
    private EnrichPreviewService enrichPreviewService;
    @MockitoBean
    private ScanService scanService;
    @MockitoBean
    private GroupFetchService groupFetchService;
    @MockitoBean
    private MaterializeService materializeService;
    @MockitoBean
    private EnrichWriteService writeService;
    @MockitoBean
    private RunnerContactMetrics contactMetrics;

    @BeforeEach
    void stubEveryHandlersServiceCall() {
        given(scanService.scan(anyString(), any(), any(), any(), any()))
                .willReturn(new ScanPage(List.of(), "c1", false, null));
        given(groupFetchService.fetch(anyString(), anyString(), any()))
                .willReturn(new GroupPage(List.of(), false));
        given(materializeService.materialize(anyString(), any(), any(), any(), any()))
                .willReturn(new MaterializePage(0L, "c1", false, null));
        given(writeService.write(anyString(), anyList())).willReturn(1);
    }

    /** A body that satisfies each endpoint's bean validation, so a 400 can never be mistaken for a 409. */
    private static MockHttpServletRequestBuilder call(String path) {
        final String body = switch (path) {
            case GROUP_ROWS -> "{\"group_key\":\"chat-1\"}";
            case ROWS -> "{\"rows\":[{\"tox_score\":1}]}";
            default -> "{}";
        };
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {SCAN, GROUP_ROWS, MATERIALIZE, ROWS})
    void noHeaderRefusesEveryRunnerEndpoint(String path) throws Exception {
        mockMvc.perform(call(path))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_contract_required"));

        // The point of refusing in preHandle: nothing below the web layer ran, so no cursor moved, no model
        // call was made and no read budget was spent.
        verifyNoInteractions(scanService, groupFetchService, materializeService, writeService, pipelines);
        verify(contactMetrics).recordRefusal(RunnerContractException.Reason.REQUIRED);
        verify(contactMetrics, never()).recordContact(anyString());
        // Decided before the pipeline lookup, so the only name in hand is the unvalidated path segment.
        verify(contactMetrics, never()).recordAttempt(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {SCAN, GROUP_ROWS, MATERIALIZE, ROWS})
    void wrongVersionRefusesEveryRunnerEndpoint(String path) throws Exception {
        mockMvc.perform(call(path).header(RunnerContract.HEADER, "999"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_contract_mismatch"))
                // Both versions, so the reader learns which side is behind without correlating deployments.
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString(RunnerContract.VERSION_VALUE),
                        Matchers.containsString("999"))));

        verifyNoInteractions(scanService, groupFetchService, materializeService, writeService, pipelines);
        verify(contactMetrics).recordRefusal(RunnerContractException.Reason.MISMATCH);
        verify(contactMetrics, never()).recordAttempt(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {SCAN, GROUP_ROWS, MATERIALIZE, ROWS})
    void matchingVersionIsServedAndRecordsContact(String path) throws Exception {
        mockMvc.perform(call(path).header(RunnerContract.HEADER, RunnerContract.VERSION_VALUE))
                .andExpect(status().isOk());

        // Recorded once the handler has succeeded, which is what makes the staleness series mean "the
        // runner last SUCCESSFULLY called", not "last tried".
        verify(contactMetrics).recordContact("tox-live");
        verify(contactMetrics).recordAttempt("tox-live");
        // A served call resolved the pipeline on its way, so the attempt costs no registry lookup of its own.
        verify(pipelines, never()).isEnabledEnrich(anyString());
    }

    /**
     * A call refused after the pipeline resolved — here for authorization and for a budget — moves the attempt
     * series and not contact, which is what separates "calls and is refused" from "is down".
     */
    @ParameterizedTest
    @ValueSource(strings = {SCAN, GROUP_ROWS, MATERIALIZE, ROWS})
    void refusedCallRecordsAnAttemptButNoContact(String path) throws Exception {
        given(pipelines.isEnabledEnrich("tox-live")).willReturn(true);
        final RuntimeException refusal = path.equals(SCAN) || path.equals(ROWS)
                ? new PipelineAccessDeniedException("no grant")
                : new PipelineReadBudgetExceededException("this read exceeded the 2GB memory ceiling", null);
        refuseEveryCall(refusal);

        mockMvc.perform(call(path).header(RunnerContract.HEADER, RunnerContract.VERSION_VALUE))
                .andExpect(refusal instanceof PipelineAccessDeniedException ? status().isForbidden()
                        : status().isUnprocessableContent());

        verify(contactMetrics).recordAttempt("tox-live");
        verify(contactMetrics, never()).recordContact(anyString());
    }

    /**
     * A disabled pipeline's refusal is no attempt: disabling dropped its series, and a straggling call must not
     * re-create one that then climbs for a pipeline nothing is meant to call.
     */
    @ParameterizedTest
    @ValueSource(strings = {MATERIALIZE, ROWS})
    void disabledPipelineRefusalRecordsNoAttempt(String path) throws Exception {
        given(pipelines.isEnabledEnrich("tox-live")).willReturn(false);
        refuseEveryCall(new PipelineConflictException("pipeline 'tox-live' is disabled"));

        mockMvc.perform(call(path).header(RunnerContract.HEADER, RunnerContract.VERSION_VALUE))
                .andExpect(status().isConflict());

        verify(contactMetrics, never()).recordAttempt(anyString());
        verify(contactMetrics, never()).recordContact(anyString());
    }

    /** A name that does not resolve as a pipeline creates no attempt series, and costs no second lookup. */
    @Test
    void unknownPipelineRecordsNoAttempt() throws Exception {
        refuseEveryCall(new PipelineNotFoundException("pipeline 'tox-live' not found"));

        mockMvc.perform(call(SCAN).header(RunnerContract.HEADER, RunnerContract.VERSION_VALUE))
                .andExpect(status().isNotFound());

        verify(contactMetrics, never()).recordAttempt(anyString());
        verify(pipelines, never()).isEnabledEnrich(anyString());
    }

    private void refuseEveryCall(RuntimeException refusal) {
        given(scanService.scan(anyString(), any(), any(), any(), any())).willThrow(refusal);
        given(groupFetchService.fetch(anyString(), anyString(), any())).willThrow(refusal);
        given(materializeService.materialize(anyString(), any(), any(), any(), any())).willThrow(refusal);
        given(writeService.write(anyString(), anyList())).willThrow(refusal);
    }

    /**
     * The refusal is decided before the pipeline is looked up, so a name that does not exist still answers
     * 409 rather than 404. It matters beyond ordering: a 404 would tell a skewed runner its pipeline is
     * gone, which is the one conclusion that would make it stop asking.
     */
    @Test
    void unknownPipelineWithNoHeaderStillAnswersContractRefusal() throws Exception {
        mockMvc.perform(post("/v1/pipelines/no-such-pipeline/scan")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("runner_contract_required"));

        verifyNoInteractions(pipelines, scanService);
    }

    @Test
    void previewNeedsNoHeader() throws Exception {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName("daily-rollup");
        pipeline.setKind(PipelineKind.AGGREGATE);
        given(pipelines.get("daily-rollup")).willReturn(pipeline);
        given(refreshService.preview("daily-rollup")).willReturn(new PipelinePreview("SELECT 1",
                List.of(new PreviewColumn("deployment", PreviewColumnRole.KEY, QueryFieldType.STRING,
                        QueryFieldType.STRING)),
                List.of(Map.of("deployment", "gpt-4")), 1L, null,
                List.of(PreviewNote.about(PreviewNoteCode.HELD_BY_ENRICHMENT, "usage_client_identity", "held"))));

        // The mapper is a mock in this slice; its preview mapping is plain code, so run it for real.
        given(mapper.toPreviewDto(any())).willCallRealMethod();
        // The shared shape on the wire: lowercase codes (the OpenAPI enumerates the same), severity derived
        // from the code, and absent members absent rather than null.
        mockMvc.perform(post("/v1/pipelines/daily-rollup/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statement").value("SELECT 1"))
                .andExpect(jsonPath("$.total_rows").value(1))
                .andExpect(jsonPath("$.columns[0].role").value("key"))
                .andExpect(jsonPath("$.columns[0].target_type").value("string"))
                .andExpect(jsonPath("$.notes[0].code").value("held_by_enrichment"))
                .andExpect(jsonPath("$.notes[0].severity").value("warning"))
                .andExpect(jsonPath("$.group_key").doesNotExist())
                .andExpect(jsonPath("$.sample_statement").doesNotExist())
                .andExpect(jsonPath("$.group_by").doesNotExist());
    }

    /** An empty aggregate sample still carries a zero count and empty arrays; unset members stay absent. */
    @Test
    void emptyAggregatePreviewKeepsZeroTotalAndEmptyArrays() throws Exception {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName("daily-rollup");
        pipeline.setKind(PipelineKind.AGGREGATE);
        given(pipelines.get("daily-rollup")).willReturn(pipeline);
        given(refreshService.preview("daily-rollup")).willReturn(new PipelinePreview(null,
                List.of(new PreviewColumn("deployment", PreviewColumnRole.KEY, QueryFieldType.STRING, null)),
                List.of(), 0L, null, List.of()));
        given(mapper.toPreviewDto(any())).willCallRealMethod();

        mockMvc.perform(post("/v1/pipelines/daily-rollup/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_rows").value(0))
                .andExpect(jsonPath("$.rows").isEmpty())
                .andExpect(jsonPath("$.notes").isEmpty())
                .andExpect(jsonPath("$.statement").doesNotExist())
                .andExpect(jsonPath("$.columns[0].target_type").doesNotExist())
                .andExpect(jsonPath("$.columns[0].max_bytes").doesNotExist());
    }

    @Test
    void refreshNeedsNoHeader() throws Exception {
        given(refreshService.refresh("daily-rollup")).willReturn(new RefreshOutcome(3L, false, 0L));

        mockMvc.perform(post("/v1/pipelines/daily-rollup/refresh")).andExpect(status().isOk());
    }

    /**
     * The aggregate write runs under {@code pipeline.budget.*}, and a refusal fails the run rather than
     * narrowing it; on demand that is the same deterministic 422 the enrichment reads answer.
     */
    @Test
    void refreshRefusedByTheBudgetAnswers422() throws Exception {
        given(refreshService.refresh("daily-rollup")).willThrow(new PipelineReadBudgetExceededException(
                "this aggregate refresh write exceeded the 2GB memory ceiling (pipeline.budget.*)", null));

        mockMvc.perform(post("/v1/pipelines/daily-rollup/refresh"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_read_budget_exceeded"));
    }

    /**
     * An operator surface is exempt from the contract, not from attribution: it is not a runner call, so it
     * must not move the series that says when the runner last called.
     */
    @Test
    void operatorSurfacesRecordNoRunnerContact() throws Exception {
        given(refreshService.refresh("daily-rollup")).willReturn(new RefreshOutcome(3L, false, 0L));

        mockMvc.perform(post("/v1/pipelines/daily-rollup/refresh")).andExpect(status().isOk());

        verify(contactMetrics, never()).recordContact(anyString());
        verify(contactMetrics, never()).recordRefusal(any());
    }

    /** Guards against the mock stubs above being silently wrong about the write path's arity. */
    @Test
    void writeBackReportsWhatTheServiceInserted() throws Exception {
        given(writeService.write(anyString(), anyList())).willReturn(7);

        mockMvc.perform(call(ROWS).header(RunnerContract.HEADER, RunnerContract.VERSION_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(7));

        verify(scanService, never()).scan(anyString(), any(), anyInt(), any(), any());
    }
}
