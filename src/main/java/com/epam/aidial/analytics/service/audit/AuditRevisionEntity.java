package com.epam.aidial.analytics.service.audit;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One row of {@code revinfo}: the revision that groups every audited change made in a single
 * transaction, stamped with the identity that made it. Envers writes exactly one of these per
 * transaction that touched an audited entity, and every {@code *_aud} row points back at it.
 *
 * <p>The id is an {@link GenerationType#IDENTITY} column rather than Envers' default sequence, whose
 * allocation size is 50: a hand-written {@code increment by 1} sequence in the migration would hand out
 * ids the application had already taken. Identity sidesteps the question instead of trying to match it.
 *
 * <p>{@link #activities} is the derived feed for this revision — one entry per changed entity, built by
 * {@link AuditRevisionListener} as Envers reports the changes and written out by the cascade. It is also
 * the listener's working set: dedup and parent linking both read it, which is what makes a
 * {@code ThreadLocal} unnecessary (the revision entity is already exactly the per-transaction scope).
 */
@Entity
@RevisionEntity(AuditRevisionListener.class)
@Table(name = "revinfo")
@Getter
@Setter
@NoArgsConstructor
public class AuditRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @RevisionNumber
    private int id;

    @RevisionTimestamp
    private long timestamp;

    /**
     * The principal that made the change, or {@code unknown} when the security context held no
     * authentication. Recorded for attribution only — never read to authorize anything.
     */
    private String author;

    /** The caller's email when the token carried one; frequently null, and null whenever author is unknown. */
    private String email;

    /**
     * The catalog import this revision was written by, from {@link AuditImportScope}; null for a revision any
     * other request or background work committed. Copied onto each of the revision's activities.
     */
    @Column(name = "import_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID importId;

    /**
     * {@code PERSIST} only, not {@code ALL}: writing the revision must write the activities derived from
     * it, and nothing else about them is a cascade. {@code ALL} would include {@code REMOVE}, quietly
     * arming a path that deletes audit entries as a side effect of deleting a revision — under a
     * capability whose whole premise is that no code and no endpoint may remove one.
     */
    @OneToMany(mappedBy = "revision", cascade = CascadeType.PERSIST)
    private List<AuditActivityEntity> activities = new ArrayList<>();
}
