package com.epam.aidial.analytics.service.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity for a row of the {@code dashboard} table: an authored dashboard holding intent only — its
 * widgets' query references or inline bodies, their rendering hints and layout, and the dashboard-wide
 * time intent and comparison. Nothing a run produces is stored here.
 *
 * <p>The widgets are one JSON document on the row rather than a child table: the client writes the whole
 * document on every save, nothing addresses a single widget, and a widget change is one {@code Update} of
 * the dashboard in the audit trail. Like every JSON payload in this catalog they are held as text and
 * converted by {@link DashboardJson} with the application's mapper, because Hibernate's JSON mapper is
 * Jackson 2 and cannot read the structured-query DSL an inline body may carry.
 *
 * <p>{@link #generation} is the optimistic-locking version, so a replace whose stored row moved on since
 * the caller read it fails at flush rather than overwriting the other write. A {@code Long} wrapper on
 * purpose: Spring Data treats a null version as new, which is what lets {@code save} persist an entity
 * whose id was assigned up front. Envers leaves a version field out of the audit table by default, and
 * the trail does not need it: every mutation bumps it by one and records a revision.
 *
 * <p>{@link #ownerId} follows the saved-query rules: the authoring principal, stored opaquely, preserved
 * across every replace, and {@code null} in {@code none} mode.
 */
@Entity
@Audited
@Table(name = "dashboard")
@Getter
@Setter
@NoArgsConstructor
public class DashboardEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DashboardVisibility visibility;

    /** The authoring identity's principal; null in {@code none} mode. The only thing ownership is decided by. */
    @Column(name = "owner_id")
    private String ownerId;

    /** The author's email at creation — display only, never compared to decide ownership. */
    @Column(name = "owner_email")
    private String ownerEmail;

    /** The dashboard's time intent as JSON text, in the saved-query {@code time} model, or null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "time_intent")
    private String timeIntent;

    /** The period comparison, or null when the client sent none; an explicit {@code off} is kept as sent. */
    @Enumerated(EnumType.STRING)
    @Column(name = "comparison")
    private DashboardCompare compare;

    /** The ordered widget array as JSON text; see the class comment for why it is one document. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "widgets", nullable = false)
    private String widgets;

    @Version
    @Column(nullable = false)
    private Long generation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
