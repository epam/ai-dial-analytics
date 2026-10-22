package com.epam.aidial.analytics.web.dto.table;

import com.epam.aidial.analytics.service.table.Cardinality;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.PartitionByRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request body for {@code PATCH /v1/tables/{name}/schema} — the live-schema surface, valid only for a
 * materialized ({@code ACTIVE}) table. Columns are evolved by {@code add} (new columns), {@code drop}
 * (exposed names to remove), and {@code rename} (exposed-name → new name) — each becomes an immediate
 * ClickHouse {@code ALTER} — plus {@code update}, a merge-patch over a column's catalog-only metadata
 * ({@code tag}, {@code display_name}, {@code sensitive}, {@code heavy}, {@code description}; no
 * {@code ALTER}). Within an {@code update} entry each metadata field follows merge-patch semantics: a
 * field that is <em>absent or null</em> leaves that attribute unchanged; a <em>blank</em> string field
 * clears it; a <em>non-blank</em> string field sets it; and {@code sensitive}/{@code heavy}, when
 * present, set that flag. A column may be the target of at most one structural operation per request. Type changes, and drops/renames of
 * system, grain/ordering-key, or scan-metadata ({@code identity_column}/{@code version_column})
 * columns, are rejected. The optional {@code ttl} member is the one
 * mutable physical member: a merge-patch of the table's row-expiry TTL (absent/null leaves it
 * unchanged, a full object sets/replaces it, an empty object removes it). All physical commands of one
 * request are issued as a single multi-command {@code ALTER} statement (drop → rename → add → ttl),
 * validated by ClickHouse as a whole.
 *
 * <p>The frozen physical keys ({@code ordering_key}, {@code partition_by}, {@code grain_key},
 * {@code cardinality}) are fixed after materialization (they are set when the schema is defined via
 * {@code POST /v1/tables/{name}/schema}), and the table's tag ordering ({@code tag_order}) is table
 * catalog metadata set via {@code PUT /v1/tables/{name}}. They are declared
 * on this record only so that a caller who sends them gets a clear 422 pointing to the right endpoint
 * rather than a silently-ignored field — supplying any of them here is rejected.
 */
public record PatchSchemaRequest(
        @Schema(description = "Columns to add (ALTER ... ADD COLUMN)")
        @Valid List<ColumnRequest> add,

        @Schema(description = "Exposed names of columns to drop (ALTER ... DROP COLUMN)")
        List<String> drop,

        @Schema(description = "Columns to rename (ALTER ... RENAME COLUMN)")
        @Valid List<RenameColumn> rename,

        @Schema(description = "Per-column metadata merge-patch; metadata only, no ALTER")
        @Valid List<ColumnMetadataUpdate> update,

        @Schema(description = "Row-expiry TTL merge-patch: absent/null leaves the TTL unchanged, a full "
                + "object sets or replaces it (ALTER ... MODIFY TTL), an empty object {} removes it "
                + "(ALTER ... REMOVE TTL); a partial object is rejected (422)")
        @Valid TtlRequest ttl,

        @Schema(description = "Not accepted here (422): physical keys are frozen after materialization; "
                + "ordering_key is set when the schema is defined via POST /v1/tables/{name}/schema")
        @JsonProperty("ordering_key") List<String> orderingKey,

        @Schema(description = "Not accepted here (422): partition_by is set when the schema is defined via "
                + "POST /v1/tables/{name}/schema")
        @JsonProperty("partition_by") PartitionByRequest partitionBy,

        @Schema(description = "Not accepted here (422): grain_key is set when the schema is defined via "
                + "POST /v1/tables/{name}/schema")
        @JsonProperty("grain_key") String grainKey,

        @Schema(description = "Not accepted here (422): cardinality is set when the schema is defined via "
                + "POST /v1/tables/{name}/schema")
        Cardinality cardinality,

        @Schema(description = "Not accepted here (422): tag ordering is table metadata — set it via "
                + "PUT /v1/tables/{name}")
        @JsonProperty("tag_order") List<String> tagOrder,

        @Schema(description = "Not accepted here (422): the write discipline selects the ClickHouse "
                + "engine, which is frozen at materialization; it is declared at POST /v1/tables")
        TableWriteMode write) {

    /** Rename the column currently exposed as {@code from} to {@code to}. */
    public record RenameColumn(
            @Schema(description = "Current exposed column name", example = "label")
            @NotBlank String from,

            @Schema(description = "New name (physical and exposed)", example = "category")
            @NotBlank String to) {}

    /**
     * Merge-patch of an exposed column's catalog-only metadata. For each string field
     * ({@code tag}/{@code display_name}/{@code description}): absent or null leaves it unchanged, a
     * blank value clears it, and a non-blank value sets it. {@code sensitive} and {@code heavy}: absent or
     * null leaves the flag unchanged, {@code true}/{@code false} sets it.
     *
     * <p>{@code enumValues} is declared here <em>only to be rejected</em>, the way the frozen physical keys
     * are on the enclosing record. An undeclared member would also be refused, but with a generic
     * unknown-field 400; declaring it lets the refusal say how a domain is widened instead.
     */
    public record ColumnMetadataUpdate(
            @Schema(description = "Exposed name of the column to update", example = "email")
            @NotBlank String name,

            @Schema(description = "Tag (max 64 chars); omit/null to leave, blank to clear", example = "user_email")
            String tag,

            @Schema(description = "Display name (max 128 chars); omit/null to leave, blank to clear",
                    example = "Total money spend")
            @JsonProperty("display_name") String displayName,

            @Schema(description = "Description (max 1024 chars); omit/null to leave, blank to clear",
                    example = "Total money spent by the user")
            String description,

            @Schema(description = "Sensitive flag; omit/null to leave unchanged", example = "true")
            Boolean sensitive,

            @Schema(description = "Heavy flag — omit the column from wildcard projections; omit/null to "
                    + "leave unchanged", example = "true")
            Boolean heavy,

            @Schema(description = "Not accepted here (422): an enum column's domain is fixed for the life "
                    + "of the column, because changing it rewrites the ClickHouse column and can fail "
                    + "against rows already stored; widen a domain by dropping and re-adding the column")
            @JsonProperty("enum_values") List<String> enumValues) {}
}
