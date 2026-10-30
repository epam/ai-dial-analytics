package com.epam.aidial.analytics.configuration;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The bounds on the work one read spends rebuilding request bodies ({@code query.request-body-rebuild.*}). Values are
 * env-driven via {@code application.yml}; descriptions live in {@code docs/configuration.md}, behaviour in
 * {@code openspec/specs/data-sync/spec.md} ("A rebuilt request body is readable beside the stored one"). A row whose
 * chain is still incomplete when either bound is reached is returned {@code partial}, never as a failed read.
 *
 * @param maxRounds    the number of chain-lookup rounds one read may run
 * @param maxAncestors the number of distinct ancestor rows one read may resolve
 */
@Validated
@ConfigurationProperties(prefix = "query.request-body-rebuild")
public record RequestBodyRebuildProperties(
        @Min(1) @DefaultValue("8") int maxRounds,
        @Min(1) @DefaultValue("20000") int maxAncestors) {}
