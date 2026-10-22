package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.model.FreshnessMode;
import com.epam.aidial.analytics.service.pipeline.model.PipelineSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the stored spec is called, now that the authoring names reach storage too.
 *
 * <p>The three renamed execution knobs are written under their authoring names
 * ({@code rows_per_scan}, {@code rows_per_call}, {@code sample_fraction}) and read under those
 * <em>or</em> their former spellings, via {@code @JsonAlias}. No row is migrated: every spec written
 * before this change keeps parsing for as long as nothing rewrites it, and one that is rewritten comes
 * back under the new name. Both are asserted below, because "the alias exists" and "the alias is
 * actually consulted by the codec's mapper" are different claims and only the second one matters.
 *
 * <p><b>Why an alias is safe here when an earlier draft of this change refused one.</b> That draft was
 * reasoning about a <em>rollback</em>: the alias lives in the new code, so reverted code has no
 * {@code @JsonAlias("cadence")} and a row this build had already rewritten to {@code scan_every} would
 * read back with those knobs unset. That is a real hazard and it is simply out of scope — this change
 * ships with the Enrichment Runner in one maintenance window and is not rolled back. In the only
 * direction that remains, new code reading old rows, an alias is the ordinary tool for the job.
 *
 * <p>The two binding lists keep their stored names on purpose. {@code transform.inputs} is a map and the
 * derived {@code outputs} is a list with each output's transform folded in; {@code input_bindings} and
 * {@code output_bindings} are neither, so renaming them to match the wire would name a list after a shape
 * it does not have.
 *
 * <p><b>{@code input_bindings} moved, and that move needs a real read shim rather than an alias.</b> It
 * now lives inside the {@code transform} block, beside the outputs it pairs with — and {@code @JsonAlias}
 * cannot reach it, because an alias renames a key within one object and cannot read a member that used to
 * sit at the parent level. A spec written before the move therefore keeps a top-level {@code input_bindings}
 * that {@code EnrichSpec} binds write-only and consults when the nested list is empty. Without it every
 * pre-fold spec would read back with <b>empty</b> bindings, which is the silent-DDL-data-loss case the
 * drop/rename guard derives its edges from — so both directions are asserted below.
 */
class StoredSpecShapeTest {

    private final JsonMapper mapper = new JsonMapperConfiguration().jsonMapper();
    private final PipelineSpecCodec codec = new PipelineSpecCodec(mapper);

    /** A stored enrich spec with every member populated, in the shape this build writes. */
    private static EnrichSpec storedEnrichSpec() {
        final EnrichSpec spec = new EnrichSpec();
        final InputBinding bound = new InputBinding();
        bound.setVar("request");
        bound.setColumn("request_body");
        final InputBinding computed = new InputBinding();
        computed.setVar("shaped");
        computed.setJsonata("$.body.text");
        spec.setInputBindings(List.of(bound, computed));
        final OutputBinding output = new OutputBinding();
        output.setColumn("title");
        output.setVar("title");
        spec.setOutputBindings(List.of(output));
        spec.setSampling(0.5d);
        spec.setBatchScanLimit(1000);
        spec.setBatchChunk(1);
        spec.setRateRpm(75);
        return spec;
    }

    private JsonNode reserialize(PipelineSpec spec) {
        return mapper.readTree(codec.write(spec));
    }

    // --- the four knobs move; the two binding lists do not -------------------------------------------

    @Test
    void storedEnrichKnobsAreWrittenUnderTheirAuthoringNames() {
        final JsonNode stored = reserialize(storedEnrichSpec());

        assertThat(stored.propertyNames()).contains(
                "rows_per_scan", "rows_per_call", "sample_fraction", "rate_rpm");
        // The former spellings are read-only aliases: accepted on the way in, never written out, so a
        // rewritten row carries exactly one spelling of each knob rather than drifting into two.
        assertThat(stored.propertyNames()).doesNotContain(
                "cadence", "batch_scan_limit", "batch_chunk", "sampling");
        // `scan_every` is retired outright (V44 strips it from every stored row), so nothing writes it.
        assertThat(stored.propertyNames()).doesNotContain("scan_every");
        // The derived output mapping keeps its own name at the top level, which is not the wire's
        // `outputs`; the input bindings moved INSIDE the transform, beside the outputs they pair with, so
        // the top level no longer carries them at all.
        assertThat(stored.propertyNames()).contains("output_bindings", "transform");
        assertThat(stored.propertyNames()).doesNotContain(
                "input_bindings", "vars", "outputs", "advanced");
        assertThat(stored.get("transform").propertyNames()).contains("input_bindings");
    }

