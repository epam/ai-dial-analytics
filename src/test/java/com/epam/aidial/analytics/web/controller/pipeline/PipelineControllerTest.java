package com.epam.aidial.analytics.web.controller.pipeline;

import com.epam.aidial.analytics.service.audit.AuditPipelineSnapshotService;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineFreshnessDiagnostics;
import com.epam.aidial.analytics.service.pipeline.PipelineJsonReadNotes;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.model.PipelineEnabledFilter;
import com.epam.aidial.analytics.service.pipeline.model.PipelineRegistration;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineResponses.PipelineListDto;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web-layer contract of the merged listing — the four query parameters, and the two that need
 * <b>custom binding</b> to behave.
 *
 * <p>A slice rather than a full boot because what is under test is the binding, not the registry: both
 * failures below are silent at the service layer. A lenient `enabled` reports the whole registry under
 * what looks like a filter, and a `kind` that will not convert answers 400 for the only spelling the API
 * publishes. Neither would fail a test that went through the service with a stubbed value.
 *
 * <p>Also covers the <b>projection</b> rule, which is the same class of failure one step further on: a
 * {@code view=compiled} this read cannot resolve used to answer 200 with the entries unresolved under the
 * compiled name — not an obviously wrong shape but a plausible one, and the precise shape a consumer turns
 * into a silent data loss. It is refused now, and the refusal is a web-layer decision like the binders.
 */
@WebMvcTest(PipelineController.class)
@AutoConfigureMockMvc(addFilters = false)
class PipelineControllerTest {

    private static final String PIPELINES = "/v1/pipelines";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PipelineService service;
    @MockitoBean
    private PipelineFreshnessDiagnostics freshness;
    @MockitoBean
    private PipelineJsonReadNotes jsonReadNotes;
    @MockitoBean
    private PipelineMapper mapper;
    @MockitoBean
    private AuditPipelineSnapshotService snapshotService;

    private void listReturnsNothing() {
        given(service.list(any(), any(), any(), any())).willReturn(List.of());
        given(service.listEnrich(any(), any(), any())).willReturn(List.of());
        given(freshness.of(anyList())).willReturn(java.util.Map.of());
        given(mapper.toListDto(anyList(), any(), any())).willReturn(new PipelineListDto(List.of()));
        given(mapper.toResolvedListDto(anyList(), any(), any())).willReturn(new PipelineListDto(List.of()));
    }

