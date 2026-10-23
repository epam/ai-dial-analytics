package com.epam.aidial.analytics.web.dto.pipeline;

import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumnRole;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNoteCode;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNoteSeverity;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/** Response envelopes of the pipeline control plane — objects, never bare arrays. */
public final class PipelineResponses {

    private PipelineResponses() {
    }

    /** Body of {@code GET /v1/pipelines}. */
    public record PipelineListDto(
            @Schema(description = "All pipelines, ordered by name") List<PipelineDto> pipelines) {}

    /**
     * Body of {@code POST /v1/pipelines/{name}/preview} — the dry-run gate, in one shape for both kinds.
     *
     * <p>It carries only what a preview alone can know: the sample, its typed columns, the statement that
     * produced it, the size of an {@code aggregate} target once caught up, and coded notes. Nothing the pipeline
     * read serves is echoed — no group keys, measures, transform, schema, inputs or outputs — because a client
     * reaching preview already holds the declaration, and every echo is one more place two answers can disagree.
     *
     * <p>{@code NON_NULL}: {@code statement}, {@code query}, {@code total_rows} and {@code group_key} are absent where they do not
     * apply; {@code columns}, {@code rows} and {@code notes} are always present.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewDto(
            @Schema(description = "The declaration as one SELECT in the ADAS SQL dialect, accepted unchanged by "
                    + "POST /v1/queries/execute-sql: it reads the catalog entity and its exposed fields (an "
                    + "enrichment column by its exposed name, with no join) and names each output by its target "
                    + "column, with no SETTINGS, helper column or physical name. It computes what the declaration "
                    + "computes, ordered by the keys (aggregate) or the grain key (Class-1) rather than newest first, "
                    + "so its rows need not be the sampled ones; running it does not reproduce `total_rows`. The "
                    + "physical statement that produced `rows` is not returned. Present for an `aggregate` and a "
                    + "Class-1 `sql` pipeline unless `notes` carries `statement_not_portable`; absent for `llm`.")
            String statement,

            @Schema(description = "The same query as a structured query DSL document, accepted verbatim by "
                    + "POST /v1/queries/execute. Present exactly when `statement` is.")
            StructuredQuery query,

            @Schema(description = "One entry per key of a row, in row order; present even when `rows` is empty")
            List<PreviewColumnDto> columns,

            @Schema(description = "A bounded sample (nothing is written, no model is called), each row keyed by "
                    + "column name. Newest first, except a `group` sample, whose members keep the order the group "
                    + "assembly delivers them in (ascending), because that is the order the model receives; there "
                    + "\"newest\" chooses the group. Every value is capped at 2048 bytes: a longer string is cut at a "
                    + "code point boundary and ends in `…`, and a longer object, array or map becomes its cut "
                    + "JSON text — `values_truncated` names each column that was capped.")
            List<Map<String, Object>> rows,

            @Schema(description = "The number of groups the declaration produces over the whole input, which is "
                    + "the number of rows the target holds once caught up (`aggregate` only; `0` when there is "
                    + "none). Counted by the sampling statement itself, before its LIMIT; the portable `statement` does not reproduce it.", example = "412")
            @JsonProperty("total_rows") Long totalRows,

            @Schema(description = "The group the sample describes (`group` trigger only), whether named in the "
                    + "request or chosen as the newest", example = "chat-42")
            @JsonProperty("group_key") String groupKey,

            @Schema(description = "Coded remarks about this preview; `[]` on an unremarkable one. Switch on "
                    + "`code`; `message` is for people.")
            List<PreviewNoteDto> notes) {}

    /** One column of a preview sample. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewColumnDto(
            @Schema(description = "The column's name, as each row keys it", example = "call_count")
            String name,

            @Schema(description = "What the column is to the declaration: `key`/`measure` on an aggregate, "
                    + "`grain_key`/`output` on a Class-1 sql pipeline, `grain_key`/`input` on a row-grain llm "
                    + "one, and `input` alone on a group one", example = "measure")
            PreviewColumnRole role,

            @Schema(description = "The type of the value as sampled", example = "long")
            QueryFieldType type,

            @Schema(description = "The type of the target column the value is written to (`key`, `measure` and "
                    + "`output` only)", example = "integer")
            @JsonProperty("target_type") QueryFieldType targetType,

            @Schema(description = "The largest UTF-8 size of this column's non-null sampled values, in bytes, "
                    + "measured before the value cap (an object, array or map as its compact JSON). Present for "
                    + "string, object, array and map columns that sampled a non-null value. On an `input` it "
                    + "hints at what one row or member costs the model.", example = "86214")
            @JsonProperty("max_bytes") Long maxBytes) {}

    /** One coded remark about a preview. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewNoteDto(
            @Schema(description = "The stable code a client switches on", example = "enrichment_behind")
            PreviewNoteCode code,

            @Schema(description = "`warning` for a condition worth acting on before arming, `info` otherwise",
                    example = "info")
            PreviewNoteSeverity severity,

            @Schema(description = "The column, output or enrichment the note is about, for the codes that name one",
                    example = "usage_sentiment")
            String subject,

            @Schema(description = "What the note means here, for people")
            String message) {}

    /**
     * Body of {@code POST /v1/pipelines/{name}/refresh}. Field names match
     * {@code MaterializeResponse}, the Class-1 write path's equivalent — the two report the same three facts
     * about one bounded window, so a client draining a backlog reads them the same way.
     */
    public record RefreshDto(
            @Schema(description = "Group-key rows written — one per recomputed key, not per input row",
                    example = "128")
            @JsonProperty("rows_written") long rowsWritten,

            @Schema(description = "Whether input remains beyond this window (the probe filled its limit), "
                    + "so another refresh will make further progress", example = "false")
            @JsonProperty("has_more") boolean hasMore,

            @Schema(description = "How far behind the input the rollup now is, in seconds", example = "5")
            @JsonProperty("lag_seconds") long lagSeconds) {}
}