    /**
     * The permanent read shim for the {@code input_bindings} move: a spec written before the transform
     * block existed keeps them at the top level, and they must still resolve. A spec is never rewritten to
     * migrate it, so this path is permanent rather than transitional — and an empty answer here is exactly
     * what makes an enrich pipeline's read columns silently droppable.
     */
    @Test
    void topLevelInputBindingsStillResolveAndAreNeverWrittenBack() {
        final EnrichSpec parsed = (EnrichSpec) codec.read(PipelineKind.ENRICH, """
                {"input_bindings":[{"var":"request","column":"request_body"}],
                 "output_bindings":[{"column":"title","var":"title"}],
                 "transform":{"type":"llm","model":"gpt-4o",
                   "outputs":[{"name":"title","prose":"A title."}]}}""");

        assertThat(parsed.getInputBindings()).singleElement()
                .satisfies(binding -> assertThat(binding.getColumn()).isEqualTo("request_body"));
        // Write-only inbound, and NORMALISED on the way through: a declaration this build rewrites lands
        // wholly inside the transform, so the stored row carries one spelling rather than drifting into
        // two. That round trip is not hypothetical — enabling a pipeline re-runs the gate over the spec it
        // parsed and stores what the gate leaves behind, so without the normalisation a folded pre-fold
        // declaration would be rewritten with an empty binding list.
        final JsonNode rewritten = reserialize(parsed);
        assertThat(rewritten.propertyNames()).doesNotContain("input_bindings");
        assertThat(rewritten.get("transform").get("input_bindings").size()).isEqualTo(1);
    }

    /** The nested list wins where both are present, so a rewritten row is not shadowed by its own past. */
    @Test
    void nestedInputBindingsWinOverTheLegacyTopLevelOnes() {
        final EnrichSpec parsed = (EnrichSpec) codec.read(PipelineKind.ENRICH, """
                {"input_bindings":[{"var":"stale","column":"old_column"}],
                 "transform":{"type":"llm","model":"gpt-4o",
                   "input_bindings":[{"var":"request","column":"request_body"}]}}""");

        assertThat(parsed.getInputBindings()).singleElement()
                .satisfies(binding -> assertThat(binding.getVar()).isEqualTo("request"));
    }

    @Test
    void specStoredUnderTheOldKnobNamesStillParses() {
        // The claim the whole no-migration decision rests on, and the reason it is asserted through the
        // codec rather than by reading the annotation: every one of the eight live specs is stored in
        // exactly this shape and none of them is rewritten by deploying.
        final EnrichSpec parsed = (EnrichSpec) codec.read(PipelineKind.ENRICH, """
                {"input_bindings":[{"var":"request","column":"request_body"}],
                 "output_bindings":[{"column":"title","var":"title"}],
                 "sampling":1,"cadence":"5m","batch_scan_limit":1000,"batch_chunk":1,"rate_rpm":75}""");

        assertThat(parsed.getSampling()).isEqualTo(1.0d);
        assertThat(parsed.getBatchScanLimit()).isEqualTo(1000);
        assertThat(parsed.getBatchChunk()).isEqualTo(1);
        assertThat(parsed.getRateRpm()).isEqualTo(75);
        assertThat(parsed.getInputBindings()).singleElement()
                .satisfies(binding -> assertThat(binding.getColumn()).isEqualTo("request_body"));
    }

