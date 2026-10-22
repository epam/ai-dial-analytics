package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.exception.CatalogExportInvalidException;
import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichSpecValidator;
import com.epam.aidial.analytics.service.otel.OtelSignal;
import com.epam.aidial.analytics.service.otel.OtelSinkEntity;
import com.epam.aidial.analytics.service.otel.OtelSinkRepository;
import com.epam.aidial.analytics.service.otel.OtelTableNames;
import com.epam.aidial.analytics.service.pipeline.AggregateSpecValidator;
import com.epam.aidial.analytics.service.pipeline.PipelineDeclaration;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.catalog.ExportComponent;
import com.epam.aidial.analytics.web.dto.catalog.ExportEntry;
import com.epam.aidial.analytics.web.dto.catalog.ExportPreview;
import com.epam.aidial.analytics.web.dto.catalog.ExportRequest;
import com.epam.aidial.analytics.web.dto.catalog.SkippedObject;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import com.epam.aidial.analytics.web.mapper.table.TableMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds catalog bundles: the selected user tables and pipelines in request shape, plus the dependency
 * closure they need and the names of the system tables they reach.
 *
 * <p>Reads the raw entities rather than the query model, because a bundle needs every stored column
 * (provenance included) where the query model serves the caller's view.
 *
 * <h2>Closure</h2>
 *
 * <p>Computed in both directions: a table pulls in its {@code source_table} and the pipeline that targets it;
 * a pipeline pulls in its input, its target and every enrichment it reads. Enrichment reads appear only as
 * {@code "<enrichment>.<column>"} tokens inside filters, measures and inputs, so they are resolved through the
 * validators' own {@code referencedEnrichmentsOrThrow} rather than from the structured fields. A system table is
 * never expanded: it is written by name as a required system table, and selecting one explicitly is a 422.
 *
 * <h2>What an export cannot carry</h2>
 *
 * <p>OTLP landing tables belong to their sink, PENDING and FAILED tables have no schema, and a pipeline whose
 * declaration no longer resolves has an unknown closure. With an explicit selection, reaching any of them is a
 * 422. Exporting everything leaves them out instead, together with every object that depends on one, and lists
 * them under {@code skipped}.
 */
@Service
@RequiredArgsConstructor
public class CatalogExportService {

    private static final String SELECTED = "selected";

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final PipelineRepository pipelines;
    private final OtelSinkRepository otelSinks;
    private final EnrichSpecValidator enrichValidator;
    private final AggregateSpecValidator aggregateValidator;
    private final TableMapper tableMapper;
    private final PipelineMapper pipelineMapper;
    private final SensitiveColumnAccess sensitiveAccess;
    private final PlatformTransactionManager transactionManager;

    /** The bundle for {@code request}. Writes nothing. */
    @Transactional(readOnly = true)
    public CatalogBundle export(ExportRequest request) {
        final Resolution resolution = resolve(request);
        return new CatalogBundle(
                CatalogBundle.FORMAT,
                CatalogBundle.FORMAT_VERSION,
                Instant.now(),
                resolution.skipped(),
                List.copyOf(resolution.systemTables().keySet()),
                resolution.tables().keySet().stream()
                        .map(name -> bundleTable(resolution.definition(name), resolution.columns(name)))
                        .toList(),
                resolution.pipelines().keySet().stream()
                        .map(name -> pipelineMapper.toBundle(resolution.pipeline(name)))
                        .toList());
    }

    /** What {@link #export} would write for {@code request}, with the reason each object is included. */
    @Transactional(readOnly = true)
    public ExportPreview preview(ExportRequest request) {
        final Resolution resolution = resolve(request);
        final List<ExportEntry> objects = new ArrayList<>();
        resolution.tables().forEach((name, reason) -> objects.add(new ExportEntry(ExportComponent.Type.TABLE, name,
                resolution.definition(name).getDescription(), reason)));
        resolution.pipelines().forEach((name, reason) -> objects.add(
                new ExportEntry(ExportComponent.Type.PIPELINE, name, null, reason)));
        final List<ExportEntry> systemTables = new ArrayList<>();
        resolution.systemTables().forEach((name, reason) -> systemTables.add(new ExportEntry(ExportComponent.Type.TABLE,
                name, resolution.definition(name).getDescription(), reason)));
        return new ExportPreview(objects, systemTables, resolution.skipped());
    }

    /**
     * A table in bundle shape, whatever the caller may see: every stored column and, when {@code withAccess},
     * its grants. For import's {@code prev}, which describes the target's own object to the importing caller.
     */
    @Transactional(readOnly = true)
    public BundleTable bundleTable(TableDefinitionEntity definition) {
        return bundleTable(definition, columns.findByTableNameOrderByOrdinal(definition.getName()));
    }

