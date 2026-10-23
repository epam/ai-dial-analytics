package com.epam.aidial.analytics.configuration;

import com.epam.aidial.analytics.sync.mapping.DeriverRegistry;
import com.epam.aidial.analytics.sync.mapping.MappingConfig;
import com.epam.aidial.analytics.sync.mapping.MappingEngine;
import com.epam.aidial.analytics.sync.mapping.MappingFileLoader;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import com.epam.aidial.analytics.sync.mapping.TransformRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads the configured ingest targets into an immutable {@code List<SyncTarget>} bean at startup. Each
 * target's mapping file is parsed into a {@link MappingConfig} and compiled into a {@link MappingEngine}.
 * A load failure (unreadable/invalid file, unknown source token, kind, transform, deriver, or match
 * operator) prevents the context from starting.
 *
 * <p>Targets come from {@code sync.targets}, which is validated non-empty at startup. Each engine is compiled under
 * {@code sync.inline-files-drop-truncated} and {@code sync.inline-files-max-size}, so its {@code inline-files} body
 * columns are rewritten (and counted) only when the repair is on or a size limit is set.
 */
@Configuration
public class MappingConfiguration {

    @Bean
    public List<SyncTarget> syncTargets(
            SyncProperties syncProperties,
            ResourceLoader resourceLoader,
            TransformRegistry transformRegistry,
            DeriverRegistry deriverRegistry,
            MeterRegistry meterRegistry) {
        MappingFileLoader loader = new MappingFileLoader(resourceLoader, transformRegistry, deriverRegistry);

        List<SyncTarget> targets = new ArrayList<>(syncProperties.targets().size());
        for (SyncProperties.Target target : syncProperties.targets()) {
            MappingConfig mapping = loader.load(target.mappingFile());
            MappingEngine engine = new MappingEngine(mapping, transformRegistry, deriverRegistry,
                    syncProperties.inlineFilesPolicy(), target.table(), meterRegistry);
            targets.add(new SyncTarget(target.table(), mapping, engine));
        }
        return List.copyOf(targets);
    }
}
