package com.epam.aidial.analytics.service.query.repository;

import java.util.List;
import java.util.Map;

/**
 * Result of executing a {@link com.epam.aidial.analytics.service.query.model.StructuredQuery}:
 * the projected rows as ordered field-name → value maps (keyed by the resolved output column names —
 * an entry's {@code as}, else the PostgreSQL-shaped default from
 * {@link com.epam.aidial.analytics.service.query.model.OutputColumnNaming}), plus an optional
 * {@code totalCount} populated only when offset paging requests
 * {@code include_total}. Values are whatever the ClickHouse client returns for each column.
 *
 * <p>{@code columns} is the executed statement's projection — exactly the key set, in order, of every
 * row — and is carried separately so a page with no rows still names its columns (the CSV endpoints
 * emit a header from it).
 */
public record QueryResultPage(List<String> columns, List<Map<String, Object>> rows, Long totalCount) {}