    private BundleTable bundleTable(TableDefinitionEntity definition, List<ColumnMappingEntity> mappings) {
        return new BundleTable(
                tableMapper.toCreateRequest(definition),
                tableMapper.toDefineSchemaRequest(definition, mappings));
    }

    /** The landing table names of every OTLP sink, whichever signals it actually declares. */
    @Transactional(readOnly = true)
    public Set<String> otlpTableNames() {
        final Set<String> names = new HashSet<>();
        for (final OtelSinkEntity sink : otelSinks.findAll()) {
            for (final OtelSignal signal : OtelSignal.values()) {
                names.add(OtelTableNames.tableName(sink.getName(), signal));
            }
        }
        return names;
    }

    /**
     * The enrichments a stored pipeline reads through its declaration's qualified tokens; throws when the
     * declaration no longer resolves, since "unreadable" and "reads nothing" must not look alike here.
     *
     * <p>The aggregate resolver is {@code @Transactional}, so a refusal escaping it would mark the export's own
     * transaction rollback-only and fail its commit even where the walk tolerates the refusal. It runs in a
     * transaction of its own instead.
     */
    Set<String> referencedEnrichments(PipelineEntity pipeline) {
        final PipelineDeclaration declaration = PipelineDeclaration.of(pipeline);
        if (pipeline.getKind() == PipelineKind.ENRICH) {
            return enrichValidator.referencedEnrichmentsOrThrow(declaration);
        }
        final TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.setReadOnly(true);
        return isolated.execute(status -> aggregateValidator.referencedEnrichmentsOrThrow(declaration));
    }

    // --- resolution ----------------------------------------------------------------------------------

    /**
     * The objects a request resolves to, each with the reason it is included: tables in dependency order
     * (sources, then enrichments), pipelines by name, and the system tables reached, by name.
     */
    private Resolution resolve(ExportRequest request) {
        final boolean everything = request == null || request.components() == null || request.components().isEmpty();
        final Walk walk = new Walk(otlpTableNames(), pipelines.findAll());
        if (everything) {
            definitions.findAll().stream()
                    .filter(definition -> !definition.isSystem())
                    .sorted(Comparator.comparing(TableDefinitionEntity::getName))
                    .forEach(definition -> walk.table(definition.getName(), SELECTED));
            walk.allPipelines.keySet().stream()
                    .sorted()
                    .forEach(name -> walk.pipeline(name, SELECTED));
        } else {
            for (final ExportComponent component : request.components()) {
                if (component.type() == ExportComponent.Type.TABLE) {
                    final TableDefinitionEntity definition = definitions.findById(component.name())
                            .filter(found -> sensitiveAccess.mayGet(found.getName()))
                            .orElseThrow(() -> new TableNotFoundException("table '" + component.name() + "' not found"));
                    if (definition.isSystem()) {
                        throw new CatalogExportInvalidException("table '" + definition.getName() + "' is a system "
                                + "table; system tables are referenced by the bundle, never exported; select the tables "
                                + "or pipelines that use it");
                    }
                    walk.table(definition.getName(), SELECTED);
                } else {
                    walk.pipeline(component.name(), SELECTED);
                }
            }
        }
        walk.run();
        return everything ? walk.withoutUnexportable() : walk.requireExportable();
    }

    /** The closure walk over one request. */
    private final class Walk {

        private final Set<String> otlp;
        private final Map<String, PipelineEntity> allPipelines;
        private final Map<String, PipelineEntity> pipelinesByTarget;
        private final Map<String, TableDefinitionEntity> loadedTables = new LinkedHashMap<>();
        private final Map<String, String> tables = new LinkedHashMap<>();
        private final Map<String, String> systemTables = new LinkedHashMap<>();
        private final Map<String, String> pipelineReasons = new LinkedHashMap<>();
        /** For each object, the tables (by name) it cannot be imported without. */
        private final Map<String, Set<String>> tableNeeds = new LinkedHashMap<>();
        private final Map<String, Set<String>> pipelineNeeds = new LinkedHashMap<>();
        /** Pipelines whose enrichment reads could not be resolved, with the resolver's refusal. */
        private final Map<String, String> unresolvable = new LinkedHashMap<>();
        private final List<Runnable> pending = new ArrayList<>();
        private Map<String, List<ColumnMappingEntity>> mappings = Map.of();

        private Walk(Set<String> otlp, List<PipelineEntity> all) {
            this.otlp = otlp;
            this.allPipelines = all.stream().collect(Collectors.toMap(PipelineEntity::getName, Function.identity()));
            this.pipelinesByTarget = all.stream()
                    .filter(pipeline -> pipeline.getTarget() != null)
                    .collect(Collectors.toMap(PipelineEntity::getTarget, Function.identity(), (first, second) -> first));
        }

