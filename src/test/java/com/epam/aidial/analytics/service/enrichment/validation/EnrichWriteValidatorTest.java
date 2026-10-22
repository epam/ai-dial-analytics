package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.exception.WriteColumnNotAllowedException;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.support.PartitionEcho;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the pipeline-scoped write column-set restriction (outputs ∪ grain ∪ provenance; no system
 * columns).
 */
class EnrichWriteValidatorTest {

    private final EnrichWriteValidator validator = new EnrichWriteValidator();

    private static final String ALIGNMENT = "request_time";
    private static final String AT = "2026-10-15T10:00:00.000Z";

    private static ResolvedEnrichPipeline resolvedPipeline() {
        return resolvedPipeline(List.of());
    }

    /** As above, with {@code extraOutputs} also bound as output columns. */
    private static ResolvedEnrichPipeline resolvedPipeline(List<String> extraOutputs) {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName("toxicity-live");
        pipeline.setKind(PipelineKind.ENRICH);
        OutputBinding binding = new OutputBinding();
        binding.setColumn("toxicity_score");
        binding.setVar("score");
        List<OutputBinding> bindings = new ArrayList<>(List.of(binding));
        for (String column : extraOutputs) {
            OutputBinding extra = new OutputBinding();
            extra.setColumn(column);
            extra.setVar(column);
            bindings.add(extra);
        }
        EnrichSpec spec = new EnrichSpec();
        spec.setOutputBindings(bindings);
        return new ResolvedEnrichPipeline(pipeline, spec, "dial_usage_log", "event_id", "_ingested_at");
    }

    @Test
    void acceptsOutputGrainAndProvenanceColumns() {
        assertThatCode(() -> validator.validate(resolvedPipeline(),
                List.of(Map.of("toxicity_score", 0.1, "event_id", "e1", "pipeline_generation", 17L))))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesTheRetiredEvaluatorVersionColumnNamingWhatReplacedIt() {
        // Not the generic "not permitted" refusal: the column exists on the target and is readable, and a
        // caller sending it is running against a retired contract rather than misreading a mapping. Nothing
        // writes it since the evaluator was folded into its pipeline, and the feed offers no member to
        // derive it from.
        //
        // Check order matters: /rows requires Adas-Contract-Version, so a stale RUNNER is refused 409
        // before any validation runs and never reaches this 422. What this covers is a hand-rolled request
        // or an out-of-date adas-mcp.
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(),
                List.of(Map.of("toxicity_score", 0.1, "event_id", "e1", "evaluator_version", 2))))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("evaluator_version")
                .hasMessageContaining("pipeline_generation");
    }

    @Test
    void refusesTheRetiredColumnEvenBesideTheReplacementStamp() {
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(), List.of(Map.of(
                "toxicity_score", 0.1, "event_id", "e1",
                "evaluator_version", 2, "pipeline_generation", 17L))))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("evaluator_version");
    }

    @Test
    void acceptsGroupProvenanceColumns() {
        // group_version and truncated describe the input a group label was computed from; they are
        // permitted provenance like any other, and un-prefixed so they stay caller-settable.
        assertThatCode(() -> validator.validate(resolvedPipeline(),
                List.of(Map.of("toxicity_score", 0.1, "event_id", "e1",
                        "group_version", 1700000000000L, "truncated", true))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUndeclaredColumn() {
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(),
                List.of(Map.of("event_id", "e1", "sentiment", "pos"))))
                .isInstanceOf(WriteColumnNotAllowedException.class);
    }

    @Test
    void rejectsSystemColumn() {
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(),
                List.of(Map.of("event_id", "e1", "_updated_at", 123))))
                .isInstanceOf(WriteColumnNotAllowedException.class);
    }

    // ------------------------------------------------------------------ aligned target (PartitionEcho)

    @Test
    void alignedTargetAcceptsTheEchoedMember() {
        assertThatCode(() -> validator.validate(resolvedPipeline(),
                List.of(Map.of("toxicity_score", 0.1, "event_id", "e1", PartitionEcho.MEMBER, AT)), ALIGNMENT))
                .doesNotThrowAnyException();
    }

    @Test
    void unalignedTargetRefusesTheMemberAsSystemColumn() {
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(),
                List.of(Map.of("toxicity_score", 0.1, "event_id", "e1", PartitionEcho.MEMBER, AT)), null))
                .isInstanceOf(WriteColumnNotAllowedException.class)
                .hasMessageContaining(PartitionEcho.MEMBER);
    }

    @Test
    void alignedRowWithoutTheMemberIsRefusedNamingIt() {
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(),
                List.of(Map.of("toxicity_score", 0.1, "event_id", "e1")), ALIGNMENT))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("row 1")
                .hasMessageContaining(PartitionEcho.MEMBER);
    }

    @Test
    void alignedRowWithNullMemberIsRefused() {
        Map<String, Object> row = new HashMap<>(Map.of("toxicity_score", 0.1, "event_id", "e1"));
        row.put(PartitionEcho.MEMBER, null);
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(), List.of(row), ALIGNMENT))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining(PartitionEcho.MEMBER);
    }

    /** The column itself is no substitute for the member: unbound, it is not one of the row's permitted columns. */
    @Test
    void alignedRowCarryingTheColumnInsteadOfTheMemberIsRefused() {
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(),
                List.of(Map.of("toxicity_score", 0.1, "event_id", "e1", ALIGNMENT, AT)), ALIGNMENT))
                .isInstanceOf(WriteColumnNotAllowedException.class)
                .hasMessageContaining(ALIGNMENT);
    }

    @Test
    void boundAlignmentColumnWithoutTheMemberIsStillRefused() {
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(List.of(ALIGNMENT)),
                List.of(Map.of("toxicity_score", 0.1, "event_id", "e1", ALIGNMENT, AT)), ALIGNMENT))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining(PartitionEcho.MEMBER);
    }

    @Test
    void boundAlignmentColumnAgreeingWithTheMemberIsAccepted() {
        assertThatCode(() -> validator.validate(resolvedPipeline(List.of(ALIGNMENT)),
                List.of(Map.of("toxicity_score", 0.1, "event_id", "e1", ALIGNMENT, AT, PartitionEcho.MEMBER, AT)), ALIGNMENT))
                .doesNotThrowAnyException();
    }

    @Test
    void boundAlignmentColumnDisagreeingWithTheMemberIsRefused() {
        assertThatThrownBy(() -> validator.validate(resolvedPipeline(List.of(ALIGNMENT)), List.of(Map.of(
                "toxicity_score", 0.1, "event_id", "e1", ALIGNMENT, "2026-10-14T10:00:00.000Z", PartitionEcho.MEMBER, AT)),
                ALIGNMENT))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("disagree");
    }
}
