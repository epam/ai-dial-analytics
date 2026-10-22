package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.web.dto.enrichment.GroupFetchRequest;
import com.epam.aidial.analytics.web.dto.enrichment.GroupFetchResponse;
import com.epam.aidial.analytics.web.dto.enrichment.MaterializeRequest;
import com.epam.aidial.analytics.web.dto.enrichment.MaterializeResponse;
import com.epam.aidial.analytics.web.dto.enrichment.MemberOrderDto;
import com.epam.aidial.analytics.web.dto.enrichment.MemberSelectDto;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpec;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpecBody;
import com.epam.aidial.analytics.web.dto.enrichment.PipelineOutputDto;
import com.epam.aidial.analytics.web.dto.enrichment.ReadyWhenDto;
import com.epam.aidial.analytics.web.dto.enrichment.ScanRequest;
import com.epam.aidial.analytics.web.dto.enrichment.ScanResponse;
import com.epam.aidial.analytics.web.dto.enrichment.TransformVarDto;
import com.epam.aidial.analytics.web.dto.enrichment.VarSpecBody;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.AdvancedRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.epam.aidial.analytics.web.dto.table.WriteRowsRequest;
import com.epam.aidial.analytics.web.dto.table.WriteRowsResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every shape {@link RunnerContract} covers against a golden list, so a change to one fails the build
 * naming the version bump it requires.
 *
 * <p>The version is only honest if it is raised, and nothing else would notice: the transport survives a
 * shape change that the Enrichment Runner's <em>reading</em> of it does not, which is the whole reason the
 * contract exists. So this test is the build-time half of the same requirement the two 409 refusals are the
 * runtime half of — it does not guard a deploy, it guards the constant those refusals compare.
 *
 * <p><b>Both halves of what the contract names are pinned</b>, because either is equally breaking. The
 * compiled projection is the obvious one ({@code grain_key} going missing is what the last skew turned on),
 * and the four data-plane endpoints' request and response records are the larger one: renaming
 * {@code next_cursor} breaks the runner's paging exactly as completely, and pinning only the projection
 * would leave that asserted by nothing.
 *
 * <p>Scope, for the compiled projection, is <b>what the runner dereferences</b> — the entry, its runtime
 * {@code state}, the authored {@code transform} (with its {@code inputs} and {@code outputs} values), the
 * derived {@code outputs} mapping, and the trigger and {@code advanced} objects. The inlined
 * {@code evaluator} object is gone: every fact it carried is on the entry — {@code name},
 * {@code generation}, {@code updated_at} and the {@code transform}'s own members — except the resolved
 * schema, which is the derived top-level {@code response_schema} pinned below. {@link TransformVarDto}
 * stays pinned, because a folded legacy declaration serves its {@code output_vars} through it.
 *
 * <p><b>One member of the pinned transform is deliberately never populated on this projection.</b>
 * {@code transform.response_schema} means "the schema this declaration <em>stores</em>", and the mapper
 * omits it on {@code view=compiled}, because the <em>resolved</em> schema is already served at the top
 * level and serving both would ship the identical multi-kilobyte object twice in every entry of a feed the
 * runner polls. It is pinned all the same: these lists are read from the record's components rather than
 * from a serialization, precisely so that the assertion does not depend on what a fixture happens to
 * populate, and the member is still served — on {@code view=source}, where a read-modify-write round trip
 * needs it. Suppressing it on the compiled projection was itself a contract change, and is one of the
 * reasons {@code RunnerContract.VERSION} is raised by the change that made it.
 *
 * <p>The {@code aggregate}-only nested shapes ({@code group_by}, {@code measures})
 * are deliberately absent: their member names appear in the entry's own list, but the runner never reads
 * inside them, so pinning their interiors would fail the build for a change that cannot reach it. Same for
 * the {@code state} sub-objects the runner does not read.
 *
 * <p>Each member is pinned as {@code name: Type}, not as a bare name. A name-only pin would pass
 * {@code limit} going from {@code Integer} to {@code String}, or {@code has_more} from {@code boolean} to
 * {@code Boolean} — a member moving between "always present" and "may be absent", which is exactly as
 * breaking to a reader as a rename and exactly as invisible. The requirement this backs is stated over
 * these endpoints' request and response <em>shapes</em>, and a type is part of a shape.
 *
 * <p>Members are read from the record components and their {@code @JsonProperty} overrides rather than
 * through a serialization, because {@code NON_NULL} inclusion means a serialized instance shows only the
 * members that happen to be populated — which is the opposite of a shape assertion.
 *
 * <p><b>This test is blunt on purpose.</b> Updating a golden list without raising
 * {@link RunnerContract#VERSION} is a reviewable one-line lie rather than an invisible one; the failure
 * message below says so outright.
 */
class RunnerContractShapeTest {

    /**
     * What a failure has to say, since the assertion itself only shows which members moved. Spelled once so
     * every pinned shape fails the same way.
     */
    private static final String BUMP = """
            This shape is part of the ADAS <-> Enrichment Runner contract. If the change above is \
            intentional, raise RunnerContract.VERSION and ship the matching Enrichment Runner change in \
            the same deploy window - updating this golden list alone leaves a runner that misreads the \
            feed with nothing to refuse it. The one case that is NOT a version bump is a pinned Java type \
            being renamed or moved with its JSON unchanged (an enum, or a nested object): update the \
            golden list alone, and say in review that the wire shape did not move.""";

    /** The compiled projection, entry by entry: the record under test and the members it must carry. */
    private static Map<Class<?>, List<String>> compiledProjection() {
        final Map<Class<?>, List<String>> shapes = new LinkedHashMap<>();
        // `notes` is pinned like every other member even though the compiled feed never carries it: it is
        // populated only on the two write responses. The assertion is over the record's whole member set,
        // so a member the runner does not read still has to be listed — leaving it out would fail the
        // build for a shape nobody moved, which is the one way to teach a reader that these lists are noise.
        shapes.put(PipelineDto.class, List.of(
                "name: String", "kind: PipelineKind", "target: String", "inputs: List<String>",
                "filter: String", "trigger: PipelineRequests.TriggerRequest", "enabled: boolean",
                "generation: long", "created_at: Instant", "updated_at: Instant",
                "group_by: List<PipelineRequests.GroupKeyRequest>",
                "measures: List<PipelineRequests.MeasureRequest>",
                // `sql` (aggregate-only, NON_NULL) was added with no VERSION bump: the compiled feed serves
                // `kind=enrich` entries only, on which it is always null and so never serialized — the
                // shape the runner reads did not move.
                "sql: String",
                "transform: PipelineDto.PipelineTransformDto",
                "response_schema: Map<String, Object>", "grain_key: String", "version_column: String",
                "outputs: List<PipelineOutputDto>", "advanced: PipelineRequests.AdvancedRequest",
                "state: PipelineDto.StateDto", "notes: List<String>"));
        shapes.put(PipelineDto.PipelineTransformDto.class, List.of(
                "type: TransformType", "preset: TransformPreset", "model: String",
                "params: Map<String, Object>", "request_template: String",
                "inputs: Map<String, VarSpecBody>", "outputs: Map<String, OutputSpec>",
                "output_vars: List<TransformVarDto>", "response_schema: Map<String, Object>"));
        shapes.put(PipelineDto.StateDto.class, List.of(
                "cursor_version: Long", "cursor_identity: String", "lag_seconds: Long",
                "last_run_at: Instant", "next_run_at: Instant", "last_error: String", "has_more: Boolean",
                "clamp: PipelineDto.PipelineClampDto",
                "unclamped_reads: List<PipelineDto.PipelineUnclampedReadDto>",
                "rebuild_required: PipelineDto.PipelineRebuildDto", "materialized_through_version: Long",
                "materialized_through_identity: String", "drained_at: Instant"));
        shapes.put(TransformVarDto.class, List.of(
                "name: String", "type: String", "jsonata: String", "sql: String"));
        // The authored outputs map's VALUE type, which serializes through @JsonValue as either the bare
        // string or the body below — so both halves of that union are part of the wire shape.
        shapes.put(OutputSpec.class, List.of("text: String", "body: OutputSpecBody"));
        shapes.put(OutputSpecBody.class, List.of(
                "prose: String", "values: List<String>", "jsonata: String"));
        shapes.put(PipelineOutputDto.class, List.of("name: String", "column: String", "jsonata: String"));
        shapes.put(VarSpecBody.class, List.of("column: String", "jsonata: String"));
        shapes.put(TriggerRequest.class, List.of(
                "kind: String", "cron: String", "group_by: String", "ready_when: ReadyWhenDto",
                "member_select: MemberSelectDto"));
        shapes.put(ReadyWhenDto.class, List.of(
                "signal: String", "idle: String", "max_staleness: String", "cost_ceiling: Integer"));
        shapes.put(MemberSelectDto.class, List.of(
                "prefer_sql: String", "order_by: List<MemberOrderDto>", "limit: Integer"));
        shapes.put(MemberOrderDto.class, List.of("column: String", "direction: SortDir"));
        shapes.put(AdvancedRequest.class, List.of(
                "rows_per_scan: Integer", "rows_per_call: Integer", "rate_rpm: Integer",
                "sample_fraction: Double"));
        return shapes;
    }

    /** The four runner-only data-plane endpoints, request and response alike. */
    private static Map<Class<?>, List<String>> dataPlane() {
        final Map<Class<?>, List<String>> shapes = new LinkedHashMap<>();
        shapes.put(ScanRequest.class, List.of(
                "cursor: String", "limit: Integer", "from: Instant", "to: Instant"));
        shapes.put(ScanResponse.class, List.of(
                "rows: List<Map<String, Object>>", "next_cursor: String", "has_more: boolean",
                "lag_seconds: Long"));
        shapes.put(GroupFetchRequest.class, List.of("group_key: String", "limit: Integer"));
        shapes.put(GroupFetchResponse.class, List.of(
                "rows: List<Map<String, Object>>", "truncated: boolean"));
        shapes.put(MaterializeRequest.class, List.of(
                "cursor: String", "limit: Integer", "from: Instant", "to: Instant"));
        shapes.put(MaterializeResponse.class, List.of(
                "rows_written: long", "next_cursor: String", "has_more: boolean", "lag_seconds: Long"));
        shapes.put(WriteRowsRequest.class, List.of("rows: List<Map<String, Object>>"));
        shapes.put(WriteRowsResponse.class, List.of("inserted: int"));
        return shapes;
    }

    static Stream<Object[]> pinnedShapes() {
        return Stream.concat(compiledProjection().entrySet().stream(), dataPlane().entrySet().stream())
                .map(entry -> new Object[] {entry.getKey().getSimpleName(), entry.getKey(), entry.getValue()});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pinnedShapes")
    void everyPinnedShapeStillCarriesItsGoldenMembers(String name, Class<?> shape, List<String> golden) {
        // Unordered: a reordered member list is not a contract change, and failing the build for one would
        // teach the next reader that the golden lists are noise.
        assertThat(membersOf(shape))
                .as("%s%n%n%s", name, BUMP)
                .containsExactlyInAnyOrderElementsOf(golden);
    }

    /**
     * Every shape the contract covers is a record, and the assertion above depends on that: a class with
     * getters would report no components and pass vacuously.
     */
    @Test
    void everyPinnedShapeIsRecord() {
        assertThat(pinnedShapes().map(row -> (Class<?>) row[1]))
                .allSatisfy(shape -> assertThat(shape.isRecord())
                        .as("%s must stay a record for its members to be pinned", shape.getSimpleName())
                        .isTrue());
    }

    /**
     * One record's members as {@code name: Type} — the wire name, and the declared type with its package
     * stripped and its nesting rendered with a dot.
     *
     * <p>The type is half the assertion because a name-only pin passes a change the runner cannot survive:
     * {@code has_more} is {@code boolean} on {@link ScanResponse} and {@code Boolean} on
     * {@link PipelineDto.StateDto}, and swapping either for the other moves a member between "always
     * present" and "may be absent" without touching a single name. Same for {@code Integer} becoming
     * {@code String}, or a scalar becoming a {@code List}.
     *
     * <p>Package names are stripped so that <b>moving</b> a pinned type between packages does not fail the
     * build — that is not a wire change. <b>Renaming</b> one still does, even though an enum or a nested
     * object serializes the same whatever its Java class is called; see {@link #BUMP} for what to do then.
     */
    private static List<String> membersOf(Class<?> shape) {
        return Stream.of(shape.getRecordComponents())
                .map(component -> wireName(component) + ": " + typeName(component))
                .toList();
    }

    /** The declared type, package-stripped, nesting rendered with a dot: {@code List<Map<String, Object>>}. */
    private static String typeName(RecordComponent component) {
        return component.getGenericType().getTypeName()
                .replaceAll("([A-Za-z_$][A-Za-z0-9_$]*\\.)+", "")
                .replace('$', '.');
    }

    /**
     * Read from the <b>accessor</b>, not from the record component. {@code @JsonProperty} does not list
     * {@code RECORD_COMPONENT} among its targets, so javac propagates it to the field, the accessor and the
     * constructor parameter but leaves the component's own annotation attribute empty — asking the component
     * would report every member under its Java name and pass this test against a golden list that is wrong
     * in the same way.
     */
    private static String wireName(RecordComponent component) {
        final JsonProperty annotation = component.getAccessor().getAnnotation(JsonProperty.class);
        return annotation == null || annotation.value().isEmpty() ? component.getName() : annotation.value();
    }
}
