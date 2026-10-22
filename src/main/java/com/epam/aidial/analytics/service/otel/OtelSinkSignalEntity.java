package com.epam.aidial.analytics.service.otel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * JPA entity for a row of {@code otel_sink_signal}: one signal a sink enabled, and the catalog table
 * backing it.
 *
 * <p>{@code tableName} is stored rather than re-derived on every read. The derivation
 * ({@link OtelTableNames}) is stable, but storing it is what lets a delete know exactly which tables the
 * sink owns even if the naming convention is ever versioned.
 */
@Entity
@Table(name = "otel_sink_signal")
@IdClass(OtelSinkSignalEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class OtelSinkSignalEntity {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sink_name", nullable = false)
    private OtelSinkEntity sink;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OtelSignal signal;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    public OtelSinkSignalEntity(OtelSignal signal, String tableName) {
        this.signal = signal;
        this.tableName = tableName;
    }

    /** Composite key mirroring the table's {@code (sink_name, signal)} primary key. */
    @lombok.EqualsAndHashCode
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Key implements Serializable {
        private String sink;
        private OtelSignal signal;
    }
}
