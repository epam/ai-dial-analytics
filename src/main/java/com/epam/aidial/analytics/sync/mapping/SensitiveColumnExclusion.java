package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves, under {@code sync.sensitive-columns-enabled=false}, which columns each ingest target leaves out of its rows:
 * the physical columns ({@code column_mapping.source_name}) the catalog flags {@code sensitive} on the target's table.
 * The catalog flag is the only source, so a column a later migration flags {@code sensitive} is covered on the next
 * restart without a code or mapping change.
 *
 * <p>Read once at startup, in two batched lookups however many targets there are. Startup fails when a sensitive column is
 * one the ingest cannot do without: the deduplication key ({@code trace_id}, {@code core_span_id}, {@code request_time}),
 * or the table's grain key, identity, ordering-key, partition, version or TTL column. Leaving one of those out would drop
 * every row, collapse rows that do not belong together, or land rows in the epoch partition where retention drops them.
 * Startup also fails for a target table the catalog does not define: its sensitivity cannot be known, and writing it
 * whole would store exactly what the setting asks to keep out.
 */
@Slf4j
public final class SensitiveColumnExclusion {

    /** The deduplication-key columns every ingest target's row is checked for (see {@code BlobIngestor}). */
    static final Set<String> DEDUP_KEY_COLUMNS = Set.of("trace_id", "core_span_id", "request_time");

    private SensitiveColumnExclusion() {
    }

    /**
     * The sensitive physical columns of each of {@code tables}, in catalog order; a table with none maps to an empty set.
     *
     * @throws IllegalStateException when a table has no catalog definition, or a sensitive column is one the ingest depends on
     */
    public static Map<String, Set<String>> resolve(Collection<String> tables, ColumnMappingRepository columns,
                                                   TableDefinitionRepository definitions) {
        Set<String> distinct = new LinkedHashSet<>(tables);
        Map<String, TableDefinitionEntity> defined = definitions.findAllById(distinct).stream()
                .collect(Collectors.toMap(TableDefinitionEntity::getName, Function.identity()));
        Map<String, Set<String>> sensitiveByTable = new LinkedHashMap<>();
        for (ColumnMappingEntity column : columns.findByTableNameInOrderByTableNameAscOrdinalAsc(distinct)) {
            if (column.isSensitive()) {
                sensitiveByTable.computeIfAbsent(column.getTableName(), t -> new LinkedHashSet<>()).add(column.getSourceName());
            }
        }

        Map<String, Set<String>> excluded = new LinkedHashMap<>();
        for (String table : distinct) {
            TableDefinitionEntity definition = defined.get(table);
            if (definition == null) {
                throw new IllegalStateException("sync.sensitive-columns-enabled=false cannot tell which columns of ingest target "
                        + "table '" + table + "' are sensitive: the catalog does not define it. Define the table in the catalog, "
                        + "or point the target at a table it defines");
            }
            Set<String> sensitive = sensitiveByTable.getOrDefault(table, Set.of());
            Set<String> required = requiredColumns(definition);
            for (String column : sensitive) {
                if (required.contains(column)) {
                    throw new IllegalStateException("sync.sensitive-columns-enabled=false cannot exclude column '" + column
                            + "' of ingest target table '" + table + "': the catalog flags it sensitive, but ingest needs it "
                            + "as a deduplication-key, grain, identity, ordering-key, partition, version or TTL column");
                }
            }
            log.info("sync.sensitive-columns-enabled=false: ingest target table {} excludes columns {}", table, sensitive);
            excluded.put(table, Collections.unmodifiableSet(sensitive));
        }
        return excluded;
    }

    /**
     * {@code mapping} without the columns named in {@code excluded}; its emit predicate is kept.
     */
    public static MappingConfig without(MappingConfig mapping, Set<String> excluded) {
        if (excluded.isEmpty()) {
            return mapping;
        }
        List<ColumnMapping> kept = mapping.columns().stream().filter(c -> !excluded.contains(c.column())).toList();
        return new MappingConfig(kept, mapping.matcher());
    }

    private static Set<String> requiredColumns(TableDefinitionEntity definition) {
        Set<String> required = new LinkedHashSet<>(DEDUP_KEY_COLUMNS);
        Stream.of(definition.getGrainKey(), definition.getIdentityColumn(), definition.getPartitionColumn(),
                definition.getVersionColumn(), definition.getTtlColumn()).filter(Objects::nonNull).forEach(required::add);
        if (definition.getOrderingKey() != null) {
            required.addAll(definition.getOrderingKey());
        }
        return required;
    }
}
