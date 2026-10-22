package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.service.query.PartitionLocality;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether an enrichment is laid out <b>aligned</b> with its source — partitioned like it, carrying the source
 * partition column's value for the source row it was written from — and which column carries it.
 *
 * <p>An aligned enrichment is the shape {@code dial_usage_log_payload} has always had: one more physical column,
 * named and typed like the source's partition column, that the catalog's {@code partition_by} names. Everything
 * that already prunes the payload (the keyed join's partition restriction, {@link PartitionLocality}, the
 * tuple join, the partition pre-selection) then prunes any aligned enrichment the same way, because each of
 * them tests the same claim: an enrichment whose partition column has its source's partition column's name.
 *
 * <p><b>Eligibility is decided by ADAS, never declared.</b> An enrichment qualifies when its grain key is the
 * source's {@code identity_column} — one enrichment row per source row, so the source row's partition value is
 * the enrichment row's — and the source declares a partition column and is partition-local, so the value cannot
 * differ between a key's versions. A group-grain enrichment ({@code client_session_id} over the usage log) has
 * no single source row to take a value from and never qualifies.
 *
 * <p><b>The column is hidden</b>, like the grain key: no catalog column, nothing in describe or an export bundle.
 * The one exception is <em>adoption</em> — an enrichment that already declares a column of that name, type and
 * non-nullability keeps it as a visible catalog column and the system writes it, so a table authored before this
 * existed (say a {@code request_time} column that drives the table's TTL) is aligned without losing
 * the column its users know.
 */
public final class EnrichmentAlignment {

    private EnrichmentAlignment() {
    }

    /**
     * The column an enrichment of {@code source} keyed on {@code grainKey} is aligned on, or null when it does
     * not qualify (see the class note).
     */
    public static String eligibleColumn(String grainKey, TableDefinitionEntity source) {
        if (grainKey == null || source == null || source.getType() != TableType.SOURCE) {
            return null;
        }
        final String partition = source.getPartitionColumn();
        // A source whose identity column is also its partition column: the grain key already is the partition
        // value, so there is no second column to add — and adding one of the same name would fail the CREATE.
        if (partition == null || partition.equals(grainKey) || !grainKey.equals(source.getIdentityColumn())) {
            return null;
        }
        return PartitionLocality.isPartitionLocal(source, null) ? partition : null;
    }

    /**
     * The alignment column of {@code enrichment}, or null when it is not aligned: its partition column, when
     * that is its source's partition column by name — the same claim {@code CatalogQueryService} keys a join
     * on, so the two can never disagree about which tables are aligned.
     */
    public static String alignedColumn(TableDefinitionEntity enrichment, TableDefinitionEntity source) {
        if (enrichment == null || source == null || enrichment.getType() != TableType.ENRICHMENT) {
            return null;
        }
        final String partition = enrichment.getPartitionColumn();
        return partition != null && partition.equals(source.getPartitionColumn()) ? partition : null;
    }

    /**
     * Whether the alignment column {@code column} is hidden — absent from {@code catalogColumns}, the
     * enrichment's own column mapping — rather than adopted from a declared column.
     */
    public static boolean isHidden(String column, Collection<ColumnMappingEntity> catalogColumns) {
        return catalogColumns.stream().noneMatch(col -> col.getSourceName().equals(column));
    }

    /**
     * Whether a declared column can be adopted as the alignment column whose source column is {@code source}:
     * the same type and element type, and non-nullable, like the hidden column it stands in for — and neither
     * {@code sensitive} nor {@code heavy}. The system writes the source row's partition value into it, so a
     * {@code sensitive} flag would claim a protection the source column's own visibility already decides, and a
     * {@code heavy} one would mark a sort-key-cheap scalar as a body to late-materialize.
     */
    public static boolean adoptable(ColumnMappingEntity declared, ColumnMappingEntity source) {
        return adoptable(declared.getType(), declared.getElementType(), declared.isNullable(), declared.isSensitive(),
                declared.isHeavy(), source);
    }

    /** As {@link #adoptable(ColumnMappingEntity, ColumnMappingEntity)}, over a column's declared attributes. */
    public static boolean adoptable(QueryFieldType type, QueryFieldType elementType, boolean nullable, boolean sensitive,
            boolean heavy, ColumnMappingEntity source) {
        return !nullable && !sensitive && !heavy
                && type == source.getType()
                && Objects.equals(elementType, source.getElementType());
    }

    /** The column of {@code columns} whose physical name is {@code sourceName}, if any. */
    public static Optional<ColumnMappingEntity> physicalColumn(Collection<ColumnMappingEntity> columns, String sourceName) {
        return columns.stream().filter(col -> col.getSourceName().equals(sourceName)).findFirst();
    }
}
