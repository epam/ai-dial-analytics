package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.configuration.TableLimitsProperties;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the {@link ImportTarget} snapshot the planner works from: the tables and pipelines on this environment
 * that the bundle names or depends on, in bundle shape, and the bundle pipeline names that were in use before.
 *
 * <p>The import endpoints need only {@code TABLE_CREATE}, so what an entry reports as {@code prev} follows the
 * export's visibility rules, never the full objects the planner compares: grants only with {@code TABLE_GRANT},
 * sensitive columns only with the clearance on that table, nothing for a table the caller may not
 * {@code TABLE_GET}, and no pipeline declaration without {@code REGISTRY_READ}.
 */
@Component
@RequiredArgsConstructor
class ImportTargetReader {

    /** Envers' {@code revtype} for a deletion. */
    private static final int REVTYPE_DELETE = 2;

    private final TableDefinitionRepository definitions;
    private final PipelineRepository pipelines;
    private final CatalogExportService exportService;
    private final PipelineMapper pipelineMapper;
    private final TableLimitsProperties limits;
    private final JdbcTemplate jdbc;
    private final SecurityModeResolver securityMode;
    private final SecurityClaimsExtractor claims;
    private final SensitiveColumnAccess sensitiveAccess;

    @Transactional(readOnly = true)
    public ImportTarget read(CatalogBundle bundle) {
        final Map<String, ImportTarget.Table> tables = new LinkedHashMap<>();
        for (final TableDefinitionEntity definition : definitions.findAllById(tableNames(bundle))) {
            final String name = definition.getName();
            final boolean visible = sensitiveAccess.mayGet(name);
            final boolean sensitiveVisible = sensitiveAccess.maySeeSensitive(name);
            if (definition.isSystem()) {
                tables.put(name, new ImportTarget.Table(name, definition.getType(), true, definition.getStatus(), null,
                        null));
            } else {
                final BundleTable full = exportService.bundleTable(definition);
                final BundleTable prev = visible
                        ? new BundleTable(full.table(), sensitiveVisible ? full.schema() : withoutSensitive(full.schema()))
                        : null;
                tables.put(name, new ImportTarget.Table(name, definition.getType(), false, definition.getStatus(), full,
                        prev));
            }
        }
        final Set<String> pipelineNames = new LinkedHashSet<>();
        final Set<String> targets = new LinkedHashSet<>();
        for (final BundlePipeline pipeline : nullSafe(bundle.pipelines())) {
            pipelineNames.add(pipeline.pipeline().name());
            targets.add(pipeline.pipeline().target());
        }
        final Map<String, BundlePipeline> bundled = new LinkedHashMap<>();
        for (final PipelineEntity pipeline : pipelines.findAllById(pipelineNames)) {
            bundled.put(pipeline.getName(), pipelineMapper.toBundle(pipeline));
        }
        final Map<String, String> byTarget = new HashMap<>();
        for (final PipelineEntity pipeline : pipelines.findByTargetIn(targets)) {
            byTarget.put(pipeline.getTarget(), pipeline.getName());
        }
        final boolean pipelinesVisible = !securityMode.isEnforced() || claims.hasPrivilege(Privilege.REGISTRY_READ);
        return new ImportTarget(tables, bundled, pipelinesVisible, byTarget, reusedNames(pipelineNames),
                (int) definitions.count(), limits.maxTables(), limits.maxColumnsPerTable());
    }

    /** Every table name the bundle names or that one of its objects depends on. */
    private static Set<String> tableNames(CatalogBundle bundle) {
        final Set<String> names = new LinkedHashSet<>();
        names.addAll(nullSafe(bundle.requiredSystemTables()));
        for (final BundleTable table : nullSafe(bundle.tables())) {
            names.add(table.table().name());
            if (table.table().sourceTable() != null) {
                names.add(table.table().sourceTable());
            }
        }
        for (final BundlePipeline pipeline : nullSafe(bundle.pipelines())) {
            names.addAll(CatalogImportPlanner.dependencies(pipeline.pipeline()));
            names.addAll(CatalogImportPlanner.sqlReadTables(pipeline.pipeline()));
        }
        return names;
    }

    /**
     * The names among {@code candidates} that a deleted pipeline used, counting only one that was enabled at some
     * revision: the Enrichment Runner and the materializer keep state only for a pipeline that ran, and an import's
     * own compensation deletes pipelines that never did.
     */
    private Set<String> reusedNames(Set<String> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        final String placeholders = String.join(",", Collections.nCopies(candidates.size(), "?"));
        final Object[] args = new Object[candidates.size() + 1];
        args[0] = REVTYPE_DELETE;
        int i = 1;
        for (final String name : candidates) {
            args[i++] = name;
        }
        return Set.copyOf(jdbc.queryForList("SELECT DISTINCT d.name FROM pipeline_aud d WHERE d.revtype = ? AND d.name IN ("
                + placeholders + ") AND EXISTS (SELECT 1 FROM pipeline_aud e WHERE e.name = d.name AND e.enabled)",
                String.class, args));
    }

    private static DefineSchemaRequest withoutSensitive(DefineSchemaRequest schema) {
        final List<ColumnRequest> visible = nullSafe(schema.columns()).stream()
                .filter(col -> !Boolean.TRUE.equals(col.sensitive()))
                .toList();
        return new DefineSchemaRequest(visible, schema.orderingKey(), schema.partitionBy(), schema.grainKey(),
                schema.cardinality(), schema.identityColumn(), schema.versionColumn(), schema.ttl(), schema.write());
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
