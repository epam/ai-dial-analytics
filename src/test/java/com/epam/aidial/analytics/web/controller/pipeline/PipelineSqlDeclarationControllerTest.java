package com.epam.aidial.analytics.web.controller.pipeline;

import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.audit.AuditPipelineSnapshotService;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineFreshnessDiagnostics;
import com.epam.aidial.analytics.service.pipeline.PipelineJsonReadNotes;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapperImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The wire contract of the {@code aggregate} SQL declaration: {@code sql} is exclusive with the form-mode members
 * on every write, a target mismatch surfaces as the pipeline validation code, and the strict binding still refuses
 * a member no kind declares. The <b>real</b> mapper runs here, because the exclusivity refusal is the mapper's and
 * a stubbed one would assert nothing about it.
 */
@WebMvcTest(PipelineController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({PipelineMapperImpl.class, PipelineSpecCodec.class})
class PipelineSqlDeclarationControllerTest {

    private static final String PIPELINES = "/v1/pipelines";
    private static final String SQL = "INSERT INTO usage_daily (deployment, call_count) "
            + "SELECT deployment, count(*) FROM dial_usage_log GROUP BY 1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PipelineSpecCodec specCodec;

    @MockitoBean
    private PipelineService service;
    @MockitoBean
    private PipelineFreshnessDiagnostics freshness;
    @MockitoBean
    private PipelineJsonReadNotes jsonReadNotes;
    @MockitoBean
    private AuditPipelineSnapshotService snapshotService;

    private static String sqlRegistration(String extra) {
        return "{\"name\": \"usage-daily\", \"kind\": \"aggregate\", \"target\": \"usage_daily\", \"sql\": \"" + SQL
                + "\"" + extra + "}";
    }

    @Test
    void sqlBesideGroupByIsRefusedNamingGroupBy() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content(sqlRegistration(", \"group_by\": [{\"column\": \"deployment\"}]")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message", containsString("group_by")));

        verify(service, never()).create(any());
    }

    @Test
    void sqlBesideFilterIsRefusedNamingFilter() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content(sqlRegistration(", \"filter\": \"deployment = 'gpt-4o'\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message", containsString("filter")));

        verify(service, never()).create(any());
    }

    @Test
    void sqlBesideMeasuresOnPatchIsRefusedNamingMeasures() throws Exception {
        final PipelineEntity stored = new PipelineEntity();
        stored.setName("usage-daily");
        stored.setKind(PipelineKind.AGGREGATE);
        stored.setTarget("usage_daily");
        given(service.get("usage-daily")).willReturn(stored);

        mockMvc.perform(patch(PIPELINES + "/usage-daily").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\": \"" + SQL + "\", \"measures\": [{\"name\": \"call_count\", \"fn\": \"count\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message", containsString("measures")));

        verify(service, never()).patch(any(), any(), any());
    }

    @Test
    void targetMismatchAtArmingIsPipelineValidationFailure() throws Exception {
        final PipelineEntity stored = new PipelineEntity();
        stored.setName("usage-daily");
        stored.setKind(PipelineKind.AGGREGATE);
        stored.setTarget("usage_weekly");
        given(service.get("usage-daily")).willReturn(stored);
        // The comparison is the arm gate's (AggregateSpecValidator.validateSql); this pins how it reaches the wire.
        given(service.patch(any(), any(), any())).willThrow(new PipelineValidationException("sql inserts into "
                + "'usage_daily', but the pipeline's target is 'usage_weekly'"));

        mockMvc.perform(patch(PIPELINES + "/usage-daily").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message", containsString("'usage_weekly'")));
    }

    @Test
    void unknownFieldBesideSqlIsBindingFailure() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content(sqlRegistration(", \"sql_mode\": true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message", containsString("sql_mode")));

        verify(service, never()).create(any());
    }

    @Test
    void blankSqlIsRefused() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"usage-daily\", \"kind\": \"aggregate\", \"target\": \"usage_daily\", \"sql\": \"  \"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value("sql must not be blank"));

        verify(service, never()).create(any());
    }

    @Test
    void inputsOnlyPatchOfEnabledSqlPipelineIsTheServicesConflict() throws Exception {
        final PipelineEntity stored = new PipelineEntity();
        stored.setName("usage-daily");
        stored.setKind(PipelineKind.AGGREGATE);
        stored.setTarget("usage_daily");
        stored.setInputTable("dial_usage_log");
        stored.setEnabled(true);
        final AggregateSpec spec = new AggregateSpec();
        spec.setSql(SQL);
        stored.setSpec(specCodec.write(spec));
        given(service.get("usage-daily")).willReturn(stored);
        given(service.patch(any(), any(), any())).willThrow(new PipelineConflictException("pipeline 'usage-daily' is enabled"));

        mockMvc.perform(patch(PIPELINES + "/usage-daily").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\": [\"dial_usage_log\"]}"))
                .andExpect(status().isConflict());
    }
}