    @Test
    void storedEnrichSpecSurvivesParseAndReserializeUnchanged() {
        // Parse then write, and compare the trees: a member the codec silently dropped or renamed on the
        // way through would show up here, which is the whole point — the rollback direction is only true
        // for as long as the bytes are.
        final String written = codec.write(storedEnrichSpec());
        final PipelineSpec parsed = codec.read(PipelineKind.ENRICH, written);

        assertThat(reserialize(parsed)).isEqualTo(mapper.readTree(written));
    }

    @Test
    void everyStoredEnrichValueRoundTrips() {
        final EnrichSpec parsed =
                (EnrichSpec) codec.read(PipelineKind.ENRICH, codec.write(storedEnrichSpec()));

        assertThat(parsed.getInputBindings()).hasSize(2);
        assertThat(parsed.getInputBindings().getFirst().getColumn()).isEqualTo("request_body");
        // The jsonata form survives too: the catalog's drop/rename guard reads the column half, and a
        // computed binding that lost its expression would evaluate to nothing at render time.
        assertThat(parsed.getInputBindings().get(1).getJsonata()).isEqualTo("$.body.text");
        assertThat(parsed.getOutputBindings()).singleElement()
                .satisfies(binding -> assertThat(binding.getColumn()).isEqualTo("title"));
        assertThat(parsed.getSampling()).isEqualTo(0.5d);
        assertThat(parsed.getBatchScanLimit()).isEqualTo(1000);
        assertThat(parsed.getBatchChunk()).isEqualTo(1);
        assertThat(parsed.getRateRpm()).isEqualTo(75);
    }

    // --- a declaration with no transform: read tolerantly, refused where it would do damage -----------

    /**
     * Reading the transform of a declaration that has none must not <b>create</b> one.
     *
     * <p>This used to be a lazy mutating getter, and the combination that made it dangerous is a rolling
     * ADAS deploy: the outgoing pod has no {@code transform} member and tolerates unknown properties, so
     * a write it serves (an enabled-only patch is enough) re-stores the declaration without the block. A
     * read on the new build that manufactured an empty transform and kept it would then let the next
     * write persist a pipeline declaring no model, no prompt and no outputs — one that parses, validates
     * and runs while doing nothing, which is the one failure shape nothing downstream can detect.
     */
    @Test
    void absentTransformIsNotCreatedByReadingIt() {
        final EnrichSpec parsed = (EnrichSpec) codec.read(PipelineKind.ENRICH, """
                {"output_bindings":[{"column":"title","var":"title"}]}""");

        // Convenient for a reader (never null), and invisible to storage.
        assertThat(parsed.transform()).isNotNull();
        parsed.transform().setModel("gpt-4o");
        assertThat(parsed.getTransform()).isNull();
        assertThat(reserialize(parsed).propertyNames()).doesNotContain("transform");
    }

    /** The authoring accessor is the one that attaches a block, and it is spelled differently on purpose. */
    @Test
    void mutableTransformIsWhatCreatesAndStoresOne() {
        final EnrichSpec spec = new EnrichSpec();
        spec.mutableTransform().setModel("gpt-4o");

        assertThat(spec.getTransform()).isNotNull();
        assertThat(reserialize(spec).get("transform").get("model").stringValue()).isEqualTo("gpt-4o");
    }

    /**
     * The parse stays tolerant and the <b>guard</b> refuses, which is the split that keeps one damaged row
     * from taking the registry down with it.
     *
     * <p>Refusing inside the codec would fail {@code GET /v1/pipelines} for every pipeline and the sync
     * feed with it — and listing the pipelines is how an operator finds the damaged one to re-declare. So
     * {@code read} answers, and the paths where an empty transform would do damage (the declaration gate,
     * the preview, the scan, the Class-1 materialize) ask {@code requireTransform} instead.
     */
    @Test
    void storedDeclarationWithNoTransformParsesButIsRefusedByTheGuard() {
        final EnrichSpec parsed = (EnrichSpec) codec.read(PipelineKind.ENRICH, """
                {"output_bindings":[{"column":"title","var":"title"}]}""");

        assertThatCode(() -> codec.read(PipelineKind.ENRICH, "{}")).doesNotThrowAnyException();
        assertThatThrownBy(() -> PipelineSpecCodec.requireTransform("session-insights-live", parsed))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("session-insights-live")
                .hasMessageContaining("no 'transform'")
                // The message has to name the cause and the repair: this state is not reachable by any
                // write this build serves, so a reader of the log needs telling where it came from.
                .hasMessageContaining("pre-fold ADAS pod")
                .hasMessageContaining("input bindings");
    }

