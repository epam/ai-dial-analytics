package com.epam.aidial.analytics.service.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity for a row of the {@code instance_setting} table: one instance-wide setting, keyed by its
 * {@link InstanceSettingName#wireName() name}, holding the setting's body as one JSON document.
 *
 * <p>The body is held as text and converted by {@link SessionsSourceSettingService} with the application's
 * mapper, like every JSON payload in this catalog, because Hibernate's JSON mapper is Jackson 2. Being a
 * {@code String}, it dirty-checks by value, so loading the row records no phantom {@code Update}.
 *
 * <p>There is no version column: a setting is written whole, last writer wins (see the
 * {@code instance-settings} capability).
 */
@Entity
@Audited
@Table(name = "instance_setting")
@Getter
@Setter
@NoArgsConstructor
public class InstanceSettingEntity {

    /** The setting name, as it appears in the URL ({@code sessions-source}). */
    @Id
    private String name;

    /** The setting's body as JSON text. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The writer's principal name; null in {@code none} mode. */
    @Column(name = "updated_by")
    private String updatedBy;
}
