package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.configuration.ScanProperties;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The freshness arithmetic, which is where this capability's silent failures would live: every case below
 * is one where the wrong answer produces a rollup that looks healthy — either permanently stalled with no
 * operator action that releases it, or reading enrichment values that are not there yet.
 */
@ExtendWith(MockitoExtension.class)
class EnrichmentFreshnessTest {

    private static final String INPUT = "dial_usage_log";
    private static final String ENRICHMENT = "usage_client_identity";
    private static final Duration SAFETY_LAG = Duration.ofSeconds(5);
    private static final JsonMapper JSON = new JsonMapperConfiguration().jsonMapper();

    @Mock
    private PipelineRepository pipelines;

    private EnrichmentFreshness freshness;

    /** A data-sync target that is an enrichment of the input — the shape of {@code dial_usage_log_payload}. */
    private static final String INGEST_ENRICHMENT = "dial_usage_log_payload";

    @BeforeEach
    void setUp() {
        final List<SyncTarget> syncTargets = List.of(
                new SyncTarget(INPUT, null, null),
                new SyncTarget(INGEST_ENRICHMENT, null, null));
        freshness = new EnrichmentFreshness(pipelines, new PipelineSpecCodec(JSON),
                new ScanProperties(SAFETY_LAG, 100, 1000), syncTargets);
    }

    // --- classification -----------------------------------------------------------------------------

    @Test
    void class1PipelineOnTheReadersOwnInputCanBeClampedAgainst() {
        registered(sqlPipeline(entity -> entity.setInputTable(null)));

        assertThat(freshness.of(ENRICHMENT, INPUT))
                .isEqualTo(EnrichmentFreshness.Clampability.CLAMPABLE);
    }

    @Test
    void enrichmentWithNoPipelineImposesNoClamp() {
        given(pipelines.findByTarget(ENRICHMENT)).willReturn(Optional.empty());

        final EnrichmentFreshness.Clampability clampability = freshness.of(ENRICHMENT, INPUT);

        // Not refused, and not clamped: an unwritten enrichment will never gain another row, so its absent
        // values are final rather than late. Clamping would freeze every dependent rollup permanently, with
        // no operator action that released it.
        assertThat(clampability).isEqualTo(EnrichmentFreshness.Clampability.NO_PIPELINE);
        assertThat(clampability.clamps()).isFalse();
        assertThat(clampability.refused()).isFalse();
    }

    /**
     * An enrichment no pipeline targets but a data-sync mapping does is not "unwritten": data-sync writes it
     * on every run. It still imposes no clamp — the run sink flushes it ahead of its source, so a visible
     * source row already has its row — but it is classified apart so the reported reason is the true one.
     * The old {@code no_pipeline} rationale ("nothing writes it") was false for this table.
     */
    @Test
    void enrichmentWrittenByDataSyncIsIngestWrittenNotUnwritten() {
        given(pipelines.findByTarget(INGEST_ENRICHMENT)).willReturn(Optional.empty());

        final EnrichmentFreshness.Clampability clampability = freshness.of(INGEST_ENRICHMENT, INPUT);

        assertThat(clampability).isEqualTo(EnrichmentFreshness.Clampability.INGEST_WRITTEN);
        assertThat(clampability.clamps()).isFalse();
        assertThat(clampability.refused()).isFalse();
        assertThat(clampability.code()).isEqualTo("ingest_written");
        assertThat(clampability.reason()).contains("data-sync writes it");
    }

    @Test
    void classifyTellsIngestWrittenFromUnwrittenInOnePass() {
        given(pipelines.findByTargetIn(any())).willReturn(List.of());

        final Map<String, EnrichmentFreshness.Clampability> classified =
                freshness.classify(List.of(ENRICHMENT, INGEST_ENRICHMENT), INPUT);

        assertThat(classified).containsEntry(ENRICHMENT, EnrichmentFreshness.Clampability.NO_PIPELINE)
                .containsEntry(INGEST_ENRICHMENT, EnrichmentFreshness.Clampability.INGEST_WRITTEN);
    }

    @Test
    void groupTriggeredPipelineIsRefused() {
        registered(sqlPipeline(entity -> {
            entity.setTriggerKind(TriggerKind.GROUP);
            entity.setTriggerGroupBy("chat_id");
        }));

        final EnrichmentFreshness.Clampability clampability = freshness.of(ENRICHMENT, INPUT);

        assertThat(clampability).isEqualTo(EnrichmentFreshness.Clampability.GROUP_TRIGGER);
        assertThat(clampability.refused()).isTrue();
    }

