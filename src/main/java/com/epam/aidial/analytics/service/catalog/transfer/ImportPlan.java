package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.web.dto.catalog.AccessImportEntry;
import com.epam.aidial.analytics.web.dto.catalog.EnvSpecificValue;
import com.epam.aidial.analytics.web.dto.catalog.ImportEntry;
import com.epam.aidial.analytics.web.dto.catalog.ImportPreview;

import java.util.List;
import java.util.stream.Stream;

/**
 * A planned import: every bundle object in import order (tables sources first, then pipelines), the bundle's access
 * entries when the import includes access (sorted by table, trimmed role and key), the environment-specific values and
 * the bundle-level problems.
 */
record ImportPlan(
        List<PlannedObject> systemTables,
        List<PlannedObject> tables,
        List<PlannedObject> pipelines,
        List<PlannedAccess> access,
        List<EnvSpecificValue> envSpecific,
        List<String> validationErrors) {

    Stream<PlannedObject> all() {
        return Stream.of(systemTables, tables, pipelines).flatMap(List::stream);
    }

    /** Whether anything refuses the import: a refused object or access entry, or a bundle-level problem. */
    boolean hasProblems() {
        return !validationErrors.isEmpty() || all().anyMatch(PlannedObject::failed) || access.stream().anyMatch(PlannedAccess::failed);
    }

    boolean hasConflicts() {
        return all().anyMatch(PlannedObject::isConflict);
    }

    ImportPreview toPreview() {
        return new ImportPreview(entries(systemTables), entries(tables), entries(pipelines), List.copyOf(envSpecific),
                List.copyOf(validationErrors), accessEntries());
    }

    List<AccessImportEntry> accessEntries() {
        return access.stream().map(PlannedAccess::toEntry).toList();
    }

    static List<ImportEntry> entries(List<PlannedObject> objects) {
        return objects.stream().map(PlannedObject::toEntry).toList();
    }
}
