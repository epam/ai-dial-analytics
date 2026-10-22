package com.epam.aidial.analytics.web.dto.table;

import com.epam.aidial.analytics.service.table.Cardinality;
import com.epam.aidial.analytics.service.table.PartitionGranularity;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.service.table.TtlUnit;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.table.access.AccessDto;
import com.epam.aidial.analytics.web.dto.table.rowpolicy.RowPolicyDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * A table definition as returned by the management API. {@code grain} is present only for an enrichment
 * and {@code partition_by} for any table that declares one (settable only on a source; see
 * {@link com.epam.aidial.analytics.service.query.EnrichmentJoin} for what an enrichment's means), and
 * {@code columns} is the exposed column list (returned
 * by single-get; omitted on the list endpoint, which carries the {@code column_count} of the same set
 * instead). System columns and the hidden grain key never appear (nor are they counted).
 *
 * <p>{@code identity_column}/{@code version_column} are the scan's total-order pair — a source's alone,
 * never an enrichment's, and optional even there. Either is absent when the definition does not declare it,
 * and a source missing either is not scannable. A {@code _}-prefixed value is a system column and so
 * legitimately not among {@code columns}: the seeded sources' {@code _ingested_at}, or an
 * {@code upsert_by_key} source's {@code _updated_at}.
 *
 * <p>{@code access} and {@code row_policies} are carried by the revision read alone, and only to a caller
 * holding {@code TABLE_GRANT} (or in {@code none} mode) — the bar both live grant reads set — so a change
 * to either is visible in a before/after of two revisions. Every other response leaves them null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableDto(
        @Schema(description = "Table name", example = "widget_metrics")
        String name,

        @Schema(description = "Human-readable description")
        String description,

        @Schema(description = "Table kind", example = "source")
        TableType type,

        @Schema(description = "Write discipline: `append` keeps every row (MergeTree), `upsert_by_key` "
                + "collapses to the latest row per ordering_key (ReplacingMergeTree). Immutable — "
                + "declared at create, since it selects the ClickHouse engine.", example = "append")
        TableWriteMode write,

        @Schema(description = "Source table this enrichment enriches (enrichment only)")
        @JsonProperty("source_table") String sourceTable,

        @Schema(description = "Materialization lifecycle status", example = "active")
        TableStatus status,

        @Schema(description = "Whether the table is system-owned (managed by ADAS). Read-only: seeded "
                + "server-side, never settable via the API. A system table is fully readable and "
                + "queryable but rejects modification (update, schema change, delete, row write).",
                example = "false", accessMode = Schema.AccessMode.READ_ONLY)
        boolean system,

        @Schema(description = "Physical column identifying one row of this source — the tiebreaker half of "
                + "the tuple the governed incremental scan pages it by. Absent for an enrichment, and for a "
                + "source that declares none, which is what makes such a table unscannable.",
                example = "event_id")
        @JsonProperty("identity_column") String identityColumn,

        @Schema(description = "Physical timestamp column carrying this source's row version — the column the "
                + "governed incremental scan orders and safety-lags by. Absent for an enrichment, and for a "
                + "definition that declares none.", example = "seen_at")
        @JsonProperty("version_column") String versionColumn,

        @Schema(description = "MergeTree ORDER BY columns (physical source_names)")
        @JsonProperty("ordering_key") List<String> orderingKey,

        @Schema(description = "Time partitioning, if the table declares any. Settable only on a source; "
                + "present on an enrichment when the catalog declares one, which claims that the column "
                + "holds the same value as the source's partition column for the same grain key and lets a "
                + "grain join prune the enrichment's partitions")
        @JsonProperty("partition_by") PartitionByDto partitionBy,

        @Schema(description = "Row-expiry TTL (absent when the table has none). Declared via "
                + "POST /v1/tables/{name}/schema and modified/removed via the ttl member of "
                + "PATCH /v1/tables/{name}/schema.")
        TtlDto ttl,

        @Schema(description = "Grain of an enrichment (enrichment only)")
        GrainDto grain,

        @Schema(description = "Per-table ordering of column tag names (absent when not set). When set, "
                + "the columns list is returned sorted by this ordering.")
        @JsonProperty("tag_order") List<String> tagOrder,

        @Schema(description = "Exposed columns (single-get only)")
        List<ColumnDto> columns,

        @Schema(description = "Number of exposed columns (list only; single-get carries `columns` instead)",
                example = "12")
        @JsonProperty("column_count") Integer columnCount,

        @Schema(description = "The calling identity's effective permissions on this table. Read-only: "
                + "computed server-side per request, never settable via the API.",
                accessMode = Schema.AccessMode.READ_ONLY)
        PermissionsDto permissions,

        @Schema(description = "The table's grant lists as of the revision, in the shape of "
                + "GET /v1/tables/{name}/access. Revision read only, and only for a caller holding TABLE_GRANT "
                + "(always in `none` mode); absent means withheld, not empty.",
                accessMode = Schema.AccessMode.READ_ONLY)
        AccessDto access,

        @Schema(description = "The table's row policies as of the revision, keyed by role, in the shape of the "
                + "`policies` member of GET /v1/tables/{name}/row-policies. Revision read only, and only for a "
                + "caller holding TABLE_GRANT (always in `none` mode); absent means withheld, not empty.",
                accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty("row_policies") Map<String, RowPolicyDto> rowPolicies,

        @Schema(description = "An enrichment's current layout relative to its source (absent from the list and "
                + "the revision read, and when there is nothing to report): `aligned` — partitioned like its source and joined on (grain key, partition "
                + "column); `migrating` — ADAS is relaying the existing table out into that layout, by itself; "
                + "`unsupported` — the relayout skipped it (an llm writer, a column it cannot adopt, or a database "
                + "engine that cannot exchange tables).",
                allowableValues = {"aligned", "migrating", "unsupported"}, accessMode = Schema.AccessMode.READ_ONLY)
        String layout) {

    /**
     * One exposed column of the table. {@code element_type} is present only for {@code array} columns,
     * and {@code enum_values} only for {@code enum} columns — where it lists the column's declared domain
     * in declared order, which is also the order the column sorts in.
     */
    public record ColumnDto(
            @JsonProperty("source_name") String sourceName,
            String name,
            QueryFieldType type,
            @JsonProperty("element_type") QueryFieldType elementType,
            @JsonProperty("enum_values") List<String> enumValues,
            boolean nullable,
            String description,
            @JsonProperty("display_name") String displayName,
            String tag,
            boolean sensitive,
            boolean heavy) {}

    /**
     * The caller's effective permissions on the table, one flag per grantable privilege, mirroring what
     * enforcement would decide and reported <b>after</b> implication — so a role granted only {@code read}
     * reports {@code read} and {@code get} both true. Always present, every flag a boolean. There is no
     * {@code list} flag: a caller seeing the table at all can list it, so it would always read {@code true}.
     *
     * <p>A {@code system} table reports {@code write=false, modify=false} for every caller, because every
     * modifying operation on it is refused whatever any grant says, while its read-side flags are
     * evaluated from the grant lists like any other table's. In {@code none} security mode every
     * non-system privilege flag is {@code true}.
     *
     * <p>{@code rows_restricted} is not a privilege: it reports whether row policies confine the caller's reads
     * to some of the table's rows, and is {@code false} in {@code none} mode.
     */
    public record PermissionsDto(
            @Schema(description = "Whether the caller may read this table's definition and columns") boolean get,
            @Schema(description = "Whether the caller may read this table's non-sensitive values") boolean read,
            @Schema(description = "Whether the caller holds the sensitive clearance on this table: combined with "
                    + "get, read or modify it extends that verb to the sensitive columns")
            boolean sensitive,
            @Schema(description = "Whether the caller may insert rows into this table") boolean write,
            @Schema(description = "Whether the caller may change this table's schema or description") boolean modify,
            @Schema(description = "Whether row policies confine the caller's reads of this table to some of its rows; "
                    + "the policy itself is readable only through GET /v1/tables/{name}/row-policies")
            @JsonProperty("rows_restricted") boolean rowsRestricted) {}

    /** Source time-partitioning descriptor. */
    public record PartitionByDto(String column, PartitionGranularity granularity) {}

    /** Row-expiry TTL descriptor: rows expire once {@code column} is older than {@code interval} {@code unit}s. */
    public record TtlDto(String column, Integer interval, TtlUnit unit) {}

    /** Enrichment grain descriptor. */
    public record GrainDto(@JsonProperty("grain_key") String grainKey, Cardinality cardinality) {}
}
