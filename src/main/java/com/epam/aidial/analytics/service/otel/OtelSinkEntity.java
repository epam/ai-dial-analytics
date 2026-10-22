package com.epam.aidial.analytics.service.otel;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA entity for a row of the {@code otel_sink} table: a named OTLP landing zone.
 *
 * <p>The signal rows are owned by the sink ({@code cascade = ALL}, {@code orphanRemoval}) because a
 * signal has no meaning without one, and deleting through the collection keeps the removal inside the
 * persistence context rather than relying on the database cascade — the same discipline the audited
 * catalog entities need and a habit worth keeping uniform.
 */
@Entity
@Table(name = "otel_sink")
@Getter
@Setter
@NoArgsConstructor
public class OtelSinkEntity {

    @Id
    private String name;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "sink", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("signal ASC")
    private List<OtelSinkSignalEntity> signals = new ArrayList<>();

    /** Attaches {@code signal} to this sink, keeping both ends of the association consistent. */
    public void addSignal(OtelSinkSignalEntity signal) {
        signal.setSink(this);
        signals.add(signal);
    }
}