    @Test
    void pipelineAdasDoesNotExecuteIsRefused() {
        registered(pipeline(TransformType.LLM, entity -> { }));

        final EnrichmentFreshness.Clampability clampability = freshness.of(ENRICHMENT, INPUT);

        assertThat(clampability).isEqualTo(EnrichmentFreshness.Clampability.NOT_CLASS1);
        assertThat(clampability.refused()).isTrue();
    }

    @Test
    void draftWriterDeclaringNoTransformIsUndeclaredAndAdmittedWithoutClamp() {
        // A disabled draft that has not declared its transform writes nothing. Classifying it NOT_CLASS1
        // would refuse every pipeline reading the enrichment with "its transform is not 'sql'" — true of
        // nothing — and stop an armed rollup refreshing because somebody began authoring beside it.
        final PipelineEntity draft = pipeline(TransformType.LLM, entity -> {
            entity.setSpec("{}");
            entity.setTriggerKind(null);
            entity.setEnabled(false);
        });
        registered(draft);

        final EnrichmentFreshness.Clampability clampability = freshness.of(ENRICHMENT, INPUT);

        assertThat(clampability).isEqualTo(EnrichmentFreshness.Clampability.UNDECLARED_WRITER);
        assertThat(clampability.refused()).isFalse();
        assertThat(clampability.clamps()).isFalse();
        assertThat(clampability.code()).isEqualTo("undeclared_writer");
        assertThat(clampability.reason()).contains("draft");
    }

    @Test
    void groupTriggeredDraftDeclaringNoTransformIsUndeclaredRatherThanRefused() {
        // A first draft saved with only `{trigger: {kind: group}}` has declared no writer yet; reading its
        // trigger first would refuse every armed rollup reading the enrichment over it.
        registered(pipeline(TransformType.LLM, entity -> {
            entity.setSpec("{}");
            entity.setTriggerKind(TriggerKind.GROUP);
            entity.setEnabled(false);
        }));

        final EnrichmentFreshness.Clampability clampability = freshness.of(ENRICHMENT, INPUT);

        assertThat(clampability).isEqualTo(EnrichmentFreshness.Clampability.UNDECLARED_WRITER);
        assertThat(clampability.refused()).isFalse();
    }

    @Test
    void draftFragmentDeclaringOnlyTheTransformTypeIsUndeclared() {
        // `{transform: {type: sql}}` declares a type and nothing it would write: classifying it by the type
        // alone would make it a Class-1 writer with no recorded position and freeze every armed rollup
        // reading the enrichment at zero rows over a declaration nobody has finished.
        registered(pipeline(TransformType.SQL, entity -> {
            entity.setSpec(fragmentWithTransformType(TransformType.SQL));
            entity.setEnabled(false);
        }));

        final EnrichmentFreshness.Clampability clampability = freshness.of(ENRICHMENT, INPUT);

        assertThat(clampability).isEqualTo(EnrichmentFreshness.Clampability.UNDECLARED_WRITER);
        assertThat(clampability.clamps()).isFalse();
        assertThat(clampability.refused()).isFalse();
    }

    @Test
    void incompleteGroupTriggeredDraftIsUndeclaredRatherThanRefused() {
        registered(pipeline(TransformType.LLM, entity -> {
            entity.setSpec(fragmentWithTransformType(TransformType.LLM));
            entity.setTriggerKind(TriggerKind.GROUP);
            entity.setEnabled(false);
        }));

        assertThat(freshness.of(ENRICHMENT, INPUT))
                .isEqualTo(EnrichmentFreshness.Clampability.UNDECLARED_WRITER);
    }

    @Test
    void completeDraftIsClassifiedByWhatItDeclares() {
        registered(sqlPipeline(entity -> entity.setEnabled(false)));
        assertThat(freshness.of(ENRICHMENT, INPUT))
                .isEqualTo(EnrichmentFreshness.Clampability.CLAMPABLE);
    }

    @Test
    void completeGroupTriggeredDraftIsStillRefused() {
        registered(pipeline(TransformType.LLM, entity -> {
            entity.setTriggerKind(TriggerKind.GROUP);
            entity.setTriggerGroupBy("chat_id");
            entity.setEnabled(false);
        }));

        assertThat(freshness.of(ENRICHMENT, INPUT))
                .isEqualTo(EnrichmentFreshness.Clampability.GROUP_TRIGGER);
    }

