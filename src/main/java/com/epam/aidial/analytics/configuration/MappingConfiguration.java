package com.epam.aidial.analytics.configuration;

import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.sync.mapping.ColumnMapping;
import com.epam.aidial.analytics.sync.mapping.DeriverRegistry;
import com.epam.aidial.analytics.sync.mapping.MappingConfig;
import com.epam.aidial.analytics.sync.mapping.MappingEngine;
import com.epam.aidial.analytics.sync.mapping.MappingFileLoader;
import com.epam.aidial.analytics.sync.mapping.SensitiveColumnExclusion;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import com.epam.aidial.analytics.sync.mapping.TransformRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads the configured ingest targets into an immutable {@code List<SyncTarget>} bean at startup. Each
 * target's mapping file is parsed into a {@link MappingConfig} and compiled into a {@link MappingEngine}.
 * A load failure (unreadable/invalid file, unknown source token, kind, transform, deriver, or match
 * operator) prevents the context from starting.
 *
 * <p>Targets come from {@code sync.targets}, which is validated non-empty at startup. Each engine is compiled under
 * {@code sync.inline-files-drop-truncated} and {@code sync.inline-files-max-size}, so its {@code inline-files} body
 * columns are rewritten (and counted) only when the repair is on or a size limit is set.
 *
 * <p>Under {@code sync.sensitive-columns-enabled=false} each mapping first loses the columns the catalog flags
 * {@code sensitive} on its table ({@link SensitiveColumnExclusion}), keeping its emit predicate, so the engine never
 * extracts them and the stored row holds their ClickHouse defaults. The catalog's Flyway migrations have run by then: the
 * repositories depend on the JPA {@code EntityManagerFactory}, which Spring Boot builds after them. Under the default
 * {@code true} the catalog is not read for this at all.
 */
@Configuration
public class MappingConfiguration {

    @Bean
    public List<SyncTarget> syncTargets(
            SyncProperties syncProperties,
            ResourceLoader resourceLoader,
            TransformRegistry transformRegistry,
            DeriverRegistry deriverRegistry,
            MeterRegistry meterRegistry,
            ObjectProvider<ColumnMappingRepository> columnMappings,
            ObjectProvider<TableDefinitionRepository> tableDefinitions) {
        MappingFileLoader loader = new MappingFileLoader(resourceLoader, transformRegistry, deriverRegistry);

        Map<String, Set<String>> excluded = syncProperties.sensitiveColumnsEnabled()
                ? Map.of()
                : SensitiveColumnExclusion.resolve(syncProperties.targets().stream().map(SyncProperties.Target::table).toList(),
                        columnMappings.getObject(), tableDefinitions.getObject());

        List<SyncTarget> targets = new ArrayList<>(syncProperties.targets().size());
        for (SyncProperties.Target target : syncProperties.targets()) {
            MappingConfig configured = loader.load(target.mappingFile());
            Set<String> sensitive = excluded.getOrDefault(target.table(), Set.of());
            Set<String> excludedColumns = configured.columns().stream().map(ColumnMapping::column)
                    .filter(sensitive::contains).collect(Collectors.toSet());
            MappingConfig mapping = SensitiveColumnExclusion.without(configured, excludedColumns);
            MappingEngine engine = new MappingEngine(mapping, transformRegistry, deriverRegistry,
                    syncProperties.inlineFilesPolicy(), target.table(), meterRegistry);
            targets.add(new SyncTarget(target.table(), mapping, engine, excludedColumns));
        }
        return List.copyOf(targets);
    }
}
