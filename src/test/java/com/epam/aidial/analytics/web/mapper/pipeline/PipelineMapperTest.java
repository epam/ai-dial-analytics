package com.epam.aidial.analytics.web.mapper.pipeline;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.TransformPreset;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.model.FreshnessMode;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.PipelineRegistration;
import com.epam.aidial.analytics.service.pipeline.model.TruncUnit;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpec;
import com.epam.aidial.analytics.web.dto.enrichment.ReadyWhenDto;
import com.epam.aidial.analytics.web.dto.enrichment.VarSpec;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.AdvancedRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.CreatePipelineRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.CreateTransformRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.GroupKeyRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.MeasureRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchPipelineRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchTransformRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TruncRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link PipelineMapper} — the wire-to-domain translation and the shapes it refuses.
 *
 * <p>Each refusal here would otherwise be a silent partial acceptance: a second input dropped, an
 * unrecognised trigger kind treated as cron, a partial declaration merged into the stored one leaving a
 * pipeline nobody described — or, since the two registries merged, <b>a field belonging to the other kind
 * quietly discarded</b>, which is the one that makes a flat union safe or unsafe.
 */
class PipelineMapperTest {

    private static final String NAME = "usage_daily_rollup";

    private final PipelineSpecCodec specCodec =
            new PipelineSpecCodec(new JsonMapperConfiguration().jsonMapper());
    private final PipelineMapper mapper = mapper();

    /**
     * The generated implementation with its one injected collaborator set directly. MapStruct owns the
     * constructor, so the mapper takes it as a field; a Spring context would be a heavier way to assert
     * pure translation.
     */
    private PipelineMapper mapper() {
        final PipelineMapper instance = new PipelineMapperImpl();
        ReflectionTestUtils.setField(instance, "specCodec", specCodec);
        return instance;
    }

    // --- request fixtures ----------------------------------------------------------------------------

    private static CreatePipelineRequest aggregate(List<String> inputs, TriggerRequest trigger) {
        return aggregate(inputs, trigger,
                List.of(new GroupKeyRequest("deployment", null, null),
                        new GroupKeyRequest(null, new TruncRequest("request_time", TruncUnit.DAY), "day")),
                List.of(new MeasureRequest("call_count", "count", null, null, false)));
    }

    private static CreatePipelineRequest aggregate(List<String> inputs, TriggerRequest trigger,
            List<GroupKeyRequest> groupBy, List<MeasureRequest> measures) {
        return new CreatePipelineRequest(NAME, PipelineKind.AGGREGATE, "usage_daily", inputs, null,
                groupBy, measures, null, trigger, null, null, null);
    }

    private static CreatePipelineRequest enrich(TriggerRequest trigger) {
        return new CreatePipelineRequest("toxicity-live", PipelineKind.ENRICH, "usage_toxicity", null, null,
                null, null, null, trigger, llmTransform(vars("text", "request_body")),
                new AdvancedRequest(5000, 50, 300, null), true);
    }

    /** An {@code llm} transform in the shape a new declaration authors: prose outputs, bound inputs. */
    private static CreateTransformRequest llmTransform(LinkedHashMap<String, VarSpec> inputs) {
        final LinkedHashMap<String, OutputSpec> outputs = new LinkedHashMap<>();
        outputs.put("verdict", new OutputSpec("Whether the text is toxic.", null));
        return new CreateTransformRequest(TransformType.LLM, null, "gpt-4o", null,
                "{\"messages\": [{\"role\": \"user\", \"content\": \"{{text}}\"}]}",
                inputs, outputs, null);
    }

    /** A {@code transform.inputs} map in the bare-string form, which is what all 8 live bindings use. */
    private static LinkedHashMap<String, VarSpec> vars(String name, String column) {
        final LinkedHashMap<String, VarSpec> vars = new LinkedHashMap<>();
        vars.put(name, new VarSpec(column, null));
        return vars;
    }

    private static TriggerRequest schedule() {
        return new TriggerRequest("schedule", "0 0 * * * *", null, null, null);
    }

    private static AggregateSpec aggregateSpecOf(PipelineRegistration registration) {
        return (AggregateSpec) registration.spec();
    }

    // --- aggregate translation -----------------------------------------------------------------------

    @Test
    void completeRequestFlattensIntoRegistration() {
        final PipelineRegistration registration =
                mapper.toRegistration(aggregate(List.of("dial_usage_log"), schedule()));
        final AggregateSpec spec = aggregateSpecOf(registration);

        assertThat(registration.inputTable()).isEqualTo("dial_usage_log");
        assertThat(registration.triggerKind()).isEqualTo(TriggerKind.SCHEDULE);
        assertThat(registration.triggerCron()).isEqualTo("0 0 * * * *");
        assertThat(spec.getGroupBy()).hasSize(2);
        assertThat(spec.getGroupBy().getFirst().getColumn()).isEqualTo("deployment");
        assertThat(spec.getGroupBy().get(1).getTrunc().getUnit()).isEqualTo(TruncUnit.DAY);
        assertThat(spec.getMeasures().getFirst().getFn()).isEqualTo("count");
        // An absent intent is stored as `periodic`, so a spec read back always states its own mode.
        assertThat(spec.getFreshness().getMode()).isEqualTo(FreshnessMode.PERIODIC);
    }

    @Test
    void measurePredicateSurvivesTheMapping() {
        // MapStruct copies the leaf measure fields by name, so `where` is carried with no explicit mapping —
        // which is exactly why it is worth pinning. A rename on either side would silently stop copying it,
        // and a dropped predicate does not fail: it just widens the measure to the whole group.
        final PipelineRegistration registration = mapper.toRegistration(aggregate(
                List.of("dial_usage_log"), schedule(),
                List.of(new GroupKeyRequest("chat_id", null, null)),
                List.of(new MeasureRequest(
                        "chain_price", "sum", "total_price", "parent_deployment IS NULL", false))));

        assertThat(aggregateSpecOf(registration).getMeasures().getFirst().getWhere())
                .isEqualTo("parent_deployment IS NULL");
    }