    @Test
    void anArmedWriterDeclaringNoTransformIsStillNotClass1() {
        // Armed with no transform is the pre-fold write signature, not a draft: still refused.
        registered(pipeline(TransformType.LLM, entity -> {
            entity.setSpec("{}");
            entity.setEnabled(true);
        }));

        final EnrichmentFreshness.Clampability clampability = freshness.of(ENRICHMENT, INPUT);

        assertThat(clampability).isEqualTo(EnrichmentFreshness.Clampability.NOT_CLASS1);
        assertThat(clampability.refused()).isTrue();
    }

    @Test
    void pipelineReadingAnotherTableIsAdmittedWithoutBeingClampedAgainst() {
        registered(sqlPipeline(entity -> entity.setInputTable("response_ratings")));

        final EnrichmentFreshness.Clampability clampability = freshness.of(ENRICHMENT, INPUT);

        // The distinction this capability rests on: the position such a pipeline records is on
        // response_ratings' ordering, and the mapping to the reader's input is the grain join, which is
        // not order-preserving. So it cannot be clamped against — but its values are settled rather than
        // provisional, so refusing it would remove working capability instead of preventing a wrong number.
        assertThat(clampability).isEqualTo(EnrichmentFreshness.Clampability.FOREIGN_AXIS);
        assertThat(clampability.clamps()).isFalse();
        assertThat(clampability.refused()).isFalse();
    }

    @Test
    void pipelineDeclaringTheSameTableItReadsIsClampable() {
        registered(sqlPipeline(entity -> entity.setInputTable(INPUT)));

        // Declaring the input explicitly means the same axis as deriving it, so it must classify the same.
        assertThat(freshness.of(ENRICHMENT, INPUT))
                .isEqualTo(EnrichmentFreshness.Clampability.CLAMPABLE);
    }

    // --- the bound ----------------------------------------------------------------------------------

    @Test
    void refreshJoiningNoEnrichmentIsBoundedByNothing() {
        assertThat(freshness.bound(Set.of(), INPUT)).isEmpty();
    }

    @Test
    void writtenPositionBoundsTheWindow() {
        registeredForBound(sqlPipeline(entity -> {
            entity.setMaterializedThroughVersion(1_000L);
            entity.setMaterializedThroughIdentity("evt-a");
            entity.setMaterializedGeneration(3L);
        }));

        assertThat(freshness.bound(Set.of(ENRICHMENT), INPUT))
                .contains(new EnrichmentFreshness.Bound(ENRICHMENT, 1_000L, "evt-a"));
    }

    @Test
    void pipelineThatHasRecordedNothingWithholdsTheWholeWindow() {
        registeredForBound(sqlPipeline(entity -> { }));

        final EnrichmentFreshness.Bound bound = freshness.bound(Set.of(ENRICHMENT), INPUT).orElseThrow();

        // Distinct from NO_PIPELINE: this pipeline exists and is expected to write, so its absent values are late.
        // Reading past them is the failure the clamp exists to prevent.
        assertThat(bound.withholdsEverything()).isTrue();
        assertThat(bound.enrichment()).isEqualTo(ENRICHMENT);
    }

    @Test
    void drainedHorizonReleasesTheClampPastTheLastMatchingRow() {
        final Instant drained = Instant.ofEpochMilli(9_000_000L);
        registeredForBound(sqlPipeline(entity -> {
            // A filtered pipeline's written position stops at its last MATCHING row, and non-matching rows sit
            // above it permanently unenriched. Without the drained horizon this rollup would wait forever
            // for rows nothing is ever going to enrich.
            entity.setMaterializedThroughVersion(1_000L);
            entity.setMaterializedThroughIdentity("evt-a");
            entity.setDrainedAt(drained);
            entity.setMaterializedGeneration(3L);
        }));

        final EnrichmentFreshness.Bound bound = freshness.bound(Set.of(ENRICHMENT), INPUT).orElseThrow();

        assertThat(bound.version()).isEqualTo(drained.minus(SAFETY_LAG).toEpochMilli());
        // No identity: the fact is "nothing matching below this instant", not a row. An empty tiebreaker
        // excludes rows sharing exactly that version rather than including them, so the horizon can never
        // release more than was observed.
        assertThat(bound.identity()).isEmpty();
    }

