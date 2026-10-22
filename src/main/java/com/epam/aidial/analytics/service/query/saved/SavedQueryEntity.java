package com.epam.aidial.analytics.service.query.saved;

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

import java.time.Instant;
import java.util.List;

/**
 * JPA entity for a row of the {@code saved_query} table: a named, reusable Query Builder state holding
 * authored <strong>intent</strong> only.
 *
 * <p>Exactly one of {@link #query} / {@link #sqlText} is set (enforced by the service layer and by a
 * table CHECK). {@link #source} is derived from whichever body is present and is never accepted from a
 * request, so the entity and its body cannot disagree about what is being queried.
 *
 * <p>{@link #ownerId} is the authoring identity's principal, stored opaquely and never parsed; it is
 * {@code null} in {@code none} security mode, where the whole capability fails open. {@link #generation}
 * is bumped on every mutation, mirroring {@code PipelineEntity}, so a client has
 * a change detector without a new column.
 *
 * <p>Deliberately absent: the editor mode (derivable from which body is set), any run outcome
 * ({@code last_run_at}, run counts — a run is a read and must not write this row), and any declared
 * parameters (a parameterized body cannot be executed by the query translator, so it is rejected
 * rather than stored).
 */
@Entity
@Audited
@Table(name = "saved_query")
@Getter
@Setter
@NoArgsConstructor
public class SavedQueryEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    private String description;

    /** At most one free-text label; not a controlled vocabulary. */
    private String tag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SavedQueryScope scope;

    /**
     * The authoring identity's principal; null in {@code none} mode. Never parsed, and the only thing
     * ownership is decided by.
     */
    @Column(name = "owner_id")
    private String ownerId;

    /**
     * The author's email at creation time — <strong>display only</strong>, for showing "Saved by".
     *
     * <p>Never compare this to decide ownership. An email changes on a name change or a domain
     * migration and can be reassigned to a different person; {@link #ownerId} is the stable identity and
     * the only one authorization may read. Frequently null: {@code security.require-email} defaults to
     * false and a plain project-key caller has no email, so a reader must fall back.
     */
    @Column(name = "owner_email")
    private String ownerEmail;

    /**
     * Every entity the body reads from, derived from the body: one element for a single-entity body,
     * more than one for a {@code sql}-kind body that joins, or subqueries against, another entity. No
     * FK: a saved query outlives the tables it names.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> source;

    /**
     * The structured body as JSON text, or null when this saved query carries SQL instead.
     *
     * <p>Held as text rather than as a mapped {@code StructuredQuery}: Hibernate's JSON format mapper is
     * Jackson 2 and cannot see the Jackson 3 wiring the DSL's {@code FilterNode} depends on. It is
     * converted by {@code SavedQueryJson} using the application's own mapper — see that class for why.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "query")
    private String query;

    /** The SQL body, or null when this saved query carries a structured query instead. */
    @Column(name = "sql_text")
    private String sqlText;

    /** The author's time intent as JSON text, or null for "whatever the toolbar has". */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "time_intent")
    private String timeIntent;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_view", nullable = false)
    private SavedQueryResultView resultView;

    /** The chart configuration as JSON text; see {@link #query} for why these payloads are held as text. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chart")
    private String chart;

    @Column(nullable = false)
    private long generation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
