package com.epam.aidial.analytics.service.table;

/**
 * List-view aggregate of one catalog table: the definition plus the number of its exposed columns
 * (the same set {@link TableView#columns()} carries on the single-get path; system columns and the
 * hidden grain key are never counted because they are never stored in {@code column_mapping}).
 */
public record TableSummary(TableDefinitionEntity definition, int columnCount) {}