    @Test
    void allFourFiltersReachTheService() throws Exception {
        listReturnsNothing();

        mockMvc.perform(get(PIPELINES)
                        .param("target", "usage_daily")
                        .param("kind", "aggregate")
                        .param("enabled", "true")
                        .param("updated_since", "2026-07-01T00:00:00Z"))
                .andExpect(status().isOk());

        // Applied together. Narrowing on one and silently dropping another would return rows the caller
        // did not ask for and look like data rather than a mistake.
        verify(service).list("usage_daily", PipelineKind.AGGREGATE, PipelineEnabledFilter.ENABLED,
                Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void anAbsentFilterDoesNotFilter() throws Exception {
        listReturnsNothing();

        mockMvc.perform(get(PIPELINES)).andExpect(status().isOk());

        verify(service).list(null, null, PipelineEnabledFilter.ANY, null);
    }

    @Test
    void theEnrichFeedTakesTheResolvedPath() throws Exception {
        listReturnsNothing();

        mockMvc.perform(get(PIPELINES).param("kind", "enrich").param("enabled", "true")
                        .param("view", "compiled"))
                .andExpect(status().isOk());

        // The enrich feed resolves the response schema, grain key and version column in a constant number of
        // queries; a cross-kind listing does not, because resolving an aggregate pipeline would cost
        // lookups for fields it has none of.
        verify(service).listEnrich(null, PipelineEnabledFilter.ENABLED, null);
        verify(service, never()).list(any(), eq(PipelineKind.ENRICH), any(), any());
    }

    @Test
    void theAuthoredListingOfEnrichPipelinesPaysForNoResolution() throws Exception {
        listReturnsNothing();

        // Same filter, no `view`: the default projection serves nothing resolution produces, so paying
        // for it would buy a discarded result. This is the assertion that keeps `?kind=enrich` a cheap
        // operator listing rather than the runner's feed by accident.
        mockMvc.perform(get(PIPELINES).param("kind", "enrich").param("enabled", "true"))
                .andExpect(status().isOk());

        verify(service, never()).listEnrich(any(), any(), any());
        verify(service).list(null, PipelineKind.ENRICH, PipelineEnabledFilter.ENABLED, null);
    }

    @Test
    void theEnrichFeedStaysResolvedWhenNarrowedByTarget() throws Exception {
        listReturnsNothing();

        mockMvc.perform(get(PIPELINES).param("kind", "enrich").param("target", "usage_sentiment")
                        .param("view", "compiled"))
                .andExpect(status().isOk());

        // Every filter rides along resolved. Sending the target-filtered listing down the unresolved path
        // would describe the same pipeline with its transform, grain key, version column and resolved
        // input all absent — one resource with two shapes, and the narrower one silently missing the
        // fields a caller added a filter in order to read.
        verify(service).listEnrich("usage_sentiment", PipelineEnabledFilter.ANY, null);
        verify(service, never()).list(any(), eq(PipelineKind.ENRICH), any(), any());
    }

    @Test
    void kindBindsByItsWireSpellingAndRejectsAnythingElse() throws Exception {
        listReturnsNothing();

        // The published spelling is lower-case (`@JsonValue`), while the framework's enum converter is
        // Enum.valueOf on the constant name — so without a custom binder the only form a client has ever
        // seen would not convert.
        mockMvc.perform(get(PIPELINES).param("kind", "enrich")).andExpect(status().isOk());
        mockMvc.perform(get(PIPELINES).param("kind", "AGGREGATE")).andExpect(status().isOk());
        mockMvc.perform(get(PIPELINES).param("kind", "rollup")).andExpect(status().isBadRequest());
    }

    @Test
    void enabledBindsStrictlyOrFailsTheRequest() throws Exception {
        listReturnsNothing();

        mockMvc.perform(get(PIPELINES).param("enabled", "true")).andExpect(status().isOk());
        mockMvc.perform(get(PIPELINES).param("enabled", "false")).andExpect(status().isOk());
        // The framework accepts all of these; a filter must not. The empty value is the one a caller
        // actually hits, by interpolating an unset variable.
        for (final String lenient : List.of("1", "yes", "on", "TRUE", "")) {
            mockMvc.perform(get(PIPELINES).param("enabled", lenient))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anUnparseableUpdatedSinceFailsTheRequest() throws Exception {
        listReturnsNothing();

        mockMvc.perform(get(PIPELINES).param("updated_since", "last tuesday"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void compiledListingWithoutEnrichFilterIsRefused() throws Exception {
        listReturnsNothing();

        // No kind at all, and the wrong kind, are the same mistake: only `enrich` resolves anything, and a
        // cross-kind listing would cost a per-pipeline lookup for entries with nothing to resolve.
        mockMvc.perform(get(PIPELINES).param("view", "compiled"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message", containsString("kind=enrich")));
        mockMvc.perform(get(PIPELINES).param("kind", "aggregate").param("view", "compiled"))
                .andExpect(status().isBadRequest());

        // Refused before the registry is touched — it is the parameter combination that is wrong, not
        // anything stored.
        verify(service, never()).list(any(), any(), any(), any());
        verify(service, never()).listEnrich(any(), any(), any());
    }

    @Test
    void compiledReadOfAnAggregatePipelineIsRefused() throws Exception {
        given(service.get("daily-rollup")).willReturn(pipelineOfKind("daily-rollup", PipelineKind.AGGREGATE));

        mockMvc.perform(get(PIPELINES + "/daily-rollup").param("view", "compiled"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                // Naming the kind, so the reader learns why rather than only that.
                .andExpect(jsonPath("$.message", containsString("aggregate")));

        // Refused on the kind before anything is resolved: the compiled read resolves through
        // getEnrichCompiled, so that is the call that must not happen (getEnrich is pinned too, as the
        // resolution a future refactor could route back through).
        verify(service, never()).getEnrichCompiled(any());
        verify(service, never()).getEnrich(any());
    }

    @Test
    void theAuthoredProjectionIsUnaffectedForEveryKind() throws Exception {
        listReturnsNothing();
        for (final PipelineKind kind : PipelineKind.values()) {
            given(service.get("p")).willReturn(pipelineOfKind("p", kind));

            mockMvc.perform(get(PIPELINES + "/p")).andExpect(status().isOk());
            mockMvc.perform(get(PIPELINES + "/p").param("view", "source")).andExpect(status().isOk());
            mockMvc.perform(get(PIPELINES).param("kind", kind.code()).param("view", "source"))
                    .andExpect(status().isOk());
        }
    }

    /**
     * Registration is on the collection, not the pipeline-scoped prefix, so the runner contract does not
     * apply to it — and this pins the interceptor's path pattern, which is the easy thing to get wrong: a
     * Spring {@code /v1/pipelines/**} matches zero trailing segments too, and would refuse every
     * registration with 409 before the body was even read.
     */
    @Test
    void registrationDoesNotRequireTheRunnerContractHeader() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A registration of identity and target alone binds: every other member is optional on the wire, and
     * nothing at the binding refuses a draft before the mapper and service decide what it is.
     */
    @Test
    void registrationOfIdentityAndTargetAloneBinds() throws Exception {
        final PipelineEntity created = pipelineOfKind("draft", PipelineKind.AGGREGATE);
        given(service.create(any())).willReturn(created);

        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"draft\",\"kind\":\"aggregate\",\"target\":\"usage_daily\"}"))
                .andExpect(status().isCreated());
    }

    /**
     * The controller hands the mapper the stored pipeline itself — the entity it just read, not fields picked
     * off it — and the service the mapper's registration with the patch's own {@code enabled}. Whether the
     * patch leaves the pipeline armed is the mapper's question, answered from those two.
     */
    @Test
    void patchHandsTheMapperTheStoredPipelineAndTheServiceItsRegistration() throws Exception {
        final PipelineEntity disabled = pipelineOfKind("draft", PipelineKind.AGGREGATE);
        disabled.setTarget("usage_daily");
        final PipelineRegistration registration = new PipelineRegistration("draft", null, null, null, null,
                null, null, null, null, null, null, false);
        given(service.get("draft")).willReturn(disabled);
        given(mapper.toRegistration(same(disabled), any(), any())).willReturn(registration);
        given(service.patch(any(), any(), any())).willReturn(disabled);

        mockMvc.perform(patch(PIPELINES + "/draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measures\":[{\"name\":\"n\",\"fn\":\"count\"}],\"enabled\":true}"))
                .andExpect(status().isOk());

        verify(mapper).toRegistration(same(disabled),
                argThat(request -> Boolean.TRUE.equals(request.enabled()) && request.measures().size() == 1), any());
        verify(service).patch(eq("draft"), same(registration), eq(Boolean.TRUE));
    }

    /** A pipeline entity carrying only what the read path dereferences. */
    private static PipelineEntity pipelineOfKind(String name, PipelineKind kind) {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(name);
        pipeline.setKind(kind);
        return pipeline;
    }
}