    @Test
    void multiSourceDeclarationIsRejectedRatherThanTruncated() {
        assertThatThrownBy(() -> mapper.toRegistration(
                aggregate(List.of("dial_usage_log", "other"), schedule())))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("exactly one source");
    }

    @Test
    void anAggregateRequestWithNoInputIsAcceptedAsDraft() {
        // An aggregate registration always lands disabled, so it is a draft and may not have named its
        // input yet; the refusal moved to the arming gate, which names the absent input.
        assertThat(mapper.toRegistration(aggregate(List.of(), schedule())).inputTable()).isNull();
        assertThat(mapper.toRegistration(aggregate(null, schedule())).inputTable()).isNull();
    }

    @Test
    void registrationOfIdentityAndTargetAloneMaps() {
        final PipelineRegistration aggregate = mapper.toRegistration(new CreatePipelineRequest(NAME,
                PipelineKind.AGGREGATE, "usage_daily", null, null, null, null, null, null, null, null, null));
        final PipelineRegistration enrich = mapper.toRegistration(new CreatePipelineRequest("toxicity-live",
                PipelineKind.ENRICH, "usage_toxicity", null, null, null, null, null, null, null, null, null));

        assertThat(aggregate.target()).isEqualTo("usage_daily");
        assertThat(aggregate.enabled()).isFalse();
        // An absent `enabled` means false for an enrich registration too: it is no longer required.
        assertThat(enrich.enabled()).isFalse();
        assertThat(enrich.triggerKind()).isNull();
    }

    @Test
    void anAggregateRequestOmittingTheTriggerLeavesTheKindToTheService() {
        // No longer a mapper-level refusal: `trigger` has always been optional on the aggregate request, so
        // the mapper passes "not stated" through and the service resolves it to `schedule`. Refusing here
        // would turn every registration body written before the merged trigger into a 422.
        final PipelineRegistration registration =
                mapper.toRegistration(aggregate(List.of("dial_usage_log"), null));

        assertThat(registration.triggerKind()).isNull();
        assertThat(registration.triggerCron()).isNull();
    }

    // --- enrich translation --------------------------------------------------------------------------

