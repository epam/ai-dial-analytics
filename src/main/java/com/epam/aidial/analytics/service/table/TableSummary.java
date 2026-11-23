package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.service.table.grant.EffectivePermissions;

/**
 * List-view aggregate of one catalog table: the definition plus the number of its exposed columns
 * (the same set {@link TableView#columns()} carries on the single-get path; system columns and the
 * hidden grain key are never counted because they are never stored in {@code column_mapping}), and the
 * caller's permissions, resolved inside the listing's transaction so an enrichment's source is not looked up
 * again per item.
 */
public record TableSummary(TableDefinitionEntity definition, int columnCount, EffectivePermissions permissions) {}