    /** With a transform present the guard is a plain accessor, returning the very instance stored. */
    @Test
    void theGuardReturnsTheStoredTransformWhenThereIsOne() {
        final EnrichSpec parsed = (EnrichSpec) codec.read(PipelineKind.ENRICH, """
                {"transform":{"type":"llm","model":"gpt-4o"}}""");

        assertThat(PipelineSpecCodec.requireTransform("toxicity-live", parsed))
                .isSameAs(parsed.getTransform());
    }

    // --- spec tolerance, which is permanent rather than transitional ---------------------------------

    @Test
    void storedSpecCarryingRemovedMembersStillParses() {
        // `priority` and `freshness` stopped being fields; no release rewrites the rows that carry them.
        // `cadence` and `scan_every` are the retired scan cadence under both of its spellings: V44 strips
        // them from every live row, and a hand-edited row that still carries one must read as if it did not.
        // Both spec types tolerate unknown properties under a mapper with FAIL_ON_UNKNOWN_PROPERTIES
        // disabled, so a leftover member is ignored rather than fatal. This is the regression test for a
        // tolerance that already existed and that decision 6 now depends on.
        final String withRemoved = """
                {"input_bindings":[],"output_bindings":[{"column":"title","var":"title"}],
                 "cadence":"5m","scan_every":"60s","priority":"live","some_future_member":{"nested":1}}""";

        assertThatCode(() -> {
            final EnrichSpec parsed = (EnrichSpec) codec.read(PipelineKind.ENRICH, withRemoved);
            assertThat(parsed.getOutputBindings()).hasSize(1);
        }).doesNotThrowAnyException();
    }

    @Test
    void storedAggregateSpecWithNoFreshnessReadsBackPeriodic() {
        // `freshness` is no longer accepted on the wire, and the mapper stops writing it. A stored spec
        // that carries none must still validate, which it does because the member is field-initialised —
        // so no stored declaration changes meaning and no re-arm can start failing.
        final AggregateSpec parsed = (AggregateSpec) codec.read(PipelineKind.AGGREGATE,
                """
                {"group_by":[{"column":"chat_id"}],
                 "measures":[{"name":"hops","fn":"count","distinct":false}]}""");

        assertThat(parsed.getFreshness()).isNotNull();
        assertThat(parsed.getFreshness().getMode()).isEqualTo(FreshnessMode.PERIODIC);
    }

    @Test
    void storedAggregateSpecWithExplicitNullFreshnessParsesToNull() {
        // The trap the mapper has to avoid, pinned here so it cannot be reintroduced. Jackson honours an
        // EXPLICIT null by calling the setter, so the field initialiser does NOT cover this case — and
        // `validateFreshness` throws when the value is null. That is why the mapper stops touching the
        // member altogether instead of passing a null through where the request's value used to go:
        // doing the latter would make every aggregate registration and every re-arm a 422.
        final AggregateSpec parsed = (AggregateSpec) codec.read(PipelineKind.AGGREGATE,
                """
                {"group_by":[{"column":"chat_id"}],
                 "measures":[{"name":"hops","fn":"count"}],"freshness":null}""");

        assertThat(parsed.getFreshness()).isNull();
    }

    @Test
    void storedAggregateSpecKeepsItsOwnMemberNames() {
        final AggregateSpec spec = new AggregateSpec();
        spec.setMeasures(List.of());
        spec.setGroupBy(List.of());

        assertThat(reserialize(spec).propertyNames()).contains("group_by", "measures");
    }
}