    @Test
    void anEnrichRequestFlattensItsTransformIntoTheSpecAndItsTriggerIntoColumns() {
        final TriggerRequest group = new TriggerRequest("group", null, "chat_id",
                new ReadyWhenDto(null, "10m", null, null), null);

        final PipelineRegistration registration = mapper.toRegistration(enrich(group));
        final EnrichSpec spec = (EnrichSpec) registration.spec();

        // The split the storage layer makes: trigger fields become columns because they carry database
        // constraints, and the transform becomes JSON.
        assertThat(registration.triggerKind()).isEqualTo(TriggerKind.GROUP);
        assertThat(registration.triggerGroupBy()).isEqualTo("chat_id");
        assertThat(registration.readyWhen().getIdle()).isEqualTo("10m");
        assertThat(registration.inputTable()).isNull();
        // The transform is stored whole, inside the spec: this is the fold's visible shape.
        assertThat(spec.getTransform().getType()).isEqualTo(TransformType.LLM);
        assertThat(spec.getTransform().getModel()).isEqualTo("gpt-4o");
        // preset defaults to chat_completion for llm, and ONLY for llm - a sql body's own preset stays
        // null so the gate can refuse it by name rather than the default filling it in and being accepted.
        assertThat(spec.getTransform().getPreset()).isEqualTo(TransformPreset.CHAT_COMPLETION);
        assertThat(spec.getTransform().getOutputs()).singleElement().satisfies(output -> {
            assertThat(output.getName()).isEqualTo("verdict");
            assertThat(output.getProse()).isEqualTo("Whether the text is toxic.");
        });
        // transform.inputs land as the stored input bindings, INSIDE the transform and under the stored
        // spelling: a list is not the map, so borrowing the wire name would be a worse lie.
        assertThat(spec.getInputBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.getVar()).isEqualTo("text");
            assertThat(binding.getColumn()).isEqualTo("request_body");
        });
        // output_bindings are NOT set here. They are derived by EnrichSpecValidator from the transform's
        // outputs matched to the target's columns, which the mapper does not have.
        assertThat(spec.getOutputBindings()).isEmpty();
        assertThat(spec.getBatchScanLimit()).isEqualTo(5000);
        assertThat(spec.getBatchChunk()).isEqualTo(50);
        assertThat(spec.getRateRpm()).isEqualTo(300);
        assertThat(registration.enabled()).isTrue();
    }

    @Test
    void enrichDeclarationWithNoTransformStoresNullRatherThanAnEmptyBlock() {
        final CreatePipelineRequest request = new CreatePipelineRequest("toxicity-live",
                PipelineKind.ENRICH, "usage_toxicity", null, null, null, null, null, schedule(), null, null,
                true);

        final EnrichSpec spec = (EnrichSpec) mapper.toRegistration(request).spec();

        // Null, not an empty block, and the difference is which refusal the declaration gate can reach.
        // A null transform is "transform is required for an 'enrich' pipeline"; an empty one is a
        // transform that WAS declared and left blank, which answers "transform.type is required" - and
        // so tells a caller who sent no `transform` at all to fill in a member of a block they never
        // wrote. Mapping an absent block to an empty one made the first message unreachable.
        assertThat(spec.getTransform()).isNull();
        // The read-side accessors are unaffected: they resolve a null transform through the detached
        // empty instance, so nothing downstream has to test for one.
        assertThat(spec.getInputBindings()).isEmpty();
    }

    @Test
    void anUnrecognisedTriggerKindIsRejectedNamingTheAlternatives() {
        assertThatThrownBy(() -> mapper.toRegistration(
                aggregate(List.of("dial_usage_log"), new TriggerRequest("push", null, null, null, null))))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("trigger.kind 'push' is not supported")
                .hasMessageContaining("(allowed: schedule, group)");
    }

    @Test
    void theRetiredOnIngestTriggerKindIsRefusedNamingItsReplacement() {
        assertThatThrownBy(() -> mapper.toRegistration(new CreatePipelineRequest("toxicity-live",
                PipelineKind.ENRICH, "usage_toxicity", null, null, null, null, null,
                new TriggerRequest("on_ingest", null, null, null, null), llmTransform(null), null, false)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("trigger.kind 'on_ingest' is retired")
                .hasMessageContaining("'schedule'");
    }

    @Test
    void sampleFractionAboveOneIsRefusedRatherThanReadAsPercentage() {
        // The runner's own sampler reads <= 1 as a fraction and > 1 as a percentage, with a discontinuity
        // at 1. ADAS refuses the second reading outright, so `50` cannot mean 50% by accident; the runner
        // needs no edit for this, its > 1 branch simply becomes unreachable.
        assertThatThrownBy(() -> mapper.toRegistration(enrichWithAdvanced(
                new AdvancedRequest(null, null, null, 50.0d))))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("advanced.sample_fraction")
                .hasMessageContaining("(0, 1]")
                .hasMessageContaining("not read as a percentage");
    }

    @Test
    void zeroSampleFractionIsRefusedNamingTheEnabledFlag() {
        // The sampler reads 0 as keep-nothing: a pipeline that is armed and evaluates no rows. That state
        // has an honest spelling already, so the refusal names it rather than storing the dishonest one.
        assertThatThrownBy(() -> mapper.toRegistration(enrichWithAdvanced(
                new AdvancedRequest(null, null, null, 0.0d))))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("advanced.sample_fraction")
                .hasMessageContaining("'enabled': false");
    }

    @Test
    void sampleFractionOfOneIsAccepted() {
        assertThat(((EnrichSpec) mapper.toRegistration(enrichWithAdvanced(
                new AdvancedRequest(null, null, null, 1.0d))).spec()).getSampling())
                .isEqualTo(1.0d);
    }

    private static CreatePipelineRequest enrichWithAdvanced(AdvancedRequest advanced) {
        return new CreatePipelineRequest("toxicity-live", PipelineKind.ENRICH, "usage_toxicity", null, null,
                null, null, null, new TriggerRequest("schedule", null, null, null, null),
                llmTransform(null), advanced, true);
    }

    // --- cross-kind rejection ------------------------------------------------------------------------

    @Test
    void anAggregateOnlyFieldOnAnEnrichDeclarationIsRejectedNamingIt() {
        final CreatePipelineRequest request = new CreatePipelineRequest("toxicity-live", PipelineKind.ENRICH,
                "usage_toxicity", null, null, null,
                List.of(new MeasureRequest("call_count", "count", null, null, false)), null,
                new TriggerRequest("schedule", null, null, null, null), llmTransform(null), null, true);

        // Silently ignoring it would read back absent from the stored pipeline, telling the caller their
        // declaration was accepted while the part they cared about was discarded.
        assertThatThrownBy(() -> mapper.toRegistration(request))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("measures")
                .hasMessageContaining("'aggregate' kind");
    }

    @Test
    void anEnrichOnlyFieldOnAnAggregateDeclarationIsRejectedNamingIt() {
        final CreatePipelineRequest request = new CreatePipelineRequest(NAME, PipelineKind.AGGREGATE,
                "usage_daily", List.of("dial_usage_log"), null,
                List.of(new GroupKeyRequest("deployment", null, null)),
                List.of(new MeasureRequest("call_count", "count", null, null, false)), null, schedule(),
                llmTransform(null), null, null);

        // Named member by member as `transform.<member>`, never as `transform`: the block tells a caller
        // nothing about which field they misplaced, which is the whole point of naming it.
        assertThatThrownBy(() -> mapper.toRegistration(request))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("transform.type")
                .hasMessageContaining("transform.model")
                .hasMessageContaining("'enrich' kind");
    }

    @Test
    void everyCrossKindFieldIsNamedRatherThanJustTheFirst() {
        final CreatePipelineRequest request = new CreatePipelineRequest(NAME, PipelineKind.AGGREGATE,
                "usage_daily", List.of("dial_usage_log"), null,
                List.of(new GroupKeyRequest("deployment", null, null)),
                List.of(new MeasureRequest("call_count", "count", null, null, false)), null, schedule(),
                llmTransform(vars("text", "request_body")),
                new AdvancedRequest(5000, null, null, null), null);

        // A caller who mistook one kind's vocabulary for another's has more than one field to fix, and a
        // message naming one of five sends them round the loop four more times.
        assertThatThrownBy(() -> mapper.toRegistration(request))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("transform.type")
                .hasMessageContaining("transform.request_template")
                .hasMessageContaining("transform.inputs")
                .hasMessageContaining("transform.outputs")
                // Named as `advanced.rows_per_scan`, not `advanced`, for the same reason.
                .hasMessageContaining("advanced.rows_per_scan");
    }

    @Test
    void transformMemberOnAnAggregateIsNamedEvenWhenTheBlockOmitsItsType() {
        final CreateTransformRequest transform =
                new CreateTransformRequest(null, null, "gpt-4o", null, null, null, null, null);
        final CreatePipelineRequest request = new CreatePipelineRequest(NAME, PipelineKind.AGGREGATE,
                "usage_daily", List.of("dial_usage_log"), null,
                List.of(new GroupKeyRequest("deployment", null, null)),
                List.of(new MeasureRequest("call_count", "count", null, null, false)), null, schedule(),
                transform, null, null);

        // The half-filled block is the realistic mistake, and it is the one a bean-level @NotNull on
        // `transform.type` got wrong: the @Valid cascade ran ahead of this dispatch and answered "type
        // must not be null", telling an aggregate author to ADD a member of a block their kind may not
        // declare at all. The kind dispatch runs first now, so the answer names what they misplaced.
        assertThatThrownBy(() -> mapper.toRegistration(request))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("transform.model")
                .hasMessageContaining("'enrich' kind")
                .hasMessageNotContaining("transform.type");
    }

    // --- patch ---------------------------------------------------------------------------------------

    private static PatchPipelineRequest patch(Boolean enabled, String target, List<String> inputs,
            List<GroupKeyRequest> groupBy, List<MeasureRequest> measures, TriggerRequest trigger) {
        return new PatchPipelineRequest(null, null, enabled, target, inputs, null, groupBy, measures, null,
                trigger, null, null);
    }

    /**
     * A patch of a pipeline that declares no read source, which is the case for every fixture here, and that
     * the patch leaves <b>armed</b> — the path on which completeness is required.
     */
    private PipelineRegistration patched(PipelineKind kind, PatchPipelineRequest request) {
        return patched(stored(kind, null, true), request);
    }

    /** A patch of the given stored pipeline whose target has no parent source. */
    private PipelineRegistration patched(PipelineEntity stored, PatchPipelineRequest request) {
        return mapper.toRegistration(stored, request, () -> null);
    }

    /** As {@link #patched}, for a patch that leaves the pipeline disabled: a draft save. */
    private PipelineRegistration draftPatched(PipelineKind kind, PatchPipelineRequest request) {
        return patched(stored(kind, null, false), request);
    }

    /** The stored pipeline a patch is mapped against: identity, target, read source and armed state. */
    private static PipelineEntity stored(PipelineKind kind, String inputTable, boolean enabled) {
        final PipelineEntity entity = new PipelineEntity();
        entity.setName(NAME);
        entity.setKind(kind);
        entity.setTarget(storedTargetOf(kind));
        entity.setInputTable(inputTable);
        entity.setEnabled(enabled);
        return entity;
    }

    /** The target the fixtures' stored pipeline writes, which an echoed {@code target} is compared with. */
    private static String storedTargetOf(PipelineKind kind) {
        return kind == PipelineKind.ENRICH ? "usage_toxicity" : "usage_daily";
    }

    /**
     * A full-body patch — the shape the retired {@code PUT /v1/rules/&#123;id&#125;} took, and the shape a
     * read-modify-write client produces — restates the identity it read. Accepting the echo is what keeps
     * that shape working now that request bodies refuse fields they do not declare.
     */
    @Test
    void patchEchoingItsOwnNameAndKindIsAccepted() {
        final PatchPipelineRequest request = new PatchPipelineRequest(NAME, PipelineKind.AGGREGATE, true,
                null, null, null, null, null, null, null, null, null);

        assertThat(patched(PipelineKind.AGGREGATE, request)).isNull();
    }

    @Test
    void patchNamingAnotherPipelineIsRefusedRatherThanApplied() {
        final PatchPipelineRequest request = new PatchPipelineRequest("someone-else", null, true,
                null, null, null, null, null, null, null, null, null);

        // The dangerous case: no declaration member, so this would otherwise take the enable-only path and
        // flip the pipeline in the URL — not the one the body names.
        assertThatThrownBy(() -> patched(PipelineKind.AGGREGATE, request))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("does not match the pipeline being patched")
                .hasMessageContaining("cannot be changed");
    }

    @Test
    void patchChangingTheKindIsRefused() {
        final PatchPipelineRequest request = new PatchPipelineRequest(NAME, PipelineKind.ENRICH, null,
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> patched(PipelineKind.AGGREGATE, request))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("kind cannot be changed");
    }

    @Test
    void anEnabledOnlyPatchCarriesNoDeclaration() {
        assertThat(patched(PipelineKind.AGGREGATE, patch(true, null, null, null, null, null))).isNull();
    }

    @Test
    void advancedOnlyPatchStillCountsAsDeclarationChange() {
        final PatchPipelineRequest request = new PatchPipelineRequest(null, null, null, null, null, null,
                null, null, null, null, null, new AdvancedRequest(1200, null, null, null));

        // A knob is a declaration member, so it bumps the change token and re-runs the gate. The failure
        // this pins is not "the knob was ignored" but "the patch answered success having dropped it": a
        // member hasDeclaration() misses takes the enable-only path silently.
        assertThat(request.hasDeclaration("usage_toxicity")).isTrue();
    }

    @Test
    void transformOnlyPatchStillCountsAsDeclarationChange() {
        // Rebinding only `transform.inputs` — or only the prompt — must take the declaration path. A member
        // hasDeclaration() misses is not merely ignored: the patch answers success having discarded it.
        final PatchPipelineRequest request = new PatchPipelineRequest(null, null, null, null, null, null,
                null, null, null, null, patchTransform(vars("text", "request_body")), null);

        assertThat(request.hasDeclaration("usage_toxicity")).isTrue();
    }

    /** The patch-side transform record, which additionally accepts the legacy {@code output_vars}. */
    private static PatchTransformRequest patchTransform(LinkedHashMap<String, VarSpec> inputs) {
        final LinkedHashMap<String, OutputSpec> outputs = new LinkedHashMap<>();
        outputs.put("verdict", new OutputSpec("Whether the text is toxic.", null));
        return new PatchTransformRequest(TransformType.LLM, null, "gpt-4o", null, "{{text}}",
                inputs, outputs, null, null);
    }

    @Test
    void partialDeclarationPatchIsRejected() {
        // Merging half a declaration into the stored one would leave a pipeline no caller ever described.
        assertThatThrownBy(() -> patched(stored(PipelineKind.AGGREGATE, null, false),
                patch(true, "usage_daily", null, null,
                        List.of(new MeasureRequest("call_count", "count", null, null, false)),
                        schedule())))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("must supply inputs, measures, and trigger");
    }

    @Test
    void targetEchoAloneIsNotDeclaration() {
        // Counting it would make {target: <same>, enabled: true} a declaration patch that clears every other
        // member — on a draft, a 200 that erased the declaration. It is an echo, as name and kind are.
        assertThat(patched(PipelineKind.AGGREGATE, patch(true, "usage_daily", null, null, null, null))).isNull();
        assertThat(draftPatched(PipelineKind.ENRICH, new PatchPipelineRequest(NAME,
                PipelineKind.ENRICH, null, "usage_toxicity", null, null, null, null, null, null, null, null)))
                .isNull();
    }

    @Test
    void differentTargetOrEchoWithAnotherMemberIsDeclaration() {
        assertThat(patch(null, "usage_weekly", null, null, null, null).hasDeclaration("usage_daily")).isTrue();
        assertThat(draftPatched(PipelineKind.AGGREGATE, patch(null, "usage_daily", null, null,
                List.of(new MeasureRequest("call_count", "count", null, null, false)), null)))
                .isNotNull();
    }

    @Test
    void partialPatchOfEnabledAggregateIsLeftToTheServicesConflict() {
        // Any declaration patch of an enabled aggregate is a 409, so the mapper does not first answer 422 asking
        // for members the caller would only have to add to meet the 409.
        assertThat(patched(PipelineKind.AGGREGATE, patch(null, null, null, null,
                List.of(new MeasureRequest("call_count", "count", null, null, false)), null))).isNotNull();
        assertThat(patched(storedSql(true), patch(null, null, List.of("dial_usage_log"), null, null, null)))
                .isNotNull();
    }

    @Test
    void draftPatchOmittingDeclaredReadSourceIsRefused() {
        // Not a completeness rule: omitting inputs would repoint the pipeline at its target's parent, a
        // change to what it reads that the draft save never wrote.
        final PatchPipelineRequest request = new PatchPipelineRequest(null, null, null, null, null, null,
                null, null, null, null, null, new AdvancedRequest(1200, null, null, null));

        assertThatThrownBy(() -> mapper.toRegistration(stored(PipelineKind.ENRICH, "response_ratings", false), request,
                () -> "turns"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("must restate 'inputs'")
                .hasMessageContaining("'turns'");
    }

    @Test
    void omittedReadSourceThatIsTheTargetParentIsKept() {
        // The omission would follow the parent, which is the declared table: nothing is repointed, so the
        // stored source is kept rather than cleared. A client reading the compiled projection cannot tell the
        // two apart, and clearing would still change the authored declaration.
        final PatchPipelineRequest request = new PatchPipelineRequest(null, null, null, null, null, "x > 1",
                null, null, null, null, null, null);

        assertThat(mapper.toRegistration(stored(PipelineKind.ENRICH, "dial_usage_log", false), request,
                () -> "dial_usage_log").inputTable()).isEqualTo("dial_usage_log");
        assertThat(mapper.toRegistration(stored(PipelineKind.ENRICH, "dial_usage_log", true), new PatchPipelineRequest(
                null, null, null, null, null, "x > 1", null, null, null, schedule(), patchTransform(null), null),
                () -> "dial_usage_log").inputTable()).as("armed too").isEqualTo("dial_usage_log");
    }

    @Test
    void targetParentIsAskedOnlyForAnOmittedDeclaredSource() {
        // It costs a catalog read, and only an omitted `inputs` over a declared source can need it.
        final Supplier<String> unasked = () -> {
            throw new AssertionError("target parent asked");
        };
        final PatchPipelineRequest omitted = new PatchPipelineRequest(null, null, null, null, null, "x > 1",
                null, null, null, null, null, null);
        final PatchPipelineRequest restated = new PatchPipelineRequest(null, null, null, null, List.of("turns"), "x > 1",
                null, null, null, null, null, null);

        assertThat(mapper.toRegistration(stored(PipelineKind.ENRICH, null, false), omitted, unasked)).isNotNull();
        assertThat(mapper.toRegistration(stored(PipelineKind.ENRICH, "dial_usage_log", false), restated, unasked)
                .inputTable()).isEqualTo("turns");
        assertThat(mapper.toRegistration(stored(PipelineKind.ENRICH, "dial_usage_log", false),
                new PatchPipelineRequest(null, null, true, null, null, null, null, null, null, null, null, null),
                unasked)).as("enable-only").isNull();
    }

    @Test
    void omittedReadSourceIsRefusedWhenTheParentIsUnknown() {
        final PatchPipelineRequest request = new PatchPipelineRequest(null, null, null, null, null, "x > 1",
                null, null, null, null, null, null);

        assertThatThrownBy(() -> patched(stored(PipelineKind.ENRICH, "dial_usage_log", false), request))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("must restate 'inputs'");
    }

    @Test
    void draftPatchMayDropDeclaredReadSourceExplicitly() {
        final PatchPipelineRequest request = new PatchPipelineRequest(null, null, null, null, List.of(), null,
                null, null, null, null, null, new AdvancedRequest(1200, null, null, null));

        assertThat(mapper.toRegistration(stored(PipelineKind.ENRICH, "response_ratings", false), request,
                () -> "response_ratings").inputTable()).as("even when the declared source is the parent").isNull();
    }

    @Test
    void partialDeclarationPatchOfDisabledPipelineIsAccepted() {
        // A draft save stores what it was given: completeness is asked of a declaration about to produce
        // rows, and this patch leaves the pipeline disabled.
        final PipelineRegistration registration = draftPatched(PipelineKind.AGGREGATE,
                patch(null, null, null, null,
                        List.of(new MeasureRequest("call_count", "count", null, null, false)), null));

        assertThat(registration.target()).as("omitted, so the service keeps the stored one").isNull();
        assertThat(registration.inputTable()).isNull();
        assertThat(registration.triggerKind()).isNull();
    }

    @Test
    void partialEnrichDeclarationPatchOfDisabledPipelineIsAccepted() {
        final PatchPipelineRequest request = new PatchPipelineRequest(null, null, null, null,
                null, null, null, null, null, null, null,
                new AdvancedRequest(1200, null, null, null));

        final PipelineRegistration registration = draftPatched(PipelineKind.ENRICH, request);

        assertThat(((EnrichSpec) registration.spec()).getBatchScanLimit()).isEqualTo(1200);
        assertThat(registration.triggerKind()).as("undeclared on a draft save").isNull();
        assertThat(registration.target()).as("omitted, so the service keeps the stored one").isNull();
    }

    @Test
    void blankTargetIsNeitherDeclarationNorRepoint() {
        // The service reads blank as omitted (sticky), so counting it would make {target: ""} a declaration
        // patch that clears every other member of a draft and answers 200.
        assertThat(patch(null, "", null, null, null, null).hasDeclaration("usage_daily")).isFalse();
        assertThat(patch(null, "  ", null, null, null, null).hasDeclaration("usage_daily")).isFalse();
        assertThat(draftPatched(PipelineKind.AGGREGATE, patch(true, "", null, null, null, null))).isNull();
    }

    @Test
    void paddedTargetEchoIsNotDeclaration() {
        assertThat(patch(null, " usage_daily ", null, null, null, null).hasDeclaration("usage_daily")).isFalse();
        assertThat(patched(PipelineKind.AGGREGATE, patch(true, " usage_daily", null, null, null, null))).isNull();
    }

    @Test
    void paddedTargetIsPassedOnTrimmed() {
        // Trimmed once, here, so the value the echo check compared is the one the service repoints to — and
        // never an untrimmed name reaching the one-writer check and the foreign key.
        final PipelineRegistration registration = draftPatched(PipelineKind.AGGREGATE,
                patch(null, " usage_weekly ", null, null, null, null));

        assertThat(registration.target()).isEqualTo("usage_weekly");
        assertThat(draftPatched(PipelineKind.AGGREGATE, patch(null, "  ", List.of("dial_usage_log"), null, null, null))
                .target()).as("blank with another member: omitted, so sticky").isNull();
    }

    @Test
    void armedIsReadFromTheStoredFlagWhenThePatchCarriesNone() {
        // Asked of an enrich pipeline: a declaration patch of an enabled aggregate is left to the service's 409.
        final PatchPipelineRequest partial = new PatchPipelineRequest(null, null, null, null,
                null, null, null, null, null, null, null, new AdvancedRequest(1200, null, null, null));

        assertThat(patched(stored(PipelineKind.ENRICH, null, false), partial)).isNotNull();
        assertThatThrownBy(() -> patched(stored(PipelineKind.ENRICH, null, true), partial))
                .isInstanceOf(PipelineValidationException.class);
        final PatchPipelineRequest disarming = patch(false, null, null, null,
                List.of(new MeasureRequest("call_count", "count", null, null, false)), null);
        assertThat(patched(stored(PipelineKind.AGGREGATE, null, true), disarming))
                .as("the patch's own enabled wins").isNotNull();
    }

    @Test
    void anArmingPatchMayOmitTheTargetItKeeps() {
        // target is sticky, so it is never absent from the declaration being armed.
        final PatchPipelineRequest request = patch(null, null, List.of("dial_usage_log"), null,
                List.of(new MeasureRequest("call_count", "count", null, null, false)), schedule());

        assertThat(patched(PipelineKind.AGGREGATE, request).target()).isNull();
    }

    @Test
    void partialEnrichDeclarationPatchIsRejectedOnItsOwnRequiredSet() {
        final PatchPipelineRequest request = new PatchPipelineRequest(null, null, null, "usage_toxicity",
                null, null, null, null, null, null, null,
                new AdvancedRequest(1200, null, null, null));

        // A different required set, because an enrich declaration has no group keys or measures — asserting
        // it against the aggregate list would refuse a complete enrich patch. `transform` replaced
        // `evaluator_name` in the trio: leaving the old member in would break every declaration patch, and
        // leaving nothing in its place would let a patch drop the transform silently.
        assertThatThrownBy(() -> patched(PipelineKind.ENRICH, request))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("must supply transform and trigger");
    }

    @Test
    void declarationPatchLeavesTheKindToTheService() {
        final PatchPipelineRequest request = patch(null, "usage_daily", List.of("dial_usage_log"),
                List.of(new GroupKeyRequest("deployment", null, null)),
                List.of(new MeasureRequest("call_count", "count", null, null, false)), schedule());

        // Kind is fixed at registration, so the wire does not restate it and the service carries it over.
        assertThat(patched(PipelineKind.AGGREGATE, request).kind()).isNull();
    }

    // --- response ------------------------------------------------------------------------------------

    @Test
    void theResponseCarriesTheDeclarationPlusDerivedState() {
        final PipelineEntity entity = aggregateEntity();
        entity.setEnabled(true);
        entity.setCursorVersion(System.currentTimeMillis() - 42_000L);
        entity.setCursorIdentity("evt-1");

        final PipelineDto dto = mapper.toDto(entity);

        assertThat(dto.inputs()).containsExactly("dial_usage_log");
        assertThat(dto.trigger().kind()).isEqualTo("schedule");
        assertThat(dto.groupBy()).hasSize(1);
        assertThat(dto.measures().getFirst().name()).isEqualTo("call_count");
        assertThat(dto.state().cursorIdentity()).isEqualTo("evt-1");
        // Staleness is derived from the cursor, not stored — so it cannot go stale itself.
        assertThat(dto.state().lagSeconds()).isBetween(41L, 45L);
    }

    @Test
    void pipelineThatHasNeverRunReportsNoStaleness() {
        // Absent, not zero: zero would read as "perfectly fresh".
        assertThat(mapper.toDto(aggregateEntity()).state().lagSeconds()).isNull();
    }

    @Test
    void anAggregateResponseCarriesNoEnrichMembersAndTheReverse() {
        final PipelineDto aggregateDto = mapper.toDto(aggregateEntity());
        final PipelineDto enrichDto = mapper.toDto(enrichEntity());

        // Absent rather than empty, in both directions: an omitted member says "this kind has no such
        // thing", where an empty list or a zero would read as a real value.
        assertThat(aggregateDto.transform()).isNull();
        assertThat(aggregateDto.responseSchema()).isNull();
        assertThat(aggregateDto.outputs()).isNull();
        assertThat(aggregateDto.advanced()).isNull();
        assertThat(enrichDto.groupBy()).isNull();
        assertThat(enrichDto.measures()).isNull();
    }

    @Test
    void anEnrichResponseOmitsTheCursorAndScheduleAdasDoesNotOwn() {
        final PipelineEntity entity = enrichEntity();
        // Values the row happens to carry — the fold left these columns on one table, so the mapper rather
        // than the schema is what keeps them out of an enrich response.
        entity.setCursorVersion(System.currentTimeMillis() - 42_000L);
        entity.setCursorIdentity("evt-1");
        entity.setNextRunAt(java.time.Instant.now());
        entity.setLastError("stale");

        final PipelineDto.StateDto state = mapper.toDto(entity).state();

        assertThat(state.cursorVersion()).isNull();
        assertThat(state.cursorIdentity()).isNull();
        assertThat(state.lagSeconds()).isNull();
        assertThat(state.nextRunAt()).isNull();
        assertThat(state.lastError()).isNull();
        assertThat(state.hasMore()).isNull();
    }

    @Test
    void draftWithNoTriggerServesNoTriggerObject() {
        final PipelineEntity draft = enrichEntity();
        draft.setTriggerKind(null);
        draft.setTriggerCron(null);

        // Absent, not {}: the next patch would post an empty object back as a declared trigger.
        assertThat(mapper.toDto(draft).trigger()).isNull();
        assertThat(mapper.toDto(enrichEntity()).trigger().kind()).isEqualTo("schedule");
    }

    @Test
    void theEnrichClass1PositionIsReadableInState() {
        final PipelineEntity entity = enrichEntity();
        entity.setMaterializedThroughVersion(1_700_000_000_000L);
        entity.setMaterializedThroughIdentity("evt-9");

        // What every dependent rollup's freshness clamp reads, so an operator has to be able to see it.
        final PipelineDto.StateDto state = mapper.toDto(entity).state();

        assertThat(state.materializedThroughVersion()).isEqualTo(1_700_000_000_000L);
        assertThat(state.materializedThroughIdentity()).isEqualTo("evt-9");
    }

    private PipelineEntity aggregateEntity() {
        final PipelineEntity entity = new PipelineEntity();
        entity.setName(NAME);
        entity.setKind(PipelineKind.AGGREGATE);
        entity.setTarget("usage_daily");
        entity.setInputTable("dial_usage_log");
        entity.setTriggerKind(TriggerKind.SCHEDULE);
        entity.setTriggerCron("0 0 * * * *");
        entity.setSpec(specCodec.write(aggregateSpec()));
        return entity;
    }

    private PipelineEntity enrichEntity() {
        final PipelineEntity entity = new PipelineEntity();
        entity.setName("toxicity-live");
        entity.setKind(PipelineKind.ENRICH);
        entity.setTarget("usage_toxicity");
        entity.setTriggerKind(TriggerKind.SCHEDULE);
        entity.setTriggerCron("0 * * * * *");
        entity.setSpec(specCodec.write(new EnrichSpec()));
        return entity;
    }

    private static AggregateSpec aggregateSpec() {
        final GroupKey key = new GroupKey();
        key.setColumn("deployment");
        final Measure measure = new Measure();
        measure.setName("call_count");
        measure.setFn("count");
        final AggregateSpec spec = new AggregateSpec();
        spec.setGroupBy(List.of(key));
        spec.setMeasures(List.of(measure));
        return spec;
    }

    // --- SQL mode ------------------------------------------------------------------------------------

    private static final String SQL = "INSERT INTO usage_daily (deployment, call_count)\n"
            + "SELECT deployment, count(*) FROM dial_usage_log GROUP BY 1 -- per deployment";

    private static CreatePipelineRequest sqlAggregate(String filter, List<GroupKeyRequest> groupBy,
            List<MeasureRequest> measures) {
        return new CreatePipelineRequest(NAME, PipelineKind.AGGREGATE, "usage_daily", null, filter,
                groupBy, measures, SQL, schedule(), null, null, null);
    }

    private static PatchPipelineRequest sqlPatch(Boolean enabled, String sql, String filter,
            List<GroupKeyRequest> groupBy, List<MeasureRequest> measures, TriggerRequest trigger) {
        return new PatchPipelineRequest(null, null, enabled, null, null, filter, groupBy, measures, sql,
                trigger, null, null);
    }

    /** A stored aggregate pipeline declared in SQL mode. */
    private PipelineEntity storedSql(boolean enabled) {
        final PipelineEntity entity = stored(PipelineKind.AGGREGATE, "dial_usage_log", enabled);
        final AggregateSpec spec = new AggregateSpec();
        spec.setSql(SQL);
        entity.setSpec(specCodec.write(spec));
        return entity;
    }

    @Test
    void sqlRegistrationStoresTheStatementVerbatimWithNoFormMembers() {
        final PipelineRegistration registration = mapper.toRegistration(sqlAggregate(null, null, null));
        final AggregateSpec spec = aggregateSpecOf(registration);

        assertThat(spec.getSql()).isEqualTo(SQL);
        assertThat(spec.getGroupBy()).isEmpty();
        assertThat(spec.getMeasures()).isEmpty();
        // inputs is optional in SQL mode: the statement's FROM names the input.
        assertThat(registration.inputTable()).isNull();
    }

    @Test
    void sqlOnAnEnrichDeclarationIsRefusedNamingIt() {
        final CreatePipelineRequest request = new CreatePipelineRequest("toxicity-live", PipelineKind.ENRICH,
                "usage_toxicity", null, null, null, null, SQL, schedule(), null, null, false);

        assertThatThrownBy(() -> mapper.toRegistration(request))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("sql belongs to the 'aggregate' kind");
    }

    @Test
    void sqlBesideFormModeMembersIsRefusedNamingEachMember() {
        assertThatThrownBy(() -> mapper.toRegistration(sqlAggregate(null,
                List.of(new GroupKeyRequest("deployment", null, null)), null)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageStartingWith("group_by is a form-mode member");
        assertThatThrownBy(() -> mapper.toRegistration(sqlAggregate(null, null,
                List.of(new MeasureRequest("call_count", "count", null, null, false)))))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageStartingWith("measures is a form-mode member");
    }

    @Test
    void sqlBesideFilterIsRefusedNamingFilter() {
        assertThatThrownBy(() -> mapper.toRegistration(sqlAggregate("deployment = 'gpt-4o'", null, null)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageStartingWith("filter is a form-mode member");
    }

    @Test
    void exclusivityIsRefusedOnDraftPatchesToo() {
        // A contradiction, not an incompleteness: no later save resolves it, so the draft path refuses it.
        assertThatThrownBy(() -> patched(storedSql(false), sqlPatch(null, SQL, null, null,
                List.of(new MeasureRequest("call_count", "count", null, null, false)), null)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageStartingWith("measures is a form-mode member");
    }

    @Test
    void sqlAloneIsDeclarationPatch() {
        assertThat(sqlPatch(null, SQL, null, null, null, null).hasDeclaration("usage_daily")).isTrue();
    }

    @Test
    void patchWithNoDeclarationModeMemberKeepsTheStoredStatement() {
        final PipelineRegistration registration = patched(storedSql(false),
                sqlPatch(null, null, null, null, null, new TriggerRequest("schedule", "0 */5 * * * *", null, null, null)));

        assertThat(aggregateSpecOf(registration).getSql()).isEqualTo(SQL);
    }

    @Test
    void formModePatchClearsTheStoredStatement() {
        final PipelineRegistration registration = patched(storedSql(false), sqlPatch(null, null,
                null, null, List.of(new MeasureRequest("call_count", "count", null, null, false)), schedule()));

        assertThat(aggregateSpecOf(registration).getSql()).isNull();
        assertThat(aggregateSpecOf(registration).getMeasures()).hasSize(1);
    }

    @Test
    void sqlPatchOfFormPipelineClearsItsFormMembers() {
        final PipelineEntity form = stored(PipelineKind.AGGREGATE, "dial_usage_log", false);
        form.setSpec(specCodec.write(aggregateSpec()));
        form.setFilterSql("deployment = 'gpt-4o'");

        final PipelineRegistration registration =
                patched(form, sqlPatch(null, SQL, null, null, null, schedule()));

        assertThat(aggregateSpecOf(registration).getSql()).isEqualTo(SQL);
        assertThat(aggregateSpecOf(registration).getGroupBy()).isEmpty();
        assertThat(aggregateSpecOf(registration).getMeasures()).isEmpty();
        assertThat(registration.filterSql()).isNull();
    }

    @Test
    void armingSqlPatchMustCarryTheTrigger() {
        assertThatThrownBy(() -> patched(storedSql(false), sqlPatch(true, SQL, null, null, null, null)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("must supply sql and trigger");
    }

    @Test
    void armingPatchOfStoredStatementIsNotAskedForInputsOrMeasures() {
        // The stored sql is carried, and it is the whole of the SQL-mode declaration with the trigger.
        assertThat(patched(storedSql(false), sqlPatch(true, null, null, null, null, schedule())))
                .isNotNull();
    }

    @Test
    void sqlPatchOfAnEnabledPipelineIsLeftToTheServicesConflict() {
        // An sql edit of an enabled aggregate is a 409 whatever else it carries, so the mapper does not answer
        // it with a 422 asking for the trigger first.
        assertThat(patched(storedSql(true), sqlPatch(null, SQL, null, null, null, null))).isNotNull();
    }

    @Test
    void blankSqlIsRefusedOnEveryWrite() {
        assertThatThrownBy(() -> mapper.toRegistration(new CreatePipelineRequest(NAME, PipelineKind.AGGREGATE, "usage_daily",
                null, null, null, null, "  ", schedule(), null, null, null)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessage("sql must not be blank");
        assertThatThrownBy(() -> patched(storedSql(false), sqlPatch(null, "", null,
                List.of(new GroupKeyRequest("deployment", null, null)), null, null)))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessage("sql must not be blank");
    }
}
