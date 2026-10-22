package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.SystemTableException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA entity for a row of the {@code table_definition} catalog table. The {@code name} is the primary
 * key and equals the ClickHouse table name (and the {@code {table_id}} in the API path). The column
 * mapping ({@link ColumnMappingEntity}) lives in its own table keyed by this name; the MergeTree
 * {@code orderingKey} and an enrichment's {@code grainKey} are owned here.
 */
@Entity
@Audited
@Table(name = "table_definition")
@Getter
@Setter
@NoArgsConstructor
public class TableDefinitionEntity {

    @Id
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    private TableType type;

    /**
     * The <em>declared</em> write discipline (see {@link TableWriteMode}). Immutable: declared at create
     * and rejected on every mutating endpoint, because an engine is fixed by {@code CREATE TABLE}.
     * Caller-settable only on a {@link TableType#SOURCE}, so for an enrichment this column holds the
     * column default rather than the discipline the table actually has — read
     * {@link #effectiveWriteMode()}, never this field, wherever the answer has to describe the engine.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "write_mode", nullable = false)
    private TableWriteMode writeMode = TableWriteMode.APPEND;

    /** The source this enrichment enriches; null unless {@link #type} is {@link TableType#ENRICHMENT}. */
    @Column(name = "source_table")
    private String sourceTable;

    @Enumerated(EnumType.STRING)
    private TableStatus status;

    /**
     * Whether this table is owned/managed by ADAS itself (materialized out of band, and for the seeded
     * sources fed by the data-sync ingest job writing directly to ClickHouse). Server-controlled
     * catalog metadata: seeded via migration, never settable through the API. A {@code system} table is
     * fully readable/queryable but rejects every modifying {@code /v1/tables} operation.
     */
    @Column(name = "system", nullable = false)
    private boolean system;

    /**
     * The column defining this source's version/order for the governed incremental scan — monotonic,
     * assigned at ingest, never backdated. Together with {@link #identityColumn} it forms the unique
     * total order the scan pages by. Null for a table that is not a scan source; a scan of such a
     * source is rejected with 422. Operator-configured via migration, never settable through the API
     * (the scan's exactly-once guarantee rests on physical properties the API cannot verify).
     */
    @Column(name = "version_column")
    private String versionColumn;

    /** The tiebreaker that makes {@link #versionColumn} a unique total order; see that field. */
    @Column(name = "identity_column")
    private String identityColumn;

    /** Optional source PARTITION BY column (physical source_name); null when unpartitioned. */
    @Column(name = "partition_column")
    private String partitionColumn;

    /** Time granularity of {@link #partitionColumn}; non-null iff {@link #partitionColumn} is set. */
    @Enumerated(EnumType.STRING)
    @Column(name = "partition_granularity")
    private PartitionGranularity partitionGranularity;

    /**
     * Optional row-expiry TTL column (physical source_name of a non-nullable date/timestamp column);
     * null when the table has no TTL. The three TTL fields are set together or not at all (mirroring
     * the ClickHouse {@code TTL <column> + INTERVAL <interval> <unit>} clause). The seeded system
     * tables' migration-defined TTLs are deliberately not mirrored here.
     */
    @Column(name = "ttl_column")
    private String ttlColumn;

    /** TTL interval count; non-null iff {@link #ttlColumn} is set. */
    @Column(name = "ttl_interval")
    private Integer ttlInterval;

    /** TTL interval unit; non-null iff {@link #ttlColumn} is set. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ttl_unit")
    private TtlUnit ttlUnit;

    /** The stored TTL as a service-layer value, or null when the table has no TTL. */
    public TtlInput ttl() {
        return ttlColumn == null ? null : new TtlInput(ttlColumn, ttlInterval, ttlUnit);
    }

    /** Sets or clears (on {@code null}) the three TTL fields together. */
    public void setTtl(TtlInput ttl) {
        this.ttlColumn = ttl == null ? null : ttl.column();
        this.ttlInterval = ttl == null ? null : ttl.interval();
        this.ttlUnit = ttl == null ? null : ttl.unit();
    }

