package com.epam.aidial.analytics.configuration;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the Class-1 set-based materialize path ({@code enrichment.materialize.*}). The
 * window's row limit is the only throttle on how much work one call does — a caller drains a backlog as
 * many small windows rather than one large one — so the maximum bounds how long a single
 * {@code INSERT … SELECT} can occupy a request. Values are env-driven via {@code application.yml}.
 *
 * @param defaultLimit window row count applied when the request omits {@code limit}
 * @param maxLimit     hard upper bound on a requested {@code limit}
 */
@Validated
@ConfigurationProperties(prefix = "enrichment.materialize")
public record MaterializeProperties(
        @Min(1) int defaultLimit,
        @Min(1) int maxLimit) {
}
