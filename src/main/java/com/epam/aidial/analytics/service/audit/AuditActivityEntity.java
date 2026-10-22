package com.epam.aidial.analytics.service.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * One row of {@code audit_activity}: the statement that a named resource changed, in a named way, at a
 * named revision, by a named identity. Denormalized on purpose — the author and timestamp are copied
 * off the revision rather than joined back to it, so the feed is a single-table read.
 *
 * <p>One row is written per changed entity, never per administrative act: a schema patch touching
 * twelve columns produces twelve rows naming exactly which columns changed. {@link #parentActivityId}
 * is what makes that affordable to read — it points at the {@code Table} activity of the same revision,
 * so a client can collapse the children under the act that caused them.
 *
 * <p>The id is a time-ordered UUIDv7, so the primary key sorts by creation time rather than scattering
 * inserts across the index as UUIDv4 would.
 */
@Entity
@Table(name = "audit_activity")
@Getter
@Setter
@NoArgsConstructor
public class AuditActivityEntity {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "activity_id", length = 36)
    private UUID activityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false)
    private ActivityType activityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    private ActivityResourceType resourceType;

    /**
     * The changed entity's identity within its resource type: a table name, or a column rendered
     * {@code <tableName>:<sourceName>}. Opaque — never parsed by a reader.
     */
    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Column(name = "epoch_timestamp_ms", nullable = false)
    private long epochTimestampMs;

    @Column(name = "initiated_author")
    private String initiatedAuthor;

    @Column(name = "initiated_email")
    private String initiatedEmail;

    /**
     * The {@code Table} activity of the same revision that this change belongs under, or null. Null is a
     * normal outcome rather than an error: a column edited without touching its definition row has no
     * parent activity to point at. Held as a plain id, not an association — the parent is written in the
     * same flush as its children, and a self-referencing foreign key would order that flush for no gain.
     */
    @Column(name = "parent_activity_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID parentActivityId;

    /**
     * The catalog import whose revision this is, copied off {@link AuditRevisionEntity#getImportId()}; null for
     * every activity an import did not write. Presence alone marks an activity as part of an import, and the
     * value is the {@code import_id} that import returned. Independent of {@link #parentActivityId}: an imported
     * column still points at its own {@code Table} activity.
     */
    @Column(name = "import_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID importId;

    /**
     * The owning revision. This is the association the cascade writes through; {@link #revisionNumber}
     * below is the same column read back as a scalar.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revision", nullable = false)
    private AuditRevisionEntity revision;

    /**
     * The revision id as a plain number, mapped read-only over the same column as {@link #revision}.
     *
     * <p>It exists so that every filterable and sortable attribute of the feed is a scalar on the root
     * entity: the request contract carries filter columns as flat names ({@code revision}), and mapping
     * one of them to an association path would make the filter layer navigate joins for a single
     * special case. Read-only ({@code insertable = false, updatable = false}) because the association
     * owns the column — two writable mappings of one column is how a silent double-write starts.
     */
    @Column(name = "revision", insertable = false, updatable = false)
    private int revisionNumber;
}