        private void table(String name, String reason) {
            if (tables.containsKey(name) || systemTables.containsKey(name)) {
                return;
            }
            final TableDefinitionEntity definition = load(name, reason);
            if (definition.isSystem()) {
                systemTables.put(name, reason);
                return;
            }
            tables.put(name, reason);
            pending.add(() -> expand(definition));
        }

        private void pipeline(String name, String reason) {
            if (pipelineReasons.containsKey(name)) {
                return;
            }
            final PipelineEntity pipeline = allPipelines.get(name);
            if (pipeline == null) {
                throw new PipelineNotFoundException("pipeline '" + name + "' not found");
            }
            pipelineReasons.put(name, reason);
            pending.add(() -> expand(pipeline));
        }

        private void run() {
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
            }
        }

        private void expand(TableDefinitionEntity definition) {
            final Set<String> needs = new LinkedHashSet<>();
            if (definition.getType() == TableType.ENRICHMENT && definition.getSourceTable() != null) {
                needs.add(definition.getSourceTable());
                table(definition.getSourceTable(), "source_table of " + definition.getName());
            }
            tableNeeds.put(definition.getName(), needs);
            final PipelineEntity writer = pipelinesByTarget.get(definition.getName());
            if (writer != null) {
                pipeline(writer.getName(), "targets " + definition.getName());
            }
        }

        private void expand(PipelineEntity pipeline) {
            final Set<String> needs = new LinkedHashSet<>();
            if (pipeline.getInputTable() != null) {
                needs.add(pipeline.getInputTable());
                table(pipeline.getInputTable(), "input of " + pipeline.getName());
            }
            needs.add(pipeline.getTarget());
            table(pipeline.getTarget(), "target of " + pipeline.getName());
            Set<String> enrichments;
            try {
                enrichments = referencedEnrichments(pipeline);
            } catch (RuntimeException ex) {
                unresolvable.put(pipeline.getName(), String.valueOf(ex.getMessage()));
                enrichments = Set.of();
            }
            for (final String enrichment : enrichments) {
                needs.add(enrichment);
                table(enrichment, "read by " + pipeline.getName());
            }
            pipelineNeeds.put(pipeline.getName(), needs);
        }

        private TableDefinitionEntity load(String name, String reason) {
            return loadedTables.computeIfAbsent(name, key -> definitions.findById(key)
                    .orElseThrow(() -> new TableNotFoundException("table '" + key + "' (" + reason + ") not found")));
        }

        /** Why a reached table cannot travel, or null when it can. */
        private Skip unexportable(String table) {
            if (otlp.contains(table)) {
                return Skip.OTLP;
            }
            final TableStatus status = loadedTables.get(table).getStatus();
            if (status == TableStatus.PENDING) {
                return Skip.PENDING;
            }
            return status == TableStatus.FAILED ? Skip.FAILED : null;
        }

        /** An explicit selection: anything it reaches that cannot travel, or cannot be seen, refuses it. */
        private Resolution requireExportable() {
            for (final Map.Entry<String, String> entry : allTables().entrySet()) {
                final String name = entry.getKey();
                if (!sensitiveAccess.mayGet(name)) {
                    throw new TableNotFoundException("table '" + name + "' (" + entry.getValue() + ") not found");
                }
                final Skip why = unexportable(name);
                if (why != null) {
                    throw new CatalogExportInvalidException("table '" + name + "' (" + entry.getValue() + ") "
                            + why.message + ", so the selection cannot be exported; select objects that do not "
                            + "depend on it");
                }
            }
            if (!unresolvable.isEmpty()) {
                final Map.Entry<String, String> entry = unresolvable.entrySet().iterator().next();
                throw new CatalogExportInvalidException("pipeline '" + entry.getKey() + "' ("
                        + pipelineReasons.get(entry.getKey()) + ") " + Skip.UNRESOLVABLE.message
                        + ", so the selection cannot be exported; repair or delete that pipeline, then retry: "
                        + entry.getValue());
            }
            loadColumns();
            requireSensitiveVisible();
            return resolution(List.of());
        }

