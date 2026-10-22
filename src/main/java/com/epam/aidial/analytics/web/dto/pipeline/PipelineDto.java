package com.epam.aidial.analytics.web.dto.pipeline;

import com.epam.aidial.analytics.service.enrichment.model.TransformPreset;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpec;
import com.epam.aidial.analytics.web.dto.enrichment.PipelineOutputDto;
import com.epam.aidial.analytics.web.dto.enrichment.TransformVarDto;
import com.epam.aidial.analytics.web.dto.enrichment.VarSpecBody;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.AdvancedRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.GroupKeyRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.MeasureRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A pipeline as returned by the management API and by the runner sync feed: its declaration, plus a
 * read-only {@code state} object carrying the runtime execution position. State is surfaced because a
 * pipeline's output is served from stored rows — a stalled run looks exactly like a quiet period at the
 * query surface, so "when did this last run, how far behind is it, and did it fail" has to be answerable
 * without reading logs.
 *
 * <p><b>One flat shape for both kinds.</b> The common members carry the same spelling and position for
 * either kind; the per-kind members are simply absent for the other one ({@code NON_NULL} inclusion).
 * That is safe only because a field belonging to the other kind is <b>rejected with 422</b> on the way in
 * rather than ignored — a field silently dropped would read back absent from the stored pipeline, telling
 * a caller their declaration was accepted while the part they cared about was discarded.
 *
 * <p>{@code generation}, {@code created_at} and {@code updated_at} are top-level rather than members of
 * {@code state}: they are declaration facts, not runtime state, and {@code generation} in particular is
 * the only change-detection token an out-of-process consumer has — it cannot live behind a projection a
 * consumer might not read.
 *
 * <h2>Two projections of one stored pipeline, one vocabulary</h2>
 *
 * <p>{@code ?view=} selects which one is served, and they differ in <b>resolution, never in naming</b>.
 * {@code source} (the <b>default</b>) is the authored declaration: {@code transform}, {@code advanced},
 * and the <em>stored</em> {@code inputs}, absent when the pipeline declared none. {@code compiled} is the
 * same vocabulary with the derived values filled in — and the derived member set is exactly five:
 * {@code response_schema} (stored, or composed from the target and {@code transform.outputs}), the
 * resolved {@code outputs} mapping, {@code grain_key}, {@code version_column} and the resolved
 * {@code inputs}. It is what the Enrichment Runner reads, asking for it explicitly.
 *
 * <p><b>There is no inlined {@code evaluator} object.</b> It existed to spare the runner a redeploy, and
 * every fact it carried is already on the entry — its {@code name} was the pipeline's {@code name}, its
 * {@code version} the {@code generation}, its {@code created_at} the {@code updated_at}, and
 * {@code type}/{@code model}/{@code preset}/{@code params}/{@code request_template} are the
 * {@code transform}'s own members under the same spellings. The one member that carried a derived value,
 * {@code response_schema}, is the top-level member above. Keeping the object would have shipped a standing
 * exception to the one-spelling rule and served a multi-kilobyte {@code request_template} twice per feed
 * entry.
 *
 * <p><b>There is deliberately no second spelling of any field.</b> An earlier draft kept
 * {@code input_bindings}, {@code output_bindings}, {@code cadence}, {@code batch_scan_limit},
 * {@code batch_chunk} and {@code sampling} on the compiled projection so that an Enrichment Runner
 * deployed before this change could keep reading it unchanged. That bought exactly one thing — tolerance
 * of a rolling deploy in which an old runner reads a new ADAS — at the standing price of every fact having
 * two names forever. What makes a misordered deploy safe instead is the {@code Adas-Contract-Version}
 * echo: the runner reads it before interpreting a body, and refuses to interpret one it does not
 * recognise. The runner ships in the same window; it is not optional.
 *
 * <p>{@code outputs} is the visible shape of that decision. It folds the old {@code output_bindings} and
 * the evaluator's {@code output_vars} into one list, because a consumer needed both and had to join them
 * by name to use either — see {@link PipelineOutputDto}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineDto(
        @Schema(description = "Pipeline name — the registry key and the {name} in the API path. Immutable.",
                example = "usage_daily_rollup")
        String name,

        @Schema(description = "Pipeline kind; selects the transform and where it runs", example = "aggregate")
        PipelineKind kind,

        @Schema(description = "The one table this pipeline writes", example = "usage_daily")
        String target,

        @Schema(description = "The tables this pipeline reads (v1: exactly one). For an `enrich` pipeline "
                + "this is **resolved** — the input it declared, else its target enrichment's parent — so "
                + "the two cases are indistinguishable here. Echoing the value back on a patch DECLARES "
                + "it, which is validated more strictly (a declared input must carry the grain key and "
                + "declare scan metadata); omit it to keep following the target's parent. For an `aggregate` "
                + "in SQL mode that declared none it is the table derived from the statement's FROM.")
        List<String> inputs,

        @Schema(description = "Membership predicate over the input (absent when none)")
        String filter,

        @Schema(description = "Trigger: how and at which grain this pipeline fires")
        TriggerRequest trigger,

        @Schema(description = "Whether the pipeline is armed. An `aggregate` pipeline is always created "
                + "disabled and runs only after being enabled, because its dry-run preview is the gate "
                + "that state exists for.", example = "false")
        boolean enabled,

        @Schema(description = "Change-detection token, bumped on every accepted mutation. A consumer "
                + "detects a re-declaration by comparing it with the value it last saw.", example = "3")
        long generation,

        @JsonProperty("created_at") Instant createdAt,

        @JsonProperty("updated_at") Instant updatedAt,

        // ---- aggregate only -------------------------------------------------------------------------

        @Schema(description = "Group keys, in order (`aggregate` only). Absent, rather than `[]`, while the "
                + "declaration has none — a draft that has not authored them and whose target's ordering_key "
                + "they could not yet be derived from — so a read sent back as a patch declares nothing.")
        @JsonProperty("group_by") List<GroupKeyRequest> groupBy,

        @Schema(description = "Measures (`aggregate` only). A measure's resolved `column` is echoed "
                + "expanded even when the declaration omitted it, so a caller can see which input column "
                + "a defaulted name resolved to; `distinct` is echoed only when true. Absent, rather than "
                + "`[]`, while the declaration has none.")
        List<MeasureRequest> measures,

        @Schema(description = "The SQL declaration (`aggregate` only), exactly as authored — comments and layout "
                + "included. Absent for a form declaration (`group_by` + `measures`); present, those two are absent.")
        String sql,

        // ---- enrich only ----------------------------------------------------------------------------

        @Schema(description = "The transform this pipeline runs (`enrich` only), exactly as authored — "
                + "served by **both** projections, so a read-modify-write client can patch it back "
                + "unchanged. An output is echoed in the form it was authored in, because that form is the "
                + "type dispatch: a `sql` output must be a bare expression string on the way in.")
        PipelineTransformDto transform,

        @Schema(description = "The response schema the model is actually held to (`enrich`, "
                + "`view=compiled` only). **Derived**: the declaration's stored `transform.response_schema` "
                + "where it has one, and otherwise the schema composed from the target's columns and "
                + "`transform.outputs`. Absent for a `sql` transform, which constrains no model response. "
                + "It is deliberately not served inside `transform`, where it would let a `view=compiled` "
                + "read-modify-write client post a composed schema back as a stored one.")
        @JsonProperty("response_schema") Map<String, Object> responseSchema,

        @Schema(description = "Grain key, derived from the target enrichment (`enrich` only)")
        @JsonProperty("grain_key") String grainKey,

        @Schema(description = "Physical name of the input's version column — the column a scan row carries "
                + "under its own name, and a group's activity timestamp. Absent when the input declares no "
                + "scan metadata.")
        @JsonProperty("version_column") String versionColumn,

        @Schema(description = "Where each model response property lands, in binding order (`enrich`, "
                + "`view=compiled` only). Derived: the authored `outputs` matched to the target's columns, "
                + "with each output's transform folded in, so a consumer never joins two lists by name.")
        List<PipelineOutputDto> outputs,

        @Schema(description = "Execution knobs as declared (`enrich` only). Absent when the declaration "
                + "set none - the runner then applies its own configured defaults, and echoing those here "
                + "would be indistinguishable from an operator having chosen them.")
        AdvancedRequest advanced,

        @Schema(description = "Runtime execution state. Read-only: server-owned, never settable — a "
                + "caller-chosen cursor could skip input rows and yield results that are wrong without "
                + "looking wrong.", accessMode = Schema.AccessMode.READ_ONLY)
        StateDto state,

        @Schema(description = "Advisory notes about the declaration that was just accepted — carried by the "
                + "**write** responses (POST /v1/pipelines, PATCH /v1/pipelines/{name}) and absent "
                + "everywhere else, because they answer \"what did I just declare\" rather than describing "
                + "stored state. They never bear on acceptance: a note describes a declaration that passed "
                + "every check and is still bounded at execution by the pipeline read budget. Today they "
                + "name the one trap that budget cannot warn about in advance — a `filter` or Class-1 "
                + "output expression reading an `object` column through `json_extract_string_ci`, whose "
                + "cost is one read stream per JSON path per part rather than a cost per row.",
                accessMode = Schema.AccessMode.READ_ONLY)
        List<String> notes) {

    /**
     * The authored transform, echoed for both projections.
     *
     * <p><b>{@code response_schema} here means "the schema this declaration stores", never the resolved
     * one.</b> The resolved schema is the top-level {@link PipelineDto#responseSchema()} on the compiled
     * projection, and serving it inside this block would let a {@code view=compiled} read-modify-write
     * client post a <em>composed</em> schema back as a stored one — pinning a prompt to a schema nobody
     * authored. The same rule is why an output's {@code prose} is echoed exactly as authored, blank or
     * absent included: the column-description default is applied at composition only, and echoing a
     * derived default here would make the next {@code PATCH} store it and silently stop tracking the
     * column.
     *
     * <p>{@code inputs} is always echoed in the <b>object</b> form, even where the declaration used the
     * bare-string shorthand: the two are two spellings of one fact, so a response that could return either
     * would make a reader guess which, and the request accepts the object form too. {@code outputs} is the
     * opposite case and is echoed <b>as authored</b>, because there the form is the type dispatch — a
     * {@code sql} output must be a bare expression string on the way in, so echoing it as an object would
     * produce a body the next {@code PATCH} refuses.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PipelineTransformDto(
            @Schema(description = "Grammar selector: `llm` or `sql`", example = "llm")
            TransformType type,

            @Schema(description = "Request-shaping preset (`llm` only)", example = "chat_completion")
            TransformPreset preset,

            @Schema(description = "DIAL deployment/model (`llm` only)", example = "gemini-2.5-flash-lite")
            String model,

            @Schema(description = "Preset parameters (`llm` only)")
            Map<String, Object> params,

            @Schema(description = "The JSON request body rendered per row or per group (`llm` only)")
            @JsonProperty("request_template") String requestTemplate,

            @Schema(description = "What the transform receives, keyed by variable name; absent for a `sql` "
                    + "transform, which renders no request")
            Map<String, VarSpecBody> inputs,

            @Schema(description = "What this transform produces, keyed by the target column that receives "
                    + "it, in declaration order")
            Map<String, OutputSpec> outputs,

            @Schema(description = "The pre-`outputs` output shape; present only on a declaration folded "
                    + "from a definition that predates `outputs`")
            @JsonProperty("output_vars") List<TransformVarDto> outputVars,

            @Schema(description = "The schema this declaration **stores**, served verbatim and absent when "
                    + "it stores none. Not the resolved one — that is the top-level `response_schema`.")
            @JsonProperty("response_schema") Map<String, Object> responseSchema) {}

    /**
     * The pipeline's runtime execution state, for both kinds. A member that does not apply to a kind is
     * <b>absent</b> rather than reported empty: an absent member says "this kind has no such position",
     * where a zero would read as a real one. The cursor and schedule members are ADAS-owned and so absent
     * on an {@code enrich} pipeline, whose scan position is held by the Enrichment Runner.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StateDto(
            @Schema(description = "Scan position in the input's total order (epoch millis); absent until "
                    + "the first successful run, and always absent for a kind whose cursor the runner owns")
            @JsonProperty("cursor_version") Long cursorVersion,

            @Schema(description = "Tiebreaker half of the scan position")
            @JsonProperty("cursor_identity") String cursorIdentity,

            @Schema(description = "How far behind the input this pipeline is, in seconds, measured against "
                    + "the present at the moment you read it — so two reads of the same unchanged cursor "
                    + "differ by the time between them, and both are correct. Expect a **sawtooth, not a "
                    + "level**: the floor is roughly the configured safety lag plus the run's own "
                    + "duration plus the input's ingest lag, and the ceiling is one cron period above that. "
                    + "It is **not** time since the last run — it derives from the cursor, so it bundles "
                    + "time-since-run with how far behind the cursor already was. Read it alongside "
                    + "`last_run_at`, `last_error` and `has_more` to tell a stall from a healthy cadence, "
                    + "and set any alert threshold above one cron period or every cron slot will fire.",
                    example = "42")
            @JsonProperty("lag_seconds") Long lagSeconds,

            @Schema(description = "When the last ADAS-driven run ran (successfully or not)")
            @JsonProperty("last_run_at") Instant lastRunAt,

            @Schema(description = "When ADAS will next run this pipeline; absent when disabled, and absent "
                    + "for a kind ADAS drives no schedule for")
            @JsonProperty("next_run_at") Instant nextRunAt,

            @Schema(description = "The last run failure; absent after a success")
            @JsonProperty("last_error") String lastError,

            @Schema(description = "Whether the last run left input behind (it filled its window "
                    + "limit). While true the pipeline is working through a backlog and is re-run on every "
                    + "scheduler tick rather than on its own cron. Read this alongside `lag_seconds`: lag "
                    + "alone cannot distinguish a caught-up pipeline from one that has settled at a large "
                    + "constant backlog. **Deliberately unaffected by `clamp`**: a clamped pipeline is held "
                    + "back on purpose and waits for its own cron rather than re-probing every tick against "
                    + "a position that has not moved.", example = "false")
            @JsonProperty("has_more") Boolean hasMore,

            @Schema(description = "What held the last run's window short of its input's available "
                    + "cursor, or absent when nothing did. A pipeline reading an enrichment is only as fresh "
                    + "as that enrichment: the window stops where the enrichment's values have actually "
                    + "been written, because reading a row whose enrichment cell is still empty writes "
                    + "a wrong result that no later run revisits. Read it as **progress**, not as an "
                    + "error — but a clamp that persists for more than a few enrichment cadences means the "
                    + "enrichment has stopped advancing (its pipeline disabled, or its runner away), and "
                    + "that does need attention.")
            PipelineClampDto clamp,

            @Schema(description = "Enrichments this pipeline reads that no clamp can protect, each with the "
                    + "reason. Empty for most pipelines. These columns are still read — the pipeline is "
                    + "simply exposed to the ordinary freshness gap for them, which is why it is "
                    + "reported rather than assumed away.")
            @JsonProperty("unclamped_reads") List<PipelineUnclampedReadDto> unclampedReads,

            @Schema(description = "Set when an enrichment this pipeline reads had its history re-derived "
                    + "after this pipeline's output was last built, so values it already wrote may have "
                    + "changed underneath it. This is an **instruction**, not progress: no window is held "
                    + "back and no future run corrects it, because the affected input rows did not move. "
                    + "Rebuild with the operations the API already has — delete and re-register the "
                    + "pipeline, re-derive over the affected range with a windowed materialize, or recreate "
                    + "the target table first where a re-run moved rows between group keys.")
            @JsonProperty("rebuild_required") PipelineRebuildDto rebuildRequired,

            @Schema(description = "How far ADAS has materialized this pipeline's target enrichment through, "
                    + "on the input's cursor axis — the end tuple of the last window it wrote. Class-1 "
                    + "`enrich` pipelines only (`transform.type: sql`), and absent until the first window "
                    + "is written. This is what every dependent rollup's freshness clamp reads.")
            @JsonProperty("materialized_through_version") Long materializedThroughVersion,

            @Schema(description = "Tiebreaker half of the materialized position")
            @JsonProperty("materialized_through_identity") String materializedThroughIdentity,

            @Schema(description = "When ADAS last probed this pipeline and found nothing left matching its "
                    + "filter — a distinct fact from the position, and the one that keeps the position "
                    + "usable: the probe applies the filter, so non-matching rows sit above the position "
                    + "and are permanently unenriched by construction.")
            @JsonProperty("drained_at") Instant drainedAt) {}

    /** The position a run's window was held at, and the enrichment that held it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PipelineClampDto(
            @Schema(description = "The enrichment whose materialized position bounded the window",
                    example = "usage_client_identity")
            String enrichment,

            @Schema(description = "Version half of the position the window was bounded to (epoch millis); "
                    + "absent when the enrichment has written nothing usable yet and the whole window was "
                    + "withheld")
            @JsonProperty("cursor_version") Long cursorVersion,

            @Schema(description = "Tiebreaker half of the bounded-to position")
            @JsonProperty("cursor_identity") String cursorIdentity) {}

    /** An enrichment this pipeline reads that contributes no bound, and why. */
    public record PipelineUnclampedReadDto(
            @Schema(description = "The enrichment read without a freshness bound", example = "turn_feedback")
            String enrichment,

            @Schema(description = "Why no bound can be established. `no_pipeline` — nothing writes this "
                    + "enrichment any more, so its absent values are final rather than late and there is "
                    + "nothing to wait for. `ingest_written` — data-sync writes it beside its source table and "
                    + "flushes it first, so a source row this pipeline can see already has its enrichment row; "
                    + "an absent value is final for the same reason, and nothing is late. `foreign_axis` — the "
                    + "pipeline writing it reads a different "
                    + "table from this one's input, so the position it records is on that table's ordering "
                    + "and cannot be compared with the one this pipeline windows over. `undeclared_writer` — "
                    + "the pipeline targeting it is a disabled draft whose declaration is incomplete, so it "
                    + "writes nothing and an absent value is final until it is armed. `group_trigger` and "
                    + "`not_class1` are refused when this pipeline is armed, so they appear only when the "
                    + "upstream changed shape after this pipeline was armed — an unprotected read that needs "
                    + "an operator's attention rather than a benign one.",
                    allowableValues = {"no_pipeline", "ingest_written", "foreign_axis", "undeclared_writer", "group_trigger",
                        "not_class1"},
                    example = "foreign_axis")
            String reason) {}

    /** The enrichment whose re-derived history invalidated this pipeline's output. */
    public record PipelineRebuildDto(
            @Schema(description = "The enrichment whose history was re-derived", example = "turn_feedback")
            String enrichment,

            @Schema(description = "When that re-derivation was recorded")
            @JsonProperty("rederived_at") Instant rederivedAt) {}
}
