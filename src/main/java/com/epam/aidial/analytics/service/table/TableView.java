package com.epam.aidial.analytics.service.table;

import java.util.List;

/**
 * A table definition together with its current exposed {@code columns} (ordered) — the read aggregate
 * returned by create, get, and {@code PATCH} so the API can surface the full definition (the list
 * endpoint returns the lean definition without columns). An enrichment's grain is read off the
 * definition itself.
 */
public record TableView(TableDefinitionEntity definition, List<ColumnMappingEntity> columns) {}