        /**
         * Exporting everything: an object that cannot travel is left out and listed, with everything that needs
         * it; one the caller cannot see is left out silently, with everything that needs it.
         */
        private Resolution withoutUnexportable() {
            final List<SkippedObject> skipped = new ArrayList<>();
            final Set<String> hidden = new HashSet<>();
            final Set<String> dropped = new HashSet<>();
            for (final String name : allTables().keySet()) {
                if (!sensitiveAccess.mayGet(name)) {
                    hidden.add(name);
                    dropped.add(name);
                    continue;
                }
                final Skip why = unexportable(name);
                if (why != null) {
                    dropped.add(name);
                    skipped.add(new SkippedObject(ExportComponent.Type.TABLE, name, why.code));
                }
            }
            final Set<String> droppedPipelines = new HashSet<>();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (final String name : tables.keySet()) {
                    if (!dropped.contains(name) && dependsOn(tableNeeds.get(name), dropped)) {
                        dropped.add(name);
                        changed = true;
                        if (!dependsOn(tableNeeds.get(name), hidden)) {
                            skipped.add(new SkippedObject(ExportComponent.Type.TABLE, name, Skip.DEPENDENCY.code));
                        } else {
                            hidden.add(name);
                        }
                    }
                }
                for (final String name : pipelineReasons.keySet()) {
                    if (!droppedPipelines.contains(name) && dependsOn(pipelineNeeds.get(name), dropped)) {
                        droppedPipelines.add(name);
                        changed = true;
                        if (!dependsOn(pipelineNeeds.get(name), hidden)) {
                            final Skip why = otlp.stream().anyMatch(pipelineNeeds.get(name)::contains)
                                    ? Skip.OTLP : Skip.DEPENDENCY;
                            skipped.add(new SkippedObject(ExportComponent.Type.PIPELINE, name, why.code));
                        }
                    }
                }
            }
            // No object needs a pipeline, so leaving an unresolvable one out drops nothing else.
            for (final String name : unresolvable.keySet()) {
                if (droppedPipelines.add(name)) {
                    skipped.add(new SkippedObject(ExportComponent.Type.PIPELINE, name, Skip.UNRESOLVABLE.code));
                }
            }
            tables.keySet().removeAll(dropped);
            systemTables.keySet().removeAll(dropped);
            pipelineReasons.keySet().removeAll(droppedPipelines);
            loadColumns();
            requireSensitiveVisible();
            return resolution(List.copyOf(skipped));
        }

        private boolean dependsOn(Set<String> needs, Set<String> names) {
            return needs != null && needs.stream().anyMatch(names::contains);
        }

        /** Every bundled table's columns, in one query. A required system table travels by name only. */
        private void loadColumns() {
            final Set<String> names = tables.keySet();
            mappings = names.isEmpty() ? Map.of() : columns.findByTableNameInOrderByTableNameAscOrdinalAsc(names).stream()
                    .collect(Collectors.groupingBy(ColumnMappingEntity::getTableName));
        }

        /** Columns are never dropped silently: a bundle table whose sensitive column the caller cannot see is a 403. */
        private void requireSensitiveVisible() {
            for (final String name : tables.keySet()) {
                if (sensitiveAccess.maySeeSensitive(name)) {
                    continue;
                }
                if (mappings.getOrDefault(name, List.of()).stream().anyMatch(ColumnMappingEntity::isSensitive)) {
                    throw new SensitiveColumnNotEntitledException("table '" + name + "' has sensitive columns this caller "
                            + "is not cleared for; a bundle carries every column, so it cannot be exported by this caller");
                }
            }
        }

        private Map<String, String> allTables() {
            final Map<String, String> all = new LinkedHashMap<>(tables);
            all.putAll(systemTables);
            return all;
        }

        private Resolution resolution(List<SkippedObject> skipped) {
            final Map<String, String> ordered = new LinkedHashMap<>();
            tables.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, String>>comparingInt(
                                    entry -> loadedTables.get(entry.getKey()).getType() == TableType.SOURCE ? 0 : 1)
                            .thenComparing(Map.Entry::getKey))
                    .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
            return new Resolution(ordered, sortedByName(systemTables), sortedByName(pipelineReasons), skipped,
                    loadedTables, allPipelines, mappings);
        }

        private static Map<String, String> sortedByName(Map<String, String> reasons) {
            final Map<String, String> sorted = new LinkedHashMap<>();
            reasons.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            return sorted;
        }
    }

    /** Why an object is left out of an export-everything: its {@link SkippedObject#reason()} and, for a refusal, why. */
    private enum Skip {
        OTLP("otlp", "is an OTLP landing table"),
        PENDING("pending", "is PENDING and has no schema yet"),
        FAILED("failed", "is FAILED and has no materialized schema"),
        UNRESOLVABLE("unresolvable", "cannot be resolved, so what it reads is unknown"),
        DEPENDENCY("dependency_skipped", "needs an object that is left out");

        private final String code;
        private final String message;

        Skip(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /** A resolved request: names in bundle order, each with its reason. */
    private record Resolution(
            Map<String, String> tables,
            Map<String, String> systemTables,
            Map<String, String> pipelines,
            List<SkippedObject> skipped,
            Map<String, TableDefinitionEntity> loadedTables,
            Map<String, PipelineEntity> loadedPipelines,
            Map<String, List<ColumnMappingEntity>> mappings) {

        TableDefinitionEntity definition(String name) {
            return loadedTables.get(name);
        }

        PipelineEntity pipeline(String name) {
            return loadedPipelines.get(name);
        }

        List<ColumnMappingEntity> columns(String name) {
            return mappings.getOrDefault(name, List.of());
        }
    }
}
