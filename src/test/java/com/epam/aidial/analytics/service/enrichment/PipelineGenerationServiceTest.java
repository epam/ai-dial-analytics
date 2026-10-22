package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichSpecValidator;
import com.epam.aidial.analytics.service.pipeline.AggregateSpecValidator;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The cascaded generation bumps log one line per pipeline, with its old and new generation and the cause —
 * never only a count, and never an invented actor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineGenerationServiceTest {

    @Mock
    private PipelineRepository pipelines;
    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private EnrichSpecValidator enrichGovernance;
    @Mock
    private PipelineSpecCodec specCodec;
    @Mock
    private AggregateSpecValidator aggregateGovernance;

    private PipelineGenerationService service;

    @BeforeEach
    void setUp() {
        service = new PipelineGenerationService(pipelines, definitions, enrichGovernance, specCodec, aggregateGovernance);
    }

    private static PipelineEntity enrich(String name, long generation) {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(name);
        pipeline.setKind(PipelineKind.ENRICH);
        pipeline.setEnabled(true);
        pipeline.setGeneration(generation);
        return pipeline;
    }

    @Test
    void versionColumnRepointLogsOneLinePerBumpedPipeline() {
        given(definitions.findBySourceTable("dial_usage_log")).willReturn(List.of());
        given(pipelines.findByInputTableAndKind("dial_usage_log", PipelineKind.ENRICH))
                .willReturn(List.of(enrich("alpha", 1), enrich("beta", 4), enrich("gamma", 9)));

        final List<String> lines;
        try (LogCapture capture = LogCapture.of(PipelineGenerationService.class)) {
            service.onVersionColumnRepointed("dial_usage_log");
            lines = capture.messagesAt(Level.INFO).stream().filter(line -> line.contains(" generation ")).toList();
        }

        assertThat(lines).containsExactly(
                "pipeline alpha generation 1 -> 2 (cause=version_column_repoint, actor=-)",
                "pipeline beta generation 4 -> 5 (cause=version_column_repoint, actor=-)",
                "pipeline gamma generation 9 -> 10 (cause=version_column_repoint, actor=-)");
    }

    @Test
    void targetColumnChangeLogsTheBumpOfAnLlmPipeline() {
        final PipelineEntity pipeline = enrich("labeller", 3);
        pipeline.setTarget("usage_labels");
        final EnrichSpec spec = new EnrichSpec();
        spec.mutableTransform().setType(TransformType.LLM);
        given(pipelines.findByTarget("usage_labels")).willReturn(Optional.of(pipeline));
        given(specCodec.readEnrich(pipeline)).willReturn(spec);
        given(specCodec.write(any())).willReturn("{}");
        given(enrichGovernance.rederiveOutputBindings(any())).willReturn(Optional.of(TransformType.LLM));

        final List<String> lines;
        try (LogCapture capture = LogCapture.of(PipelineGenerationService.class)) {
            service.onTargetColumnsChanged("usage_labels");
            lines = capture.messagesAt(Level.INFO);
        }

        assertThat(pipeline.getGeneration()).isEqualTo(4);
        assertThat(lines).contains("pipeline labeller generation 3 -> 4 (cause=target_columns_changed, actor=-)");
    }
}
