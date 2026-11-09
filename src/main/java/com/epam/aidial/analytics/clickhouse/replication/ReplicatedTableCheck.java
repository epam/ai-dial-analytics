package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.clickhouse.ClickHouseMigrationRunner;
import com.epam.aidial.analytics.clickhouse.ClickHouseWriterClient;
import com.epam.aidial.analytics.clickhouse.ReplicatedDdl;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The last step of the storage phase: every table the service owns is a {@code Replicated*MergeTree} with insert
 * deduplication off, or startup fails naming each offender. The owned tables are those the ClickHouse migrations
 * create, the Flyway history table, and every {@code ACTIVE} catalog table; a table the startup conversion gave up on
 * is checked as well, whoever owns it. A table the catalog lists but ClickHouse
 * does not have is the existing reconciliation's concern, not this check's. A table left over from an interrupted
 * conversion also fails the check, so no instance serves writes until a start with migrations enabled resumes it.
 */
@Slf4j
@Component
public class ReplicatedTableCheck {

    private static final String MIGRATIONS = "classpath*:db/migration/clickhouse/*.sql";

    private final ClickHouseWriterClient writer;
    private final ClickHouseProperties connection;
    private final TableDefinitionRepository definitions;

    public ReplicatedTableCheck(ClickHouseWriterClient writer, ClickHouseProperties connection,
            TableDefinitionRepository definitions) {
        this.writer = writer;
        this.connection = connection;
        this.definitions = definitions;
    }

    /**
     * Checks every owned table.
     *
     * @param reasons why the conversion left a table plain, by table, to name in the failure
     * @throws IllegalStateException naming each table that is not replicated or still deduplicates
     */
    public void check(Map<String, String> reasons) {
        final Set<String> owned = owned();
        // A table the conversion gave up on fails too, owned or not (a relayout copy, a non-ACTIVE catalog table): left
        // plain, it would take writes now and be converted by a later start while other instances write to it.
        owned.addAll(reasons.keySet());
        final Map<String, GenericRecord> live = new LinkedHashMap<>();
        for (final GenericRecord row : writer.primaryClient().queryAll(
                "SELECT name, engine, engine_full FROM system.tables WHERE database = {db:String}",
                Map.of("db", connection.sessionDatabase()))) {
            live.put(row.getString("name"), row);
        }
        final List<String> offenders = new ArrayList<>();
        // A conversion twin means a conversion was cut short. The service must not serve writes past one: the next
        // start with migrations enabled resumes it from the row counts, which only holds while nothing has written.
        for (final String table : live.keySet()) {
            if (table.startsWith(ReplicatedTableConversion.PREFIX)) {
                offenders.add("'" + table + "' is left over from an interrupted conversion of '"
                        + table.substring(ReplicatedTableConversion.PREFIX.length())
                        + "'; a start with clickhouse.migrations.enabled resumes it");
            }
        }
        for (final String table : owned) {
            final GenericRecord row = live.get(table);
            if (row == null) {
                continue;
            }
            final String engine = row.getString("engine");
            if (!ReplicatedDdl.isReplicated(engine)) {
                offenders.add("'" + table + "' uses " + engine + (reasons.containsKey(table)
                        ? " (" + reasons.get(table) + ")" : ""));
            } else if (!ReplicatedDdl.disablesDeduplication(row.getString("engine_full"))) {
                offenders.add("'" + table + "' does not set " + ReplicatedDdl.NO_INSERT_DEDUPLICATION);
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("ClickHouse tables of database '" + connection.sessionDatabase()
                    + "' are not replicated with insert deduplication off: " + String.join("; ", offenders)
                    + ". With clickhouse.migrations.enabled the service converts them at startup; otherwise convert "
                    + "them as docs/clickhouse-replication.md describes");
        }
        log.info("ClickHouse storage check passed: {} owned table(s) are replicated with insert deduplication off",
                owned.stream().filter(live::containsKey).count());
    }

    private Set<String> owned() {
        final Set<String> owned = new LinkedHashSet<>(migrationTables());
        owned.add(ClickHouseMigrationRunner.HISTORY_TABLE);
        for (final TableType type : TableType.values()) {
            definitions.findByTypeAndStatus(type, TableStatus.ACTIVE).stream()
                    .map(TableDefinitionEntity::getName)
                    .forEach(owned::add);
        }
        return owned;
    }

    /** The tables the shipped ClickHouse migrations create. */
    static Set<String> migrationTables() {
        final Set<String> tables = new LinkedHashSet<>();
        try {
            for (final Resource resource : new PathMatchingResourcePatternResolver().getResources(MIGRATIONS)) {
                try (InputStream in = resource.getInputStream()) {
                    final String script = ReplicatedDdl.stripLineComments(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    for (final String statement : script.split(";")) {
                        ReplicatedDdl.createdTable(statement).map(ReplicatedTableCheck::unqualified).ifPresent(tables::add);
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the ClickHouse migrations", ex);
        }
        return tables;
    }

    private static String unqualified(String name) {
        final String last = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return last.replace("`", "").replace("\"", "");
    }
}
