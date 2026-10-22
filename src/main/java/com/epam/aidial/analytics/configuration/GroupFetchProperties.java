package com.epam.aidial.analytics.configuration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for group-input assembly ({@code enrichment.group-fetch.*}). A group's members can carry
 * request/response bodies, so a group of a few hundred rows can be megabytes — which is why the response
 * is bounded by <b>bytes</b> as well as rows. A row cap alone would leave the actual failure mode (memory
 * and payload size, on both sides) unbounded. Values are env-driven via {@code application.yml}.
 *
 * @param defaultRows rows returned when the request omits {@code limit}
 * @param maxRows     hard upper bound on a requested {@code limit}
 * @param maxBytes    serialized-size ceiling for the returned members; whichever cap trims the group
 *                    first sets {@code truncated}
 */
@Validated
@ConfigurationProperties(prefix = "enrichment.group-fetch")
public record GroupFetchProperties(
        @Min(1) int defaultRows,
        @Min(1) int maxRows,
        @NotNull DataSize maxBytes) {
}
