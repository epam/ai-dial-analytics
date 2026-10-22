package com.epam.aidial.analytics.web.dto.table;

import com.epam.aidial.analytics.service.table.Cardinality;
import com.epam.aidial.analytics.service.table.PartitionGranularity;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for {@code POST /v1/tables}, which is <b>identity-only</b> and issues no ClickHouse DDL:
 * it records the table's identity and catalog metadata as a {@code PENDING} definition. Required fields
 * are {@code name} and {@code type} (plus {@code source_table} for an enrichment); {@code description}
 * and {@code tag_order} are optional catalog metadata. The physical-schema fields — {@code columns},
 * {@code ordering_key}, {@code partition_by}, {@code grain_key}, {@code cardinality}, {@code ttl} — are NOT accepted
 * here (supplying any of them is rejected with 422); the physical schema is defined via
 * {@code POST /v1/tables/{name}/schema}, which defines it and materializes the table in one call. The
 * fields remain on this record only so that a client that still sends them gets a clear validation error
 * pointing to the schema endpoint rather than a silently-ignored field.
 */
public record CreateTableRequest(
        @Schema(description = "Table name (also the ClickHouse table name)", example = "widget_metrics")
        @NotBlank String name,

        @Schema(description = "Human-readable description", example = "Per-widget metrics")
        String description,

        @Schema(description = "Table kind", example = "source")
        @NotNull TableType type,

        @Schema(description = "Server-controlled and **ignored**: a created table is never `system`, "
                + "whatever the body says (only a seeded, migration-owned table is). It is part of the "
                + "vocabulary rather than omitted from it because it is echoed by every table read, so a "
                + "caller creating a table from one it read back would otherwise be refused for a field it "
                + "did not choose to send — request bodies reject fields they do not declare.",
                accessMode = Schema.AccessMode.READ_ONLY)
        Boolean system,

        @Schema(description = "Write discipline (source only; defaults to append). `append` keeps every "
                + "row (MergeTree); `upsert_by_key` collapses to the latest row per ordering_key "
                + "(ReplacingMergeTree), making the ordering_key the table's unique key. Immutable — it "
                + "selects the ClickHouse engine, which is frozen at materialization. An enrichment "
                + "always collapses by its grain key, so the member is rejected for one.",
                example = "append")
        TableWriteMode write,

        @Schema(description = "Not accepted at create; define columns via POST /v1/tables/{name}/schema "
                + "(a body carrying columns is rejected with 422)")
        List<ColumnRequest> columns,

        @Schema(description = "MergeTree ORDER BY columns, physical source_names (source only)")
        @JsonProperty("ordering_key") List<String> orderingKey,

        @Schema(description = "Optional time-partitioning of the source (source only)")
        @JsonProperty("partition_by") @Valid PartitionByRequest partitionBy,

        @Schema(description = "Source table this enrichment enriches (enrichment only)", example = "widget_metrics")
        @JsonProperty("source_table") String sourceTable,

        @Schema(description = "Grain key joining this enrichment to its source (enrichment only); any "
                + "declared column of the source (not necessarily one of its ordering_key columns)", example = "widget_id")
        @JsonProperty("grain_key") String grainKey,

        @Schema(description = "Enrichment cardinality (enrichment only; v1: zero_or_one)")
        Cardinality cardinality,

        @Schema(description = "Not accepted at create; declare the TTL via POST /v1/tables/{name}/schema "
                + "(a body carrying ttl is rejected with 422)")
        TtlRequest ttl,

        @Schema(description = "Optional per-table ordering of column tag names, a display hint for "
                + "grouping columns by tag on the read/discovery surfaces (max 100 entries)")
        @JsonProperty("tag_order") List<String> tagOrder) {

    /** One exposed column: physical {@code source_name} → exposed {@code name}, typed, optionally nullable. */
    public record ColumnRequest(
            @Schema(description = "Physical ClickHouse column name", example = "score")
            @JsonProperty("source_name") @NotBlank String sourceName,

            @Schema(description = "Exposed (flat) field name", example = "score")
            @NotBlank String name,

            @Schema(description = "Field type", example = "long")
            @NotNull QueryFieldType type,

            @Schema(description = "Scalar element type of an array column (required iff type is array; "
                    + "array and object elements are not allowed)", example = "string")
            @JsonProperty("element_type") QueryFieldType elementType,

            @Schema(description = "Declared value domain of an enum column (required iff type is enum, "
                    + "rejected on any other type): 1-512 distinct non-blank values of at most 64 "
                    + "characters each, materialized as ClickHouse Enum8/Enum16. Order is significant — it "
                    + "assigns each value's id, so the column sorts in declared order rather than "
                    + "alphabetically. Immutable once the column exists: widen a domain by dropping and "
                    + "re-adding the column",
                    example = "[\"pending\", \"running\", \"failed\"]")
            @JsonProperty("enum_values") List<String> enumValues,

            @Schema(description = "Whether the column is nullable; defaults to false for sources, true for "
                    + "enrichments. An array column is always non-nullable (ClickHouse has no "
                    + "Nullable(Array)); declaring it nullable is rejected")
            Boolean nullable,

            @Schema(description = "Column description")
            String description,

            @Schema(description = "Optional user-friendly display name for the column (max 128 chars)",
                    example = "Total money spend")
            @JsonProperty("display_name") String displayName,

            @Schema(description = "Optional label to help identify the column (max 64 chars)", example = "user_email")
            String tag,

            @Schema(description = "Mark the column sensitive: it is visible, readable and changeable only "
                    + "by callers holding the TABLE_SENSITIVE clearance on this table alongside the verb they use. "
                    + "Declaring or changing the flag requires TABLE_MODIFY and TABLE_SENSITIVE (defaults to false)", example = "false")
            Boolean sensitive,

            @Schema(description = "Mark the column heavy: expensive to transfer, so wildcard projections "
                    + "(SQL SELECT *, or a row-mode query with no select) skip it. Still returned when named "
                    + "explicitly — a cost hint, not access control (defaults to false)", example = "false")
            Boolean heavy) {}

    /** Source time-partitioning: a declared column and a time granularity (e.g. request_time + month). */
    public record PartitionByRequest(
            @Schema(description = "Declared source column to partition by", example = "request_time")
            @NotBlank String column,

            @Schema(description = "Time granularity", example = "month")
            @NotNull PartitionGranularity granularity) {}
}
