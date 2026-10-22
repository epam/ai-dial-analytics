package com.epam.aidial.analytics.web.flight;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the Arrow Flight SQL endpoint configuration ({@code flight.*}), env-overridable
 * via {@code application.yml}; property descriptions live in {@code docs/configuration.md}.
 *
 * <p>Unlike the other structured-query governance (field validation, the function catalog, join
 * rules), which Flight inherits unchanged, the row ceiling is Flight's own: {@code maxRows} replaces
 * the shared 1000-row maximum for this transport, in both directions — it is the implicit bound on a
 * statement that stated no {@code LIMIT} and the largest explicit {@code LIMIT} accepted. One value,
 * because a configuration allowing an unbounded query more rows than an explicit {@code LIMIT} would
 * punish the client that asked for less.
 *
 * @param enabled                  whether the gRPC listener starts at all (default false)
 * @param port                     dedicated gRPC port; 0 binds an ephemeral port for tests
 * @param batchRows                flush a record batch at this many rows
 * @param batchMaxBytes            ... or at this much buffered Arrow memory, whichever comes first
 * @param maxConcurrentStreams     cap on concurrently executing doGet streams
 * @param requestMemoryLimitBytes  per-request child-allocator hard bound
 * @param maxRows                  the most rows this transport returns, implicit bound and explicit ceiling
 */
@ConfigurationProperties(prefix = "flight")
public record FlightProperties(
        boolean enabled,
        int port,
        int batchRows,
        long batchMaxBytes,
        int maxConcurrentStreams,
        long requestMemoryLimitBytes,
        int maxRows) {}
