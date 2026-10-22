package com.epam.aidial.analytics.web.dto.table;

import com.epam.aidial.analytics.service.table.Cardinality;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.PartitionByRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

import java.util.List;

/**
 * Request body for {@code POST /v1/tables/{name}/schema} — the complete physical schema of a
 * not-yet-materialized table. This single call defines the schema and materializes the table (issues
 * {@code CREATE TABLE}, flips it to {@code ACTIVE}). It carries the exposed {@code columns} plus the
 * physical keys for the table's kind: a source's {@code ordering_key}/{@code partition_by}, or an
 * enrichment's {@code grain_key}/{@code cardinality}. The identity fields ({@code name}, {@code type},
 * {@code source_table}) are fixed by {@code POST /v1/tables} and are not part of this body. The schema
 * must be complete for its kind (a source declares at least one column and a non-empty
 * {@code ordering_key}; an enrichment declares a {@code grain_key}); an incomplete or inconsistent
 * schema is rejected with 422. Every column a source's {@code ordering_key} or {@code partition_by}
 * names must be non-nullable: ClickHouse refuses a nullable sorting key and a nullable partition key,
 * so such a schema could not materialize.
 *
 * <p>A source MAY declare {@code identity_column} and {@code version_column} — the pair the governed
 * incremental scan pages it by — and a source that declares neither is simply not scannable (the scan
 * answers 400 naming the missing configuration). When declared, each must be one of this request's
 * {@code columns}, non-nullable and not {@code sensitive}, and the version column must be declared
 * {@code timestamp} — or, on an {@code upsert_by_key} source, may name the ReplacingMergeTree version
 * {@code _updated_at} instead. An enrichment declares neither (it is keyed on its {@code grain_key}, and
 * its row version is the ReplacingMergeTree column the platform stamps); either member is rejected
 * with 422.
 */
public record DefineSchemaRequest(
        @Schema(description = "Exposed columns")
        @Valid List<ColumnRequest> columns,

        @Schema(description = "MergeTree ORDER BY columns, physical source_names (source only). Each must "
                + "name a non-nullable declared column — ClickHouse refuses a nullable sorting key")
        @JsonProperty("ordering_key") List<String> orderingKey,

        @Schema(description = "Optional time-partitioning of the source (source only). The named column "
                + "must be non-nullable — ClickHouse refuses a nullable partition key")
        @JsonProperty("partition_by") @Valid PartitionByRequest partitionBy,

        @Schema(description = "Grain key joining this enrichment to its source (enrichment only); any "
                + "declared column of the source (not necessarily one of its ordering_key columns)",
                example = "widget_id")
        @JsonProperty("grain_key") String grainKey,

        @Schema(description = "Enrichment cardinality (enrichment only; v1: zero_or_one)")
        Cardinality cardinality,

        @Schema(description = "Physical column identifying one row of this source, the tiebreaker half of "
                + "the tuple the governed incremental scan pages by. Optional (a source that declares no "
                + "scan metadata is not scannable), rejected for an enrichment — see the endpoint "
                + "description. Declare it only if it is genuinely unique per row: no check here can see "
                + "that, the scan's exactly-once guarantee rests on it, and a value repeated across rows "
                + "makes the scan skip them. A rollup keyed on several columns therefore has no valid "
                + "single-column identity, and should declare none.",
                example = "event_id")
        @JsonProperty("identity_column") String identityColumn,

        @Schema(description = "Physical timestamp column carrying this table's row version — the column the "
                + "governed incremental scan orders and safety-lags by. Optional, and required alongside "
                + "identity_column for the table to be scannable; rejected for an enrichment (see the "
                + "endpoint description). Whether it is assigned at ingest, monotonic and never backdated is "
                + "a property of your ingest that no check here can see, and the scan's exactly-once "
                + "guarantee rests on it.", example = "seen_at")
        @JsonProperty("version_column") String versionColumn,

        @Schema(description = "Optional row-expiry TTL, rendered into the CREATE TABLE "
                + "(TTL <column> + INTERVAL <interval> <unit>); all three fields are required when present")
        @Valid TtlRequest ttl,

        @Schema(description = "Not accepted here; the write discipline is identity, declared at "
                + "POST /v1/tables (a body carrying write is rejected with 422)")
        TableWriteMode write) {}
