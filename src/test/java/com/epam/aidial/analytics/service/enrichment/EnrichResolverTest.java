package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.ResponseSchemaComposer;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
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
 * How an ENRICH pipeline's <b>read source</b> resolves, and why the batched feed has to resolve it the
 * same way the per-pipeline reads do.
 *
 * <p>A declared input wins; an omitted one follows the target enrichment's parent. Getting that wrong is
 * not a visible failure: every governed read, every SQL fragment the gate validates, and every version
 * column the feed publishes would simply be about a different table than the one the pipeline scans.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrichResolverTest {

    private static final String TARGET = "usage_toxicity";

    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private ColumnMappingRepository columns;

    private EnrichResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new EnrichResolver(definitions,
                new PipelineSpecCodec(new JsonMapperConfiguration().jsonMapper()),
                columns, new ResponseSchemaComposer());
    }

    private static PipelineEntity pipeline(String name, String target, String declaredInput) {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(name);
        pipeline.setKind(PipelineKind.ENRICH);
        pipeline.setTarget(target);
        pipeline.setInputTable(declaredInput);
        pipeline.setTriggerKind(TriggerKind.SCHEDULE);
        pipeline.setTriggerCron("0 * * * * *");
        return pipeline;
    }

    private static TableDefinitionEntity table(String name, String sourceTable, String versionColumn) {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(name);
        definition.setSourceTable(sourceTable);
        definition.setVersionColumn(versionColumn);
        return definition;
    }

    @Test
    void declaredInputWinsOverTheTargetsParent() {
        given(definitions.findById(TARGET))
                .willReturn(Optional.of(table(TARGET, "dial_usage_log", null)));

        // The pipeline reads what it declared, not what its target enrichment hangs off — which is the
        // whole point of the column being separate from the enrichment's read-side attachment.
        assertThat(resolver.sourceOf(pipeline("toxicity-live", TARGET, "rate_analytics")))
                .isEqualTo("rate_analytics");
    }

    @Test
    void omittedInputFollowsTheTargetsParent() {
        given(definitions.findById(TARGET))
                .willReturn(Optional.of(table(TARGET, "dial_usage_log", null)));

        assertThat(resolver.sourceOf(pipeline("toxicity-live", TARGET, null)))
                .isEqualTo("dial_usage_log");
    }

    @Test
    void targetThatResolvesToNothingYieldsNoSource() {
        given(definitions.findById(TARGET)).willReturn(Optional.empty());

        // Tolerant by design: a pipeline read must not fail because its target has drifted — the scan path
        // itself rejects an unscannable source with the 422 it deserves.
        assertThat(resolver.sourceOf(pipeline("toxicity-live", TARGET, null))).isNull();
    }

    @Test
    void theFeedToleratesOnePipelineWhoseTargetHasNoResolvableSource() {
        given(definitions.findAllById(any())).willReturn(List.of());

        // No target definition means no source, so no version column to look up either. The feed must still
        // resolve the pipeline rather than failing the whole sync over one unresolvable target.
        assertThat(resolver.resolveAll(List.of(pipeline("toxicity-live", TARGET, null))))
                .singleElement()
                .satisfies(resolved -> {
                    assertThat(resolved.source()).isNull();
                    assertThat(resolved.versionColumn()).isNull();
                });
    }

    @Test
    void theFeedResolvesEachPipelineAgainstItsOwnReadSource() {
        final PipelineEntity declaring = pipeline("declaring-live", TARGET, "rate_analytics");
        final PipelineEntity deriving = pipeline("deriving-live", TARGET, null);
        given(definitions.findAllById(any())).willReturn(
                List.of(table(TARGET, "dial_usage_log", null)),
                List.of(table("dial_usage_log", null, "_ingested_at"),
                        table("rate_analytics", null, "_rated_at")));

        // Each pipeline carries the version column of the table IT reads: batching over the targets'
        // source_table would hand the declaring pipeline dial_usage_log's column.
        assertThat(resolver.resolveAll(List.of(declaring, deriving)))
                .satisfiesExactly(
                        first -> {
                            assertThat(first.source()).isEqualTo("rate_analytics");
                            assertThat(first.versionColumn()).isEqualTo("_rated_at");
                        },
                        second -> {
                            assertThat(second.source()).isEqualTo("dial_usage_log");
                            assertThat(second.versionColumn()).isEqualTo("_ingested_at");
                        });
    }

    @Test
    void theWritePathResolvesGrainOnlySoItPaysForNothingElse() {
        given(definitions.findById(TARGET))
                .willReturn(Optional.of(table(TARGET, "dial_usage_log", null)));

        final ResolvedEnrichPipeline resolved =
                resolver.resolveForWrite(pipeline("toxicity-live", TARGET, null));

        // Queries per write saved by not resolving what the write never reads. Asserted rather than
        // assumed, because an eager resolve here is invisible except as latency. There is no evaluator
        // left to omit: the transform arrives inside the declaration, at no extra cost.
        assertThat(resolved.grainKey()).isNull();
        assertThat(resolved.source()).isNull();
        assertThat(resolved.versionColumn()).isNull();
        assertThat(resolved.spec()).isNotNull();
    }
}