    @Test
    void writtenPositionWinsWhenItIsAheadOfTheDrainedHorizon() {
        registeredForBound(sqlPipeline(entity -> {
            entity.setMaterializedThroughVersion(9_000_000L);
            entity.setMaterializedThroughIdentity("evt-z");
            entity.setDrainedAt(Instant.ofEpochMilli(1_000L));
            entity.setMaterializedGeneration(3L);
        }));

        assertThat(freshness.bound(Set.of(ENRICHMENT), INPUT))
                .contains(new EnrichmentFreshness.Bound(ENRICHMENT, 9_000_000L, "evt-z"));
    }

    @Test
    void markRecordedUnderSupersededGenerationReadsAsAbsent() {
        registeredForBound(sqlPipeline(entity -> {
            entity.setMaterializedThroughVersion(9_000_000L);
            entity.setMaterializedThroughIdentity("evt-z");
            entity.setDrainedAt(Instant.ofEpochMilli(9_000_000L));
            // Recorded two generations ago. A version-column repoint bumps the generation, and it does not
            // make the tuple stale so much as meaningless: the axis it is expressed on no longer exists.
            entity.setMaterializedGeneration(1L);
        }));

        assertThat(freshness.bound(Set.of(ENRICHMENT), INPUT).orElseThrow().withholdsEverything()).isTrue();
    }

    @Test
    void earliestPositionAcrossTheJoinedSetIsTheBound() {
        final PipelineEntity ahead = sqlPipeline(entity -> {
            entity.setMaterializedThroughVersion(9_000L);
            entity.setMaterializedThroughIdentity("evt-z");
            entity.setMaterializedGeneration(3L);
        });
        final PipelineEntity behind = sqlPipeline(entity -> {
            entity.setName("usage-sentiment-live");
            entity.setTarget("usage_sentiment");
            entity.setMaterializedThroughVersion(2_000L);
            entity.setMaterializedThroughIdentity("evt-b");
            entity.setMaterializedGeneration(3L);
        });
        given(pipelines.findByTargetIn(any())).willReturn(List.of(ahead, behind));

        // The window has to be safe for every enrichment it joins, so the slowest one decides.
        assertThat(freshness.bound(Set.of(ENRICHMENT, "usage_sentiment"), INPUT))
                .contains(new EnrichmentFreshness.Bound("usage_sentiment", 2_000L, "evt-b"));
    }

    @Test
    void unclampableEnrichmentContributesNoBoundEvenAlongsideOneThatDoes() {
        final PipelineEntity clampable = sqlPipeline(entity -> {
            entity.setMaterializedThroughVersion(9_000L);
            entity.setMaterializedThroughIdentity("evt-z");
            entity.setMaterializedGeneration(3L);
        });
        final PipelineEntity foreign = sqlPipeline(entity -> {
            entity.setName("usage-sentiment-live");
            entity.setTarget("turn_feedback");
            entity.setInputTable("response_ratings");
            // Deliberately far behind: if a foreign-axis pipeline contributed a bound, this would dominate it.
            entity.setMaterializedThroughVersion(1L);
            entity.setMaterializedThroughIdentity("evt-a");
            entity.setMaterializedGeneration(3L);
        });
        given(pipelines.findByTargetIn(any())).willReturn(List.of(clampable, foreign));

        assertThat(freshness.bound(Set.of(ENRICHMENT, "turn_feedback"), INPUT))
                .contains(new EnrichmentFreshness.Bound(ENRICHMENT, 9_000L, "evt-z"));
    }

    // --- re-derivation ------------------------------------------------------------------------------

    @Test
    void reDerivationInstantIsReportedRegardlessOfGeneration() {
        final Instant rederived = Instant.ofEpochMilli(5_000L);
        registered(sqlPipeline(entity -> {
            entity.setHistoryRederivedAt(rederived);
            // A generation two behind: a declaration edit does not make a rollup that already aggregated the
            // re-derived values correct again, so this fact must survive one.
            entity.setMaterializedGeneration(1L);
        }));

        assertThat(freshness.historyRederivedAt(ENRICHMENT)).contains(rederived);
    }

