package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.PipelinePreviewBudgetExceededException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.PipelineAuthorizer;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichSpecValidator;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.preview.PipelinePreview;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNote;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNoteCode;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewValueCapper;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.PortableRenderers;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The enrich preview reports an over-budget read under the <b>preview's</b> code, though the read itself ran on
 * the runner's path and was refused under the runner's: the code follows the surface that issued the read, and
 * the remedies it names are ones the arm accepts.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrichPreviewServiceTest {

    private static final String NAME = "tox-live";
    private static final String CEILING = "the 1GB memory ceiling";

    @Mock
    private PipelineService pipelines;
    @Mock
    private PipelineAuthorizer authorizer;
    @Mock
    private EnrichResolver resolver;
    @Mock
    private EnrichSpecValidator validator;
    @Mock
    private ColumnMappingRepository columns;
    @Mock
    private ScanService scans;
    @Mock
    private GroupFetchService groups;
    @Mock
    private MaterializeService materialize;

    private EnrichPreviewService service;

    @BeforeEach
    void setUp() {
        service = new EnrichPreviewService(pipelines, authorizer, resolver, validator, columns, scans, groups,
                materialize, new PreviewValueCapper(new JsonMapperConfiguration().jsonMapper()),
                PortableRenderers.over(DialUsageLogTestModel.model()));
        given(resolver.composeSchema(any(), any())).willReturn(Map.of("type", "object"));
    }

    @Test
    void rowGrainReadOverItsBudgetIsTheAuthorsRefusal() {
        given(pipelines.getEnrich(NAME)).willReturn(enabledLlm(null));
        given(scans.previewRows(any(), anyInt(), any(), any())).willThrow(refusedByTheRunnerBudget());

        assertThatThrownBy(() -> service.preview(NAME, null, null, null))
                .isInstanceOf(PipelinePreviewBudgetExceededException.class)
                .hasMessageContaining("this pipeline's preview (preview sample) exceeded " + CEILING + " (pipeline.budget.*)")
                .hasMessageContaining("narrower `from`/`to` window");
    }

    @Test
    void groupReadOverItsBudgetOffersNoWindowTheArmWouldRefuse() {
        given(pipelines.getEnrich(NAME)).willReturn(enabledLlm("chat_id"));
        given(scans.pickGroup(any())).willThrow(refusedByTheRunnerBudget());

        assertThatThrownBy(() -> service.preview(NAME, null, null, null))
                .isInstanceOf(PipelinePreviewBudgetExceededException.class)
                .hasMessageContaining(CEILING)
                .hasMessageNotContaining("from`/`to")
                .hasMessageContaining("member_select");
    }

    @Test
    void class1ReadOverItsBudgetIsTheAuthorsRefusalToo() {
        final ResolvedEnrichPipeline sql = enabledLlm(null);
        sql.spec().getTransform().setType(TransformType.SQL);
        given(pipelines.getEnrich(NAME)).willReturn(sql);
        given(materialize.preview(any(), any(), any())).willThrow(refusedByTheRunnerBudget());

        assertThatThrownBy(() -> service.preview(NAME, null, null, null))
                .isInstanceOf(PipelinePreviewBudgetExceededException.class)
                .hasMessageContaining(CEILING);
    }

    @Test
    void namedGroupKeyIsUsedExactlyAsGiven() {
        given(pipelines.getEnrich(NAME)).willReturn(enabledLlm("chat_id"));
        given(groups.previewMembers(any(), anyString(), anyInt()))
                .willReturn(new GroupFetchService.MemberSample(List.of(), List.of(), 0, 200, true, 0));

        final PipelinePreview preview = service.preview(NAME, " chat-42", null, null);

        // Not trimmed into another group's key, and no fallback to choosing one.
        verify(groups).previewMembers(any(), eq(" chat-42"), anyInt());
        verify(scans, never()).pickGroup(any());
        assertThat(preview.groupKey()).isEqualTo(" chat-42");
    }

    @Test
    void emptyGroupKeyChoosesTheNewestGroup() {
        given(pipelines.getEnrich(NAME)).willReturn(enabledLlm("chat_id"));
        given(scans.pickGroup(any())).willReturn(new ScanService.GroupPick(null, null, List.of()));

        final PipelinePreview preview = service.preview(NAME, "", null, null);

        assertThat(preview.notes()).extracting(PreviewNote::code).containsExactly(PreviewNoteCode.NO_GROUP_FOUND);
    }

    @Test
    void membersSelectedButNotReadBackAreNotBlamedOnTheFilter() {
        given(pipelines.getEnrich(NAME)).willReturn(enabledLlm("chat_id"));
        given(groups.previewMembers(any(), anyString(), anyInt()))
                .willReturn(new GroupFetchService.MemberSample(List.of(), List.of(), 3, 200, true, 3));

        final PipelinePreview preview = service.preview(NAME, "chat-42", null, null);

        assertThat(preview.notes()).extracting(PreviewNote::code).doesNotContain(PreviewNoteCode.NO_MATCHING_ROWS);
    }

    @Test
    void rowGrainSampleWhollyPastAnEnrichmentsPositionIsHeldWithTheGap() {
        given(pipelines.getEnrich(NAME)).willReturn(enabledLlm(null));
        given(scans.previewRows(any(), anyInt(), any(), any())).willReturn(new ScanService.RowSample(List.of(),
                List.of(), null, new EnrichmentFreshness.Bound("usage_sentiment", 1_000L, "a"), 61_000L));

        final PipelinePreview preview = service.preview(NAME, null, null, null);

        assertThat(preview.notes()).singleElement().satisfies(note -> {
            assertThat(note.code()).isEqualTo(PreviewNoteCode.HELD_BY_ENRICHMENT);
            assertThat(note.message()).contains("1m past it").doesNotContain("no usable materialized position");
        });
    }

    /**
     * A named group's member read is unclamped, but an enrichment with no bound at all is reported on every arm —
     * asked of the scan's plan, so the members are still sampled and no group is chosen.
     */
    @Test
    void namedGroupHeldByAnEnrichmentIsSampledAndSaysSo() {
        given(pipelines.getEnrich(NAME)).willReturn(enabledLlm("chat_id"));
        given(scans.withholdingEnrichment(any())).willReturn("usage_sentiment");
        given(groups.previewMembers(any(), anyString(), anyInt())).willReturn(new GroupFetchService.MemberSample(
                List.of(Map.of("deployment", "gpt-4o")), List.of(), 1, 200, true, 1));

        final PipelinePreview preview = service.preview(NAME, "chat-42", null, null);

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.notes()).singleElement().satisfies(note -> {
            assertThat(note.code()).isEqualTo(PreviewNoteCode.HELD_BY_ENRICHMENT);
            assertThat(note.subject()).isEqualTo("usage_sentiment");
        });
        verify(scans, never()).pickGroup(any());
    }

    /** A whitespace-only key is a key, as the group arm reads it, so a row-grain arm refuses it rather than ignoring it. */
    @Test
    void blankGroupKeyOnRowGrainIsRefused() {
        given(pipelines.getEnrich(NAME)).willReturn(enabledLlm(null));

        assertThatThrownBy(() -> service.preview(NAME, "  ", null, null))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("`group_key`");
        verify(scans, never()).previewRows(any(), anyInt(), any(), any());
    }

    @Test
    void emptyGroupKeyOnRowGrainIsAbsent() {
        given(pipelines.getEnrich(NAME)).willReturn(enabledLlm(null));
        given(scans.previewRows(any(), anyInt(), any(), any()))
                .willReturn(new ScanService.RowSample(List.of(), List.of(), null, null, null));

        service.preview(NAME, "", null, null);

        verify(scans).previewRows(any(), anyInt(), any(), any());
    }

    /** What the shared read path raises: the runner's code, with the ceiling held apart from its message. */
    private static PipelineReadBudgetExceededException refusedByTheRunnerBudget() {
        return new PipelineReadBudgetExceededException("this preview sample exceeded " + CEILING
                + " (pipeline.budget.*). reduce the heavy columns", CEILING, "preview sample", null);
    }

    /** An enabled {@code llm} pipeline, so the preview runs only its enabled checks; grouped when {@code groupBy} is set. */
    private static ResolvedEnrichPipeline enabledLlm(String groupBy) {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(NAME);
        pipeline.setTarget("tox_label");
        pipeline.setInputTable("dial_usage_log");
        pipeline.setEnabled(true);
        if (groupBy != null) {
            pipeline.setTriggerKind(TriggerKind.GROUP);
            pipeline.setTriggerGroupBy(groupBy);
        } else {
            pipeline.setTriggerKind(TriggerKind.SCHEDULE);
        }
        final EnrichSpec spec = new EnrichSpec();
        // Setting the bindings creates the transform they live in.
        spec.setInputBindings(List.of());
        final TransformOutput output = new TransformOutput();
        output.setName("label");
        output.setProse("The label.");
        spec.getTransform().setType(TransformType.LLM);
        spec.getTransform().setOutputs(List.of(output));
        return new ResolvedEnrichPipeline(pipeline, spec, "dial_usage_log", "event_id", "_ingested_at");
    }
}
