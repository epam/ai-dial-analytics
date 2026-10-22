package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * JPA entity for one EXPOSED column of a catalog table ({@code column_mapping}). {@code sourceName}
 * is the physical ClickHouse column; {@code name} is the alias clients see in the flat schema. The
 * type is the abstract {@link QueryFieldType}; {@code elementType} is set only for {@code ARRAY}, and
 * {@code enumValues} only for {@code ENUM}. {@code nullable}
 * renders the ClickHouse column as {@code Nullable(T)}. Hidden plumbing columns (an enrichment's grain
 * key, system {@code _}-prefixed columns) are NOT stored here — they never appear in the flat schema.
 */
@Entity
@Audited
@Table(name = "column_mapping")
@IdClass(ColumnMappingId.class)
@Getter
@Setter
@NoArgsConstructor
public class ColumnMappingEntity {

    @Id
    @Column(name = "table_name")
    private String tableName;

    @Id
    @Column(name = "source_name")
    private String sourceName;

    private String name;

    private QueryFieldType type;

    @Column(name = "element_type")
    private QueryFieldType elementType;

    /**
     * The declared domain of an {@link QueryFieldType#ENUM} column — its closed set of string values,
     * the type parameter {@code enum} takes the way {@code array} takes an {@code element_type}.
     * {@code null} for a column of any other type (a non-enum column declaring values is rejected at
     * validation).
     *
     * <p><b>Order is significant and is never sorted.</b> Each value's 1-based position here is the
     * numeric id it gets in the ClickHouse {@code Enum8}/{@code Enum16} type, which is both what the
     * read surfaces return and why {@code ORDER BY} on an enum column sorts by declared order (say,
     * {@code low}/{@code medium}/{@code high}) rather than alphabetically. Immutable for the life of
     * the column: widening a domain is a drop-and-re-add, since {@code ALTER MODIFY COLUMN} on a
     * ClickHouse enum rewrites the column and can fail against rows already stored.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "enum_values", columnDefinition = "text[]")
    private List<String> enumValues;

    private boolean nullable;

    private String description;

    /**
     * Short, human-friendly display name for this column (e.g. {@code "Total money spend"} for a column
     * exposed as {@code total_money}), used to render the field beautifully in consumer UIs. Catalog-only
     * metadata (never part of the ClickHouse DDL, like {@code tag}); {@code null} when unset.
     */
    @Column(name = "display_name")
    private String displayName;

    private String tag;

    /**
     * Whether this column is sensitive. Catalog-only metadata (never part of the ClickHouse DDL, like
     * {@code tag}); when {@code true} the column is exposed on the query/discovery path only to
     * callers holding the {@code TABLE_SENSITIVE} clearance on this table (alongside the verb they use). Defaults to {@code false}.
     */
    private boolean sensitive;

    /**
     * Whether this column is heavy — expensive to transfer, typically a large payload/blob. Catalog-only
     * metadata (never part of the ClickHouse DDL, like {@code tag}); when {@code true} the column is
     * omitted from <em>wildcard</em> projections (a SQL {@code SELECT *}, or a row-mode DSL query with no
     * {@code select}) and returned normally when named explicitly. It is a cost hint, NOT access
     * control: unlike {@code sensitive} it hides nothing and restricts no clause. Defaults to
     * {@code false}.
     */
    private boolean heavy;

    private int ordinal;
}
