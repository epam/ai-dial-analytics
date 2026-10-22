package com.epam.aidial.analytics.service.table;

/**
 * Service-layer TTL declaration: expire rows once {@code column} is older than {@code interval}
 * {@code unit}s. {@code column} is the physical {@code source_name} of a declared non-nullable
 * {@code date}/{@code timestamp} column of the table (validated by {@link TableTtlRules}); the
 * structured shape (never a free-form expression) keeps the rendered DDL injection-safe.
 */
public record TtlInput(String column, int interval, TtlUnit unit) {}
