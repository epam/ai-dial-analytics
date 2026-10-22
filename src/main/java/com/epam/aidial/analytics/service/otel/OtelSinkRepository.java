package com.epam.aidial.analytics.service.otel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository over {@code otel_sink}, keyed by the sink name. */
public interface OtelSinkRepository extends JpaRepository<OtelSinkEntity, String> {

    /** Every sink ordered by name — the listing order the API contract promises. */
    List<OtelSinkEntity> findAllByOrderByNameAsc();
}
