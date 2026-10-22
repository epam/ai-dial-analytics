package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.List;

/**
 * Service-layer declaration of one EXPOSED column, used when defining a table's schema
 * ({@code POST .../schema}, a source's/enrichment's columns) and by {@code PATCH .../schema} (added
 * columns) — create is identity-only and never carries columns. {@code sourceName} is the physical ClickHouse column;
 * {@code name} is the exposed alias; {@code nullable} is {@code null} when unspecified so the caller can
 * apply a kind-based default (source columns non-nullable, enrichment columns nullable; an
 * {@code array} column is always non-nullable). {@code elementType} is the scalar element type of an
 * {@code array} column (required iff {@code type} is {@code ARRAY}, null otherwise), and
 * {@code enumValues} is the declared value domain of an {@code enum} column (required iff {@code type}
 * is {@code ENUM}, null otherwise) — the same parameter shape, for the other parameterized type. Its
 * order is significant: it assigns the ClickHouse enum ids, and so the column's sort order.
 * {@code displayName} is an optional user-friendly display name (≤128 chars) for the column.
 * {@code tag} is an optional free-form label (≤64 chars) to help consumers identify the column.
 * {@code sensitive} marks the column as restricted, on the query path, to callers holding
 * the {@code TABLE_SENSITIVE} clearance on the table that owns it
 * (catalog-only metadata, like {@code tag}); it defaults to {@code false}. {@code heavy} marks the column
 * as expensive to transfer, so wildcard projections skip it (also catalog-only metadata, and a cost hint
 * rather than access control); it defaults to {@code false}.
 */
public record ColumnInput(
        String sourceName,
        String name,
        QueryFieldType type,
        QueryFieldType elementType,
        List<String> enumValues,
        Boolean nullable,
        String description,
        String displayName,
        String tag,
        boolean sensitive,
        boolean heavy) {}
