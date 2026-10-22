package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link IngestFlushOrder} the catalog answers: a target is an enrichment of another target when its
 * {@code table_definition} row says so ({@code type = ENRICHMENT}, {@code source_table} naming the other).
 * That is the same fact the query path builds the grain join from, so the ingest path and the read path
 * agree on which rows belong together without a second declaration in the mapping files.
 *
 * <p>Consulted once per run, when {@link BlobIngestor#newRunSink} builds the sink — not per row, and not
 * per blob. A poll cycle therefore costs one small catalog read, and a catalog change (a new data-sync
 * target registered as an enrichment) takes effect on the next run.
 */
@Component
@RequiredArgsConstructor
public class CatalogIngestFlushOrder implements IngestFlushOrder {

    private final TableDefinitionRepository definitions;

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> sourceOf(Collection<String> tables) {
        final Map<String, String> sourceOf = new LinkedHashMap<>();
        for (final String table : tables) {
            final Optional<TableDefinitionEntity> definition = definitions.findById(table);
            if (definition.isEmpty() || definition.get().getType() != TableType.ENRICHMENT) {
                continue;
            }
            final String source = definition.get().getSourceTable();
            if (source != null && tables.contains(source)) {
                sourceOf.put(table, source);
            }
        }
        return sourceOf;
    }
}
