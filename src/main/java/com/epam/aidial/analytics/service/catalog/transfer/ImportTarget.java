package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;

import java.util.Map;
import java.util.Set;

/**
 * What the import planner needs to know about the target environment, read up front so the planner itself
 * touches no repository. Only the objects the bundle names or depends on are read, plus the table count.
 *
 * @param tables                the target's tables the bundle names or depends on, system tables included, by name
 * @param pipelines             the target's pipelines the bundle names, in bundle shape, by name
 * @param pipelinesVisible      whether the caller may see a pipeline declaration ({@code REGISTRY_READ}), and so
 *                              whether an entry reports one as {@code prev}
 * @param pipelineByTarget      the name of the pipeline targeting each bundle pipeline's target that has one
 * @param reusedPipelineNames   bundle pipeline names {@code pipeline_aud} records as deleted after having been
 *                              enabled at some revision: only a pipeline that ran left runner state behind
 * @param tableCount            how many tables the target holds
 * @param maxTables             the catalog cap
 * @param maxColumnsPerTable    the per-table column cap
 */
record ImportTarget(
        Map<String, Table> tables,
        Map<String, BundlePipeline> pipelines,
        boolean pipelinesVisible,
        Map<String, String> pipelineByTarget,
        Set<String> reusedPipelineNames,
        int tableCount,
        int maxTables,
        int maxColumnsPerTable) {

    /**
     * One table on the target.
     *
     * @param bundle       the table in bundle shape, every column and its grants; null for a system table
     * @param prev         what an entry reports of it: the caller's view of {@code bundle} (sensitive columns only
     *                     with the clearance), or null for a system table or when the caller may not
     *                     {@code TABLE_GET} it
     */
    record Table(
            String name,
            TableType type,
            boolean system,
            TableStatus status,
            BundleTable bundle,
            BundleTable prev) {
    }
}