    @Test
    void writeAtOrBelowTheRecordedPositionIsReDerivingHistory() {
        final PipelineEntity pipeline = sqlPipeline(entity -> {
            entity.setMaterializedThroughVersion(2_000L);
            entity.setMaterializedThroughIdentity("evt-m");
            entity.setMaterializedGeneration(3L);
        });

        // Strictly below, and exactly at: both rewrite rows a dependent rollup was already allowed to read
        // past. "Exactly at" is the shape a re-executed page takes, which is why the comparison is
        // inclusive rather than strict.
        assertThat(freshness.reDerivesHistory(pipeline, 1_000L, "evt-a")).isTrue();
        assertThat(freshness.reDerivesHistory(pipeline, 2_000L, "evt-m")).isTrue();
        // Forward progress on the same version, separated only by the tiebreaker: ordinary live-tick work.
        assertThat(freshness.reDerivesHistory(pipeline, 2_000L, "evt-z")).isFalse();
        assertThat(freshness.reDerivesHistory(pipeline, 3_000L, "evt-a")).isFalse();
    }

    @Test
    void writeBelowTheDrainedHorizonIsReDerivingHistoryWithNoWrittenPosition() {
        final PipelineEntity pipeline = sqlPipeline(entity -> {
            // Drained with nothing written: the shape a pipeline settles into when its filter has matched
            // nothing since its last mutation. The horizon alone is what a rollup read past.
            entity.setDrainedAt(Instant.ofEpochMilli(9_000_000L));
            entity.setMaterializedGeneration(3L);
        });

        assertThat(freshness.reDerivesHistory(pipeline, 1_000L, "evt-a")).isTrue();
        assertThat(freshness.reDerivesHistory(pipeline, 9_000_000L, "evt-a")).isFalse();
    }

    @Test
    void writeByPipelineWithNoUsableMarkIsNotReDerivingHistory() {
        // Nothing was ever declared complete, so nothing can have been read past. A superseded generation
        // reads the same way, which is what keeps a declaration edit — a definition change — out of this signal.
        assertThat(freshness.reDerivesHistory(sqlPipeline(entity -> { }), 1_000L, "evt-a")).isFalse();
        assertThat(freshness.reDerivesHistory(sqlPipeline(entity -> {
            entity.setMaterializedThroughVersion(9_000L);
            entity.setMaterializedThroughIdentity("evt-z");
            entity.setMaterializedGeneration(1L);
        }), 1_000L, "evt-a")).isFalse();
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /**
     * Stubs what {@code of()} reads: the single-pipeline lookup. The transform rides along inside the
     * pipeline's own stored {@code spec} now, so there is no second lookup left to stub — which is what
     * made the three separate fixtures below collapse into one.
     */
    private void registered(PipelineEntity pipeline) {
        given(pipelines.findByTarget(ENRICHMENT)).willReturn(Optional.of(pipeline));
    }

    /** Stubs what {@code bound()} reads: the batch lookup. */
    private void registeredForBound(PipelineEntity pipeline) {
        given(pipelines.findByTargetIn(any())).willReturn(List.of(pipeline));
    }

    private static PipelineEntity sqlPipeline(java.util.function.Consumer<PipelineEntity> customise) {
        return pipeline(TransformType.SQL, customise);
    }

    private static PipelineEntity pipeline(
            TransformType type, java.util.function.Consumer<PipelineEntity> customise) {
        final PipelineEntity entity = new PipelineEntity();
        entity.setName("usage-client-identity-live");
        entity.setKind(PipelineKind.ENRICH);
        entity.setSpec(specWithTransformType(type));
        entity.setTarget(ENRICHMENT);
        entity.setTriggerKind(TriggerKind.SCHEDULE);
        entity.setTriggerCron("0 * * * * *");
        entity.setGeneration(3L);
        customise.accept(entity);
        return entity;
    }

    /**
     * A stored enrich declaration carrying what clampability dispatches on — the transform's type — and
     * the one output and derived mapping that make it complete, so a disabled fixture is classified by what
     * it declares rather than as an undeclared draft.
     */
    private static String specWithTransformType(TransformType type) {
        final EnrichSpec spec = new EnrichSpec();
        spec.mutableTransform().setType(type);
        final TransformOutput output = new TransformOutput();
        output.setName("label");
        spec.mutableTransform().setOutputs(List.of(output));
        final OutputBinding binding = new OutputBinding();
        binding.setColumn("label");
        binding.setVar("label");
        spec.setOutputBindings(List.of(binding));
        return JSON.writeValueAsString(spec);
    }

    /** A stored enrich declaration carrying the transform's type and nothing else — a draft fragment. */
    private static String fragmentWithTransformType(TransformType type) {
        final EnrichSpec spec = new EnrichSpec();
        spec.mutableTransform().setType(type);
        return JSON.writeValueAsString(spec);
    }
}
