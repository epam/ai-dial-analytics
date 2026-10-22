package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.EnrichmentAlignment;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The lookup index ADAS adds to an aligned enrichment column a pipeline groups by: a group trigger's
 * {@code group_by}, or a form aggregate's group key. Such a column is what a partition pre-selection looks up
 * ({@code PartitionPreselection}), and the index turns that lookup into an index read: on a multi-million-row
 * table, a small session's pre-selection reads a few percent of the rows with it and all of them without.
 *
 * <p><b>Added, never materialized.</b> {@code ALTER TABLE … ADD INDEX} covers the parts written or merged from then
 * on; no {@code MATERIALIZE INDEX} mutation rebuilds it over history. A part without it is read correctly, only
 * slower to pre-select, and a table relaid out by ADAS has the index in its DDL before the copy fills it, so its
 * whole history is covered anyway. Deleting the pipeline leaves the index: it is harmless, at about 200 KiB per
 * 6M rows.
 *
 * <p><b>After commit, best effort.</b> The DDL runs once the registration has committed, so a registration that
 * rolls back — the catalog import's validation pass always does — issues none, and a failure is logged rather than
 * failing a registration whose correctness does not depend on it.
 *
 * <p><b>Synchronous.</b> The {@code ALTER} runs on the request thread inside {@code afterCommit}, so the
 * registration's response waits for it. On a {@code Replicated} database the statement waits for the replicas to
 * apply it (up to the server's {@code distributed_ddl_task_timeout}), so a slow replica delays the response; the
 * registration itself is committed whether or not the index lands.
 *
 * <p><b>Coverage is judged by expression.</b> A column counts as indexed when any {@code bloom_filter} index is over
 * exactly that column, whatever its name: a {@code RENAME COLUMN} keeps an index's name ({@code idx_<old>}) while
 * pointing its expression at the new name. When {@code idx_<col>} is taken by such an index over another column,
 * the new one takes the first free {@code idx_<col>_<n>}.
 *
 * <p>Only form-mode group keys are read: an SQL-mode aggregate's grouping lives in its statement, which this does
 * not parse, so it gets the partition pre-selection but no index.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LookupIndexService {

    /** The {@code type} of a bloom-filter skip index in {@code system.data_skipping_indices}, whatever its rate. */
    private static final String BLOOM_FILTER = "bloom_filter";

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final PipelineRepository pipelines;
    private final PipelineSpecCodec specCodec;
    private final TableDdlRenderer ddlRenderer;
    private final ClickHouseDdlExecutor ddl;
    private final ClickHouseTableInspector inspector;

    /** Ensures, once the current transaction commits, the lookup indexes {@code pipeline}'s group keys need. */
    public void ensureAfterCommit(PipelineEntity pipeline) {
        final Map<String, Set<String>> required = required(pipeline, true);
        if (required.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            required.forEach(this::ensure);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                required.forEach(LookupIndexService.this::ensure);
            }
        });
    }

    /**
     * The physical columns of enrichment {@code table} that some pipeline groups by, whether or not the table is
     * aligned yet — what the aligned copy of a table being relaid out is created with.
     */
    public List<String> requiredColumns(String table) {
        final Set<String> wanted = new LinkedHashSet<>();
        for (final PipelineEntity pipeline : pipelines.findAll()) {
            wanted.addAll(required(pipeline, false).getOrDefault(table, Set.of()));
        }
        return new ArrayList<>(wanted);
    }

    /**
     * Adds each of {@code physicalColumns} a lookup index on {@code table} when it has none. Never throws: the
     * index is an optimisation.
     */
    public void ensure(String table, Set<String> physicalColumns) {
        try {
            final List<ClickHouseTableInspector.SkipIndex> present = inspector.skipIndexes(table);
            final Set<String> names = new HashSet<>();
            present.forEach(index -> names.add(index.name()));
            final List<String> commands = new ArrayList<>();
            for (final String column : physicalColumns) {
                if (present.stream().noneMatch(index -> BLOOM_FILTER.equals(index.type()) && index.covers(column))) {
                    commands.add(ddlRenderer.addLookupIndexCommand(column, freeName(column, names)));
                }
            }
            if (commands.isEmpty()) {
                return;
            }
            ddl.execute(ddlRenderer.renderAlter(table, commands));
            log.info("Added lookup index(es) on '{}' for column(s) {}: new and merged parts only, none materialized",
                    table, physicalColumns);
        } catch (RuntimeException ex) {
            log.warn("Could not add lookup index(es) on '{}' for column(s) {}; lookups on them stay unindexed: {}",
                    table, physicalColumns, ex.getMessage());
        }
    }

    /** {@code idx_<column>}, or the first {@code idx_<column>_<n>} not in {@code taken}; added to {@code taken}. */
    private static String freeName(String column, Set<String> taken) {
        String name = TableDdlRenderer.lookupIndexName(column);
        for (int n = 2; taken.contains(name); n++) {
            name = TableDdlRenderer.lookupIndexName(column) + "_" + n;
        }
        taken.add(name);
        return name;
    }

    /** The (aligned, when {@code alignedOnly}) enrichment columns {@code pipeline} groups by, as physical names by table. */
    private Map<String, Set<String>> required(PipelineEntity pipeline, boolean alignedOnly) {
        final List<String> groupKeys = new ArrayList<>();
        if (pipeline.getKind() == PipelineKind.AGGREGATE) {
            try {
                final AggregateSpec spec = specCodec.readAggregate(pipeline);
                if (spec != null && spec.getGroupBy() != null) {
                    spec.getGroupBy().stream().map(GroupKey::inputColumn).forEach(groupKeys::add);
                }
            } catch (RuntimeException ex) {
                // A draft or an SQL declaration: no form group keys to index.
                return Map.of();
            }
        } else if (pipeline.getTriggerGroupBy() != null) {
            groupKeys.add(pipeline.getTriggerGroupBy());
        }
        final String input = inputOf(pipeline);
        final Map<String, Set<String>> required = new LinkedHashMap<>();
        for (final String key : groupKeys) {
            resolve(input, key, alignedOnly).ifPresent(column ->
                    required.computeIfAbsent(column.table(), ignored -> new LinkedHashSet<>()).add(column.column()));
        }
        return required;
    }

    /** A physical column of a table. */
    private record PhysicalColumn(String table, String column) {
    }

    /** The physical column a flat {@code enrichment.column} name of an aligned enrichment of {@code input} names. */
    private Optional<PhysicalColumn> resolve(String input, String flatName, boolean alignedOnly) {
        if (input == null || StringUtils.isBlank(flatName) || !flatName.contains(".")) {
            return Optional.empty();
        }
        final String table = flatName.substring(0, flatName.indexOf('.'));
        final String exposed = flatName.substring(flatName.indexOf('.') + 1);
        final TableDefinitionEntity enrichment = definitions.findById(table).orElse(null);
        if (enrichment == null || enrichment.getType() != TableType.ENRICHMENT
                || enrichment.getStatus() != TableStatus.ACTIVE || !input.equals(enrichment.getSourceTable())
                || alignedOnly && EnrichmentAlignment.alignedColumn(enrichment, definitions.findById(input).orElse(null)) == null) {
            return Optional.empty();
        }
        return columns.findByTableNameOrderByOrdinal(table).stream()
                .filter(column -> column.getName().equals(exposed))
                .map(ColumnMappingEntity::getSourceName)
                .findFirst()
                .map(physical -> new PhysicalColumn(table, physical));
    }

    /** The table {@code pipeline} reads: its declared input, or its target's own source. */
    private String inputOf(PipelineEntity pipeline) {
        if (StringUtils.isNotBlank(pipeline.getInputTable())) {
            return pipeline.getInputTable();
        }
        return definitions.findById(pipeline.getTarget()).map(TableDefinitionEntity::getSourceTable).orElse(null);
    }
}