    /**
     * MergeTree ORDER BY columns (physical source_names), in order. For an enrichment, its grain key.
     * Stored as a Postgres {@code text[]}: an array is ordered, which is the only thing the former
     * {@code table_ordering_key} collection table's {@code ordinal} bought, and it makes a duplicate
     * position structurally impossible rather than merely constrained. Empty (never null) for a table
     * whose schema is not yet defined.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "ordering_key", columnDefinition = "text[]", nullable = false)
    private List<String> orderingKey = new ArrayList<>();

    /**
     * The column an enrichment is keyed on and LEFT-joined by ({@code source.grain_key =
     * enrichment.grain_key}) — a declared column of its {@link #sourceTable}, carried physically on the
     * enrichment and hidden from the flat query schema. It is also the enrichment's entire
     * {@link #orderingKey}, which is what makes the declared {@code ZERO_OR_ONE} cardinality physically
     * true under {@code ReplacingMergeTree}.
     *
     * <p>Always null on a {@link TableType#SOURCE} (enforced by the {@code grain_key_only_on_enrichment}
     * check), and null on an enrichment until its schema is defined — an enrichment is created as a
     * grain-less shell and gets its grain at {@code POST /v1/tables/{name}/schema}. A null here therefore
     * means "no grain yet", <em>not</em> "not an enrichment": test {@link #type} for that.
     */
    @Column(name = "grain_key")
    private String grainKey;

    /**
     * Optional per-table ordering of column tag names — a display hint for grouping columns by tag on
     * the read and discovery surfaces. {@code null} when no ordering is set (the default, which
     * preserves the pre-existing declared-order behaviour). Stored denormalized as a Postgres
     * {@code text[]} column; deliberately decoupled from the tags currently on columns (entries need
     * not match any column tag, and column add/drop/rename/update never mutate it).
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tag_order", columnDefinition = "text[]")
    private List<String> tagOrder;

    /** Per-table grant lists, one per table-scoped privilege; see {@link TableAccess}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "access", nullable = false)
    private TableAccess access = new TableAccess();

    /**
     * Per-role row policies as JSON text ({@code {"<role>": {"filter": …}}}); read and
     * written through {@code RowPolicyCodec}, never parsed here.
     *
     * <p>Held as text rather than as a mapped value class for the reason {@code SavedQueryEntity.query} is:
     * Hibernate's JSON format mapper is Jackson 2 and cannot see the Jackson 3 wiring the DSL's
     * {@code FilterNode} depends on. Text also makes the Envers dirty check a plain string comparison.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_policies", nullable = false)
    private String rowPolicies = "{}";

    /**
     * Guards every modifying {@code /v1/tables} operation. A {@code system} table is protected from
     * modification via the API (it is materialized out of band and, for the seeded sources, fed by the
     * data-sync ingest job), so update, schema change, delete, and row write are all refused.
     *
     * @throws SystemTableException if this definition is {@code system}
     */
    public void requireModifiable() {
        if (system) {
            throw new SystemTableException(
                    "table '" + name + "' is system-owned and cannot be modified via the API");
        }
    }

    /**
     * Whether this table has been materialized in ClickHouse. Only an {@link TableStatus#ACTIVE}
     * definition has a confirmed ClickHouse table (and is queryable/writable); {@link TableStatus#PENDING}
     * and {@link TableStatus#FAILED} are the un-materialized states; their schema is defined and
     * materialized via {@code POST /v1/tables/{name}/schema}. A table never transitions back to an
     * un-materialized state.
     */
    public boolean isMaterialized() {
        return status == TableStatus.ACTIVE;
    }

    /**
     * The write discipline this table actually has — the one that describes its engine rather than the one
     * a caller was allowed to declare. An {@link TableType#ENRICHMENT} is always a
     * {@code ReplacingMergeTree(_updated_at)} collapsed on its grain key, so its discipline is
     * {@link TableWriteMode#UPSERT_BY_KEY} whatever {@link #writeMode} holds; only a
     * {@link TableType#SOURCE} takes its discipline from the declaration.
     *
     * <p>The two differ because the facet is not caller-settable on an enrichment: the stored column keeps
     * its {@code APPEND} default there, which as a <em>declaration</em> is fine (nothing reads it to choose
     * the engine — {@code TableDdlRenderer} derives the collapse from the kind) but as a <em>description</em>
     * says the opposite of what the engine does. Since the write facet exists to describe the engine and
     * not merely to have selected it, every read surface reports this, not the raw field.
     */
    public TableWriteMode effectiveWriteMode() {
        return type == TableType.ENRICHMENT ? TableWriteMode.UPSERT_BY_KEY : writeMode;
    }
}
