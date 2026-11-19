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
 * Resolves, under a {@code sync.sensitive-columns} other than {@code *}, which columns each ingest target leaves out of its
 * rows: the physical columns ({@code column_mapping.source_name}) the catalog flags {@code sensitive} on the target's table
 * that no entry of the {@link SensitiveColumnPolicy} names there. The catalog flag is the only source of sensitivity, so a
 * column a later migration flags {@code sensitive} is left out on the next restart, unless an entry names it, without a
 * code or mapping change.
 *
 * <p>Read once at startup, in two batched lookups however many targets there are. Startup fails when an entry names no
 * column at all of an ingest target table (of its own table, for a qualified entry): the policy is an allowlist, so a
 * typo would otherwise silently drop a column the operator meant to keep. An entry that names only columns the catalog
 * does not flag {@code sensitive} is logged as a warning instead: ingest writes such a column anyway, and the flag can be
 * cleared through the table API, which must not stop the next startup. Startup also fails
 * when a column left out is one the ingest cannot do without: the deduplication key ({@code trace_id}, {@code core_span_id},
 * {@code request_time}), or the table's grain key, identity, ordering-key, partition, version or TTL column. Leaving one of
 * those out would drop every row, collapse rows that do not belong together, or land rows in the epoch partition where
 * retention drops them; a sensitive key column an entry names is written, so it is not refused. And startup fails for a
 * target table the catalog does not define: its sensitivity cannot be known, and writing it whole could store exactly
 * what the setting asks to keep out.
 */
@Slf4j
public final class SensitiveColumnExclusion {

    /** The deduplication-key columns every ingest target's row is checked for (see {@code BlobIngestor}). */
    static final Set<String> DEDUP_KEY_COLUMNS = Set.of("trace_id", "core_span_id", "request_time");

    private static final String SETTING = SensitiveColumnPolicy.SETTING;

    private SensitiveColumnExclusion() {
    }

    /**
     * The sensitive physical columns of each of {@code tables} that {@code policy} leaves out, in catalog order; a table
     * leaving none out maps to an empty set.
     *
     * @throws IllegalStateException when a table has no catalog definition, an entry names no column of a target,
     *                               or a column left out is one the ingest depends on
     */
    public static Map<String, Set<String>> resolve(Collection<String> tables, SensitiveColumnPolicy policy,
                                                   ColumnMappingRepository columns, TableDefinitionRepository definitions) {
        Set<String> distinct = new LinkedHashSet<>(tables);
        Map<String, TableDefinitionEntity> defined = definitions.findAllById(distinct).stream()
                .collect(Collectors.toMap(TableDefinitionEntity::getName, Function.identity()));
        Map<String, Set<String>> columnsByTable = new LinkedHashMap<>();
        Map<String, Set<String>> sensitiveByTable = new LinkedHashMap<>();
        for (ColumnMappingEntity column : columns.findByTableNameInOrderByTableNameAscOrdinalAsc(distinct)) {
            columnsByTable.computeIfAbsent(column.getTableName(), t -> new LinkedHashSet<>()).add(column.getSourceName());
            if (column.isSensitive()) {
                sensitiveByTable.computeIfAbsent(column.getTableName(), t -> new LinkedHashSet<>()).add(column.getSourceName());
            }
        }

        for (String table : distinct) {
            if (!defined.containsKey(table)) {
                throw new IllegalStateException(SETTING + " cannot tell which columns of ingest target table '" + table
                        + "' are sensitive: the catalog does not define it. Define the table in the catalog, or point the "
                        + "target at a table it defines");
            }
        }
        for (SensitiveColumnPolicy.Entry entry : policy.entries()) {
            if (entry.table() != null && !distinct.contains(entry.table())) {
                throw new IllegalStateException(SETTING + " entry '" + entry + "' names table '" + entry.table()
                        + "', which is not an ingest target table; remove it, or correct its spelling");
            }
            String where = entry.table() == null ? "any ingest target table" : "ingest target table '" + entry.table() + "'";
            if (!namesAny(entry, distinct, columnsByTable)) {
                throw new IllegalStateException(SETTING + " entry '" + entry + "' names no column of " + where
                        + "; remove it, or correct its spelling");
            }
            if (!namesAny(entry, distinct, sensitiveByTable)) {
                log.warn("{} entry '{}' names no column the catalog flags sensitive on {}; ingest writes that column anyway, "
                        + "so the entry has no effect", SETTING, entry, where);
            }
        }

        Map<String, Set<String>> excluded = new LinkedHashMap<>();
        for (String table : distinct) {
            Set<String> sensitive = sensitiveByTable.getOrDefault(table, Set.of());
            Set<String> written = new LinkedHashSet<>();
            Set<String> left = new LinkedHashSet<>();
            for (String column : sensitive) {
                (policy.writes(table, column) ? written : left).add(column);
            }
            Set<String> required = requiredColumns(defined.get(table));
            for (String column : left) {
                if (required.contains(column)) {
                    throw new IllegalStateException(SETTING + " cannot exclude column '" + column + "' of ingest target table '"
                            + table + "': the catalog flags it sensitive, but ingest needs it as a deduplication-key, grain, "
                            + "identity, ordering-key, partition, version or TTL column. Name it in " + SETTING);
                }
            }
            log.info("{}: ingest target table {} excludes sensitive columns {} and writes sensitive columns {}", SETTING, table, left, written);
            excluded.put(table, Collections.unmodifiableSet(left));
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

    private static boolean namesAny(SensitiveColumnPolicy.Entry entry, Set<String> tables, Map<String, Set<String>> columnsByTable) {
        return tables.stream().anyMatch(table ->
                columnsByTable.getOrDefault(table, Set.of()).stream().anyMatch(column -> entry.names(table, column)));
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
