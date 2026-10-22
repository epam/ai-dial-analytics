package com.epam.aidial.analytics.configuration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the governed incremental scan ({@code enrichment.scan.*}). Values are env-driven
 * via {@code application.yml}.
 *
 * @param safetyLag    rows newer than {@code now64(3) - safetyLag} are excluded so a scan never
 *                     enters the still-mutating recent window
 * @param defaultLimit page size applied when the request omits {@code limit}
 * @param maxLimit     hard upper bound on a requested {@code limit}
 */
@Validated
@ConfigurationProperties(prefix = "enrichment.scan")
public record ScanProperties(
        @NotNull Duration safetyLag,
        @Min(1) int defaultLimit,
        @Min(1) int maxLimit) {
}
