package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How user-facing queries deduplicate ({@code query.*}). Values are env-driven via {@code application.yml};
 * descriptions live in {@code docs/configuration.md}.
 *
 * @param forceFinal the migration switch: while on, every user-facing read of a version-collapsing entity
 *     is read {@code FINAL} whatever the query says — the always-on deduplication the system shipped before
 *     queries could say {@code final} themselves. Off, a query is read final only where it says so (a SQL
 *     {@code FINAL}, a structured query's {@code final}, or an absent {@code final} over a collapsing source).
 *     The pipeline's own reads ignore it. See the structured-query spec, "Read result deduplication".
 */
@ConfigurationProperties(prefix = "query")
public record QueryFinalProperties(@DefaultValue("true") boolean forceFinal) {}
